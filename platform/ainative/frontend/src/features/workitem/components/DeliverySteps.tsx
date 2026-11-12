import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Collapse, Space, Tag, Tooltip, Typography } from 'antd';
import { CheckCircleFilled, ClockCircleOutlined, CloseCircleFilled, LoadingOutlined, PauseCircleOutlined } from '@ant-design/icons';
import type { DeliveryStep, DispatchAttempt, SubStep } from '@/shared/types/workitem';
import { formatDuration } from '../duration';
import { dispatchExecutionLabel, subStepName } from '../runtimeLabels';
import type { ConvergedDeliveryStep, StepDisplayStatus } from './deliveryTypes';
import { DispatchActivityFeed } from './DispatchActivityFeed';
import { StepTokenBadge } from './TokenUsageBadge';

const { Text } = Typography;

type DisplayAttempt = DispatchAttempt & {
  displayIndex: number;
  isActivityFeedHost: boolean;
};

type TerminalKind = 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export function normalizeTerminalStatus(terminalStatus?: string | null): TerminalKind | null {
  if (!terminalStatus) return null;
  const value = terminalStatus.trim().toUpperCase();
  if (value === 'SUCCEEDED' || value === 'FAILED' || value === 'CANCELLED') return value;
  if (value === 'CANCELED') return 'CANCELLED';
  if (value === 'CLOSED' || terminalStatus.trim() === '已关闭') return 'SUCCEEDED';
  return null;
}

function convergedSubStepStatus(status: SubStep['status'], terminal: TerminalKind, isFailurePoint: boolean): SubStep['status'] | 'cancelled' {
  if (status !== 'active') return status;
  if (terminal === 'SUCCEEDED') return 'done';
  if (terminal === 'CANCELLED') return 'cancelled';
  return isFailurePoint ? 'failed' : 'done';
}

// 终态收敛：run/工单已终态但步骤事件流不完整（缺 completed 事件）时，把仍为 active 的步骤映射到与终态一致的展示状态。
export function convergeStepsForTerminalStatus(steps: DeliveryStep[], terminalStatus?: string | null): ConvergedDeliveryStep[] {
  const terminal = normalizeTerminalStatus(terminalStatus);
  if (!terminal) return steps.map((step) => ({ ...step }));

  let lastActiveIndex = -1;
  if (terminal === 'FAILED') {
    for (let i = steps.length - 1; i >= 0; i--) {
      if (steps[i].status === 'active') {
        lastActiveIndex = i;
        break;
      }
    }
  }

  return steps.map((step, index) => {
    if (step.status !== 'active') return { ...step };
    const isFailurePoint = index === lastActiveIndex;
    const status: StepDisplayStatus = terminal === 'SUCCEEDED'
      ? 'done'
      : terminal === 'CANCELLED'
        ? 'cancelled'
        : isFailurePoint ? 'failed' : 'done';
    return {
      ...step,
      status,
      subSteps: step.subSteps?.map((sub) => ({ ...sub, status: convergedSubStepStatus(sub.status, terminal, isFailurePoint) })) ?? null,
    };
  });
}

