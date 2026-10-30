import { StrictMode, type ReactNode } from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows, reconcileRows, type FeedRow } from '../components/messenger/feed/feedModel';
import { sendStateOf } from '../components/messenger/feed/sendAck';
import { motionFor } from '../feel/motionTokens';
import type { Message } from '../types/messenger';

const ME = 'user-me';
const TODAY = new Date('2026-10-18T12:30:00Z');
const AWAIT = 'Чекає підтвердження';

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;

const message = (over: Partial<Message> = {}): Message => ({
  id: 'msg-1',
  senderId: ME,
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: '12:00',
  sentAt: '2026-10-18T12:00:00Z',
  type: 'text',
  text: 'Привіт',
  status: 'sending',
  ...over,
});

const rowOf = (msg: Message, prev: FeedRow[] | null = null): FeedRow[] =>
  reconcileRows(prev, buildFeedRows([msg], ME, TODAY));

function mount(first: Message, strict = false) {
  let rows = rowOf(first);
  const ui = (row: FeedRow): ReactNode => {
    const tree = (
      <FeedActionsContext.Provider value={actions}>
        <MessageRow
          row={row} chatId="c1" currentUserId={ME} currentUserName="Олена" groupish={false} direct
          phone={false} isSelectionMode={false} isSelected={false} isHighlighted={false}
          isSearchMatch={false} isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
        />
      </FeedActionsContext.Provider>
    );
    return strict ? <StrictMode>{tree}</StrictMode> : tree;
  };
  const view = render(ui(rows[0]));
  return {
    container: view.container,
    // Стор замінює лист новим обʼєктом — рядок перебудовується через reconcileRows.
    ack: (over: Partial<Message>) => {
      rows = rowOf({ ...rows[0].msg, ...over }, rows);
      view.rerender(ui(rows[0]));
    },
    bubble: () => view.container.querySelector<HTMLElement>('[data-send-state]'),
    fill: () => view.container.querySelector('[data-send-fill]'),
  };
}

