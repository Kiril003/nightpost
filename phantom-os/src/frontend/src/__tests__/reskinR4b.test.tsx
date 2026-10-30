/**
 * R4 follow-ups: one amount formatter for the eyes (decimal comma in uk/cs,
 * the coin glued with a no-break space) and a one-line meetup expiry.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { formatAmount, displayUnits } from '../services/meetup/split';
import { shortWhen } from '../components/messenger/meetup/words';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const NB = '\u00A0';
const lang = (l: string) => useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));
afterEach(() => lang('uk'));

describe('amounts', () => {
  it('uk and cs use a decimal comma, en a point; the coin never wraps away', () => {
    lang('uk');
    expect(formatAmount(18_000_000n, 'USDC')).toBe(`18,00${NB}USDC`);
    expect(formatAmount(50_000_000n, 'SOL')).toBe(`0,05${NB}SOL`);
    expect(displayUnits(5_500_000n, 'USDC')).toBe('5,50');
    lang('cs');
    expect(formatAmount(18_000_000n, 'USDC')).toBe(`18,00${NB}USDC`);
    lang('en');
    expect(formatAmount(18_000_000n, 'USDC')).toBe(`18.00${NB}USDC`);
  });
});

describe('meetup expiry', () => {
  it('names a weekday within a week and a short date beyond, never the long date', () => {
    lang('uk');
    const now = new Date(2026, 9, 17, 12, 0).getTime();
    const in3 = new Date(2026, 9, 20, 19, 0).getTime();
    const in10 = new Date(2026, 9, 27, 19, 0).getTime();
    expect(shortWhen(in3, now)).toMatch(/^[^\s,]+ · 19:00$/);
    expect(shortWhen(in10, now)).toMatch(/^27\.10 · 19:00$/);
    expect(shortWhen(new Date(2026, 9, 18, 19, 0).getTime(), now)).toMatch(/· 19:00$/);
    lang('cs');
    expect(shortWhen(in10, now)).toMatch(/^27\. ?10\.? · 19:00$/);
    lang('en');
    expect(shortWhen(in10, now)).toMatch(/^27\/10 · 19:00$/);
  });
});
