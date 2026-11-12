import '@testing-library/jest-dom';
import { Blob, File } from 'node:buffer';
import { createRequire } from 'node:module';
import { useId } from 'react';
import { getConfig } from '@testing-library/react';
import { configure } from '@testing-library/dom';
import { fetch, FormData, Headers, Request, Response } from 'undici';

// user-event resolves the top-level DOM package; RTL configures its nested copy.
// Flush individual user-event DOM events through RTL's public React wrappers.
// Keep top-level DOM's native async pass-through: RTL's waitFor asyncWrapper
// toggles its act environment and cannot wrap existing outer-act gestures.
const reactTestingConfig = getConfig();
configure({
  eventWrapper: reactTestingConfig.eventWrapper,
  unstable_advanceTimersWrapper: reactTestingConfig.unstable_advanceTimersWrapper,
});

// Ant Design loads rc-util through CommonJS, so an ESM vi.mock cannot intercept it.
// Its test-only fixed ID breaks aria-labelledby when multiple modals are mounted.
// Match the normal React 18 behavior: stable, unique IDs, with explicit IDs preserved.
const require = createRequire(import.meta.url);
require('rc-util/lib/hooks/useId').default = function useAccessibleId(id?: string) {
  const reactId = useId();
  return id || reactId;
};

Object.assign(globalThis, {
  Blob,
  File,
  fetch,
  FormData,
  Headers,
  Request,
  Response,
});

// Node ≥22 的内置 localStorage 在未给 --localstorage-file 时是一个「存在但
// 没有方法」的残缺对象,会骗过存在性检查,进而让 zustand persist 在
// rehydrate/clear 时抛 storage.setItem is not a function——所以这里探测的是
// 可用性(方法能走通),不可用就退回 Map 实现。
let localStorageUsable = false;
try {
  globalThis.localStorage?.setItem('__ainative_probe__', '1');
  globalThis.localStorage?.removeItem('__ainative_probe__');
  localStorageUsable = true;
} catch {
  // fall through to the Map-backed mock
}
if (!localStorageUsable) {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, String(value)),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      key: (index: number) => Array.from(store.keys())[index] ?? null,
      get length() {
        return store.size;
      },
    },
  });
}

const { server } = await import('./mocks/server');

// zustand persist 的 rehydrate 是异步的：等它完成后再开始跑用例，
// 避免迟到的 rehydrate 用空 localStorage 覆盖测试中设置的 auth 状态。
const { useAuthStore } = await import('@/shared/auth/store');
if ('persist' in useAuthStore && typeof useAuthStore.persist?.rehydrate === 'function') {
  await Promise.race([
    useAuthStore.persist.rehydrate(),
    new Promise((resolve) => setTimeout(resolve, 500)),
  ]);
  useAuthStore.getState().clear();
}

// Mock window.matchMedia for antd responsive components
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

const originalGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element, pseudoElt?: string | null) =>
  originalGetComputedStyle(element, pseudoElt ? undefined : pseudoElt);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
