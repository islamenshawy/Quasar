package com.cms.emv;

import com.cms.card.KeyRepository;
import com.cms.hsm.PayShieldClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
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
        char s = cryptoScheme(scheme);
        String mk = keys.requireActiveKey(imkName);
        byte[] y = y(pan, psn);
        boolean ok = hsm.verifyArqc(mk, s, y, atc, un, data, arqc);
        return new Check(ok, ok ? null : "ARQC verification failed", ((atc[0] & 0xFF) << 8) | (atc[1] & 0xFF),
                s, mk, y, atc, un, data, arqc);
    }

    /** HSM scheme: Visa CVN10 and CVN17 (qVSDC) use the card key, everything else the EMV common session key. */
    public static char cryptoScheme(String emvScheme) {
        return "VISA_CVN10".equals(emvScheme) || "VISA_CVN17".equals(emvScheme) ? '0' : '1';
    }

    /** Field 55 for the response: tag 91 = ARPC(8) || ARC(2), ARC "00" when approved, "05" otherwise. */
    public String responseTlv(Check c, boolean approved) {
        return responseTlv(c, approved, List.of());
    }

    /** ... followed by issuer script templates (tag 72), when any are due. */
    public String responseTlv(Check c, boolean approved, List<byte[]> scriptTemplates) {
        byte[] arc = (approved ? "00" : "05").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] arpc = hsm.generateArpc(c.mkAc(), c.scheme(), c.y(), c.atcBytes(), c.un(), c.data(), c.arqc(), arc);
        byte[] iad = new byte[10];
        System.arraycopy(arpc, 0, iad, 0, 8);
        System.arraycopy(arc, 0, iad, 8, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Tlv.encodeOne("91", iad));
        for (byte[] t : scriptTemplates) out.writeBytes(t);
        return HEX.formatHex(out.toByteArray());
    }

    /**
     * Issuer script template 72 for one command: 9F18 script id || 86 APDU. The APDU carries a MAC over
     * header || ATC || ARQC || data, computed by the HSM (KU) with the card's MK-SMI from the product's IMK-SMI.
     * Commands: PIN UNBLOCK 84 24 00 00, APPLICATION BLOCK 84 1E 00 00, APPLICATION UNBLOCK 84 18 00 00,
     * PUT DATA 9F14 (lower consecutive offline limit) 04 DA 9F 14 with a 1-byte value.
     */
    public byte[] scriptTemplate(Check c, String imkSmiName, String command, String value, String scriptId) {
        byte[] header, data;
        switch (command) {
            case "PIN_UNBLOCK" -> { header = HEX.parseHex("8424000004"); data = new byte[0]; }
            case "APPLICATION_BLOCK" -> { header = HEX.parseHex("841E000004"); data = new byte[0]; }
            case "APPLICATION_UNBLOCK" -> { header = HEX.parseHex("8418000004"); data = new byte[0]; }
            case "UPDATE_OFFLINE_LIMIT" -> {
                header = HEX.parseHex("04DA9F1405");
                data = new byte[]{(byte) Integer.parseInt(value)};
            }
            default -> throw new IllegalArgumentException("unknown script command " + command);
        }
        ByteArrayOutputStream macInput = new ByteArrayOutputStream();
        macInput.writeBytes(header);
        macInput.writeBytes(c.atcBytes());
        macInput.writeBytes(c.arqc());
        macInput.writeBytes(data);
        byte[] mac = hsm.issuerScriptMac(keys.requireActiveKey(imkSmiName), c.scheme(), c.y(), c.atcBytes(), c.arqc(),
                macInput.toByteArray());
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.writeBytes(header);
        apdu.writeBytes(data);
        apdu.writeBytes(mac);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(Tlv.encodeOne("9F18", HEX.parseHex(scriptId)));
        body.writeBytes(Tlv.encodeOne("86", apdu.toByteArray()));
        return Tlv.encodeOne("72", body.toByteArray());
    }

    /** One issuer script result reported by the card (9F5B): result 2 = successful, 1 = failed, 0 = not performed. */
    public record ScriptResult(String scriptId, int result) {}

    /** Script results in field 55 (tag 9F5B, 5 bytes per script). Empty when absent or unreadable. */
    public static List<ScriptResult> scriptResults(String iccHex) {
        if (iccHex == null || iccHex.isBlank()) return List.of();
        try {
            byte[] v = Tlv.parse(HEX.parseHex(iccHex)).get("9F5B");
            if (v == null) return List.of();
            List<ScriptResult> out = new ArrayList<>();
            for (int i = 0; i + 5 <= v.length; i += 5) {
                out.add(new ScriptResult(HEX.formatHex(v, i + 1, i + 5), (v[i] & 0xF0) >> 4));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
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
            } else if (t.matches("[0-9A-F]{2,6}:B[0-9]{1,2}")) {
                // one byte of a tag, 1-based (Visa CVN17 uses 9F10:B5)
                String tag = t.substring(0, t.indexOf(':'));
                int n = Integer.parseInt(t.substring(t.indexOf(':') + 2));
                byte[] v = tags.get(tag);
                if (v == null || v.length < n || n < 1) throw new IllegalArgumentException("tag " + tag + " too short for byte " + n);
                b.write(v[n - 1]);
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
