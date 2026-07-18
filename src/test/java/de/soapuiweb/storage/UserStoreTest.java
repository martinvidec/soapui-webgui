package de.soapuiweb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.soapuiweb.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UserStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserStore newStore() {
        return new UserStore(new AppProperties(tempDir, null, null), objectMapper);
    }

    @Test
    void roundtripSurvivesReload() {
        UserStore store = newStore();
        store.upsert(new UserAccount("alice", "hash1", UserRole.ADMIN, true, true));
        store.upsert(new UserAccount("bob", "hash2", UserRole.USER, false, false));

        UserStore reloaded = newStore();
        assertThat(reloaded.all()).hasSize(2);
        assertThat(reloaded.findByLogin("alice")).hasValueSatisfying(u -> {
            assertThat(u.role()).isEqualTo(UserRole.ADMIN);
            assertThat(u.mustChangePassword()).isTrue();
        });
        assertThat(reloaded.findByLogin("bob")).hasValueSatisfying(u ->
                assertThat(u.active()).isFalse());
    }

    @Test
    void upsertReplacesExistingUser() {
        UserStore store = newStore();
        store.upsert(new UserAccount("alice", "hash1", UserRole.USER, true, true));
        store.upsert(new UserAccount("alice", "hash2", UserRole.USER, true, false));

        assertThat(store.all()).hasSize(1);
        assertThat(store.findByLogin("alice").orElseThrow().passwordHash()).isEqualTo("hash2");
    }

    @Test
    void writesAtomicallyWithoutTmpLeftovers() throws IOException {
        UserStore store = newStore();
        store.upsert(new UserAccount("alice", "hash1", UserRole.USER, true, false));
        store.upsert(new UserAccount("bob", "hash2", UserRole.USER, true, false));

        assertThat(tempDir.resolve("users.json")).exists();
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }
}
