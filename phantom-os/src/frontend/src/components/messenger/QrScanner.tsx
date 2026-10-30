import React, { useEffect, useRef, useState } from 'react';
import { CameraOff, RotateCcw, X } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';

interface QrScannerProps {
  /**
   * Прочитаний текст. `false` — «це не те, шукай далі»: камера не гасне, а
   * той самий текст більше не пропонується (меню чи Wi-Fi поруч із QR людини).
   * Будь-що інше — знайдено, сканер зупиняється.
   */
  onFound: (text: string) => boolean | void;
  /** Без нього кнопки «Зупинити камеру» немає: аркуш перемикає сам (Мій QR ↔ Сканувати). */
  onCancel?: () => void;
  /** Камера не ввімкнулась — екран може запропонувати свій запасний шлях. */
  onUnavailable?: () => void;
}

/** Камера, що фокусується, — запамʼятована, щоб наступного разу не перебирати. */
export const SCAN_CAMERA_KEY = 'phantom_scan_camera';
/** Сторона квадрата, який іде на розбір: досить для QR на відстані руки. */
export const SCAN_MAX_SIDE = 480;
/** Не частіше одного розбору за стільки мс (≤7 на секунду). */
export const SCAN_INTERVAL_MS = 150;
/**
 * Стеля одного розбору. Воркер, що не завантажився чи замовк, інакше тримав
 * би сканер вічно: камера жива, читання нема, помилки нема.
 */
export const SCAN_DECODE_TIMEOUT_MS = 1000;
/** Стільки відмов розбору поспіль — і розпізнавач міняється на jsQR у сторінці; з нього — чесна помилка. */
export const SCAN_MAX_FAILURES = 3;
/**
 * Рідний `BarcodeDetector` стільки мс поспіль не бачить нічого — і поруч із
 * ним через кадр іде jsQR. Рідний може бути сліпим мовчки: модуль Google Play
 * Services ще вантажиться (новий телефон) або його нема (телефон без сервісів
 * Google) — `detect()` тоді віддає `[]`, а не кидає.
 */
export const SCAN_NATIVE_BLIND_MS = 2000;
/** Стеля розбору jsQR-помічника: менша за загальну, щоб його мовчання не записалось відмовою рідному. */
const HELPER_TIMEOUT_MS = SCAN_DECODE_TIMEOUT_MS / 2;
/**
 * Перебір задніх камер — не довше за стільки мс (одну спробу робимо завжди).
 * На холодному старті кожне відкриття камери — сотні мс, а камер у телефоні
 * буває п'ять.
 */
export const SCAN_SEARCH_BUDGET_MS = 1200;
/** Скільки чекати, поки WebView допише `focusMode` у можливості треку, якщо одразу його нема. */
const CAPS_SETTLE_MS = 150;
/** Скільки разів поспіль перевідкриваємо камеру, чий трек скінчився не з нашої волі. */
const MAX_REOPENS = 3;

/**
 * Камера сканера.
 *
 * Виміряно на A52 (08.10): `facingMode: 'environment'` віддає камеру 2 —
 * ширококутну з ФІКСОВАНИМ фокусом, 640×480. QR на відстані руки в ній
 * розмитий, і код ловився через раз. Головна камера 0 має неперервний
 * автофокус. Раніше сканер таки відкривав «що дала система» (камеру 2),
 * мірив у ній фокус і лише тоді йшов до камери 0 — секунда зайвого
 * відкриття перед першим кадром.
 *
 * Тепер задню камеру обирають ДО першого `getUserMedia`:
 *  1. запамʼятований deviceId — одразу до нього;
 *  2. інакше — перелік камер. Поки доступу немає, назв і id у ньому нема, тож
 *     спершу одна легка проба дозволу (передня камера, малий кадр), перелік
 *     береться, ПОКИ вона жива (WebView інакше може сховати назви), і проба
 *     відпускається одразу: Android не відкриває дві камери разом;
 *  3. першою відкривається найменша за номером у назві задня («camera2 0» —
 *     основна; ширококутна, макро й глибина йдуть далі).
 * Номер у назві — підказка, а не гарантія, тож фокус усе одно міряється:
 * якщо камера його не має — перебір, як і раніше (стеля часу, памʼять до кінця
 * запуску). Камера без автофокусу назавжди не запамʼятовується: це означало б
 * ніколи більше не шукати кращої. А коли WebView `focusMode` не віддає зовсім —
 * не перебираємо взагалі: розрізнити камери однаково нічим, а кожне відкриття
 * на холодному старті — сотні мс.
 */
