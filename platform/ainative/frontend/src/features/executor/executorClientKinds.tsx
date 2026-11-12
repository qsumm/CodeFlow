import { CheckCircleFilled, CodeOutlined, CodeSandboxOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';

// labelKey:产品名不翻译,中英资源同值;i18n 门禁要求经 t() 呈现
export type ClientKindLabelKey =
  | 'clientKindQODER_CN_CLI' | 'clientKindQODER_CLI' | 'clientKindCLAUDE_CODE'
  | 'clientKindGEMINI_CLI' | 'clientKindCODEX_CLI' | 'clientKindCURSOR_CLI';

export const CLIENT_KINDS: { value: string; labelKey: ClientKindLabelKey; color: string; Icon: typeof CodeOutlined | typeof RobotHeadIcon }[] = [
  { value: 'QODER_CN_CLI', labelKey: 'clientKindQODER_CN_CLI', color: 'var(--cf-accent-text)', Icon: CodeOutlined },
  { value: 'QODER_CLI', labelKey: 'clientKindQODER_CLI', color: 'var(--cf-accent-text)', Icon: CodeOutlined },
  { value: 'CLAUDE_CODE', labelKey: 'clientKindCLAUDE_CODE', color: '#d4380d', Icon: RobotHeadIcon },
  { value: 'GEMINI_CLI', labelKey: 'clientKindGEMINI_CLI', color: '#4285f4', Icon: RobotHeadIcon },
  { value: 'CODEX_CLI', labelKey: 'clientKindCODEX_CLI', color: '#13a8a8', Icon: CodeSandboxOutlined },
  { value: 'CURSOR_CLI', labelKey: 'clientKindCURSOR_CLI', color: 'var(--cf-text)', Icon: RobotHeadIcon },
];

export const clientKindMap = Object.fromEntries(CLIENT_KINDS.map((k) => [k.value, k]));

// 新建执行器开放全部客户端类型(含 Claude/Gemini/Codex/Cursor CLI);
// 非 Qoder 系不拉模型目录(qoderProviderForClientKind 返回 undefined),模型字段隐藏,启动命令只带 --provider。
export const CREATABLE_CLIENT_KINDS = CLIENT_KINDS;

/** 客户端类型卡片式单选：value/onChange 由 antd Form.Item 注入，不手工接线。 */
export function ClientKindSelect({ value, onChange }: { value?: string; onChange?: (v: string) => void }) {
  const { t } = useTranslation('executor');
  return (
    <div role="group" aria-label={t('clientChoice')} style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
      {CREATABLE_CLIENT_KINDS.map(({ value: v, labelKey, color, Icon }) => {
        const selected = value === v;
        return (
          <button
            type="button"
            aria-pressed={selected}
            key={v}
            onClick={() => onChange?.(v)}
            style={{
              color: 'inherit', font: 'inherit',
              flex: '1 1 150px',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              padding: '12px 16px',
              border: `2px solid ${selected ? 'var(--cf-accent-text)' : 'var(--cf-border)'}`,
              borderRadius: 8,
              cursor: 'pointer',
              background: selected ? 'rgba(var(--cf-accent-rgb), .10)' : 'var(--cf-panel)',
              transition: 'all 0.2s',
              position: 'relative',
            }}
          >
            <span style={{ fontSize: 24, color }}><Icon aria-hidden="true" /></span>
            <span style={{ fontWeight: 500, textAlign: 'center' }}>{t(labelKey)}</span>
            {selected && (
              <CheckCircleFilled aria-hidden="true"
                style={{ position: 'absolute', top: 8, right: 8, color: 'var(--cf-accent-text)', fontSize: 16 }}
              />
            )}
          </button>
        );
      })}
    </div>
  );
}
