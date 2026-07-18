package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestRequestStep;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.SimpleContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.XPathContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.registry.DelayStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.WsdlTestRequestStepFactory;
import de.soapuiweb.service.MockManager;
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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testausführung (FA-33/34/36, NFA-11): Lauf mit Live-Ergebnissen inkl.
 * Request/Response und Property-Expansion, fehlschlagende Assertion mit
 * Fehlertext, Abbruch, ein Lauf pro Projekt, parallele Läufe verschiedener
 * Projekte.
 */
@SpringBootTest(properties = "app.data-dir=target/testrun-it-data")
@AutoConfigureMockMvc
class TestRunIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/testrun-it-data").toFile());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MockManager mockManager;

    @Autowired
    private TestRunManager runManager;

    private final List<String> createdProjects = new ArrayList<>();

    private record Fixture(String projectId, String mockId, String passCaseId,
                           String failCaseId, String slowCaseId) {
    }

    private Fixture createProject(String name, int port) throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName(name);
        project.setPropertyValue("marker", "MARKER-123");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());
        WsdlOperation operation = ifaces[0].getOperationAt(0);
        WsdlRequest request = operation.addNewRequest("Basis");
        request.setRequestContent(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">${#Project#marker}"
                + "</EchoRequest></soapenv:Body></soapenv:Envelope>");
        request.setEndpoint("http://localhost:" + port + "/mock");

        WsdlMockService mock = project.addNewMockService("RunMock");
        mock.setPort(port);
        mock.setPath("/mock");
        WsdlMockOperation mockOp = (WsdlMockOperation) mock.addNewMockOperation(operation);
        mockOp.addNewMockResponse("Default", true).setResponseContent(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">MOCK-OK"
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>");

        WsdlTestSuite suite = project.addNewTestSuite("Suite");

        WsdlTestCase pass = suite.addNewTestCase("Pass");
        pass.addTestStep(WsdlTestRequestStepFactory.createConfig(request, "Anfrage"));
        XPathContainsAssertion xpath = (XPathContainsAssertion)
                ((WsdlTestRequestStep) pass.getTestStepByName("Anfrage"))
                        .addAssertion(XPathContainsAssertion.LABEL);
        xpath.setPath("//*[local-name()='EchoResponse']/text()");
        xpath.setExpectedContent("MOCK-OK");

        WsdlTestCase fail = suite.addNewTestCase("Fail");
        fail.addTestStep(WsdlTestRequestStepFactory.createConfig(request, "Anfrage"));
        SimpleContainsAssertion contains = (SimpleContainsAssertion)
                ((WsdlTestRequestStep) fail.getTestStepByName("Anfrage"))
                        .addAssertion(SimpleContainsAssertion.LABEL);
        contains.setToken("GIBTSNICHT");

        WsdlTestCase slow = suite.addNewTestCase("Slow");
        var delay = (com.eviware.soapui.impl.wsdl.teststeps.WsdlDelayTestStep)
                slow.addTestStep(DelayStepFactory.DELAY_TYPE, "Warten");
        delay.setDelay(15000);

        File file = File.createTempFile("run-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), "runner");
        createdProjects.add(handle.id());

        WsdlProject loaded = handle.project();
        WsdlTestSuite loadedSuite = loaded.getTestSuiteByName("Suite");
        return new Fixture(handle.id(),
                loaded.getMockServiceByName("RunMock").getId(),
                loadedSuite.getTestCaseByName("Pass").getId(),
                loadedSuite.getTestCaseByName("Fail").getId(),
                loadedSuite.getTestCaseByName("Slow").getId());
    }

    @AfterEach
    void cleanup() {
        for (String id : createdProjects) {
            if (projectService.find(id).isPresent()) {
                mockManager.stopAllOf(id);
                projectService.delete(id);
            }
        }
        createdProjects.clear();
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

    @Test
    void caseRunShowsResultsWithExpandedPropertyAndPassingAssertion() throws Exception {
        Fixture fx = createProject("RunProjekt", freePort(18820));
        mockManager.startMock(fx.projectId(), fx.mockId());

        TestRunManager.ActiveRun run = runManager.startTestCase(fx.projectId(), fx.passCaseId());
        await(() -> !run.isRunning(), 15000, "Lauf beendet");

        assertThat(run.status()).isEqualTo("FINISHED");
        assertThat(run.results()).hasSize(1);
        TestRunManager.StepResultRow row = run.results().get(0);
        assertThat(row.status()).isEqualTo("OK");
        assertThat(row.requestContent())
                .as("Property-Expansion muss beim Lauf den Projektwert einsetzen (FA-35)")
                .contains("MARKER-123")
                .doesNotContain("${#Project#marker}");
        assertThat(row.responseContent()).contains("MOCK-OK");

        // Ergebnis-Fragment über den Endpunkt (FA-34)
        mockMvc.perform(get("/runs/{r}/results", run.runId())
                        .with(user("runner").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FINISHED")))
                .andExpect(content().string(containsString("Anfrage")))
                .andExpect(content().string(containsString("MOCK-OK")));
    }

    @Test
    void failingAssertionShowsErrorText() throws Exception {
        Fixture fx = createProject("FailProjekt", freePort(18830));
        mockManager.startMock(fx.projectId(), fx.mockId());

        TestRunManager.ActiveRun run = runManager.startTestCase(fx.projectId(), fx.failCaseId());
        await(() -> !run.isRunning(), 15000, "Lauf beendet");

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.results().get(0).status()).isEqualTo("FAILED");
        assertThat(String.join(" ", run.results().get(0).messages()))
                .contains("GIBTSNICHT");

        mockMvc.perform(get("/runs/{r}/results", run.runId())
                        .with(user("runner").roles("USER")))
                .andExpect(content().string(containsString("FAILED")))
                .andExpect(content().string(containsString("GIBTSNICHT")));
    }

    @Test
    void runCanBeCancelled() throws Exception {
        Fixture fx = createProject("CancelProjekt", freePort(18840));

        TestRunManager.ActiveRun run = runManager.startTestCase(fx.projectId(), fx.slowCaseId());
        await(run::isRunning, 2000, "Lauf gestartet");

        mockMvc.perform(post("/runs/{r}/cancel", run.runId())
                        .with(user("runner").roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        await(() -> !run.isRunning(), 8000, "Lauf abgebrochen");
        assertThat(run.status()).isEqualTo("CANCELED");
    }

    @Test
    void secondRunInSameProjectIsRejectedWhileParallelProjectsWork() throws Exception {
        Fixture fx1 = createProject("Parallel1", freePort(18850));
        Fixture fx2 = createProject("Parallel2", freePort(18860));
        mockManager.startMock(fx1.projectId(), fx1.mockId());
        mockManager.startMock(fx2.projectId(), fx2.mockId());

        TestRunManager.ActiveRun slow = runManager.startTestCase(fx1.projectId(), fx1.slowCaseId());

        // zweiter Lauf im selben Projekt -> Ablehnung mit Meldung (FA-33)
        mockMvc.perform(post("/projects/{p}/testcases/{c}/run", fx1.projectId(), fx1.passCaseId())
                        .with(user("runner").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("läuft bereits")));

        // paralleler Lauf im ANDEREN Projekt funktioniert (NFA-11)
        TestRunManager.ActiveRun other = runManager.startTestCase(fx2.projectId(), fx2.passCaseId());
        await(() -> !other.isRunning(), 15000, "Parallel-Lauf beendet");
        assertThat(other.status()).isEqualTo("FINISHED");

        runManager.cancel(slow.runId());
        await(() -> !slow.isRunning(), 8000, "Slow-Lauf abgebrochen");
    }

    private static int freePort(int from) {
        for (int port = from; port <= 18999; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (Exception ignored) {
                // belegt
            }
        }
        throw new IllegalStateException("Kein freier Port gefunden");
    }
}
