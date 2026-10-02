package com.cms.api;

import com.cms.approval.ApprovalService;
import com.cms.approval.ApprovalService.Policy;
import com.cms.approval.ApprovalService.Request;
import com.cms.common.Page;
import com.cms.security.Operator;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Maker-checker queue and policy. Approve / reject: SUPERVISOR or ADMIN, never the maker. */
@RestController
@RequestMapping("/api/admin")
public class ApprovalController {

    public record Decision(String comment) {}

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/approvals")
    public Page<Request> search(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String maker,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) String entityType,
                                @RequestParam(required = false) String entityId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return approvals.search(status, maker, action, entityType, entityId, page, size);
    }

    @GetMapping("/approvals/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", approvals.pendingCount());
    }

    @GetMapping("/approvals/{id}")
    public Request get(@PathVariable long id) {
        return approvals.get(id);
    }

    @PostMapping("/approvals/{id}/approve")
    public Request approve(@PathVariable long id, @RequestBody(required = false) Decision d, @Operator String op) {
        return approvals.approve(id, op, d == null ? null : d.comment());
    }

    @PostMapping("/approvals/{id}/reject")
    public Request reject(@PathVariable long id, @RequestBody Decision d, @Operator String op) {
        return approvals.reject(id, op, d.comment());
    }

    @PostMapping("/approvals/{id}/cancel")
    public Request cancel(@PathVariable long id, @Operator String op) {
        return approvals.cancel(id, op);
    }

    @GetMapping("/approval-policy")
    public List<Policy> policies() {
        return approvals.policies();
    }

    @PutMapping("/approval-policy/{action}")
    public List<Policy> setPolicy(@PathVariable String action, @RequestBody Map<String, Boolean> body, @Operator String op) {
        return approvals.setPolicy(action, Boolean.TRUE.equals(body.get("required")), op);
    }
}
