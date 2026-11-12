import { useNavigate } from 'react-router-dom';
import type { Workitem } from '@/shared/types/workitem';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { LineIcon } from '@/shared/ui/LineIcon';

const HASH = '#';
const TIME_OPTS: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hour12: false };

function dayLabel(iso: string | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  const today = new Date();
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diff = Math.round((startOf(today) - startOf(date)) / 86400000);
  if (diff === 0) return i18n.t('workitem:list.dayToday');
  if (diff === 1) return i18n.t('workitem:list.dayYesterday');
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

/** 工单流(单列)——按创建日分组的紧凑行式;看板视图保留为并列视图。 */
export function WorkitemFlowList({ items, emptyText }: { items: Workitem[]; emptyText?: string }) {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation('workitem');
  const fallbackEmpty = t('execution.empty');
  const locale = i18n.language;

  if (!items.length) {
    return <div className="empty" style={{ padding: '44px 0' }}>{emptyText ?? fallbackEmpty}</div>;
  }

  // 按创建日分组(服务端默认按创建时间降序,这里只做相邻归组,不重排)
  const groups: Array<{ label: string | null; items: Workitem[] }> = [];
  for (const item of items) {
    const label = dayLabel(item.gmtCreate);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
  }

  return (
    <div className="flow-list">
      {groups.map((group, gi) => (
        <div key={`${group.label ?? 'g'}-${gi}`} className="flow-group">
          {group.label && <div className="flow-group__label">{group.label}</div>}
          {group.items.map((item) => {
            const statusText = item.statusName ?? fallbackEmpty;
            const created = item.gmtCreate
              ? new Date(item.gmtCreate).toLocaleTimeString(locale, TIME_OPTS)
              : null;
            return (
              <div
                key={item.id}
                className="flow-item"
                role="button"
                tabIndex={0}
                aria-label={item.title}
                onClick={() => navigate(`/workitems/${item.id}`)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    navigate(`/workitems/${item.id}`);
                  }
                }}
              >
                <span className="flow-item__dot" data-tone={statusTone(statusText)} aria-hidden="true" />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div className="flow-item__title">{item.title}</div>
                  <div className="flow-item__meta">
                    <span className="flow-item__id">{HASH}{item.id}</span>
                    <span>{item.sdlcName ?? item.workType}</span>
                    {item.assigneeDisplayName || item.assigneeName ? (
                      <span>{item.assigneeDisplayName || item.assigneeName}</span>
                    ) : null}
                    <span className={`tag tag--${statusTone(statusText)}`} style={{ marginLeft: 4 }}>{statusText}</span>
                  </div>
                </div>
                <span className="flow-item__time">{created}</span>
                <LineIcon name="chevronDown" size={14} style={{ transform: 'rotate(-90deg)', color: 'var(--cf-muted)', flexShrink: 0 }} />
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

function statusTone(status: string): string {
  // 仅按服务端状态码判定色调,不做中文字面量匹配(避免多语言下误判)
  const s = status.toLowerCase();
  if (s.includes('done') || s.includes('closed') || s.includes('complete')) return 'done';
  if (s.includes('fail') || s.includes('error')) return 'fail';
  if (s.includes('wait') || s.includes('decision') || s.includes('pending')) return 'wait';
  if (s.includes('progress') || s.includes('running') || s.includes('queue')) return 'run';
  return 'idle';
}
