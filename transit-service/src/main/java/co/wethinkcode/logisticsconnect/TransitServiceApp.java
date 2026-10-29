package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public class TransitServiceApp {

    private static final Logger LOG = Logger.getLogger(TransitServiceApp.class.getName());
    static final String DEFAULT_HUB_URL   = "http://localhost:7051";
    static final String DEFAULT_DELAY_URL = "http://localhost:7052";

    public static void main(String[] args) {
        String hubUrl   = args.length > 0 ? args[0] : DEFAULT_HUB_URL;
        String delayUrl = args.length > 1 ? args[1] : DEFAULT_DELAY_URL;

        Javalin app = Javalin.create();
        registerRoutes(app, hubUrl, delayUrl);
        app.start(7053);

        LOG.info(() -> "Transit service ready on :7053 (hub-service=" + hubUrl
                + ", delay-stage-service=" + delayUrl + ")");
    }

    static void registerRoutes(Javalin app, String hubUrl, String delayUrl) {
        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/eta/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            String id = normalizeId(rawId);

            try {
                // ── 1. Hub details from hub-service (place-name source of truth)
                Http.Response hubResp = Http.get(hubUrl.replaceAll("/+$", "")
                        + "/hubs/" + id);
                LOG.fine(() -> "transit -> hub-service GET /hubs/" + id
                        + " -> " + hubResp.status());
                if (hubResp.status() == 404) {
                    fail(ctx, 404, "hub not found: " + rawId);
                    return;
                }
                if (hubResp.status() != 200) {
                    fail(ctx, 502, "hub-service returned " + hubResp.status());
                    return;
                }
                Map<String, Object> hub = JsonParser.parseObject(hubResp.body());

                // ── 2. Current delay stage from delay-stage-service
                Http.Response delayResp = Http.get(delayUrl.replaceAll("/+$", "")
                        + "/delay-stage/" + id);
                LOG.fine(() -> "transit -> delay-stage-service GET /delay-stage/" + id
                        + " -> " + delayResp.status());
                if (delayResp.status() == 404) {
                    fail(ctx, 404, "no delay stage for hub: " + rawId);
                    return;
                }
                if (delayResp.status() != 200) {
                    fail(ctx, 502, "delay-stage-service returned " + delayResp.status());
                    return;
                }
                Map<String, Object> delay = JsonParser.parseObject(delayResp.body());

                Object stageRaw = delay.get("stage");
                if (!(stageRaw instanceof Number n)) {
                    fail(ctx, 502, "delay-stage response missing numeric 'stage'");
                    return;
                }
                int stage = n.intValue();

                // ── 3. Compute and shape the ETA
                Instant computedAt = Instant.now();
                Instant eta        = EtaCalculator.compute(computedAt, stage);

                String body = "{"
                        + "\"hubId\":\""      + Json.escape(id)                        + "\","
                        + "\"hubName\":\""    + Json.escape(str(hub.get("name")))     + "\","
                        + "\"province\":\""   + Json.escape(str(hub.get("province"))) + "\","
                        + "\"stage\":"        + stage                                 + ","
                        + "\"baseHours\":"    + EtaCalculator.BASE_HOURS              + ","
                        + "\"totalHours\":"   + EtaCalculator.totalHoursFor(stage)    + ","
                        + "\"computedAt\":\"" + computedAt                            + "\","
                        + "\"eta\":\""        + eta                                   + "\""
                        + "}";

                LOG.info(() -> "ETA for " + id + ": stage=" + stage
                        + " totalHours=" + EtaCalculator.totalHoursFor(stage)
                        + " eta=" + eta);

                ctx.contentType("application/json").result(body);
            } catch (Exception e) {
                LOG.warning("ETA failed for " + id + ": " + e);
                fail(ctx, 502, "upstream failure: " + e.getMessage());
            }
        });
    }

    private static void fail(io.javalin.http.Context ctx, int status, String msg) {
        ctx.status(status);
        ctx.contentType("application/json")
                .result("{\"error\":\"" + Json.escape(msg) + "\"}");
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}