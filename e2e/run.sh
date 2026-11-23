#!/usr/bin/env bash
# End-to-end test of QuantumLock on a fresh setup (KAN-20, KAN-24, KAN-25, KAN-29, KAN-30).
#
# Builds everything from nothing: a new PostgreSQL database and user, the schema and demo data, the backend,
# one API key and the dashboard. A real browser then uses the dashboard (dashboard/e2e/), the Java SDK's sample app
# (sdk/sample-app) agrees on a shared secret and signs and verifies a message through the API, and the database is
# checked directly at the end.
#
# Needs: Java 21, Node 22 or later, psql, openssl, curl, and a PostgreSQL server reachable with a superuser
# through the usual PGHOST, PGPORT, PGUSER and PGPASSWORD variables. The script creates its own database and
# user (both named quantumlock_e2e) and refuses to run if they already exist, so it never touches another
# database. It removes both when it finishes.
#
# Secrets: the database password, MASTER_KEY and API key are random for every run, are never printed and are
# deleted at the end. On GitHub Actions they are also masked in the log.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_NAME=quantumlock_e2e
BACKEND_PORT="${E2E_BACKEND_PORT:-18080}"
DASHBOARD_PORT="${E2E_DASHBOARD_PORT:-15173}"
WORK="$(mktemp -d)"
chmod 700 "$WORK"
BACKEND_PID=""
DASHBOARD_PID=""
CREATED_DB=false

