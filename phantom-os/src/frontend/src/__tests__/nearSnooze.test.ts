import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      pulse: vi.fn().mockResolvedValue(null),
      peerProfiles: vi.fn().mockResolvedValue([]),
    },
  };
});

import { readPulse, useLiving, type Face } from '../services/livingPulse';
import {
  NEAR_TTL_MS as CORE_NEAR_TTL_MS,
  canSnoozeNear as coreCanSnoozeNear,
  dayKey as coreDayKey,
  nearTick as coreNearTick,
} from '../services/nearSnoozeCore';
import {
  NEAR_TTL_MS,
  armNearSnooze,
  canSnoozeNear,
  dayKey,
  disarmNearSnooze,
  nearTick,
  type NearSnoozeIO,
} from '../services/nearSnooze';
import {
  addNearReminder,
  armReminders,
  cancelReminder,
  didntMeet,
  disarmReminders,
  readLetterReminders,
  type LetterReminder,
} from '../services/letterReminders';
import { useUIStore } from '../stores/uiStore';

const NOW = new Date(2025, 9, 1, 12, 0, 0).getTime();

function buildPulse(near: 'same' | 'near' | 'far' | null) {
  return readPulse({
    near: [
      {
        peer_id: 'peerA',
        name: 'Олена',
        near,
        precision: null,
        at: new Date(NOW).toISOString(),
      },
    ],
  })!;
}

function makeItem(overrides: Partial<LetterReminder> = {}): LetterReminder {
  return {
    id: 'near_1',
    trigger: 'near',
    peer: 'peerA',
    at: NOW + NEAR_TTL_MS,
    who: 'Олена',
    text: 'Віддати ключі',
    chatId: 'chat-a',
    ...overrides,
  };
}

