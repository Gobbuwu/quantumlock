# QuantumLock

A REST API, web dashboard and Java/Spring SDK that let small development teams use NIST post-quantum
cryptography (ML-KEM-1024 key exchange and ML-DSA-65 signatures) without cryptography expertise.

Capstone Prototype (INFO34049), group 269_09.

**Quickest start:** install the [prerequisites](#prerequisites), then [run the demo](#run-the-demo):

| | Once | Every time |
|---|---|---|
| **Windows** | double-click `demo\setup.cmd` | double-click `demo\start.cmd` |
| **macOS and Linux** | `demo/setup.sh` | `demo/start.sh` |

Then open http://127.0.0.1:4173 and paste the API key that setup gave you.

## Contents

- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Run the demo](#run-the-demo) (setup, start, remove, options)
- Set up by hand instead:
  - [1. Create the local database (once)](#1-create-the-local-database-once)
  - [2. Set the password and the master key (once)](#2-set-the-password-and-the-master-key-once)
  - [3. Run the backend](#3-run-the-backend) (API key, endpoints, settings, rate limits)
- [4. Run the tests](#4-run-the-tests) (including the [end-to-end test](#end-to-end-test-fresh-setup))
- [Versions](#versions)
- [Working together](#working-together)
- [AI use](#ai-use)

## Repository layout

| Folder | What | Role | Jira |
|---|---|---|---|
| `backend/` | Spring Boot REST API | Backend / API | KAN-11 (2.1), KAN-15 (3.2), KAN-17 (3.4) |
| `database/` | PostgreSQL schema and sample data | Data & Security | KAN-12 (2.2) |
| `dashboard/` | React dashboard | Frontend / Dashboard | KAN-13 (2.3) |
| `e2e/` | End-to-end test on a fresh setup | Testing | KAN-24 (6.3) |
| `sdk/` | Java client, Spring Boot starter and sample app ([sdk/README.md](sdk/README.md)) | Backend / API | KAN-21 (5.1) |
| `demo/` | Scripts that set up and start the whole demo on one computer | Demo | KAN-26 (7.1) |

## Prerequisites

| Need | Version | Get it |
|---|---|---|
| Git, to clone the repository (or download the ZIP from GitHub instead) | any | [git-scm.com](https://git-scm.com) |
| JDK | 21 or later. GitHub Actions tests with 21, and the Windows demo also ran on JDK 25 (Nov 23, 2026) | [Temurin](https://adoptium.net) |
| PostgreSQL, with the password of its administrator user `postgres` | tested with version 16 | |
| Node.js, for the dashboard | 22 or later | [nodejs.org](https://nodejs.org) |

Maven does not need to be installed. The Maven Wrapper in `backend/` downloads the right version on the first run:
type `mvnw` on Windows or `./mvnw` on macOS and Linux wherever you would type `mvn`.

> [!WARNING]
> The Windows PostgreSQL installer can leave the database listening on every network interface. QuantumLock itself
> listens on this computer only, but on a laptop that joins other networks, keep the database private too: set
> `listen_addresses = 'localhost'` in `postgresql.conf` (in the PostgreSQL `data` folder) and restart the PostgreSQL
> service, or make sure the firewall blocks port 5432.

## Run the demo

The scripts in `demo/` do steps 1 to 3 below in one go and start the dashboard as well. This is the quickest way to
show QuantumLock on a new computer.

| | Set up (once) | Start (every time) | Remove |
|---|---|---|---|
| **Windows** (double-click) | `demo\setup.cmd` | `demo\start.cmd` | `demo\remove.cmd` |
| **macOS and Linux** | `demo/setup.sh` | `demo/start.sh` | `demo/remove.sh` |

### Setup

Setup asks for the password of the PostgreSQL user `postgres` once. It then:

1. creates the database and user `quantumlock` with a random password
2. loads the tables and demo data
3. writes `backend/.env` with that password and a random `MASTER_KEY`
4. builds the backend
5. issues the first API key

It shows the key masked (`ql_test_...` and its last four characters), so a screenshot of the window does not
expose it:

| Press | What happens |
|---|---|
| **C** | copies the key (Windows keeps this copy out of clipboard history) |
| **S** | shows the key |
| **Enter** | finishes, which clears the clipboard again |

The key cannot be shown again later, so keep it somewhere safe. Setup needs a terminal window for this;
`--show-key` (`-ShowKey` on Windows) prints the key instead, for scripts whose output stays private. The database
password and `MASTER_KEY` are never shown, and `backend/.env` is readable by you only.

Setup never overwrites anything: it stops if `backend/.env`, the database or the user already exists. If a step
fails, it removes what it had created, so it can simply be run again.

### Start

Start builds the backend and the dashboard when needed, starts both and prints the address.

1. Open http://127.0.0.1:4173, paste the API key and generate a key pair.
2. Press Ctrl+C to stop both. On Windows the window may then ask "Terminate batch job (Y/N)?": both answers are
   fine, the demo has already stopped.

Both listen on this computer only, and the dashboard is the production build with its content security policy.

### Remove

`demo\remove.cmd` (or `demo/remove.sh`) deletes the database, its user and `backend/.env` again, after you type
the database name. Every key pair stored in it is lost. It only removes a database that setup created: the settings
file must name that database and user, so a database set up by hand (steps 1 to 3) is never touched.

### Options

Options, for example to keep a second demo next to your own setup:

| Option | Windows | macOS and Linux |
|---|---|---|
| Database name | `-DbName <name>` | `--db-name <name>` |
| Settings file | `-EnvFile <path>` | `--env-file <path>` |
| Print the API key instead of masking it | `-ShowKey` | `--show-key` |

The administrator connection can be changed with the usual `PGHOST`, `PGPORT`, `PGUSER` and `PGPASSWORD` variables.
GitHub Actions runs the scripts on every pull request that touches them (`.github/workflows/demo.yml`).

To set things up by hand instead, follow steps 1 to 3.

## 1. Create the local database (once)

Run as the PostgreSQL admin user (in a terminal, the "SQL Shell (psql)" app or the pgAdmin query tool).
Choose your own password and put it in place of `<your password>`:

```sql
CREATE USER quantumlock WITH PASSWORD '<your password>';
CREATE DATABASE quantumlock OWNER quantumlock;
```

Then create the tables and load the demo data, from the repository folder:

```bash
psql -h localhost -U quantumlock -d quantumlock -f database/schema.sql
psql -h localhost -U quantumlock -d quantumlock -f database/seed.sql
```

> [!NOTE]
> **Already have a database from before Nov 23, 2026?** Run the upgrade script once, as the PostgreSQL admin user, so
> the audit log accepts key revocations (KAN-30). It is safe to run again:
>
> ```bash
> psql -h localhost -U postgres -d quantumlock -v ON_ERROR_STOP=1 -f database/upgrades/001-revoke-action.sql
> ```

## 2. Set the password and the master key (once)

Copy `backend/.env.example` to `backend/.env` and fill in two values:

```
DB_PASSWORD=<your password>
MASTER_KEY=<32 random bytes, Base64>
```

`MASTER_KEY` encrypts every private key the backend stores. Create it on your own computer with one of these
commands and paste the output:

- Windows PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`
- macOS or Linux: `openssl rand -base64 32`

> [!IMPORTANT]
> Keep a copy somewhere safe: if the master key is lost, the stored private keys can no longer be used.

`backend/.env` is ignored by git, so both values stay on your computer. Neither has a default: without them the
backend stops at startup with a message such as `Could not resolve placeholder 'MASTER_KEY'`.
Environment variables with the same names work too. The other settings are listed under
[Settings](#settings).

## 3. Run the backend

```bash
cd backend
./mvnw spring-boot:run        # Windows: mvnw spring-boot:run
```

Then open:

- Health check: http://localhost:8080/api/v1/health should return `"status": "UP"`, `"database": "UP"` and `"cryptoProvider": "UP"`
- API docs (Swagger UI): http://localhost:8080/swagger-ui.html, where you can try the [endpoints](#endpoints)

### First API key

Every endpoint except the health check needs an API key in the `X-API-Key` header. Issue the first key for the
demo team from `database/seed.sql` once (the backend does not need to be running):

```bash
cd backend
./mvnw spring-boot:run "-Dspring-boot.run.arguments=issue-api-key"     # Windows: mvnw spring-boot:run "-Dspring-boot.run.arguments=issue-api-key"
```

For another team, add its account id after a space (not a comma):
`"-Dspring-boot.run.arguments=issue-api-key <account id>"`.

The key (`ql_test_...`) is printed once and only its hash is stored, so copy it somewhere safe and never commit it.
In Swagger UI click **Authorize** and paste it. With a key you can also create more keys (`POST /api/v1/api-keys`),
list them (`GET /api/v1/api-keys`) and revoke one (`DELETE /api/v1/api-keys/{apiKeyId}`).

### Endpoints

| Endpoint | What it does |
|---|---|
| `POST /api/v1/keys/generate` | Makes a key pair: `{"algorithm": "ML-KEM-1024"}` or `{"algorithm": "ML-DSA-65"}` |
| `GET /api/v1/keys` and `GET /api/v1/keys/{keyPairId}` | Lists your team's key pairs, or reads one |
| `POST /api/v1/keys/{keyPairId}/encapsulate` | ML-KEM-1024: returns a fresh `sharedSecret` and its `ciphertext` |
| `POST /api/v1/keys/{keyPairId}/decapsulate` | ML-KEM-1024: returns the `sharedSecret` from a `ciphertext` |
| `POST /api/v1/keys/{keyPairId}/sign` | ML-DSA-65: returns a `signature` for a message |
| `POST /api/v1/keys/{keyPairId}/verify` | ML-DSA-65: returns `{"valid": true}` or `{"valid": false}` |
| `POST /api/v1/keys/{keyPairId}/rotate` | Replaces a key pair; the old one keeps a grace period |
| `POST /api/v1/keys/{keyPairId}/revoke` | Stops a key pair for good |
| `GET /api/v1/audit-logs` | Reads your team's audit log, newest first |
| `POST /api/v1/api-keys`, `GET /api/v1/api-keys` and `DELETE /api/v1/api-keys/{apiKeyId}` | Creates, lists and revokes API keys |

<details>
<summary><b>Endpoint details</b> (request bodies, sizes, grace period, audit log paging)</summary>

- `POST /api/v1/keys/generate` with `{"algorithm": "ML-KEM-1024"}` or `{"algorithm": "ML-DSA-65"}`
- `GET /api/v1/keys` and `GET /api/v1/keys/{keyPairId}`
- `POST /api/v1/keys/{keyPairId}/encapsulate` (no body) returns a fresh `sharedSecret` and the `ciphertext` that
  carries it; `POST /api/v1/keys/{keyPairId}/decapsulate` with `{"ciphertext": "..."}` returns the same
  `sharedSecret`. ML-KEM-1024 key pairs only. A sender can also encapsulate with the public key on their own
  computer and send only the ciphertext. The `sharedSecret` is the 32-byte FIPS 203 output K with no extra key
  derivation, so it matches what any other FIPS 203 ML-KEM library computes.
- `POST /api/v1/keys/{keyPairId}/sign` with `{"message": "...", "context": "..."}` returns a 3309-byte
  `signature`; `POST /api/v1/keys/{keyPairId}/verify` with `{"message": "...", "signature": "...", "context": "..."}`
  returns `{"valid": true}` or `{"valid": false}`. ML-DSA-65 key pairs only (FIPS 204). All values are Base64. The
  message is 1 byte to 32 KiB. The optional context string (at most 255 bytes) ties a signature to one purpose, such
  as "invoices": a signature only verifies with the same context. Anyone can also verify with the public key on
  their own computer, using any FIPS 204 library.
- `POST /api/v1/keys/{keyPairId}/rotate` with an optional `{"gracePeriodDays": 7}` makes a new key pair of the
  same algorithm and returns it. The old one becomes `rotated` and points to its replacement (`replacedBy`). Until
  its `gracePeriodEndsAt` it can still decapsulate and verify, so data made for it before can still be read, but it
  can no longer encapsulate, sign or be rotated again. The default grace period is 7 days and the most a caller may
  ask for is 90. Both can be changed in `backend/.env` (`ROTATION_GRACE_DAYS`, `ROTATION_MAX_GRACE_DAYS`, see
  `.env.example`). 0 ends the old key pair's use at once.
- `POST /api/v1/keys/{keyPairId}/revoke` stops an active or rotated key pair for good. It cannot be undone, and
  the key pair stays in the list, marked `revoked`, for the record.
- `GET /api/v1/audit-logs` lists your team's audit log, newest first: every generate, encapsulate, decapsulate,
  sign, verify, rotate and revoke call, with its time, `success` or `failure`, the key pair and the API key used.
  Optional filters: `keyPairId`, `action` and `status` (`success` or `failure`). `limit` is 1 to 100 (default 50).
  When there are older rows the answer has a `nextBefore` value: pass it as `before` to get the next page. The log
  holds no messages, secrets or key material, and it can only be read, never changed through the API.
- `POST /api/v1/api-keys`, `GET /api/v1/api-keys` and `DELETE /api/v1/api-keys/{apiKeyId}`

</details>

### Settings

The backend listens on this computer only (`127.0.0.1`) and answers only to the host names `localhost` and
`127.0.0.1`. That is on purpose, see [SECURITY.md](SECURITY.md). Other settings are listed in `backend/.env.example`.
Set any of them in `backend/.env` (or as an environment variable) and restart the backend:

| Setting | Default | What it does |
|---|---|---|
| `DB_PASSWORD` | none, required | Password of the PostgreSQL user `quantumlock` |
| `MASTER_KEY` | none, required | Encrypts every private key the backend stores (32 random bytes, Base64) |
| `DB_URL` | `jdbc:postgresql://localhost:5432/quantumlock` | Database to connect to |
| `DB_USERNAME` | `quantumlock` | Database user |
| `PORT` | `8080` | Port the backend listens on |
| `API_DOCS_ENABLED` | `true` | `false` switches off Swagger UI and the OpenAPI document |
| `MAX_REQUEST_BYTES` | `65536` | Largest request body in bytes; larger requests get 413 |
| `ROTATION_GRACE_DAYS` | `7` | Days a rotated key pair can still decapsulate and verify when the caller does not say (0 to 3650) |
| `ROTATION_MAX_GRACE_DAYS` | `90` | The most a caller may ask for (0 to 3650) |
| `RATE_LIMIT_PER_MINUTE` | `120` | Requests one API key may make in a minute (1 to 1000000) |
| `RATE_LIMIT_FAILED_CHECKS_PER_MINUTE` | `20` | Failed API-key checks one client address may cause in a minute (1 to 1000000) |
| `SERVER_ADDRESS` | `127.0.0.1` | Leave alone unless you know why you are changing it (see [SECURITY.md](SECURITY.md)) |
| `ALLOWED_HOSTS` | `localhost,127.0.0.1` | Leave alone unless you know why you are changing it (see [SECURITY.md](SECURITY.md)) |

### Rate limits

Requests are rate limited:

- Each API key may make 120 requests per minute (`RATE_LIMIT_PER_MINUTE`).
- A client address that fails the API-key check 20 times in a minute (`RATE_LIMIT_FAILED_CHECKS_PER_MINUTE`) gets
  429 for its failed checks for the rest of that minute; a valid key is never refused because of its address.
- Both answers are 429 `{"error": "rate_limited"}` with a `Retry-After` header that gives the seconds to wait.
- A request that a browser marks as coming from another web site gets 403 `{"error": "cross_site_request"}` and
  does not count as a failed check.
- A refused request does nothing and is not audited.
- The health check and the API docs are not limited.

Set either value in `backend/.env` (1 to 1000000) and restart the backend to change it.

## 4. Run the tests

```bash
cd backend
./mvnw verify                 # Windows: mvnw verify
```

On Windows you can also double-click `run-tests.cmd` in the repository root. It runs the same command and writes
the output to `test-result.txt`.

Tests use an in-memory H2 database, so PostgreSQL does not need to be running. GitHub Actions runs the same build
on every pull request and every push to main (`.github/workflows/backend-ci.yml`, started by `ci.yml`).

The Java SDK in `sdk/` has its own tests and CI. See [sdk/README.md](sdk/README.md#tests).

<details>
<summary><b>What the backend tests cover</b></summary>

- the health endpoint and the Swagger docs (`HealthControllerTest`, `ApiDocsDisabledTest`)
- key generation through the API, including that the stored private key is encrypted, still works after
  decryption and is never returned, and that one team cannot see another team's keys (`KeyApiTest`)
- API-key authentication: every kind of bad key gets the same 401, keys in the URL are ignored, revocation
  works at once and only the hash is stored (`ApiKeyAuthTest`, `ApiKeysTest`, `IssueApiKeyCommandTest`)
- ML-KEM-1024 key exchange through the API: round trips, a client encapsulating on its own, a tampered ciphertext,
  malformed input, wrong key types, other teams' keys and the audit log (`KeyExchangeApiTest`)
- the private-key encryption: tampering, a moved ciphertext and a wrong master key are all detected (`PrivateKeyEncryptorTest`)
- attacks over real HTTP against the running application: forged Host header, oversized body, TRACE,
  path tricks, information leaks (`ApiSecurityTest`)
- the decisions inside the security filters (`SecurityFiltersTest`) and the secure defaults in `application.yml` (`SecureDefaultsTest`)
- rate limiting: the counting itself, including a memory cap and 8 threads at once (`RateLimiterTest`), and through
  the API the 429 answer, per-key and per-address limits, that a valid key is never locked out, that cross-site
  requests get 403 without counting, that a refused request does nothing and that forwarding
  headers cannot dodge the limit (`RateLimitTest`), and that out-of-range settings are refused (`RateLimitSettingsTest`)
- ML-KEM-1024 key generation, encapsulation and decapsulation against NIST's own FIPS 203 test vectors, run
  through the application's code (`MlKem1024KnownAnswerTest`)
- ML-KEM-1024 and ML-DSA-65 round trips (`PostQuantumSmokeTest`) and how Bouncy Castle reacts to tampered
  ciphertexts, malformed keys and forged signatures (`PostQuantumMisuseTest`)
- ML-DSA-65 signatures through the API: round trips, an independent verifier, tampered messages, signatures and
  contexts, the size limits, malformed input, wrong key types, other teams' keys and the audit log (`SignatureApiTest`)
- ML-DSA-65 key generation, signing and verification against NIST's own FIPS 204 test vectors, run through the
  application's code (`MlDsa65KnownAnswerTest`)
- key rotation and revocation: what each state allows, the grace period and its end, bad grace periods, two
  rotations (or a rotation and a revocation) at the same moment, other teams' keys and the audit log
  (`KeyRotationApiTest`)
- the audit log API: every field, newest first, paging that never repeats or skips a row (also while new rows
  arrive and when times are equal), the filters, the limits, other teams' rows and cursors and every bad
  parameter (`AuditLogApiTest`)

</details>

### End-to-end test (fresh setup)

`e2e/run.sh` starts everything from nothing and checks the whole product through a real browser, the SDK and the
database. To run it yourself (macOS or Linux; not yet tried on Windows), with a PostgreSQL superuser:

```bash
cd dashboard && npm ci && npx playwright install chromium && cd ..
PGHOST=localhost PGUSER=postgres PGPASSWORD=<postgres password> ./e2e/run.sh
```

GitHub Actions runs it on every pull request that touches the backend, dashboard, database or SDK
(`.github/workflows/e2e.yml`).

<details>
<summary><b>What the end-to-end test does</b></summary>

1. **Fresh setup:** a new database and user (both named `quantumlock_e2e`), the schema and demo data, the backend
   on port 18080, one API key and the dashboard's production build (with its content security policy) on port 15173.
2. **Dashboard in a real browser (Playwright):** refuses a wrong key, generates an ML-KEM-1024 and an ML-DSA-65
   key pair, sees both in the list and checks that the API key is kept only in memory unless "Remember" is ticked.
   It then generates a third ML-DSA-65 key pair, signs a message and verifies it (a changed message must be
   refused), rotates it, revokes the old one and reads its audit log, all through the dashboard.
3. **Accessibility:** axe-core then checks every view for accessibility problems at desktop and phone width in both
   themes, and the keyboard test checks the skip link and the sideways-scrolling audit table. Any content security
   policy violation fails the test.
4. **Java SDK:** next, the Java SDK's sample app generates an ML-KEM-1024 key pair and agrees on a shared secret
   through the API, then generates an ML-DSA-65 one, signs a message, verifies it and checks that a changed copy is
   refused. A wrong key must be refused with 401.
5. **Rotation, upgrade and audit log API:** the script then rotates the sample app's ML-KEM key pair, revokes the
   replacement, checks that the rotated key cannot be rotated again and runs the database upgrade script twice. It
   reads the whole audit log through the API, 7 rows a page, and one filtered page.
6. **Database checks:** last, it checks in the database that all seven private keys are stored encrypted, that the
   rotated key pair points to its replacement with a 1-day grace period, that every generate, encapsulate,
   decapsulate, sign, verify, rotate and revoke call was audited and that the audit log API returned every row
   exactly once, newest first.

The database password, `MASTER_KEY` and API key are random for every run and never printed. The script refuses to
run if a database or user named `quantumlock_e2e` already exists, and removes both at the end.

</details>

## Versions

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.2.0 |
| springdoc-openapi | 3.2.0 |
| Bouncy Castle (bcprov-jdk18on) | 1.87 |
| PostgreSQL JDBC driver, H2 | managed by Spring Boot |

## Working together

- Create a branch per Jira task and put the key in its name, for example `KAN-15-key-generation`.
- Open a pull request into `main`; merge when the `summary` check passes. It is the one check the branch rule
  requires: `.github/workflows/ci.yml` runs the backend, dashboard, SDK, demo and end-to-end checks that the change
  needs and passes only when all of them pass.
- Move the Jira task to Done when it is merged.
- Read the checklist in [SECURITY.md](SECURITY.md) before building the key and API-key endpoints (tasks 3.2 to 3.4).

## AI use

Parts of this code were drafted with the help of Claude AI and reviewed by Quang Dung Le, as allowed by the course AI policy.
