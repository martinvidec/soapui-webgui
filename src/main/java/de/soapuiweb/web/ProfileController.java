package de.soapuiweb.web;

import de.soapuiweb.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile/password")
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public String form(Authentication auth, Model model) {
        model.addAttribute("mustChange", userService.mustChangePassword(auth.getName()));
        return "profile/password";
    }

    @PostMapping
    public String change(Authentication auth,
                         @RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         RedirectAttributes redirect) {
        if (!newPassword.equals(confirmPassword)) {
            redirect.addFlashAttribute("error", "Neues Passwort und Bestätigung stimmen nicht überein");
            return "redirect:/profile/password";
        }
        try {
            userService.changePassword(auth.getName(), currentPassword, newPassword);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/profile/password";
        }
        redirect.addFlashAttribute("message", "Passwort geändert");
        return "redirect:/";
    }
}
