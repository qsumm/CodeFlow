#!/usr/bin/env bash
# Tear down everything up.sh created, and prove it is gone.
#
#   e2e-tests/down.sh
#
# Cleanup is asserted, not assumed. `compose down -v` removes the containers and
# the named volumes it created, but three classes of residue survive it and would
# silently corrupt the next "fresh install" run:
#
#   * containers and volumes left by an earlier interrupted run;
#   * the network, when its name was overridden and no longer matches the
#     project - compose then leaves it behind;
#   * the state directory, which holds the generated secrets. Leaving temporary
#     credentials on disk after a release check is exactly what must not happen.
#
# So this script removes the compose stack, sweeps by explicit name, re-checks
# the ports, and finally deletes the credentials. The teardown report is written
# outside the state directory, since that directory is what gets deleted.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=lib/common.sh
source e2e-tests/lib/common.sh

AINATIVE_E2E_TEARDOWN_REPORT="${AINATIVE_E2E_TEARDOWN_REPORT:-${TMPDIR:-/tmp}/ainative-e2e-${AINATIVE_E2E_PROJECT}-teardown.txt}"
export AINATIVE_E2E_TEARDOWN_REPORT

ainative_e2e_init_runtime
ainative_e2e_prepare_state_dir
if ! ainative_e2e_load_resolved; then
  log "no resolved.env from a previous up.sh; using knob defaults"
  ainative_e2e_resolve_compose
fi

log "tearing down project=$AINATIVE_E2E_PROJECT network=$AINATIVE_E2E_NETWORK report=$AINATIVE_E2E_TEARDOWN_REPORT"

# --- 1. the compose stack, including named volumes ---------------------------
if ainative_e2e_compose down -v --remove-orphans >"$AINATIVE_E2E_STATE_DIR/compose-down.log" 2>&1; then
  tail -12 "$AINATIVE_E2E_STATE_DIR/compose-down.log"
else
  log "compose down returned non-zero; continuing with the explicit sweep"
  tail -20 "$AINATIVE_E2E_STATE_DIR/compose-down.log" || true
fi

# --- 2. explicit sweep by name ----------------------------------------------
count_and_remove_containers() {
  local filter="$1" label="$2" found
  found="$("$DOCKER" ps -aq --filter "$filter" || true)"
  [[ -n "$found" ]] || return 0
  log "removing $(printf '%s\n' "$found" | wc -l | tr -d ' ') ${label} container(s)"
  # shellcheck disable=SC2086
  "$DOCKER" rm -f $found >/dev/null
}
count_and_remove_containers "label=com.docker.compose.project=${AINATIVE_E2E_PROJECT}" "labelled"
count_and_remove_containers "name=^${AINATIVE_E2E_PROJECT}[-_]" "name-matched"

project_volumes="$("$DOCKER" volume ls --format '{{.Name}}' | grep "^${AINATIVE_E2E_PROJECT}_" || true)"
if [[ -n "$project_volumes" ]]; then
  log "removing volumes: $(printf '%s' "$project_volumes" | tr '\n' ' ')"
  # shellcheck disable=SC2086
  "$DOCKER" volume rm -f $project_volumes >/dev/null
fi

remove_network() {
  "$DOCKER" network inspect "$1" >/dev/null 2>&1 || return 0
  log "removing network $1"
  "$DOCKER" network rm "$1" >/dev/null || log "could not remove network $1 (in use?)"
}
remove_network "$AINATIVE_E2E_NETWORK"
# The layer file's default network name, in case an earlier run did not export
# AINATIVE_E2E_NETWORK and compose created its own.
remove_network ainative-e2e-net

# --- 3. any app process this harness started --------------------------------
# Legacy host-JVM runs are stopped only by their recorded PID. Never search for
# every ainative.jar process: another worktree may be under test concurrently.
if [[ -f "$AINATIVE_E2E_STATE_DIR/app.pid" ]]; then
  app_pid="$(cat "$AINATIVE_E2E_STATE_DIR/app.pid")"
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    log "stopping app pid=$app_pid"
    kill "$app_pid" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$app_pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$app_pid" 2>/dev/null || true
  fi
  rm -f "$AINATIVE_E2E_STATE_DIR/app.pid"
fi

# --- 4. assertions, written before the credentials are deleted ---------------
{
  echo "# teardown verification"
  echo "PROJECT=$AINATIVE_E2E_PROJECT"
  echo "NETWORK=$AINATIVE_E2E_NETWORK"
  echo "CONTAINERS_LEFT=$("$DOCKER" ps -aq --filter "label=com.docker.compose.project=${AINATIVE_E2E_PROJECT}" | wc -l | tr -d ' ')"
  echo "NAME_MATCHED_CONTAINERS_LEFT=$("$DOCKER" ps -aq --filter "name=^${AINATIVE_E2E_PROJECT}[-_]" | wc -l | tr -d ' ')"
  echo "VOLUMES_LEFT=$("$DOCKER" volume ls --format '{{.Name}}' | grep -c "^${AINATIVE_E2E_PROJECT}_" || true)"
  echo "NETWORK_PRESENT=$("$DOCKER" network inspect "$AINATIVE_E2E_NETWORK" >/dev/null 2>&1 && echo yes || echo no)"
  echo "DEFAULT_NETWORK_PRESENT=$("$DOCKER" network inspect ainative-e2e-net >/dev/null 2>&1 && echo yes || echo no)"
  echo "RECORDED_APP_PID_PRESENT=$([[ -f "$AINATIVE_E2E_STATE_DIR/app.pid" ]] && echo yes || echo no)"
  for p in "$RESOLVED_MYSQL_PORT" "$RESOLVED_REDIS_PORT" "$AINATIVE_E2E_MINIO_PORT" "$AINATIVE_E2E_MINIO_CONSOLE_PORT" "$AINATIVE_E2E_APP_PORT"; do
    echo "PORT_${p}_LISTENERS=$(ainative_e2e_port_listeners "$p")"
  done
} >"$AINATIVE_E2E_TEARDOWN_REPORT"
cat "$AINATIVE_E2E_TEARDOWN_REPORT"

# --- 5. credentials and state, deleted last ---------------------------------
secrets_removed="$(find "$AINATIVE_E2E_STATE_DIR" -maxdepth 1 -name '*.env' 2>/dev/null | wc -l | tr -d ' ')"
rm -rf "$AINATIVE_E2E_STATE_DIR"
{
  echo "CREDENTIAL_FILES_DELETED=$secrets_removed"
  echo "STATE_DIR_PRESENT=$([[ -d "$AINATIVE_E2E_STATE_DIR" ]] && echo yes || echo no)"
} >>"$AINATIVE_E2E_TEARDOWN_REPORT"
log "deleted state directory including $secrets_removed credential file(s)"
log "down.sh done"
