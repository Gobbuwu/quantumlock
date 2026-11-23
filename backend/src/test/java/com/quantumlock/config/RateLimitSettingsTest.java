package com.quantumlock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quantumlock.security.RateLimiter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The two rate-limit settings (KAN-31) refuse values that would switch limiting off or break it. */
class RateLimitSettingsTest {

    private final WebSecurityConfig config = new WebSecurityConfig();

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, WebSecurityConfig.MAX_PER_MINUTE + 1, Integer.MAX_VALUE})
    void refusesAValueOutsideTheRange(int value) {
        IllegalStateException perKey = assertThrows(IllegalStateException.class,
                () -> config.requestsPerKeyLimiter(value));
        assertThat(perKey.getMessage()).isEqualTo("RATE_LIMIT_PER_MINUTE must be 1 to 1000000.");
        IllegalStateException perAddress = assertThrows(IllegalStateException.class,
                () -> config.failedChecksPerAddressLimiter(value));
        assertThat(perAddress.getMessage()).isEqualTo("RATE_LIMIT_FAILED_CHECKS_PER_MINUTE must be 1 to 1000000.");
    }

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(ints = {1, 20, 120, WebSecurityConfig.MAX_PER_MINUTE})
    void acceptsAValueInTheRange(int value) {
        RateLimiter limiter = config.requestsPerKeyLimiter(value);
        for (int i = 0; i < Math.min(value, 200); i++) {
            assertThat(limiter.acquire("key")).isZero();
        }
        assertThat(config.failedChecksPerAddressLimiter(value)).isNotNull();
    }
}
