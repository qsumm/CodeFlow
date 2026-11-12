import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Empty, Modal, Select, Space, Tabs, Tag, Typography } from 'antd';
import { CompressOutlined, LoadingOutlined } from '@ant-design/icons';
import type { Artifact, DeliveryProgress as DeliveryProgressModel, DispatchAttempt } from '@/shared/types/workitem';
import { formatDateTime } from '@/shared/i18n/format';
import { formatDuration } from '../duration';
import { dispatchExecutionLabel } from '../runtimeLabels';
import type { ExecutionActionKind } from '../hooks';
import type { ConvergedAgentProgress, ConvergedDeliveryStep } from './deliveryTypes';
import { StepIcon, StepList, stepIsPausing } from './DeliverySteps';
import { DispatchFailureDetails, executionEntries } from './DeliveryExecutionEntries';
import type { ExecutionEntry } from './DeliveryExecutionEntries';
import { ArtifactGroup } from './ArtifactList';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { AuthoritativeExecutionProcessGraph, artifactsForAgent, orderedDispatchIds } from './ExecutionProcessGraph';
import { statusLabel } from './DeliveryPlanSummary';
import { DispatchActivityFeed } from './DispatchActivityFeed';
import { ExecutionActionBar } from './ExecutionActionBar';
import { LiveActivityPanel } from './LiveActivityPanel';
import { RuntimeLogPanel } from './RuntimeLogDrawer';
import { TokenUsageBadge } from './TokenUsageBadge';
import { StepDurationChart } from './StepDurationChart';
import './DeliveryWorkbench.css';

const { Text } = Typography;

type DeliveryWorkbenchProps = {
  agents: ConvergedAgentProgress[];
  progress: DeliveryProgressModel | null | undefined;
  artifacts: Artifact[];
  artifactsLoading?: boolean;
  onExecutionAction?: (attempt: DispatchAttempt, action: ExecutionActionKind) => void;
  pendingExecutionAction?: { dispatchId: number; action: ExecutionActionKind } | null;
  renderControlTimeline?: (dispatchId: number, compact?: boolean) => ReactNode;
  renderRecovery?: (dispatchId: number) => ReactNode;
  restartHistory?: ReactNode;
};

