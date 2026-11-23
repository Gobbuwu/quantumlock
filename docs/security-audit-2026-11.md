# Security and quality audit, November 2026 (KAN-35)

Audit of QuantumLock on main at `dff69b6` (KAN-34), run on Nov 23, 2026. Written with the help of Claude AI.

## Summary

No high or medium security issue was found in the application. Every test suite passed on Linux and on Windows, all 40 black-box API checks passed, team data stayed isolated and the rate limits behaved as documented. The audit found 3 low-severity code issues (the Claude Security scan), 3 repository settings to fix, 3 accessibility issues in the dashboard and a handful of small documentation and robustness items. Each finding below has a proposed fix; nothing was changed by the audit itself.

| Severity | Count | Findings |
|---|---|---|
| Medium | 3 | S1 private vulnerability reporting off, S2 branch protection requires only the backend check, A1 audit table not reachable by keyboard |
| Low | 10 | F1, F2, F3 from the scan, P1 any API key can create more API keys, P2 no limit or paging on key lists, P3 browser offers to save the API key, S3 Dependabot security updates off, A2 page title outside a landmark, A3 skip link leaves the Audit log page, D1 issue-api-key comment shows the wrong syntax |
| Info | 10 | D2, D3, I1 to I8 |

## What was checked

| # | Check | How | Result |
|---|---|---|---|
| 1 | Claude Security scan | Multi-agent scan at medium depth: 7 components, 22 researchers, a 3-voter panel per candidate | 3 low findings, each confirmed 3 of 3 |
| 2 | Dependencies | npm audit, Dependabot alerts, Maven versions plugin, Maven Central metadata | 0 known vulnerabilities; all pinned versions current except springdoc (I3) |
| 3 | Secrets and history | gitleaks over the tree and every commit, author list | 1 false positive (the public test master key in `application-test.yml`); no personal email in history |
| 4 | CI workflows | zizmor, manual review | No real findings; actions pinned to commits, read-only permissions |
| 5 | SECURITY.md conformance | Each protection row checked by test or by a black-box request | All rows hold |
| 6 | OWASP API Security Top 10 (2023) | Mapped below | P1, P2 and F2 noted |
| 7 | Black-box run | 40 scripted requests against a fresh backend, plus rate limits, idle timeout and a second team | 40 of 40 passed |
| 8 | Requirements trace | Scope in the Final Design Proposal against the code and Jira | Core, stretch and later-phase items all present (I4) |
| 9 | Regression | Every suite on main | Backend 339, SDK 165, dashboard 89 tests, end-to-end and both demo scripts passed |
| 10 | Walkthrough on Windows | Quang on the laptop, dashboard and Swagger UI, 10 steps | All passed; A3 found with the keyboard |
| 11 | Windows runs | Suites from cmd.exe; demo by double-click, Command Prompt and PowerShell 7 | All passed |
| 12 | README commands | Followed on a fresh database | All worked; D1 found in a code comment |
| 13 | Accessibility | axe-core 4.14.1, three views, desktop and phone width, dark and light | A1, A2 |
| 14 | Performance | 20 timed calls per operation on a local backend | Median 8 to 10 ms, worst 85 ms (first call) |
| 15 | Code quality | TODO and debug-print search, file sizes, version freshness | Clean |
| 16 | Demo dry run | Quang, informal run of the walkthrough as a demo | Fast, no hiccups; Swagger UI unfamiliar (I4). A timed run follows the demo script (KAN-27) |

## Findings

### Security scan (check 1)

The full scan report is kept outside the repository. Summary:

- **F1 (low). The PowerShell setup creates `backend/.env` before restricting it.** `demo/common.ps1:189` (`New-SecretFile`) creates the file with inherited permissions and only then applies the ACL or `chmod 600`. Another local account that opens the file in that moment keeps a valid handle and can later read `DB_PASSWORD` and `MASTER_KEY`. The bash setup avoids this with `umask 077`. Fix: create the file with its restricted ACL or mode in one step, then write.
- **F2 (low). Failed-key lockout is counted per address, and every local client shares 127.0.0.1.** `ApiKeyAuthFilter.java:76`. Twenty requests without a key, from any local program or from a hostile web page loading images from `127.0.0.1:8080`, lock out the dashboard, SDK and demo for a minute, valid keys included. The audit's own tests tripped this by accident. Fix: never let the address lockout refuse a request with a valid key (check the key first and count only failures), and refuse cross-site requests before they count.
- **F3 (low). `e2e/run.sh:67` passes the test database password to psql on the command line,** where other users of a shared machine can read it in the process list. Only the throwaway `quantumlock_e2e` role is exposed. Fix: use `\getenv` as `demo/setup.sh` does.

