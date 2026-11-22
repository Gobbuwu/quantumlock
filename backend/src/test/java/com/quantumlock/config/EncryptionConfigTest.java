package com.quantumlock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.UUID;

import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The backend must refuse to start without a valid MASTER_KEY (SECURITY.md), and the refusal must never
 * print the value it was given (KAN-22, gap G1).
 */
class EncryptionConfigTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EncryptionConfig.class);

    @Test
    void refusesToStartWhenMasterKeyIsMissing() {
        context.run(started -> assertThat(started).hasFailed());
    }

    @Test
    void startsWithAValidMasterKey() {
        context.withPropertyValues("quantumlock.master-key=" + VALID_KEY)
                .run(started -> assertThat(started).hasNotFailed().hasSingleBean(PrivateKeyEncryptor.class));
    }

    @Test
    void aValidKeyEncryptsAndDecrypts() throws Exception {
        PrivateKeyEncryptor encryptor = new EncryptionConfig().privateKeyEncryptor(" " + VALID_KEY + "\n");
        UUID keyPair = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        byte[] secret = {1, 2, 3};
        assertThat(encryptor.decrypt(encryptor.encrypt(secret, keyPair, account), keyPair, account)).isEqualTo(secret);
    }

    @ParameterizedTest(name = "refuses \"{0}\"")
    @ValueSource(strings = {
            "",                                                // MASTER_KEY= left empty, as in .env.example
            "   ",
            "not*base64*at*all!",
            "c2hvcnQta2V5LTE2LWJ5dGU=",                         // 16 bytes
            "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB", // 36 bytes
    })
    void refusesAnInvalidMasterKeyWithoutShowingIt(String value) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new EncryptionConfig().privateKeyEncryptor(value));
        assertThat(e.getMessage()).contains("MASTER_KEY");
        if (!value.isBlank()) {
            assertFalse(e.getMessage().contains(value.trim()), "the error must not contain the key");
        }
        assertThat(e.getCause()).isNull();
    }
}
