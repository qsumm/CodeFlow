#!/usr/bin/env bash
set -euo pipefail

if [[ "${AINATIVE_DOCKER_RELEASE_GATE:-}" != "true" ]]; then
  echo "V037 Docker release gate requires AINATIVE_DOCKER_RELEASE_GATE=true" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

test_classes=(
  DockerReleaseGateIT
  V037LegacyWorkitemIntegrationTest
  V037CompatibilityMatrixTest
  ScheduledTaskEndToEndTest
  ScheduledTaskConcurrencyTest
  ScheduledTaskSpringMybatisIntegrationTest
  V037SchemaCapabilityDetectorMySqlTest
  V037LegacyArtifactServiceFlowMySqlTest
  MemoryBehaviorLoopMySqlTest
  MemoryTopicTransactionMySqlTest
  DispatchControlEventMySqlTest
  ExecutorLaunchConfigRecoveryMySqlTest
  DispatchResultConcurrencyMySqlTest
  InteractionWaiterRecoveryMySqlTest
  ExternalWorkitemLinkConcurrencyMySqlTest
  ExternalWorkitemIdentityMigrationMySqlTest
  SharedGroupChannelMySqlTest
  InsightsDeliveryMySqlTest
  SquadWorkflowConcurrencyMySqlTest
  SdlcStructureMySqlTest
)
report_classes=(
  com.ainative.scheduledtask.DockerReleaseGateIT
  com.ainative.scheduledtask.V037LegacyWorkitemIntegrationTest
  com.ainative.scheduledtask.V037CompatibilityMatrixTest
  com.ainative.scheduledtask.ScheduledTaskEndToEndTest
  com.ainative.scheduledtask.ScheduledTaskConcurrencyTest
  com.ainative.scheduledtask.ScheduledTaskSpringMybatisIntegrationTest
  com.ainative.scheduledtask.V037SchemaCapabilityDetectorMySqlTest
  com.ainative.artifact.V037LegacyArtifactServiceFlowMySqlTest
  com.ainative.memory.store.MemoryBehaviorLoopMySqlTest
  com.ainative.memory.store.MemoryTopicTransactionMySqlTest
  com.ainative.dispatch.DispatchControlEventMySqlTest
  com.ainative.executor.ExecutorLaunchConfigRecoveryMySqlTest
  com.ainative.dispatch.DispatchResultConcurrencyMySqlTest
  com.ainative.guidance.InteractionWaiterRecoveryMySqlTest
  com.ainative.integration.ExternalWorkitemLinkConcurrencyMySqlTest
  com.ainative.integration.ExternalWorkitemIdentityMigrationMySqlTest
  com.ainative.integration.SharedGroupChannelMySqlTest
  com.ainative.insights.InsightsDeliveryMySqlTest
  com.ainative.squad.workflow.SquadWorkflowConcurrencyMySqlTest
  com.ainative.sdlc.SdlcStructureMySqlTest
)
test_selector="$(IFS=,; echo "${test_classes[*]}")"

mvn -q \
  -DskipFrontend=true \
  -DskipGitCommitId=true \
  -Dainative.docker.release.gate=true \
  "-Dtest=${test_selector}" \
  clean test

total_tests=0
for ((index = 0; index < ${#test_classes[@]}; index++)); do
  test_class="${test_classes[${index}]}"
  report="target/surefire-reports/TEST-${report_classes[${index}]}.xml"
  if [[ ! -f "${report}" ]]; then
    echo "missing current Surefire report: ${report}" >&2
    exit 3
  fi

  suite="$(grep -m 1 '<testsuite ' "${report}")"
  tests="$(printf '%s\n' "${suite}" | sed -nE 's/.* tests="([0-9]+)".*/\1/p')"
  failures="$(printf '%s\n' "${suite}" | sed -nE 's/.* failures="([0-9]+)".*/\1/p')"
  errors="$(printf '%s\n' "${suite}" | sed -nE 's/.* errors="([0-9]+)".*/\1/p')"
  skipped="$(printf '%s\n' "${suite}" | sed -nE 's/.* skipped="([0-9]+)".*/\1/p')"

  for value in "${tests}" "${failures}" "${errors}" "${skipped}"; do
    if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
      echo "invalid Surefire counters in ${report}" >&2
      exit 4
    fi
  done
  if (( tests == 0 || failures != 0 || errors != 0 || skipped != 0 )); then
    echo "failed release report ${test_class}: tests=${tests} failures=${failures} errors=${errors} skipped=${skipped}" >&2
    exit 5
  fi
  total_tests=$((total_tests + tests))
done

if (( total_tests == 0 )); then
  echo "V037 Docker release gate executed no tests" >&2
  exit 6
fi

echo "V037 Docker release gate passed: tests=${total_tests} failures=0 errors=0 skipped=0"
