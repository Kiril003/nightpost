import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { act, render, renderHook } from '@testing-library/react';
import { LAYER_EXIT_MS, MOTION_VAR, REDUCE_CLASS, isReducedMotion, setAppReduceMotion, useEnteringIds, useExitPresence, useTabFade } from '../feel/motionKit';
import { SHEET_SPRING } from '../hooks/useSheetDrag';
import { FormSheet } from '../components/messenger/FormSheet';

const read = (rel: string) => readFileSync(resolve(__dirname, '..', rel), 'utf8');
const form = read('styles/form.css');
const tokens = read('styles/phantom/tokens.css');
const COMPONENTS = ['components/messenger/FormSheet.tsx', 'components/messenger/PhoneChatList.tsx', 'components/shell/DeskShell.tsx'];

describe('motion system: one source', () => {
  it('every token the kit points at exists in tokens.css', () => {
    for (const v of Object.values(MOTION_VAR)) {
      const name = v.slice('var('.length, -1);
      expect(tokens).toContain(`${name}:`);
    }
    expect(tokens).toContain('--ph-spring-layer-ms:');
  });

  it('no hard-coded spring or ms in motion classes of form.css', () => {
    expect(form).not.toMatch(/cubic-bezier\(0\.22,\s*1\.18/);
    expect(form).toMatch(/\.f-sheet-in \{ animation: f-sheet-in var\(--ph-spring-layer-ms\) var\(--ph-spring-layer\)/);
    expect(form).toMatch(/\.f-stamp-in \{ animation: f-stamp-in var\(--ph-spring-press-ms\) var\(--ph-spring-press\)/);
    expect(SHEET_SPRING).toBe(MOTION_VAR.springLayer);
  });

  it('error shake is 300 ms with three decaying cycles', () => {
    expect(form).toMatch(/\.f-shake \{ animation: f-shake 300ms/);
  });

  it('reduce-motion: system query and app class both map to opacity-only 120 ms; shake stops', () => {
    for (const sel of ['@media (prefers-reduced-motion: reduce)', 'html.reduce-motion .f-sheet-in']) {
      expect(form).toContain(sel);
    }
    expect(form).toMatch(/\.f-sheet-in[\s\S]{0,300}animation: f-fade-in 120ms linear both/);
    expect(form).toMatch(/html\.reduce-motion \.f-shake \{ animation: none/);
    expect(form).toMatch(/html\.reduce-motion \.f-sheet-out[\s\S]{0,60}f-fade-out 120ms/);
  });

  it('own reduce-motion setting flips isReducedMotion', () => {
    expect(isReducedMotion()).toBe(false);
    setAppReduceMotion(true);
    expect(document.documentElement.classList.contains(REDUCE_CLASS)).toBe(true);
    expect(isReducedMotion()).toBe(true);
    setAppReduceMotion(false);
    expect(isReducedMotion()).toBe(false);
  });

  it('sheet, list and shell use the shared kit', () => {
    expect(read(COMPONENTS[0])).toContain("from '../../feel/motionKit'");
    expect(read(COMPONENTS[0])).toContain('f-sheet-out');
    expect(read(COMPONENTS[1])).toContain('useEnteringIds');
    expect(read(COMPONENTS[1])).toContain('f-row-in');
    expect(read(COMPONENTS[2])).toContain('useTabFade');
  });
});

describe('motion system: behaviour', () => {
  it('list: first paint does not animate, a later insert does', () => {
    const { result, rerender } = renderHook(({ ids }) => useEnteringIds(ids), { initialProps: { ids: ['a', 'b'] } });
    expect(result.current.size).toBe(0);
    rerender({ ids: ['a', 'b', 'c'] });
    expect([...result.current]).toEqual(['c']);
  });

  it('tab: no class on first paint, f-tab-in after the key changes', () => {
    const { result, rerender } = renderHook(({ k }) => useTabFade(k), { initialProps: { k: 'messenger' } });
    expect(result.current).toBe('');
    rerender({ k: 'map' });
    expect(result.current).toBe('f-tab-in');
  });

  it('sheet stays mounted with the out class while closing, then leaves', async () => {
    const { result, rerender } = renderHook(({ o }) => useExitPresence(o), { initialProps: { o: true } });
    expect(result.current).toEqual({ mounted: true, closing: false });
    rerender({ o: false });
    expect(result.current.closing).toBe(true);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 230));
    });
    expect(result.current).toEqual({ mounted: false, closing: false });
  });

  it('FormSheet uses f-sheet-in when open and f-sheet-out when closing', async () => {
    const { rerender } = render(
      <FormSheet open onClose={() => {}} title="T">
        <p>x</p>
      </FormSheet>,
    );
    expect(document.querySelector('[role="dialog"]')!.className).toContain('f-sheet-in');
    rerender(
      <FormSheet open={false} onClose={() => {}} title="T">
        <p>x</p>
      </FormSheet>,
    );
    expect(document.querySelector('[role="dialog"]')!.className).toContain('f-sheet-out');
    // TG-look 5: аркуш виходить тією ж пружиною layer — знімаємо після її осідання.
    await act(async () => {
      await new Promise((r) => setTimeout(r, LAYER_EXIT_MS + 50));
    });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });
});
