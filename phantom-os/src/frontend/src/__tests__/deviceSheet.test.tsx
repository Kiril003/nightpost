/**
 * «Мій пристрій» — одне вікно на телефоні й на ПК: стан лише з даних, дії
 * однаковими кнопками, свої сходинки міняються, чужі лише видно, журнал
 * людськими рядками, одна кнопка «Зупинити все».
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { DeviceSheet } from '../components/messenger/DeviceSheet';
import { deviceApi, deviceFor, type Ladders, type OwnDevice } from '../services/deviceApi';
import { useMessengerStore } from '../stores/messengerStore';
import { MemoryRouter } from 'react-router-dom';
import { Header } from '../components/messenger/Header';
import type { Chat, UserProfile } from '../types/messenger';

const NOW = Date.UTC(2026, 8, 27, 18, 0, 0);

const pc: OwnDevice = { id: 'pc', name: 'Ноутбук', kind: 'pc', peer_id: 'node-pc', online: true, battery_pct: 81, charging: true };
const phone: OwnDevice = { id: 'dev-1', name: 'Телефон Кирила', kind: 'phone', online: false, seen_at: new Date(NOW - 12 * 60_000).toISOString() };

const phoneLadders: Ladders = {
  here: {
    editable: true,
    spheres: [
      { sphere: 'place', title: 'Місце', level: 'see', irreversible: false },
      { sphere: 'sound', title: 'Звук і голос', level: 'see', irreversible: false },
      { sphere: 'notices', title: 'Сповіщення', level: 'see', irreversible: false },
    ],
  },
  there: {
    editable: false,
    spheres: [
      { sphere: 'clipboard', title: 'Буфер і посилання', level: 'ask', irreversible: false },
      { sphere: 'keyboard', title: 'Клавіатура', level: 'silent', irreversible: true },
    ],
  },
};

const pcLadders: Ladders = {
  here: {
    editable: true,
    spheres: [
      { sphere: 'clipboard', title: 'Буфер і посилання', level: 'see', irreversible: false },
      { sphere: 'keyboard', title: 'Клавіатура', level: 'see', irreversible: true },
    ],
  },
  there: null,
};

const asPhone = () => {
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
};

const stubs = (devices: OwnDevice[], ladders: Ladders, entries = [] as Awaited<ReturnType<typeof deviceApi.journal>>['entries'], stopped = false) => {
  vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped, devices });
  vi.spyOn(deviceApi, 'ladder').mockResolvedValue(ladders);
  vi.spyOn(deviceApi, 'journal').mockResolvedValue({ stopped, entries });
};

beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(NOW);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

const sheet = () => screen.getByTestId('device-sheet');

describe('стан іншого пристрою', () => {
  it('на телефоні: ПК на звʼязку, заряд — і жодної техніки', async () => {
    asPhone();
    stubs([pc], phoneLadders);
    render(<DeviceSheet title="Мій комп" peerNodeId="node-pc" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('device-name').textContent).toBe('Ноутбук'));
    const status = screen.getByTestId('device-status').textContent ?? '';
    expect(status).toContain('На звʼязку');
    expect(status).toContain('заряд 81%, заряджається');
    const text = sheet().textContent ?? '';
    for (const tech of ['node-pc', 'pc.', 'clipboard', 'keyboard', 'symbiote', '403', 'ask', 'silent']) {
      expect(text).not.toContain(tech);
    }
  });

  it('на ПК: телефон мовчить — «був 12 хв тому», заряду й «Де він» немає', async () => {
    stubs([phone], pcLadders);
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('device-status').textContent).toBe('був 12 хв тому'));
    expect(sheet().textContent).not.toContain('заряд');
    expect(screen.queryByTestId('device-act-where')).toBeNull();
  });

  it('не спарено для керування — ні дій, ні «Зупинити все»', async () => {
    stubs([], pcLadders);
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('device-status').textContent).toBe('Не спарено для керування'));
    expect(screen.queryByTestId('device-actions')).toBeNull();
    expect(screen.queryByTestId('device-stop-all')).toBeNull();
    expect(screen.getByTestId('device-name').textContent).toBe('Мій телефон');
  });
});

describe('прямі дії', () => {
  it('з телефона на ПК — чотири однакові кнопки, головна одна', async () => {
    asPhone();
    stubs([pc], phoneLadders);
    render(<DeviceSheet title="Ноутбук" peerNodeId="node-pc" onClose={() => {}} />);
    const grid = await screen.findByTestId('device-actions');
    const buttons = within(grid).getAllByRole('button');
    expect(buttons.map((b) => b.textContent)).toEqual([
      'Скопіювати на компʼютер'.replace('’', 'ʼ'),
      'Відкрити посилання',
      'Показати сповіщення',
      'Замкнути екран',
    ]);
    expect(grid.getAttribute('style')).toContain('repeat(2, minmax(0, 1fr))');
    expect(buttons.filter((b) => b.className.includes('bg-accent'))).toHaveLength(1);
    expect(buttons.every((b) => /min-h-\[52px\]/.test(b.className))).toBe(true);
  });

  it('посилання: поле → «Надіслати» → слова відповіді, не код', async () => {
    asPhone();
    stubs([pc], phoneLadders);
    const act = vi.spyOn(deviceApi, 'act').mockResolvedValue({ ok: true, state: 'asked', said: 'Чекає на ваше «так» на комп\'ютері' });
    render(<DeviceSheet title="Ноутбук" peerNodeId="node-pc" onClose={() => {}} />);
    fireEvent.click(await screen.findByTestId('device-act-pc.open'));
    fireEvent.change(screen.getByLabelText('Посилання'), { target: { value: 'https://example.org' } });
    fireEvent.click(screen.getByText('Надіслати'));
    await waitFor(() => expect(act).toHaveBeenCalledWith('pc', 'pc.open', 'https://example.org'));
    expect((await screen.findByTestId('device-said')).textContent).toBe('Чекає на ваше «так» на комп\'ютері');
    expect(screen.queryByTestId('device-compose')).toBeNull();
  });

  it('з ПК на телефон: «Знайти телефон» стає «Зупинити дзвінок»', async () => {
    stubs([{ ...phone, online: true, place: { lat: 50.45, lon: 30.52 } }], pcLadders);
    const act = vi.spyOn(deviceApi, 'act').mockResolvedValue({ ok: true, state: 'sent', said: 'Передано телефону' });
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    const grid = await screen.findByTestId('device-actions');
    expect(within(grid).getAllByRole('button').map((b) => b.textContent)).toEqual(['Знайти телефон', 'Де він', 'Сказати вголос']);
    fireEvent.click(screen.getByTestId('device-act-phone.find'));
    await waitFor(() => expect(act).toHaveBeenCalledWith('dev-1', 'phone.find', ''));
    fireEvent.click(await screen.findByTestId('device-act-phone.stop'));
    await waitFor(() => expect(act).toHaveBeenLastCalledWith('dev-1', 'phone.stop', ''));
    expect(await screen.findByTestId('device-act-phone.find')).toBeTruthy();
  });

  it('«Де він» відкриває наявне місце і закриває вікно', async () => {
    stubs([{ ...phone, place: { lat: 50.45, lon: 30.52 } }], pcLadders);
    const openPlace = vi.fn();
    const prev = useMessengerStore.getState().openLocationSheet;
    useMessengerStore.setState({ openLocationSheet: openPlace });
    const onClose = vi.fn();
    render(<DeviceSheet title="Мій телефон" onClose={onClose} />);
    fireEvent.click(await screen.findByTestId('device-act-where'));
    expect(onClose).toHaveBeenCalled();
    expect(openPlace.mock.calls[0][0].coords).toEqual({ lat: 50.45, lng: 30.52 });
    useMessengerStore.setState({ openLocationSheet: prev });
  });
});

describe('що може робити', () => {
  it('на телефоні: свої сходинки міняються, сходинки ПК лише видно', async () => {
    asPhone();
    stubs([pc], phoneLadders);
    const raised: Ladders = {
      ...phoneLadders,
      here: { ...phoneLadders.here, spheres: phoneLadders.here.spheres.map((s) => (s.sphere === 'sound' ? { ...s, level: 'ask' } : s)) },
    };
    const set = vi.spyOn(deviceApi, 'setRung').mockImplementation(async () => {
      vi.spyOn(deviceApi, 'ladder').mockResolvedValue(raised);
      return raised;
    });
    render(<DeviceSheet title="Ноутбук" peerNodeId="node-pc" onClose={() => {}} />);
    const here = await screen.findByTestId('device-ladder-here');
    expect(here.textContent).toContain('Що комп');
    const there = screen.getByTestId('device-ladder-there');
    expect(there.textContent).toContain('Змінюється на комп');
    expect(within(there).queryAllByRole('button')).toHaveLength(0);
    // Незворотне на «мовчки» — все одно з «так».
    // Незворотне вище за «так» не буває: старе «мовчки» показано як «так».
    const keyboard = there.querySelector('[data-rung="keyboard"]')?.textContent ?? '';
    expect(keyboard).toContain('Незворотне, завжди з вашого «так»');
    expect(keyboard).toContain('Робити з мого «так»');
    expect(keyboard).not.toContain('мовчки');

    fireEvent.click(within(here).getByText('Звук і голос'));
    const options = within(here).getAllByRole('radio');
    expect(options.map((o) => o.textContent)).toEqual([
      'Вимкнено', 'Лише бачити', 'Пропонувати', 'Робити з мого «так»', 'Робити й казати мені', 'Робити мовчки',
    ]);
    fireEvent.click(within(here).getByText('Робити з мого «так»'));
    await waitFor(() => expect(set).toHaveBeenCalledWith('pc', 'sound', 'ask'));
    await waitFor(() => expect(here.querySelector('[data-rung="sound"]')?.textContent).toContain('Робити з мого «так»'));
  });

  it('незворотна сфера пропонує рівні лише до «так»', async () => {
    stubs([phone], { here: { editable: true, spheres: [{ sphere: 'keyboard', title: 'Клавіатура', level: 'report', irreversible: true }] }, there: null });
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    const here = await screen.findByTestId('device-ladder-here');
    expect(here.querySelector('[data-rung="keyboard"]')?.textContent).toContain('Робити з мого «так»');
    fireEvent.click(within(here).getByText('Клавіатура'));
    const options = within(here).getAllByRole('radio');
    expect(options.map((o) => o.textContent)).toEqual(['Вимкнено', 'Лише бачити', 'Пропонувати', 'Робити з мого «так»']);
    expect(options[3].getAttribute('aria-checked')).toBe('true');
  });

  it('на ПК: сходинки телефона не вигадуються', async () => {
    stubs([phone], pcLadders);
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    const here = await screen.findByTestId('device-ladder-here');
    expect(here.textContent).toContain('Що телефон може робити з цим компʼютером');
    expect(here.textContent).toContain('Незворотне, завжди з вашого «так»');
    expect(screen.queryByTestId('device-ladder-there')).toBeNull();
  });
});

describe('журнал і стоп', () => {
  it('порожній журнал — «Поки нічого»', async () => {
    stubs([phone], pcLadders, []);
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    expect((await screen.findByTestId('device-journal')).textContent).toContain('Поки нічого');
  });

  it('рядки людські, «так» на ПК відповідає з вікна', async () => {
    stubs([phone], pcLadders, [
      { id: '9', at: new Date(NOW - 5 * 60_000).toISOString(), verb: 'pc.open', status: 'asked', what: 'Відкрий посилання', who: 'Телефон Кирила', status_title: 'Чекає на «так»', detail: 'Чекає на твоє «так» на комп\'ютері', can_answer: true },
      { id: '8', at: new Date(NOW - 60 * 60_000).toISOString(), verb: 'symbiote.ladder', status: 'ladder', what: 'Дозвіл змінено', who: 'Цей комп\'ютер', status_title: 'Дозвіл змінено', detail: 'Буфер і посилання: Робити з «так»', can_answer: false },
      { id: '7', at: new Date(NOW - 70 * 60_000).toISOString(), verb: 'pc.clipboard', status: 'done', what: 'Поклади в буфер', who: 'Телефон Кирила', status_title: 'Зроблено', detail: 'зроблено', can_answer: false },
      { id: '6', at: new Date(NOW - 80 * 60_000).toISOString(), verb: 'pc.lock', status: 'declined', what: 'Замкни екран', who: 'Телефон Кирила', status_title: 'Людина сказала «ні»', detail: 'Людина сказала «ні»', can_answer: false },
      { id: '5', at: new Date(NOW - 90 * 60_000).toISOString(), verb: 'phone.find', status: 'done', what: 'Знайти телефон', who: 'Цей комп\'ютер', status_title: 'Зроблено', detail: 'Дзвоню, доки телефон не знайдуть', can_answer: false },
    ]);
    const answer = vi.spyOn(deviceApi, 'answer').mockResolvedValue({ ok: true, said: 'Зроблено' });
    render(<DeviceSheet title="Мій телефон" onClose={() => {}} />);
    const journal = await screen.findByTestId('device-journal');
    const lines = journal.querySelectorAll('[data-journal-line]');
    const text = [...lines].map((l) => l.querySelector('span')?.textContent);
    expect(text).toEqual(['Відкриття посилання', 'Дозвіл змінено', 'Скопійовано в буфер', 'Замикання екрана', 'Телефон дзвонив']);
    expect(lines[0].textContent).toContain('Телефон Кирила · Чекає на «так»');
    expect(lines[1].textContent).toContain('Цей комп\'ютер · Буфер і посилання: Робити з «так»');
    expect(lines[3].textContent).toContain('Людина сказала «ні»');
    // Наказових назв із реєстру й службових відповідей рук на екрані немає.
    for (const raw of ['Відкрий', 'Поклади', 'Замкни', 'Знайти телефон', 'Дзвоню']) expect(journal.textContent).not.toContain(raw);
    fireEvent.click(screen.getByTestId('device-yes-9'));
    await waitFor(() => expect(answer).toHaveBeenCalledWith('dev-1', '9', true));
    expect((await screen.findByTestId('device-said')).textContent).toBe('Зроблено');
  });

  it('«Зупинити все» — одна кнопка; зупинене показує «Відновити», дії гаснуть', async () => {
    asPhone();
    stubs([pc], phoneLadders);
    const stop = vi.spyOn(deviceApi, 'stop').mockResolvedValue({ stopped: true });
    render(<DeviceSheet title="Ноутбук" peerNodeId="node-pc" onClose={() => {}} />);
    expect(await screen.findAllByTestId('device-stop-all')).toHaveLength(1);
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: true, devices: [pc] });
    fireEvent.click(screen.getByTestId('device-stop-all'));
    await waitFor(() => expect(stop).toHaveBeenCalledWith('pc', true));
    expect(await screen.findByTestId('device-stopped')).toBeTruthy();
    expect(screen.queryByTestId('device-stop-all')).toBeNull();
    expect((screen.getByTestId('device-act-pc.lock') as HTMLButtonElement).disabled).toBe(true);
    // Головна тепер одна — «Відновити».
    expect(screen.getByTestId('device-sheet').querySelectorAll('button.bg-accent')).toHaveLength(1);
    stop.mockResolvedValue({ stopped: false });
    fireEvent.click(screen.getByText('Відновити'));
    await waitFor(() => expect(stop).toHaveBeenLastCalledWith('pc', false));
  });
});

describe('помічники', () => {
  it('пристрій розмови — за сітьовою особою, інакше єдиний', () => {
    expect(deviceFor([pc], 'node-pc')).toBe(pc);
    expect(deviceFor([phone], 'node-x')).toBe(phone);
    expect(deviceFor([phone, { ...phone, id: 'dev-2' }], 'node-x')).toBeNull();
  });
});

describe('звідки відкривається', () => {
  const noop = () => {};
  const head = (over: Partial<Chat>) =>
    render(
      <MemoryRouter>
        <Header
          currentChat={{ id: 'c1', title: 'Мій телефон', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 0, messages: [], peerNodeId: 'node-ph', ...over } as unknown as Chat}
          currentUser={{ id: 'me', name: 'Я' } as unknown as UserProfile}
          onOpenSettings={noop}
          onOpenGroupDetails={noop}
          isSoundEnabled
          onToggleSound={noop}
          onToggleSearch={noop}
          isSearching={false}
        />
      </MemoryRouter>,
    );

  it('шапка розмови зі своїм пристроєм відкриває «Мій пристрій», звідти — картку контакту', async () => {
    useMessengerStore.setState({ typingStatus: {} });
    stubs([phone], pcLadders);
    head({ mine: true });
    fireEvent.click(screen.getByTitle('Профіль'));
    expect(await screen.findByTestId('device-sheet')).toBeTruthy();
    expect(screen.queryByTestId('peer-profile')).toBeNull();
    fireEvent.click(screen.getByText('Про контакт'));
    expect(screen.queryByTestId('device-sheet')).toBeNull();
    expect(screen.getByTestId('peer-profile')).toBeTruthy();
  });

  it('шапка розмови з людиною — як і була, профіль людини', () => {
    useMessengerStore.setState({ typingStatus: {} });
    head({ mine: false, title: 'Оксана' });
    fireEvent.click(screen.getByTitle('Профіль'));
    expect(screen.queryByTestId('device-sheet')).toBeNull();
    expect(screen.getByTestId('peer-profile')).toBeTruthy();
  });
});
