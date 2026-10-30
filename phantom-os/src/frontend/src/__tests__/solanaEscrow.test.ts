import { beforeEach, describe, expect, it, vi } from 'vitest';

const mockPhoneBridge = vi.fn<() => boolean>();
const mockRequest = vi.fn();

vi.mock('../services/api', () => ({
  request: (...args: unknown[]) => mockRequest(...args),
}));

vi.mock('../services/messengerApi', () => ({
  phoneBridge: () => mockPhoneBridge(),
}));

import { escrow } from '../services/solanaActions';

const OFF = { on: false, cluster: null, program: null };

describe('escrow()', () => {
  beforeEach(() => {
    mockPhoneBridge.mockReset();
    mockRequest.mockReset();
  });

  it('returns off without calling request when phoneBridge is absent', async () => {
    mockPhoneBridge.mockReturnValue(false);
    await expect(escrow()).resolves.toEqual(OFF);
    expect(mockRequest).not.toHaveBeenCalled();
  });

  it('returns on: true when bridge replies with devnet and non-empty program', async () => {
    mockPhoneBridge.mockReturnValue(true);
    mockRequest.mockResolvedValue({
      on: true,
      cluster: 'devnet',
      program: 'Esc1111111111111111111111111111111111111111',
    });

    await expect(escrow()).resolves.toEqual({
      on: true,
      cluster: 'devnet',
      program: 'Esc1111111111111111111111111111111111111111',
    });
    expect(mockRequest).toHaveBeenCalledWith('GET', '/messenger/solana/escrow');
  });

  it.each([
    ['mainnet cluster', { on: true, cluster: 'mainnet', program: 'X' }],
    ['null program', { on: true, cluster: 'devnet', program: null }],
    ['empty program', { on: true, cluster: 'devnet', program: '' }],
    ['non-boolean on', { on: 'yes' }],
    ['null body', null],
    ['string body', 'unexpected'],
  ])('returns off and does not throw for invalid payload: %s', async (_label, payload) => {
    mockPhoneBridge.mockReturnValue(true);
    mockRequest.mockResolvedValue(payload);

    await expect(escrow()).resolves.toEqual(OFF);
  });

  it('returns off and does not throw when request rejects', async () => {
    mockPhoneBridge.mockReturnValue(true);
    mockRequest.mockRejectedValue(new Error('bridge unreachable'));

    await expect(escrow()).resolves.toEqual(OFF);
  });
});