/** Прозорий 1×1 GIF: постер, який нічого не малює, — без сірої заглушки WebView. */
export const BLANK_POSTER = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

const VIDEO_ASK = { width: { ideal: 1280 }, focusMode: 'continuous' } as MediaTrackConstraints;
const FRONT = /front|user|передн|selfie/i;

const readCamera = (): string | null => {
  try {
    return localStorage.getItem(SCAN_CAMERA_KEY);
  } catch {
    return null;
  }
};
const rememberCamera = (id: string) => {
  if (!id) return;
  try {
    localStorage.setItem(SCAN_CAMERA_KEY, id);
  } catch {
    /* приватний режим — перебиратимемо щоразу, не біда */
  }
};
const forgetCamera = () => {
  try {
    localStorage.removeItem(SCAN_CAMERA_KEY);
  } catch {
    /* */
  }
  try {
    sessionStorage.removeItem(SCAN_CAMERA_KEY);
  } catch {
    /* */
  }
};
/** Камера без автофокусу — лише до кінця цього запуску. */
const readSessionCamera = (): string | null => {
  try {
    return sessionStorage.getItem(SCAN_CAMERA_KEY);
  } catch {
    return null;
  }
};
const rememberForSession = (id: string) => {
  if (!id) return;
  try {
    sessionStorage.setItem(SCAN_CAMERA_KEY, id);
  } catch {
    /* */
  }
};

const trackOf = (s: MediaStream): MediaStreamTrack | undefined => s.getVideoTracks?.()[0] ?? s.getTracks()[0];
const deviceOf = (s: MediaStream): string => {
  try {
    return trackOf(s)?.getSettings?.().deviceId ?? '';
  } catch {
    return '';
  }
};
const stopAll = (s: MediaStream | null) => s?.getTracks().forEach((t) => t.stop());
type Focus = 'continuous' | 'other' | 'unknown';
const readFocus = (s: MediaStream): Focus => {
  try {
    const caps = trackOf(s)?.getCapabilities?.() as { focusMode?: unknown } | undefined;
    if (!Array.isArray(caps?.focusMode)) return 'unknown';
    return caps.focusMode.includes('continuous') ? 'continuous' : 'other';
  } catch {
    return 'unknown';
  }
};
/**
 * Чи фокусується камера. `unknown` — WebView не каже `focusMode` зовсім;
 * буває, що він дописує його трохи згодом, тож ще раз дивимось за мить.
 */
const focusOf = async (s: MediaStream): Promise<Focus> => {
  const now = readFocus(s);
  if (now !== 'unknown') return now;
  await new Promise((r) => setTimeout(r, CAPS_SETTLE_MS));
  return readFocus(s);
};
/** Людина сказала «ні» — перебір цього не змінить, інші камери питати не можна. */
const refused = (e: unknown): boolean => {
  const name = (e as { name?: unknown } | null)?.name;
  return name === 'NotAllowedError' || name === 'SecurityError';
};

/** Номер камери в назві Android: «camera2 0, facing back» → 0. Нема номера — у кінець черги. */
const cameraNumber = (label: string): number => {
  const m = /camera2?\s*(\d+)/i.exec(label);
  return m ? Number(m[1]) : Number.POSITIVE_INFINITY;
};
/** Задні камери (не передні) за зростанням номера; за однакового — як у переліку. */
const rankBack = (devices: MediaDeviceInfo[]): MediaDeviceInfo[] =>
  devices
    .filter((d) => d.kind === 'videoinput' && d.deviceId && !FRONT.test(d.label || ''))
    .map((d, i) => ({ d, i, n: cameraNumber(d.label || '') }))
    .sort((a, b) => a.n - b.n || a.i - b.i)
    .map((x) => x.d);
