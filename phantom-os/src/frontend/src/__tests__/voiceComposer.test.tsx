/**
 * W0-6 з реального входу: мікрофон композера месенджера. Палець (pointer
 * touch), фальшивий мікрофон і MediaRecorder — і перевірка, що жест справді
 * веде запис: дотик не пише, утримання шле, свайп уліво знищує, замок
 * тримає запис без пальця, «Стоп» дає перегляд, а «Надіслати» шле ТОЙ САМИЙ
 * запис. Клавіатура записує без утримання.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';

import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { probeMicGrant } from '../hooks/useVoiceRecorder';

class FakePointerEvent extends MouseEvent {
  pointerId: number;
  pointerType: string;
  constructor(type: string, init: PointerEventInit = {}) {
    super(type, init);
    this.pointerId = init.pointerId ?? 1;
    this.pointerType = init.pointerType ?? 'touch';
  }
}

class ReadableBlob extends Blob {
  arrayBuffer() {
    return Promise.resolve(new TextEncoder().encode('opus-bytes').buffer as ArrayBuffer);
  }
}

let recorders: FakeRecorder[] = [];
class FakeRecorder {
  static isTypeSupported = () => true;
  state: 'inactive' | 'recording' | 'paused' = 'inactive';
  mimeType = 'audio/webm;codecs=opus';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  constructor() {
    recorders.push(this);
  }
  start() {
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
    this.ondataavailable?.({ data: new Blob(['opus-bytes'], { type: this.mimeType }) });
    queueMicrotask(() => this.onstop?.());
  }
}

let getUserMedia: ReturnType<typeof vi.fn>;
let micPerm: PermissionState = 'prompt';
let trackStops: number;

const props = (onSendVoiceMessage = vi.fn()) => ({
  onSendMessage: () => {},
  onSendVoiceMessage,
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'oksana',
});

beforeEach(async () => {
  micPerm = 'prompt';
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    value: { query: async () => ({ state: micPerm }) },
  });
  await probeMicGrant();
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance'] });
  recorders = [];
  trackStops = 0;
  getUserMedia = vi.fn(async () => ({ getTracks: () => [{ stop: () => (trackStops += 1) }] }));
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } });
  vi.stubGlobal('MediaRecorder', FakeRecorder);
  vi.stubGlobal('Blob', ReadableBlob);
  vi.stubGlobal('PointerEvent', FakePointerEvent);
  URL.createObjectURL = vi.fn(() => 'blob:voice');
  URL.revokeObjectURL = vi.fn();
  // jsdom не грає звук: перегляд перевіряємо тим, що плеєр просили грати.
  HTMLMediaElement.prototype.play = vi.fn(async () => {});
  HTMLMediaElement.prototype.pause = vi.fn();
  // Телефонна оболонка: без розпізнавання на ПК і з мостом гаптики.
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
    haptic: vi.fn(),
    call: () => JSON.stringify({ ok: true, status: 200, data: {} }),
  };
  useMessengerStore.setState({ storageFailure: null } as never);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

const mic = () => screen.getByTestId('composer-media-button');
const press = (x = 370, y = 700) => fireEvent.pointerDown(mic(), { pointerId: 1, pointerType: 'touch', clientX: x, clientY: y });
const moveTo = (x: number, y: number) => fireEvent.pointerMove(mic(), { pointerId: 1, pointerType: 'touch', clientX: x, clientY: y });
/** Після замка мікрофон уже став «Надіслати» — палець відпускають над порожнім місцем. */
const release = () => {
  const el = screen.queryByTestId('composer-media-button');
  if (el) fireEvent.pointerUp(el, { pointerId: 1, pointerType: 'touch' });
};
const wait = (ms: number) => act(async () => {
  await vi.advanceTimersByTimeAsync(ms);
});
const flush = () => act(async () => {
  await Promise.resolve();
  await Promise.resolve();
  await vi.advanceTimersByTimeAsync(0);
});

