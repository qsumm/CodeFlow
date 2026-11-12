import type { CSSProperties } from 'react';

/** 统一线性图标集(24 viewBox / stroke 1.9 / currentColor),替代 antd 混合图标风格。 */
const PATHS: Record<string, string> = {
  overview: 'M3 3v18h18M7 15l4-5 3 3 5-7',
  workitems: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4',
  scheduled: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  executions: 'M4 17V7a2 2 0 0 1 4 0v10M8 12h6a4 4 0 0 1 4 4v1M3 21h18',
  recycled: 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14',
  agents: 'M4 8h16v12H4zM12 4a2 2 0 1 0 0-4 2 2 0 0 0 0 4M9 13h.01M15 13h.01',
  executors: 'M13 2 4 14h7l-1 8 9-12h-7z',
  repos: 'M6 3v12M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6M18 9v3a3 3 0 0 1-3 3H9',
  memories: 'M12 3a3 3 0 0 0-3 3v1H7a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2V6a3 3 0 0 0-3-3z',
  skills: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5V16h8v-2.5A6 6 0 0 0 12 3z',
  sdlc: 'M4 6h16M4 12h16M4 18h16M9 4v4M15 10v4M7 16v4',
  statusTemplates: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  integrations: 'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1',
  members: 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 7a4 4 0 1 0 0 8 4 4 0 0 0 0-8',
  backup: 'M12 3v12M8 11l4 4 4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2',
  envVars: 'M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h.01M16 4v4M10 10v4',
  insights: 'M3 3v18h18M7 16v-4M11 16V8M15 16v-6M19 16V5',
  audit: 'M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2M12 11v4M10 13h4',
  about: 'M12 16v-5M12 8h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5',
  gear: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.9 1.7 1.7 0 0 1-2.8 2.8 1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1-1.5V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.9.3 1.7 1.7 0 0 1-2.8-2.8 1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1.9-.3 1.7 1.7 0 0 1 2.8 2.8 1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1 1.5V21a2 2 0 1 1-4 0',
  appearance: 'M12 3v2M12 19v2M5 12H3M21 12h-2M6 6l1.5 1.5M16.5 16.5 18 18M18 6l-1.5 1.5M7.5 16.5 6 18',
  bell: 'M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 0 1-3.4 0',
  user: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8',
  question: 'M12 16v-5M12 8h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  language: 'M3 5h10M8 3v2M5 5c0 5 3 9 7 11M11 5c0 4-2 8-6 10M14 21l4.5-11L23 21',
  chevronDown: 'm6 9 6 6 6-6',
  plus: 'M12 5v14M5 12h14',
  filter: 'M3 5h18M6 12h12M10 19h4',
  refresh: 'M21 12a9 9 0 1 1-3-6.7M3 12a9 9 0 1 0 3 6.7M21 4v5h-5M3 20v-5h5',
  comment: 'M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  share: 'M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7M16 6l-4-4-4 4M12 2v14',
  close: 'M18 6 6 18M6 6l12 12',
  trash: 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14',
  flag: 'M4 15s1-1 4-1 5 2 4 4-1 4-2 3 2 4-1 2 3-2 3 2v-4l-1-2-4 1-4 2-4-2-3-2-4 1Z',
};

export type LineIconName = keyof typeof PATHS;

export function LineIcon({ name, size = 16, className, style, title }: {
  name: LineIconName | string;
  size?: number;
  className?: string;
  style?: CSSProperties;
  title?: string;
}) {
  const d = PATHS[name] ?? PATHS.about;
  return (
    <svg
      className={className}
      style={style}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.9}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden={title ? undefined : 'true'}
      role={title ? 'img' : undefined}
      aria-label={title}
    >
      <path d={d} />
    </svg>
  );
}

export const lineIconNames = Object.keys(PATHS);
