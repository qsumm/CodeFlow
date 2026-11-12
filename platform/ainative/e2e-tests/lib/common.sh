#!/usr/bin/env bash
# Shared helpers for the community end-to-end harness.
#
# Machine independence is a hard requirement: nothing here may assume a
# particular host, a particular docker install path, an unrestricted registry,
# or a pre-existing container runtime state. Every host-specific fact is
# discovered at run time or supplied through an AINATIVE_E2E_* variable.

set -euo pipefail

E2E_DIR="${E2E_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REPO_ROOT="${REPO_ROOT:-$(cd "$E2E_DIR/.." && pwd)}"
# shellcheck source=lifecycle.sh
source "$E2E_DIR/lib/lifecycle.sh"

# ---------------------------------------------------------------------------
# Knobs. Defaults are the values the community compose file already uses, so
# an unset environment means "run the documented thing".
# ---------------------------------------------------------------------------
AINATIVE_E2E_PROJECT="${AINATIVE_E2E_PROJECT:-ainative-e2e}"
AINATIVE_E2E_NETWORK="${AINATIVE_E2E_NETWORK:-${AINATIVE_E2E_PROJECT}-net}"
AINATIVE_E2E_STATE_DIR="${AINATIVE_E2E_STATE_DIR:-${TMPDIR:-/tmp}/ainative-e2e-${AINATIVE_E2E_PROJECT}}"

AINATIVE_E2E_MYSQL_PORT="${AINATIVE_E2E_MYSQL_PORT:-33060}"
AINATIVE_E2E_REDIS_PORT="${AINATIVE_E2E_REDIS_PORT:-63790}"
AINATIVE_E2E_MINIO_PORT="${AINATIVE_E2E_MINIO_PORT:-9000}"
AINATIVE_E2E_MINIO_CONSOLE_PORT="${AINATIVE_E2E_MINIO_CONSOLE_PORT:-9001}"
AINATIVE_E2E_APP_PORT="${AINATIVE_E2E_APP_PORT:-7001}"

AINATIVE_E2E_MYSQL_IMAGE="${AINATIVE_E2E_MYSQL_IMAGE:-mysql:8.0}"
AINATIVE_E2E_REDIS_IMAGE="${AINATIVE_E2E_REDIS_IMAGE:-redis:7-alpine}"
AINATIVE_E2E_MINIO_IMAGE="${AINATIVE_E2E_MINIO_IMAGE:-quay.io/minio/minio:latest}"
AINATIVE_E2E_MC_IMAGE="${AINATIVE_E2E_MC_IMAGE:-quay.io/minio/mc:latest}"

# Public mirror of the Docker Official Images, used only when the primary
# registry is unreachable. This is a public AWS endpoint, not an internal one.
AINATIVE_E2E_OFFICIAL_IMAGE_MIRROR="${AINATIVE_E2E_OFFICIAL_IMAGE_MIRROR:-public.ecr.aws/docker/library}"

AINATIVE_E2E_BUCKET="${AINATIVE_E2E_BUCKET:-ainative-e2e-ainative}"
AINATIVE_E2E_MINIO_ROOT_USER="${AINATIVE_E2E_MINIO_ROOT_USER:-ainative-e2e-minio}"

# Compose file defaults are the community file plus the end-to-end layer.
AINATIVE_E2E_BASE_COMPOSE="${AINATIVE_E2E_BASE_COMPOSE:-$REPO_ROOT/docs/community/docker-compose.dependencies.yml}"
AINATIVE_E2E_LAYER_COMPOSE="${AINATIVE_E2E_LAYER_COMPOSE:-$E2E_DIR/compose.e2e.yml}"

# Every variable compose.e2e.yml interpolates has to be *exported*. Compose reads
# the process environment, not the shell's variable table, so an unexported knob
# makes compose fall back to its own default while the scripts keep using the real
# value - the two then disagree about the network name and `docker run --network`
# fails with "network not found" after a perfectly healthy `up`.
export AINATIVE_E2E_PROJECT AINATIVE_E2E_NETWORK AINATIVE_E2E_STATE_DIR
export AINATIVE_E2E_MYSQL_PORT AINATIVE_E2E_REDIS_PORT
export AINATIVE_E2E_MINIO_PORT AINATIVE_E2E_MINIO_CONSOLE_PORT AINATIVE_E2E_APP_PORT
export AINATIVE_E2E_MYSQL_IMAGE AINATIVE_E2E_REDIS_IMAGE AINATIVE_E2E_MINIO_IMAGE AINATIVE_E2E_MC_IMAGE
export AINATIVE_E2E_BUCKET AINATIVE_E2E_MINIO_ROOT_USER

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Runtime discovery
# ---------------------------------------------------------------------------

