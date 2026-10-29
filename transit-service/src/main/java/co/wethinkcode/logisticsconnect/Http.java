package co.wethinkcode.logisticsconnect;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal HTTP client used for synchronous service-to-service requests. */
final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    record Response(int status, String body) {}

    private Http() {}

    static Response get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return send(request);
    }

    private static Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
