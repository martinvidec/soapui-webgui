package de.soapuiweb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.soapuiweb.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dateibasierte Projektablage (Spezifikation 2.2):
 * {@code ${app.data-dir}/projects/<projectId>/project.xml + meta.json},
 * alle Schreibvorgänge atomar (temp + move, NFA-05).
 */
@Component
public class ProjectStore {

    public static final String PROJECT_FILE = "project.xml";
    public static final String META_FILE = "meta.json";

    private final ObjectMapper objectMapper;
    private final Path projectsDir;

    public ProjectStore(AppProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.projectsDir = properties.dataDir().resolve("projects");
    }

    public List<String> listProjectIds() {
        if (!Files.isDirectory(projectsDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            return dirs.filter(dir -> Files.isRegularFile(dir.resolve(PROJECT_FILE)))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Projektverzeichnis nicht lesbar: " + projectsDir, e);
        }
    }

    public Path projectFile(String projectId) {
        return projectsDir.resolve(projectId).resolve(PROJECT_FILE);
    }

    public byte[] readProjectBytes(String projectId) {
        try {
            return Files.readAllBytes(projectFile(projectId));
        } catch (IOException e) {
            throw new UncheckedIOException("Projektdatei nicht lesbar: " + projectId, e);
        }
    }

    public long projectFileSize(String projectId) {
        try {
            return Files.size(projectFile(projectId));
        } catch (IOException e) {
            throw new UncheckedIOException("Projektdatei nicht lesbar: " + projectId, e);
        }
    }

    public void writeProjectFile(String projectId, byte[] content) {
        writeAtomically(projectFile(projectId), content);
    }

    public ProjectMeta readMeta(String projectId) {
        Path metaFile = projectsDir.resolve(projectId).resolve(META_FILE);
        try {
            return objectMapper.readValue(metaFile.toFile(), ProjectMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("meta.json nicht lesbar: " + projectId, e);
        }
    }

    public void writeMeta(String projectId, ProjectMeta meta) {
        try {
            writeAtomically(projectsDir.resolve(projectId).resolve(META_FILE),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
        } catch (IOException e) {
            throw new UncheckedIOException("meta.json nicht schreibbar: " + projectId, e);
        }
    }

    public void deleteProject(String projectId) {
        try {
            FileSystemUtils.deleteRecursively(projectsDir.resolve(projectId));
        } catch (IOException e) {
            throw new UncheckedIOException("Projekt nicht löschbar: " + projectId, e);
        }
    }

    private void writeAtomically(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht schreibbar: " + target, e);
        }
    }
}
