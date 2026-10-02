package com.cms.fee;

import com.cms.fee.FeeService.FeeRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeeServiceTest {

    private static FeeRule rule(long fixed, String pct, Long min, Long max) {
        return new FeeRule(1L, "POS_PURCHASE", "ANY", fixed, new BigDecimal(pct), min, max, 0);
    }

    @Test
    void fixedPlusPercentRoundsHalfUp() {
        assertEquals(500 + 125, FeeService.compute(rule(500, "1.25", null, null), 10_000));
        assertEquals(1, FeeService.compute(rule(0, "0.5", null, null), 101));     // 0.505 -> 1
    }

    @Test
    void minimumAndMaximumBound_theFee() {
        assertEquals(50, FeeService.compute(rule(0, "1", 50L, 1000L), 1_000));     // 10 -> min 50
        assertEquals(1000, FeeService.compute(rule(0, "1", 50L, 1000L), 500_000)); // 5000 -> max 1000
    }

    @Test
    void noRuleNoFee() {
        assertEquals(0, FeeService.compute(null, 123_456));
    }
}
