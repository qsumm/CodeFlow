import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useQuery } from '@tanstack/react-query';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';
import { LIFECYCLE_STEPS, type LifecycleStep } from '@/shared/ui/lifecycle';
import './AboutPlatformPage.css';

/**
 * 关于平台 · 生命周期「交付 9 号线」(2026-11-12 用户选定地铁图方向):
 * 蓝色主线路串起九站(上 5 下 4),小列车沿线路巡航、逐站点亮站环;
 * 橙色虚线从运维回流始发站,表达「交付完成自动回流需求」的闭环。
 * 版本号在底部票务行(「本班列车」取自品牌配置 deploymentVersion)。
 * 九段定义仍以 shared/ui/lifecycle 为单一事实来源;站点文案走 about 命名空间。
 */

/* 九站坐标(viewBox 1120×330):上行 5 站、下行 4 站,与 CSS 的轨道 path 一一对应 */
const STOPS: Array<{ x: number; y: number; top: boolean }> = [
  { x: 90, y: 120, top: true },
  { x: 280, y: 120, top: true },
  { x: 470, y: 120, top: true },
  { x: 660, y: 120, top: true },
  { x: 850, y: 120, top: true },
  { x: 940, y: 230, top: false },
  { x: 760, y: 230, top: false },
  { x: 580, y: 230, top: false },
  { x: 400, y: 230, top: false },
];
const MAP_W = 1120;
const MAP_H = 330;
const MAIN_LINE_D = 'M 90 120 L 940 120 A 40 40 0 0 1 980 160 L 980 190 A 40 40 0 0 1 940 230 L 280 230';
const LOOP_LINE_D = 'M 280 230 L 90 230 L 90 148';
const LOOP_ARROW_D = 'M 104 162 L 90 146 L 76 162';

/* strictKeyChecks 下 t() 只收字面量键,动态键经这两张静态映射表取 */
const STEP_DESC_KEYS = {
  clarification: 'clarificationDesc',
  start: 'startDesc',
  development: 'developmentDesc',
  verification: 'verificationDesc',
  review: 'reviewDesc',
  deployment: 'deploymentDesc',
  testing: 'testingDesc',
  delivery: 'deliveryDesc',
  ops: 'opsDesc',
} as const satisfies Record<LifecycleStep, string>;
const STEP_STATE_KEYS = {
  clarification: 'clarificationState',
  start: 'startState',
  development: 'developmentState',
  verification: 'verificationState',
  review: 'reviewState',
  deployment: 'deploymentState',
  testing: 'testingState',
  delivery: 'deliveryState',
  ops: 'opsState',
} as const satisfies Record<LifecycleStep, string>;

/* 状态行色调:进行中=主题蓝,已交付=绿,持续运营=青(与站环语义一致) */
const STOP_TONES: Record<number, string> = { 7: 'aam-status-v--done', 8: 'aam-status-v--ops' };

const pct = (v: number, total: number) => `${((v / total) * 100).toFixed(3)}%`;

