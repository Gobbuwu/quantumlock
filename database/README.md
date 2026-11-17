# Database (task 2.2, KAN-12, Data & Security)

Put the PostgreSQL scripts here:

- `schema.sql`: the accounts, api_keys, key_pairs and audit_logs tables from the RRD ER diagram
- `seed.sql`: sample data for testing and the demo

Run them against the local `quantumlock` database created in the main README, for example:

```
psql -h localhost -U quantumlock -d quantumlock -f database/schema.sql
psql -h localhost -U quantumlock -d quantumlock -f database/seed.sql
```

The backend does not create tables itself (`spring.jpa.hibernate.ddl-auto: none`), so these scripts are the single source of the schema.

Security rules for these scripts (details in [SECURITY.md](../SECURITY.md)):

- `seed.sql` holds made-up demo data only. Never real API keys, real private keys or real people.
- `api_keys` stores a hash of each API key, never the key itself.
- `key_pairs` stores private keys encrypted, never as plain bytes.
