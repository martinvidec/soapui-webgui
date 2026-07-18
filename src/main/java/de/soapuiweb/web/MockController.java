package de.soapuiweb.web;

import com.eviware.soapui.impl.support.AbstractMockService;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.EventStreamService;
import de.soapuiweb.service.MockManager;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock-Betrieb (FA-10/11/12/14): globale Übersicht, Start/Stop, Autostart-Flag,
 * SSE-Request-Log. Panel-Varianten (HTMX) liefern das Mock-Panel-Fragment
 * direkt zurück statt zu redirecten.
 */
@Controller
public class MockController {

    private final ProjectService projectService;
    private final MockManager mockManager;
    private final EventStreamService eventStreamService;
    private final MockPanelModel mockPanelModel;

    public MockController(ProjectService projectService, MockManager mockManager,
                          EventStreamService eventStreamService, MockPanelModel mockPanelModel) {
        this.projectService = projectService;
        this.mockManager = mockManager;
        this.eventStreamService = eventStreamService;
        this.mockPanelModel = mockPanelModel;
    }

    public record MockRow(String projectId, String projectName, String mockId, String mockName,
                          String typeLabel, int port, String path, boolean running,
                          boolean autostart) {
    }

    /** Globale Übersicht aller MockServices über alle Projekte (FA-10). */
    @GetMapping("/mocks")
    public String overview(Model model) {
        List<MockRow> rows = new ArrayList<>();
        for (ProjectHandle handle : projectService.list()) {
            for (var mockService : handle.project().getMockServiceList()) {
                rows.add(toRow(handle, (AbstractMockService<?, ?>) mockService));
            }
            for (var mockService : handle.project().getRestMockServiceList()) {
                rows.add(toRow(handle, mockService));
            }
        }
        model.addAttribute("mocks", rows);
        model.addAttribute("autostartWarnings", mockManager.autostartWarnings());
        return "mocks/overview";
    }

    @PostMapping("/projects/{id}/mocks/{mockId}/start")
    public String start(@PathVariable String id, @PathVariable String mockId,
                        @RequestParam(required = false) String panel,
                        org.springframework.security.core.Authentication auth,
                        Model model, RedirectAttributes redirect) {
        String error = null;
        try {
            mockManager.startMock(id, mockId);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        return respond(id, mockId, panel, error, auth.getName(), model, redirect);
    }

    @PostMapping("/projects/{id}/mocks/{mockId}/stop")
    public String stop(@PathVariable String id, @PathVariable String mockId,
                       @RequestParam(required = false) String panel,
                       org.springframework.security.core.Authentication auth,
                       Model model, RedirectAttributes redirect) {
        mockManager.stopMock(mockId);
        return respond(id, mockId, panel, null, auth.getName(), model, redirect);
    }

    /** Ein-Klick-Neustart, damit Editier-Änderungen sicher wirken (FA-13). */
    @PostMapping("/projects/{id}/mocks/{mockId}/restart")
    public String restart(@PathVariable String id, @PathVariable String mockId,
                          @RequestParam(required = false) String panel,
                          org.springframework.security.core.Authentication auth,
                          Model model, RedirectAttributes redirect) {
        String error = null;
        try {
            mockManager.stopMock(mockId);
            mockManager.startMock(id, mockId);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        return respond(id, mockId, panel, error, auth.getName(), model, redirect);
    }

    @PostMapping("/projects/{id}/mocks/{mockId}/autostart")
    public String autostart(@PathVariable String id, @PathVariable String mockId,
                            @RequestParam boolean enabled,
                            @RequestParam(required = false) String panel,
                            org.springframework.security.core.Authentication auth,
                            Model model, RedirectAttributes redirect) {
        projectService.setAutostart(id, mockId, enabled);
        return respond(id, mockId, panel, null, auth.getName(), model, redirect);
    }

    /** SSE-Request-Log (FA-12); Last-Event-ID sorgt für verlustfreien Reconnect. */
    @GetMapping("/projects/{id}/mocks/{mockId}/log")
    public SseEmitter log(@PathVariable String id, @PathVariable String mockId,
                          @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
        projectService.require(id);
        return eventStreamService.subscribe(mockId, lastEventId);
    }

    private String respond(String projectId, String mockId, String panel, String error,
                           String user, Model model, RedirectAttributes redirect) {
        if (panel != null) {
            mockPanelModel.fill(projectId, mockId, error, null, user, model);
            return "project/mock-panel :: panel";
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
        }
        return "redirect:/mocks";
    }

    private MockRow toRow(ProjectHandle handle, AbstractMockService<?, ?> mockService) {
        return new MockRow(handle.id(), handle.meta().name(), mockService.getId(),
                mockService.getName(), ModelItems.typeLabel(mockService),
                mockService.getPort(), mockService.getPath(),
                mockManager.isMockRunning(mockService.getId()),
                handle.meta().autostartMockIds().contains(mockService.getId()));
    }
}
