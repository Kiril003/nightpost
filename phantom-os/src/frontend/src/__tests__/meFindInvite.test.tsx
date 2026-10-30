/**
 * «Мій код» і «Поділитися» у профілі — той самий вміст, що й в аркуші
 * «Додати людину» (D6): у QR — сирий код телефона, у тексті «Поділитися» —
 * https-посилання і код запасом.
 *
 * Досі профіль малював у QR і слав людям `phantom://invite/…`, а аркуш
 * «Додати людину» — сирий код і https: у застосунку жили два різні QR однієї
 * людини, і посилання з профілю месенджери не підсвічували.
 *
 * Червоніти вміє: поверніть `me.link` в `inviteOf` — падають обидва тести.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { FindMe } from '../components/messenger/MeSections';
import { resetPhonePortForTests } from '../services/phonePort';

const MY_CODE = 'PH2:bWV8S3lyeWxvfHww:123abc';

vi.mock('qrcode', () => ({
  default: {
    toString: vi.fn(async () => '<svg xmlns="http://www.w3.org/2000/svg"></svg>'),
    toDataURL: vi.fn(async () => 'data:image/png;base64,AA=='),
  },
}));

let shareText: ReturnType<typeof vi.fn>;
beforeEach(() => {
  resetPhonePortForTests();
  shareText = vi.fn(() => true);
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    shareText,
    call: (_method: string, fullPath: string) => {
      const path = fullPath.split('?')[0];
      if (path === '/messenger/identity') {
        return JSON.stringify({
          ok: true,
          status: 200,
          data: { node_id: 'me', bundle: { name: 'Кирило' }, compact: MY_CODE, link: `phantom://invite/${MY_CODE}` },
        });
      }
      return JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    },
  };
});
afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('профіль: «Мій код» і «Поділитися» (D6)', () => {
  it('QR на весь екран несе сирий код, без phantom://', async () => {
    const QRCode = (await import('qrcode')).default;
    render(<FindMe handle={null} />);
    fireEvent.click(screen.getByTestId('me-code'));
    await screen.findByTestId('me-qr-full');
    await waitFor(() => expect(QRCode.toDataURL).toHaveBeenCalled());
    expect(vi.mocked(QRCode.toDataURL).mock.calls.at(-1)![0]).toBe(MY_CODE);
  });

  it('«Поділитися» — https-посилання і код запасом', async () => {
    render(<FindMe handle={null} />);
    fireEvent.click(screen.getByTestId('me-share'));
    await waitFor(() => expect(shareText).toHaveBeenCalledTimes(1));
    const [text] = shareText.mock.calls[0] as [string];
    expect(text).toContain(`https://phantom-os.dev/i/#${MY_CODE}`);
    expect(text.split(MY_CODE).length - 1).toBe(2);
    expect(text).not.toContain('phantom://');
  });
});
