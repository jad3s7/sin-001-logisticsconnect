package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the last observed delay-stage per hub, populated from
 * package-status-topic. This is the ONLY source of stage data in transit-service;
 * there is no synchronous fallback to delay-stage-service.
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

    public Map<String, Entry> snapshot() { return Map.copyOf(entries); }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}