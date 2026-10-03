package com.cms.iso;

import com.cms.auth.AuthRequest;
import com.cms.auth.AuthRequest.OriginalRef;
import com.cms.auth.AuthResponse;
import com.cms.auth.Channel;
import com.cms.auth.TxnType;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;

import java.util.function.Function;

/**
 * BASE24 ISO 8583:1993 <-> CMS authorization model.
 *
 * PROVISIONAL mapping (CMS-053), to confirm against the BASE24 spec (IN-01):
 *   MTI 1100 / 1120      pre-authorisation / its advice
 *   MTI 1200 / 1220      financial request / advice; 1220 with field 56 naming a 1100 = completion
 *   MTI 1400 / 1420      reversal (1421 repeat)
 *   field 3 positions 1-2: 00 purchase, 01 cash, 20 refund, 31 balance inquiry, 92 PIN change
 *   channel: ATM when MCC (26 or 18) = 6011 or cash / balance / PIN change; e-commerce when field 22
 *            position 6 (card present) = '0'; otherwise POS
 *   reversal: field 56 = original MTI(4) + STAN(6) + local date-time(12) + acquirer id (LL + n..11);
 *            field 30 positions 1-12 = original amount, field 4 = amount actually completed (partial)
 *   field 55: chip data (hex) to the engine; the response carries tag 91 (ARPC + ARC) when the ARQC was checked
 *   field 48: tagged values, each tag + value: CV2 + 3-4 digits (CVV2), TKN + LL + token number (token payment),
 *            CAV + 40 hex (3-D Secure CAVV), ECI + 2 digits
 *   field 54 in responses: per amount 20 chars = account type(2) + amount type(2: 01 ledger, 02 available)
 *            + currency(3) + C/D + amount(12)
 * Repeat MTIs (xxx1) are normalised to xxx0 so duplicates match the original message.
 */
public final class IsoMapper {

    private IsoMapper() {}

    public static boolean isNetworkManagement(ISOMsg m) throws ISOException {
        return m.getMTI().startsWith("18");
    }

    public static AuthRequest toRequest(ISOMsg m) throws ISOException {
        String mti = normalise(m.getMTI());
        String pc = m.hasField(3) ? m.getString(3) : "000000";
        String txnCode = pc.substring(0, 2);
        boolean advice = mti.equals("1120") || mti.equals("1220");
        OriginalRef original = parseOriginal(m.getString(56));

        TxnType type;
        if (mti.startsWith("14")) {
            type = TxnType.REVERSAL;
        } else if (mti.startsWith("11")) {
            type = TxnType.PREAUTH;
        } else if (mti.equals("1220") && original != null && original.mti().startsWith("11")) {
            type = TxnType.COMPLETION;
            advice = false;   // completions are checked against the hold, not forced
        } else {
            type = switch (txnCode) {
                case "01" -> TxnType.WITHDRAWAL;
                case "20" -> TxnType.REFUND;
                case "31" -> TxnType.BALANCE_INQUIRY;
                case "92" -> TxnType.PIN_CHANGE;
                default -> TxnType.PURCHASE;
            };
        }

        String mcc = m.hasField(26) ? m.getString(26) : m.getString(18);
        Channel channel;
        if ("6011".equals(mcc) || txnCode.equals("01") || txnCode.equals("31") || txnCode.equals("92")) {
            channel = Channel.ATM;
        } else {
            String posData = m.getString(22);
            channel = posData != null && posData.length() >= 6 && posData.charAt(5) == '0' ? Channel.ECOM : Channel.POS;
        }

        String pan = m.getString(2);
        String track2 = m.getString(35);
        if ((pan == null || pan.isBlank()) && track2 != null) {
            int sep = Math.max(track2.indexOf('='), track2.indexOf('D'));
            if (sep > 0) pan = track2.substring(0, sep).replace(";", "");
        }

        Long completed = null;
        if (type == TxnType.REVERSAL && m.hasField(30) && m.hasField(4)) {
            completed = Long.parseLong(m.getString(4));
        }

        return new AuthRequest(type, channel, mti, pc, pan, m.getString(14), track2,
                m.hasField(52) ? ISOUtil.hexString(m.getBytes(52)) : null,
                blankToNull(m.getString(125)),
                amount(m, type),
                m.getString(49), m.getString(11), m.getString(37), m.getString(7), m.getString(12),
                m.getString(32), trim(m.getString(41)), mcc, trim(m.getString(43)), advice, original, completed,
                m.hasField(55) ? ISOUtil.hexString(m.getBytes(55)) : null,
                blankToNull(m.getString(19)),
                entryMode(m.getString(22), channel),
                cvv2(m.getString(48)),
                token(m.getString(48)),
                tagged(m.getString(48), "CAV([0-9A-Fa-f]{40})"),
                tagged(m.getString(48), "ECI([0-9]{2})"));
    }

