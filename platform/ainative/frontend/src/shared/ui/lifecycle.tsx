/**
 * 平台九段生命周期:关于页的环形与登录页的价值链跑马灯共用同一份定义。
 * 阶段名走 about 命名空间的 i18n key(单一事实来源,勿在页面里另写文案)。
 */
export const LIFECYCLE_STEPS = [
  'clarification',
  'start',
  'development',
  'verification',
  'review',
  'deployment',
  'testing',
  'delivery',
  'ops',
] as const;

export type LifecycleStep = (typeof LIFECYCLE_STEPS)[number];

/** 自研线性图标:24 viewBox / stroke 1.9 / round——与平台 LineIcon 同一视觉语言。 */
export const LIFECYCLE_ICONS: Record<LifecycleStep, string> = {
  clarification: 'M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-5 4v-4H6a2 2 0 0 1-2-2V5ZM10.4 9.2a1.9 1.9 0 1 1 2.6 1.8c-.65.28-.95.75-.95 1.6M12 15.3h.01',
  start: 'M9 5.8v12.4a.7.7 0 0 0 1.07.6l9.6-6.2a.7.7 0 0 0 0-1.2l-9.6-6.2A.7.7 0 0 0 9 5.8Z',
  development: 'M9.5 7.5 5 12l4.5 4.5M14.5 7.5 19 12l-4.5 4.5',
  verification: 'M12 3.2 19 6v5.2c0 4.6-3 8.2-7 9.8-4-1.6-7-5.2-7-9.8V6l7-2.8ZM9.2 12l2.1 2.1 3.9-4.2',
  review: 'M9 4.5h6V7H9zM9 4.5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-12a2 2 0 0 0-2-2h-2M9.4 13.2l1.9 1.9 3.4-3.8',
  deployment: 'M7.2 16.4a4.1 4.1 0 1 1 .82-8.1 5.5 5.5 0 0 1 10.75 1.4 3.6 3.6 0 0 1-.77 7M12 20.5v-8.4M8.9 15.2 12 12.1l3.1 3.1',
  testing: 'M10 3.2h4M10.6 3.2v4.9l-4.9 9a2.4 2.4 0 0 0 2.15 3.5h8.3a2.4 2.4 0 0 0 2.15-3.5l-4.9-9V3.2M7.6 14.2h8.8',
  delivery: 'M12 3.2a8.8 8.8 0 1 0 8.8 8.8M15.5 4.4a8.8 8.8 0 0 1 4.6 4.9M8.6 12.1l2.4 2.4 4.5-4.7',
  ops: 'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z',
};

export function LifecycleIcon({ step, size = 14 }: { step: LifecycleStep; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={LIFECYCLE_ICONS[step]} />
    </svg>
  );
}
