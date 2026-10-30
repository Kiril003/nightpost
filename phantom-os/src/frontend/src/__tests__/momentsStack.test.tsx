import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MomentsStrip } from '../components/messenger/MomentsStrip';
import { useMoments, type Moment } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import type { Chat } from '../types/messenger';

const chat = (id: string, title: string, peerNodeId = id): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'all',
    peerNodeId,
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'привіт',
    lastAt: new Date().toISOString(),
  }) as unknown as Chat;

const moment = (id: string, fromId: string, extra: Partial<Moment> = {}): Moment =>
  ({
    id,
    fromId,
    fromName: fromId,
    mine: false,
    seen: false,
    caption: 'привіт',
    media: [],
    createdAt: Date.now() - 1_000,
    expiresAt: Date.now() + 3_600_000,
    audience: { kind: 'contacts' },
    keep: false,
    reactions: [],
    ...extra,
  }) as unknown as Moment;

const setMoments = (list: Moment[] | null) => {
  useMoments.setState({ list } as never);
};

beforeEach(() => {
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
  setMoments([]);
});

afterEach(() => {
  cleanup();
  setMoments(null);
  delete (window as unknown as { __phantomComposeMoment?: () => boolean }).__phantomComposeMoment;
});

describe('W14 Moments stack and band', () => {
  it('2 unseen → 2 circles of 32 with border-ink, no border-accent, no ring-', () => {
    const chats = [chat('c1', 'Оксана', 'p1'), chat('c2', 'Тарас', 'p2')];
    setMoments([moment('m1', 'p1', { seen: false }), moment('m2', 'p2', { seen: false })]);

    const { container } = render(
      <MomentsStrip variant="stack" chats={chats} onOpenChat={vi.fn()} />,
    );

    const stack = container.querySelector('[data-moments-stack]') as HTMLElement;
    expect(stack).toBeTruthy();
    const circles = container.querySelectorAll('[data-moment-circle]');
    expect(circles).toHaveLength(2);
    circles.forEach((el) => {
      expect(el.className).toContain('w-8');
      expect(el.className).toContain('h-8');
      expect(el.className).toContain('border-ink');
      expect(el.className).not.toContain('border-accent');
      expect(el.className).not.toMatch(/\bring-/);
    });
    expect(container.innerHTML).not.toContain('border-accent');
    expect(container.innerHTML).not.toMatch(/\bring-/);
  });

  it('seen circle in stack has no border-ink and no border-accent', () => {
    const chats = [chat('c1', 'Оксана', 'p1'), chat('c2', 'Тарас', 'p2')];
    setMoments([moment('m1', 'p1', { seen: false }), moment('m2', 'p2', { seen: true })]);

    const { container } = render(
      <MomentsStrip variant="stack" chats={chats} onOpenChat={vi.fn()} />,
    );

    const circles = container.querySelectorAll('[data-moment-circle]');
    expect(circles).toHaveLength(2);
    expect(circles[0].className).toContain('border-ink');
    expect(circles[1].className).not.toContain('border-ink');
    expect(circles[1].className).not.toContain('border-accent');
  });

  it('5 peers → capped at 3 circles in stack', () => {
    const chats = ['p1', 'p2', 'p3', 'p4', 'p5'].map((p, i) => chat(`c${i}`, `Людина ${i}`, p));
    setMoments(['p1', 'p2', 'p3', 'p4', 'p5'].map((p, i) => moment(`m${i}`, p)));

    const { container } = render(
      <MomentsStrip variant="stack" chats={chats} onOpenChat={vi.fn()} />,
    );

    expect(container.querySelectorAll('[data-moment-circle]')).toHaveLength(3);
  });

  it('none → no stack rendered', () => {
    setMoments([]);
    const { container } = render(
      <MomentsStrip variant="stack" chats={[chat('c1', 'Оксана', 'p1')]} onOpenChat={vi.fn()} />,
    );

    expect(container.querySelector('[data-moments-stack]')).toBeNull();
    expect(container.querySelector('[data-moment-circle]')).toBeNull();
  });

  it('stack tap → onStateChange("open")', () => {
    setMoments([moment('m1', 'p1')]);
    const onStateChange = vi.fn();
    const { container } = render(
      <MomentsStrip
        variant="stack"
        chats={[chat('c1', 'Оксана', 'p1')]}
        onOpenChat={vi.fn()}
        onStateChange={onStateChange}
      />,
    );

    const stack = container.querySelector('[data-moments-stack]') as HTMLElement;
    fireEvent.click(stack);
    expect(onStateChange).toHaveBeenCalledWith('open');
  });

  it('band: Add first calls the compose hook, then peers at 56 with name caption and no border-accent', () => {
    const chats = [chat('c1', 'Оксана', 'p1'), chat('c2', 'Тарас', 'p2')];
    setMoments([moment('m1', 'p1', { seen: false }), moment('m2', 'p2', { seen: true })]);
    const composeSpy = vi.fn(() => true);

    const { container } = render(
      <MomentsStrip variant="band" chats={chats} onOpenChat={vi.fn()} />,
    );

    const w = window as unknown as { __phantomComposeMoment?: () => boolean };
    expect(typeof w.__phantomComposeMoment).toBe('function');
    const origCompose = w.__phantomComposeMoment;
    w.__phantomComposeMoment = () => {
      composeSpy();
      return origCompose ? origCompose() : true;
    };

    const items = container.querySelectorAll('li');
    expect(items.length).toBe(4);
    const addBtn = items[0].querySelector('[data-moment-add]') as HTMLElement;
    expect(addBtn).toBeTruthy();

    const firstTile = items[1].querySelector('[data-moment-tile]') as HTMLElement;
    expect(firstTile).toBeTruthy();
    const ring = items[1].querySelector('[data-moment-ring]') as HTMLElement;
    expect(ring.className).toContain('w-[62px]');
    expect(ring.className).toContain('h-[62px]');
    expect(ring.className).toContain('border-ink');
    expect(ring.className).not.toContain('border-accent');
    expect(items[1].textContent).toContain('Оксана');
    expect(items[2].textContent).toContain('Тарас');

    fireEvent.click(addBtn);
    expect(composeSpy).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('band: the last tile opens the full Moments page', () => {
    setMoments([moment('m1', 'p1')]);
    const { container } = render(<MomentsStrip variant="band" chats={[chat('c1', 'Оксана', 'p1')]} onOpenChat={vi.fn()} />);
    expect(container.querySelector('[data-moments-open]')).toBeTruthy();
    fireEvent.click(container.querySelector('[data-moments-open]') as HTMLElement);
    expect(document.querySelector('[data-moments-page]')).toBeTruthy();
  });

  it('band without moments: Add only, no feed door', () => {
    setMoments([]);
    const { container } = render(<MomentsStrip variant="band" chats={[chat('c1', 'Оксана', 'p1')]} onOpenChat={vi.fn()} />);
    expect(container.querySelectorAll('li')).toHaveLength(0);
    expect(container.querySelectorAll('[data-moment-add]')).toHaveLength(1);
    expect(container.querySelector('[data-moments-open]')).toBeNull();
  });

});
