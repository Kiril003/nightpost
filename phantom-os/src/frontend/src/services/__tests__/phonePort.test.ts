/**
 * Порт у телефон (MESSENGER_V2 1Б): відповідь рівно одна, на свій запит,
 * тайм-аут — чесна помилка, старий APK — синхронний `call`, байти — без base64.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PhonePort, PortTimeout, phoneRequest, resetPhonePortForTests } from '../phonePort';
import { request } from '../api';

class FakePort {
  sent: Array<string | ArrayBuffer> = [];
  private listener: ((e: { data: unknown }) => void) | null = null;
  postMessage(m: string | ArrayBuffer) {
    this.sent.push(m);
  }
  addEventListener(_t: 'message', cb: (e: { data: unknown }) => void) {
    this.listener = cb;
  }
  reply(data: string) {
    this.listener?.({ data });
  }
  lastRequest() {
    return JSON.parse(this.sent[this.sent.length - 1] as string) as { id: number; method: string; path: string };
  }
}

const g = globalThis as { PhantomPort?: unknown; PhantomBridge?: unknown };

afterEach(() => {
  delete g.PhantomPort;
  delete g.PhantomBridge;
  resetPhonePortForTests();
  vi.useRealTimers();
});

describe('PhonePort', () => {
  it('запит не блокує: відповідь приходить окремим листом на свій id', async () => {
    const fake = new FakePort();
    const port = new PhonePort(fake);
    const a = port.request('GET', '/messenger/a', null);
    const idA = fake.lastRequest().id;
    const b = port.request('GET', '/messenger/b', null);
    const idB = fake.lastRequest().id;
    expect(idA).not.toBe(idB);
    // Телефон відповідає не по порядку — кожна обіцянка бере своє.
    fake.reply(JSON.stringify({ id: idB, r: { ok: true, status: 200, data: 'b' } }));
    fake.reply(JSON.stringify({ id: idA, r: { ok: true, status: 200, data: 'a' } }));
    await expect(a).resolves.toMatchObject({ data: 'a' });
    await expect(b).resolves.toMatchObject({ data: 'b' });
  });

  it('відповідь рівно одна: дубль і чужий id не ламають нічого', async () => {
    const fake = new FakePort();
    const port = new PhonePort(fake);
    const onDone = vi.fn();
    const p = port.request('GET', '/x', null).then(onDone);
    const id = fake.lastRequest().id;
    fake.reply(JSON.stringify({ id, r: { ok: true, status: 200, data: 1 } }));
    fake.reply(JSON.stringify({ id, r: { ok: true, status: 200, data: 2 } }));
    fake.reply(JSON.stringify({ id: 999, r: { ok: true, status: 200 } }));
    fake.reply('не json');
    await p;
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone.mock.calls[0][0]).toMatchObject({ data: 1 });
  });

  it('телефон мовчить — чесна помилка після тайм-ауту, а не вічне чекання', async () => {
    vi.useFakeTimers();
    const fake = new FakePort();
    const port = new PhonePort(fake, 1000);
    const p = port.request('POST', '/messenger/call/offer', '{}');
    const caught = p.catch((e) => e);
    vi.advanceTimersByTime(1001);
    const e = await caught;
    expect(e).toBeInstanceOf(PortTimeout);
    // Запізніла відповідь після тайм-ауту — нікуди не лягає й не кидає.
    fake.reply(JSON.stringify({ id: fake.lastRequest().id, r: { ok: true, status: 200 } }));
  });

  it('api.request на тайм-ауті порту кидає ApiError 504', async () => {
    vi.useFakeTimers();
    const fake = new FakePort();
    g.PhantomPort = fake;
    // Порт спільний: власний тайм-аут не підставити, тож рахуємо стандартний.
    const p = request('GET', '/messenger/identity').catch((e) => e);
    await vi.advanceTimersByTimeAsync(60_001);
    const e = await p;
    expect(e).toMatchObject({ status: 504, code: 'timeout' });
  });

  it('байти йдуть ArrayBuffer-ом за заголовком, без base64', async () => {
    const fake = new FakePort();
    const port = new PhonePort(fake);
    const ask = port.canSendBytes();
    fake.reply(JSON.stringify({ t: 'hello', bin: true }));
    expect(await ask).toBe(true);
    const buf = new Uint8Array([1, 2, 3, 250]).buffer;
    const p = port.sendBytes('peer_a', buf);
    const head = JSON.parse(fake.sent[fake.sent.length - 2] as string);
    expect(head.bin).toEqual({ conversation_id: 'peer_a' });
    expect(fake.sent[fake.sent.length - 1]).toBe(buf);
    fake.reply(JSON.stringify({ id: head.id, r: { ok: true, status: 200, data: { blob_id: 'b1' } } }));
    await expect(p).resolves.toMatchObject({ data: { blob_id: 'b1' } });
  });

  it('телефон без байтового порту — привітання не приходить, лишається base64', async () => {
    vi.useFakeTimers();
    const port = new PhonePort(new FakePort());
    const ask = port.canSendBytes();
    vi.advanceTimersByTime(2000);
    expect(await ask).toBe(false);
  });
});

describe('вибір дороги', () => {
  it('порт є — синхронний call не кличеться взагалі', async () => {
    const fake = new FakePort();
    const call = vi.fn(() => '{}');
    g.PhantomPort = fake;
    g.PhantomBridge = { call };
    const p = request<{ n: number }>('GET', '/messenger/conversations');
    fake.reply(JSON.stringify({ id: fake.lastRequest().id, r: { ok: true, status: 200, data: { n: 3 } } }));
    await expect(p).resolves.toEqual({ n: 3 });
    expect(call).not.toHaveBeenCalled();
  });

  it('старий APK без порту — синхронний call, як і було', async () => {
    const call = vi.fn(() => JSON.stringify({ ok: true, status: 200, data: [7] }));
    g.PhantomBridge = { call };
    await expect(request('GET', '/messenger/conversations')).resolves.toEqual([7]);
    expect(call).toHaveBeenCalledWith('GET', '/messenger/conversations', null);
  });

  it('ні порту, ні моста — не телефон (ПК іде по HTTP)', () => {
    expect(phoneRequest('GET', '/x', null)).toBeNull();
  });
});
