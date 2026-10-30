/**
 * Бали у вебі: `/messenger/points/me` → картка в «Я» і в колонці «Гаманець».
 * Стани як у рідного PointsLoad: немає сервера чи ключа — картки немає;
 * сервер мовчить — «немає звʼязку», не «0»; балів ще немає — так і сказано.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { loadPoints, parsePoints } from '../services/points';
import { PointsCardView } from '../components/messenger/PointsCard';
import { checkinWords } from '../services/meetupApi';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { DeskSidePane } from '../components/messenger/DeskSidePane';
import { useUIStore } from '../stores/uiStore';

const ME = { total: 15, events: [
  { kind: 'meetup.happened', ref: 'm1', points: 10, at: Date.UTC(2026, 9, 20, 12) },
  { kind: 'payment.first', ref: 'p1', points: 5, at: Date.UTC(2026, 9, 25, 12) },
] };

type Envelope = { ok: boolean; status: number; data?: unknown; code?: string; detail?: string };
const bridge = (points: Envelope) => {
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
    call: (_m: string, p: string) =>
      JSON.stringify(p.split('?')[0] === '/messenger/points/me' ? points : { ok: true, status: 200, data: {} }),
  };
};
const setLang = (lang: string) =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: lang } }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  setLang('uk');
});

describe('відповідь сервера → вигляд', () => {
  it('найновіші зверху, разом — як сказав сервер', () => {
    const v = parsePoints(ME);
    expect(v?.total).toBe(15);
    expect(v?.events.map((e) => e.kind)).toEqual(['payment.first', 'meetup.happened']);
  });

  it('не те, що обіцяв сервер, — не вигляд', () => {
    expect(parsePoints({ total: '15', events: [] })).toBeNull();
    expect(parsePoints({ total: 1.5, events: [] })).toBeNull();
    expect(parsePoints({ total: 5, events: [{ kind: 'x', points: 5 }] })).toBeNull();
    expect(parsePoints([])).toBeNull();
  });
});

describe('стани, як у рідного PointsLoad', () => {
  it('ПК без мосту телефона — картки немає, сервер не питали', async () => {
    const read = vi.fn();
    expect(await loadPoints(read)).toEqual({ kind: 'hidden' });
    expect(read).not.toHaveBeenCalled();
  });

  it('міст відповів — бали показано', async () => {
    bridge({ ok: true, status: 200, data: ME });
    const l = await loadPoints();
    expect(l.kind).toBe('shown');
  });

  it('у збірці немає сервера балів або ключа — картки немає', async () => {
    bridge({ ok: false, status: 503, code: 'not_enabled', detail: 'x' });
    expect(await loadPoints()).toEqual({ kind: 'hidden' });
    bridge({ ok: false, status: 409, code: 'no_key', detail: 'x' });
    expect(await loadPoints()).toEqual({ kind: 'hidden' });
  });

  it('сервер не відповів або відповів не тим — «немає звʼязку», не нуль', async () => {
    bridge({ ok: false, status: 502, code: 'unreachable', detail: 'x' });
    expect(await loadPoints()).toEqual({ kind: 'offline' });
    bridge({ ok: true, status: 200, data: { total: 'many' } });
    expect(await loadPoints()).toEqual({ kind: 'offline' });
  });
});

describe('картка', () => {
  it('разом і що саме зараховано, словами мови людини', () => {
    setLang('en');
    render(<PointsCardView load={{ kind: 'shown', view: parsePoints(ME)! }} />);
    expect(screen.getByTestId('points-total').textContent).toBe('15');
    const rows = screen.getAllByTestId('points-event').map((r) => r.textContent);
    expect(rows[0]).toContain('First paid share');
    expect(rows[0]).toContain('+5');
    expect(rows[1]).toContain('Meetup happened');
  });

  it('балів ще немає — порожній стан без «0»', () => {
    render(<PointsCardView load={{ kind: 'shown', view: { total: 0, events: [] } }} />);
    expect(screen.getByTestId('points-empty').textContent).toBe('Балів ще немає');
    expect(screen.queryByTestId('points-total')).toBeNull();
  });

  it('немає звʼязку — так і сказано; прихована — нічого', () => {
    const { rerender } = render(<PointsCardView load={{ kind: 'offline' }} />);
    expect(screen.getByTestId('points-offline').textContent).toBe('Немає звʼязку з сервером балів');
    rerender(<PointsCardView load={{ kind: 'hidden' }} />);
    expect(screen.queryByTestId('points-card')).toBeNull();
  });
});

describe('«Я на місці»: речення мовою людини', () => {
  it('англійською — англійською, не українською', () => {
    setLang('en');
    expect(checkinWords({ awarded: [{ kind: 'meetup.happened' }], waiting_for: 0 })).toBe('Meetup counted. Points added.');
    setLang('cs');
    expect(checkinWords({ awarded: [], waiting_for: 1 })).toContain('Odbavení přijato');
  });
});

describe('досяжність: «Я» на телефоні без рідної вкладки', () => {
  let prev: ReturnType<typeof useMessengerStore.getState>;
  beforeEach(() => {
    prev = useMessengerStore.getState();
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ currentUser: { ...prev.currentUser, name: 'Кирило', avatar: '' }, chats: [] });
  });
  afterEach(() => useMessengerStore.setState(prev, true));

  it('картка балів стоїть в «Я» з числом від мосту', async () => {
    bridge({ ok: true, status: 200, data: ME });
    await act(async () => {
      render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    });
    const me = screen.getByTestId('me-screen');
    await waitFor(() => expect(within(me).getByTestId('points-total').textContent).toBe('15'));
  });

  it('картка балів стоїть у колонці «Гаманець»', async () => {
    bridge({ ok: true, status: 200, data: ME });
    useUIStore.setState({ deskPane: 'wallet' });
    await act(async () => {
      render(<DeskSidePane />);
    });
    await waitFor(() => expect(screen.getByTestId('points-total').textContent).toBe('15'));
    useUIStore.setState({ deskPane: null });
  });
});

describe('звертання в балах — «ви», як усюди в застосунку', () => {
  it('приватність балів каже «Бачите лише ви»', async () => {
    const { uk } = await import('../i18n/messages/parts/points');
    expect(uk['points.private']).toBe('Бачите лише ви');
  });
});
