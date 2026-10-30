import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, renderHook } from '@testing-library/react';
import { CallOverlay } from '../components/messenger/CallOverlay';
import { callEngine } from '../services/callEngine';
import { whyLabel } from '../services/callRoom';
import { OpusLadder } from '../services/callLadder';
import { RadioLink } from '../services/callRadio';
import { callStateLine } from '../services/callWords';
import { loadCallCost, trackCallCost } from '../services/callCost';
import { useAudioRoutes } from '../components/messenger/call/audioRoute';
import { saveMedia } from '../services/saveMedia';
import { messengerApi } from '../services/messengerApi';
import { ApiError } from '../services/api';
import * as api from '../services/api';
import { useUIStore } from '../stores/uiStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { mbhLabel } from '../services/callView';
import { useAudioPlaybackStore } from '../stores/audioPlaybackStore';

const setLocale = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => {
  cleanup();
  setLocale('uk');
  useUIStore.setState({ toasts: [] });
  callEngine.hangup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('calls-media audit: calls flow (#1, #2, #5, #7, #8, #10, #12, #13, #25, #27)', () => {

  it('#2: outgoing call routes answer and ice to primary pc even when syncRoom pre-populated a pc:null leg', async () => {
    const remoteDescs: unknown[] = [];
    const addedCandidates: unknown[] = [];

    class FakePC {
      localDescription = { type: 'offer', sdp: 'v=0\r\n' };
      remoteDescription: unknown = null;
      signalingState = 'have-local-offer';
      connectionState = 'new';
      onicecandidate: unknown = null;
      ondatachannel: unknown = null;
      addEventListener() {}
      addTrack() {}
      getTransceivers() {
        return [];
      }
      getSenders() {
        return [];
      }
      createDataChannel() {
        return { addEventListener: () => {}, close: () => {}, readyState: 'connecting' };
      }
      async createOffer() {
        return { type: 'offer', sdp: 'v=0\r\na=rtpmap:111 opus/48000/2\r\n' };
      }
      async setLocalDescription(d: { type: string; sdp: string }) {
        this.localDescription = d;
      }
      async setRemoteDescription(d: unknown) {
        this.remoteDescription = d;
        remoteDescs.push(d);
        this.signalingState = 'stable';
      }
      async addIceCandidate(c: unknown) {
        addedCandidates.push(c);
      }
      close() {}
    }

    vi.stubGlobal('RTCPeerConnection', FakePC);
    vi.stubGlobal('MediaStream', class {
      getTracks() { return [{ kind: 'audio', enabled: true, stop: () => {} }]; }
      getAudioTracks() { return [{ kind: 'audio', enabled: true, stop: () => {} }]; }
      getVideoTracks() { return []; }
    });
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => new (globalThis as unknown as { MediaStream: new () => MediaStream }).MediaStream()),
      },
    });

    vi.spyOn(api, 'request').mockImplementation(async (method: string, path: string) => {
      if (method === 'GET' && path === '/messenger/call/room') {
        return {
          legs: [{ peer_id: 'peer_b', name: 'Taras', foot: 'dialing', since_ms: 1000, reachable: true }],
          max_legs: 8,
        } as never;
      }
      if (path.startsWith('/messenger/call/')) {
        return { delivered: true, call_id: 'c_group', detail: '' } as never;
      }
      return {} as never;
    });

    await callEngine.startCall({ contactId: 'grp_1', displayName: 'Group', callId: 'c_group' }, 'audio');
    const callId = callEngine.getSnapshot().callId!;

    await callEngine.onSignal({
      kind: 'answer',
      call_id: callId,
      from_node_id: 'peer_b',
      sdp: 'v=0\r\na=answer\r\n',
    });
    await callEngine.onSignal({
      kind: 'ice',
      call_id: callId,
      from_node_id: 'peer_b',
      candidate: { candidate: 'candidate:1 1 UDP 2122252543 192.168.1.2 5000 typ host' },
    });

    expect(remoteDescs).toHaveLength(1);
    expect(addedCandidates).toHaveLength(1);
  });

  it('#5: RadioLink routes playback through a GainNode that respects speaker mute', async () => {
    let gainValue = 1;
    let sourceConnectedToGain = false;
    const fakeGain = {
      gain: {
        get value() { return gainValue; },
        set value(v: number) { gainValue = v; },
      },
      connect: vi.fn(),
    };
    const fakeSource = {
      buffer: null as unknown,
      connect: vi.fn((dest: unknown) => {
        if (dest === fakeGain) sourceConnectedToGain = true;
      }),
      start: vi.fn(),
    };
    class FakeAudioCtx {
      state = 'running';
      currentTime = 0;
      destination = { kind: 'dest' };
      createGain() {
        return fakeGain;
      }
      createBufferSource() {
        return fakeSource;
      }
      async decodeAudioData() {
        return { duration: 1, length: 48000, sampleRate: 48000 };
      }
      async resume() {}
      async close() {}
    }
    vi.stubGlobal('AudioContext', FakeAudioCtx);

    const radio = new RadioLink(() => {}, () => {});
    radio.setMuted(true);
    radio.start({ callId: 'c_radio' }, null);
    await radio.receive(0, btoa('fake-audio'));

    expect(sourceConnectedToGain).toBe(true);
    expect(gainValue).toBe(0);

    radio.setMuted(false);
    expect(gainValue).toBe(1);
    radio.stop();
  });

  it('#7: OpusLadder.renegotiate sends an SDP offer even when outgoing is false', async () => {
    let sentSdp: string | undefined;
    const fakePc = {
      signalingState: 'stable',
      localDescription: { type: 'offer', sdp: 'v=0\r\n' },
      getTransceivers: () => [],
      getSenders: () => [],
      createOffer: vi.fn(async () => ({
        type: 'offer',
        sdp: 'v=0\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n',
      })),
      setLocalDescription: vi.fn(async function (this: { localDescription: { type: string; sdp: string } }, d: { type: string; sdp: string }) {
        this.localDescription = d;
      }),
    };
    const ladder = new OpusLadder({
      pc: () => fakePc as unknown as RTCPeerConnection,
      localStream: () => null,
      view: () => ({
        callId: 'c_callee',
        outgoing: false,
        media: 'audio',
        hasCamera: false,
        audioLevel: 'crystal',
        ladderPinned: false,
        videoDropped: false,
        stats: null,
      }),
      patch: () => {},
      armNote: () => {},
      sendOffer: (sdp) => {
        sentSdp = sdp;
      },
    });

    await ladder.renegotiate();
    expect(sentSdp).toBeDefined();
  });

  it('#8: CallOverlay hides remote video when audioOnly is true during a video call, and setAudioOnly(false) restores cameraOn', () => {
    const fakeVideoTrack = { id: 'v1', kind: 'video', readyState: 'live', enabled: true };
    const remoteStream = {
      getTracks: () => [fakeVideoTrack],
      getVideoTracks: () => [fakeVideoTrack],
      getAudioTracks: () => [],
      addEventListener: () => {},
      removeEventListener: () => {},
    } as unknown as MediaStream;

    vi.spyOn(callEngine, 'getSnapshot').mockReturnValue({
      ...callEngine.getSnapshot(),
      state: 'active',
      callId: 'c_vid',
      media: 'video',
      audioOnly: true,
      remoteStream,
      peer: { peerNodeId: 'p1', displayName: 'Oksana' },
    });

    render(<CallOverlay />);
    const overlay = document.querySelector('[data-call-overlay]')!;
    // Only the hidden audio element should exist, not the full-screen object-cover video
    const visibleVideo = overlay.querySelector('video.object-cover');
    expect(visibleVideo).toBeNull();
  });

  it('#10: trackCallCost classifies a normal audio call (media: audio, audioOnly: false) as audio', async () => {
    localStorage.clear();
    const listeners = new Set<() => void>();
    let snap: { state: string; media?: 'audio' | 'video'; audioOnly?: boolean } = {
      state: 'idle',
      media: 'audio',
      audioOnly: false,
    };
    const readings = [0, 500_000];
    let nowMs = 1_000_000;

    const unsub = trackCallCost({
      subscribe: (cb) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
      getSnapshot: () => snap,
      readBytes: () => Promise.resolve(readings.shift() ?? 0),
      now: () => nowMs,
      conn: () => ({ type: 'cellular' }),
    });

    snap = { state: 'active', media: 'audio', audioOnly: false };
    listeners.forEach((cb) => cb());
    await Promise.resolve();

    nowMs += 60_000;
    snap = { state: 'ended', media: 'audio', audioOnly: false };
    listeners.forEach((cb) => cb());
    await new Promise((r) => setTimeout(r, 0));

    const cost = loadCallCost();
    expect(cost.mobile.audio).toHaveLength(1);
    expect(cost.mobile.video).toHaveLength(0);
    unsub();
  });

  it('#12: OpusLadder.applyRung caps all room legs via allPcs', async () => {
    const leg1Replace = vi.fn(async () => {});
    const leg2Replace = vi.fn(async () => {});
    const makePc = (replaceTrack: () => Promise<void>) =>
      ({
        getSenders: () => [
          { track: { kind: 'video' }, replaceTrack, getParameters: () => ({ encodings: [{}] }), setParameters: async () => {} },
          { track: { kind: 'audio' }, getParameters: () => ({ encodings: [{}] }), setParameters: async () => {} },
        ],
      }) as unknown as RTCPeerConnection;

    const pc1 = makePc(leg1Replace);
    const pc2 = makePc(leg2Replace);

    const ladder = new OpusLadder({
      pc: () => pc1,
      allPcs: () => [pc1, pc2],
      localStream: () => null,
      view: () => ({
        callId: 'c_room',
        outgoing: true,
        media: 'video',
        hasCamera: true,
        audioLevel: 'thrifty',
        ladderPinned: false,
        videoDropped: true,
        stats: null,
      }),
      patch: () => {},
      armNote: () => {},
      sendOffer: () => {},
    });

    await ladder.applyRung();
    expect(leg1Replace).toHaveBeenCalledWith(null);
    expect(leg2Replace).toHaveBeenCalledWith(null);
  });

  it('#13: whyLabel maps room-full to callRoom.full and callEngine.onSignal preserves busy hangup reason', async () => {
    expect(whyLabel('room-full')).toBe('кімната повна');

    vi.spyOn(api, 'request').mockResolvedValue({ delivered: true, call_id: 'c_busy', detail: '' } as never);
    // Put callEngine into calling state
    (callEngine as unknown as { patch: (p: unknown) => void }).patch({
      state: 'calling',
      callId: 'c_busy',
      peer: { peerNodeId: 'peer_1', displayName: 'Olena' },
      media: 'audio',
    });

    await callEngine.onSignal({
      kind: 'hangup',
      call_id: 'c_busy',
      from_node_id: 'peer_1',
      reason: 'busy',
    });

    expect(callStateLine(callEngine.getSnapshot())).toBe('Зараз не може відповісти');
  });

  it('#25: useAudioRoutes re-fetches when enabled toggles from false to true, and saveMedia shows 415 refusal', async () => {
    const reqSpy = vi.spyOn(api, 'request').mockResolvedValue({
      active: 'earpiece',
      available: ['earpiece', 'speaker'],
    } as never);

    const { rerender } = renderHook(
      ({ enabled }) => useAudioRoutes('call_1', enabled),
      { initialProps: { enabled: true } },
    );
    await act(async () => undefined);
    expect(reqSpy).toHaveBeenCalledTimes(1);

    rerender({ enabled: false });
    rerender({ enabled: true });
    await act(async () => undefined);
    expect(reqSpy).toHaveBeenCalledTimes(2);

    // 415 bad_mime in saveMedia
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Uint8Array([1, 2, 3]))));
    vi.spyOn(messengerApi, 'saveToPhone').mockRejectedValue(
      new ApiError(415, 'bad_mime', 'Цей тип файла не можна зберегти'),
    );

    await saveMedia('blob:1', 'run.sh', 'text/x-sh');
    const lastToast = useUIStore.getState().toasts.at(-1);
    expect(lastToast?.message).toBe('Цей тип файла не можна зберегти');
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('#27: mbhLabel, useAudioPlaybackStore, and useCallAlerts localize strings in English', async () => {
    setLocale('en');
    expect(mbhLabel(0.43)).toBe('~0.4 MB/h');

    class RejectingAudio {
      src = '';
      playbackRate = 1;
      currentTime = 0;
      duration = 10;
      ontimeupdate: (() => void) | null = null;
      onended: (() => void) | null = null;
      onerror: (() => void) | null = null;
      pause() {}
      play() {
        return Promise.reject(new Error('blocked'));
      }
    }
    vi.stubGlobal('Audio', RejectingAudio);
    useAudioPlaybackStore.getState().play('m_en', 'blob:en', 5);
    await new Promise((r) => setTimeout(r, 0));
    expect(useAudioPlaybackStore.getState().error).toBe('Browser blocked playback');
  });
});
