#!/usr/bin/env bash
# End-to-end test of QuantumLock on a fresh setup (KAN-20, KAN-24).
#
# Builds everything from nothing: a new PostgreSQL database and user, the schema and demo data, the backend,
# one API key and the dashboard. A real browser then uses the dashboard (dashboard/e2e/), and the database is
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

log "Database check"
# Expected after the browser test: one ML-KEM-1024 and one ML-DSA-65 key pair for the demo team, each private key
# stored only encrypted (format version byte 1, and the PKCS#8 algorithm identifier of either algorithm nowhere in
# the stored bytes), and one successful "generate" audit entry per key pair.
RESULT="$(PGPASSWORD="$DB_PASSWORD" psql -X -tA -U "$APP_PGUSER" -v ON_ERROR_STOP=1 <<'SQL'
SELECT
  (SELECT count(*) FROM key_pairs),
  (SELECT string_agg(algorithm, ',' ORDER BY algorithm) FROM key_pairs),
  (SELECT count(*) FROM key_pairs
     WHERE get_byte(private_key_ciphertext, 0) = 1
       AND position('\x0609608648016503040403'::bytea IN private_key_ciphertext) = 0
       AND position('\x0609608648016503040312'::bytea IN private_key_ciphertext) = 0),
  (SELECT count(*) FROM audit_logs a JOIN key_pairs k ON k.key_pair_id = a.key_pair_id
     WHERE a.action = 'generate' AND a.status = 'success')
SQL
)"
echo "key pairs | algorithms | stored encrypted | generate audit rows"
echo "$RESULT"
if [ "$RESULT" != "2|ML-DSA-65,ML-KEM-1024|2|2" ]; then
  echo "Database check failed. Expected 2|ML-DSA-65,ML-KEM-1024|2|2" >&2
  exit 1
fi

log "End-to-end test passed"
