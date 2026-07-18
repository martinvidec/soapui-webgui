package de.soapuiweb.service;

import com.eviware.soapui.impl.support.AbstractMockService;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.mock.MockResult;
import com.eviware.soapui.model.mock.MockRunner;
import com.eviware.soapui.model.support.MockRunListenerAdapter;
import de.soapuiweb.config.AppProperties;
import de.soapuiweb.engine.ModelItems;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle laufender MockServices (FA-11/14/15): Registry mockId → Runner,
 * Portbereichs- und Konfliktprüfung mit Verursacher-Meldung, Autostart aus dem
 * meta.json-Sidecar beim App-Start, sauberes Stoppen beim Shutdown.
 */
@Service
public class MockManager implements SmartLifecycle {

    public record RunningMock(String projectId, String projectName, String mockId,
                              String mockName, int port, String path, MockRunner runner,
                              long startedAtEpochMs) {
    }

    private static final Logger log = LogManager.getLogger(MockManager.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ProjectService projectService;
    private final MockLogService mockLogService;
    private final AppProperties properties;
    private final Map<String, RunningMock> running = new ConcurrentHashMap<>();
    private final Set<String> listenerAttached = ConcurrentHashMap.newKeySet();
    private final List<String> autostartWarnings = new CopyOnWriteArrayList<>();
    private volatile boolean lifecycleRunning;

    public MockManager(ProjectService projectService, MockLogService mockLogService,
                       AppProperties properties) {
        this.projectService = projectService;
        this.mockLogService = mockLogService;
        this.properties = properties;
    }

    /** Autostart (FA-14): Fehler einzelner Mocks blockieren den App-Start nicht. */
    @Override
    public void start() {
        autostartWarnings.clear();
        for (ProjectHandle handle : projectService.list()) {
            for (String mockId : handle.meta().autostartMockIds()) {
                try {
                    startMock(handle.id(), mockId);
                    log.info("Autostart: Mock {} in Projekt '{}' gestartet", mockId, handle.meta().name());
                } catch (Exception e) {
                    String warning = "Autostart fehlgeschlagen — Projekt '" + handle.meta().name()
                            + "': " + e.getMessage();
                    autostartWarnings.add(warning);
                    log.warn(warning);
                }
            }
        }
        lifecycleRunning = true;
    }

    @Override
    public void stop() {
        lifecycleRunning = false;
        List.copyOf(running.values()).forEach(mock -> {
            try {
                mock.runner().stop();
            } catch (Exception e) {
                log.warn("Mock {} ließ sich nicht sauber stoppen: {}", mock.mockName(), e.getMessage());
            }
        });
        running.clear();
        log.info("Alle MockRunner gestoppt");
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    @Override
    public int getPhase() {
        // Nach ProjectService (MIN+200) starten, vor ihm stoppen
        return Integer.MIN_VALUE + 300;
    }

    public synchronized RunningMock startMock(String projectId, String mockItemId) {
        ProjectHandle handle = projectService.require(projectId);
        AbstractMockService<?, ?> mock = requireMockService(handle, mockItemId);
        if (running.containsKey(mockItemId)) {
            throw new IllegalStateException("Mock '" + mock.getName() + "' läuft bereits");
        }
        int port = mock.getPort();
        int min = properties.mock().portMin();
        int max = properties.mock().portMax();
        if (port < min || port > max) {
            throw new IllegalArgumentException("Port " + port + " liegt außerhalb des freigegebenen "
                    + "Bereichs " + min + "–" + max + " — Port im Projekt anpassen");
        }
        Optional<RunningMock> holder = running.values().stream()
                .filter(r -> r.port() == port).findFirst();
        if (holder.isPresent()) {
            throw new IllegalStateException("Port " + port + " wird bereits von Mock '"
                    + holder.get().mockName() + "' (Projekt '" + holder.get().projectName()
                    + "') belegt");
        }
        ensureOsPortFree(port);
        attachRequestLogListener(mock, mockItemId);
        MockRunner runner;
        try {
            runner = mock.start();
        } catch (Exception e) {
            throw new IllegalStateException("Mock-Start fehlgeschlagen: " + e.getMessage(), e);
        }
        RunningMock runningMock = new RunningMock(projectId, handle.meta().name(), mockItemId,
                mock.getName(), port, mock.getPath(), runner, Instant.now().toEpochMilli());
        running.put(mockItemId, runningMock);
        log.info("Mock '{}' (Projekt '{}') gestartet auf Port {} Pfad {}",
                mock.getName(), handle.meta().name(), port, mock.getPath());
        return runningMock;
    }

    public synchronized void stopMock(String mockItemId) {
        RunningMock mock = running.remove(mockItemId);
        if (mock == null) {
            return;
        }
        try {
            mock.runner().stop();
            log.info("Mock '{}' gestoppt, Port {} freigegeben", mock.mockName(), mock.port());
        } catch (Exception e) {
            log.warn("Mock '{}' ließ sich nicht sauber stoppen: {}", mock.mockName(), e.getMessage());
        }
    }

    /** Hook für das Projekt-Löschen (Issue #3): stoppt alle Mocks des Projekts. */
    public void stopAllOf(String projectId) {
        running.values().stream()
                .filter(mock -> mock.projectId().equals(projectId))
                .map(RunningMock::mockId)
                .toList()
                .forEach(this::stopMock);
    }

    public boolean isMockRunning(String mockItemId) {
        return running.containsKey(mockItemId);
    }

    public Optional<RunningMock> runningMock(String mockItemId) {
        return Optional.ofNullable(running.get(mockItemId));
    }

    public long runningCountOf(String projectId) {
        return running.values().stream().filter(m -> m.projectId().equals(projectId)).count();
    }

    public List<RunningMock> listRunning() {
        return List.copyOf(running.values());
    }

    public List<String> autostartWarnings() {
        return List.copyOf(autostartWarnings);
    }

    private AbstractMockService<?, ?> requireMockService(ProjectHandle handle, String mockItemId) {
        ModelItem item = ModelItems.findById(handle.project(), mockItemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Mock: " + mockItemId));
        if (!(item instanceof AbstractMockService<?, ?> mock)) {
            throw new IllegalArgumentException("Element '" + item.getName() + "' ist kein MockService");
        }
        return mock;
    }

    private void ensureOsPortFree(int port) {
        // SO_REUSEADDR wie Jetty selbst — sonst blockiert TIME_WAIT nach einem
        // frischen Stop das sofortige Neustarten desselben Mocks
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(port));
        } catch (IOException e) {
            throw new IllegalStateException("Port " + port
                    + " ist auf dem Server bereits belegt (anderer Prozess)");
        }
    }

    /** Hängt genau einmal pro MockService den Request-Log-Listener an (FA-12). */
    private void attachRequestLogListener(AbstractMockService<?, ?> mock, String mockItemId) {
        if (!listenerAttached.add(mockItemId)) {
            return;
        }
        mock.addMockRunListener(new MockRunListenerAdapter() {
            @Override
            public void onMockResult(MockResult result) {
                mockLogService.append(mockItemId, renderLogEntry(result));
            }
        });
    }

    private static String renderLogEntry(MockResult result) {
        String time = TIME_FORMAT.format(Instant.ofEpochMilli(result.getTimestamp()));
        String operation = result.getMockOperation() == null
                ? "?" : result.getMockOperation().getName();
        String request = trimmed(result.getMockRequest() == null
                ? "" : result.getMockRequest().getRequestContent());
        String response = trimmed(result.getResponseContent());
        return "<div class=\"log-entry\">"
                + "<span class=\"log-time\">" + time + "</span> "
                + "<strong>" + HtmlUtils.htmlEscape(operation) + "</strong> "
                + "<span class=\"muted\">" + result.getTimeTaken() + " ms</span>"
                + "<details><summary>Request/Response</summary>"
                + "<pre>" + HtmlUtils.htmlEscape(request) + "</pre>"
                + "<pre>" + HtmlUtils.htmlEscape(response) + "</pre>"
                + "</details></div>";
    }

    private static String trimmed(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 2000 ? content.substring(0, 2000) + "\n… (gekürzt)" : content;
    }
}
