# QuantumLock

A REST API, web dashboard and Java/Spring SDK that let small development teams use NIST post-quantum
cryptography (ML-KEM-1024 key exchange and ML-DSA-65 signatures) without cryptography expertise.

Capstone Prototype (INFO34049), group 269_09.

## Repository layout

| Folder | What | Role | Jira |
|---|---|---|---|
| `backend/` | Spring Boot REST API | Backend / API | KAN-11 (2.1), KAN-15 (3.2), KAN-17 (3.4) |
| `database/` | PostgreSQL schema and sample data | Data & Security | KAN-12 (2.2) |
| `dashboard/` | React dashboard | Frontend / Dashboard | KAN-13 (2.3) |
| `sdk/` (later) | Java/Spring SDK | Backend / API | KAN-21 (5.1) |

## Prerequisites

- JDK 21
- PostgreSQL (tested with version 16)

Maven does not need to be installed. The Maven Wrapper in `backend/` downloads the right version on the first run:
type `mvnw` on Windows or `./mvnw` on macOS and Linux wherever you would type `mvn`.

## 1. Create the local database (once)

Run as the PostgreSQL admin user (in a terminal, the "SQL Shell (psql)" app or the pgAdmin query tool).
Choose your own password and put it in place of `<your password>`:

```sql
CREATE USER quantumlock WITH PASSWORD '<your password>';
CREATE DATABASE quantumlock OWNER quantumlock;
```

## 2. Tell the backend your password (once)

Copy `backend/.env.example` to `backend/.env` and set the same password there:

```
DB_PASSWORD=<your password>
```

`backend/.env` is ignored by git, so the password stays on your computer. There is no default password:
without `DB_PASSWORD` the backend stops at startup with the message `Could not resolve placeholder 'DB_PASSWORD'`.
Setting a `DB_PASSWORD` environment variable works too.

## 3. Run the backend

```bash
cd backend
./mvnw spring-boot:run        # Windows: mvnw spring-boot:run
```

Then open:

- Health check: http://localhost:8080/api/v1/health should return `"status": "UP"`, `"database": "UP"` and `"cryptoProvider": "UP"`
- API docs (Swagger UI): http://localhost:8080/swagger-ui.html

The backend listens on this computer only (`127.0.0.1`) and answers only to the host names `localhost` and
`127.0.0.1`. That is on purpose, see [SECURITY.md](SECURITY.md). Other settings are listed in `backend/.env.example`.

## 4. Run the tests

```bash
cd backend
./mvnw verify                 # Windows: mvnw verify
```

On Windows you can also double-click `run-tests.cmd` in the repository root. It runs the same command and writes
the output to `test-result.txt`.

Tests use an in-memory H2 database, so PostgreSQL does not need to be running. They cover:

- the health endpoint and the Swagger docs (`HealthControllerTest`, `ApiDocsDisabledTest`)
- attacks over real HTTP against the running application: forged Host header, oversized body, TRACE,
  path tricks, information leaks (`ApiSecurityTest`)
- the decisions inside the security filters (`SecurityFiltersTest`) and the secure defaults in `application.yml` (`SecureDefaultsTest`)
- ML-KEM-1024 and ML-DSA-65 round trips (`PostQuantumSmokeTest`) and how Bouncy Castle reacts to tampered
  ciphertexts, malformed keys and forged signatures (`PostQuantumMisuseTest`)

GitHub Actions runs the same build on every push (`.github/workflows/backend-ci.yml`).

## Versions

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.2 |
| springdoc-openapi | 3.2.0 |
| Bouncy Castle (bcprov-jdk18on) | 1.87 |
| PostgreSQL JDBC driver, H2 | managed by Spring Boot |

## Working together

- Create a branch per Jira task and put the key in its name, for example `KAN-15-key-generation`.
- Open a pull request into `main`; merge when the Backend CI check passes.
- Move the Jira task to Done when it is merged.
- Read the checklist in [SECURITY.md](SECURITY.md) before building the key and API-key endpoints (tasks 3.2 to 3.4).

## AI use

Parts of this code were drafted with the help of Claude AI and reviewed by the team, as allowed by the course AI policy.