# Prints the docker binary to use. Prefers $AINATIVE_E2E_DOCKER, then PATH, then the
# well-known per-user install location that Docker Desktop creates on macOS.
ainative_e2e_find_docker() {
  if [[ -n "${AINATIVE_E2E_DOCKER:-}" ]]; then
    [[ -x "$AINATIVE_E2E_DOCKER" ]] || die "AINATIVE_E2E_DOCKER=$AINATIVE_E2E_DOCKER is not executable"
    printf '%s' "$AINATIVE_E2E_DOCKER"
    return
  fi
  if command -v docker >/dev/null 2>&1; then
    command -v docker
    return
  fi
  local candidate
  for candidate in \
    "$HOME/.docker/bin/docker" \
    /usr/local/bin/docker \
    /Applications/Docker.app/Contents/Resources/bin/docker; do
    if [[ -x "$candidate" ]]; then printf '%s' "$candidate"; return; fi
  done
  die "no docker binary found; set AINATIVE_E2E_DOCKER=/path/to/docker"
}

# Docker reads credsStore from ~/.docker/config.json and execs
# docker-credential-<store>. Docker Desktop puts that helper in its own
# resource directory, which is frequently absent from PATH in non-interactive
# shells; every pull then fails with "error getting credentials". Put it back.
ainative_e2e_fix_credential_helper() {
  local cfg="$HOME/.docker/config.json" store helper dir
  [[ -f "$cfg" ]] || return 0
  store="$(python3 - "$cfg" <<'PY' 2>/dev/null || true
import json,sys
try:
    print(json.load(open(sys.argv[1])).get("credsStore") or "")
except Exception:
    print("")
PY
)"
  [[ -n "$store" ]] || return 0
  helper="docker-credential-$store"
  command -v "$helper" >/dev/null 2>&1 && return 0
  for dir in \
    /Applications/Docker.app/Contents/Resources/bin \
    "$HOME/.docker/bin" \
    /usr/local/bin; do
    if [[ -x "$dir/$helper" ]]; then
      export PATH="$dir:$PATH"
      log "credential helper $helper was missing from PATH; added $dir"
      return 0
    fi
  done
  log "WARNING: credsStore=$store but $helper not found; pulls may fail"
}

# Exports DOCKER_HOST when the daemon is not reachable on the default socket.
ainative_e2e_export_docker_host() {
  if [[ -n "${DOCKER_HOST:-}" ]]; then
    "$DOCKER" version >/dev/null 2>&1 && return 0
    log "DOCKER_HOST=$DOCKER_HOST does not answer; probing alternatives"
  fi
  "$DOCKER" version >/dev/null 2>&1 && return 0
  local sock
  for sock in \
    "$HOME/.docker/run/docker.sock" \
    /var/run/docker.sock \
    "$HOME/.colima/default/docker.sock" \
    "$HOME/.rd/docker.sock"; do
    if [[ -S "$sock" ]]; then
      export DOCKER_HOST="unix://$sock"
      if "$DOCKER" version >/dev/null 2>&1; then
        log "DOCKER_HOST set to unix://$sock"
        return 0
      fi
    fi
  done
  die "no answering docker daemon; start one or set DOCKER_HOST"
}

ainative_e2e_init_runtime() {
  DOCKER="$(ainative_e2e_find_docker)"
  export DOCKER
  ainative_e2e_fix_credential_helper
  ainative_e2e_export_docker_host
  DOCKER_SERVER_VERSION="$("$DOCKER" version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
  DOCKER_MIN_API="$("$DOCKER" version --format '{{.Server.MinAPIVersion}}' 2>/dev/null || echo unknown)"
  log "docker binary=$DOCKER server=$DOCKER_SERVER_VERSION minAPI=$DOCKER_MIN_API"
}

# ---------------------------------------------------------------------------
# Images
# ---------------------------------------------------------------------------

# Proves that a tag really runs under a pinned platform, instead of trusting
# `docker image inspect`. Under the containerd image store a tag resolves to a
# manifest index whose Architecture field is empty or shows the host
# architecture, even when the pinned platform is materialised and runnable -
# and compose, which does check the pin, will then go to the registry for an
# image that is already here. Running it is the only decisive test.
ainative_e2e_platform_uname() {
  local image="$1" platform="$2"
  "$DOCKER" image inspect "$image" >/dev/null 2>&1 || return 1
  if [[ -n "$platform" ]]; then
    "$DOCKER" run --rm --platform "$platform" --entrypoint /bin/sh "$image" -c 'uname -m' 2>/dev/null
  else
    "$DOCKER" run --rm --entrypoint /bin/sh "$image" -c 'uname -m' 2>/dev/null
  fi
}

