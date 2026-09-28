package com.cms.hsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayShieldClientHelpersTest {

    @Test
    void accountNumberIs12RightmostExcludingCheckDigit() {
        assertEquals("000123456789", PayShieldClient.accountNumber12("4000001234567899"));
    }

    @Test
    void responseCodeIsCommandPlusOne() {
        assertEquals("ND", PayShieldClient.responseCode("NC"));
        assertEquals("ED", PayShieldClient.responseCode("EC"));
        assertEquals("JF", PayShieldClient.responseCode("JE"));
        assertEquals("CX", PayShieldClient.responseCode("CW"));
    }

    @Test
    void keyTokenLengthByScheme() {
        assertEquals(33, PayShieldClient.keyTokenLength("U" + "0".repeat(32), 0));
        assertEquals(49, PayShieldClient.keyTokenLength("T" + "0".repeat(48), 0));
    }
}
