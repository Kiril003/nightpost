import { afterEach, describe, expect, it, vi } from 'vitest';
import { whereApi, type WhereState } from '../services/peerWhere';
import { chipAction, isWhereAsk, whereChips } from '../services/whereAsk';

const NOW = 1_790_000_000_000;
const base: WhereState = { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null };

afterEach(() => {
  vi.restoreAllMocks();
});

describe('isWhereAsk', () => {
  it('розпізнає питання про поточне місце uk/en/cs', () => {
    expect(isWhereAsk('Де ти?')).toBe(true);
    expect(isWhereAsk('ти де')).toBe(true);
    expect(isWhereAsk('Де ви зараз?')).toBe(true);
    expect(isWhereAsk('ви де там')).toBe(true);
    expect(isWhereAsk('де зараз')).toBe(true);
    expect(isWhereAsk('Where are you?')).toBe(true);
    expect(isWhereAsk('where r u now')).toBe(true);
    expect(isWhereAsk('where you at')).toBe(true);
    expect(isWhereAsk('kde jsi')).toBe(true);
    expect(isWhereAsk('Kde jste?')).toBe(true);
    expect(isWhereAsk('kde teď jsi?')).toBe(true);
  });

  it('відсікає побутові питання зі словами після фрази та сторонні фрази', () => {
    expect(isWhereAsk('Where did you buy it?')).toBe(false);
    expect(isWhereAsk('де ти це купив?')).toBe(false);
    expect(isWhereAsk('kde jsi to koupil')).toBe(false);
    expect(isWhereAsk('Привіт')).toBe(false);
  });
});

describe('whereChips', () => {
  it('повертає порожній масив для null, недоступного або вже ввімкненого показу', () => {
    expect(whereChips(null)).toEqual([]);
    expect(whereChips({ ...base, eligible: false, why_not: 'no_key' })).toEqual([]);
    expect(
      whereChips({
        ...base,
        mine: { precision: 'district', until_ms: NOW + 60_000, stand: false },
      }),
    ).toEqual([]);
  });

  it('повертає 4 чипи: area60 першим за замовчуванням і exact15 першим, коли співрозмовник ділиться точно', () => {
    expect(whereChips(base).map((c) => c.id)).toEqual(['area60', 'exact15', 'meet', 'notNow']);
    expect(
      whereChips({
        ...base,
        theirs: {
          precision: 'exact',
          near: 'near',
          at_ms: NOW - 30_000,
          expires_at_ms: NOW + 60_000,
          stand: false,
        },
      }).map((c) => c.id),
    ).toEqual(['exact15', 'area60', 'meet', 'notNow']);
  });
});

describe('chipAction', () => {
  it('викликає відповідний метод whereApi, а для notNow не викликає нічого', async () => {
    const showSpy = vi.spyOn(whereApi, 'show').mockResolvedValue(base);
    const meetSpy = vi.spyOn(whereApi, 'showMeet').mockResolvedValue(base);

    await chipAction({ id: 'exact15', precision: 'exact', minutes: 15 }, 'oksana', NOW);
    expect(showSpy).toHaveBeenCalledWith('oksana', 'exact', 15);

    await chipAction({ id: 'area60', precision: 'quarter', minutes: 60 }, 'oksana', NOW);
    expect(showSpy).toHaveBeenCalledWith('oksana', 'quarter', 60);

    await chipAction({ id: 'meet', precision: 'exact' }, 'oksana', NOW);
    expect(meetSpy).toHaveBeenCalledWith('oksana', NOW + 2 * 3600_000);

    showSpy.mockClear();
    meetSpy.mockClear();
    await chipAction({ id: 'notNow' }, 'oksana', NOW);
    expect(showSpy).not.toHaveBeenCalled();
    expect(meetSpy).not.toHaveBeenCalled();
  });

  it('приймає вступ через кому і слова-заповнювачі', () => {
    expect(isWhereAsk('Слухай, де ти зараз?')).toBe(true);
    expect(isWhereAsk('де ти вже')).toBe(true);
    expect(isWhereAsk('Where are you already?')).toBe(true);
    expect(isWhereAsk('Kde teď jsi?')).toBe(true);
    expect(isWhereAsk('Слухай, де ти був учора?')).toBe(false);
  });
});