/** Перелік чесний: у кожної камери є id і назва (до дозволу їх нема). */
const isNamed = (cams: MediaDeviceInfo[]): boolean => cams.length > 0 && cams.every((d) => d.deviceId && d.label);

/**
 * Перелік камер з назвами. Якщо доступу ще нема — одна легка проба дозволу:
 * передня камера (задню не чіпаємо — саме відкриття не тієї задньої ми й
 * прибираємо), малий кадр. Перелік береться, поки проба жива, і вона
 * відпускається одразу. Заборона — кидаємо далі; будь-яка інша хиба проби
 * (камеру зайнято) — порожній перелік, і сканер відкриває як раніше.
 */
const listCameras = async (md: MediaDevices): Promise<MediaDeviceInfo[]> => {
  const list = async (): Promise<MediaDeviceInfo[]> => {
    try {
      return (await md.enumerateDevices()).filter((d) => d.kind === 'videoinput');
    } catch {
      return [];
    }
  };
  const cams = await list();
  // Камер нема (чи перелік не підтримується) — вибирати нікому; або назви вже видно.
  if (cams.length === 0 || isNamed(cams)) return cams;
  let probe: MediaStream | null = null;
  try {
    probe = await md.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'user' }, width: { ideal: 160 }, height: { ideal: 120 } },
    });
    return await list();
  } catch (e) {
    if (refused(e)) throw e;
    return [];
  } finally {
    stopAll(probe);
  }
};

export async function openScanCamera(): Promise<MediaStream> {
  const md = navigator.mediaDevices;
  const open = (deviceId?: string) =>
    md.getUserMedia({
      audio: false,
      video: deviceId
        ? { ...VIDEO_ASK, deviceId: { exact: deviceId } }
        : { ...VIDEO_ASK, facingMode: { ideal: 'environment' } },
    });

  const remembered = readCamera() ?? readSessionCamera();
  if (remembered) {
    try {
      return await open(remembered);
    } catch (e) {
      forgetCamera();
      if (refused(e)) throw e;
    }
  }

  // Камеру обираємо ДО першого відкриття: перше, що побачить людина, — вже 0.
  const cams = await listCameras(md);
  const ranked = rankBack(cams);
  let first: MediaStream | null = null;
  if (ranked.length > 0) {
    try {
      first = await open(ranked[0].deviceId);
    } catch (e) {
      if (refused(e)) throw e;
    }
  }
  // Назв нема (чи камеру не вдалося взяти) — як раніше: що дасть система.
  if (!first) first = await open();
  const firstId = deviceOf(first);
  const focus = await focusOf(first);
  if (focus === 'continuous') {
    rememberCamera(firstId);
    return first;
  }
  // WebView не каже, яка камера фокусується, — перебір нічого не розрізнить:
  // відкрили б кожну задню по черзі лише для того, щоб повернутись до цієї ж.
  // І так на кожному холодному старті (рецензія WC, 08.10).
  if (focus === 'unknown') return first;
  let devices = cams;
  if (!isNamed(devices)) {
    try {
      devices = await md.enumerateDevices();
    } catch {
      devices = [];
    }
  }
  const others = rankBack(devices).filter((d) => d.deviceId !== firstId);
  if (others.length === 0) {
    rememberForSession(firstId);
    return first;
  }
  stopAll(first);
  const searchFrom = Date.now();
  let tried = 0;
  for (const d of others) {
    if (tried > 0 && Date.now() - searchFrom > SCAN_SEARCH_BUDGET_MS) break;
    tried += 1;
    try {
      const next = await open(d.deviceId);
      if ((await focusOf(next)) === 'continuous') {
        rememberCamera(d.deviceId);
        return next;
      }
      stopAll(next);
    } catch {
      /* зайнята чи зникла — далі */
    }
  }
  // Жодна не фокусується — назад до тієї, з якої почали.
  const back = await open(firstId || undefined);
  rememberForSession(deviceOf(back) || firstId);
  return back;
}

