/**
 * Стрічка «Що я роблю» не тієї форми — Профіль не падає в «Щось зламалось»,
 * рядки стрічки просто не показуються.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PhantomRows } from '../components/messenger/PhantomFeed';
import { feedOf } from '../services/deviceApi';
import * as api from '../services/api';

const GOOD = { stopped: false, chain: { ok: true, count: 1, broken_at: null, why: '' }, entries: [{ n: 1, at_iso: '2026-10-18T00:00:00Z', who: 'mind', what: 'note', status: 'ok', detail: '', risk: '', hash: 'h' }] };

afterEach(() => vi.restoreAllMocks());

describe('feedOf', () => {
  it('rejects shapes without entries or chain and drops malformed rows', () => {
    expect(feedOf({})).toBeNull();
    expect(feedOf([])).toBeNull();
    expect(feedOf({ entries: [] })).toBeNull();
    const f = feedOf({ ...GOOD, stopped: 'yes', entries: [...GOOD.entries, null, { n: 2 }] });
    expect(f?.entries).toHaveLength(1);
    expect(f?.stopped).toBe(false);
  });
});

describe('Profile rows', () => {
  it('an empty object from the node hides the rows instead of crashing', async () => {
    const spy = vi.spyOn(api, 'request').mockResolvedValue({} as never);
    const { container } = render(<PhantomRows onFeed={() => undefined} />);
    await waitFor(() => expect(spy).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(container.innerHTML).toBe('');
  });

  it('a real feed shows the rows', async () => {
    vi.spyOn(api, 'request').mockResolvedValue(GOOD as never);
    render(<PhantomRows onFeed={() => undefined} />);
    expect(await screen.findByTestId('me-feed')).toBeTruthy();
  });
});