### Product (checks 6 and 7)

- **P1 (low). Any API key can create unlimited new API keys.** `POST /api/v1/api-keys` needs only a valid key, so a stolen key can mint replacements and survive its own revocation. Creating and revoking keys is also not in the audit log (a known limit in SECURITY.md). Fix for a hosted version: a separate admin permission for key management, and audit rows for key creation and revocation.
- **P2 (low). No limit on key pairs or API keys per team, and the list endpoints return everything.** `GET /api/v1/keys` and `GET /api/v1/api-keys` have no paging. The per-key rate limit bounds the growth, but a long-lived team would get slow lists. Fix: a per-team cap and paging like the audit log's.
- **P3 (low). The browser offers to save the API key as a password.** Seen on the laptop: the dashboard's key field is a password field in a form, so the browser's password manager offers to store it. Fix: tell the browser it is not a login (for example `autocomplete="off"` on the form plus a non-password field that is masked another way); needs testing in Chrome, Firefox and Opera.

### Repository settings (check 3 and 4)

- **S1 (medium). Private vulnerability reporting is off** while SECURITY.md asks reporters to use it. Fix: Settings, Code security, Private vulnerability reporting, Enable.
- **S2 (medium). The `protect-main` ruleset requires only the backend `build` check,** so a pull request that breaks the dashboard, demo or end-to-end workflow can still be merged. Those workflows have path filters, so they cannot simply be required as they are. Fix: an always-running summary job that passes when the relevant workflows pass or were skipped, and require that job.
- **S3 (low). Dependabot security updates are off.** Version updates and alerts are on. Fix: Settings, Code security, Dependabot security updates, Enable.

### Accessibility (check 13)

- **A1 (medium, WCAG 2.1.1). On a narrow screen the audit log table scrolls sideways but cannot be reached by keyboard,** and the Key pair column is cut off. axe rule `scrollable-region-focusable` on `.table-frame`. Fix: give the scroll frame `tabIndex=0`, `role="region"` and a label, or stack the columns on narrow screens.
- **A3 (low). "Skip to content" on the Audit log page switches to the Key pairs page.** The skip link goes to `#main`, and the hash router (`App.jsx`, `parseView`) treats any hash other than `#audit` as the key list. Found by Quang with the keyboard on the laptop and reproduced in the browser test. On the Key pairs page it works: the next Tab lands in the page content. Fix: have the skip link move focus to `<main>` (with `tabIndex=-1`) without changing the address.
- **A2 (low). The page title sits outside any landmark** (axe `region`, best practice) on every view. Fix: move `.page-title` inside `<main>`.

### Documentation and scripts (checks 11 and 12)

- **D1 (low). The comment in `IssueApiKeyCommand.java:14` shows `issue-api-key,<account id>`.** Spring Boot's Maven plugin splits arguments on spaces, so that form starts the web server instead of issuing a key. The space form (`"issue-api-key <account id>"`) works. Fix: correct the comment, and have the command fail when the first argument starts with `issue-api-key` but is not exactly it.
- **D2 (info). `run-tests.cmd` calls `mvnw.cmd` without a path,** which fails where Windows has `NoDefaultCurrentDirectoryInExePath` set. Fix: call `"%~dp0backend\mvnw.cmd"`.
- **D3 (info). Swagger UI shows a lock on the health check,** which needs no key, and the API description lists ML-DSA-65 under key exchange. Fix: mark the health endpoint as public in the OpenAPI setup and reword the description.

### Information only

- **I1.** The demo's "Press any key" pause also appears when `start.cmd` is run from a PowerShell window. Cosmetic.
- **I2.** Swagger UI is on by default (`API_DOCS_ENABLED=true`). Documented and acceptable on localhost; switch it off before hosting.
- **I3.** springdoc 3.2.1 is out (pinned 3.2.0). Dependabot will propose it.
- **I4.** The dashboard has no key exchange buttons; key exchange is available through Swagger UI and the SDK. The Final Design Proposal does not require it in the dashboard. Quang found Swagger UI unfamiliar in the walkthrough, so the demo script (KAN-27) should either rehearse the Swagger steps or show key exchange through the SDK sample app.
- **I5.** CI runs on Linux only; Windows is covered by the manual laptop runs in this audit. Since KAN-43 (after this audit), CI also runs every check on macOS.
- **I6.** Jira epics KAN-1 to KAN-8 are still To Do although most of their tasks are Done.
- **I7.** A `ql_try` test database may remain on the laptop from KAN-34 testing.
- **I8.** The dashboard cannot create, list or revoke API keys; that is done through Swagger UI or the API. Fine for the capstone, worth adding for a hosted version.

