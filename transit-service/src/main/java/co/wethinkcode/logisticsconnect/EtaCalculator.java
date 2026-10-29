package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ETA policy: base transit time of 2 hours plus one hour per delay stage.
 *   Stage 0 = 2h (normal)  ...  Stage 8 = 10h (weather shutdown).
 * Pure math, no HTTP, no clock — easy to unit-test.
 */
final class EtaCalculator {

    static final long BASE_HOURS = 2;

    private EtaCalculator() {}

    static int clampStage(int stage) {
        return Math.max(0, Math.min(8, stage));
    }

    static long totalHoursFor(int stage) {
        return BASE_HOURS + clampStage(stage);
    }

    static Instant compute(Instant now, int stage) {
        return now.plus(totalHoursFor(stage), ChronoUnit.HOURS);
    }
}