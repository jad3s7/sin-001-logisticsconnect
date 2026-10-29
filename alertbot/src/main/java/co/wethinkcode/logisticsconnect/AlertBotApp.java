package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import co.wethinkcode.logisticsconnect.mq.PackageStatusSubscriber;
import io.javalin.Javalin;
import io.javalin.http.Context;

import javax.jms.JMSException;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

public class AlertBotApp {

    private static final Logger LOG = Logger.getLogger(AlertBotApp.class.getName());

    static final int DEFAULT_THRESHOLD = 5;

    public static void main(String[] args) throws JMSException {
        int threshold   = parseThreshold(System.getenv("ALERT_THRESHOLD"));
        String webhook  = System.getenv("ALERT_WEBHOOK_URL");
        AlertSink sink  = (webhook == null || webhook.isBlank())
                ? AlertSink.logging()
                : AlertSink.webhook(webhook);

        AlertTracker tracker = new AlertTracker(threshold);

        PackageStatusSubscriber subscriber;
        try {
            subscriber = new PackageStatusSubscriber((data, valid) -> {
                if (!valid || data == null) return;
                tracker.recordAndDecide(data.hubId(), data.stage(), data.timestamp())
                        .ifPresent(alert -> {
                            try { sink.publish(alert); }
                            catch (Exception e) {
                                LOG.warning("Alert sink failed for " + alert.hubId() + ": " + e.getMessage());
                            }
                        });
            });
        } catch (JMSException e) {
            LOG.severe("Failed to connect to broker " + MqConfig.BROKER_URL + ": " + e.getMessage());
            LOG.severe("Start it with: cd common && docker compose up -d");
            throw e;
        }

        Javalin app = Javalin.create();
        registerRoutes(app, tracker, sink, webhook);
        app.start(7054);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { app.stop(); } catch (Exception ignored) { }
            subscriber.close();
        }));

        LOG.info(() -> "AlertBot ready on :7054 — subscribed to " + MqConfig.TOPIC
                + " at " + MqConfig.BROKER_URL + ", threshold=" + threshold
                + (webhook == null || webhook.isBlank()
                ? ", sink=LOGGING (simulated)"
                : ", sink=WEBHOOK " + webhook));
    }

    static void registerRoutes(Javalin app, AlertTracker tracker, AlertSink sink, String webhookUrl) {
        app.error(404, ctx -> jsonError(ctx, 404,
                "no route matches " + ctx.method() + " " + ctx.path()));
        app.error(405, ctx -> jsonError(ctx, 405,
                "method " + ctx.method() + " not allowed on " + ctx.path()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.warning("unhandled on " + ctx.method() + " " + ctx.path() + ": " + e);
            jsonError(ctx, 500, "internal error");
        });

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alertbot/status", ctx -> {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"threshold\":").append(tracker.threshold()).append(',');
            sb.append("\"trackedHubs\":").append(tracker.trackedHubs()).append(',');
            sb.append("\"alertCount\":").append(tracker.recentAlerts().size()).append(',');
            sb.append("\"sink\":\"")
                    .append(webhookUrl == null || webhookUrl.isBlank() ? "LOGGING_SIMULATED" : "WEBHOOK")
                    .append("\",");
            sb.append("\"topic\":\"").append(Json.escape(MqConfig.TOPIC)).append("\",");
            sb.append("\"brokerUrl\":\"").append(Json.escape(MqConfig.BROKER_URL)).append("\"");
            sb.append("}");
            json(ctx, 200, sb.toString());
        });

        app.get("/alerts", ctx -> {
            List<AlertTracker.Alert> alerts = tracker.recentAlerts();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (AlertTracker.Alert a : alerts) {
                if (!first) sb.append(',');
                first = false;
                sb.append(alertToJson(a));
            }
            sb.append(']');
            json(ctx, 200, sb.toString());
        });

        // Manual trigger: useful for demos and for the smoke test. Same code path
        // the MQ subscriber uses — no duplicate logic.
        app.post("/alertbot/trigger/{hubId}", ctx -> {
            String raw = ctx.pathParam("hubId");
            try {
                var obj = JsonParser.parseObject(ctx.body());
                Object stO = obj.get("stage");
                if (!(stO instanceof Number n)) { jsonError(ctx, 400, "body must be {stage:<int 0-8>}"); return; }
                int stage = n.intValue();
                if (stage < 0 || stage > 8) { jsonError(ctx, 400, "stage out of range"); return; }
                Instant ts = obj.get("timestamp") instanceof String s ? Instant.parse(s) : Instant.now();
                var alert = tracker.recordAndDecide(raw, stage, ts);
                if (alert.isEmpty()) {
                    json(ctx, 200, "{\"alerted\":false,\"reason\":\"no threshold crossing\"}");
                } else {
                    sink.publish(alert.get());
                    json(ctx, 201, "{\"alerted\":true,\"alert\":" + alertToJson(alert.get()) + "}");
                }
            } catch (Exception e) {
                jsonError(ctx, 400, "bad body: " + e.getMessage());
            }
        });
    }

    private static String alertToJson(AlertTracker.Alert a) {
        return "{"
                + "\"hubId\":\""              + Json.escape(a.hubId())     + "\","
                + "\"previousStage\":"        + a.previousStage()          + ","
                + "\"stage\":"                + a.stage()                  + ","
                + "\"sourceTimestamp\":\""    + a.sourceTimestamp()        + "\","
                + "\"alertedAt\":\""          + a.alertedAt()              + "\","
                + "\"reason\":\""             + Json.escape(a.reason())     + "\""
                + "}";
    }

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    private static void jsonError(Context ctx, int status, String msg) {
        json(ctx, status, "{\"error\":\"" + Json.escape(msg) + "\",\"status\":" + status + "}");
    }

    static int parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_THRESHOLD;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 0 || n > 8) return DEFAULT_THRESHOLD;
            return n;
        } catch (NumberFormatException e) {
            return DEFAULT_THRESHOLD;
        }
    }
}