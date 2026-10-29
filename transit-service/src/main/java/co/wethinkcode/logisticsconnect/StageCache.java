package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the last observed delay-stage for each hub, populated from
 * package-status-topic. Replaces transit-service's synchronous GET to
 * delay-stage-service (stage 2) with an MQ-driven read model.
 */
public class StageCache {

    public record Entry(int stage, Instant timestamp) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public void update(String hubId, int stage, Instant timestamp) {
        entries.put(normalize(hubId), new Entry(stage, timestamp));
    }

    public Optional<Entry> get(String hubId) {
        return Optional.ofNullable(entries.get(normalize(hubId)));
    }

    public int size() { return entries.size(); }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}