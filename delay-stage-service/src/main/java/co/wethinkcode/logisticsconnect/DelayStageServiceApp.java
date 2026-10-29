package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.ActiveMqStatusPublisher;
import co.wethinkcode.logisticsconnect.mq.MqConfig;
import co.wethinkcode.logisticsconnect.mq.StatusPublisher;
import io.javalin.Javalin;
import io.javalin.http.Context;

import javax.jms.JMSException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DelayStageServiceApp {

    private static final Logger LOG = Logger.getLogger(DelayStageServiceApp.class.getName());
    static final int MIN_STAGE = 0;
    static final int MAX_STAGE = 8;

    public static void main(String[] args) throws JMSException {
        ActiveMqStatusPublisher publisher;
        try {
            publisher = new ActiveMqStatusPublisher();
        } catch (JMSException e) {
            LOG.severe("Failed to connect to broker " + MqConfig.BROKER_URL + ": " + e.getMessage());
            LOG.severe("Start it with: cd common && docker compose up -d");
            throw e;
        }

        Map<String, Integer> stages = new ConcurrentHashMap<>();
        Javalin app = Javalin.create();
        registerRoutes(app, stages, publisher::publish);
        app.start(7052);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { app.stop(); } catch (Exception ignored) { }
            publisher.close();
        }));

        LOG.info("Delay stage service ready on :7052 (broker " + MqConfig.BROKER_URL
                + ", topic " + MqConfig.TOPIC + ")");
    }

    /** Back-compat overload: no-op publisher, used by tests that don't care about MQ. */
    static void registerRoutes(Javalin app, Map<String, Integer> stages) {
        registerRoutes(app, stages, (id, s, ts) -> { });
    }

    static void registerRoutes(Javalin app, Map<String, Integer> stages, StatusPublisher publisher) {
        app.error(404, ctx -> jsonError(ctx, 404,
                "no route matches " + ctx.method() + " " + ctx.path()));
        app.error(405, ctx -> jsonError(ctx, 405,
                "method " + ctx.method() + " not allowed on " + ctx.path()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.warning("unhandled on " + ctx.method() + " " + ctx.path() + ": " + e);
            jsonError(ctx, 500, "internal error");
        });

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/delay-stage", ctx -> json(ctx, 200, Json.encode(stages)));

        app.get("/delay-stage/{hubId}", ctx -> {
            String raw = ctx.pathParam("hubId");
            String id = normalizeId(raw);
            Integer stage = stages.get(id);
            if (stage == null) { jsonError(ctx, 404, "no stage for hub: " + raw); return; }
            json(ctx, 200, stageJson(id, stage, true));
        });

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

            boolean published = true;
            try {
                publisher.publish(id, stage, Instant.now());
            } catch (Exception e) {
                published = false;
                LOG.warning("MQ publish failed for " + id + " stage=" + stage + ": " + e);
            }

            boolean pub = published;
            LOG.info(() -> "hub " + id + " -> stage " + stage
                    + (existed ? " (update)" : " (create)") + " published=" + pub);
            json(ctx, existed ? 200 : 201, stageJson(id, stage, published));
        });
    }

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    private static void jsonError(Context ctx, int status, String msg) {
        json(ctx, status, "{\"error\":\"" + Json.escape(msg) + "\",\"status\":" + status + "}");
    }

    private static String stageJson(String hubId, int stage, boolean published) {
        return "{\"hubId\":\"" + Json.escape(hubId) + "\","
                + "\"stage\":" + stage + ","
                + "\"published\":" + published + "}";
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
        } catch (Exception e) { return null; }
    }

    static String normalizeId(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}