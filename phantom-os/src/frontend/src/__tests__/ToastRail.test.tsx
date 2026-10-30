import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, act } from '@testing-library/react';
import { ToastRail } from '../components/core/ToastRail';
import { useUIStore } from '../stores/uiStore';

describe('ToastRail', () => {
  beforeEach(() => {
    useUIStore.setState({ toasts: [] });
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders nothing when there are no toasts', () => {
    const { container } = render(<ToastRail />);
    // The shell <div> remains, but no <button role="alert">.
    expect(container.querySelector('button[role="alert"]')).toBeNull();
  });

  it('renders a toast added via store.toast()', () => {
    const { container } = render(<ToastRail />);
    act(() => {
      useUIStore.getState().toast({ kind: 'error', message: 'boom' });
    });
    const btn = container.querySelector('button[role="alert"]');
    expect(btn).not.toBeNull();
    expect(btn!.textContent).toContain('boom');
  });

  it('auto-dismisses after 4s (§7.10), not before', () => {
    const { container } = render(<ToastRail />);
    act(() => {
      useUIStore.getState().toast({ kind: 'info', message: 'gone soon' });
    });
    expect(container.querySelector('button[role="status"]')).not.toBeNull();
    act(() => {
      vi.advanceTimersByTime(3001);
    });
    expect(useUIStore.getState().toasts).toHaveLength(1);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });

  it('one at a time: the newest replaces the older, and the older still expires', () => {
    const { container } = render(<ToastRail />);
    act(() => {
      useUIStore.getState().toast({ kind: 'info', message: 'first' });
      useUIStore.getState().toast({ kind: 'warn', message: 'second' });
    });
    const shown = container.querySelectorAll('button[role="status"], button[role="alert"]');
    expect(shown).toHaveLength(1);
    expect(shown[0].textContent).toContain('second');
    act(() => {
      vi.advanceTimersByTime(4001);
    });
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });

  it('looks like the system: raised card, no colour wash, no mono', () => {
    const { container } = render(<ToastRail />);
    act(() => {
      useUIStore.getState().toast({ kind: 'warn', message: 'x' });
    });
    const el = container.querySelector('button[role="status"]') as HTMLElement;
    expect(el.className).toContain('bg-raised');
    expect(el.className).toContain('rounded-card');
    expect(el.className).not.toMatch(/font-mono|rounded-full/);
    expect(el.getAttribute('style') ?? '').not.toMatch(/background/);
  });

  it('action toast: the action runs once, closes the toast and lives 6 s', () => {
    const run = vi.fn();
    const { container } = render(<ToastRail />);
    act(() => {
      useUIStore.getState().toast({ kind: 'info', message: 'archived', action: { label: 'Undo', run } });
    });
    const status = container.querySelector('[role="status"]');
    expect(status?.textContent).toContain('archived');
    act(() => {
      vi.advanceTimersByTime(4500);
    });
    const btn = container.querySelector<HTMLButtonElement>('[data-testid="toast-action"]');
    expect(btn?.textContent).toBe('Undo');
    act(() => {
      btn!.click();
    });
    expect(run).toHaveBeenCalledTimes(1);
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });
});
