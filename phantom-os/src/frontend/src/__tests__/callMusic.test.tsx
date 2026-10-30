import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { audioCapture, setMusicMode, tuneOpus } from '../services/callOpus';
import { callEngine } from '../services/callEngine';
import type { CallSnapshot } from '../services/callEngine';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';

const route = vi.hoisted(() => ({ current: 'speaker' }));
vi.mock('../services/api', async (orig) => ({
  ...(await orig<typeof import('../services/api')>()),
  request: vi.fn(async () => ({ routes: ['earpiece', 'speaker'], current: route.current })),
}));

const SDP = ['v=0', 'm=audio 9 UDP/TLS/RTP/SAVPF 111', 'a=rtpmap:111 opus/48000/2', 'a=fmtp:111 minptime=10;useinbandfec=1'].join('\r\n');
const fmtp = (sdp: string) => {
  const line = sdp.split('\r\n').find((l) => l.startsWith('a=fmtp:111 ')) ?? '';
  return Object.fromEntries(line.slice('a=fmtp:111 '.length).split(';').map((kv) => kv.split('=')));
};

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => setLocale('en'));

afterEach(async () => {
  cleanup();
  vi.restoreAllMocks();
  setMusicMode(false);
  await callEngine.setMusicMode(false);
  Reflect.set(callEngine, 'pc', null);
  setLocale('uk');
});

describe('media-18: інструмент у дзвінку', () => {
  it('музика на верхній сходинці — стерео і 128 кбіт/с у fmtp', () => {
    expect(fmtp(tuneOpus(SDP, 'crystal'))).toMatchObject({ stereo: '0', maxaveragebitrate: '64000' });
    setMusicMode(true);
    const music = fmtp(tuneOpus(SDP, 'crystal'));
    expect(music).toMatchObject({ stereo: '1', 'sprop-stereo': '1', maxaveragebitrate: '128000', usedtx: '0' });
    expect(fmtp(tuneOpus(SDP, 'full')).maxaveragebitrate).toBe('128000');
  });

  it('на вузькому каналі музика поступається голосу', () => {
    setMusicMode(true);
    expect(fmtp(tuneOpus(SDP, 'narrow'))).toMatchObject({ stereo: '0', maxaveragebitrate: '10000' });
  });

  it('мікрофон без обробки голосу лише в режимі інструмента', () => {
    expect(audioCapture().echoCancellation).toBe(true);
    setMusicMode(true);
    expect(audioCapture()).toMatchObject({ echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 2 });
  });

  it('перемикач у живому дзвінку міняє доріжку і стелю відправника', async () => {
    const old = { kind: 'audio', enabled: false, stop: vi.fn(), applyConstraints: vi.fn(async () => undefined) };
    const fresh = { kind: 'audio', enabled: true, stop: vi.fn() };
    let params: RTCRtpSendParameters = { encodings: [{}], transactionId: 't', codecs: [], headerExtensions: [], rtcp: {} };
    const sender = {
      track: old,
      replaceTrack: vi.fn(async () => undefined),
      getParameters: () => params,
      setParameters: vi.fn(async (p: RTCRtpSendParameters) => {
        params = p;
      }),
    };
    Reflect.set(callEngine, 'pc', { getSenders: () => [sender] });
    const getUserMedia = vi.fn(async () => ({ getAudioTracks: () => [fresh] }));
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } });

    await callEngine.setMusicMode(true);

    expect(callEngine.getSnapshot().musicMode).toBe(true);
    expect(getUserMedia).toHaveBeenCalledWith({ audio: expect.objectContaining({ echoCancellation: false }) });
    expect(sender.replaceTrack).toHaveBeenCalledWith(fresh);
    expect(fresh.enabled).toBe(false);
    expect(old.stop).toHaveBeenCalled();
    expect(params.encodings[0].maxBitrate).toBe(128000);
  });

  it('replaceTrack не вдався — свіжий мікрофон закрито, стара доріжка лишається', async () => {
    const old = { kind: 'audio', enabled: true, stop: vi.fn(), applyConstraints: vi.fn(async () => undefined) };
    const fresh = { kind: 'audio', enabled: true, stop: vi.fn() };
    const sender = {
      track: old,
      replaceTrack: vi.fn(async () => {
        throw new Error('InvalidModificationError');
      }),
      getParameters: () => ({ encodings: [{}], transactionId: 't', codecs: [], headerExtensions: [], rtcp: {} }),
      setParameters: vi.fn(async () => undefined),
    };
    Reflect.set(callEngine, 'pc', { getSenders: () => [sender] });
    const getUserMedia = vi.fn(async () => ({ getAudioTracks: () => [fresh] }));
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } });

    await callEngine.setMusicMode(true);

    expect(fresh.stop).toHaveBeenCalled();
    expect(old.stop).not.toHaveBeenCalled();
    expect(old.applyConstraints).toHaveBeenCalledWith(expect.objectContaining({ echoCancellation: false }));
  });

  it('налаштування дзвінка: перемикач кличе рушій', () => {
    const spy = vi.spyOn(callEngine, 'setMusicMode').mockResolvedValue(undefined);
    render(<CallSettingsSheet active={true} readDataUsedBytes={() => null} />);
    const toggle = screen.getByRole('switch', { name: 'Instrument sound' });
    expect(toggle.className).toContain('min-h-[48px]');
    act(() => {
      fireEvent.click(toggle);
    });
    expect(spy).toHaveBeenCalledWith(true);
  });

  it('звук у гучномовці — підказка про навушники; у трубці — без неї', async () => {
    const snap: CallSnapshot = { ...callEngine.getSnapshot(), callId: 'c1', state: 'active', musicMode: true };
    vi.spyOn(callEngine, 'getSnapshot').mockReturnValue(snap);
    route.current = 'speaker';
    render(<CallSettingsSheet readDataUsedBytes={() => null} />);
    await waitFor(() => expect(screen.getByText('Use headphones')).toBeTruthy());
    cleanup();

    route.current = 'earpiece';
    render(<CallSettingsSheet readDataUsedBytes={() => null} />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByText('Use headphones')).toBeNull();
  });
});
