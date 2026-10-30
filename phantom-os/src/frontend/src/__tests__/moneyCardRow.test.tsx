/**
 * 29.10: рядок «Nightpost» після картки «Гроші» казав «Лист у форматі, якого
 * ця збірка ще не знає» — міст віддавав сирий `PH-CRD:mn;…` як уривок, а веб
 * чесно не впізнавав позначку. Картка помічника — це рід, а не невідомий лист.
 */
import { describe, expect, it } from 'vitest';
import { conv, mountRoot, previewLine, useRootHooks } from './phoneFixRoot';
import { readPeerWire } from '../services/peerWire';

useRootHooks();

const b64 = (s: string) => btoa(s).replace(/=+$/, '');
const moneyWire = `PH-CRD:mn;${b64('{"v":1,"at":1,"wallet":true,"balances":[{"coin":"SOL","amount":"5"}],"week":[],"splits":[],"test":true}')}`;

describe('рядок розмови з карткою помічника', () => {
  it('дріт картки — рід, а не «невідомий формат»', () => {
    expect(readPeerWire(moneyWire)).toEqual({ kind: 'card', type: 'widget:money' });
  });

  it('стара збірка мосту дала сирий дріт: «Гроші», а не «Лист у форматі…»', async () => {
    await mountRoot([
      conv('phantom', 'Nightpost', 'phantom', { last_kind: 'text', last_snippet: moneyWire, last_at: '2026-10-29T07:03:00' }),
    ]);
    expect(previewLine('phantom')?.textContent).toBe('Гроші');
  });

  it('новий міст: рід і баланс з полів рядка — «Гроші · 5 SOL»', async () => {
    await mountRoot([
      conv('phantom', 'Nightpost', 'phantom', {
        last_kind: 'widget:money', last_snippet: null, last_at: '2026-10-29T07:03:00',
        last_card_total: 5, last_card_currency: 'SOL',
      }),
    ]);
    expect(previewLine('phantom')?.textContent).toBe('Гроші · 5 SOL');
  });

  it('день і людина від помічника теж мають слово', async () => {
    await mountRoot([
      conv('a', 'A', 'dm', { last_kind: 'widget:day', last_snippet: null, last_at: '2026-10-29T07:03:00' }),
      conv('b', 'B', 'dm', { last_kind: 'widget:glance', last_snippet: null, last_at: '2026-10-29T07:02:00' }),
    ]);
    expect(previewLine('a')?.textContent).toBe('День');
    expect(previewLine('b')?.textContent).toBe('Людина');
  });
});
