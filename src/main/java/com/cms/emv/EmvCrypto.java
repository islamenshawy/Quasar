package com.cms.emv;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Card-side EMV cryptography in software, for the DEV corehost simulator and tests only.
 * The CMS itself never holds an IMK in clear: production verification is done by the HSM (KQ).
 * Mirrors hsm-sim: option A card key, EMV common session key, ISO 9797-1 MAC algorithm 3, ARPC method 1.
 */
public final class EmvCrypto {

    private EmvCrypto() {}

    /** scheme '0' Visa CVN10 (card key), '1' EMV common session key. */
    public static byte[] cryptogramKey(byte[] imk, byte[] y, byte[] atc, char scheme) {
        byte[] udk = udk(imk, y);
        return scheme == '0' ? udk : sessionKey(udk, atc);
    }

    public static byte[] udk(byte[] imk, byte[] y) {
        byte[] yx = new byte[8];
        for (int i = 0; i < 8; i++) yx[i] = (byte) (y[i] ^ 0xFF);
        return parity(concat(tdes(imk, y), tdes(imk, yx)));
    }

    public static byte[] sessionKey(byte[] udk, byte[] atc) {
        byte[] l = new byte[8], r = new byte[8];
        l[0] = r[0] = atc[0];
        l[1] = r[1] = atc[1];
        l[2] = (byte) 0xF0;
        r[2] = 0x0F;
        return parity(concat(tdes(udk, l), tdes(udk, r)));
    }

    public static byte[] arqc(byte[] key, byte[] data, char scheme) {
        int n = scheme == '0' ? data.length : data.length + 1;
        byte[] p = Arrays.copyOf(data, (n + 7) / 8 * 8);
        if (scheme != '0') p[data.length] = (byte) 0x80;
        byte[] k1 = Arrays.copyOfRange(key, 0, 8), k2 = Arrays.copyOfRange(key, 8, 16), h = new byte[8];
        for (int i = 0; i < p.length; i += 8) {
            for (int j = 0; j < 8; j++) h[j] ^= p[i + j];
            h = des(k1, h, true);
        }
        return des(k1, des(k2, h, false), true);
    }

    /**
     * Issuer script MAC (secure messaging for integrity), as the card checks it: card key for scheme '0',
     * else the session key derived from the ARQC (EMV Book 2 A1.3, R = AC); MAC algorithm 3, padding method 2,
     * leftmost 4 bytes. Mirrors hsm-sim KU.
     */
    public static byte[] scriptMac(byte[] imkSmi, byte[] y, byte[] arqc, char scheme, byte[] data) {
        byte[] key = udk(imkSmi, y);
        if (scheme != '0') {
            byte[] l = arqc.clone(), r = arqc.clone();
            l[2] = (byte) 0xF0;
            r[2] = 0x0F;
            key = parity(concat(tdes(key, l), tdes(key, r)));
        }
        return Arrays.copyOf(arqc(key, data, '1'), 4);
    }

    public static byte[] arpc(byte[] key, byte[] arqc, byte[] arc) {
        byte[] x = Arrays.copyOf(arqc, 8);
        x[0] ^= arc[0];
        x[1] ^= arc[1];
        return tdes(key, x);
    }

    private static byte[] parity(byte[] k) {
        for (int i = 0; i < k.length; i++) {
            int b = k[i] & 0xFE;
            k[i] = (byte) (b | (Integer.bitCount(b) % 2 == 0 ? 1 : 0));
        }
        return k;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] tdes(byte[] key16, byte[] data) {
        byte[] k = new byte[24];
        System.arraycopy(key16, 0, k, 0, 16);
        System.arraycopy(key16, 0, k, 16, 8);
        try {
            Cipher c = Cipher.getInstance("DESede/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "DESede"));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] des(byte[] key8, byte[] data, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key8, "DES"));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
