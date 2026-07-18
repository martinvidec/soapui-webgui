package de.soapuiweb.web;

import de.soapuiweb.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Erzwingt die Änderung von Initialpasswörtern: authentifizierte Nutzer mit
 * mustChangePassword-Flag werden auf /profile/password umgeleitet (FA-51).
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS = Set.of("/profile/password", "/logout", "/login");

    private final UserService userService;

    public MustChangePasswordFilter(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ALLOWED_PATHS.contains(path)
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && userService.mustChangePassword(auth.getName())) {
            response.sendRedirect("/profile/password");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
