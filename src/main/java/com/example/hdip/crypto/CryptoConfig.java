package com.example.hdip.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the field cipher from environment-supplied keys. There are NO built-in default keys: the application
 * refuses to start unless keys are provided (env / secret manager / mounted secret).
 *
 * <ul>
 *   <li>{@code PHI_ENCRYPTION_KEYS}  ring of {@code id:base64key,id2:base64key} (rotation: add a new id, switch the active id)</li>
 *   <li>{@code PHI_ACTIVE_KEY_ID}    id used for new writes (defaults to the last key in the ring)</li>
 *   <li>{@code PHI_ENCRYPTION_KEY}   legacy single-key form, treated as id {@value AesGcmFieldCipher#LEGACY_KEY_ID}</li>
 *   <li>{@code PHI_HMAC_KEY}         base64 key for MRN lookup hashes</li>
 * </ul>
 */
@Configuration
public class CryptoConfig {
    @Bean
    public AesGcmFieldCipher aesGcmFieldCipher(@Value("${app.crypto.encryption-keys:}") String keyRing,
                                               @Value("${app.crypto.encryption-key:}") String singleKey,
                                               @Value("${app.crypto.active-key-id:}") String activeKeyId,
                                               @Value("${app.crypto.hmac-key:}") String hmacKey) {
        Map<String, String> keys = new LinkedHashMap<>(AesGcmFieldCipher.parseKeyRing(keyRing));
        if (!singleKey.isBlank()) keys.putIfAbsent(AesGcmFieldCipher.LEGACY_KEY_ID, singleKey.trim());
        if (keys.isEmpty()) {
            throw new IllegalStateException("No PHI encryption key configured: set PHI_ENCRYPTION_KEYS (see README, 'Secrets and keys')");
        }
        if (hmacKey.isBlank()) throw new IllegalStateException("No PHI HMAC key configured: set PHI_HMAC_KEY");
        String active = activeKeyId.isBlank() ? keys.keySet().stream().reduce((a, b) -> b).orElseThrow() : activeKeyId.trim();
        return new AesGcmFieldCipher(keys, active, hmacKey);
    }
}
