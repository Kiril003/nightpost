import { describe, expect, it } from 'vitest';
import type { MeetupData, VenueSnap } from '../../../types/meetup';
import { parseBillData, parseMeetupData } from '../../../types/meetup';
import {
  billFromMeetup,
  buildMeetupDraft,
  groupTitle,
  meetupFromDraft,
  parseDraftArgs,
  rankInvitees,
  resolvePeople,
  resolveWhen,
} from '../draft';

const CONTACTS = [
  { id: 'olena', name: 'Олена Коваль' },
  { id: 'petro', name: 'Петро' },
  { id: 'andrii', name: 'Андрій' },
  { id: 'maria', name: 'Марія' },
  { id: 'ivan1', name: 'Іван Сидоренко' },
  { id: 'ivan2', name: 'Іван Ткач' },
];
const CAFE: VenueSnap = {
  id: 'cafe-x',
  name: 'Cafe X',
  address: 'Stodolní 1',
  lat: 49.83,
  lon: 18.28,
  rating: { mean: 5, count: 1 },
  menuVersion: 3,
  wallet: 'CafeAddr',
};
const NOW = new Date(2026, 9, 7, 20, 15); // 7 жовтня, 20:15 місцевого часу

describe('аргументи від помічника', () => {
  it('сміття не валить, а стає порожньою чернеткою', () => {
    for (const bad of [null, 5, 'x', [], { people: 'Олена' }, { people: [1, 2] }]) {
      expect(parseDraftArgs(bad)).toEqual({ people: [], place: null, at: null });
    }
    expect(parseDraftArgs('{"people":["Олену"],"place":"Cafe X","at":"19:00"}')).toEqual({
      people: ['Олену'],
      place: 'Cafe X',
      at: '19:00',
    });
  });
});

describe('імена з голосу', () => {
  it('відмінок не заважає: «Олену й Петра» → Олена, Петро', () => {
    const r = resolvePeople(['Олену', 'Петра', 'Марію', 'Андрія'], CONTACTS);
    expect(r.found.map((p) => p.id)).toEqual(['olena', 'petro', 'maria', 'andrii']);
    expect(r.missing).toEqual([]);
  });

  it('незнайоме імʼя — чесно не знайдено; два Івани — не вгадуємо', () => {
    const r = resolvePeople(['Степана', 'Івана', 'Петра', 'петро'], CONTACTS);
    expect(r.found.map((p) => p.id)).toEqual(['petro']);
    expect(r.missing).toEqual([
      { name: 'Степана', candidates: [] },
      { name: 'Івана', candidates: [{ id: 'ivan1', name: 'Іван Сидоренко' }, { id: 'ivan2', name: 'Іван Ткач' }] },
    ]);
  });

  it('повне імʼя знаходить одного з двох', () => {
    expect(resolvePeople(['Івана Ткача'], CONTACTS).found.map((p) => p.id)).toEqual(['ivan2']);
  });
});

describe('час', () => {
  it('«19:00» о 20:15 — це завтра 19:00 за місцевим часом; о 18:00 — сьогодні', () => {
    expect(resolveWhen('19:00', NOW)).toBe(new Date(2026, 9, 8, 19, 0).getTime());
    expect(resolveWhen('19:00', new Date(2026, 9, 7, 18, 0))).toBe(new Date(2026, 9, 7, 19, 0).getTime());
    expect(resolveWhen('завтра о 7 вечора', NOW)).toBe(new Date(2026, 9, 8, 19, 0).getTime());
  });

  it('мс беремо як є, якщо в майбутньому; минуле, NaN і нісенітниця — null', () => {
    const t = new Date(2026, 9, 9, 19, 30).getTime();
    expect(resolveWhen(t, NOW)).toBe(t);
    expect(resolveWhen(NOW.getTime() - 60_000, NOW)).toBeNull();
    expect(resolveWhen(Number.NaN, NOW)).toBeNull();
    expect(resolveWhen('коли зручно', NOW)).toBeNull();
    expect(resolveWhen('сьогодні о 10:00', NOW)).toBeNull();
    expect(resolveWhen(null, NOW)).toBeNull();
  });
});

