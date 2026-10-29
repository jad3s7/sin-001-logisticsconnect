package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Decides WHEN to alert and records alerts. Pure logic — no MQ, no HTTP.
 *
 * Rule (see debrief reasoning):
 *   An alert fires when a hub's stage transitions from BELOW the threshold
 *   to AT OR ABOVE it. A hub already at or above threshold does NOT re-alert
 *   on every repeat publish. This is what an on-call engineer actually wants:
 *   "tell me when a hub crosses into weather-shutdown territory", not
 *   "tell me every time someone re-publishes the same stage".
 *
 * Trade-off: if the alert is dropped (webhook down), we do not retry on the
 * next message with the same stage. A production system would back off-and-
 * retry the webhook itself, or persist a pending-alert queue. Out of scope
 * for the scaffold; documented here so the choice is visible.
 */
public class AlertTracker {

    private static final Logger LOG = Logger.getLogger(AlertTracker.class.getName());
    private static final int RECENT_CAPACITY = 50;

    public record Alert(
            String hubId,
            int previousStage,
            int stage,
            Instant sourceTimestamp,
            Instant alertedAt,
            String reason) {}

    private final int threshold;
    private final ConcurrentHashMap<String, Integer> lastStage = new ConcurrentHashMap<>();
    private final Deque<Alert> recent = new ArrayDeque<>();

    public AlertTracker(int threshold) {
        if (threshold < 0 || threshold > 8) {
            throw new IllegalArgumentException("threshold must be 0..8, got " + threshold);
        }
        this.threshold = threshold;
    }

    public int threshold() { return threshold; }

    public List<Alert> recentAlerts() {
        synchronized (recent) { return List.copyOf(recent); }
    }

    public Optional<Alert> recordAndDecide(String hubId, int stage, Instant sourceTimestamp) {
        if (hubId == null || hubId.isBlank()) return Optional.empty();
        String id = normalize(hubId);

        Integer prev = lastStage.put(id, stage);
        int previousStage = (prev == null) ? -1 : prev;

        boolean wasBelow = (prev == null) || (prev < threshold);
        boolean nowAtOrAbove = stage >= threshold;

        if (!(wasBelow && nowAtOrAbove)) return Optional.empty();

        Alert alert = new Alert(
                id,
                previousStage,
                stage,
                sourceTimestamp,
                Instant.now(),
                "stage crossed threshold " + threshold
                        + " (" + (previousStage < 0 ? "unknown" : previousStage) + " → " + stage + ")");

        synchronized (recent) {
            recent.addFirst(alert);
            while (recent.size() > RECENT_CAPACITY) recent.removeLast();
        }
        LOG.warning(() -> "ALERT " + alert.hubId()
                + ": stage " + alert.previousStage() + " → " + alert.stage()
                + " (" + alert.reason() + ")");
        return Optional.of(alert);
    }

    public int trackedHubs() { return lastStage.size(); }

    private static String normalize(String id) {
        return id.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}