package de.soapuiweb.config;

import de.soapuiweb.service.UserService;
import de.soapuiweb.web.MustChangePasswordFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Zugriffsschutz laut FA-50/52: alle UI-/API-Endpunkte nur authentifiziert,
 * /admin nur für ADMIN. Die Mock-Endpunkte laufen auf eigenen Jetty-Ports
 * außerhalb dieses Filters und bleiben bewusst offen (Spezifikation 2.1).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserService userService) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout"))
            // Kein @Component: sonst würde Boot den Filter zusätzlich global registrieren
            .addFilterAfter(new MustChangePasswordFilter(userService), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
