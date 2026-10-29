package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class IngestionServiceApp {

    private static final Logger LOG = Logger.getLogger(IngestionServiceApp.class.getName());

    public static void main(String[] args) throws Exception {
        List<Map<String, String>> hubs = loadAndClean();
        Javalin app = Javalin.create();
        registerRoutes(app, hubs);
        app.start(7050);
        LOG.info(() -> "Ingestion ready on :7050 — " + hubs.size()
                + " cleaned hub record(s) available at GET /hubs");
    }

    /**
     * Extracted so tests can boot Javalin on a random port and hit the same routes.
     * The cleaned hubs list is loaded once and served from memory: hubs-global.csv
     * is treated as immutable source data (a restart picks up a new export; there
     * is no runtime refresh endpoint).
     */
    static void registerRoutes(Javalin app, List<Map<String, String>> hubs) {
        app.get("/health", ctx -> ctx.result("OK"));

        // Stage 1 contract: full cleaned array, consumed by hub-service.
        app.get("/hubs", ctx -> json(ctx, Json.encode(hubs)));

        // Convenience single-record lookup, used by hub-service's own /hubs/{id}.
        // Lookup is case-insensitive and whitespace-insensitive on the id.
        app.get("/hubs/{hubId}", ctx -> {
            Map<String, String> hub = HubCsvCleaner.findById(hubs, ctx.pathParam("hubId"));
            if (hub == null) {
                ctx.status(404);
                json(ctx, "{\"error\":\"hub not found: " + Json.escape(ctx.pathParam("hubId")) + "\"}");
                return;
            }
            json(ctx, Json.encode(hub));
        });
    }

    static List<Map<String, String>> loadAndClean() throws Exception {
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream("/hubs-global.csv")) {
            if (in == null) {
                throw new IllegalStateException(
                        "hubs-global.csv not found under src/main/resources/");
            }
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return HubCsvCleaner.clean(csv);
        }
    }

    private static void json(io.javalin.http.Context ctx, String body) {
        ctx.contentType("application/json").result(body);
    }
}