export function DeliveryWorkbench({ agents, progress, artifacts, artifactsLoading, onExecutionAction, pendingExecutionAction, renderRecovery, renderControlTimeline, restartHistory }: DeliveryWorkbenchProps) {
  const { t } = useTranslation('workitem');
  const entries = useMemo(() => executionEntries(agents, progress?.executions), [agents, progress?.executions]);
  const currentAgent = [...agents].filter(agent => agent.status === 'active').sort((a, b) =>
    Math.max(0, ...orderedDispatchIds(b)) - Math.max(0, ...orderedDispatchIds(a)))[0] ?? null;
  const attentionAgent = currentAgent ?? [...agents].reverse().find(agent => agent.status === 'paused' || agent.status === 'failed') ?? null;
  const currentEntry = currentAgent ? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId && ['PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'PAUSING', 'PAUSE_FAILED', 'WAITING_FOR_PAUSE'].includes(entry.attempt.status ?? ''))
    ?? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId && entry.attempt.canPause)
    ?? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId) : null;
  const defaultEntry = currentEntry ?? [...entries].reverse().find(entry => entry.agent.agentId === attentionAgent?.agentId) ?? entries[entries.length - 1];
  const [expanded, setExpanded] = useState(false);
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const [tab, setTab] = useState('terminal');
  const [pinnedDispatch, setPinnedDispatch] = useState<number | null>(null);
  const [pinnedAgent, setPinnedAgent] = useState<number | null>(null);
  const [selectedStepId, setSelectedStepId] = useState<number | null>(null);
  const [railVisible, setRailVisible] = useState(() => window.innerWidth >= 760);
  const [railWidth, setRailWidth] = useState(260);
  const [showAllExecutions, setShowAllExecutions] = useState(false);
  const followCurrent = pinnedDispatch === null && pinnedAgent === null;
  const selectedEntry = pinnedDispatch !== null ? entries.find(entry => entry.dispatchId === pinnedDispatch) : pinnedAgent !== null ? [...entries].reverse().find(entry => entry.agent.agentId === pinnedAgent) : defaultEntry;
  const selectedAgent = pinnedAgent !== null ? agents.find(agent => agent.agentId === pinnedAgent) : selectedEntry?.agent ?? attentionAgent ?? agents[agents.length - 1];
  const selectedStep = selectedAgent?.steps.find(step => step.stepId === selectedStepId);
  const selectedIsTimeout = selectedEntry?.attempt.status === 'TIMEOUT';
  const currentStep = currentAgent?.steps.find(step => step.status === 'active');
  const selectedLive = selectedEntry?.dispatchId === currentEntry?.dispatchId && currentAgent !== null;
  const actionAttempt = defaultEntry?.attempt ?? null;
  const stopWarning = actionAttempt && actionAttempt.stopPending
    ? actionAttempt.status === 'TIMEOUT'
      ? t('delivery.timeoutStopUnconfirmed', { id: actionAttempt.dispatchId })
      : t('delivery.stopUnconfirmed', { id: actionAttempt.dispatchId })
    : null;
  const [handoffId, setHandoffId] = useState<number | null>(null);
  const previousCurrent = useRef(currentEntry?.dispatchId ?? null);
  useEffect(() => {
    const next = currentEntry?.dispatchId ?? null;
    if (previousCurrent.current !== null && next !== null && next !== previousCurrent.current) setHandoffId(next);
    previousCurrent.current = next;
  }, [currentEntry?.dispatchId]);
  const open = (nextTab: string) => { setTab(nextTab); setExpanded(true); };
  const selectEntry = (entry: ExecutionEntry) => { setPinnedDispatch(entry.dispatchId); setPinnedAgent(null); setSelectedStepId(null); };
  const returnToCurrent = () => { setPinnedDispatch(null); setPinnedAgent(null); setSelectedStepId(null); setHandoffId(null); };
  const attentionCancelling = attentionAgent?.steps.some(step => step.attempts?.some(attempt =>
    attempt.cancelRequested && attempt.status === 'PAUSING'));
  const attentionLabel = attentionCancelling ? { text: t('delivery.ending'), color: 'warning' } : attentionAgent?.steps.some(stepIsPausing) ? { text: t('delivery.pausing'), color: 'warning' } : attentionAgent?.steps.some(step => step.attempts?.some(attempt => attempt.error?.startsWith('CANCEL_REQUESTED:'))) ? { text: t('delivery.ending'), color: 'warning' } : attentionAgent ? statusLabel(attentionAgent.status, currentEntry?.attempt.status ?? attentionAgent.executionStatus, currentEntry?.attempt.waitingReason ?? attentionAgent.waitingReason) : defaultEntry ? statusLabel(defaultEntry.agent.status, defaultEntry.attempt.status, defaultEntry.attempt.waitingReason) : { text: t('workbench.noExecution'), color: 'default' };
  const stageStatus = currentStep ? [currentStep.planStatus === 'RUN' ? t('delivery.rerun') : null, formatDuration(currentStep.durationMs)].filter(Boolean).join(' · ') : null;
  const summary = <section className="delivery-workbench-current" data-testid="delivery-current-execution">
    <div className="delivery-workbench-heading">
      <div className="delivery-workbench-identity">
        {currentAgent && !currentAgent.steps.some(stepIsPausing) && <LoadingOutlined aria-hidden="true" className="delivery-workbench-active" />}
        <div>
          <Text strong data-testid="current-agent-name">{attentionAgent?.agentName ?? defaultEntry?.agent.agentName ?? t('workbench.noActiveAgent')}</Text>
          <Tag color={attentionLabel.color}>{attentionLabel.text}</Tag>
          {defaultEntry && <div className="delivery-workbench-meta">{t('workbench.sessionDuration', { id: defaultEntry.dispatchId, duration: formatDuration(defaultEntry.attempt.durationMs) })}{defaultEntry.attempt.executorName && <span>{t('delivery.runner', { name: defaultEntry.attempt.executorName })}</span>}</div>}
          {currentStep && <div className="delivery-workbench-step">{currentStep.name}</div>}
          {stageStatus && <Text type="secondary" style={{ fontSize: 12 }}>{stageStatus}</Text>}
        </div>
      </div>
      <Space wrap>
        {!expanded && <Button type="primary" onClick={() => open('terminal')}>{t('workbench.openTerminal')}</Button>}
        {actionAttempt && onExecutionAction && (
          <ExecutionActionBar
            attempt={actionAttempt}
            pending={pendingExecutionAction}
            onAction={(action) => onExecutionAction(actionAttempt, action)}
          />
        )}
      </Space>
    </div>
    {stopWarning && <Text style={{ display: 'block', fontSize: 12, color: 'var(--cf-warning)' }}>{stopWarning}</Text>}
    {attentionAgent?.currentActivity && <Text type="secondary" ellipsis={{ tooltip: attentionAgent.currentActivity }} data-testid="agent-current-activity">{attentionAgent.currentActivity}</Text>}
    {currentEntry && <div className="delivery-workbench-activity"><LiveActivityPanel key={currentEntry.dispatchId} dispatchId={currentEntry.dispatchId} enabled summaryOnly /></div>}
    {defaultEntry && renderControlTimeline?.(defaultEntry.dispatchId, true)}
  </section>;
  const recentEntries = [...entries].reverse().slice(0, 3);
  if (defaultEntry && !recentEntries.some(entry => entry.dispatchId === defaultEntry.dispatchId)) recentEntries[recentEntries.length - 1] = defaultEntry;
  const sidebarEntries = showAllExecutions ? [...entries].reverse() : selectedEntry && !recentEntries.some(entry => entry.dispatchId === selectedEntry.dispatchId) ? [selectedEntry, ...recentEntries] : recentEntries;
  const executionStatusColor = (status: string | null) => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' || status === 'TIMEOUT' ? 'error' : status === 'PAUSED' || status === 'PAUSING' || status === 'PAUSE_FAILED' ? 'warning' : status === 'CANCELED' || !status ? 'default' : 'processing';
  const executionTimes = (attempt: DispatchAttempt) => <div className="delivery-workbench-entry-meta">
    <div>{t('workbench.startedAt', { time: attempt.startedAt ? formatDateTime(attempt.startedAt) : t('workbench.unknownTime') })}</div>
    <div>{t('workbench.endedAt', { time: attempt.endedAt ? formatDateTime(attempt.endedAt) : attempt.status && ['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED'].includes(attempt.status) ? t('workbench.unknownTime') : t('workbench.notEnded') })}</div>
  </div>;
  const timeline = (visibleEntries: ExecutionEntry[], showPending = false) => <div className="delivery-workbench-timeline" aria-label={t('workbench.handoffs')}>
    {visibleEntries.map(entry => <div key={entry.dispatchId} className="delivery-workbench-entry-card">
      <button type="button" className={`delivery-workbench-entry ${selectedEntry?.dispatchId === entry.dispatchId ? 'is-selected' : ''}`}
        aria-pressed={selectedEntry?.dispatchId === entry.dispatchId} onClick={() => { selectEntry(entry); open('terminal'); }}>
        <span className="delivery-workbench-entry-name" data-testid="agent-progress-name">{entry.agent.agentName}</span>
        <Tag color={executionStatusColor(entry.attempt.status)}>{dispatchExecutionLabel(entry.attempt.status, entry.attempt.waitingReason)}</Tag>
        <span className="delivery-workbench-entry-meta">{t('workbench.sessionDuration', { id: entry.dispatchId, duration: formatDuration(entry.attempt.durationMs) })}</span>
        {entry.attempt.executorName && <span className="delivery-workbench-entry-meta">{t('delivery.runner', { name: entry.attempt.executorName })}</span>}
      </button>
      {!expanded && renderControlTimeline?.(entry.dispatchId, true)}
    </div>)}
    {showPending && agents.filter(agent => !entries.some(entry => entry.agent.agentId === agent.agentId)).slice(0, 3).map(agent => <button key={agent.agentId} type="button" className="delivery-workbench-entry" onClick={() => { setPinnedAgent(agent.agentId); setPinnedDispatch(null); setSelectedStepId(null); if (!expanded) open('details'); }}>
      <span className="delivery-workbench-entry-name" data-testid="agent-progress-name">{agent.agentName}</span><span>{statusLabel(agent.status, agent.executionStatus, agent.waitingReason).text}</span><span className="delivery-workbench-entry-meta">{formatDuration(agent.durationMs)}</span>
    </button>)}
  </div>;
  const stagePlanLabel = (step: ConvergedDeliveryStep) => step.planStatus === 'REUSED' || step.status === 'reused' ? t('delivery.reusedPrevious') : step.planStatus === 'RUN' ? t('delivery.rerun') : null;
  const stageList = selectedAgent && <div className="delivery-workbench-stages" aria-label={t('workbench.stages')}>
    {selectedAgent.steps.map((step, index) => <button key={step.stepId} type="button" className={`delivery-workbench-stage ${step.stepId === selectedStepId ? 'is-selected' : ''}`} aria-pressed={step.stepId === selectedStepId}
      onClick={() => { setPinnedDispatch(selectedEntry?.dispatchId ?? null); setPinnedAgent(selectedAgent.agentId); setSelectedStepId(step.stepId); setTab('details'); }}>
      <StepIcon step={step} index={index} pausing={stepIsPausing(step)} /><span>{step.name}</span><span className="delivery-workbench-entry-meta">{stagePlanLabel(step)}</span>
    </button>)}
  </div>;
  const details = selectedAgent && <>
    <div className="delivery-workbench-detail-heading"><Text strong>{selectedStep?.name ?? selectedAgent.agentName}</Text><Space wrap>
      {selectedAgent.durationMs != null && <Text type="secondary">{formatDuration(selectedAgent.durationMs)}</Text>}
      {selectedAgent.usage && <TokenUsageBadge usage={selectedAgent.usage} />}
    </Space></div>
    {selectedEntry && executionTimes(selectedEntry.attempt)}
    {selectedEntry?.attempt.error && <DispatchFailureDetails dispatchId={selectedEntry.dispatchId} error={selectedEntry.attempt.error} status={selectedEntry.attempt.status} />}
    {selectedEntry && selectedEntry.steps.length === 0 ? <>
      <DispatchActivityFeed dispatchId={selectedEntry.dispatchId} status={selectedEntry.attempt.status} />
    </> : <StepList steps={selectedStep ? [selectedStep] : progress?.executions?.length ? selectedEntry?.steps ?? selectedAgent.steps : selectedAgent.steps} />}
  </>;
  return <>
    {!expanded && summary}
    <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.historyExecutions')}</Text><Space wrap><Button type="text" onClick={() => { setShowAllExecutions(true); open('terminal'); }}>{t('workbench.historyCount', { count: entries.length })}</Button><Button type="text" onClick={() => open('details')}>{t('workbench.viewDetails')}</Button></Space></div>
    {!expanded && timeline(recentEntries.filter(entry => entry.dispatchId !== defaultEntry?.dispatchId), true)}
    <Space wrap className="delivery-workbench-footer"><Button onClick={() => open('timing')}>{t('stepTiming.title')}</Button><Button onClick={() => open('activity')}>{t('workbench.activity')}</Button><Button onClick={() => open('artifacts')}>{t('artifacts.agentCount', { count: artifacts.length })}</Button><Button onClick={() => open('graph')}>{t('workbench.graph')}</Button></Space>
    <Modal open={expanded} onCancel={() => setExpanded(false)} footer={null} width="calc(100vw - 32px)" style={{ top: 16 }} className="delivery-workbench-modal"
      title={<div className="delivery-workbench-heading"><span>{t('delivery.title')}</span><Button icon={<CompressOutlined aria-hidden="true" />} onClick={() => setExpanded(false)}>{t('workbench.exit')}</Button></div>}>
      <div className="delivery-workbench-expanded" data-testid="delivery-workbench">
        {handoffId !== null && <Alert showIcon type="info" message={t(followCurrent ? 'workbench.handoffFollowing' : 'workbench.handoffPinned', { name: currentAgent?.agentName ?? '' })} closable onClose={() => setHandoffId(null)} />}
        <div className={`delivery-workbench-layout ${railVisible ? '' : 'without-rail'}`} style={{ '--delivery-rail-width': `${railWidth}px` } as CSSProperties}>
          <aside className="delivery-workbench-rail" hidden={!railVisible}>
            <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.handoffs')}</Text></div>
            {timeline(sidebarEntries, true)}
            <Button type="text" onClick={() => setShowAllExecutions(value => !value)}>{t('workbench.historyCount', { count: entries.length })}</Button>
            {restartHistory}
            <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.stages')}</Text></div>
            {stageList}
            <label className="delivery-workbench-width">{t('workbench.sidebarWidth')}<input type="range" min={200} max={400} step={20} value={railWidth} onChange={event => setRailWidth(Number(event.target.value))} /></label>
          </aside>
          <main className="delivery-workbench-main">
            <div className="delivery-workbench-session">
              <Button size="small" onClick={() => setRailVisible(!railVisible)}>{t(railVisible ? 'workbench.hideSidebar' : 'workbench.showSidebar')}</Button>
              <Text strong data-testid="selected-agent-name">{selectedAgent?.agentName}</Text>
              {selectedEntry && <Tag color={executionStatusColor(selectedEntry.attempt.status)}>{dispatchExecutionLabel(selectedEntry.attempt.status, selectedEntry.attempt.waitingReason)}</Tag>}
              {entries.length > 0 && <Select aria-label={t('workbench.executionSession')} data-testid="live-activity-dispatch-select" value={selectedEntry?.dispatchId} onChange={id => { const entry = entries.find(value => value.dispatchId === id); if (entry) selectEntry(entry); }} options={entries.map(entry => ({ value: entry.dispatchId, label: t('delivery.dispatchOption', { id: entry.dispatchId, latest: entry.dispatchId === defaultEntry?.dispatchId ? t('delivery.latest') : '' }) }))} />}
              <Text type="secondary">{t(followCurrent ? currentEntry ? 'workbench.following' : 'workbench.latestSession' : 'workbench.history')}</Text>
              {!followCurrent && <Button onClick={returnToCurrent}>{t('workbench.returnCurrent')}</Button>}
              {selectedEntry && onExecutionAction && (
                <ExecutionActionBar
                  attempt={selectedEntry.attempt}
                  pending={pendingExecutionAction}
                  onAction={(action) => onExecutionAction(selectedEntry.attempt, action)}
                />
              )}
            </div>
            {selectedEntry && <div className="delivery-workbench-meta">
              {selectedEntry.attempt.executorName && <span>{t('delivery.runner', { name: selectedEntry.attempt.executorName })}</span>}
              <span>{formatDuration(selectedEntry.attempt.durationMs)}</span>
            </div>}
            {selectedEntry?.attempt.stopPending && (
              <Text style={{ display: 'block', color: 'var(--cf-warning)', fontSize: 12 }}>
                {selectedIsTimeout
                  ? t('delivery.timeoutStopUnconfirmed', { id: selectedEntry.dispatchId })
                  : t('delivery.stopUnconfirmed', { id: selectedEntry.dispatchId })}
              </Text>
            )}
            <div key={`execution-${selectedEntry?.dispatchId ?? 'none'}`}>{selectedEntry && renderRecovery?.(selectedEntry.dispatchId)}</div>
            <Tabs activeKey={tab} onChange={setTab} items={[
              { key: 'terminal', label: t('runtimeLog.terminal'), children: selectedEntry ? <RuntimeLogPanel key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} terminal embedded enabled={expanded && tab === 'terminal'} /> : <Empty description={t('workbench.noSession')} /> },
              { key: 'activity', label: t('workbench.activity'), children: selectedEntry ? <LiveActivityPanel key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} enabled={selectedLive && expanded && tab === 'activity'} /> : <Empty description={t('workbench.noSession')} /> },
              ...(renderControlTimeline ? [{ key: 'controls', label: t('executionControl.timeline'), children: selectedEntry && renderControlTimeline(selectedEntry.dispatchId) }] : []),
              { key: 'details', label: t('workbench.details'), children: details },
              { key: 'timing', label: t('stepTiming.title'), children: selectedEntry ? <StepDurationChart key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} steps={selectedEntry.attempt.stepTimings} /> : <Empty description={t('workbench.noSession')} /> },
              { key: 'artifacts', label: t('artifacts.agentCount', { count: selectedAgent ? artifactsForAgent(selectedAgent, artifacts, progress?.executions).length : artifacts.length }), children: <ArtifactGroup key={selectedAgent?.agentId ?? 'all'} executions={progress?.executions?.filter(attempt => !selectedAgent || attempt.agentId === selectedAgent.agentId)} steps={selectedAgent?.steps ?? []} artifacts={selectedAgent ? artifactsForAgent(selectedAgent, artifacts, progress?.executions) : artifacts} loading={artifactsLoading} onPreview={setPreviewArtifact} /> },
              { key: 'graph', label: t('workbench.graph'), children: <AuthoritativeExecutionProcessGraph processGraph={progress?.processGraph} /> },
            ]} />
          </main>
        </div>
      </div>
      <ArtifactPreviewModal
        open={expanded && previewArtifact != null}
        artifact={previewArtifact}
        onClose={() => setPreviewArtifact(null)}
      />
    </Modal>
  </>;
}
