package com.cms.digital;

import com.cms.api.DevTspController;
import com.cms.card.Luhn;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThreeDsServiceTest {

    @Test
    void cavvReferenceIsTheFirst13BytesOfTheAuthenticationId() {
        UUID id = UUID.fromString("54a13308-baef-4211-b5fc-addd966caf5d");
        assertEquals("54A13308BAEF4211B5FCADDD96", ThreeDsService.ref(id));
        assertEquals(26, ThreeDsService.ref(UUID.randomUUID()).length());
    }

    @Test
    void simulatedTokenNumbersAreStableLuhnValidAndOutsideTheCardBins() {
        String a = DevTspController.tokenPan("DNITHE0123456789ABCDEF");
        assertEquals(a, DevTspController.tokenPan("DNITHE0123456789ABCDEF"));
        assertNotEquals(a, DevTspController.tokenPan("DNITHE0123456789ABCDEG"));
        assertEquals(16, a.length());
        assertTrue(a.startsWith("489537"));
        assertTrue(Luhn.isValid(a));
    }
}
