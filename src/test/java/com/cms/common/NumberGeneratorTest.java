package com.cms.common;

import com.cms.card.IssuanceException;
import com.cms.card.Luhn;
import com.cms.common.NumberGenerator.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumberGeneratorTest {

    @Test
    void padsToBodyLengthAfterPrefix() {
        assertEquals("C00000042", NumberGenerator.format(new Format("C", 8, "NONE"), 42));
        assertEquals("1000000003", NumberGenerator.format(new Format("", 10, "NONE"), 1000000003L));
    }

    @Test
    void appendsLuhnDigitOverPrefixAndBody() {
        String n = NumberGenerator.format(new Format("21", 8, "LUHN"), 7);
        assertEquals(11, n.length());
        assertTrue(n.startsWith("2100000007"));
        assertTrue(Luhn.isValid(n));
    }

    @Test
    void refusesValueThatNoLongerFits() {
        IssuanceException e = assertThrows(IssuanceException.class,
                () -> NumberGenerator.format(new Format("", 4, "NONE"), 10000));
        assertEquals("RANGE_EXHAUSTED", e.code());
    }
}
