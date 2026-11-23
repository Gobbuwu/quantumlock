#!/usr/bin/env bash
# Checks the demo scripts the way a person uses them (KAN-26): the API key hand-over in a terminal (KAN-34), setup, a
# second setup that must refuse, start, real requests through the dashboard, Ctrl+C in a terminal, then remove. Run by
# .github/workflows/demo.yml.
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
    SETUP=("$ROOT/demo/setup.sh" --db-name "$DB" --env-file "$ENV_FILE" --show-key)
    QUIET_SETUP=("$ROOT/demo/setup.sh" --db-name "$DB" --env-file "$ENV_FILE")
    HAND_OVER=(bash -c 'source "$QL_ROOT/demo/common.sh"; hand_over_key "$QL_KEY" false')
    START=("$ROOT/demo/start.sh" --env-file "$ENV_FILE")
    REMOVE=("$ROOT/demo/remove.sh" --db-name "$DB" --env-file "$ENV_FILE" --confirm-db-name "$DB") ;;
  ps1)
    SETUP=(pwsh -NoProfile -File "$ROOT/demo/setup.ps1" -DbName "$DB" -EnvFile "$ENV_FILE" -ShowKey)
    QUIET_SETUP=(pwsh -NoProfile -File "$ROOT/demo/setup.ps1" -DbName "$DB" -EnvFile "$ENV_FILE")
    HAND_OVER=(pwsh -NoProfile -Command '. (Join-Path $env:QL_ROOT "demo/common.ps1"); Send-ApiKey $env:QL_KEY $false')
    START=(pwsh -NoProfile -File "$ROOT/demo/start.ps1" -EnvFile "$ENV_FILE")
    REMOVE=(pwsh -NoProfile -File "$ROOT/demo/remove.ps1" -DbName "$DB" -EnvFile "$ENV_FILE" -ConfirmDbName "$DB") ;;
  *) echo "Usage: demo/ci-test.sh sh|ps1" >&2; exit 2 ;;
esac

log() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
db_exists() { [ -n "$(psql -X -w -tA -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$DB' UNION SELECT 1 FROM pg_roles WHERE rolname = '$DB'")" ]; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# GNU stat (Linux) takes -c, BSD stat (macOS) takes -f. sha256sum is not on every Mac, openssl is.
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
file_hash() { openssl dgst -sha256 <"$1"; }

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

log "Setup without a terminal must refuse, unless asked to print the key"
# Its output could be a log file, and with no terminal there is no one to press C or S.
if "${QUIET_SETUP[@]}" </dev/null >"$WORK/quiet.out" 2>&1; then fail "setup without a terminal succeeded"; fi
grep -q 'not one' "$WORK/quiet.out" || { cat "$WORK/quiet.out"; fail "setup without a terminal failed for another reason"; }
! db_exists && [ ! -e "$ENV_FILE" ] || fail "setup without a terminal changed something"
echo "Refused, nothing changed."

log "The API key hand-over shows the key only on request (KAN-34)"
# Runs the hand-over in a pseudo-terminal, as in a terminal window, and presses keys at each prompt. The key is a
# random one made here, not a real key.
cat >"$WORK/keys.py" <<'PY'
import os, pty, select, signal, sys, time
out_path, keys, cmd = sys.argv[1], sys.argv[2].split(','), sys.argv[3:]
send = [b'\r' if k == 'enter' else k.encode() for k in keys]
pid, fd = pty.fork()
if pid == 0:
    os.execvp(cmd[0], cmd)
data, sent, deadline = b'', 0, time.time() + 120
while time.time() < deadline:
    if select.select([fd], [], [], 0.2)[0]:
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            break
        if not chunk:
            break
        data += chunk
    if sent < len(send) and data.count(b'finish: ') > sent:
        time.sleep(0.2)
        os.write(fd, send[sent])
        sent += 1
else:
    os.kill(pid, signal.SIGKILL)
with open(out_path, 'wb') as out:
    out.write(data)
status = os.waitpid(pid, 0)[1]
sys.exit(os.waitstatus_to_exitcode(status) if sent == len(send) else 99)
PY
QL_KEY="ql_test_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
export QL_KEY QL_ROOT="$ROOT"
MASKED="${QL_KEY:0:8}...${QL_KEY: -4}"
hand_over() {
  local status=0
  python3 -I "$WORK/keys.py" "$WORK/$1.out" "$2" "${HAND_OVER[@]}" || status=$?
  [ "$status" -eq 0 ] || { cat -v "$WORK/$1.out"; fail "the hand-over ($1) exited with $status"; }
}
# Enter alone first warns that the key cannot be shown again, then finishes: the key never appears.
hand_over finish enter,enter
grep -qF -- "$MASKED" "$WORK/finish.out" || fail "the masked key was not shown"
grep -q 'neither copied nor shown' "$WORK/finish.out" || fail "finishing without the key did not warn"
! grep -qF -- "$QL_KEY" "$WORK/finish.out" || fail "the key was shown without S"
# S shows it once, after the masked form.
hand_over show s,enter
[ "$(grep -cF -- "$QL_KEY" "$WORK/show.out")" = 1 ] || { cat -v "$WORK/show.out"; fail "S did not show the key exactly once"; }
python3 -I -c 'import sys; d = open(sys.argv[1], "rb").read(); sys.exit(0 if 0 <= d.find(sys.argv[2].encode()) < d.find(b"finish: ") < d.find(sys.argv[3].encode()) else 1)' \
  "$WORK/show.out" "$MASKED" "$QL_KEY" || fail "the key appeared before S was pressed"
! grep -q 'neither copied nor shown' "$WORK/show.out" || fail "finishing after S warned"
if [ "$FLAVOR" = sh ]; then
  # C copies through a stand-in for pbcopy (macOS), which records what it was given. Enter must clear it again.
  mkdir "$WORK/bin"
  printf '#!/bin/sh\ncat >"$QL_CLIP"\ncat "$QL_CLIP" >>"$QL_CLIP.got"\n' >"$WORK/bin/pbcopy"
  printf '#!/bin/sh\ncat "$QL_CLIP"\n' >"$WORK/bin/pbpaste"
  chmod 700 "$WORK/bin/pbcopy" "$WORK/bin/pbpaste"
  export QL_CLIP="$WORK/clipboard"
  PATH="$WORK/bin:$PATH" hand_over copy c,enter
  [ "$(cat "$QL_CLIP.got")" = "$QL_KEY" ] || fail "C did not copy the key"
  [ ! -s "$QL_CLIP" ] || fail "Enter did not clear the clipboard"
  grep -q 'Clipboard cleared' "$WORK/copy.out" || fail "clearing the clipboard was not reported"
  ! grep -qF -- "$QL_KEY" "$WORK/copy.out" || fail "C showed the key"
  echo "Masked until S, copied with C and cleared with Enter, warned when neither was used."
else
  # PowerShell offers C on Windows only, where the copy can be kept out of clipboard history.
  ! grep -qF '[C]' "$WORK/finish.out" || fail "C was offered on a system where the copy cannot be kept private"
  echo "Masked until S, warned when the key was neither copied nor shown, no C outside Windows."
fi
unset QL_KEY

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
[ "$(file_mode "$ENV_FILE")" = 600 ] || fail "the settings file is readable by others"
[ "$(head -c 3 "$ENV_FILE" | od -An -tx1 | tr -d ' ')" != efbbbf ] || fail "the settings file starts with a byte order mark"
db_exists || fail "setup did not create $DB"

log "A second setup must refuse"
BEFORE="$(file_hash "$ENV_FILE")"
if "${SETUP[@]}" >"$WORK/again.out" 2>&1; then fail "a second setup succeeded"; fi
grep -q 'already exists' "$WORK/again.out" || { cat "$WORK/again.out"; fail "a second setup failed for another reason"; }
[ "$BEFORE" = "$(file_hash "$ENV_FILE")" ] && db_exists || fail "a second setup changed something"
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
