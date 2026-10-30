import { beforeEach, describe, expect, it, vi } from 'vitest';

const mockPhoneBridge = vi.fn<() => boolean>();
const mockRequest = vi.fn();

vi.mock('../services/api', () => ({
  request: (...args: unknown[]) => mockRequest(...args),
}));

vi.mock('../services/messengerApi', () => ({
  phoneBridge: () => mockPhoneBridge(),
}));

import { escrowBill } from '../services/solanaActions';

const ADDR = 'Esc1111111111111111111111111111111111111111';

describe('escrowBill()', () => {
  beforeEach(() => {
    mockPhoneBridge.mockReset();
    mockRequest.mockReset();
  });

  it('returns null without calling request when phoneBridge is absent', async () => {
    mockPhoneBridge.mockReturnValue(false);
    await expect(escrowBill(ADDR)).resolves.toBeNull();
    expect(mockRequest).not.toHaveBeenCalled();
  });

  it('returns the parsed EscrowBill when bridge replies with a valid payload', async () => {
    mockPhoneBridge.mockReturnValue(true);
    const valid = { status: 'open' as const, paid: 1, total: 3, deadline: 1700000000 };
    mockRequest.mockResolvedValue(valid);

    await expect(escrowBill(ADDR)).resolves.toEqual(valid);
    expect(mockRequest).toHaveBeenCalledWith('GET', `/messenger/solana/escrow/bill/${encodeURIComponent(ADDR)}`);
  });

  it.each([
    ['unknown status', { status: 'paid', paid: 1, total: 2, deadline: 1 }],
    ['paid exceeds total', { status: 'open', paid: 3, total: 2, deadline: 1 }],
    ['negative paid', { status: 'open', paid: -1, total: 2, deadline: 1 }],
    ['null status', { status: null, paid: 0, total: 0, deadline: null }],
    ['null body', null],
    ['empty escrow', { status: 'open', paid: 0, total: 0, deadline: 1 }],
    ['fractional paid', { status: 'open', paid: 1.5, total: 2, deadline: 1 }],
    ['string deadline', { status: 'open', paid: 1, total: 2, deadline: '1' }],
  ])('returns null and does not throw for invalid payload: %s', async (_label, payload) => {
    mockPhoneBridge.mockReturnValue(true);
    mockRequest.mockResolvedValue(payload);

    await expect(escrowBill(ADDR)).resolves.toBeNull();
  });

  it('returns null and does not throw when request rejects', async () => {
    mockPhoneBridge.mockReturnValue(true);
    mockRequest.mockRejectedValue(new Error('bridge unreachable'));

    await expect(escrowBill(ADDR)).resolves.toBeNull();
  });
});
