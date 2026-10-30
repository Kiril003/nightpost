/**
 * «Додати свій пристрій» — один скан, без PIN і «коду».
 *
 * Власник 28.09: «дуже бісить нинішній спаринг… якісь коди тощо». На ПК —
 * великий QR, на телефоні — камера; цифри лише як запас, коли камери немає.
 * І сторож: на головних шляхах (пошук, «Нова розмова», запрошення, додати
 * пристрій) нема слів «код», «PIN», «ключ», «відбиток», «вузол».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, act } from '@testing-library/react';
import { AddOwnDevice } from '../components/messenger/AddOwnDevice';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { messengerApi } from '../services/messengerApi';
import { pairApi } from '../services/api';
import { useMessengerStore } from '../stores/messengerStore';
import { uk as l1Uk, en as l1En } from '../i18n/messages/parts/l1';
import { cs as l1Cs } from '../i18n/messages/parts/cs/l1';
import type { Chat, UserProfile } from '../types/messenger';

const SCANNED = '{"v":2,"pair_id":"p-12345678","server_pub":"AAAA","exp":4102444800,"nonce":"bm9uY2U="}';

// Камери в jsdom немає: підмінюємо сам сканер — кнопкою «побачив QR» і «камери нема».
vi.mock('../components/messenger/QrScanner', () => ({
  QrScanner: ({ onFound, onUnavailable }: { onFound: (t: string) => void; onUnavailable?: () => void }) => (
    <div data-testid="camera">
      <button type="button" onClick={() => onFound(SCANNED)}>
        побачив QR
      </button>
      <button type="button" onClick={() => onUnavailable?.()}>
        камери нема
      </button>
    </div>
  ),
}));

/** Слова, яких людина не має бачити на головних шляхах. */
const TECH = /(^|[^а-яіїєґ])(код|коду|кодом|pin|ключ|ключа|ключем|відбит\S*|вузол|вузла|вузлом)([^а-яіїєґ]|$)/i;
const techWords = (root: HTMLElement = document.body): string | null => {
  const m = (root.textContent ?? '').match(TECH);
  return m ? m[0].trim() : null;
};

const setPhone = (on: boolean) => {
  if (on) (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{"ok":true,"status":200,"data":[]}' };
  else delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
};

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  useMessengerStore.setState({ refreshConversations: vi.fn().mockResolvedValue(undefined) });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setPhone(false);
  useMessengerStore.setState(prev, true);
});

describe('ПК: великий QR, без цифр на першому екрані', () => {
  it('QR одразу; цифри — лише за запитом; спарювання закриває аркуш словом «Готово»', async () => {
    vi.spyOn(pairApi, 'init').mockResolvedValue({
      pair_id: 'p-1',
      expires_in_seconds: 60,
      qr: {} as never,
      qr_svg_data_url: 'data:image/svg+xml;base64,AAAA',
      pin: '48213907',
    });
    const status = vi
      .spyOn(pairApi, 'status')
      .mockResolvedValueOnce({ pair_id: 'p-1', status: 'pending', device_id: null })
      .mockResolvedValue({ pair_id: 'p-1', status: 'closed', device_id: 'd-1' });
    render(<AddOwnDevice />);
    fireEvent.click(screen.getByRole('button', { name: 'Додати свій пристрій' }));

    expect(await screen.findByAltText('QR для телефона')).toBeTruthy();
    const sheet = screen.getByTestId('add-own-device');
    expect(sheet.textContent).not.toContain('48213907');
    expect(techWords(sheet)).toBeNull();

    fireEvent.click(screen.getByText('Не працює камера?'));
    expect(sheet.textContent).toContain('48213907');

    await waitFor(() => expect(screen.getByText(/телефон тепер серед ваших пристроїв/)).toBeTruthy(), {
      timeout: 6000,
    });
    expect(status).toHaveBeenCalledWith('p-1');
    expect(useMessengerStore.getState().refreshConversations).toHaveBeenCalled();
  }, 10000);
});

