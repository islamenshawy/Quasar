package com.cms.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PAN protection at rest (PCI DSS req. 3):
 *  - encrypt(): AES-256-GCM, output = 12-byte IV || ciphertext+tag
 *  - hash():    HMAC-SHA256 keyed hash, used as the unique lookup key
 *
 * TEST ENVIRONMENT: keys come from config/env. Production must source them from a
 * KMS or HSM-wrapped key store with rotation - this class is the only place to change.
 */
@Component
public class PanCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encKey;
    private final SecretKeySpec macKey;
    private final SecureRandom random = new SecureRandom();

    public PanCrypto(@Value("${cms.security.pan-enc-key}") String encKeyB64,
                     @Value("${cms.security.pan-hmac-key}") String macKeyB64) {
        byte[] e = Base64.getDecoder().decode(encKeyB64);
        byte[] m = Base64.getDecoder().decode(macKeyB64);
        if (e.length != 32 || m.length < 32) {
            throw new IllegalStateException("PAN keys must be 256-bit");
        }
        this.encKey = new SecretKeySpec(e, "AES");
        this.macKey = new SecretKeySpec(m, "HmacSHA256");
    }

    public byte[] encrypt(String pan) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PAN encryption failed", ex);
        }
    }

    public String decrypt(byte[] blob) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            byte[] pt = c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
            return new String(pt, StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException ex) {
            throw new PanKeyException(ex);
        }
    }

    public byte[] hash(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return mac.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PAN hashing failed", ex);
        }
    }

    public static String mask(String pan) {
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }
}
