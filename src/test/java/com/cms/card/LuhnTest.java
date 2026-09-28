package com.cms.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LuhnTest {

    @Test
    void knownTestPansAreValid() {
        assertTrue(Luhn.isValid("4111111111111111"));
        assertTrue(Luhn.isValid("5555555555554444"));
        assertFalse(Luhn.isValid("4111111111111112"));
    }

    @Test
    void checkDigitMatchesReferenceVector() {
        assertEquals('3', Luhn.checkDigit("7992739871"));  // classic Luhn example
        assertEquals('1', Luhn.checkDigit("411111111111111"));
    }
}