ainative_e2e_expected_uname() {
  case "$1" in
    linux/amd64) printf 'x86_64' ;;
    linux/arm64) printf 'aarch64' ;;
    *) printf '' ;;
  esac
}

# ainative_e2e_ensure_image <wanted-ref> <platform|-> [mirror-ref...]
# Leaves <wanted-ref> present locally AND runnable as <platform>, pulling from a
# mirror and retagging when the primary registry is unreachable. Never rewrites
# the community compose file: the compose file keeps naming the image it always
# named, so the mirror fallback stays invisible to the thing under test.
ainative_e2e_ensure_image() {
  local wanted="$1" platform="$2"; shift 2
  if [[ "$platform" == "-" ]]; then platform=""; fi
  local slug expected got pull_rc
  slug="$(printf '%s' "$wanted" | tr '/:' '__')"
  expected="$(ainative_e2e_expected_uname "$platform")"

  got="$(ainative_e2e_platform_uname "$wanted" "$platform" || true)"
  if [[ -n "$got" && ( -z "$expected" || "$got" == "$expected" ) ]]; then
    log "image ready: $wanted (runs as ${platform:-host}, uname -m=$got)"
    return 0
  fi
  if "$DOCKER" image inspect "$wanted" >/dev/null 2>&1; then
    log "image $wanted is present but not runnable as ${platform:-host} (uname='${got:-none}'); pulling"
  fi

  local ref
  for ref in "$wanted" "$@"; do
    log "pulling $ref${platform:+ for $platform}"
    # The status has to be captured inline: a bare failing pull inside this
    # if-body would trip the caller's `set -e` and end the script before the
    # mirror fallback below ever ran.
    pull_rc=0
    if [[ -n "$platform" ]]; then
      "$DOCKER" pull --platform "$platform" "$ref" >"$AINATIVE_E2E_STATE_DIR/pull-$slug.log" 2>&1 || pull_rc=$?
    else
      "$DOCKER" pull "$ref" >"$AINATIVE_E2E_STATE_DIR/pull-$slug.log" 2>&1 || pull_rc=$?
    fi
    if [[ "$pull_rc" == "0" ]]; then
      if [[ "$ref" != "$wanted" ]]; then
        "$DOCKER" tag "$ref" "$wanted"
        log "retagged $ref -> $wanted (primary registry unreachable)"
      fi
      got="$(ainative_e2e_platform_uname "$wanted" "$platform" || true)"
      if [[ -n "$got" && ( -z "$expected" || "$got" == "$expected" ) ]]; then
        log "image ready: $wanted (runs as ${platform:-host}, uname -m=$got)"
        return 0
      fi
      log "pulled $ref but it still does not run as ${platform:-host} (uname='${got:-none}'); trying next source"
      continue
    fi
    log "pull failed for $ref (exit $pull_rc); trying next source"
  done
  die "could not obtain image $wanted from any of: $wanted $*"
}

