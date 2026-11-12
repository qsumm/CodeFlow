import { afterEach, describe, expect, it, vi } from 'vitest';
import { readWorkitemViewPreference, writeWorkitemViewPreference } from './viewPreference';

describe('workitem view preference', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('defaults to kanban when nothing is stored', () => {
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('remembers the latest choice for both views', () => {
    writeWorkitemViewPreference('table');
    expect(window.localStorage.getItem('ainative.workitems.view')).toBe('table');
    expect(readWorkitemViewPreference()).toBe('table');

    writeWorkitemViewPreference('kanban');
    expect(window.localStorage.getItem('ainative.workitems.view')).toBe('kanban');
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('falls back to kanban for invalid stored values', () => {
    window.localStorage.setItem('ainative.workitems.view', 'gallery');
    expect(readWorkitemViewPreference()).toBe('kanban');
    window.localStorage.setItem('ainative.workitems.view', '');
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('tolerates unavailable storage on both read and write', () => {
    window.localStorage.setItem('ainative.workitems.view', 'table');
    const storage = window.localStorage;
    const getItem = vi.fn(() => { throw new Error('storage blocked'); });
    const setItem = vi.fn(() => { throw new Error('storage blocked'); });
    vi.stubGlobal('localStorage', { getItem, setItem });
    expect(readWorkitemViewPreference()).toBe('kanban');
    expect(getItem).toHaveBeenCalledWith('ainative.workitems.view');
    storage.removeItem('ainative.workitems.view');
    expect(() => writeWorkitemViewPreference('table')).not.toThrow();
    expect(setItem).toHaveBeenCalledWith('ainative.workitems.view', 'table');
    vi.unstubAllGlobals();
    expect(window.localStorage).toBe(storage);
    expect(window.localStorage.getItem('ainative.workitems.view')).toBeNull();
  });
});
