# Security

QuantumLock stores and uses cryptographic keys, so security rules apply from the first commit.
This file says what is protected today, what is not protected yet, and the rules for the tasks that come next.

## Where the project stands

The backend generates and stores key pairs (task 3.2), with private keys encrypted at rest (task 3.1).
Every endpoint except the health check and the API documentation needs an API key (task 3.3), and each
request acts only for the team that owns the key. The backend must still stay reachable from the developer's
own computer only (no TLS yet). That is the default and it is enforced by the settings below.

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
| Script injection or framing in the dashboard | The production build is served with a content security policy that allows script, styles, fonts, images and API calls from the dashboard's own address only, no inline script and no framing, plus `nosniff`, `DENY` framing and `no-referrer`. The end-to-end test fails on any violation and checks that an injected inline script does not run | `dashboard/vite.config.js`, `dashboard/e2e/` |
| Other web sites read API responses | No CORS permission is given to any origin | Spring default, checked in `ApiSecurityTest` |
| API documentation exposed where it should not be | `API_DOCS_ENABLED=false` switches off Swagger UI and the OpenAPI document | `application.yml` |
| Vulnerable or tampered dependencies | Versions are pinned, Dependabot watches Maven and GitHub Actions, the CI workflow has read-only permissions and its actions are pinned to exact commits. The Maven wrapper refuses a Maven download whose SHA-256 does not match the pinned value | `pom.xml`, `.github/`, `maven-wrapper.properties` |
| A stolen database exposes private keys | Every private key is encrypted with AES-256-GCM under `MASTER_KEY`, which is never stored in the database or the repository. The key pair id and account id are bound into the encryption, so a ciphertext copied to another row or account does not decrypt | `PrivateKeyEncryptor`, `EncryptionConfig` |
| Private keys leak through the API or the logs | Responses carry only the id, algorithm, status, public key and time. Failures are logged by type only | `KeyPairResponse`, `KeyService` |
| Anyone on the computer uses the API | Every path except `/api/v1/health` and the API docs needs a valid key in the `X-API-Key` header. Missing, malformed, unknown, revoked or duplicated keys all get the same 401 `{"error":"unauthorized"}`. A key in the URL is ignored | `ApiKeyAuthFilter`, `ApiKeyService` |
| A stolen database exposes API keys | Keys are 32 bytes from `SecureRandom`, shown once and stored only as their SHA-256 hash, compared with `MessageDigest.isEqual`. A revoked key stops working on the next request | `ApiKeys`, `api_keys.key_hash` |
| Bad input crashes or probes the key exchange | Ciphertexts are decoded as strict Base64 and must be exactly 1568 bytes before Bouncy Castle sees them. Keys are loaded with the `ML-KEM-1024` key factory only. Every malformed input gets the same 400 `invalid_request`; a tampered but well-formed ciphertext gets a normal answer with a different secret (FIPS 203 implicit rejection). Library failures become a fixed 500 `operation_failed`, logged by type only. Every call is audited | `KeyExchangeService` |
| One team reads another team's keys | Every key lookup includes the account id. Another team's key, an unknown id and a malformed id all get the same 404 | `KeyPairRepository`, `ApiExceptionHandler` |
| One team reads another team's audit log | Every audit query and cursor lookup includes the account id. Another team's key pair gives an empty page and another team's cursor the same 400 as an unknown one. The log is read only and holds no key material | `AuditLogService`, `AuditLogQuery` |
| A retired or compromised key pair stays in use | Rotation replaces a key pair: the old one can only decapsulate and verify, and only until its grace period ends (default 7 days, at most 90). Revocation stops a key pair at once and for good. Every operation checks the state first, and a refused one gets 400 `key_not_active` and is audited. Status changes lock the row, so two at the same moment run one after the other | `KeyPairAccess`, `KeyService`, `KeyPairStore` |
| Loosely typed input changes meaning | Number fields take JSON numbers only and whole ones: `"7"`, `7.5` and `true` are refused with 400, not turned into 7 | `spring.jackson.*` in `application.yml` |
| An application using the SDK leaks its API key or a shared secret | The Java SDK sends the key in the `X-API-Key` header only, refuses plain `http://` except on the same computer, never follows redirects, times out every call and checks every answer strictly. The key and secrets never appear in `toString()`, exception messages or logs, and `SharedSecret` can be wiped | `sdk/quantumlock-client` |
| Floods of requests or API-key guessing | Each API key may make 120 requests per minute. A client address that fails the API-key check 20 times in a minute is refused for the rest of that minute, even with a valid key. Both get 429 `rate_limited` with `Retry-After`, before any other work. Counting uses the connection's address only, never a forwarding header. At most 10,000 keys and 10,000 addresses are tracked: when a table is full, new ones are refused until a minute has passed, so a flood cannot use up memory. Both limits are settings (`RATE_LIMIT_PER_MINUTE`, `RATE_LIMIT_FAILED_CHECKS_PER_MINUTE`) | `RateLimiter`, `ApiKeyAuthFilter` |
| Secrets or keys committed by accident | `.env` files and key files (`*.pem`, `*.key`, `*.p12`, `*.jks`) are ignored by git | `.gitignore` |

