# QuantumLock Java SDK (KAN-21)

Use QuantumLock from Java or Spring Boot without writing any HTTP or cryptography code. Java 21, Spring Boot 4.2.0.

| Module | What it is |
|---|---|
| `quantumlock-client` | Plain Java client. Needs only the JDK and Jackson, no Spring. |
| `quantumlock-spring-boot-starter` | Set two properties and a ready `QuantumLockClient` is injected. |
| `sample-app` | Agrees on a shared secret with ML-KEM-1024, then signs and verifies a message with ML-DSA-65 (the KAN-25 and KAN-29 demo). |

## Spring Boot in a few lines

Add the starter (after `./mvnw install` in this folder, until the SDK is published):

```xml
<dependency>
    <groupId>com.quantumlock</groupId>
    <artifactId>quantumlock-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Set the two settings. Keep the key out of files in git and read it from the environment:

```yaml
quantumlock:
  api-url: http://127.0.0.1:8080
  api-key: ${QUANTUMLOCK_API_KEY}
```

Inject the client and use it:

```java
KeyPairInfo keyPair = quantumLock.generateKeyPair(Algorithm.ML_KEM_1024);

try (Encapsulation sent = quantumLock.encapsulate(keyPair.keyPairId());
     SharedSecret received = quantumLock.decapsulate(keyPair.keyPairId(), sent.ciphertext())) {
    boolean match = sent.sharedSecret().matches(received);   // true
}
```

The application refuses to start when the URL or key is missing or invalid. Optional settings:
`quantumlock.connect-timeout` (default 5s) and `quantumlock.request-timeout` (default 30s).

## Plain Java

```java
try (QuantumLockClient quantumLock = QuantumLockClient.builder()
        .apiUrl("http://127.0.0.1:8080")
        .apiKey(System.getenv("QUANTUMLOCK_API_KEY"))
        .build()) {
    List<KeyPairInfo> keyPairs = quantumLock.listKeyPairs();
}
```

## Calls

| Method | API call | Returns |
|---|---|---|
| `generateKeyPair(Algorithm)` | `POST /api/v1/keys/generate` | `KeyPairInfo` (id, algorithm, status, public key, time) |
| `listKeyPairs()` | `GET /api/v1/keys` | all key pairs of the account, newest first |
| `getKeyPair(UUID)` | `GET /api/v1/keys/{id}` | one key pair |
| `encapsulate(UUID)` | `POST /api/v1/keys/{id}/encapsulate` | `Encapsulation`: 1568-byte ciphertext and a `SharedSecret` |
| `decapsulate(UUID, byte[])` | `POST /api/v1/keys/{id}/decapsulate` | `SharedSecret` |
| `sign(UUID, byte[] message)` and `sign(UUID, byte[] message, byte[] context)` | `POST /api/v1/keys/{id}/sign` | the 3309-byte ML-DSA-65 signature |
| `verify(UUID, byte[] message, byte[] signature)` and `verify(..., byte[] context)` | `POST /api/v1/keys/{id}/verify` | `true` or `false` |
| `rotate(UUID)` and `rotate(UUID, Integer gracePeriodDays)` | `POST /api/v1/keys/{id}/rotate` | the new `KeyPairInfo` |
| `revoke(UUID)` | `POST /api/v1/keys/{id}/revoke` | the revoked `KeyPairInfo` |

The shared secret is the 32-byte FIPS 203 ML-KEM value K, the same as any FIPS 203 library gives. Use it as a key,
for example for AES-256-GCM. No private key is ever returned: it stays on the server, stored encrypted.

Rotation replaces a key pair with a new one of the same algorithm. The old one's `status()` becomes `ROTATED`,
`replacedBy()` names the new one, and until `gracePeriodEndsAt()` it can still decapsulate and verify (data made
for it before can still be read), but not encapsulate or sign. Leave out the grace period for the server's default
(7 days unless it is set up otherwise). `revoke` stops a key pair for good and cannot be undone. Using a key pair in
a state that does not allow the call throws `QuantumLockApiException` with `key_not_active`.

Signatures follow FIPS 204 (ML-DSA-65), so anyone can also check one with the public key and any FIPS 204 library.
A message is 1 byte to 32 KiB. The optional context string (at most 255 bytes, for example `"invoices"`) ties a
signature to one purpose: it only verifies with the same context. Leaving it out is the same as an empty context.
A signature that does not match gives `false`, not an exception. Wrong sizes throw `IllegalArgumentException`
before any call is made.

```java
byte[] context = "invoices".getBytes(StandardCharsets.UTF_8);
byte[] signature = quantumLock.sign(keyPairId, message, context);
boolean valid = quantumLock.verify(keyPairId, message, signature, context);   // true
```

## Errors

Every failed call throws a subclass of `QuantumLockException`:

| Exception | When | Example |
|---|---|---|
| `QuantumLockApiException` | The API refused the call. `status()` and `errorCode()` tell why. | 401 `unauthorized`, 404 `key_not_found`, 400 `wrong_key_type`, 400 `key_not_active` |
| `QuantumLockTransportException` | The API could not be reached or the call timed out. Retrying may help. | backend not running |
| `QuantumLockResponseException` | The answer was not what QuantumLock sends: a redirect, a missing field, a value of the wrong length, another key pair's id or a body over 16 MiB. | a proxy answering instead of the API |

A wrong setting (bad URL, bad key format, zero timeout) throws `IllegalArgumentException` when the client is built.

## Security

- The API key is sent only in the `X-API-Key` header, never in a URL. It never appears in `toString()`, in an
  exception message or in a log: the client does not log at all, and error messages never repeat a refused value.
- Plain `http://` is refused unless the API runs on the same computer (`localhost`, `127.0.0.1` or `::1`).
  Anywhere else the key and the shared secrets must travel over `https://`.
