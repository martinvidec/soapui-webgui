package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.RestRequestInterface;
import com.eviware.soapui.impl.rest.mock.RestMockAction;
import com.eviware.soapui.impl.rest.mock.RestMockService;
import com.eviware.soapui.impl.support.AbstractMockOperation;
import com.eviware.soapui.impl.support.AbstractMockService;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.mock.MockOperation;
import com.eviware.soapui.model.mock.MockResponse;
import de.soapuiweb.engine.ModelItems;
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
 * Mock-Editing (FA-13): MockOperationen und -Responses anlegen/löschen,
 * Response-Content editieren, Dispatch konfigurieren. Alle Mutationen
 * erfordern die Edit-Sperre und persistieren das Projekt sofort.
 * Antworten sind HTMX-Panel-Fragmente.
 */
@Controller
public class MockEditController {

    private final ProjectService projectService;
    private final LockService lockService;
    private final MockPanelModel panels;

    public MockEditController(ProjectService projectService, LockService lockService,
                              MockPanelModel panels) {
        this.projectService = projectService;
        this.lockService = lockService;
        this.panels = panels;
    }

    @PostMapping("/projects/{id}/mocks/{mockId}/operations")
    public String addOperation(@PathVariable String id, @PathVariable String mockId,
                               @RequestParam(required = false) String operationId,
                               @RequestParam(required = false) String method,
                               @RequestParam(required = false) String actionPath,
                               Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            AbstractMockService<?, ?> mockService =
                    (AbstractMockService<?, ?>) requireItem(handle, mockId);
            if (mockService instanceof RestMockService restMock) {
                if (actionPath == null || actionPath.isBlank()) {
                    throw new IllegalArgumentException("Pfad für die Mock-Action angeben");
                }
                RestMockAction action = restMock.addEmptyMockAction(
                        RestRequestInterface.HttpMethod.valueOf(method), actionPath.trim());
                action.addNewMockResponse("Response 1");
                message = "Mock-Action '" + action.getName() + "' angelegt";
            } else {
                ModelItem operation = requireItem(handle, operationId);
                // liefert null, wenn für die Operation bereits eine MockOperation existiert
                MockOperation created = ((WsdlMockService) mockService)
                        .addNewMockOperation((Operation) operation);
                if (created == null) {
                    throw new IllegalArgumentException("Für Operation '" + operation.getName()
                            + "' existiert bereits eine MockOperation");
                }
                message = "MockOperation für '" + operation.getName() + "' angelegt";
            }
            projectService.save(id);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fill(id, mockId, error, message, auth.getName(), model);
        return "project/mock-panel :: panel";
    }

    @PostMapping("/projects/{id}/mockops/{opId}/delete")
    public String deleteOperation(@PathVariable String id, @PathVariable String opId,
                                  Authentication auth, Model model) {
        ProjectHandle handle = projectService.require(id);
        MockOperation op = (MockOperation) requireItem(handle, opId);
        String mockId = op.getMockService().getId();
        String opName = op.getName();
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            op.getMockService().removeMockOperation(op);
            projectService.save(id);
            message = "MockOperation '" + opName + "' gelöscht";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fill(id, mockId, error, message, auth.getName(), model);
        return "project/mock-panel :: panel";
    }

    @PostMapping("/projects/{id}/mockops/{opId}/responses")
    public String addResponse(@PathVariable String id, @PathVariable String opId,
                              @RequestParam String name, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            MockOperation op = (MockOperation) requireItem(handle, opId);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name der Response angeben");
            }
            if (op instanceof WsdlMockOperation wsdlOp) {
                wsdlOp.addNewMockResponse(name.trim(), true);
            } else {
                ((RestMockAction) op).addNewMockResponse(name.trim());
            }
            projectService.save(id);
            message = "MockResponse '" + name.trim() + "' angelegt";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillOperation(id, opId, error, message, auth.getName(), model);
        return "project/mockop-panel :: panel";
    }

    @PostMapping("/projects/{id}/mockops/{opId}/dispatch")
    public String updateDispatch(@PathVariable String id, @PathVariable String opId,
                                 @RequestParam String dispatchStyle,
                                 @RequestParam(required = false) String dispatchScript,
                                 @RequestParam(required = false) String defaultResponse,
                                 Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            AbstractMockOperation<?, ?> op =
                    (AbstractMockOperation<?, ?>) requireItem(handle, opId);
            op.setDispatchStyle(dispatchStyle);
            // XPATH: Ausdruck, dessen Ergebnis gegen Response-Namen gematcht wird;
            // SCRIPT: Groovy, das den Response-Namen zurückgibt — beides im script-Feld
            op.setScript(dispatchScript == null ? "" : dispatchScript);
            op.setDefaultResponse(defaultResponse == null || defaultResponse.isBlank()
                    ? null : defaultResponse);
            projectService.save(id);
            message = "Dispatch-Konfiguration gespeichert (" + dispatchStyle + ")";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillOperation(id, opId, error, message, auth.getName(), model);
        return "project/mockop-panel :: panel";
    }

    @PostMapping("/projects/{id}/mockresponses/{responseId}")
    public String updateResponseContent(@PathVariable String id, @PathVariable String responseId,
                                        @RequestParam String content,
                                        Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ProjectHandle handle = projectService.require(id);
            MockResponse response = (MockResponse) requireItem(handle, responseId);
            response.setResponseContent(content);
            projectService.save(id);
            message = "Response-Content gespeichert";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillResponse(id, responseId, error, message, auth.getName(), model);
        return "project/mockresponse-panel :: panel";
    }

    @PostMapping("/projects/{id}/mockresponses/{responseId}/delete")
    public String deleteResponse(@PathVariable String id, @PathVariable String responseId,
                                 Authentication auth, Model model) {
        ProjectHandle handle = projectService.require(id);
        MockResponse response = (MockResponse) requireItem(handle, responseId);
        String opId = response.getMockOperation().getId();
        String responseName = response.getName();
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            ((AbstractMockOperation<?, ?>) response.getMockOperation())
                    .removeMockResponse(response);
            projectService.save(id);
            message = "MockResponse '" + responseName + "' gelöscht";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillOperation(id, opId, error, message, auth.getName(), model);
        return "project/mockop-panel :: panel";
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
