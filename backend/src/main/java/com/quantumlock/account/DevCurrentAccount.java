package com.quantumlock.account;

import java.util.UUID;

import com.quantumlock.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Temporary stand-in for API-key login (task 3.3): every request acts for one configured account,
 * by default the demo team from database/seed.sql. Safe only because the server listens on
 * 127.0.0.1 and answers to localhost alone (see SECURITY.md). Remove it when task 3.3 is done.
 */
@Component
public class DevCurrentAccount implements CurrentAccount {

    private final UUID accountId;
    private final AccountRepository accounts;

    public DevCurrentAccount(@Value("${quantumlock.dev-account-id}") String accountId, AccountRepository accounts) {
        this.accountId = UUID.fromString(accountId);
        this.accounts = accounts;
    }

    @Override
    public UUID accountId() {
        if (!accounts.existsById(accountId)) {
            // The database exists but database/seed.sql has not been run on it.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "demo_account_missing");
        }
        return accountId;
    }
}
