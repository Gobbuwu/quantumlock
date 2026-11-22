package com.quantumlock.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API-key authentication (task 3.3) through the whole application: every way of failing gets the same 401,
 * a valid key acts for its own team only, keys are stored as hashes and revocation works at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyAuthTest {

    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");

    private static final String UNAUTHORIZED = "{\"error\":\"unauthorized\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ApiKeyRepository apiKeyRows;

    @Test
    void everyProtectedEndpointRefusesARequestWithoutKey() throws Exception {
        MockHttpServletRequestBuilder[] requests = {
            get("/api/v1/keys"),
            get("/api/v1/keys/" + UUID.randomUUID()),
            post("/api/v1/keys/generate").contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"ML-KEM-1024\"}"),
            get("/api/v1/api-keys"),
            post("/api/v1/api-keys"),
            delete("/api/v1/api-keys/" + UUID.randomUUID()),
            get("/api/v1/does-not-exist"),
            get("/api/v1/health/"),
            get("/api/v1/health;x=y"),
            get("/API/v1/keys"),
        };
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized()).andExpect(content().string(UNAUTHORIZED));
        }
    }

    @Test
    void everyKindOfBadKeyGetsTheSameAnswer() throws Exception {
        String valid = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        String[] badKeys = {
            "",
            " ",
            "not-a-key",
            valid + "x",
            valid.substring(0, valid.length() - 1),
            " " + valid,
            valid.toUpperCase(),
            valid.replace("ql_test_", "ql_live_"),
            ApiKeys.newKey("test"), // well formed but never issued
            ApiKeys.hash(valid), // the stored hash is not a key
        };
        for (String key : badKeys) {
            mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, key))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(UNAUTHORIZED));
        }
    }

    @Test
    void revokedKeyIsRefused() throws Exception {
        IssuedApiKey issued = apiKeys.issue(DEMO_ACCOUNT, "test");
        mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, issued.key())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/api-keys/" + issued.entity().getApiKeyId())
                        .header(ApiKeys.HEADER, issued.key()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, issued.key()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(UNAUTHORIZED));
    }

    @Test
    void keyInTheUrlIsIgnored() throws Exception {
        String key = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        for (String name : new String[] {"apiKey", "api_key", "X-API-Key", "key"}) {
            mockMvc.perform(get("/api/v1/keys").param(name, key))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(UNAUTHORIZED));
        }
    }

    @Test
    void twoKeyHeadersAreRefusedEvenIfBothAreValid() throws Exception {
        String key = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, key, key))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(UNAUTHORIZED));
    }

    @Test
    void healthAndDocsStayPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void openApiDocumentDescribesTheKeyHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"));
    }

    @Test
    void aKeyActsForItsOwnTeamOnly() throws Exception {
        String demoKey = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        String otherKey = apiKeys.issue(OTHER_ACCOUNT, "test").key();

        String created = mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, otherKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"ML-DSA-65\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherKeyPairId = JsonPath.read(created, "$.keyPairId");

        mockMvc.perform(get("/api/v1/keys/" + otherKeyPairId).header(ApiKeys.HEADER, otherKey))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/keys/" + otherKeyPairId).header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("key_not_found"));
        String demoList = mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, demoKey))
                .andReturn().getResponse().getContentAsString();
        assertFalse(demoList.contains(otherKeyPairId));
    }

    @Test
    void onlyTheHashIsStored() {
        IssuedApiKey issued = apiKeys.issue(DEMO_ACCOUNT, "test");

        ApiKeyEntity row = apiKeyRows.findById(issued.entity().getApiKeyId()).orElseThrow();
        assertEquals(ApiKeys.hash(issued.key()), row.getKeyHash());
        assertTrue(row.getKeyHash().matches("[0-9a-f]{64}"));
        assertFalse(row.getKeyHash().contains(issued.key().substring(8)));
        assertFalse(issued.toString().contains(issued.key()), "toString must not reveal the key");
    }

    @Test
    void createdKeyIsShownOnceAndWorks() throws Exception {
        String key = apiKeys.issue(DEMO_ACCOUNT, "test").key();

        String body = mockMvc.perform(post("/api/v1/api-keys").header(ApiKeys.HEADER, key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String newKey = JsonPath.read(body, "$.apiKey");
        String newId = JsonPath.read(body, "$.apiKeyId");
        assertTrue(ApiKeys.hasValidFormat(newKey));

        mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, newKey)).andExpect(status().isOk());

        String list = mockMvc.perform(get("/api/v1/api-keys").header(ApiKeys.HEADER, newKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(newKey), "a key is never shown again");
        assertFalse(list.contains(ApiKeys.hash(newKey)), "the hash is never shown");
        List<String> ids = JsonPath.read(list, "$[*].apiKeyId");
        assertTrue(ids.contains(newId));
        Map<String, Object> first = JsonPath.read(list, "$[0]");
        assertEquals(List.of("apiKeyId", "createdAt", "environment", "revoked"),
                first.keySet().stream().sorted().toList());
    }

    @Test
    void anotherTeamsKeyCannotBeListedOrRevoked() throws Exception {
        String demoKey = apiKeys.issue(DEMO_ACCOUNT, "test").key();
        IssuedApiKey other = apiKeys.issue(OTHER_ACCOUNT, "test");
        String otherId = other.entity().getApiKeyId().toString();

        String list = mockMvc.perform(get("/api/v1/api-keys").header(ApiKeys.HEADER, demoKey))
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(otherId));

        for (String id : new String[] {otherId, UUID.randomUUID().toString(), "not-a-uuid"}) {
            mockMvc.perform(delete("/api/v1/api-keys/" + id).header(ApiKeys.HEADER, demoKey))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("api_key_not_found"));
        }
        mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, other.key())).andExpect(status().isOk());
    }
}
