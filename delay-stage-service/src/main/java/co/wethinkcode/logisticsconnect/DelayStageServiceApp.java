package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DelayStageServiceApp {

    private static final Logger LOG = Logger.getLogger(DelayStageServiceApp.class.getName());
    static final int MIN_STAGE = 0;
    static final int MAX_STAGE = 8;

    public static void main(String[] args) {
        Javalin app = Javalin.create();
        registerRoutes(app, new ConcurrentHashMap<>());
        app.start(7052);
        LOG.info("Delay stage service ready on :7052 (stages " + MIN_STAGE + "-" + MAX_STAGE + ")");
    }

    static void registerRoutes(Javalin app, Map<String, Integer> stages) {
        app.get("/health", ctx -> ctx.result("OK"));

        // Ops convenience: view all known stage assignments.
        app.get("/delay-stage", ctx ->
                ctx.contentType("application/json").result(Json.encode(stages)));

        app.get("/delay-stage/{hubId}", ctx -> {
            String id = normalizeId(ctx.pathParam("hubId"));
            Integer stage = stages.get(id);
            if (stage == null) {
                ctx.status(404);
                ctx.contentType("application/json")
                        .result("{\"error\":\"no stage for hub: " + Json.escape(ctx.pathParam("hubId")) + "\"}");
                return;
            }
            respondStage(ctx, id, stage);
        });

        // The state-change endpoint referenced by the integration contract.
        // Stage 3 will publish to package-status-topic from here.
        app.post("/delay-stage/{hubId}", ctx -> {
            String id = normalizeId(ctx.pathParam("hubId"));
            if (id.isEmpty()) {
                ctx.status(400);
                ctx.contentType("application/json").result("{\"error\":\"hubId is required\"}");
                return;
            }
            Integer stage = parseStage(ctx.body());
            if (stage == null) {
                ctx.status(400);
                ctx.contentType("application/json")
                        .result("{\"error\":\"body must be {\\\"stage\\\": <int " + MIN_STAGE + "-" + MAX_STAGE + ">}\"}");
                return;
            }
            stages.put(id, stage);
            LOG.info(() -> "hub " + id + " -> stage " + stage);
            respondStage(ctx, id, stage);
        });
    }

    private static void respondStage(io.javalin.http.Context ctx, String hubId, int stage) {
        ctx.contentType("application/json")
                .result("{\"hubId\":\"" + Json.escape(hubId) + "\",\"stage\":" + stage + "}");
    }

    @SuppressWarnings("unchecked")
    static Integer parseStage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            Object parsed = JsonParser.parse(body);
            if (!(parsed instanceof Map<?, ?> m)) return null;
            Object raw = ((Map<String, Object>) m).get("stage");
            int n;
            if (raw instanceof Number num) n = num.intValue();
            else if (raw instanceof String str) n = Integer.parseInt(str.trim());
            else return null;
            if (n < MIN_STAGE || n > MAX_STAGE) return null;
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}