export function AboutPlatformPage() {
  const navigate = useNavigate();
  const { t } = useTranslation('about');
  const { data: branding } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const deploymentVersion =
    branding?.deploymentVersion?.trim() || DEFAULT_BRANDING.deploymentVersion;

  // 列车巡航:rAF 里直接改 SVG 属性,不经过 React 状态(与飞行线时期同一做法)
  const pathRef = useRef<SVGPathElement>(null);
  const trainRef = useRef<SVGGElement>(null);
  const ringsBoxRef = useRef<SVGGElement>(null);
  const statusRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const path = pathRef.current;
    const train = trainRef.current;
    const status = statusRef.current;
    const rings = ringsBoxRef.current
      ? Array.from(ringsBoxRef.current.querySelectorAll('.aam-ring'))
      : [];
    if (!path || !train || !status || rings.length !== STOPS.length) return;
    const stopLabel = (i: number) =>
      t('metroArrivingWith', {
        name: t(LIFECYCLE_STEPS[i]),
        state: t(STEP_STATE_KEYS[LIFECYCLE_STEPS[i]]),
      });
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      rings.forEach((r) => r.classList.add('aam-ring--done'));
      train.style.display = 'none';
      status.textContent = t('metroArrivedWith', {
        name: t('delivery'),
        state: t('deliveryState'),
      });
      status.className = 'aam-status-v aam-status-v--done';
      return;
    }
    let cancelled = false;
    // jsdom 等测试环境没有 SVG 几何 API:列车巡航是纯装饰,缺 API 就整段跳过,
    // 站名牌/状态行仍以 React 初始渲染兜底。
    if (typeof path.getTotalLength !== 'function' || typeof path.getPointAtLength !== 'function') return;
    const L = path.getTotalLength();
    // 各站在弧长上的位置:由站坐标反查最近弧长(圆角处路径偏离直线段,不能按 x 折算)
    const stopLens = STOPS.map((s) => {
      let best = 0;
      let bd = Number.POSITIVE_INFINITY;
      for (let l = 0; l <= L; l += 3) {
        const q = path.getPointAtLength(l);
        const d2 = (q.x - s.x) ** 2 + (q.y - s.y) ** 2;
        if (d2 < bd) { bd = d2; best = l; }
      }
      return best;
    });
    const CYCLE = 13000;
    const t0 = performance.now();
    let raf = 0;
    let lastCur = -1;
    const frame = (now: number) => {
      if (cancelled) return;
      const dist = (((now - t0) % CYCLE) / CYCLE) * L;
      const p = path.getPointAtLength(dist);
      const q = path.getPointAtLength(Math.min(L, dist + 2));
      const deg = (Math.atan2(q.y - p.y, q.x - p.x) * 180) / Math.PI;
      train.setAttribute('transform', `translate(${p.x.toFixed(1)} ${p.y.toFixed(1)}) rotate(${deg.toFixed(1)})`);
      // 已过站只看「本班列车此程是否驶过」(stopLens ≤ dist):
      // 不能用「半圈内都算经过」的环绕判断——那会让上一班的尾段站
      // (交付/运维)在下一班刚出站时就抢走「即将到达」的播报。
      let cur = -1;
      rings.forEach((r, i) => {
        const passed = stopLens[i] <= dist;
        r.classList.toggle('aam-ring--done', passed);
        if (passed) cur = i;
      });
      if (cur < 0) cur = 0;
      if (cur !== lastCur) {
        lastCur = cur;
        status.textContent = stopLabel(cur);
        status.className = `aam-status-v ${STOP_TONES[cur] ?? ''}`.trimEnd();
      }
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
    return () => { cancelled = true; cancelAnimationFrame(raf); };
  }, [t]);

  return (
    <section className="about-an-page" aria-label={t('title')}>
      <PageHeading
        title={<span className="about-an-heading-copy"><span className="about-an-heading-eyebrow">{t('aboutHeadingEyebrow')}</span>{t('title')}</span>}
        extra={<Button onClick={() => { localStorage.removeItem('ainative.onboarding.done'); navigate('/onboarding'); }}>{t('replayTour', { defaultValue: '重看首次使用引导' })}</Button>}
      />
      <header className="about-an-header">
        <p className="about-an-eyebrow">{t('tagline')}</p>
        <h2 className="about-an-intro">{t('intro')}</h2>
      </header>
      <figure className="aam" aria-label={t('flow')}>
        <div className="aam-hd">
          <span className="aam-badge" aria-hidden="true">{9}</span>
          <div className="aam-hd-copy">
            <h3 className="aam-title">{t('metroTitle')}</h3>
            <p className="aam-sub">{t('metroSub')}</p>
          </div>
          <div className="aam-status">
            <span className="aam-status-k">{t('metroStatus')}</span>
            <strong className="aam-status-v" ref={statusRef}>
              {t('metroArrivingWith', { name: t('clarification'), state: t('clarificationState') })}
            </strong>
          </div>
        </div>
        <div className="aam-legend" aria-hidden="true">
          <span><i className="aam-lg aam-lg--main" />{t('metroMain')}</span>
          <span><i className="aam-lg aam-lg--loop" />{t('metroLoop')}</span>
        </div>
        <div className="aam-map">
          <div className="aam-canvas">
            <svg className="aam-lines" viewBox={`0 0 ${MAP_W} ${MAP_H}`} aria-hidden="true">
              <path className="aam-main" d={MAIN_LINE_D} ref={pathRef} />
              <path className="aam-loop" d={LOOP_LINE_D} />
              <path className="aam-loop-arrow" d={LOOP_ARROW_D} />
              <text className="aam-loop-label" x="12" y="230" transform="rotate(-90 12 230)">{t('metroLoop')}</text>
              <text className="aam-first" x="78" y="44">{t('metroFirst')}</text>
              <g ref={ringsBoxRef}>
                {LIFECYCLE_STEPS.map((step, i) => (
                  <circle key={step} className="aam-ring" cx={STOPS[i].x} cy={STOPS[i].y} r="9.5" />
                ))}
              </g>
              <g className="aam-train" ref={trainRef} transform={`translate(${STOPS[0].x} ${STOPS[0].y})`}>
                <rect className="aam-train-body" x="-14" y="-7" width="28" height="14" rx="7" />
                <circle className="aam-train-light" cx="8" cy="0" r="2.6" />
              </g>
            </svg>
            <ol className="aam-stops">
              {LIFECYCLE_STEPS.map((step, i) => (
                <li
                  key={step}
                  className={STOPS[i].top ? 'aam-stop aam-stop--top' : 'aam-stop aam-stop--btm'}
                  style={{ left: pct(STOPS[i].x, MAP_W), top: pct(STOPS[i].y, MAP_H) }}
                >
                  <span className="aam-stop-head">
                    <span className="aam-stop-no">{i + 1}</span>
                    <span className="aam-stop-nm">{t(step)}</span>
                  </span>
                  <span className="aam-stop-ds">{t(STEP_DESC_KEYS[step])}</span>
                </li>
              ))}
            </ol>
          </div>
        </div>
        <div className="aam-foot">
          <span>{t('metroFootStops')}</span>
          <span className="aam-foot-train">
            {t('metroFootTrain')}
            <strong className="aam-vv">{deploymentVersion}</strong>
          </span>
        </div>
        <figcaption className="about-an-iteration">
          <svg width={12} height={12} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ marginRight: 5 }}>
            <path d="M21 12a9 9 0 1 1-2.64-6.36M21 3v6h-6" />
          </svg>
          {t('iteration')}
        </figcaption>
      </figure>
    </section>
  );
}
