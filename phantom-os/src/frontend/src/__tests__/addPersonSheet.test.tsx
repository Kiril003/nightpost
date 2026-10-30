/**
 * «Додати людину» — одне місце (08.10): мій QR великим, «Сканувати»,
 * «Надіслати посилання», @нік, і один рядок про те, що одного сканування
 * досить.
 *
 * Власник 08.10: «створення/додавання/сканування організовано неймовірно
 * тупо». Досі QR жив у «Запросити» (крок 1), камера — у «Маю запрошення»
 * (крок 2), нік — у пошуку на першому екрані (крок 3), і кожен із трьох
 * кроків мовчав про інші два.
 *
 * Тут монтується те саме, що монтує застосунок, — з мостом-підробкою, який
 * відповідає на ті самі шляхи, що й телефон.
 *
 * Міст тут двох поколінь. Нинішній (до B0) ставить `session_ready` = «ключ є»,
 * тобто true одразу після будь-якого скану, і поля `reach` не має; вузол ПК
 * лишає `session_ready` false для коду телефона назавжди. Тому обіцянку
 * «одного сканування досить» і стан «чекаємо» аркуш показує ЛИШЕ мосту, що
 * каже `reach` (B0): інакше перша — неправда, а друга — вічне очікування.
 *
 * Червоніти вміє: заберіть `source: 'camera'` — падає «камера каже мосту»;
 * заберіть рядок очікування — падає «чекаємо, поки…»; поверніть QR у
 * `useEffect` без відкладення — падає «аркуш піднімається порожнім»; поверніть
 * `isWaiting` на `!session_ready` — падає «старий міст»; заберіть «Додаю…» —
 * падає «поки міст додає».
 *
 * Друга рецензія (08.10):
 *  - B0 за брифом кладе `reach` ЛИШЕ в рядки контактів, не в /identity. Тож
 *    підробка моста «b0» тепер саме така, а `reach` у /identity — окремий
 *    перемикач (прохання до B0). На першому запуску без нього рядок «одного
 *    сканування досить» зʼявляється, щойно міст віддав перший рядок із
 *    `reach` — у відповідь на додавання;
 *  - після додавання список людей аркуша не застарівав: «Назад» вів на
 *    перший запуск, «Нова група» — до «нікого» (приберіть оновлення списку —
 *    падає «перший запуск, B0 як у брифі»);
 *  - чужий QR у кадрі не гасить камеру (приберіть `return false` — падає
 *    «QR меню»).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact } from '../services/messengerApi';
import { resetPhonePortForTests } from '../services/phonePort';
import { inviteShareText } from '../utils/messengerInvite';
import { selfAdded, __resetSelfAdded } from '../services/selfAdded';
import { isRequest } from '../services/chatFilters';
import type { Chat } from '../types/messenger';

const CODE = 'PH2:cGVlcnxNYXJ0YXx8MA:48377a';
const MY_CODE = 'PH2:bWV8S3lyeWxvfHww:123abc';
const SCANNED = CODE;
const SAFETY = '30512 88047 61290 05733';
const SHOW_SAFETY = 'Показати код безпеки';
const ENCRYPTED = 'Зашифровано. Читати можете лише ви двоє.';

function expectBareAdded() {
  const added = screen.getByTestId('person-added');
  expect(within(added).queryByTestId('reach-status')).toBeNull();
  expect(screen.queryByTestId('safety-code')).toBeNull();
  expect(screen.queryByRole('button', { name: SHOW_SAFETY })).toBeNull();
  expect(added.textContent).not.toContain(ENCRYPTED);
  expect(added.textContent).not.toContain(SAFETY);
}

vi.mock('qrcode', () => ({
  default: {
    toString: vi.fn(async () => '<svg xmlns="http://www.w3.org/2000/svg"></svg>'),
    toDataURL: vi.fn(async () => 'data:image/png;base64,AA=='),
  },
}));

const MENU_QR = 'https://cafe.example/menu';

// Камери в jsdom нема — сканер підмінено кнопкою «побачив QR», але він
// рахує свої монтування: камера не мусить перезапускатися від рендерів аркуша.
// Що відповів екран на прочитане — пишемо в `scannerAnswers`.
const scannerMounts = vi.fn();
const scannerAnswers: unknown[] = [];
vi.mock('../components/messenger/QrScanner', async () => {
  const React = await import('react');
  return {
    QrScanner: ({ onFound }: { onFound: (t: string) => boolean | void }) => {
      React.useEffect(() => {
        scannerMounts();
      }, []);
      return (
        <div data-testid="camera">
          <button type="button" onClick={() => scannerAnswers.push(onFound(SCANNED))}>
            побачив QR
          </button>
          <button type="button" onClick={() => scannerAnswers.push(onFound(MENU_QR))}>
            побачив меню
          </button>
        </div>
      );
    },
  };
});

interface Bridge {
  posted: Array<{ path: string; body: Record<string, unknown> | null }>;
  contacts: NodeContact[];
  handle: string | null;
  shareText: ReturnType<typeof vi.fn>;
  haptic: ReturnType<typeof vi.fn>;
  /** Скільки ще разів /identity відмовить (ключі ще не готові — холодний старт). */
  identityFails: number;
}

