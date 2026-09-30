package com.cms.common;

import com.cms.card.IssuanceException;
import com.cms.card.Luhn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Predicate;

/**
 * Generates CIF and account numbers from number_sequence:
 *   number = prefix + zero-padded value (body_length) [+ Luhn digit]
 *
 * The sequence row is locked for the caller's transaction, so a rollback returns the
 * value (gaps are acceptable, duplicates are not). Values already taken by a manually
 * entered number are skipped.
 */
@Component
public class NumberGenerator {

    private static final int MAX_SKIPS = 1000;

    public record Format(String prefix, int bodyLength, String checkDigit) {}

    private final JdbcTemplate jdbc;

    public NumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String sequenceCode, Predicate<String> taken) {
        record Seq(Format format, long next) {}
        Seq s = jdbc.query("""
                SELECT prefix, body_length, check_digit, next_value
                  FROM number_sequence WHERE code = ? FOR UPDATE
                """, rs -> rs.next() ? new Seq(new Format(rs.getString(1), rs.getInt(2), rs.getString(3)),
                        rs.getLong(4)) : null, sequenceCode);
        if (s == null) throw new IssuanceException("INVALID_REQUEST", "Unknown number sequence " + sequenceCode);

        long value = s.next();
        for (int i = 0; i < MAX_SKIPS; i++, value++) {
            String n = format(s.format(), value);
            if (!taken.test(n)) {
                jdbc.update("UPDATE number_sequence SET next_value = ? WHERE code = ?", value + 1, sequenceCode);
                return n;
            }
        }
        throw new IssuanceException("RANGE_EXHAUSTED", "No free number in sequence " + sequenceCode);
    }

    /** Formats a value; fails when it no longer fits the configured length. */
    public static String format(Format f, long value) {
        String body = String.format("%0" + f.bodyLength() + "d", value);
        if (body.length() > f.bodyLength()) {
            throw new IssuanceException("RANGE_EXHAUSTED",
                    "Value " + value + " does not fit " + f.bodyLength() + " digits");
        }
        String n = f.prefix() + body;
        return "LUHN".equals(f.checkDigit()) ? n + Luhn.checkDigit(n) : n;
    }
}