describe('nearSnooze', () => {
  beforeEach(() => {
    localStorage.clear();
    disarmReminders();
    useLiving.setState({ pulse: null, faces: {} });
  });

  afterEach(() => {
    disarmReminders();
    useLiving.setState({ pulse: null, faces: {} });
    vi.restoreAllMocks();
    vi.useRealTimers();
    localStorage.clear();
  });

  it('1. far -> near fires once and sets firedDay', () => {
    const pulse = buildPulse('near');
    const first = nearTick([makeItem()], { peerA: false }, pulse, {}, NOW);
    expect(first.ring).toHaveLength(1);
    expect(first.ring[0].firedDay).toBe(dayKey(NOW));
    expect(first.list[0].firedDay).toBe(dayKey(NOW));

    const second = nearTick(first.list, first.seen, pulse, {}, NOW + 20_000);
    expect(second.ring).toHaveLength(0);
  });

  it('2. near -> far -> near on the same day does not fire again', () => {
    const first = nearTick([makeItem()], { peerA: false }, buildPulse('near'), {}, NOW);
    expect(first.ring).toHaveLength(1);

    const away = nearTick(first.list, first.seen, buildPulse('far'), {}, NOW + 60_000);
    expect(away.ring).toHaveLength(0);
    expect(away.seen.peerA).toBe(false);

    const back = nearTick(away.list, away.seen, buildPulse('near'), {}, NOW + 120_000);
    expect(back.ring).toHaveLength(0);
  });

  it('3. next day fires again on far -> near', () => {
    const first = nearTick([makeItem()], { peerA: false }, buildPulse('near'), {}, NOW);
    expect(first.ring).toHaveLength(1);

    const away = nearTick(first.list, first.seen, buildPulse('far'), {}, NOW + 60_000);
    const nextDay = NOW + 86_400_000;
    const secondDay = nearTick(away.list, away.seen, buildPulse('near'), {}, nextDay);
    expect(secondDay.ring).toHaveLength(1);
    expect(secondDay.ring[0].firedDay).toBe(dayKey(nextDay));
  });

  it('4. LAN seen within freshness window counts as meeting', () => {
    const emptyPulse = readPulse({ near: [] })!;
    const faces: Record<string, Face> = {
      peerA: { lanSeenAtMs: NOW - 10_000 } as Face,
    };
    const res = nearTick([makeItem()], {}, emptyPulse, faces, NOW);
    expect(res.ring).toHaveLength(1);
  });

  it('5. far or null nearness is not a meeting', () => {
    const farRes = nearTick([makeItem()], { peerA: false }, buildPulse('far'), {}, NOW);
    expect(farRes.ring).toHaveLength(0);

    const nullRes = nearTick([makeItem()], { peerA: false }, buildPulse(null), {}, NOW);
    expect(nullRes.ring).toHaveLength(0);
  });

  it('6. silent bridge (pulse === null) rings nothing and keeps seen unchanged', () => {
    const seen = { peerA: false };
    const res = nearTick([makeItem()], seen, null, {}, NOW);
    expect(res.ring).toHaveLength(0);
    expect(res.seen).toEqual(seen);
  });

  it('7. drops expired near items and leaves time items untouched', () => {
    const expiredNear = makeItem({ id: 'near_exp', at: NOW });
    const timeItem: LetterReminder = {
      id: 'time_1',
      trigger: 'time',
      at: NOW - 5_000,
      who: 'Іван',
      text: 'Подзвонити',
      chatId: 'chat-b',
    };
    const res = nearTick([expiredNear, timeItem], {}, buildPulse('near'), {}, NOW);
    expect(res.ring).toHaveLength(0);
    expect(res.list.map((r) => r.id)).toEqual(['time_1']);
  });

  it('8. canSnoozeNear hides option when no sharing', () => {
    expect(canSnoozeNear(null, 'peerA')).toBe(false);
    expect(canSnoozeNear(readPulse({ near: [] }), 'peerA')).toBe(false);
    expect(canSnoozeNear(buildPulse('far'), null)).toBe(false);
    expect(canSnoozeNear(buildPulse('far'), 'peerA')).toBe(true);
  });

  it('9. storage: addNearReminder, armNearSnooze + useLiving.setState rings, didntMeet clears firedDay', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(NOW);
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    useLiving.setState({ pulse: buildPulse('far'), faces: {} });

    const item = addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Обговорити план', chatId: 'chat-a' },
      NOW,
    );
    const stored = readLetterReminders();
    expect(stored).toHaveLength(1);
    expect(stored[0].trigger).toBe('near');
    expect(stored[0].peer).toBe('peerA');
    expect(stored[0].at).toBe(NOW + NEAR_TTL_MS);
    expect(stored[0].firedDay).toBeUndefined();

    armReminders(NOW);
    armNearSnooze();
    vi.runAllTimers();
    expect(readLetterReminders()).toHaveLength(1);
    expect(toastSpy).not.toHaveBeenCalled();

    useLiving.setState({ pulse: buildPulse('near'), faces: {} });
    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).toHaveBeenCalledWith({
      kind: 'info',
      message: 'Нагадування · Олена: Обговорити план',
    });
    expect(readLetterReminders()[0].firedDay).toBe(dayKey(NOW));

    didntMeet(item.id);
    expect(readLetterReminders()[0].firedDay).toBeUndefined();
  });

  it('10. peer already near at add rings immediately without any later store change', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    useLiving.setState({ pulse: buildPulse('near'), faces: {} });

    addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Віддати ключі', chatId: 'chat-a' },
      NOW,
    );

    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(readLetterReminders()[0].firedDay).toBe(dayKey(NOW));
  });

  it('11. removing and re-adding an item for the same peer does not double-ring or miss', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);

    // Pure nearTick check:
    const t1 = nearTick([makeItem({ id: 'r1' })], {}, buildPulse('near'), {}, NOW);
    expect(t1.ring).toHaveLength(1);
    expect(t1.seen.peerA).toBe(true);

    // Removed while still near -> seen stays true, re-added item does not double-ring:
    const t2 = nearTick([], t1.seen, buildPulse('near'), {}, NOW + 1_000);
    expect(t2.seen.peerA).toBe(true);
    const t3 = nearTick([makeItem({ id: 'r2' })], t2.seen, buildPulse('near'), {}, NOW + 2_000);
    expect(t3.ring).toHaveLength(0);

    // Removed and peer goes far -> seen updates to false, re-added item rings on next meeting:
    const t4 = nearTick([], t3.seen, buildPulse('far'), {}, NOW + 3_000);
    expect(t4.seen.peerA).toBe(false);
    const t5 = nearTick([makeItem({ id: 'r3' })], t4.seen, buildPulse('near'), {}, NOW + 4_000);
    expect(t5.ring).toHaveLength(1);

    // Stateful check through addNearReminder / cancelReminder while another near item stays active:
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');
    useLiving.setState({ pulse: buildPulse('near'), faces: {} });
    const keepOther = addNearReminder(
      { peer: 'peerB', who: 'Іван', text: 'Інше', chatId: 'chat-b' },
      NOW,
    );
    const first = addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Перше', chatId: 'chat-a' },
      NOW,
    );
    expect(toastSpy).toHaveBeenCalledTimes(1);

    await cancelReminder({ where: 'page', id: first.id });
    const second = addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Друге', chatId: 'chat-a' },
      NOW + 1_000,
    );
    expect(toastSpy).toHaveBeenCalledTimes(1);

    useLiving.setState({ pulse: buildPulse('far'), faces: {} });
    await cancelReminder({ where: 'page', id: second.id });
    const third = addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Третє', chatId: 'chat-a' },
      NOW + 2_000,
    );
    expect(toastSpy).toHaveBeenCalledTimes(1);

    useLiving.setState({ pulse: buildPulse('near'), faces: {} });
    expect(toastSpy).toHaveBeenCalledTimes(2);

    await cancelReminder({ where: 'page', id: third.id });
    await cancelReminder({ where: 'page', id: keepOther.id });
  });

  it('12. disarm is idempotent, arming twice keeps one subscription, and ignores unrelated face updates', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    const subSpy = vi.spyOn(useLiving, 'subscribe');
    let list: LetterReminder[] = [makeItem()];
    const ringSpy = vi.fn();
    const readSpy = vi.fn(() => list);
    const io: NearSnoozeIO = {
      read: readSpy,
      write: (next) => {
        list = next;
      },
      ring: ringSpy,
    };

    armNearSnooze(io);
    armNearSnooze(io);
    expect(subSpy).toHaveBeenCalledTimes(1);

    const callsAfterArm = readSpy.mock.calls.length;
    // Updating faces without changing pulse or lanSeenAtMs must not trigger tick:
    useLiving.setState({
      faces: { peerA: {} as Face },
    });
    expect(readSpy).toHaveBeenCalledTimes(callsAfterArm);
    expect(ringSpy).not.toHaveBeenCalled();

    // Updating pulse triggers tick:
    useLiving.setState({ pulse: buildPulse('near') });
    expect(ringSpy).toHaveBeenCalledTimes(1);

    // disarmNearSnooze is idempotent:
    expect(() => {
      disarmNearSnooze();
      disarmNearSnooze();
    }).not.toThrow();

    const callsAfterDisarm = readSpy.mock.calls.length;
    useLiving.setState({ pulse: buildPulse('far') });
    expect(readSpy).toHaveBeenCalledTimes(callsAfterDisarm);
  });

  it('13. leaf module exports match nearSnooze re-exports', () => {
    expect(CORE_NEAR_TTL_MS).toBe(NEAR_TTL_MS);
    expect(coreCanSnoozeNear).toBe(canSnoozeNear);
    expect(coreDayKey).toBe(dayKey);
    expect(coreNearTick).toBe(nearTick);
  });

  it('14. cancelReminder and expiry disarm nearSnooze and release the living subscription even after peer was seen', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);

    let activeSubs = 0;
    const origSubscribe = useLiving.subscribe.bind(useLiving);
    vi.spyOn(useLiving, 'subscribe').mockImplementation((listener) => {
      activeSubs += 1;
      const unsub = origSubscribe(listener);
      return () => {
        activeSubs -= 1;
        unsub();
      };
    });

    useLiving.setState({ pulse: buildPulse('near'), faces: {} });
    const item = addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Віддати ключі', chatId: 'chat-a' },
      NOW,
    );
    expect(activeSubs).toBe(1);

    await cancelReminder({ where: 'page', id: item.id });
    expect(readLetterReminders()).toHaveLength(0);
    expect(activeSubs).toBe(0);

    // Re-add while near -> arms again and sets seen.peerA = true; when it expires on tick, it must disarm too:
    addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Друга спроба', chatId: 'chat-a' },
      NOW,
    );
    expect(activeSubs).toBe(1);

    vi.setSystemTime(NOW + NEAR_TTL_MS + 1);
    useLiving.setState({ pulse: buildPulse('near'), faces: {} });
    expect(readLetterReminders()).toHaveLength(0);
    expect(activeSubs).toBe(0);
  });

  it('15. pulse unchanged, faces[peer].lanSeenAtMs becoming fresh rings reminder once', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');

    const initialPulse = buildPulse('far');
    useLiving.setState({ pulse: initialPulse, faces: {} });

    addNearReminder(
      { peer: 'peerA', who: 'Олена', text: 'Зустріти у холі', chatId: 'chat-a' },
      NOW,
    );
    expect(toastSpy).not.toHaveBeenCalled();

    useLiving.setState({
      faces: { peerA: { lanSeenAtMs: NOW - 5_000 } as Face },
    });

    expect(useLiving.getState().pulse).toBe(initialPulse);
    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).toHaveBeenCalledWith({
      kind: 'info',
      message: 'Нагадування · Олена: Зустріти у холі',
    });
    expect(readLetterReminders()[0].firedDay).toBe(dayKey(NOW));

    useLiving.setState({
      faces: { peerA: { lanSeenAtMs: NOW } as Face },
    });
    expect(toastSpy).toHaveBeenCalledTimes(1);
  });
});
