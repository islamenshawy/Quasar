package com.cms.api;

import com.cms.approval.ApprovalActions.*;
import com.cms.approval.ApprovalService;
import com.cms.common.Settings;
import com.cms.common.Settings.Setting;
import com.cms.reference.ReferenceDataService;
import com.cms.reference.ReferenceDataService.*;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Configuration screens: currencies, segments, account types, number sequences, card products,
 * eligibility and settings. Records are deactivated, never deleted.
 * Writes need SUPERVISOR or ADMIN and go through maker-checker: 200 with the result when the action's
 * policy does not require approval, 202 with the request id when it does.
 */
@RestController
@RequestMapping("/api/admin/setup")
public class SetupController {

    private final ReferenceDataService ref;
    private final Settings settings;
    private final ApprovalService approvals;

    public SetupController(ReferenceDataService ref, Settings settings, ApprovalService approvals) {
        this.ref = ref;
        this.settings = settings;
        this.approvals = approvals;
    }

    // ---------- currencies ----------

    @GetMapping("/currencies")
    public List<Currency> currencies() { return ref.currencies(); }

    @PostMapping("/currencies")
    public ResponseEntity<Object> createCurrency(@RequestBody Currency c, @Operator String op) {
        return approvals.submit("CURRENCY_SAVE", "currency", c.code(), "New currency " + c.code() + " " + c.name(),
                new CurrencySave(null, true, c), op).toResponse();
    }

    @PutMapping("/currencies/{code}")
    public ResponseEntity<Object> updateCurrency(@PathVariable String code, @RequestBody Currency c, @Operator String op) {
        return approvals.submit("CURRENCY_SAVE", "currency", code, "Change currency " + code + (c.active() ? "" : " (inactive)"),
                new CurrencySave(code, false, c), op).toResponse();
    }

    // ---------- segments ----------

    @GetMapping("/segments")
    public List<Segment> segments() { return ref.segments(); }

    @PostMapping("/segments")
    public ResponseEntity<Object> createSegment(@RequestBody Segment s, @Operator String op) {
        return approvals.submit("SEGMENT_SAVE", "segment", s.code(), "New segment " + s.code() + " " + s.name(),
                new SegmentSave(null, true, s), op).toResponse();
    }

    @PutMapping("/segments/{code}")
    public ResponseEntity<Object> updateSegment(@PathVariable String code, @RequestBody Segment s, @Operator String op) {
        return approvals.submit("SEGMENT_SAVE", "segment", code, "Change segment " + code + (s.active() ? "" : " (inactive)"),
                new SegmentSave(code, false, s), op).toResponse();
    }

    // ---------- account types ----------

    @GetMapping("/account-types")
    public List<AccountType> accountTypes() { return ref.accountTypes(); }

    @PostMapping("/account-types")
    public ResponseEntity<Object> createAccountType(@RequestBody AccountType t, @Operator String op) {
        return approvals.submit("ACCOUNT_TYPE_SAVE", "account_type", t.code(), "New account type " + t.code() + " " + t.name(),
                new AccountTypeSave(null, true, t), op).toResponse();
    }

    @PutMapping("/account-types/{code}")
    public ResponseEntity<Object> updateAccountType(@PathVariable String code, @RequestBody AccountType t, @Operator String op) {
        return approvals.submit("ACCOUNT_TYPE_SAVE", "account_type", code, "Change account type " + code
                + " (" + t.numberSource() + ", " + t.currencies() + ")", new AccountTypeSave(code, false, t), op).toResponse();
    }

    // ---------- number sequences ----------

    @GetMapping("/number-sequences")
    public List<NumberSequence> numberSequences() { return ref.numberSequences(); }

    @PostMapping("/number-sequences")
    public ResponseEntity<Object> createNumberSequence(@RequestBody NumberSequence s, @Operator String op) {
        return approvals.submit("NUMBER_SEQUENCE_SAVE", "number_sequence", s.code(), "New number sequence " + s.code(),
                new SequenceSave(null, true, s), op).toResponse();
    }

    @PutMapping("/number-sequences/{code}")
    public ResponseEntity<Object> updateNumberSequence(@PathVariable String code, @RequestBody NumberSequence s, @Operator String op) {
        return approvals.submit("NUMBER_SEQUENCE_SAVE", "number_sequence", code, "Change number sequence " + code
                + " (prefix " + s.prefix() + ", next " + s.nextValue() + ")", new SequenceSave(code, false, s), op).toResponse();
    }

    // ---------- products ----------

    @GetMapping("/products")
    public List<Product> products() { return ref.products(); }

    @GetMapping("/products/{code}")
    public Product product(@PathVariable String code) { return ref.product(code); }

    @PostMapping("/products")
    public ResponseEntity<Object> createProduct(@RequestBody ProductRequest r, @Operator String op) {
        return approvals.submit("PRODUCT_CREATE", "card_product", r.code(), "New card product " + r.code() + " " + r.name()
                + " (BIN " + r.bin() + ")", new ProductSave(null, r), op).toResponse();
    }

    @PutMapping("/products/{code}")
    public ResponseEntity<Object> updateProduct(@PathVariable String code, @RequestBody ProductRequest r, @Operator String op) {
        return approvals.submit("PRODUCT_UPDATE", "card_product", code, "Change card product " + code,
                new ProductSave(code, r), op).toResponse();
    }

    @GetMapping("/products/{code}/eligibility")
    public List<Eligibility> eligibility(@PathVariable String code) { return ref.eligibility(code); }

    @PutMapping("/products/{code}/eligibility")
    public ResponseEntity<Object> saveEligibility(@PathVariable String code, @RequestBody List<Eligibility> rows, @Operator String op) {
        return approvals.submit("ELIGIBILITY_UPDATE", "card_product", code, "Eligibility of " + code + ": " + rows.size()
                + " combination(s)", new EligibilitySave(code, rows), op).toResponse();
    }

    @GetMapping("/hsm-keys")
    public List<HsmKeyRef> hsmKeys() { return ref.hsmKeys(); }

    // ---------- settings ----------

    @GetMapping("/settings")
    public List<Setting> settings() { return settings.all(); }

    @PutMapping("/settings/{key}")
    public ResponseEntity<Object> updateSetting(@PathVariable String key, @RequestBody Map<String, String> body, @Operator String op) {
        return approvals.submit("SETTING_UPDATE", "setting", key, "Set " + key + " = " + body.get("value"),
                new SettingUpdate(key, body.get("value")), op).toResponse();
    }
}
