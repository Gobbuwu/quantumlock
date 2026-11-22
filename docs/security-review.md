# Key-handling security review (KAN-22)

Reviewed on Nov 22, 2026 against `main` at commit 4c08097. Written with the help of Claude AI.

**Scope (from the Jira task):** private keys are never returned or logged in plaintext, API keys are stored only
as hashes and no secret is in the repository. The review also covers shared secrets, the configuration promised in
`SECURITY.md`, the dashboard's API-key storage and the dependencies.

**Result:** every check passes. Three gaps were found and are fixed in the same change (G1 to G3 below). None of them exposes a key today. No review can
prove the absence of every flaw. This one shows that the protections listed in `SECURITY.md` exist in the code and
are covered by tests.

## 1. Private keys

| Check | Result | Evidence |
|---|---|---|
| The plaintext private key exists only inside key generation and decapsulation | Pass | `KeyService.java:58` (encoded and encrypted at once), `KeyExchangeService.java:106` (decrypted for one decapsulation) |
| The plaintext bytes are wiped after use, on success and on failure | Pass | `finally` blocks: `KeyService.java:68`, `KeyExchangeService.java:113` |
| No response contains a private key, encrypted or not | Pass | `KeyPairResponse` has id, algorithm, status, public key and time only. Tested in `KeyApiTest` |
| No log line, error message or audit row contains key material | Pass | The three `log.error` calls log the exception type only (`KeyService.java:64`, `KeyExchangeService.java:197`, `ApiKeyAuthFilter.java:73`). Error bodies are fixed codes (`ApiExceptionHandler`, `spring.web.error.*`). `KeyExchangeApiTest` captures the log output and checks that no secret appears |
| Stored private keys are encrypted and bound to their row | Pass | AES-256-GCM with a fresh nonce, key pair id and account id as associated data (`PrivateKeyEncryptor`). Tampering, a moved ciphertext and a wrong master key are rejected (`PrivateKeyEncryptorTest`) |
| Edge case: DEBUG logging | Pass | Response records that carry secrets override `toString()` to hide them (`EncapsulateResponse`, `DecapsulateResponse`, `NewApiKeyResponse`, `IssuedApiKey`), so Spring's DEBUG "Writing [...]" lines show no secret. Hibernate bind logging could only show the encrypted private key, never the plaintext |
| Edge case: Bouncy Castle exception messages | Pass | They are never logged or returned. Only the exception's class name is logged |

**Known limit (accepted):** the Bouncy Castle `PrivateKey` object made from the decrypted bytes cannot be wiped, so a
copy stays in memory until Java's garbage collector reclaims it. Every Java application has this limit. It matters only
to someone who can already read the server's memory.

## 2. API keys and shared secrets

| Check | Result | Evidence |
|---|---|---|
| Keys are random and stored only as a hash | Pass | 32 bytes from `SecureRandom`, SHA-256 stored (`ApiKeys`). Tested in `ApiKeyAuthTest`, `ApiKeysTest` |
| Comparison is constant-time | Pass | `MessageDigest.isEqual` (`ApiKeys.java:61`) |
| A key is accepted from the `X-API-Key` header only, never the URL | Pass | `ApiKeyAuthFilter.java:86`. Tested in `ApiKeyAuthTest` |
| Every failure gives the same 401 | Pass | `ApiKeyAuthTest` |
| Shared secrets are never stored or logged | Pass | They exist only in the response. `KeyExchangeApiTest` checks the logs |

## 3. Master key and repository

