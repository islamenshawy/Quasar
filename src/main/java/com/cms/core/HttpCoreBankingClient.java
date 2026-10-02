package com.cms.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * HTTP implementation of the provisional core banking contract (see {@link CoreBankingClient}).
 * Timeouts, connection failures and 5xx answers are UNAVAILABLE (stand-in rules apply);
 * a 4xx answer without a JSON status is a DECLINED with reason REJECTED_&lt;code&gt;.
 */
@Component
public class HttpCoreBankingClient implements CoreBankingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCoreBankingClient.class);

    private final String base;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpCoreBankingClient(@Value("${cms.core-banking.base-url:}") String base,
                                 @Value("${cms.core-banking.api-key:}") String apiKey,
                                 @Value("${cms.core-banking.timeout-ms:3000}") long timeoutMs,
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
    public Result health() {
        if (!configured()) return Result.unavailable("core banking URL not configured");
        try {
            HttpResponse<String> r = http.send(request("/health").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? new Result(Status.APPROVED, null, null, null, null)
                    : Result.unavailable("health answered HTTP " + r.statusCode());
        } catch (Exception e) {
            return Result.unavailable(describe(e));
        }
    }

    @Override
    public Result balance(String accountRef, String currency) {
        return call("/accounts/" + enc(accountRef) + "/balance", Map.of("currency", currency));
    }

    @Override
    public Result debit(Posting p) { return call("/accounts/" + enc(p.accountRef()) + "/debits", body(p)); }

    @Override
    public Result credit(Posting p) { return call("/accounts/" + enc(p.accountRef()) + "/credits", body(p)); }

    @Override
    public Result hold(Posting p) { return call("/accounts/" + enc(p.accountRef()) + "/holds", body(p)); }

    @Override
    public Result capture(String holdRef, Posting p) { return call("/holds/" + enc(holdRef) + "/capture", body(p)); }

    @Override
    public Result release(String holdRef, String reference) {
        return call("/holds/" + enc(holdRef) + "/release", Map.of("reference", reference));
    }

    @Override
    public Result reverse(String originalReference, Posting p) {
        return call("/postings/" + enc(originalReference) + "/reverse", body(p));
    }

    // ---------------- plumbing ----------------

    private Map<String, Object> body(Posting p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", p.reference());
        m.put("amount", p.amount());
        m.put("fee", p.fee());
        m.put("currency", p.currency());
        m.put("type", p.type());
        m.put("narrative", p.narrative());
        m.put("force", p.force());
        m.put("includeFee", p.includeFee());
        return m;
    }

    private Result call(String path, Object payload) {
        if (!configured()) return Result.unavailable("core banking URL not configured");
        try {
            HttpRequest req = request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 500) return Result.unavailable("core answered HTTP " + r.statusCode());
            JsonNode n = r.body() == null || r.body().isBlank() ? null : json.readTree(r.body());
            if (n == null || !n.hasNonNull("status")) {
                return r.statusCode() >= 400 ? new Result(Status.DECLINED, "REJECTED_" + r.statusCode(), null, null, null)
                        : Result.unavailable("core answered without a status");
            }
            Status s = "APPROVED".equals(n.get("status").asText()) ? Status.APPROVED : Status.DECLINED;
            return new Result(s, text(n, "reason"), number(n, "ledgerBalance"), number(n, "availableBalance"), text(n, "coreRef"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unavailable("interrupted");
        } catch (Exception e) {
            log.warn("Core banking call {} failed: {}", path, describe(e));
            return Result.unavailable(describe(e));
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout);
        if (apiKey != null && !apiKey.isBlank()) b.header("X-Api-Key", apiKey);
        return b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static Long number(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asLong() : null;
    }

    private static String describe(Exception e) {
        String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return m.length() > 180 ? m.substring(0, 180) : m;
    }
}