function reducedMotion(reduced: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: reduced && query.includes('prefers-reduced-motion'),
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

function stubAnimate() {
  const runs: Array<{ finish: () => void; cancel: ReturnType<typeof vi.fn> }> = [];
  const spy = vi.fn(() => {
    let finish!: () => void;
    const finished = new Promise<void>((res) => {
      finish = res;
    });
    const cancel = vi.fn();
    runs.push({ finish, cancel });
    return { finished, cancel } as unknown as Animation;
  });
  HTMLElement.prototype.animate = spy as unknown as typeof HTMLElement.prototype.animate;
  return { spy, runs };
}

const settle = async (finish: () => void) => {
  await act(async () => {
    finish();
    await Promise.resolve();
    await Promise.resolve();
  });
};

describe('власний лист: контур до підтвердження, заливка на підтвердження', () => {
  const origMatchMedia = window.matchMedia;
  const origAnimate = HTMLElement.prototype.animate;

  beforeEach(() => {
    vi.useFakeTimers();
    reducedMotion(false);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    window.matchMedia = origMatchMedia;
    HTMLElement.prototype.animate = origAnimate;
  });

  it('1. sending → outline with the sr-only label', () => {
    const v = mount(message({ status: 'sending' }));
    expect(v.bubble()?.dataset.sendState).toBe('outline');
    expect(v.bubble()?.className).toContain('bg-transparent!');
    expect(v.bubble()?.className).toContain('border-line-strong!');
    expect(screen.getByText(AWAIT)).toBeInTheDocument();
  });

  it('2. store ack (sending → sent) flips to solid at once, no timers advanced', () => {
    reducedMotion(true);
    const v = mount(message({ status: 'sending' }));
    v.ack({ status: 'sent' });
    expect(v.bubble()?.dataset.sendState).toBe('solid');
    expect(screen.queryByText(AWAIT)).toBeNull();
  });

  it('3. time alone never flips the state', () => {
    const v = mount(message({ status: 'sending' }));
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(v.bubble()?.dataset.sendState).toBe('outline');
  });

  it.each([
    ['queued + stored', { status: 'queued', relayState: 'stored' }, 'solid', false],
    ['queued, not stored', { status: 'queued', relayState: 'local' }, 'outline', true],
    ['failed', { status: 'failed' }, 'outline', false],
    ['history without status', { status: undefined }, 'solid', false],
  ] as const)('4. %s', (_name, over, state, label) => {
    const msg = message(over as Partial<Message>);
    expect(sendStateOf(rowOf(msg)[0])).toBe(state);
    const v = mount(msg);
    expect(v.bubble()?.dataset.sendState).toBe(state);
    expect(screen.queryAllByText(AWAIT)).toHaveLength(label ? 1 : 0);
    if (over.status === 'queued' && 'relayState' in over && over.relayState === 'stored') {
      expect(v.container.querySelector('[data-msg-tick="stored"]')).not.toBeNull();
    }
  });

  it('5. reduced motion: no fill layer, no override right after the ack', () => {
    reducedMotion(true);
    const { spy } = stubAnimate();
    const v = mount(message({ status: 'sending' }));
    v.ack({ status: 'sent' });
    expect(v.fill()).toBeNull();
    expect(v.bubble()?.className).not.toContain('bg-transparent!');
    expect(spy).not.toHaveBeenCalled();
  });

  it('6. motion allowed: 420 ms clip-path fill, gone when the animation finishes', async () => {
    const { spy, runs } = stubAnimate();
    const v = mount(message({ status: 'sending' }));
    v.ack({ status: 'sent' });

    expect(v.fill()).not.toBeNull();
    expect(spy).toHaveBeenCalledTimes(1);
    const [frames, opts] = spy.mock.calls[0] as unknown as [Keyframe[], KeyframeAnimationOptions];
    expect(frames).toEqual([{ clipPath: 'inset(0 100% 0 0)' }, { clipPath: 'inset(0 0 0 0)' }]);
    expect(opts.duration).toBe(420);
    expect(String(opts.easing).startsWith('linear(')).toBe(true);

    await settle(runs[0].finish);
    expect(v.fill()).toBeNull();
    expect(v.bubble()?.className).not.toContain('bg-transparent!');
  });

  it('6b. StrictMode re-run still clears the fill', async () => {
    const { runs } = stubAnimate();
    const v = mount(message({ status: 'sending' }), true);
    v.ack({ status: 'sent' });
    expect(v.fill()).not.toBeNull();
    await settle(runs[runs.length - 1].finish);
    expect(v.fill()).toBeNull();
  });

  it('6c. no animate on the fill layer: swap is instant, never stuck outlined', () => {
    stubAnimate();
    const v = mount(message({ status: 'sending' }));
    HTMLElement.prototype.animate = undefined as unknown as typeof HTMLElement.prototype.animate;
    v.ack({ status: 'sent' });
    expect(v.fill()).toBeNull();
    expect(v.bubble()?.className).not.toContain('bg-transparent!');
  });

  it('6d. back to outline mid-fill cancels, the next ack fills again', () => {
    const { spy, runs } = stubAnimate();
    const v = mount(message({ status: 'sending' }));
    v.ack({ status: 'sent' });
    v.ack({ status: 'failed' });
    expect(v.fill()).toBeNull();
    expect(runs[0].cancel).toHaveBeenCalled();
    v.ack({ status: 'sent' });
    expect(v.fill()).not.toBeNull();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('7. mounting an already sent message never animates', () => {
    const { spy } = stubAnimate();
    const v = mount(message({ status: 'sent' }));
    expect(v.bubble()?.dataset.sendState).toBe('solid');
    expect(v.fill()).toBeNull();
    expect(spy).not.toHaveBeenCalled();
  });

  it("8. another person's message has no send state", () => {
    const msg = message({ senderId: 'user-peer', senderName: 'Тарас', status: 'sent' });
    expect(sendStateOf(rowOf(msg)[0])).toBeNull();
    const v = mount(msg);
    expect(v.bubble()).toBeNull();
  });

  it('9. motionFor fill: quiet opacity when reduced, 420 ms clip-path otherwise', () => {
    expect(motionFor('fill', { reduced: true })).toMatchObject({ end: true, prop: 'opacity' });
    expect(motionFor('fill', { reduced: false })).toMatchObject({ end: false, ms: 420, prop: 'clip-path' });
  });
});
