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
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
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
}
