-- QuantumLock sample data (task 2.2, KAN-12). Run after schema.sql.
-- Made-up demo data only: no real people, no real API keys and no real key material.
--
-- Key pairs are not seeded. Their private keys must be encrypted with each developer's own
-- MASTER_KEY, so they are created through the API (POST /api/v1/keys/generate) instead.
-- API keys are not seeded either: they are issued by the API-key feature (task 3.3).

-- The demo team. Until API-key login exists (task 3.3) the backend acts as this account
-- (DEV_ACCOUNT_ID in backend/.env.example).
INSERT INTO accounts (account_id, team_name, contact_email, plan_tier, created_at)
VALUES ('5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51', 'QuantumLock Demo Team', 'demo-team@example.com', 'free',
        TIMESTAMP WITH TIME ZONE '2026-11-22 09:00:00+00');

-- A second team, used to check that one team can never see another team's keys.
INSERT INTO accounts (account_id, team_name, contact_email, plan_tier, created_at)
VALUES ('a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34', 'Other Demo Team', 'other-team@example.com', 'free',
        TIMESTAMP WITH TIME ZONE '2026-11-22 09:00:00+00');
