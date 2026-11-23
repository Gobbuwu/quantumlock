-- Upgrade 001 (KAN-30): allows the audit action 'revoke' in a database created before Nov 23, 2026.
-- Databases created from the current schema.sql already allow it; running this on them changes nothing.
-- Safe to run more than once. Run it as the database owner or as postgres, for example:
--     psql -U postgres -d quantumlock -v ON_ERROR_STOP=1 -f database/upgrades/001-revoke-action.sql

BEGIN;

-- PostgreSQL named the old, unnamed check audit_logs_action_check, the same name schema.sql now gives it.
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_action_check;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check
    CHECK (action IN ('generate', 'encapsulate', 'decapsulate', 'sign', 'verify', 'rotate', 'revoke'));

COMMIT;
