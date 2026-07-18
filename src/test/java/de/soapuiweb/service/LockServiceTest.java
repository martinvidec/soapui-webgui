package de.soapuiweb.service;

import de.soapuiweb.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockServiceTest {

    /** Steuerbare Uhr für Ablauf-Tests. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private LockService lockService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        lockService = new LockService(new AppProperties(Path.of("target"), null, 30), clock);
    }

    @Test
    void acquireAndConflict() {
        lockService.acquire("p1", "alice");
        assertThat(lockService.ownerOf("p1")).contains("alice");
        assertThatThrownBy(() -> lockService.acquire("p1", "bob"))
                .isInstanceOf(LockService.LockConflictException.class);
    }

    @Test
    void ownerCanReacquireAndTouchExtends() {
        lockService.acquire("p1", "alice");
        clock.advance(Duration.ofMinutes(20));
        lockService.touch("p1", "alice");
        clock.advance(Duration.ofMinutes(20));
        // 40 min seit Acquire, aber nur 20 seit Touch → Sperre lebt noch
        assertThat(lockService.ownerOf("p1")).contains("alice");
    }

    @Test
    void lockExpiresAfterTimeout() {
        lockService.acquire("p1", "alice");
        clock.advance(Duration.ofMinutes(31));
        assertThat(lockService.ownerOf("p1")).isEmpty();
        // danach kann ein anderer Nutzer übernehmen
        lockService.acquire("p1", "bob");
        assertThat(lockService.ownerOf("p1")).contains("bob");
    }

    @Test
    void touchByNonOwnerDoesNotExtend() {
        lockService.acquire("p1", "alice");
        clock.advance(Duration.ofMinutes(29));
        lockService.touch("p1", "bob");
        clock.advance(Duration.ofMinutes(2));
        assertThat(lockService.ownerOf("p1")).isEmpty();
    }

    @Test
    void releaseByOtherRequiresForce() {
        lockService.acquire("p1", "alice");
        assertThatThrownBy(() -> lockService.release("p1", "bob", false))
                .isInstanceOf(LockService.LockConflictException.class);
        lockService.release("p1", "bob", true);
        assertThat(lockService.ownerOf("p1")).isEmpty();
    }

    @Test
    void guardsEnforceLockSemantics() {
        assertThatThrownBy(() -> lockService.ensureHeldBy("p1", "alice"))
                .isInstanceOf(IllegalStateException.class);
        lockService.acquire("p1", "alice");
        lockService.ensureHeldBy("p1", "alice");
        lockService.ensureNotLockedByOther("p1", "alice");
        assertThatThrownBy(() -> lockService.ensureHeldBy("p1", "bob"))
                .isInstanceOf(LockService.LockConflictException.class);
        assertThatThrownBy(() -> lockService.ensureNotLockedByOther("p1", "bob"))
                .isInstanceOf(LockService.LockConflictException.class);
    }
}
