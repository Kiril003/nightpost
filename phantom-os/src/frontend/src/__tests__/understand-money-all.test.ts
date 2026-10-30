import { describe, expect, it } from 'vitest';
import { findAllMoney, findMoney, understand } from '../services/understand';
import { uk } from '../i18n/messages/uk';
import { en } from '../i18n/messages/en';
import { cs } from '../i18n/messages/cs';

describe('findAllMoney', () => {
  it('returns multiple amounts in text order with raw text and index', () => {
    const hits = findAllMoney('скинь $23 і ще 150 грн');
    expect(hits).toEqual([
      { kind: 'money', currency: 'USD', amount: 23, raw: '$23', index: 6 },
      { kind: 'money', currency: 'UAH', amount: 150, raw: '150 грн', index: 15 },
    ]);
  });

  it('skips non-positive amounts and returns empty array when nothing matches', () => {
    expect(findAllMoney('20 € та 0 грн')).toEqual([
      { kind: 'money', currency: 'EUR', amount: 20, raw: '20 €', index: 0 },
    ]);
    expect(findAllMoney('нічого')).toEqual([]);
  });

  it('resets regex state across repeated calls on the same input', () => {
    const text = 'кава $4.50 і обід 12 €';
    const first = findAllMoney(text);
    const second = findAllMoney(text);
    expect(first).toEqual(second);
    expect(first).toHaveLength(2);
  });

  it('keeps findMoney and understand output unchanged while exposing amountTap.pay translations', () => {
    const sample = 'винен $23 завтра о 19:40';
    const sentAt = new Date(2026, 3, 10, 12, 0, 0);

    expect(findMoney(sample)).toEqual({
      kind: 'money',
      amount: 23,
      currency: 'USD',
    });
    expect(understand(sample, sentAt)).toEqual([
      {
        kind: 'time',
        at: new Date(2026, 3, 11, 19, 40, 0),
        hasClock: true,
        deadline: false,
      },
      {
        kind: 'money',
        amount: 23,
        currency: 'USD',
      },
    ]);
    expect(findAllMoney(sample)).toEqual([
      { kind: 'money', amount: 23, currency: 'USD', raw: '$23', index: 6 },
    ]);
    expect(uk['amountTap.pay']).toBe('Оплатити {amount}');
    expect(en['amountTap.pay']).toBe('Pay {amount}');
    expect(cs['amountTap.pay']).toContain('{amount}');
  });
});
