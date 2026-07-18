package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory;
import de.soapuiweb.service.EventStreamService;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import de.soapuiweb.service.TestRunManager;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Groovy-Vervollständigung (FA-40/41): log-Ausgaben aus Skripten landen im
 * Lauf-Feed (Log-Bridge), Setup-/TearDown-Skripte sind über die Endpunkte
 * editierbar und werden beim Lauf ausgeführt, Skriptfehler erscheinen als
 * verständliche Step-Meldung, Mock-Skripte sind editierbar.
 */
@SpringBootTest(properties = "app.data-dir=target/groovy-it-data")
@AutoConfigureMockMvc
class GroovyIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/groovy-it-data").toFile());
    }

    private static final String EDITOR = "editor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TestRunManager runManager;

    @Autowired
    private EventStreamService events;

    @Autowired
    private LockService lockService;

    private String projectId;

    private ProjectHandle setup(String groovyScript) throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("GroovyProjekt");
        project.addNewMockService("LeererMock");
        WsdlTestCase testCase = project.addNewTestSuite("Suite").addNewTestCase("Case");
        WsdlGroovyScriptTestStep step = (WsdlGroovyScriptTestStep)
                testCase.addTestStep(GroovyScriptStepFactory.GROOVY_TYPE, "Skript");
        step.setScript(groovyScript);

        File file = File.createTempFile("groovy-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), EDITOR);
        projectId = handle.id();
        lockService.acquire(projectId, EDITOR);
        return handle;
    }

    @AfterEach
    void cleanup() {
        if (projectId != null && projectService.find(projectId).isPresent()) {
            lockService.releaseAllOf(projectId);
            projectService.delete(projectId);
        }
    }

    private static void await(Supplier<Boolean> condition, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timeout: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String runAndCollectEvents(String caseId) {
        TestRunManager.ActiveRun run = runManager.startTestCase(projectId, caseId);
        await(() -> !run.isRunning(), 15000, "Lauf beendet");
        return events.eventsAfter(run.runId(), 0).stream()
                .map(EventStreamService.LogEvent::html)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void groovyLogAppearsInRunFeed() throws Exception {
        ProjectHandle handle = setup("log.info 'HALLO-AUS-GROOVY'");
        String caseId = handle.project().getTestSuiteByName("Suite")
                .getTestCaseByName("Case").getId();

        String feed = runAndCollectEvents(caseId);
        assertThat(feed)
                .as("log.info aus dem Groovy-Step muss im Lauf-Feed erscheinen (FA-41)")
                .contains("HALLO-AUS-GROOVY")
                .contains("Skript");
    }

    @Test
    void setupAndTearDownEditableViaEndpointsAndExecuted() throws Exception {
        ProjectHandle handle = setup("log.info 'STEP-LOG'");
        WsdlTestCase testCase = handle.project().getTestSuiteByName("Suite")
                .getTestCaseByName("Case");

        mockMvc.perform(post("/projects/{p}/scripts/{h}", projectId, testCase.getId())
                        .param("scriptType", "setup")
                        .param("script", "log.info 'SETUP-MARKER'")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gespeichert")));
        mockMvc.perform(post("/projects/{p}/scripts/{h}", projectId, testCase.getId())
                        .param("scriptType", "teardown")
                        .param("script", "log.info 'TEARDOWN-MARKER'")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());

        assertThat(testCase.getSetupScript()).contains("SETUP-MARKER");
        assertThat(new String(projectService.downloadBytes(projectId)))
                .contains("SETUP-MARKER").contains("TEARDOWN-MARKER");

        String feed = runAndCollectEvents(testCase.getId());
        assertThat(feed)
                .as("Setup/TearDown-Skripte müssen beim Lauf ausgeführt werden")
                .contains("SETUP-MARKER")
                .contains("TEARDOWN-MARKER");
    }

    @Test
    void suiteAndProjectScriptsEditable() throws Exception {
        ProjectHandle handle = setup("log.info 'x'");
        WsdlTestSuite suite = handle.project().getTestSuiteByName("Suite");

        mockMvc.perform(post("/projects/{p}/scripts/{h}", projectId, suite.getId())
                        .param("scriptType", "setup").param("script", "log.info 'SUITE-SETUP'")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(suite.getSetupScript()).contains("SUITE-SETUP");

        mockMvc.perform(post("/projects/{p}/scripts/{h}", projectId, handle.project().getId())
                        .param("scriptType", "afterLoad").param("script", "log.info 'LOAD'")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(handle.project().getAfterLoadScript()).contains("LOAD");
    }

    @Test
    void mockScriptsEditableViaEndpoint() throws Exception {
        ProjectHandle handle = setup("log.info 'x'");
        var mock = handle.project().getMockServiceByName("LeererMock");

        mockMvc.perform(post("/projects/{p}/scripts/{h}", projectId, mock.getId())
                        .param("scriptType", "start").param("script", "log.info 'MOCK-START'")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gespeichert")));
        assertThat(mock.getStartScript()).contains("MOCK-START");
        assertThat(new String(projectService.downloadBytes(projectId))).contains("MOCK-START");
    }

    @Test
    void scriptErrorShownAsStepMessageNotStacktracePage() throws Exception {
        ProjectHandle handle = setup("das ist ((( kein groovy !!");
        String caseId = handle.project().getTestSuiteByName("Suite")
                .getTestCaseByName("Case").getId();

        TestRunManager.ActiveRun run = runManager.startTestCase(projectId, caseId);
        await(() -> !run.isRunning(), 15000, "Lauf beendet");

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.results()).hasSize(1);
        assertThat(String.join(" ", run.results().get(0).messages()))
                .as("Skriptfehler muss als verständliche Meldung am Step stehen")
                .isNotBlank();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/runs/{r}/results", run.runId())
                        .with(user(EDITOR).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FAILED")));
    }
}
