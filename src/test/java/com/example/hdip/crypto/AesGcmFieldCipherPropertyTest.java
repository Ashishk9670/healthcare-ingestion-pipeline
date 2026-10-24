package com.example.hdip.crypto;

import net.jqwik.api.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Property tests for AES-256-GCM field encryption (random SYNTHETIC keys and plaintexts). */
class AesGcmFieldCipherPropertyTest {
    private static final SecureRandom RNG = new SecureRandom();

    private static String randomKeyB64(int bytes) {
        byte[] k = new byte[bytes];
        RNG.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Provide
    Arbitrary<String> plaintexts() {
        return Arbitraries.strings().ofMaxLength(300)
                .filter(s -> new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(s));
    }

    @Provide
    Arbitrary<Integer> keySizes() {
        return Arbitraries.of(16, 24, 32);
    }

    @Property(tries = 300)
    void decryptInvertsEncrypt(@ForAll("plaintexts") String plaintext, @ForAll("keySizes") int keyBytes) {
        AesGcmFieldCipher c = new AesGcmFieldCipher(randomKeyB64(keyBytes), randomKeyB64(32));
        String ct = c.encrypt(plaintext);
        assertThat(ct).startsWith("k1:");
        assertThat(c.decrypt(ct)).isEqualTo(plaintext);
    }

    @Property(tries = 200)
    void encryptionIsRandomisedAndHidesPlaintext(@ForAll("plaintexts") String plaintext) {
        AesGcmFieldCipher c = new AesGcmFieldCipher(randomKeyB64(32), randomKeyB64(32));
        String a = c.encrypt(plaintext);
        String b = c.encrypt(plaintext);
        assertThat(a).isNotEqualTo(b);
        if (plaintext.length() >= 8) assertThat(a).doesNotContain(plaintext);
    }

    @Property(tries = 200)
    void wrongKeyFailsToDecrypt(@ForAll("plaintexts") String plaintext) {
        AesGcmFieldCipher c1 = new AesGcmFieldCipher(randomKeyB64(32), randomKeyB64(32));
        AesGcmFieldCipher c2 = new AesGcmFieldCipher(randomKeyB64(32), randomKeyB64(32));
        String ct = c1.encrypt(plaintext);
        assertThatThrownBy(() -> c2.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Property(tries = 200)
    void tamperedCiphertextIsRejected(@ForAll("plaintexts") String plaintext, @ForAll int position) {
        AesGcmFieldCipher c = new AesGcmFieldCipher(randomKeyB64(32), randomKeyB64(32));
        String ct = c.encrypt(plaintext);
        int bodyStart = ct.indexOf(':') + 1;
        byte[] raw = Base64.getDecoder().decode(ct.substring(bodyStart));
        raw[Math.floorMod(position, raw.length)] ^= 0x01; // flip one bit of iv/ciphertext/tag
        String tampered = ct.substring(0, bodyStart) + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Property(tries = 100)
    void rotationKeepsOldCiphertextReadable(@ForAll("plaintexts") String plaintext) {
        String k1 = randomKeyB64(32);
        String k2 = randomKeyB64(32);
        String hmac = randomKeyB64(32);
        AesGcmFieldCipher oldCipher = new AesGcmFieldCipher(Map.of("k1", k1), "k1", hmac);
        Map<String, String> ring = new LinkedHashMap<>();
        ring.put("k1", k1);
        ring.put("k2", k2);
        AesGcmFieldCipher rotated = new AesGcmFieldCipher(ring, "k2", hmac);

        String legacy = oldCipher.encrypt(plaintext);
        assertThat(rotated.needsRotation(legacy)).isTrue();
        assertThat(rotated.decrypt(legacy)).isEqualTo(plaintext);
        String fresh = rotated.encrypt(plaintext);
        assertThat(rotated.needsRotation(fresh)).isFalse();
        assertThat(rotated.decrypt(fresh)).isEqualTo(plaintext);
    }

    @Property(tries = 200)
    void hmacIsDeterministicAndCaseInsensitive(@ForAll("plaintexts") String value) {
        AesGcmFieldCipher c = new AesGcmFieldCipher(randomKeyB64(32), randomKeyB64(32));
        assertThat(c.hmac(value)).isEqualTo(c.hmac(value));
        assertThat(c.hmac(value.toUpperCase())).isEqualTo(c.hmac(value.toUpperCase()));
        assertThat(c.hmac(value)).hasSize(64);
    }
}
