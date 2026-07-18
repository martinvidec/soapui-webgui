package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.RestRequest;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfer;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfersTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlDelayTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.registry.DelayStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.PropertiesStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.PropertyTransfersStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.RestRequestStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.WsdlTestRequestStepFactory;
import com.eviware.soapui.impl.wsdl.MutableTestPropertyHolder;
import com.eviware.soapui.model.ModelItem;
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
 * Test-Verwaltung (FA-30/31/35): CRUD für TestSuites/TestCases/TestSteps,
 * Properties auf allen Ebenen, Property-Transfer-Editor. Alle Mutationen
 * erfordern die Edit-Sperre und persistieren sofort.
 */
@Controller
public class TestEditController {

    private final ProjectService projectService;
    private final LockService lockService;
    private final TestPanelModel panels;

    public TestEditController(ProjectService projectService, LockService lockService,
                              TestPanelModel panels) {
        this.projectService = projectService;
        this.lockService = lockService;
        this.panels = panels;
    }

    // ---------- TestSuites ----------

    @PostMapping("/projects/{id}/testsuites")
    public String createSuite(@PathVariable String id, @RequestParam String name,
                              Authentication auth, Model model) {
        String[] result = mutate(id, auth, () ->
                projectService.require(id).project().addNewTestSuite(requireName(name)),
                "TestSuite '" + name.trim() + "' angelegt");
        panels.fillProject(id, result[0], result[1], auth.getName(), model);
        return "project/project-panel :: panel";
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/rename")
    public String renameSuite(@PathVariable String id, @PathVariable String suiteId,
                              @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, suiteId, WsdlTestSuite.class).setName(requireName(name)),
                "Umbenannt in '" + name.trim() + "'");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/disable")
    public String disableSuite(@PathVariable String id, @PathVariable String suiteId,
                               @RequestParam boolean disabled, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, suiteId, WsdlTestSuite.class).setDisabled(disabled),
                disabled ? "TestSuite deaktiviert" : "TestSuite aktiviert");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/move")
    public String moveSuite(@PathVariable String id, @PathVariable String suiteId,
                            @RequestParam String dir, Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            WsdlProject project = projectService.require(id).project();
            WsdlTestSuite suite = item(id, suiteId, WsdlTestSuite.class);
            int index = project.getTestSuiteList().indexOf(suite);
            project.moveTestSuite(index, "up".equals(dir) ? -1 : 1);
        }, "Reihenfolge geändert");
        panels.fillProject(id, result[0], result[1], auth.getName(), model);
        return "project/project-panel :: panel";
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/clone")
    public String cloneSuite(@PathVariable String id, @PathVariable String suiteId,
                             @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            WsdlProject project = projectService.require(id).project();
            WsdlTestSuite suite = item(id, suiteId, WsdlTestSuite.class);
            project.importTestSuite(suite, requireName(name), project.getTestSuiteCount(),
                    true, null);
        }, "TestSuite als '" + name.trim() + "' geklont");
        panels.fillProject(id, result[0], result[1], auth.getName(), model);
        return "project/project-panel :: panel";
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/delete")
    public String deleteSuite(@PathVariable String id, @PathVariable String suiteId,
                              Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            WsdlProject project = projectService.require(id).project();
            project.removeTestSuite(item(id, suiteId, WsdlTestSuite.class));
        }, "TestSuite gelöscht");
        panels.fillProject(id, result[0], result[1], auth.getName(), model);
        return "project/project-panel :: panel";
    }

    // ---------- TestCases ----------

    @PostMapping("/projects/{id}/testsuites/{suiteId}/testcases")
    public String createCase(@PathVariable String id, @PathVariable String suiteId,
                             @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, suiteId, WsdlTestSuite.class).addNewTestCase(requireName(name)),
                "TestCase '" + name.trim() + "' angelegt");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/rename")
    public String renameCase(@PathVariable String id, @PathVariable String caseId,
                             @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, caseId, WsdlTestCase.class).setName(requireName(name)),
                "Umbenannt in '" + name.trim() + "'");
        panels.fillCase(id, caseId, result[0], result[1], auth.getName(), model);
        return "project/case-panel :: panel";
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/disable")
    public String disableCase(@PathVariable String id, @PathVariable String caseId,
                              @RequestParam boolean disabled, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, caseId, WsdlTestCase.class).setDisabled(disabled),
                disabled ? "TestCase deaktiviert" : "TestCase aktiviert");
        panels.fillCase(id, caseId, result[0], result[1], auth.getName(), model);
        return "project/case-panel :: panel";
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/move")
    public String moveCase(@PathVariable String id, @PathVariable String caseId,
                           @RequestParam String dir, Authentication auth, Model model) {
        WsdlTestCase testCase = item(id, caseId, WsdlTestCase.class);
        String suiteId = testCase.getTestSuite().getId();
        String[] result = mutate(id, auth, () -> {
            WsdlTestSuite suite = testCase.getTestSuite();
            int index = suite.getTestCaseList().indexOf(testCase);
            suite.moveTestCase(index, "up".equals(dir) ? -1 : 1);
        }, "Reihenfolge geändert");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/clone")
    public String cloneCase(@PathVariable String id, @PathVariable String caseId,
                            @RequestParam String name, Authentication auth, Model model) {
        WsdlTestCase testCase = item(id, caseId, WsdlTestCase.class);
        String suiteId = testCase.getTestSuite().getId();
        String[] result = mutate(id, auth,
                () -> testCase.getTestSuite().cloneTestCase(testCase, requireName(name)),
                "TestCase als '" + name.trim() + "' geklont");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/delete")
    public String deleteCase(@PathVariable String id, @PathVariable String caseId,
                             Authentication auth, Model model) {
        WsdlTestCase testCase = item(id, caseId, WsdlTestCase.class);
        String suiteId = testCase.getTestSuite().getId();
        String[] result = mutate(id, auth,
                () -> testCase.getTestSuite().removeTestCase(testCase), "TestCase gelöscht");
        panels.fillSuite(id, suiteId, result[0], result[1], auth.getName(), model);
        return "project/suite-panel :: panel";
    }

    // ---------- TestSteps ----------

    @PostMapping("/projects/{id}/testcases/{caseId}/teststeps")
    public String createStep(@PathVariable String id, @PathVariable String caseId,
                             @RequestParam String type, @RequestParam String name,
                             @RequestParam(required = false) String requestId,
                             Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            WsdlTestCase testCase = item(id, caseId, WsdlTestCase.class);
            String stepName = requireName(name);
            switch (type) {
                case "groovy" -> testCase.addTestStep(GroovyScriptStepFactory.GROOVY_TYPE, stepName);
                case "delay" -> testCase.addTestStep(DelayStepFactory.DELAY_TYPE, stepName);
                case "properties" -> testCase.addTestStep(PropertiesStepFactory.PROPERTIES_TYPE, stepName);
                case "transfer" -> testCase.addTestStep(PropertyTransfersStepFactory.TRANSFER_TYPE, stepName);
                case "request" -> {
                    if (requestId == null || requestId.isBlank()) {
                        throw new IllegalArgumentException("Für einen Request-Step einen Request auswählen");
                    }
                    ModelItem request = requireItem(id, requestId);
                    if (request instanceof WsdlRequest wsdlRequest) {
                        testCase.addTestStep(WsdlTestRequestStepFactory.createConfig(wsdlRequest, stepName));
                    } else if (request instanceof RestRequest restRequest) {
                        testCase.addTestStep(RestRequestStepFactory.createConfig(restRequest, stepName));
                    } else {
                        throw new IllegalArgumentException("Ausgewähltes Element ist kein Request");
                    }
                }
                default -> throw new IllegalArgumentException("Unbekannter Step-Typ: " + type);
            }
        }, "TestStep '" + name.trim() + "' angelegt");
        panels.fillCase(id, caseId, result[0], result[1], auth.getName(), model);
        return "project/case-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/rename")
    public String renameStep(@PathVariable String id, @PathVariable String stepId,
                             @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, stepId, WsdlTestStep.class).setName(requireName(name)),
                "Umbenannt in '" + name.trim() + "'");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/move")
    public String moveStep(@PathVariable String id, @PathVariable String stepId,
                           @RequestParam String dir, Authentication auth, Model model) {
        WsdlTestStep step = item(id, stepId, WsdlTestStep.class);
        String caseId = step.getTestCase().getId();
        String[] result = mutate(id, auth, () -> {
            WsdlTestCase testCase = step.getTestCase();
            int index = testCase.getTestStepList().indexOf(step);
            testCase.moveTestStep(index, "up".equals(dir) ? -1 : 1);
        }, "Reihenfolge geändert");
        panels.fillCase(id, caseId, result[0], result[1], auth.getName(), model);
        return "project/case-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/delete")
    public String deleteStep(@PathVariable String id, @PathVariable String stepId,
                             Authentication auth, Model model) {
        WsdlTestStep step = item(id, stepId, WsdlTestStep.class);
        String caseId = step.getTestCase().getId();
        String[] result = mutate(id, auth,
                () -> step.getTestCase().removeTestStep(step), "TestStep gelöscht");
        panels.fillCase(id, caseId, result[0], result[1], auth.getName(), model);
        return "project/case-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/script")
    public String saveScript(@PathVariable String id, @PathVariable String stepId,
                             @RequestParam String script, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, stepId, WsdlGroovyScriptTestStep.class).setScript(script),
                "Skript gespeichert");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/delay")
    public String saveDelay(@PathVariable String id, @PathVariable String stepId,
                            @RequestParam String delay, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, stepId, WsdlDelayTestStep.class).setDelayString(delay.trim()),
                "Delay gespeichert");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    // ---------- Property-Transfers ----------

    @PostMapping("/projects/{id}/teststeps/{stepId}/transfers")
    public String addTransfer(@PathVariable String id, @PathVariable String stepId,
                              @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, stepId, PropertyTransfersTestStep.class).addTransfer(requireName(name)),
                "Transfer '" + name.trim() + "' angelegt");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/transfers/{index}")
    public String updateTransfer(@PathVariable String id, @PathVariable String stepId,
                                 @PathVariable int index,
                                 @RequestParam String sourceStep, @RequestParam String sourceProperty,
                                 @RequestParam(defaultValue = "") String sourcePath,
                                 @RequestParam String targetStep, @RequestParam String targetProperty,
                                 @RequestParam(defaultValue = "") String targetPath,
                                 Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            PropertyTransfer transfer =
                    item(id, stepId, PropertyTransfersTestStep.class).getTransferAt(index);
            transfer.setSourceStepName(sourceStep);
            transfer.setSourcePropertyName(sourceProperty);
            transfer.setSourcePath(sourcePath);
            transfer.setTargetStepName(targetStep);
            transfer.setTargetPropertyName(targetProperty);
            transfer.setTargetPath(targetPath);
        }, "Transfer gespeichert");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/transfers/{index}/delete")
    public String deleteTransfer(@PathVariable String id, @PathVariable String stepId,
                                 @PathVariable int index, Authentication auth, Model model) {
        String[] result = mutate(id, auth,
                () -> item(id, stepId, PropertyTransfersTestStep.class).removeTransferAt(index),
                "Transfer gelöscht");
        panels.fillStep(id, stepId, result[0], result[1], auth.getName(), model);
        return "project/step-panel :: panel";
    }

    // ---------- Properties (Projekt/Suite/Case/Properties-Step) ----------

    @PostMapping("/projects/{id}/properties/{holderId}")
    public String upsertProperty(@PathVariable String id, @PathVariable String holderId,
                                 @RequestParam String name, @RequestParam String value,
                                 Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> {
            MutableTestPropertyHolder holder = holder(id, holderId);
            if (!holder.hasProperty(requireName(name))) {
                holder.addProperty(name.trim());
            }
            holder.setPropertyValue(name.trim(), value);
        }, "Property '" + name.trim() + "' gespeichert");
        return holderPanel(id, holderId, result, auth, model);
    }

    @PostMapping("/projects/{id}/properties/{holderId}/delete")
    public String deleteProperty(@PathVariable String id, @PathVariable String holderId,
                                 @RequestParam String name, Authentication auth, Model model) {
        String[] result = mutate(id, auth, () -> holder(id, holderId).removeProperty(name),
                "Property '" + name + "' gelöscht");
        return holderPanel(id, holderId, result, auth, model);
    }

    private String holderPanel(String id, String holderId, String[] result,
                               Authentication auth, Model model) {
        ModelItem holder = requireItem(id, holderId);
        if (holder instanceof WsdlTestSuite) {
            panels.fillSuite(id, holderId, result[0], result[1], auth.getName(), model);
            return "project/suite-panel :: panel";
        }
        if (holder instanceof WsdlTestCase) {
            panels.fillCase(id, holderId, result[0], result[1], auth.getName(), model);
            return "project/case-panel :: panel";
        }
        if (holder instanceof WsdlTestStep) {
            panels.fillStep(id, holderId, result[0], result[1], auth.getName(), model);
            return "project/step-panel :: panel";
        }
        panels.fillProject(id, result[0], result[1], auth.getName(), model);
        return "project/project-panel :: panel";
    }

    // ---------- Helfer ----------

    /** Führt eine Mutation mit Sperren-Guard und Sofort-Persistierung aus; [error, message]. */
    private String[] mutate(String projectId, Authentication auth, Runnable action, String message) {
        try {
            lockService.ensureHeldBy(projectId, auth.getName());
            action.run();
            projectService.save(projectId);
            return new String[]{null, message};
        } catch (RuntimeException e) {
            return new String[]{e.getMessage(), null};
        }
    }

    private MutableTestPropertyHolder holder(String projectId, String holderId) {
        ModelItem item = requireItem(projectId, holderId);
        if (!(item instanceof MutableTestPropertyHolder mutable)) {
            throw new IllegalArgumentException("Element hat keine editierbaren Properties");
        }
        return mutable;
    }

    private <T> T item(String projectId, String itemId, Class<T> type) {
        ModelItem item = requireItem(projectId, itemId);
        if (!type.isInstance(item)) {
            throw new IllegalArgumentException("Element '" + item.getName()
                    + "' hat nicht den erwarteten Typ " + type.getSimpleName());
        }
        return type.cast(item);
    }

    private ModelItem requireItem(String projectId, String itemId) {
        ProjectHandle handle = projectService.require(projectId);
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name angeben");
        }
        return name.trim();
    }
}
