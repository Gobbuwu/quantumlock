package com.quantumlock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Guards the secure defaults in application.yml, so a later edit cannot quietly weaken them.
 * It reads the file as text and needs no Spring context.
 */
class SecureDefaultsTest {

    @Test
    void serverListensOnThisComputerOnlyByDefault() throws IOException {
        assertTrue(applicationYml().contains("address: ${SERVER_ADDRESS:127.0.0.1}"),
                "server.address must default to 127.0.0.1");
    }

    @Test
    void databasePasswordHasNoDefault() throws IOException {
        String yml = applicationYml();

        assertTrue(yml.contains("password: ${DB_PASSWORD}"), "the password must come from DB_PASSWORD");
        assertFalse(yml.contains("${DB_PASSWORD:"), "DB_PASSWORD must not have a default value");
    }

    @Test
    void errorResponsesNeverIncludeInternalDetails() throws IOException {
        String yml = applicationYml();

        assertTrue(yml.contains("include-message: never"));
        assertTrue(yml.contains("include-binding-errors: never"));
        assertTrue(yml.contains("include-stacktrace: never"));
        assertTrue(yml.contains("include-exception: false"));
        assertTrue(yml.contains("include-path: never"));
    }

    @Test
    void onlyLocalHostNamesAreAllowedByDefault() throws IOException {
        assertTrue(applicationYml().contains("allowed-hosts: ${ALLOWED_HOSTS:localhost,127.0.0.1}"));
    }

    private static String applicationYml() throws IOException {
        try (InputStream in = SecureDefaultsTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
