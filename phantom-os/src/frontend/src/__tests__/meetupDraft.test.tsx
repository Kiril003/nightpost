/**
 * Чернетка зустрічі (W2): заклад, час, люди → «Запросити».
 *
 * 1. Поки дріт не везе `widget:meetup` (`isCardKind` = false до N1),
 *    «Запросити» вимкнено з причиною — картка не ляже «лише собі».
 * 2. Коли везе — рівно два запити: створити групу `{title, member_ids}` і
 *    надіслати в неї картку зустрічі; тимчасова група = `at + 24 год`.
 * 3. Досяжність: чернетка відкривається з «+ → Ще → Зустріч» і з
 *    `window.__phantomOpenMeetupDraft(json)` (помічник телефона), уже
 *    заповнена з його слів; чого не знайшли — сказано вголос.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const wire = vi.hoisted(() => ({ carries: false }));
vi.mock('../services/composerCards', async (orig) => {
  const real = await orig<typeof import('../services/composerCards')>();
  const extra: Record<string, string> = { 'widget:meetup': 'meetupData', 'widget:bill': 'billData' };
  return {
    ...real,
    // Ці два роди вирішує лише `wire.carries` — і до N1, і після нього (коли
    // справжній CARD_FIELD їх уже знає), щоб «до нового дроту» лишалось перевіркою.
    isCardKind: (k: string) => (k in extra ? wire.carries : real.isCardKind(k)),
    cardFieldOf: (k: string) => (k in extra ? (wire.carries ? extra[k] : undefined) : real.cardFieldOf(k)),
    refuseCard: (k: string, p: string) =>
      k in extra ? (wire.carries ? null : `Цей вузол не вміє везти картку «${k}» — вона нікуди не поїде`) : real.refuseCard(k, p),
  };
});

import { MeetupDraftSheet } from '../components/messenger/meetup/MeetupDraftSheet';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { messengerApi } from '../services/messengerApi';
import { openMeetupAsk } from '../services/nativeMeetupDraft';
import { useMessengerStore } from '../stores/messengerStore';
import { AT, ME, MEETUP, OLENA, PETRO, VENUE, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';
import { parseMeetupData, typedVenue } from '../types/meetup';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { BillDraftSheet } from '../components/messenger/meetup/BillSheet';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { nextStep, pickWhen, stepNow } from './whenPick';

/** Від порожньої чернетки до «Де»: сьогодні ввечері, Олена. */
const toWhere = (next = /^Далі/) => {
  fireEvent.click(screen.getByTestId('when-quick-tonight'));
  fireEvent.click(screen.getByRole('button', { name: next }));
  fireEvent.click(screen.getByTestId(`draft-person-${OLENA}`));
  fireEvent.click(screen.getByRole('button', { name: next }));
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const contact = (id: string, name: string) => ({
  id,
  peer_node_id: id,
  display_name: name,
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: false,
});

const SUMMARY = {
  id: 'cafe-x',
  name: 'Cafe X',
  address: VENUE.address,
  lat: VENUE.lat,
  lon: VENUE.lon,
  cell: '861e3480fffffff',
  token: 'USDC',
  menu_version: VENUE.menuVersion,
  rating: { mean: 4.5, count: 2, sum: 9 },
};

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

function answer(c: Call) {
  if (c.p === '/messenger/contacts') return { data: [contact(PETRO, 'Петро'), contact(OLENA, 'Олена')] };
  if (c.p === `/messenger/where/${OLENA}`) {
    return {
      data: {
        now_ms: AT,
        eligible: true,
        why_not: null,
        mine: null,
        theirs: { precision: 'quarter', near: 'near', at_ms: AT - 4 * 3600_000, expires_at_ms: AT, stand: false },
      },
    };
  }
  if (c.p.startsWith('/messenger/where/')) return { status: 404 };
  if (c.p === '/messenger/venues/near') return { data: { venues: [SUMMARY] } };
  if (c.m === 'POST' && c.p === '/messenger/conversations') {
    return { data: { id: 'grp-new', title: c.b.title, kind: 'group', circle: 'friends', created_at: '2026-10-09T15:00:00' } };
  }
  if (c.m === 'POST' && c.p === '/messenger/conversations/grp-new/messages') {
    return { data: { id: 'row-1', conversation_id: 'grp-new', delivery: 'sent', kind: c.b.kind } };
  }
  return undefined;
}

const flush = () =>
  act(async () => {
    for (let i = 0; i < 10; i++) await Promise.resolve();
  });

beforeEach(() => {
  prev = useMessengerStore.getState();
  wire.carries = false;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 4 * 3600_000));
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [],
    activeChatId: '',
  });
  calls = installBridge(answer);
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const ARGS = { people: ['Олену', 'Марка'], place: 'cafe x', at: '19:00' };