type Row = NodeContact & { reach?: 'mutual' | 'waiting' };
const contact = (over: Partial<Row> = {}): Row => ({
  id: 'ct_marta',
  peer_node_id: 'peer-marta',
  display_name: 'Марта',
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: false,
  session_ready: false,
  created_at: '2026-10-08T10:00:00',
  ...over,
});

let bridge: Bridge;
/**
 * `b0` — міст після B0, як у брифі (P0-9 п.1): `reach` несуть рядки контактів
 * (і рядок, який POST /messenger/contacts віддає). `identityReach` — ще й
 * /identity (прохання до B0: без нього на першому запуску знаку нема). Без
 * `b0` — нинішній міст: `session_ready` = «ключ є» (true одразу після скану),
 * `reach` нема.
 */
const setBridge = (
  opts: {
    handle?: string | null;
    contacts?: Row[];
    readyAfterAdd?: boolean;
    b0?: boolean;
    /** Без `reach`: що міст каже в `session_ready` (нинішній телефон — true; ПК на код телефона — false). */
    legacyReady?: boolean;
    identityFails?: number;
    identityReach?: boolean;
  } = {},
) => {
  const b0 = opts.b0 ?? true;
  bridge = {
    posted: [],
    contacts: opts.contacts ?? [],
    handle: opts.handle ?? null,
    shareText: vi.fn(() => true),
    haptic: vi.fn(),
    identityFails: opts.identityFails ?? 0,
  };
  const ok = (data: unknown) => JSON.stringify({ ok: true, status: 200, data });
  const row = (c: Row): Row => {
    if (b0) return c;
    const { reach: _drop, ...old } = c;
    return { ...old, session_ready: opts.legacyReady ?? true };
  };
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    shareText: bridge.shareText,
    haptic: bridge.haptic,
    call: (method: string, fullPath: string, body: string | null) => {
      const path = fullPath.split('?')[0];
      const parsed = body ? (JSON.parse(body) as Record<string, unknown>) : null;
      if (method !== 'GET') bridge.posted.push({ path, body: parsed });
      if (path === '/messenger/identity') {
        if (bridge.identityFails > 0) {
          bridge.identityFails -= 1;
          return JSON.stringify({ ok: false, status: 503, code: 'no_identity', detail: 'ключі ще готуються' });
        }
        return ok({
          node_id: 'me',
          bundle: { name: 'Кирило' },
          compact: MY_CODE,
          link: `phantom://invite/${MY_CODE}`,
          ...(b0 && opts.identityReach ? { reach: 'door' } : {}),
        });
      }
      if (path === '/messenger/directory/me') {
        return ok(bridge.handle ? { handle: bridge.handle, published: true } : { handle: null, published: false });
      }
      if (path === '/messenger/contacts' && method === 'GET') return ok(bridge.contacts.map(row));
      if (path === '/messenger/contacts' && method === 'POST') {
        const ready = Boolean(opts.readyAfterAdd);
        const added = contact({
          session_ready: ready,
          reach: ready ? 'mutual' : 'waiting',
          safety_number_pretty: ready ? SAFETY : '',
        });
        bridge.contacts = [added];
        return ok(row(added));
      }
      if (path === '/messenger/conversations' && method === 'GET') return ok([]);
      if (path === '/messenger/conversations' && method === 'POST') {
        return ok({ id: 'dm-marta', title: 'Марта', kind: 'dm', circle: 'all', contact_id: 'ct_marta' });
      }
      if (path.startsWith('/messenger/directory')) return ok([]);
      return JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    },
  };
};

