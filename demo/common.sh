# Shared by demo/setup.sh, demo/start.sh and demo/remove.sh (KAN-26). Not meant to be run on its own.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/demo/.run"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }
mask() { if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$1"; fi; }

# Database and user share one name, used unquoted in SQL, so only plain lower-case names are accepted.
check_db_name() {
  [[ "$1" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || fail "Database name '$1' is not allowed. Use lower-case letters, digits and _ (at most 63)."
}

absolute_path() { case "$1" in /*) echo "$1" ;; *) echo "$PWD/$1" ;; esac; }

need() { command -v "$1" >/dev/null 2>&1 || fail "$1 was not found. $2"; }

check_java() {
  need java "Install JDK 21 (https://adoptium.net) and try again."
  local major
  # Look for the version line: JAVA_TOOL_OPTIONS can add a "Picked up" line before it.
  major="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
  [ -n "$major" ] && [ "$major" -ge 21 ] || fail "Java 21 or later is needed, found: $(java -version 2>&1 | grep -m 1 version)"
}

check_node() {
  need node "Install Node.js 22 (https://nodejs.org) and try again."
  local version major minor
  version="$(node -p 'process.versions.node')"
  major="${version%%.*}"; minor="${version#*.}"; minor="${minor%%.*}"
  # Vite 8 needs Node 20.19 or a later 20.x, or 22.12 or later (not 21).
  if [ "$major" -lt 20 ] || { [ "$major" -eq 20 ] && [ "$minor" -lt 19 ]; } || [ "$major" -eq 21 ] || { [ "$major" -eq 22 ] && [ "$minor" -lt 12 ]; }; then
    fail "Node.js 20.19, 22.12 or later is needed, found $version."
  fi
}

backend_jar() { ls "$ROOT"/backend/target/quantumlock-backend-*.jar 2>/dev/null | grep -v -- '-plain' | head -n 1 || true; }

# demo/.run/backend-built marks the last successful build. Maven leaves an up-to-date jar untouched, so the jar's own
# date cannot tell whether the sources changed since.
BUILD_STAMP="$RUN_DIR/backend-built"

build_backend() {
  echo "Building the backend (the first build downloads the libraries and can take a few minutes)."
  mkdir -p "$RUN_DIR" && chmod 700 "$RUN_DIR"
  touch "$BUILD_STAMP.new"
  (cd "$ROOT/backend" && ./mvnw -B -q package -DskipTests) || fail "The backend build failed."
  JAR="$(backend_jar)"
  [ -n "$JAR" ] || fail "The backend build did not produce a jar."
  mv -f "$BUILD_STAMP.new" "$BUILD_STAMP"
}

backend_needs_build() {
  JAR="$(backend_jar)"
  [ -z "$JAR" ] || [ ! -f "$BUILD_STAMP" ] ||
    [ -n "$(find "$ROOT/backend/src" "$ROOT/backend/pom.xml" -newer "$BUILD_STAMP" -print -quit)" ]
}

# Connects to PostgreSQL as an administrator: PGHOST, PGPORT and PGUSER as usual (defaults localhost, 5432, postgres).
# The password comes from PGPASSWORD or is asked for once. It stays in this script's environment only.
connect_admin() {
  need psql "Install the PostgreSQL client (psql) and try again, or add its bin folder to PATH."
  local major
  major="$(psql --version | grep -Eo '[0-9]+' | head -n 1)"
  [ "$major" -ge 15 ] || fail "psql 15 or later is needed, found $(psql --version)."
  export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
  case "$PGHOST" in /*) fail "Set PGHOST to a host name such as localhost, not a socket folder: the backend connects over TCP." ;; esac
  unset PGDATABASE
  if ! psql -X -w -tA -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
    if [ -z "${PGPASSWORD:-}" ] && [ -t 0 ]; then
      read -rs -p "Password of the PostgreSQL user $PGUSER (not shown): " PGPASSWORD
      echo
      export PGPASSWORD
    fi
    psql -X -w -tA -d postgres -c 'SELECT 1' >/dev/null ||
      fail "Could not connect to PostgreSQL as $PGUSER on $PGHOST:$PGPORT. Is the server running and the password right?"
  fi
  [ "$(psql -X -w -tA -d postgres -c "SELECT rolsuper OR (rolcreaterole AND rolcreatedb) FROM pg_roles WHERE rolname = current_user")" = t ] ||
    fail "The PostgreSQL user $PGUSER may not create users and databases. Use an administrator such as postgres (PGUSER)."
}

admin_sql() { psql -X -w -q -v ON_ERROR_STOP=1 -d postgres "$@"; }

db_or_user_exists() { # name
  [ -n "$(psql -X -w -tA -d postgres -v name="$1" <<'SQL'
SELECT 1 FROM pg_database WHERE datname = :'name' UNION SELECT 1 FROM pg_roles WHERE rolname = :'name';
SQL
)" ]
}
