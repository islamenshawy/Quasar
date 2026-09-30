package com.cms.iso;

import com.cms.auth.ActionCode;
import com.cms.card.IssuanceException;
import com.cms.security.PanCrypto;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DEV ONLY corehost (BASE24) simulator: builds ISO 8583:1993 messages the way the switch would,
 * sends them over TCP to the CMS ISO server and returns the parsed response.
 *
 * It holds TEST clear keys from seed-dev.sql (acquirer ZPK, ZMK) to build PIN blocks and to run a
 * dynamic key exchange. Never enable outside the dev profile.
 */
@Profile("dev")
@Component
public class CorehostSimulator {

    private static final DateTimeFormatter F7 = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter F12 = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    public record SimRequest(String type, String channel, Long cardId, String pan, String pin, String newPin,
                             Long amount, String currency, String terminalId, String acquirerId, String mcc,
                             String merchant, String originalRef, Long amountCompleted, Boolean advice,
                             Boolean repeat) {}

    public record SimResult(String ref, String mti, Map<String, String> request, Map<String, String> response,
                            String actionCode, String actionText, boolean approved, Long ledgerBalance,
                            Long availableBalance, long elapsedMs) {}

    private record Sent(String mti, String stan, String localDt, String acquirerId, long amount, String pc,
                        String pan, String terminalId, String currency) {}

    private final IsoCodec codec;
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final String host;
    private final int port;
    private final byte[] zmk;
    private final AtomicReference<byte[]> zpk = new AtomicReference<>();
    private final AtomicInteger stan = new AtomicInteger((int) (System.currentTimeMillis() % 800000) + 100000);
    private final Map<String, Sent> sent = new ConcurrentHashMap<>();
    private final Map<String, byte[]> lastFrames = new ConcurrentHashMap<>();

    public CorehostSimulator(IsoCodec codec, JdbcTemplate jdbc, PanCrypto panCrypto,
                             @Value("${cms.dev.iso-host:localhost}") String host,
                             @Value("${cms.iso.port:7000}") int port,
                             @Value("${cms.dev.acquirer-zpk-clear:4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E}") String zpkClear,
                             @Value("${cms.dev.zmk-clear:1C1C1C1C1C1C1C1C2A2A2A2A2A2A2A2A}") String zmkClear) {
        this.codec = codec;
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.host = host;
        this.port = port;
        this.zpk.set(HEX.parseHex(zpkClear));
        this.zmk = HEX.parseHex(zmkClear);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", host + ":" + port);
        m.put("acquirerZpkKcv", kcv(zpk.get()));
        m.put("sentMessages", sent.size());
        return m;
    }

    // ---------------- financial / authorization / reversal ----------------