const OKSANA = (): Row => contact({ id: 'ct_o', display_name: 'Оксана', session_ready: true, reach: 'mutual' });

let ready: ReturnType<typeof vi.fn>;
const open = () => render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={ready} />);

/** Аркуш «Додати людину» — з першого екрана, дверима «Додати людину». */
const openAdd = async () => {
  open();
  const door = await screen.findByRole('button', { name: /Додати людину/ });
  fireEvent.click(door);
  return screen.findByTestId('add-person');
};

beforeEach(() => {
  resetPhonePortForTests();
  scannerMounts.mockClear();
  scannerAnswers.length = 0;
  ready = vi.fn();
  setBridge({ contacts: [OKSANA()] });
});
afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('«Додати людину» — одне місце', () => {
  it('мій QR великим, «Сканувати», «Надіслати посилання», @нік і рядок «одного сканування досить»', async () => {
    const sheet = await openAdd();
    expect(await within(sheet).findByTestId('invite-qr-view')).toBeTruthy();
    expect(within(sheet).getByRole('button', { name: 'Сканувати' })).toBeTruthy();
    expect(within(sheet).getByRole('button', { name: 'Надіслати посилання' })).toBeTruthy();
    expect(within(sheet).getByRole('textbox', { name: '@нік людини' })).toBeTruthy();
    expect(within(sheet).getByTestId('one-scan').textContent).toMatch(/Досить одного сканування/);
    // Старих двох кроків більше нема.
    expect(screen.queryByText('Маю запрошення')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Запросити' })).toBeNull();
  });

  it('QR несе сам код, а «Надіслати посилання» — https-посилання і код запасом', async () => {
    const QRCode = (await import('qrcode')).default;
    const sheet = await openAdd();
    await waitFor(() => expect(QRCode.toString).toHaveBeenCalled());
    expect(vi.mocked(QRCode.toString).mock.calls.at(-1)![0]).toBe(MY_CODE);
    fireEvent.click(within(sheet).getByRole('button', { name: 'Надіслати посилання' }));
    expect(bridge.shareText).toHaveBeenCalledTimes(1);
    const [text] = bridge.shareText.mock.calls[0] as [string, string];
    expect(text).toContain('Nightpost');
    expect(text).toContain(`https://phantom-os.dev/i/#${MY_CODE}`);
    expect(text.split(MY_CODE).length - 1).toBe(2);
    expect(text).not.toContain('phantom://');
  });

  it('фокус у полі @ніка згортає QR і лишає перемикач «Мій QR | Сканувати» на виду', async () => {
    const sheet = await openAdd();
    expect(await within(sheet).findByTestId('invite-qr-view')).toBeTruthy();
    const field = within(sheet).getByRole('textbox', { name: '@нік людини' });

    fireEvent.focus(field);
    expect(within(sheet).queryByTestId('invite-qr-view')).toBeNull();
    expect(within(sheet).queryByTestId('one-scan')).toBeNull();
    expect(within(sheet).getByRole('button', { name: 'Мій QR' })).toBeTruthy();
    expect(within(sheet).getByRole('button', { name: 'Сканувати' })).toBeTruthy();

    fireEvent.blur(field);
    expect(await within(sheet).findByTestId('invite-qr-view')).toBeTruthy();

    fireEvent.focus(field);
    expect(within(sheet).queryByTestId('invite-qr-view')).toBeNull();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Мій QR' }));
    expect(await within(sheet).findByTestId('invite-qr-view')).toBeTruthy();
  });

  it('набраний @нік не розгортає QR при втраті фокусу — кнопка під пальцем не стрибає', async () => {
    const sheet = await openAdd();
    const field = within(sheet).getByRole('textbox', { name: '@нік людини' });
    fireEvent.focus(field);
    fireEvent.change(field, { target: { value: 'oksana' } });
    fireEvent.blur(field);
    expect(within(sheet).queryByTestId('invite-qr-view')).toBeNull();
  });

  it('ніка нема — «Отримати свій @нік» відкриває аркуш ніка', async () => {
    setBridge({ handle: null, contacts: [OKSANA()] });
    const sheet = await openAdd();
    const cta = await within(sheet).findByRole('button', { name: 'Отримати свій @нік' });
    fireEvent.click(cta);
    expect(await screen.findByTestId('handle-sheet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(await screen.findByTestId('add-person')).toBeTruthy();
  });

  it('нік є — показано @нік, без заклику', async () => {
    setBridge({ handle: 'kyrylo_kyiv', contacts: [OKSANA()] });
    const sheet = await openAdd();
    expect(await within(sheet).findByText('@kyrylo_kyiv')).toBeTruthy();
    expect(within(sheet).queryByRole('button', { name: 'Отримати свій @нік' })).toBeNull();
  });

  it('скан → людина додана одразу, міст чує source: camera; поки вона не отримала контакт — «чекаємо»', async () => {
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    const status = await screen.findByTestId('reach-status');
    expect(status.textContent).toBe('Чекаємо, поки Марта отримає ваш контакт');
    const add = bridge.posted.find((p) => p.path === '/messenger/contacts');
    expect(add?.body).toMatchObject({ compact: CODE, source: 'camera' });
    // Розмова вже є — писати можна одразу, лист чекатиме в дорозі.
    fireEvent.click(screen.getByRole('button', { name: 'Написати' }));
    expect(ready).toHaveBeenCalledWith('dm-marta');
  });

  it('людина отримала контакт — «чекаємо» зникає, без перезавантаження і без обіцянок про шифрування', async () => {
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    await screen.findByText('Чекаємо, поки Марта отримає ваш контакт');
    bridge.contacts = [contact({ session_ready: true, reach: 'mutual', safety_number_pretty: SAFETY })];
    await waitFor(() => expect(screen.queryByTestId('reach-status')).toBeNull(), { timeout: 4000 });
    expectBareAdded();
    await waitFor(() => {
      const kinds = bridge.haptic.mock.calls.map((c) => c[0]);
      expect(kinds.filter((k) => k === 'success')).toHaveLength(1);
      expect(kinds.lastIndexOf('tap')).toBeLessThan(kinds.indexOf('success'));
    });
  });

  it('скан і ключі вже зійшлись — момент: двоє назустріч, тик і сильніший тик, без рядка і без коду', async () => {
    setBridge({ readyAfterAdd: true, contacts: [OKSANA()] });
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    await screen.findByTestId('person-added');
    expectBareAdded();
    const meet = screen.getByTestId('key-meet');
    expect(meet.getAttribute('data-met')).toBe('true');
    expect(meet.querySelectorAll('[data-meet-side]')).toHaveLength(2);
    await waitFor(() => {
      const kinds = bridge.haptic.mock.calls.map((c) => c[0]);
      expect(kinds.filter((k) => k === 'success')).toHaveLength(1);
      expect(kinds.lastIndexOf('tap')).toBeLessThan(kinds.indexOf('success'));
    });
    expect(ready).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Написати' }));
    expect(ready).toHaveBeenCalledWith('dm-marta');
  });

  it('вставлене запрошення, ключі зійшлись — як і було, одразу розмова', async () => {
    setBridge({ readyAfterAdd: true, contacts: [OKSANA()] });
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Вставити запрошення' }));
    fireEvent.change(screen.getByLabelText('Запрошення'), { target: { value: `https://phantom-os.dev/i/#${CODE}` } });
    fireEvent.click(await screen.findByRole('button', { name: 'Додати' }));
    await waitFor(() => expect(ready).toHaveBeenCalledWith('dm-marta'));
    expect(screen.queryByTestId('reach-status')).toBeNull();
  });

  it('вставлене запрошення не каже мосту «камера»', async () => {
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Вставити запрошення' }));
    fireEvent.change(screen.getByLabelText('Запрошення'), { target: { value: `https://phantom-os.dev/i/#${CODE}` } });
    expect(await screen.findByTestId('incoming-invite')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Додати' }));
    await screen.findByTestId('reach-status');
    const add = bridge.posted.find((p) => p.path === '/messenger/contacts');
    expect(add?.body?.compact).toBe(CODE);
    expect(add?.body).not.toHaveProperty('source');
  });

  it('набір у полі ніка не перезапускає камеру', async () => {
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    await screen.findByTestId('camera');
    const field = within(sheet).getByRole('textbox', { name: '@нік людини' });
    for (const v of ['m', 'ma', 'mar', 'mart']) fireEvent.change(field, { target: { value: v } });
    expect(scannerMounts).toHaveBeenCalledTimes(1);
  });

  it('людей ще нема — перший екран і є «Додати людину», без зайвих дверей', async () => {
    setBridge({ contacts: [] });
    open();
    expect(await screen.findByTestId('add-person')).toBeTruthy();
    expect(screen.getByTestId('new-chat-sheet')).toBeTruthy();
  });

  it('аркуш піднімається порожнім: ключ і буфер обміну — лише після першого кадру', async () => {
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      frames.push(cb);
      return frames.length;
    });
    const identity = vi.spyOn(messengerApi, 'identity');
    const clip = vi.fn(() => '');
    setBridge({ contacts: [] });
    (window as unknown as { PhantomBridge: Record<string, unknown> }).PhantomBridge.getFromClipboard = clip;
    open();
    await screen.findByTestId('add-person');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    expect(identity).not.toHaveBeenCalled();
    expect(clip).not.toHaveBeenCalled();
    await act(async () => {
      while (frames.length) frames.shift()!(performance.now());
      await new Promise((r) => setTimeout(r, 30));
    });
    await waitFor(() => expect(identity).toHaveBeenCalledTimes(1));
    expect(clip).toHaveBeenCalled();
  });
});

describe('міст без `reach` — без обіцянок, яких він не тримає', () => {
  it('нинішній міст (session_ready = «ключ є»): рядка «одного сканування досить» нема', async () => {
    setBridge({ b0: false, contacts: [OKSANA()] });
    const sheet = await openAdd();
    await within(sheet).findByRole('img', { name: 'Мій QR для сканування' });
    expect(within(sheet).queryByTestId('one-scan')).toBeNull();
  });

  it('міст із `reach` і першим запуском (людей ще нема) — рядок є: про це каже /identity', async () => {
    setBridge({ contacts: [], identityReach: true });
    open();
    const sheet = await screen.findByTestId('add-person');
    expect((await within(sheet).findByTestId('one-scan')).textContent).toMatch(/Досить одного сканування/);
  });

  it('вузол без `reach` і з session_ready=false (ПК на код телефона) — одразу розмова, а не вічне «чекаємо»', async () => {
    setBridge({ b0: false, legacyReady: false, contacts: [OKSANA()] });
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    await waitFor(() => expect(ready).toHaveBeenCalledWith('dm-marta'));
    expect(screen.queryByTestId('reach-status')).toBeNull();
  });
});

describe('перший запуск і B0 як у брифі (reach лише в рядках)', () => {
  it('до першого скану обіцянки нема; після — людина в списку аркуша, «Назад» не на перший запуск, рядок є', async () => {
    setBridge({ contacts: [] });
    open();
    const first = await screen.findByTestId('add-person');
    await within(first).findByRole('img', { name: 'Мій QR для сканування' });
    // Знаку від моста ще нема — і обіцянки нема.
    expect(within(first).queryByTestId('one-scan')).toBeNull();
    fireEvent.click(within(first).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    expect((await screen.findByTestId('reach-status')).textContent).toBe('Чекаємо, поки Марта отримає ваш контакт');

    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    // Людина вже є: це «Додати людину» з «Назад», а не перший запуск.
    const again = await screen.findByTestId('add-person-sheet');
    expect(within(again).getByTestId('one-scan').textContent).toMatch(/Досить одного сканування/);
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    const home = await screen.findByTestId('new-chat-sheet');
    expect(within(home).getByText('Марта')).toBeTruthy();
    fireEvent.click(within(home).getByRole('button', { name: 'Нова група' }));
    const group = await screen.findByTestId('new-group-sheet');
    expect(within(group).getByText('Марта')).toBeTruthy();
    expect(within(group).queryByText('У списку ще нікого немає.')).toBeNull();
  });
});

describe('чужий QR у кадрі', () => {
  it('QR меню — камера світить далі з підказкою, нікого не додано; тоді QR людини — додано', async () => {
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив меню'));
    expect(scannerAnswers).toEqual([false]);
    expect(within(sheet).getByTestId('camera')).toBeTruthy();
    expect(within(sheet).getByTestId('scan-note').textContent).toBe('Це не схоже на запрошення');
    expect(bridge.posted.some((p) => p.path === '/messenger/contacts')).toBe(false);
    expect(scannerMounts).toHaveBeenCalledTimes(1);

    fireEvent.click(within(sheet).getByText('побачив QR'));
    expect(scannerAnswers.at(-1)).toBe(true);
    expect(await screen.findByTestId('reach-status')).toBeTruthy();
    expect(screen.queryByTestId('scan-note')).toBeNull();
  });
});

describe('холодний міст: аркуш не мовчить і не глухне', () => {
  it('скан → поки міст додає, на місці камери «Додаю…», а не мій QR', async () => {
    let release: (rows: never[]) => void = () => {};
    const slow = new Promise<never[]>((r) => {
      release = r;
    });
    vi.spyOn(messengerApi, 'listConversations').mockImplementation(() => slow);
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    const adding = await within(sheet).findByTestId('add-person-adding');
    expect(adding.textContent).toMatch(/Додаю…/);
    expect(within(sheet).queryByTestId('camera')).toBeNull();
    expect(within(sheet).queryByTestId('invite-qr-view')).toBeNull();
    // Перемикач не пускає назад у камеру, поки людину ще додаємо.
    expect((within(sheet).getByRole('button', { name: 'Сканувати' }) as HTMLButtonElement).disabled).toBe(true);
    await act(async () => {
      release([]);
    });
    expect(await screen.findByTestId('reach-status')).toBeTruthy();
    expect(screen.queryByTestId('add-person-adding')).toBeNull();
  });

  it('аркуш закрили посеред додавання — розмова однаково стає в список', async () => {
    let release: (rows: never[]) => void = () => {};
    const slow = new Promise<never[]>((r) => {
      release = r;
    });
    vi.spyOn(messengerApi, 'listConversations').mockImplementation(() => slow);
    const refresh = vi.fn(async () => {});
    useMessengerStore.setState({ refreshConversations: refresh });
    const { rerender } = render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={ready} />);
    fireEvent.click(await screen.findByRole('button', { name: /Додати людину/ }));
    const sheet = await screen.findByTestId('add-person');
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    await within(sheet).findByTestId('add-person-adding');
    rerender(<CreateChatModal isOpen={false} onClose={() => {}} onConversationReady={ready} />);
    await act(async () => {
      release([]);
    });
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(ready).not.toHaveBeenCalled();
  });

  it('/identity відмовив (ключі ще готуються) — «Спробувати ще раз» без закриття аркуша', async () => {
    setBridge({ contacts: [OKSANA()], identityFails: 1 });
    const sheet = await openAdd();
    expect(await within(sheet).findByText('Телефон не віддав запрошення — спробуйте ще раз')).toBeTruthy();
    expect((within(sheet).getByRole('button', { name: 'Надіслати посилання' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(sheet).getByRole('button', { name: 'Спробувати ще раз' }));
    expect(await within(sheet).findByRole('img', { name: 'Мій QR для сканування' })).toBeTruthy();
    await waitFor(() =>
      expect((within(sheet).getByRole('button', { name: 'Надіслати посилання' }) as HTMLButtonElement).disabled).toBe(false),
    );
  });

  it('у буфері — моє ж запрошення (щойно скопійоване): «Відкрити» не пропонуємо; чуже — пропонуємо', async () => {
    (window as unknown as { PhantomBridge: Record<string, unknown> }).PhantomBridge.getFromClipboard = () =>
      inviteShareText(MY_CODE, 'uk');
    const sheet = await openAdd();
    await within(sheet).findByRole('img', { name: 'Мій QR для сканування' });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    expect(screen.queryByTestId('clipboard-invite')).toBeNull();

    cleanup();
    setBridge({ contacts: [OKSANA()] });
    (window as unknown as { PhantomBridge: Record<string, unknown> }).PhantomBridge.getFromClipboard = () =>
      inviteShareText(CODE, 'uk');
    const again = await openAdd();
    expect(await within(again).findByTestId('clipboard-invite')).toBeTruthy();
  });
});

describe('core-list: додав сам — не запит; один бурштин', () => {
  it('C3: людина, додана сканом, позначена як моя — у «Нові люди» не падає', async () => {
    __resetSelfAdded();
    const sheet = await openAdd();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Сканувати' }));
    fireEvent.click(await screen.findByText('побачив QR'));
    await screen.findByTestId('reach-status');
    expect(selfAdded.has('dm-marta')).toBe(true);
    const dm = { id: 'dm-marta', title: 'Марта', type: 'dm', circle: 'all', unreadCount: 1, lastSelfAt: null } as unknown as Chat;
    expect(isRequest(dm)).toBe(false);
    expect(isRequest({ ...dm, id: 'dm-stranger' })).toBe(true);
  });

  it('C14: у буфері чуже запрошення — бурштинова лише «Відкрити», «Надіслати посилання» тиха', async () => {
    (window as unknown as { PhantomBridge: Record<string, unknown> }).PhantomBridge.getFromClipboard = () =>
      inviteShareText(CODE, 'uk');
    const sheet = await openAdd();
    await within(sheet).findByTestId('clipboard-invite');
    const amber = [...sheet.querySelectorAll('button')].filter((b) => /(^|\s)bg-accent(\s|$)/.test(b.className));
    expect(amber).toHaveLength(1);
    expect(within(sheet).getByTestId('clipboard-invite').contains(amber[0])).toBe(true);
    expect(within(sheet).getByTestId('share-invite').className).not.toMatch(/(^|\s)bg-accent(\s|$)/);
  });

  it('C14: без запрошення в буфері «Надіслати посилання» — єдиний бурштин', async () => {
    const sheet = await openAdd();
    await within(sheet).findByRole('img', { name: 'Мій QR для сканування' });
    const amber = [...sheet.querySelectorAll('button')].filter((b) => /(^|\s)bg-accent(\s|$)/.test(b.className));
    expect(amber.map((b) => b.getAttribute('data-testid'))).toEqual(['share-invite']);
  });

  it('C13: перемикач «Мій QR | Сканувати» без тіні', async () => {
    const sheet = await openAdd();
    const seg = within(sheet).getByRole('button', { name: 'Сканувати' }).parentElement!;
    for (const b of seg.querySelectorAll('button')) expect(b.className).not.toMatch(/shadow/);
  });
});

describe('досяжність: «+» у списку розмов → «Додати людину»', () => {
  it('з MessengerRoot, тим самим дотиком, що й палець власника', async () => {
    Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    const plus = await screen.findAllByRole('button', { name: 'Створити новий діалог' });
    fireEvent.click(plus[0]);
    const home = await screen.findByTestId('new-chat-sheet', {}, { timeout: 4000 });
    fireEvent.click(await within(home).findByRole('button', { name: /Додати людину/ }));
    const sheet = await screen.findByTestId('add-person');
    expect(within(sheet).getByRole('button', { name: 'Сканувати' })).toBeTruthy();
  });
});

describe('олівець → одразу «Новий чат», як у Telegram (власник 29.10: ті самі пункти двічі)', () => {
  it('один дотик: аркуш «Новий чат», без проміжного; «Нова група» і «Новий канал» — по одному разу, згори, над пошуком', async () => {
    Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    const pencil = await screen.findAllByRole('button', { name: 'Створити новий діалог' });
    fireEvent.click(pencil[0]);
    expect(screen.queryByTestId('today-compose-sheet')).toBeNull();
    const home = await screen.findByTestId('new-chat-sheet', {}, { timeout: 4000 });
    await within(home).findByText('Оксана');
    expect(screen.queryByTestId('today-compose-sheet')).toBeNull();
    expect(within(home).getAllByRole('button', { name: /Нова група/ })).toHaveLength(1);
    expect(within(home).getAllByRole('button', { name: /Новий канал/ })).toHaveLength(1);
    const group = within(home).getByTestId('door-group');
    const search = within(home).getByRole('textbox');
    expect(group.compareDocumentPosition(search) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe('«Сканувати QR» — перший рядок «Нового чату» на телефоні (скан пішов із шапки списку)', () => {
  it('олівець → «Новий чат» → перший рядок відкриває рідний сканер', async () => {
    Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
    const navigate = vi.fn(() => true);
    const w = window as unknown as { PhantomBridge?: unknown };
    w.PhantomBridge = { navigate };
    try {
      useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
      vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
      render(
        <MemoryRouter>
          <MessengerRoot />
        </MemoryRouter>,
      );
      const pencil = await screen.findAllByRole('button', { name: 'Створити новий діалог' });
      fireEvent.click(pencil[0]);
      const home = await screen.findByTestId('new-chat-sheet', {}, { timeout: 4000 });
      const scan = await within(home).findByTestId('door-scan');
      expect(scan.textContent).toContain('Сканувати QR');
      const group = within(home).getByTestId('door-group');
      expect(scan.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      fireEvent.click(scan);
      expect(navigate).toHaveBeenCalledWith('scan', '{}');
    } finally {
      delete w.PhantomBridge;
    }
  });
});

describe('старт «add»: двері «Додати людину» поза аркушем відкривають крок одразу', () => {
  it('createChatStart = add → аркуш «Додати людину», без «Нового чату»', async () => {
    useMessengerStore.setState({ createChatStart: 'add' });
    open();
    const sheet = await screen.findByTestId('add-person-sheet');
    expect(within(sheet).getByRole('button', { name: 'Сканувати' })).toBeTruthy();
    expect(screen.queryByTestId('new-chat-sheet')).toBeNull();
    useMessengerStore.setState({ createChatStart: null });
  });
});
