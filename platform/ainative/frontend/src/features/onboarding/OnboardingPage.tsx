import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Checkbox, Modal, Spin } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';
import './OnboardingPage.css';

const DONE_KEY = 'ainative.onboarding.done';
export const onboardingDone = () => localStorage.getItem(DONE_KEY) === '1';
export const markOnboardingDone = () => localStorage.setItem(DONE_KEY, '1');
export const resetOnboarding = () => localStorage.removeItem(DONE_KEY);

interface ExecutorRow {
  id: number;
  name: string | null;
  status: string;
}

const TOTAL = 4;

export function OnboardingPage() {
  const { t } = useTranslation('onboarding');
  const navigate = useNavigate();
  const [current, setCurrent] = useState(0);
  const [skipOpen, setSkipOpen] = useState(false);

  const finish = useCallback(() => {
    markOnboardingDone();
    navigate('/workitems');
  }, [navigate]);

  const goStep = useCallback(
    (path: string) => {
      markOnboardingDone();
      navigate(path);
    },
    [navigate],
  );

  // ---- 运行器探测(第 2 步) ----
  type ProbeState = 'loading' | 'online' | 'offline' | 'no-workspace';
  const [probe, setProbe] = useState<ProbeState>('loading');
  const [executors, setExecutors] = useState<ExecutorRow[]>([]);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (current !== 1) return;
    let stop = false;
    const run = async () => {
      try {
        const resp = await apiClient.get<ExecutorRow[]>('/api/executors');
        const rows = resp.data ?? [];
        if (stop) return;
        setExecutors(rows);
        setProbe(rows.some((e) => e.status === 'ONLINE') ? 'online' : 'offline');
      } catch (e) {
        if (stop) return;
        const status = (e as { response?: { status?: number } }).response?.status;
        setProbe(status === 403 ? 'no-workspace' : 'offline');
      }
    };
    run();
    timer.current = window.setInterval(run, 3000);
    return () => {
      stop = true;
      window.clearInterval(timer.current);
    };
  }, [current]);

  // ---- 平台生成的启动命令 ----
  const [cmd, setCmd] = useState<string | null>(null);
  const [cmdState, setCmdState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (current !== 1 || probe === 'loading' || executors.length === 0) return;
    if (cmd || cmdState === 'loading') return;
    const first = executors[0];
    setCmdState('loading');
    apiClient
      .post<{ command: string }>(`/api/executors/${first.id}/launch-command`, {})
      .then((resp) => {
        const body = resp.data as unknown as
          | { data?: { command?: string } }
          | { command?: string };
        const command =
          'data' in (body ?? {})
            ? (body as { data: { command: string } }).data.command
            : (body as { command: string }).command;
        setCmd(command ?? null);
        setCmdState('idle');
      })
      .catch(() => setCmdState('error'));
  }, [current, probe, executors, cmd, cmdState]);

  const copyCommand = async () => {
    if (!cmd) return;
    try {
      await navigator.clipboard.writeText(cmd);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* 非安全上下文:静默,用户可手动选中复制 */
    }
  };

  // 首次进入时若已引导过,直接放行到工作台
  useEffect(() => {
    if (onboardingDone()) navigate('/workitems', { replace: true });
  }, [navigate]);

  const next = () => setCurrent((v) => Math.min(v + 1, TOTAL - 1));
  const prev = () => setCurrent((v) => Math.max(v - 1, 0));

  const trySkip = () => {
    if (probe === 'online') {
      finish();
      return;
    }
    setSkipOpen(true);
  };

    const eyebrow = useMemo<Record<number, string>>(() => ({
    0: t('step1.eyebrow'), 1: t('step2.eyebrow'), 2: t('step3.eyebrow'), 3: t('step4.eyebrow'),
  }), [t]);
  const nextTitle = useMemo<Record<number, string>>(() => ({
    0: t('step2.title'), 1: t('step3.title'), 2: t('step4.title'),
  }), [t]);

  const isLast = current === TOTAL - 1;
  const pad = (n: number) => String(n).padStart(2, '0');

  return (
    <div className="onb">
      <div className="wrap">
        <div className="top">
          <div className="brand">Code<em>Flow</em></div>
          <div className="pg">{t('pageTag', { current: pad(current + 1), total: pad(TOTAL) })}</div>
          <button className="skip-top" onClick={trySkip}>{t('skip')}</button>
        </div>

        <div className="bignum">{pad(current + 1)}<small>{eyebrow[current]}</small></div>

        {/* ── 1 认识模型 ── */}
        {current === 0 && (
          <>
            <h1>{t('step1.headingHtml')}</h1>
            <p className="lead">{t('step1.lead')}</p>

            <div className="flow">
              {[
                { n: t('step1.f1n'), tt: t('step1.f1t'), d: t('step1.f1d'), who: t('who.human'), cls: 'human' },
                { n: t('step1.f2n'), tt: t('step1.f2t'), d: t('step1.f2d'), who: t('who.auto'), cls: '' },
                { n: t('step1.f3n'), tt: t('step1.f3t'), d: t('step1.f3d'), who: t('who.auto'), cls: '' },
                { n: t('step1.f4n'), tt: t('step1.f4t'), d: t('step1.f4d'), who: t('who.human'), cls: 'human' },
              ].map((f: { n: string; tt: string; d: string; who: string; cls: string }) => (
                <div className="frow" key={f.n}>
                  <div className="num">{f.n}</div>
                  <div className="t">{f.tt}</div>
                  <div className="d">{f.d}</div>
                  <div className={`tag2 ${f.cls}`}>{f.who}</div>
                </div>
              ))}
            </div>

            <div className="key">
              <div className="kt">{t('step1.keyTag')}</div>
              <p>{t('step1.keyHtml')}</p>
            </div>
          </>
        )}

        {/* ── 2 跑起运行器 ── */}
        {current === 1 && (
          <>
            <h1>{t('step2.heading')}</h1>
            <p className="lead">{t('step2.lead')}</p>

            <div className={`probe ${probe}`} data-testid="onb-probe" role="status">
              <div className="probe__icon">
                {probe === 'loading' && <Spin size="small" />}
                {probe === 'online' && <CheckOutlined />}
                {probe !== 'online' && probe !== 'loading' && '!'}
              </div>
              <div className="probe__body">
                <div className="probe__t">
                  {probe === 'loading' && t('step2.probing')}
                  {probe === 'online' && t('step2.onlineTitle')}
                  {probe === 'offline' && t('step2.offlineTitle')}
                  {probe === 'no-workspace' && t('step2.noWorkspaceTitle')}
                </div>
                <div className="probe__d">
                  {probe === 'loading' && t('step2.probingDesc')}
                  {probe === 'online' && t('step2.onlineDesc')}
                  {probe === 'offline' && t('step2.offlineDesc')}
                  {probe === 'no-workspace' && t('step2.noWorkspaceDesc')}
                </div>
              </div>
              <span className={`probe__badge ${probe}`}>
                {probe === 'online' ? t('step2.badgeOnline') : t('step2.badgeProbe')}
              </span>
            </div>

            <div className="steps3">
              <div className="srow">
                <div className="sn">{t('step2.s1n')}</div>
                <div className="sbody">
                  <div className="st">{t('step2.s1t')}</div>
                  <div className="sd">{t('step2.s1d')}</div>
                  <Button className="sbtn" onClick={() => goStep('/executors')}>{t('step2.s1btn')}</Button>
                </div>
              </div>
              <div className="srow">
                <div className="sn">{t('step2.s2n')}</div>
                <div className="sbody">
                  <div className="st">{t('step2.s2t')}</div>
                  <div className="sd">{t('step2.s2d')}</div>
                </div>
              </div>
              <div className="srow">
                <div className="sn">{t('step2.s3n')}</div>
                <div className="sbody">
                  <div className="st">{t('step2.s3t')}</div>
                  <div className="sd">{t('step2.s3d')}</div>
                </div>
              </div>
            </div>

            {cmd && (
              <div className="cmdcard" data-testid="onb-cmd">
                <div className="cmdcard__tag">{t('step2.cmdTag')}</div>
                <pre className="cmdcard__pre">{cmd}</pre>
                <button className="cmdcard__copy" onClick={copyCommand}>
                  {copied ? t('step2.copied') : t('step2.copyCmd')}
                </button>
              </div>
            )}
            {cmdState === 'error' && (
              <div className="cmdcard cmdcard--err">{t('step2.cmdError')}</div>
            )}
          </>
        )}

        {/* ── 3 预置阵容 ── */}
        {current === 2 && (
          <>
            <h1>{t('step3.heading')}</h1>
            <p className="lead">{t('step3.lead')}</p>

            <div className="flow">
              {[
                { tt: t('step3.squadTitle'), d: t('step3.squadBodyDesc') },
                { tt: t('step3.agentTitle'), d: t('step3.agentDesc') },
                { tt: t('step3.sdlcTitle'), d: t('step3.sdlcDesc') },
              ].map((f: { tt: string; d: string }, i: number) => (
                <div className="frow" key={i}>
                  <div className="num">{pad(i + 1)}</div>
                  <div className="t">{f.tt}</div>
                  <div className="d">{f.d}</div>
                  <div className="tag2">{t('step3.presetTag')}</div>
                </div>
              ))}
            </div>

            <div className="key">
              <div className="kt">{t('step3.customTag')}</div>
              <p>{t('step3.customLead')}</p>
            </div>
            <div className="quicklinks">
              <button className="ql" onClick={() => goStep('/squads')}>{t('step3.goSquads')}</button>
              <button className="ql" onClick={() => navigate('/sdlcs/generate')}>{t('step3.goGenerate')}</button>
              <button className="ql" onClick={() => navigate('/repos')}>{t('step3.goRepos')}</button>
            </div>
          </>
        )}

        {/* ── 4 打通需求源 ── */}
        {current === 3 && (
          <>
            <h1>{t('step4.heading')}</h1>
            <p className="lead">{t('step4.lead')}</p>

            <div className="flow">
              <div className="frow">
                <div className="num">{t('step4.s1n')}</div>
                <div className="t">{t('step4.s1t')}</div>
                <div className="d">{t('step4.s1d')}</div>
              </div>
              <div className="frow">
                <div className="num">{t('step4.s2n')}</div>
                <div className="t">{t('step4.s2t')}</div>
                <div className="d">{t('step4.s2d')}</div>
              </div>
              <div className="frow">
                <div className="num">{t('step4.s3n')}</div>
                <div className="t">{t('step4.s3t')}</div>
                <div className="d">{t('step4.s3d')}</div>
              </div>
            </div>

            <div className="key">
              <div className="kt">{t('step4.keyTag')}</div>
              <p>{t('step4.optionalNote')}</p>
            </div>
            <div className="quicklinks">
              <button className="ql ql--primary" onClick={() => goStep('/integrations')}>{t('step4.goIntegrations')}</button>
              <button className="ql" onClick={() => navigate('/workitems/new')}>{t('step4.createManually')}</button>
            </div>
          </>
        )}

        {/* 底部导航 */}
        <div className="nav">
          <button className="back" disabled={current === 0} onClick={prev}>
            ← {t('prev')}
          </button>
          {isLast ? (
            <button className="next" onClick={finish} data-testid="onb-finish">
              {t('finishGoWorkitems')} <span className="circ">✓</span>
            </button>
          ) : (
            <button className="next" onClick={next}>
              {t('nextLabel', { title: nextTitle[current] ?? '' })} <span className="circ">→</span>
            </button>
          )}
        </div>
        <div className="later">
          <button onClick={finish}>{t('finishLater')}</button>
        </div>
      </div>

      <Modal
        open={skipOpen}
        title={t('skipConfirmTitle')}
        okText={t('skipConfirmOk')}
        cancelText={t('skipConfirmCancel')}
        onCancel={() => setSkipOpen(false)}
        onOk={() => {
          setSkipOpen(false);
          finish();
        }}
      >
        <p>{t('skipConfirmContent')}</p>
        <Checkbox onChange={(e) => { if (e.target.checked) markOnboardingDone(); }}>
          {t('skipDontShow')}
        </Checkbox>
      </Modal>
    </div>
  );
}

export function shouldShowOnboarding(): boolean {
  return !onboardingDone();
}
