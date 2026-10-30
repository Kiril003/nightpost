/**
 * Список розмов «опустів» (власник, 25.09): простір заповнюють справжні
 * сигнали, а порожні записи контактів ідуть із «Розмов».
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const api = vi.hoisted(() => ({
  pulse: vi.fn(),
  peerProfiles: vi.fn(),
  focusPeerOnMap: vi.fn(),
}));
vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return { ...real, messengerApi: { ...real.messengerApi, ...api } };
});

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { toneIndex } from '../styles/formTokens';
import { useLiving } from '../services/livingPulse';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), peerNodeId: id, ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('chat_saved_notes', 'Збережене', { type: 'saved', pinned: true, lastSnippet: undefined, peerNodeId: undefined }),
  chat('phantom', 'Nightpost', { type: 'phantom', lastSnippet: undefined, peerNodeId: undefined }),
  chat('c1', 'Оксана'),
  chat('n1', 'Марко · dfff·524a', { lastSnippet: undefined, lastKind: undefined }),
  chat('n2', 'Марко · eb3b·67d3', { lastSnippet: undefined, lastKind: undefined, draft: 'ще пишу' }),
];

const renderList = () =>
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

beforeEach(() => {
  api.pulse.mockReset().mockRejectedValue(new Error('немає мосту'));
  api.peerProfiles.mockReset().mockRejectedValue(new Error('немає мосту'));
  api.focusPeerOnMap.mockReset().mockResolvedValue({ opened: true });
  useLiving.setState({ pulse: null, faces: {} });
});

describe('розмови без жодного листа', () => {
  it('контакт без листа не стоїть у «Розмовах»; закріплені й чернетка — стоять', () => {
    renderList();
    const ids = [...document.querySelectorAll('[data-chat-row]')].map((e) => e.getAttribute('data-chat-row'));
    expect(ids).toContain('chat_saved_notes');
    expect(ids).toContain('phantom');
    expect(ids).toContain('c1');
    expect(ids).toContain('n2');
    expect(ids).not.toContain('n1');
  });
});

describe('жива смуга', () => {
  it('немає сигналів — немає смуги', () => {
    renderList();
    expect(document.querySelector('[data-living-strip]')).toBeNull();
  });

  it('міст не відповів — мовчання, а не вчорашні люди', async () => {
    useLiving.setState({ pulse: { sky: null, near: [{ peerId: 'c1', name: 'Оксана', near: 'same', precision: 'quarter', atMs: 0 }], link: { online: true, direct: true } } });
    await useLiving.getState().refresh();
    expect(useLiving.getState().pulse).toBeNull();
  });

  it('зв\'язок і черга — не людська новина: без тривоги й людей смуги немає', () => {
    useLiving.setState({ pulse: { sky: null, near: [], link: { online: false, direct: false } } });
    renderList();
    expect(document.querySelector('[data-living-strip]')).toBeNull();
    expect(document.body.textContent).not.toMatch(/Без мережі|чекає|чекають|сховок/);
  });

  it('фото людини з профілю стоїть в аватарі рядка', () => {
    useLiving.setState({ faces: { c1: { avatar: 'data:image/webp;base64,AAAA', heardAtMs: null, lanSeenAtMs: null } } });
    renderList();
    const row = document.querySelector('[data-chat-row="c1"]') as HTMLElement;
    expect(row.querySelector('img')?.getAttribute('src')).toMatch(/^data:image\/webp/);
    expect(screen.queryByText('Тривога')).toBeNull();
  });
});

describe('назви рядків', () => {
  it('однакові імена — без відбитка в назві, тон аватара — з власного ключа кожного', () => {
    const twins = [
      chat('9b1041af0000', 'Марко · 9b10·41af'),
      chat('eb3b67d30000', 'Марко · eb3b·67d3'),
    ];
    render(
      <PhoneChatList chats={twins} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    const rows = [...document.querySelectorAll('[data-chat-row]')] as HTMLElement[];
    expect(rows).toHaveLength(2);
    for (const r of rows) {
      expect(r.textContent).toContain('Марко');
      expect(r.textContent).not.toMatch(/[0-9a-f]{4}·[0-9a-f]{4}/);
    }
    // Шість тонів (дизайн-система §2.1): двійники МОЖУТЬ збігтися тоном, тож
    // доводимо джерело — тон кожного рядка береться з його ключа, а не з імені.
    const tone = (r: HTMLElement) => (r.querySelector('[style]') as HTMLElement | null)?.getAttribute('style') ?? '';
    expect(tone(rows[0])).toContain(`var(--ph-person-${toneIndex('9b1041af0000')})`);
    expect(tone(rows[1])).toContain(`var(--ph-person-${toneIndex('eb3b67d30000')})`);
  });
});
