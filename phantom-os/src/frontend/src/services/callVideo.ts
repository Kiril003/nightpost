/**
 * Налаштування відео-тракту для кришталевої якості дзвінка (Crystal HD Video).
 *
 * ЧОМУ ВІДЕО БУЛО РОЗМИТИМ ТА ТОРМОЗИЛО:
 * 1. Запит `getUserMedia` стояв на `{ ideal: 640, ideal: 480 }` — VGA якість
 *    із 2005 року, яка на FHD+ екранах сучасних смартфонів виглядала милом.
 * 2. `RTCRtpSender` для відео не отримував жодних параметрів бітрейту — рушій
 *    Chromium за замовчуванням обмежував потік до ~300-500 кбіт/с, спричиняючи
 *    артефакти компресії («квадрати»).
 * 3. Без вибору кодеків браузер часто обирав софтверний софт-енкодер VP8 замість
 *    апаратного H.264, який підтримується апаратними блоками Exynos/Snapdragon
 *    на смартфонах Galaxy без нагріву та просідання кадрів.
 *
 * Цей модуль надає:
 * - План енкодера з ПОГЛЯДУ приймача (`callView.ts`), без нього — 720p30/2,0 Мбіт/с
 * - Пріоритезацію апаратного кодека H.264 перед софтверним VP8
 * - Налаштування SDP для відео (стеля b=AS/b=TIAS) і ЄДИНУ лійку локального
 *   SDP [finalizeLocalSdp] для всіх шляхів переговорів
 */

import { tuneOpus } from './callOpus';
import type { AudioLevel } from './callOpus';
import { DEFAULT_PLAN, VIDEO_RUNGS, localVideoCap, ownBatteryOff } from './callView';
import type { VideoPlan } from './callView';

/**
 * Що камера знімає зараз — щоб не смикати `applyConstraints` тим самим
 * планом знову (кожна зміна захоплення може коштувати ключового кадру).
 */
const captured = new WeakMap<MediaStreamTrack, string>();

const degradationByTrack = new WeakMap<MediaStreamTrack, string>();

export const hintFor = (facing: 'user' | 'environment') =>
  facing === 'environment'
    ? ({ contentHint: 'detail', degradation: 'maintain-resolution' } as const)
    : ({ contentHint: 'motion', degradation: 'maintain-framerate' } as const);

export const applyVideoHint = (
  track: MediaStreamTrack | null | undefined,
  facing: 'user' | 'environment',
): void => {
  if (!track) return;
  const { contentHint, degradation } = hintFor(facing);
  degradationByTrack.set(track, degradation);
  try {
    if ('contentHint' in track) {
      track.contentHint = contentHint;
    }
  } catch {
    /* WebView не підтримує contentHint */
  }
};

/**
 * Ставить план ПОГЛЯДУ (`callView.ts`) на відправника відео.
 *
 * Стояло: 2,5 Мбіт/с, 60 fps, `maintain-framerate` будь-куди — ~1,1 ГБ/год на
 * ногу, навіть коли плитка приймача 320 px. Тепер стелю задає приймач; без
 * його кадру — [DEFAULT_PLAN] (720p30 / 2,0 Мбіт/с).
 *
 * `constrainTrack` — чи можна стиснути саму камеру. Доріжка спільна для всіх
 * ніг кімнати, тож коли ніг більше за одну, рушій камеру не чіпає й лише
 * стелить енкодер цієї ноги.
 */
export const capVideoSender = async (
  sender: RTCRtpSender | undefined,
  plan: VideoPlan = DEFAULT_PLAN,
  constrainTrack = true,
): Promise<boolean> => {
  if (!sender) return false;
  const track = sender.track;
  const cap = localVideoCap();
  const targetHeight = cap !== null ? Math.min(plan.height, cap) : plan.height;
  const defaultHeight = cap !== null ? Math.min(DEFAULT_PLAN.height, cap) : DEFAULT_PLAN.height;

  // Спершу камера: вона сама знімає менше, і гріються менше й ISP, і енкодер.
  if (track && constrainTrack && plan.active && typeof track.applyConstraints === 'function') {
    const key = `${targetHeight}@${plan.maxFramerate}`;
    if (captured.get(track) !== key) {
      try {
        await track.applyConstraints({
          height: { ideal: targetHeight },
          frameRate: { ideal: plan.maxFramerate, max: plan.maxFramerate },
        });
        captured.set(track, key);
      } catch {
        /* камера не вміє такого — масштабування нижче все одно зріже кадр */
      }
    }
  } else if (track && !constrainTrack && captured.has(track) && typeof track.applyConstraints === 'function') {
    // Камеру колись стиснули під одну ногу, а тепер ніг кілька — повертаємо
    // типове захоплення, інакше решта кімнати дивилась би на чужу мініатюру.
    try {
      await track.applyConstraints({
        height: { ideal: defaultHeight },
        frameRate: { ideal: DEFAULT_PLAN.maxFramerate, max: DEFAULT_PLAN.maxFramerate },
      });
    } catch {
      /* лишається як є */
    }
    captured.delete(track);
  }

  // Що камера віддає насправді — від цього рахуємо масштаб енкодера.
  let scale = 1;
  if (track && typeof track.getSettings === 'function') {
    const { width, height } = track.getSettings();
    const short = width && height ? Math.min(width, height) : 0;
    if (short > targetHeight) scale = short / targetHeight;
  }

  try {
    const params = sender.getParameters();
    if (!params.encodings || params.encodings.length === 0) {
      params.encodings = [{}];
    }
    const enc = params.encodings[0];
    enc.active = plan.active && !ownBatteryOff();
    enc.maxBitrate = plan.maxBitrate;
    enc.maxFramerate = plan.maxFramerate;
    enc.scaleResolutionDownBy = scale;
    const degradation = ((track && degradationByTrack.get(track)) ?? 'balanced') as
      | 'maintain-resolution'
      | 'maintain-framerate'
      | 'balanced';
    (params as RTCRtpSendParameters & { degradationPreference?: string }).degradationPreference = degradation;
    try {
      await sender.setParameters(params);
    } catch {
      // Частина WebView не приймає degradationPreference у setParameters.
      delete (params as { degradationPreference?: string }).degradationPreference;
      await sender.setParameters(params);
    }
    return true;
  } catch {
    return false;
  }
};