# Prints a mirror candidate for a Docker Official Image such as mysql:8.0.
ainative_e2e_official_mirror() {
  case "$1" in
    */*) return 1 ;;                       # not an official image
    *) printf '%s/%s' "$AINATIVE_E2E_OFFICIAL_IMAGE_MIRROR" "$1" ;;
  esac
}

# ---------------------------------------------------------------------------
# Ports and state
# ---------------------------------------------------------------------------

ainative_e2e_port_listeners() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    { lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || true; } \
      | tail -n +2 | wc -l | tr -d ' '
  else
    (ss -ltn "sport = :$port" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ') \
      || printf '0'
  fi
}

ainative_e2e_port_reserved() {
  local candidate="$1" reserved
  shift
  for reserved in "$@"; do
    [[ "$candidate" == "$reserved" ]] && return 0
  done
  return 1
}

ainative_e2e_first_free_port() {
  local start="${1:-39000}" p
  shift || true
  for ((p = start; p < start + 400; p++)); do
    if [[ "$(ainative_e2e_port_listeners "$p")" == "0" ]] && ! ainative_e2e_port_reserved "$p" "$@"; then
      printf '%s' "$p"
      return
    fi
  done
  die "no free port found from $start"
}

ainative_e2e_minio_internal_port() {
  printf '9000'
}

ainative_e2e_prepare_state_dir() {
  mkdir -p "$AINATIVE_E2E_STATE_DIR"
  chmod 700 "$AINATIVE_E2E_STATE_DIR"
}

# ---------------------------------------------------------------------------
# Secrets. Generated per run, 0600, never printed, deleted by down.sh.
# ---------------------------------------------------------------------------
ainative_e2e_generate_secrets() {
  local f="$AINATIVE_E2E_STATE_DIR/secrets.env"
  if [[ -f "$f" ]]; then log "reusing existing secrets.env"; return 0; fi
  command -v openssl >/dev/null 2>&1 || die "openssl is required to generate secrets"
  {
    printf 'AINATIVE_SECRET_MASTER_KEY=%s\n' "$(openssl rand -base64 32)"
    printf 'AINATIVE_JWT_SECRET=%s\n' "$(openssl rand -base64 48)"
    printf 'AINATIVE_E2E_MINIO_ROOT_PASSWORD=%s\n' "$(openssl rand -base64 24)"
  } >"$f"
  chmod 600 "$f"
  log "generated 3 secrets into $f (mode 600)"
}

# Reads one key out of secrets.env without echoing it.
ainative_e2e_secret() {
  local key="$1"
  sed -n "s|^${key}=||p" "$AINATIVE_E2E_STATE_DIR/secrets.env" | head -1
}

# ---------------------------------------------------------------------------
# Compose
# ---------------------------------------------------------------------------

# Resolves the compose file arguments into the global AINATIVE_E2E_COMPOSE_ARGS array.
# The community file is used byte-for-byte unless a host port it hardcodes is
# already taken, or the caller explicitly asked for different ports; in that
# case a derived copy with only the host port numbers substituted is placed in
# the state directory. The substitution is reported so the evidence states
# which file actually ran.
#
# This is a resolver that sets globals, not a printer: a caller that captured
# its output with $(...) would run it in a subshell and silently lose
# RESOLVED_MYSQL_PORT, RESOLVED_REDIS_PORT and COMPOSE_BASE_MODE.
AINATIVE_E2E_COMPOSE_ARGS=()
ainative_e2e_resolve_compose() {
  [[ "${#AINATIVE_E2E_COMPOSE_ARGS[@]}" -gt 0 ]] && return 0
  local base="$AINATIVE_E2E_BASE_COMPOSE" derived="$AINATIVE_E2E_STATE_DIR/dependencies.derived.yml"
  local need_derive=0 mysql_port="$AINATIVE_E2E_MYSQL_PORT" redis_port="$AINATIVE_E2E_REDIS_PORT"
  local minio_port="$AINATIVE_E2E_MINIO_PORT" minio_console_port="$AINATIVE_E2E_MINIO_CONSOLE_PORT"
  local app_port="$AINATIVE_E2E_APP_PORT"

  grep -q "\"$mysql_port:3306\"" "$base" || need_derive=1
  grep -q "\"$redis_port:6379\"" "$base" || need_derive=1
  if [[ "$(ainative_e2e_port_listeners "$mysql_port")" != "0" ]]; then
    mysql_port="$(ainative_e2e_first_free_port 39060)"; need_derive=1
    log "host port $AINATIVE_E2E_MYSQL_PORT busy; remapping mysql to $mysql_port"
  fi
  if [[ "$(ainative_e2e_port_listeners "$redis_port")" != "0" ]] || ainative_e2e_port_reserved "$redis_port" "$mysql_port"; then
    redis_port="$(ainative_e2e_first_free_port 39160 "$mysql_port")"; need_derive=1
    log "host port $AINATIVE_E2E_REDIS_PORT busy; remapping redis to $redis_port"
  fi
  if [[ "$(ainative_e2e_port_listeners "$minio_port")" != "0" ]] || ainative_e2e_port_reserved "$minio_port" "$mysql_port" "$redis_port"; then
    minio_port="$(ainative_e2e_first_free_port 39260 "$mysql_port" "$redis_port")"
    log "host port $AINATIVE_E2E_MINIO_PORT busy; remapping minio API to $minio_port"
  fi
  if [[ "$(ainative_e2e_port_listeners "$minio_console_port")" != "0" ]] || ainative_e2e_port_reserved "$minio_console_port" "$mysql_port" "$redis_port" "$minio_port"; then
    minio_console_port="$(ainative_e2e_first_free_port 39360 "$mysql_port" "$redis_port" "$minio_port")"
    log "host port $AINATIVE_E2E_MINIO_CONSOLE_PORT busy; remapping minio console to $minio_console_port"
  fi
  if [[ "$(ainative_e2e_port_listeners "$app_port")" != "0" ]] || ainative_e2e_port_reserved "$app_port" "$mysql_port" "$redis_port" "$minio_port" "$minio_console_port"; then
    app_port="$(ainative_e2e_first_free_port 39460 "$mysql_port" "$redis_port" "$minio_port" "$minio_console_port")"
    log "host port $AINATIVE_E2E_APP_PORT busy; remapping application to $app_port"
  fi

  if [[ "$need_derive" == "1" ]]; then
    sed -e "s|\"[0-9]*:3306\"|\"$mysql_port:3306\"|" \
        -e "s|\"[0-9]*:6379\"|\"$redis_port:6379\"|" \
        -e "s|../ainative-schema.sql|$REPO_ROOT/docs/ainative-schema.sql|" \
        "$base" >"$derived"
    AINATIVE_E2E_COMPOSE_ARGS=(-f "$derived" -f "$AINATIVE_E2E_LAYER_COMPOSE")
    COMPOSE_BASE_MODE="derived (host ports substituted; community file unchanged on disk)"
  else
    AINATIVE_E2E_COMPOSE_ARGS=(-f "$base" -f "$AINATIVE_E2E_LAYER_COMPOSE")
    COMPOSE_BASE_MODE="verbatim"
  fi
  RESOLVED_MYSQL_PORT="$mysql_port"
  RESOLVED_REDIS_PORT="$redis_port"
  RESOLVED_MINIO_PORT="$minio_port"
  RESOLVED_MINIO_CONSOLE_PORT="$minio_console_port"
  RESOLVED_APP_PORT="$app_port"
  AINATIVE_E2E_MINIO_PORT="$minio_port"
  AINATIVE_E2E_MINIO_CONSOLE_PORT="$minio_console_port"
  AINATIVE_E2E_APP_PORT="$app_port"
  export RESOLVED_MYSQL_PORT RESOLVED_REDIS_PORT
  export RESOLVED_MINIO_PORT RESOLVED_MINIO_CONSOLE_PORT RESOLVED_APP_PORT
  export AINATIVE_E2E_MINIO_PORT AINATIVE_E2E_MINIO_CONSOLE_PORT AINATIVE_E2E_APP_PORT COMPOSE_BASE_MODE

  # Persist the resolution so smoke.sh and down.sh replay exactly what up.sh
  # used instead of re-deriving it and possibly landing on different ports.
  {
    printf 'RESOLVED_MYSQL_PORT=%s\n' "$RESOLVED_MYSQL_PORT"
    printf 'RESOLVED_REDIS_PORT=%s\n' "$RESOLVED_REDIS_PORT"
    printf 'RESOLVED_MINIO_PORT=%s\n' "$RESOLVED_MINIO_PORT"
    printf 'RESOLVED_MINIO_CONSOLE_PORT=%s\n' "$RESOLVED_MINIO_CONSOLE_PORT"
    printf 'RESOLVED_APP_PORT=%s\n' "$RESOLVED_APP_PORT"
    printf 'AINATIVE_E2E_MINIO_PORT=%s\n' "$AINATIVE_E2E_MINIO_PORT"
    printf 'AINATIVE_E2E_MINIO_CONSOLE_PORT=%s\n' "$AINATIVE_E2E_MINIO_CONSOLE_PORT"
    printf 'AINATIVE_E2E_APP_PORT=%s\n' "$AINATIVE_E2E_APP_PORT"
    printf 'AINATIVE_E2E_COMPOSE_ARGS=(%s)\n' "$(printf '%q ' "${AINATIVE_E2E_COMPOSE_ARGS[@]}")"
  } >"$AINATIVE_E2E_STATE_DIR/resolved.env"
}

# Loads a previous resolution written by ainative_e2e_resolve_compose.
ainative_e2e_load_resolved() {
  [[ -f "$AINATIVE_E2E_STATE_DIR/resolved.env" ]] || return 1
  # shellcheck disable=SC1091
  source "$AINATIVE_E2E_STATE_DIR/resolved.env"
  return 0
}

ainative_e2e_compose() {
  ainative_e2e_resolve_compose
  # compose.e2e.yml declares MINIO_ROOT_PASSWORD as a required interpolation,
  # and compose re-parses every file for every subcommand - including exec and
  # ps - so the value has to be present for all of them, not just up.
  if [[ -z "${AINATIVE_E2E_MINIO_ROOT_PASSWORD:-}" && -f "$AINATIVE_E2E_STATE_DIR/secrets.env" ]]; then
    AINATIVE_E2E_MINIO_ROOT_PASSWORD="$(ainative_e2e_secret AINATIVE_E2E_MINIO_ROOT_PASSWORD)"
    export AINATIVE_E2E_MINIO_ROOT_PASSWORD
  fi
  "$DOCKER" compose -p "$AINATIVE_E2E_PROJECT" "${AINATIVE_E2E_COMPOSE_ARGS[@]}" "$@"
}
