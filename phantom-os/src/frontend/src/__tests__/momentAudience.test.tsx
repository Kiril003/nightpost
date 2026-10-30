import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});

import { MomentComposer, momentCircles } from '../components/messenger/MomentsStrip';
import { momentPeople } from '../components/messenger/MomentViewer';
import { audienceCount } from '../services/audienceCount';
import { useMoments, type MomentPrefs } from '../services/moments';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const origLoadPrefs = useMoments.getState().loadPrefs;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat => ({
  id,
  title,
  type: 'dm',
  circle: 'all',
  avatar: '',
  unreadCount: 0,
  pinned: false,
  messages: [],
  lastSnippet: 'hi',
  lastAt: new Date().toISOString(),
  peerNodeId: id,
  ...extra,
});

beforeEach(() => {
  localStorage.setItem('nightpost.moments.audienceChosen', '1');
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  useMoments.setState({ list: [], prefs: null, loadPrefs: origLoadPrefs });
  bridge.request.mockReset().mockImplementation((method: string, path: string, body?: unknown) => {
    if (path === '/messenger/moments/prefs') {
      return Promise.resolve({
        audience: { kind: 'contacts' },
        to: [],
        keep: false,
        everyone: false,
        muted: [],
      });
    }
    if (method === 'POST' && path === '/messenger/moments') {
      const b = body as { text: string; to?: string[]; audience: unknown };
      return Promise.resolve({
        id: 'm'.repeat(32),
        from_id: 'me',
        from_name: '',
        mine: true,
        at_ms: Date.now(),
        until_ms: Date.now() + 86_400_000,
        keep: false,
        audience: b.audience,
        text: b.text,
        seen: true,
        to_count: b.to?.length ?? 0,
        media: [],
      });
    }
    return Promise.resolve({});
  });
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  useMoments.setState({ loadPrefs: origLoadPrefs });
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('MomentComposer audience count and leave-out chip', () => {
  it('posts {kind:"contacts"} when nobody is left out and {kind:"people"} with to when Work is left out under Contacts', async () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'close' }),
      chat('mum', 'Mum', { circle: 'personal' }),
      chat('ivan', 'Ivan', { circle: 'friends' }),
      chat('taras', 'Taras', { circle: 'work' }),
    ];
    const { unmount } = render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('4 people · 1 Work'));

    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'for all contacts' } });
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);

    await waitFor(() =>
      expect(bridge.request).toHaveBeenCalledWith(
        'POST',
        '/messenger/moments',
        {
          media: [],
          text: 'for all contacts',
          keep: false,
          audience: { kind: 'contacts' },
        },
      ),
    );
    unmount();

    bridge.request.mockClear();
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet2 = await screen.findByRole('dialog');
    fireEvent.click(sheet2.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('4 people · 1 Work'));
    const leaveWork = document.querySelector('[data-testid="aud-leave-work"]') as HTMLButtonElement;
    expect(leaveWork).not.toBeNull();
    expect(leaveWork.textContent).toBe('Leave out Work');

    fireEvent.click(leaveWork);

    expect(document.querySelector('[data-testid="aud-contacts"]')?.getAttribute('aria-checked')).toBe('true');
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('3 people');
    expect(document.querySelector('[data-testid^="aud-leave-"]')).toBeNull();

    fireEvent.change(sheet2.querySelector('[data-moment-caption]')!, { target: { value: 'without work' } });
    fireEvent.click(sheet2.querySelector('[data-moment-share]')!);

    await waitFor(() =>
      expect(bridge.request).toHaveBeenCalledWith(
        'POST',
        '/messenger/moments',
        {
          media: [],
          text: 'without work',
          keep: false,
          audience: { kind: 'people' },
          to: ['olena', 'mum', 'ivan'],
        },
      ),
    );
  });

  it('resets leftOut only when picking a different reach so Work people are included again under Circles', async () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'close' }),
      chat('mum', 'Mum', { circle: 'personal' }),
      chat('ivan', 'Ivan', { circle: 'friends' }),
      chat('taras', 'Taras', { circle: 'work' }),
    ];
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('4 people · 1 Work'));

    fireEvent.click(document.querySelector('[data-testid="aud-leave-work"]')!);
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('3 people');

    fireEvent.click(document.querySelector('[data-testid="aud-contacts"]')!);
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('3 people');

    fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-close"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-work"]')!);

    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work');
    expect(document.querySelector('[data-testid="aud-leave-work"]')).not.toBeNull();
  });

  it('keeps reach as circles and does not mutate selected circle names when leaving out Work under Circles', async () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'close' }),
      chat('taras', 'Taras', { circle: 'work' }),
    ];
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work'));

    fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-close"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-work"]')!);
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work');

    fireEvent.click(document.querySelector('[data-testid="aud-leave-work"]')!);

    expect(document.querySelector('[data-testid="aud-circles"]')?.getAttribute('aria-checked')).toBe('true');
    expect(document.querySelector('[data-testid="aud-circle-close"]')?.getAttribute('aria-pressed')).toBe('true');
    expect(document.querySelector('[data-testid="aud-circle-work"]')?.getAttribute('aria-pressed')).toBe('true');
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('1 person');
    expect(document.querySelector('[data-testid^="aud-leave-"]')).toBeNull();

    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'circle note' } });
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);

    await waitFor(() =>
      expect(bridge.request).toHaveBeenCalledWith(
        'POST',
        '/messenger/moments',
        {
          media: [],
          text: 'circle note',
          keep: false,
          audience: { kind: 'circles', circles: ['close', 'work'] },
          to: ['olena'],
        },
      ),
    );
  });

  it('counts each person under one primary ring from their most recent non-archived DM chat, else closest ring, and excludes archived-only and non-real-circle people', () => {
    const chats = [
      chat('dm-olena-old', 'Olena', { peerNodeId: 'olena', circle: 'close', lastAt: '2026-01-01T10:00:00.000Z' }),
      chat('dm-olena-new', 'Olena', { peerNodeId: 'olena', circle: 'work', lastAt: '2026-01-01T10:00:00.500Z' }),
      chat('dm-ivan-1', 'Ivan', { peerNodeId: 'ivan', circle: 'social', lastAt: '2026-01-01T10:00:00.000Z' }),
      chat('dm-ivan-2', 'Ivan', { peerNodeId: 'ivan', circle: 'friends', lastAt: '2026-01-01T10:00:00.000Z' }),
      chat('dm-maks', 'Maks', { peerNodeId: 'maks', circle: 'work', circles: ['work', 'personal'] }),
      chat('dm-arch-only', 'Ghost', { peerNodeId: 'ghost', circle: 'work', archived: true, lastAt: '2026-01-05T10:00:00.000Z' }),
      chat('dm-petro-arch', 'Petro', { peerNodeId: 'petro', circle: 'work', archived: true, lastAt: '2026-01-05T10:00:00.000Z' }),
      chat('dm-petro-bad-date', 'Petro', { peerNodeId: 'petro', circle: 'work', archived: false, lastAt: 'not-a-date' }),
      chat('dm-petro-live', 'Petro', { peerNodeId: 'petro', circle: 'close', archived: false, lastAt: '2026-01-01T10:00:00.000Z' }),
      chat('dm-unringed', 'NoRing', { peerNodeId: 'noring', circle: 'all', lastAt: '2026-01-03T10:00:00.000Z' }),
    ];

    const res = audienceCount({ kind: 'contacts' }, chats);
    expect(res).toEqual({
      total: 4,
      ids: ['olena', 'ivan', 'maks', 'petro'],
      byRing: {
        work: ['olena'],
        personal: ['maks'],
        friends: ['ivan'],
        close: ['petro'],
      },
    });
    const sumByRing = Object.values(res!.byRing).reduce((sum, arr) => sum + (arr?.length ?? 0), 0);
    expect(res!.total).toBe(sumByRing);
    expect(momentCircles(chats)).toEqual([
      { name: 'personal', ids: ['maks'] },
      { name: 'close', ids: ['petro'] },
      { name: 'friends', ids: ['ivan'] },
      { name: 'work', ids: ['olena'] },
    ]);
    expect(audienceCount({ kind: 'people', ids: ['ghost'] }, chats)).toBeNull();
    expect(audienceCount({ kind: 'people', ids: ['noring'] }, chats)).toBeNull();
  });

  it('shows the count line after 1500 ms when loadPrefs settles late and does not let late prefs overwrite the reach', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let resolvePrefs!: (value: MomentPrefs | null) => void;
      const pending = new Promise<MomentPrefs | null>((resolve) => {
        resolvePrefs = resolve;
      });
      useMoments.setState({
        prefs: null,
        loadPrefs: vi.fn(() => pending),
      });
      const chats = [
        chat('olena', 'Olena', { circle: 'close' }),
        chat('taras', 'Taras', { circle: 'work' }),
      ];
      render(
        <MomentComposer
          file={null}
          people={momentPeople(chats)}
          chats={chats}
          onClose={vi.fn()}
        />,
      );
      const sheet = await screen.findByRole('dialog');
      fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
      expect(document.querySelector('[data-testid="aud-count"]')).toBeNull();

      await act(async () => {
        vi.advanceTimersByTime(1500);
      });

      expect(document.querySelector('[data-testid="aud-contacts"]')?.getAttribute('aria-checked')).toBe('true');
      expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work');

      await act(async () => {
        resolvePrefs({
          audience: { kind: 'me' },
          keep: true,
          everyone: false,
          muted: [],
        });
        await pending;
      });

      expect(document.querySelector('[data-testid="aud-contacts"]')?.getAttribute('aria-checked')).toBe('true');
      expect(document.querySelector('[data-testid="aud-me"]')?.getAttribute('aria-checked')).toBe('false');
      expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work');
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not let late prefs overwrite a reach touched by the user before 1500 ms', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let resolvePrefs!: (value: MomentPrefs | null) => void;
      const pending = new Promise<MomentPrefs | null>((resolve) => {
        resolvePrefs = resolve;
      });
      useMoments.setState({
        prefs: null,
        loadPrefs: vi.fn(() => pending),
      });
      const chats = [
        chat('olena', 'Olena', { circle: 'close' }),
        chat('taras', 'Taras', { circle: 'work' }),
      ];
      render(
        <MomentComposer
          file={null}
          people={momentPeople(chats)}
          chats={chats}
          onClose={vi.fn()}
        />,
      );
      const sheet = await screen.findByRole('dialog');
      fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
      fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
      fireEvent.click(document.querySelector('[data-testid="aud-circle-close"]')!);
      expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('1 person');

      await act(async () => {
        resolvePrefs({
          audience: { kind: 'contacts' },
          keep: false,
          everyone: false,
          muted: [],
        });
        await pending;
      });

      expect(document.querySelector('[data-testid="aud-circles"]')?.getAttribute('aria-checked')).toBe('true');
      expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('1 person');
    } finally {
      vi.useRealTimers();
    }
  });

  it('renders no leave chip when no outer-ring people are in the audience', async () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'close' }),
      chat('mum', 'Mum', { circle: 'personal' }),
      chat('ivan', 'Ivan', { circle: 'friends' }),
    ];
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('3 people'));
    expect(document.querySelector('[data-testid^="aud-leave-"]')).toBeNull();
  });

  it('renders the count line using store prefs when loadPrefs rejects', async () => {
    useMoments.setState({
      prefs: { audience: { kind: 'contacts' }, keep: false, everyone: false, muted: [] },
      loadPrefs: vi.fn().mockRejectedValue(new Error('offline')),
    });
    const chats = [
      chat('olena', 'Olena', { circle: 'close' }),
      chat('taras', 'Taras', { circle: 'work' }),
    ];
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work'));
  });

  it('counts and posts only primary-ring members for Work, excluding Friends with Work in chat.circles', async () => {
    const chats = [
      chat('petro', 'Petro', { circle: 'friends', circles: ['friends', 'work'] }),
      chat('taras', 'Taras', { circle: 'work' }),
    ];
    render(
      <MomentComposer
        file={null}
        people={momentPeople(chats)}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('2 people · 1 Work'));

    fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
    fireEvent.click(document.querySelector('[data-testid="aud-circle-work"]')!);
    expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toBe('1 person · 1 Work');

    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'hello' } });
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);

    await waitFor(() =>
      expect(bridge.request).toHaveBeenCalledWith(
        'POST',
        '/messenger/moments',
        {
          media: [],
          text: 'hello',
          keep: false,
          audience: { kind: 'circles', circles: ['work'] },
          to: ['taras'],
        },
      ),
    );
  });
});
