/**
 * Сканер бере камеру, що фокусується, — і не перезапускає її на кожен рендер.
 *
 * Виміряно на A52 (08.10): `facingMode: 'environment'` віддає камеру 2 —
 * ширококутну з фіксованим фокусом, 640×480. QR на відстані руки в ній
 * розмитий, і код «ловився через раз». Головна камера 0 має неперервний
 * автофокус. Тут — той самий набір камер підробкою: система віддає камеру 2,
 * сканер мусить сам перейти на 0, запамʼятати її й наступного разу йти
 * одразу до неї.
 *
 * Друга вада: `useEffect(..., [onFound])` при `onFound`-стрілці від батька —
 * кожен рендер батька (набір літери в сусідньому полі) зупиняв камеру й
 * просив її знову.
 *
 * Третя (рецензія WC): після блокування екрана чи переходу в інший
 * застосунок Android забирає камеру — трек закінчується, і видошукач
 * застигав назавжди. Тепер сканер відпускає камеру, коли сторінку сховано,
 * і бере знову, коли її видно; трек, що скінчився не з нашої волі, —
 * перевідкривається.
 *
 * Червоніти вміє: поверніть `getUserMedia({ video: { facingMode } })` без
 * перебору — падає «камера з автофокусом»; поверніть `[onFound]` у
 * залежності — падає «повторний рендер»; заберіть слухача visibilitychange —
 * падає «сховали й повернули»; запамʼятовуйте будь-яку камеру в localStorage —
 * падає «жодна не фокусується».
 *
 * Друга рецензія (08.10): коли WebView не каже `focusMode` зовсім, сканер
 * відкривав КОЖНУ задню камеру по черзі — і так на кожному холодному старті,
 * бо вибір «без автофокусу» живе лише до кінця запуску. Тепер: поля нема —
 * без перебору (заберіть це — падає «WebView не каже»); поле дописалось за
 * мить — перебір однаково знаходить фокусну (заберіть повторний погляд —
 * падає «дописав згодом»); перебір має стелю часу (заберіть — падає «стеля»).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, act, screen } from '@testing-library/react';
import { QrScanner, SCAN_CAMERA_KEY, SCAN_SEARCH_BUDGET_MS } from '../components/messenger/QrScanner';

interface FakeCam {
  id: string;
  label: string;
  /** undefined — WebView поля `focusMode` не віддає зовсім. */
  focus?: string[];
  /** `focusMode` зʼявляється в можливостях лише через стільки мс після відкриття. */
  focusLateMs?: number;
}

/** Як на A52: система на «environment» віддає ширококутну 2. */
const A52: FakeCam[] = [
  { id: 'cam-wide', label: 'camera2 2, facing back', focus: ['fixed'] },
  { id: 'cam-main', label: 'camera2 0, facing back', focus: ['single-shot', 'continuous', 'manual'] },
  { id: 'cam-front', label: 'camera2 1, facing front', focus: ['fixed'] },
];

/** Назви без номерів: за ними не вгадаєш, яка камера головна, — лишається вимір фокусу й перебір. */
const PLAIN: FakeCam[] = [
  { id: 'cam-wide', label: 'Back ultra wide camera', focus: ['fixed'] },
  { id: 'cam-main', label: 'Back camera', focus: ['single-shot', 'continuous', 'manual'] },
  { id: 'cam-front', label: 'Front camera', focus: ['fixed'] },
];

type VideoAsk = MediaTrackConstraints & { deviceId?: { exact?: string }; focusMode?: unknown };

interface FakeOpts {
  /** `prompt` — доступу ще нема: назв і id камер не видно, доки перший getUserMedia його не дасть. */
  permission?: 'granted' | 'prompt';
  /** WebView віддає назви камер лише поки якийсь потік живий. */
  labelsOnlyWhileLive?: boolean;
}

