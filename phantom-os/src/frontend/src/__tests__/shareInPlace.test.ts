import { describe, expect, it, vi } from 'vitest';

vi.mock('../services/api', () => ({ request: vi.fn() }));

import { request } from '../services/api';
import { fetchPendingShare } from '../services/shareIn';

describe('fetchPendingShare: a map place', () => {
  it('keeps the point of a place item and drops a place without one', async () => {
    vi.mocked(request).mockResolvedValue({
      id: 'p1',
      skipped: 0,
      items: [
        { index: 0, kind: 'place', text: 'Market square', mime: null, name: null, size: 0, lat: 49.84, lon: 24.03 },
        { index: 1, kind: 'place', text: 'No point', size: 0 },
      ],
    } as never);
    const got = await fetchPendingShare();
    expect(got?.items).toEqual([
      { index: 0, kind: 'place', text: 'Market square', mime: null, name: null, size: 0, lat: 49.84, lon: 24.03 },
    ]);
  });
});