describe('чернетка зустрічі', () => {
  it('голос «збери Олену й Петра в Cafe X о 19:00» → повна чернетка', () => {
    const d = buildMeetupDraft({ people: ['Олену', 'Петра'], place: 'cafe x', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW });
    expect(d.venue?.id).toBe('cafe-x');
    expect(d.at).toBe(new Date(2026, 9, 8, 19, 0).getTime());
    expect(d.invitees.map((p) => p.id)).toEqual(['olena', 'petro']);
    expect(d.missing).toEqual([]);
    expect(d.ready).toBe(true);
  });

  it('заклад не знайдено — лишаємо запит для пошуку, чернетка не готова', () => {
    const d = buildMeetupDraft({ people: ['Олену'], place: 'Кав’ярня Y', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW });
    expect(d.venue).toBeNull();
    expect(d.venueQuery).toBe('Кав’ярня Y');
    expect(d.ready).toBe(false);
  });

  it('без людей або без часу — не готова', () => {
    expect(buildMeetupDraft({ people: [], place: 'Cafe X', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW }).ready).toBe(false);
    expect(buildMeetupDraft({ people: ['Петра'], place: 'Cafe X', at: null }, { contacts: CONTACTS, venues: [CAFE], now: NOW }).ready).toBe(false);
  });

  it('готова чернетка → картка, що проходить перевірку форми; нова група — строк +24 год', () => {
    const d = buildMeetupDraft({ people: ['Олену', 'Петра'], place: 'Cafe X', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW });
    const m = meetupFromDraft(d, { id: 'mu-1', organizer: 'me', newGroup: true });
    expect(m).not.toBeNull();
    expect(m!.expiresAt).toBe(m!.at + 24 * 3600_000);
    expect(parseMeetupData(JSON.parse(JSON.stringify(m)))).toEqual(m);
    expect(meetupFromDraft(d, { id: 'mu-1', organizer: 'me', newGroup: false })!.expiresAt).toBeNull();
    expect(groupTitle(m!)).toBe('Cafe X · 19:00');
  });

  it('неготова чернетка картки не дає; організатора серед запрошених не дублюємо', () => {
    const notReady = buildMeetupDraft({ people: [], place: 'Cafe X', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW });
    expect(meetupFromDraft(notReady, { id: 'x', organizer: 'me', newGroup: true })).toBeNull();
    const d = buildMeetupDraft({ people: ['Олену', 'Петра'], place: 'Cafe X', at: '19:00' }, { contacts: CONTACTS, venues: [CAFE], now: NOW });
    expect(meetupFromDraft(d, { id: 'x', organizer: 'olena', newGroup: true })!.invitees.map((p) => p.id)).toEqual(['petro']);
  });
});

describe('рахунок із зустрічі', () => {
  const m: MeetupData = {
    v: 1,
    id: 'mu-1',
    venue: CAFE,
    at: new Date(2026, 9, 8, 19, 0).getTime(),
    organizer: 'me',
    invitees: [{ id: 'olena', name: 'Олена' }, { id: 'petro', name: 'Петро' }],
    expiresAt: null,
  };
  const menu = [
    { id: 'soup', name: 'Суп', price: '4.5' },
    { id: 'pizza', name: 'Піца', price: '10' },
  ];

  it('за замовчуванням платять організатору; люди — організатор і запрошені', () => {
    const b = billFromMeetup(m, menu, { id: 'bill-1', token: 'USDC', organizerName: 'Я' });
    expect(b.payee).toEqual({ kind: 'peer', id: 'me' });
    expect(b.people.map((p) => p.id)).toEqual(['me', 'olena', 'petro']);
    expect(b.meetupId).toBe('mu-1');
    expect(parseBillData(JSON.parse(JSON.stringify(b)))).toEqual(b);
  });

  it('«платити закладу» — лише коли в закладу є гаманець', () => {
    expect(billFromMeetup(m, menu, { id: 'b', token: 'USDC', organizerName: 'Я', payTo: 'venue' }).payee).toEqual({
      kind: 'venue',
      venueId: 'cafe-x',
      address: 'CafeAddr',
    });
    const noWallet = { ...m, venue: { ...CAFE, wallet: null } };
    expect(() => billFromMeetup(noWallet, menu, { id: 'b', token: 'USDC', organizerName: 'Я', payTo: 'venue' })).toThrow();
  });

  it('ціна, якої токен не вміщає, або поганий id страви — відмова', () => {
    expect(() => billFromMeetup(m, [{ id: 'x', name: 'x', price: '0.0000001' }], { id: 'b', token: 'USDC', organizerName: 'Я' })).toThrow();
    expect(() => billFromMeetup(m, [{ id: 'з пробілом', name: 'x', price: '1' }], { id: 'b', token: 'USDC', organizerName: 'Я' })).toThrow();
  });
});

describe('кого кликати першим', () => {
  it('ті, хто зараз ділиться місцем, — нагорі, ближчі раніше; решта за абеткою', () => {
    const where = {
      petro: { near: 'far' as const, expires_at_ms: NOW.getTime() + 60_000 },
      maria: { near: 'near' as const, expires_at_ms: NOW.getTime() + 60_000 },
      andrii: { near: 'same' as const, expires_at_ms: NOW.getTime() - 1 },
    };
    const r = rankInvitees(CONTACTS.slice(0, 4), where, NOW.getTime());
    expect(r.map((x) => [x.id, x.near])).toEqual([
      ['maria', 'near'],
      ['petro', 'far'],
      ['andrii', null],
      ['olena', null],
    ]);
  });
});
