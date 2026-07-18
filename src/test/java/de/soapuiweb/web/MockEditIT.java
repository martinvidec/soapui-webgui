package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.MockManager;
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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Mock-Editing (FA-13): Response-Content-Änderungen persistieren (Roundtrip in
 * die Projektdatei) und wirken am laufenden Mock; XPath-/Script-Dispatch wählt
 * Responses anhand des Request-Inhalts; ohne Edit-Sperre wird abgelehnt.
 */
@SpringBootTest(properties = "app.data-dir=target/mockedit-it-data")
@AutoConfigureMockMvc
class MockEditIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/mockedit-it-data").toFile());
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String EDITOR = "editor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MockManager mockManager;

    @Autowired
    private LockService lockService;

    private String projectId;

    private record Fixture(String projectId, String mockId, String opId, String responseId,
                           int port) {
    }

    private Fixture setupProject() throws Exception {
        int port = freePortInRange();
        WsdlProject project = new WsdlProject();
        project.setName("EditProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());
        WsdlMockService mock = project.addNewMockService("EditMock");
        mock.setPort(port);
        mock.setPath("/edit");
        WsdlMockOperation op = (WsdlMockOperation) mock
                .addNewMockOperation(ifaces[0].getOperationAt(0));
        op.addNewMockResponse("Default", true).setResponseContent(envelope("URSPRUNG"));

        File file = File.createTempFile("edit-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), EDITOR);
        projectId = handle.id();
        lockService.acquire(projectId, EDITOR);

        WsdlProject loaded = handle.project();
        WsdlMockService loadedMock = loaded.getMockServiceByName("EditMock");
        WsdlMockOperation loadedOp = (WsdlMockOperation) loadedMock.getMockOperationAt(0);
        return new Fixture(projectId, loadedMock.getId(), loadedOp.getId(),
                loadedOp.getMockResponseAt(0).getId(), port);
    }

    @AfterEach
    void cleanup() {
        if (projectId != null && projectService.find(projectId).isPresent()) {
            mockManager.stopAllOf(projectId);
            lockService.releaseAllOf(projectId);
            projectService.delete(projectId);
        }
    }

    @Test
    void responseEditPersistsToFileAndServesNewContent() throws Exception {
        Fixture fx = setupProject();

        mockMvc.perform(post("/projects/{p}/mockresponses/{r}", fx.projectId(), fx.responseId())
                        .param("content", envelope("GEAENDERT"))
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gespeichert")));

        // Roundtrip: Änderung steht in der Projektdatei auf Platte (Download = Datei)
        assertThat(new String(projectService.downloadBytes(fx.projectId())))
                .contains("GEAENDERT");

        // Laufender Mock liefert den neuen Content
        mockManager.startMock(fx.projectId(), fx.mockId());
        assertThat(callMock(fx.port(), "egal").body()).contains("GEAENDERT");
    }

    @Test
    void editWithoutLockIsRejected() throws Exception {
        Fixture fx = setupProject();

        mockMvc.perform(post("/projects/{p}/mockresponses/{r}", fx.projectId(), fx.responseId())
                        .param("content", envelope("HACK"))
                        .with(user("andererNutzer").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gesperrt")));

        assertThat(new String(projectService.downloadBytes(fx.projectId())))
                .doesNotContain("HACK");
    }

    @Test
    void xpathDispatchSelectsResponseByRequestContent() throws Exception {
        Fixture fx = setupProject();

        createResponse(fx, "A", envelope("ANTWORT-A"));
        createResponse(fx, "B", envelope("ANTWORT-B"));
        mockMvc.perform(post("/projects/{p}/mockops/{o}/dispatch", fx.projectId(), fx.opId())
                        .param("dispatchStyle", "XPATH")
                        .param("dispatchScript", "//*[local-name()='EchoRequest']/text()")
                        .param("defaultResponse", "Default")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("XPATH")));

        mockManager.startMock(fx.projectId(), fx.mockId());
        assertThat(callMock(fx.port(), "A").body()).contains("ANTWORT-A");
        assertThat(callMock(fx.port(), "B").body()).contains("ANTWORT-B");
    }

    @Test
    void scriptDispatchReturnsNamedResponse() throws Exception {
        Fixture fx = setupProject();

        createResponse(fx, "B", envelope("SKRIPT-B"));
        mockMvc.perform(post("/projects/{p}/mockops/{o}/dispatch", fx.projectId(), fx.opId())
                        .param("dispatchStyle", "SCRIPT")
                        .param("dispatchScript", "return \"B\"")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());

        mockManager.startMock(fx.projectId(), fx.mockId());
        assertThat(callMock(fx.port(), "egal").body()).contains("SKRIPT-B");
    }

    @Test
    void addAndDeleteOperationViaEndpoints() throws Exception {
        Fixture fx = setupProject();
        ProjectHandle handle = projectService.require(fx.projectId());
        String operationId = handle.project().getInterfaceAt(0).getOperationAt(0).getId();
        WsdlMockService mock = (WsdlMockService) de.soapuiweb.engine.ModelItems
                .findById(handle.project(), fx.mockId()).orElseThrow();

        // Duplikat wird abgelehnt (SoapUI: eine MockOperation pro Interface-Operation)
        mockMvc.perform(post("/projects/{p}/mocks/{m}/operations", fx.projectId(), fx.mockId())
                        .param("operationId", operationId)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("existiert bereits")));
        assertThat(mock.getMockOperationCount()).isEqualTo(1);

        // löschen, dann neu anlegen
        mockMvc.perform(post("/projects/{p}/mockops/{o}/delete", fx.projectId(), fx.opId())
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gelöscht")));
        assertThat(mock.getMockOperationCount()).isEqualTo(0);

        mockMvc.perform(post("/projects/{p}/mocks/{m}/operations", fx.projectId(), fx.mockId())
                        .param("operationId", operationId)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("angelegt")));
        assertThat(mock.getMockOperationCount()).isEqualTo(1);
    }

    private void createResponse(Fixture fx, String name, String content) throws Exception {
        mockMvc.perform(post("/projects/{p}/mockops/{o}/responses", fx.projectId(), fx.opId())
                        .param("name", name)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        ProjectHandle handle = projectService.require(fx.projectId());
        WsdlMockOperation op = (WsdlMockOperation) de.soapuiweb.engine.ModelItems
                .findById(handle.project(), fx.opId()).orElseThrow();
        String responseId = op.getMockResponseByName(name).getId();
        mockMvc.perform(post("/projects/{p}/mockresponses/{r}", fx.projectId(), responseId)
                        .param("content", content)
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
    }

    private static HttpResponse<String> callMock(int port, String echoText) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/edit"))
                        .header("Content-Type", "text/xml; charset=utf-8")
                        .header("SOAPAction", "\"http://example.com/echo/Echo\"")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">" + echoText
                                + "</EchoRequest></soapenv:Body></soapenv:Envelope>")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String envelope(String text) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">" + text
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>";
    }

    private static int freePortInRange() {
        for (int port = 18500; port <= 18999; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (Exception ignored) {
                // belegt
            }
        }
        throw new IllegalStateException("Kein freier Port gefunden");
    }
}