## Limits of this audit

- The code scan reads the code; it ran nothing. The black-box run and the walkthrough cover what was executed.
- osv-scanner and the GitHub advisory database could not be reached from the test machine; npm audit, Dependabot alerts and Maven Central were used instead.
- Windows 11 (laptop) and Linux were tested. macOS was not. Since KAN-43 (after this audit), GitHub Actions runs every test suite, the demo scripts and the end-to-end test on macOS too.
- Test code was not security reviewed, only run.

## OWASP API Security Top 10 (2023)

| Risk | Result |
|---|---|
| API1 Broken object level authorization | Pass. A second team got 404 on every key pair route, an empty audit page and 404 revoking the first team's API key |
| API2 Broken authentication | Pass, with P1 |
| API3 Broken object property level authorization | Pass. Responses carry only public fields; requests are fixed records |
| API4 Unrestricted resource consumption | Mostly pass: 64 KiB body limit, 10 s idle timeout, rate limits. P2 and F2 noted |
| API5 Broken function level authorization | P1: no separate permission for key management |
| API6 Unrestricted access to sensitive business flows | Not applicable |
| API7 Server side request forgery | Not applicable: the backend fetches no URLs |
| API8 Security misconfiguration | Pass: security headers on every response, no CORS, loopback only, errors without detail |
| API9 Improper inventory management | Pass: one versioned API (`/api/v1`) with generated docs |
| API10 Unsafe consumption of APIs | Pass: the SDK refuses plain HTTP off the computer and never follows redirects |

## Black-box run (check 7)

Fresh database set up with the README's commands, backend started with `mvnw spring-boot:run`, first key from `issue-api-key`. All 40 checks passed, among them:

- 401 without a key on every protected route; 400 for a foreign Host header; no CORS header for a foreign origin; 405 for TRACE.
- 400 with a bare error code for malformed JSON, an unknown algorithm, an out-of-range audit limit and an injection attempt in a filter; 404 for an unknown or malformed id; 413 for a 70 KB body.
- Key exchange round trip; a tampered ciphertext gives a different secret (implicit rejection); a short ciphertext gives 400; signing with an ML-KEM key gives 400.
- Sign and verify, tampered message verifies false; rotation with grace 0 stops the old key; grace over 90 gives 400; `"7"`, `7.5` and `true` as grace give 400; a revoked key can neither sign nor verify.
- A new API key works, does not appear in the list, and stops working once revoked.
- The audit log lists every action and contains no secret.
- 120 requests per minute per key, then 429 with `Retry-After`; 20 failed key checks per minute per address, then 429.
- The backend listens on 127.0.0.1 only and drops a silent connection after 10 seconds.

## Performance (check 14)

Local backend and PostgreSQL 16 on the cloud test machine, 20 calls each (1 for decapsulate and verify):

| Operation | Median | Worst |
|---|---|---|
| Generate ML-KEM-1024 | 7.9 ms | 84.7 ms (first call) |
| Encapsulate | 7.8 ms | 42.5 ms |
| Decapsulate | 25.4 ms | 25.4 ms |
| Generate ML-DSA-65 | 7.9 ms | 19.1 ms |
| Sign | 10.1 ms | 28.1 ms |
| Verify | 17.5 ms | 17.5 ms |

## Proposed fix tasks

Fixes are due by Dec 4, before the code freeze on Dec 10. Each needs Quang's OK before a Jira task is created.

1. F2: lockout never blocks a valid key; cross-site requests refused before counting.
2. F1 and F3: create secrets files restricted from the start; e2e password via `\getenv`.
3. A1, A2 and A3: keyboard access to the audit table, title inside the main landmark, skip link that keeps the page.
4. P3: stop the browser offering to save the API key.
5. S1, S2 and S3: repository settings and a required summary check.
6. D1, D2 and D3: comment, `run-tests.cmd` path, Swagger health lock and description.
7. P1 and P2: recorded as known limits in SECURITY.md for the capstone; built only for a hosted version.
