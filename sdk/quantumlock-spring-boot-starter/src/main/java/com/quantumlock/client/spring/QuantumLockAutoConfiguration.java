package com.quantumlock.client.spring;

import com.quantumlock.client.QuantumLockClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Makes a {@link QuantumLockClient} from the {@code quantumlock.*} settings. The application refuses to
 * start when the API URL or API key is missing or invalid, so a bad setting is found at once and not at the
 * first call. Define your own QuantumLockClient bean to replace this one.
 */
@AutoConfiguration
@EnableConfigurationProperties(QuantumLockProperties.class)
public class QuantumLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QuantumLockClient quantumLockClient(QuantumLockProperties properties) {
        try {
            return QuantumLockClient.builder()
                    .apiUrl(properties.getApiUrl())
                    .apiKey(properties.getApiKey())
                    .connectTimeout(properties.getConnectTimeout())
                    .requestTimeout(properties.getRequestTimeout())
                    .build();
        } catch (IllegalArgumentException e) {
            // The client's messages never contain the value; this adds which settings to look at.
            throw new IllegalStateException(e.getMessage() + ". Check quantumlock.api-url and quantumlock.api-key.");
        }
    }
}