- Redirects are never followed, so the key cannot be sent on to another server.
- Every call has a connect timeout and a total timeout. Response bodies over 16 MiB are refused.
- Responses are checked strictly. A shared secret must be 32 bytes, a ciphertext 1568 bytes, a signature 3309 bytes
  and the key pair id
  in the answer must be the one asked for. Error codes are taken from the response only if they are plain words
  (`[a-z_]`), and no exception ever carries the response body.
- `SharedSecret` prints as `hidden`, compares in constant time with `matches` and is wiped (overwritten with
  zeros) by `destroy()` or `close()`. The raw response bytes are wiped too.
- Known limits: Java strings cannot be wiped, so the API key and the Base64 text of a secret stay in memory until
  the garbage collector reclaims them. As with the backend's own limit, this matters only to someone who can
  already read the process memory.

## Run the sample app

With the backend running (see the main README) and an API key you issued:

```bash
cd sdk
./mvnw -B package -DskipTests                  # Windows: mvnw -B package -DskipTests
QUANTUMLOCK_API_URL=http://127.0.0.1:8080 QUANTUMLOCK_API_KEY=<your key> \
  java -jar sample-app/target/quantumlock-sample-app-0.1.0-SNAPSHOT.jar
```

It prints the two new key pairs' ids, `Shared secrets match: true` and
`Signature valid: true. Changed message accepted: false`, and exits with 0. It exits with 1 if a call fails, the
secrets differ, the signature is not valid or the changed message is accepted. On Windows, set the two variables with `set NAME=value` first.

## Tests

```bash
cd sdk
./mvnw verify                                  # Windows: mvnw verify
```

139 tests, no backend or database needed:

- `QuantumLockClientTest`: every call against a stand-in API on 127.0.0.1. What is sent (method, path, header,
  body), every error code, rotation and revocation answers, redirects not followed, wrong lengths, other ids, malformed or oversized bodies,
  timeouts, an API that is down, and no key or secret in any `toString`
- `ClientSettingsTest`: which URLs and keys are accepted, and that a refused value is never repeated
- `SharedSecretTest`: copies, wiping, constant-time comparison
- `QuantumLockAutoConfigurationTest`: the Spring setup, missing or wrong settings, your own client bean
- `SampleApplicationTest`: the whole sample app, including a mismatch, an accepted changed message and a refused key

GitHub Actions runs them on every change to `sdk/` (`.github/workflows/sdk-ci.yml`).

The end-to-end test (`e2e/run.sh`, KAN-25) also runs the sample app against a real backend and PostgreSQL on every
pull request, checks that both shared secrets match, that the signature is valid and a changed message is refused,
that a wrong key is refused with 401 and that the key never appears in the output, and then checks the key pairs and
their audit rows in the database.

## Not included yet

- Encapsulating on the caller's own computer (it would need Bouncy Castle in every application).
- Publishing to Maven Central. Use `./mvnw install` for now.
