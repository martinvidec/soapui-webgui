package de.soapuiweb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.soapuiweb.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dateibasierte Nutzerablage: {@code ${app.data-dir}/users.json}, atomar
 * geschrieben (temp + move, NFA-05). Kein DB-Backend (Spezifikation 2.2).
 */
@Component
public class UserStore {

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Map<String, UserAccount> users = new LinkedHashMap<>();

    public UserStore(AppProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.file = properties.dataDir().resolve("users.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            UsersDocument document = objectMapper.readValue(file.toFile(), UsersDocument.class);
            document.users().forEach(u -> users.put(u.login(), u));
        } catch (IOException e) {
            throw new UncheckedIOException("users.json nicht lesbar: " + file, e);
        }
    }

    public synchronized List<UserAccount> all() {
        return List.copyOf(users.values());
    }

    public synchronized Optional<UserAccount> findByLogin(String login) {
        return Optional.ofNullable(users.get(login));
    }

    public synchronized boolean isEmpty() {
        return users.isEmpty();
    }

    public synchronized void upsert(UserAccount account) {
        users.put(account.login(), account);
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), new UsersDocument(List.copyOf(users.values())));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("users.json nicht schreibbar: " + file, e);
        }
    }

    record UsersDocument(List<UserAccount> users) {
    }
}
