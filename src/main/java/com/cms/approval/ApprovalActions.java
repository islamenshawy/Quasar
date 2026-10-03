package com.cms.approval;

import com.cms.account.AccountService;
import com.cms.batch.BatchService;
import com.cms.card.CardAdminService;
import com.cms.card.CardIssuanceService;
import com.cms.card.CardAdminService.ControlsRequest;
import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Settings;
import com.cms.core.CoreSafService;
import com.cms.digital.TokenService;
import com.cms.emv.IssuerScriptService;
import com.cms.fee.FeeService;
import com.cms.fraud.FraudService;
import com.cms.notify.NotificationService;
import com.cms.customer.CustomerService;
import com.cms.ledger.LedgerService;
import com.cms.reference.ReferenceDataService;
import com.cms.reference.ReferenceDataService.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The actions that go through maker-checker, with their payloads and how to run them.
 * Payloads are stored as JSON and must never hold PAN, PIN or key material.
 */
@Component
public class ApprovalActions {

    public record CurrencySave(String code, boolean create, Currency data) {}
    public record SegmentSave(String code, boolean create, Segment data) {}
    public record AccountTypeSave(String code, boolean create, AccountType data) {}
    public record SequenceSave(String code, boolean create, NumberSequence data) {}
    public record SettingUpdate(String key, String value) {}
    public record ProductSave(String code, ProductRequest data) {}
    public record EligibilitySave(String productCode, List<Eligibility> rows) {}
    public record LedgerEntry(long accountId, String type, long amount, String narrative) {}
    public record HoldRelease(long holdId, String reason) {}
    public record CardLimits(long cardId, ControlsRequest data) {}
    public record StatusChange(long id, String status, String reason) {}
    public record Reason(long id, String reason) {}
    public record CardReplace(long cardId, String reason, boolean samePan, String embossingName, String branchId) {}
    public record BatchJobUpdate(String code, String cron, boolean enabled) {}
    public record SafCancel(long id, String reason) {}
    public record FraudRuleSave(String code, boolean create, FraudService.Rule rule) {}
    public record AlertResolve(long id, FraudService.Resolution resolution) {}
    public record FeePlanSave(String code, boolean create, FeeService.FeePlan plan) {}
    public record FxRateSave(FeeService.FxRate rate) {}
    public record TemplateSave(NotificationService.Template template) {}
    public record ChipScript(long cardId, String command, String value, String reason) {}
    public record TokenLifecycle(long tokenId, String action, String reason) {}

