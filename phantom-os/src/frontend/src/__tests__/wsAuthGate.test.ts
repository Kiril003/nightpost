/**
 * /ws вузла більше не приймає анонімів (4401). Клієнт не відкриває сокет без
 * токена і не стукає тим самим токеном по колу: перший 4401 — тихий refresh,
 * другий поспіль — кінець сесії.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

class FakeSocket {
  static all: FakeSocket[] = [];
  static OPEN = 1;
  static CONNECTING = 0;
  readyState = 0;
  onopen: (() => void) | null = null;
  onclose: ((ev: { code: number; reason?: string }) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string, public protocols?: string[]) {
    FakeSocket.all.push(this);
  }
  closed = false;
  close() {
    this.closed = true;
  }
  addEventListener() {}
}

async function freshClient() {
  vi.resetModules();
  const mod = await import('../services/websocket');
  return mod;
}

beforeEach(() => {
  FakeSocket.all = [];
  vi.useFakeTimers();
  vi.stubGlobal('WebSocket', FakeSocket);
  sessionStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('/ws лише з токеном', () => {
  it('без токена сокета немає і повторів теж', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect(undefined);
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(0);
  });

  it('з токеном — під-протокол, не адресний рядок', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect('tok.en.x');
    expect(FakeSocket.all).toHaveLength(1);
    expect(FakeSocket.all[0].protocols).toEqual(['phantom.bearer.v1', 'tok.en.x']);
    expect(FakeSocket.all[0].url).not.toContain('token=');
  });

  it('4401 раз — refresh-подія без перепідключення; двічі поспіль — сесії кінець', async () => {
    const seen: string[] = [];
    const onWs = () => seen.push('ws-unauthorized');
    const onAll = () => seen.push('unauthorized');
    window.addEventListener('phantom:ws-unauthorized', onWs);
    window.addEventListener('phantom:unauthorized', onAll);
    try {
      const { wsClient } = await freshClient();
      wsClient.connect('tok.en.x');
      // Вузол спершу приймає сокет, а тоді кладе слухавку 4401.
      FakeSocket.all[0].onopen?.();
      FakeSocket.all[0].onclose?.({ code: 4401 });
      vi.advanceTimersByTime(60_000);
      expect(FakeSocket.all).toHaveLength(1);
      expect(seen).toEqual(['ws-unauthorized']);

      wsClient.connect('tok.en.y');
      FakeSocket.all[1].onopen?.();
      FakeSocket.all[1].onclose?.({ code: 4401 });
      expect(seen).toEqual(['ws-unauthorized', 'unauthorized']);
    } finally {
      window.removeEventListener('phantom:ws-unauthorized', onWs);
      window.removeEventListener('phantom:unauthorized', onAll);
    }
  });

  it('сплив токен на тихому сокеті — оновлення щоразу, а не вихід', async () => {
    const seen: string[] = [];
    const onWs = () => seen.push('ws-unauthorized');
    const onAll = () => seen.push('unauthorized');
    window.addEventListener('phantom:ws-unauthorized', onWs);
    window.addEventListener('phantom:unauthorized', onAll);
    try {
      const { wsClient } = await freshClient();
      wsClient.connect('tok.en.1');
      FakeSocket.all[0].onopen?.();
      FakeSocket.all[0].onclose?.({ code: 4401, reason: 'token_expired' });
      wsClient.connect('tok.en.2');
      FakeSocket.all[1].onopen?.();
      FakeSocket.all[1].onclose?.({ code: 4401, reason: 'token_expired' });
      expect(seen).toEqual(['ws-unauthorized', 'ws-unauthorized']);
      // Оновлений токен теж відкинуто — сесії кінець.
      wsClient.connect('tok.en.3');
      FakeSocket.all[2].onopen?.();
      FakeSocket.all[2].onclose?.({ code: 4401, reason: 'unauthorized' });
      expect(seen).toEqual(['ws-unauthorized', 'ws-unauthorized', 'unauthorized']);
    } finally {
      window.removeEventListener('phantom:ws-unauthorized', onWs);
      window.removeEventListener('phantom:unauthorized', onAll);
    }
  });

  it('старий сокет після заміни мовчить: його кадри не доходять і не скидають лічильник', async () => {
    const { wsClient } = await freshClient();
    const got: unknown[] = [];
    wsClient.on('chat' as never, (m: unknown) => got.push(m));
    wsClient.connect('tok.en.a');
    const stale = FakeSocket.all[0];
    stale.readyState = 1;
    sessionStorage.clear();
    wsClient.connect('tok.en.b');
    expect(stale.onmessage).toBeNull();
    expect(stale.onclose).toBeNull();
    expect(got).toEqual([]);
  });

  it('refresh після 4401 підіймає сокет, навіть коли рядок токена той самий', async () => {
    const { wsClient } = await freshClient();
    const { useAuthStore } = await import('../stores/authStore');
    const connect = vi.spyOn(wsClient, 'connect');
    useAuthStore.setState({ token: 'same.tok.en', refreshToken: async () => true } as never);
    window.dispatchEvent(new Event('phantom:ws-unauthorized'));
    await vi.waitFor(() => expect(connect).toHaveBeenCalledWith('same.tok.en'));
  });

  it('оновлення не дісталось ядра (мережа) — не вихід: повтор і сокет', async () => {
    const { wsClient } = await freshClient();
    const { useAuthStore } = await import('../stores/authStore');
    const connect = vi.spyOn(wsClient, 'connect');
    const seen: string[] = [];
    const onAll = () => seen.push('unauthorized');
    window.addEventListener('phantom:unauthorized', onAll);
    try {
      const refresh = vi.fn().mockResolvedValueOnce('offline').mockResolvedValue(true);
      useAuthStore.setState({ token: 'tok.en.n', refreshToken: refresh } as never);
      window.dispatchEvent(new Event('phantom:ws-unauthorized'));
      await vi.advanceTimersByTimeAsync(0);
      expect(seen).toEqual([]);
      expect(connect).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(5_000);
      expect(refresh).toHaveBeenCalledTimes(2);
      expect(connect).toHaveBeenCalledWith('tok.en.n');
      expect(seen).toEqual([]);
    } finally {
      window.removeEventListener('phantom:unauthorized', onAll);
    }
  });

  it('вихід під час refresh: відповідь не повертає сесію', async () => {
    await freshClient();
    const api = await import('../services/api');
    const { useAuthStore } = await import('../stores/authStore');
    useAuthStore.setState({ token: 'old.tok.en' } as never);
    let answer!: (v: unknown) => void;
    vi.spyOn(api.authApi, 'refresh').mockReturnValueOnce(
      new Promise((r) => { answer = r; }) as never,
    );
    const pending = useAuthStore.getState().refreshToken();
    useAuthStore.getState().clearAuth();
    answer({ token: 'resurrected.tok.en', expires_at: '2099-01-01T00:00:00Z' });
    await pending;
    expect(useAuthStore.getState().token).toBeNull();
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('resurrected');
  });

  it('новий вхід під час refresh: 401 старого токена не виганяє нову сесію', async () => {
    await freshClient();
    const api = await import('../services/api');
    const { useAuthStore } = await import('../stores/authStore');
    useAuthStore.setState({ token: 'old.tok.en' } as never);
    let fail!: (e: unknown) => void;
    vi.spyOn(api.authApi, 'refresh').mockReturnValueOnce(
      new Promise((_r, j) => { fail = j; }) as never,
    );
    const pending = useAuthStore.getState().refreshToken();
    useAuthStore.setState({ token: 'new.tok.en' } as never);
    fail(new api.ApiError(401, 'TOKEN_INVALID', 'no'));
    expect(await pending).not.toBe(false);
    expect(useAuthStore.getState().token).toBe('new.tok.en');
  });

  it('повтори офлайн не множаться: другий 4401 під час очікування — той самий ланцюг', async () => {
    await freshClient();
    const { useAuthStore } = await import('../stores/authStore');
    const refresh = vi.fn().mockResolvedValue('offline');
    useAuthStore.setState({ token: 'tok.en.n', refreshToken: refresh } as never);
    window.dispatchEvent(new Event('phantom:ws-unauthorized'));
    await vi.advanceTimersByTimeAsync(0);
    window.dispatchEvent(new Event('phantom:ws-unauthorized'));
    await vi.advanceTimersByTimeAsync(0);
    expect(refresh).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(refresh).toHaveBeenCalledTimes(2);
    useAuthStore.setState({ token: null } as never);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('refreshToken: мовчання дроту — offline, 401 — відмова', async () => {
    await freshClient();
    const api = await import('../services/api');
    const { useAuthStore } = await import('../stores/authStore');
    const spy = vi.spyOn(api.authApi, 'refresh');
    spy.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await useAuthStore.getState().refreshToken()).toBe('offline');
    spy.mockRejectedValueOnce(new api.ApiError(401, 'TOKEN_INVALID', 'no'));
    expect(await useAuthStore.getState().refreshToken()).toBe(false);
  });

  it('новий токен (інша людина) — старий сокет закрито, новий з новим токеном', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect('tok.en.a');
    FakeSocket.all[0].readyState = 1;
    FakeSocket.all[0].onopen?.();
    wsClient.connect('tok.en.b');
    expect(FakeSocket.all[0].closed).toBe(true);
    expect(FakeSocket.all).toHaveLength(2);
    expect(FakeSocket.all[1].protocols).toEqual(['phantom.bearer.v1', 'tok.en.b']);
    FakeSocket.all[0].onclose?.({ code: 1000 });
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(2);
  });

  it('той самий токен — сокет лишається', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect('tok.en.a');
    FakeSocket.all[0].readyState = 1;
    wsClient.connect('tok.en.a');
    expect(FakeSocket.all).toHaveLength(1);
    expect(FakeSocket.all[0].closed).toBe(false);
  });

  it('токена більше нема (вихід) — сокет закрито і без повторів', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect('tok.en.a');
    FakeSocket.all[0].readyState = 1;
    wsClient.connect(undefined);
    expect(FakeSocket.all[0].closed).toBe(true);
    FakeSocket.all[0].onclose?.({ code: 1000 });
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it('clearAuth (вихід, phantom:unauthorized) закриває сокет одразу', async () => {
    const { wsClient } = await freshClient();
    const { useAuthStore } = await import('../stores/authStore');
    wsClient.connect('tok.en.a');
    FakeSocket.all[0].readyState = 1;
    useAuthStore.getState().clearAuth();
    expect(FakeSocket.all[0].closed).toBe(true);
    window.dispatchEvent(new Event('phantom:unauthorized'));
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it('звичайний обрив — перепідключення як і було', async () => {
    const { wsClient } = await freshClient();
    wsClient.connect('tok.en.x');
    FakeSocket.all[0].onclose?.({ code: 1006 });
    vi.advanceTimersByTime(1_500);
    expect(FakeSocket.all).toHaveLength(2);
  });
});
