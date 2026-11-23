package com.quantumlock.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.IssuedApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The audit log API (KAN-32): order, every field, keyset paging, filters, limits, team isolation and bad input.
 * Each test uses a team of its own, so rows written by other tests never show up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogApiTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID team;
    private String key;
    private UUID keyId;

    @BeforeEach
    void newTeam() {
        team = newAccount();
        IssuedApiKey issued = apiKeys.issue(team, "test");
        key = issued.key();
        keyId = issued.entity().getApiKeyId();
    }

    // ---- Content and order ------------------------------------------------------------------------

    @Test
    void realOperationsShowUpNewestFirstWithEveryField() throws Exception {
        String keyPairId = generate("ML-KEM-1024");
        mockMvc.perform(withKey(post("/api/v1/keys/" + keyPairId + "/encapsulate"))).andExpect(status().isOk());

        String body = mockMvc.perform(withKey(get("/api/v1/audit-logs")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].action").value("encapsulate"))
                .andExpect(jsonPath("$.entries[1].action").value("generate"))
                .andExpect(jsonPath("$.entries[0].status").value("success"))
                .andExpect(jsonPath("$.entries[0].keyPairId").value(keyPairId))
                .andExpect(jsonPath("$.entries[0].apiKeyId").value(keyId.toString()))
                .andExpect(jsonPath("$.nextBefore").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> entry = JsonPath.read(body, "$.entries[0]");
        assertThat(entry).containsOnlyKeys("id", "time", "action", "status", "keyPairId", "apiKeyId");
        assertThat(Instant.parse((String) entry.get("time"))).isBefore(Instant.now().plusSeconds(1));
        Map<String, Object> page = JsonPath.read(body, "$");
        assertThat(page).containsOnlyKeys("entries", "nextBefore");
    }

    @Test
    void aRefusalWithoutAKeyPairShowsANullKeyPair() throws Exception {
        mockMvc.perform(withKey(post("/api/v1/keys/" + UUID.randomUUID() + "/encapsulate")))
                .andExpect(status().isNotFound());

        mockMvc.perform(withKey(get("/api/v1/audit-logs")))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].action").value("encapsulate"))
                .andExpect(jsonPath("$.entries[0].status").value("failure"))
                .andExpect(jsonPath("$.entries[0].keyPairId").value((Object) null));
    }

    @Test
    void readingTheLogWritesNothingToIt() throws Exception {
        insert(team, null, "generate", "success", T0);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(withKey(get("/api/v1/audit-logs"))).andExpect(status().isOk());
        }
        assertEquals(1, rowsOf(team));
    }

    @Test
    void theLogIsReadOnly() throws Exception {
        mockMvc.perform(withKey(post("/api/v1/audit-logs"))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(withKey(delete("/api/v1/audit-logs"))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anApiKeyIsRequired() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"unauthorized\"}", true));
    }

    // ---- Paging -----------------------------------------------------------------------------------

    @Test
    void pagesCoverEveryRowOnceEvenWhenTimesAreEqual() throws Exception {
        List<Long> expected = new ArrayList<>();
        // Rows inserted later get larger ids. Seven share one time, so the id decides their order.
        for (int i = 0; i < 4; i++) {
            insert(team, null, "sign", "success", T0.plusSeconds(i));
        }
        for (int i = 0; i < 7; i++) {
            insert(team, null, "verify", "success", T0.plusSeconds(10));
        }
        insert(team, null, "generate", "success", T0.plusSeconds(20));
        expected.addAll(idsNewestFirst(team));
        assertEquals(12, expected.size());

        for (int limit : new int[] {1, 3, 5, 7, 12, 100}) {
            assertEquals(expected, readAll(Map.of(), limit), "limit " + limit);
        }
    }

    @Test
    void rowsWrittenWhilePagingNeitherRepeatNorSkipOlderRows() throws Exception {
        for (int i = 0; i < 6; i++) {
            insert(team, null, "sign", "success", T0.plusSeconds(i));
        }
        List<Long> before = idsNewestFirst(team);

        String first = page("limit=2");
        String cursor = JsonPath.read(first, "$.nextBefore");
        // New activity arrives between the two requests.
        insert(team, null, "verify", "success", T0.plusSeconds(100));
        insert(team, null, "verify", "success", T0.plusSeconds(100));

        List<Long> seen = new ArrayList<>(ids(first));
        String next = cursor;
        while (next != null) {
            String body = page("limit=2&before=" + next);
            seen.addAll(ids(body));
            next = JsonPath.read(body, "$.nextBefore");
        }
        assertEquals(before, seen);
    }

    @Test
    void theLastPageHasNoCursorAndAnEmptyLogIsAnEmptyPage() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/audit-logs")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"entries\":[],\"nextBefore\":null}", true));

        insert(team, null, "sign", "success", T0);
        insert(team, null, "sign", "success", T0.plusSeconds(1));
        String body = page("limit=2");
        assertThat(ids(body)).hasSize(2);
        assertThat((Object) JsonPath.read(body, "$.nextBefore")).isNull();
    }

    @Test
    void theDefaultLimitIs50AndTheMaximumIs100() throws Exception {
        for (int i = 0; i < 120; i++) {
            insert(team, null, "sign", "success", T0.plusSeconds(i));
        }
        assertThat(ids(page(""))).hasSize(50);
        assertThat(ids(page("limit=100"))).hasSize(100);
        assertThat(ids(page("limit=1"))).hasSize(1);
        invalid("limit=101");
    }

    // ---- Filters ----------------------------------------------------------------------------------

    @Test
    void filtersNarrowTheRowsAndCombine() throws Exception {
        String a = generate("ML-DSA-65");
        String b = generate("ML-DSA-65");
        UUID idA = UUID.fromString(a);
        insert(team, idA, "sign", "success", T0);
        insert(team, idA, "sign", "failure", T0.plusSeconds(1));
        insert(team, idA, "verify", "success", T0.plusSeconds(2));
        insert(team, UUID.fromString(b), "sign", "success", T0.plusSeconds(3));

        assertThat(actions("keyPairId=" + a)).containsExactly("generate", "verify", "sign", "sign");
        assertThat(actions("action=sign")).containsExactly("sign", "sign", "sign");
        assertThat(actions("status=failure")).containsExactly("sign");
        assertThat(actions("keyPairId=" + a + "&action=sign&status=success")).containsExactly("sign");
        assertThat(actions("action=revoke")).isEmpty();
        assertThat(readAll(Map.of("action", "sign"), 1)).hasSize(3);
    }

    // ---- Teams ------------------------------------------------------------------------------------

    @Test
    void anotherTeamsRowsKeyPairsAndCursorsGiveNothingAway() throws Exception {
        UUID other = newAccount();
        IssuedApiKey otherKey = apiKeys.issue(other, "test");
        String otherKeyPair = JsonPath.read(mockMvc.perform(post("/api/v1/keys/generate")
                .header(ApiKeys.HEADER, otherKey.key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"algorithm\":\"ML-KEM-1024\"}")).andReturn().getResponse().getContentAsString(),
                "$.keyPairId");
        insert(other, null, "sign", "success", T0);
        long otherRow = idsNewestFirst(other).get(0);
        insert(team, null, "verify", "success", T0);

        assertThat(actions("")).containsExactly("verify");
        // Another team's key pair looks like a key pair with no rows, the same as an unknown one.
        assertThat(actions("keyPairId=" + otherKeyPair)).isEmpty();
        assertThat(actions("keyPairId=" + UUID.randomUUID())).isEmpty();
        // Another team's cursor is refused exactly like a cursor that does not exist.
        invalid("before=" + otherRow);
        invalid("before=999999999999");
    }

    // ---- Bad input --------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "keyPairId=not-a-uuid",
            "keyPairId=",
            "keyPairId=5F1C3A9E-7B2D-4E8F-9A6C-1D0E2B3C4A51",
            "keyPairId=5f1c3a9e7b2d4e8f9a6c1d0e2b3c4a51",
            "action=GENERATE",
            "action=delete",
            "action=",
            "status=ok",
            "status=Success",
            "limit=0",
            "limit=-1",
            "limit=1.5",
            "limit=abc",
            "limit=050",
            "limit=",
            "limit=99999999999999999999",
            "limit=1&limit=2",
            "before=0",
            "before=-5",
            "before=abc",
            "before=01",
            "before=",
            "before=99999999999999999999",
    })
    void badParametersGetOneFixedAnswer(String query) throws Exception {
        invalid(query);
    }

    @Test
    void theErrorRepeatsNothingFromTheRequest() throws Exception {
        String body = mockMvc.perform(withKey(get("/api/v1/audit-logs?action=<script>x</script>")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("{\"error\":\"invalid_request\"}", body);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeys.HEADER, key);
    }

    private UUID newAccount() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (account_id, team_name, contact_email) VALUES (?, ?, ?)",
                id, "Audit Test Team", "audit-test@example.com");
        return id;
    }

    private String generate(String algorithm) throws Exception {
        String body = mockMvc.perform(withKey(post("/api/v1/keys/generate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"" + algorithm + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.keyPairId");
    }

    private void insert(UUID account, UUID keyPair, String action, String status, Instant time) {
        jdbc.update("INSERT INTO audit_logs (account_id, api_key_id, key_pair_id, action, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", account, null, keyPair, action, status, Timestamp.from(time));
    }

    private int rowsOf(UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE account_id = ?", Integer.class, account);
    }

    /** What the API must return: every row of the team, newest first, ties broken by the larger id. */
    private List<Long> idsNewestFirst(UUID account) {
        return jdbc.queryForList("SELECT log_id FROM audit_logs WHERE account_id = ? "
                + "ORDER BY created_at DESC, log_id DESC", Long.class, account);
    }

    private String page(String query) throws Exception {
        return mockMvc.perform(withKey(get("/api/v1/audit-logs" + (query.isEmpty() ? "" : "?" + query))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Long> readAll(Map<String, String> filters, int limit) throws Exception {
        StringBuilder base = new StringBuilder("limit=" + limit);
        filters.forEach((name, value) -> base.append('&').append(name).append('=').append(value));
        List<Long> all = new ArrayList<>();
        String next = null;
        do {
            String body = page(base + (next == null ? "" : "&before=" + next));
            List<Long> ids = ids(body);
            assertThat(ids.size()).isLessThanOrEqualTo(limit);
            all.addAll(ids);
            next = JsonPath.read(body, "$.nextBefore");
        } while (next != null);
        return all;
    }

    private static List<Long> ids(String body) {
        List<String> ids = JsonPath.read(body, "$.entries[*].id");
        return ids.stream().map(Long::valueOf).toList();
    }

    private List<String> actions(String query) throws Exception {
        return JsonPath.read(page(query), "$.entries[*].action");
    }

    private void invalid(String query) throws Exception {
        mockMvc.perform(withKey(get("/api/v1/audit-logs?" + query)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_request\"}", true));
    }
}
