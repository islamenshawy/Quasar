package com.cms.emv;

import com.cms.card.KeyRepository;
import com.cms.hsm.PayShieldClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.Map;

/**
 * Chip (EMV) cryptograms for the authorization engine (CMS-057).
 *
 * verify(): parses field 55, builds the cryptogram data block from the product's data list, and asks
 * the HSM (KQ) to verify the ARQC with the card key derived from the product's IMK-AC (EMV option A)
 * and, for EMV_CSK, the session key of this ATC.
 * responseTlv(): ARPC method 1 over the final answer, returned in field 55 as tag 91 (ARPC || ARC).
 *
 * Nothing from field 55 is logged or stored: it can contain track-2 equivalent data (tag 57).
 */
@Service
public class EmvService {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** Result of an ARQC check, kept for the ARPC once the decision is known. */
    public record Check(boolean ok, String reason, int atc, char scheme, String mkAc, byte[] y, byte[] atcBytes,
                        byte[] un, byte[] data, byte[] arqc) {}

    private final PayShieldClient hsm;
    private final KeyRepository keys;

    public EmvService(PayShieldClient hsm, KeyRepository keys) {
        this.hsm = hsm;
        this.keys = keys;
    }

    public Check verify(String iccHex, String pan, String psn, String imkName, String scheme, String dataList) {
        Map<String, byte[]> tags;
        try {
            tags = Tlv.parse(HEX.parseHex(iccHex));
        } catch (RuntimeException e) {
            return fail("field 55 not parseable");
        }
        byte[] arqc = tags.get("9F26"), atc = tags.get("9F36"), un = tags.get("9F37");
        if (arqc == null || arqc.length != 8 || atc == null || atc.length != 2 || un == null || un.length != 4) {
            return fail("ARQC, ATC or unpredictable number missing");
        }
        byte[] data;
        try {
            data = dataBlock(tags, dataList);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        char s = "VISA_CVN10".equals(scheme) ? '0' : '1';
        String mk = keys.requireActiveKey(imkName);
        byte[] y = y(pan, psn);
        boolean ok = hsm.verifyArqc(mk, s, y, atc, un, data, arqc);
        return new Check(ok, ok ? null : "ARQC verification failed", ((atc[0] & 0xFF) << 8) | (atc[1] & 0xFF),
                s, mk, y, atc, un, data, arqc);
    }

    /** Field 55 for the response: tag 91 = ARPC(8) || ARC(2), ARC "00" when approved, "05" otherwise. */
    public String responseTlv(Check c, boolean approved) {
        byte[] arc = (approved ? "00" : "05").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] arpc = hsm.generateArpc(c.mkAc(), c.scheme(), c.y(), c.atcBytes(), c.un(), c.data(), c.arqc(), arc);
        byte[] iad = new byte[10];
        System.arraycopy(arpc, 0, iad, 0, 8);
        System.arraycopy(arc, 0, iad, 8, 2);
        return HEX.formatHex(Tlv.encode(Map.of("91", iad)));
    }

    /** Same data block for a card-side emulation (dev simulator, tests). */
    public static byte[] dataBlockFor(Map<String, byte[]> tags, String dataList) {
        return dataBlock(tags, dataList);
    }

    /** Concatenates the values named in the data list, e.g. "9F02,9F03,...,9F10:CVR". */
    static byte[] dataBlock(Map<String, byte[]> tags, String dataList) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String token : dataList.split(",")) {
            String t = token.trim().toUpperCase();
            if (t.isEmpty()) continue;
            if (t.equals("9F10:CVR")) {
                byte[] iad = tags.get("9F10");
                if (iad == null || iad.length < 7) throw new IllegalArgumentException("issuer application data (9F10) missing");
                b.write(iad, 3, 4);
            } else {
                byte[] v = tags.get(t);
                if (v == null) throw new IllegalArgumentException("tag " + t + " missing from field 55");
                b.writeBytes(v);
            }
        }
        return b.toByteArray();
    }

    /** EMV option A input: rightmost 16 digits of PAN || PSN, as 8 bytes BCD. */
    public static byte[] y(String pan, String psn) {
        String s = pan + (psn == null ? "00" : psn);
        s = s.length() >= 16 ? s.substring(s.length() - 16) : "0".repeat(16 - s.length()) + s;
        return HEX.parseHex(s);
    }

    private static Check fail(String reason) {
        return new Check(false, reason, -1, '1', null, null, null, null, null, null);
    }
}