describe('чернетка з помічника — заповнена, і незнайдене сказано', () => {
  it('Олена знайдена з відмінка, Марка немає, заклад і час — з його слів', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ args: ARGS }} />);
    await flush();
    const sheet = screen.getByTestId('meetup-draft');
    expect(within(sheet).getByTestId('draft-venue').textContent).toContain('Cafe X');
    expect(within(sheet).getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-09T19:00');
    expect(stepNow()).toBe('Крок 4 з 4 · Перевірка');
    expect(within(sheet).getByTestId('draft-row-who').textContent).toContain('Олена');
    expect(within(sheet).getByTestId('draft-row-who').textContent).not.toContain('Петро');
    expect(within(sheet).getByTestId('draft-missing').textContent).toContain('Не знайшов «Марка»');
    fireEvent.click(within(sheet).getByTestId('draft-row-who'));
    expect(within(sheet).getByTestId(`draft-person-${OLENA}`).getAttribute('aria-checked')).toBe('true');
    expect(within(sheet).getByTestId(`draft-person-${PETRO}`).getAttribute('aria-checked')).toBe('false');
  });

  it('хто зараз ділиться місцем — нагорі, з «поруч»', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ venue: VENUE }} />);
    await flush();
    fireEvent.click(screen.getByTestId('when-quick-tonight'));
    nextStep();
    const people = screen.getAllByTestId(/^draft-person-/);
    expect(people[0].getAttribute('data-testid')).toBe(`draft-person-${OLENA}`);
    expect(people[0].textContent).toContain('поруч');
    expect(people[1].textContent).not.toContain('поруч');
  });

  it('закладів поруч немає (старий міст без шляху) — чесне слово, не вигаданий список', async () => {
    calls = installBridge((c) =>
      c.p === '/messenger/venues/near'
        ? { status: 404, code: 'not_found', detail: 'немає такого шляху: GET /messenger/venues/near' }
        : answer(c),
    );
    render(<MeetupDraftSheet open onClose={() => {}} seed={{}} />);
    await flush();
    toWhere();
    expect(screen.getByTestId('draft-venues-refused').textContent).toBe('Заклади поруч поки недоступні. Впишіть назву.');
  });
});

describe('«Запросити» — лише коли дріт везе картку', () => {
  it('до нового дроту — вимкнено, і сказано чому', async () => {
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ args: ARGS }} />);
    await flush();
    const go = screen.getByRole('button', { name: 'Запросити' }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    expect(screen.getByTestId('draft-why').textContent).toContain('ще не везе картку зустрічі');
    fireEvent.click(go);
    await flush();
    expect(calls.some((c) => c.m === 'POST' && c.p === '/messenger/conversations')).toBe(false);
  });

  it('чого бракує — сказано: без людей «Запросити» вимкнено', async () => {
    wire.carries = true;
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ args: { place: 'Cafe X', at: '19:00' } }} />);
    await flush();
    expect((screen.getByRole('button', { name: 'Запросити' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId('draft-why').textContent).toContain('людину');
  });

  it('дріт везе — група «Cafe X · 19:00» з Оленою і картка зустрічі в ній', async () => {
    wire.carries = true;
    const closed = vi.fn();
    render(<MeetupDraftSheet open onClose={closed} seed={{ args: ARGS }} />);
    await flush();
    fireEvent.click(screen.getByRole('button', { name: 'Запросити' }));
    await flush();
    const create = calls.find((c) => c.m === 'POST' && c.p === '/messenger/conversations');
    expect(create?.b).toEqual({ title: 'Cafe X · 19:00', kind: 'group', circle: 'friends', avatar: '', member_ids: [OLENA] });
    const card = calls.find((c) => c.p === '/messenger/conversations/grp-new/messages');
    expect(card?.b.kind).toBe('widget:meetup');
    const body = JSON.parse(card!.b.body);
    expect(body).toMatchObject({
      v: 1,
      at: AT,
      organizer: ME,
      invitees: [{ id: OLENA, name: 'Олена' }],
      expiresAt: AT + 24 * 3600_000,
      venue: { id: 'cafe-x', name: 'Cafe X', rating: { mean: 4.5, count: 2 }, menuVersion: VENUE.menuVersion },
    });
    expect(useMessengerStore.getState().activeChatId).toBe('grp-new');
    expect(closed).toHaveBeenCalled();
  });
});