| Check | Result | Evidence |
|---|---|---|
| No secret anywhere in git history | Pass | All 16 branches and every commit were scanned for `.env` files, key files, passwords, API keys, private-key blocks and 32-byte Base64 values. The only hit is the public test master key in `application-test.yml` (bytes 0 to 31, marked as a test value) |
| `.env` and key files are ignored by git | Pass | `.gitignore`: `.env`, `.env.*`, `*.env`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.keystore` |
| GitHub secret scanning | Pass | Secret scanning and push protection are on (repository settings) |
| The backend refuses to start without a valid `MASTER_KEY` | Pass, but untested (**G1**) | `EncryptionConfig` refuses a missing, non-Base64 or wrong-length key and never prints the value. No automated test covers this |
| The build tools are verified before running | Gap (**G2**) | The Maven wrapper downloads Maven with no checksum (`distributionSha256Sum` is missing in `maven-wrapper.properties`) |

## 4. Configuration

Each row of the "What is in place" table in `SECURITY.md` was checked against the code and its test: localhost-only
listening and allowed hosts (`SecureDefaultsTest`, `ApiSecurityTest`), size limits (`SecurityFiltersTest`), the 10-second connection timeout (set in `application.yml`, no test),
error bodies with no internals (`SecureDefaultsTest`), security headers and no CORS (`ApiSecurityTest`), and switching off
the API docs (`ApiDocsDisabledTest`). All pass. The application has no Actuator endpoints.

## 5. Dashboard

| Check | Result | Evidence |
|---|---|---|
| The API key is kept in memory, or in sessionStorage only if the user opts in | Pass | `dashboard/src/api/apiKey.js`. Tested in `apiKey.test.js`, `ApiKeyEntry.test.jsx` |
| Never in localStorage, a cookie, the URL or the source | Pass | No use of `localStorage`, `document.cookie` or URL parameters in `dashboard/src` |
| No raw HTML injection | Pass | No `dangerouslySetInnerHTML` or `eval` |
| Content security policy | Recommendation (**R1**) | The dashboard has no CSP. It runs only on 127.0.0.1 in development, where Vite needs inline scripts. Add a CSP when a production build is served |

## 6. Dependencies

| Check | Result | Evidence |
|---|---|---|
| Known vulnerabilities in backend dependencies | Pass | GitHub Dependabot alerts: 0 open, 0 ever (checked Nov 22). OSV could not be queried from the review environment, so that second source is unverified |
| Known vulnerabilities in dashboard dependencies | Pass | `npm audit`: 0 vulnerabilities |
| The 8 open Dependabot PRs | Gap (**G3**) | All are tooling updates (Maven wrapper, GitHub Actions, test libraries), none are security fixes. Each passed CI, but against a `main` that is 4 to 8 commits older. #2 updates `actions/checkout` in the backend workflow only, not the dashboard one. #1 re-stores `mvnw.cmd` with different line endings (no content change) |

## Gaps and fixes

| Id | Gap | Fix in this change | Result |
|---|---|---|---|
| G1 | `MASTER_KEY` startup checks have no test | New `EncryptionConfigTest` (8 tests): a missing key stops the application, and an empty, blank, non-Base64, 16-byte or 36-byte key is refused with an error that never contains the value. A valid key works | Passes. When the error message is changed to include the value, the test fails |
| G2 | Maven is downloaded with no checksum | `distributionSha256Sum` added to `maven-wrapper.properties`. Its value is the SHA-256 of the Maven 3.10.1 zip whose SHA-512 matches both Maven Central and archive.apache.org | With the right value the wrapper downloads and runs Maven 3.10.1. With one wrong character it stops with "Failed to validate Maven distribution SHA-256" and exit code 1. Checked with `mvnw` on Linux. `mvnw.cmd` (Windows) runs the same check (its lines 138 to 145) but was not run here: unverified on Windows |
| G3 | Stale and partly inconsistent Dependabot PRs | The 8 updates applied together on current `main`: Maven 3.10.1, `actions/checkout` 7.0.1 in both workflows, `actions/setup-java` 6.3.0 (both still pinned to the commit of their release tag, checked with `git ls-remote`), and the 5 dashboard test tools | 176 backend tests and 29 dashboard tests pass, the dashboard build works and `npm audit` finds 0 vulnerabilities |
| R1 | No dashboard CSP | Deferred until a production build exists | Open |
