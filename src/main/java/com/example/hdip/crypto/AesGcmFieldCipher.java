package com.example.hdip.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM field-level encryption. Output format: base64(iv[12] || ciphertext || tag[16]).
 * Also provides a keyed HMAC-SHA256 for deterministic lookup columns (e.g. MRN hash).
 */
public class AesGcmFieldCipher {
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmFieldCipher(String base64AesKey, String base64HmacKey) {
        byte[] k = Base64.getDecoder().decode(base64AesKey);
        if (k.length != 16 && k.length != 24 && k.length != 32) {
            throw new IllegalArgumentException("AES key must be 128/192/256 bits (base64 encoded)");
        }
        byte[] h = Base64.getDecoder().decode(base64HmacKey);
        if (h.length < 16) throw new IllegalArgumentException("HMAC key too short");
        this.aesKey = new SecretKeySpec(k, "AES");
        this.hmacKey = new SecretKeySpec(h, "HmacSHA256");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length < IV_LEN + TAG_BITS / 8) throw new IllegalStateException("Ciphertext too short");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    /** Deterministic keyed hash (hex) for equality lookups without storing the plaintext. */
    public String hmac(String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.trim().toUpperCase().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
