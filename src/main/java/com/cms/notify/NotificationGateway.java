package com.cms.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers SMS and e-mail (CMS-105). Per channel, cms.notify.{sms|email}.provider:
 * <ul>
 *   <li>HTTP: POST {url} with X-Api-Key and JSON {to, subject, text, sender}; 2xx = sent (body may carry
 *       {"id": ...} as provider reference), 4xx = rejected (not retried), 5xx / timeout = retried.
 *       PROVISIONAL until the bank's SMS / e-mail gateway is chosen (IN-07).</li>
 *   <li>LOG: nothing leaves the CMS; the message is logged (masked) and kept in a small in-memory sink that the dev
 *       console shows. A test switch makes LOG deliveries fail.</li>
 * </ul>
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);
    private static final int SINK_SIZE = 200;

    public record Result(boolean sent, boolean retry, String providerRef, String error) {}

    public record SinkEntry(long notificationId, String channel, String to, String subject, String text, OffsetDateTime at) {}

    private record Channel(String provider, String url, String apiKey, String sender) {}

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final Deque<SinkEntry> sink = new ArrayDeque<>();
    private volatile boolean failLog;

    public NotificationGateway(ObjectMapper json,
                               @Value("${cms.notify.sms.provider:LOG}") String smsProvider,
                               @Value("${cms.notify.sms.url:}") String smsUrl,
                               @Value("${cms.notify.sms.api-key:}") String smsKey,
                               @Value("${cms.notify.sms.sender:BANK}") String smsSender,
                               @Value("${cms.notify.email.provider:LOG}") String emailProvider,
                               @Value("${cms.notify.email.url:}") String emailUrl,
                               @Value("${cms.notify.email.api-key:}") String emailKey,
                               @Value("${cms.notify.email.sender:no-reply@bank.local}") String emailSender) {
        this.json = json;
        channels.put("SMS", new Channel(smsProvider.toUpperCase(), smsUrl, smsKey, smsSender));
        channels.put("EMAIL", new Channel(emailProvider.toUpperCase(), emailUrl, emailKey, emailSender));
    }

    public Result send(long notificationId, String channel, String to, String subject, String text) {
        Channel c = channels.get(channel);
        if (c == null) return new Result(false, false, null, "unknown channel " + channel);
        if ("LOG".equals(c.provider())) return sendToLog(notificationId, channel, to, subject, text);
        if (c.url() == null || c.url().isBlank()) return new Result(false, true, null, channel + " gateway URL not configured");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("to", to);
            if (subject != null) body.put("subject", subject);
            body.put("text", text);
            body.put("sender", c.sender());
            body.put("reference", "N" + notificationId);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.url())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (c.apiKey() != null && !c.apiKey().isBlank()) b.header("X-Api-Key", c.apiKey());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 200 && r.statusCode() < 300) {
                String ref = null;
                try {
                    JsonNode n = json.readTree(r.body());
                    if (n != null && n.hasNonNull("id")) ref = n.get("id").asText();
                } catch (Exception ignored) { /* reference is optional */ }
                return new Result(true, false, ref, null);
            }
            return new Result(false, r.statusCode() >= 500, null, "gateway answered HTTP " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, true, null, "interrupted");
        } catch (Exception e) {
            return new Result(false, true, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private synchronized Result sendToLog(long id, String channel, String to, String subject, String text) {
        if (failLog) return new Result(false, true, null, "LOG provider set to fail (test)");
        sink.addFirst(new SinkEntry(id, channel, to, subject, text, OffsetDateTime.now()));
        while (sink.size() > SINK_SIZE) sink.removeLast();
        log.info("{} to {}: {}", channel, mask(to), text.replaceAll("[0-9]{6}", "******"));
        return new Result(true, false, "LOG-" + id, null);
    }

    public Map<String, String> providers() {
        Map<String, String> m = new LinkedHashMap<>();
        channels.forEach((k, v) -> m.put(k, v.provider() + ("HTTP".equals(v.provider()) && (v.url() == null || v.url().isBlank()) ? " (no URL)" : "")));
        return m;
    }

    // ---------------- dev sink ----------------

    public synchronized List<SinkEntry> sink() {
        return new ArrayList<>(sink);
    }

    public synchronized void clearSink() {
        sink.clear();
    }

    public void failLog(boolean fail) {
        failLog = fail;
    }

    public boolean failLog() {
        return failLog;
    }

    static String mask(String to) {
        if (to == null || to.length() < 5) return "***";
        int at = to.indexOf('@');
        if (at > 1) return to.charAt(0) + "***" + to.substring(at);
        return "***" + to.substring(to.length() - 4);
    }
}
