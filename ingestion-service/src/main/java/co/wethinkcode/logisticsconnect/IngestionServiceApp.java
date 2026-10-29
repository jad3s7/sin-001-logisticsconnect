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

        Javalin app = Javalin.create().start(7050);

        app.get("/health", ctx -> ctx.result("OK"));

        // Stage 1: cleaned records consumed by hub-service / transit-service downstream.
        app.get("/hubs", ctx -> ctx.contentType("application/json").result(Json.encode(hubs)));

        LOG.info(() -> "Ingestion ready on :7050 with " + hubs.size() + " cleaned hub records");
    }

    private static List<Map<String, String>> loadAndClean() throws Exception {
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream("/hubs-global.csv")) {
            if (in == null) {
                throw new IllegalStateException(
                        "hubs-global.csv not found under src/main/resources/");
            }
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return HubCsvCleaner.clean(csv);
        }
    }
}