log() { printf '\n== %s\n' "$*"; }
mask() { if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$1"; fi; }

cleanup() {
  local status=$?
  [ -n "$DASHBOARD_PID" ] && kill "$DASHBOARD_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "$CREATED_DB" = true ]; then
    psql -X -q -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME" -c "DROP ROLE IF EXISTS $DB_NAME" >/dev/null || true
  fi
  if [ $status -ne 0 ] && [ -f "$WORK/backend.log" ]; then
    echo "Backend log (last 40 lines):"
    tail -n 40 "$WORK/backend.log"
  fi
  rm -rf "$WORK"
  exit $status
}
trap cleanup EXIT

wait_for() { # url, seconds
  for _ in $(seq "$2"); do
    if curl -fsS -o /dev/null "$1" 2>/dev/null; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for $1" >&2
  return 1
}

log "Fresh database"
if [ -n "$(psql -X -tA -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME' UNION SELECT 1 FROM pg_roles WHERE rolname = '$DB_NAME'")" ]; then
  echo "A database or user named $DB_NAME already exists. Remove it first; this script only uses a fresh one." >&2
  exit 1
fi
DB_PASSWORD="$(openssl rand -hex 24)"
MASTER_KEY="$(openssl rand -base64 32)"
mask "$DB_PASSWORD"; mask "$MASTER_KEY"
CREATED_DB=true
psql -X -q -v ON_ERROR_STOP=1 -d postgres -v pw="$DB_PASSWORD" <<'SQL'
CREATE ROLE quantumlock_e2e LOGIN PASSWORD :'pw';
CREATE DATABASE quantumlock_e2e OWNER quantumlock_e2e;
SQL
export PGDATABASE="$DB_NAME"
APP_PGUSER="$DB_NAME"
PGPASSWORD="$DB_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -U "$APP_PGUSER" -f "$ROOT/database/schema.sql"
PGPASSWORD="$DB_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -U "$APP_PGUSER" -f "$ROOT/database/seed.sql"

log "Build the backend"
(cd "$ROOT/backend" && ./mvnw -B -q package -DskipTests)
JAR="$(ls "$ROOT"/backend/target/quantumlock-backend-*.jar | grep -v -- '-plain' | head -n 1)"

# The backend reads these from the environment. It runs from $WORK, so a developer's backend/.env is not used.
export DB_URL="jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$DB_NAME"
export DB_USERNAME="$APP_PGUSER" DB_PASSWORD MASTER_KEY PORT="$BACKEND_PORT"

log "Issue one API key"
(cd "$WORK" && java -jar "$JAR" issue-api-key >"$WORK/issued.txt" 2>"$WORK/issue.err") || { cat "$WORK/issue.err" >&2; exit 1; }
E2E_API_KEY="$(grep -Eo 'ql_test_[A-Za-z0-9_-]{43}' "$WORK/issued.txt" | head -n 1)"
rm -f "$WORK/issued.txt"
[ -n "$E2E_API_KEY" ] || { echo "No API key was issued" >&2; exit 1; }
mask "$E2E_API_KEY"

log "Start the backend on 127.0.0.1:$BACKEND_PORT"
(cd "$WORK" && exec java -jar "$JAR" >"$WORK/backend.log" 2>&1) &
BACKEND_PID=$!
wait_for "http://127.0.0.1:$BACKEND_PORT/api/v1/health" 90

log "Build the dashboard and serve the production build on 127.0.0.1:$DASHBOARD_PORT"
# The production build is what gets demonstrated, and only it carries the content security policy (vite.config.js).
(cd "$ROOT/dashboard" && ./node_modules/.bin/vite build >"$WORK/dashboard-build.log" 2>&1) || { cat "$WORK/dashboard-build.log" >&2; exit 1; }
# Vite is started directly, not through npx, so $DASHBOARD_PID is Vite itself and the cleanup can stop it.
(cd "$ROOT/dashboard" && QL_API_TARGET="http://127.0.0.1:$BACKEND_PORT" exec ./node_modules/.bin/vite preview --port "$DASHBOARD_PORT" --strictPort \
  >"$WORK/dashboard.log" 2>&1) &
DASHBOARD_PID=$!
wait_for "http://127.0.0.1:$DASHBOARD_PORT/" 60

log "Browser test"
(cd "$ROOT/dashboard" && E2E_API_KEY="$E2E_API_KEY" E2E_BASE_URL="http://127.0.0.1:$DASHBOARD_PORT" \
  npx playwright test --config e2e/playwright.config.js)

log "SDK sample app (KAN-25, KAN-29)"
# The Java SDK's sample app runs against the same backend, with the same API key: it generates an ML-KEM-1024 key pair,
# encapsulates and decapsulates, then generates an ML-DSA-65 key pair, signs a message and verifies it and a changed
# copy. It exits with 0 only when both shared secrets match, the signature is valid and the changed copy is refused.
(cd "$ROOT/sdk" && ./mvnw -B -q package -DskipTests)
SAMPLE_JAR="$ROOT/sdk/sample-app/target/quantumlock-sample-app-0.1.0-SNAPSHOT.jar"
run_sample() { # api key; prints the app's output, returns its exit code
  (cd "$WORK" && QUANTUMLOCK_API_URL="http://127.0.0.1:$BACKEND_PORT" QUANTUMLOCK_API_KEY="$1" java -jar "$SAMPLE_JAR" 2>&1)
}
SAMPLE_STATUS=0
SAMPLE_OUT="$(run_sample "$E2E_API_KEY")" || SAMPLE_STATUS=$?
if grep -qF -- "$E2E_API_KEY" <<<"$SAMPLE_OUT"; then
  echo "The sample app printed the API key" >&2
  exit 1
fi
grep 'QuantumLockDemo' <<<"$SAMPLE_OUT" || true
if [ "$SAMPLE_STATUS" -ne 0 ] || ! grep -q 'Shared secrets match: true' <<<"$SAMPLE_OUT" \
    || ! grep -q 'Signature: 3309 bytes. Signature valid: true. Changed message accepted: false' <<<"$SAMPLE_OUT"; then
  echo "The sample app failed (exit code $SAMPLE_STATUS)" >&2
  exit 1
fi
SAMPLE_KEY_ID="$(grep -Eo 'Generated ML-KEM-1024 key pair [0-9a-f-]{36}' <<<"$SAMPLE_OUT" | grep -Eo '[0-9a-f-]{36}$' || true)"
SIGNING_KEY_ID="$(grep -Eo 'Generated ML-DSA-65 key pair [0-9a-f-]{36}' <<<"$SAMPLE_OUT" | grep -Eo '[0-9a-f-]{36}$' || true)"
[ -n "$SAMPLE_KEY_ID" ] && [ -n "$SIGNING_KEY_ID" ] || { echo "The sample app did not report its key pairs" >&2; exit 1; }

# A well-formed key that was never issued must be refused, and the app must say so without showing the key.
WRONG_KEY="ql_test_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n' | cut -c1-43)"
WRONG_STATUS=0
WRONG_OUT="$(run_sample "$WRONG_KEY")" || WRONG_STATUS=$?
if [ "$WRONG_STATUS" -ne 1 ] || ! grep -q 'QuantumLock API error 401 unauthorized' <<<"$WRONG_OUT" \
    || grep -qF -- "$WRONG_KEY" <<<"$WRONG_OUT"; then
  echo "A wrong API key was not refused as expected (exit code $WRONG_STATUS)" >&2
  exit 1
fi
echo "A wrong API key is refused with 401 and exit code 1"

log "Key rotation and revocation (KAN-30)"
# On real PostgreSQL, where the row lock and the audit action check matter: the sample app's ML-KEM key pair is
# rotated with a 1-day grace period, then its replacement is revoked. The API key goes to curl in a file, never on
# the command line. The database upgrade script must also be safe to run on a database that already has it, twice.
printf 'X-API-Key: %s\n' "$E2E_API_KEY" >"$WORK/api-key-header"
api() { # method path [json body]; prints the response body, fails on an HTTP error
  curl -fsS -X "$1" -H @"$WORK/api-key-header" -H 'Content-Type: application/json' ${3:+--data "$3"} \
    "http://127.0.0.1:$BACKEND_PORT$2"
}
ROTATED="$(api POST "/api/v1/keys/$SAMPLE_KEY_ID/rotate" '{"gracePeriodDays":1}')"
REPLACEMENT_ID="$(grep -Eo '"keyPairId":"[0-9a-f-]{36}"' <<<"$ROTATED" | grep -Eo '[0-9a-f-]{36}')"
[ -n "$REPLACEMENT_ID" ] && [ "$REPLACEMENT_ID" != "$SAMPLE_KEY_ID" ] || { echo "Rotation did not return a new key pair" >&2; exit 1; }
REVOKED="$(api POST "/api/v1/keys/$REPLACEMENT_ID/revoke")"
grep -q '"status":"revoked"' <<<"$REVOKED" || { echo "Revocation failed" >&2; exit 1; }
ROTATE_AGAIN="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H @"$WORK/api-key-header" \
  "http://127.0.0.1:$BACKEND_PORT/api/v1/keys/$SAMPLE_KEY_ID/rotate")"
[ "$ROTATE_AGAIN" = 400 ] || { echo "A rotated key pair was rotated again (HTTP $ROTATE_AGAIN)" >&2; exit 1; }
rm -f "$WORK/api-key-header"
for _ in 1 2; do
  PGPASSWORD="$DB_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -U "$APP_PGUSER" -f "$ROOT/database/upgrades/001-revoke-action.sql"
done
echo "Rotated $SAMPLE_KEY_ID into $REPLACEMENT_ID and revoked the replacement"

log "Database check"
# Expected after the browser test, the sample app and the rotation: three ML-KEM-1024 key pairs and two ML-DSA-65 key
# pairs for the demo team, each private key stored only encrypted (format version byte 1, and the PKCS#8 algorithm
# identifier of either algorithm nowhere in the stored bytes), one successful "generate" audit entry per key pair, one
# successful "encapsulate" and one "decapsulate" entry for the sample app's ML-KEM key pair, one successful "sign" and
# two successful "verify" entries for its ML-DSA key pair (a "no" answer is still a successful check), the rotated key
# pair pointing at its revoked replacement, and one successful and one refused "rotate" entry plus one "revoke" entry.
RESULT="$(PGPASSWORD="$DB_PASSWORD" psql -X -tA -U "$APP_PGUSER" -v ON_ERROR_STOP=1 -v sample="$SAMPLE_KEY_ID" \
  -v signing="$SIGNING_KEY_ID" -v replacement="$REPLACEMENT_ID" <<'SQL'
SELECT
  (SELECT count(*) FROM key_pairs),
  (SELECT string_agg(algorithm, ',' ORDER BY algorithm) FROM key_pairs),
  (SELECT count(*) FROM key_pairs
     WHERE get_byte(private_key_ciphertext, 0) = 1
       AND position('\x0609608648016503040403'::bytea IN private_key_ciphertext) = 0
       AND position('\x0609608648016503040312'::bytea IN private_key_ciphertext) = 0),
  (SELECT count(*) FROM audit_logs a JOIN key_pairs k ON k.key_pair_id = a.key_pair_id
     WHERE a.action = 'generate' AND a.status = 'success'),
  (SELECT count(*) FROM audit_logs
     WHERE key_pair_id = :'sample'::uuid AND action = 'encapsulate' AND status = 'success'),
  (SELECT count(*) FROM audit_logs
     WHERE key_pair_id = :'sample'::uuid AND action = 'decapsulate' AND status = 'success'),
  (SELECT count(*) FROM audit_logs
     WHERE key_pair_id = :'signing'::uuid AND action = 'sign' AND status = 'success'),
  (SELECT count(*) FROM audit_logs
     WHERE key_pair_id = :'signing'::uuid AND action = 'verify' AND status = 'success'),
  (SELECT status || ',' || (replaced_by = :'replacement'::uuid) || ',' || (grace_period_ends_at - rotated_at)
     FROM key_pairs WHERE key_pair_id = :'sample'::uuid),
  (SELECT status FROM key_pairs WHERE key_pair_id = :'replacement'::uuid),
  (SELECT string_agg(action || ':' || status, ',' ORDER BY action, status) FROM audit_logs
     WHERE key_pair_id IN (:'sample'::uuid, :'replacement'::uuid) AND action IN ('rotate', 'revoke'))
SQL
)"
EXPECTED="5|ML-DSA-65,ML-DSA-65,ML-KEM-1024,ML-KEM-1024,ML-KEM-1024|5|5|1|1|1|2|rotated,true,1 day|revoked|revoke:success,rotate:failure,rotate:success"
echo "key pairs | algorithms | stored encrypted | generate audit rows | sample encapsulate | sample decapsulate | sample sign | sample verify | rotated | replacement | rotate and revoke audit rows"
echo "$RESULT"
if [ "$RESULT" != "$EXPECTED" ]; then
  echo "Database check failed. Expected $EXPECTED" >&2
  exit 1
fi

log "End-to-end test passed"
