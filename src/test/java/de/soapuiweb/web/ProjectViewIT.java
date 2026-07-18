package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory;
import com.eviware.soapui.model.ModelItem;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectService;
import org.junit.jupiter.api.BeforeAll;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.data-dir=target/view-it-data")
@AutoConfigureMockMvc
class ProjectViewIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/view-it-data").toFile());
    }

    private static byte[] referenceProject;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private LockService lockService;

    /**
     * Referenzprojekt mit allen Baum-Elementtypen: Interface mit Operation und
     * Request, TestSuite mit TestCase und Groovy-Step, SOAP-Mock mit Operation
     * und Response, REST-MockService.
     */
    @BeforeAll
    static void buildReferenceProject(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("BaumProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                ProjectViewIT.class.getResource("/echo.wsdl").toString());
        ifaces[0].getOperationAt(0).addNewRequest("Request 1");

        WsdlTestCase testCase = project.addNewTestSuite("Suite").addNewTestCase("Case");
        testCase.addTestStep(GroovyScriptStepFactory.GROOVY_TYPE, "GroovyStep");

        WsdlMockOperation mockOp = (WsdlMockOperation) project.addNewMockService("SoapMock")
                .addNewMockOperation(ifaces[0].getOperationAt(0));
        mockOp.addNewMockResponse("Antwort 1", true);
        project.addNewRestMockService("RestMock");

        File file = tempDir.resolve("baum-projekt.xml").toFile();
        project.saveAs(file.getAbsolutePath());
        project.release();
        referenceProject = Files.readAllBytes(file.toPath());
    }

    private String uploadProject() throws Exception {
        java.util.Set<String> before = projectService.list().stream()
                .map(de.soapuiweb.service.ProjectHandle::id)
                .collect(java.util.stream.Collectors.toSet());
        mockMvc.perform(multipart("/projects")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "baum.xml", "text/xml", referenceProject))
                        .with(user("usera").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return projectService.list().stream()
                .map(de.soapuiweb.service.ProjectHandle::id)
                .filter(id -> !before.contains(id))
                .findFirst().orElseThrow();
    }

    private String itemId(String projectId, String name) {
        WsdlProject project = projectService.require(projectId).project();
        return findByName(project, name).getId();
    }

    private static ModelItem findByName(ModelItem root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (ModelItem child : ModelItems.childrenOf(root)) {
            try {
                return findByName(child, name);
            } catch (IllegalStateException ignored) {
                // in diesem Teilbaum nicht gefunden
            }
        }
        throw new IllegalStateException("Nicht gefunden: " + name);
    }

    @Test
    void workViewShowsAllRootElementTypes() throws Exception {
        String id = uploadProject();
        mockMvc.perform(get("/projects/{id}", id).with(user("usera").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BaumProjekt")))
                .andExpect(content().string(containsString("EchoBinding")))
                .andExpect(content().string(containsString("Suite")))
                .andExpect(content().string(containsString("SoapMock")))
                .andExpect(content().string(containsString("RestMock")))
                .andExpect(content().string(containsString("Sperre übernehmen")));
        projectService.delete(id);
    }

    @Test
    void treeLoadsAllLevelsLazily() throws Exception {
        String id = uploadProject();

        mockMvc.perform(get("/projects/{id}/tree/{item}", id, itemId(id, "EchoBinding"))
                        .with(user("usera").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Echo")));
        mockMvc.perform(get("/projects/{id}/tree/{item}", id, itemId(id, "Echo"))
                        .with(user("usera").roles("USER")))
                .andExpect(content().string(containsString("Request 1")));
        mockMvc.perform(get("/projects/{id}/tree/{item}", id, itemId(id, "Suite"))
                        .with(user("usera").roles("USER")))
                .andExpect(content().string(containsString("Case")));
        mockMvc.perform(get("/projects/{id}/tree/{item}", id, itemId(id, "Case"))
                        .with(user("usera").roles("USER")))
                .andExpect(content().string(containsString("GroovyStep")));
        mockMvc.perform(get("/projects/{id}/tree/{item}", id, itemId(id, "SoapMock"))
                        .with(user("usera").roles("USER")))
                .andExpect(content().string(containsString("Echo")));

        projectService.delete(id);
    }

    @Test
    void detailPanelShowsTypeAndName() throws Exception {
        String id = uploadProject();
        mockMvc.perform(get("/projects/{id}/items/{item}", id, itemId(id, "Case"))
                        .with(user("usera").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Case")))
                .andExpect(content().string(containsString("TestCase")));
        projectService.delete(id);
    }

    @Test
    void unknownItemReturns404() throws Exception {
        String id = uploadProject();
        mockMvc.perform(get("/projects/{id}/items/{item}", id, "gibt-es-nicht")
                        .with(user("usera").roles("USER")))
                .andExpect(status().isNotFound());
        projectService.delete(id);
    }

    @Test
    void lockFlowWithForceUnlock() throws Exception {
        String id = uploadProject();

        // A übernimmt die Sperre
        mockMvc.perform(post("/projects/{id}/lock", id)
                        .with(user("usera").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lockService.ownerOf(id)).contains("usera");

        // B sieht read-only
        mockMvc.perform(get("/projects/{id}", id).with(user("userb").roles("USER")))
                .andExpect(content().string(containsString("gesperrt von")))
                .andExpect(content().string(containsString("usera")));

        // B kann nicht löschen (Mutations-Guard)
        mockMvc.perform(post("/projects/{id}/delete", id)
                        .with(user("userb").roles("USER")).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(projectService.find(id)).isPresent();

        // B kann die Sperre nicht regulär übernehmen
        mockMvc.perform(post("/projects/{id}/lock", id)
                        .with(user("userb").roles("USER")).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(lockService.ownerOf(id)).contains("usera");

        // force=true ohne ADMIN-Rolle bricht die Sperre NICHT
        mockMvc.perform(post("/projects/{id}/unlock?force=true", id)
                        .with(user("userb").roles("USER")).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(lockService.ownerOf(id)).contains("usera");

        // Admin bricht die Sperre, danach kann B übernehmen
        mockMvc.perform(post("/projects/{id}/unlock?force=true", id)
                        .with(user("chef").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lockService.ownerOf(id)).isEmpty();
        mockMvc.perform(post("/projects/{id}/lock", id)
                        .with(user("userb").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lockService.ownerOf(id)).contains("userb");

        lockService.releaseAllOf(id);
        projectService.delete(id);
    }
}