    public SimResult send(SimRequest r) throws ISOException, IOException {
        String type = r.type() == null ? "BALANCE_INQUIRY" : r.type();
        boolean advice = Boolean.TRUE.equals(r.advice());

        // a repeat resends the stored frame of an earlier message (MTI xxx1), to test duplicate handling
        if (Boolean.TRUE.equals(r.repeat()) && r.originalRef() != null) {
            byte[] frame = lastFrames.get(r.originalRef());
            if (frame == null) throw new IssuanceException("NOT_FOUND", "No message " + r.originalRef() + " to repeat");
            ISOMsg m = codec.unpack(frame);
            m.setMTI(m.getMTI().substring(0, 3) + "1");
            return exchange(r.originalRef(), m, false);
        }

        Sent orig = r.originalRef() == null ? null : sent.get(r.originalRef());
        if ((type.equals("REVERSAL") || type.equals("COMPLETION")) && orig == null) {
            throw new IssuanceException("INVALID_REQUEST", "originalRef of an earlier simulated message is required");
        }
        String pan = orig != null && (r.pan() == null && r.cardId() == null) ? orig.pan() : resolvePan(r);
        String channel = r.channel() == null ? "ATM" : r.channel();
        String terminal = pad(r.terminalId() == null ? (orig != null ? orig.terminalId() : channel.equals("ATM") ? "ATM00001" : "POS00001") : r.terminalId(), 8);
        String acquirer = r.acquirerId() == null ? "123456" : r.acquirerId();
        String currency = r.currency() == null ? (orig != null ? orig.currency() : "818") : r.currency();
        long amount = r.amount() == null ? 0 : r.amount();

        String mti = switch (type) {
            case "PREAUTH" -> advice ? "1120" : "1100";
            case "COMPLETION" -> "1220";
            case "REVERSAL" -> "1420";
            default -> advice ? "1220" : "1200";
        };
        String pc = switch (type) {
            case "WITHDRAWAL" -> "010000";
            case "BALANCE_INQUIRY" -> "310000";
            case "REFUND" -> "200000";
            case "PIN_CHANGE" -> "920000";
            case "REVERSAL" -> orig.pc();
            default -> "000000";
        };

        LocalDateTime now = LocalDateTime.now();
        String s = String.format("%06d", stan.incrementAndGet() % 1000000);
        ISOMsg m = codec.newMessage();
        m.setMTI(mti);
        m.set(2, pan);
        m.set(3, pc);
        m.set(7, LocalDateTime.now(ZoneOffset.UTC).format(F7));
        m.set(11, s);
        m.set(12, now.format(F12));
        m.set(22, channel.equals("ECOM") ? "100010000000" : "210101210000");
        m.set(26, r.mcc() != null ? r.mcc() : channel.equals("ATM") ? "6011" : "5411");
        m.set(32, acquirer);
        m.set(37, now.format(DateTimeFormatter.ofPattern("yyDDD")) + String.format("%07d", Integer.parseInt(s)));
        m.set(41, terminal);
        m.set(42, pad("CMSSIM" + terminal, 15));
        if (r.merchant() != null && !r.merchant().isBlank()) m.set(43, r.merchant());
        m.set(49, currency);

        if (type.equals("REVERSAL")) {
            long completed = r.amountCompleted() == null ? 0 : r.amountCompleted();
            if (completed > 0) {
                m.set(30, String.format("%012d%012d", orig.amount(), orig.amount()));
                m.set(4, String.format("%012d", completed));
            } else {
                m.set(4, String.format("%012d", orig.amount()));
            }
            m.set(56, orig.mti() + orig.stan() + orig.localDt() + String.format("%02d", orig.acquirerId().length()) + orig.acquirerId());
        } else {
            if (!type.equals("BALANCE_INQUIRY") && !type.equals("PIN_CHANGE")) m.set(4, String.format("%012d", amount));
            if (type.equals("COMPLETION")) {
                m.set(56, orig.mti() + orig.stan() + orig.localDt() + String.format("%02d", orig.acquirerId().length()) + orig.acquirerId());
            }
            if (r.pin() != null && !r.pin().isBlank()) m.set(52, pinBlock(r.pin(), pan));
            if (type.equals("PIN_CHANGE") && r.newPin() != null) m.set(125, HEX.formatHex(pinBlock(r.newPin(), pan)));
        }

        sent.put(s, new Sent(mti, s, now.format(F12), acquirer, type.equals("REVERSAL") ? orig.amount() : amount, pc, pan,
                terminal.trim(), currency));
        return exchange(s, m, true);
    }

    // ---------------- network management ----------------

    /** @param zpkClear for 811 only: the new ZPK to send (hex); null = random. */
    public SimResult network(String function, String zpkClear) throws ISOException, IOException, GeneralSecurityException {
        String s = String.format("%06d", stan.incrementAndGet() % 1000000);
        ISOMsg m = codec.newMessage();
        m.setMTI("1804");
        m.set(7, LocalDateTime.now(ZoneOffset.UTC).format(F7));
        m.set(11, s);
        m.set(24, function);
        m.set(32, "123456");
        byte[] newZpk = null;
        if (function.equals("811")) {
            if (zpkClear != null && zpkClear.matches("[0-9A-Fa-f]{32}")) {
                newZpk = HEX.parseHex(zpkClear);
            } else {
                newZpk = new byte[16];
                new SecureRandom().nextBytes(newZpk);
            }
            m.set(96, "U" + HEX.formatHex(tdes(zmk, newZpk, true)) + kcv(newZpk));
        }
        SimResult r = exchange(s, m, false);
        if (newZpk != null && NetworkManagementService.ACCEPTED.equals(r.actionCode())) zpk.set(newZpk);
        return r;
    }

