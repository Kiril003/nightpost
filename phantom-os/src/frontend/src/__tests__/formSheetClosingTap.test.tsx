import { describe, it, expect, vi } from 'vitest';
import { render, act } from '@testing-library/react';
import { FormSheet } from '../components/messenger/FormSheet';

describe('T46: sheet does not leak taps to the chat while it leaves', () => {
  it('scrim keeps pointer events during the exit animation', () => {
    vi.useFakeTimers();
    const { rerender } = render(<FormSheet open onClose={() => {}} title="x"><p>a</p></FormSheet>);
    rerender(<FormSheet open={false} onClose={() => {}} title="x"><p>a</p></FormSheet>);
    act(() => {});
    const scrim = document.body.querySelector('.f-scrim') as HTMLElement;
    expect(scrim.className).toContain('f-layer-scrim-out');
    expect(scrim.style.pointerEvents).toBe('auto');
    vi.useRealTimers();
  });
});
