import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { useVoiceNote } from '../hooks/useVoiceRecorder';
import { MessageComposer } from '../components/messenger/MessageComposer';

class FakeStream {
  _tracks = [{ stop: vi.fn() }];
  getTracks() {
    return this._tracks;
  }
}

class FakeMediaRecorder {
  static isTypeSupported = vi.fn(() => true);
  static instances: Array<{ stream: FakeStream; options?: MediaRecorderOptions }> = [];

  ondataavailable: ((ev: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: ((ev: { error: Error }) => void) | null = null;
  state: 'inactive' | 'recording' | 'paused' = 'inactive';
  mimeType = 'audio/webm;codecs=opus';

  constructor(public stream: FakeStream, public options?: MediaRecorderOptions) {
    FakeMediaRecorder.instances.push({ stream, options });
  }

  start(_interval?: number) {
    this.state = 'recording';
  }

  pause() {
    this.state = 'paused';
  }

  resume() {
    this.state = 'recording';
  }

  stop() {
    this.state = 'inactive';
    this.ondataavailable?.({ data: new Blob([new Uint8Array([1, 2, 3])], { type: this.mimeType }) });
    this.onstop?.();
  }
}

class FakeAnalyser {
  fftSize = 512;
  getByteTimeDomainData(arr: Uint8Array) {
    arr.fill(128);
  }
  disconnect() {
    /* noop */
  }
}

class FakeAudioContext {
  createMediaStreamSource() {
    return { connect: vi.fn() };
  }
  createAnalyser() {
    return new FakeAnalyser();
  }
  close() {
    /* noop */
  }
}

describe('useVoiceNote modes and composer Music entry', () => {
  let getUserMediaMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    FakeMediaRecorder.instances = [];
    FakeMediaRecorder.isTypeSupported = vi.fn(() => true);
    getUserMediaMock = vi.fn().mockResolvedValue(new FakeStream());
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: getUserMediaMock },
      configurable: true,
    });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).MediaRecorder = FakeMediaRecorder;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).AudioContext = FakeAudioContext;
  });

  afterEach(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    delete (globalThis as any).MediaRecorder;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    delete (globalThis as any).AudioContext;
  });

  it('start() requests 48 kHz mono with noiseSuppression and 48 kbps MediaRecorder', async () => {
    const { result } = renderHook(() => useVoiceNote());

    await act(async () => {
      await result.current.start();
    });

    expect(result.current.mode).toBe('voice');
    expect(getUserMediaMock).toHaveBeenCalledWith({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
        sampleRate: 48000,
      },
    });
    expect(FakeMediaRecorder.instances).toHaveLength(1);
    expect(FakeMediaRecorder.instances[0].options).toEqual({
      mimeType: 'audio/webm;codecs=opus',
      audioBitsPerSecond: 48000,
    });
  });

  it("start('music') disables processing, requests stereo 48 kHz at 128 kbps, and reports mode === 'music'", async () => {
    const { result } = renderHook(() => useVoiceNote());

    await act(async () => {
      await result.current.start('music');
    });

    expect(result.current.mode).toBe('music');
    expect(getUserMediaMock).toHaveBeenCalledWith({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        channelCount: 2,
        sampleRate: 48000,
      },
    });
    expect(FakeMediaRecorder.instances).toHaveLength(1);
    expect(FakeMediaRecorder.instances[0].options).toEqual({
      mimeType: 'audio/webm;codecs=opus',
      audioBitsPerSecond: 128000,
    });
  });

  it('T34: AttachSheet "Music" opens an audio file picker, never the microphone', async () => {
    const clicked: HTMLInputElement[] = [];
    const click = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function (this: HTMLInputElement) {
      clicked.push(this);
    });
    render(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(screen.getByTestId('send-what-sheet')).toBeTruthy();

    await act(async () => {
      fireEvent.click(screen.getByTestId('send-music'));
    });

    expect(screen.queryByTestId('send-what-sheet')).toBeNull();
    expect(screen.queryByTestId('voice-locked')).toBeNull();
    expect(getUserMediaMock).not.toHaveBeenCalled();
    expect(FakeMediaRecorder.instances).toHaveLength(0);
    expect(clicked).toHaveLength(1);
    expect(clicked[0].dataset.testid).toBe('composer-music-input');
    expect(clicked[0].accept).toBe('audio/*');
    click.mockRestore();
  });
});
