package com.cms.approval;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Maker-checker (four-eyes). An action whose policy requires approval is validated at once
 * (executed and rolled back, so the maker sees errors immediately), stored as PENDING, and
 * executed only when a different SUPERVISOR/ADMIN approves it. Actions whose policy does not
 * require approval run immediately. Policies are per action and changed by an ADMIN.
 */
@Service
public class ApprovalService {

    public record Handler<T>(Class<T> type, BiFunction<T, String, Object> run) {}

    /** What the maker gets back: the result, or the pending request. */
    public record Outcome(boolean pending, Long requestId, Object result) {
        public ResponseEntity<Object> toResponse() {
            return pending
                    ? ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("approvalPending", true, "requestId", requestId,
                            "message", "Sent for approval (request #" + requestId + ")"))
                    : ResponseEntity.ok(result);
        }
    }

    public record Policy(String action, String description, boolean required, OffsetDateTime updatedAt, String updatedBy) {}

    public record Request(long id, String action, String description, String entityType, String entityId, String summary,
                          JsonNode payload, String status, String maker, OffsetDateTime madeAt, String checker,
                          OffsetDateTime checkedAt, String checkerComment, String error) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final Map<String, Handler<?>> handlers = new ConcurrentHashMap<>();

    public ApprovalService(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.json = json;
        this.audit = audit;
    }

    public <T> void register(String action, Class<T> type, BiFunction<T, String, Object> run) {
        handlers.put(action, new Handler<>(type, run));
    }

    // ---------------- submit ----------------

    public Outcome submit(String action, String entityType, Object entityId, String summary, Object payload, String maker) {
        Handler<?> h = handler(action);
        if (!required(action)) {
            return new Outcome(false, null, tx.execute(s -> execute(h, payload, maker)));
        }
        // dry run: the same validation the checker's execution will do, then roll back
        tx.executeWithoutResult(s -> {
            s.setRollbackOnly();
            execute(h, payload, maker);
        });
        String p;
        try {
            p = json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("payload not serialisable", e);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO approval_request (action, entity_type, entity_id, summary, payload, maker)
                VALUES (?, ?, ?, ?, ?::jsonb, ?) RETURNING id
                """, Long.class, action, entityType, entityId == null ? null : String.valueOf(entityId),
                clip(summary, 256), p, maker);
        audit.record(maker, "APPROVAL_SUBMIT", "approval_request", id, Map.of("action", action, "summary", clip(summary, 120)));
        return new Outcome(true, id, null);
    }

    // ---------------- decide ----------------

    public Request approve(long id, String checker, String comment) {
        Request r = lockPending(id, checker);
        Handler<?> h = handler(r.action());
        try {
            tx.executeWithoutResult(s -> {
                Object payload = json.convertValue(r.payload(), h.type());
                execute(h, payload, r.maker());
                jdbc.update("""
                        UPDATE approval_request SET status = 'APPROVED', checker = ?, checked_at = now(), checker_comment = ?
                         WHERE id = ? AND status = 'PENDING'
                        """, checker, clip(comment, 256), id);
                audit.record(checker, "APPROVAL_APPROVE", "approval_request", id,
                        Map.of("action", r.action(), "maker", r.maker()));
            });
        } catch (IssuanceException | IllegalArgumentException e) {
            // the world changed since submission (e.g. balance, status); record why and keep the request closed
            jdbc.update("""
                    UPDATE approval_request SET status = 'FAILED', checker = ?, checked_at = now(), checker_comment = ?, error = ?
                     WHERE id = ? AND status = 'PENDING'
                    """, checker, clip(comment, 256), clip(e.getMessage(), 256), id);
            audit.record(checker, "APPROVAL_FAILED", "approval_request", id, Map.of("action", r.action(), "error", clip(e.getMessage(), 120)));
        }
        return get(id);
    }

    public Request reject(long id, String checker, String comment) {
        if (comment == null || comment.isBlank()) throw new IssuanceException("INVALID_REQUEST", "Give a reason for rejecting");
        Request r = lockPending(id, checker);
        jdbc.update("""
                UPDATE approval_request SET status = 'REJECTED', checker = ?, checked_at = now(), checker_comment = ?
                 WHERE id = ? AND status = 'PENDING'
                """, checker, clip(comment, 256), id);
        audit.record(checker, "APPROVAL_REJECT", "approval_request", id, Map.of("action", r.action(), "maker", r.maker()));
        return get(id);
    }

    /** The maker withdraws their own pending request. */
    public Request cancel(long id, String maker) {
        Request r = get(id);
        if (!"PENDING".equals(r.status())) throw new IssuanceException("INVALID_STATUS", "Request is " + r.status());
        if (!r.maker().equals(maker)) throw new IssuanceException("INVALID_REQUEST", "Only the maker can cancel a request");
        jdbc.update("UPDATE approval_request SET status = 'CANCELLED', checked_at = now() WHERE id = ? AND status = 'PENDING'", id);
        audit.record(maker, "APPROVAL_CANCEL", "approval_request", id, Map.of("action", r.action()));
        return get(id);
    }

    private Request lockPending(long id, String checker) {
        Request r = get(id);
        if (!"PENDING".equals(r.status())) throw new IssuanceException("INVALID_STATUS", "Request is " + r.status());
        if (r.maker().equals(checker)) throw new IssuanceException("FOUR_EYES", "You cannot approve your own request");
        return r;
    }

    @SuppressWarnings("unchecked")
    private <T> Object execute(Handler<T> h, Object payload, String actor) {
        T p = h.type().isInstance(payload) ? (T) payload : json.convertValue(payload, h.type());
        return h.run().apply(p, actor);
    }

    private Handler<?> handler(String action) {
        Handler<?> h = handlers.get(action);
        if (h == null) throw new IllegalStateException("No handler for action " + action);
        return h;
    }

    // ---------------- read ----------------

    public Request get(long id) {
        List<Request> r = jdbc.query(SELECT + " WHERE r.id = ?", (rs, i) -> map(rs), id);
        if (r.isEmpty()) throw new IssuanceException("NOT_FOUND", "Approval request not found");
        return r.get(0);
    }

    public Page<Request> search(String status, String maker, String action, String entityType, String entityId, int page, int size) {
        size = Page.size(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" AND r.status = ?"); args.add(status); }
        if (maker != null && !maker.isBlank()) { where.append(" AND r.maker = ?"); args.add(maker); }
        if (action != null && !action.isBlank()) { where.append(" AND r.action = ?"); args.add(action); }
        if (entityType != null && !entityType.isBlank()) { where.append(" AND r.entity_type = ?"); args.add(entityType); }
        if (entityId != null && !entityId.isBlank()) { where.append(" AND r.entity_id = ?"); args.add(entityId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM approval_request r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Page.offset(page, size));
        return new Page<>(jdbc.query(SELECT + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", (rs, i) -> map(rs),
                pageArgs.toArray()), total, page, size);
    }

    public long pendingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE status = 'PENDING'", Long.class);
    }

    // ---------------- policy ----------------

    public boolean required(String action) {
        Boolean b = jdbc.query("SELECT required FROM approval_policy WHERE action = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, action);
        if (b == null) throw new IllegalStateException("No approval policy for " + action);
        return b;
    }

    public List<Policy> policies() {
        return jdbc.query("SELECT action, description, required, updated_at, updated_by FROM approval_policy ORDER BY action",
                (rs, i) -> new Policy(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5)));
    }

    public List<Policy> setPolicy(String action, boolean required, String admin) {
        int n = jdbc.update("UPDATE approval_policy SET required = ?, updated_at = now(), updated_by = ? WHERE action = ?",
                required, admin, action);
        if (n == 0) throw new IssuanceException("NOT_FOUND", "Unknown action " + action);
        audit.record(admin, "APPROVAL_POLICY", "approval_policy", null, Map.of("action", action, "required", required));
        return policies();
    }

    private static final String SELECT = """
            SELECT r.id, r.action, p.description, r.entity_type, r.entity_id, r.summary, r.payload::text, r.status,
                   r.maker, r.made_at, r.checker, r.checked_at, r.checker_comment, r.error
              FROM approval_request r JOIN approval_policy p ON p.action = r.action
            """;

    private Request map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode payload;
        try {
            payload = json.readTree(rs.getString(7));
        } catch (Exception e) {
            payload = null;
        }
        return new Request(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), payload, rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class),
                rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getString(13), rs.getString(14));
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
