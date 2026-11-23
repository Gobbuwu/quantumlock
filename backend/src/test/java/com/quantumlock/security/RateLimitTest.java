package com.quantumlock.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The rate limits through the real filter chain (KAN-31), with small limits: 5 requests per API key and 3 failed
 * key checks per address in a minute. Each test uses its own client address and API key, so they do not interfere.
 * Window ends and Retry-After values are tested with a test clock in {@link RateLimiterTest}.
 */
@SpringBootTest(properties = {
    "quantumlock.rate-limit.requests-per-minute=5",
    "quantumlock.rate-limit.failed-checks-per-minute=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final String WRONG_KEY = "ql_test_" + "W".repeat(43);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aKeyGetsItsLimitThenATooManyRequestsAnswer() throws Exception {
        String key = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(from("10.0.1.1", get("/api/v1/keys")).header(ApiKeys.HEADER, key))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(from("10.0.1.1", get("/api/v1/keys")).header(ApiKeys.HEADER, key))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"rate_limited\"}", true))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void aRefusedRequestDoesNoWork() throws Exception {
        String key = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        int before = keyPairCount();
        int auditBefore = auditCount();
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(from("10.0.2.1", post("/api/v1/keys/generate")).header(ApiKeys.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"ML-KEM-1024\"}"));
        }
        assertEquals(before + 5, keyPairCount(), "only the 5 allowed requests generated a key pair");
        assertEquals(auditBefore + 5, auditCount(), "only the 5 allowed requests were audited");
    }

    @Test
    void everyKeyHasItsOwnLimit() throws Exception {
        String first = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        String second = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(from("10.0.3.1", get("/api/v1/keys")).header(ApiKeys.HEADER, first));
        }
        mockMvc.perform(from("10.0.3.1", get("/api/v1/keys")).header(ApiKeys.HEADER, first))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(from("10.0.3.1", get("/api/v1/keys")).header(ApiKeys.HEADER, second))
                .andExpect(status().isOk());
    }

    @Test
    void tooManyFailedChecksBlockTheAddressEvenForAValidKey() throws Exception {
        String valid = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from("10.0.4.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY))
                    .andExpect(status().isUnauthorized());
        }
        // Guessing now gets no answer at all, whatever is sent: wrong key, no key, a valid key.
        for (String key : new String[] {WRONG_KEY, null, valid}) {
            MockHttpServletRequestBuilder request = from("10.0.4.1", get("/api/v1/keys"));
            if (key != null) {
                request.header(ApiKeys.HEADER, key);
            }
            mockMvc.perform(request).andExpect(status().isTooManyRequests())
                    .andExpect(content().json("{\"error\":\"rate_limited\"}", true));
        }
        // Another address is not affected.
        mockMvc.perform(from("10.0.4.2", get("/api/v1/keys")).header(ApiKeys.HEADER, valid))
                .andExpect(status().isOk());
    }

    @Test
    void successfulChecksDoNotCountAsFailures() throws Exception {
        String valid = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        String other = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(from("10.0.5.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY));
        }
        for (String key : new String[] {valid, valid, valid, other, other}) {
            mockMvc.perform(from("10.0.5.1", get("/api/v1/keys")).header(ApiKeys.HEADER, key))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(from("10.0.5.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from("10.0.5.1", get("/api/v1/keys")).header(ApiKeys.HEADER, valid))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forwardingHeadersDoNotChangeTheAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from("10.0.6.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY)
                    .header("X-Forwarded-For", "203.0.113." + i).header("X-Real-IP", "203.0.113." + i));
        }
        mockMvc.perform(from("10.0.6.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY)
                        .header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theHealthCheckIsNeverLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from("10.0.7.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY));
        }
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(from("10.0.7.1", get("/api/v1/health"))).andExpect(status().isOk());
        }
    }

    @Test
    void theAnswerRepeatsNothingFromTheRequest() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from("10.0.8.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY));
        }
        String body = mockMvc.perform(from("10.0.8.1", get("/api/v1/keys")).header(ApiKeys.HEADER, WRONG_KEY))
                .andReturn().getResponse().getContentAsString();
        assertTrue(!body.contains("10.0.8.1") && !body.contains(WRONG_KEY), body);
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        RequestPostProcessor remote = r -> {
            r.setRemoteAddr(address);
            return r;
        };
        return request.with(remote);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
    }

    private int keyPairCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM key_pairs", Integer.class);
    }
}
