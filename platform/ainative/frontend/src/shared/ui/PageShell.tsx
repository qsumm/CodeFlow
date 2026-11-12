import type { ReactNode } from 'react';
import './PageShell.css';
import './LayoutGrid.css';
import './ModalTheme.css';

/**
 * 统一页面壳:所有一级/二级页面的头部与骨架都走这里。
 *
 * 设计语言(与「选择项目」页同源):
 * - 品牌色渐变横幅 + 平台主色高对比;
 * - 标题 + 副标题左对齐,操作区右对齐,垂直居中;
 * - 速览胶囊承载关键计数,不再堆在正文里;
 * - 正文统一用 .cfds-body 容器控制左右留白与最大宽度。
 */

export interface PageShellBadge {
  label: string;
  value: string;
  tone?: 'default' | 'accent' | 'muted';
}

export function PageShell({
  title,
  subtitle,
  badges,
  actions,
  children,
  compact,
  testId,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  badges?: PageShellBadge[];
  actions?: ReactNode;
  children: ReactNode;
  /** 紧凑模式:用于抽屉/详情类二级页,横幅更矮。 */
  compact?: boolean;
  testId?: string;
}) {
  return (
    <section className={`cfds-page${compact ? ' cfds-page--compact' : ''}`} data-testid={testId}>
      <header className="cfds-hero">
        <div className="cfds-hero__main">
          <div className="cfds-hero__titles">
            <h1 className="cfds-hero__title">{title}</h1>
            {subtitle ? <p className="cfds-hero__subtitle">{subtitle}</p> : null}
          </div>
          {badges && badges.length > 0 ? (
            <div className="cfds-hero__badges">
              {badges.map((badge) => (
                <span
                  key={`${badge.label}:${badge.value}`}
                  className={`cfds-badge${badge.tone && badge.tone !== 'default' ? ` cfds-badge--${badge.tone}` : ''}`}
                >
                  <span className="cfds-badge__label">{badge.label}</span>
                  <span className="cfds-badge__value">{badge.value}</span>
                </span>
              ))}
            </div>
          ) : null}
        </div>
        {actions ? <div className="cfds-hero__actions">{actions}</div> : null}
      </header>
      <div className="cfds-body">{children}</div>
    </section>
  );
}

/** 统计小块:一格一个关键数字,用于页面顶部的速览区。 */
export function StatTile({ label, value, hint, tone }: {
  label: ReactNode;
  value: ReactNode;
  hint?: ReactNode;
  tone?: 'default' | 'accent' | 'warn';
}) {
  return (
    <div className={`cfds-stat${tone && tone !== 'default' ? ` cfds-stat--${tone}` : ''}`}>
      <div className="cfds-stat__label">{label}</div>
      <div className="cfds-stat__value">{value}</div>
      {hint ? <div className="cfds-stat__hint">{hint}</div> : null}
    </div>
  );
}

/** 内容面板:标题 + 右侧操作 + 内容。所有卡片区块统一用它,避免各自为政。 */
export function SurfaceCard({
  title,
  description,
  actions,
  children,
  bodyClassName,
  className,
  testId,
}: {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  bodyClassName?: string;
  /** 追加根类名（如需要兼容测试的 .ant-card 定位时传入）。 */
  className?: string;
  testId?: string;
}) {
  return (
    <section className={`cfds-card${className ? ` ${className}` : ''}`} data-testid={testId}>
      {title || actions ? (
        <header className="cfds-card__head">
          <div className="cfds-card__titles">
            {title ? <h2 className="cfds-card__title">{title}</h2> : null}
            {description ? <p className="cfds-card__desc">{description}</p> : null}
          </div>
          {actions ? <div className="cfds-card__actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className={`cfds-card__body${bodyClassName ? ` ${bodyClassName}` : ''}`}>{children}</div>
    </section>
  );
}

/**
 * 工具栏:左筛选右操作。控件高度与内边距统一,输入框与选择器同宽,
 * 避免出现「大一号输入框配小一号按钮」的错位。
 */
export function Toolbar({ filters, actions, className }: {
  filters?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  if (!filters && !actions) return null;
  return (
    <div className={`cfds-toolbar${className ? ` ${className}` : ''}`}>
      <div className="cfds-toolbar__filters">{filters}</div>
      <div className="cfds-toolbar__actions">{actions}</div>
    </div>
  );
}

/** 表单行:标签与控件左对齐、控件列等宽,同一表单内所有行节奏一致。 */
export function FormRow({ label, required, hint, children }: {
  label: ReactNode;
  required?: boolean;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="cfds-form-row">
      <label className="cfds-form-row__label">
        {label}
        {required ? <span className="cfds-form-row__required" aria-hidden="true" /> : null}
      </label>
      <div className="cfds-form-row__control">
        {children}
        {hint ? <p className="cfds-form-row__hint">{hint}</p> : null}
      </div>
    </div>
  );
}
