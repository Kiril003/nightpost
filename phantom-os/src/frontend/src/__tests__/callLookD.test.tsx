/**
 * Екран дзвінка в стилі D (calls-r, 23.10): рівні поверхні без тіні, скла й
 * градієнта; один бурштиновий головний — «Прийняти»; червоне лише на
 * «Відхилити»/«Завершити»; цілі дотику ≥48; завершений дзвінок каже, скільки
 * тривав. Вхід — слухавка в шапці розмови.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { CallSnapshot } from '../services/callEngine';
import type { LegView } from '../services/callRoom';

let snap: CallSnapshot;
const listeners = new Set<() => void>();
const setSnap = (next: CallSnapshot) => {
  snap = next;
  listeners.forEach((l) => l());
};

vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: (l: () => void) => {
        listeners.add(l);
        return () => listeners.delete(l);
      },
      getSnapshot: () => snap,
      toggleMic: vi.fn(),
      toggleSpeaker: vi.fn(),
      toggleCamera: vi.fn(),
      flipCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dismiss: vi.fn(),
      redial: vi.fn(),
      dropLeg: vi.fn(),
      setAudioOnly: vi.fn(),
      setMusicMode: vi.fn(),
      peekCamera: vi.fn(),
      dataUsedBytes: vi.fn(async () => 1_000_000),
    },
  };
});

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

vi.mock('../services/dayApi', () => ({ dayApi: { today: vi.fn(() => new Promise(() => {})) } }));

import { CallOverlay } from '../components/messenger/CallOverlay';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';
import { Header } from '../components/messenger/Header';
import { useMessengerStore } from '../stores/messengerStore';
import { loadCallPrefs } from '../services/callPrefs';
import type { Chat, UserProfile } from '../types/messenger';

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'audio',
  outgoing: true,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: Date.now() - 65_000,
  stats: null,
  endedReason: null,
  stall: null,
  audioLevel: 'crystal',
  ladderPinned: false,
  videoDropped: false,
  radio: null,
  radioReason: null,
  linkNote: null,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  served: null,
  weakSide: null,
};

const track = { id: 'v1', kind: 'video', readyState: 'live', enabled: true };
const videoStream = {
  getTracks: () => [track],
  getVideoTracks: () => [track],
  getAudioTracks: () => [],
  addEventListener: () => {},
  removeEventListener: () => {},
} as unknown as MediaStream;

const leg = (peerId: string, name: string, speaking: boolean, primary: boolean): LegView => ({
  peerId,
  name,
  foot: 'talking',
  sinceMs: Date.now() - 10_000,
  why: null,
  here: true,
  reachable: true,
  level: speaking ? 0.4 : 0,
  speaking,
  primary,
  stream: null,
});

const FX = /gradient|blur\(|backdrop-filter|backdrop-blur|box-shadow|shadow-e\d|drop-shadow/i;
const SMALL = /(?:^|\s)(?:w|h|min-w|min-h)-(?:8|9|10|11|\[44px\]|\[40px\])(?:\s|$)/;

const overlay = () => document.querySelector('[data-call-overlay]') as HTMLElement;

afterEach(() => {
  cleanup();
  listeners.clear();
  useMessengerStore.setState({ chats: [] } as never);
});

describe('екран дзвінка — стиль D', () => {
  it('вхідний: «Прийняти» — єдиний бурштин, «Відхилити» — червоне', () => {
    snap = { ...BASE, state: 'ringing', outgoing: false, startedAt: null, media: 'video' };
    render(<CallOverlay />);
    const accept = screen.getByRole('button', { name: 'Прийняти дзвінок' });
    const decline = screen.getByRole('button', { name: 'Відхилити дзвінок' });
    expect(accept.style.background).toBe('var(--ph-accent)');
    expect(accept.style.color).toBe('var(--ph-on-accent)');
    expect(decline.style.background).toBe('var(--ph-danger)');
    const amber = [...overlay().querySelectorAll<HTMLElement>('*')].filter((el) =>
      /var\(--ph-accent\)/.test(el.getAttribute('style') ?? '') || /(?:^|\s)bg-accent(?:\s|$)/.test(el.className as string),
    );
    expect(amber).toEqual([accept]);
  });

  const states: Array<[string, () => CallSnapshot]> = [
    ['вхідний із відповіддю', () => ({ ...BASE, state: 'ringing', outgoing: false, startedAt: null })],
    ['набір', () => ({ ...BASE, state: 'calling', startedAt: null })],
    ['розмова голосом зі слабким звʼязком', () => ({ ...BASE, weakSide: 'them' })],
    [
      'відео на весь екран',
      () => ({ ...BASE, media: 'video', hasCamera: true, cameraOn: true, remoteStream: videoStream, localStream: videoStream }),
    ],
    [
      'кімната на трьох',
      () => ({ ...BASE, legs: [leg('a1', 'Марта', true, true), leg('b2', 'Олег', false, false), leg('c3', 'Іра', false, false)] }),
    ],
  ];

  it.each(states)('%s: без тіні, скла, розмиття й градієнта; цілі ≥48', (_name, make) => {
    useMessengerStore.setState({
      chats: [{ id: 'd1', title: 'Марта', type: 'dm', circle: 'all', peerNodeId: BASE.peer!.peerNodeId, avatar: '', unreadCount: 0, messages: [] }],
    } as never);
    snap = make();
    render(<CallOverlay />);
    const root = overlay();
    expect(root.innerHTML).not.toMatch(FX);
    expect(root.innerHTML).not.toMatch(/srgb, var\(--ph-canvas\) \d+%, transparent/);
    for (const b of root.querySelectorAll<HTMLElement>('button:not([role="menuitemradio"])')) {
      expect(b.className, b.getAttribute('aria-label') ?? b.textContent ?? '').not.toMatch(SMALL);
    }
  });

  it('панель кнопок — рівна поверхня з лінією, не напівпрозоре скло', () => {
    snap = { ...BASE };
    render(<CallOverlay />);
    const dock = overlay().querySelector('[data-call-controls]') as HTMLElement;
    expect(dock.style.background).toBe('var(--ph-surface)');
    expect(dock.style.border).toContain('var(--ph-line)');
  });

  it('згорнута смуга: без тіні, цілі ≥48, таймер не зелений', () => {
    snap = { ...BASE };
    render(<CallOverlay />);
    fireEvent.click(screen.getByRole('button', { name: 'Згорнути дзвінок' }));
    const pill = document.querySelector('[data-call-pill]') as HTMLElement;
    expect(pill.className).not.toMatch(/shadow-/);
    expect(pill.innerHTML).not.toMatch(/--ph-success/);
    for (const b of pill.querySelectorAll<HTMLElement>('button')) expect(b.className).not.toMatch(SMALL);
  });

  it('завершений після розмови — підсумок із тривалістю', () => {
    const now = Date.now();
    snap = { ...BASE, state: 'active', startedAt: now - 65_000 };
    render(<CallOverlay />);
    act(() => setSnap({ ...snap, state: 'ended', endedReason: 'завершено' }));
    const line = overlay().querySelector('[data-call-line]') as HTMLElement;
    expect(line.textContent).toMatch(/^Дзвінок завершено · 01:0[56]$/);
    expect(overlay().getAttribute('data-call-state')).toBe('ended');
  });

  it('відхилений до розмови — без тривалості', () => {
    snap = { ...BASE, state: 'ended', startedAt: null, endedReason: 'відхилено' };
    render(<CallOverlay />);
    expect((overlay().querySelector('[data-call-line]') as HTMLElement).textContent).toBe('Дзвінок відхилено');
  });

  it('завершений: «Закрити» — рівна кнопка, «Передзвонити» — єдиний бурштин', () => {
    snap = { ...BASE, state: 'ended', startedAt: null, endedReason: 'завершено' };
    render(<CallOverlay />);
    const root = overlay();
    const close = screen.getByRole('button', { name: 'Закрити' });
    const again = screen.getByRole('button', { name: 'Передзвонити' });
    expect(close.style.background).toBe('var(--ph-raised)');
    expect(close.style.border).toContain('var(--ph-line)');
    expect(again.style.background).toBe('var(--ph-accent)');
    const amber = [...root.querySelectorAll<HTMLElement>('*')].filter((el) =>
      /var\(--ph-accent\)/.test(el.getAttribute('style') ?? ''),
    );
    expect(amber).toEqual([again]);
    expect(root.innerHTML).not.toMatch(FX);
    for (const b of root.querySelectorAll<HTMLElement>('button')) expect(b.className).not.toMatch(SMALL);
  });

  it('кожен tone у CallOverlay — з набору CallButtonTone', () => {
    const btn = readFileSync(resolve(__dirname, '../components/messenger/call/CallButton.tsx'), 'utf8');
    const union = /export type CallButtonTone = ([^;]+);/.exec(btn)![1];
    const tones = new Set([...union.matchAll(/'([a-z]+)'/g)].map((m) => m[1]));
    const overlaySrc = readFileSync(resolve(__dirname, '../components/messenger/CallOverlay.tsx'), 'utf8');
    const used = [...overlaySrc.matchAll(/tone="([a-z]+)"/g)].map((m) => m[1]);
    expect(used.length).toBeGreaterThan(0);
    expect(used.filter((t) => !tones.has(t))).toEqual([]);
  });

  it('немає доступу до мікрофона — чесний рядок замість «завершено»', () => {
    snap = { ...BASE, state: 'ended', startedAt: null, endedReason: 'доступ до мікрофона й камери не дано' };
    render(<CallOverlay />);
    expect((overlay().querySelector('[data-call-line]') as HTMLElement).textContent).toMatch(/мікрофона/);
  });

  it('налаштування: перемикачі режимів ≥48 і вибір живе після перевідкриття', () => {
    localStorage.clear();
    snap = { ...BASE };
    const first = render(<CallSettingsSheet open active />);
    const wifi = document.querySelector('[data-call-setting="wifi"]') as HTMLElement;
    const save = [...wifi.querySelectorAll<HTMLElement>('button')].find((b) => b.textContent === 'Економія')!;
    for (const b of document.querySelectorAll<HTMLElement>('[data-call-settings-sheet] button')) expect(b.className).not.toMatch(SMALL);
    fireEvent.click(save);
    first.unmount();
    expect(loadCallPrefs().wifi).toBe('save');
    render(<CallSettingsSheet open active />);
    const again = [...(document.querySelector('[data-call-setting="wifi"]') as HTMLElement).querySelectorAll('button')].find(
      (b) => b.textContent === 'Економія',
    )!;
    expect(again.getAttribute('aria-pressed')).toBe('true');
  });

  it('усі файли екрана дзвінка — без тіні, розмиття й градієнта', () => {
    const dir = resolve(__dirname, '../components/messenger');
    const files = [
      'CallOverlay.tsx',
      'CallSettingsSheet.tsx',
      'RingDowngradeSheet.tsx',
      'call/CallBackdrop.tsx',
      'call/CallButton.tsx',
      'call/RoutePicker.tsx',
      'call/SelfView.tsx',
      'call/QualityBars.tsx',
    ];
    for (const f of files) {
      const code = readFileSync(resolve(dir, f), 'utf8').replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '');
      expect(code, f).not.toMatch(/gradient\(|blur|backdrop-|backdropFilter|boxShadow|shadow-e\d/);
    }
  });
});

describe('вхід: слухавка в шапці розмови → екран дзвінка', () => {
  const ME = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;
  const dm = { id: 'chat_m', title: 'Марта', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'f0e1d2c3b4a59687' } as unknown as Chat;

  it('дотик по слухавці відкриває набір у стилі D', () => {
    snap = { ...BASE, state: 'idle', callId: null, startedAt: null };
    const root = readFileSync(resolve(__dirname, '../components/messenger/MessengerRoot.tsx'), 'utf8');
    expect(root).toMatch(/<CallOverlay \/>/);
    expect(root).toMatch(/addEventListener\('phantom:start-call'/);
    expect(root).toMatch(/callEngine\.startCall\(/);
    const onStart = (e: Event) => {
      const d = (e as CustomEvent).detail ?? {};
      setSnap({ ...BASE, state: 'calling', startedAt: null, media: d.video ? 'video' : 'audio', peer: { peerNodeId: dm.peerNodeId!, displayName: 'Марта' } });
    };
    window.addEventListener('phantom:start-call', onStart);
    render(
      <MemoryRouter>
        <Header currentChat={dm} currentUser={ME} onOpenSettings={() => {}} onOpenGroupDetails={() => {}} isSoundEnabled
          onToggleSound={() => {}} onToggleSearch={() => {}} isSearching={false} onBack={() => {}} />
        <CallOverlay />
      </MemoryRouter>,
    );
    expect(overlay()).toBeNull();
    act(() => {
      fireEvent.click(document.querySelector('[data-call-start="audio"]') as HTMLElement);
    });
    window.removeEventListener('phantom:start-call', onStart);
    expect(overlay().getAttribute('data-call-state')).toBe('calling');
    expect(overlay().innerHTML).not.toMatch(FX);
    const cancel = screen.getByRole('button', { name: /Скасувати/ });
    expect(cancel.style.background).toBe('var(--ph-danger)');
  });
});
