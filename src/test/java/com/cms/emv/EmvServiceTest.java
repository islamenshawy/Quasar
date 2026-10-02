package com.cms.emv;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmvServiceTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    @Test
    void tlvRoundTripWithTwoByteTagsAndLongLength() {
        Map<String, byte[]> t = new LinkedHashMap<>();
        t.put("9F26", HEX.parseHex("0102030405060708"));
        t.put("82", HEX.parseHex("3C00"));
        t.put("9F10", new byte[130]);            // needs the 0x81 length form
        Map<String, byte[]> back = Tlv.parse(Tlv.encode(t));
        assertArrayEquals(t.get("9F26"), back.get("9F26"));
        assertArrayEquals(t.get("82"), back.get("82"));
        assertEquals(130, back.get("9F10").length);
    }

    @Test
    void rejectsTruncatedTlv() {
        assertThrows(IllegalArgumentException.class, () -> Tlv.parse(HEX.parseHex("9F2608010203")));
    }

    @Test
    void dataBlockFollowsTheListAndTakesTheCvrFromIad() {
        Map<String, byte[]> t = new LinkedHashMap<>();
        t.put("9F02", HEX.parseHex("000000010000"));
        t.put("9F36", HEX.parseHex("0007"));
        t.put("9F10", HEX.parseHex("06011203A0B000"));
        assertEquals("000000010000" + "0007" + "03A0B000",
                HEX.formatHex(EmvService.dataBlock(t, "9F02, 9F36 ,9F10:CVR")));
        assertThrows(IllegalArgumentException.class, () -> EmvService.dataBlock(t, "9F02,9F37"));
    }

    @Test
    void byteTokenTakesOneByteOfATag_forVisaCvn17() {
        Map<String, byte[]> t = new LinkedHashMap<>();
        t.put("9F02", HEX.parseHex("000000010000"));
        t.put("9F37", HEX.parseHex("A1B2C3D4"));
        t.put("9F36", HEX.parseHex("0007"));
        t.put("9F10", HEX.parseHex("06011103A00000"));
        assertEquals("000000010000" + "A1B2C3D4" + "0007" + "A0",
                HEX.formatHex(EmvService.dataBlock(t, "9F02,9F37,9F36,9F10:B5")));
        assertEquals('0', EmvService.cryptoScheme("VISA_CVN17"));
        assertEquals('1', EmvService.cryptoScheme("EMV_CSK"));
    }

    @Test
    void readsIssuerScriptResults() {
        // 9F5B: two scripts, first successful (2), second failed (1)
        String icc = "9F5B0A" + "21" + "AABBCCDD" + "12" + "11223344";
        var r = EmvService.scriptResults(icc);
        assertEquals(2, r.size());
        assertEquals("AABBCCDD", r.get(0).scriptId());
        assertEquals(2, r.get(0).result());
        assertEquals(1, r.get(1).result());
        assertTrue(EmvService.scriptResults("9F2608" + "0102030405060708").isEmpty());
    }

    @Test
    void scriptMacDependsOnTheCommandAndTheArqc() {
        byte[] smi = HEX.parseHex("5A5A5A5A5A5A5A5A3C3C3C3C3C3C3C3C");
        byte[] y = EmvService.y("9999995000000005", "00");
        byte[] ac = HEX.parseHex("1122334455667788");
        byte[] unblock = HEX.parseHex("8424000004" + "0007" + "1122334455667788");
        byte[] block = HEX.parseHex("841E000004" + "0007" + "1122334455667788");
        byte[] m1 = EmvCrypto.scriptMac(smi, y, ac, '1', unblock);
        assertEquals(4, m1.length);
        assertFalse(java.util.Arrays.equals(m1, EmvCrypto.scriptMac(smi, y, ac, '1', block)));
        assertFalse(java.util.Arrays.equals(m1, EmvCrypto.scriptMac(smi, y, HEX.parseHex("1122334455667789"), '1', unblock)));
    }

    @Test
    void optionAInputIsRightmost16DigitsOfPanAndPsn() {
        assertEquals("9999500000000501", HEX.formatHex(EmvService.y("9999995000000005", "01")));
        assertEquals("0041111111111101", HEX.formatHex(EmvService.y("411111111111", "01")));   // 14 digits, left-padded
    }

    @Test
    void cryptogramDependsOnSchemeAtcAndData() {
        byte[] imk = HEX.parseHex("4A4A4A4A4A4A4A4A6D6D6D6D6D6D6D6D");
        byte[] y = EmvService.y("9999995000000005", "00");
        byte[] data = HEX.parseHex("0000000100000000000000000818");
        byte[] a1 = EmvCrypto.arqc(EmvCrypto.cryptogramKey(imk, y, new byte[]{0, 1}, '1'), data, '1');
        byte[] a2 = EmvCrypto.arqc(EmvCrypto.cryptogramKey(imk, y, new byte[]{0, 2}, '1'), data, '1');
        byte[] v = EmvCrypto.arqc(EmvCrypto.cryptogramKey(imk, y, new byte[]{0, 1}, '0'), data, '0');
        assertEquals(8, a1.length);
        assertFalse(java.util.Arrays.equals(a1, a2), "session key must change with the ATC");
        assertFalse(java.util.Arrays.equals(a1, v), "schemes must differ");
        byte[] key = EmvCrypto.cryptogramKey(imk, y, new byte[]{0, 1}, '1');
        assertFalse(java.util.Arrays.equals(EmvCrypto.arpc(key, a1, "00".getBytes()), EmvCrypto.arpc(key, a1, "05".getBytes())));
    }
}