    // ---------------- plumbing ----------------

    private SimResult exchange(String ref, ISOMsg m, boolean remember) throws ISOException, IOException {
        byte[] packed = codec.pack(m);
        if (remember) lastFrames.put(ref, packed);
        long t0 = System.nanoTime();
        ISOMsg resp;
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 3000);
            sock.setSoTimeout(15000);
            byte[] header = new byte[codec.headerLength()];
            java.util.Arrays.fill(header, (byte) '0');
            codec.writeFrame(sock.getOutputStream(), header, packed);
            byte[] frame = codec.readFrame(new DataInputStream(new BufferedInputStream(sock.getInputStream())));
            if (frame == null) throw new IOException("no response from CMS ISO server");
            resp = codec.split(frame).msg();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String ac = resp.getString(39);
        Long ledger = null, available = null;
        String f54 = resp.getString(54);
        if (f54 != null) {
            for (int i = 0; i + 20 <= f54.length(); i += 20) {
                String part = f54.substring(i, i + 20);
                long v = Long.parseLong(part.substring(8)) * (part.charAt(7) == 'D' ? -1 : 1);
                if (part.startsWith("01", 2)) ledger = v;
                if (part.startsWith("02", 2)) available = v;
            }
        }
        String text = "800".equals(ac) ? "Accepted" : ActionCode.text(ac);
        return new SimResult(ref, m.getMTI(), fields(m), fields(resp), ac, text,
                ActionCode.isApproval(ac) || "800".equals(ac), ledger, available, ms);
    }

    /** Fields for display: PAN masked, PIN blocks and key data hidden. */
    private static Map<String, String> fields(ISOMsg m) throws ISOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("0", m.getMTI());
        for (int i = 2; i <= 128; i++) {
            if (!m.hasField(i)) continue;
            String v = switch (i) {
                case 2 -> { String p = m.getString(2); yield p.substring(0, 6) + "******" + p.substring(p.length() - 4); }
                case 52, 125 -> "[PIN block]";
                case 96 -> "[key data]";
                default -> m.getString(i);
            };
            f.put(String.valueOf(i), v);
        }
        return f;
    }

    private String resolvePan(SimRequest r) {
        if (r.pan() != null && !r.pan().isBlank()) return r.pan().trim();
        if (r.cardId() == null) throw new IssuanceException("INVALID_REQUEST", "cardId or pan is required");
        byte[] enc = jdbc.query("SELECT pan_enc FROM card WHERE id = ?", rs -> rs.next() ? rs.getBytes(1) : null, r.cardId());
        if (enc == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        return panCrypto.decrypt(enc);
    }

    /** ISO 9564 format 0 PIN block under the current acquirer ZPK. */
    private byte[] pinBlock(String pin, String pan) {
        if (!pin.matches("\\d{4,12}")) throw new IssuanceException("INVALID_REQUEST", "PIN must be 4-12 digits");
        String field = ("0" + Integer.toHexString(pin.length()).toUpperCase() + pin + "F".repeat(14)).substring(0, 16);
        String acct = pan.substring(0, pan.length() - 1);
        acct = acct.substring(acct.length() - 12);
        byte[] p = HEX.parseHex(field), a = HEX.parseHex("0000" + acct);
        for (int i = 0; i < 8; i++) p[i] ^= a[i];
        try {
            return tdes(zpk.get(), p, true);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] tdes(byte[] key, byte[] data, boolean encrypt) throws GeneralSecurityException {
        byte[] k = new byte[24];
        System.arraycopy(key, 0, k, 0, 16);
        System.arraycopy(key, 0, k, 16, 8);
        Cipher c = Cipher.getInstance("DESede/ECB/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(k, "DESede"));
        return c.doFinal(data);
    }

    private static String kcv(byte[] key) {
        try {
            return HEX.formatHex(tdes(key, new byte[8], true)).substring(0, 6);
        } catch (GeneralSecurityException e) {
            return "?";
        }
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }

    static String hex(byte[] b) {
        return ISOUtil.hexString(b);
    }
}
