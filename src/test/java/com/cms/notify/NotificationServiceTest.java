package com.cms.notify;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationServiceTest {

    @Test
    void rendersKnownPlaceholdersAndBlanksUnknownOnes() {
        String t = "Card {{pan}}: {{amount}} {{currency}} at {{merchant}}{{nope}}.";
        assertEquals("Card ****1234: 200.00 EGP at CITY STARS.",
                NotificationService.render(t, Map.of("pan", "****1234", "amount", "200.00", "currency", "EGP", "merchant", "CITY STARS")));
    }

    @Test
    void valuesAreInsertedLiterally() {
        // a $ or backslash in a value must not be read as a regex group reference
        assertEquals("Paid $5 \\ ok", NotificationService.render("Paid {{amount}} ok", Map.of("amount", "$5 \\")));
    }

    @Test
    void masksDestinations() {
        assertEquals("***4567", NotificationGateway.mask("+201001234567"));
        assertEquals("n***@example.com", NotificationGateway.mask("nadia@example.com"));
    }
}
