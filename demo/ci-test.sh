#!/usr/bin/env bash
# Checks the demo scripts the way a person uses them (KAN-26): setup, a second setup that must refuse, start, real
# requests through the dashboard, Ctrl+C in a terminal, then remove. Run by .github/workflows/demo.yml.
#
#   demo/ci-test.sh sh     tests setup.sh, start.sh and remove.sh
#   demo/ci-test.sh ps1    tests setup.ps1, start.ps1 and remove.ps1 (needs pwsh)
#
# Needs a PostgreSQL administrator through PGHOST, PGPORT, PGUSER and PGPASSWORD, plus python3 and curl. Uses its own
# database (quantumlock_ci_sh or quantumlock_ci_ps1) and settings file under demo/.run, and removes both at the end.

set -euo pipefail
FLAVOR="${1:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="quantumlock_ci_$FLAVOR"
ENV_FILE="$ROOT/demo/.run/ci-$FLAVOR.env"
WORK="$(mktemp -d)"
chmod 700 "$WORK"
case "$FLAVOR" in
  sh)
    SETUP=("$ROOT/demo/setup.sh" --db-name "$DB" --env-file "$ENV_FILE")
    START=("$ROOT/demo/start.sh" --env-file "$ENV_FILE")
    REMOVE=("$ROOT/demo/remove.sh" --db-name "$DB" --env-file "$ENV_FILE" --confirm-db-name "$DB") ;;
  ps1)
    SETUP=(pwsh -NoProfile -File "$ROOT/demo/setup.ps1" -DbName "$DB" -EnvFile "$ENV_FILE")
    START=(pwsh -NoProfile -File "$ROOT/demo/start.ps1" -EnvFile "$ENV_FILE")
    REMOVE=(pwsh -NoProfile -File "$ROOT/demo/remove.ps1" -DbName "$DB" -EnvFile "$ENV_FILE" -ConfirmDbName "$DB") ;;
  *) echo "Usage: demo/ci-test.sh sh|ps1" >&2; exit 2 ;;
esac

log() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
db_exists() { [ -n "$(psql -X -w -tA -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$DB' UNION SELECT 1 FROM pg_roles WHERE rolname = '$DB'")" ]; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

cleanup() {
  local status=$?
  pkill -f "$ENV_FILE" 2>/dev/null || true
  psql -X -w -q -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "DROP ROLE IF EXISTS $DB" >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
  rm -rf "$WORK"
  exit $status
}
trap cleanup EXIT
db_exists && fail "$DB already exists"

log "Setup"
"${SETUP[@]}" >"$WORK/setup.out" 2>&1 || { status=$?; cat "$WORK/setup.out"; fail "setup exited with $status"; }
API_KEY="$(grep -Eo 'ql_test_[A-Za-z0-9_-]{43}' "$WORK/setup.out" | head -n 1 || true)"
[ -n "$API_KEY" ] || { cat "$WORK/setup.out"; fail "setup printed no API key"; }
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$API_KEY"; fi
grep -v -e 'Picked up' -e '^::add-mask::' "$WORK/setup.out"
# On GitHub Actions the scripts hand each secret to the runner in an ::add-mask:: line, which the runner hides from the
# log. Every other line counts as shown.
grep -v '^::add-mask::' "$WORK/setup.out" >"$WORK/setup.shown" || true
for name in DB_PASSWORD MASTER_KEY; do
  value="$(grep "^$name=" "$ENV_FILE" | cut -d= -f2-)"
  [ -n "$value" ] || fail "$name missing from the settings file"
  ! grep -qF -- "$value" "$WORK/setup.shown" || fail "setup printed $name"
done
[ "$(stat -c %a "$ENV_FILE")" = 600 ] || fail "the settings file is readable by others"
[ "$(head -c 3 "$ENV_FILE" | od -An -tx1 | tr -d ' ')" != efbbbf ] || fail "the settings file starts with a byte order mark"
db_exists || fail "setup did not create $DB"

log "A second setup must refuse"
BEFORE="$(sha256sum "$ENV_FILE")"
if "${SETUP[@]}" >"$WORK/again.out" 2>&1; then fail "a second setup succeeded"; fi
grep -q 'already exists' "$WORK/again.out" || { cat "$WORK/again.out"; fail "a second setup failed for another reason"; }
[ "$BEFORE" = "$(sha256sum "$ENV_FILE")" ] && db_exists || fail "a second setup changed something"
echo "Refused, nothing changed."

log "Start, use the dashboard, press Ctrl+C"
# start runs in a pseudo-terminal, as in a real terminal window, so Ctrl+C reaches it the way it does for a person.
cat >"$WORK/ctrl_c.py" <<'PY'
import os, pty, select, signal, sys, time
go, out_path, cmd = sys.argv[1], sys.argv[2], sys.argv[3:]
pid, fd = pty.fork()
if pid == 0:
    # A background job of a script starts with Ctrl+C ignored. A terminal window does not, so undo that.
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    os.execvp(cmd[0], cmd)
sent, deadline = False, time.time() + 600
with open(out_path, 'ab', buffering=0) as out:
    while time.time() < deadline:
        if select.select([fd], [], [], 0.5)[0]:
            try:
                data = os.read(fd, 4096)
            except OSError:
                break
            if not data:
                break
            out.write(data)
        if not sent and os.path.exists(go):
            os.write(fd, b'\x03')
            sent = True
status = os.waitpid(pid, 0)[1]
sys.exit(os.waitstatus_to_exitcode(status) if sent else 99)
PY
python3 -I "$WORK/ctrl_c.py" "$WORK/go" "$WORK/start.out" "${START[@]}" &
PTY_PID=$!
for _ in $(seq 300); do grep -q 'Press Ctrl+C' "$WORK/start.out" 2>/dev/null && break; kill -0 "$PTY_PID" 2>/dev/null || break; sleep 1; done
grep -q 'Press Ctrl+C' "$WORK/start.out" || { cat "$WORK/start.out"; fail "start did not report that it is running"; }
CSP="$(curl -fsS -D - -o /dev/null http://127.0.0.1:4173/ | grep -i '^content-security-policy:' || true)"
[ -n "$CSP" ] || fail "the dashboard sent no content security policy"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"algorithm":"ML-KEM-1024"}' http://127.0.0.1:4173/api/v1/keys/generate)" = 201 ] || fail "generating a key pair through the dashboard failed"
[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4173/api/v1/keys)" = 401 ] || fail "a request without a key was not refused"
echo "Dashboard serves its content security policy, the API key works through it and no key gets 401."
touch "$WORK/go"
START_STATUS=0
wait "$PTY_PID" || START_STATUS=$?
[ "$START_STATUS" -eq 0 ] || { cat "$WORK/start.out"; fail "start exited with $START_STATUS after Ctrl+C"; }
for _ in $(seq 10); do port_open 8080 || port_open 4173 || break; sleep 1; done
! port_open 8080 && ! port_open 4173 || fail "the backend or dashboard still runs after Ctrl+C"
! grep -rqF -- "$API_KEY" "$WORK/start.out" "$ROOT/demo/.run/" || fail "the API key was written to the output or a log"
echo "Ctrl+C stopped both and exited with 0."

log "Remove must refuse a database that setup did not create"
# A settings file written by hand, like the one in the main README, names no database: remove must not drop anything.
printf 'DB_PASSWORD=x\nMASTER_KEY=y\n' >"$WORK/manual.env"
case "$FLAVOR" in
  sh) REFUSE=("$ROOT/demo/remove.sh" --db-name "$DB" --env-file "$WORK/manual.env" --confirm-db-name "$DB") ;;
  ps1) REFUSE=(pwsh -NoProfile -File "$ROOT/demo/remove.ps1" -DbName "$DB" -EnvFile "$WORK/manual.env" -ConfirmDbName "$DB") ;;
esac
if "${REFUSE[@]}" >"$WORK/refuse.out" 2>&1; then fail "remove accepted a settings file that names no database"; fi
grep -q 'Nothing was removed' "$WORK/refuse.out" || { cat "$WORK/refuse.out"; fail "remove failed for another reason"; }
db_exists && [ -f "$WORK/manual.env" ] || fail "a refused remove changed something"
echo "Refused, nothing changed."

log "Remove"
"${REMOVE[@]}"
! db_exists || fail "remove left $DB behind"
[ ! -e "$ENV_FILE" ] || fail "remove left the settings file behind"

log "Demo scripts ($FLAVOR) passed"
