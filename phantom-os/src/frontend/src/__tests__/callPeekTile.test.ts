import { afterEach, describe, expect, it, vi } from 'vitest';
import { callEngine } from '../services/callEngine';
import type { CallSnapshot } from '../services/callEngine';

class FakeStream {
  tracks: MediaStreamTrack[] = [];
  addTrack(t: MediaStreamTrack) {
    this.tracks.push(t);
  }
  removeTrack(t: MediaStreamTrack) {
    this.tracks = this.tracks.filter((x) => x !== t);
  }
  getTrackById(id: string) {
    return this.tracks.find((t) => t.id === id) ?? null;
  }
  getTracks() {
    return this.tracks;
  }
  getVideoTracks() {
    return this.tracks.filter((t) => t.kind === 'video');
  }
}

let built: { ontrack: ((e: { track: MediaStreamTrack; streams: MediaStream[] }) => void) | null } | null = null;
class FakePc {
  ontrack: ((e: { track: MediaStreamTrack; streams: MediaStream[] }) => void) | null = null;
  connectionState = 'new';
  signalingState = 'stable';
  constructor() {
    // eslint-disable-next-line @typescript-eslint/no-this-alias
    built = this;
  }
  createDataChannel() {
    return { label: 'view', readyState: 'connecting' };
  }
  addEventListener() {}
  getSenders() {
    return [];
  }
  getReceivers() {
    return [];
  }
  getTransceivers() {
    return [];
  }
  close() {}
}

const patch = (p: Partial<CallSnapshot>) => (Reflect.get(callEngine, 'patch') as (p: Partial<CallSnapshot>) => void).call(callEngine, p);
const build = () => (Reflect.get(callEngine, 'buildPeerConnection') as (id: string, ice: { iceServers: RTCIceServer[] }) => unknown).call(callEngine, 'c1', { iceServers: [] });

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.set(callEngine, 'pc', null);
  patch({ state: 'idle', media: 'audio', callId: null, remoteStream: null });
});

describe('media-19: плитка показу в співрозмовника', () => {
  it('голосовий дзвінок: відео приїхало — у потоці; змовкло — зникло; ожило — знову', () => {
    vi.stubGlobal('MediaStream', FakeStream);
    vi.stubGlobal('RTCPeerConnection', FakePc);
    patch({ state: 'active', media: 'audio', outgoing: false, callId: 'c1' });
    build();
    const track = { id: 'v1', kind: 'video', enabled: true, onmute: null, onunmute: null } as unknown as MediaStreamTrack;
    built!.ontrack!({ track, streams: [] });
    const remote = () => callEngine.getSnapshot().remoteStream as unknown as FakeStream;
    expect(remote().getVideoTracks()).toHaveLength(1);

    track.onmute!(new Event('mute'));
    expect(remote().getVideoTracks()).toHaveLength(0);

    track.onunmute!(new Event('unmute'));
    expect(remote().getVideoTracks()).toHaveLength(1);
  });

  it('відеодзвінок: змовкле відео лишається (тут це просто пауза кадрів)', () => {
    vi.stubGlobal('MediaStream', FakeStream);
    vi.stubGlobal('RTCPeerConnection', FakePc);
    patch({ state: 'active', media: 'video', outgoing: false, callId: 'c1' });
    build();
    const track = { id: 'v2', kind: 'video', enabled: true, onmute: null, onunmute: null } as unknown as MediaStreamTrack;
    built!.ontrack!({ track, streams: [] });
    expect(track.onmute).toBeNull();
  });
});
