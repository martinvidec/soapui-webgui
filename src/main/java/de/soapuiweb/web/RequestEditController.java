package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.RestMethod;
import com.eviware.soapui.impl.rest.RestRequest;
import com.eviware.soapui.impl.support.AbstractHttpRequest;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Interface;
import com.eviware.soapui.support.types.StringToStringsMap;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.engine.RequestSubmitter;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Request-Editor (FA-20–23): Speichern (Content, Header, Endpoint), Senden über
 * die Engine, Request-CRUD an Operationen, Endpoint-Verwaltung am Interface.
 * Senden ist ohne Sperre erlaubt und nutzt den gespeicherten Stand.
 */
@Controller
public class RequestEditController {

    private final ProjectService projectService;
    private final LockService lockService;
    private final RequestSubmitter submitter;
    private final RequestPanelModel panels;

    public RequestEditController(ProjectService projectService, LockService lockService,
                                 RequestSubmitter submitter, RequestPanelModel panels) {
        this.projectService = projectService;
        this.lockService = lockService;
        this.submitter = submitter;
        this.panels = panels;
    }

    @PostMapping("/projects/{id}/requests/{requestId}")
    public String save(@PathVariable String id, @PathVariable String requestId,
                       @RequestParam String content, @RequestParam String endpoint,
                       @RequestParam(defaultValue = "") String headers,
                       Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            applyEdits(id, requestId, content, endpoint, headers);
            projectService.save(id);
            message = "Request gespeichert";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillRequest(id, requestId, error, message, auth.getName(), null, model);
        return "project/request-panel :: panel";
    }

    @PostMapping("/projects/{id}/requests/{requestId}/submit")
    public String submit(@PathVariable String id, @PathVariable String requestId,
                         Authentication auth, Model model) {
        ProjectHandle handle = projectService.require(id);
        AbstractHttpRequest<?> request =
                (AbstractHttpRequest<?>) requireItem(handle, requestId);
        RequestSubmitter.SubmitResult result = submitter.submit(request);
        panels.fillRequest(id, requestId, result.success() ? null : result.error(),
                null, auth.getName(), result, model);
        return "project/request-panel :: panel";
    }

    @PostMapping("/projects/{id}/operations/{opId}/requests")
    public String createRequest(@PathVariable String id, @PathVariable String opId,
                                @RequestParam String name,
                                @RequestParam(defaultValue = "false") boolean withSample,
                                Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            ModelItem op = requireItem(handle, opId);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name angeben");
            }
            if (op instanceof WsdlOperation wsdlOp) {
                WsdlRequest request = wsdlOp.addNewRequest(name.trim());
                if (withSample) {
                    // Beispiel-Request aus dem Schema generieren (FA-23)
                    request.setRequestContent(wsdlOp.createRequest(true));
                }
                if (wsdlOp.getInterface().getEndpoints().length > 0) {
                    request.setEndpoint(wsdlOp.getInterface().getEndpoints()[0]);
                }
            } else if (op instanceof RestMethod restMethod) {
                restMethod.addNewRequest(name.trim());
            } else {
                throw new IllegalArgumentException("Element unterstützt keine Requests");
            }
            projectService.save(id);
            message = "Request '" + name.trim() + "' angelegt";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillOperation(id, opId, error, message, auth.getName(), model);
        return "project/operation-panel :: panel";
    }

    @PostMapping("/projects/{id}/requests/{requestId}/clone")
    public String cloneRequest(@PathVariable String id, @PathVariable String requestId,
                               @RequestParam String name, Authentication auth, Model model) {
        String error = null;
        String message = null;
        ProjectHandle handle = projectService.require(id);
        AbstractHttpRequest<?> source = (AbstractHttpRequest<?>) requireItem(handle, requestId);
        try {
            lockService.ensureHeldBy(id, auth.getName());
            AbstractHttpRequest<?> copy;
            if (source instanceof WsdlRequest wsdlRequest) {
                copy = wsdlRequest.getOperation().addNewRequest(name.trim());
            } else {
                copy = ((RestRequest) source).getRestMethod().addNewRequest(name.trim());
            }
            copy.setRequestContent(source.getRequestContent());
            copy.setEndpoint(source.getEndpoint());
            copy.setRequestHeaders(source.getRequestHeaders());
            projectService.save(id);
            message = "Request als '" + name.trim() + "' geklont";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillRequest(id, requestId, error, message, auth.getName(), null, model);
        return "project/request-panel :: panel";
    }

    @PostMapping("/projects/{id}/requests/{requestId}/rename")
    public String renameRequest(@PathVariable String id, @PathVariable String requestId,
                                @RequestParam String name, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            AbstractHttpRequest<?> request =
                    (AbstractHttpRequest<?>) requireItem(handle, requestId);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name angeben");
            }
            request.setName(name.trim());
            projectService.save(id);
            message = "Umbenannt in '" + name.trim() + "'";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillRequest(id, requestId, error, message, auth.getName(), null, model);
        return "project/request-panel :: panel";
    }

    @PostMapping("/projects/{id}/requests/{requestId}/delete")
    public String deleteRequest(@PathVariable String id, @PathVariable String requestId,
                                Authentication auth, Model model) {
        ProjectHandle handle = projectService.require(id);
        AbstractHttpRequest<?> request = (AbstractHttpRequest<?>) requireItem(handle, requestId);
        String opId = request.getParent().getId();
        String requestName = request.getName();
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            if (request instanceof WsdlRequest wsdlRequest) {
                wsdlRequest.getOperation().removeRequest(wsdlRequest);
            } else {
                RestRequest restRequest = (RestRequest) request;
                restRequest.getRestMethod().removeRequest(restRequest);
            }
            projectService.save(id);
            message = "Request '" + requestName + "' gelöscht";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillOperation(id, opId, error, message, auth.getName(), model);
        return "project/operation-panel :: panel";
    }

    @PostMapping("/projects/{id}/interfaces/{ifaceId}/endpoints")
    public String addEndpoint(@PathVariable String id, @PathVariable String ifaceId,
                              @RequestParam String endpoint, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            Interface iface = (Interface) requireItem(handle, ifaceId);
            if (endpoint.isBlank()) {
                throw new IllegalArgumentException("Endpoint angeben");
            }
            iface.addEndpoint(endpoint.trim());
            projectService.save(id);
            message = "Endpoint hinzugefügt";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillInterface(id, ifaceId, error, message, auth.getName(), model);
        return "project/interface-panel :: panel";
    }

    @PostMapping("/projects/{id}/interfaces/{ifaceId}/endpoints/delete")
    public String removeEndpoint(@PathVariable String id, @PathVariable String ifaceId,
                                 @RequestParam String endpoint, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            Interface iface = (Interface) requireItem(handle, ifaceId);
            iface.removeEndpoint(endpoint);
            projectService.save(id);
            message = "Endpoint entfernt";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillInterface(id, ifaceId, error, message, auth.getName(), model);
        return "project/interface-panel :: panel";
    }

    private void applyEdits(String projectId, String requestId, String content,
                            String endpoint, String headerLines) {
        ProjectHandle handle = projectService.require(projectId);
        AbstractHttpRequest<?> request = (AbstractHttpRequest<?>) requireItem(handle, requestId);
        request.setRequestContent(content);
        if (!endpoint.isBlank()) {
            request.setEndpoint(endpoint.trim());
        }
        StringToStringsMap headers = new StringToStringsMap();
        for (String line : headerLines.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        request.setRequestHeaders(headers);
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
