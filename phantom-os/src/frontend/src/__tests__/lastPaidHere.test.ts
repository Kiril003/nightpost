import { describe, it, expect } from 'vitest';
import { lastPaidHere } from '../services/money/lastPaidHere';
import { BILL, ME, OLENA, billMsg } from './meetupFixtures';

const at = (d: number) => new Date(2026, 9, d, 21, 0).toISOString();
const chat = (...messages: ReturnType<typeof billMsg>[]) => [{ messages }];

describe('lastPaidHere', () => {
  it('takes the newest bill at the venue', () => {
    const old = billMsg({ id: 'a', sentAt: at(2), billData: { ...BILL, id: 'b1', payee: { kind: 'peer', id: ME } } });
    const fresh = billMsg({ id: 'b', sentAt: at(14), billData: { ...BILL, id: 'b2', payee: { kind: 'peer', id: OLENA } } });
    const r = lastPaidHere(chat(fresh, old), 'cafe-x', ME);
    expect(r).toEqual({ payerName: 'Олена', isMe: false, dateMs: Date.parse(at(14)) });
  });
  it('me as payee is flagged, no name', () => {
    const r = lastPaidHere(chat(billMsg({ sentAt: at(3) })), 'cafe-x', ME);
    expect(r).toMatchObject({ isMe: true, payerName: '' });
  });
  it('other venue gives null', () => {
    expect(lastPaidHere(chat(billMsg()), 'other', ME)).toBeNull();
    expect(lastPaidHere([], 'cafe-x', ME)).toBeNull();
  });
});
