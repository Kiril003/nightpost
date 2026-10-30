import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WhereAskChips } from '../components/messenger/WhereAskChips';
import { whereApi, type WhereState } from '../services/peerWhere';
import type { Chat, Message } from '../types/messenger';

const NOW = Date.now();
const base: WhereState = { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null };

let state: WhereState = base;
const calls: Array<{ m: string; p: string; b: string | null }> = [];

let failPost = false;

function bridge(m: string, p: string, b: string | null): string {
  calls.push({ m, p, b });
  if (!p.startsWith('/messenger/where/')) return JSON.stringify({ ok: false, status: 404 });
  if (failPost && m === 'POST') return JSON.stringify({ ok: false, status: 503, code: 'DOWN', detail: 'x' });
  return JSON.stringify({ ok: true, status: 200, data: state });
}

const flush = () =>
  act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });

let seq = 0;
function makeChat(overrides: Partial<Chat> = {}, lastMsgOverrides: Partial<Message> = {}): Chat {
  seq += 1;
  const msg: Message = {
    id: `msg-${seq}`,
    senderId: 'oksana',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '12:00',
    sentAt: new Date(Date.now() - 60_000).toISOString(),
    type: 'text',
    text: 'Де ти?',
    isSelf: false,
    ...lastMsgOverrides,
  };
  return {
    id: `chat-${seq}`,
    title: 'Оксана',
    peerNodeId: 'oksana',
    avatar: '',
    type: 'direct',
    circle: 'friends',
    unreadCount: 1,
    messages: [msg],
    ...overrides,
  };
}

beforeEach(() => {
  state = base;
  failPost = false;
  calls.length = 0;
  sessionStorage.clear();
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: bridge };
});

afterEach(() => {
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('WhereAskChips', () => {
  it('показує 4 чипи у 1:1 розмові, коли останній вхідний лист — «Де ти?»', async () => {
    render(<WhereAskChips chat={makeChat()} />);
    await flush();
    const row = screen.getByTestId('where-ask-chips');
    expect(row.querySelectorAll('[data-kit="filter-chip"]')).toHaveLength(4);
    expect(screen.getByText('Район · 1 год')).toBeTruthy();
    expect(screen.getByText('Точно · 15 хв')).toBeTruthy();
    expect(screen.getByText('До зустрічі')).toBeTruthy();
    expect(screen.getByText('Не зараз')).toBeTruthy();
  });

  it('не показує нічого в груповій розмові', async () => {
    const { container } = render(<WhereAskChips chat={makeChat({ type: 'group' })} />);
    await flush();
    expect(container.innerHTML).toBe('');
  });

  it('не показує нічого, коли останній лист від мене', async () => {
    const { container } = render(<WhereAskChips chat={makeChat({}, { isSelf: true })} />);
    await flush();
    expect(container.innerHTML).toBe('');
  });

  it('натискання exact15 викликає whereApi.show(peer,"exact",15) і ховає рядок', async () => {
    const showSpy = vi.spyOn(whereApi, 'show');
    render(<WhereAskChips chat={makeChat()} />);
    await flush();

    fireEvent.click(screen.getByText('Точно · 15 хв'));
    await flush();

    expect(showSpy).toHaveBeenCalledWith('oksana', 'exact', 15);
    expect(screen.queryByTestId('where-ask-chips')).toBeNull();
  });

  it('натискання «Не зараз» ховає рядок без жодного виклику', async () => {
    const showSpy = vi.spyOn(whereApi, 'show');
    const meetSpy = vi.spyOn(whereApi, 'showMeet');
    render(<WhereAskChips chat={makeChat()} />);
    await flush();
    const beforeCalls = calls.length;

    fireEvent.click(screen.getByText('Не зараз'));
    await flush();

    expect(showSpy).not.toHaveBeenCalled();
    expect(meetSpy).not.toHaveBeenCalled();
    expect(calls.slice(beforeCalls)).toEqual([]);
    expect(screen.queryByTestId('where-ask-chips')).toBeNull();
  });

  it('exact15 шле в міст точність exact і 15 хв; «До зустрічі» — meet до 2 год', async () => {
    render(<WhereAskChips chat={makeChat()} />);
    await flush();
    fireEvent.click(screen.getByText('Точно · 15 хв'));
    await flush();
    const post = calls.find((c) => c.m === 'POST');
    expect(JSON.parse(post!.b!)).toEqual({ precision: 'exact', minutes: 15 });

    calls.length = 0;
    render(<WhereAskChips chat={makeChat()} />);
    await flush();
    fireEvent.click(screen.getByText('До зустрічі'));
    await flush();
    const meet = JSON.parse(calls.find((c) => c.m === 'POST')!.b!);
    expect(meet.meet).toBe(true);
    expect(meet.precision).toBe('exact');
    expect(meet.until_ms - Date.now()).toBeGreaterThan(2 * 3600_000 - 5_000);
  });

  it('збій моста лишає чипи й показує «Не вдалося»', async () => {
    failPost = true;
    render(<WhereAskChips chat={makeChat()} />);
    await flush();
    fireEvent.click(screen.getByText('Район · 1 год'));
    await flush();
    expect(screen.getByTestId('where-ask-failed')).toBeTruthy();
    expect(screen.getByTestId('where-ask-chips')).toBeTruthy();
  });

  it('питання старше 2 год або без часу відправлення — без чипів', async () => {
    const old = render(<WhereAskChips chat={makeChat({}, { sentAt: new Date(Date.now() - 3 * 3600_000).toISOString() })} />);
    await flush();
    expect(old.container.innerHTML).toBe('');
    old.unmount();
    const noTime = render(<WhereAskChips chat={makeChat({}, { sentAt: undefined })} />);
    await flush();
    expect(noTime.container.innerHTML).toBe('');
  });
});
