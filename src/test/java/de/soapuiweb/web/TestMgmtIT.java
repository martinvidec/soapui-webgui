package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfersTestStep;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test-Verwaltung (FA-30/31/35): Suite/Case/Step-CRUD über die Endpunkte,
 * alle Phase-1-Step-Typen, Properties auf allen Ebenen mit Datei-Roundtrip,
 * Property-Transfer-Editor, Reihenfolge und Disabled-Zustand.
 */
@SpringBootTest(properties = "app.data-dir=target/testmgmt-it-data")
@AutoConfigureMockMvc
class TestMgmtIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/testmgmt-it-data").toFile());
    }

    private static final String EDITOR = "editor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private LockService lockService;

    private String projectId;

    private String setup() throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("TestMgmt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());
        WsdlOperation operation = ifaces[0].getOperationAt(0);
        operation.addNewRequest("Basis").setRequestContent(operation.createRequest(true));

        File file = File.createTempFile("testmgmt", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), EDITOR);
        projectId = handle.id();
        lockService.acquire(projectId, EDITOR);
        return projectId;
    }

    @AfterEach
    void cleanup() {
        if (projectId != null && projectService.find(projectId).isPresent()) {
            lockService.releaseAllOf(projectId);
            projectService.delete(projectId);
        }
    }

    private void postOk(String url, String expectContains, String... params) throws Exception {
        var request = post(url, new Object[0]).with(user(EDITOR).roles("USER")).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectContains)));
    }

    @Test
    void suiteCaseAndAllStepTypesViaEndpoints() throws Exception {
        String id = setup();
        WsdlProject project = projectService.require(id).project();

        postOk("/projects/" + id + "/testsuites", "angelegt", "name", "Suite A");
        WsdlTestSuite suite = project.getTestSuiteByName("Suite A");
        assertThat(suite).isNotNull();

        postOk("/projects/" + id + "/testsuites/" + suite.getId() + "/testcases",
                "angelegt", "name", "Case 1");
        WsdlTestCase testCase = suite.getTestCaseByName("Case 1");
        assertThat(testCase).isNotNull();

        String requestId = project.getInterfaceAt(0).getOperationAt(0)
                .getRequestByName("Basis").getId();
        String base = "/projects/" + id + "/testcases/" + testCase.getId() + "/teststeps";
        postOk(base, "angelegt", "type", "request", "name", "Anfrage", "requestId", requestId);
        postOk(base, "angelegt", "type", "groovy", "name", "Skript", "requestId", "");
        postOk(base, "angelegt", "type", "transfer", "name", "Transfer", "requestId", "");
        postOk(base, "angelegt", "type", "properties", "name", "Props", "requestId", "");
        postOk(base, "angelegt", "type", "delay", "name", "Pause", "requestId", "");

        assertThat(testCase.getTestStepCount()).isEqualTo(5);
        assertThat(testCase.getTestStepByName("Anfrage")).isNotNull();

        // Roundtrip: Struktur steht in der Projektdatei
        String saved = new String(projectService.downloadBytes(id));
        assertThat(saved).contains("Suite A").contains("Case 1")
                .contains("Anfrage").contains("Skript").contains("Pause");
    }

    @Test
    void reorderDisableCloneAndDelete() throws Exception {
        String id = setup();
        WsdlProject project = projectService.require(id).project();
        postOk("/projects/" + id + "/testsuites", "angelegt", "name", "S");
        WsdlTestSuite suite = project.getTestSuiteByName("S");
        postOk("/projects/" + id + "/testsuites/" + suite.getId() + "/testcases",
                "angelegt", "name", "C1");
        postOk("/projects/" + id + "/testsuites/" + suite.getId() + "/testcases",
                "angelegt", "name", "C2");

        // C2 nach oben
        postOk("/projects/" + id + "/testcases/" + suite.getTestCaseByName("C2").getId() + "/move",
                "geändert", "dir", "up");
        assertThat(suite.getTestCaseAt(0).getName()).isEqualTo("C2");

        // Disabled-Zustand
        postOk("/projects/" + id + "/testcases/" + suite.getTestCaseByName("C1").getId() + "/disable",
                "deaktiviert", "disabled", "true");
        assertThat(suite.getTestCaseByName("C1").isDisabled()).isTrue();

        // Klonen mit Steps
        postOk("/projects/" + id + "/testcases/" + suite.getTestCaseByName("C2").getId()
                + "/teststeps", "angelegt", "type", "groovy", "name", "G", "requestId", "");
        postOk("/projects/" + id + "/testcases/" + suite.getTestCaseByName("C2").getId() + "/clone",
                "geklont", "name", "C2-Kopie");
        assertThat(suite.getTestCaseByName("C2-Kopie").getTestStepCount()).isEqualTo(1);

        // Löschen
        postOk("/projects/" + id + "/testcases/" + suite.getTestCaseByName("C2-Kopie").getId()
                + "/delete", "gelöscht");
        assertThat(suite.getTestCaseByName("C2-Kopie")).isNull();
    }

    @Test
    void propertiesOnAllLevelsWithFileRoundtrip() throws Exception {
        String id = setup();
        WsdlProject project = projectService.require(id).project();
        postOk("/projects/" + id + "/testsuites", "angelegt", "name", "S");
        WsdlTestSuite suite = project.getTestSuiteByName("S");
        postOk("/projects/" + id + "/testsuites/" + suite.getId() + "/testcases",
                "angelegt", "name", "C");
        WsdlTestCase testCase = suite.getTestCaseByName("C");

        postOk("/projects/" + id + "/properties/" + project.getId(), "gespeichert",
                "name", "projProp", "value", "PROJEKT-WERT");
        postOk("/projects/" + id + "/properties/" + suite.getId(), "gespeichert",
                "name", "suiteProp", "value", "SUITE-WERT");
        postOk("/projects/" + id + "/properties/" + testCase.getId(), "gespeichert",
                "name", "caseProp", "value", "CASE-WERT");

        assertThat(project.getPropertyValue("projProp")).isEqualTo("PROJEKT-WERT");
        assertThat(suite.getPropertyValue("suiteProp")).isEqualTo("SUITE-WERT");
        assertThat(testCase.getPropertyValue("caseProp")).isEqualTo("CASE-WERT");

        // Wert ändern + Datei-Roundtrip (Expansion beim Lauf folgt in #10)
        postOk("/projects/" + id + "/properties/" + project.getId(), "gespeichert",
                "name", "projProp", "value", "NEUER-WERT");
        assertThat(project.getPropertyValue("projProp")).isEqualTo("NEUER-WERT");
        String saved = new String(projectService.downloadBytes(id));
        assertThat(saved).contains("projProp").contains("NEUER-WERT")
                .contains("suiteProp").contains("caseProp");

        // Löschen
        postOk("/projects/" + id + "/properties/" + project.getId() + "/delete", "gelöscht",
                "name", "projProp");
        assertThat(project.getPropertyValue("projProp")).isNull();
    }

    @Test
    void propertyTransferEditor() throws Exception {
        String id = setup();
        WsdlProject project = projectService.require(id).project();
        postOk("/projects/" + id + "/testsuites", "angelegt", "name", "S");
        WsdlTestSuite suite = project.getTestSuiteByName("S");
        postOk("/projects/" + id + "/testsuites/" + suite.getId() + "/testcases",
                "angelegt", "name", "C");
        WsdlTestCase testCase = suite.getTestCaseByName("C");
        postOk("/projects/" + id + "/testcases/" + testCase.getId() + "/teststeps",
                "angelegt", "type", "groovy", "name", "Quelle", "requestId", "");
        postOk("/projects/" + id + "/testcases/" + testCase.getId() + "/teststeps",
                "angelegt", "type", "transfer", "name", "Transfers", "requestId", "");

        PropertyTransfersTestStep transferStep =
                (PropertyTransfersTestStep) testCase.getTestStepByName("Transfers");
        postOk("/projects/" + id + "/teststeps/" + transferStep.getId() + "/transfers",
                "angelegt", "name", "T1");
        postOk("/projects/" + id + "/teststeps/" + transferStep.getId() + "/transfers/0",
                "gespeichert",
                "sourceStep", "Quelle", "sourceProperty", "result",
                "sourcePath", "", "targetStep", "Transfers", "targetProperty", "input",
                "targetPath", "");

        assertThat(transferStep.getTransferCount()).isEqualTo(1);
        assertThat(transferStep.getTransferAt(0).getSourceStepName()).isEqualTo("Quelle");
        assertThat(transferStep.getTransferAt(0).getTargetPropertyName()).isEqualTo("input");
    }

    @Test
    void mutationWithoutLockIsRejected() throws Exception {
        String id = setup();
        mockMvc.perform(post("/projects/" + id + "/testsuites")
                        .param("name", "Fremd")
                        .with(user("fremder").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gesperrt")));
        assertThat(projectService.require(id).project().getTestSuiteByName("Fremd")).isNull();
    }
}
