import { useTranslation } from 'react-i18next';
import { Card, Space, Tag, Typography } from 'antd';
import { i18n } from '@/shared/i18n';
import type { AgentDeliveryProgress, WorkflowPlan } from '@/shared/types/workitem';
import { dispatchExecutionLabel } from '../runtimeLabels';

const { Text } = Typography;

/** 计划摘要卡:复用/重跑/跳过 三组标签 + 轮次与执行者。 */
export function WorkflowPlanSummary({ plan }: { plan: WorkflowPlan }) {
  const { t } = useTranslation('workitem');
  const grouped = (status: WorkflowPlan['steps'][number]['planStatus']) => plan.steps
    .filter((step) => step.planStatus === status)
    .map((step) => step.name || step.stepKey)
    .filter((name): name is string => Boolean(name));
  const reused = grouped('REUSED');
  const run = grouped('RUN');
  const skipped = grouped('SKIPPED');

  return (
    <Card
      size="small"
      title={t('delivery.plan')}
      data-testid="workflow-plan-card"
      styles={{ body: { padding: '8px 10px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 7 }}>
        <Tag color="blue" style={{ marginInlineEnd: 0 }}>{t('delivery.round', { number: plan.revision })}</Tag>
      </div>
      <Space size={[6, 6]} wrap>
        {reused.length > 0 && <Tag color="green">{t('delivery.reuseNames', { names: reused.join(i18n.language === 'en-US' ? ', ' : '、') })}</Tag>}
        {run.length > 0 && <Tag color="volcano">{t('delivery.rerunNames', { names: run.join(i18n.language === 'en-US' ? ', ' : '、') })}</Tag>}
        <Tag>{t('delivery.skipNames', { names: skipped.length > 0 ? skipped.join(i18n.language === 'en-US' ? ', ' : '、') : t('delivery.none') })}</Tag>
      </Space>
      {plan.reason && (
        <Text type="secondary" style={{ display: 'block', marginTop: 7, fontSize: 11 }}>
          {t('delivery.reason', { reason: plan.reason })}
        </Text>
      )}
      {plan.agentName && (
        <Text type="secondary" style={{ display: 'block', marginTop: 3, fontSize: 11 }}>
          {t('delivery.executor', { name: plan.agentName })}
        </Text>
      )}
    </Card>
  );
}

/** 执行状态 → 文案与标签色(纯函数,DeliveryProgress 与工作台共用)。 */
export function statusLabel(status: AgentDeliveryProgress['status'], executionStatus?: string | null, waitingReason?: string | null) {
  if (status === 'active' && executionStatus) return { text: dispatchExecutionLabel(executionStatus, waitingReason), color: executionStatus === 'PENDING' ? 'default' : 'processing' };
  if (status === 'cancelled') return { text: i18n.t('workitem:delivery.cancelled'), color: 'default' };
  if (status === 'active') return { text: i18n.t('workitem:delivery.running'), color: 'processing' };
  if (status === 'paused') return { text: i18n.t('workitem:delivery.paused'), color: 'warning' };
  if (status === 'finished') return { text: i18n.t('workitem:delivery.completed'), color: 'success' };
  if (status === 'failed') return { text: i18n.t('workitem:delivery.failed'), color: 'error' };
  return { text: i18n.t('workitem:delivery.notRun'), color: 'default' };
}