describe('телефон: один скан — і все', () => {
  it('камера → скан → міст спарюється → «Готово»; ні PIN, ні цифр', async () => {
    setPhone(true);
    const pair = vi.spyOn(messengerApi, 'pairOwnDevice').mockResolvedValue({ state: 'working' });
    vi.spyOn(messengerApi, 'ownPairState').mockResolvedValue({ state: 'done' });
    render(<AddOwnDevice />);
    fireEvent.click(screen.getByRole('button', { name: 'Додати свій пристрій' }));

    expect(screen.getByTestId('camera')).toBeTruthy();
    expect(screen.queryByText('Ввести цифри з екрана')).toBeNull();
    expect(techWords(screen.getByTestId('add-own-device'))).toBeNull();

    fireEvent.click(screen.getByText('побачив QR'));
    await waitFor(() => expect(screen.getByText(/компʼютер тепер серед ваших пристроїв/)).toBeTruthy(), {
      timeout: 4000,
    });
    expect(pair).toHaveBeenCalledWith(SCANNED);
  });

  it('камери нема — тоді й лише тоді цифри з екрана', async () => {
    setPhone(true);
    const pair = vi.spyOn(messengerApi, 'pairOwnDevice').mockResolvedValue({ state: 'done' });
    render(<AddOwnDevice />);
    fireEvent.click(screen.getByRole('button', { name: 'Додати свій пристрій' }));
    fireEvent.click(screen.getByText('камери нема'));
    fireEvent.click(screen.getByText('Ввести цифри з екрана'));
    fireEvent.change(screen.getByLabelText('Цифри з екрана компʼютера'), { target: { value: '4821 3907' } });
    fireEvent.click(screen.getByText('Додати'));
    await waitFor(() => expect(pair).toHaveBeenCalledWith('48213907'));
  });

  it('відмова — людське речення й «Ще раз»', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'pairOwnDevice').mockResolvedValue({ state: 'failed', reason: 'not_found' });
    render(<AddOwnDevice />);
    fireEvent.click(screen.getByRole('button', { name: 'Додати свій пристрій' }));
    fireEvent.click(screen.getByText('побачив QR'));
    expect(await screen.findByText(/Компʼютер не знайдено/)).toBeTruthy();
    fireEvent.click(screen.getByText('Ще раз'));
    expect(screen.getByTestId('camera')).toBeTruthy();
  });

  it('прострочений QR просить новий код, а не той самий QR (uk / en / cs)', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'pairOwnDevice').mockResolvedValue({ state: 'failed', reason: 'expired' });
    render(<AddOwnDevice />);
    fireEvent.click(screen.getByRole('button', { name: 'Додати свій пристрій' }));
    fireEvent.click(screen.getByText('побачив QR'));
    expect(await screen.findByText('Код застарів — наведіть камеру на новий')).toBeTruthy();
    expect(l1En['addOwnDevice.failExpired']).toBe('That code expired. Point the camera at the new one');
    expect(l1Uk['addOwnDevice.failExpired']).toBe('Код застарів — наведіть камеру на новий');
    expect(l1Cs['addOwnDevice.failExpired']).toBe('Kód vypršel. Namiřte kameru na nový');
    for (const dict of [l1En, l1Uk, l1Cs]) {
      for (const [k, v] of Object.entries(dict)) {
        if (k.startsWith('addOwnDevice.')) {
          expect(v).not.toMatch(/same QR|at it again|тим самим|на něj kameru znovu/i);
        }
      }
    }
  });
});

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: id,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

describe('досяжність і сторож слів', () => {
  it('«Мої пристрої» у списку ведуть до «Додати свій пристрій»', () => {
    render(
      <PhoneChatList
        chats={[chat('o', 'Оксана'), chat('pc', 'Мій ПК', { mine: true })]}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    fireEvent.click(document.querySelector('[data-folder-row="devices"]')!);
    expect(screen.getByRole('button', { name: 'Додати свій пристрій' })).toBeTruthy();
  });

  it('пошук із людьми за ніком — без «коду», «ключа», «вузла»', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([
      { id: 'u1', username: 'marta_kyiv', display_name: 'Марта', role: '', is_online: false, compact: 'PH2:ZTc2:1' },
    ]);
    render(
      <PhoneChatList
        chats={[chat('o', 'Оксана')]}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    fireEvent.click(document.querySelector('[data-search-pill]') as HTMLElement);
    fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: '@marta_kyiv' } });
    await act(async () => new Promise((r) => setTimeout(r, 600)));
    expect(document.querySelector('[data-search-network]')).toBeTruthy();
    expect(techWords()).toBeNull();
  });

  it('«Нова розмова» на телефоні: перший екран без технічних слів', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />);
    await act(async () => new Promise((r) => setTimeout(r, 20)));
    expect(techWords(screen.getByTestId('new-chat-sheet'))).toBeNull();
  });

  it('сторож уміє почервоніти', () => {
    const el = document.createElement('div');
    el.textContent = 'Введіть PIN';
    expect(techWords(el)).toBe('PIN');
    el.textContent = 'Відбиток ключа';
    expect(techWords(el)).not.toBeNull();
    el.textContent = 'Кодова фраза не рахується, як і «кожен»';
    expect(techWords(el)).toBeNull();
  });
});
