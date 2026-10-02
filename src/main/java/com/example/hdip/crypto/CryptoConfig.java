package com.example.hdip.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {
    @Bean
    public AesGcmFieldCipher aesGcmFieldCipher(@Value("${app.crypto.encryption-key}") String key,
                                               @Value("${app.crypto.hmac-key}") String hmacKey) {
        return new AesGcmFieldCipher(key, hmacKey);
    }
}
