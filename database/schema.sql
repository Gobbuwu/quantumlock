-- QuantumLock database schema (task 2.2, KAN-12).
-- Run once on a fresh, empty database (see database/README.md).
-- Works on PostgreSQL 16 and on the H2 database the backend tests use (PostgreSQL mode).
--
-- Follows the ER diagram in the Research and Requirements Document, with these changes:
-- * The ENUM columns are VARCHAR with a CHECK constraint, so the same script runs on PostgreSQL and H2.
-- * audit_logs.timestamp is called created_at, because TIMESTAMP is a reserved word in SQL.
-- * audit_logs.api_key_id may be empty: requests made before API-key login exists (task 3.3)
--   have no API key to record.
-- * audit_logs.log_id is an identity column, the standard form of BIGSERIAL.

CREATE TABLE accounts (
    account_id     UUID                     PRIMARY KEY,
    team_name      VARCHAR(100)             NOT NULL,
    contact_email  VARCHAR(254)             NOT NULL,
    plan_tier      VARCHAR(20)              NOT NULL DEFAULT 'free',
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- An API key is shown to its owner once. Only its SHA-256 hash (64 hex characters) is stored.
CREATE TABLE api_keys (
    api_key_id   UUID                     PRIMARY KEY,
    account_id   UUID                     NOT NULL REFERENCES accounts (account_id),
    key_hash     VARCHAR(64)              NOT NULL UNIQUE,
    environment  VARCHAR(4)               NOT NULL CHECK (environment IN ('test', 'live')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked      BOOLEAN                  NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_api_keys_account ON api_keys (account_id);

-- public_key holds the X.509 (SubjectPublicKeyInfo) encoding of the public key.
-- private_key_ciphertext holds the PKCS#8 private key encrypted with AES-256-GCM (task 3.1):
-- 1 format byte, 12-byte nonce, then the ciphertext with its 16-byte authentication tag.
CREATE TABLE key_pairs (
    key_pair_id             UUID                     PRIMARY KEY,
    account_id              UUID                     NOT NULL REFERENCES accounts (account_id),
    algorithm               VARCHAR(20)              NOT NULL CHECK (algorithm IN ('ML-KEM-1024', 'ML-DSA-65')),
    public_key              BYTEA                    NOT NULL,
    private_key_ciphertext  BYTEA                    NOT NULL,
    status                  VARCHAR(10)              NOT NULL CHECK (status IN ('active', 'rotated', 'revoked')),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at              TIMESTAMP WITH TIME ZONE,
    grace_period_ends_at    TIMESTAMP WITH TIME ZONE,
    replaced_by             UUID                     REFERENCES key_pairs (key_pair_id)
);

CREATE INDEX idx_key_pairs_account ON key_pairs (account_id);

CREATE TABLE audit_logs (
    log_id       BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   UUID                     NOT NULL REFERENCES accounts (account_id),
    api_key_id   UUID                     REFERENCES api_keys (api_key_id),
    key_pair_id  UUID                     REFERENCES key_pairs (key_pair_id),
    action       VARCHAR(12)              NOT NULL
                 CHECK (action IN ('generate', 'encapsulate', 'decapsulate', 'sign', 'verify', 'rotate')),
    status       VARCHAR(7)               NOT NULL CHECK (status IN ('success', 'failure')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_account_time ON audit_logs (account_id, created_at);
CREATE INDEX idx_audit_logs_key_pair ON audit_logs (key_pair_id);
