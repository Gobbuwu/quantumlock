# Security

QuantumLock stores and uses cryptographic keys, so security rules apply from the first commit.
This file says what is protected today, what is not protected yet, and the rules for the tasks that come next.

## Where the project stands

The backend is a skeleton. It has one endpoint (`GET /api/v1/health`) plus the API documentation.
It does not create, store or return any key yet, and it has **no login or API-key check yet** (task 3.3).
Until task 3.3 is merged, the backend must stay reachable from the developer's own computer only.
That is the default and it is enforced by the settings below.

## What is in place

| Risk | Protection | Where |
|---|---|---|
| Anyone on the same network calls the API | The server listens on `127.0.0.1` only | `server.address` in `application.yml` |
| A web page attacks the local API through the developer's browser (DNS rebinding) | Requests whose Host header is not `localhost` or `127.0.0.1` get 400 | `AllowedHostsFilter` |
| A well-known database password | No default password. The app does not start without `DB_PASSWORD`, which lives in a git-ignored `.env` file | `application.yml`, `.env.example`, `.gitignore` |
| Huge request bodies exhaust memory | Bodies over 64 KB are refused with 413, or cut off while streaming when the length was not declared. Form posts have the same limit and file uploads are switched off | `RequestSizeLimitFilter`, `application.yml` |
| Stalled connections hold server threads | Silent connections are closed after 10 seconds | `server.tomcat.connection-timeout` |
| Error messages reveal internals | Error responses contain a status code only: no exception, message, stack trace or requested path. The health check answers UP or DOWN, never a version | `spring.web.error.*`, `HealthController` |
| Clickjacking, content sniffing, cached secrets, script injection | `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` and a `default-src 'none'` content security policy on every response except the Swagger UI page | `SecurityHeadersFilter` |
| Other web sites read API responses | No CORS permission is given to any origin | Spring default, checked in `ApiSecurityTest` |
| API documentation exposed where it should not be | `API_DOCS_ENABLED=false` switches off Swagger UI and the OpenAPI document | `application.yml` |
| Vulnerable or tampered dependencies | Versions are pinned, Dependabot watches Maven and GitHub Actions, the CI workflow has read-only permissions and its actions are pinned to exact commits | `pom.xml`, `.github/` |
| Secrets or keys committed by accident | `.env` files and key files (`*.pem`, `*.key`, `*.p12`, `*.jks`) are ignored by git | `.gitignore` |

## How it is checked

`mvn verify` runs these on every push (GitHub Actions) and locally:

- `ApiSecurityTest` starts the application and attacks it over real HTTP connections: forged Host headers,
  an oversized body, the TRACE method, a path trick against the header rules, a cross-origin request,
  probes for developer and configuration paths, and checks for leaked versions or stack traces.
- `SecurityFiltersTest` tests the decisions inside the filters, including a 5 MB body that must be cut off
  after 64 KB plus one byte.
- `SecureDefaultsTest` fails the build if someone gives `DB_PASSWORD` a default, opens the listen address
  or switches error details back on.
- `PostQuantumMisuseTest` feeds Bouncy Castle tampered ciphertexts, malformed and swapped keys, corrupted
  stored private keys and forged signatures, and pins down how it reacts.

## Known limits

Nothing here makes the application impossible to attack. These gaps are known and accepted for now:

- **No authentication yet.** Every caller on the same computer can use every endpoint. Task 3.3 closes this.
- **No TLS.** Traffic is plain HTTP, which is acceptable only because it never leaves the computer.
- **No rate limiting.** A local program can send as many requests as it likes.
- A client that announces a body and then stays silent occupies one server thread for up to 10 seconds
  (20 seconds if it stalls in the middle of a body). A reverse proxy is the usual protection.
- Requests that Tomcat itself refuses before they reach the application (malformed HTTP, for example two
  Host headers) get Tomcat's plain error page. It contains only the status line, but not the security headers.
- The Host check protects against browsers. A program on the same computer can send any Host header it likes,
  which is why the listen address and, later, API keys matter.

## Before the API is reachable from another computer

All of these, not some of them:

1. API-key authentication on every endpoint except the health check (task 3.3).
2. TLS, directly or through a reverse proxy. Private keys and shared secrets travel in responses.
3. `SERVER_ADDRESS` and `ALLOWED_HOSTS` set to the real address and host name, nothing wider.
4. `API_DOCS_ENABLED=false`.
5. Rate limiting, at least on failed API-key checks.
6. A strong, unique database password and a database that is not reachable from outside.

## Rules for the next tasks

### Key generation and storage (tasks 3.1 and 3.2)

- Private keys are encrypted before they are stored (AES-256-GCM, a fresh random 12-byte nonce per record).
  The master key comes from an environment variable, never from the repository or the database.
- Private keys, shared secrets and API keys are never written to logs, error messages or the audit log.
  The audit log records who did what to which key id.
- Reload stored keys through `KeyFactory`. Bouncy Castle 1.87 rejects a stored private key that was altered
  (`corruptedStoredPrivateKeyIsRejected`). Treat that as an error, never fall back to "best effort".
- Every database read of a key includes the owner: look up by key id **and** account id, and answer 404
  for a key that belongs to someone else. Never trust an account id sent by the client.
- Use Spring Data methods or `@Query` with parameters. Never build SQL by joining strings.

### API keys (task 3.3)

- Generate API keys with `SecureRandom`, at least 32 random bytes. Show a key once, store only its SHA-256 hash.
- Compare hashes with `MessageDigest.isEqual` (constant time).
- Accept the key in a request header only, never in the URL (URLs end up in logs and browser history).
- Deny by default: a request without a valid key gets the same 401 whatever the reason.
- A custom header also protects against cross-site requests, because browsers will not send it without CORS permission.

### Encapsulation, decapsulation and signatures (task 3.4 and later)

- Decode Base64 strictly and check sizes before calling the library: an ML-KEM-1024 public key is 1568 bytes,
  a ciphertext 1568 bytes, an ML-DSA-65 signature 3309 bytes. A ciphertext of the wrong length makes
  Bouncy Castle throw an unchecked `IllegalArgumentException`, which would otherwise become a 500.
- Parse every key a client sends with `KeyFactory.getInstance("ML-KEM-1024", "BC")`. The general `"ML-KEM"`
  key factory also accepts ML-KEM-512 and ML-KEM-768 keys, so with it a client could quietly lower the
  security level (`weakerParameterSetIsAcceptedByTheGeneralKeyFactory`, `mlKem1024KeyFactoryRejectsWeakerParameterSets`).
- Answer every kind of bad crypto input with the same 400 and a fixed error code. Never pass on the library's
  exception message.
- Decapsulation of a tampered ciphertext does not fail, it returns a different secret (FIPS 203 implicit
  rejection). Keep it that way: do not add a "ciphertext was invalid" answer, which would tell an attacker
  something the algorithm is designed to hide.
- Create one `SecureRandom` and let it seed itself. Never seed it with fixed or time-based values.

### Dashboard (task 2.3 and later)

- Allow exactly the dashboard's origin in CORS. Never `*`.
- No API key in the React source or in any committed file.

## Reporting a problem

Please do not open a public issue for a security problem. Use "Report a vulnerability" on the repository's
Security tab if it is available, or tell the team directly.
