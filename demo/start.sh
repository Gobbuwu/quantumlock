#!/usr/bin/env bash
# Starts the QuantumLock demo on this computer (KAN-26): the backend and the dashboard's production build, with its
# content security policy. Windows: start.cmd or start.ps1. Run setup.sh once first.
#
#   demo/start.sh [--env-file PATH]
#
# PATH is the settings file written by setup.sh (default backend/.env). The backend listens on 127.0.0.1 at PORT from
# that file (default 8080) and the dashboard on 127.0.0.1:4173. Press Ctrl+C to stop both.
# Logs go to demo/.run/backend.log and demo/.run/dashboard.log.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

ENV_FILE="$ROOT/backend/.env"
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || fail "--env-file needs a value"; ENV_FILE="$(absolute_path "$2")"; shift 2 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) fail "Unknown option $1 (see --help)" ;;
  esac
done
[ -f "$ENV_FILE" ] || fail "$ENV_FILE does not exist. Run demo/setup.sh first."

# The settings file becomes this script's environment, so the backend gets exactly these settings.
# Format: NAME=value per line, # comments. Values are taken as they are, without quotes or escapes.
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in ''|'#'*|'!'*) continue ;; esac
  name="${line%%=*}"; name="${name//[[:space:]]/}"
  [[ "$name" =~ ^[A-Z_][A-Z0-9_]*$ ]] && [ "$name" != "$line" ] || continue
  value="${line#*=}"; value="${value#"${value%%[![:space:]]*}"}"
  export "$name=$value"
done <"$ENV_FILE"
[ -n "${DB_PASSWORD:-}" ] && [ -n "${MASTER_KEY:-}" ] || fail "$ENV_FILE must set DB_PASSWORD and MASTER_KEY."
mask "$DB_PASSWORD"; mask "$MASTER_KEY"
BACKEND_PORT="${PORT:-8080}"
DASHBOARD_PORT=4173 # fixed in dashboard/vite.config.js

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for port in "$BACKEND_PORT" "$DASHBOARD_PORT"; do
  ! port_in_use "$port" || fail "Port $port is already in use. Stop whatever runs there (an earlier demo?) and try again."
done

step "Check the tools"
check_java
check_node

step "Build"
if backend_needs_build; then
  build_backend
else
  echo "Backend is up to date."
fi
cd "$ROOT/dashboard"
if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
  echo "Installing the dashboard's packages."
  npm ci --no-audit --no-fund >/dev/null || fail "npm ci failed."
fi
mkdir -p "$RUN_DIR" && chmod 700 "$RUN_DIR"
npm run build >"$RUN_DIR/dashboard-build.log" 2>&1 || { tail -n 30 "$RUN_DIR/dashboard-build.log" >&2; fail "The dashboard build failed."; }
echo "Dashboard built."

BACKEND_PID=""
DASHBOARD_PID=""
stop_all() {
  local status=$?
  trap - EXIT INT TERM
  [ -n "$DASHBOARD_PID" ] && kill "$DASHBOARD_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  [ -z "$BACKEND_PID$DASHBOARD_PID" ] || echo "Stopped."
  exit $status
}
trap stop_all EXIT
trap 'exit 0' INT TERM

show_log() { echo "Last lines of $1:" >&2; tail -n 30 "$1" >&2; }
wait_for() { # url, seconds, pid, log
  for _ in $(seq "$2"); do
    curl -fsS -o /dev/null "$1" 2>/dev/null && return 0
    kill -0 "$3" 2>/dev/null || { show_log "$4"; fail "It stopped while starting."; }
    sleep 1
  done
  show_log "$4"
  fail "Timed out waiting for $1"
}

step "Start"
# Started from demo/.run so the backend reads no .env file of its own, only the settings above.
(cd "$RUN_DIR" && exec java -jar "$JAR" >"$RUN_DIR/backend.log" 2>&1) &
BACKEND_PID=$!
wait_for "http://127.0.0.1:$BACKEND_PORT/api/v1/health" 90 "$BACKEND_PID" "$RUN_DIR/backend.log"
echo "Backend is up."
# Node runs Vite directly, so DASHBOARD_PID is the server itself and Ctrl+C stops it.
(QL_API_TARGET="http://127.0.0.1:$BACKEND_PORT" exec node node_modules/vite/bin/vite.js preview >"$RUN_DIR/dashboard.log" 2>&1) &
DASHBOARD_PID=$!
wait_for "http://127.0.0.1:$DASHBOARD_PORT/" 60 "$DASHBOARD_PID" "$RUN_DIR/dashboard.log"

cat <<READY

QuantumLock is running.

    Dashboard:   http://127.0.0.1:$DASHBOARD_PORT
    API docs:    http://127.0.0.1:$BACKEND_PORT/swagger-ui.html

Open the dashboard and paste the API key from setup. Press Ctrl+C to stop.
READY

while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$DASHBOARD_PID" 2>/dev/null; do sleep 1; done
if ! kill -0 "$BACKEND_PID" 2>/dev/null; then show_log "$RUN_DIR/backend.log"; else show_log "$RUN_DIR/dashboard.log"; fi
fail "One of the two stopped on its own."
