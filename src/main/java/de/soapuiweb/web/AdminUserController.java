package de.soapuiweb.web;

import de.soapuiweb.service.UserService;
import de.soapuiweb.storage.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userService.allUsers());
        return "admin/users";
    }

    @PostMapping
    public String create(@RequestParam String login, @RequestParam UserRole role,
                         RedirectAttributes redirect) {
        try {
            String initialPassword = userService.createUser(login.trim().toLowerCase(), role);
            redirect.addFlashAttribute("createdLogin", login.trim().toLowerCase());
            redirect.addFlashAttribute("initialPassword", initialPassword);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{login}/deactivate")
    public String deactivate(@PathVariable String login, Authentication auth,
                             RedirectAttributes redirect) {
        if (auth.getName().equals(login)) {
            redirect.addFlashAttribute("error", "Der eigene Account kann nicht deaktiviert werden");
            return "redirect:/admin/users";
        }
        userService.setActive(login, false);
        redirect.addFlashAttribute("message", "Nutzer '" + login + "' deaktiviert");
        return "redirect:/admin/users";
    }

    @PostMapping("/{login}/activate")
    public String activate(@PathVariable String login, RedirectAttributes redirect) {
        userService.setActive(login, true);
        redirect.addFlashAttribute("message", "Nutzer '" + login + "' aktiviert");
        return "redirect:/admin/users";
    }

    @PostMapping("/{login}/reset-password")
    public String resetPassword(@PathVariable String login, RedirectAttributes redirect) {
        String initialPassword = userService.resetPassword(login);
        redirect.addFlashAttribute("createdLogin", login);
        redirect.addFlashAttribute("initialPassword", initialPassword);
        return "redirect:/admin/users";
    }
}
