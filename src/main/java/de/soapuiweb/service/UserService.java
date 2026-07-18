package de.soapuiweb.service;

import de.soapuiweb.storage.UserAccount;
import de.soapuiweb.storage.UserRole;
import de.soapuiweb.storage.UserStore;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LogManager.getLogger(UserService.class);
    private static final Pattern LOGIN_PATTERN = Pattern.compile("[a-z0-9._-]{3,32}");
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserStore store;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserStore store, PasswordEncoder passwordEncoder) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
    }

    /** Erststart: Admin mit Initialpasswort anlegen (Spezifikation, Abschnitt 5). */
    @PostConstruct
    void seedAdminOnFirstStart() {
        if (!store.isEmpty()) {
            return;
        }
        String initialPassword = generatePassword();
        store.upsert(new UserAccount("admin", passwordEncoder.encode(initialPassword),
                UserRole.ADMIN, true, true));
        log.warn("Erststart: Nutzer 'admin' angelegt. Initialpasswort: {} "
                + "(muss beim ersten Login geändert werden)", initialPassword);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = store.findByLogin(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.withUsername(account.login())
                .password(account.passwordHash())
                .roles(account.role().name())
                .disabled(!account.active())
                .build();
    }

    public List<UserAccount> allUsers() {
        return store.all();
    }

    /** Legt einen Nutzer an und liefert das generierte Initialpasswort zurück. */
    public String createUser(String login, UserRole role) {
        if (login == null || !LOGIN_PATTERN.matcher(login).matches()) {
            throw new IllegalArgumentException(
                    "Login muss aus 3-32 Zeichen (a-z, 0-9, . _ -) bestehen");
        }
        if (store.findByLogin(login).isPresent()) {
            throw new IllegalArgumentException("Login '" + login + "' existiert bereits");
        }
        String initialPassword = generatePassword();
        store.upsert(new UserAccount(login, passwordEncoder.encode(initialPassword), role, true, true));
        return initialPassword;
    }

    public void setActive(String login, boolean active) {
        store.upsert(require(login).withActive(active));
    }

    /** Setzt das Passwort zurück und liefert das neue Initialpasswort zurück. */
    public String resetPassword(String login) {
        String initialPassword = generatePassword();
        store.upsert(require(login).withPasswordHash(passwordEncoder.encode(initialPassword), true));
        return initialPassword;
    }

    public void changePassword(String login, String currentPassword, String newPassword) {
        UserAccount account = require(login);
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new IllegalArgumentException("Aktuelles Passwort ist falsch");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Neues Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen haben");
        }
        store.upsert(account.withPasswordHash(passwordEncoder.encode(newPassword), false));
    }

    public boolean mustChangePassword(String login) {
        return store.findByLogin(login).map(UserAccount::mustChangePassword).orElse(false);
    }

    private UserAccount require(String login) {
        return store.findByLogin(login)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Nutzer: " + login));
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
