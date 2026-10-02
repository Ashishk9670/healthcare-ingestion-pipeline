package com.example.hdip.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** JPA converter: transparently AES-GCM encrypts annotated String columns. */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    private final AesGcmFieldCipher cipher;

    public EncryptedStringConverter(AesGcmFieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
