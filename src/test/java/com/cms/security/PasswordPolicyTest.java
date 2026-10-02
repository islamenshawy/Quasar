package com.cms.security;

import com.cms.card.IssuanceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @Test
    void acceptsStrongPassword() {
        assertDoesNotThrow(() -> UserService.checkPolicy("Card-Ops-2026!", "alice"));
    }

    @Test
    void refusesWeakPasswords() {
        for (String p : new String[]{"short1!A", "alllowercase1!", "ALLUPPERCASE1!", "NoDigitsHere!!", "NoSymbols12345"}) {
            assertThrows(IssuanceException.class, () -> UserService.checkPolicy(p, "alice"), p);
        }
    }

    @Test
    void refusesPasswordContainingUsername() {
        assertThrows(IssuanceException.class, () -> UserService.checkPolicy("Alice-Pass-2026!", "alice"));
    }
}
