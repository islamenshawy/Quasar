package com.cms.api;

import com.cms.approval.ApprovalActions.FeePlanSave;
import com.cms.approval.ApprovalActions.FxRateSave;
import com.cms.approval.ApprovalService;
import com.cms.fee.FeeService;
import com.cms.fee.FeeService.ChargeView;
import com.cms.fee.FeeService.FeePlan;
import com.cms.fee.FeeService.FxRate;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Fee plans, FX rates (setup, maker-checker) and the fees charged to a card (CMS-100). */
@RestController
@RequestMapping("/api/admin")
public class FeeController {

    private final FeeService fees;
    private final ApprovalService approvals;

    public FeeController(FeeService fees, ApprovalService approvals) {
        this.fees = fees;
        this.approvals = approvals;
    }

    @GetMapping("/setup/fee-plans")
    public List<FeePlan> plans() {
        return fees.plans();
    }

    @GetMapping("/setup/fee-events")
    public List<String> events() {
        return FeeService.EVENTS;
    }

    @PostMapping("/setup/fee-plans")
    public ResponseEntity<Object> createPlan(@RequestBody FeePlan p, @Operator String op) {
        return approvals.submit("FEE_PLAN_SAVE", "fee_plan", p.code(), "New fee plan " + p.code() + " (" + size(p) + " rules)",
                new FeePlanSave(null, true, p), op).toResponse();
    }

    @PutMapping("/setup/fee-plans/{code}")
    public ResponseEntity<Object> updatePlan(@PathVariable String code, @RequestBody FeePlan p, @Operator String op) {
        return approvals.submit("FEE_PLAN_SAVE", "fee_plan", code, "Change fee plan " + code + " (" + size(p) + " rules)",
                new FeePlanSave(code, false, p), op).toResponse();
    }

    @GetMapping("/setup/fx-rates")
    public List<FxRate> rates() {
        return fees.fxRates();
    }

    @PostMapping("/setup/fx-rates")
    public ResponseEntity<Object> createRate(@RequestBody FxRate r, @Operator String op) {
        return saveRate(r, op);
    }

    @PutMapping("/setup/fx-rates/{pair}")
    public ResponseEntity<Object> updateRate(@PathVariable String pair, @RequestBody FxRate r, @Operator String op) {
        String[] p = pair.split("-");
        return saveRate(new FxRate(pair, p[0], p.length > 1 ? p[1] : null, r.rate(), null, null), op);
    }

    private ResponseEntity<Object> saveRate(FxRate r, String op) {
        return approvals.submit("FX_RATE_SAVE", "fx_rate", r.baseCcy() + "-" + r.quoteCcy(),
                "FX rate 1 " + r.baseCcy() + " = " + r.rate() + " " + r.quoteCcy(), new FxRateSave(r), op).toResponse();
    }

    @GetMapping("/cards/{id}/fees")
    public Map<String, Object> cardFees(@PathVariable long id) {
        List<ChargeView> c = fees.charges(id);
        return Map.of("items", c, "total", c.stream().mapToLong(ChargeView::amount).sum());
    }

    private static int size(FeePlan p) {
        return p.rules() == null ? 0 : p.rules().size();
    }
}
