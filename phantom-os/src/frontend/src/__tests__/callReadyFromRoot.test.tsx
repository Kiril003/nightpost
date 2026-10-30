/**
 * Досяжність «я готовий» зі справжнього входу (P0-5, 08.10).
 *
 * `callReady.test.ts` доводить, що `attach()` ставить двері й шле
 * `POST /messenger/call/ready` один раз. Тут — що це справді стається в
 * застосунку: монтується той самий `MessengerRoot`, що й на телефоні, а
 * запити йдуть крізь міст `window.PhantomBridge`, як у WebView APK. Жодних
 * підмін рушія чи `api`.
 *
 * Червоніє на коді до 08.10: корінь ставив свої двері, але телефону ніхто
 * не казав «готовий» — кадри, що чекали в `PeerCallHub`, так і чекали.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';

const seen: string[] = [];

const bridge = {
  call: (method: string, path: string) => {
    seen.push(`${method} ${path}`);
    if (path.startsWith('/messenger/ice')) {
      return JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    }
    return JSON.stringify({ ok: true, status: 200, data: {} });
  },
};

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('MessengerRoot → callEngine.attach()', () => {
  it('двері дзвінка стоять, і телефон чує «готовий» рівно один раз', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = bridge;
    useMessengerStore.setState({ chats: [], activeChatId: undefined, hydrated: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);

    const { unmount } = render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await waitFor(() => expect(seen).toContain('POST /messenger/call/ready'));
    expect(typeof (window as unknown as { __phantomCallSignal?: unknown }).__phantomCallSignal).toBe('function');

    // Перемонтування кореня (повернення на вкладку) — без другого «готовий».
    unmount();
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await new Promise((r) => setTimeout(r, 20));
    expect(seen.filter((s) => s === 'POST /messenger/call/ready')).toHaveLength(1);
  });
});
