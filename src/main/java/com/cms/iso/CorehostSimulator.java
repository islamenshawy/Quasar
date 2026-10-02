package com.cms.iso;

import com.cms.auth.ActionCode;
import com.cms.card.IssuanceException;
import com.cms.security.PanCrypto;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                             Boolean repeat, Boolean chip, Integer atc, Boolean tamperArqc, String tvr,
                             String country, Boolean contactless, String cvv2, Boolean failScripts) {}

    public record SimResult(String ref, String mti, Map<String, String> request, Map<String, String> response,
                            String actionCode, String actionText, boolean approved, Long ledgerBalance,
                            Long availableBalance, long elapsedMs, Map<String, Object> chip) {}

    private record Sent(String mti, String stan, String localDt, String acquirerId, long amount, String pc,
                        String pan, String terminalId, String currency) {}

    private final IsoCodec codec;
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final String host;
    private final int port;
    private final byte[] zmk;
    private static final Logger log = LoggerFactory.getLogger(CorehostSimulator.class);

    private final AtomicReference<byte[]> zpk = new AtomicReference<>();
    private final String zpkName;
    private final AtomicInteger stan = new AtomicInteger((int) (System.currentTimeMillis() % 800000) + 100000);
    private final Map<String, Sent> sent = new ConcurrentHashMap<>();
    private final Map<String, byte[]> lastFrames = new ConcurrentHashMap<>();
    private final byte[] imk;
    private final Map<String, Integer> atcByCard = new ConcurrentHashMap<>();
    /** Card key per sent chip message, to check the ARPC in the answer. */
    private final Map<String, byte[][]> chipState = new ConcurrentHashMap<>();
    /** Per sent chip message: card id, Y, scheme, whether the emulated chip should refuse scripts. */
    private record ChipMeta(long cardId, byte[] y, char scheme, boolean failScripts) {}
    private final Map<String, ChipMeta> chipMeta = new ConcurrentHashMap<>();
    /** Issuer script results the emulated chip reports in 9F5B on its next transaction, per card. */
    private final Map<Long, java.util.List<byte[]>> scriptResults = new ConcurrentHashMap<>();
    private final byte[] imkSmi;

    public CorehostSimulator(IsoCodec codec, JdbcTemplate jdbc, PanCrypto panCrypto,
                             @Value("${cms.dev.iso-host:localhost}") String host,
                             @Value("${cms.iso.port:7000}") int port,
                             @Value("${cms.dev.acquirer-zpk-clear:4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E}") String zpkClear,
                             @Value("${cms.dev.zmk-clear:1C1C1C1C1C1C1C1C2A2A2A2A2A2A2A2A}") String zmkClear,
                             @Value("${cms.dev.imk-ac-clear:4A4A4A4A4A4A4A4A6D6D6D6D6D6D6D6D}") String imkClear,
                             @Value("${cms.dev.imk-smi-clear:5A5A5A5A5A5A5A5A3C3C3C3C3C3C3C3C}") String imkSmiClear,
                             @Value("${cms.keys.corehost-zpk-name}") String zpkName) {
        this.codec = codec;
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.host = host;
        this.port = port;
        this.zpk.set(HEX.parseHex(zpkClear));
        this.zmk = HEX.parseHex(zmkClear);
        this.imk = HEX.parseHex(imkClear);
        this.imkSmi = HEX.parseHex(imkSmiClear);
        this.zpkName = zpkName;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", host + ":" + port);
        m.put("acquirerZpkKcv", kcv(zpk.get()));
        m.put("cmsZpkKcv", activeZpkKcv());
        m.put("sentMessages", sent.size());
        return m;
    }

    // ---------------- financial / authorization / reversal ----------------

    public SimResult send(SimRequest r) throws ISOException, IOException {
        String type = r.type() == null ? "BALANCE_INQUIRY" : r.type();
        boolean advice = Boolean.TRUE.equals(r.advice());
        syncZpk();

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
        // position 7, card data input mode (provisional, IN-01): 5 chip, M contactless, 2 magnetic stripe
        char input = Boolean.TRUE.equals(r.contactless()) ? 'M' : Boolean.TRUE.equals(r.chip()) ? '5' : '2';
        m.set(22, channel.equals("ECOM") ? "100010000000" : "210101" + input + "10000");
        if (r.cvv2() != null && r.cvv2().matches("[0-9]{3,4}")) m.set(48, "CV2" + r.cvv2());
        m.set(26, r.mcc() != null ? r.mcc() : channel.equals("ATM") ? "6011" : "5411");
        m.set(32, acquirer);
        if (r.country() != null && r.country().matches("[0-9]{3}")) m.set(19, r.country());
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
            if (Boolean.TRUE.equals(r.chip())) m.set(55, chipData(s, r, pan, pc, amount, currency, now));
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

    /**
     * The simulator holds its ZPK in memory only: after a restart it is back to the seed key while the CMS
     * still has the key from the last 811. A real switch keeps its keys, so here: before sending, compare the
     * check values and, when they differ, run the key exchange with the key this simulator holds.
     */
    private void syncZpk() {
        String cms = activeZpkKcv();
        String mine = kcv(zpk.get());
        if (cms == null || cms.equalsIgnoreCase(mine)) return;
        log.warn("Simulator ZPK (KCV {}) differs from the CMS acquirer ZPK (KCV {}); re-running key exchange 811", mine, cms);
        try {
            SimResult r = network("811", HEX.formatHex(zpk.get()));
            if (!NetworkManagementService.ACCEPTED.equals(r.actionCode())) {
                log.warn("Key exchange refused: {} {}", r.actionCode(), r.actionText());
            }
        } catch (Exception e) {
            log.warn("Key exchange failed: {}", e.getMessage());
        }
    }

    private String activeZpkKcv() {
        return jdbc.query("SELECT kcv FROM hsm_key WHERE key_name = ? AND active", rs -> rs.next() ? rs.getString(1) : null, zpkName);
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
                ActionCode.isApproval(ac) || "800".equals(ac), ledger, available, ms, chipResult(ref, resp));
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
                case 55 -> "[chip data " + m.getBytes(55).length + " bytes]";
                case 96 -> "[key data]";
                default -> m.getString(i);
            };
            f.put(String.valueOf(i), v);
        }
        return f;
    }

    /** Field 55 as the chip would send it; the ARQC is computed with the dev IMK-AC, like a real card. */
    private byte[] chipData(String ref, SimRequest r, String pan, String pc, long amount, String currency, LocalDateTime now) {
        if (r.cardId() == null) throw new IssuanceException("INVALID_REQUEST", "Chip transactions need cardId");
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT k.psn, pr.emv_scheme, pr.emv_data_list, pr.scheme FROM card k JOIN card_product pr ON pr.id = k.product_id WHERE k.id = ?
                """, r.cardId());
        String psn = (String) p.get("psn");
        char scheme = com.cms.emv.EmvService.cryptoScheme((String) p.get("emv_scheme"));
        // like a real chip, the counter only goes up: continue after the highest ATC the CMS has seen
        int atcValue = r.atc() != null ? r.atc() : atcByCard.merge(String.valueOf(r.cardId()), 1,
                (cur, one) -> cur + one);
        if (r.atc() == null) {
            Integer seen = jdbc.queryForObject("SELECT last_atc FROM card WHERE id = ?", Integer.class, r.cardId());
            if (seen != null && atcValue <= seen) {
                atcValue = seen + 1;
                atcByCard.put(String.valueOf(r.cardId()), atcValue);
            }
        }
        byte[] atc = {(byte) (atcValue >> 8), (byte) atcValue};
        byte[] un = new byte[4];
        new SecureRandom().nextBytes(un);
        boolean atm = "ATM".equals(r.channel()) || "01".equals(pc.substring(0, 2)) || "31".equals(pc.substring(0, 2));
        boolean pinEntered = r.pin() != null && !r.pin().isBlank();
        String tvr = r.tvr() != null && r.tvr().matches("[0-9A-Fa-f]{10}") ? r.tvr() : "0000000000";
        Map<String, byte[]> t = new java.util.LinkedHashMap<>();
        // card and terminal data as a real terminal would send them (profile: contact chip, online-capable)
        t.put("9F02", HEX.parseHex(String.format("%012d", amount)));
        t.put("9F03", new byte[6]);
        t.put("9F1A", HEX.parseHex("0818"));
        t.put("95", HEX.parseHex(tvr));
        t.put("5F2A", HEX.parseHex("0" + currency));
        t.put("9A", HEX.parseHex(now.format(DateTimeFormatter.ofPattern("yyMMdd"))));
        t.put("9F21", HEX.parseHex(now.format(DateTimeFormatter.ofPattern("HHmmss"))));
        t.put("9C", HEX.parseHex(pc.substring(0, 2)));
        t.put("9F37", un);
        t.put("82", HEX.parseHex("3C00"));
        t.put("9F36", atc);
        t.put("9F10", HEX.parseHex("VISA_CVN17".equals(p.get("emv_scheme")) ? "06011103A00000"
                : scheme == '0' ? "06010A03A00000" : "06011203A00000"));
        java.util.List<byte[]> done = scriptResults.remove(r.cardId());
        if (done != null && !done.isEmpty()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            done.forEach(b::writeBytes);
            t.put("9F5B", b.toByteArray());     // issuer script results from the previous answer
        }
        t.put("9F27", HEX.parseHex("80"));
        t.put("5F34", HEX.parseHex(psn));
        t.put("84", HEX.parseHex(switch (String.valueOf(p.get("scheme"))) {
            case "VISA" -> "A0000000031010";
            case "MASTERCARD" -> "A0000000041010";
            default -> "F0000000010001";     // proprietary (unregistered) RID for domestic/test products
        }));
        t.put("9F09", HEX.parseHex("VISA".equals(p.get("scheme")) ? "008C" : "0002"));
        t.put("9F07", HEX.parseHex("FF00"));
        t.put("9F33", HEX.parseHex(atm ? "6040E8" : "E0F8C8"));
        t.put("9F35", HEX.parseHex(atm ? "14" : "22"));
        t.put("9F34", HEX.parseHex(pinEntered ? "420300" : "1F0302"));
        t.put("9B", HEX.parseHex("E800"));
        t.put("9F1E", "SIMTERM1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        t.put("9F41", HEX.parseHex("00" + ref));
        byte[] data = com.cms.emv.EmvService.dataBlockFor(t, (String) p.get("emv_data_list"));
        byte[] key = com.cms.emv.EmvCrypto.cryptogramKey(imk, com.cms.emv.EmvService.y(pan, psn), atc, scheme);
        byte[] arqc = com.cms.emv.EmvCrypto.arqc(key, data, scheme);
        if (Boolean.TRUE.equals(r.tamperArqc())) arqc[0] ^= 0x01;
        t.put("9F26", arqc);
        byte[] field55 = com.cms.emv.Tlv.encode(t);
        chipState.put(ref, new byte[][]{key, arqc, atc, field55});
        chipMeta.put(ref, new ChipMeta(r.cardId(), com.cms.emv.EmvService.y(pan, psn), scheme, Boolean.TRUE.equals(r.failScripts())));
        return field55;
    }

    /** What the card would conclude from the answer: ARPC present and valid for the returned ARC. */
    private Map<String, Object> chipResult(String ref, ISOMsg resp) throws ISOException {
        byte[][] st = chipState.remove(ref);
        if (st == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atc", ((st[2][0] & 0xFF) << 8) | (st[2][1] & 0xFF));
        // raw DE55 both ways, for the decoder in the console (dev simulator data only: no track-2 equivalent)
        out.put("requestIcc", HEX.formatHex(st[3]));
        out.put("responseIcc", resp.hasField(55) ? HEX.formatHex(resp.getBytes(55)) : null);
        byte[] iad = resp.hasField(55) ? com.cms.emv.Tlv.parse(resp.getBytes(55)).get("91") : null;
        out.put("arpcReceived", iad != null);
        if (iad != null && iad.length == 10) {
            byte[] arc = java.util.Arrays.copyOfRange(iad, 8, 10);
            byte[] expected = com.cms.emv.EmvCrypto.arpc(st[0], st[1], arc);
            out.put("arc", new String(arc, java.nio.charset.StandardCharsets.US_ASCII));
            out.put("arpcValid", java.util.Arrays.equals(expected, java.util.Arrays.copyOf(iad, 8)));
        }
        ChipMeta meta = chipMeta.remove(ref);
        if (meta != null && resp.hasField(55)) out.put("scripts", runScripts(meta, st, resp.getBytes(55)));
        return out;
    }

    /**
     * The emulated chip runs the issuer scripts of the answer (tag 72): checks each command's MAC with the dev
     * IMK-SMI exactly as a card would, and keeps the results (9F5B) for its next transaction.
     */
    private java.util.List<Map<String, Object>> runScripts(ChipMeta meta, byte[][] st, byte[] field55) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.List<byte[]> results = new java.util.ArrayList<>();
        int seq = 0;
        for (Map.Entry<String, byte[]> e : com.cms.emv.Tlv.parseList(field55)) {
            if (!"72".equals(e.getKey())) continue;
            Map<String, byte[]> inner = com.cms.emv.Tlv.parse(e.getValue());
            byte[] id = inner.get("9F18"), apdu = inner.get("86");
            if (id == null || apdu == null || apdu.length < 9) continue;
            byte[] header = java.util.Arrays.copyOfRange(apdu, 0, 5);
            byte[] data = java.util.Arrays.copyOfRange(apdu, 5, apdu.length - 4);
            byte[] mac = java.util.Arrays.copyOfRange(apdu, apdu.length - 4, apdu.length);
            java.io.ByteArrayOutputStream in = new java.io.ByteArrayOutputStream();
            in.writeBytes(header);
            in.writeBytes(st[2]);
            in.writeBytes(st[1]);
            in.writeBytes(data);
            boolean valid = java.util.Arrays.equals(mac, com.cms.emv.EmvCrypto.scriptMac(imkSmi, meta.y(), st[1], meta.scheme(), in.toByteArray()));
            int result = valid && !meta.failScripts() ? 2 : 1;
            seq++;
            byte[] r = new byte[5];
            r[0] = (byte) ((result << 4) | Math.min(seq, 15));
            System.arraycopy(id, 0, r, 1, 4);
            results.add(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scriptId", HEX.formatHex(id));
            m.put("apdu", HEX.formatHex(apdu));
            m.put("macValid", valid);
            m.put("result", result == 2 ? "SUCCESSFUL" : "FAILED");
            out.add(m);
        }
        if (!results.isEmpty()) scriptResults.computeIfAbsent(meta.cardId(), k -> new java.util.ArrayList<>()).addAll(results);
        return out;
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
