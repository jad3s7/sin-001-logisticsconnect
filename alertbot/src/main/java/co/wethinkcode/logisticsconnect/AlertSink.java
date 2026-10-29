package co.wethinkcode.logisticsconnect;

import java.util.Map;

/** Where an alert goes once fired. "Simulated" per the brief — never real social media. */
@FunctionalInterface
public interface AlertSink {
    void publish(AlertTracker.Alert alert) throws Exception;

    /** Default: log only. This is the "simulated social-media post" when no webhook is configured. */
    static AlertSink logging() {
        java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AlertSink.class.getName());
        return alert -> LOG.info(() -> "SIMULATED SOCIAL-MEDIA POST: "
                + "[DelayAlert] Hub " + alert.hubId()
                + " has entered delay stage " + alert.stage()
                + " (threshold " + alert.reason() + ")");
    }

    /**
     * POSTs a JSON body to the given webhook URL — the "simulated" external post
     * from the brief. Any failure is logged but does not crash the bot.
     */
    static AlertSink webhook(String url) {
        java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AlertSink.class.getName());
        return alert -> {
            String body = Json.encode(Map.of(
                    "text", "[DelayAlert] Hub " + alert.hubId()
                            + " has entered delay stage " + alert.stage()
                            + " (" + alert.reason() + ")",
                    "hubId", alert.hubId(),
                    "stage", alert.stage(),
                    "previousStage", alert.previousStage(),
                    "sourceTimestamp", String.valueOf(alert.sourceTimestamp()),
                    "alertedAt", String.valueOf(alert.alertedAt())));
            try {
                Http.Response r = Http.post(url, body);
                LOG.info(() -> "Webhook POST → " + url + " (" + r.status() + ")");
            } catch (Exception e) {
                LOG.warning(() -> "Webhook POST failed: " + e.getMessage());
            }
        };
    }
}