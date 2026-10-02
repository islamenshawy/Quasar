package com.cms.api;

import com.cms.approval.ApprovalActions.AlertResolve;
import com.cms.approval.ApprovalActions.FraudRuleSave;
import com.cms.approval.ApprovalService;
import com.cms.common.Page;
import com.cms.fraud.FraudService;
import com.cms.fraud.FraudService.AlertView;
import com.cms.fraud.FraudService.Resolution;
import com.cms.fraud.FraudService.Rule;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Fraud and risk (CMS-095): rules under setup (supervisors, maker-checker FRAUD_RULE_SAVE) and the alert
 * queue for the fraud desk (resolution through FRAUD_ALERT_RESOLVE, direct by default).
 */
@RestController
@RequestMapping("/api/admin")
public class FraudController {

    private final FraudService fraud;
    private final ApprovalService approvals;

    public FraudController(FraudService fraud, ApprovalService approvals) {
        this.fraud = fraud;
        this.approvals = approvals;
    }

    // ---------- rules ----------

    @GetMapping("/setup/fraud-rules")
    public List<Rule> rules() {
        return fraud.rules();
    }

    @PostMapping("/setup/fraud-rules")
    public ResponseEntity<Object> createRule(@RequestBody Rule r, @Operator String op) {
        return approvals.submit("FRAUD_RULE_SAVE", "fraud_rule", r.code(), "New fraud rule " + r.code() + " (" + r.action() + ")",
                new FraudRuleSave(null, true, r), op).toResponse();
    }

    @PutMapping("/setup/fraud-rules/{code}")
    public ResponseEntity<Object> updateRule(@PathVariable String code, @RequestBody Rule r, @Operator String op) {
        return approvals.submit("FRAUD_RULE_SAVE", "fraud_rule", code,
                "Change fraud rule " + code + " (" + r.action() + (r.active() ? ", active" : ", inactive") + ")",
                new FraudRuleSave(code, false, r), op).toResponse();
    }

    // ---------- alerts ----------

    @GetMapping("/fraud/alerts")
    public Page<AlertView> alerts(@RequestParam(required = false) String status, @RequestParam(required = false) Long cardId,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return fraud.alerts(status, cardId, page, size);
    }

    @GetMapping("/fraud/alerts/count")
    public Map<String, Long> openCount() {
        return Map.of("open", fraud.openCount());
    }

    @GetMapping("/fraud/alerts/{id}")
    public AlertView alert(@PathVariable long id) {
        return fraud.alert(id);
    }

    @PostMapping("/fraud/alerts/{id}/assign")
    public AlertView assign(@PathVariable long id, @Operator String op) {
        return fraud.assign(id, op);
    }

    @PostMapping("/fraud/alerts/{id}/note")
    public AlertView note(@PathVariable long id, @RequestBody Map<String, String> body, @Operator String op) {
        return fraud.note(id, body.get("note"), op);
    }

    @PostMapping("/fraud/alerts/{id}/resolve")
    public ResponseEntity<Object> resolve(@PathVariable long id, @RequestBody Resolution r, @Operator String op) {
        AlertView a = fraud.alert(id);
        return approvals.submit("FRAUD_ALERT_RESOLVE", "fraud_alert", String.valueOf(id),
                "Fraud alert " + id + " on " + a.maskedPan() + ": " + r.outcome(), new AlertResolve(id, r), op).toResponse();
    }
}
