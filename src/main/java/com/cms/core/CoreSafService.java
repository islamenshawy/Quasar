package com.cms.core;

import com.cms.batch.BatchService;
import com.cms.batch.BatchService.Result;
import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.cms.core.CoreBankingClient.Posting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Store-and-forward to core banking (CMS-090). Rows are written inside the authorisation transaction, so a
 * queued posting exists only if the transaction committed. The CORE_SAF_REPLAY job sends them in id order,
 * per account: once one posting of an account is still undeliverable, later ones of that account wait, so a
 * reversal never reaches core before its debit. Replays are forced postings (core must not decline them).
 */
@Service
public class CoreSafService {

    private static final Logger log = LoggerFactory.getLogger(CoreSafService.class);
    private static final int BATCH = 200;

    /** What to send later. holdRef for CAPTURE / RELEASE, originalRef for REVERSAL. */
    public record SafPayload(Posting posting, String holdRef, String originalRef) {}

    public record SafView(long id, Long isoTxnId, long accountId, String accountNumber, String operation, String reference,
                          long amount, long fee, String currency, String type, String status, int attempts,
                          String lastError, OffsetDateTime nextAttemptAt, OffsetDateTime createdAt,
                          OffsetDateTime sentAt, String closedBy) {}

    private final JdbcTemplate jdbc;
    private final CoreBankingClient core;
    private final ObjectMapper json;
    private final AuditLog audit;

    public CoreSafService(JdbcTemplate jdbc, CoreBankingClient core, ObjectMapper json, AuditLog audit, BatchService batch) {
        this.jdbc = jdbc;
        this.core = core;
        this.json = json;
        this.audit = audit;
        batch.register("CORE_SAF_REPLAY", this::replay);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String operation, Long isoTxnId, long accountId, SafPayload p) {
        String payload;
        try {
            payload = json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO core_saf (iso_txn_id, account_id, operation, reference, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, isoTxnId, accountId, operation, p.posting().reference(), payload);
        log.info("Queued {} {} for core banking (account {})", operation, p.posting().reference(), accountId);
    }

    /** CORE_SAF_REPLAY: one call per due row, each status update its own statement (no long transaction). */
    Result replay(String actor) {
        record Row(long id, long accountId, String operation, String payload, int attempts) {}
        List<Row> due = jdbc.query("""
                SELECT id, account_id, operation, payload::text, attempts FROM core_saf
                 WHERE status = 'PENDING' ORDER BY id LIMIT ?
                """, (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5)), BATCH);
        Set<Long> waiting = new HashSet<>();
        int sent = 0, failed = 0, retry = 0;
        for (Row r : due) {
            if (waiting.contains(r.accountId())) continue;
            boolean notYet = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT next_attempt_at > now() FROM core_saf WHERE id = ?", Boolean.class, r.id()));
            if (notYet) {
                waiting.add(r.accountId());
                continue;
            }
            CoreBankingClient.Result res;
            try {
                res = send(r.operation(), json.readValue(r.payload(), SafPayload.class));
            } catch (Exception e) {
                res = CoreBankingClient.Result.unavailable("bad payload: " + e.getMessage());
            }
            if (res.approved()) {
                jdbc.update("UPDATE core_saf SET status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?", r.id());
                jdbc.update("UPDATE iso_transaction SET core_ref = COALESCE(core_ref, ?) WHERE id = (SELECT iso_txn_id FROM core_saf WHERE id = ?)",
                        res.coreRef(), r.id());
                sent++;
            } else if (res.unavailable()) {
                int minutes = Math.min(60, 1 << Math.min(r.attempts(), 6));
                jdbc.update("""
                        UPDATE core_saf SET attempts = attempts + 1, last_error = ?,
                               next_attempt_at = now() + make_interval(mins => ?) WHERE id = ?
                        """, clip(res.reason()), minutes, r.id());
                waiting.add(r.accountId());
                retry++;
            } else {
                // a forced posting refused by core needs operations: keep the account's later postings waiting
                jdbc.update("UPDATE core_saf SET status = 'FAILED', attempts = attempts + 1, last_error = ? WHERE id = ?",
                        clip("declined: " + res.reason()), r.id());
                waiting.add(r.accountId());
                failed++;
            }
        }
        return new Result(sent + failed + retry, sent + " sent, " + retry + " to retry, " + failed + " failed");
    }