export function StepIcon({ step, index, pausing = false }: { step: ConvergedDeliveryStep; index: number; pausing?: boolean }) {
  if (step.status === 'done' || step.status === 'reused' || step.planStatus === 'REUSED') {
    return <CheckCircleFilled aria-hidden="true" style={{ color: 'var(--cf-success)', fontSize: 16 }} />;
  }
  if (pausing) {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--cf-warning)', fontSize: 16 }} />;
  }
  if (step.status === 'active' && step.executionStatus && step.executionStatus !== 'RUNNING') {
    return <ClockCircleOutlined aria-hidden="true" style={{ color: 'var(--cf-muted)', fontSize: 16 }} />;
  }
  if (step.status === 'active') {
    return <LoadingOutlined aria-hidden="true" style={{ color: 'var(--cf-accent-text)', fontSize: 16 }} />;
  }
  if (step.status === 'cancelled') {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--cf-muted)', fontSize: 16 }} />;
  }
  if (step.status === 'failed') {
    return <CloseCircleFilled aria-hidden="true" style={{ color: 'var(--cf-error)', fontSize: 16 }} />;
  }
  if (step.status === 'paused') {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--cf-warning)', fontSize: 16 }} />;
  }
  if (step.status === 'skipped' || step.status === 'stale' || step.status === 'interrupted' || step.planStatus === 'SKIPPED') {
    return <span style={{ color: step.status === 'stale' || step.status === 'interrupted' ? 'var(--cf-warning)' : 'var(--cf-muted)', fontSize: 11 }}>—</span>;
  }
  // pending
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 16,
        height: 16,
        borderRadius: '50%',
        border: '1.5px solid var(--cf-border)',
        fontSize: 10,
        color: 'var(--cf-muted)',
        lineHeight: 1,
      }}
    >
      {index + 1}
    </span>
  );
}

function SubStepDot({ status }: { status: SubStep['status'] | 'cancelled' }) {
  const colorMap: Record<SubStep['status'] | 'cancelled', string> = {
    done: 'var(--cf-success)',
    active: 'var(--cf-accent-text)',
    pending: 'var(--cf-border)',
    failed: 'var(--cf-error)',
    cancelled: 'var(--cf-muted)',
  };
  return (
    <span
      style={{
        display: 'inline-block',
        width: 6,
        height: 6,
        borderRadius: '50%',
        backgroundColor: colorMap[status],
        marginRight: 6,
        flexShrink: 0,
      }}
    />
  );
}

function findActionTargetStep(steps: ConvergedDeliveryStep[], ownerStep: ConvergedDeliveryStep, attempt: DispatchAttempt): ConvergedDeliveryStep | null {
  if (attempt.resumeMode === 'COMMENT_INTERACTION'
    || attempt.resumeMode === 'SIDE_INTERACTION'
    || attempt.resumeMode === 'CANONICAL_INTERACTION') {
    return null;
  }
  if (attempt.canPause) {
    if (ownerStep.status === 'active') {
      return ownerStep;
    }
    return steps.find((step) => step.status === 'active') ?? null;
  }
  if (attempt.canContinue) {
    if (ownerStep.status === 'paused' || ownerStep.status === 'failed') {
      return ownerStep;
    }
    return steps.find((step) => step.status === 'paused' || step.status === 'failed') ?? null;
  }
  return null;
}

function buildAttemptViews(steps: ConvergedDeliveryStep[]): Map<number, DisplayAttempt[]> {
  const byStep = new Map<number, DisplayAttempt[]>();
  const relocatedActions = new Map<number, Set<number>>();

  steps.forEach((step) => {
    step.attempts?.forEach((attempt, i) => {
      const target = findActionTargetStep(steps, step, attempt);
      if (target && target.stepId !== step.stepId) {
        relocatedActions.set(step.stepId, (relocatedActions.get(step.stepId) ?? new Set()).add(attempt.dispatchId));
        byStep.set(target.stepId, [
          ...(byStep.get(target.stepId) ?? []),
          { ...attempt, displayIndex: i, isActivityFeedHost: true },
        ]);
      }
    });
  });

  steps.forEach((step) => {
    const strippedActionIds = relocatedActions.get(step.stepId);
    const attempts = step.attempts?.map((attempt, i) => {
      const isRelocatedAction = strippedActionIds?.has(attempt.dispatchId) ?? false;
      return {
        ...attempt,
        ...(isRelocatedAction ? { canContinue: false, canPause: false } : {}),
        displayIndex: i,
        isActivityFeedHost: !isRelocatedAction,
      };
    }) ?? [];
    byStep.set(step.stepId, [...attempts, ...(byStep.get(step.stepId) ?? [])]);
  });

  return byStep;
}

