package com.cms.api;

import com.cms.hsm.HsmException;
import com.cms.hsm.PayShieldClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/admin/hsm/health  ->  NC diagnostics.
 * UP: returns LMK check value and firmware; alert if the LMK check value ever changes.
 * DOWN: 503, so monitoring can alarm while the CMS itself stays up.
 */
@RestController
public class HsmHealthController {

    private final PayShieldClient hsm;

    public HsmHealthController(PayShieldClient hsm) {
        this.hsm = hsm;
    }

    @GetMapping("/api/admin/hsm/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            String nc = hsm.diagnostics();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "lmkCheckValue", nc.substring(0, Math.min(16, nc.length())),
                    "firmware", nc.length() > 16 ? nc.substring(16) : ""));
        } catch (HsmException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "reason", e.getMessage()));
        }
    }
}
