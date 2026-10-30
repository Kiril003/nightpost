import { describe, expect, it } from 'vitest';
import type { CardMarkCast } from '../../../types/meetup';
import {
  HERE_VALUE,
  KEEP_VALUE,
  dishValue,
  etaValue,
  foldCardMarkLog,
  marksToSend,
  readCardMarks,
  rsvpValue,
} from '../marks';

let n = 0;
const cast = (author: string, value: string, on: boolean, at: number): CardMarkCast => ({ author, value, on, at, id: `m${n++}` });

describe('значення позначок', () => {
  it('складаються в межах 64 байт і з закритого набору', () => {
    expect(rsvpValue('yes')).toBe('rsvp:yes');
    expect(rsvpValue('no')).toBe('rsvp:no');
    // 19:10 7 жовтня 2026 за UTC — у хвилинах Unix.
    expect(etaValue(Date.UTC(2026, 9, 7, 19, 10, 42))).toBe(`eta:${Math.floor(Date.UTC(2026, 9, 7, 19, 10) / 60000)}`);
    expect(dishValue('pizza')).toBe('dish:pizza');
    expect(() => dishValue('піца з пробілом')).toThrow();
    expect(() => dishValue('x'.repeat(37))).toThrow();
    expect(KEEP_VALUE).toBe('keep:1');
    expect(HERE_VALUE).toBe('here:1');
  });
});

describe('журнал позначок → стан (дзеркало PeerMark.foldAll)', () => {
  it('eta: перемагає останнє слово автора після зняття й нового', () => {
    const log = [
      cast('olena', 'eta:100', true, 1000),
      cast('olena', 'eta:100', false, 2000),
      cast('olena', 'eta:130', true, 2001),
      cast('petro', 'eta:120', true, 1500),
    ];
    const st = readCardMarks(foldCardMarkLog(log));
    expect(st.eta.get('olena')).toBe(130);
    expect(st.eta.get('petro')).toBe(120);
  });

  it('eta: нове значення без зняття старого однаково лишає одне — порядок за годинником автора, не за приходом', () => {
    const late = cast('olena', 'eta:130', true, 3000);
    const early = cast('olena', 'eta:100', true, 1000);
    expect(readCardMarks(foldCardMarkLog([late, early])).eta.get('olena')).toBe(130);
    expect(foldCardMarkLog([late, early])).toEqual({ 'eta:130': ['olena'] });
  });

  it('rsvp: передумав — лічиться раз', () => {
    const log = [cast('olena', 'rsvp:yes', true, 1), cast('olena', 'rsvp:no', true, 2), cast('petro', 'rsvp:yes', true, 3)];
    const st = readCardMarks(foldCardMarkLog(log));
    expect(st.rsvp.get('olena')).toBe('no');
    expect(st.going).toEqual(['petro']);
    expect(st.notGoing).toEqual(['olena']);
  });

  it('dish: багато на автора, зняття знімає лише своє', () => {
    const log = [
      cast('olena', 'dish:soup', true, 1),
      cast('olena', 'dish:tea', true, 2),
      cast('petro', 'dish:soup', true, 3),
      cast('olena', 'dish:soup', false, 4),
    ];
    const st = readCardMarks(foldCardMarkLog(log));
    expect(st.dishes.get('soup')).toEqual(['petro']);
    expect(st.dishes.get('tea')).toEqual(['olena']);
  });

  it('значення без жодного автора зникає цілком', () => {
    expect(foldCardMarkLog([cast('a', 'keep:1', true, 1), cast('a', 'keep:1', false, 2)])).toEqual({});
  });
});

describe('згорнуте мостом card_marks → стан', () => {
  it('нічого або сміття — порожній стан, а не виняток', () => {
    for (const bad of [undefined, null, 5, 'x', [], { 'rsvp:yes': 'olena' }]) {
      const st = readCardMarks(bad as never);
      expect(st.going).toEqual([]);
      expect(st.eta.size).toBe(0);
    }
  });

  it('загублене зняття: два rsvp в автора — перемагає «ні», два eta — пізніший час', () => {
    const st = readCardMarks({ 'rsvp:yes': ['olena', 'petro'], 'rsvp:no': ['olena'], 'eta:200': ['olena'], 'eta:150': ['olena'] });
    expect(st.rsvp.get('olena')).toBe('no');
    expect(st.going).toEqual(['petro']);
    expect(st.eta.get('olena')).toBe(200);
    // Порядок ключів мапи не вирішує: «ні» першим — однаково «ні».
    expect(readCardMarks({ 'rsvp:no': ['olena'], 'rsvp:yes': ['olena'] }).rsvp.get('olena')).toBe('no');
  });

  it('«може»: окремий лік; загублене зняття — «ні» над «може», «може» над «іду»', () => {
    const st = readCardMarks({ 'rsvp:yes': ['olena', 'petro', 'marta'], 'rsvp:maybe': ['petro', 'ivan'], 'rsvp:no': ['marta'] });
    expect(st.going).toEqual(['olena']);
    expect(st.maybe).toEqual(['petro', 'ivan']);
    expect(st.notGoing).toEqual(['marta']);
    expect(readCardMarks({ 'rsvp:maybe': ['olena'], 'rsvp:yes': ['olena'] }).rsvp.get('olena')).toBe('maybe');
    expect(readCardMarks({ 'rsvp:maybe': ['olena'], 'rsvp:no': ['olena'] }).rsvp.get('olena')).toBe('no');
    expect(marksToSend({ 'rsvp:yes': ['olena'] }, 'olena', rsvpValue('maybe'))).toEqual([
      { value: 'rsvp:yes', on: false },
      { value: 'rsvp:maybe', on: true },
    ]);
  });

  it('keep і here — множини авторів; незнані значення пропускаються', () => {
    const st = readCardMarks({ 'keep:1': ['petro'], 'here:1': ['olena', 'petro'], 'eta:abc': ['x'], 'mood:ok': ['y'] });
    expect([...st.keep]).toEqual(['petro']);
    expect([...st.here].sort()).toEqual(['olena', 'petro']);
    expect(st.eta.size).toBe(0);
  });
});

describe('що надіслати, щоб змінити свій стан', () => {
  it('нове eta: спершу зняти старе, потім поставити нове', () => {
    expect(marksToSend({ 'eta:100': ['olena'], 'eta:90': ['petro'] }, 'olena', 'eta:130')).toEqual([
      { value: 'eta:100', on: false },
      { value: 'eta:130', on: true },
    ]);
  });

  it('те саме значення ще раз — нічого не шле; rsvp перемикається', () => {
    expect(marksToSend({ 'rsvp:yes': ['olena'] }, 'olena', 'rsvp:yes')).toEqual([]);
    expect(marksToSend({ 'rsvp:yes': ['olena'] }, 'olena', 'rsvp:no')).toEqual([
      { value: 'rsvp:yes', on: false },
      { value: 'rsvp:no', on: true },
    ]);
  });

  it('страва — перемикач без зачіпання інших страв', () => {
    expect(marksToSend({ 'dish:soup': ['olena'] }, 'olena', 'dish:tea')).toEqual([{ value: 'dish:tea', on: true }]);
    expect(marksToSend({ 'dish:soup': ['olena'] }, 'olena', 'dish:soup')).toEqual([{ value: 'dish:soup', on: false }]);
  });
});
