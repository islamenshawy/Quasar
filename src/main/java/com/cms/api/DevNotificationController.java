package com.cms.api;

import com.cms.notify.NotificationGateway;
import com.cms.notify.NotificationGateway.SinkEntry;
import com.cms.notify.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * DEV ONLY: what the LOG provider "sent" (full text, including one-time passwords, for testing), a switch that makes
 * LOG deliveries fail, and a way to run the dispatcher now instead of waiting for its schedule.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/notifications")
public class DevNotificationController {

    private final NotificationGateway gateway;
    private final NotificationService notifications;

    public DevNotificationController(NotificationGateway gateway, NotificationService notifications) {
        this.gateway = gateway;
        this.notifications = notifications;
    }

    @GetMapping("/sink")
    public List<SinkEntry> sink() {
        return gateway.sink();
    }

    @DeleteMapping("/sink")
    public Map<String, Object> clear() {
        gateway.clearSink();
        return Map.of("cleared", true);
    }

    @PutMapping("/sink")
    public Map<String, Object> failMode(@RequestBody Map<String, Boolean> body) {
        gateway.failLog(Boolean.TRUE.equals(body.get("fail")));
        return Map.of("fail", gateway.failLog());
    }

    @PostMapping("/dispatch")
    public Map<String, Object> dispatch() {
        return Map.of("sent", notifications.dispatch());
    }
}