const fakeCameras = (cams: FakeCam[], systemPick = cams[0].id, openMs = 0, opts: FakeOpts = {}) => {
  const opened: string[] = [];
  const stopped: string[] = [];
  const asked: VideoAsk[] = [];
  /** Порядок подій: open:<камера>, stop:<камера>, enumerate. */
  const events: string[] = [];
  const tracks: Array<{ onended: (() => void) | null; stop: () => void }> = [];
  let granted = (opts.permission ?? 'granted') === 'granted';
  let live = 0;
  const getUserMedia = vi.fn(async (c: MediaStreamConstraints) => {
    const video = c.video as VideoAsk;
    asked.push(video);
    const wantsUser = (video.facingMode as { ideal?: string } | undefined)?.ideal === 'user';
    const front = cams.find((cam) => /front/i.test(cam.label))?.id;
    const id = video.deviceId?.exact ?? (wantsUser && front ? front : systemPick);
    if (!cams.some((cam) => cam.id === id)) throw new DOMException('нема такої', 'OverconstrainedError');
    if (openMs > 0) await new Promise((r) => setTimeout(r, openMs));
    // Android не відкриває дві камери разом.
    if (live > 0) throw new DOMException('камера зайнята', 'NotReadableError');
    live += 1;
    granted = true;
    opened.push(id);
    events.push(`open:${id}`);
    const cam = cams.find((c) => c.id === id)!;
    const openedAt = Date.now();
    let released = false;
    const track = {
      kind: 'video',
      onended: null as (() => void) | null,
      stop: vi.fn(() => {
        stopped.push(id);
        if (released) return;
        released = true;
        live -= 1;
        events.push(`stop:${id}`);
      }),
      getSettings: () => ({ deviceId: id }),
      getCapabilities: () =>
        cam.focus && Date.now() - openedAt >= (cam.focusLateMs ?? 0) ? { deviceId: id, focusMode: cam.focus } : { deviceId: id },
      applyConstraints: vi.fn(async () => {}),
    };
    tracks.push(track);
    return { id: `stream:${id}:${opened.length}`, getTracks: () => [track], getVideoTracks: () => [track] } as unknown as MediaStream;
  });
  const enumerateDevices = vi.fn(async () => {
    events.push('enumerate');
    if (!granted || (opts.labelsOnlyWhileLive && live === 0)) {
      // Як Chrome до дозволу: одна безіменна камера без id.
      return [{ deviceId: '', kind: 'videoinput', label: '', groupId: '' }];
    }
    return cams.map((cam) => ({ deviceId: cam.id, kind: 'videoinput', label: cam.label, groupId: 'g' }));
  });
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia, enumerateDevices },
  });
  return { getUserMedia, enumerateDevices, opened, stopped, asked, tracks, events };
};

/** Яка камера зараз у відео: `stream:<камера>` (номер відкриття відрізано). */
const shownStream = (container: HTMLElement): string | undefined =>
  shownOpen(container)?.replace(/:\d+$/, '');
/** Те саме, але з номером відкриття — щоб бачити, що потік НОВИЙ. */
const shownOpen = (container: HTMLElement): string | undefined =>
  ((container.querySelector('video') as unknown as { srcObject?: { id: string } } | null)?.srcObject)?.id;

let hidden = false;
const setHidden = (value: boolean) => {
  hidden = value;
  document.dispatchEvent(new Event('visibilitychange'));
};

let playSpy: ReturnType<typeof vi.fn>;
beforeEach(() => {
  playSpy = vi.fn(async () => {});
  Object.defineProperty(HTMLMediaElement.prototype, 'play', { configurable: true, value: playSpy });
  hidden = false;
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') });
});
afterEach(() => {
  delete (navigator as unknown as { mediaDevices?: unknown }).mediaDevices;
  delete (document as unknown as { visibilityState?: unknown }).visibilityState;
  try {
    sessionStorage.clear();
  } catch {
    /* */
  }
  vi.restoreAllMocks();
});

