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

# Setup hands the new API key over without printing it (KAN-34): it shows a masked form, and the person presses C to
# copy the key or S to show it. The key reaches a clipboard tool through standard input only, never as a command
# argument, and is never written to a file or a log.
is_terminal() { [ -t 0 ] && [ -t 1 ]; }

masked_key() { printf '%s...%s' "${1:0:8}" "${1: -4}"; }

# The clipboard tool of this desktop, or nothing when there is none (for example over SSH).
clipboard_tool() {
  if command -v pbcopy >/dev/null 2>&1 && command -v pbpaste >/dev/null 2>&1; then echo pbcopy
  elif [ -n "${WAYLAND_DISPLAY:-}" ] && command -v wl-copy >/dev/null 2>&1 && command -v wl-paste >/dev/null 2>&1; then echo wl-copy
  elif [ -n "${DISPLAY:-}" ] && command -v xclip >/dev/null 2>&1; then echo xclip
  elif [ -n "${DISPLAY:-}" ] && command -v xsel >/dev/null 2>&1; then echo xsel
  fi
}

# Copies standard input to the clipboard.
clipboard_copy() {
  case "$1" in
    pbcopy) pbcopy ;;
    wl-copy) wl-copy ;;
    xclip) xclip -selection clipboard -in >/dev/null 2>&1 ;;
    xsel) xsel --clipboard --input ;;
  esac
}

clipboard_read() {
  case "$1" in
    pbcopy) pbpaste ;;
    wl-copy) wl-paste --no-newline ;;
    xclip) xclip -selection clipboard -out ;;
    xsel) xsel --clipboard --output ;;
  esac 2>/dev/null
}

clipboard_clear() {
  case "$1" in
    pbcopy) printf '' | pbcopy ;;
    wl-copy) wl-copy --clear ;;
    xclip) printf '' | xclip -selection clipboard -in >/dev/null 2>&1 ;;
    xsel) xsel --clipboard --clear ;;
  esac 2>/dev/null || true
}

# hand_over_key KEY true|false. With true the key is printed (--show-key). Otherwise it is masked, and the person
# chooses C (copy), S (show) or Enter (finish). Enter clears the clipboard if it still holds the key, and asks once
# more when the key was neither copied nor shown, because it cannot be shown again.
hand_over_key() {
  local key="$1" tool choice copied=false shown=false warned=false
  echo "API key for the demo team. It is shown only this once: keep it somewhere safe and never commit it."
  echo
  if [ "$2" = true ]; then
    printf '    %s\n\n' "$key"
    return
  fi
  printf '    %s (hidden)\n\n' "$(masked_key "$key")"
  tool="$(clipboard_tool)"
  while true; do
    if [ -n "$tool" ]; then printf '[C] copy it   [S] show it   [Enter] finish: '; else printf '[S] show it   [Enter] finish: '; fi
    IFS= read -rsn1 choice || { echo; break; }
    echo
    case "$choice" in
      c|C)
        if [ -z "$tool" ]; then continue; fi
        if printf '%s' "$key" | clipboard_copy "$tool" && [ "$(clipboard_read "$tool")" = "$key" ]; then
          copied=true
          echo "Copied. Paste it where you keep secrets, then press Enter: that clears the clipboard."
        else
          echo "Copying did not work. Press S to show the key instead."
        fi ;;
      s|S)
        printf '\n    %s\n\n' "$key"
        shown=true ;;
      '')
        if [ "$copied" = false ] && [ "$shown" = false ] && [ "$warned" = false ]; then
          echo "The key was neither copied nor shown, and it cannot be shown again. Press Enter again to finish anyway."
          warned=true
          continue
        fi
        break ;;
    esac
  done
  if [ "$copied" = true ] && [ "$(clipboard_read "$tool")" = "$key" ]; then
    clipboard_clear "$tool"
    echo "Clipboard cleared."
  fi
  echo
}