describe('мікрофон композера', () => {
  it('media-24: мікрофон уже дозволено — тап відкриває його від дотику й одразу закриває, нічого не надіслано', async () => {
    micPerm = 'granted';
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    await flush();
    press();
    await flush();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    await wait(150);
    release();
    await flush();
    expect(trackStops).toBe(1);
    expect(send).not.toHaveBeenCalled();
    expect(screen.queryByTestId('voice-bin')).toBeNull();
  });

  it('media-24: утримання підхоплює запис від дотику — мікрофон один, перший шматок не втрачено', async () => {
    micPerm = 'granted';
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    await flush();
    press();
    await flush();
    const rec = recorders.at(-1);
    expect(rec?.state).toBe('recording');
    await wait(200);
    expect(screen.getByTestId('voice-holding')).toBeInTheDocument();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(recorders).toHaveLength(1);
    expect(recorders[0]).toBe(rec);
  });

  it('media-24: система забрала палець до утримання — мікрофон закрито, без смітника й без листа', async () => {
    micPerm = 'granted';
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    await flush();
    press();
    await flush();
    fireEvent.pointerCancel(mic(), { pointerId: 1, pointerType: 'touch' });
    await flush();
    await wait(300);
    expect(trackStops).toBe(1);
    expect(send).not.toHaveBeenCalled();
    expect(screen.queryByTestId('voice-bin')).toBeNull();
    expect(screen.queryByTestId('voice-holding')).toBeNull();
  });

  it('утримання → запис; відпустив → надіслано голосове з байтами, мікрофон закрито', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press();
    await wait(200);
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('voice-holding')).toBeInTheDocument();
    expect(screen.getByTestId('voice-lock')).toBeInTheDocument();
    await wait(2100);
    release();
    await flush();
    await flush();
    expect(send).toHaveBeenCalledTimes(1);
    const [duration, transcript, blob] = send.mock.calls[0];
    expect(duration).toBe(2);
    expect(transcript).toBe('');
    expect((blob as Blob).size).toBeGreaterThan(0);
    expect(trackStops).toBe(1);
    expect(screen.queryByTestId('voice-holding')).toBeNull();
  });

  it('свайп уліво на 40 % ширини — запис знищено, нічого не надіслано, смітник на місці мікрофона', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press(370, 700);
    await wait(200);
    // Ширина рядка в jsdom — 0, тож мірою стає вікно (1024 у jsdom): 40 % ≈ 410.
    moveTo(370 - window.innerWidth * 0.4 - 1, 700);
    await flush();
    release();
    await flush();
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByTestId('voice-bin')).toBeInTheDocument();
    expect(trackStops).toBe(1);
    const bridge = (globalThis as { PhantomBridge?: { haptic: ReturnType<typeof vi.fn> } }).PhantomBridge!;
    expect(bridge.haptic).toHaveBeenCalledWith('warning');
    await wait(600);
    expect(screen.queryByTestId('voice-bin')).toBeNull();
  });

  it('замок угору 64: запис без пальця → «Стоп» → перегляд → «Надіслати» шле той самий запис', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press(370, 700);
    await wait(200);
    moveTo(370, 700 - 64);
    await flush();
    release();
    await wait(3000);
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByTestId('voice-locked')).toBeInTheDocument();
    expect(screen.getByTestId('voice-locked')).toHaveTextContent('0:03');
    // Пауза є, бо цей MediaRecorder її вміє; на паузі час стоїть.
    fireEvent.click(screen.getByTestId('voice-pause'));
    await wait(5000);
    expect(screen.getByTestId('voice-locked')).toHaveTextContent('0:03');
    fireEvent.click(screen.getByTestId('voice-pause'));
    await wait(1000);
    fireEvent.click(screen.getByTestId('voice-stop'));
    await flush();
    await flush();
    expect(screen.getByTestId('voice-preview')).toBeInTheDocument();
    expect(screen.getByTestId('voice-play')).not.toBeDisabled();
    expect(document.querySelector('audio')?.getAttribute('src')).toBe('blob:voice');
    fireEvent.click(screen.getByTestId('voice-play'));
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalled();
    const previewBlob = (URL.createObjectURL as ReturnType<typeof vi.fn>).mock.calls[0][0] as Blob;
    fireEvent.click(screen.getByTestId('voice-send'));
    await flush();
    await flush();
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0][0]).toBe(4);
    expect(send.mock.calls[0][2]).toBe(previewBlob);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:voice');
  });

  it('у перегляді «Видалити» знищує запис і нічого не шле', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press(370, 700);
    await wait(200);
    moveTo(370, 600);
    release();
    await wait(2000);
    fireEvent.click(screen.getByTestId('voice-stop'));
    await flush();
    await flush();
    fireEvent.click(screen.getByTestId('voice-delete'));
    await flush();
    expect(screen.queryByTestId('voice-preview')).toBeNull();
    expect(send).not.toHaveBeenCalled();
  });

  it('клавіатура (Enter → click без дотику) — запис без утримання, одразу в замку', async () => {
    render(<MessageComposer {...props()} />);
    fireEvent.click(mic());
    await flush();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('voice-locked')).toBeInTheDocument();
  });

  it('до дозволу: система забрала палець до утримання — ні мікрофона, ні смітника', async () => {
    render(<MessageComposer {...props()} />);
    await flush();
    press();
    await flush();
    fireEvent.pointerCancel(mic(), { pointerId: 1, pointerType: 'touch' });
    await wait(300);
    expect(getUserMedia).not.toHaveBeenCalled();
    expect(screen.queryByTestId('voice-bin')).toBeNull();
  });

  it('мікрофон не дали — жодного «запису» на екрані, чесне слово людині', async () => {
    getUserMedia.mockRejectedValueOnce(new Error('NotAllowedError'));
    const toast = vi.spyOn(useUIStore.getState(), 'toast');
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press();
    await wait(200);
    await flush();
    expect(screen.queryByTestId('voice-holding')).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('мікрофона') }));
    release();
    await flush();
    expect(send).not.toHaveBeenCalled();
  });

  it('кружечок — окрема плитка вкладень, а не другий режим мікрофона', () => {
    render(<MessageComposer {...props()} />);
    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(screen.getByTestId('send-video-note')).toHaveTextContent('Кружечок');
  });

  it('T36: a quick tap toggles mic ⇄ round video and remembers it; holding in video mode opens the recorder', async () => {
    localStorage.removeItem('phantom.micMode');
    render(<MessageComposer {...props()} />);
    press();
    await wait(60);
    release();
    await flush();
    expect(screen.getByTestId('mic-mode-video')).toBeTruthy();
    expect(localStorage.getItem('phantom.micMode')).toBe('video');
    press();
    await wait(1200);
    release();
    await flush();
    expect(screen.getByTestId('mic-mode-video')).toBeTruthy();
    press();
    await wait(60);
    release();
    await flush();
    expect(screen.queryByTestId('mic-mode-video')).toBeNull();
    expect(localStorage.getItem('phantom.micMode')).toBe('voice');
  });

  it('a phone voice note is sent with its words', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
      haptic: vi.fn(),
      call: (method: string, path: string) => {
        if (method === 'POST' && path === '/messenger/voice/transcribe') {
          return JSON.stringify({ ok: true, status: 200, data: { text: 'hello' } });
        }
        return JSON.stringify({ ok: true, status: 200, data: {} });
      },
    };
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press();
    await wait(200);
    await wait(1200);
    release();
    await flush();
    await flush();
    await flush();
    expect(send).toHaveBeenCalledTimes(1);
    const [, transcript] = send.mock.calls[0];
    expect(transcript).toBe('hello');
  });

  it('a clip the phone cannot read is sent without words and says so', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
      haptic: vi.fn(),
      call: () => JSON.stringify({ ok: false, status: 422, code: 'undecodable', detail: '' }),
    };
    useUIStore.setState({ toasts: [] } as never);
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press();
    await wait(200);
    await wait(1200);
    release();
    await flush();
    await flush();
    await flush();
    expect(send).toHaveBeenCalledTimes(1);
    const [, transcript] = send.mock.calls[0];
    expect(transcript).toBe('');
    expect(useUIStore.getState().toasts.some((x) => x.kind === 'warn')).toBe(true);
  });

  describe('Текст голосових', () => {
    const calls: string[] = [];
    const bridgeWithStt = () => {
      calls.length = 0;
      (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
        haptic: vi.fn(),
        call: (method: string, path: string) => {
          calls.push(`${method} ${path}`);
          if (path === '/messenger/voice/transcribe') {
            return JSON.stringify({ ok: true, status: 200, data: { text: 'hello' } });
          }
          return JSON.stringify({ ok: true, status: 200, data: {} });
        },
      };
    };
    const toPreview = async () => {
      press(370, 700);
      await wait(200);
      moveTo(370, 700 - 64);
      await flush();
      release();
      await wait(2000);
      fireEvent.click(screen.getByTestId('voice-stop'));
      await flush();
      await flush();
    };
    afterEach(() => localStorage.clear());

    it('перемикач вимкнено: порожній текст, розпізнавання не викликано', async () => {
      bridgeWithStt();
      const send = vi.fn();
      render(<MessageComposer {...props(send)} />);
      await toPreview();
      const sw = screen.getByTestId('voice-text-switch');
      expect(sw).toHaveAttribute('aria-checked', 'true');
      fireEvent.click(sw);
      expect(sw).toHaveAttribute('aria-checked', 'false');
      fireEvent.click(screen.getByTestId('voice-send'));
      await flush();
      await flush();
      expect(send).toHaveBeenCalledTimes(1);
      expect(send.mock.calls[0][1]).toBe('');
      expect(calls.some((c) => c.includes('transcribe'))).toBe(false);
    });

    it('перемикач увімкнено (типово): текст приходить як раніше', async () => {
      bridgeWithStt();
      const send = vi.fn();
      render(<MessageComposer {...props(send)} />);
      await toPreview();
      fireEvent.click(screen.getByTestId('voice-send'));
      await flush();
      await flush();
      await flush();
      expect(send.mock.calls[0][1]).toBe('hello');
      expect(calls.some((c) => c.includes('transcribe'))).toBe(true);
    });
  });
});

