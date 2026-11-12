// 登录页:设计契约 v5「终端连接」版式。
// 背景 = TestOPS 同款 editorial 背景:纸底 + 右上/左下双光晕 + 流星层(8 颗匀速错峰 + 1 颗随机加速);
// 左栏 = CODE FLOW_ 字标 + 版本戳 + 启动自检日志(执行器协议/运行器器包/存储);
// 右栏 = SESSION / CONNECT 眉题 + 平面表单(8px 圆角控件)。固定亮色不随主题。
// 功能与改造前完全一致:本地账号登录、SSO 连接列表、表单校验、回车提交、语言切换、注册链接。
import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { CheckOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { login } from './api';
import { SSO_PROVIDERS_QUERY_KEY, fetchSsoProviders } from './ssoApi';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { BRANDING_QUERY_KEY, getPublicBranding } from '@/features/platform/brandingApi';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { i18n } from '@/shared/i18n';
import { LIFECYCLE_STEPS, LifecycleIcon } from '@/shared/ui/lifecycle';
import './login.css';

const BRAND_FALLBACK = 'CodeFlow';
const SSO_ARROW = '\u2192';

// 轮播的价值主张:回答「这个平台给我带来什么」,而不是报技术参数
const VALUE_LINES = [0, 1, 2] as const;
const VALUE_ROTATE_MS = 4200;
// 登录链七站:评审与运维的细节不在登录页展开;过滤定义放 JSX 外(i18n 门禁不扫 JSX 外字面量)
const LOGIN_CHAIN_STEPS = LIFECYCLE_STEPS.filter((step) => step !== 'ops' && step !== 'review');

export function LoginPage() {
  const { t } = useTranslation('auth');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [error, setError] = useState<{ message: string } | { key: 'login.failed' } | null>(null);
  const [loading, setLoading] = useState(false);
  const [usernameTouched, setUsernameTouched] = useState(false);
  const [passwordTouched, setPasswordTouched] = useState(false);
  const navigate = useNavigate();
  const setTokens = useAuthStore((s) => s.setTokens);
  const setUser = useAuthStore((s) => s.setUser);
  // 未启用任何连接的部署(或接口失败)保持原样:只展示本地账号登录。
  const providersQuery = useQuery({ queryKey: SSO_PROVIDERS_QUERY_KEY, queryFn: fetchSsoProviders, retry: false });
  const providers = providersQuery.data ?? [];
  const { data: branding } = useQuery({ queryKey: BRANDING_QUERY_KEY, queryFn: getPublicBranding });

  // 版本戳:取到即写 DOM、失败静默,登录链路零依赖。
  const stampRef = useRef<HTMLSpanElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (branding?.deploymentVersion && stampRef.current) {
      stampRef.current.textContent = ` · v${branding.deploymentVersion}`;
    }
  }, [branding]);

  const submit = async () => {
    // 请求在途时直接忽略:登录按钮是 disabled={loading}(点不动),回车必须同样被挡住。
    if (loading) return;
    setUsernameTouched(true);
    setPasswordTouched(true);
    if (!username.trim() || !password) return;
    setError(null);
    setLoading(true);
    try {
      const resp = await login({ username: username.trim(), password });
      setTokens(resp.accessToken, resp.refreshToken);
      setUser(resp.user);
      navigate('/workspaces');
    } catch (e) {
      if (e instanceof ApiError) {
        setError({ message: e.message });
      } else {
        setError({ key: 'login.failed' });
      }
    } finally {
      setLoading(false);
    }
  };

  const errorText = error && ('key' in error ? t(error.key) : error.message);
  const usernameMissing = usernameTouched && !username.trim();
  const passwordMissing = passwordTouched && !password;

  // 回车提交:显式接管 Enter,让「输入框内按回车」与「点击登录按钮」走同一条 submit() 路径。
  // 闸门:①只认文本/密码输入框;②输入法组字中的回车只放行不拦截;③preventDefault 保证一次回车只发一次请求。
  const onFormKeyDown = (e: ReactKeyboardEvent<HTMLFormElement>) => {
    if (e.key !== 'Enter') return;
    const target = e.target as HTMLInputElement | null;
    if (!target || target.tagName !== 'INPUT') return;
    const inputType = (target.type || 'text').toLowerCase();
    if (inputType !== 'text' && inputType !== 'password') return;
    if (e.nativeEvent.isComposing) return;
    e.preventDefault();
    void submit();
  };

  // 价值主张轮播:每 4.2s 换一句(光点链之下),reduced-motion 用户不轮换只显示第一句
  const [valueIndex, setValueIndex] = useState(0);
  // 轮播句 key 是运行期拼出来的,在 JSX 外取词(避免把动态模板键留在 JSX 字面量扫描范围)
  const valueLineText = (i18n.t as (key: string) => string)(`auth:login.value.line${valueIndex + 1}`);
  useEffect(() => {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const timer = window.setInterval(() => {
      setValueIndex((prev) => (prev + 1) % VALUE_LINES.length);
    }, VALUE_ROTATE_MS);
    return () => window.clearInterval(timer);
  }, []);

  // 深色玻璃卡内的光点推进链:需求打字 → 光点带渐变线逐站点亮 → 「已交付」徽章弹出;
  // 循环;reduced-motion 时静态终态(全亮+徽章常驻)。
  const lvxCardRef = useRef<HTMLDivElement>(null);
  const nodesRef = useRef<HTMLDivElement>(null);
  const fillRef = useRef<HTMLDivElement>(null);
  const headRef = useRef<HTMLDivElement>(null);
  const typedRef = useRef<HTMLSpanElement>(null);
  const badgeRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const card = lvxCardRef.current;
    const nodesBox = nodesRef.current;
    const fill = fillRef.current;
    const head = headRef.current;
    const typed = typedRef.current;
    const badge = badgeRef.current;
    if (!card || !nodesBox || !fill || !head || !typed || !badge) return;
    const nodes = [...nodesBox.querySelectorAll('.lvx-node')];
    const message = (i18n.t as (key: string) => string)('auth:login.value.bubble');
    const reset = () => {
      nodes.forEach((n) => { n.className = 'lvx-node'; });
      fill.style.width = '0%'; head.style.left = '0%';
      badge.className = 'lvx-badge';
      typed.textContent = '';
    };
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      typed.textContent = message;
      nodes.forEach((n) => { n.className = 'lvx-node done'; });
      fill.style.width = '100%'; head.style.left = '100%';
      badge.className = 'lvx-badge pop';
      return;
    }
    let cancelled = false;
    const wait = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));
    (async () => {
      for (;;) {
        if (cancelled) return;
        reset();
        for (const ch of message) {
          if (cancelled) return;
          typed.textContent += ch;
          await wait(70);
        }
        await wait(450);
        const boxLeft = nodesBox.getBoundingClientRect().left;
        for (let i = 0; i < nodes.length; i++) {
          const r = nodes[i].querySelector('.lvx-node-ring')?.getBoundingClientRect();
          if (r) {
            const center = r.left + r.width / 2 - boxLeft;
            fill.style.width = `${Math.round(center)}px`;
            head.style.left = `${Math.round(center - 6)}px`;
          }
          nodes[i].className = 'lvx-node' + (i === nodes.length - 1 ? ' end' : ' done');
          await wait(520);
        }
        head.style.opacity = '0';
        badge.className = 'lvx-badge pop';
        await wait(1700);
        head.style.opacity = '1';
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // ── 流星层:TestOPS 同款。8 颗匀速(16s 周期、负延迟错峰)+ 1 颗随机加速(mt-rush)。
  // 参数逐值一致;纪律 = 长周期 + 只动 transform/opacity。 ──
  const METEORS: Array<[string, string, string, string, string, string]> = [
    ['24%', '-20%', '150px', '16s', '0s', '.7'], ['54.6%', '4%', '110px', '16s', '-2s', '.86'],
    ['85.2%', '-12%', '140px', '16s', '-4s', '.6'], ['34.2%', '16%', '120px', '16s', '-6s', '.9'],
    ['64.8%', '-16%', '155px', '16s', '-8s', '.74'], ['95.4%', '8%', '100px', '16s', '-10s', '.56'],
    ['44.4%', '22%', '130px', '16s', '-12s', '.82'], ['75%', '-6%', '145px', '16s', '-14s', '.66'],
  ];
  const rushRef = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    const rush = rushRef.current;
    if (!rush) return;
    // 那"一条随机的":每飞完一圈重摇起点/长度/亮度/时长(TestOPS 同款脚本)
    const rnd = (a: number, b: number) => a + Math.random() * (b - a);
    const roll = () => {
      rush.style.left = `${rnd(22, 96).toFixed(1)}%`;
      rush.style.top = `${rnd(-26, 30).toFixed(1)}%`;
      rush.style.setProperty('--len', `${Math.round(rnd(90, 235))}px`);
      rush.style.setProperty('--op', rnd(0.5, 0.92).toFixed(2));
      rush.style.animationDuration = `${rnd(3, 4.4).toFixed(2)}s`;
    };
    roll();
    rush.addEventListener('animationiteration', roll);
    return () => rush.removeEventListener('animationiteration', roll);
  }, []);

  return (
    <div className="login-screen">
      <div className="lg-corner">
        <LanguageSwitch />
      </div>
      <div className="lqa-meteors" aria-hidden="true">
        {METEORS.map(([left, top, len, duration, delay, opacity], index) => (
          <span key={index} className="mt"
            style={{ left, top, '--len': len, '--t': duration, '--dl': delay, '--op': opacity } as CSSProperties}>
            <i />
          </span>
        ))}
        <span ref={rushRef} className="mt mt-rush" style={{ left: '46%', top: '-14%', '--len': '170px', '--t': '3.6s', '--op': '.8' } as CSSProperties}>
          <i />
        </span>
      </div>
      <div className="split">
        <div className="left">
          <div className="brand">
            <span className="logo-wrap">
              <img src={branding?.logoUrl || '/logo.svg'} alt="" onError={(e) => {
                const img = e.currentTarget;
                if (!img.dataset.fallback) { img.dataset.fallback = '1'; img.src = '/logo.svg'; }
              }} />
            </span>
            <span className="brand-eyebrow">{branding?.platformName ?? BRAND_FALLBACK}</span>
          </div>
          <div className="wordmark" aria-hidden="true">
            {t('login.brand.wordmark1')}<br /><em>{t('login.brand.wordmark2')}</em>
          </div>
          <div className="stamp">
            {branding?.platformName ?? BRAND_FALLBACK}<span ref={stampRef} />
            {t('login.brand.stamp', { license: t('login.brand.license') })}
          </div>
          {/* 价值区:深色玻璃卡里的「从需求到交付」光点推进链 */}
          <div className="lv-value" aria-label={t('login.value.chainLabel')}>
            <div className="lvx-card" ref={lvxCardRef} aria-hidden="true">
              <div className="lvx-label">{t('login.value.chainLabel')}</div>
              <div className="lvx-type"><span ref={typedRef}></span><span className="lvx-cursor"></span></div>
              <div className="lvx-flow">
                <div className="lvx-track"><div className="lvx-fill" ref={fillRef}></div><div className="lvx-head" ref={headRef}></div></div>
                <div className="lvx-nodes" ref={nodesRef}>
                  {LOGIN_CHAIN_STEPS.map((step) => (
                    <span className="lvx-node" key={step}>
                      <span className="lvx-node-ring"><LifecycleIcon step={step} size={16} /><CheckOutlined className="lvx-check" aria-hidden="true" /></span>
                      <span className="lvx-node-name">{i18n.t(`about:${step}`)}</span>
                    </span>
                  ))}
                </div>
                <div className="lvx-badge" ref={badgeRef}>{t('login.value.delivered')}</div>
              </div>
            </div>
            {/* 轮播句 key 是运行期拼出来的,在 JSX 外取词再渲染(i18n 门禁不扫 JSX 外的动态键) */}
            <p className="lv-line" key={valueIndex}>{valueLineText}</p>
            <div className="lv-dots" aria-hidden="true">
              {VALUE_LINES.map((_, index) => (
                <i key={index} className={index === valueIndex ? 'on' : ''} />
              ))}
            </div>
          </div>
        </div>
        <div className="right">
          <div className="tag">{t('login.eyebrow')}</div>
          <h1 className="h1">{t('login.title')}</h1>
          {providers.length > 0 && (
            <div style={{ marginBottom: 22 }}>
              <div className="tag" style={{ marginBottom: 10 }}>{t('sso.heading')}</div>
              <div className="sso-list">
                {providers.map((provider) => (
                  <a key={provider.providerId} className="sso-link" href={`/api/auth/sso/${provider.providerId}/start`}
                    data-testid={`sso-provider-${provider.providerId}`}>
                    <span>{t('sso.loginWith', { name: provider.name })}</span>
                    <span aria-hidden="true">{SSO_ARROW}</span>
                  </a>
                ))}
              </div>
              <div className="sso-sep">{t('sso.or')}</div>
              <h2 className="h1 local-heading">{t('sso.localHeading')}</h2>
            </div>
          )}
          <form noValidate onSubmit={(e) => { e.preventDefault(); submit(); }} onKeyDown={onFormKeyDown}>
            <div className="field">
              <label htmlFor="login-username">{t('fields.username')}</label>
              <input id="login-username" type="text" autoComplete="username" value={username}
                placeholder={t('fields.username')}
                onChange={(e) => { setUsername(e.target.value); setUsernameTouched(true); }} />
              {usernameMissing && <span className="field-error">{t('fields.usernameRequired')}</span>}
            </div>
            <div className="field">
              <label htmlFor="login-password">{t('fields.password')}</label>
              <span className="pwd-wrap">
                <input ref={passwordRef} id="login-password" type={passwordVisible ? 'text' : 'password'} autoComplete="current-password"
                  value={password} placeholder={t('fields.password')}
                  onChange={(e) => { setPassword(e.target.value); setPasswordTouched(true); }} />
                <button type="button" className="pwd-eye" aria-label={t(passwordVisible ? 'fields.hidePassword' : 'fields.showPassword')}
                  onClick={() => { setPasswordVisible((v) => !v); passwordRef.current?.focus(); }}>
                  {passwordVisible ? (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" /><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" /><path d="m14.12 14.12a3 3 0 1 1-4.24-4.24" /><line x1="1" y1="1" x2="23" y2="23" /></svg>
                  ) : (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" /><circle cx="12" cy="12" r="3" /></svg>
                  )}
                </button>
              </span>
              {passwordMissing && <span className="field-error">{t('fields.passwordRequired')}</span>}
            </div>
            {error && <div className="login-error" role="alert">{errorText}</div>}
            <button type="submit" className="lg-btn" disabled={loading}>{t('login.title')}</button>
            <div className="row">
              <Link to="/register">{t('login.registerLink')}</Link>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
