import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { LoginPage } from './LoginPage';
import { useAuthStore } from '@/shared/auth/store';

function renderLogin() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter>
      <LoginPage />
    </MemoryRouter></QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  afterEach(async () => { vi.restoreAllMocks(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders login form', () => {
    renderLogin();
    expect(screen.getAllByRole('button', { name: '切换语言' })).toHaveLength(1);
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/, { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('keeps the pending submit action localized and raw failure readable through an in-flight language change', async () => {
    let release!: () => void; let started = false;
    const pending = new Promise<void>(resolve => { release = resolve; });
    server.use(http.post('/api/auth/login', async () => { started = true; await pending; return HttpResponse.json({ success: false, code: '10401', message: '<raw 登录错误>', data: null }); }));
    renderLogin(); const user = userEvent.setup();
    await user.type(screen.getByLabelText('用户名'), 'raw user');
    await user.type(screen.getByLabelText('密码'), 'raw secret');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));
    await waitFor(() => expect(started).toBe(true));
    // Finish the fake request even when a pending-state assertion fails.
    try {
      expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument();
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
      await act(async () => { await i18n.changeLanguage('en-US'); });
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
      expect(screen.getByLabelText('Password')).toHaveValue('raw secret');
    } finally { await act(async () => { release(); }); }
    expect(await screen.findByText('<raw 登录错误>')).toBeInTheDocument();
  });

  it.each(['zh-CN', 'en-US'])('keeps the raw password and keyboard toggle through live locale changes from %s', async locale => {
    const errors = vi.spyOn(console, 'error');
    await act(async () => { await i18n.changeLanguage(locale); });
    renderLogin(); const user = userEvent.setup();
    const password = screen.getByLabelText(locale === 'zh-CN' ? '密码' : 'Password');
    await act(async () => { await user.type(password, '<raw secret>'); });
    await act(async () => { await user.tab(); });
    const show = screen.getByRole('button', { name: locale === 'zh-CN' ? '显示密码' : 'Show password' });
    expect(show).toHaveFocus();
    await act(async () => { await user.keyboard('{Enter}'); });
    expect(password).toHaveAttribute('type', 'text');
    expect(password).toHaveValue('<raw secret>');
    expect(password).toHaveFocus();
    await act(async () => { await i18n.changeLanguage(locale === 'zh-CN' ? 'en-US' : 'zh-CN'); });
    expect(screen.getByLabelText(locale === 'zh-CN' ? 'Password' : '密码')).toBe(password);
    const hide = screen.getByRole('button', { name: locale === 'zh-CN' ? 'Hide password' : '隐藏密码' });
    expect(password).toHaveFocus();
    await act(async () => { await user.tab(); });
    expect(hide).toHaveFocus();
    await act(async () => { await user.keyboard('{Enter}'); });
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('<raw secret>');
    expect(screen.queryByRole('img', { name: /eye|user|lock/ })).not.toBeInTheDocument();
    expect(errors.mock.calls.filter(args => args.some(value => typeof value === 'string' && (value.includes('not wrapped in act') || value.includes('not configured to support act'))))).toHaveLength(0);
  });

  it('renders the value-first layout: lifecycle marquee and rotating value lines', async () => {
    renderLogin();
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    // 左栏:CODE FLOW_ 字标 + 价值区(生命周期跑马灯 + 轮播价值主张)
    expect(screen.getByText('CODE')).toBeInTheDocument();
    expect(screen.getByText('FLOW')).toBeInTheDocument();
    const value = screen.getByLabelText('从需求到交付的生命周期');
    expect(value.textContent).toContain('需求澄清');
    expect(value.textContent).toContain('交付');
    expect(value.textContent).not.toContain('运维');

    expect(value.textContent).toContain('一句话需求，自动拆成可执行的计划');
    // 右栏:SESSION / CONNECT 眉题 + 平面表单
    expect(screen.getByText('SESSION / CONNECT')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
    // 技术自检日志不再是登录页叙事:不出现 mysql/运行器包等参数
    expect(screen.queryByText(/mysql 8/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ymai-codeflow@/)).not.toBeInTheDocument();
    // 旧 editorial 版式的元素不再存在
    expect(screen.queryByText('换一句')).not.toBeInTheDocument();
    expect(screen.queryByText(/CODEFLOW MANIFESTO/)).not.toBeInTheDocument();
    expect(screen.queryByText('上手步骤')).not.toBeInTheDocument();
    expect(screen.queryByRole('figure', { name: /软件自动交付流程示意/ })).not.toBeInTheDocument();
  });

  it('calls login API and stores authenticated user on success', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            userId: 1,
            accessToken: 'acc-1',
            refreshToken: 'ref-1',
            user: {
              id: 1,
              username: 'caihe',
              nickname: '蔡何',
              email: 'caihe@example.com',
            },
          },
          traceId: null,
        });
      }),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'alice');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('acc-1');
    });
    expect(useAuthStore.getState().user).toEqual({
      id: 1,
      username: 'caihe',
      nickname: '蔡何',
      email: 'caihe@example.com',
    });
  });

  it('shows error message on login failure', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: false,
          code: '10401',
          message: '用户名或密码错误',
          data: null,
          traceId: 'trace-x',
        });
      }),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'wrong');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByText(/用户名或密码错误/)).toBeInTheDocument();
    });
  });

  it('shows backend message when login fails with HTTP 401', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: false,
          code: '10401',
          message: '用户名或密码错误',
          data: null,
          traceId: 'trace-y',
        }, { status: 401 });
      }),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'wrong');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByText(/用户名或密码错误/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/未登录或登录已失效/)).not.toBeInTheDocument();
  });

  it('lists enabled enterprise connections as link entries above the local form', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [
            { providerId: 3, name: '企业 ADFS', protocol: 'SAML2', idpName: 'adfs.corp.example' },
            { providerId: 5, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' },
          ],
        });
      }),
    );

    renderLogin();

    const first = await screen.findByRole('link', { name: '使用 企业 ADFS 登录' });
    expect(first).toHaveAttribute('href', '/api/auth/sso/3/start');
    expect(screen.getByRole('link', { name: '使用 Contoso 登录' })).toHaveAttribute('href', '/api/auth/sso/5/start');
    expect(screen.getByText('选择公司的身份系统登录 CodeFlow')).toBeInTheDocument();
    expect(screen.getByText('或')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '使用 CodeFlow 账号登录' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('hides the enterprise section and keeps the local form when no connection is enabled', async () => {
    let providersFetched = false;
    let loginCalled = false;
    server.use(
      http.get('/api/auth/sso/providers', () => {
        providersFetched = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
      http.post('/api/auth/login', () => {
        loginCalled = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            userId: 1,
            accessToken: 'acc-2',
            refreshToken: 'ref-2',
            user: { id: 1, username: 'bob', nickname: '鲍勃', email: 'bob@example.com' },
          },
        });
      }),
    );

    renderLogin();

    expect(await screen.findByLabelText(/用户名/)).toBeInTheDocument();
    await waitFor(() => expect(providersFetched).toBe(true));
    expect(screen.queryByText('选择公司的身份系统登录 CodeFlow')).not.toBeInTheDocument();
    expect(screen.queryByText('使用 CodeFlow 账号登录')).not.toBeInTheDocument();
    expect(screen.queryByText('或')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(loginCalled).toBe(true));
    expect(useAuthStore.getState().accessToken).toBe('acc-2');
  });

  it('keeps the local login usable when the enabled connections cannot be loaded', async () => {
    let providersFetched = false;
    let loginCalled = false;
    server.use(
      http.get('/api/auth/sso/providers', () => {
        providersFetched = true;
        return HttpResponse.json(
          { success: false, code: '10002', message: '服务暂时不可用', data: null, traceId: null },
          { status: 500 },
        );
      }),
      http.post('/api/auth/login', () => {
        loginCalled = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            userId: 1,
            accessToken: 'acc-3',
            refreshToken: 'ref-3',
            user: { id: 1, username: 'bob', nickname: '鲍勃', email: 'bob@example.com' },
          },
        });
      }),
    );

    renderLogin();

    await waitFor(() => expect(providersFetched).toBe(true));
    expect(screen.queryByText('选择公司的身份系统登录 CodeFlow')).not.toBeInTheDocument();
    expect(screen.queryByText('服务暂时不可用')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(loginCalled).toBe(true));
    expect(useAuthStore.getState().accessToken).toBe('acc-3');
  });

  // ---- 工作项 #10018:回车键提交登录表单 ----
  // 需求:用户名或密码输入框内按 Enter 即触发表单登录提交,行为与点击「登录」按钮完全一致。
  // 实现:LoginPage 的 <form> 上挂 onKeyDown 显式接管 Enter(只认文本/密码输入框、IME 组字中跳过、
  // preventDefault 抑制浏览器原生隐式提交),回车与点击共用同一个 submit()。
  describe('Enter 提交登录表单', () => {
    const SUCCESS_BODY = {
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data: {
        userId: 1,
        accessToken: 'acc-enter',
        refreshToken: 'ref-enter',
        user: { id: 1, username: 'alice', nickname: '爱丽丝', email: 'alice@example.com' },
      },
    };
    const FAIL_BODY = { success: false, code: '10401', message: '用户名或密码错误', data: null, traceId: 'trace-enter' };

    it('在用户名输入框内按 Enter 即提交登录并写入会话', async () => {
      let called = 0; let body: unknown = null;
      server.use(http.post('/api/auth/login', async ({ request }) => {
        called += 1; body = await request.json();
        return HttpResponse.json(SUCCESS_BODY);
      }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('用户名'));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(called).toBe(1));
      expect(body).toEqual({ username: 'alice', password: 'pass123' });
      await waitFor(() => expect(useAuthStore.getState().accessToken).toBe('acc-enter'));
    });

    it('在密码输入框内按 Enter 即提交登录并写入会话', async () => {
      let called = 0; let body: unknown = null;
      server.use(http.post('/api/auth/login', async ({ request }) => {
        called += 1; body = await request.json();
        return HttpResponse.json(SUCCESS_BODY);
      }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(called).toBe(1));
      expect(body).toEqual({ username: 'alice', password: 'pass123' });
      await waitFor(() => expect(useAuthStore.getState().accessToken).toBe('acc-enter'));
    });

    it('回车提交失败时的错误提示与点击「登录」按钮完全一致', async () => {
      server.use(http.post('/api/auth/login', () => HttpResponse.json(FAIL_BODY)));

      const errorTextBy = async (via: 'enter' | 'click') => {
        renderLogin();
        const user = userEvent.setup();
        await user.type(screen.getByLabelText('用户名'), 'bob');
        await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'wrong');
        if (via === 'enter') {
          await user.click(screen.getByLabelText('密码', { selector: 'input' }));
          await user.keyboard('{Enter}');
        } else {
          await user.click(screen.getByRole('button', { name: /登\s*录/ }));
        }
        const alert = await screen.findByRole('alert');
        const text = alert.textContent ?? '';
        cleanup(); // 两次渲染必须隔离,否则 screen 查询会命中多个节点
        return text;
      };

      const viaEnter = await errorTextBy('enter');
      const viaClick = await errorTextBy('click');
      expect(viaEnter).toContain('用户名或密码错误');
      expect(viaEnter).toBe(viaClick);
    });

    it('空表单按 Enter 只出必填校验、不发请求', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.click(screen.getByLabelText('用户名'));
      await user.keyboard('{Enter}');

      expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
      expect(await screen.findByText('请输入密码')).toBeInTheDocument();
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    it('「显示/隐藏密码」按钮上按 Enter 只切换可见性、不触发表单提交', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.tab(); // 焦点落到「显示密码」按钮(type=button)

      const toggle = screen.getByRole('button', { name: '显示密码' });
      expect(toggle).toHaveFocus();
      await user.keyboard('{Enter}');

      expect(screen.getByLabelText('密码', { selector: 'input' })).toHaveAttribute('type', 'text');
      expect(screen.getByLabelText('密码', { selector: 'input' })).toHaveValue('pass123');
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    it('请求在途时再按 Enter 不产生第二次请求(与按钮 disabled 行为一致)', async () => {
      let release!: () => void; let called = 0;
      const pending = new Promise<void>(resolve => { release = resolve; });
      server.use(http.post('/api/auth/login', async () => {
        called += 1; await pending; return HttpResponse.json(SUCCESS_BODY);
      }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');
      await waitFor(() => expect(called).toBe(1));

      await user.keyboard('{Enter}'); // 在途再按一次回车
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(1);

      await act(async () => { release(); });
      await waitFor(() => expect(useAuthStore.getState().accessToken).toBe('acc-enter'));
    });

    // jsdom 不实现浏览器的「回车隐式提交」默认行为,user-event 只在 keydown 未被取消时
    // 点一次提交按钮;而 React 18 对离散事件(keydown)同步 flush,按钮会在原生默认行为
    // 之前就变成 disabled —— 于是「双提交」在 jsdom 里被 disabled 顺手挡住了,
    // 掩盖了 preventDefault 是否真的生效。要锁住 preventDefault 这条契约,必须直接
    // 观察事件本身:dispatchEvent 返回 false == 事件被 preventDefault 了。
    it('回车事件被 preventDefault 抑制(不与浏览器原生隐式提交叠加)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

      // 契约:处理器必须吞掉回车的默认行为,浏览器才不会再叠加一次原生隐式提交。
      const notPrevented = fireEvent.keyDown(screen.getByLabelText('用户名'), { key: 'Enter' });
      expect(notPrevented).toBe(false);
      await waitFor(() => expect(called).toBe(1));
    });

    it('输入法组字中(IME composing)的回车不提交、也不被吞掉', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

      // 组字中的回车是「确认候选词」,既不能提交登录,也不能 preventDefault 抢走输入法的按键。
      const notPrevented = fireEvent.keyDown(screen.getByLabelText('用户名'), { key: 'Enter', isComposing: true });
      expect(notPrevented).toBe(true);
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    // ==========================================================================
    // 工作项 #10018/#10019 返工(CR 10005 执行评审:R-02 / R-05 / R-07)新增用例
    //
    // 背景:CR 的变异测试发现,原有 T1/T2 两条 AC1 用例**无法区分「本次实现」与「浏览器原生隐式提交」**
    // —— 把整个 onKeyDown 从 form 上摘掉(M11),它们依然全绿;inputType 白名单(M4/M7)被单独摘掉也
    // 没有任何用例抓到(19/19 存活)。机理:jsdom 不实现原生隐式提交,user-event v14 在 keydown
    // **未被取消**时才会模拟「点一次提交按钮」⇒ 走原生路径也能满足「POST 了一次」的断言。
    //
    // 下面这组用例与 T7 同构:**直接观察事件本身**——fireEvent.keyDown 的返回值即
    // 「事件有没有被 preventDefault」,从而把「回车确由本次代码驱动」这条契约钉死。
    // 观察点:fireEvent → element.dispatchEvent(event) → 有 preventDefault 则返回 false。
    // ==========================================================================

    it('密码框的回车同样由显式处理器接管(不被浏览器原生隐式提交顶替)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

      // 密码框必须与用户名框走同一条显式路径:被 preventDefault 才说明是本次处理器接管的。
      // 若白名单被收窄到只剩 text(变异 M7/M12),这里会变成 true ⇒ 本用例变红。
      const notPrevented = fireEvent.keyDown(screen.getByLabelText('密码', { selector: 'input' }), { key: 'Enter' });
      expect(notPrevented).toBe(false);
      await waitFor(() => expect(called).toBe(1));
    });

    it('闸门① 是快路径:email 型输入框不被显式接管(退回浏览器原生路径,不是禁止提交)', () => {
      renderLogin();
      const form = screen.getByLabelText('用户名').closest('form') as HTMLFormElement;
      // 本表单当前没有 email 型控件,临时挂一个探针,用来直接观察闸门① 的判定
      // (若白名单被整个删掉,变异 M4,探针会被接管 ⇒ 本用例变红)。
      const probe = document.createElement('input');
      probe.type = 'email';
      form.appendChild(probe);
      const notPrevented = fireEvent.keyDown(probe, { key: 'Enter' });
      form.removeChild(probe);
      // 不被接管 ⇒ 不 preventDefault ⇒ 浏览器原生隐式提交路径照常可用(本表单有默认按钮)。
      expect(notPrevented).toBe(true);
    });

    it('tagName 闸门(闸门① 的元素判据):非 INPUT 元素(表单本身)不被显式接管', () => {
      renderLogin();
      const form = screen.getByLabelText('用户名').closest('form') as HTMLFormElement;
      // 目标不是 INPUT 时必须放行(变异 M5 把 tagName 闸门放宽到「只判非空」时,这里会被接管 ⇒ 变红)。
      const notPrevented = fireEvent.keyDown(form, { key: 'Enter' });
      expect(notPrevented).toBe(true);
    });

    it('回车由显式处理器接管:不产生原生 submit 事件(不会与浏览器原生隐式提交叠加)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const form = screen.getByLabelText('用户名').closest('form') as HTMLFormElement;
      const nativeSubmit = vi.fn();
      form.addEventListener('submit', nativeSubmit);

      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(called).toBe(1));
      // 显式路径接管后,浏览器不会再额外触发一次原生 submit:user-event 在 keydown 被 preventDefault 时
      // 不再调度 keypress,也就不会去点默认提交按钮(原生隐式提交正是在 keypress 阶段发生的)。
      // 整段显式接管被摘掉时(变异 M11),这里会变成 1 次 ⇒ 本用例变红。
      expect(nativeSubmit).not.toHaveBeenCalled();
    });

    it('表单内的「注册」链接上按 Enter 不触发登录提交(锚点默认行为不被劫持)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

      // 「去注册」是 <a>,位于 <form> 内部 ⇒ 会冒泡到表单级处理器,必须被元素闸门放行。
      const link = screen.getByRole('link', { name: /去注册/ });
      const notPrevented = fireEvent.keyDown(link, { key: 'Enter' });
      expect(notPrevented).toBe(true);
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    it('密码可见态(type=text)下按 Enter 仍由显式处理器接管', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByRole('button', { name: '显示密码' })); // 切成可见态(type=text)

      const password = screen.getByLabelText('密码', { selector: 'input' });
      expect(password).toHaveAttribute('type', 'text');

      const notPrevented = fireEvent.keyDown(password, { key: 'Enter' });
      expect(notPrevented).toBe(false);
      await waitFor(() => expect(called).toBe(1));
    });

    it('密码含空格时按空格键不提交(键闸门只认 Enter)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      const password = screen.getByLabelText('密码', { selector: 'input' });
      await user.type(password, 'pa ss');
      await user.click(password);
      await user.keyboard(' ');

      // 空格键只是普通输入(值里多一个空格),不得触发登录提交。
      expect(password).toHaveValue('pa ss ');
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    // ==========================================================================
    // 工作项 #10018/#10019 · 步骤 10001 返工(CR 10005 执行评审 · R-08)
    //
    // 原用例用 user.keyboard('{Shift>}{Enter}{/Shift}') + expect(called).toBe(1),
    // 但这个写法在 jsdom 里**区分不了**「本次实现接管」与「浏览器原生隐式提交顶替」:
    // user-event v14 只在 keydown **未被取消**时模拟点一次默认提交按钮 ⇒ 即便显式处理器
    // 完全不接管修饰键,原生路径照样发出一次 POST ⇒ called===1 依然成立。
    // CR 的变异 V-14(显式处理器跳过修饰键)实测 35/35 全绿 ⇒ 该用例守不住任何东西。
    //
    // 改写为与 :449 / :493 / :570 同构的「直接观察事件本身」写法:
    //   fireEvent.keyDown 的返回值 === false ⇔ 事件被 preventDefault ⇔ 由显式处理器接管。
    // 三个修饰键变体**各自独立渲染、各自发 1 次回车**(probeModifier 内部 renderLogin() + cleanup()
    // 逐次隔离)⇒ 每个探针各自断言 1 次 POST:断言**不被在途守卫**(if (loading) return)绑架,
    // 也不会因三个探针共用同一渲染或同一个 msw handler 计数而漂移。
    // ==========================================================================
    it('修饰键组合(Shift/Ctrl/Meta+Enter)按默认口径一律按提交处理(待产品确认,此处锁定当前行为)', async () => {
      const probeModifier = async (init: KeyboardEventInit) => {
        let called = 0;
        server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

        renderLogin();
        const user = userEvent.setup();
        await user.type(screen.getByLabelText('用户名'), 'alice');
        await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

        const username = screen.getByLabelText('用户名');
        const form = username.closest('form') as HTMLFormElement;
        const nativeSubmit = vi.fn();
        form.addEventListener('submit', nativeSubmit);

        const notPrevented = fireEvent.keyDown(username, { key: 'Enter', ...init });
        const nativeSubmitCount = nativeSubmit.mock.calls.length;

        // 判别式断言必须**同步**、且排在任何异步等待之前:
        // 「事件被 preventDefault」只有显式处理器接管时才成立 ⇒ 这是区分
        // 「本次实现接管」与「浏览器原生隐式提交顶替」的唯一判据。
        // 若把它排在下面的 waitFor 之后,变异(V-14)下失败信息会变成 waitFor 超时,指不到判别点。
        expect(notPrevented).toBe(false);
        // 接管后必须真的走显式提交路径(发出 1 次请求),且不叠加浏览器原生 submit 事件。
        await waitFor(() => expect(called).toBe(1));
        expect(nativeSubmitCount).toBe(0);
        cleanup(); // 多次渲染必须隔离,否则 screen 查询会命中多个节点
        return { notPrevented, nativeSubmitCount };
      };

      const shift = await probeModifier({ shiftKey: true });
      const ctrl = await probeModifier({ ctrlKey: true });
      const meta = await probeModifier({ metaKey: true });

      // 三个修饰键变体都必须由显式处理器接管(判别式断言已在 probeModifier 内同步完成),
      // 且都不得叠加浏览器原生隐式提交事件。
      expect([shift.notPrevented, ctrl.notPrevented, meta.notPrevented]).toEqual([false, false, false]);
      expect([shift.nativeSubmitCount, ctrl.nativeSubmitCount, meta.nativeSubmitCount]).toEqual([0, 0, 0]);
    });

    // ==========================================================================
    // 工作项 #10018/#10019 · 步骤 10001 补充用例
    //
    // 背景:CR 第 2 轮已判 PASS,head B 的 27 条用例已把「回车与点击同源」这条核心契约钉死
    // (M11 摘掉整个 onKeyDown ⇒ 4 条变红)。但逐条比对工作项 4 条验收标准后,仍有 8 处
    // **行为已实现、却无用例守住**的地方(CR 原文口径:「3 条已实测正确但无用例守住的边界」,
    // 本步把这一类补齐,并把 AC2「完全一致」从单一失败面扩到全部失败面):
    //   N1 部分填写(密码为空)—— 最常见的用户误操作;
    //   N2 失败后重试 —— 锁住在途守卫 `if (loading) return` 会**正确释放**
    //      (若不释放,用户改完密码后再也回车不了 ⇒ AC1 静默失效);
    //   N3 网络中断 —— 补齐 AC2 的第二个失败面(原用例只覆盖了业务码 10401);
    //   N4 用户名 trim —— 锁住请求体里的用户名是 trim 后的值;
    //   N5 纯空白用户名 —— 按缺失处理、不发请求;
    //   N6 IME 组字 —— 锁住「组字中的回车被放行」与「非组字态的回车由显式处理器接管」;
    //   N7 校验失败面 —— AC2「完全一致」必须覆盖**校验失败**,不只是请求失败;
    //   N8 noValidate —— 锁住浏览器原生校验不会静默拦截回车提交。
    // ==========================================================================

    it('部分填写(密码为空)时按 Enter 只提示密码缺失、不发请求', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');

      // submit() 会把两个 touched 标记都置上,但只有「密码为空」这一项该报缺失。
      expect(await screen.findByText('请输入密码')).toBeInTheDocument();
      expect(screen.queryByText('请输入用户名')).not.toBeInTheDocument();
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    it('登录失败后再次按 Enter 仍能提交(在途守卫必须正确释放)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => {
        called += 1;
        return HttpResponse.json({ success: false, code: '10401', message: '用户名或密码错误', data: null, traceId: 'trace-retry' });
      }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'wrong');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');
      await waitFor(() => expect(called).toBe(1));
      expect(await screen.findByRole('alert')).toHaveTextContent('用户名或密码错误');

      // 失败后 loading 必须回到 false,否则用户改对密码再按回车会被在途守卫挡掉:
      // 那会让「回车」比「点击」更严格(点击按钮在 loading 时也是 disabled,点击同样会被挡,
      // 但失败后按钮会重新可点)⇒ 回车必须同步恢复,否则 AC1/AC2 都不成立。
      await user.keyboard('{Enter}');
      await waitFor(() => expect(called).toBe(2));
    });

    it('网络中断时回车与点击的错误提示完全一致(补齐 AC2 的第二个失败面)', async () => {
      server.use(http.post('/api/auth/login', () => HttpResponse.error()));

      const errorTextBy = async (via: 'enter' | 'click') => {
        renderLogin();
        const user = userEvent.setup();
        await user.type(screen.getByLabelText('用户名'), 'alice');
        await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
        if (via === 'enter') {
          await user.click(screen.getByLabelText('密码', { selector: 'input' }));
          await user.keyboard('{Enter}');
        } else {
          await user.click(screen.getByRole('button', { name: /登\s*录/ }));
        }
        const alert = await screen.findByRole('alert');
        const text = alert.textContent ?? '';
        cleanup(); // 两次渲染必须隔离,否则 screen 查询会命中多个节点
        return text;
      };

      const viaEnter = await errorTextBy('enter');
      const viaClick = await errorTextBy('click');
      expect(viaEnter).toBe(viaClick);
      // axios 响应拦截器把所有失败统一规范化为 ApiError ⇒ 展示的是网络错误原文,
      // 而不是 LoginPage 里那条泛化文案 login.failed(该分支在 login() 路径上不可达,
      // 见交付说明「发现 F-01」)。此处锁死「原文透出」这一实际行为。
      expect(viaEnter).toContain('Network Error');
    });

    it('用户名的首尾空格被 trim 后提交', async () => {
      let called = 0; let body: unknown = null;
      server.use(http.post('/api/auth/login', async ({ request }) => {
        called += 1; body = await request.json();
        return HttpResponse.json(SUCCESS_BODY);
      }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), '  alice  ');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(called).toBe(1));
      // 请求体里的用户名是 trim 后的值(与点击路径共用同一个 submit(),两条路径天然一致)。
      expect(body).toEqual({ username: 'alice', password: 'pass123' });
    });

    it('纯空白用户名按缺失处理(不得发请求)', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText('用户名'), '   ');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');
      await user.click(screen.getByLabelText('密码', { selector: 'input' }));
      await user.keyboard('{Enter}');

      expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);
    });

    it('组字中的回车被放行(不提交);随后一次非组字态回车由显式处理器接管', async () => {
      let called = 0;
      server.use(http.post('/api/auth/login', () => { called += 1; return HttpResponse.json(SUCCESS_BODY); }));

      renderLogin();
      const user = userEvent.setup();
      const username = screen.getByLabelText('用户名');
      await user.type(username, 'alice');
      await user.type(screen.getByLabelText('密码', { selector: 'input' }), 'pass123');

      // 组字中:回车是「确认候选词」,不得提交、也不得被吞掉。
      fireEvent.compositionStart(username);
      const notPreventedDuring = fireEvent.keyDown(username, { key: 'Enter', isComposing: true });
      expect(notPreventedDuring).toBe(true);
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 200)); });
      expect(called).toBe(0);

      // 组字结束后再按一次回车(不带 isComposing 标记):必须由显式处理器接管 ⇒ 提交 1 次。
      //
      // 【本用例断言能力的边界】这里**不能**证明「组字守卫在组字结束后不会残留状态」:
      // 实现侧的 onFormKeyDown 不持有任何组字状态(无 ref、无 state),唯一判据是
      // e.nativeEvent.isComposing;而 jsdom 的 KeyboardEvent.isComposing 完全由 dispatch
      // 时的 init 决定,不会因 compositionStart/End 而改变 ⇒ 上面的两行组字事件
      // 对被测实现是无副作用的装饰,本用例只能断言两件事:
      //   ① 组字中的回车被放行(不被 preventDefault、不提交);
      //   ② 非组字态的回车由显式处理器接管(被 preventDefault、提交 1 次)。
      // 跨浏览器真实 IME 组字行为无法自动化,见交付文档的人工验收项 U1。
      fireEvent.compositionEnd(username);
      const notPreventedAfter = fireEvent.keyDown(username, { key: 'Enter' });
      expect(notPreventedAfter).toBe(false);
      await waitFor(() => expect(called).toBe(1));
    });

    it('校验失败面:回车与点击产生的必填提示完全一致(AC2 覆盖校验失败)', async () => {
      const requiredTextBy = async (via: 'enter' | 'click') => {
        renderLogin();
        const user = userEvent.setup();
        await user.click(screen.getByLabelText('用户名'));
        if (via === 'enter') {
          await user.keyboard('{Enter}');
        } else {
          await user.click(screen.getByRole('button', { name: /登\s*录/ }));
        }
        const nodes = await screen.findAllByText(/请输入用户名|请输入密码/);
        const joined = nodes.map(node => node.textContent).sort().join('|');
        cleanup(); // 两次渲染必须隔离,否则 screen 查询会命中多个节点
        return joined;
      };

      const viaEnter = await requiredTextBy('enter');
      const viaClick = await requiredTextBy('click');
      expect(viaEnter).toBe(viaClick);
      expect(viaEnter).toBe('请输入密码|请输入用户名');
    });

    it('表单带 noValidate:浏览器原生校验不会静默拦截回车提交', () => {
      renderLogin();
      const form = screen.getByLabelText('用户名').closest('form') as HTMLFormElement;
      // 一旦去掉 noValidate,将来表单里若出现 type="email" 之类的原生校验控件,
      // 浏览器会拦下回车提交 ⇒ AC1 静默失效。jsdom 不实现原生约束校验,
      // 这类回归没有任何运行时症状,只能靠这条断言守住。
      expect(form.noValidate).toBe(true);
    });

    // ==========================================================================
    // 工作项 #10018/#10019 · 步骤 10001 返工(CR 10005 执行评审 · R-11)结构性守护
    //
    // 背景:AC3「不影响现有『记住我』功能」在本仓是**空真** —— origin/main 的
    // platform/ainative/frontend/src/features/auth/ 目录下 rememberMe|记住我 仅命中
    // LoginPage.tsx:4 的一句注释(「记住我/忘记密码本产品无对应能力,该行位让给注册链接」),
    // 无勾选项、无 rememberMe 状态、无持久化逻辑。空真的依据只有那一句注释 ⇒
    // 一旦将来有人给本页加上「记住我」勾选,AC3 的语义就从「无需验证」悄然变成
    // 「必须验证不得破坏」,而当前没有任何用例盯住这个前提 ⇒ 回归不会被发现。
    //
    // 本用例让「前提变化本身」可被察觉。**变红时的正确处置**(不要直接删掉本用例):
    //   ① 更新验收记录里 AC3 的口径(「空真」改为「需真验」),并说明依据;
    //   ② 补 AC3 用例:勾选「记住我」后按回车仍能提交,且回车不得破坏记住我状态与持久化;
    //   ③ 再把本守护改写为「记住我勾选与回车提交互不干扰」的正向断言。
    // ==========================================================================
    it('结构性守护:登录表单内不存在「记住我」勾选项(AC3 空真的前提)', () => {
      renderLogin();
      const form = screen.getByLabelText('用户名').closest('form') as HTMLFormElement;
      // 「记住我」若被加进来,勾选框会落在表单内(原设计里那一行现在放的是「去注册」链接)。
      expect(form.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
      // 文案侧再兜一道:表单内不得出现「记住我」相关文案。
      expect(form.textContent ?? '').not.toMatch(/记住我|remember/i);
    });
  });
});
