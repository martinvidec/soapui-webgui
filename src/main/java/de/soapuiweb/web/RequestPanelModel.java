package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.RestMethod;
import com.eviware.soapui.impl.support.AbstractHttpRequest;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Interface;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.engine.RequestSubmitter;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Model-Befüllung für Request-, Operation- und Interface-Panels (FA-20–23). */
@Component
public class RequestPanelModel {

    private final ProjectService projectService;
    private final LockService lockService;

    public RequestPanelModel(ProjectService projectService, LockService lockService) {
        this.projectService = projectService;
        this.lockService = lockService;
    }

    public void fillRequest(String projectId, String requestId, String error, String message,
                            String user, RequestSubmitter.SubmitResult result, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        AbstractHttpRequest<?> request = (AbstractHttpRequest<?>) requireItem(handle, requestId);
        ModelItem operation = request.getParent();
        Interface iface = parentInterface(request);
        model.addAttribute("projectId", projectId);
        model.addAttribute("requestId", requestId);
        model.addAttribute("requestName", request.getName());
        model.addAttribute("opId", operation.getId());
        model.addAttribute("opName", operation.getName());
        model.addAttribute("endpoint", request.getEndpoint());
        model.addAttribute("endpoints", iface == null ? List.of() : List.of(iface.getEndpoints()));
        model.addAttribute("headerLines", headerLines(request));
        model.addAttribute("content", request.getRequestContent());
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
        model.addAttribute("result", result);
    }

    public void fillOperation(String projectId, String opItemId, String error, String message,
                              String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        ModelItem op = requireItem(handle, opItemId);
        List<MockPanelModel.NamedRef> requests = new ArrayList<>();
        for (ModelItem child : ModelItems.childrenOf(op)) {
            if (child instanceof AbstractHttpRequest<?>) {
                requests.add(new MockPanelModel.NamedRef(child.getId(), child.getName()));
            }
        }
        model.addAttribute("projectId", projectId);
        model.addAttribute("opId", opItemId);
        model.addAttribute("opName", op.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(op));
        model.addAttribute("parentId", op.getParent() == null ? null : op.getParent().getId());
        model.addAttribute("parentName", op.getParent() == null ? "" : op.getParent().getName());
        model.addAttribute("requests", requests);
        model.addAttribute("isWsdl", op instanceof WsdlOperation);
        model.addAttribute("isRestMethod", op instanceof RestMethod);
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
    }

    public void fillInterface(String projectId, String ifaceItemId, String error, String message,
                              String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        Interface iface = (Interface) requireItem(handle, ifaceItemId);
        model.addAttribute("projectId", projectId);
        model.addAttribute("ifaceId", ifaceItemId);
        model.addAttribute("ifaceName", iface.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(iface));
        model.addAttribute("endpoints", List.of(iface.getEndpoints()));
        model.addAttribute("operationCount", iface.getOperationCount());
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
    }

    static Interface parentInterface(ModelItem item) {
        ModelItem current = item;
        while (current != null && !(current instanceof Interface)) {
            current = current.getParent();
        }
        return (Interface) current;
    }

    private static String headerLines(AbstractHttpRequest<?> request) {
        if (request.getRequestHeaders() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : request.getRequestHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\n");
            }
        }
        return sb.toString();
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