/** Усі тексти, що розпізнались у кадрі (рідний бачить кілька QR разом; jsQR — один). */
type Decode = (canvas: HTMLCanvasElement, ctx: CanvasRenderingContext2D, side: number) => Promise<string[]>;

interface Decoder {
  ready: Promise<Decode>;
  close: () => void;
  /** jsQR тут, у сторінці, — останній запасний; далі лише чесна помилка. */
  inline: boolean;
}

interface DetectorLike {
  detect: (source: CanvasImageSource) => Promise<Array<{ rawValue?: string }>>;
}
interface DetectorClass {
  new (opts: { formats: string[] }): DetectorLike;
  getSupportedFormats?: () => Promise<string[]>;
}

/** Розбір, що не вклався в стелю, — відмова, а не вічне очікування. */
const withDeadline = <T,>(work: Promise<T>, ms: number): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('qr decode timeout')), ms);
    work.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (e: unknown) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });

/**
 * jsQR у воркері: кадр їде буфером без копії, відповідь — `{ id, text }`.
 *
 * Помилка воркера (скрипт не приїхав, CSP, MIME) могла прийти ДО першого
 * розбору, коли чекати було нікому, — і тоді кожен наступний розбір чекав
 * вічно. Тепер вона ламає воркер назавжди: розбори відмовляють одразу, і
 * сканер переходить на jsQR у сторінці.
 */
const workerDecoder = (): Decoder => {
  const worker = new Worker(new URL('../../workers/qrDecode.worker.ts', import.meta.url), { type: 'module' });
  let seq = 0;
  let broken = false;
  let pending: { id: number; resolve: (t: string[]) => void; reject: (e: Error) => void } | null = null;
  worker.onmessage = (e: MessageEvent<{ id: number; text: string | null }>) => {
    const p = pending;
    if (!p || e.data?.id !== p.id) return;
    pending = null;
    p.resolve(e.data.text ? [e.data.text] : []);
  };
  worker.onerror = () => {
    broken = true;
    const p = pending;
    pending = null;
    p?.reject(new Error('qr worker'));
  };
  const decode: Decode = (_canvas, ctx, side) => {
    if (broken) return Promise.reject(new Error('qr worker'));
    const image = ctx.getImageData(0, 0, side, side);
    const buffer = image.data.buffer as ArrayBuffer;
    seq += 1;
    const id = seq;
    return new Promise<string[]>((resolve, reject) => {
      pending = { id, resolve, reject };
      worker.postMessage({ id, width: side, height: side, data: buffer }, [buffer]);
    });
  };
  const close = () => {
    try {
      worker.terminate();
    } catch {
      /* уже мертвий */
    }
  };
  return { ready: Promise.resolve(decode), close, inline: false };
};

/** Ні рідного розпізнавача, ні воркерів: jsQR тут, але так само рідко й на малому квадраті. */
const inlineDecoder = (): Decoder => ({
  ready: import('jsqr').then(({ default: jsQR }) => {
    const decode: Decode = async (_canvas, ctx, side) => {
      const image = ctx.getImageData(0, 0, side, side);
      const text = jsQR(image.data, side, side, { inversionAttempts: 'dontInvert' })?.data;
      return text ? [text] : [];
    };
    return decode;
  }),
  close: () => {},
  inline: true,
});

/**
 * Розпізнавач починає вантажитись разом із запитом камери, а не після.
 * `BarcodeDetector` (є у WebView A52) — рідний і поза JS; без нього — jsQR у
 * воркері, щоб головний потік малював аркуш і відповідав на дотики.
 */
