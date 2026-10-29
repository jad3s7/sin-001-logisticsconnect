package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

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

    static final class Cache {
        final List<Map<String, Object>> hubs;
        final Instant loadedAt;
        final String source;
        Cache(List<Map<String, Object>> hubs, Instant loadedAt, String source) {
            this.hubs = List.copyOf(hubs);
            this.loadedAt = loadedAt;
            this.source = source;
        }
    }

    public static void main(String[] args) throws Exception {
        String ingestionUrl = args.length > 0 ? args[0] : DEFAULT_INGESTION_URL;
        List<Map<String, Object>> loaded = fetchWithRetry(ingestionUrl, STARTUP_RETRIES);
        AtomicReference<Cache> cache = new AtomicReference<>(
                new Cache(loaded, Instant.now(), ingestionUrl));

        Javalin app = Javalin.create();
        registerRoutes(app, cache);
        app.start(7051);
        LOG.info(() -> "Hub service ready on :7051 — " + cache.get().hubs.size()
                + " hub(s) sourced from " + ingestionUrl);
    }

    static void registerRoutes(Javalin app, AtomicReference<Cache> cache) {
        // ── Global HTTP semantics ─────────────────────────────────────────
        // These fire only when a handler has NOT already written a body,
        // so explicit 404/405 responses from handlers below are preserved.
        app.error(404, ctx -> jsonError(ctx, 404,
                "no route matches " + ctx.method() + " " + ctx.path()));
        app.error(405, ctx -> jsonError(ctx, 405,
                "method " + ctx.method() + " not allowed on " + ctx.path()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.warning("unhandled on " + ctx.method() + " " + ctx.path() + ": " + e);
            jsonError(ctx, 500, "internal error");
        });

        // ── Routes ────────────────────────────────────────────────────────
        app.get("/health", ctx -> ctx.result("OK"));   // text/plain, matches scaffold

        app.get("/status", ctx -> {
            Cache c = cache.get();
            json(ctx, 200, "{"
                    + "\"source\":\""   + Json.escape(c.source) + "\","
                    + "\"loadedAt\":\"" + c.loadedAt          + "\","
                    + "\"hubCount\":"   + c.hubs.size()
                    + "}");
        });

        app.get("/hubs", ctx -> json(ctx, 200, Json.encode(cache.get().hubs)));

        app.get("/hubs/{hubId}", ctx -> {
            Map<String, Object> hub = find(cache.get(), ctx.pathParam("hubId"));
            if (hub == null) { jsonError(ctx, 404, "hub not found: " + ctx.pathParam("hubId")); return; }
            json(ctx, 200, Json.encode(hub));
        });

        app.get("/hubs/{hubId}/name", ctx -> {
            Map<String, Object> hub = find(cache.get(), ctx.pathParam("hubId"));
            if (hub == null) { jsonError(ctx, 404, "hub not found: " + ctx.pathParam("hubId")); return; }
            json(ctx, 200, "{"
                    + "\"hubId\":\""    + Json.escape(str(hub.get("hub_id")))   + "\","
                    + "\"name\":\""     + Json.escape(str(hub.get("name")))     + "\","
                    + "\"province\":\"" + Json.escape(str(hub.get("province"))) + "\""
                    + "}");
        });

        app.post("/hubs/refresh", ctx -> {
            try {
                String src = cache.get().source;
                List<Map<String, Object>> fresh = fetchHubs(src);
                cache.set(new Cache(fresh, Instant.now(), src));
                LOG.info(() -> "Refreshed hub cache from " + src + " (" + fresh.size() + " hubs)");
                json(ctx, 200, "{\"refreshed\":" + fresh.size() + "}");
            } catch (Exception e) {
                LOG.warning("refresh failed: " + e);
                jsonError(ctx, 502, "refresh failed: " + e.getMessage());
            }
        });
    }

    // ── Response helpers ──────────────────────────────────────────────────

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    private static void jsonError(Context ctx, int status, String msg) {
        json(ctx, status, "{\"error\":\"" + Json.escape(msg) + "\",\"status\":" + status + "}");
    }

    // ── Data access ───────────────────────────────────────────────────────

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
            try { return fetchHubs(url); }
            catch (Exception e) {
                last = e;
                LOG.warning("ingestion attempt " + i + "/" + attempts + " failed: " + e.getMessage());
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