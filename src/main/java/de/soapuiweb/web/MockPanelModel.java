package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.mock.RestMockService;
import com.eviware.soapui.impl.support.AbstractMockService;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Interface;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.mock.MockOperation;
import com.eviware.soapui.model.mock.MockResponse;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.MockManager;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * Befüllt die Models der Mock-Panels (Service, Operation, Response) —
 * gemeinsam genutzt vom Detail-Routing und den Editier-Endpunkten (HTMX).
 */
@Component
public class MockPanelModel {

    public record NamedRef(String id, String name) {
    }

    private final ProjectService projectService;
    private final MockManager mockManager;
    private final LockService lockService;

    public MockPanelModel(ProjectService projectService, MockManager mockManager,
                          LockService lockService) {
        this.projectService = projectService;
        this.mockManager = mockManager;
        this.lockService = lockService;
    }

    public void fill(String projectId, String mockItemId, String error, String message,
                     String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        AbstractMockService<?, ?> mockService =
                (AbstractMockService<?, ?>) requireItem(handle, mockItemId);
        boolean isRest = mockService instanceof RestMockService;
        model.addAttribute("projectId", projectId);
        model.addAttribute("mockId", mockItemId);
        model.addAttribute("mockName", mockService.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(mockService));
        model.addAttribute("port", mockService.getPort());
        model.addAttribute("path", mockService.getPath());
        model.addAttribute("isRest", isRest);
        model.addAttribute("operations", mockService.getMockOperationList().stream()
                .map(op -> new NamedRef(op.getId(), op.getName())).toList());
        model.addAttribute("soapOperations", isRest ? List.of() : soapOperations(handle));
        model.addAttribute("running", mockManager.isMockRunning(mockItemId));
        model.addAttribute("autostart", handle.meta().autostartMockIds().contains(mockItemId));
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
        model.addAttribute("startScript", mockService.getStartScript());
        model.addAttribute("stopScript", mockService.getStopScript());
        model.addAttribute("onRequestScript", mockService.getOnRequestScript());
        model.addAttribute("afterRequestScript", mockService.getAfterRequestScript());
    }

    public void fillOperation(String projectId, String opItemId, String error, String message,
                              String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        MockOperation op = (MockOperation) requireItem(handle, opItemId);
        String serviceId = op.getMockService().getId();
        model.addAttribute("projectId", projectId);
        model.addAttribute("opId", opItemId);
        model.addAttribute("opName", op.getName());
        model.addAttribute("serviceId", serviceId);
        model.addAttribute("serviceName", op.getMockService().getName());
        model.addAttribute("responses", op.getMockResponses().stream()
                .map(r -> new NamedRef(r.getId(), r.getName())).toList());
        var abstractOp = (com.eviware.soapui.impl.support.AbstractMockOperation<?, ?>) op;
        model.addAttribute("dispatchStyle", abstractOp.getDispatchStyle());
        model.addAttribute("dispatchScript", abstractOp.getScript());
        model.addAttribute("defaultResponse", abstractOp.getDefaultResponse());
        model.addAttribute("running", mockManager.isMockRunning(serviceId));
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
    }

    public void fillResponse(String projectId, String responseItemId, String error, String message,
                             String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        MockResponse response = (MockResponse) requireItem(handle, responseItemId);
        String serviceId = response.getMockOperation().getMockService().getId();
        model.addAttribute("projectId", projectId);
        model.addAttribute("responseId", responseItemId);
        model.addAttribute("responseName", response.getName());
        model.addAttribute("opId", response.getMockOperation().getId());
        model.addAttribute("opName", response.getMockOperation().getName());
        model.addAttribute("serviceId", serviceId);
        model.addAttribute("content", response.getResponseContent());
        model.addAttribute("running", mockManager.isMockRunning(serviceId));
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
    }

    private List<NamedRef> soapOperations(ProjectHandle handle) {
        List<NamedRef> operations = new ArrayList<>();
        for (Interface iface : handle.project().getInterfaceList()) {
            for (Operation operation : iface.getOperationList()) {
                operations.add(new NamedRef(operation.getId(),
                        iface.getName() + " · " + operation.getName()));
            }
        }
        return operations;
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
