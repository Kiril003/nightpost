/**
 * Ворота дзвінка: одна умова, дві слухавки, і жодної мовчазної відмови.
 *
 * Вимір 16.09: умова жила приватними константами в `Header.tsx`, а ДРУГА
 * кнопка слухавки — у картці співрозмовника — не питала нічого й кидала
 * `phantom:start-call` беззастережно. Слухач у `MessengerRoot` на порожній
 * адресі робить `return` мовчки. Тобто палець по «Аудіо» в картці не робив
 * нічого й не казав нічого.
 *
 * Останній тест — дзеркало контракту: він падає, щойно якась із двох поверхонь
 * знову почне розкладати умову в себе. Два оголошення одного правила
 * розходяться на першій же правці, і тоді дві слухавки в одному застосунку
 * скажуть різне про одне й те саме.
 */
import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import {
  callGate,
  callsCarried,
  NO_CALL_ROADS,
  NO_CALL_GROUP,
  NO_CALL_NO_PEER,
} from '../services/callGate';

const ok = { roads: { inbound: true }, phoneShell: false, hasPeer: true, isGroup: false };

describe('ворота дзвінка', () => {
  it('усе на місці — кнопка активна й пояснювати нема чого', () => {
    expect(callGate(ok)).toEqual({ can: true, why: '' });
  });

  it('телефон вимагає ЯВНОГО «приймаю вхідні», ПК — лише не «ні»', () => {
    // У WebView міст каже `inbound: true`, коли слухач справді піднявся; доти
    // слати нікуди. Поля немає — на телефоні це «ще ні».
    expect(callsCarried({}, true)).toBe(false);
    expect(callsCarried({ inbound: null }, true)).toBe(false);
    expect(callsCarried({ inbound: true }, true)).toBe(true);
    // На ПК поля може не бути взагалі — стара збірка вузла. Трактувати «не
    // сказано» як «не можна» означало б зламати те, що працює.
    expect(callsCarried({}, false)).toBe(true);
    expect(callsCarried(null, false)).toBe(true);
    expect(callsCarried({ inbound: false }, false)).toBe(false);
  });

  it('немає доріг — кажемо про дороги, а не про адресу', () => {
    // Порядок причин не довільний: коли слати нікуди, рід розмови й адреса
    // вже не мають значення, і людина не мусить чинити те, що не допоможе.
    const gate = callGate({ ...ok, roads: {}, phoneShell: true, hasPeer: false, isGroup: true });
    expect(gate.can).toBe(false);
    expect(gate.why).toBe(NO_CALL_ROADS);
  });

  it('група — не «немає адреси», і навпаки', () => {
    expect(callGate({ ...ok, isGroup: true }).why).toBe(NO_CALL_GROUP);
    expect(callGate({ ...ok, hasPeer: false }).why).toBe(NO_CALL_NO_PEER);
  });

  it('жодна відмова не мовчить: у кожної є речення', () => {
    const refusals = [
      callGate({ ...ok, roads: {}, phoneShell: true }),
      callGate({ ...ok, isGroup: true }),
      callGate({ ...ok, hasPeer: false }),
    ];
    refusals.forEach((r) => {
      expect(r.can).toBe(false);
      expect(r.why.length).toBeGreaterThan(10);
    });
    // І всі три різні — інакше людина не знала б, що саме чинити.
    expect(new Set(refusals.map((r) => r.why)).size).toBe(3);
  });

  it('ДЗЕРКАЛО: обидві слухавки питають ці ворота, а не свою копію умови', () => {
    const read = (p: string) =>
      fs.readFileSync(path.resolve(__dirname, '../components/messenger', p), 'utf8');
    for (const file of ['Header.tsx', 'PeerProfileSheet.tsx']) {
      const src = read(file);
      expect(src, `${file} мусить питати спільні ворота`).toContain('callGate(');
      // Власної копії умови бути не повинно — саме вона розходиться першою.
      expect(src, `${file} розклав умову вдруге`).not.toMatch(/inbound\s*[!=]==\s*(true|false)/);
    }
  });
});
