package com.cms.api;

import com.cms.approval.ApprovalActions.ChipScript;
import com.cms.approval.ApprovalService;
import com.cms.emv.IssuerScriptService;
import com.cms.emv.IssuerScriptService.Script;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Issuer scripts for a card's chip (CMS-110): queue (maker-checker CHIP_SCRIPT), list, cancel. */
@RestController
@RequestMapping("/api/admin/cards")
public class ChipScriptController {

    private final IssuerScriptService scripts;
    private final ApprovalService approvals;

    public ChipScriptController(IssuerScriptService scripts, ApprovalService approvals) {
        this.scripts = scripts;
        this.approvals = approvals;
    }

    @GetMapping("/{id}/chip-scripts")
    public List<Script> list(@PathVariable long id) {
        return scripts.list(id);
    }

    @PostMapping("/{id}/chip-scripts")
    public ResponseEntity<Object> queue(@PathVariable long id, @RequestBody Map<String, String> body, @Operator String op) {
        String command = body.get("command");
        return approvals.submit("CHIP_SCRIPT", "card", String.valueOf(id), "Chip command " + command + " for card " + id,
                new ChipScript(id, command, body.get("value"), body.get("reason")), op).toResponse();
    }

    @PostMapping("/chip-scripts/{scriptId}/cancel")
    public Script cancel(@PathVariable long scriptId, @Operator String op) {
        return scripts.cancel(scriptId, op);
    }
}
