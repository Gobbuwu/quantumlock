package com.quantumlock;

import com.quantumlock.account.AccountRepository;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.IssueApiKeyCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the QuantumLock REST API.
 *
 * <p>Started with the argument {@code issue-api-key} it does not start the web server: it issues one
 * API key, prints it and exits (see {@link IssueApiKeyCommand}).
 */
@SpringBootApplication
public class QuantumLockApplication {

    public static void main(String[] args) {
        if (IssueApiKeyCommand.isMistyped(args)) {
            System.err.println(IssueApiKeyCommand.USAGE);
            System.exit(2);
        }
        if (IssueApiKeyCommand.isRequested(args)) {
            int exitCode;
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QuantumLockApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(IssueApiKeyCommand.springOptions(args))) {
                exitCode = IssueApiKeyCommand.run(args, context.getBean(ApiKeyService.class),
                        context.getBean(AccountRepository.class), System.out, System.err);
            }
            System.exit(exitCode);
        }
        SpringApplication.run(QuantumLockApplication.class, args);
    }
}
