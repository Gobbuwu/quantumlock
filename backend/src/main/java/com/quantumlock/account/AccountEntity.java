package com.quantumlock.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the accounts table (database/schema.sql). Read-only for now. */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "team_name", nullable = false)
    private String teamName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "plan_tier", nullable = false)
    private String planTier;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
        // for JPA
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getTeamName() {
        return teamName;
    }
}
