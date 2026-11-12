import { formatDuration } from '../duration';
import { useTranslation } from 'react-i18next';
import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { Card, Typography, Space, Spin, Tooltip, Alert } from 'antd';
import type { AgentDeliveryProgress, Artifact, DeliveryProgress as DeliveryProgressModel, DeliveryStep, DispatchAttempt } from '@/shared/types/workitem';
import { AuthoritativeExecutionProcessGraph } from './ExecutionProcessGraph';
import { WorkflowPlanSummary } from './DeliveryPlanSummary';
import { ExecutionActionBar } from './ExecutionActionBar';
import type { ExecutionActionKind } from '../hooks';
import { StepList, convergeStepsForTerminalStatus, normalizeTerminalStatus } from './DeliverySteps';
import { DeliveryWorkbench } from './DeliveryWorkbench';

const { Text } = Typography;



interface DeliveryProgressProps {
  showWorkflowPlan?: boolean;
  renderControlTimeline?: (dispatchId: number, compact?: boolean) => ReactNode;
  renderRecovery?: (dispatchId: number) => ReactNode;
  restartHistory?: ReactNode;
  steps?: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  artifacts?: Artifact[];
  artifactsLoading?: boolean;
  loading?: boolean;
  terminalStatus?: string | null;
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
  onExecutionAction?: (attempt: DispatchAttempt, action: ExecutionActionKind) => void;
  pendingExecutionAction?: { dispatchId: number; action: ExecutionActionKind } | null;
}

export { formatDuration } from '../duration';
export { convergeStepsForTerminalStatus } from './DeliverySteps';
export type { StepDisplayStatus, ConvergedDeliveryStep, ConvergedAgentProgress } from './deliveryTypes';
import type { ConvergedAgentProgress } from './deliveryTypes';






export function DeliveryProgress({ showWorkflowPlan = true, steps = [], progress, artifacts = [], artifactsLoading, loading, terminalStatus, onContinue, onPause, onExecutionAction, pendingExecutionAction, renderRecovery, renderControlTimeline, restartHistory }: DeliveryProgressProps) {
  const { t } = useTranslation('workitem');
  const handleExecutionAction = onExecutionAction ?? ((attempt: DispatchAttempt, action: ExecutionActionKind) => {
    if (action === 'pause') onPause?.(attempt.dispatchId);
    if (action === 'resume') onContinue?.(attempt.dispatchId);
  });

  const agents = useMemo(() => {
    const terminal = normalizeTerminalStatus(terminalStatus);
    const agents: ConvergedAgentProgress[] = (progress?.agents ?? []).map((agent) => ({
      ...agent,
      status: terminal && (agent.status === 'active' || agent.status === 'paused')
        ? terminal === 'SUCCEEDED' ? 'finished' : terminal === 'FAILED' ? 'failed' : 'cancelled'
        : agent.status,
      steps: convergeStepsForTerminalStatus(agent.steps, terminalStatus),
    }));
    const seenAgentIds = new Set<number>();
    [...(progress?.executions ?? [])].sort((a, b) => b.dispatchId - a.dispatchId).forEach(execution => {
      const agentId = execution.agentId ?? -execution.dispatchId;
      if (seenAgentIds.has(agentId)) return;
      seenAgentIds.add(agentId);
      const status: AgentDeliveryProgress['status'] = execution.status === 'SUCCEEDED' ? 'finished'
        : execution.status === 'FAILED' || execution.status === 'TIMEOUT' ? 'failed'
        : execution.status === 'CANCELED' ? 'cancelled' : execution.status === 'PAUSED' ? 'paused' : 'active';
      const existing = agents.find(agent => agent.agentId === agentId);
      if (existing) {
        if (!terminal) {
          existing.status = status;
          existing.currentDispatchId = execution.dispatchId;
          existing.executionStatus = execution.status;
          existing.waitingReason = execution.waitingReason;
        }
        return;
      }
      agents.push({ agentId, agentName: execution.agentName ?? '—', currentDispatchId: execution.dispatchId, executionStatus: execution.status, waitingReason: execution.waitingReason,
        status: terminal ? terminal === 'SUCCEEDED' ? 'finished' : terminal === 'FAILED' ? 'failed' : 'cancelled' : status,
        durationMs: execution.durationMs, steps: [] });
    });
    return agents;
  }, [progress?.agents, progress?.executions, terminalStatus]);

  if (loading) {
    return (
      <Card size="small" title={t('delivery.title')}>
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  const displaySteps = agents.length > 0 ? [] : convergeStepsForTerminalStatus(progress?.steps ?? steps, terminalStatus);
  const stepActionAttempt = agents.length === 0
    ? displaySteps.flatMap(step => step.attempts ?? []).find(attempt => attempt.canContinue || attempt.canPause) ?? null
    : null;
  const cardTitle = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ flex: 1 }}>{t('delivery.title')}</span>
      {progress?.totalDurationMs != null && (
        <Tooltip title={t('delivery.durationHint')}>
          <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            {t('delivery.totalDuration', { duration: formatDuration(progress.totalDurationMs) })}
          </Text>
        </Tooltip>
      )}
    </div>
  );

  if (agents.length > 0) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {showWorkflowPlan && progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
        {progress?.closed && <Alert showIcon type="warning" message={t('recovery.deliveryClosed')} description={t('recovery.closeHint')} />}
        <Card size="small" title={cardTitle} data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
          <DeliveryWorkbench renderControlTimeline={renderControlTimeline} renderRecovery={renderRecovery} restartHistory={restartHistory} agents={agents} progress={progress} artifacts={artifacts} artifactsLoading={artifactsLoading}
            onExecutionAction={handleExecutionAction} pendingExecutionAction={pendingExecutionAction} />
        </Card>
        <AuthoritativeExecutionProcessGraph processGraph={progress?.processGraph} />
      </Space>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      {showWorkflowPlan && progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
      {progress?.closed && <Alert showIcon type="warning" message={t('recovery.deliveryClosed')} description={t('recovery.closeHint')} />}
      <Card size="small" title={cardTitle} data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
        {stepActionAttempt && (
          <div style={{ marginBottom: 6 }}>
            <ExecutionActionBar
              attempt={stepActionAttempt}
              pending={pendingExecutionAction}
              onAction={(action) => handleExecutionAction(stepActionAttempt, action)}
            />
          </div>
        )}
        <StepList steps={displaySteps} renderControlTimeline={renderControlTimeline} />
      </Card>
    </Space>
  );
}

export { WorkflowPlanSummary } from './DeliveryPlanSummary';