describe('«У цю групу» — кнопка з того самого списку людей, що й запрошення', () => {
  const GROUP = { id: 'grp', type: 'group', title: 'Друзі', messages: [], unreadCount: 0 };
  const member = (id: string, name: string, isMe = false) => ({
    id,
    name,
    in_roster: true,
    verified: false,
    session_ready: true,
    is_me: isMe,
    role: 'member',
    may_remove: false,
    may_grant: false,
    may_own: false,
  });

  it('склад групи ще невідомий — «Запросити» вимкнено, а не «увімкнено й падає»', async () => {
    wire.carries = true;
    useMessengerStore.setState({ chats: [GROUP as never] });
    calls = installBridge((c) => (c.p === '/messenger/conversations/grp/members' ? { status: 503, code: 'busy' } : answer(c)));
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ args: { place: 'Cafe X', at: '19:00' }, chatId: 'grp' }} />);
    await flush();
    fireEvent.click(screen.getByTestId('draft-row-who'));
    fireEvent.click(screen.getByRole('radio', { name: 'У «Друзі»' }));
    await flush();
    const go = screen.getByRole('button', { name: /^Далі/ }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    expect(screen.getByTestId('draft-why').textContent).not.toBe('');
    fireEvent.click(go);
    await flush();
    expect(calls.some((c) => c.m === 'POST' && c.p.startsWith('/messenger/conversations/grp/messages'))).toBe(false);
  });

  it('склад приїхав — «Запросити» увімкнено, і запрошені — люди групи без мене', async () => {
    wire.carries = true;
    useMessengerStore.setState({ chats: [GROUP as never] });
    calls = installBridge((c) =>
      c.p === '/messenger/conversations/grp/members'
        ? {
            data: {
              id: 'grp',
              title: 'Друзі',
              members: [member(ME, 'Я', true), member(OLENA, 'Олена')],
              addable: [],
              max_members: 50,
              authoritative: false,
              standing: 'in',
              my_role: 'owner',
              owner_id: ME,
              powers: {},
            },
          }
        : answer(c),
    );
    render(<MeetupDraftSheet open onClose={() => {}} seed={{ args: { place: 'Cafe X', at: '19:00' }, chatId: 'grp' }} />);
    await flush();
    fireEvent.click(screen.getByTestId('draft-row-who'));
    fireEvent.click(screen.getByRole('radio', { name: 'У «Друзі»' }));
    await flush();
    nextStep();
    nextStep();
    expect(screen.getByTestId('draft-row-who').textContent).toContain('У «Друзі»');
    expect((screen.getByRole('button', { name: 'Запросити' }) as HTMLButtonElement).disabled).toBe(false);
  });
});

describe('нове прохання помічника — нова чернетка, без хвостів попереднього', () => {
  it('«збери Олену в Cafe X о 19:00», а потім «збери Петра» — час і заклад не лишаються від першого', async () => {
    removeBridge();
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(OLENA, 'Олена'), contact(PETRO, 'Петро')] as never);
    useMessengerStore.setState({ hydrated: false });
    try {
      render(
        <MemoryRouter>
          <MessengerRoot />
        </MemoryRouter>,
      );
      await flush();
      const open = (window as unknown as { __phantomOpenMeetupDraft: (j: string) => boolean }).__phantomOpenMeetupDraft;
      act(() => {
        open(JSON.stringify({ people: ['Олену'], place: 'Cafe X', at: '19:00' }));
      });
      await flush();
      let sheet = await screen.findByTestId('meetup-draft');
      expect(within(sheet).getByTestId('draft-at').getAttribute('data-value')).toBe('2026-10-09T19:00');
      expect(within(sheet).getByTestId('draft-venue').textContent).toContain('Cafe X');
      act(() => {
        open(JSON.stringify({ people: ['Петра'] }));
      });
      await flush();
      sheet = screen.getByTestId('meetup-draft');
      expect(within(sheet).getByTestId('draft-venue').textContent).toContain('Обрати місце');
      expect(within(sheet).getByTestId('draft-at').getAttribute('data-value')).toBe('');
      expect(within(sheet).getByTestId('draft-row-who').textContent).toContain('Петро');
      expect(within(sheet).getByTestId('draft-row-who').textContent).not.toContain('Олена');
    } finally {
      act(() => openMeetupAsk(null));
    }
  });
});

