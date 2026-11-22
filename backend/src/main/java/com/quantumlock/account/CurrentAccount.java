package com.quantumlock.account;

import java.util.UUID;

/**
 * Tells the API which account the current request acts for. Every key operation is scoped to it.
 *
 * <p>Until API-key login exists (task 3.3) this is always the demo account from database/seed.sql
 * ({@link DevCurrentAccount}). Task 3.3 replaces that with the account of the caller's API key.
 */
public interface CurrentAccount {

    UUID accountId();
}
