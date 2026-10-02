package com.cms.emv;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Issuer scripts (CMS-110): commands queued for a card's chip and delivered in field 55 on the card's next
 * online chip transaction whose ARQC verified (at most 2 per answer). The card reports the outcome in tag 9F5B
 * on a later transaction; SENT scripts then become APPLIED or FAILED.
 */
@Service
public class IssuerScriptService {

    public static final List<String> COMMANDS = List.of("PIN_UNBLOCK", "APPLICATION_BLOCK", "APPLICATION_UNBLOCK", "UPDATE_OFFLINE_LIMIT");
    private static final int PER_ANSWER = 2;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    public record Script(long id, long cardId, String command, String value, String scriptId, String status, String apdu,
                         String reason, Long sentTxnId, OffsetDateTime sentAt, OffsetDateTime resultAt,
                         OffsetDateTime createdAt, String createdBy) {}

    private static final RowMapper<Script> MAPPER = (rs, i) -> new Script(rs.getLong(1), rs.getLong(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), (Long) rs.getObject(9),
            rs.getObject(10, OffsetDateTime.class), rs.getObject(11, OffsetDateTime.class),
            rs.getObject(12, OffsetDateTime.class), rs.getString(13));
    private static final String SELECT = """
            SELECT id, card_id, command, value, script_id, status, apdu, reason, sent_txn_id, sent_at, result_at,
                   created_at, created_by FROM issuer_script""";

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public IssuerScriptService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Maker-checker action CHIP_SCRIPT (direct by default). */
    @Transactional
    public Script queue(long cardId, String command, String value, String reason, String op) {
        if (!COMMANDS.contains(String.valueOf(command))) throw bad("Command must be one of " + COMMANDS);
        if (reason == null || reason.isBlank()) throw bad("reason is required");
        record C(String status, String imkSmi) {}
        C c = jdbc.query("""
                SELECT k.status, p.imk_smi_key_name FROM card k JOIN card_product p ON p.id = k.product_id WHERE k.id = ?
                """, rs -> rs.next() ? new C(rs.getString(1), rs.getString(2)) : null, cardId);
        if (c == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (c.imkSmi() == null) throw bad("The card's product has no IMK-SMI key: scripts cannot be signed");
        if (List.of("EXPIRED", "CANCELLED").contains(c.status())) throw new IssuanceException("INVALID_STATUS", "Card is " + c.status());
        String v = null;
        if ("UPDATE_OFFLINE_LIMIT".equals(command)) {
            if (value == null || !value.matches("[0-9]{1,3}") || Integer.parseInt(value) > 255) throw bad("Offline limit must be 0-255");
            v = String.valueOf(Integer.parseInt(value));
        }
        Integer queued = jdbc.queryForObject("SELECT count(*) FROM issuer_script WHERE card_id = ? AND command = ? AND status = 'QUEUED'",
                Integer.class, cardId, command);
        if (queued > 0) throw new IssuanceException("DUPLICATE", command + " is already waiting for this card");
        byte[] id = new byte[4];
        random.nextBytes(id);
        long scriptId = jdbc.queryForObject("""
                INSERT INTO issuer_script (card_id, command, value, script_id, reason, created_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, cardId, command, v, HEX.formatHex(id), reason.trim(), op);
        audit.record(op, "CHIP_SCRIPT_QUEUED", "card", cardId, Map.of("command", command, "reason", reason.trim()));
        return get(scriptId);
    }

    @Transactional
    public Script cancel(long id, String op) {
        Script s = get(id);
        if (jdbc.update("UPDATE issuer_script SET status = 'CANCELLED', result_at = now() WHERE id = ? AND status = 'QUEUED'", id) == 0) {
            throw new IssuanceException("INVALID_STATUS", "Only queued scripts can be cancelled");
        }
        audit.record(op, "CHIP_SCRIPT_CANCELLED", "card", s.cardId(), Map.of("command", s.command()));
        return get(id);
    }

    public List<Script> list(long cardId) {
        return jdbc.query(SELECT + " WHERE card_id = ? ORDER BY id DESC", MAPPER, cardId);
    }

    public Script get(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Script not found"));
    }

    // ---------------- authorisation side (caller's transaction) ----------------

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Script> due(long cardId) {
        return jdbc.query(SELECT + " WHERE card_id = ? AND status = 'QUEUED' ORDER BY id LIMIT ? FOR UPDATE", MAPPER, cardId, PER_ANSWER);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sent(long id, String apduHex, long txnId) {
        jdbc.update("UPDATE issuer_script SET status = 'SENT', apdu = ?, sent_txn_id = ?, sent_at = now() WHERE id = ?", apduHex, txnId, id);
    }

    /** Results the card reported (9F5B): 2 = successful -> APPLIED, otherwise FAILED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int results(long cardId, List<EmvService.ScriptResult> results, long txnId) {
        int n = 0;
        for (EmvService.ScriptResult r : results) {
            n += jdbc.update("""
                    UPDATE issuer_script SET status = ?, result_txn_id = ?, result_at = now()
                     WHERE card_id = ? AND script_id = ? AND status = 'SENT'
                    """, r.result() == 2 ? "APPLIED" : "FAILED", txnId, cardId, r.scriptId());
        }
        return n;
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }
}
