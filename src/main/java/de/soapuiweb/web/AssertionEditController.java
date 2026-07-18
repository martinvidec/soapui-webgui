package de.soapuiweb.web;

import com.eviware.soapui.config.TestAssertionConfig;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlMessageAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.AbstractXmlContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.GroovyScriptAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.ResponseSLAAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.SimpleContainsAssertion;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.testsuite.Assertable;
import com.eviware.soapui.model.testsuite.TestAssertion;
import com.eviware.soapui.security.assertion.ValidHttpStatusCodesAssertion;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectService;
import org.apache.xmlbeans.XmlObject;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Assertions generisch über die Engine-Registry (FA-32): anlegen per Label,
 * konfigurieren über Komfort-Formulare (Contains, XPath, Script, SLA,
 * Status-Codes) oder den generischen XML-Fallback, löschen.
 */
@Controller
public class AssertionEditController {

    private final ProjectService projectService;
    private final LockService lockService;
    private final TestPanelModel panels;

    public AssertionEditController(ProjectService projectService, LockService lockService,
                                   TestPanelModel panels) {
        this.projectService = projectService;
        this.lockService = lockService;
        this.panels = panels;
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/assertions")
    public String create(@PathVariable String id, @PathVariable String stepId,
                         @RequestParam String label, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            TestAssertion created = assertable(id, stepId).addAssertion(label);
            if (created == null) {
                throw new IllegalArgumentException(
                        "Assertion-Typ '" + label + "' ist hier nicht anwendbar");
            }
            projectService.save(id);
            message = "Assertion '" + label + "' angelegt";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillStep(id, stepId, error, message, auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/assertions/{assertionId}")
    public String configure(@PathVariable String id, @PathVariable String stepId,
                            @PathVariable String assertionId,
                            @RequestParam(required = false) String token,
                            @RequestParam(defaultValue = "false") boolean ignoreCase,
                            @RequestParam(required = false) String path,
                            @RequestParam(required = false) String expectedContent,
                            @RequestParam(required = false) String script,
                            @RequestParam(required = false) String sla,
                            @RequestParam(required = false) String codes,
                            @RequestParam(required = false) String configXml,
                            Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            TestAssertion assertion = requireAssertion(id, stepId, assertionId);
            if (assertion instanceof SimpleContainsAssertion contains) {
                contains.setToken(token == null ? "" : token);
                contains.setIgnoreCase(ignoreCase);
            } else if (assertion instanceof AbstractXmlContainsAssertion xpath) {
                xpath.setPath(path == null ? "" : path);
                xpath.setExpectedContent(expectedContent == null ? "" : expectedContent);
            } else if (assertion instanceof GroovyScriptAssertion groovy) {
                groovy.setScriptText(script == null ? "" : script);
            } else if (assertion instanceof ResponseSLAAssertion slaAssertion) {
                slaAssertion.setSLA(sla == null ? "" : sla.trim());
            } else if (assertion instanceof ValidHttpStatusCodesAssertion codesAssertion) {
                codesAssertion.setCodes(codes == null ? "" : codes.trim());
            } else if (configXml != null && !configXml.isBlank()) {
                // Generischer Fallback: Konfigurations-XML direkt setzen (FA-32)
                WsdlMessageAssertion wsdlAssertion = (WsdlMessageAssertion) assertion;
                TestAssertionConfig config = wsdlAssertion.getConfig();
                config.setConfiguration(XmlObject.Factory.parse(configXml));
                wsdlAssertion.updateConfig(config);
            } else {
                throw new IllegalArgumentException("Dieser Assertion-Typ hat keine Konfiguration");
            }
            projectService.save(id);
            message = "Assertion gespeichert";
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        panels.fillStep(id, stepId, error, message, auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/assertions/{assertionId}/delete")
    public String delete(@PathVariable String id, @PathVariable String stepId,
                         @PathVariable String assertionId, Authentication auth, Model model) {
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            Assertable assertable = assertable(id, stepId);
            TestAssertion assertion = requireAssertion(id, stepId, assertionId);
            String label = assertion.getLabel();
            assertable.removeAssertion(assertion);
            projectService.save(id);
            message = "Assertion '" + label + "' gelöscht";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        panels.fillStep(id, stepId, error, message, auth.getName(), model);
        return "project/step-panel :: panel";
    }

    private Assertable assertable(String projectId, String stepId) {
        ModelItem item = ModelItems.findById(projectService.require(projectId).project(), stepId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Step: " + stepId));
        if (!(item instanceof WsdlTestStep) || !(item instanceof Assertable assertable)) {
            throw new IllegalArgumentException("Step unterstützt keine Assertions");
        }
        return assertable;
    }

    private TestAssertion requireAssertion(String projectId, String stepId, String assertionId) {
        return assertable(projectId, stepId).getAssertionList().stream()
                .filter(a -> assertionId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Assertion"));
    }
}