## How it is checked

`mvn verify` runs these on every push (GitHub Actions) and locally:

- `EncryptionConfigTest` checks that the backend does not start without a valid `MASTER_KEY` and never prints the value.
- `MlKem1024KnownAnswerTest` checks key generation, encapsulation and decapsulation against all 60 ML-KEM-1024
  test vectors in NIST's ACVP-Server repository (FIPS 203), including implicit rejection of modified
  ciphertexts. The shared secret is the FIPS 203 output K itself: Bouncy Castle's default extra key
  derivation is turned off so other FIPS 203 libraries get the same secret.
- `KeyExchangeApiTest` round-trips encapsulation and decapsulation through the API and with an independent
  FIPS 203 client (Bouncy Castle's low-level ML-KEM API), and checks implicit rejection, malformed and weaker-parameter ciphertexts, wrong key
  types, inactive keys, team isolation and the audit entries.
- `ApiKeyAuthTest` checks every way of failing the API-key check, keys in the URL, revocation, that only
  the hash is stored and that a key acts for its own team only. `ApiKeysTest` checks the key format and hashing.
- `ApiSecurityTest` starts the application and attacks it over real HTTP connections: forged Host headers,
  an oversized body, the TRACE method, a path trick against the header rules, a cross-origin request,
  probes for developer and configuration paths, and checks for leaked versions or stack traces.
- `SecurityFiltersTest` tests the decisions inside the filters, including a 5 MB body that must be cut off
  after 64 KB plus one byte.
- `RateLimiterTest` checks the counting with a fake clock: the exact limit, the wait, refused events not counted,
  the memory cap and exactly 100 allowed out of 800 calls from 8 threads. `RateLimitTest` checks the limits through
  the API: 429 with `Retry-After`, no work done for a refused request, a blocked address even with a valid key,
  forwarding headers ignored and the health check never limited.
- `SecureDefaultsTest` fails the build if someone gives `DB_PASSWORD` a default, opens the listen address
  or switches error details back on.
- `PostQuantumMisuseTest` feeds Bouncy Castle tampered ciphertexts, malformed and swapped keys, corrupted
  stored private keys and forged signatures, and pins down how it reacts.
- The SDK's own tests (`cd sdk && mvn verify`, GitHub Actions `sdk-ci.yml`) check the header, the refused URLs
  and redirects, every error code, strict response checks and that no key or secret shows up in any message.

## Known limits

Nothing here makes the application impossible to attack. These gaps are known and accepted for now:

- **Rate limits live in memory, per backend.** They restart at zero when the backend restarts, and two backends
  would each count separately. The windows are fixed minutes, so a client can make up to twice the limit across the
  turn of a minute. Behind a reverse proxy every client has the proxy's address: the proxy must then do the
  per-address limiting, or `RATE_LIMIT_FAILED_CHECKS_PER_MINUTE` must be raised.
- Audit row ids are one sequence for all teams, so the gap between two of a team's ids shows roughly how much
  other teams did in between. Acceptable on one computer; a hosted service should return opaque ids instead.
- Creating and revoking API keys is not in the audit log yet: `audit_logs.action` allows only key-pair actions.
- Anyone who can run the backend on the computer can issue a key with `issue-api-key`. That person can also
  read the database and `.env`, so this gives them nothing new.
- **No TLS.** Traffic is plain HTTP, which is acceptable only because it never leaves the computer.
  Encapsulate and decapsulate return the shared secret in the response, so TLS is required before the API
  leaves the computer.
- A client that announces a body and then stays silent occupies one server thread for up to 10 seconds
  (20 seconds if it stalls in the middle of a body). A reverse proxy is the usual protection.
- Requests that Tomcat itself refuses before they reach the application (malformed HTTP, for example two
  Host headers) get Tomcat's plain error page. It contains only the status line, but not the security headers.
- The Host check protects against browsers. A program on the same computer can send any Host header it likes,
  which is why the listen address and, later, API keys matter.

## Before the API is reachable from another computer

All of these, not some of them:

1. API-key authentication on every endpoint except the health check (done in task 3.3).
2. TLS, directly or through a reverse proxy. Private keys and shared secrets travel in responses.
3. `SERVER_ADDRESS` and `ALLOWED_HOSTS` set to the real address and host name, nothing wider.
4. `API_DOCS_ENABLED=false`.
5. Rate limiting, at least on failed API-key checks (done in KAN-31, but see the note on reverse proxies above).
6. A strong, unique database password and a database that is not reachable from outside.

## Rules for the next tasks

### Key generation and storage (tasks 3.1 and 3.2)

- Done in tasks 3.1 and 3.2: private keys are encrypted before they are stored (AES-256-GCM, a fresh random
  12-byte nonce per record), and the master key comes from `MASTER_KEY`, never from the repository or the database.
- Private keys, shared secrets and API keys are never written to logs, error messages or the audit log.
  The audit log records who did what to which key id.
- Reload stored keys through `KeyFactory`. Bouncy Castle 1.87 rejects a stored private key that was altered
  (`corruptedStoredPrivateKeyIsRejected`). Treat that as an error, never fall back to "best effort".
- Every database read of a key includes the owner: look up by key id **and** account id, and answer 404
  for a key that belongs to someone else. Never trust an account id sent by the client.
- Use Spring Data methods or `@Query` with parameters. Never build SQL by joining strings.

### API keys (task 3.3)

Done in task 3.3. Keep these true:

- Generate API keys with `SecureRandom`, at least 32 random bytes. Show a key once, store only its SHA-256 hash.
- Compare hashes with `MessageDigest.isEqual` (constant time).
- Accept the key in a request header only, never in the URL (URLs end up in logs and browser history).
- Deny by default: a request without a valid key gets the same 401 whatever the reason.
- A custom header also protects against cross-site requests, because browsers will not send it without CORS permission.

### Encapsulation, decapsulation and signatures (task 3.4 and later)

Done for encapsulation and decapsulation in task 3.4 and for signatures in KAN-29. Keep these true:

- Decode Base64 strictly and check sizes before calling the library: an ML-KEM-1024 public key is 1568 bytes
  (1590 in the X.509 encoding the API returns),
  a ciphertext 1568 bytes, an ML-DSA-65 signature 3309 bytes. A ciphertext of the wrong length makes
  Bouncy Castle throw an unchecked `IllegalArgumentException`, which would otherwise become a 500.
- Parse every key a client sends with `KeyFactory.getInstance("ML-KEM-1024", "BC")`. The general `"ML-KEM"`
  key factory also accepts ML-KEM-512 and ML-KEM-768 keys, so with it a client could quietly lower the
  security level (`weakerParameterSetIsAcceptedByTheGeneralKeyFactory`, `mlKem1024KeyFactoryRejectsWeakerParameterSets`).
  Signature keys are parsed the same way, with the `"ML-DSA-65"` key factory and signature only.
- Answer every kind of bad crypto input with the same 400 and a fixed error code. Never pass on the library's
  exception message.
- Decapsulation of a tampered ciphertext does not fail, it returns a different secret (FIPS 203 implicit
  rejection). Keep it that way: do not add a "ciphertext was invalid" answer, which would tell an attacker
  something the algorithm is designed to hide.
- Create one `SecureRandom` and let it seed itself. Never seed it with fixed or time-based values.
- Signing is hedged (randomized), the FIPS 204 default, so the same message gives a new signature every time.
  The deterministic variant is used only in the NIST test vectors.
- A signature that does not match is an answer (`"valid": false`), not an error, and is audited as a successful
  check. Only a malformed request gets the 400.
- Messages are 1 byte to 32 KiB and context strings at most 255 bytes (FIPS 204), so a request with a message,
  a signature and a context stays under the 64 KiB body limit. Nothing a client sends to sign or verify is
  stored or logged.

### Key rotation and revocation (KAN-30)

Done in KAN-30. Keep these true:

- Check the key pair's state on every operation, in `KeyPairAccess`: active allows everything; rotated allows only
  decapsulate and verify, and only before `grace_period_ends_at`; revoked allows nothing. New operations must be
  added to that one rule, not checked on their own.
- Change a key pair's status only inside `KeyPairStore`, with the row locked (`findForUpdate`) and the audit entry in
  the same transaction, so a status change and its record are written together or not at all.
- Generate the replacement key before taking the lock, so the lock is held for a few writes only. A replacement that
  loses the race is never saved.
- Revocation cannot be undone, and revoked rows are kept: the audit log refers to them.

### Audit log API (KAN-32)

Done in KAN-32. Keep these true:

- Every audit query includes the caller's account id. Another team's key pair gives an empty page and another
  team's cursor the same 400 as a cursor that does not exist, so neither tells whether it exists.
- The API reads the log only. No endpoint changes or deletes audit rows, and reading the log is not itself audited,
  so paging cannot fill it.
- Every query parameter is checked strictly (one spelling per value) and any bad value gets the same 400
  `invalid_request`, which repeats nothing from the request.
- Never add messages, signatures, shared secrets or key material to audit rows: the API returns every column.

### SDK (task 5.1 and later)

Done in task 5.1. Keep these true when the SDK grows:

- Send the API key in the `X-API-Key` header only. Never log it, print it or put it in an exception message,
  and never repeat a refused setting in an error.
- Refuse plain `http://` to any other computer, and never follow redirects.
- Check every answer: exact lengths (32-byte secret, 1568-byte ciphertext), the key pair id asked for, and a
  size limit. Never copy the response body or a parser message into an exception.
- Return secrets in an object that hides itself and can be wiped, never as a plain `byte[]` or `String`.

### Dashboard (task 2.3 and later)

- Allow exactly the dashboard's origin in CORS. Never `*`.
- Keep the content security policy of the production build (`vite.config.js`) free of `'unsafe-inline'`,
  `'unsafe-eval'` and other sites. If a new feature needs another source, add only that source. The end-to-end
  test fails on any violation, so a change that breaks the policy is found at once.
- No API key in the React source or in any committed file. Done in task 4.2: the user types the key in, it is kept
  in memory (or, if they choose, in `sessionStorage` until the tab closes) and sent only in the `X-API-Key` header.
- Done in KAN-33: what is typed into Sign and Verify stays in component state only, never in storage, the address
  or a log. Revoking asks for a second, explicit confirmation. The page offers only what a key pair's state allows,
  but the API's checks stay the rule: the page must never be the only check.

## Reporting a problem

Please do not open a public issue for a security problem. Use "Report a vulnerability" on the repository's
Security tab if it is available, or tell the team directly.

The key-handling security review (KAN-22) and its evidence are in [docs/security-review.md](docs/security-review.md).
