import { describe, it, expect, beforeEach } from 'vitest';
import { currencySign, expensesOf, localCurrency, money, setRegionCountry } from '../components/messenger/cards/cardModel';
import type { Message } from '../types/messenger';

const card = (currency?: string) =>
  ({ id: 'm', type: 'widget:family-expense', familyExpenseData: { id: 'fe', title: 'Dinner', currency, items: [] } }) as unknown as Message;

describe('валюта картки витрат — з регіону телефона, не гривня всюди', () => {
  beforeEach(() => {
    setRegionCountry(null);
  });

  it('czCountry_beatsUkLanguage', () => {
    expect(localCurrency(['en', 'uk-UA'], 'CZ')).toBe('Kč');
  });

  it('ukWithoutCountry_staysHryvnia', () => {
    expect(localCurrency(['uk'], null)).toBe('₴');
  });

  it('decimalsKept', () => {
    expect(money(4.5)).toMatch(/4[.,]50/);
  });

  it('wholeStaysShort', () => {
    expect(money(12400)).toBe('12 400');
  });

  it('currencySign maps ISO codes', () => {
    expect(currencySign('UAH')).toBe('₴');
    expect(currencySign('CZK')).toBe('Kč');
    expect(currencySign('EUR')).toBe('€');
    expect(currencySign('USD')).toBe('$');
    expect(currencySign('PLN')).toBe('zł');
    expect(currencySign('GBP')).toBe('£');
    expect(currencySign('CHF')).toBe('CHF');
    expect(currencySign('')).toBe('');
  });

  it('регіон дає знак', () => {
    expect(localCurrency(['en-US'])).toBe('$');
    expect(localCurrency(['en-GB'])).toBe('£');
    expect(localCurrency(['de-DE'])).toBe('€');
    expect(localCurrency(['cs-CZ'])).toBe('Kč');
    expect(localCurrency(['uk-UA'])).toBe('₴');
    expect(localCurrency(['uk'])).toBe('₴');
  });

  it('регіон невідомий — без знака', () => {
    expect(localCurrency(['en'])).toBe('');
    expect(localCurrency([])).toBe('');
  });

  it('стара картка без валюти не стає гривнею на англійському телефоні', () => {
    const langs = Object.getOwnPropertyDescriptor(navigator, 'languages');
    Object.defineProperty(navigator, 'languages', { value: ['en-US'], configurable: true });
    try {
      expect(expensesOf(card())!.currency).toBe('$');
      expect(expensesOf(card('₴'))!.currency).toBe('₴');
    } finally {
      if (langs) Object.defineProperty(navigator, 'languages', langs);
      else delete (navigator as unknown as Record<string, unknown>).languages;
    }
  });
});
