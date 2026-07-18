package de.soapuiweb.web;

import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
public class ProjectController {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ProjectService projectService;
    private final de.soapuiweb.service.LockService lockService;

    public ProjectController(ProjectService projectService,
                             de.soapuiweb.service.LockService lockService) {
        this.projectService = projectService;
        this.lockService = lockService;
    }

    public record ProjectRow(String id, String name, String size, String uploadedBy,
                             String uploadedAt, String lastModifiedAt,
                             String lockedBy, int runningMocks) {
    }

    @GetMapping("/")
    public String list(Model model) {
        List<ProjectRow> rows = projectService.list().stream().map(this::toRow).toList();
        model.addAttribute("projects", rows);
        return "index";
    }

    @PostMapping("/projects")
    public String upload(@RequestParam("file") MultipartFile file, Authentication auth,
                         RedirectAttributes redirect) throws IOException {
        try {
            ProjectHandle handle = projectService.upload(file.getBytes(), auth.getName());
            redirect.addFlashAttribute("message",
                    "Projekt '" + handle.meta().name() + "' hochgeladen");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/projects/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        ProjectHandle handle = projectService.require(id);
        String filename = handle.meta().name().replaceAll("[^A-Za-z0-9._-]", "_") + ".xml";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(projectService.downloadBytes(id));
    }

    @PostMapping("/projects/{id}/delete")
    public String delete(@PathVariable String id, org.springframework.security.core.Authentication auth,
                         RedirectAttributes redirect) {
        ProjectHandle handle = projectService.require(id);
        try {
            lockService.ensureNotLockedByOther(id, auth.getName());
        } catch (de.soapuiweb.service.LockService.LockConflictException e) {
            redirect.addFlashAttribute("error",
                    "Löschen nicht möglich: " + e.getMessage());
            return "redirect:/";
        }
        projectService.delete(id);
        lockService.releaseAllOf(id);
        redirect.addFlashAttribute("message", "Projekt '" + handle.meta().name() + "' gelöscht");
        return "redirect:/";
    }

    private ProjectRow toRow(ProjectHandle handle) {
        return new ProjectRow(
                handle.id(),
                handle.meta().name(),
                formatSize(projectService.fileSize(handle.id())),
                handle.meta().uploadedBy(),
                DATE_FORMAT.format(Instant.ofEpochMilli(handle.meta().uploadedAtEpochMs())),
                DATE_FORMAT.format(Instant.ofEpochMilli(handle.meta().lastModifiedAtEpochMs())),
                lockService.ownerOf(handle.id()).orElse(null),
                0);     // laufende Mocks folgen mit Issue #5
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
