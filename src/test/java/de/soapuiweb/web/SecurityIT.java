package de.soapuiweb.web;

import de.soapuiweb.service.UserService;
import de.soapuiweb.storage.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.data-dir=target/security-it-data")
@AutoConfigureMockMvc
class SecurityIT {

    static {
        // Vor dem Spring-Kontext: frisches Datenverzeichnis für reproduzierbare Läufe
        FileSystemUtils.deleteRecursively(Path.of("target/security-it-data").toFile());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void actuatorHealthIsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorInfoRequiresAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info").with(user("someone").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").with(user("boss").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithSeededUserSucceeds() throws Exception {
        String password = userService.createUser("login.test", UserRole.USER);
        mockMvc.perform(formLogin().user("login.test").password(password))
                .andExpect(authenticated().withUsername("login.test"));
    }

    @Test
    void deactivatedUserCannotLogin() throws Exception {
        String password = userService.createUser("deactivated.test", UserRole.USER);
        userService.setActive("deactivated.test", false);
        mockMvc.perform(formLogin().user("deactivated.test").password(password))
                .andExpect(unauthenticated());
    }

    @Test
    void adminPageIsForbiddenForUserRole() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("someone").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateUser() throws Exception {
        mockMvc.perform(post("/admin/users").with(user("boss").roles("ADMIN")).with(csrf())
                        .param("login", "created.test")
                        .param("role", "USER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(userService.allUsers())
                .anySatisfy(u -> assertThat(u.login()).isEqualTo("created.test"));
    }

    @Test
    void userWithInitialPasswordIsForcedToChangeIt() throws Exception {
        userService.createUser("fresh.test", UserRole.USER);
        mockMvc.perform(get("/").with(user("fresh.test").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile/password"));
    }

    @Test
    void passwordChangeClearsMustChangeFlag() throws Exception {
        String initialPassword = userService.createUser("changer.test", UserRole.USER);
        mockMvc.perform(post("/profile/password")
                        .with(user("changer.test").roles("USER")).with(csrf())
                        .param("currentPassword", initialPassword)
                        .param("newPassword", "neues-passwort-1")
                        .param("confirmPassword", "neues-passwort-1"))
                .andExpect(redirectedUrl("/"));
        assertThat(userService.mustChangePassword("changer.test")).isFalse();
    }
}
