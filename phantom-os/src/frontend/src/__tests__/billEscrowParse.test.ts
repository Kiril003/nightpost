import { describe, expect, it } from 'vitest';
import { parseBillData } from '../types/meetup';
import { BILL } from './meetupFixtures';

const VALID_ADDR = 'Esc1111111111111111111111111111111111111111';

describe('parseBillData escrow fields', () => {
  it('keeps mode and escrow for a valid escrow bill', () => {
    const raw = {
      ...BILL,
      mode: 'escrow',
      escrow: { address: VALID_ADDR },
    };
    const parsed = parseBillData(raw);
    expect(parsed).toEqual({
      ...BILL,
      mode: 'escrow',
      escrow: { address: VALID_ADDR },
    });
  });

  it('drops invalid mode and bad escrow address without rejecting the bill', () => {
    const raw = {
      ...BILL,
      mode: 'x',
      escrow: { address: 'not-base58-0OIl!' },
    };
    const parsed = parseBillData(raw);
    expect(parsed).toEqual(BILL);
    expect(parsed).not.toHaveProperty('mode');
    expect(parsed).not.toHaveProperty('escrow');
  });

  it('parses an old bill without mode or escrow unchanged', () => {
    const parsed = parseBillData({ ...BILL });
    expect(parsed).toEqual(BILL);
    expect(parsed).not.toHaveProperty('mode');
    expect(parsed).not.toHaveProperty('escrow');
  });
});
