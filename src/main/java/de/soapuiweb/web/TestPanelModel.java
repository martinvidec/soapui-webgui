package de.soapuiweb.web;

import com.eviware.soapui.impl.rest.RestRequest;
import com.eviware.soapui.impl.support.AbstractHttpRequest;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfer;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfersTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlDelayTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.TestPropertyHolder;
import com.eviware.soapui.model.iface.Interface;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.testsuite.TestProperty;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

/** Model-Befüllung für Projekt-, TestSuite-, TestCase- und TestStep-Panels (FA-30/31/35). */
@Component
public class TestPanelModel {

    public record PropertyRow(String name, String value) {
    }

    public record StepRow(String id, String name, String typeLabel) {
    }

    public record CaseRow(String id, String name, boolean disabled) {
    }

    public record TransferRow(int index, String name, String sourceStep, String sourceProperty,
                              String sourcePath, String targetStep, String targetProperty,
                              String targetPath) {
    }

    private final ProjectService projectService;
    private final LockService lockService;

    public TestPanelModel(ProjectService projectService, LockService lockService) {
        this.projectService = projectService;
        this.lockService = lockService;
    }

    public void fillProject(String projectId, String error, String message, String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        WsdlProject project = handle.project();
        common(projectId, error, message, user, model);
        model.addAttribute("projectItemId", project.getId());
        model.addAttribute("projectName", handle.meta().name());
        model.addAttribute("suites", project.getTestSuiteList().stream()
                .map(s -> new CaseRow(s.getId(), s.getName(), s.isDisabled())).toList());
        model.addAttribute("props", propertyRows(project));
        model.addAttribute("holderId", project.getId());
    }

    public void fillSuite(String projectId, String suiteId, String error, String message,
                          String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        WsdlTestSuite suite = (WsdlTestSuite) requireItem(handle, suiteId);
        common(projectId, error, message, user, model);
        model.addAttribute("projectItemId", handle.project().getId());
        model.addAttribute("suiteId", suiteId);
        model.addAttribute("suiteName", suite.getName());
        model.addAttribute("disabled", suite.isDisabled());
        model.addAttribute("cases", suite.getTestCaseList().stream()
                .map(c -> new CaseRow(c.getId(), c.getName(), c.isDisabled())).toList());
        model.addAttribute("props", propertyRows(suite));
        model.addAttribute("holderId", suiteId);
    }

    public void fillCase(String projectId, String caseId, String error, String message,
                         String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        WsdlTestCase testCase = (WsdlTestCase) requireItem(handle, caseId);
        common(projectId, error, message, user, model);
        model.addAttribute("caseId", caseId);
        model.addAttribute("caseName", testCase.getName());
        model.addAttribute("suiteId", testCase.getTestSuite().getId());
        model.addAttribute("suiteName", testCase.getTestSuite().getName());
        model.addAttribute("disabled", testCase.isDisabled());
        model.addAttribute("steps", testCase.getTestStepList().stream()
                .map(s -> new StepRow(s.getId(), s.getName(), ModelItems.typeLabel(s))).toList());
        model.addAttribute("availableRequests", availableRequests(handle));
        model.addAttribute("props", propertyRows(testCase));
        model.addAttribute("holderId", caseId);
    }

    public void fillStep(String projectId, String stepId, String error, String message,
                         String user, Model model) {
        ProjectHandle handle = projectService.require(projectId);
        WsdlTestStep step = (WsdlTestStep) requireItem(handle, stepId);
        WsdlTestCase testCase = step.getTestCase();
        common(projectId, error, message, user, model);
        model.addAttribute("stepId", stepId);
        model.addAttribute("stepName", step.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(step));
        model.addAttribute("caseId", testCase.getId());
        model.addAttribute("caseName", testCase.getName());
        model.addAttribute("isGroovy", step instanceof WsdlGroovyScriptTestStep);
        model.addAttribute("isDelay", step instanceof WsdlDelayTestStep);
        model.addAttribute("isTransfer", step instanceof PropertyTransfersTestStep);
        model.addAttribute("isPropertiesStep",
                step instanceof com.eviware.soapui.impl.wsdl.teststeps.WsdlPropertiesTestStep);
        if (step instanceof WsdlGroovyScriptTestStep groovy) {
            model.addAttribute("script", groovy.getScript());
        }
        if (step instanceof WsdlDelayTestStep delay) {
            model.addAttribute("delayString", delay.getDelayString());
        }
        if (step instanceof PropertyTransfersTestStep transfers) {
            List<TransferRow> rows = new ArrayList<>();
            for (int i = 0; i < transfers.getTransferCount(); i++) {
                PropertyTransfer t = transfers.getTransferAt(i);
                rows.add(new TransferRow(i, t.getName(), t.getSourceStepName(),
                        t.getSourcePropertyName(), t.getSourcePath(), t.getTargetStepName(),
                        t.getTargetPropertyName(), t.getTargetPath()));
            }
            model.addAttribute("transfers", rows);
            List<String> stepNames = new ArrayList<>();
            testCase.getTestStepList().forEach(s -> stepNames.add(s.getName()));
            model.addAttribute("stepNames", stepNames);
        }
        if (step instanceof com.eviware.soapui.impl.wsdl.teststeps.WsdlPropertiesTestStep) {
            model.addAttribute("props", propertyRows((TestPropertyHolder) step));
            model.addAttribute("holderId", stepId);
        }
        // eingebetteter Request eines Request-Steps -> Link in den Request-Editor
        String childRequestId = ModelItems.childrenOf(step).stream()
                .filter(c -> c instanceof AbstractHttpRequest<?>)
                .map(ModelItem::getId).findFirst().orElse(null);
        model.addAttribute("childRequestId", childRequestId);
    }

    private void common(String projectId, String error, String message, String user, Model model) {
        model.addAttribute("projectId", projectId);
        model.addAttribute("canEdit", lockService.isHeldBy(projectId, user));
        model.addAttribute("panelError", error);
        model.addAttribute("panelMessage", message);
    }

    private List<MockPanelModel.NamedRef> availableRequests(ProjectHandle handle) {
        List<MockPanelModel.NamedRef> requests = new ArrayList<>();
        for (Interface iface : handle.project().getInterfaceList()) {
            for (Operation op : iface.getOperationList()) {
                for (ModelItem child : ModelItems.childrenOf(op)) {
                    if (child instanceof AbstractHttpRequest<?> || child instanceof RestRequest) {
                        requests.add(new MockPanelModel.NamedRef(child.getId(),
                                iface.getName() + " · " + op.getName() + " · " + child.getName()));
                    }
                }
            }
        }
        return requests;
    }

    static List<PropertyRow> propertyRows(TestPropertyHolder holder) {
        List<PropertyRow> rows = new ArrayList<>();
        for (TestProperty property : holder.getPropertyList()) {
            rows.add(new PropertyRow(property.getName(), property.getValue()));
        }
        return rows;
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
    }
}
