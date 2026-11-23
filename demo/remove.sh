#!/usr/bin/env bash
# Removes what demo/setup.sh created (KAN-26): the database, its user and the settings file. Windows: remove.cmd or
# remove.ps1. Every key pair stored in that database is lost. Stop start.sh first.
#
#   demo/remove.sh [--db-name NAME] [--env-file PATH] [--confirm-db-name NAME]
#
# Defaults as in setup.sh. It asks you to type the database name, unless --confirm-db-name gives it.
# The settings file is deleted only when it points at that database.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

DB_NAME=quantumlock
ENV_FILE="$ROOT/backend/.env"
CONFIRM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --db-name) [ $# -ge 2 ] || fail "--db-name needs a value"; DB_NAME="$2"; shift 2 ;;
    --env-file) [ $# -ge 2 ] || fail "--env-file needs a value"; ENV_FILE="$(absolute_path "$2")"; shift 2 ;;
    --confirm-db-name) [ $# -ge 2 ] || fail "--confirm-db-name needs a value"; CONFIRM="$2"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) fail "Unknown option $1 (see --help)" ;;
  esac
done
check_db_name "$DB_NAME"

connect_admin
if [ -z "$CONFIRM" ]; then
  echo "This deletes the database $DB_NAME, its user and every key pair stored in it."
  read -r -p "Type the database name to confirm: " CONFIRM
fi
[ "$CONFIRM" = "$DB_NAME" ] || fail "The name did not match. Nothing was removed."

if db_or_user_exists "$DB_NAME"; then
  admin_sql -v db="$DB_NAME" <<'SQL' || fail "Could not remove $DB_NAME. Is the demo still running? Stop start.sh and try again."
DROP DATABASE IF EXISTS :"db";
DROP ROLE IF EXISTS :"db";
SQL
  echo "Removed the database and user $DB_NAME."
else
  echo "There is no database or user named $DB_NAME."
fi

if [ -f "$ENV_FILE" ]; then
  if grep -Eq "^DB_URL=jdbc:postgresql://[^/]+/$DB_NAME[[:space:]]*\$" "$ENV_FILE"; then
    rm -f "$ENV_FILE"
    echo "Deleted $ENV_FILE."
  else
    echo "Kept $ENV_FILE: it does not point at the database $DB_NAME."
  fi
fi
