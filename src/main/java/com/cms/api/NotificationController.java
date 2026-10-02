package com.cms.api;

import com.cms.approval.ApprovalActions.TemplateSave;
import com.cms.approval.ApprovalService;
import com.cms.common.Page;
import com.cms.notify.NotificationService;
import com.cms.notify.NotificationService.NotificationView;
import com.cms.notify.NotificationService.Preferences;
import com.cms.notify.NotificationService.Template;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer notifications for operations (CMS-105): outbox, resend, preferences, templates. */
@RestController
@RequestMapping("/api/admin")
public class NotificationController {

    private final NotificationService notifications;
    private final ApprovalService approvals;

    public NotificationController(NotificationService notifications, ApprovalService approvals) {
        this.notifications = notifications;
        this.approvals = approvals;
    }

    @GetMapping("/notifications")
    public Page<NotificationView> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long customerId,
                                       @RequestParam(required = false) Long cardId, @RequestParam(required = false) String event,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return notifications.list(status, customerId, cardId, event, page, size);
    }

    @GetMapping("/notifications/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", notifications.counts());
        m.put("providers", notifications.providers());
        return m;
    }

    @PostMapping("/notifications/{id}/resend")
    public NotificationView resend(@PathVariable long id, @Operator String op) {
        return notifications.resend(id, op);
    }

    @GetMapping("/customers/{id}/notification-settings")
    public Preferences preferences(@PathVariable long id) {
        return notifications.preferences(id);
    }

    @PutMapping("/customers/{id}/notification-settings")
    public Preferences savePreferences(@PathVariable long id, @RequestBody Preferences p, @Operator String op) {
        return notifications.savePreferences(id, p, op);
    }

    @GetMapping("/setup/notification-templates")
    public List<Template> templates() {
        return notifications.templates();
    }

    @PutMapping("/setup/notification-templates/{key}")
    public ResponseEntity<Object> saveTemplate(@PathVariable String key, @RequestBody Template t, @Operator String op) {
        String[] k = key.split("\\.");
        Template fixed = new Template(key, k[0], k.length > 1 ? k[1] : null, k.length > 2 ? k[2] : null, t.subject(), t.body(),
                t.active(), null, null);
        return approvals.submit("NOTIFICATION_TEMPLATE_SAVE", "notification_template", key,
                "Change message " + key + (t.active() ? "" : " (off)"), new TemplateSave(fixed), op).toResponse();
    }
}
