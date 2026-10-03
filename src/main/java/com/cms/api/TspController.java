package com.cms.api;

import com.cms.digital.TokenService;
import com.cms.digital.TokenService.Completion;
import com.cms.digital.TokenService.Decision;
import com.cms.digital.TokenService.ProvisionRequest;
import com.cms.digital.TokenService.TokenView;
import com.cms.notify.OtpService.Verified;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token service provider -> issuer (CMS-115), X-Api-Key = cms.tsp.inbound-api-key (role TSP).
 * PROVISIONAL contract (IN-08) until the VTS / MDES issuer specifications are received:
 * <pre>
 *   POST /api/tsp/tokens/authorize           ProvisionRequest            -> {tokenRef, decision, reasons, idvMethod, destination}
 *   POST /api/tsp/tokens/{ref}/verify        {code}                      -> {verified, status, attemptsLeft}
 *   POST /api/tsp/tokens/{ref}/complete      {tokenPan, tokenExpiry, status} -> token status
 *   POST /api/tsp/tokens/{ref}/lifecycle     {action: SUSPEND|RESUME|DELETE, reason} -> token status
 * </pre>
 * The PAN and the token number travel in bodies only.
 */
@RestController
@RequestMapping("/api/tsp/tokens")
public class TspController {

    private static final String TSP = "TSP";
    private final TokenService tokens;

    public TspController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/authorize")
    public Decision authorize(@RequestBody ProvisionRequest r) {
        return tokens.authorize(r, TSP);
    }

    @PostMapping("/{ref}/verify")
    public Verified verify(@PathVariable String ref, @RequestBody Map<String, String> body) {
        return tokens.verifyIdv(ref, body.get("code"), TSP);
    }

    @PostMapping("/{ref}/complete")
    public Map<String, Object> complete(@PathVariable String ref, @RequestBody Completion c) {
        return brief(tokens.complete(ref, c, TSP));
    }

    @PostMapping("/{ref}/lifecycle")
    public Map<String, Object> lifecycle(@PathVariable String ref, @RequestBody Map<String, String> body) {
        return brief(tokens.walletEvent(ref, body.get("action"), body.get("reason"), TSP));
    }

    /** The TSP gets the token's state, not the cardholder's details. */
    static Map<String, Object> brief(TokenView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tokenRef", t.tokenRef());
        m.put("status", t.status());
        m.put("tokenLast4", t.tokenLast4());
        return m;
    }
}
