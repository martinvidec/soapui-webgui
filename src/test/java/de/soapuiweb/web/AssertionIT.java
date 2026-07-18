package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestRequestStep;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.TestAssertionRegistry;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.SimpleContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.XPathContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.registry.WsdlTestRequestStepFactory;
import com.eviware.soapui.model.testsuite.Assertable;
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
 * Assertions (FA-32): alle Registry-Typen anlegbar, Komfort-Formulare
 * (Contains, XPath) konfigurieren die Engine-Objekte korrekt, Roundtrip in
 * die Projektdatei, Löschen und Sperren-Guard.
 */
@SpringBootTest(properties = "app.data-dir=target/assertion-it-data")
@AutoConfigureMockMvc
class AssertionIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/assertion-it-data").toFile());
    }

    private static final String EDITOR = "editor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private LockService lockService;

    private String projectId;
    private String stepId;
    private WsdlTestRequestStep step;

    private void setup() throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("AssertionProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());
        WsdlOperation operation = ifaces[0].getOperationAt(0);
        operation.addNewRequest("Basis").setRequestContent(operation.createRequest(true));
        WsdlTestCase testCase = project.addNewTestSuite("S").addNewTestCase("C");
        testCase.addTestStep(WsdlTestRequestStepFactory.createConfig(
                operation.getRequestByName("Basis"), "Anfrage"));

        File file = File.createTempFile("assertion-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), EDITOR);
        projectId = handle.id();
        lockService.acquire(projectId, EDITOR);

        step = (WsdlTestRequestStep) handle.project().getTestSuiteByName("S")
                .getTestCaseByName("C").getTestStepByName("Anfrage");
        stepId = step.getId();
    }

    @AfterEach
    void cleanup() {
        if (projectId != null && projectService.find(projectId).isPresent()) {
            lockService.releaseAllOf(projectId);
            projectService.delete(projectId);
        }
    }

    @Test
    void allRegistryTypesAreCreatable() throws Exception {
        setup();
        String[] types = TestAssertionRegistry.getInstance()
                .getAvailableAssertionNames((Assertable) step);
        assertThat(types).isNotEmpty();

        int created = 0;
        for (String label : types) {
            mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions", projectId, stepId)
                            .param("label", label)
                            .with(user(EDITOR).roles("USER")).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("angelegt")));
            created++;
        }
        assertThat(step.getAssertionCount())
                .as("Alle %d Registry-Typen müssen anlegbar sein", types.length)
                .isEqualTo(created);
    }

    @Test
    void xpathComfortFormConfiguresEngineAndRoundtrips() throws Exception {
        setup();
        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions", projectId, stepId)
                        .param("label", XPathContainsAssertion.LABEL)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        String assertionId = step.getAssertionAt(0).getId();

        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions/{a}",
                        projectId, stepId, assertionId)
                        .param("path", "//*[local-name()='EchoResponse']/text()")
                        .param("expectedContent", "ERWARTET")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gespeichert")));

        XPathContainsAssertion assertion = (XPathContainsAssertion) step.getAssertionAt(0);
        assertThat(assertion.getPath()).contains("EchoResponse");
        assertThat(assertion.getExpectedContent()).isEqualTo("ERWARTET");

        // Roundtrip: Konfiguration steht in der Projektdatei
        assertThat(new String(projectService.downloadBytes(projectId)))
                .contains("EchoResponse").contains("ERWARTET");
    }

    @Test
    void containsFormAndDelete() throws Exception {
        setup();
        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions", projectId, stepId)
                        .param("label", SimpleContainsAssertion.LABEL)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        String assertionId = step.getAssertionAt(0).getId();

        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions/{a}",
                        projectId, stepId, assertionId)
                        .param("token", "SUCHTEXT").param("ignoreCase", "true")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("gespeichert")));
        SimpleContainsAssertion assertion = (SimpleContainsAssertion) step.getAssertionAt(0);
        assertThat(assertion.getToken()).isEqualTo("SUCHTEXT");

        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions/{a}/delete",
                        projectId, stepId, assertionId)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("gelöscht")));
        assertThat(step.getAssertionCount()).isZero();
    }

    @Test
    void createWithoutLockIsRejected() throws Exception {
        setup();
        mockMvc.perform(post("/projects/{p}/teststeps/{s}/assertions", projectId, stepId)
                        .param("label", SimpleContainsAssertion.LABEL)
                        .with(user("fremder").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gesperrt")));
        assertThat(step.getAssertionCount()).isZero();
    }
}
