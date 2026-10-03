package com.cms.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP implementation of the provisional TSP lifecycle contract (see {@link TokenServiceProvider}). */
@Component
public class HttpTokenServiceProvider implements TokenServiceProvider {

    private final String base;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpTokenServiceProvider(@Value("${cms.tsp.base-url:}") String base,
                                    @Value("${cms.tsp.api-key:}") String apiKey,
                                    @Value("${cms.tsp.timeout-ms:5000}") long timeoutMs,
                                    ObjectMapper json) {
        this.base = base == null ? "" : base.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2000))).build();
    }

    @Override
    public boolean configured() {
        return !base.isBlank();
    }

    @Override
    public void lifecycle(String tokenRef, String action, String reason, CardUpdate card) {
        if (!configured()) throw new IllegalStateException("token service provider URL not configured");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("reason", reason);
        if (card != null) {
            body.put("pan", card.pan());
            body.put("expiry", card.expiryYYMM());
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/tokens/"
                            + URLEncoder.encode(tokenRef, StandardCharsets.UTF_8) + "/lifecycle"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) b.header("X-Api-Key", apiKey);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("TSP answered HTTP " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("TSP not reachable: " + e.getClass().getSimpleName());
        }
    }
}
