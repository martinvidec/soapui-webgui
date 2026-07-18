package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
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
 * Request-Editor (FA-20–23): Beispiel-Request-Generierung, Speichern nur mit
 * Sperre, Senden gegen den laufenden Mock (Status/Zeit im Panel), Endpoint-
 * Wechsel wirkt beim nächsten Submit, Clone/Rename/Delete.
 */
@SpringBootTest(properties = "app.data-dir=target/requestedit-it-data")
@AutoConfigureMockMvc
class RequestEditIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/requestedit-it-data").toFile());
    }

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

    private record Fixture(String projectId, String opId, String requestId, String mockId,
                           int mockPort) {
    }

    private Fixture setup() throws Exception {
        int port = freePort(18700);
        WsdlProject project = new WsdlProject();
        project.setName("RequestProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                getClass().getResource("/echo.wsdl").toString());
        WsdlOperation operation = ifaces[0].getOperationAt(0);
        WsdlRequest request = operation.addNewRequest("Basis");
        request.setRequestContent(operation.createRequest(true));
        request.setEndpoint("http://localhost:" + port + "/mock");

        WsdlMockService mock = project.addNewMockService("EchoMock");
        mock.setPort(port);
        mock.setPath("/mock");
        WsdlMockOperation mockOp = (WsdlMockOperation) mock.addNewMockOperation(operation);
        mockOp.addNewMockResponse("Default", true).setResponseContent(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">MOCK-ANTWORT"
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>");

        File file = File.createTempFile("request-fixture", ".xml");
        project.saveAs(file.getAbsolutePath());
        project.release();
        ProjectHandle handle = projectService.upload(Files.readAllBytes(file.toPath()), EDITOR);
        projectId = handle.id();
        lockService.acquire(projectId, EDITOR);

        WsdlProject loaded = handle.project();
        WsdlOperation loadedOp = (WsdlOperation) loaded.getInterfaceAt(0).getOperationAt(0);
        return new Fixture(projectId, loadedOp.getId(), loadedOp.getRequestAt(0).getId(),
                loaded.getMockServiceByName("EchoMock").getId(), port);
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
    void createRequestWithGeneratedSample() throws Exception {
        Fixture fx = setup();
        mockMvc.perform(post("/projects/{p}/operations/{o}/requests", fx.projectId(), fx.opId())
                        .param("name", "Neu").param("withSample", "true")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("angelegt")));

        WsdlOperation op = (WsdlOperation) de.soapuiweb.engine.ModelItems
                .findById(projectService.require(fx.projectId()).project(), fx.opId()).orElseThrow();
        WsdlRequest created = op.getRequestByName("Neu");
        assertThat(created.getRequestContent())
                .as("Beispiel-Request muss aus dem Schema generiert sein (FA-23)")
                .contains("EchoRequest");
    }

    @Test
    void submitAgainstRunningMockShowsStatusTimeAndBody() throws Exception {
        Fixture fx = setup();
        mockManager.startMock(fx.projectId(), fx.mockId());

        mockMvc.perform(post("/projects/{p}/requests/{r}/submit", fx.projectId(), fx.requestId())
                        // Senden ist bewusst ohne Sperre erlaubt (AK)
                        .with(user("nurLeser").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HTTP 200")))
                .andExpect(content().string(containsString("MOCK-ANTWORT")))
                .andExpect(content().string(containsString(" ms")));
    }

    @Test
    void endpointSwitchTakesEffectOnNextSubmit() throws Exception {
        Fixture fx = setup();
        mockManager.startMock(fx.projectId(), fx.mockId());
        String sample = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">Hi"
                + "</EchoRequest></soapenv:Body></soapenv:Envelope>";

        // Endpoint auf toten Port -> Submit-Fehler
        int deadPort = freePort(18900);
        mockMvc.perform(post("/projects/{p}/requests/{r}", fx.projectId(), fx.requestId())
                        .param("content", sample)
                        .param("endpoint", "http://localhost:" + deadPort + "/mock")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("gespeichert")));
        mockMvc.perform(post("/projects/{p}/requests/{r}/submit", fx.projectId(), fx.requestId())
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("alert-error")));

        // zurück auf den Mock -> HTTP 200
        mockMvc.perform(post("/projects/{p}/requests/{r}", fx.projectId(), fx.requestId())
                        .param("content", sample)
                        .param("endpoint", "http://localhost:" + fx.mockPort() + "/mock")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("gespeichert")));
        mockMvc.perform(post("/projects/{p}/requests/{r}/submit", fx.projectId(), fx.requestId())
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("HTTP 200")));
    }

    @Test
    void saveWithoutLockIsRejectedAndNotPersisted() throws Exception {
        Fixture fx = setup();
        mockMvc.perform(post("/projects/{p}/requests/{r}", fx.projectId(), fx.requestId())
                        .param("content", "HACK").param("endpoint", "")
                        .with(user("fremder").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gesperrt")));
        assertThat(new String(projectService.downloadBytes(fx.projectId())))
                .doesNotContain("HACK");
    }

    @Test
    void cloneRenameDelete() throws Exception {
        Fixture fx = setup();
        WsdlOperation op = (WsdlOperation) de.soapuiweb.engine.ModelItems
                .findById(projectService.require(fx.projectId()).project(), fx.opId()).orElseThrow();

        mockMvc.perform(post("/projects/{p}/requests/{r}/clone", fx.projectId(), fx.requestId())
                        .param("name", "Kopie")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("geklont")));
        assertThat(op.getRequestCount()).isEqualTo(2);
        assertThat(op.getRequestByName("Kopie").getRequestContent())
                .isEqualTo(op.getRequestByName("Basis").getRequestContent());

        mockMvc.perform(post("/projects/{p}/requests/{r}/rename", fx.projectId(),
                        op.getRequestByName("Kopie").getId())
                        .param("name", "Umbenannt")
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("Umbenannt")));
        assertThat(op.getRequestByName("Umbenannt")).isNotNull();

        mockMvc.perform(post("/projects/{p}/requests/{r}/delete", fx.projectId(),
                        op.getRequestByName("Umbenannt").getId())
                        .with(user(EDITOR).roles("USER")).with(csrf()))
                .andExpect(content().string(containsString("gelöscht")));
        assertThat(op.getRequestCount()).isEqualTo(1);
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
