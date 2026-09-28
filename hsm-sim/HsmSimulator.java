// =====================================================================================
//  hsm-sim : payShield host-command SIMULATOR for DEVELOPMENT AND TEST ONLY
//  Version 1.0.0
//
//  Run (no build needed, JDK 21):
//     java HsmSimulator.java serve [port] [headerLength]      default 1500 4
//     java HsmSimulator.java seed                              SQL + clear test keys
//     java HsmSimulator.java import <clearKey32Hex>            key under sim LMK + KCV
//     java HsmSimulator.java pinblock <pan> <pin> <clearZpk32Hex>   ISO-0 PIN block for tests
//     java HsmSimulator.java selftest                          algorithm checks
//
//  Environment:
//     SIM_LMK       32 hex, simulator master key standing in for the LMK (default test value)
//     SIM_DELAY_MS  add latency to every response (timeout testing)
//     SIM_FAIL      force error codes, e.g. "EC:15,JE:20"
//
//  Same wire protocol as payShield TCP host port:
//     [2-byte length][header][command(2)][fields]  ->  [2-byte length][header][response(2)][error(2)][fields]
//
//  Supported: NC FA JE JG BA DG EC CW CY.   Real algorithms: 3DES, ISO-0 PIN block,
//  Visa PVV, Visa CVV, KCV. Given the same CLEAR keys it returns the same PVV/CVV as a
//  real HSM. Key cryptograms use a simplified LMK scheme (no key-type variants) and are
//  NOT portable to a real payShield. Clear test keys only. Not PCI compliant.
// =====================================================================================

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class HsmSimulator {

    static final String VERSION = "1.0.0";
    static final HexFormat HEX = HexFormat.of().withUpperCase();
    static final String DEFAULT_LMK = "89ABCDEF0123456776543210FEDCBA98";

    static byte[] lmk;
    static int headerLength = 4;
    static long delayMs = 0;
    static final Map<String, String> forcedErrors = new HashMap<>();
    static final AtomicLong counter = new AtomicLong();

    public static void main(String[] args) throws Exception {
        lmk = HEX.parseHex(Optional.ofNullable(System.getenv("SIM_LMK")).orElse(DEFAULT_LMK));
        if (lmk.length != 16) throw new IllegalArgumentException("SIM_LMK must be 32 hex");
        delayMs = Long.parseLong(Optional.ofNullable(System.getenv("SIM_DELAY_MS")).orElse("0"));
        String fail = System.getenv("SIM_FAIL");
        if (fail != null && !fail.isBlank()) {
            for (String p : fail.split(",")) {
                String[] kv = p.trim().split(":");
                forcedErrors.put(kv[0].toUpperCase(), kv[1]);
            }
        }

        String mode = args.length > 0 ? args[0] : "serve";
        switch (mode) {
            case "serve" -> serve(args.length > 1 ? Integer.parseInt(args[1]) : 1500,
                                  args.length > 2 ? Integer.parseInt(args[2]) : 4);
            case "seed" -> seed();
            case "import" -> {
                byte[] k = HEX.parseHex(args[1]);
                System.out.println("underLmk=" + keyUnderLmk(k) + " kcv=" + kcv(k));
            }
            case "pinblock" -> System.out.println(
                    HEX.formatHex(tdes(HEX.parseHex(args[3]), isoFormat0(args[2], account12(args[1])), true)));
            case "selftest" -> selfTest();
            default -> System.err.println("modes: serve | seed | import | pinblock | selftest");
        }
    }

    // =================================================================================
    // Server
    // =================================================================================

    static void serve(int port, int hdrLen) throws IOException {
        headerLength = hdrLen;
        System.out.printf("hsm-sim %s listening on %d, header length %d, LMK check %s%n",
                VERSION, port, hdrLen, HEX.formatHex(tdes(lmk, new byte[8], true)));
        if (!forcedErrors.isEmpty()) System.out.println("forced errors: " + forcedErrors);
        if (delayMs > 0) System.out.println("added delay ms: " + delayMs);
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                Thread.ofVirtual().start(() -> handle(s));
            }
        }
    }

    static void handle(Socket s) {
        try (s; DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            while (true) {
                int len;
                try { len = in.readUnsignedShort(); } catch (EOFException eof) { return; }
                String msg = new String(in.readNBytes(len), StandardCharsets.US_ASCII);
                String header = msg.substring(0, Math.min(headerLength, msg.length()));
                String body = msg.substring(Math.min(headerLength, msg.length()));
                String cmd = body.length() >= 2 ? body.substring(0, 2) : "??";
                String resp = process(cmd, body.substring(Math.min(2, body.length())));
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] r = (header + resp).getBytes(StandardCharsets.US_ASCII);
                out.writeShort(r.length);
                out.write(r);
                out.flush();
                System.out.printf("#%d %s -> %s%n", counter.incrementAndGet(), cmd, resp.substring(0, Math.min(4, resp.length())));
            }
        } catch (Exception e) {
            System.err.println("connection closed: " + e.getMessage());
        }
    }

    /** Returns responseCode + errorCode + data. */
    static String process(String cmd, String b) {
        String rc = "" + cmd.charAt(0) + (char) (cmd.charAt(1) + 1);
        String forced = forcedErrors.get(cmd);
        if (forced != null) return rc + forced;
        try {
            Cursor c = new Cursor(b);
            return rc + switch (cmd) {
                case "NC" -> "00" + HEX.formatHex(tdes(lmk, new byte[8], true)) + "SIM" + VERSION;
                case "FA" -> cmdFA(c);
                case "JE" -> cmdJE(c);
                case "JG" -> cmdJG(c);
                case "BA" -> cmdBA(b);
                case "DG" -> cmdDG(c);
                case "EC" -> cmdEC(c);
                case "CW" -> cmdCW(c);
                case "CY" -> cmdCY(c);
                default -> "68";                       // command not supported by simulator
            };
        } catch (SimError e) {
            return rc + e.code;
        } catch (Exception e) {
            return rc + "15";                          // input data error
        }
    }

    // ---- FA: ZPK from ZMK to LMK.  ZMK(lmk) + ZPK(zmk)  ->  ZPK(lmk) + KCV
    static String cmdFA(Cursor c) {
        byte[] zmk = fromLmk(c.key());
        byte[] zpk = tdes(zmk, HEX.parseHex(stripTag(c.key())), false);
        return "00" + keyUnderLmk(zpk) + kcv(zpk);
    }

    // ---- JE: PIN ZPK -> LMK.  ZPK + PINblock(16) + fmt(2) + acct(12)  ->  PIN(lmk, 16H)
    static String cmdJE(Cursor c) {
        byte[] zpk = fromLmk(c.key());
        String pb = c.take(16); requireFormat(c.take(2)); String acct = c.take(12);
        String pin = pinFromIso0(tdes(zpk, HEX.parseHex(pb), false), acct);
        return "00" + pinUnderLmk(pin);
    }

    // ---- JG: PIN LMK -> ZPK.  ZPK + fmt(2) + acct(12) + PIN(lmk)  ->  PINblock(16)
    static String cmdJG(Cursor c) {
        byte[] zpk = fromLmk(c.key());
        requireFormat(c.take(2)); String acct = c.take(12);
        String pin = pinFromLmk(c.take(16));
        return "00" + HEX.formatHex(tdes(zpk, isoFormat0(pin, acct), true));
    }

    // ---- BA: encrypt clear PIN.  PIN (digits, F-padded) + acct(12)  ->  PIN(lmk)
    static String cmdBA(String b) {
        if (b.length() < 16) throw new SimError("15");
        String pin = b.substring(0, b.length() - 12).replace("F", "");
        checkPin(pin);
        return "00" + pinUnderLmk(pin);
    }

    // ---- DG: PVV from LMK PIN.  PVK + PIN(lmk) + acct(12) + PVKI  ->  PVV(4)
    static String cmdDG(Cursor c) {
        byte[] pvk = fromLmk(c.key());
        String pin = pinFromLmk(c.take(16)); String acct = c.take(12); char pvki = c.take(1).charAt(0);
        return "00" + pvv(pvk, acct, pvki, pin);
    }

    // ---- EC: verify PIN (ZPK) by PVV.  ZPK + PVK + PINblock + fmt + acct + PVKI + PVV  ->  00/01
    static String cmdEC(Cursor c) {
        byte[] zpk = fromLmk(c.key()); byte[] pvk = fromLmk(c.key());
        String pb = c.take(16); requireFormat(c.take(2)); String acct = c.take(12);
        char pvki = c.take(1).charAt(0); String expected = c.take(4);
        String pin = pinFromIso0(tdes(zpk, HEX.parseHex(pb), false), acct);
        return pvv(pvk, acct, pvki, pin).equals(expected) ? "00" : "01";
    }

    // ---- CW: generate CVV.  CVK + PAN + ';' + exp(4) + sc(3)  ->  CVV(3)
    static String cmdCW(Cursor c) {
        byte[] cvk = fromLmk(c.key());
        String pan = c.until(';'); String exp = c.take(4); String sc = c.take(3);
        return "00" + cvv(cvk, pan, exp, sc);
    }

    // ---- CY: verify CVV.  CVK + CVV(3) + PAN + ';' + exp + sc  ->  00/01
    static String cmdCY(Cursor c) {
        byte[] cvk = fromLmk(c.key());
        String given = c.take(3); String pan = c.until(';'); String exp = c.take(4); String sc = c.take(3);
        return cvv(cvk, pan, exp, sc).equals(given) ? "00" : "01";
    }

    // =================================================================================
    // Algorithms
    // =================================================================================

    /** Visa PVV: TSP = 11 rightmost acct digits + PVKI + 4 PIN digits, 3DES with PVK, decimalise. */
    static String pvv(byte[] pvk, String acct12, char pvki, String pin) {
        String tsp = acct12.substring(1) + pvki + pin.substring(0, 4);
        return decimalise(HEX.formatHex(tdes(pvk, HEX.parseHex(tsp), true)), 4);
    }

    /** Visa CVV with CVK pair A|B. */
    static String cvv(byte[] cvk, String pan, String exp, String sc) {
        String data = pan + exp + sc;
        data = (data + "0".repeat(32)).substring(0, 32);
        byte[] a = Arrays.copyOfRange(cvk, 0, 8), bKey = Arrays.copyOfRange(cvk, 8, 16);
        byte[] b1 = HEX.parseHex(data.substring(0, 16)), b2 = HEX.parseHex(data.substring(16));
        byte[] r = des(a, b1, true);
        for (int i = 0; i < 8; i++) r[i] ^= b2[i];
        r = des(a, des(bKey, des(a, r, true), false), true);
        return decimalise(HEX.formatHex(r), 3);
    }

    static String decimalise(String hex, int n) {
        StringBuilder sb = new StringBuilder();
        for (char ch : hex.toCharArray()) if (Character.isDigit(ch) && sb.length() < n) sb.append(ch);
        for (char ch : hex.toCharArray()) if (!Character.isDigit(ch) && sb.length() < n) sb.append((char) ('0' + (ch - 'A')));
        return sb.toString();
    }

    /** ISO 9564 format 0 clear block (before encryption). */
    static byte[] isoFormat0(String pin, String acct12) {
        checkPin(pin);
        String pinField = ("0" + pin.length() + pin + "F".repeat(14)).substring(0, 16);
        byte[] p = HEX.parseHex(pinField), a = HEX.parseHex("0000" + acct12);
        for (int i = 0; i < 8; i++) p[i] ^= a[i];
        return p;
    }

    static String pinFromIso0(byte[] clearBlock, String acct12) {
        byte[] a = HEX.parseHex("0000" + acct12);
        byte[] p = clearBlock.clone();
        for (int i = 0; i < 8; i++) p[i] ^= a[i];
        String f = HEX.formatHex(p);
        if (f.charAt(0) != '0') throw new SimError("20");
        int len = Character.digit(f.charAt(1), 16);
        if (len < 4 || len > 12) throw new SimError("24");
        String pin = f.substring(2, 2 + len);
        if (!pin.matches("\\d+") || !f.substring(2 + len).matches("F*")) throw new SimError("20");
        return pin;
    }

    /** Simulator PIN-under-LMK format: 16H = 3DES(LMK, lenHex + PIN + F-pad). */
    static String pinUnderLmk(String pin) {
        String block = (Integer.toHexString(pin.length()).toUpperCase() + pin + "F".repeat(15)).substring(0, 16);
        return HEX.formatHex(tdes(lmk, HEX.parseHex(block), true));
    }

    static String pinFromLmk(String enc) {
        String block = HEX.formatHex(tdes(lmk, HEX.parseHex(enc), false));
        int len = Character.digit(block.charAt(0), 16);
        String pin = block.substring(1, 1 + len);
        checkPin(pin);
        return pin;
    }

    static void checkPin(String pin) {
        if (!pin.matches("\\d{4,12}")) throw new SimError("24");
    }

    static void requireFormat(String fmt) {
        if (!"01".equals(fmt)) throw new SimError("23");   // only ISO-0 simulated
    }

    // =================================================================================
    // Keys and crypto primitives
    // =================================================================================

    static String keyUnderLmk(byte[] clear) {
        String enc = HEX.formatHex(tdes(lmk, clear, true));
        return clear.length == 16 ? "U" + enc : enc;
    }

    static byte[] fromLmk(String token) {
        return tdes(lmk, HEX.parseHex(stripTag(token)), false);
    }

    static String stripTag(String token) {
        return Character.isLetter(token.charAt(0)) && token.length() % 2 == 1 ? token.substring(1) : token;
    }

    static String kcv(byte[] key) {
        return HEX.formatHex(tdes(key, new byte[8], true)).substring(0, 6);
    }

    /** 3DES ECB; 8-byte key = single DES, 16-byte = K1K2K1. */
    static byte[] tdes(byte[] key, byte[] data, boolean encrypt) {
        try {
            if (key.length == 8) return des(key, data, encrypt);
            byte[] k = new byte[24];
            System.arraycopy(key, 0, k, 0, 16);
            System.arraycopy(key, 0, k, 16, 8);
            Cipher c = Cipher.getInstance("DESede/ECB/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(k, "DESede"));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new SimError("15");
        }
    }

    static byte[] des(byte[] key8, byte[] data, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key8, "DES"));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new SimError("15");
        }
    }

    static String account12(String pan) {
        String noCheck = pan.substring(0, pan.length() - 1);
        return noCheck.substring(noCheck.length() - 12);
    }

    static byte[] randomKey() {
        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        for (int i = 0; i < k.length; i++) {           // odd parity
            int b = k[i] & 0xFE;
            k[i] = (byte) (Integer.bitCount(b) % 2 == 0 ? b | 1 : b);
        }
        return k;
    }

    // =================================================================================
    // Parsing helpers
    // =================================================================================

    static final class Cursor {
        final String s; int p = 0;
        Cursor(String s) { this.s = s; }
        String take(int n) {
            if (p + n > s.length()) throw new SimError("15");
            String r = s.substring(p, p + n); p += n; return r;
        }
        String until(char delim) {
            int i = s.indexOf(delim, p);
            if (i < 0) throw new SimError("15");
            String r = s.substring(p, i); p = i + 1; return r;
        }
        /** Key token: U/X + 32H, T/Y + 48H, otherwise 16H single. */
        String key() {
            char t = s.charAt(p);
            int n = switch (t) { case 'U', 'X' -> 33; case 'T', 'Y' -> 49; default -> 16; };
            return take(n);
        }
    }

    static final class SimError extends RuntimeException {
        final String code;
        SimError(String code) { super(code, null, false, false); this.code = code; }
    }

    // =================================================================================
    // Tooling
    // =================================================================================

    /** Fixed, documented TEST keys so every developer gets the same PVVs/CVVs. */
    static final String[][] TEST_KEYS = {
        {"ZMK_COREHOST", "ZMK", "1C1C1C1C1C1C1C1C2A2A2A2A2A2A2A2A"},
        {"ZPK_KIOSK",    "ZPK", "0B0B0B0B0B0B0B0B1616161616161616"},
        {"ZPK_COREHOST", "ZPK", "4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E"},
        {"PVK_P01",      "PVK", "FEDCBA98765432100123456789ABCDEF"},
        {"CVK_P01",      "CVK", "0123456789ABCDEFFEDCBA9876543210"},
    };

    static void seed() {
        System.out.println("-- hsm-sim " + VERSION + " test keys (CLEAR values are for TEST ONLY)");
        for (String[] k : TEST_KEYS) {
            byte[] clear = HEX.parseHex(k[2]);
            System.out.printf("-- %-13s clear=%s kcv=%s%n", k[0], k[2], kcv(clear));
        }
        System.out.println("INSERT INTO hsm_key (key_name, key_type, key_scheme, key_under_lmk, kcv) VALUES");
        for (int i = 0; i < TEST_KEYS.length; i++) {
            byte[] clear = HEX.parseHex(TEST_KEYS[i][2]);
            System.out.printf(" ('%s','%s','U','%s','%s')%s%n", TEST_KEYS[i][0], TEST_KEYS[i][1],
                    keyUnderLmk(clear), kcv(clear), i == TEST_KEYS.length - 1 ? ";" : ",");
        }
    }

    static void selfTest() {
        int fail = 0;
        // Published Visa CVV example: PAN 4123456789012345, exp 8701, sc 101, CVK A/B below -> 561
        String cvv = cvv(HEX.parseHex("0123456789ABCDEFFEDCBA9876543210"), "4123456789012345", "8701", "101");
        fail += check("Visa CVV reference vector (561)", "561".equals(cvv), cvv);

        byte[] zpk = HEX.parseHex("0B0B0B0B0B0B0B0B1616161616161616");
        byte[] pvk = HEX.parseHex("FEDCBA98765432100123456789ABCDEF");
        String pan = "4000001234567899", acct = account12(pan);
        String pb = HEX.formatHex(tdes(zpk, isoFormat0("1234", acct), true));
        fail += check("ISO-0 round trip", "1234".equals(pinFromIso0(tdes(zpk, HEX.parseHex(pb), false), acct)), pb);

        String lmkPin = pinUnderLmk("1234");
        fail += check("LMK PIN round trip", "1234".equals(pinFromLmk(lmkPin)), lmkPin);

        String p1 = pvv(pvk, acct, '1', "1234");
        fail += check("PVV is 4 digits", p1.matches("\\d{4}"), p1);
        fail += check("PVV differs for other PIN", !p1.equals(pvv(pvk, acct, '1', "4321")), "");

        // protocol-level: JE -> DG equals EC
        String zpkLmk = keyUnderLmk(zpk), pvkLmk = keyUnderLmk(pvk);
        String je = process("JE", zpkLmk + pb + "01" + acct);
        String dg = process("DG", pvkLmk + je.substring(4) + acct + "1");
        String ec = process("EC", zpkLmk + pvkLmk + pb + "01" + acct + "1" + dg.substring(4));
        fail += check("JE+DG then EC verifies", ec.equals("ED00"), je + " / " + dg + " / " + ec);
        String pbWrong = HEX.formatHex(tdes(zpk, isoFormat0("9999", acct), true));
        String ecBad = process("EC", zpkLmk + pvkLmk + pbWrong + "01" + acct + "1" + dg.substring(4));
        fail += check("EC wrong PIN returns 01", ecBad.equals("ED01"), ecBad);

        String cw = process("CW", keyUnderLmk(HEX.parseHex("0123456789ABCDEFFEDCBA9876543210"))
                + "4123456789012345;8701101");
        fail += check("CW over protocol", cw.equals("CX00561"), cw);

        System.out.println(fail == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED: " + fail);
        if (fail > 0) System.exit(1);
    }

    static int check(String name, boolean ok, String detail) {
        System.out.printf("%-32s %s %s%n", name, ok ? "OK  " : "FAIL", detail);
        return ok ? 0 : 1;
    }
}
