/**
 * Телефон відкривається списком розмов (збірка 17, рішення власника 05.10):
 * продукт — «месенджер, мапа й гаманець», звʼязок першим. Домівку помічника
 * (PhantomHome, 02.10) знято: питання й голос — в одній капсулі «Пошук або
 * спитати Nightpost» над списком (збірка 18),
 * Nightpost — закріплена розмова, а картки домівки стали тонкою смугою, яка
 * зʼявляється ЛИШЕ з даними.
 *
 * Монтується те саме, що монтує застосунок — `MessengerRoot` з мостом
 * телефона, — тож доведено досяжність із реального входу.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { useLiving, readPulse } from '../services/livingPulse';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { linkState } from '../services/phantomHome';
import { chatMarkUnread } from '../services/chatMarkUnread';

const SETUP = { sms_build: true, permitted: true, gateway_number: '+380000000000', pc_paired: true };

describe('стан звʼязку словами — лише з даних', () => {
  it('звичайна мережа — «на звʼязку»; невідома — мовчання', () => {
    expect(linkState(null, true, null, 0).kind).toBe('online');
    expect(linkState(null, null, null, 0)).toEqual({ kind: 'unknown', word: '', works: [] });
  });

  it('без інтернету — каже, що працює, і лише те, що справді є', () => {
    const offline = readPulse({ link: { online: false, direct: true } } as never);
    expect(linkState(offline, false, SETUP, 3).works).toEqual([
      '3\u00A0листи чекають у черзі й підуть самі, щойно буде звʼязок',
      'Є прямий звʼязок із людьми поруч — листи йдуть без інтернету',
      'Питання до Nightpost їдуть SMS на ваш домашній шлюз',
    ]);
    const bare = linkState(readPulse({ link: { online: false, direct: false } } as never), false, { ...SETUP, permitted: false, pc_paired: false }, 0);
    expect(bare.works).toEqual(['Нові листи стануть у чергу й підуть самі, щойно буде звʼязок']);
  });
});

// ── Досяжність: справжній корінь месенджера з мостом телефона ─────────────

const conv = (id: string, title: string, kind: string, extra: Partial<NodeConversation> = {}): NodeConversation =>
  ({
    id, title, kind, circle: 'all', handle: null, avatar: null, pinned: kind === 'phantom', archived: false,
    created_at: '2026-10-01T10:00:00', updated_at: '2026-10-02T11:00:00', ...extra,
  }) as NodeConversation;

const CONVS = [
  conv('chat_phantom', 'Nightpost', 'phantom', { last_snippet: 'Укриття за 300 м, вхід з двору.' }),
  conv('conv_marta', 'Марта', 'dm', { unread_count: 2, last_snippet: 'Ти вдома?', last_at: '2026-10-02T11:40:00', peer_node_id: 'marta-node' }),
];

let pulseBody: unknown = { sky: null, near: [], link: { online: true, direct: false } };
let queued = 0;
const navigate = vi.fn((_to: string, _args?: string) => true);
const bridge = {
  call: (_m: string, path: string) => {
    if (path.startsWith('/messenger/pulse/where/')) return JSON.stringify({ ok: true, status: 200, data: { opened: true } });
    if (path === '/messenger/pulse') return JSON.stringify({ ok: true, status: 200, data: pulseBody });
    if (path === '/messenger/queue/status') return JSON.stringify({ ok: true, status: 200, data: { queued } });
    if (path === '/messenger/intents/setup') return JSON.stringify({ ok: true, status: 200, data: SETUP });
    return JSON.stringify({ ok: true, status: 200, data: {} });
  },
  navigate,
};

const mount = async (rows: NodeConversation[] = CONVS) => {
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue(rows);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  render(
    <MemoryRouter>
      <MessengerRoot />
    </MemoryRouter>,
  );
  return screen.findByTestId('chat-list');
};

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = bridge;
  useLiving.setState({ pulse: null, faces: {} });
  chatMarkUnread.reset();
  navigate.mockClear();
  pulseBody = { sky: null, near: [], link: { online: true, direct: false } };
  queued = 0;
});

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('телефон відкривається списком розмов', () => {
  it('перший кадр — список; домівки помічника немає; Nightpost — закріплена розмова в списку', async () => {
    const list = await mount();
    expect(screen.queryByTestId('phantom-home')).toBeNull();
    expect(screen.queryByTestId('list-to-home')).toBeNull();
    await waitFor(() => expect(list.textContent).toContain('Марта'));
    expect(list.textContent).toContain('Nightpost');
    // Питання й пошук — одна капсула в шапці, окремого поля питання немає.
    expect(screen.queryByTestId('ask-nightpost')).toBeNull();
    expect(document.querySelectorAll('[data-search-pill]').length).toBe(1);
    expect(screen.getByLabelText('Пошук або спитати Nightpost')).toBeTruthy();
  });

  it('«Спитати Nightpost» з пошуку — питання йде в справжню закріплену розмову, своєї відповіді капсула не малює', async () => {
    await mount();
    const send = vi.fn();
    useMessengerStore.setState({ sendMessage: send } as never);
    await waitFor(() => expect(useMessengerStore.getState().chats.some((c) => c.id === 'chat_phantom')).toBe(true));
    fireEvent.click(document.querySelector('[data-search-pill]') as HTMLElement);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Де найближче укриття?' } });
    fireEvent.click(screen.getByTestId('search-ask'));
    expect(useMessengerStore.getState().activeChatId).toBe('chat_phantom');
    expect(send).toHaveBeenCalledWith('Де найближче укриття?');
  });

  it('голос — жива розмова з Nightpost через рідну оболонку', async () => {
    await mount();
    fireEvent.click(document.querySelector('[data-search-pill]')!);
    fireEvent.click(screen.getByTestId('search-ask-voice'));
    expect(navigate).toHaveBeenCalledWith('call-phantom', '{}');
  });

  it('BACK: з кореня списку — рідній оболонці (застосунок у фон), з розмови — назад у список', async () => {
    await mount();
    const back = () => (window as unknown as { __phantomBack?: () => boolean }).__phantomBack?.() ?? false;
    expect(back()).toBe(false);
    act(() => useMessengerStore.getState().setActiveChat('conv_marta'));
    act(() => {
      expect(back()).toBe(true);
    });
    expect(useMessengerStore.getState().activeChatId).toBe('');
    expect(screen.getByTestId('chat-list')).toBeTruthy();
  });

  it('поза телефоном (ПК, без мосту) — капсула лише пошуку, без голосу й питання', async () => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    await mount();
    expect(screen.queryByTestId('ask-nightpost')).toBeNull();
    expect(screen.queryByTestId('search-ask-voice')).toBeNull();
    expect(document.querySelectorAll('[data-search-pill]').length).toBe(1);
  });
});

describe('смуга над списком — лише з даних', () => {
  it('немає даних — немає нічого: ні тривоги, ні людей, ні черги, ні «без інтернету»', async () => {
    const list = await mount();
    await waitFor(() => expect(list.textContent).toContain('Марта'));
    expect(document.querySelector('[data-living-strip]')).toBeNull();
    expect(screen.queryByTestId('queue-line')).toBeNull();
    expect(screen.queryByTestId('offline-line')).toBeNull();
  });
});

