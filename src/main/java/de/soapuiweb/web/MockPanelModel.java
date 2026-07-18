package de.soapuiweb.web;

import com.eviware.soapui.impl.support.AbstractMockService;
import com.eviware.soapui.model.ModelItem;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.MockManager;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Befüllt das Model für das Mock-Panel-Fragment — gemeinsam genutzt vom
 * Detail-Panel-Routing (ProjectViewController) und den Mock-Aktionen
 * (MockController, HTMX-Panel-Variante).
 */
@Component
public class MockPanelModel {

    private final ProjectService projectService;
    private final MockManager mockManager;

    public MockPanelModel(ProjectService projectService, MockManager mockManager) {
        this.projectService = projectService;
        this.mockManager = mockManager;
    }

    public void fill(String projectId, String mockItemId, String error, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        ModelItem item = ModelItems.findById(handle.project(), mockItemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Mock: " + mockItemId));
        AbstractMockService<?, ?> mockService = (AbstractMockService<?, ?>) item;
        model.addAttribute("projectId", projectId);
        model.addAttribute("mockId", mockItemId);
        model.addAttribute("mockName", mockService.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(mockService));
        model.addAttribute("port", mockService.getPort());
        model.addAttribute("path", mockService.getPath());
        model.addAttribute("operationCount", mockService.getMockOperationCount());
        model.addAttribute("running", mockManager.isMockRunning(mockItemId));
        model.addAttribute("autostart",
                handle.meta().autostartMockIds().contains(mockItemId));
        model.addAttribute("panelError", error);
    }
}
