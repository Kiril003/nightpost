import { describe, expect, it } from 'vitest';
import { findAsk, findMoney, findParcel, findPhone, findPlace, findTime, sentMoment, understand } from '../understand';

// Неділя, 27 вересня 2026, 19:24 за місцевим часом.
const SENT = new Date(2026, 8, 27, 19, 24);
const hm = (d: Date) => `${d.getDate()}.${d.getMonth() + 1} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

describe('час у листі', () => {
  it.each([
    ['Посилка чекає до 20:00', '27.9 20:00', true],
    ['Зустрінемось о 19:40 біля входу', '27.9 19:40', false],
    ['завтра о 10 буду вдома', '28.9 10:00', false],
    ['Давай завтра о 7 вечора', '28.9 19:00', false],
    ['в неділю о 12:30 обід у мами', '4.10 12:30', false],
    ['У понеділок об 11 нарада', '28.9 11:00', false],
    ['Приходь о 8', '27.9 20:00', false],
    ['Кіно о 9', '27.9 21:00', false],
    ['30 вересня о 18:00 збори', '30.9 18:00', false],
    ['Лікар 02.10 о 9:15', '2.10 09:15', false],
    ['meet me tomorrow at 7pm', '28.9 19:00', false],
    ['Call me at 21:30', '27.9 21:30', false],
    ['Світло дали, прибирання о 10 ранку', '28.9 10:00', false],
  ])('«%s» → %s', (text, want, deadline) => {
    const t = findTime(text, SENT);
    expect(t && hm(t.at)).toBe(want);
    expect(t?.hasClock).toBe(true);
    expect(t?.deadline).toBe(deadline);
  });

  it('день без години — лише день', () => {
    const t = findTime('в неділю поїдемо на дачу', SENT);
    expect(t?.hasClock).toBe(false);
    expect(t?.at.getDate()).toBe(4);
  });

  it.each([
    'На 5 хвилин запізнюсь',
    'Мені 25 років',
    'купи 2 хліба',
    'в 2 рази дешевше',
    'Будинок 12, квартира 40',
    'Ок',
  ])('не бачить часу в «%s»', (text) => {
    expect(findTime(text, SENT)).toBeNull();
  });

  it('«завтра» рахується від листа, а не від сьогодні', () => {
    const t = findTime('завтра о 10', new Date(2026, 8, 20, 12, 0));
    expect(hm(t!.at)).toBe('21.9 10:00');
  });
});

describe('номер посилки', () => {
  it('ТТН Нової пошти з пробілами', () => {
    expect(findParcel('ТТН 2045 0012 3456 78, забереш?')?.number).toBe('20450012345678');
  });
  it('голосове з макета: «Номер 2045 1188 3302»', () => {
    expect(findParcel('Номер 2045 1188 3302, скажеш на касі, що від Мельник')?.number).toBe('204511883302');
  });
  it('Укрпошта міжнародна', () => {
    expect(findParcel('трек RB123456789UA')?.number).toBe('RB123456789UA');
  });
  it('телефон — не посилка', () => {
    expect(findParcel('мій номер 380671234567')).toBeNull();
    expect(findParcel('Зустріч о 19:40')).toBeNull();
  });
});

describe('телефон', () => {
  it.each([
    ['Подзвони на 067 123 45 67', '+380671234567'],
    ['+38 (050) 111-22-33 це Тарас', '+380501112233'],
    ['0931234567', '+380931234567'],
    ['номер +380671234567', '+380671234567'],
  ])('«%s»', (text, want) => {
    expect(findPhone(text)?.number).toBe(want);
  });
  it('ТТН — не телефон', () => {
    expect(findPhone('ТТН 20450012345678')).toBeNull();
  });
});

describe('місце', () => {
  it.each([
    ['Чекаю на вул. Хрещатик, 22', 'вул. Хрещатик, 22'],
    ['Забери на Новій пошті №12', 'Новій пошті №12'],
    ['Я на проспект Перемоги 37 біля входу', 'проспект Перемоги 37'],
    ['відділення 145 до восьмої', 'відділення 145'],
  ])('«%s»', (text, want) => {
    expect(findPlace(text)?.text).toBe(want);
  });
  it('звичайна фраза — не місце', () => {
    expect(findPlace('Сиджу в кавʼярні навпроти пошти')).toBeNull();
  });
});

describe('гроші', () => {
  it.each([
    ['Скинь 500 грн за квитки', 500, 'UAH'],
    ['з тебе 1 250 гривень', 1250, 'UAH'],
    ['це коштувало $20', 20, 'USD'],
    ['50 євро за номер', 50, 'EUR'],
    ['₴349,50 доставка', 349.5, 'UAH'],
  ])('«%s»', (text, amount, currency) => {
    expect(findMoney(text)).toEqual({ kind: 'money', amount, currency });
  });
  it('число без валюти — не гроші', () => {
    expect(findMoney('буду за 20 хвилин')).toBeNull();
  });
});

describe('питання й прохання', () => {
  it('так/ні', () => {
    expect(findAsk('Встигнеш до 20:00?')).toEqual({ kind: 'ask', open: false, request: false });
  });
  it('відкрите питання', () => {
    expect(findAsk('Коли приїдеш?')?.open).toBe(true);
    expect(findAsk('Where are you?')?.open).toBe(true);
  });
  it('прохання без знака', () => {
    expect(findAsk('Скинь номер, будь ласка')).toEqual({ kind: 'ask', open: false, request: true });
  });
  it('звичайна розповідь — не питання', () => {
    expect(findAsk('Сиджу в кавʼярні навпроти пошти')).toBeNull();
  });
});

describe('understand', () => {
  it('цифри номера не стають часом', () => {
    const kinds = understand('Номер 2045 1188 3302, скажеш на касі', SENT).map((f) => f.kind);
    expect(kinds).toEqual(['parcel']);
  });
  it('лист про посилку: час першим', () => {
    const kinds = understand('Посилка на Новій пошті №12 до 20:00, ТТН 20450012345678', SENT).map((f) => f.kind);
    expect(kinds).toEqual(['time', 'parcel', 'place']);
  });
  it('порожнє — нічого', () => {
    expect(understand('', SENT)).toEqual([]);
    expect(understand(null, SENT)).toEqual([]);
  });
  it('момент вузла без поясу — UTC', () => {
    expect(sentMoment('2026-09-27T16:24:00')?.toISOString()).toBe('2026-09-27T16:24:00.000Z');
    expect(sentMoment('')).toBeNull();
    expect(sentMoment('nonsense')).toBeNull();
  });
});
