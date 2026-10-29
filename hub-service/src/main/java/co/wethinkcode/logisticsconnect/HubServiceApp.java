package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public class HubServiceApp {

    private static final Logger LOG = Logger.getLogger(HubServiceApp.class.getName());
    static final String DEFAULT_INGESTION_URL = "http://localhost:7050";
    private static final int STARTUP_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 500L;

    /**
     * Immutable snapshot of the hub cache. Swapping the reference is atomic for
     * concurrent readers, so a refresh never exposes a half-populated list.
     * provenance fields record where and when the data came from.
     */
    static final class Cache {
        final List<Map<String, Object>> hubs;
        final Instant loadedAt;
        final String source;

        Cache(List<Map<String, Object>> hubs, Instant loadedAt, String source) {
            this.hubs = List.copyOf(hubs);      // defensive: immutable, no null elements
            this.loadedAt = loadedAt;
            this.source = source;
        }
    }

    public static void main(String[] args) throws Exception {
        String ingestionUrl = args.length > 0 ? args[0] : DEFAULT_INGESTION_URL;

        // Bootstrap from ingestion's cleaned output. Retry briefly, then fail fast:
        // a hub-service that boots with an empty cache would silently answer
        // "no hubs" downstream — worse than not starting.
        List<Map<String, Object>> loaded = fetchWithRetry(ingestionUrl, STARTUP_RETRIES);
        Cache initial = new Cache(loaded, Instant.now(), ingestionUrl);
        AtomicReference<Cache> cache = new AtomicReference<>(initial);

        Javalin app = Javalin.create();
        registerRoutes(app, cache);
        app.start(7051);

        LOG.info(() -> "Hub service ready on :7051 — " + cache.get().hubs.size()
                + " hub(s) sourced from " + ingestionUrl);
    }

    static void registerRoutes(Javalin app, AtomicReference<Cache> cache) {
        app.get("/health", ctx -> ctx.result("OK"));

        // Prove the "sourced from" claim: what we're serving, when it was loaded,
        // and where from. Lets you eyeball the cache state without diffing payloads.
        app.get("/status", ctx -> {
            Cache c = cache.get();
            ctx.contentType("application/json").result("{"
                    + "\"source\":\""    + Json.escape(c.source) + "\","
                    + "\"loadedAt\":\""  + c.loadedAt          + "\","
                    + "\"hubCount\":"    + c.hubs.size()
                    + "}");
        });

        // Full place dataset — verbatim from ingestion's cleaned output.
        app.get("/hubs", ctx ->
                ctx.contentType("application/json").result(Json.encode(cache.get().hubs)));

        app.get("/hubs/{hubId}", ctx -> {
            Map<String, Object> hub = find(cache.get(), ctx.pathParam("hubId"));
            if (hub == null) { notFound(ctx, ctx.pathParam("hubId")); return; }
            ctx.contentType("application/json").result(Json.encode(hub));
        });

        // Place-name projection: the contract downstream services depend on when
        // they only need {id, name, province}, not the full record.
        app.get("/hubs/{hubId}/name", ctx -> {
            Map<String, Object> hub = find(cache.get(), ctx.pathParam("hubId"));
            if (hub == null) { notFound(ctx, ctx.pathParam("hubId")); return; }
            ctx.contentType("application/json").result("{"
                    + "\"hubId\":\""    + Json.escape(str(hub.get("hub_id")))    + "\","
                    + "\"name\":\""     + Json.escape(str(hub.get("name")))      + "\","
                    + "\"province\":\"" + Json.escape(str(hub.get("province")))  + "\""
                    + "}");
        });

        // Re-pull from the same source URL recorded in the current cache entry.
        // Uses atomic swap so concurrent readers never see a transient empty list.
        app.post("/hubs/refresh", ctx -> {
            try {
                String src = cache.get().source;
                List<Map<String, Object>> fresh = fetchHubs(src);
                cache.set(new Cache(fresh, Instant.now(), src));
                LOG.info(() -> "Refreshed hub cache from " + src + " (" + fresh.size() + " hubs)");
                ctx.contentType("application/json")
                        .result("{\"refreshed\":" + fresh.size() + "}");
            } catch (Exception e) {
                LOG.warning("refresh failed: " + e);
                ctx.status(502);
                ctx.contentType("application/json")
                        .result("{\"error\":\"refresh failed: " + Json.escape(e.getMessage()) + "\"}");
            }
        });
    }

    private static void notFound(io.javalin.http.Context ctx, String requested) {
        ctx.status(404);
        ctx.contentType("application/json")
                .result("{\"error\":\"hub not found: " + Json.escape(requested) + "\"}");
    }

    private static Map<String, Object> find(Cache c, String rawId) {
        String requested = normalizeId(rawId);
        for (Map<String, Object> hub : c.hubs) {
            Object id = hub.get("hub_id");
            if (id != null && requested.equals(normalizeId(String.valueOf(id)))) return hub;
        }
        return null;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static List<Map<String, Object>> fetchWithRetry(String url, int attempts) throws Exception {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return fetchHubs(url);
            } catch (Exception e) {
                last = e;
                LOG.warning("ingestion fetch attempt " + i + "/" + attempts
                        + " failed for " + url + ": " + e.getMessage());
                if (i < attempts) Thread.sleep(RETRY_BACKOFF_MS * i);
            }
        }
        throw last;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fetchHubs(String ingestionUrl) throws Exception {
        String url = ingestionUrl.replaceAll("/+$", "") + "/hubs";
        Http.Response r = Http.get(url);
        if (r.status() != 200) {
            throw new IllegalStateException("ingestion returned " + r.status() + " for " + url);
        }
        List<Object> parsed = JsonParser.parseArray(r.body());
        List<Map<String, Object>> hubs = new ArrayList<>(parsed.size());
        for (Object o : parsed) {
            if (o instanceof Map<?, ?> m) hubs.add((Map<String, Object>) m);
        }
        return hubs;
    }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}