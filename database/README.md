# Database (task 2.2, KAN-12)

- `schema.sql`: the accounts, api_keys, key_pairs and audit_logs tables from the RRD ER diagram.
  The header of the file lists where it differs from the diagram and why.
- `seed.sql`: demo data (two made-up teams). Key pairs are created through the API instead, because their
  private keys must be encrypted with your own `MASTER_KEY`.

Both scripts are tested on PostgreSQL 16, and the backend tests build their H2 database from the same two files.

Run them once against the local `quantumlock` database created in the main README, for example:

```
psql -h localhost -U quantumlock -d quantumlock -f database/schema.sql
psql -h localhost -U quantumlock -d quantumlock -f database/seed.sql
```

The backend does not create tables itself (`spring.jpa.hibernate.ddl-auto: none`), so these scripts are the single source of the schema.
To start over, drop and recreate the database, then run both scripts again.

Security rules for these scripts (details in [SECURITY.md](../SECURITY.md)):

- `seed.sql` holds made-up demo data only. Never real API keys, real private keys or real people.
- `api_keys` stores a hash of each API key, never the key itself.
- `key_pairs` stores private keys encrypted, never as plain bytes.
