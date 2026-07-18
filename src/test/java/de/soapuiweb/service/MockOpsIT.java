package de.soapuiweb.service;

import com.eviware.soapui.impl.rest.RestRequestInterface;
import com.eviware.soapui.impl.rest.mock.RestMockAction;
import com.eviware.soapui.impl.rest.mock.RestMockService;
import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockResponse;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock-Betrieb end-to-end mit echten Ports (FA-11/12/14/15/16): SOAP- und
 * REST-Mock antworten auf reale HTTP-Requests, Portbereich/-konflikte werden
 * verständlich gemeldet, Autostart übersteht einen simulierten Neustart.
 */
@SpringBootTest(properties = "app.data-dir=target/mock-it-data")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MockOpsIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/mock-it-data").toFile());
    }

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MockManager mockManager;

    @Autowired
    private EventStreamService eventStreamService;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private record Fixture(String projectId, String soapMockId, String restMockId,
                           String badPortMockId, int soapPort, int restPort) {
    }

    private Fixture buildAndUploadFixture() throws Exception {
        int soapPort = freePortInRange();
        int restPort = freePortInRange(soapPort + 1);

        WsdlProject project = new WsdlProject();
        project.setName("MockProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());

        WsdlMockService soapMock = project.addNewMockService("SoapMock");
        soapMock.setPort(soapPort);
        soapMock.setPath("/soap");
        WsdlMockOperation op = (WsdlMockOperation) soapMock
                .addNewMockOperation(ifaces[0].getOperationAt(0));
        WsdlMockResponse response = op.addNewMockResponse("Default", true);
        response.setResponseContent("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">SOAP-MOCK-OK"
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>");

        RestMockService restMock = project.addNewRestMockService("RestMock");
        restMock.setPort(restPort);
        restMock.setPath("/rest");
        RestMockAction action = restMock.addEmptyMockAction(
                RestRequestInterface.HttpMethod.GET, "/ping");
        action.addNewMockResponse("Pong").setResponseContent("pong");

        WsdlMockService badPortMock = project.addNewMockService("BadPortMock");
        badPortMock.setPort(1234);

        File file = File.createTempFile("mock-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        byte[] bytes = Files.readAllBytes(file.toPath());
        ProjectHandle handle = projectService.upload(bytes, "mocktester");

        WsdlProject loaded = handle.project();
        return new Fixture(handle.id(),
                loaded.getMockServiceByName("SoapMock").getId(),
                loaded.getRestMockServiceByName("RestMock").getId(),
                loaded.getMockServiceByName("BadPortMock").getId(),
                soapPort, restPort);
    }

    @Test
    @Order(1)
    void soapAndRestMockLifecycleWithLogAndPortRelease() throws Exception {
        Fixture fx = buildAndUploadFixture();

        mockManager.startMock(fx.projectId(), fx.soapMockId());
        mockManager.startMock(fx.projectId(), fx.restMockId());
        assertThat(mockManager.runningCountOf(fx.projectId())).isEqualTo(2);

        // SOAP-Mock beantwortet echten HTTP-Request (FA-11)
        HttpResponse<String> soap = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + fx.soapPort() + "/soap"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"http://example.com/echo/Echo\"")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">Hi"
                        + "</EchoRequest></soapenv:Body></soapenv:Envelope>")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(soap.statusCode()).isEqualTo(200);
        assertThat(soap.body()).contains("SOAP-MOCK-OK");

        // Request-Log-Event landet im Ring-Puffer (FA-12, < 2 s)
        long deadline = System.currentTimeMillis() + 2000;
        while (eventStreamService.eventsAfter(fx.soapMockId(), 0).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(eventStreamService.eventsAfter(fx.soapMockId(), 0))
                .as("Mock-Request muss binnen 2 s im Log-Puffer sein")
                .isNotEmpty()
                .anySatisfy(e -> assertThat(e.html()).contains("Echo"));

        // REST-Mock (FA-16)
        HttpResponse<String> rest = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + fx.restPort() + "/rest/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(200);
        assertThat(rest.body()).contains("pong");

        // Stop gibt Ports frei (FA-11) — Jetty schließt asynchron, kurz warten
        mockManager.stopMock(fx.soapMockId());
        mockManager.stopMock(fx.restMockId());
        awaitPortFree(fx.soapPort());

        // erneutes Binden funktioniert
        mockManager.startMock(fx.projectId(), fx.soapMockId());
        mockManager.stopMock(fx.soapMockId());

        projectService.delete(fx.projectId());
    }

    @Test
    @Order(2)
    void portValidationAndConflictMessages() throws Exception {
        Fixture fx = buildAndUploadFixture();

        // Port außerhalb des Bereichs (FA-15)
        assertThatThrownBy(() -> mockManager.startMock(fx.projectId(), fx.badPortMockId()))
                .hasMessageContaining("außerhalb")
                .hasMessageContaining("1234");

        // Registry-Konflikt nennt Verursacher (FA-15)
        mockManager.startMock(fx.projectId(), fx.soapMockId());
        ProjectHandle handle = projectService.require(fx.projectId());
        WsdlMockService second = handle.project().addNewMockService("ZweiterMock");
        second.setPort(fx.soapPort());
        assertThatThrownBy(() -> mockManager.startMock(fx.projectId(), second.getId()))
                .hasMessageContaining("SoapMock")
                .hasMessageContaining("MockProjekt");
        mockManager.stopMock(fx.soapMockId());
        awaitPortFree(fx.soapPort());

        // extern belegter Port (FA-15)
        try (ServerSocket blocker = new ServerSocket(fx.soapPort())) {
            assertThatThrownBy(() -> mockManager.startMock(fx.projectId(), fx.soapMockId()))
                    .hasMessageContaining("bereits belegt");
        }

        projectService.delete(fx.projectId());
    }

    @Test
    @Order(3)
    void autostartSurvivesRestartAndFailuresDoNotBlock() throws Exception {
        Fixture fx = buildAndUploadFixture();

        projectService.setAutostart(fx.projectId(), fx.soapMockId(), true);
        projectService.setAutostart(fx.projectId(), fx.badPortMockId(), true);
        assertThat(new String(Files.readAllBytes(
                Path.of("target/mock-it-data/projects", fx.projectId(), "meta.json"))))
                .contains(fx.soapMockId());

        // Neustart simulieren: MockManager-Lifecycle stop/start
        mockManager.stop();
        assertThat(mockManager.isMockRunning(fx.soapMockId())).isFalse();
        mockManager.start();

        // valider Autostart-Mock läuft, kaputter erzeugt nur eine Warnung (FA-14)
        assertThat(mockManager.isMockRunning(fx.soapMockId())).isTrue();
        assertThat(mockManager.isMockRunning(fx.badPortMockId())).isFalse();
        assertThat(mockManager.autostartWarnings())
                .anySatisfy(w -> assertThat(w).contains("MockProjekt"));

        // Abwahl wirkt nach erneutem Restart
        projectService.setAutostart(fx.projectId(), fx.soapMockId(), false);
        mockManager.stop();
        mockManager.start();
        assertThat(mockManager.isMockRunning(fx.soapMockId())).isFalse();

        mockManager.stopAllOf(fx.projectId());
        projectService.delete(fx.projectId());
    }

    /** Wartet bis der Port wieder bindbar ist (Jetty-Stop ist asynchron). */
    private static void awaitPortFree(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress(port));
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private static int freePortInRange() {
        return freePortInRange(18100);
    }

    private static int freePortInRange(int from) {
        for (int port = from; port <= 18999; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (Exception ignored) {
                // belegt, nächsten versuchen
            }
        }
        throw new IllegalStateException("Kein freier Port im Bereich gefunden");
    }
}
