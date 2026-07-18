package de.soapuiweb.service;

import com.eviware.soapui.impl.wsdl.WsdlProject;
import de.soapuiweb.storage.ProjectMeta;
import de.soapuiweb.storage.ProjectStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProjectService implements SmartLifecycle {

    private static final Logger log = LogManager.getLogger(ProjectService.class);

    private final ProjectStore store;
    private final Map<String, ProjectHandle> handles = new ConcurrentHashMap<>();
    private volatile boolean running;

    public ProjectService(ProjectStore store) {
        this.store = store;
    }

    /** Projekte werden beim App-Start geladen (Spezifikation 2.2) — nach der Engine. */
    @Override
    public void start() {
        for (String projectId : store.listProjectIds()) {
            try {
                loadHandle(projectId);
            } catch (Exception e) {
                // Absturz während save(): Backup der letzten guten Version wiederherstellen
                java.nio.file.Path backup = backupFile(projectId);
                if (java.nio.file.Files.exists(backup)) {
                    try {
                        java.nio.file.Files.move(backup, store.projectFile(projectId),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        loadHandle(projectId);
                        log.warn("Projekt {} aus Backup wiederhergestellt (unterbrochener Speichervorgang)",
                                projectId);
                        continue;
                    } catch (Exception restoreFailure) {
                        log.error("Projekt {} auch aus Backup nicht ladbar: {}",
                                projectId, restoreFailure.getMessage());
                        continue;
                    }
                }
                log.error("Projekt {} konnte nicht geladen werden und wird übersprungen: {}",
                        projectId, e.getMessage());
            }
        }
        running = true;
        log.info("{} Projekt(e) geladen", handles.size());
    }

    private void loadHandle(String projectId) throws Exception {
        WsdlProject project = new WsdlProject(store.projectFile(projectId).toString());
        handles.put(projectId, new ProjectHandle(projectId, project, store.readMeta(projectId)));
    }

    private java.nio.file.Path backupFile(String projectId) {
        return store.projectFile(projectId).resolveSibling("project.xml.bak");
    }

    /**
     * Persistiert den In-Memory-Zustand in die Projektdatei (NFA-05):
     * Backup der letzten guten Version, direktes Schreiben via Engine
     * ({@code saveIn} hält den internen Pfad konsistent), Backup-Cleanup.
     * Bei Absturz mitten im Schreiben stellt {@link #start()} das Backup wieder her.
     */
    public void save(String projectId) {
        ProjectHandle handle = require(projectId);
        handle.lock().writeLock().lock();
        try {
            java.nio.file.Path target = store.projectFile(projectId);
            java.nio.file.Path backup = backupFile(projectId);
            java.nio.file.Files.copy(target, backup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                handle.project().saveIn(target.toFile());
                java.nio.file.Files.deleteIfExists(backup);
            } catch (Exception saveFailure) {
                java.nio.file.Files.move(backup, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                throw new IllegalStateException(
                        "Projekt konnte nicht gespeichert werden: " + saveFailure.getMessage(),
                        saveFailure);
            }
            ProjectMeta touched = handle.meta().touched(Instant.now().toEpochMilli());
            store.writeMeta(projectId, touched);
            handle.updateMeta(touched);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Projekt-Backup fehlgeschlagen: " + e.getMessage(), e);
        } finally {
            handle.lock().writeLock().unlock();
        }
    }

    @Override
    public void stop() {
        running = false;
        handles.values().forEach(handle -> release(handle.project()));
        handles.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Nach der Engine starten (deren Phase ist MIN_VALUE+100), vor ihr stoppen
        return Integer.MIN_VALUE + 200;
    }

    /**
     * Nimmt eine hochgeladene Projektdatei an: erst in die Ablage schreiben,
     * dann mit der Engine validieren — schlägt das Laden fehl, wird das
     * Verzeichnis restlos entfernt (FA-01).
     */
    public ProjectHandle upload(byte[] content, String uploadedBy) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Die hochgeladene Datei ist leer");
        }
        requireSoapUiProjectXml(content);
        String projectId = UUID.randomUUID().toString();
        store.writeProjectFile(projectId, content);
        WsdlProject project;
        try {
            project = new WsdlProject(store.projectFile(projectId).toString());
        } catch (Exception e) {
            store.deleteProject(projectId);
            throw new IllegalArgumentException(
                    "Keine valide SoapUI-Projektdatei: " + rootMessage(e));
        }
        long now = Instant.now().toEpochMilli();
        ProjectMeta meta = new ProjectMeta(project.getName(), uploadedBy, now, now, List.of());
        store.writeMeta(projectId, meta);
        ProjectHandle handle = new ProjectHandle(projectId, project, meta);
        handles.put(projectId, handle);
        log.info("Projekt '{}' ({}) von {} hochgeladen", project.getName(), projectId, uploadedBy);
        return handle;
    }

    public List<ProjectHandle> list() {
        return handles.values().stream()
                .sorted(Comparator.comparing(h -> h.meta().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<ProjectHandle> find(String projectId) {
        return Optional.ofNullable(handles.get(projectId));
    }

    public ProjectHandle require(String projectId) {
        return find(projectId).orElseThrow(
                () -> new IllegalArgumentException("Unbekanntes Projekt: " + projectId));
    }

    /** Download liefert die serverseitige Datei unverändert (FA-02). */
    public byte[] downloadBytes(String projectId) {
        require(projectId);
        return store.readProjectBytes(projectId);
    }

    public long fileSize(String projectId) {
        return store.projectFileSize(projectId);
    }

    /** Löscht das Projekt; laufende Mocks stoppt ab Issue #5 der MockManager davor. */
    public void delete(String projectId) {
        ProjectHandle handle = require(projectId);
        handles.remove(projectId);
        release(handle.project());
        store.deleteProject(projectId);
        log.info("Projekt '{}' ({}) gelöscht", handle.meta().name(), projectId);
    }

    /** Autostart-Flag im meta.json-Sidecar pflegen (FA-14) — nie in der Projekt-XML. */
    public void setAutostart(String projectId, String mockItemId, boolean enabled) {
        ProjectHandle handle = require(projectId);
        java.util.LinkedHashSet<String> ids =
                new java.util.LinkedHashSet<>(handle.meta().autostartMockIds());
        if (enabled) {
            ids.add(mockItemId);
        } else {
            ids.remove(mockItemId);
        }
        ProjectMeta updated = new ProjectMeta(handle.meta().name(), handle.meta().uploadedBy(),
                handle.meta().uploadedAtEpochMs(), handle.meta().lastModifiedAtEpochMs(),
                List.copyOf(ids));
        store.writeMeta(projectId, updated);
        handle.updateMeta(updated);
    }

    private void release(WsdlProject project) {
        try {
            project.release();
        } catch (Exception e) {
            log.warn("Projekt-Release fehlgeschlagen: {}", e.getMessage());
        }
    }

    /**
     * WsdlProject lädt beliebiges XML „erfolgreich" (Recovery-Verhalten der
     * Desktop-App) — deshalb wird das Root-Element vorab geprüft. DTDs und
     * externe Entities sind deaktiviert (XXE-Schutz am Upload-Endpunkt).
     */
    private static void requireSoapUiProjectXml(byte[] content) {
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(content)) {
            javax.xml.stream.XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                if (reader.next() == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    boolean isProject = "soapui-project".equals(reader.getLocalName())
                            && "http://eviware.com/soapui/config".equals(reader.getNamespaceURI());
                    if (!isProject) {
                        throw new IllegalArgumentException(
                                "Keine SoapUI-Projektdatei (Root-Element: " + reader.getName() + ")");
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("Keine SoapUI-Projektdatei (kein XML-Inhalt)");
        } catch (javax.xml.stream.XMLStreamException | java.io.IOException e) {
            throw new IllegalArgumentException("Keine valide XML-Datei: " + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
