package com.cms.auth;

import com.cms.auth.AuthorizationService.Track2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    private static AuthRequest req(TxnType type, String pan, String stan, AuthRequest.OriginalRef original) {
        return new AuthRequest(type, Channel.ATM, "1200", "010000", pan, null, null, null, null, 1000, "818",
                stan, null, "1001120000", null, "123456", "ATM00001", null, null, false, original, null);
    }

    @Test
    void parsesTrack2WithEitherSeparator() {
        Track2 t = Track2.parse(";9999990000000014=2909221100000123?");
        assertNotNull(t);
        assertEquals("9999990000000014", t.pan());
        assertEquals("2909", t.expiry());
        assertEquals("221", t.serviceCode());
        assertEquals("100000123", t.discretionary());
        assertEquals("2909", Track2.parse("9999990000000014D2909221").expiry());
        assertNull(Track2.parse("12345=2909"));
    }

    @Test
    void validatesMandatoryFields() {
        assertNull(AuthorizationService.validate(req(TxnType.WITHDRAWAL, "9999990000000014", "000001", null)));
        assertEquals("invalid PAN", AuthorizationService.validate(req(TxnType.WITHDRAWAL, "9999990000000015", "000001", null)));
        assertEquals("STAN must be 6 digits", AuthorizationService.validate(req(TxnType.WITHDRAWAL, "9999990000000014", "12", null)));
    }

    @Test
    void reversalNeedsOriginalButNoPan() {
        assertEquals("reversal needs original data elements",
                AuthorizationService.validate(req(TxnType.REVERSAL, null, "000002", null)));
        assertNull(AuthorizationService.validate(req(TxnType.REVERSAL, null, "000002",
                new AuthRequest.OriginalRef("1200", "000001", "1001120000", "123456"))));
    }

    @Test
    void requestNeverPrintsFullPan() {
        String s = req(TxnType.WITHDRAWAL, "9999990000000014", "000001", null).toString();
        assertFalse(s.contains("9999990000000014"));
        assertTrue(s.contains("999999******0014"));
    }

    @Test
    void actionCodesClassifyApprovals() {
        assertTrue(ActionCode.isApproval("000"));
        assertTrue(ActionCode.isApproval("400"));
        assertFalse(ActionCode.isApproval("116"));
        assertFalse(ActionCode.isApproval(null));
    }
}
