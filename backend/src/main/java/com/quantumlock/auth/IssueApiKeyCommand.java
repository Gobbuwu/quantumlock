package com.quantumlock.auth;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.UUID;

import com.quantumlock.account.AccountRepository;

/**
 * One-off command that issues the first API key for a team, before any key exists to call the API with.
 *
 * <pre>
 *   mvnw spring-boot:run -Dspring-boot.run.arguments=issue-api-key
 *   mvnw spring-boot:run -Dspring-boot.run.arguments="issue-api-key,&lt;account id&gt;"
 * </pre>
 *
 * <p>Without an account id it uses the demo team from database/seed.sql. The key is printed once to the
 * console, not through the logger, so it does not end up in log files. Only its hash is stored.
 */
public final class IssueApiKeyCommand {

    public static final String NAME = "issue-api-key";

    /** The demo team in database/seed.sql. */
    static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");

    private IssueApiKeyCommand() {
    }

    /**
     * True when the program was started to run this command instead of the web server. Spring options such as
     * {@code --spring.profiles.active=...} (which the Maven plugin may put first) are skipped.
     */
    public static boolean isRequested(String[] args) {
        String[] plain = plainArguments(args);
        return plain.length > 0 && NAME.equals(plain[0]);
    }

    /** The Spring options ({@code --name=value}), to start the application context with. */
    public static String[] springOptions(String[] args) {
        return Arrays.stream(args).filter(a -> a.startsWith("--")).toArray(String[]::new);
    }

    private static String[] plainArguments(String[] allArgs) {
        return Arrays.stream(allArgs).filter(a -> !a.startsWith("--")).toArray(String[]::new);
    }

    /** Runs the command and returns the process exit code: 0 on success, 2 for a bad account id. */
    public static int run(String[] allArgs, ApiKeyService apiKeys, AccountRepository accounts, PrintStream out,
            PrintStream err) {
        String[] args = plainArguments(allArgs);
        if (args.length > 2) {
            err.println("Usage: " + NAME + " [account id]");
            return 2;
        }
        UUID accountId;
        try {
            accountId = args.length == 2 ? UUID.fromString(args[1]) : DEMO_ACCOUNT;
        } catch (IllegalArgumentException e) {
            err.println("Not an account id. Usage: " + NAME + " [account id]");
            return 2;
        }
        if (!accounts.existsById(accountId)) {
            err.println("No such account. Has database/seed.sql been run?");
            return 2;
        }
        IssuedApiKey issued = apiKeys.issue(accountId, "test");
        out.println();
        out.println("New API key for account " + accountId + " (key id " + issued.entity().getApiKeyId() + "):");
        out.println();
        out.println("    " + issued.key());
        out.println();
        out.println("It is shown only this once. Store it somewhere safe and send it in the X-API-Key header.");
        out.println("Never commit it to git. Revoke it with DELETE /api/v1/api-keys/{key id}.");
        out.println();
        return 0;
    }
}
