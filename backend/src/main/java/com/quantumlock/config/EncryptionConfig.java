package com.quantumlock.config;

import java.util.Base64;

import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the private-key encryptor from MASTER_KEY: 32 random bytes, Base64 encoded.
 * There is no default. Without a valid MASTER_KEY the application refuses to start.
 */
@Configuration
public class EncryptionConfig {

    @Bean
    public PrivateKeyEncryptor privateKeyEncryptor(@Value("${quantumlock.master-key}") String masterKey) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(masterKey.trim());
        } catch (IllegalArgumentException e) {
            // The message deliberately does not include the value.
            throw new IllegalStateException("MASTER_KEY is not valid Base64. See backend/.env.example.");
        }
        if (key.length != PrivateKeyEncryptor.MASTER_KEY_BYTES) {
            PrivateKeyEncryptor.wipe(key);
            throw new IllegalStateException("MASTER_KEY must decode to exactly 32 bytes. See backend/.env.example.");
        }
        try {
            return new PrivateKeyEncryptor(key);
        } finally {
            PrivateKeyEncryptor.wipe(key);
        }
    }
}
