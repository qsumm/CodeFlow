import { useTranslation } from 'react-i18next';
import { Typography } from 'antd';
import type { DispatchAttempt, StepTiming } from '@/shared/types/workitem';
import type { ConvergedAgentProgress, ConvergedDeliveryStep } from './deliveryTypes';

const { Text } = Typography;

export type ExecutionEntry = { agent: ConvergedAgentProgress; dispatchId: number; attempt: DispatchAttempt; steps: ConvergedDeliveryStep[] };

export function stageFromTiming(timing: StepTiming): ConvergedDeliveryStep {
  return {
    stepId: timing.stepId,
    stepKey: null,
    name: timing.name,
    status: timing.status,
    planStatus: null,
    sourceAttempt: null,
    agentName: null,
    executorId: null,
    executorName: null,
    error: null,
    subSteps: null,
    durationMs: timing.durationMs,
    attempts: null,
    usage: null,
  };
}

// 后端按 dispatch 投影的阶段快照：本 dispatch 执行过的阶段即使没有落在派生 attempt 关联里
// （例如同一 dispatch 连续跑完入口阶段与后续阶段）也必须进入详情；pending 表示该 dispatch
// 未执行该阶段，不进详情，保证切换 dispatch 时只看到当前所选执行的阶段。
export function withExecutionStages(entry: ExecutionEntry): ExecutionEntry {
  const timings = entry.attempt.stepTimings;
  if (!timings?.length) return entry;
  const known = new Map(entry.steps.map(step => [step.stepId, step]));
  const snapshotIds = new Set(timings.map(timing => timing.stepId));
  const stages = timings.flatMap(timing => {
    const step = known.get(timing.stepId);
    if (step) return [step];
    return timing.status === 'pending' ? [] : [stageFromTiming(timing)];
  });
  if (!stages.length) return entry;
  return { ...entry, steps: [...stages, ...entry.steps.filter(step => !snapshotIds.has(step.stepId))] };
}

// Dispatch IDs are creation-ordered; traversing agent/step arrays is not a handoff timeline.
export function executionEntries(agents: ConvergedAgentProgress[], executions?: DispatchAttempt[] | null): ExecutionEntry[] {
  const entries = new Map<number, ExecutionEntry>();
  agents.forEach(agent => agent.steps.forEach(step => step.attempts?.forEach(attempt => {
    const existing = entries.get(attempt.dispatchId);
    if (existing) {
      if (!existing.steps.some(value => value.stepId === step.stepId)) existing.steps.push(step);
      // A later step may carry more recent status/action metadata for the same dispatch.
      if (attempt.control || attempt.canPause || attempt.canContinue) existing.attempt = { ...existing.attempt, ...attempt };
    } else entries.set(attempt.dispatchId, { agent, dispatchId: attempt.dispatchId, attempt, steps: [step] });
  })));
  executions?.forEach(attempt => {
    const existing = entries.get(attempt.dispatchId);
    if (existing) {
      existing.attempt = {
        ...existing.attempt,
        ...attempt,
        control: attempt.control ?? existing.attempt.control,
        canPause: attempt.control?.pause?.allowed ?? existing.attempt.canPause,
        canContinue: attempt.control?.resume?.allowed ?? existing.attempt.canContinue,
        cancelRequested: attempt.cancelRequested ?? existing.attempt.cancelRequested,
        stopPending: attempt.stopPending ?? existing.attempt.stopPending,
        forced: attempt.forced ?? existing.attempt.forced,
      };
    } else {
      const agent = agents.find(value => value.agentId === attempt.agentId) ?? {
        agentId: attempt.agentId ?? -attempt.dispatchId, agentName: attempt.agentName ?? '—',
        status: 'pending' as const, durationMs: null, steps: [],
      };
      entries.set(attempt.dispatchId, { agent, dispatchId: attempt.dispatchId, attempt, steps: [] });
    }
  });
  return [...entries.values()].sort((a, b) => a.dispatchId - b.dispatchId).map(withExecutionStages);
}

export function DispatchFailureDetails({ dispatchId, error, status }: { dispatchId: number; error: string; status: string | null }) {
  const { t } = useTranslation('workitem');
  const heading = t(status === 'FAILED' || status === 'TIMEOUT' ? 'delivery.dispatchFailure' : 'delivery.dispatchDiagnostic', { id: dispatchId });
  const checklist = /checklist not fully checked: "([^"]+)" remains pending/.exec(error);
  const artifact = /code=ARTIFACT_REFERENCE_INVALID path="([^"]+)"/.exec(error);
  return <div role="note" aria-label={heading}>
    <Text strong>{heading}</Text>
    {checklist && <div>{t('delivery.checklistPending', { id: checklist[1] })}</div>}
    {artifact && <div>{t('delivery.invalidArtifactReference', { path: artifact[1] })}</div>}
    <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', color: 'var(--cf-error)' }}>{error}</pre>
  </div>;
}
