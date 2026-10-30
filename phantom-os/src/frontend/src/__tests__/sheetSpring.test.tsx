/**
 * TG-look 5: одна пружина (layer, k 400 ζ 0.86) для всіх аркушів і вікон —
 * відкриття, закриття, жест униз. Жест відпускає ту саму пружину з
 * пальцевою швидкістю; CSS-крива й таймер виходу беруться з неї ж.
 * Відкриття розмови (T7) не чіпаємо.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { LAYER, springSettleMs } from '../feel/physics';
import { LAYER_EXIT_MS } from '../feel/motionKit';
import { FormSheet } from '../components/messenger/FormSheet';
import { MessageActionSheet } from '../components/messenger/feed/MessageActionSheet';
import type { Message } from '../types/messenger';

const read = (rel: string) => readFileSync(resolve(__dirname, '..', rel), 'utf8');
const form = read('styles/form.css');
const tokens = read('styles/phantom/tokens.css');
const SPRING = 'var(--ph-spring-layer-ms) var(--ph-spring-layer)';
const rule = (cls: string) => form.match(new RegExp(`\\n\\.${cls} \\{[^}]*\\}`))?.[0] ?? '';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('one layer spring', () => {
  it('JS settle time of LAYER equals the CSS --ph-spring-layer-ms the sheets play', () => {
    const css = Number(tokens.match(/--ph-spring-layer-ms: (\d+)ms/)?.[1]);
    expect(LAYER).toEqual({ k: 400, zeta: 0.86 });
    expect(springSettleMs(LAYER)).toBe(css);
    expect(LAYER_EXIT_MS).toBe(css);
  });

  it.each(['f-sheet-in', 'f-sheet-out', 'f-layer-scrim-in', 'f-layer-scrim-out', 'f-win-fwd', 'f-win-back', 'f-pop-in'])(
    '%s plays the layer spring',
    (cls) => {
      expect(rule(cls)).toContain(SPRING);
    },
  );

  it('closing sheet lets taps through to its scrim, and the scrim swallows them (T46)', () => {
    expect(rule('f-sheet-out')).toContain('pointer-events: none');
    expect(rule('f-layer-scrim-out')).not.toContain('pointer-events: none');
  });

  it('reduced motion: the new classes fall back to the 120 ms fade', () => {
    const block = form.slice(form.indexOf('@media (prefers-reduced-motion: reduce) {\n  .f-sheet-in'));
    expect(block).toMatch(/\.f-pop-in[^{]*\{ animation: f-fade-in 120ms linear both; \}/);
    expect(block).toMatch(/\.f-layer-scrim-in[^{]*\{ animation: f-fade-in 120ms linear both; \}/);
    expect(block).toMatch(/\.f-layer-scrim-out[^{]*\{ animation: f-fade-out 120ms linear both; \}/);
    expect(form).toMatch(/html\.reduce-motion \.f-pop-in/);
  });
});

describe('FormSheet close plays the whole spring', () => {
  it('stays on f-sheet-out until the spring settles; scrim fades on the same curve and still holds taps', () => {
    vi.useFakeTimers();
    const { rerender } = render(
      <FormSheet open onClose={() => {}} title="T" testId="s">
        <p>x</p>
      </FormSheet>,
    );
    const scrim = () => document.querySelector('[data-testid="s"] > [aria-hidden="true"]') as HTMLElement;
    expect(scrim().className).toContain('f-layer-scrim-in');
    rerender(
      <FormSheet open={false} onClose={() => {}} title="T" testId="s">
        <p>x</p>
      </FormSheet>,
    );
    expect(document.querySelector('[role="dialog"]')!.className).toContain('f-sheet-out');
    expect(scrim().className).toContain('f-layer-scrim-out');
    expect(scrim().style.pointerEvents).toBe('auto');
    act(() => {
      vi.advanceTimersByTime(LAYER_EXIT_MS - 40);
    });
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    act(() => {
      vi.advanceTimersByTime(60);
    });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });
});

describe('message menu window', () => {
  it('pops in on the layer spring from the bubble side', () => {
    const msg = { id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'hi', isSelf: true } as Message;
    const on = new Proxy({}, { get: () => () => {} });
    render(<MessageActionSheet msg={msg} currentUserId="me" speaking={false} onClose={() => {}} on={on as never} />);
    const stack = document.querySelector('[data-msg-stack]') as HTMLElement;
    expect(stack.className).toContain('f-pop-in');
    expect(stack.className).toContain('origin-bottom-right');
  });
});

describe('sheet removed by its parent still leaves on the spring', () => {
  const stubAnimate = () => {
    const calls: { el: Element; frames: Keyframe[]; opts: KeyframeAnimationOptions }[] = [];
    let finish!: () => void;
    const finished = new Promise<void>((r) => (finish = r));
    const proto = Element.prototype as unknown as { animate?: unknown };
    const had = 'animate' in proto;
    proto.animate = function (this: Element, frames: Keyframe[], opts: KeyframeAnimationOptions) {
      calls.push({ el: this, frames, opts });
      return { finished } as unknown as Animation;
    };
    return { calls, finish: () => finish(), restore: () => (had ? undefined : delete proto.animate) };
  };

  it('a conditionally mounted sheet slides out as a mute snapshot, then the snapshot goes', async () => {
    const a = stubAnimate();
    try {
      const { rerender } = render(<div>{<FormSheet open onClose={() => {}} title="T" testId="s"><button>go</button></FormSheet>}</div>);
      rerender(<div>{null}</div>);
      const ghost = document.querySelector('[data-exit-ghost]') as HTMLElement;
      expect(ghost).not.toBeNull();
      expect(ghost.getAttribute('aria-hidden')).toBe('true');
      expect(ghost.querySelector('[role="dialog"],[data-testid]')).toBeNull();
      expect(document.querySelector('[data-testid="s"]')).toBeNull();
      const slide = a.calls.find((c) => c.el.matches('[data-kit="sheet-frame"]'))!;
      expect(slide.frames[1]).toEqual({ transform: 'translateY(100%)' });
      expect(slide.opts.duration).toBe(LAYER_EXIT_MS);
      expect(a.calls.some((c) => c.el.classList.contains('f-scrim'))).toBe(true);
      a.finish();
      await act(async () => {});
      expect(document.querySelector('[data-exit-ghost]')).toBeNull();
    } finally {
      a.restore();
    }
  });

  it('no snapshot when the exit already played (open → false, or drag-dismiss)', () => {
    const a = stubAnimate();
    vi.useFakeTimers();
    try {
      const { rerender } = render(<FormSheet open onClose={() => {}} title="T"><p>x</p></FormSheet>);
      rerender(<FormSheet open={false} onClose={() => {}} title="T"><p>x</p></FormSheet>);
      act(() => {
        vi.advanceTimersByTime(LAYER_EXIT_MS + 10);
      });
      expect(document.querySelector('[role="dialog"]')).toBeNull();
      expect(document.querySelector('[data-exit-ghost]')).toBeNull();
      expect(a.calls).toHaveLength(0);
    } finally {
      a.restore();
    }
  });
});