const makeDecoder = (): Decoder => {
  const Native = (globalThis as unknown as { BarcodeDetector?: DetectorClass }).BarcodeDetector;
  const fallback = (): Decoder => {
    if (typeof Worker !== 'function') return inlineDecoder();
    try {
      return workerDecoder();
    } catch {
      return inlineDecoder(); // CSP відмовив воркеру ще в конструкторі
    }
  };
  if (typeof Native !== 'function') return fallback();
  let spare: Decoder | null = null;

  // jsQR-помічник рідного: вмикається, коли рідний довго сліпий; сам
  // відмовив — воркер міняється на jsQR у сторінці, а той — на «без помічника».
  let helper: Decoder | null = null;
  let helperFn: Decode | null = null;
  let helperFails = 0;
  let helperGone = false;
  const takeHelper = (d: Decoder) => {
    helper = d;
    helperFn = null;
    helperFails = 0;
    d.ready.then(
      (fn) => {
        if (helper === d) helperFn = fn;
      },
      () => {
        if (helper === d) dropHelper();
      },
    );
  };
  const dropHelper = () => {
    const h = helper;
    if (!h) return;
    h.close();
    if (!h.inline) {
      takeHelper(inlineDecoder());
      return;
    }
    helper = null;
    helperFn = null;
    helperGone = true;
  };

  const ready = (async (): Promise<Decode> => {
    try {
      const formats = (await Native.getSupportedFormats?.()) ?? ['qr_code'];
      if (!formats.includes('qr_code')) throw new Error('no qr_code');
      const detector = new Native({ formats: ['qr_code'] });
      let blindSince = 0;
      let turn = 0;
      return async (canvas, ctx, side) => {
        turn += 1;
        const assist = helperFn;
        if (assist && turn % 2 === 0) {
          try {
            const texts = await withDeadline(assist(canvas, ctx, side), HELPER_TIMEOUT_MS);
            helperFails = 0;
            return texts;
          } catch {
            helperFails += 1;
            if (helperFails >= SCAN_MAX_FAILURES) dropHelper();
            return [];
          }
        }
        const hits = await detector.detect(canvas);
        const texts = hits.map((h) => h.rawValue ?? '').filter(Boolean);
        if (texts.length > 0) {
          blindSince = 0;
          return texts;
        }
        // Порожня відповідь — це не відмова: рідний мовчки сліпий так само,
        // як і «в кадрі QR нема». Тож рахуємо час, а не помилки.
        const now = Date.now();
        if (!blindSince) blindSince = now;
        else if (!helper && !helperGone && now - blindSince >= SCAN_NATIVE_BLIND_MS) takeHelper(fallback());
        return [];
      };
    } catch {
      spare = fallback();
      return spare.ready;
    }
  })();
  return {
    ready,
    close: () => {
      spare?.close();
      helper?.close();
    },
    get inline() {
      return spare?.inline ?? false;
    },
  };
};