export function stepIsPausing(step: ConvergedDeliveryStep): boolean {
  return step.attempts?.some((attempt) => attempt.status === 'PAUSING') ?? false;
}

function StepCard({ step, index, attempts, renderControlTimeline }: {
  step: ConvergedDeliveryStep;
  index: number;
  attempts: DisplayAttempt[];
  renderControlTimeline?: (dispatchId: number, compact?: boolean) => ReactNode;
}) {
  const { t } = useTranslation('workitem');
  const isCancelling = attempts.some((attempt) => attempt.cancelRequested && attempt.status === 'PAUSING');
  const isPausing = !isCancelling && attempts.some((attempt) => attempt.status === 'PAUSING');
  const isActive = step.status === 'active';
  const isPaused = step.status === 'paused';
  const isCancelled = step.status === 'cancelled';
  const isDone = step.status === 'done' || step.status === 'reused' || step.status === 'skipped'
    || step.planStatus === 'REUSED' || step.planStatus === 'SKIPPED';
  const isFailed = step.status === 'failed';
  const isInterrupted = step.status === 'interrupted';
  const executionLabel = step.planStatus === 'SKIPPED' ? t('delivery.noExecution') : isDone ? t('delivery.completed') : isCancelling ? t('delivery.ending') : isPausing ? t('delivery.pausing') : isActive ? step.executionStatus ? dispatchExecutionLabel(step.executionStatus, step.waitingReason) : t('delivery.running') : isPaused ? t('delivery.paused') : isCancelled ? t('delivery.cancelled') : isFailed ? t('delivery.failed') : isInterrupted ? t('stepTiming.status.interrupted') : t('delivery.pending');
  const planLabel = step.planStatus === 'RUN'
    ? t('delivery.rerun')
    : step.planStatus === 'REUSED'
      ? step.sourceAttempt ? t('delivery.reusedResult', { number: step.sourceAttempt }) : t('delivery.reusedPrevious')
      : step.planStatus === 'SKIPPED'
        ? t('delivery.skipped')
        : null;
  const hasAttemptDetails = attempts.length > 0 || Boolean(step.agentName) || Boolean(step.executorName) || Boolean(step.subSteps?.length);
  const attentionStatus = isActive || isPaused || isFailed;
  const [recordsOpen, setRecordsOpen] = useState(attentionStatus);
  const previousAttentionStatus = useRef(attentionStatus);
  const attemptRecords = attempts.length > 0 ? attempts : [];

  useEffect(() => {
    if (attentionStatus && !previousAttentionStatus.current) {
      setRecordsOpen(true);
    }
    previousAttentionStatus.current = attentionStatus;
  }, [attentionStatus]);

  return (
    <div
      data-testid={`delivery-step-${step.stepId}`}
      style={{
        padding: '6px 8px',
        borderRadius: 6,
        border: isPausing || isPaused ? '1px solid color-mix(in srgb, var(--cf-warning) 35%, var(--cf-border))' : isActive ? '1px solid var(--cf-accent-text)' : isFailed ? '1px solid color-mix(in srgb, var(--cf-error) 35%, var(--cf-border))' : '1px solid var(--cf-border)',
        backgroundColor: isPausing || isPaused ? 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))' : isActive ? 'rgba(var(--cf-accent-rgb),.10)' : isFailed ? 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))' : 'var(--cf-panel)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <StepIcon step={step} index={index} pausing={isPausing} />
        <Text
          style={{
            flex: 1,
            fontSize: 12,
            color: isDone ? 'var(--cf-muted)' : isPausing || isPaused ? 'var(--cf-warning)' : isActive ? 'var(--cf-accent-text)' : isFailed ? 'var(--cf-error)' : isInterrupted ? 'var(--cf-warning)' : 'var(--cf-muted)',
            fontWeight: isActive || isPausing || isPaused || isFailed ? 500 : 400,
          }}
        >
          {step.name}
        </Text>
        {step.error && (
          <Tooltip title={step.error}>
            <Tag color="error" style={{ marginInlineEnd: 0, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {t('delivery.failureDetail', { error: step.error })}
            </Tag>
          </Tooltip>
        )}
        <Text
          type="secondary"
          style={{ fontSize: 11, color: isPausing || isPaused ? 'var(--cf-warning)' : isActive ? 'var(--cf-accent-text)' : isFailed ? 'var(--cf-error)' : isInterrupted ? 'var(--cf-warning)' : undefined }}
        >
          {planLabel ? t('delivery.stepStatus', { plan: planLabel, status: executionLabel }) : step.status === 'reused' ? t('delivery.reusedPrevious') : step.status === 'skipped' ? t('delivery.skipped') : step.status === 'stale' ? t('delivery.stale') : executionLabel}
          {step.durationMs != null && `  ${formatDuration(step.durationMs)}`}
        </Text>
        {step.usage && <StepTokenBadge usage={step.usage} />}
      </div>

      {hasAttemptDetails && (
        <div style={{ marginTop: 4, paddingLeft: 22 }}>
          <Collapse
            ghost
            size="small"
            activeKey={recordsOpen ? ['records'] : []}
            onChange={(keys) => setRecordsOpen((Array.isArray(keys) ? keys : [keys]).includes('records'))}
            items={[
              {
                key: 'records',
                label: <Text type="secondary" style={{ fontSize: 12 }}>{t('artifacts.runtime')}</Text>,
                children: (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    {step.agentName && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                        {t('delivery.executor', { name: step.agentName })}
                      </Text>
                    )}
                    {step.executorName && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                        {t('delivery.runner', { name: step.executorName })}
                      </Text>
                    )}
                    {recordsOpen && attemptRecords.map((attempt) => (
                      <div key={String(attempt.dispatchId)}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Text type="secondary" style={{ fontSize: 11, flex: 1 }}>
                            {attempt.executorName
                              ? t('delivery.attemptOnRunner', { number: attempt.displayIndex + 1, name: attempt.agentName ?? '—', runner: attempt.executorName, status: dispatchExecutionLabel(attempt.status, attempt.waitingReason) })
                              : t('delivery.attempt', { number: attempt.displayIndex + 1, name: attempt.agentName ?? '—', status: dispatchExecutionLabel(attempt.status, attempt.waitingReason) })}
                            {attempt.durationMs != null && ` · ${formatDuration(attempt.durationMs)}`}
                            {attempt.error && ` · ${attempt.error}`}
                          </Text>
                        </div>
                        {recordsOpen && attempt.isActivityFeedHost && (
                          <>
                            {renderControlTimeline?.(attempt.dispatchId)}
                            <DispatchActivityFeed dispatchId={attempt.dispatchId} status={attempt.status} />
                          </>
                        )}
                      </div>
                    ))}
                    {step.subSteps && step.subSteps.length > 0 && (
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        {step.subSteps.map((sub, i) => (
                          <div key={i} style={{ display: 'flex', alignItems: 'center' }}>
                            <SubStepDot status={sub.status} />
                            <Text style={{ fontSize: 12, color: sub.status === 'pending' ? 'var(--cf-muted)' : 'var(--cf-muted)' }}>
                              {subStepName(sub)}
                            </Text>
                          </div>
                        ))}
                      </Space>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </div>
      )}
    </div>
  );
}

export function StepList({ steps, renderControlTimeline }: { steps: ConvergedDeliveryStep[]; renderControlTimeline?: (dispatchId: number, compact?: boolean) => ReactNode }) {
  const attemptsByStep = buildAttemptViews(steps);

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={4}>
      {steps.map((step, i) => (
        <StepCard
          key={String(step.stepId)}
          step={step}
          index={i}
          attempts={attemptsByStep.get(step.stepId) ?? []}
          renderControlTimeline={renderControlTimeline}
        />
      ))}
    </Space>
  );
}
