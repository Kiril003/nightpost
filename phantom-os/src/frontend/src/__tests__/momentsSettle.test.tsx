import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Chat } from '../types/messenger';
import { useMoments, type Moment } from '../services/moments';
import { BAND_MIN_HEIGHT_PX, MomentsStrip } from '../components/messenger/MomentsStrip';

vi.mock('../services/moments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/moments')>();
  return {
    ...actual,
    watchMoments: vi.fn(() => () => {}),
  };
});

const CHATS: Chat[] = [
  {
    id: 'peer-1',
    peerId: 'peer-1',
    participantId: 'peer-1',
    name: 'Олена',
    type: 'direct',
    isGroup: false,
    circle: 'close',
    unreadCount: 0,
  } as unknown as Chat,
];

function makeMoment(overrides: Partial<Moment> = {}): Moment {
  const now = Date.now();
  return {
    id: 'm-1',
    fromId: 'peer-1',
    fromName: 'Олена',
    mine: false,
    seen: false,
    text: 'Тихий вечір',
    media: [],
    atMs: now - 1_000,
    editedMs: now - 1_000,
    untilMs: now + 86_400_000,
    keep: false,
    reach: 'contacts' as Moment['reach'],
    circles: [],
    toCount: 1,
    viewers: [],
    reactions: {},
    myReaction: '',
    ...overrides,
  };
}

const originalMatchMedia = window.matchMedia;
const originalAnimate = HTMLElement.prototype.animate;

