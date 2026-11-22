package com.quantumlock.account;

import java.util.UUID;

/**
 * Tells the API which account the current request acts for. Every key operation is scoped to it.
 * It comes from the caller's API key (task 3.3), never from anything else the client sends.
 */
public interface CurrentAccount {

    UUID accountId();

    /** The API key used for this request, recorded in the audit log. */
    UUID apiKeyId();
}
