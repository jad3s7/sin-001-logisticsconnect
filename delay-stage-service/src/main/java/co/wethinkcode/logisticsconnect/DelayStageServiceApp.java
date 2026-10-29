package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

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
        // ── Global HTTP semantics ─────────────────────────────────────────
        app.error(404, ctx -> jsonError(ctx, 404,
                "no route matches " + ctx.method() + " " + ctx.path()));
        app.error(405, ctx -> jsonError(ctx, 405,
                "method " + ctx.method() + " not allowed on " + ctx.path()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.warning("unhandled on " + ctx.method() + " " + ctx.path() + ": " + e);
            jsonError(ctx, 500, "internal error");
        });

        // ── Routes ────────────────────────────────────────────────────────
        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/delay-stage", ctx -> json(ctx, 200, Json.encode(stages)));

        app.get("/delay-stage/{hubId}", ctx -> {
            String raw = ctx.pathParam("hubId");
            String id = normalizeId(raw);
            Integer stage = stages.get(id);
            if (stage == null) { jsonError(ctx, 404, "no stage for hub: " + raw); return; }
            json(ctx, 200, stageJson(id, stage));
        });

        // 201 on first assignment, 200 on update — the correct code pair for
        // an idempotent create-or-replace endpoint.
        app.post("/delay-stage/{hubId}", ctx -> {
            String raw = ctx.pathParam("hubId");
            String id = normalizeId(raw);
            if (id.isEmpty()) { jsonError(ctx, 400, "hubId is required"); return; }

            Integer stage = parseStage(ctx.body());
            if (stage == null) {
                jsonError(ctx, 400, "body must be {\"stage\": <int " + MIN_STAGE + "-" + MAX_STAGE + ">}");
                return;
            }

            boolean existed = stages.containsKey(id);
            stages.put(id, stage);
            LOG.info(() -> "hub " + id + " -> stage " + stage + (existed ? " (update)" : " (create)"));
            json(ctx, existed ? 200 : 201, stageJson(id, stage));
        });
    }

    // ── Response helpers ──────────────────────────────────────────────────

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    private static void jsonError(Context ctx, int status, String msg) {
        json(ctx, status, "{\"error\":\"" + Json.escape(msg) + "\",\"status\":" + status + "}");
    }

    private static String stageJson(String hubId, int stage) {
        return "{\"hubId\":\"" + Json.escape(hubId) + "\",\"stage\":" + stage + "}";
    }

    // ── Parsing ───────────────────────────────────────────────────────────

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
        } catch (Exception e) { return null; }
    }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}