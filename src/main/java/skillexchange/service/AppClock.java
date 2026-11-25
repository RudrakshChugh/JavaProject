package skillexchange.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The one clock every rule asks ("has this session started?"). Normally it is the real clock;
 * the demo data and the tests swap it to build history in the past.
 */
public final class AppClock {
    // volatile: when one thread swaps the clock, every other thread sees the new one at once.
    private volatile Clock clock = Clock.systemDefaultZone();

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    public void set(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void reset() {
        this.clock = Clock.systemDefaultZone();
    }
}
