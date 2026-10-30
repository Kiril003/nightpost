/**
 * «Я» — особистий центр: шапка, як мене знаходять, пристрої, памʼять, тиша, мережа, налаштування.
 * Кожен розділ — лише з даних мосту; немає даних — немає розділу.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi, type KnownFact } from '../services/phantomSelfApi';
import { whereApi, type WhereState } from '../services/peerWhere';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import type { Chat } from '../types/messenger';
import { useLiving } from '../services/livingPulse';
import { deviceApi } from '../services/deviceApi';

const NOW = Date.UTC(2026, 8, 26, 16, 0, 0);

const fact = (id: string, text: string, old = false): KnownFact => ({
  id, text, from: 'self', from_label: 'ти сказав', skill: false, at: new Date(NOW).toISOString(), ...(old ? { old } : {}),
});

const dm = (id: string, title: string): Chat =>
  ({ id: `c_${id}`, title, type: 'dm', peerNodeId: id, messages: [], unreadCount: 0 }) as unknown as Chat;

const where = (mine: WhereState['mine']): WhereState => ({ now_ms: NOW, eligible: true, why_not: null, mine, theirs: null });

const open = () =>
  render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);

let prevState: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prevState = useMessengerStore.getState();
  vi.spyOn(Date, 'now').mockReturnValue(NOW);
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  useMessengerStore.setState({
    currentUser: { ...prevState.currentUser, name: 'Кирило', status: 'Вдома · вільний після 20:00', avatar: '' },
    chats: [],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prevState, true);
});

describe('«Я»: імʼя, якого людина не давала', () => {
  it('назва пристрою — не імʼя: «Як вас звати?» і задати імʼя через телефон', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const rename = vi.spyOn(messengerApi, 'renameSelf').mockResolvedValue({ bundle: { name: 'Кирило' } } as never);
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, name: 'Nightpost-A52' } });
    open();
    const me = screen.getByTestId('me-screen');
    expect(me.textContent).not.toContain('Nightpost-A52');
    fireEvent.click(within(me).getByText('Як вас звати?'));
    fireEvent.change(within(me).getByLabelText('Ваше імʼя'), { target: { value: 'Кирило' } });
    fireEvent.click(within(me).getByText('Готово'));
    await waitFor(() => expect(rename).toHaveBeenCalledWith('Кирило'));
    await waitFor(() => expect(within(me).getByText('Кирило')).toBeTruthy());
    expect(useMessengerStore.getState().currentUser.name).toBe('Кирило');
  });

  it('людське імʼя, навіть зі словом Nightpost усередині, показується як є', async () => {
    const { isMachineName } = await import('../services/selfName');
    expect(isMachineName('Nightpost-A52')).toBe(true);
    expect(isMachineName('Nightpost')).toBe(true);
    // Старе імʼя застосунку вже лежить у профілях — теж не людське.
    expect(isMachineName('PHANTOM-A52')).toBe(true);
    expect(isMachineName('PHANTOM')).toBe(true);
    expect(isMachineName('SM-A525F')).toBe(true);
    expect(isMachineName('')).toBe(true);
    expect(isMachineName('Кирило')).toBe(false);
    expect(isMachineName('Кирило з Nightpost')).toBe(false);
  });
});

describe('«Я»: шапка', () => {
  it('імʼя і «про себе»; ні відбитка, ні ключа', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    open();
    const me = screen.getByTestId('me-screen');
    expect(within(me).getByText('Кирило')).toBeTruthy();
    expect(within(me).getByTestId('me-about').textContent).toBe('Вдома · вільний після 20:00');
    expect(me.textContent).not.toMatch(/відбит|ключ|звірен|шифр/i);
  });

  it('«про себе» бере bio, коли людина його дала', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, bio: 'Фотограф з Подолу' } });
    open();
    expect(screen.getByTestId('me-about').textContent).toBe('Фотограф з Подолу');
  });

  it('без статусу й bio рядка під імʼям немає', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, status: '', bio: '' } });
    open();
    expect(screen.queryByTestId('me-about')).toBeNull();
  });

  it('шапка відкриває власний профіль', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ bundle: { name: 'Кирило' } } as never);
    open();
    fireEvent.click(screen.getByTestId('me-head'));
    expect(await screen.findByTestId('profile-sheet')).toBeTruthy();
  });
});

describe('«Я»: як мене знаходять', () => {
  it('ключ не береться, поки людина не торкнулась', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const identity = vi.spyOn(messengerApi, 'identity');
    open();
    expect(screen.getByTestId('me-share')).toBeTruthy();
    expect(identity).not.toHaveBeenCalled();
  });

  it('«Поділитися» віддає посилання-запрошення системному «Поділитися»', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ link: 'phantom://invite/PH2-abc', compact: 'PH2-abc', bundle: { name: 'Кирило' } } as never);
    const shareText = vi.fn(() => true);
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}', shareText };
    open();
    fireEvent.click(screen.getByTestId('me-share'));
    await waitFor(() => expect(shareText).toHaveBeenCalledWith('phantom://invite/PH2-abc', 'Запрошення в Nightpost'));
  });

  it('«Показати код» — QR на весь екран, з імʼям і ніком, без рядка ключа (глосарій §8: QR → «код»)', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ link: 'phantom://invite/PH2-abc', compact: 'PH2-abc', bundle: { name: 'Кирило' } } as never);
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ published: true, handle: 'kyrylo_m' } as never);
    open();
    await screen.findByText('@kyrylo_m');
    fireEvent.click(screen.getByTestId('me-code'));
    const full = await screen.findByTestId('me-qr-full');
    expect(within(full).getByText('@kyrylo_m')).toBeTruthy();
    expect(full.textContent).not.toContain('PH2');
    const words = (el: HTMLElement) =>
      [el.textContent ?? '', ...Array.from(el.querySelectorAll('[aria-label],[alt]')).map((n) => `${n.getAttribute('aria-label') ?? ''} ${n.getAttribute('alt') ?? ''}`), el.getAttribute('aria-label') ?? ''].join(' ');
    expect(words(full)).not.toMatch(/\bQR\b|ключ|PH2:/i);
    fireEvent.click(within(full).getByLabelText('Закрити'));
    expect(screen.queryByTestId('me-qr-full')).toBeNull();
  });

  it('телефон не віддав ключ — тихе речення, QR немає', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('down'));
    open();
    fireEvent.click(screen.getByTestId('me-code'));
    expect(await screen.findByText(/Запрошення зараз не зробити/)).toBeTruthy();
    expect(screen.queryByTestId('me-qr-full')).toBeNull();
  });
});

describe('«Я»: мої пристрої', () => {
  it('рядок на кожен пристрій зі станом; тап відкриває «Мій пристрій»', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({
      stopped: false,
      devices: [
        { id: 'pc1', name: 'Ноутбук', kind: 'pc', online: true, peer_id: 'p_pc' },
        { id: 'pc2', name: 'Робочий ПК', kind: 'pc', online: false, seen_at: new Date(NOW - 12 * 60_000).toISOString() },
      ],
    });
    vi.spyOn(deviceApi, 'ladder').mockRejectedValue(new Error('no'));
    vi.spyOn(deviceApi, 'journal').mockRejectedValue(new Error('no'));
    open();
    const card = await screen.findByTestId('me-devices');
    expect(within(card).getByText('на звʼязку')).toBeTruthy();
    expect(within(card).getByText('був 12 хв тому')).toBeTruthy();
    fireEvent.click(within(card).getByText('Ноутбук'));
    expect(await screen.findAllByRole('dialog')).toHaveLength(2);
  });

  it('пристроїв немає або вузол не відповів — лишається лише «Додати свій пристрій»', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const list = vi.spyOn(deviceApi, 'list').mockRejectedValue(new Error('404'));
    open();
    await waitFor(() => expect(list).toHaveBeenCalled());
    const part = screen.getByTestId('me-devices');
    expect(part.querySelector('[data-add-own-device]')).not.toBeNull();
    expect(part.querySelectorAll('button:not([data-web-signin])').length).toBe(1);
  });
});

describe('«Я»: мої люди', () => {
  it('люди й групи одним рядком; тап відкриває людей тут же', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const group = { ...dm('g', 'Двір'), type: 'group', peerNodeId: undefined } as unknown as Chat;
    useMessengerStore.setState({ chats: [dm('p_oks', 'Оксана'), dm('p_ihor', 'Ігор'), group] });
    vi.spyOn(whereApi, 'get').mockResolvedValue(where(null));
    const onClose = vi.fn();
    render(<PhoneSettingsSheet isOpen onClose={onClose} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    const row = screen.getByTestId('me-tile-people');
    expect(row.textContent).toContain('Мої люди');
    expect(row.textContent).toContain('2\u00A0людини · 1\u00A0група');
    fireEvent.click(row);
    expect(await screen.findByTestId('people-screen')).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('нікого немає — плитка без вигаданого значення', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    expect(screen.getByTestId('me-tile-people').textContent).toBe('Мої люди');
  });
});

describe('«Я»: хто бачить, де ви', () => {
  it('людина з живим поширенням — «ще 50 хв» і «Зупинити», що кличе міст', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ chats: [dm('p_oks', 'Оксана'), dm('p_ihor', 'Ігор')] });
    vi.spyOn(whereApi, 'get').mockImplementation(async (id) =>
      id === 'p_oks' ? where({ precision: 'quarter', until_ms: NOW + 50 * 60_000, stand: false }) : where(null),
    );
    const stop = vi.spyOn(whereApi, 'stop').mockResolvedValue(where(null));
    open();
    const card = await screen.findByTestId('me-watchers');
    expect(within(card).getByText('Оксана')).toBeTruthy();
    expect(within(card).getByText('ще 50 хв')).toBeTruthy();
    expect(within(card).queryByText('Ігор')).toBeNull();
    fireEvent.click(within(card).getByText('Зупинити'));
    await waitFor(() => expect(stop).toHaveBeenCalledWith('p_oks'));
    await waitFor(() => expect(screen.queryByTestId('me-watchers')).toBeNull());
  });

  it('ніхто не бачить — розділу немає', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ chats: [dm('p_oks', 'Оксана')] });
    const get = vi.spyOn(whereApi, 'get').mockResolvedValue(where(null));
    open();
    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(screen.queryByText('Хто бачить, де ви')).toBeNull();
  });
});

describe('«Я»: що Nightpost про мене памʼятає', () => {
  it('чотири свіжі рядки і «Усе · N», що відкриває повний список', async () => {
    const facts = [
      fact('1', 'Дім — Костянтинівська'),
      fact('2', 'Кава о 9:00'),
      fact('3', 'Алергія на пил'),
      fact('4', 'Мама — неділі'),
      fact('5', 'Бігає по суботах'),
      fact('6', 'стара нотатка', true),
    ];
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue(facts);
    open();
    const card = await screen.findByTestId('me-knows');
    expect(within(card).getAllByRole('listitem')).toHaveLength(4);
    expect(within(card).queryByText('Бігає по суботах')).toBeNull();
    expect(within(card).queryByText('стара нотатка')).toBeNull();
    fireEvent.click(within(card).getByText('Усе · 6'));
    expect(await screen.findByTestId('phantom-knows')).toBeTruthy();
  });

  it('«Забути» стирає факт у памʼяті, і рядок зникає лише після згоди телефона', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([fact('1', 'Кава о 9:00'), fact('2', 'Алергія на пил')]);
    const forget = vi.spyOn(phantomSelfApi, 'forget').mockResolvedValue({ forgotten: true });
    open();
    const card = await screen.findByTestId('me-knows');
    fireEvent.click(within(card).getByLabelText('Забути: Кава о 9:00'));
    await waitFor(() => expect(forget).toHaveBeenCalledWith('1', 'forget'));
    await waitFor(() => expect(within(card).queryByText('Кава о 9:00')).toBeNull());
    expect(within(card).getByText('Алергія на пил')).toBeTruthy();
  });

  it('телефон не забув — рядок лишається і сказано чому', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([fact('1', 'Кава о 9:00')]);
    vi.spyOn(phantomSelfApi, 'forget').mockRejectedValue(new Error('no'));
    open();
    const card = await screen.findByTestId('me-knows');
    fireEvent.click(within(card).getByText('Забути'));
    expect(await within(card).findByText(/Не вдалося забути/)).toBeTruthy();
    expect(within(card).getByText('Кава о 9:00')).toBeTruthy();
  });

  it('фактів ще немає — одне тихе речення', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    open();
    expect(await screen.findByTestId('me-knows-empty')).toBeTruthy();
    expect(screen.queryByRole('listitem')).toBeNull();
  });

  it('памʼяті тут немає (ПК без маршруту) — частини немає', async () => {
    const facts = vi.spyOn(phantomSelfApi, 'facts').mockRejectedValue(new Error('404'));
    open();
    await waitFor(() => expect(facts).toHaveBeenCalled());
    expect(screen.queryByTestId('me-knows')).toBeNull();
  });
});

describe('«Я»: налаштування всередині', () => {
  it('«Налаштування» ведуть до звуку, звітів і заблокованих', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    open();
    expect(screen.queryByText('Звіти про прочитання')).toBeNull();
    fireEvent.click(screen.getByTestId('me-prefs'));
    fireEvent.click(await screen.findByTestId('settings-row-privacy'));
    expect(await screen.findByText('Звіти про прочитання')).toBeTruthy();
    expect(await screen.findByText('Заблоковані')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('Назад'));
    fireEvent.click(await screen.findByTestId('settings-row-notifications'));
    expect(await screen.findByText('Звук повідомлень')).toBeTruthy();
  });
});

describe('«Я»: фото', () => {
  it('дотик до фото відкриває вибір знімка, а не профіль', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    open();
    const input = screen.getByTestId('me-photo-input') as HTMLInputElement;
    const click = vi.spyOn(input, 'click').mockImplementation(() => {});
    fireEvent.click(screen.getByTestId('me-photo'));
    expect(click).toHaveBeenCalled();
    expect(input.accept).toBe('image/*');
    expect(screen.queryByTestId('profile-sheet')).toBeNull();
  });

  it('є фото: дотик відкриває його на весь екран, змінює лише камера', () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, avatar: '/webmsgr/_faces/me.jpg' } });
    open();
    const input = screen.getByTestId('me-photo-input') as HTMLInputElement;
    const click = vi.spyOn(input, 'click').mockImplementation(() => {});
    fireEvent.click(screen.getByTestId('me-photo'));
    expect(click).not.toHaveBeenCalled();
    expect(screen.getByTestId('media-lightbox')).toBeTruthy();
    fireEvent.click(screen.getByTestId('me-photo-change'));
    expect(click).toHaveBeenCalled();
  });

  it('«Хто бачить, де ви» — з фото людини, не з літерою', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const photo = '/webmsgr/_faces/0123456789abcdef0123456789abcdef?v=2';
    useLiving.setState({ faces: { p_oks: { avatar: photo, heardAtMs: null, lanSeenAtMs: null } } });
    useMessengerStore.setState({ chats: [dm('p_oks', 'Оксана')] });
    vi.spyOn(whereApi, 'get').mockResolvedValue(where({ precision: 'quarter', until_ms: NOW + 50 * 60_000, stand: false }));
    open();
    const card = await screen.findByTestId('me-watchers');
    expect(card.querySelector('img')?.getAttribute('src')).toBe(photo);
    useLiving.setState({ faces: {} });
  });
});

describe('«Я»: «Без інтернету» — рядки згрупованого списку; «Офлайн-мап» і «Для розробника» немає', () => {
  // Ця «Я» — настільна: телефон з аватара йде в рідний профіль. На ПК у бекенді
  // немає ні /messenger/backpack, ні /messenger/relayfs, тож рядки «Офлайн-мапи»
  // і «Для розробника» тут лише мовчали або обіцяли неможливе — віднято (04.10).
  it('з мостом — рядок «Без інтернету» є, а «Офлайн-мап» і групи «Для розробника» немає', async () => {
    const { intentsApi } = await import('../services/intentsApi');
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const setup = vi.spyOn(intentsApi, 'setup').mockResolvedValue({ sms_build: false } as never);
    vi.spyOn(intentsApi, 'list').mockResolvedValue({ intents: [] });
    open();
    const offline = await screen.findByTestId('me-offline-group');
    await waitFor(() => expect(setup).toHaveBeenCalled());
    await waitFor(() => expect(within(offline).getByTestId('me-offline')).toBeTruthy());
    expect(within(offline).queryByTestId('me-maps')).toBeNull();
    expect(screen.queryByTestId('me-dev-group')).toBeNull();
    expect(screen.queryByTestId('me-dev')).toBeNull();
    const text = screen.getByTestId('me-screen').textContent ?? '';
    expect(text).not.toContain('Офлайн-мапи');
    expect(text).not.toContain('Для розробника');
    // Нова розмітка — лише новими токенами.
    expect(offline.outerHTML).not.toMatch(/\b(text|bg|rounded)-f-/);
  });

  it('без мосту (ПК) — немає ні рядків, ні їхньої групи', async () => {
    const { intentsApi } = await import('../services/intentsApi');
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    const setup = vi.spyOn(intentsApi, 'setup').mockRejectedValue(new Error('no bridge'));
    vi.spyOn(intentsApi, 'list').mockRejectedValue(new Error('no bridge'));
    open();
    await waitFor(() => expect(setup).toHaveBeenCalled());
    expect(screen.queryByTestId('me-offline')).toBeNull();
    expect(screen.queryByTestId('me-dev-group')).toBeNull();
    // Картка групи порожня — CSS ховає її разом із заголовком (has-[>div:empty]).
    expect(screen.getByTestId('me-offline-group').className).toContain('has-[>div:empty]:hidden');
  });
});

describe('«Я» → «Звук і приватність»: без гумки «Доказові розмови»', () => {
  it('розмова з назвою «тест» чи позначкою is_demo не робить групи масового прибирання', async () => {
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([
      { id: 'c1', title: 'Тест звʼязку', is_demo: true } as never,
      { id: 'c2', title: 'proof-call', is_demo: false } as never,
    ]);
    open();
    fireEvent.click(screen.getByTestId('me-prefs'));
    const prefs = await screen.findByTestId('phone-settings');
    fireEvent.click(within(prefs).getByTestId('settings-row-privacy'));
    const privacy = await screen.findByTestId('settings-privacy');
    await waitFor(() => expect(within(privacy).getByText('Звіти про прочитання')).toBeTruthy());
    expect(privacy.textContent).not.toContain('Доказові розмови');
    expect(within(privacy).queryByRole('button', { name: /Прибрати обрані/ })).toBeNull();
  });
});