describe('камера сканера', () => {
  it('з двох задніх одразу бере ту, що має автофокус: ширококутну 2 не відкрито взагалі', async () => {
    const cams = fakeCameras(A52);
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
    // Доступ уже є — назви видно: один перелік і одне відкриття, ту саму камеру 0.
    expect(cams.events).toEqual(['enumerate', 'open:cam-main']);
    expect(cams.asked[0].deviceId).toEqual({ exact: 'cam-main' });
    expect(cams.opened).not.toContain('cam-wide');
    expect(cams.opened).not.toContain('cam-front');
  });

  it('перший запуск, доступу ще нема: легка проба передньою, відпущена одразу, — і до камери 0 без обходу', async () => {
    const cams = fakeCameras(A52, 'cam-wide', 0, { permission: 'prompt' });
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    // Проба йде передньою (не задньою!) і малим кадром; перелік береться, поки
    // вона жива; камеру 0 відкрито лише після зупинки проби.
    expect(cams.events).toEqual(['enumerate', 'open:cam-front', 'enumerate', 'stop:cam-front', 'open:cam-main']);
    expect(cams.getUserMedia).toHaveBeenCalledTimes(2);
    expect(cams.asked[0].facingMode).toEqual({ ideal: 'user' });
    expect((cams.asked[0].width as { ideal: number }).ideal).toBeLessThanOrEqual(320);
    expect(cams.asked[1].deviceId).toEqual({ exact: 'cam-main' });
    expect(cams.opened).not.toContain('cam-wide');
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
  });

  it('WebView дає назви камер лише поки потік проби живий — перелік береться до її зупинки', async () => {
    const cams = fakeCameras(A52, 'cam-wide', 0, { permission: 'prompt', labelsOnlyWhileLive: true });
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(cams.opened).not.toContain('cam-wide');
    expect(cams.events.indexOf('stop:cam-front')).toBeGreaterThan(cams.events.lastIndexOf('enumerate'));
  });

  it('задні камери ранжуються за номером у назві, а не за порядком переліку', async () => {
    const shuffled: FakeCam[] = [
      { id: 'cam-tele', label: 'camera2 3, facing back', focus: ['fixed'] },
      { id: 'cam-wide', label: 'camera2 2, facing back', focus: ['fixed'] },
      { id: 'cam-front', label: 'camera2 1, facing front', focus: ['fixed'] },
      { id: 'cam-main', label: 'camera2 0, facing back', focus: ['single-shot', 'continuous', 'manual'] },
    ];
    const cams = fakeCameras(shuffled);
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(cams.events).toEqual(['enumerate', 'open:cam-main']);
  });

  it('проба дозволу відхилена — так і кажемо, і камер далі не питаємо', async () => {
    const getUserMedia = vi.fn(async () => Promise.reject(new DOMException('ні', 'NotAllowedError')));
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia,
        enumerateDevices: vi.fn(async () => [{ deviceId: '', kind: 'videoinput', label: '', groupId: '' }]),
      },
    });
    render(<QrScanner onFound={() => {}} />);
    expect(await screen.findByText('Немає дозволу на камеру — дозвольте його в налаштуваннях')).toBeTruthy();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
  });

  it('проба впала не через заборону (камеру зайнято) — далі як раніше: система дала, фокус виміряно, перебір знайшов 0', async () => {
    const cams = fakeCameras(A52, 'cam-wide', 0, { permission: 'prompt' });
    const real = cams.getUserMedia;
    let first = true;
    (navigator.mediaDevices as unknown as { getUserMedia: unknown }).getUserMedia = vi.fn(async (c: MediaStreamConstraints) => {
      if (first) {
        first = false;
        throw new DOMException('зайнята', 'NotReadableError');
      }
      return real(c);
    });
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
  });

  it('назви без номерів: перша за переліком може бути не тією — фокус міряється, перебір лишається', async () => {
    const cams = fakeCameras(PLAIN);
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
    expect(cams.opened).toEqual(['cam-wide', 'cam-main']);
    expect(cams.stopped).toContain('cam-wide');
  });

  it('запамʼятована камера — одразу до неї, без перебору', async () => {
    localStorage.setItem(SCAN_CAMERA_KEY, 'cam-main');
    const cams = fakeCameras(A52);
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(cams.getUserMedia).toHaveBeenCalledTimes(1);
    expect(cams.asked[0].deviceId).toEqual({ exact: 'cam-main' });
    expect(cams.enumerateDevices).not.toHaveBeenCalled();
  });

  it('запамʼятованої камери вже нема — чесний перебір, і памʼять оновлюється', async () => {
    localStorage.setItem(SCAN_CAMERA_KEY, 'cam-gone');
    fakeCameras(A52);
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
  });

  it('просить 1280 завширшки й неперервний фокус', async () => {
    const cams = fakeCameras(A52);
    render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(cams.getUserMedia).toHaveBeenCalled());
    const first = cams.asked[0];
    expect(first.width).toEqual({ ideal: 1280 });
    expect(first.focusMode).toBe('continuous');
  });

  it('одна камера без автофокусу (ноутбук) — береться вона ж, без зайвих відкривань', async () => {
    const cams = fakeCameras([{ id: 'webcam', label: 'HD Webcam', focus: [] }]);
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:webcam'));
    expect(cams.getUserMedia).toHaveBeenCalledTimes(1);
    expect(cams.stopped).toEqual([]);
  });

  it('повторний рендер батька з новими стрілками не перезапускає камеру', async () => {
    localStorage.setItem(SCAN_CAMERA_KEY, 'cam-main');
    const cams = fakeCameras(A52);
    const { container, rerender } = render(<QrScanner onFound={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    for (let i = 0; i < 3; i += 1) {
      rerender(<QrScanner onFound={() => {}} onCancel={() => {}} onUnavailable={() => {}} />);
    }
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(cams.getUserMedia).toHaveBeenCalledTimes(1);
    expect(cams.stopped).toEqual([]);
  });

  it('камери нема — onUnavailable і жодного вічного кадру', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => Promise.reject(new DOMException('ні', 'NotAllowedError'))), enumerateDevices: vi.fn(async () => []) },
    });
    const unavailable = vi.fn();
    const { container } = render(<QrScanner onFound={() => {}} onCancel={() => {}} onUnavailable={unavailable} />);
    await waitFor(() => expect(unavailable).toHaveBeenCalledTimes(1));
    expect(container.querySelector('video')).toBeNull();
  });

  it('доступ заборонено — так і кажемо, а не «камера недоступна»', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => Promise.reject(new DOMException('ні', 'NotAllowedError'))), enumerateDevices: vi.fn(async () => []) },
    });
    render(<QrScanner onFound={() => {}} />);
    expect(await screen.findByText('Немає дозволу на камеру — дозвольте його в налаштуваннях')).toBeTruthy();
    expect(screen.queryByText('Камера недоступна.')).toBeNull();
  });

  it('жодна задня не фокусується — камеру не памʼятаємо назавжди, лише до кінця цього запуску', async () => {
    const fixed: FakeCam[] = [
      { id: 'cam-wide', label: 'camera2 2, facing back', focus: ['fixed'] },
      { id: 'cam-tele', label: 'camera2 3, facing back', focus: [] },
    ];
    const first = fakeCameras(fixed);
    const one = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(one.container)).toBe('stream:cam-wide'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBeNull();
    expect(first.enumerateDevices).toHaveBeenCalledTimes(1);
    one.unmount();

    // Той самий запуск — без нового перебору.
    const second = fakeCameras(fixed);
    const two = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(two.container)).toBe('stream:cam-wide'));
    expect(second.getUserMedia).toHaveBeenCalledTimes(1);
    expect(second.enumerateDevices).not.toHaveBeenCalled();
  });

  it('сторінку сховали (блокування, інший застосунок) — камеру відпущено; повернули — взято знову', async () => {
    localStorage.setItem(SCAN_CAMERA_KEY, 'cam-main');
    const cams = fakeCameras(A52);
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    const before = shownOpen(container);
    await act(async () => setHidden(true));
    expect(cams.stopped).toEqual(['cam-main']);
    await act(async () => setHidden(false));
    await waitFor(() => expect(cams.getUserMedia).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(shownOpen(container)).not.toBe(before));
    expect(shownStream(container)).toBe('stream:cam-main');
  });

  it('трек скінчився не з нашої волі — видошукач не застигає: камеру перевідкрито', async () => {
    localStorage.setItem(SCAN_CAMERA_KEY, 'cam-main');
    const cams = fakeCameras(A52);
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    const before = shownOpen(container);
    await act(async () => cams.tracks[0].onended?.());
    await waitFor(() => expect(cams.getUserMedia).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(shownOpen(container)).not.toBe(before));
  });

  it('WebView не каже focusMode зовсім — одна камера, без перебору всіх задніх', async () => {
    const mute: FakeCam[] = [
      { id: 'cam-wide', label: 'camera2 2, facing back' },
      { id: 'cam-main', label: 'camera2 0, facing back' },
      { id: 'cam-tele', label: 'camera2 3, facing back' },
      { id: 'cam-macro', label: 'camera2 4, facing back' },
    ];
    const cams = fakeCameras(mute);
    const { container } = render(<QrScanner onFound={() => {}} />);
    // Фокус невідомий — лишається обрана за номером камера 0, без жодного перебору.
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(cams.getUserMedia).toHaveBeenCalledTimes(1);
    expect(cams.stopped).toEqual([]);
  });

  it('WebView дописав focusMode згодом — перебір однаково знаходить камеру з автофокусом', async () => {
    const late = PLAIN.map((cam) => ({ ...cam, focusLateMs: 60 }));
    fakeCameras(late);
    const { container } = render(<QrScanner onFound={() => {}} />);
    await waitFor(() => expect(shownStream(container)).toBe('stream:cam-main'));
    expect(localStorage.getItem(SCAN_CAMERA_KEY)).toBe('cam-main');
  });

  it('стеля перебору: шість задніх без автофокусу, кожна відкривається 500 мс — пробуємо не всі', async () => {
    vi.useFakeTimers();
    try {
      const many: FakeCam[] = ['a', 'b', 'c', 'd', 'e', 'f'].map((n, i) => ({
        id: `cam-${n}`,
        label: `camera2 ${i}, facing back`,
        focus: ['fixed'],
      }));
      const cams = fakeCameras(many, 'cam-a', 500);
      const { container } = render(<QrScanner onFound={() => {}} />);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(8000);
      });
      expect(shownStream(container)).toBe('stream:cam-a');
      // Система + не більше, ніж влазить у стелю (+1 почата до неї), + повернення до системної.
      const tries = cams.opened.length - 2;
      expect(tries).toBeGreaterThanOrEqual(1);
      expect(tries).toBeLessThanOrEqual(Math.ceil(SCAN_SEARCH_BUDGET_MS / 500) + 1);
      expect(cams.opened).not.toContain('cam-f');
    } finally {
      vi.useRealTimers();
    }
  });
});
