package com.cms.api;

import com.cms.audit.AuditService;
import com.cms.audit.AuditService.AuditEntry;
import com.cms.common.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Audit trail and dashboard figures for the console. */
@RestController
@RequestMapping("/api/admin")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    public Page<AuditEntry> search(@RequestParam(required = false) String actor,
                                   @RequestParam(required = false) String action,
                                   @RequestParam(required = false) String entityType,
                                   @RequestParam(required = false) Long entityId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return audit.search(actor, action, entityType, entityId, from, to, page, size);
    }

    @GetMapping("/audit/actions")
    public List<String> actions() {
        return audit.actions();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return audit.dashboard();
    }
}
