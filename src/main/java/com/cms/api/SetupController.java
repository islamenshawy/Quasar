package com.cms.api;

import com.cms.common.Settings;
import com.cms.common.Settings.Setting;
import com.cms.reference.ReferenceDataService;
import com.cms.reference.ReferenceDataService.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Configuration screens: currencies, segments, account types, number sequences, card products,
 * eligibility and settings. Records are deactivated, never deleted.
 * TEST ONLY operator header; these endpoints need a SUPERVISOR role once CMS-060 lands.
 */
@RestController
@RequestMapping("/api/admin/setup")
public class SetupController {

    private final ReferenceDataService ref;
    private final Settings settings;

    public SetupController(ReferenceDataService ref, Settings settings) {
        this.ref = ref;
        this.settings = settings;
    }

    // ---------- currencies ----------

    @GetMapping("/currencies")
    public List<Currency> currencies() { return ref.currencies(); }

    @PostMapping("/currencies")
    public Currency createCurrency(@RequestBody Currency c, @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveCurrency(null, c, true, op);
    }

    @PutMapping("/currencies/{code}")
    public Currency updateCurrency(@PathVariable String code, @RequestBody Currency c,
                                   @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveCurrency(code, c, false, op);
    }

    // ---------- segments ----------

    @GetMapping("/segments")
    public List<Segment> segments() { return ref.segments(); }

    @PostMapping("/segments")
    public Segment createSegment(@RequestBody Segment s, @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveSegment(null, s, true, op);
    }

    @PutMapping("/segments/{code}")
    public Segment updateSegment(@PathVariable String code, @RequestBody Segment s,
                                 @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveSegment(code, s, false, op);
    }

    // ---------- account types ----------

    @GetMapping("/account-types")
    public List<AccountType> accountTypes() { return ref.accountTypes(); }

    @PostMapping("/account-types")
    public AccountType createAccountType(@RequestBody AccountType t,
                                         @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveAccountType(null, t, true, op);
    }

    @PutMapping("/account-types/{code}")
    public AccountType updateAccountType(@PathVariable String code, @RequestBody AccountType t,
                                         @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveAccountType(code, t, false, op);
    }

    // ---------- number sequences ----------

    @GetMapping("/number-sequences")
    public List<NumberSequence> numberSequences() { return ref.numberSequences(); }

    @PostMapping("/number-sequences")
    public NumberSequence createNumberSequence(@RequestBody NumberSequence s,
                                               @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveNumberSequence(null, s, true, op);
    }

    @PutMapping("/number-sequences/{code}")
    public NumberSequence updateNumberSequence(@PathVariable String code, @RequestBody NumberSequence s,
                                               @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveNumberSequence(code, s, false, op);
    }

    // ---------- products ----------

    @GetMapping("/products")
    public List<Product> products() { return ref.products(); }

    @GetMapping("/products/{code}")
    public Product product(@PathVariable String code) { return ref.product(code); }

    @PostMapping("/products")
    public Product createProduct(@RequestBody ProductRequest r,
                                 @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.createProduct(r, op);
    }

    @PutMapping("/products/{code}")
    public Product updateProduct(@PathVariable String code, @RequestBody ProductRequest r,
                                 @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.updateProduct(code, r, op);
    }

    @GetMapping("/products/{code}/eligibility")
    public List<Eligibility> eligibility(@PathVariable String code) { return ref.eligibility(code); }

    @PutMapping("/products/{code}/eligibility")
    public List<Eligibility> saveEligibility(@PathVariable String code, @RequestBody List<Eligibility> rows,
                                             @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return ref.saveEligibility(code, rows, op);
    }

    @GetMapping("/hsm-keys")
    public List<HsmKeyRef> hsmKeys() { return ref.hsmKeys(); }

    // ---------- settings ----------

    @GetMapping("/settings")
    public List<Setting> settings() { return settings.all(); }

    @PutMapping("/settings/{key}")
    public List<Setting> updateSetting(@PathVariable String key, @RequestBody Map<String, String> body,
                                       @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        settings.set(key, body.get("value"), op);
        return settings.all();
    }
}
