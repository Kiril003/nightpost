/**
 * Сторож межі MQTT-пакета.
 *
 * `handlePublishPacket` рахував `remainingLength` (щоб просунути курсор через
 * заголовок змінної довжини) — і викидав саме значення, а корисне
 * навантаження брав як `data.subarray(index)`, тобто все до кінця кадру.
 *
 * Поки брокер шле один пакет на кадр, це збігається, і жоден тест нічого не
 * помічає. Але MQTT поверх WebSocket одного пакета на кадр НЕ гарантує:
 * склеєні пакети законні. Тоді в JSON.parse летить хвіст сусіднього пакета,
 * парсер падає, `catch` унизу ковтає помилку — і лист просто не приходить.
 * Мовчазна втрата листа: рівно той клас, за яким ми полюємо.
 *
 * Мутація, що має червоніти: повернути `data.subarray(index)` без межі.
 */

import { describe, it, expect, vi } from 'vitest';
import { globalP2PMesh } from '../services/globalP2PMesh';

/** Кодує довжину за правилом MQTT (7 біт на байт, старший — прапорець «ще»). */
const encodeRemainingLength = (n: number): number[] => {
  const out: number[] = [];
  do {
    let byte = n % 128;
    n = Math.floor(n / 128);
    if (n > 0) byte |= 128;
    out.push(byte);
  } while (n > 0);
  return out;
};

/** Один PUBLISH-пакет: fixed header + топік + корисне навантаження. */
const publishPacket = (topic: string, payload: unknown): number[] => {
  const topicBytes = [...new TextEncoder().encode(topic)];
  const payloadBytes = [...new TextEncoder().encode(JSON.stringify(payload))];
  const body = [
    (topicBytes.length >> 8) & 0xff,
    topicBytes.length & 0xff,
    ...topicBytes,
    ...payloadBytes,
  ];
  // 0x30 = PUBLISH, QoS 0 (тоді ідентифікатора пакета немає).
  return [0x30, ...encodeRemainingLength(body.length), ...body];
};

const packet = (handle: string) => ({
  type: 'chat:message',
  senderId: `id_${handle}`,
  senderHandle: handle,
  senderName: handle,
  payload: { text: `лист від ${handle}` },
});

describe('склеєні MQTT-пакети не зʼїдають лист', () => {
  it('перший пакет читається, коли за ним у кадрі лежить другий', () => {
    const seen = vi.fn();
    const off = globalP2PMesh.onMessage(seen);

    // Два PUBLISH в одному кадрі — те, що брокер має право надіслати.
    const frame = new Uint8Array([
      ...publishPacket('phantom/mesh', packet('marta')),
      ...publishPacket('phantom/mesh', packet('petro')),
    ]);

    (globalP2PMesh as unknown as {
      handlePublishPacket: (d: Uint8Array) => void;
    }).handlePublishPacket(frame);

    off();
    expect(
      seen,
      'лист із першого пакета не дійшов — межу пакета знову не враховано',
    ).toHaveBeenCalledTimes(1);
    expect(seen.mock.calls[0][0].senderHandle).toBe('marta');
  });

  it('одиночний пакет у кадрі читається так само (регресії немає)', () => {
    const seen = vi.fn();
    const off = globalP2PMesh.onMessage(seen);

    const frame = new Uint8Array(publishPacket('phantom/mesh', packet('solo')));
    (globalP2PMesh as unknown as {
      handlePublishPacket: (d: Uint8Array) => void;
    }).handlePublishPacket(frame);

    off();
    expect(seen).toHaveBeenCalledTimes(1);
    expect(seen.mock.calls[0][0].senderHandle).toBe('solo');
  });
});
