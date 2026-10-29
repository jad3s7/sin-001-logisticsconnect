package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class HubServiceApp {

    private static final Logger LOG = Logger.getLogger(HubServiceApp.class.getName());
    static final String DEFAULT_INGESTION_URL = "http://localhost:7050";

    public static void main(String[] args) throws Exception {
        String ingestionUrl = args.length > 0 ? args[0] : DEFAULT_INGESTION_URL;
        List<Map<String, Object>> hubs = new CopyOnWriteArrayList<>(fetchHubs(ingestionUrl));
        Javalin app = Javalin.create();
        registerRoutes(app, hubs, ingestionUrl);
        app.start(7051);
        LOG.info(() -> "Hub service ready on :7051 with " + hubs.size()
                + " hub(s) from " + ingestionUrl);
    }

    static void registerRoutes(Javalin app, List<Map<String, Object>> hubs, String ingestionUrl) {
        app.get("/health", ctx -> ctx.result("OK"));

        // Place-name source of truth. Serves the cleaned ingestion output.
        app.get("/hubs", ctx ->
                ctx.contentType("application/json").result(Json.encode(hubs)));

        app.get("/hubs/{hubId}", ctx -> {
            String requested = normalizeId(ctx.pathParam("hubId"));
            for (Map<String, Object> hub : hubs) {
                Object id = hub.get("hub_id");
                if (id != null && requested.equals(normalizeId(String.valueOf(id)))) {
                    ctx.contentType("application/json").result(Json.encode(hub));
                    return;
                }
            }
            ctx.status(404);
            ctx.contentType("application/json")
                    .result("{\"error\":\"hub not found: " + Json.escape(ctx.pathParam("hubId")) + "\"}");
        });

        // Convenience: re-pull from ingestion without restarting.
        app.post("/hubs/refresh", ctx -> {
            try {
                List<Map<String, Object>> fresh = fetchHubs(ingestionUrl);
                hubs.clear();
                hubs.addAll(fresh);
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