// Камера тут вмикається насправді: getUserMedia, центральний квадрат кадру в
// canvas, розпізнавач по ньому — не частіше разу на 150 мс.
export const QrScanner: React.FC<QrScannerProps> = ({ onFound, onCancel, onUnavailable }) => {
  const { t } = useTranslation();
  // Батько передає нові стрілки на кожен рендер (набір у сусідньому полі) —
  // камера від цього не перезапускається: колбеки живуть у ref.
  const foundRef = useRef(onFound);
  foundRef.current = onFound;
  const unavailableRef = useRef(onUnavailable);
  unavailableRef.current = onUnavailable;
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [error, setError] = useState<'camera' | 'denied' | 'decoder' | null>(null);
  const [live, setLive] = useState(false);
  /**
   * Нова спроба після відмови чи збою. FR-2 (ніч 09.10): після «Не зараз» на
   * картці камери в коробці стояло «Немає дозволу…» без жодної кнопки —
   * глухий кут. Тепер «Дозволити камеру» питає знову: рідний бік покаже свою
   * картку, а після остаточної відмови — «Відкрити налаштування».
   */
  const [attempt, setAttempt] = useState(0);
  const retry = () => {
    setError(null);
    setLive(false);
    setAttempt((n) => n + 1);
  };

  useEffect(() => {
    let stream: MediaStream | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    /** Кінець назавжди: розмонтовано, код знайдено або відмова. */
    let stopped = false;
    /** Сторінку сховано (блокування, інший застосунок): камеру відпущено до повернення. */
    let paused = false;
    /** Покоління відкриття камери: відповідь застарілого відкриття — у смітник. */
    let opening = 0;
    /** Покоління циклу розбору: лише останній ставить себе знову. */
    let looping = 0;
    let reopens = 0;
    let decode: Decode | null = null;
    let ctx: CanvasRenderingContext2D | null = null;
    let decoder: Decoder = makeDecoder();
    let failures = 0;
    /** Прочитане, від якого екран відмовився (`onFound` → false), — більше не пропонуємо. */
    const passed = new Set<string>();

    /** Відпустити камеру й зупинити цикл. Свою зупинку треку «кінцем» не рахуємо. */
    const release = () => {
      if (timer) clearTimeout(timer);
      timer = null;
      looping += 1;
      const s = stream;
      stream = null;
      const track = s ? trackOf(s) : undefined;
      if (track) track.onended = null;
      stopAll(s);
    };

    const fail = (kind: 'camera' | 'denied' | 'decoder') => {
      if (stopped) return;
      stopped = true;
      opening += 1;
      release();
      setError(kind);
      unavailableRef.current?.();
    };

    const adopt = (d: Decoder) => {
      decoder = d;
      decode = null;
      d.ready.then(
        (fn) => {
          if (decoder === d) decode = fn;
        },
        () => {
          if (decoder === d) fail('decoder');
        },
      );
    };
    adopt(decoder);

    /** Кілька відмов поспіль: на jsQR у сторінці, а коли відмовляє й він — чесна помилка. */
    const giveUpOnDecoder = () => {
      failures = 0;
      if (decoder.inline) {
        fail('decoder');
        return;
      }
      decoder.close();
      adopt(inlineDecoder());
    };

    const loop = async (run: number) => {
      timer = null;
      if (stopped || paused || run !== looping) return;
      const started = Date.now();
      const video = videoRef.current;
      const canvas = canvasRef.current;
      if (decode && video && canvas && video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
        const vw = video.videoWidth;
        const vh = video.videoHeight;
        const crop = Math.min(vw, vh);
        const side = Math.min(SCAN_MAX_SIDE, crop);
        // Полотно міняє розмір лише тоді, коли міняється кадр (поворот), не щоразу.
        if (canvas.width !== side || canvas.height !== side) {
          canvas.width = side;
          canvas.height = side;
        }
        if (!ctx) ctx = canvas.getContext('2d', { willReadFrequently: true });
        if (ctx) {
          ctx.drawImage(video, (vw - crop) / 2, (vh - crop) / 2, crop, crop, 0, 0, side, side);
          let texts: string[] = [];
          try {
            texts = await withDeadline(decode(canvas, ctx, side), SCAN_DECODE_TIMEOUT_MS);
            failures = 0;
          } catch {
            failures += 1;
            if (failures >= SCAN_MAX_FAILURES) giveUpOnDecoder();
          }
          if (stopped || paused || run !== looping) return;
          for (const raw of texts) {
            const clean = raw.trim();
            if (!clean || passed.has(clean)) continue;
            // Чужий QR у кадрі (меню, Wi-Fi) — не кінець: камера світить далі.
            if (foundRef.current(clean) === false) {
              passed.add(clean);
              if (stopped || paused || run !== looping) return;
              continue;
            }
            stopped = true;
            release();
            return;
          }
        }
      }
      timer = setTimeout(() => void loop(run), Math.max(SCAN_INTERVAL_MS - (Date.now() - started), 0));
    };

    const open = async () => {
      opening += 1;
      const mine = opening;
      let s: MediaStream;
      try {
        s = await openScanCamera();
      } catch (e) {
        // Камери немає або доступ не дали — кажемо прямо, а не крутимо порожній кадр.
        if (mine === opening && !paused) fail(refused(e) ? 'denied' : 'camera');
        return;
      }
      if (stopped || paused || mine !== opening) {
        stopAll(s);
        return;
      }
      stream = s;
      const track = trackOf(s);
      if (track) {
        // Систему забрала камеру не з нашої волі (дзвінок, інший застосунок,
        // блокування без visibilitychange) — видошукач не мусить застигнути.
        track.onended = () => {
          if (stream !== s || stopped || paused) return;
          release();
          setLive(false);
          if (reopens >= MAX_REOPENS) {
            fail('camera');
            return;
          }
          reopens += 1;
          void open();
        };
      }
      const video = videoRef.current;
      if (video) {
        video.srcObject = s;
        try {
          await video.play();
        } catch {
          /* muted + playsInline у WebView грає й так; кадри однаково йдуть */
        }
      }
      if (stopped || paused || stream !== s) return;
      setLive(true);
      looping += 1;
      void loop(looping);
    };

    // Блокування екрана чи перехід в інший застосунок: Android однаково
    // забирає камеру. Відпускаємо її самі й беремо знову, коли сторінку видно.
    const onVisibility = () => {
      if (stopped) return;
      if (document.visibilityState === 'hidden') {
        if (paused) return;
        paused = true;
        opening += 1;
        release();
        setLive(false);
      } else if (paused) {
        paused = false;
        reopens = 0;
        void open();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    void open();

    return () => {
      stopped = true;
      opening += 1;
      release();
      decoder.close();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [attempt]);

  const errorText =
    error === 'denied' ? t('msgr.scan.denied') : error === 'decoder' ? t('msgr.scan.decoderFailed') : t('msgr.scan.unavailable');

  return (
    <div className="space-y-3" data-testid="qr-scanner">
      {error ? (
        <div className="p-3.5 bg-overlay rounded-card space-y-3">
          <div className="flex items-start gap-2.5">
            <CameraOff className="w-4 h-4 text-ink-2 mt-0.5 shrink-0" strokeWidth={1.75} />
            <span className="text-callout text-ink-2">{errorText}</span>
          </div>
          <button
            type="button"
            onClick={retry}
            data-testid="qr-scanner-retry"
            className="touch-floor min-h-[44px] w-full px-3.5 py-2 rounded-control bg-accent text-on-accent flex items-center justify-center gap-2 text-callout font-semibold"
          >
            <RotateCcw className="w-4 h-4" strokeWidth={1.75} />
            <span>{error === 'denied' ? t('msgr.scan.allowCamera') : t('msgr.scan.retry')}</span>
          </button>
        </div>
      ) : (
        // Чорне тло й порожній постер: доки камера стартує, WebView інакше
        // малює свою сіру заглушку відео з кнопкою «play» (UX-аудит №13).
        <div className="relative w-full aspect-square rounded-card overflow-hidden bg-black" data-testid="qr-scanner-frame">
          <video
            ref={videoRef}
            className="absolute inset-0 w-full h-full object-cover bg-black"
            poster={BLANK_POSTER}
            muted
            playsInline
          />
          <canvas ref={canvasRef} className="hidden" aria-hidden="true" />
          {/* Видошукач: чотири кути; міняється лише прозорість. */}
          <div
            aria-hidden="true"
            className={`absolute inset-[14%] pointer-events-none transition-opacity duration-standard ${live ? 'opacity-100' : 'opacity-40'}`}
          >
            <span className="absolute left-0 top-0 w-8 h-8 border-l-[3px] border-t-[3px] border-accent rounded-tl-card" />
            <span className="absolute right-0 top-0 w-8 h-8 border-r-[3px] border-t-[3px] border-accent rounded-tr-card" />
            <span className="absolute left-0 bottom-0 w-8 h-8 border-l-[3px] border-b-[3px] border-accent rounded-bl-card" />
            <span className="absolute right-0 bottom-0 w-8 h-8 border-r-[3px] border-b-[3px] border-accent rounded-br-card" />
          </div>
        </div>
      )}
      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          className="touch-floor min-h-[44px] w-full px-3.5 py-2 rounded-control bg-overlay text-ink-2 active:text-ink flex items-center justify-center gap-2 text-callout font-semibold transition-colors"
        >
          <X className="w-4 h-4 text-ink-2" strokeWidth={1.75} />
          <span>{t('msgr.scan.stop')}</span>
        </button>
      )}
    </div>
  );
};

export default QrScanner;
