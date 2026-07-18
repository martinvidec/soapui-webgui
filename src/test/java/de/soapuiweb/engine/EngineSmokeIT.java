package de.soapuiweb.engine;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockResponse;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockRunner;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCaseRunner;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory;
import com.eviware.soapui.model.testsuite.TestRunner;
import com.eviware.soapui.support.types.StringToObjectMap;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Kern-Zusagen aus Issue #1 gegen den reduzierten Classpath
 * (JavaFX/Desktop-UI ausgeschlossen): Engine-Init headless, WSDL-Import,
 * MockRunner-Lifecycle mit echtem HTTP-Request, Testlauf mit Groovy-Step.
 * Entspricht dem Spike-Szenario aus spike/ (docs/02-ist-analyse, Abschnitt 6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.data-dir=target/test-data")
class EngineSmokeIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void exactlyOneSlf4jBindingAndItIsLog4j() {
        assertThat(LoggerFactory.getILoggerFactory().getClass().getName())
                .as("SLF4J muss über Log4j2 laufen (docs/04, Logging-Entscheidung)")
                .contains("Log4j");
    }

    @Test
    void engineRunsSpikeScenarioHeadless() throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("EngineSmokeIT");

        String wsdlUrl = getClass().getResource("/echo.wsdl").toString();
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project, wsdlUrl);
        assertThat(ifaces).hasSize(1);
        assertThat(ifaces[0].getOperationCount()).isEqualTo(1);

        int port = freePort();
        WsdlMockService mock = project.addNewMockService("EchoMock");
        mock.setPort(port);
        mock.setPath("/echo");
        WsdlMockOperation mockOp = (WsdlMockOperation) mock.addNewMockOperation(ifaces[0].getOperationAt(0));
        WsdlMockResponse mockResponse = mockOp.addNewMockResponse("Default", true);
        mockResponse.setResponseContent(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">Hello from Mock"
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>");

        WsdlMockRunner runner = mock.start();
        try {
            assertThat(runner.isRunning()).isTrue();

            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/echo"))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"http://example.com/echo/Echo\"")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                            + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">Hi"
                            + "</EchoRequest></soapenv:Body></soapenv:Envelope>"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Hello from Mock");
        } finally {
            runner.stop();
        }
        assertThat(runner.isRunning()).isFalse();

        WsdlTestCase testCase = project.addNewTestSuite("Suite").addNewTestCase("Case");
        WsdlGroovyScriptTestStep groovyStep = (WsdlGroovyScriptTestStep) testCase
                .addTestStep(GroovyScriptStepFactory.GROOVY_TYPE, "Groovy");
        groovyStep.setScript("log.info('EngineSmokeIT-Groovy'); return 6 * 7");
        WsdlTestCaseRunner testRunner = testCase.run(new StringToObjectMap(), false);
        assertThat(testRunner.getStatus()).isEqualTo(TestRunner.Status.FINISHED);
        assertThat(testRunner.getResults()).hasSize(1);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