    private CoreBankingClient.Result send(String op, SafPayload p) {
        return switch (op) {
            case "DEBIT" -> core.debit(p.posting());
            case "CREDIT" -> core.credit(p.posting());
            case "HOLD" -> core.hold(p.posting());
            case "CAPTURE" -> p.holdRef() == null ? core.debit(p.posting()) : core.capture(p.holdRef(), p.posting());
            case "RELEASE" -> core.release(p.holdRef(), p.posting().reference());
            case "REVERSAL" -> core.reverse(p.originalRef(), p.posting());
            default -> throw new IllegalArgumentException(op);
        };
    }

    // ---------------- operations screens ----------------

    public Page<SafView> list(String status, Long accountId, int page, int size) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND s.status = ?"); args.add(status); }
        if (accountId != null) { where.append(" AND s.account_id = ?"); args.add(accountId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM core_saf s" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        List<SafView> items = jdbc.query(SELECT + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
        return new Page<>(items, total, page, size);
    }

    public Map<String, Long> counts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>(Map.of("PENDING", 0L, "FAILED", 0L));
        jdbc.query("SELECT status, count(*) FROM core_saf WHERE status IN ('PENDING','FAILED') GROUP BY status",
                rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    /** Sends a pending or failed posting at the next replay (failed ones go back to pending). */
    @Transactional
    public SafView retry(long id, String operator) {
        int n = jdbc.update("""
                UPDATE core_saf SET status = 'PENDING', next_attempt_at = now() WHERE id = ? AND status IN ('PENDING','FAILED')
                """, id);
        if (n == 0) throw new IssuanceException("INVALID_STATUS", "Only pending or failed postings can be retried");
        audit.record(operator, "CORE_SAF_RETRY", "core_saf", id, Map.of());
        return get(id);
    }

    /** Maker-checker action CORE_SAF_CANCEL: the posting will never reach core; settle it manually. */
    @Transactional
    public SafView cancel(long id, String reason, String operator) {
        if (reason == null || reason.isBlank()) throw new IssuanceException("INVALID_REQUEST", "reason is required");
        int n = jdbc.update("""
                UPDATE core_saf SET status = 'CANCELLED', closed_by = ?, last_error = ? WHERE id = ? AND status IN ('PENDING','FAILED')
                """, operator, clip("cancelled: " + reason.trim()), id);
        if (n == 0) throw new IssuanceException("INVALID_STATUS", "Only pending or failed postings can be cancelled");
        audit.record(operator, "CORE_SAF_CANCEL", "core_saf", id, Map.of("reason", reason.trim()));
        return get(id);
    }

    public SafView get(long id) {
        return jdbc.query(SELECT + " WHERE s.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Queued posting not found"));
    }

    private static final String SELECT = """
            SELECT s.id, s.iso_txn_id, s.account_id, a.account_number, s.operation, s.reference,
                   COALESCE((s.payload->'posting'->>'amount')::bigint, 0), COALESCE((s.payload->'posting'->>'fee')::bigint, 0),
                   s.payload->'posting'->>'currency', s.payload->'posting'->>'type', s.status, s.attempts, s.last_error,
                   s.next_attempt_at, s.created_at, s.sent_at, s.closed_by
              FROM core_saf s JOIN account a ON a.id = s.account_id""";

    private static final org.springframework.jdbc.core.RowMapper<SafView> MAPPER = (rs, i) -> new SafView(
            rs.getLong(1), (Long) rs.getObject(2), rs.getLong(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getInt(12), rs.getString(13), rs.getObject(14, OffsetDateTime.class),
            rs.getObject(15, OffsetDateTime.class), rs.getObject(16, OffsetDateTime.class), rs.getString(17));

    private static String clip(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }
}
