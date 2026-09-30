package com.cms.card;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CardAdminServiceTest {

    @Test
    void finalStatusesHaveNoOperatorTransitions() {
        for (String s : Set.of("LOST", "STOLEN", "EXPIRED", "CANCELLED")) {
            assertFalse(CardAdminService.TRANSITIONS.containsKey(s), s + " must be final");
        }
    }

    @Test
    void pendingCardsCanOnlyBeCancelledByOperator() {
        // activation of a pending card is the kiosk's job (PIN set), never an operator status change
        assertEquals(Set.of("CANCELLED"), CardAdminService.TRANSITIONS.get("PENDING_PRINT"));
        assertEquals(Set.of("CANCELLED"), CardAdminService.TRANSITIONS.get("PRINTED"));
    }

    @Test
    void blockedCardsCanBeUnblocked() {
        assertTrue(CardAdminService.TRANSITIONS.get("BLOCKED").contains("ACTIVE"));
        assertTrue(CardAdminService.TRANSITIONS.get("PIN_BLOCKED").contains("ACTIVE"));
    }
}
