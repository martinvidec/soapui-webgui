package de.soapuiweb.service;

import de.soapuiweb.config.AppProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fachliche Edit-Sperre pro Projekt (FA-06): in-memory, mit Timeout und
 * Verlängerung bei Aktivität. Ein App-Restart löst alle Sperren — laut
 * Spezifikation 2.1 akzeptiert.
 */
@Service
public class LockService {

    public record EditLock(String owner, Instant expiresAt) {
    }

    /** Sperre wird von einem anderen Nutzer gehalten. */
    public static class LockConflictException extends RuntimeException {
        private final String owner;

        LockConflictException(String owner) {
            super("Projekt ist gesperrt von '" + owner + "'");
            this.owner = owner;
        }

        public String owner() {
            return owner;
        }
    }

    private static final Logger log = LogManager.getLogger(LockService.class);

    private final Map<String, EditLock> locks = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Clock clock;

    public LockService(AppProperties properties, Clock clock) {
        this.timeout = Duration.ofMinutes(properties.lockTimeoutMinutes());
        this.clock = clock;
    }

    /** Erwirbt (oder verlängert) die Sperre; wirft LockConflictException bei Fremdsperre. */
    public synchronized EditLock acquire(String projectId, String user) {
        Optional<EditLock> current = ownerLock(projectId);
        if (current.isPresent() && !current.get().owner().equals(user)) {
            throw new LockConflictException(current.get().owner());
        }
        EditLock lock = new EditLock(user, clock.instant().plus(timeout));
        locks.put(projectId, lock);
        return lock;
    }

    /** Gibt die Sperre frei; force erlaubt Fremdsperren zu brechen (nur ADMIN, prüft der Controller). */
    public synchronized void release(String projectId, String user, boolean force) {
        Optional<EditLock> current = ownerLock(projectId);
        if (current.isEmpty()) {
            return;
        }
        if (!force && !current.get().owner().equals(user)) {
            throw new LockConflictException(current.get().owner());
        }
        locks.remove(projectId);
        if (force && !current.get().owner().equals(user)) {
            log.info("Sperre von '{}' auf Projekt {} durch '{}' gebrochen",
                    current.get().owner(), projectId, user);
        }
    }

    /** Verlängert die Sperre bei Aktivität des Inhabers. */
    public synchronized void touch(String projectId, String user) {
        ownerLock(projectId)
                .filter(l -> l.owner().equals(user))
                .ifPresent(l -> locks.put(projectId, new EditLock(user, clock.instant().plus(timeout))));
    }

    public synchronized Optional<EditLock> ownerLock(String projectId) {
        EditLock lock = locks.get(projectId);
        if (lock == null) {
            return Optional.empty();
        }
        if (lock.expiresAt().isBefore(clock.instant())) {
            locks.remove(projectId);
            return Optional.empty();
        }
        return Optional.of(lock);
    }

    public Optional<String> ownerOf(String projectId) {
        return ownerLock(projectId).map(EditLock::owner);
    }

    public boolean isHeldBy(String projectId, String user) {
        return ownerOf(projectId).filter(user::equals).isPresent();
    }

    /** Guard für Mutations-Endpunkte: wirft, wenn ein anderer Nutzer die Sperre hält. */
    public void ensureNotLockedByOther(String projectId, String user) {
        ownerOf(projectId).filter(owner -> !owner.equals(user)).ifPresent(owner -> {
            throw new LockConflictException(owner);
        });
    }

    /** Guard für Editieroperationen: der Nutzer muss die Sperre selbst halten. */
    public void ensureHeldBy(String projectId, String user) {
        Optional<String> owner = ownerOf(projectId);
        if (owner.isEmpty()) {
            throw new IllegalStateException("Zum Bearbeiten muss die Projekt-Sperre übernommen werden");
        }
        if (!owner.get().equals(user)) {
            throw new LockConflictException(owner.get());
        }
    }

    public void releaseAllOf(String projectId) {
        locks.remove(projectId);
    }
}
