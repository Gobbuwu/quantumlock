package com.quantumlock.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The builder refuses unsafe or broken settings, and never repeats a refused value. */
class ClientSettingsTest {

    static final String API_KEY = QuantumLockClientTest.API_KEY;

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "HTTP://LOCALHOST:8080/",
            "https://quantumlock.example.com",
            "https://quantumlock.example.com:8443/base/path/",
            "  https://quantumlock.example.com  ",
    })
    void acceptsLocalHttpAndAnyHttps(String url) {
        try (QuantumLockClient client = QuantumLockClient.builder().apiUrl(url).apiKey(API_KEY).build()) {
            assertThat(client.toString()).doesNotEndWith("/]");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://quantumlock.example.com",
            "http://192.168.1.20:8080",
            "http://10.0.0.5",
            "http://127.0.0.1.example.com",
            "http://localhost.example.com",
    })
    void refusesPlainHttpToAnotherComputer(String url) {
        assertThatThrownBy(() -> QuantumLockClient.builder().apiUrl(url).apiKey(API_KEY).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "quantumlock.example.com",
            "ftp://quantumlock.example.com",
            "file:///etc/passwd",
            "https://",
            "https://user:password@quantumlock.example.com",
            "https://quantumlock.example.com/?apiKey=x",
            "https://quantumlock.example.com/#x",
            "https://quantum lock.example.com",
    })
    void refusesBrokenUrlsWithoutRepeatingThem(String url) {
        IllegalArgumentException e = catchThrowableOfType(IllegalArgumentException.class,
                () -> QuantumLockClient.builder().apiUrl(url).apiKey(API_KEY).build());
        assertThat(e.getMessage()).contains("QuantumLock API URL");
        if (url != null && !url.isBlank()) {
            assertThat(e.getMessage()).doesNotContain(url.strip());
        }
        assertThat(e.getMessage()).doesNotContain("password");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "ql_test_tooShort",
            "ql_prod_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ql_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA*",
            "ql_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ql_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\r\nX-Injected: 1",
    })
    void refusesAMalformedApiKeyWithoutRepeatingIt(String key) {
        IllegalArgumentException e = catchThrowableOfType(IllegalArgumentException.class,
                () -> QuantumLockClient.builder().apiUrl("http://127.0.0.1:8080").apiKey(key).build());
        assertThat(e.getMessage()).contains("QuantumLock API key");
        if (key != null && !key.isBlank()) {
            assertThat(e.getMessage()).doesNotContain(key.strip());
        }
    }

    @Test
    void acceptsTestAndLiveKeysAndTrimsSpaces() {
        for (String key : new String[] {API_KEY, "ql_live_" + "a-b_".repeat(10) + "xyz", " " + API_KEY + "\n"}) {
            QuantumLockClient.builder().apiUrl("http://127.0.0.1:8080").apiKey(key).build().close();
        }
    }

    @Test
    void refusesZeroOrNegativeTimeouts() {
        for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> QuantumLockClient.builder().apiUrl("http://127.0.0.1:8080").apiKey(API_KEY)
                    .connectTimeout(bad).build()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuantumLockClient.builder().apiUrl("http://127.0.0.1:8080").apiKey(API_KEY)
                    .requestTimeout(bad).build()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
