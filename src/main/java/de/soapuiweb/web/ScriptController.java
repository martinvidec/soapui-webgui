package de.soapuiweb.web;

import com.eviware.soapui.impl.support.AbstractMockService;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.model.ModelItem;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Setup-/TearDown- und Mock-Skripte (FA-40): Projekt (Load/Save), TestSuite
 * und TestCase (Setup/TearDown), MockService (Start/Stop/OnRequest/AfterRequest).
 */
@Controller
public class ScriptController {

    private final ProjectService projectService;
    private final LockService lockService;
    private final TestPanelModel testPanels;
    private final MockPanelModel mockPanels;

    public ScriptController(ProjectService projectService, LockService lockService,
                            TestPanelModel testPanels, MockPanelModel mockPanels) {
        this.projectService = projectService;
        this.lockService = lockService;
        this.testPanels = testPanels;
        this.mockPanels = mockPanels;
    }

    @PostMapping("/projects/{id}/scripts/{holderId}")
    public String saveScript(@PathVariable String id, @PathVariable String holderId,
                             @RequestParam String scriptType, @RequestParam String script,
                             Authentication auth, Model model) {
        ModelItem holder = requireItem(id, holderId);
        String error = null;
        String message = null;
        try {
            lockService.ensureHeldBy(id, auth.getName());
            apply(holder, scriptType, script);
            projectService.save(id);
            message = "Skript gespeichert (" + scriptType + ")";
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        return renderPanel(id, holder, holderId, error, message, auth, model);
    }

    private void apply(ModelItem holder, String scriptType, String script) {
        if (holder instanceof WsdlProject project) {
            switch (scriptType) {
                case "afterLoad" -> project.setAfterLoadScript(script);
                case "beforeSave" -> project.setBeforeSaveScript(script);
                default -> throw new IllegalArgumentException("Unbekannter Skript-Typ: " + scriptType);
            }
        } else if (holder instanceof WsdlTestSuite suite) {
            switch (scriptType) {
                case "setup" -> suite.setSetupScript(script);
                case "teardown" -> suite.setTearDownScript(script);
                default -> throw new IllegalArgumentException("Unbekannter Skript-Typ: " + scriptType);
            }
        } else if (holder instanceof WsdlTestCase testCase) {
            switch (scriptType) {
                case "setup" -> testCase.setSetupScript(script);
                case "teardown" -> testCase.setTearDownScript(script);
                default -> throw new IllegalArgumentException("Unbekannter Skript-Typ: " + scriptType);
            }
        } else if (holder instanceof AbstractMockService<?, ?> mock) {
            switch (scriptType) {
                case "start" -> mock.setStartScript(script);
                case "stop" -> mock.setStopScript(script);
                case "onRequest" -> mock.setOnRequestScript(script);
                case "afterRequest" -> mock.setAfterRequestScript(script);
                default -> throw new IllegalArgumentException("Unbekannter Skript-Typ: " + scriptType);
            }
        } else {
            throw new IllegalArgumentException("Element unterstützt keine Skripte");
        }
    }

    private String renderPanel(String id, ModelItem holder, String holderId, String error,
                               String message, Authentication auth, Model model) {
        if (holder instanceof WsdlTestSuite) {
            testPanels.fillSuite(id, holderId, error, message, auth.getName(), model);
            return "project/suite-panel :: panel";
        }
        if (holder instanceof WsdlTestCase) {
            testPanels.fillCase(id, holderId, error, message, auth.getName(), model);
            return "project/case-panel :: panel";
        }
        if (holder instanceof AbstractMockService<?, ?>) {
            mockPanels.fill(id, holderId, error, message, auth.getName(), model);
            return "project/mock-panel :: panel";
        }
        testPanels.fillProject(id, error, message, auth.getName(), model);
        return "project/project-panel :: panel";
    }

    private ModelItem requireItem(String projectId, String itemId) {
        return ModelItems.findById(projectService.require(projectId).project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