    /** Response to a financial / authorization / reversal message: echoes the keys, adds 38, 39, 54. */
    public static ISOMsg toResponse(ISOMsg req, AuthResponse r, IsoCodec codec,
                                    Function<String, String> numericCurrency) throws ISOException {
        ISOMsg resp = codec.newMessage();
        resp.setMTI(responseMti(req.getMTI()));
        for (int f : new int[]{2, 3, 4, 7, 11, 12, 14, 22, 24, 30, 32, 37, 41, 42, 49, 56}) {
            if (req.hasField(f)) resp.set(f, req.getString(f));
        }
        if (r.authId() != null) resp.set(38, r.authId());
        resp.set(39, r.actionCode());
        if (r.iccResponse() != null) resp.set(55, ISOUtil.hex2byte(r.iccResponse()));
        if (r.ledgerBalance() != null && r.availableBalance() != null) {
            String ccy = numericCurrency.apply(r.currencyCode());
            resp.set(54, additionalAmount("01", r, ccy) + additionalAmount("02", r, ccy));
        }
        return resp;
    }

    /** Function digit + 1, origin kept; repeats answer as the base message: 1200/1201 -> 1210, 1804 -> 1814. */
    static String responseMti(String mti) {
        String base = normalise(mti);
        return base.substring(0, 2) + (char) (base.charAt(2) + 1) + base.charAt(3);
    }

    /** Repeat origins (1 acquirer repeat, 3 issuer repeat) become their base origin (0, 2). */
    static String normalise(String mti) {
        char origin = mti.charAt(3);
        return origin == '1' || origin == '3' ? mti.substring(0, 3) + (char) (origin - 1) : mti;
    }

    /** MTI(4) + STAN(6) + date-time(10 or 12) + acquirer id (LL + digits, or remaining digits). */
    static OriginalRef parseOriginal(String f56) {
        if (f56 == null || f56.length() < 20) return null;
        String mti = normalise(f56.substring(0, 4));
        String stan = f56.substring(4, 10);
        String rest = f56.substring(10);
        String dt;
        String acq;
        if (rest.length() >= 14 && lengthPrefixed(rest.substring(12))) {
            dt = rest.substring(0, 12);
            acq = rest.substring(14);
        } else if (rest.length() >= 12 && lengthPrefixed(rest.substring(10))) {
            dt = rest.substring(0, 10);
            acq = rest.substring(12);
        } else {
            dt = rest.substring(0, Math.min(12, rest.length()));
            acq = rest.length() > 12 ? rest.substring(12) : "";
        }
        return new OriginalRef(mti, stan, dt, acq);
    }

    private static boolean lengthPrefixed(String s) {
        if (s.length() < 2 || !s.substring(0, 2).chars().allMatch(Character::isDigit)) return false;
        return Integer.parseInt(s.substring(0, 2)) == s.length() - 2;
    }

    /** Transaction amount; for a reversal with field 30, the original amount. */
    private static long amount(ISOMsg m, TxnType type) {
        if (type == TxnType.REVERSAL && m.hasField(30)) return Long.parseLong(m.getString(30).substring(0, 12));
        return m.hasField(4) ? Long.parseLong(m.getString(4)) : 0L;
    }

    private static String additionalAmount(String amountType, AuthResponse r, String numericCurrency) {
        long v = amountType.equals("01") ? r.ledgerBalance() : r.availableBalance();
        return "00" + amountType + (numericCurrency == null ? "000" : numericCurrency) + (v < 0 ? "D" : "C")
                + String.format("%012d", Math.abs(v));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * Card data input mode, field 22 position 7. PROVISIONAL (IN-01): 5 chip, M / A / 7 contactless,
     * 2 / 8 / 9 magnetic stripe, 1 / 6 manual; e-commerce from the channel.
     */
    static String entryMode(String pos, Channel channel) {
        if (channel == Channel.ECOM) return "ECOM";
        if (pos == null || pos.length() < 7) return null;
        return switch (pos.charAt(6)) {
            case '5' -> "CHIP";
            case 'M', 'A', '7' -> "CONTACTLESS";
            case '2', '8', '9' -> "MAGSTRIPE";
            case '1', '6' -> "MANUAL";
            default -> null;
        };
    }

    /** CVV2 in field 48 as "CV2" + 3-4 digits. PROVISIONAL (IN-01): replace with the BASE24 token layout. */
    static String cvv2(String f48) {
        if (f48 == null) return null;
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("CV2([0-9]{3,4})").matcher(f48);
        return x.find() ? x.group(1) : null;
    }

    /** Token number in field 48 as "TKN" + 2-digit length + digits. PROVISIONAL (IN-01 / IN-08). */
    static String token(String f48) {
        if (f48 == null) return null;
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("TKN([0-9]{2})([0-9]+)").matcher(f48);
        if (!x.find()) return null;
        int n = Integer.parseInt(x.group(1));
        return n >= 13 && n <= 19 && x.group(2).length() >= n ? x.group(2).substring(0, n) : null;
    }

    static String tagged(String f48, String regex) {
        if (f48 == null) return null;
        java.util.regex.Matcher x = java.util.regex.Pattern.compile(regex).matcher(f48);
        return x.find() ? x.group(1).toUpperCase() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
