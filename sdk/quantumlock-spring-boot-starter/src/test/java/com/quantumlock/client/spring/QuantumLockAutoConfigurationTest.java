package com.quantumlock.client.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.quantumlock.client.QuantumLockClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class QuantumLockAutoConfigurationTest {

    static final String API_KEY = "ql_test_" + "B".repeat(43);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuantumLockAutoConfiguration.class));

    @Test
    void twoSettingsGiveAReadyClient() {
        context.withPropertyValues("quantumlock.api-url=http://127.0.0.1:8080", "quantumlock.api-key=" + API_KEY)
                .run(started -> {
                    assertThat(started).hasNotFailed().hasSingleBean(QuantumLockClient.class);
                    assertThat(started.getBean(QuantumLockClient.class).toString())
                            .isEqualTo("QuantumLockClient[apiUrl=http://127.0.0.1:8080, apiKey=hidden]");
                });
    }

    @Test
    void timeoutsCanBeSet() {
        context.withPropertyValues("quantumlock.api-url=https://quantumlock.example.com",
                "quantumlock.api-key=" + API_KEY, "quantumlock.connect-timeout=2s", "quantumlock.request-timeout=1m")
                .run(started -> {
                    QuantumLockProperties properties = started.getBean(QuantumLockProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.toString()).doesNotContain(API_KEY).contains("apiKey=hidden");
                });
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        context.withPropertyValues("quantumlock.api-url=http://127.0.0.1:8080").run(started -> {
            assertThat(started).hasFailed();
            assertThat(started.getStartupFailure()).rootCause().hasMessageContaining("API key is required")
                    .hasMessageContaining("quantumlock.api-key");
        });
    }

    @Test
    void refusesToStartWithoutAnApiUrl() {
        context.withPropertyValues("quantumlock.api-key=" + API_KEY).run(started -> {
            assertThat(started).hasFailed();
            assertThat(started.getStartupFailure()).rootCause().hasMessageContaining("API URL is required");
        });
    }

    @Test
    void aWrongKeyIsRefusedWithoutBeingShown() {
        String typo = API_KEY.substring(1);
        context.withPropertyValues("quantumlock.api-url=http://127.0.0.1:8080", "quantumlock.api-key=" + typo)
                .run(started -> {
                    assertThat(started).hasFailed();
                    Throwable failure = started.getStartupFailure();
                    while (failure != null) {
                        assertThat(String.valueOf(failure.getMessage())).doesNotContain(typo);
                        failure = failure.getCause();
                    }
                });
    }

    @Test
    void refusesPlainHttpToAnotherComputer() {
        context.withPropertyValues("quantumlock.api-url=http://quantumlock.example.com", "quantumlock.api-key=" + API_KEY)
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void yourOwnClientWins() {
        context.withUserConfiguration(OwnClient.class)
                .run(started -> assertThat(started).hasSingleBean(QuantumLockClient.class)
                        .getBean(QuantumLockClient.class).isSameAs(OwnClient.CLIENT));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnClient {
        static final QuantumLockClient CLIENT = QuantumLockClient.builder()
                .apiUrl("https://own.example.com").apiKey(API_KEY).build();

        @Bean
        QuantumLockClient ownClient() {
            return CLIENT;
        }
    }
}
