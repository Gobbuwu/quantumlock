package com.quantumlock.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.quantumlock.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The one-off command that issues the first API key for the demo (task 3.3). */
@SpringBootTest
@ActiveProfiles("test")
class IssueApiKeyCommandTest {

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private AccountRepository accounts;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void recognisesOnlyItsOwnName() {
        assertTrue(IssueApiKeyCommand.isRequested(new String[] {"issue-api-key"}));
        assertFalse(IssueApiKeyCommand.isRequested(new String[] {}));
        assertTrue(IssueApiKeyCommand.isRequested(new String[] {"--spring.profiles.active=dev", "issue-api-key"}));
        assertFalse(IssueApiKeyCommand.isRequested(new String[] {"--server.port=9090"}));
        assertFalse(IssueApiKeyCommand.isRequested(new String[] {"something-else", "issue-api-key"}));
    }

    @Test
    void refusesNearMissesInsteadOfStartingTheServer() {
        // KAN-41: the Maven plugin splits on spaces, so the comma form arrives as one argument.
        String comma = "issue-api-key," + IssueApiKeyCommand.DEMO_ACCOUNT;
        assertTrue(IssueApiKeyCommand.isMistyped(new String[] {comma}));
        assertTrue(IssueApiKeyCommand.isMistyped(new String[] {"--spring.profiles.active=dev", comma}));
        assertTrue(IssueApiKeyCommand.isMistyped(new String[] {"Issue-API-Key"}));
        assertTrue(IssueApiKeyCommand.isMistyped(new String[] {"issue-api-keys"}));
        assertFalse(IssueApiKeyCommand.isRequested(new String[] {comma}));

        assertFalse(IssueApiKeyCommand.isMistyped(new String[] {"issue-api-key"}));
        assertFalse(IssueApiKeyCommand.isMistyped(new String[] {"issue-api-key", "x"}));
        assertFalse(IssueApiKeyCommand.isMistyped(new String[] {}));
        assertFalse(IssueApiKeyCommand.isMistyped(new String[] {"--server.port=9090"}));
        assertTrue(IssueApiKeyCommand.USAGE.contains("issue-api-key <account id>"), IssueApiKeyCommand.USAGE);
    }

    @Test
    void issuesAWorkingKeyForTheDemoTeamByDefault() {
        assertEquals(0, run("issue-api-key"));

        Matcher m = Pattern.compile("ql_test_[A-Za-z0-9_-]{43}").matcher(printed(out));
        assertTrue(m.find(), printed(out));
        assertEquals(IssueApiKeyCommand.DEMO_ACCOUNT, apiKeys.authenticate(m.group()).orElseThrow().accountId());
    }

    @Test
    void issuesForAGivenAccount() {
        UUID other = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");
        assertEquals(0, run("--spring.profiles.active=test", "issue-api-key", other.toString()));

        Matcher m = Pattern.compile("ql_test_[A-Za-z0-9_-]{43}").matcher(printed(out));
        assertTrue(m.find());
        assertEquals(other, apiKeys.authenticate(m.group()).orElseThrow().accountId());
    }

    @Test
    void refusesBadOrUnknownAccounts() {
        assertEquals(2, run("issue-api-key", "not-a-uuid"));
        assertEquals(2, run("issue-api-key", UUID.randomUUID().toString()));
        assertEquals(2, run("issue-api-key", "a", "b"));
        assertFalse(printed(out).contains("ql_"), "no key may be printed when the command fails");
    }

    private int run(String... args) {
        return IssueApiKeyCommand.run(args, apiKeys, accounts, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static String printed(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }
}
