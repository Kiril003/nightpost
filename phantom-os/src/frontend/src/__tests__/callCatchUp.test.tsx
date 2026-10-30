import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
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
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
    },
  };
});

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
};

const remoteEar = (): HTMLVideoElement => document.querySelector('video.hidden') as HTMLVideoElement;

afterEach(cleanup);

describe('media-28: живий звук чекає, поки дограє хвіст рації', () => {
  it('догін іде — живий звук мовчить; дограв — звучить', async () => {
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    snap = { ...BASE, catchingUp: true };
    render(React.createElement(CallOverlay));
    expect(remoteEar().muted).toBe(true);
    cleanup();

    snap = { ...BASE, catchingUp: false };
    render(React.createElement(CallOverlay));
    expect(remoteEar().muted).toBe(false);
  });
});
