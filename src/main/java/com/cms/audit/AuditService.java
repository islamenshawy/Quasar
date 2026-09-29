package com.cms.audit;

import com.cms.common.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read side of audit_log for the console: filtered audit trail and dashboard figures. */
@Service
public class AuditService {

    public record AuditEntry(long id, String actor, String action, String entityType, Long entityId,
                             JsonNode details, OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Page<AuditEntry> search(String actor, String action, String entityType, Long entityId,
                                   LocalDate from, LocalDate to, int page, int size) {
        size = Page.size(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) { where.append(" AND actor ILIKE ?"); args.add("%" + actor.trim() + "%"); }
        if (action != null && !action.isBlank()) { where.append(" AND action = ?"); args.add(action); }
        if (entityType != null && !entityType.isBlank()) { where.append(" AND entity_type = ?"); args.add(entityType); }
        if (entityId != null) { where.append(" AND entity_id = ?"); args.add(entityId); }
        if (from != null) { where.append(" AND created_at >= ?"); args.add(from); }
        if (to != null) { where.append(" AND created_at < ?"); args.add(to.plusDays(1)); }

        long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Page.offset(page, size));
        List<AuditEntry> items = jdbc.query("""
                SELECT id, actor, action, entity_type, entity_id, details::text, created_at FROM audit_log
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Long) rs.getObject(5), parse(rs.getString(6)), rs.getObject(7, OffsetDateTime.class)),
                pageArgs.toArray());
        return new Page<>(items, total, page, size);
    }

    public List<String> actions() {
        return jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class);
    }

    /** Counts per status for customers, accounts and cards, plus today's activity. */
    public Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("customers", countBy("SELECT status, count(*) FROM customer GROUP BY status"));
        d.put("accounts", countBy("SELECT status, count(*) FROM account GROUP BY status"));
        d.put("cards", countBy("SELECT status, count(*) FROM card GROUP BY status"));
        d.put("cardsByProduct", countBy("""
                SELECT p.code, count(k.id) FROM card_product p LEFT JOIN card k ON k.product_id = p.id
                 GROUP BY p.code ORDER BY p.code
                """));
        d.put("issuedToday", jdbc.queryForObject(
                "SELECT count(*) FROM card WHERE created_at >= CURRENT_DATE", Long.class));
        d.put("activatedToday", jdbc.queryForObject(
                "SELECT count(*) FROM card WHERE activated_at >= CURRENT_DATE", Long.class));
        d.put("customersToday", jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE created_at >= CURRENT_DATE", Long.class));
        d.put("lowRangeProducts", jdbc.queryForList("""
                SELECT code, name, range_end - next_sequence + 1 AS remaining FROM card_product
                 WHERE active AND range_end - next_sequence + 1 < GREATEST(1000, (range_end - range_start + 1) / 10)
                 ORDER BY remaining
                """));
        return d;
    }

    private Map<String, Long> countBy(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    private JsonNode parse(String s) {
        if (s == null) return null;
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(s);
        }
    }
}