describe('T35: мікрофон, зайнятий іншим застосунком', () => {
  let level = 128;
  class FakeAudioContext {
    createAnalyser() {
      return {
        fftSize: 512,
        connect: () => {},
        getByteTimeDomainData: (buf: Uint8Array) => {
          for (let i = 0; i < buf.length; i += 1) buf[i] = i % 2 ? level : 256 - level;
        },
      };
    }
    createMediaStreamSource() {
      return { connect: () => {} };
    }
    close() {
      return Promise.resolve();
    }
  }

  beforeEach(() => {
    level = 128;
    vi.stubGlobal('AudioContext', FakeAudioContext);
    useUIStore.setState({ toasts: [] } as never);
  });

  const holdAndRelease = async () => {
    press();
    await wait(200);
    await wait(2100);
    release();
    await flush();
    await flush();
  };
  const toastTexts = () => useUIStore.getState().toasts.map((x) => x.message);

  it('цифрова тиша — голосове не надіслано, один тост «зайнятий»', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    await holdAndRelease();
    expect(send).not.toHaveBeenCalled();
    expect(toastTexts()).toEqual(['Мікрофон зайнятий іншим застосунком']);
  });

  it('замок → «Стоп» → тиша в перегляді → «Надіслати» теж не шле', async () => {
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    press(370, 700);
    await wait(200);
    moveTo(370, 700 - 64);
    await flush();
    release();
    await wait(2000);
    fireEvent.click(screen.getByTestId('voice-stop'));
    await flush();
    await flush();
    fireEvent.click(screen.getByTestId('voice-send'));
    await flush();
    await flush();
    expect(send).not.toHaveBeenCalled();
    expect(toastTexts()).toEqual(['Мікрофон зайнятий іншим застосунком']);
  });

  it('голос є — надіслано', async () => {
    level = 160;
    const send = vi.fn();
    render(<MessageComposer {...props(send)} />);
    await holdAndRelease();
    expect(send).toHaveBeenCalledTimes(1);
    expect(toastTexts()).toEqual([]);
  });

});
