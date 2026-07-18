package de.soapuiweb.web;

import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import de.soapuiweb.service.ProjectService;
import de.soapuiweb.storage.ProjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.data-dir=target/project-it-data")
@AutoConfigureMockMvc
class ProjectWebIT {

    static {
        FileSystemUtils.deleteRecursively(Path.of("target/project-it-data").toFile());
    }

    private static byte[] referenceProject;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectStore projectStore;

    /** Referenzprojekt mit der echten Engine erzeugen (WSDL-Interface + MockService). */
    @BeforeAll
    static void buildReferenceProject(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        WsdlProject project = new WsdlProject();
        project.setName("RefProjekt");
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                ProjectWebIT.class.getResource("/echo.wsdl").toString());
        project.addNewMockService("RefMock").addNewMockOperation(ifaces[0].getOperationAt(0));
        File file = tempDir.resolve("ref-project.xml").toFile();
        project.saveAs(file.getAbsolutePath());
        project.release();
        referenceProject = Files.readAllBytes(file.toPath());
    }

    private String upload() throws Exception {
        java.util.Set<String> before = projectService.list().stream()
                .map(de.soapuiweb.service.ProjectHandle::id)
                .collect(java.util.stream.Collectors.toSet());
        mockMvc.perform(multipart("/projects")
                        .file(new MockMultipartFile("file", "ref.xml", "text/xml", referenceProject))
                        .with(user("tester").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        return projectService.list().stream()
                .map(de.soapuiweb.service.ProjectHandle::id)
                .filter(id -> !before.contains(id))
                .findFirst().orElseThrow();
    }

    @Test
    void uploadListDownloadRoundtripIsByteIdentical() throws Exception {
        String id = upload();

        mockMvc.perform(get("/").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RefProjekt")));

        MvcResult download = mockMvc.perform(
                        get("/projects/{id}/download", id).with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"RefProjekt.xml\""))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray())
                .as("Download muss byte-identisch zum Upload sein (FA-02)")
                .isEqualTo(referenceProject);

        assertThat(handleMeta(id).uploadedBy()).isEqualTo("tester");
        projectService.delete(id);
    }

    @Test
    void invalidUploadIsRejectedWithoutLeftovers() throws Exception {
        int before = projectStore.listProjectIds().size();
        mockMvc.perform(multipart("/projects")
                        .file(new MockMultipartFile("file", "kaputt.xml", "text/xml",
                                "<das-ist-kein-soapui-projekt/>".getBytes()))
                        .with(user("tester").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
        assertThat(projectStore.listProjectIds()).hasSize(before);
    }

    @Test
    void deleteRemovesProjectDirectory() throws Exception {
        String id = upload();
        mockMvc.perform(post("/projects/{id}/delete", id)
                        .with(user("tester").roles("USER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(projectStore.listProjectIds()).doesNotContain(id);
        assertThat(projectService.find(id)).isEmpty();
        assertThat(Path.of("target/project-it-data/projects", id)).doesNotExist();
    }

    @Test
    void storeScanFindsUploadedProjectForRestart() throws Exception {
        String id = upload();
        assertThat(projectStore.listProjectIds()).contains(id);
        assertThat(projectStore.readMeta(id).name()).isEqualTo("RefProjekt");
        projectService.delete(id);
    }

    @Test
    void twoProjectsWithSameNameCoexist() throws Exception {
        String first = upload();
        String second = upload();
        assertThat(first).isNotEqualTo(second);
        assertThat(projectService.list().stream()
                .filter(h -> h.meta().name().equals("RefProjekt"))).hasSize(2);
        projectService.delete(first);
        projectService.delete(second);
    }

    private de.soapuiweb.storage.ProjectMeta handleMeta(String id) {
        return projectService.require(id).meta();
    }
}
