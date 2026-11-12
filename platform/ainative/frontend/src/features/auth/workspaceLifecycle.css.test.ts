import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// F7's four states and its visible keyboard focus ring exist only in this stylesheet, and vitest
// stubs CSS imports — the plain, `?raw` and `?inline` forms all resolve to an empty string — so
// the rules cannot be observed through an import or through computed styles. The file is read
// straight off disk instead: a component test would still pass if these rules were deleted, which
// is exactly the regression F7 is written against.
// The path is joined with dirname/resolve rather than `new URL('./workspaceLifecycle.css',
// import.meta.url)`: Vite rewrites that pattern into a served http asset URL, which fileURLToPath
// then rejects.
const css = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), 'workspaceLifecycle.css'),
  'utf8',
);

/**
 * The declaration block of one selector. Matching on the selector alone would pass for an
 * empty rule, so the block is what gets asserted. `${selector} {` also disambiguates the
 * base rule from its own pseudo-class variants.
 */
function rule(selector: string): string {
  const start = css.indexOf(`${selector} {`);
  expect(start, `no rule for ${selector}`).toBeGreaterThanOrEqual(0);
  const end = css.indexOf('}', start);
  expect(end, `unterminated rule for ${selector}`).toBeGreaterThan(start);
  return css.slice(start + selector.length + 2, end).replace(/\s+/g, ' ').trim();
}

function expectInteractiveStates(selector: string) {
  for (const state of [':hover', ':active', ':focus-visible']) {
    expect(rule(`${selector}${state}`), `${selector}${state} is empty`).not.toBe('');
  }
}

describe('workspaceLifecycle.css', () => {
  it('styles the apply button with the shared appearance tokens', () => {
    const base = rule('.cf-apply-access-button');
    expect(base).toContain('border: 1px solid var(--cf-accent-text)');
    expect(base).toContain('background: var(--cf-panel)');
    expect(base).toContain('color: var(--cf-accent-text)');
    expect(base).not.toContain('#ff6a00');
    expect(base).toContain('cursor: pointer');
    expect(base).not.toContain('cursor: not-allowed');
    expect(base).toContain('font-weight: 700');
  });

  it('gives the apply button distinct hover, active and visible-focus states', () => {
    expectInteractiveStates('.cf-apply-access-button');
    expect(rule('.cf-apply-access-button:hover')).toContain('background: var(--cf-button-hover)');
    expect(rule('.cf-apply-access-button:active')).toContain('background: var(--cf-button-active)');
    // F7.3: an outline is the only focus affordance that survives Windows high-contrast
    // mode, and the offset keeps it off the border so the two do not merge into one line.
    const focus = rule('.cf-apply-access-button:focus-visible');
    expect(focus).toContain('outline: 2px solid var(--cf-accent-text)');
    expect(focus).toContain('outline-offset: 2px');
  });

  it('keeps every hover and active background distinct from the default', () => {
    const base = rule('.cf-apply-access-button');
    const hover = rule('.cf-apply-access-button:hover');
    const active = rule('.cf-apply-access-button:active');
    const backgrounds = [base, hover, active].map((block) => /background:\s*([^;]+)/.exec(block)?.[1]);
    // Three identical values would mean the states are declared but visually inert, which
    // is exactly the failure F7 is written against.
    expect(new Set(backgrounds).size).toBe(3);
    expect(backgrounds).not.toContain(undefined);
  });

  it('gives the card management controls the same focus affordance as the apply button', () => {
    // F1.2: these sit beside the enter control, so they cannot inherit the card's border
    // highlight as their focus signal — they need a ring of their own.
    expectInteractiveStates('.cf-card-manage-button');
    expect(rule('.cf-card-manage-button:focus-visible')).toContain('outline: 2px solid var(--cf-accent-text)');
    const danger = rule('.cf-card-manage-button--danger:hover');
    expect(danger).toContain('border-color: var(--cf-error)');
    expect(danger).toContain('color: var(--cf-error)');
    expect(rule('.cf-card-manage-button--danger:focus-visible')).toContain('outline-color: var(--cf-error)');
  });

  it('gives the recycle bin entry the same focus affordance', () => {
    // F4.1: the entry is a real control, so it has to be reachable and visibly focusable.
    expectInteractiveStates('.cf-recycle-bin-entry');
    expect(rule('.cf-recycle-bin-entry:focus-visible')).toContain('outline: 2px solid var(--cf-accent-text)');
    expect(rule('.cf-recycle-bin-entry')).toContain('color: var(--cf-accent-text)');
  });
});
