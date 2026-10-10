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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-256-GCM field-level encryption with a rotation-ready key ring.
 *
 * <p>Output format: {@code <keyId>:base64(iv[12] || ciphertext || tag[16])}. New values are always written with
 * the active key; any key still present in the ring can decrypt. Values without a {@code keyId:} prefix (written by
 * versions before key ids existed) are decrypted with the legacy key (see {@link #LEGACY_KEY_ID}).
 *
 * <p>Also provides a keyed HMAC-SHA256 for deterministic lookup columns (e.g. MRN hash).
 */
public class AesGcmFieldCipher {
    /** Key id assumed for ciphertext that carries no id prefix. */
    public static final String LEGACY_KEY_ID = "k1";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String activeKeyId;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    /** Single-key convenience constructor: the key gets id {@value #LEGACY_KEY_ID}. */
    public AesGcmFieldCipher(String base64AesKey, String base64HmacKey) {
        this(Map.of(LEGACY_KEY_ID, base64AesKey), LEGACY_KEY_ID, base64HmacKey);
    }

    public AesGcmFieldCipher(Map<String, String> base64KeysById, String activeKeyId, String base64HmacKey) {
        if (base64KeysById == null || base64KeysById.isEmpty()) throw new IllegalArgumentException("No encryption keys configured");
        base64KeysById.forEach((id, b64) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid key id: " + id);
            byte[] k = decode(b64, "AES key " + id);
            if (k.length != 16 && k.length != 24 && k.length != 32) {
                throw new IllegalArgumentException("AES key must be 128/192/256 bits (base64 encoded)");
            }
            keys.put(id, new SecretKeySpec(k, "AES"));
        });
        if (!keys.containsKey(activeKeyId)) throw new IllegalArgumentException("Active key id '" + activeKeyId + "' is not in the key ring");
        this.activeKeyId = activeKeyId;
        byte[] h = decode(base64HmacKey, "HMAC key");
        if (h.length < 16) throw new IllegalArgumentException("HMAC key too short");
        this.hmacKey = new SecretKeySpec(h, "HmacSHA256");
    }

    private static byte[] decode(String b64, String what) {
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(what + " is not valid base64");
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /** Parses {@code id1:base64,id2:base64} (as used by PHI_ENCRYPTION_KEYS) into an ordered map. */
    public static Map<String, String> parseKeyRing(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int i = p.indexOf(':');
            if (i <= 0 || i == p.length() - 1) throw new IllegalArgumentException("Key ring entries must look like id:base64key");
            out.put(p.substring(0, i).trim(), p.substring(i + 1).trim());
        }
        return out;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            String kid = LEGACY_KEY_ID;
            String body = encoded;
            int sep = encoded.indexOf(':');
            if (sep > 0) {
                kid = encoded.substring(0, sep);
                body = encoded.substring(sep + 1);
            }
            SecretKeySpec key = keys.get(kid);
            if (key == null) throw new IllegalStateException("Decryption failed (unknown key id '" + kid + "')");
            byte[] all = Base64.getDecoder().decode(body);
            if (all.length < IV_LEN + TAG_BITS / 8) throw new IllegalStateException("Ciphertext too short");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    /** True if the value was not written with the currently active key (candidate for re-encryption). */
    public boolean needsRotation(String encoded) {
        if (encoded == null) return false;
        int sep = encoded.indexOf(':');
        String kid = sep > 0 ? encoded.substring(0, sep) : LEGACY_KEY_ID;
        return !activeKeyId.equals(kid);
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
