import { describe, it, expect, vi, beforeEach } from 'vitest';
import { request } from '../api';
import { loadRegion, resetRegionCache } from '../regionApi';
import { localCurrency, setRegionCountry } from '../../components/messenger/cards/cardModel';

vi.mock('../api', () => ({
  request: vi.fn(),
}));

const mockedRequest = vi.mocked(request);

describe('regionApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetRegionCache();
    setRegionCountry(null);
  });

  it('door_setsCountry', async () => {
    mockedRequest.mockResolvedValueOnce({
      country: 'CZ',
      currency: 'CZK',
      emergency: '112',
      metric: true,
    });
    const res = await loadRegion();
    expect(res?.country).toBe('CZ');
    expect(localCurrency(['uk'])).toBe('Kč');
  });

  it('noDoor_returnsNull', async () => {
    mockedRequest.mockRejectedValueOnce(new Error('404'));
    const res = await loadRegion();
    expect(res).toBeNull();
    expect(localCurrency(['uk'])).toBe('₴');
  });
});
