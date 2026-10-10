package com.example.hdip.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmFieldCipherTest {
    static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final String KEY2 = Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes());
    static final String HMAC = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    final AesGcmFieldCipher cipher = new AesGcmFieldCipher(KEY, HMAC);

    @Test
    void roundTrip() {
        String enc = cipher.encrypt("Testerson, Alex 1970-01-01");
        assertThat(enc).doesNotContain("Testerson");
        assertThat(cipher.decrypt(enc)).isEqualTo("Testerson, Alex 1970-01-01");
    }

    @Test
    void randomIvGivesDifferentCiphertexts() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void nullsPassThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(new EncryptedStringConverter(cipher).convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void unicodeAndEmpty() {
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
        assertThat(cipher.decrypt(cipher.encrypt("Zoë Müller 患者"))).isEqualTo("Zoë Müller 患者");
    }

    @Test
    void tamperedCiphertextRejected() {
        String enc = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(enc.substring(enc.indexOf(':') + 1));
        raw[raw.length - 1] ^= 1;
        String tampered = "k1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyRejected() {
        String enc = cipher.encrypt("secret");
        assertThatThrownBy(() -> new AesGcmFieldCipher(KEY2, HMAC).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKeyLengthRejected() {
        assertThatThrownBy(() -> new AesGcmFieldCipher(Base64.getEncoder().encodeToString(new byte[10]), HMAC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hmacIsDeterministicAndNormalized() {
        assertThat(cipher.hmac("syn-1")).isEqualTo(cipher.hmac(" SYN-1 ")).hasSize(64);
        assertThat(cipher.hmac("SYN-1")).isNotEqualTo(cipher.hmac("SYN-2"));
    }

    @Test
    void converterEncryptsAndDecrypts() {
        var conv = new EncryptedStringConverter(cipher);
        String db = conv.convertToDatabaseColumn("abc");
        assertThat(db).isNotEqualTo("abc");
        assertThat(conv.convertToEntityAttribute(db)).isEqualTo("abc");
    }

    @Test
    void ciphertextCarriesActiveKeyId() {
        assertThat(cipher.encrypt("x")).startsWith("k1:");
        assertThat(cipher.activeKeyId()).isEqualTo("k1");
    }

    @Test
    void rotationOldDataStaysReadableAndNewDataUsesNewKey() {
        String oldValue = cipher.encrypt("Testerson");
        var rotated = new AesGcmFieldCipher(java.util.Map.of("k1", KEY, "k2", KEY2), "k2", HMAC);
        assertThat(rotated.decrypt(oldValue)).isEqualTo("Testerson");
        assertThat(rotated.needsRotation(oldValue)).isTrue();
        String fresh = rotated.encrypt("Testerson");
        assertThat(fresh).startsWith("k2:");
        assertThat(rotated.needsRotation(fresh)).isFalse();
        // once k1 is retired from the ring, old data is unreadable
        var retired = new AesGcmFieldCipher(java.util.Map.of("k2", KEY2), "k2", HMAC);
        assertThatThrownBy(() -> retired.decrypt(oldValue)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyUnprefixedCiphertextDecryptsWithLegacyKey() throws Exception {
        // value as written before key ids existed: base64(iv || ct || tag), no prefix
        String legacy = cipher.encrypt("legacy").substring(3);
        assertThat(cipher.decrypt(legacy)).isEqualTo("legacy");
        assertThat(cipher.needsRotation(legacy)).isFalse(); // k1 is active here
        var rotated = new AesGcmFieldCipher(java.util.Map.of("k1", KEY, "k2", KEY2), "k2", HMAC);
        assertThat(rotated.needsRotation(legacy)).isTrue();
    }

    @Test
    void activeKeyMustBeInRing() {
        assertThatThrownBy(() -> new AesGcmFieldCipher(java.util.Map.of("k1", KEY), "k9", HMAC)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesKeyRing() {
        assertThat(AesGcmFieldCipher.parseKeyRing("k1:" + KEY + ", k2:" + KEY2)).containsOnlyKeys("k1", "k2").containsEntry("k2", KEY2);
        assertThat(AesGcmFieldCipher.parseKeyRing("")).isEmpty();
        assertThatThrownBy(() -> AesGcmFieldCipher.parseKeyRing("nocolon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cryptoConfigFailsFastWithoutKeys() {
        var cfg = new CryptoConfig();
        assertThatThrownBy(() -> cfg.aesGcmFieldCipher("", "", "", HMAC)).isInstanceOf(IllegalStateException.class).hasMessageContaining("PHI_ENCRYPTION_KEYS");
        assertThatThrownBy(() -> cfg.aesGcmFieldCipher("k1:" + KEY, "", "", "")).isInstanceOf(IllegalStateException.class).hasMessageContaining("PHI_HMAC_KEY");
        // active defaults to the last key in the ring
        assertThat(cfg.aesGcmFieldCipher("k1:" + KEY + ",k2:" + KEY2, "", "", HMAC).activeKeyId()).isEqualTo("k2");
        assertThat(cfg.aesGcmFieldCipher("k1:" + KEY + ",k2:" + KEY2, "", "k1", HMAC).activeKeyId()).isEqualTo("k1");
        // legacy single-key variable still works
        assertThat(cfg.aesGcmFieldCipher("", KEY, "", HMAC).activeKeyId()).isEqualTo("k1");
    }
}