    public ApprovalActions(ApprovalService approvals, ReferenceDataService ref, Settings settings,
                           LedgerService ledger, CardAdminService cards, CustomerService customers,
                           AccountService accounts, AuditLog audit, CardIssuanceService issuance, BatchService batch,
                           CoreSafService saf, FraudService fraud, FeeService fees,
                           NotificationService notifications, IssuerScriptService scripts, TokenService tokens) {
        approvals.register("CURRENCY_SAVE", CurrencySave.class, (p, op) -> ref.saveCurrency(p.code(), p.data(), p.create(), op));
        approvals.register("SEGMENT_SAVE", SegmentSave.class, (p, op) -> ref.saveSegment(p.code(), p.data(), p.create(), op));
        approvals.register("ACCOUNT_TYPE_SAVE", AccountTypeSave.class, (p, op) -> ref.saveAccountType(p.code(), p.data(), p.create(), op));
        approvals.register("NUMBER_SEQUENCE_SAVE", SequenceSave.class, (p, op) -> ref.saveNumberSequence(p.code(), p.data(), p.create(), op));
        approvals.register("SETTING_UPDATE", SettingUpdate.class, (p, op) -> {
            settings.set(p.key(), p.value(), op);
            return settings.all();
        });
        approvals.register("PRODUCT_CREATE", ProductSave.class, (p, op) -> ref.createProduct(p.data(), op));
        approvals.register("PRODUCT_UPDATE", ProductSave.class, (p, op) -> ref.updateProduct(p.code(), p.data(), op));
        approvals.register("ELIGIBILITY_UPDATE", EligibilitySave.class, (p, op) -> ref.saveEligibility(p.productCode(), p.rows(), op));
        approvals.register("LEDGER_ENTRY", LedgerEntry.class, (p, op) -> {
            var journal = ledger.manualEntry(p.accountId(), p.type(), p.amount(), p.narrative(), op);
            audit.record(op, "LEDGER_" + p.type(), "account", p.accountId(),
                    Map.of("amount", p.amount(), "narrative", p.narrative(), "journal", journal.toString()));
            return Map.of("journalId", journal, "balance", ledger.balance(p.accountId()));
        });
        approvals.register("HOLD_RELEASE", HoldRelease.class, (p, op) -> {
            if (p.reason() == null || p.reason().isBlank()) throw new IssuanceException("INVALID_REQUEST", "reason is required");
            if (!ledger.closeHold(p.holdId(), "RELEASED", 0, p.reason().trim(), op)) {
                throw new IssuanceException("INVALID_STATUS", "Hold is not open");
            }
            audit.record(op, "RELEASE_HOLD", "hold", p.holdId(), Map.of("reason", p.reason().trim()));
            return Map.of("released", true);
        });
        approvals.register("CARD_LIMITS", CardLimits.class, (p, op) -> cards.updateControls(p.cardId(), p.data(), op));
        approvals.register("CUSTOMER_STATUS", StatusChange.class, (p, op) -> customers.changeStatus(p.id(), p.status(), p.reason(), op));
        approvals.register("ACCOUNT_STATUS", StatusChange.class, (p, op) -> accounts.changeStatus(p.id(), p.status(), p.reason(), op));
        approvals.register("CARD_STATUS", StatusChange.class, (p, op) -> cards.changeStatus(p.id(), p.status(), p.reason(), op));
        approvals.register("RESET_PIN_TRIES", Reason.class, (p, op) -> cards.resetPinTries(p.id(), p.reason(), op));
        // the full PAN of a new-number replacement is returned only when the action runs directly (no approval);
        // after an approval the maker uses "Show card number for printing" on the pending card
        approvals.register("CARD_REPLACE", CardReplace.class, (p, op) -> issuance.issueReplacement(p.cardId(), p.reason(),
                p.samePan(), p.embossingName(), p.branchId(), "REPLACEMENT", op));
        approvals.register("BATCH_JOB_UPDATE", BatchJobUpdate.class, (p, op) -> batch.update(p.code(), p.cron(), p.enabled(), op));
        approvals.register("CORE_SAF_CANCEL", SafCancel.class, (p, op) -> saf.cancel(p.id(), p.reason(), op));
        approvals.register("FRAUD_RULE_SAVE", FraudRuleSave.class, (p, op) -> fraud.saveRule(p.code(), p.create(), p.rule(), op));
        approvals.register("FRAUD_ALERT_RESOLVE", AlertResolve.class, (p, op) -> fraud.resolve(p.id(), p.resolution(), op));
        approvals.register("FEE_PLAN_SAVE", FeePlanSave.class, (p, op) -> fees.savePlan(p.code(), p.create(), p.plan(), op));
        approvals.register("FX_RATE_SAVE", FxRateSave.class, (p, op) -> fees.saveFxRate(p.rate(), op));
        approvals.register("NOTIFICATION_TEMPLATE_SAVE", TemplateSave.class, (p, op) -> notifications.saveTemplate(p.template(), op));
        approvals.register("CHIP_SCRIPT", ChipScript.class, (p, op) -> scripts.queue(p.cardId(), p.command(), p.value(), p.reason(), op));
        approvals.register("TOKEN_LIFECYCLE", TokenLifecycle.class, (p, op) -> tokens.issuerAction(p.tokenId(), p.action(), p.reason(), op));
    }
}
