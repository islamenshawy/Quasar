package com.cms.card;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next PAN from a product's account range:
 *   PAN = BIN + zero-padded range number + Luhn check digit
 *
 * The product row is locked (SELECT ... FOR UPDATE) so concurrent kiosks can
 * never receive the same number. Must run inside the caller's transaction so a
 * rollback also returns the number (gaps are acceptable, duplicates are not).
 */
@Component
public class PanAllocator {

    private final JdbcTemplate jdbc;

    public PanAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextPan(long productId) {
        record Range(String bin, int panLength, long next, long end) {}

        Range r = jdbc.queryForObject("""
                SELECT bin, pan_length, next_sequence, range_end
                  FROM card_product WHERE id = ? FOR UPDATE
                """,
                (rs, i) -> new Range(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)),
                productId);

        if (r.next() > r.end()) {
            throw new IssuanceException("RANGE_EXHAUSTED", "Account range exhausted for product " + productId);
        }
        int bodyLen = r.panLength() - r.bin().length() - 1;
        String body = String.format("%0" + bodyLen + "d", r.next());
        if (body.length() != bodyLen) {
            throw new IllegalStateException("Range number does not fit PAN length");
        }

        jdbc.update("UPDATE card_product SET next_sequence = next_sequence + 1 WHERE id = ?", productId);

        String withoutCheck = r.bin() + body;
        return withoutCheck + Luhn.checkDigit(withoutCheck);
    }
}
