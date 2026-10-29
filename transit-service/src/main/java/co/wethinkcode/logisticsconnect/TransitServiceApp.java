package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import co.wethinkcode.logisticsconnect.mq.PackageStatusSubscriber;
import io.javalin.Javalin;
import io.javalin.http.Context;

import javax.jms.JMSException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public class TransitServiceApp {

    private static final Logger LOG = Logger.getLogger(TransitServiceApp.class.getName());
    static final String DEFAULT_HUB_URL = "http://localhost:7051";

    public static void main(String[] args) throws JMSException {
        String hubUrl = args.length > 0 ? args[0] : DEFAULT_HUB_URL;

        StageCache cache = new StageCache();
        PackageStatusSubscriber subscriber;
        try {
            subscriber = new PackageStatusSubscriber(cache);
        } catch (JMSException e) {
            LOG.severe("Failed to connect to broker " + MqConfig.BROKER_URL + ": " + e.getMessage());
            LOG.severe("Start it with: cd common && docker compose up -d");
            throw e;
        }

        Javalin app = Javalin.create();
        registerRoutes(app, hubUrl, cache);
        app.start(7053);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { app.stop(); } catch (Exception ignored) { }
            subscriber.close();
        }));

        LOG.info(() -> "Transit service ready on :7053 (hub=" + hubUrl
                + ", broker=" + MqConfig.BROKER_URL + ", topic=" + MqConfig.TOPIC + ")");
    }

    static void registerRoutes(Javalin app, String hubUrl, StageCache cache) {
        app.error(404, ctx -> jsonError(ctx, 404,
                "no route matches " + ctx.method() + " " + ctx.path()));
        app.error(405, ctx -> jsonError(ctx, 405,
                "method " + ctx.method() + " not allowed on " + ctx.path()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.warning("unhandled on " + ctx.method() + " " + ctx.path() + ": " + e);
            jsonError(ctx, 500, "internal error");
        });

        app.get("/health", ctx -> ctx.result("OK"));

        // Ops visibility into the current view of the world from MQ.
        app.get("/stages", ctx -> {
            StringBuilder sb = new StringBuilder("{");
            // we can't iterate StageCache's map from outside; add a list endpoint later if needed
            sb.append("\"size\":").append(cache.size()).append("}");
            json(ctx, 200, sb.toString());
        });

        app.get("/eta/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            String id = normalizeId(rawId);
            try {
                // ── 1. Stage from MQ-driven cache (replaces sync delay-stage call)
                StageCache.Entry stageEntry = cache.get(id).orElse(null);
                if (stageEntry == null) {
                    jsonError(ctx, 404, "no MQ status received yet for hub: " + rawId);
                    return;
                }
                int stage = stageEntry.stage();

                // ── 2. Hub details from hub-service (still sync — place-name source)
                Http.Response hubResp = Http.get(hubUrl.replaceAll("/+$", "") + "/hubs/" + id);
                if (hubResp.status() == 404) { jsonError(ctx, 404, "hub not found: " + rawId); return; }
                if (hubResp.status() != 200) { jsonError(ctx, 502, "hub-service returned " + hubResp.status()); return; }
                Map<String, Object> hub = JsonParser.parseObject(hubResp.body());

                // ── 3. ETA
                Instant computedAt = Instant.now();
                Instant eta        = EtaCalculator.compute(computedAt, stage);

                String body = "{"
                        + "\"hubId\":\""       + Json.escape(id)                        + "\","
                        + "\"hubName\":\""     + Json.escape(str(hub.get("name")))     + "\","
                        + "\"province\":\""    + Json.escape(str(hub.get("province"))) + "\","
                        + "\"stage\":"         + stage                                 + ","
                        + "\"stageObservedAt\":\"" + stageEntry.timestamp()            + "\","
                        + "\"baseHours\":"     + EtaCalculator.BASE_HOURS              + ","
                        + "\"totalHours\":"    + EtaCalculator.totalHoursFor(stage)    + ","
                        + "\"computedAt\":\""  + computedAt                            + "\","
                        + "\"eta\":\""         + eta                                   + "\""
                        + "}";
                json(ctx, 200, body);
            } catch (Exception e) {
                LOG.warning("ETA failed for " + id + ": " + e);
                jsonError(ctx, 502, "upstream failure: " + e.getMessage());
            }
        });
    }

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    private static void jsonError(Context ctx, int status, String msg) {
        json(ctx, status, "{\"error\":\"" + Json.escape(msg) + "\",\"status\":" + status + "}");
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}