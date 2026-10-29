package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ETA policy: a base transit time of 2 hours plus one hour per delay stage.
 * Stage 0 = 2h (normal), stage 8 = 10h (weather shutdown).
 * Extracted as its own class so the math is unit-tested independently of HTTP.
 */
final class EtaCalculator {

    static final long BASE_HOURS = 2;

    private EtaCalculator() {}

    static long totalHoursFor(int stage) {
        int s = Math.max(0, Math.min(8, stage));
        return BASE_HOURS + s;
    }

    static Instant compute(Instant now, int stage) {
        return now.plus(totalHoursFor(stage), ChronoUnit.HOURS);
    }
}