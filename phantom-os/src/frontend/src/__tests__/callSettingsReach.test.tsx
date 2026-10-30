import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';

let snap: CallSnapshot;
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: () => () => undefined,
      getSnapshot: () => snap,
      toggleMic: vi.fn(),
      toggleSpeaker: vi.fn(),
      toggleCamera: vi.fn(),
      flipCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
      setAudioOnly: vi.fn(),
      dataUsedBytes: vi.fn(async () => 4_200_000),
    },
  };
});

vi.mock('../services/api', () => ({
  request: vi.fn(async () => {
    const err = new Error('Not found') as Error & { status: number };
    err.status = 404;
    throw err;
  }),
  ApiError: class extends Error {},
}));

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

describe('CallOverlay відкриває налаштування дзвінка', () => {
  afterEach(() => cleanup());

  it('кнопка на картці розмови відкриває лист із живим лічильником даних', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    expect(document.querySelector('[data-call-settings-sheet]')).toBeNull();
    fireEvent.click(document.querySelector('[aria-label="Налаштування дзвінка"]') as HTMLElement);
    expect(document.querySelector('[data-call-settings-sheet]')).not.toBeNull();
    expect(document.querySelector('[data-call-setting="audio-only"]')).not.toBeNull();
    await vi.waitFor(() =>
      expect(document.querySelector('[data-call-data-used]')?.textContent).toBe('4,2 МБ'),
    );
  });

  it('T73: налаштування — у шапці праворуч, а не п’ятою кнопкою в панелі', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    const btn = document.querySelector('[data-call-settings-btn]') as HTMLElement;
    const minimize = document.querySelector('[aria-label="Згорнути дзвінок"]') as HTMLElement;
    expect(btn.closest('[data-call-controls]')).toBeNull();
    expect(btn.parentElement).toBe(minimize.parentElement);
    expect(btn.parentElement?.lastElementChild).toBe(btn);
  });
});
