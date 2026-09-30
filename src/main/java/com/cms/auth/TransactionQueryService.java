package com.cms.auth;

import com.cms.card.IssuanceException;
import com.cms.common.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read side of iso_transaction for the console: search, detail and today's figures. */
@Service
public class TransactionQueryService {

    public record TxnView(long id, String mti, String txnType, String channel, String maskedPan, Long cardId,
                          Long accountId, String accountNumber, Long customerId, String customerName,
                          Long amount, String currencyCode, int exponent, long feeAmount, String actionCode,
                          String actionText, boolean approved, String authId, String declineReason, String stan,
                          String rrn, String terminalId, String acquirerId, String merchantType, String cardAcceptor,
                          boolean advice, boolean reversed, Long amountCompleted, String originalKey,
                          Long ledgerAfter, Long availableAfter, OffsetDateTime receivedAt, OffsetDateTime respondedAt) {}

    private static final String SELECT = """
            SELECT t.id, t.mti, t.txn_type, t.channel,
                   CASE WHEN k.id IS NULL THEN '**** ' || COALESCE(t.pan_last4, '????')
                        ELSE k.pan_first6 || repeat('*', p.pan_length - 10) || k.pan_last4 END,
                   t.card_id, t.account_id, a.account_number, a.customer_id, cu.full_name,
                   t.amount, COALESCE(t.currency_code, a.currency_code), COALESCE(cur.exponent, 2), t.fee_amount,
                   t.action_code, t.auth_id, t.decline_reason, t.stan, t.rrn, t.terminal_id, t.acquirer_id,
                   t.merchant_type, t.card_acceptor, t.is_advice, t.reversed, t.amount_completed, t.original_key,
                   t.ledger_after, t.available_after, t.received_at, t.responded_at
              FROM iso_transaction t
              LEFT JOIN card k          ON k.id = t.card_id
              LEFT JOIN card_product p  ON p.id = k.product_id
              LEFT JOIN account a       ON a.id = t.account_id
              LEFT JOIN customer cu     ON cu.id = a.customer_id
              LEFT JOIN currency cur    ON cur.code = COALESCE(t.currency_code, a.currency_code)
            """;

    private final JdbcTemplate jdbc;

    public TransactionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TxnView get(long id) {
        List<TxnView> t = jdbc.query(SELECT + " WHERE t.id = ?", (rs, i) -> map(rs), id);
        if (t.isEmpty()) throw new IssuanceException("NOT_FOUND", "Transaction not found");
        return t.get(0);
    }

    /** result: APPROVED, DECLINED or empty. q matches STAN, RRN, terminal, auth id or last 4 digits. */
    public Page<TxnView> search(String q, Long cardId, Long accountId, String type, String channel, String result,
                                LocalDate from, LocalDate to, int page, int size) {
        size = Page.size(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String v = q.trim();
            where.append(" AND (t.stan = ? OR t.rrn = ? OR t.terminal_id = ? OR t.auth_id = ? OR t.pan_last4 = ?)");
            for (int i = 0; i < 5; i++) args.add(v);
        }
        if (cardId != null) { where.append(" AND t.card_id = ?"); args.add(cardId); }
        if (accountId != null) { where.append(" AND t.account_id = ?"); args.add(accountId); }
        if (type != null && !type.isBlank()) { where.append(" AND t.txn_type = ?"); args.add(type); }
        if (channel != null && !channel.isBlank()) { where.append(" AND t.channel = ?"); args.add(channel); }
        if ("APPROVED".equals(result)) where.append(" AND t.action_code IN ('000','400')");
        if ("DECLINED".equals(result)) where.append(" AND t.action_code NOT IN ('000','400')");
        if (from != null) { where.append(" AND t.received_at >= ?"); args.add(from); }
        if (to != null) { where.append(" AND t.received_at < ?"); args.add(to.plusDays(1)); }

        long total = jdbc.queryForObject("SELECT count(*) FROM iso_transaction t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Page.offset(page, size));
        List<TxnView> items = jdbc.query(SELECT + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        return new Page<>(items, total, page, size);
    }

    /** Today's counts and approved volume per currency, plus the most frequent decline codes. */
    public Map<String, Object> today() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", jdbc.queryForObject(
                "SELECT count(*) FROM iso_transaction WHERE received_at >= CURRENT_DATE AND action_code IN ('000','400')", Long.class));
        m.put("declined", jdbc.queryForObject(
                "SELECT count(*) FROM iso_transaction WHERE received_at >= CURRENT_DATE AND action_code NOT IN ('000','400')", Long.class));
        m.put("volume", jdbc.queryForList("""
                SELECT currency_code AS currency, txn_type AS type, count(*) AS count, sum(amount) AS amount
                  FROM iso_transaction
                 WHERE received_at >= CURRENT_DATE AND action_code = '000' AND NOT reversed
                   AND txn_type IN ('WITHDRAWAL','PURCHASE','COMPLETION')
                 GROUP BY currency_code, txn_type ORDER BY currency_code, txn_type
                """));
        m.put("topDeclines", jdbc.queryForList("""
                SELECT action_code AS code, count(*) AS count FROM iso_transaction
                 WHERE received_at >= CURRENT_DATE AND action_code NOT IN ('000','400')
                 GROUP BY action_code ORDER BY count(*) DESC LIMIT 5
                """));
        return m;
    }

    private static TxnView map(ResultSet rs) throws SQLException {
        String code = rs.getString(15);
        return new TxnView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                (Long) rs.getObject(6), (Long) rs.getObject(7), rs.getString(8), (Long) rs.getObject(9), rs.getString(10),
                (Long) rs.getObject(11), rs.getString(12), rs.getInt(13), rs.getLong(14), code,
                code == null ? "No response" : ActionCode.text(code), ActionCode.isApproval(code), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21),
                rs.getString(22), rs.getString(23), rs.getBoolean(24), rs.getBoolean(25), (Long) rs.getObject(26),
                rs.getString(27), (Long) rs.getObject(28), (Long) rs.getObject(29),
                rs.getObject(30, OffsetDateTime.class), rs.getObject(31, OffsetDateTime.class));
    }
}
