package com.cms.hsm;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * payShield 10K host-command client for the CMS.
 *
 * Every key argument is the key cryptogram UNDER THE LMK exactly as the HSM
 * expects it, including its scheme tag (e.g. "U" + 32H for a double-length
 * variant-LMK key, or "S..." for a key-block LMK). The CMS never holds a clear
 * key, PIN, or PIN block in clear.
 *
 * IMPORTANT: field layouts below follow the payShield host command reference as
 * commonly configured (variant LMK, ISO-0 PIN blocks, 12-digit account number).
 * Verify each against the Host Command Reference for YOUR firmware and LMK type
 * before relying on it. Optional fields / delimiters (e.g. LMK identifier '%',
 * key-block options '#') are not included.
 */
public final class PayShieldClient implements AutoCloseable {

    /** payShield PIN block format codes. */
    public enum PinBlockFormat {
        ISO_0("01"),   // ISO 9564-1 format 0 / ANSI X9.8
        ISO_1("05"),
        ISO_3("47"),
        ISO_4("48");
        final String code;
        PinBlockFormat(String code) { this.code = code; }
    }

    public record Config(String host, int port, int headerLength, int poolSize,
                         int connectTimeoutMs, int readTimeoutMs, int borrowTimeoutMs) {}

    public record ImportedKey(String keyUnderLmk, String kcv) {}

    private final Config cfg;
    /** Idle connections ready for reuse. */
    private final ConcurrentLinkedQueue<PayShieldConnection> idle = new ConcurrentLinkedQueue<>();
    /** Caps concurrent HSM calls (and therefore open connections) at poolSize. */
    private final Semaphore permits;

    /**
     * Connections are opened lazily on first use, so the CMS starts even when the HSM
     * is down; the health endpoint (NC) reports the HSM state instead.
     */
    public PayShieldClient(Config cfg) {
        this.cfg = cfg;
        this.permits = new Semaphore(cfg.poolSize(), true);
    }

    // ------------------------------------------------------------------
    // NC - diagnostics. Use at startup and as a health check.
    // Response ND + err + LMK check value (16) + firmware number (rest)
    // ------------------------------------------------------------------
    public String diagnostics() {
        String data = call("NC", "");
        return data; // LMK KCV (first 16) + firmware; log it, alert if LMK KCV changes
    }

    // ------------------------------------------------------------------
    // FA - translate a ZPK from ZMK to LMK encryption (dynamic key exchange,
    // BASE24 network management message carries ZPK under ZMK + KCV).
    // Request : FA + ZMK(lmk) + ZPK(under ZMK)
    // Response: FB + err + ZPK(under LMK) + KCV
    // Caller MUST compare returned KCV with the KCV received in the message.
    // ------------------------------------------------------------------
    public ImportedKey importZpk(String zmkUnderLmk, String zpkUnderZmk) {
        String data = call("FA", zmkUnderLmk + zpkUnderZmk);
        int keyLen = keyTokenLength(data, 0);
        String zpk = data.substring(0, keyLen);
        String kcv = data.substring(keyLen).trim();
        return new ImportedKey(zpk, kcv);
    }

    // ------------------------------------------------------------------
    // JE - translate a PIN block from ZPK to LMK encryption.
    // Used for PIN set (activation) and new PIN in PIN change, before DG.
    // Request : JE + ZPK + PIN block(16H) + format(2) + account(12)
    // Response: JF + err + PIN under LMK (length = PIN length + 1, variable)
    // ------------------------------------------------------------------
    public String translatePinZpkToLmk(String zpk, String pinBlock,
                                       PinBlockFormat fmt, String pan) {
        requireHex(pinBlock, 16, "PIN block");
        return call("JE", zpk + pinBlock + fmt.code + accountNumber12(pan));
    }

    // ------------------------------------------------------------------
    // DG - generate a VISA PVV from an LMK-encrypted PIN.
    // Request : DG + PVK pair + PIN(lmk) + account(12) + PVKI(1)
    // Response: DH + err + PVV(4)
    // ------------------------------------------------------------------
    public String generatePvv(String pvk, String pinUnderLmk, String pan, char pvki) {
        String data = call("DG", pvk + pinUnderLmk + accountNumber12(pan) + pvki);
        return data.substring(0, 4);
    }

    // ------------------------------------------------------------------
    // EC - verify an interchange PIN (under ZPK) using the VISA PVV method.
    // Request : EC + ZPK + PVK pair + PIN block + format + account(12) + PVKI + PVV
    // Response: ED + err   (00 = verified, 01 = verification failure)
    // Returns false only for a genuine wrong PIN; anything else throws.
    // ------------------------------------------------------------------
    public boolean verifyPinVisa(String zpk, String pvk, String pinBlock,
                                 PinBlockFormat fmt, String pan, char pvki, String pvv) {
        requireHex(pinBlock, 16, "PIN block");
        String body = zpk + pvk + pinBlock + fmt.code + accountNumber12(pan) + pvki + pvv;
        Response r = raw("EC", body);
        if ("00".equals(r.errorCode)) return true;
        if ("01".equals(r.errorCode)) return false;
        throw new HsmException("EC", r.errorCode, "PIN verification error");
    }

