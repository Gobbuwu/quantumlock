package com.quantumlock.client.spring;

import java.time.Duration;

import com.quantumlock.client.QuantumLockClient;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code quantumlock.*}. Only {@code api-url} and {@code api-key} are required.
 * Keep the API key out of files in git: set it with the QUANTUMLOCK_API_KEY environment variable,
 * for example {@code quantumlock.api-key: ${QUANTUMLOCK_API_KEY}}.
 */
@ConfigurationProperties("quantumlock")
public class QuantumLockProperties {

    /** Base URL of the QuantumLock API, for example https://quantumlock.example.com. */
    private String apiUrl;
    /** The API key, ql_test_... or ql_live_... . */
    private String apiKey;
    /** How long to wait for a connection to open. */
    private Duration connectTimeout = QuantumLockClient.DEFAULT_CONNECT_TIMEOUT;
    /** How long a whole call may take. */
    private Duration requestTimeout = QuantumLockClient.DEFAULT_REQUEST_TIMEOUT;

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String toString() {
        return "QuantumLockProperties[apiUrl=" + apiUrl + ", apiKey=hidden, connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }
}
