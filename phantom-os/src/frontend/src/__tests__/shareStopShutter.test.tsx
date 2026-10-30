import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/api', () => ({ request: vi.fn() }));

import { request } from '../services/api';
import { WhereCards, WhereHeaderSign } from '../components/messenger/WhereCard';
import { tNow } from '../components/chat/tNow';
import type { WhereState } from '../services/peerWhere';

const requestMock = vi.mocked(request);

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const liveState: WhereState = {
  now_ms: 1_700_000_000_000,
  eligible: true,
  why_not: null,
  mine: { precision: 'district', until_ms: null, stand: false },
  theirs: null,
};

const endedState: WhereState = {
  ...liveState,
  mine: null,
};

describe('WhereCards and WhereHeaderSign stop shutter', () => {
  const originalMatchMedia = window.matchMedia;

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    window.matchMedia = originalMatchMedia;
    requestMock.mockReset();
  });

  it('marks where-card-mine stopping with disabled buttons and no shutter before ack', async () => {
    const del = deferred<WhereState>();
    let firstGet = true;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        if (firstGet) {
          firstGet = false;
          return Promise.resolve(liveState);
        }
        return new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-1', name: 'Олена' }} onChange={vi.fn()} />);
    const card = await screen.findByTestId('where-card-mine');
    expect(card.getAttribute('data-state')).toBe('live');

    vi.useFakeTimers();
    fireEvent.click(screen.getByText(tNow('whereCard.stop')));

    expect(screen.getByTestId('where-card-mine').getAttribute('data-state')).toBe('stopping');
    const stopBtn = screen.getByText(tNow('whereCard.stopping')) as HTMLButtonElement;
    const changeBtn = screen.getByText(tNow('whereCard.change')) as HTMLButtonElement;
    expect(stopBtn.disabled).toBe(true);
    expect(changeBtn.disabled).toBe(true);
    expect(screen.queryByTestId('where-shutter')).toBeNull();

    await act(async () => {
      vi.advanceTimersByTime(5_000);
    });
    expect(screen.getByTestId('where-card-mine').getAttribute('data-state')).toBe('stopping');
    expect(screen.queryByTestId('where-shutter')).toBeNull();

    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });
  });

  it('swaps to where-card-mine-ended with data-state="shut" and running shutter on DELETE ack', async () => {
    const del = deferred<WhereState>();
    let firstGet = true;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        if (firstGet) {
          firstGet = false;
          return Promise.resolve(liveState);
        }
        return new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-2', name: 'Олена' }} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');

    fireEvent.click(screen.getByText(tNow('whereCard.stop')));
    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });

    expect(screen.queryByTestId('where-card-mine')).toBeNull();
    const ended = screen.getByTestId('where-card-mine-ended');
    expect(ended.getAttribute('data-state')).toBe('shut');
    const shutter = screen.getByTestId('where-shutter');
    expect(ended.contains(shutter)).toBe(true);
    expect(shutter.getAttribute('data-motion')).toBe('run');
  });

  it('renders plain ended card without shutter when share ends via __phantomWhereChanged', async () => {
    let getCount = 0;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        getCount += 1;
        return Promise.resolve(getCount === 1 ? liveState : endedState);
      }
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-3', name: 'Олена' }} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');

    await act(async () => {
      (window as unknown as { __phantomWhereChanged?: () => void }).__phantomWhereChanged?.();
    });

    const ended = await screen.findByTestId('where-card-mine-ended');
    expect(ended.getAttribute('data-state')).toBe('ended');
    expect(screen.queryByTestId('where-shutter')).toBeNull();
  });

  it('renders where-shutter with data-motion="end" when reduced motion matches', async () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('reduce'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    const del = deferred<WhereState>();
    let firstGet = true;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        if (firstGet) {
          firstGet = false;
          return Promise.resolve(liveState);
        }
        return new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-4', name: 'Олена' }} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');

    fireEvent.click(screen.getByText(tNow('whereCard.stop')));
    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });

    expect(screen.getByTestId('where-card-mine-ended').getAttribute('data-state')).toBe('shut');
    expect(screen.getByTestId('where-shutter').getAttribute('data-motion')).toBe('end');
  });

  it('stops from header chip: sign shows stopping until ack, then disappears while card shuts', async () => {
    const del = deferred<WhereState>();
    let gets = 0;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        gets += 1;
        return gets <= 2 ? Promise.resolve(liveState) : new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    const peer = { id: 'peer-5', name: 'Олена' };
    render(
      <div>
        <WhereHeaderSign peer={peer} />
        <WhereCards peer={peer} onChange={vi.fn()} />
      </div>,
    );

    const sign = await screen.findByTestId('where-header-sign');
    await screen.findByTestId('where-card-mine');
    expect(sign.getAttribute('data-state')).toBe('live');

    fireEvent.click(sign);
    fireEvent.click(screen.getByText(tNow('whereCard.stopSharing')));

    expect(screen.getByTestId('where-header-sign').getAttribute('data-state')).toBe('stopping');

    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });

    expect(screen.queryByTestId('where-header-sign')).toBeNull();
    expect(screen.getByTestId('where-card-mine-ended').getAttribute('data-state')).toBe('shut');
  });

  it('restores where-card-mine to live and shows stopFailed when DELETE rejects', async () => {
    const del = deferred<WhereState>();
    let firstGet = true;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        if (firstGet) {
          firstGet = false;
          return Promise.resolve(liveState);
        }
        return new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-6', name: 'Олена' }} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');

    fireEvent.click(screen.getByText(tNow('whereCard.stop')));
    expect(screen.getByTestId('where-card-mine').getAttribute('data-state')).toBe('stopping');

    await act(async () => {
      del.reject(new Error('failed'));
      try {
        await del.promise;
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(screen.getByTestId('where-card-mine').getAttribute('data-state')).toBe('live');
    });
    expect(screen.getByText(tNow('whereCard.stopFailed'))).toBeTruthy();
    expect(screen.queryByTestId('where-shutter')).toBeNull();
  });

  it('drops a pre-stop GET that resolves after the ack, so the live card never comes back', async () => {
    const del = deferred<WhereState>();
    const stale = deferred<WhereState>();
    let gets = 0;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        gets += 1;
        if (gets === 1) return Promise.resolve(liveState);
        if (gets === 2) return stale.promise;
        return new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    render(<WhereCards peer={{ id: 'peer-7', name: 'Олена' }} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');
    await act(async () => {
      (window as unknown as { __phantomWhereChanged?: () => void }).__phantomWhereChanged?.();
    });
    expect(gets).toBe(2);

    fireEvent.click(screen.getByText(tNow('whereCard.stop')));
    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });
    expect(gets).toBe(3);

    await act(async () => {
      stale.resolve(liveState);
      await stale.promise;
    });

    expect(screen.queryByTestId('where-card-mine')).toBeNull();
    expect(screen.getByTestId('where-card-mine-ended').getAttribute('data-state')).toBe('shut');
  });

  it('shuts the card on a header stop even before the card saw its first GET', async () => {
    const del = deferred<WhereState>();
    let gets = 0;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        gets += 1;
        return gets === 1 ? Promise.resolve(liveState) : new Promise<WhereState>(() => {});
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    const peer = { id: 'peer-8', name: 'Олена' };
    render(
      <div>
        <WhereHeaderSign peer={peer} />
        <WhereCards peer={peer} onChange={vi.fn()} />
      </div>,
    );
    fireEvent.click(await screen.findByTestId('where-header-sign'));
    expect(screen.queryByTestId('where-card-mine')).toBeNull();
    fireEvent.click(screen.getByText(tNow('whereCard.stopSharing')));
    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });

    expect(screen.getByTestId('where-card-mine-ended').getAttribute('data-state')).toBe('shut');
  });

  it('does not replay the shutter after reopening, and a later expiry stays a plain ended card', async () => {
    const del = deferred<WhereState>();
    let gets = 0;
    requestMock.mockImplementation((method: string) => {
      if (method === 'GET') {
        gets += 1;
        return Promise.resolve(gets === 1 ? liveState : endedState);
      }
      if (method === 'DELETE') return del.promise;
      return new Promise(() => {});
    });

    const peer = { id: 'peer-9', name: 'Олена' };
    const first = render(<WhereCards peer={peer} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');
    fireEvent.click(screen.getByText(tNow('whereCard.stop')));
    await act(async () => {
      del.resolve(endedState);
      await del.promise;
    });
    expect(screen.getByTestId('where-card-mine-ended').getAttribute('data-state')).toBe('shut');
    first.unmount();

    render(<WhereCards peer={peer} onChange={vi.fn()} />);
    await act(async () => {});
    expect(screen.queryByTestId('where-card-mine-ended')).toBeNull();
    expect(screen.queryByTestId('where-shutter')).toBeNull();
    cleanup();

    gets = 0;
    render(<WhereCards peer={peer} onChange={vi.fn()} />);
    await screen.findByTestId('where-card-mine');
    await act(async () => {
      (window as unknown as { __phantomWhereChanged?: () => void }).__phantomWhereChanged?.();
    });
    const ended = await screen.findByTestId('where-card-mine-ended');
    expect(ended.getAttribute('data-state')).toBe('ended');
    expect(screen.queryByTestId('where-shutter')).toBeNull();
  });
});
