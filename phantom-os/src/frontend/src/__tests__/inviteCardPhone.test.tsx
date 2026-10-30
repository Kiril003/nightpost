/**
 * «Ваше запрошення» на телефоні говорить те, що каже міст, — і нічого з origin.
 *
 * Дефект зі скла 04.09 02:02 (A52, aee02764): поле «Адреса вашого вузла»
 * показувало https://phantom.local — вигаданий origin перехоплювача WebView —
 * з підписом «Підставили ту, за якою ви самі відкрили цю сторінку», а рядок
 * PH2 поруч ніс 158.196.227.74. Веб брав location.origin за адресу вузла;
 * хост уже робив це правильно: compact/link/address у /messenger/identity.
 *
 * Тут міст — заглушка, і в кожному випадку картка мусить показати рівно те,
 * що прийшло з нього: адресу, коли вона є; речення про сховок, коли її нема
 * або поля немає (стара збірка хоста); рядок — link моста, а не власний
 * конверт. Origin у jsdom — http://localhost:3000 — на екрані не зʼявляється.
 *
 * Червоніти вміє (мутація 04.09): поверніть `selfAddressGuess()` у phone-гілку
 * InviteCard — падають «адреса з моста…», «без адреси…», «origin…».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import QRCode from 'qrcode';
import { InviteCard } from '../components/messenger/InviteCard';
import { messengerApi } from '../services/messengerApi';

vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn(async () => 'data:image/png;base64,AA==') },
}));

const COMPACT = 'PH2:ZTc2:483778';
const LINK = `phantom://invite/${COMPACT}`;
const ADDRESS = '1.2.3.4:443';

type Identity = Record<string, unknown>;

/** Міст телефона: /messenger/identity віддає рівно те, що передали; решта — 404. */
const setPhone = (identity: Identity | null) => {
  const w = window as unknown as { PhantomBridge?: unknown };
  if (!identity) {
    delete w.PhantomBridge;
    return;
  }
  w.PhantomBridge = {
    call: (_m: string, path: string) =>
      path.split('?')[0] === '/messenger/identity'
        ? JSON.stringify({ ok: true, status: 200, data: identity })
        : JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' }),
  };
};

const identity = (extra: Identity): Identity => ({
  node_id: 'abc123',
  bundle: { name: 'Nightpost-A52', host: '', port: 0 },
  compact: COMPACT,
  link: LINK,
  fingerprint: '123456789012345678901234',
  ...extra,
});

const openCard = async () => {
  render(<InviteCard />);
  return screen.findByTestId('invite-string');
};

const cardText = () => screen.getByTestId('invite-card').textContent ?? '';

afterEach(() => {
  setPhone(null);
  vi.restoreAllMocks();
});