function setReducedMotion(reduced: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: reduced && query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

describe('MomentsStrip settle and height reservation', () => {
  let animateSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setReducedMotion(false);
    animateSpy = vi.fn(() => ({ cancel: vi.fn() } as unknown as Animation));
    Object.defineProperty(HTMLElement.prototype, 'animate', {
      writable: true,
      configurable: true,
      value: animateSpy,
    });
    useMoments.setState({ list: null });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      configurable: true,
      value: originalMatchMedia,
    });
    Object.defineProperty(HTMLElement.prototype, 'animate', {
      writable: true,
      configurable: true,
      value: originalAnimate,
    });
  });

  it('1. Band, loading and loaded: reserves the same derived min-height on both states', () => {
    expect(BAND_MIN_HEIGHT_PX).toBe(12 + 62 + 6 + 16 + 4);
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);

    const loadingBand = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(loadingBand).not.toBeNull();
    expect(loadingBand?.getAttribute('data-moments-state')).toBe('loading');
    expect(loadingBand?.getAttribute('aria-busy')).toBe('true');
    expect(loadingBand?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(loadingBand?.querySelector('li')).toBeNull();
    expect(loadingBand?.querySelector('[data-moment-add]')).toBeNull();
    expect(loadingBand?.querySelector('[data-moment-tile]')).toBeNull();
    const loadingMinHeight = loadingBand?.style.minHeight;

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const readyBand = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(readyBand?.getAttribute('data-moments-state')).toBe('ready');
    expect(readyBand?.style.minHeight).toBe(loadingMinHeight);
    expect(readyBand?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
  });

  it('2. Band, loading -> loaded: marks ready, settles the list, and starts the rise animation', () => {
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    expect(animateSpy).not.toHaveBeenCalled();

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const readyBand = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(readyBand?.getAttribute('data-moments-state')).toBe('ready');
    expect(readyBand?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(readyBand?.querySelector('[data-moment-tile]')).not.toBeNull();
    expect(readyBand?.hasAttribute('data-moments-settle')).toBe(true);
    expect(readyBand?.className.split(/\s+/)).not.toContain('contents');
    expect(animateSpy).toHaveBeenCalledTimes(1);
    expect(animateSpy.mock.contexts[0]).toBe(readyBand);
    expect(animateSpy).toHaveBeenCalledWith(
      [
        { opacity: 0, transform: 'translate3d(0, 4px, 0)' },
        { opacity: 1, transform: 'translate3d(0, 0, 0)' },
      ],
      { duration: 200, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
    );
  });

  it('3. Band, settle runs once: clears data-moments-settle on next list change and after 200ms', () => {
    vi.useFakeTimers();
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const listEl = container.querySelector('[data-moments-band]');
    expect(listEl?.hasAttribute('data-moments-settle')).toBe(true);
    expect(animateSpy).toHaveBeenCalledTimes(1);

    act(() => {
      useMoments.setState({ list: [makeMoment(), makeMoment({ id: 'm-2', text: 'Другий' })] });
    });

    expect(listEl?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).toHaveBeenCalledTimes(1);

    cleanup();
    animateSpy.mockClear();
    useMoments.setState({ list: null });

    const second = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });
    const secondList = second.container.querySelector('[data-moments-band]');
    expect(secondList?.hasAttribute('data-moments-settle')).toBe(true);

    act(() => {
      vi.advanceTimersByTime(200);
    });

    expect(secondList?.hasAttribute('data-moments-settle')).toBe(false);
  });

  it('4. Band, reduced motion: keeps reserved height and renders tile without settle or animation', () => {
    setReducedMotion(true);
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    const loadingBand = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(loadingBand?.getAttribute('data-moments-state')).toBe('loading');
    expect(loadingBand?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const readyBand = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(readyBand?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(readyBand?.getAttribute('data-moments-state')).toBe('ready');
    expect(readyBand?.querySelector('[data-moment-tile]')).not.toBeNull();
    expect(readyBand?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).not.toHaveBeenCalled();
  });

  it('5. Band, loaded with an empty list from the start: ready with reserve and no settle', () => {
    useMoments.setState({ list: [] });
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);

    const band = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(band).not.toBeNull();
    expect(band?.getAttribute('data-moments-state')).toBe('ready');
    expect(band?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(band?.querySelector('[data-moment-add]')).not.toBeNull();
    expect(band?.querySelector('[data-moment-tile]')).toBeNull();
    expect(band?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).not.toHaveBeenCalled();
  });

  it('5b. Band, loading -> empty list: the add pill keeps the reserved height and settles on its own box', () => {
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    act(() => {
      useMoments.setState({ list: [] });
    });
    const band = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(band?.hasAttribute('data-moments-pill')).toBe(true);
    expect(band?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(band?.hasAttribute('data-moments-settle')).toBe(true);
    expect(animateSpy.mock.contexts[0]).toBe(band);
  });

  it('5c. Band, bridge refuses moments: no reserved band at all', () => {
    useMoments.setState({ list: null, unavailable: true });
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    expect(container.querySelector('[data-moments-band]')).toBeNull();
    useMoments.setState({ unavailable: false });
  });

  it('6. Band, cached mount: renders tile immediately without settle attribute or animation', () => {
    useMoments.setState({ list: [makeMoment()] });
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);

    const band = container.querySelector<HTMLElement>('[data-moments-band]');
    expect(band?.getAttribute('data-moments-state')).toBe('ready');
    expect(band?.style.minHeight).toBe(`${BAND_MIN_HEIGHT_PX}px`);
    expect(band?.querySelector('[data-moment-tile]')).not.toBeNull();
    expect(band?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).not.toHaveBeenCalled();
  });

  it('7. Band, loading: registers window.__phantomComposeMoment and opens composer before list arrives', () => {
    render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="band" />);
    const w = window as unknown as { __phantomComposeMoment?: () => boolean };
    expect(typeof w.__phantomComposeMoment).toBe('function');

    let opened = false;
    act(() => {
      opened = Boolean(w.__phantomComposeMoment?.());
    });

    expect(opened).toBe(true);
    expect(document.body.querySelector('[data-moment-composer]')).not.toBeNull();
  });

  it('8. Stack, loading: renders nothing while list is null', () => {
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="stack" />);

    expect(container.firstChild).toBeNull();
    expect(container.querySelector('[data-moments-stack]')).toBeNull();
    expect(container.querySelector('[data-moments-band]')).toBeNull();
  });

  it('9. Stack, loading -> loaded with one group: renders stack with settle, animates once, and clears on next list change', () => {
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="stack" />);
    expect(container.querySelector('[data-moments-stack]')).toBeNull();

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const stack = container.querySelector('[data-moments-stack]');
    expect(stack).not.toBeNull();
    expect(stack?.hasAttribute('data-moments-settle')).toBe(true);
    expect(stack?.querySelectorAll('[data-moment-circle]').length).toBe(1);
    expect(animateSpy).toHaveBeenCalledTimes(1);

    act(() => {
      useMoments.setState({ list: [makeMoment(), makeMoment({ id: 'm-2' })] });
    });

    expect(stack?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).toHaveBeenCalledTimes(1);
  });

  it('10. Stack, loading -> loaded with no groups: stays hidden with no reserve element', () => {
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="stack" />);

    act(() => {
      useMoments.setState({ list: [] });
    });

    expect(container.querySelector('[data-moments-stack]')).toBeNull();
    expect(container.querySelector('[data-moments-band]')).toBeNull();
    expect(container.firstChild).toBeNull();
  });

  it('11. Stack, reduced motion: renders stack on arrival without settle attribute or animation', () => {
    setReducedMotion(true);
    const { container } = render(<MomentsStrip chats={CHATS} onOpenChat={vi.fn()} variant="stack" />);

    act(() => {
      useMoments.setState({ list: [makeMoment()] });
    });

    const stack = container.querySelector('[data-moments-stack]');
    expect(stack).not.toBeNull();
    expect(stack?.hasAttribute('data-moments-settle')).toBe(false);
    expect(animateSpy).not.toHaveBeenCalled();
  });
});