/**
 * Пріоритезує апаратний H.264 кодек над VP8 для мінімальної затримки і
 * максимальної енергоефективності на смартфонах.
 */
export const prioritizeVideoCodecs = (pc: RTCPeerConnection): void => {
  if (typeof RTCRtpReceiver === 'undefined' || typeof RTCRtpReceiver.getCapabilities !== 'function') {
    return;
  }
  try {
    const capabilities = RTCRtpReceiver.getCapabilities('video');
    if (!capabilities || !capabilities.codecs || capabilities.codecs.length === 0) {
      return;
    }

    // Впорядковуємо: спочатку H.264 (апаратне прискорення), потім VP9/VP8
    const h264Codecs = capabilities.codecs.filter(
      (c) => c.mimeType.toLowerCase() === 'video/h264',
    );
    const otherCodecs = capabilities.codecs.filter(
      (c) => c.mimeType.toLowerCase() !== 'video/h264',
    );
    const sortedCodecs = [...h264Codecs, ...otherCodecs];

    const transceivers = pc.getTransceivers();
    for (const transceiver of transceivers) {
      if (
        transceiver.receiver.track.kind === 'video' &&
        typeof transceiver.setCodecPreferences === 'function'
      ) {
        transceiver.setCodecPreferences(sortedCodecs);
      }
    }
  } catch {
    // Якщо браузер не підтримує setCodecPreferences — лишаємо системний вибір
  }
};

/**
 * Стеля, яку ми оголошуємо в SDP для ПРИЙОМУ відео: верх драбини ПОГЛЯДУ
 * (1080p30 / 3,5 Мбіт/с). Реальну стелю ставить відправник через
 * setParameters за планом — b=AS лише не дає браузеру зрізати те, що план
 * дозволив, і не потребує нових переговорів, коли план піднімається.
 */
export const VIDEO_SDP_CEILING_KBPS = VIDEO_RUNGS[VIDEO_RUNGS.length - 1].kbps;

/**
 * Ставить стелю відео в SDP — РІВНО ОДИН `b=AS` і `b=TIAS` на секцію відео.
 *
 * Порядок за RFC 4566: `m= i= c= b= … a=`. Тут до 08.10 стояло
 * `out.push('b=AS'); out.push(line)` — тобто `b=` ПЕРЕД `c=`, і частина
 * стеків такий рядок мовчки відкидала. А повторний прохід (пропозиція
 * поверх пропозиції) дописував другий `b=`. Тепер старі `b=` секції
 * знімаються, а новий стає після `c=` (або одразу після `m=`/`i=`, якщо
 * свого `c=` у секції немає) — до будь-якого `a=`.
 */
export const tuneVideoSdp = (sdp: string, kbps: number = VIDEO_SDP_CEILING_KBPS): string => {
  if (!sdp || !sdp.includes('m=video')) return sdp;

  const eol = sdp.includes('\r\n') ? '\r\n' : '\n';
  const lines = sdp.split(/\r\n|\n/);
  const out: string[] = [];
  const bandwidth = [`b=AS:${kbps}`, `b=TIAS:${kbps * 1000}`];

  let inVideo = false;
  let placed = false;

  for (const line of lines) {
    if (line.startsWith('m=')) {
      // Секцію відео покинули, а `b=` так і не став (секція без атрибутів).
      if (inVideo && !placed) out.push(...bandwidth);
      inVideo = line.startsWith('m=video ');
      placed = false;
      out.push(line);
      continue;
    }
    if (!inVideo) {
      out.push(line);
      continue;
    }
    if (line.startsWith('b=')) continue; // старі стелі секції — геть
    if (!placed && line.startsWith('c=')) {
      out.push(line, ...bandwidth);
      placed = true;
      continue;
    }
    if (!placed && !line.startsWith('i=')) {
      // Свого `c=` у секції немає — `b=` стає перед першим атрибутом.
      out.push(...bandwidth);
      placed = true;
    }
    out.push(line);
  }
  if (inVideo && !placed) out.push(...bandwidth);

  return out.join(eol);
};

/**
 * ЄДИНА лійка локального SDP. Кожен шлях переговорів — набір, прийом,
 * повторна пропозиція співрозмовника, повернення з рації (ICE restart і
 * з чистого аркуша), крок драбини — кладе в `setLocalDescription` лише те,
 * що пройшло тут. До 08.10 повторні шляхи ставили тільки Opus, і після
 * першого ж перепідключення відео лишалось без своєї стелі.
 */
export const finalizeLocalSdp = (sdp: string, level: AudioLevel): string =>
  tuneVideoSdp(tuneOpus(sdp, level));