    // ------------------------------------------------------------------
    // CW - generate a card verification value.
    // Request : CW + CVK pair + PAN + ';' + expiry(YYMM) + service code(3)
    // Response: CX + err + CVV(3)
    // Service code conventions: magstripe CVV1 = card's service code,
    // iCVV = "999", CVV2 = "000". Confirm with the scheme/product spec.
    // ------------------------------------------------------------------
    public String generateCvv(String cvk, String pan, String expiryYYMM, String serviceCode) {
        String data = call("CW", cvk + pan + ";" + expiryYYMM + serviceCode);
        return data.substring(0, 3);
    }

    // ------------------------------------------------------------------
    // CY - verify a card verification value.
    // Request : CY + CVK pair + CVV(3) + PAN + ';' + expiry(YYMM) + service code(3)
    // Response: CZ + err   (00 = verified, 01 = verification failure)
    // ------------------------------------------------------------------
    public boolean verifyCvv(String cvk, String cvv, String pan, String expiryYYMM, String serviceCode) {
        Response r = raw("CY", cvk + cvv + pan + ";" + expiryYYMM + serviceCode);
        if ("00".equals(r.errorCode)) return true;
        if ("01".equals(r.errorCode)) return false;
        throw new HsmException("CY", r.errorCode, "CVV verification error");
    }

    // ------------------------------------------------------------------
    // KQ - ARQC verification / ARPC generation. NOT IMPLEMENTED YET.
    // Needs: mode flag, scheme ID (Visa/MC CVN), MK-AC, PAN+PSN, ATC,
    // unpredictable number, transaction data (from BASE24 EMV token / field 55),
    // ARQC, ARC. Layout differs per scheme/CVN - build from the manual once the
    // BASE24 EMV token layout is known.
    // ------------------------------------------------------------------
    public boolean verifyArqcAndGenerateArpc(/* EmvData data */) {
        throw new UnsupportedOperationException("KQ pending BASE24 EMV token spec");
    }

    // ==================================================================
    // Plumbing
    // ==================================================================

    private record Response(String errorCode, String data) {}

    /** Executes and requires error code 00; returns the data after the error code. */
    private String call(String cmd, String body) {
        Response r = raw(cmd, body);
        if (!"00".equals(r.errorCode)) {
            throw new HsmException(cmd, r.errorCode, "non-zero HSM error code");
        }
        return r.data;
    }

    private Response raw(String cmd, String body) {
        PayShieldConnection conn = borrow(cmd);
        boolean healthy = true;
        try {
            String resp = conn.exchange(cmd + body);
            String expected = responseCode(cmd);
            String got = resp.substring(0, 2);
            if (!expected.equals(got)) {
                throw new HsmException(cmd, "--", "unexpected response code " + got);
            }
            return new Response(resp.substring(2, 4), resp.substring(4));
        } catch (IOException e) {
            healthy = false;
            throw new HsmException(cmd, "I/O error", e);
        } finally {
            giveBack(conn, healthy);
        }
    }

    private PayShieldConnection borrow(String cmd) {
        try {
            if (!permits.tryAcquire(cfg.borrowTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new HsmException(cmd, "HSM pool exhausted", (Throwable) null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HsmException(cmd, "interrupted", e);
        }
        PayShieldConnection c;
        while ((c = idle.poll()) != null) {
            if (c.isUsable()) return c;
            closeQuietly(c);
        }
        try {
            return open();
        } catch (HsmException e) {
            permits.release();
            throw e;
        }
    }

    private void giveBack(PayShieldConnection c, boolean healthy) {
        try {
            if (healthy && c.isUsable()) {
                idle.offer(c);
            } else {
                closeQuietly(c);   // next borrow opens a fresh connection
            }
        } finally {
            permits.release();
        }
    }

    private static void closeQuietly(PayShieldConnection c) {
        try { c.close(); } catch (IOException ignored) { }
    }

    private PayShieldConnection open() {
        try {
            return new PayShieldConnection(cfg.host(), cfg.port(), cfg.headerLength(),
                    cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        } catch (IOException e) {
            throw new HsmException("CONNECT", "cannot connect to HSM", e);
        }
    }

    /** NC->ND, JE->JF, EC->ED ... second character incremented. */
    static String responseCode(String cmd) {
        return "" + cmd.charAt(0) + (char) (cmd.charAt(1) + 1);
    }

    /**
     * 12 right-most PAN digits excluding the check digit.
     * e.g. PAN 4000001234567899 -> "000123456789"
     */
    static String accountNumber12(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}")) {
            throw new IllegalArgumentException("invalid PAN");
        }
        String noCheck = pan.substring(0, pan.length() - 1);
        return noCheck.substring(noCheck.length() - 12);
    }

    /**
     * Length of a key token at position pos, by scheme tag.
     * U/X = double length (1+32), T/Y = triple (1+48), Z = single (1+16),
     * S = Thales key block (length taken from its header), none = 16H single.
     * Verify key-block length parsing against your LMK configuration.
     */
    static int keyTokenLength(String s, int pos) {
        char tag = s.charAt(pos);
        return switch (tag) {
            case 'U', 'X' -> 33;
            case 'T', 'Y' -> 49;
            case 'Z'      -> 17;
            case 'S'      -> 1 + Integer.parseInt(s.substring(pos + 2, pos + 6));
            default       -> 16;
        };
    }

    private static void requireHex(String v, int len, String name) {
        if (v == null || v.length() != len || !v.matches("[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException(name + " must be " + len + " hex chars");
        }
    }

    @Override
    public void close() {
        PayShieldConnection c;
        while ((c = idle.poll()) != null) {
            closeQuietly(c);
        }
    }
}