describe('«Ваше запрошення» на телефоні', () => {
  // 24.09: адреса вузла людині не показується зовсім — вона вже вшита хостом
  // у рядок запрошення. Лишається одне речення-наслідок (invite-reach).

  it('адреса з моста на екран не виходить — ні полем, ні словом «адреса»', async () => {
    setPhone(identity({ address: ADDRESS }));
    await openCard();
    expect(screen.queryByTestId('invite-address')).toBeNull();
    expect(screen.queryByTestId('invite-address-none')).toBeNull();
    expect(cardText()).not.toContain(ADDRESS);
    expect(cardText()).not.toMatch(/Адреса вашого вузла|Так вас видно ззовні/);
  });

  it('на картці немає механіки доставки — ні «сховку», ні «напряму», ні адрес', async () => {
    for (const extra of [{ address: '10.0.2.15:38211', address_is_local: true }, { address: ADDRESS, address_is_local: false }, { address: null }]) {
      setPhone(identity(extra));
      const { unmount } = render(<InviteCard />);
      await screen.findByTestId('invite-string');
      expect(cardText()).not.toMatch(/сховок|напряму|ззовні|10\.0\.2\.15/);
      unmount();
    }
  });

  // D6 (08.10): у QR — сирий код моста (його читає наш же сканер), а віддається
  // https-посилання сайту і той самий код запасом. Конверта веб не складає:
  // і посилання, і код — рівно compact від моста.
  it('QR — сирий код моста; рядок — https-посилання і той самий код запасом', async () => {
    setPhone(identity({ address: ADDRESS }));
    const field = (await openCard()) as HTMLTextAreaElement;
    expect(field.value).toContain(`https://phantom-os.dev/i/#${COMPACT}`);
    expect(field.value.split(COMPACT).length - 1).toBe(2);
    expect(field.value).not.toContain('phantom://');

    fireEvent.click(screen.getByTestId('invite-qr'));
    await waitFor(() => expect(QRCode.toDataURL).toHaveBeenCalled());
    const [payload] = vi.mocked(QRCode.toDataURL).mock.calls.at(-1) as unknown as [string];
    expect(payload).toBe(COMPACT);
  });

  it('без link — compact від моста; веб не складає PHI/PH2 сам', async () => {
    setPhone(identity({ link: undefined }));
    const field = (await openCard()) as HTMLTextAreaElement;
    expect(field.value).toContain(COMPACT);
    expect(field.value.trim().split('\n').at(-1)).toBe(COMPACT);
  });

  it('«Як вас підписано» — імʼя з ідентичності, і його можна змінити тут же', async () => {
    setPhone(identity({}));
    await openCard();
    const named = screen.getByTestId('invite-name');
    expect(named.textContent).toContain('Nightpost-A52');
    expect(screen.queryByTestId('invite-name-input')).toBeNull();
    const rename = vi.spyOn(messengerApi, 'renameSelf').mockResolvedValue(identity({ bundle: { name: 'Кирило' } }) as never);
    fireEvent.click(named);
    const input = screen.getByTestId('invite-name-input') as HTMLInputElement;
    expect(input.value).toBe('Nightpost-A52');
    fireEvent.change(input, { target: { value: 'Кирило' } });
    fireEvent.submit(input.closest('form')!);
    await waitFor(() => expect(rename).toHaveBeenCalledWith('Кирило'));
    await waitFor(() => expect(screen.getByTestId('invite-name').textContent).toContain('Кирило'));
  });

  it('імені нема → «Без імені», без прикладу-імені', async () => {
    setPhone(identity({ bundle: { name: '' } }));
    await openCard();
    expect(screen.getByTestId('invite-name').textContent).toContain('Без імені');
  });

  it('origin і phantom.local на екрані не зʼявляються, підпису ПК немає', async () => {
    setPhone(identity({ address: null }));
    await openCard();
    const text = cardText();
    expect(text).not.toContain(window.location.origin);
    expect(text).not.toContain(window.location.host);
    expect(text).not.toMatch(/phantom\.local/);
    expect(text).not.toMatch(/Підставили ту, за якою ви самі відкрили/);
  });

  it('27.09: без пояснень і без рядка на видноті — імʼя, код і три рівні дії', async () => {
    setPhone(identity({}));
    await openCard();
    const text = cardText();
    expect(text).not.toMatch(/Один рядок|Так вас побачить|Показати рядок|будь-яким месенджером|Хай наведе камеру/);
    expect(screen.getByTestId('invite-string').className).toContain('hidden');
    expect(screen.getByTestId('invite-copy')).toBeTruthy();
    expect(screen.getByTestId('invite-qr')).toBeTruthy();
  });

  it('відповідь без link і compact — відмова, а не вічне «беру ключ»', async () => {
    setPhone(identity({ link: '', compact: '' }));
    render(<InviteCard />);
    expect(await screen.findByText(/Телефон не віддав запрошення/)).toBeTruthy();
    expect(screen.queryByTestId('invite-string')).toBeNull();
  });
});

describe('«Ваше запрошення» на ПК — адреса з origin лишається й редагується', () => {
  it('поле вводу з origin і підпис про нього на місці', async () => {
    setPhone(null);
    vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('вузла нема'));
    render(<InviteCard />);
    const field = screen.getByTestId('invite-address') as HTMLInputElement;
    expect(field.tagName).toBe('INPUT');
    expect(field.value).toBe(window.location.origin);
    expect(cardText()).toMatch(/Підставили ту, за якою ви самі відкрили/);
    expect(screen.queryByTestId('invite-address-none')).toBeNull();
    // Імʼя тут вводить людина — конверт PHI бере його з поля.
    expect(screen.getByPlaceholderText('Ваше імʼя')).toBeTruthy();
    expect(await screen.findByText(/Не вдалося підготувати запрошення/)).toBeTruthy();
  });
});