describe('досяжність', () => {
  const composerProps = {
    onSendMessage: () => {},
    onSendVoiceMessage: () => {},
    onOpenActions: () => {},
    onOpenScheduler: () => {},
    replyingTo: null,
    onCancelReply: () => {},
    editingMessage: null,
    onCancelEdit: () => {},
    onSaveEdit: () => {},
    selectedMessagesForQuote: [],
    onSynthesizeMultiQuote: () => {},
    onClearSelectedQuotes: () => {},
    chatId: 'grp',
  };

  it('«+ → Ще → Зустріч» відкриває чернетку', async () => {
    render(<MessageComposer {...composerProps} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    await flush();
    fireEvent.click(screen.getByTestId('send-meetup'));
    await flush();
    expect(screen.getByTestId('meetup-draft')).toBeTruthy();
  });

  it('window.__phantomOpenMeetupDraft з кореня месенджера відкриває заповнену чернетку', async () => {
    removeBridge();
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(OLENA, 'Олена')] as never);
    useMessengerStore.setState({ hydrated: false });
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await flush();
    const open = (window as unknown as { __phantomOpenMeetupDraft?: (j: string) => boolean }).__phantomOpenMeetupDraft;
    expect(typeof open).toBe('function');
    act(() => {
      open!(JSON.stringify({ people: ['Олену'], at: '19:00' }));
    });
    await flush();
    const sheet = await screen.findByTestId('meetup-draft');
    expect(within(sheet).getByTestId('draft-row-who').textContent).toContain('Олена');
  });

  it('збірка 19: «+ → Ще → Зустріч», закладів поруч немає — вписана назва стає місцем, і картка йде', async () => {
    wire.carries = true;
    calls = installBridge((c) =>
      c.p === '/messenger/venues/near'
        ? { status: 503, code: 'places_off', detail: 'The places and points server is not switched on yet.' }
        : answer(c),
    );
    render(<MessageComposer {...composerProps} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    await flush();
    fireEvent.click(screen.getByTestId('send-meetup'));
    await flush();
    const sheet = screen.getByTestId('meetup-draft');
    pickWhen(null, '2026-10-09', '20', '00');
    nextStep();
    fireEvent.click(within(sheet).getByTestId(`draft-person-${OLENA}`));
    nextStep();
    expect(within(sheet).getByTestId('draft-venues-refused').textContent).toBe('Заклади поруч поки недоступні. Впишіть назву.');
    expect(sheet.textContent).not.toMatch(/server|Не вдалося/);
    expect(within(sheet).getByTestId('draft-why').textContent).toBe('Впишіть або оберіть місце.');
    fireEvent.change(within(sheet).getByTestId('draft-venue-query'), { target: { value: 'Pho Bar' } });
    nextStep();
    expect(within(sheet).getByTestId('draft-typed-note').textContent).toBe('На мапі цього місця не буде.');
    const go = screen.getByRole('button', { name: 'Запросити' }) as HTMLButtonElement;
    expect(go.disabled).toBe(false);
    fireEvent.click(go);
    await flush();
    const create = calls.find((c) => c.m === 'POST' && c.p === '/messenger/conversations');
    expect(create?.b.title).toBe('Pho Bar · 20:00');
    const card = calls.find((c) => c.p === '/messenger/conversations/grp-new/messages');
    const body = JSON.parse(card!.b.body);
    expect(body.venue).toEqual({ id: 'typed:pho bar', name: 'Pho Bar', address: '', lat: null, lon: null, rating: null, menuVersion: null });
    expect(parseMeetupData(body)?.venue.name).toBe('Pho Bar');
  });
});

describe('збірка 19: вписане місце без точки', () => {
  it('англійською — один людський рядок замість відмови сервера', async () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
    try {
      calls = installBridge((c) =>
        c.p === '/messenger/venues/near'
          ? { status: 503, code: 'places_off', detail: 'The places and points server is not switched on yet.' }
          : answer(c),
      );
      render(<MeetupDraftSheet open onClose={() => {}} seed={{}} />);
      await flush();
      toWhere(/^Next/);
      expect(screen.getByTestId('draft-venues-refused').textContent).toBe("Nearby places aren't available yet. Type the name.");
    } finally {
      useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
    }
  });

  it('картка з вписаним місцем: без рядка закладу й без запиту сторінки чи меню', async () => {
    const msg = meetupMsg({ meetupData: { ...MEETUP, venue: typedVenue('Pho Bar') } });
    render(<MeetupCard msg={msg} meId={ME} />);
    expect(screen.getByTestId('card-meetup').textContent).toContain('Pho Bar');
    expect(screen.queryByTestId('meetup-venue')).toBeNull();
    cleanup();
    render(<BillDraftSheet open onClose={() => {}} meetupMsg={msg} />);
    await flush();
    expect(calls.some((c) => c.p.startsWith('/messenger/venues/'))).toBe(false);
  });
});
