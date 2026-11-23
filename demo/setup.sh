#!/usr/bin/env bash
# Sets up QuantumLock for a demo on this computer, once (KAN-26). Windows: setup.cmd or setup.ps1.
#
# Creates the PostgreSQL database and its user with a random password, loads database/schema.sql and seed.sql,
# writes backend/.env with that password and a random MASTER_KEY, builds the backend and issues the first API key
# for the demo team. The key is shown once. The database password and MASTER_KEY are never shown.
# It refuses to run when the settings file, the database or the user already exists, so it never overwrites
# anything. If a step fails, everything it created is removed again.
#
#   demo/setup.sh [--db-name NAME] [--env-file PATH] [--show-key]
#
# NAME is the database and user name (default quantumlock). PATH is the settings file (default backend/.env).
# The API key is shown masked, with keys to copy or show it, so it does not appear on screen unless you ask. Without
# a terminal setup stops before it changes anything. --show-key prints the key instead, for scripts: keep that output
# private.
# PostgreSQL administrator: PGHOST, PGPORT, PGUSER (defaults localhost, 5432, postgres) and PGPASSWORD,
# which is asked for when it is not set.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

DB_NAME=quantumlock
ENV_FILE="$ROOT/backend/.env"
SHOW_KEY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --db-name) [ $# -ge 2 ] || fail "--db-name needs a value"; DB_NAME="$2"; shift 2 ;;
    --env-file) [ $# -ge 2 ] || fail "--env-file needs a value"; ENV_FILE="$(absolute_path "$2")"; shift 2 ;;
    --show-key) SHOW_KEY=true; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) fail "Unknown option $1 (see --help)" ;;
  esac
done
check_db_name "$DB_NAME"
if [ "$SHOW_KEY" = false ] && ! is_terminal; then
  fail "Setup hands over the API key in a terminal window, and this is not one. Run it in a terminal, or add --show-key to print the key (then keep the output private). Nothing was changed."
fi

CREATED_DB=false
WROTE_ENV=false
rollback() {
  local status=$?
  if [ $status -ne 0 ]; then
    if [ "$WROTE_ENV" = true ]; then rm -f "$ENV_FILE"; fi
    if [ "$CREATED_DB" = true ]; then
      admin_sql -v db="$DB_NAME" >/dev/null 2>&1 <<'SQL' || true
DROP DATABASE IF EXISTS :"db";
DROP ROLE IF EXISTS :"db";
SQL
    fi
    if [ "$CREATED_DB" = true ] || [ "$WROTE_ENV" = true ]; then
      echo "Setup failed. What it had created was removed again, so it can simply be run again." >&2
    fi
  fi
  exit $status
}
trap rollback EXIT
trap 'exit 130' INT TERM

step "Check the tools"
check_java
check_node
need openssl "Install OpenSSL and try again."
[ ! -e "$ENV_FILE" ] || fail "$ENV_FILE already exists. Setup never overwrites it. Use --env-file for another file, or remove.sh first."
connect_admin
! db_or_user_exists "$DB_NAME" || fail "A database or user named $DB_NAME already exists. Setup never overwrites it. Use --db-name for another name, or remove.sh first."
echo "Java, Node.js and PostgreSQL ($PGUSER on $PGHOST:$PGPORT) are ready."

step "Create the database $DB_NAME"
DB_PASSWORD="$(openssl rand -hex 24)"
MASTER_KEY="$(openssl rand -base64 32)"
mask "$DB_PASSWORD"; mask "$MASTER_KEY"
CREATED_DB=true
# The password reaches psql through its environment, not the command line, so other programs cannot see it.
QL_NEW_DB_PASSWORD="$DB_PASSWORD" admin_sql -v db="$DB_NAME" <<'SQL'
\getenv pw QL_NEW_DB_PASSWORD
CREATE ROLE :"db" LOGIN PASSWORD :'pw';
CREATE DATABASE :"db" OWNER :"db";
SQL
for file in schema.sql seed.sql; do
  PGUSER="$DB_NAME" PGPASSWORD="$DB_PASSWORD" psql -X -w -q -v ON_ERROR_STOP=1 -d "$DB_NAME" -f "$ROOT/database/$file"
done
echo "Tables and demo data loaded."

step "Write $ENV_FILE"
mkdir -p "$(dirname "$ENV_FILE")"
# Created empty with mode 600 from the start and never over an existing file (set -C), then filled (KAN-37).
( umask 077 && set -C && : >"$ENV_FILE" ) || fail "Could not create $ENV_FILE."
WROTE_ENV=true
cat >>"$ENV_FILE" <<ENV
# Written by demo/setup.sh on $(date -u +%Y-%m-%d). Local secrets: never commit or share this file.
# MASTER_KEY encrypts every stored private key. Keep a copy somewhere safe: without it they cannot be used.
DB_URL=jdbc:postgresql://$PGHOST:$PGPORT/$DB_NAME
DB_USERNAME=$DB_NAME
DB_PASSWORD=$DB_PASSWORD
MASTER_KEY=$MASTER_KEY
ENV
echo "Written, readable by you only."

step "Build the backend and issue the first API key"
build_backend
mkdir -p "$RUN_DIR" && chmod 700 "$RUN_DIR"
# Run from demo/.run so no other .env file is picked up, with the new settings in the environment.
ISSUE_STATUS=0
ISSUE_OUT="$(cd "$RUN_DIR" && DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/$DB_NAME" DB_USERNAME="$DB_NAME" \
  DB_PASSWORD="$DB_PASSWORD" MASTER_KEY="$MASTER_KEY" java -jar "$JAR" issue-api-key 2>&1)" || ISSUE_STATUS=$?
API_KEY="$(grep -Eo 'ql_test_[A-Za-z0-9_-]{43}' <<<"$ISSUE_OUT" | head -n 1 || true)"
if [ "$ISSUE_STATUS" -ne 0 ] || [ -z "$API_KEY" ]; then
  echo "$ISSUE_OUT" | tail -n 30 >&2
  fail "Issuing the API key failed (exit code $ISSUE_STATUS)."
fi
mask "$API_KEY"
unset ISSUE_OUT

CREATED_DB=false
WROTE_ENV=false
step "Done"
echo "The database $DB_NAME and $ENV_FILE are ready."
echo
hand_over_key "$API_KEY" "$SHOW_KEY"
unset API_KEY
cat <<DONE
Next: run demo/start.sh$([ "$ENV_FILE" = "$ROOT/backend/.env" ] || printf ' --env-file %q' "$ENV_FILE"), open the dashboard address it prints and paste the key.
DONE
