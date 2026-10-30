/**
 * Три сходинки звуку — і фізика, через яку вони саме такі.
 *
 * Наївне «менше бітрейт — менше трафік» на поганому каналі не працює. Пакет
 * несе не лише голос: IP+UDP+RTP це 40-54 байти заголовків на КОЖЕН пакет,
 * плюс накладні SRTP. При ptime=20 мс таких пакетів 50 на секунду, тобто
 * ~20 кбіт/с самої обгортки. Опустити голос до 10 кбіт/с і лишити ptime=20 —
 * значить везти 10 кбіт корисного і 20 кбіт обгортки: платимо втричі за те
 * саме. Тому головний виграш на вузькому каналі дає не бітрейт, а ДОВГИЙ
 * ptime: 120 мс це 8 пакетів на секунду замість 50, і обгортка падає з
 * ~20 кбіт/с до ~3.5. Саме тому «вузький» — це 10000 біт/с І ptime 120,
 * і одне без одного сенсу не має.
 *
 * usedtx=1 дозволяє кодеку мовчати, коли людина мовчить: у паузах у канал
 * не йде майже нічого. useinbandfec=1 кладе стиснуту копію попереднього
 * кадру всередину наступного — втрачений пакет відновлюється з наступного,
 * і саме це рятує розбірливість на 20-25% втрат.
 *
 * cbr=1 лише на найвужчій сходинці: сталий бітрейт гірший для якості, але
 * передбачуваний для каналу, який уже на межі.
 */

import { voiceNoteProfile } from '../hooks/voiceNoteProfile';

export type AudioLevel = 'crystal' | 'full' | 'thrifty' | 'narrow';

/**
 * Як сходинка називається для людини (журнал і тест-хуки, не екран).
 *
 * 08.10 (P0-5): верх — МОНО. Мікрофон телефона пише один канал, і стерео
 * 160k/96k везло двічі той самий голос за подвійну ціну. Opus моно на
 * повну смугу (48 кГц) при 64 кбіт/с — прозорий для мови; стерео лишено
 * майбутньому режиму музики чи показу екрана, де другий канал справді є.
 */
export const AUDIO_LEVEL_LABEL: Record<AudioLevel, string> = {
  crystal: 'crystal 64k mono',
  full: 'high 48k mono',
  thrifty: 'thrifty',
  narrow: 'narrow link',
};

export interface AudioProfile {
  /** maxaveragebitrate у бітах за секунду — стеля для самого голосу. */
  bitrate: number;
  /** Мілісекунд звуку в одному пакеті. Головний важіль на вузькому каналі. */
  ptime: number;
  maxptime: number;
  cbr: boolean;
  stereo: boolean;
  dtx: boolean;
  /**
   * Частота, яку ми готові ПРИЙМАТИ (fmtp maxplaybackrate). 48000 — повна
   * смуга; null — параметр не ставимо, кодек вибирає сам (вузькі сходинки).
   */
  maxplaybackrate: number | null;
}

export const AUDIO_PROFILES: Record<AudioLevel, AudioProfile> = {
  crystal: { bitrate: 64000, ptime: 20, maxptime: 40, cbr: false, stereo: false, dtx: false, maxplaybackrate: 48000 },
  full: { bitrate: 48000, ptime: 20, maxptime: 60, cbr: false, stereo: false, dtx: false, maxplaybackrate: 48000 },
  thrifty: { bitrate: 32000, ptime: 40, maxptime: 60, cbr: false, stereo: false, dtx: true, maxplaybackrate: null },
  narrow: { bitrate: 10000, ptime: 120, maxptime: 120, cbr: true, stereo: false, dtx: true, maxplaybackrate: null },
};

/** Від кращої до гіршої. Порядок спуску й підйому читається звідси. */
export const AUDIO_LEVELS: AudioLevel[] = ['crystal', 'full', 'thrifty', 'narrow'];

/**
 * Що просимо в мікрофона. Один канал — бо кодуємо моно і бо телефонний
 * мікрофон більшого не має; `{ideal: 2}`, що стояло, змушувало частину
 * WebView дублювати канал і віддавати енкодеру вдвічі більше семплів.
 * Обробку голосу (луна, шум, підсилення) не вимикаємо: без неї на гучномовці
 * співрозмовник чує сам себе.
 */
export const AUDIO_CAPTURE: MediaTrackConstraints = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
  channelCount: { ideal: 1 },
  sampleRate: { ideal: 48000 },
  sampleSize: { ideal: 16 },
};

/**
 * Інструмент у дзвінку: без обробки голосу, стерео, 128 кбіт/с — те саме, що
 * у голосовому «Музика». Лише на двох верхніх сходинках: вузький канал
 * музику все одно не довезе, а голос — довезе.
 */
const MUSIC_NOTE = voiceNoteProfile('music');
export const MUSIC_CAPTURE: MediaTrackConstraints = MUSIC_NOTE.audio;
export const MUSIC_PROFILE: AudioProfile = {
  bitrate: MUSIC_NOTE.bitsPerSecond,
  ptime: 20,
  maxptime: 40,
  cbr: false,
  stereo: true,
  dtx: false,
  maxplaybackrate: 48000,
};

let music = false;
export const setMusicMode = (on: boolean): void => {
  music = on;
};
export const musicMode = (): boolean => music;
export const audioCapture = (): MediaTrackConstraints => (music ? MUSIC_CAPTURE : AUDIO_CAPTURE);
export const audioProfileFor = (level: AudioLevel): AudioProfile =>
  music && (level === 'crystal' || level === 'full') ? MUSIC_PROFILE : AUDIO_PROFILES[level];

const OPUS_RTPMAP = /^a=rtpmap:(\d+)\s+opus\/48000/i;

/** Розбирає `a=fmtp:111 k=v;k2=v2` у пари, зберігаючи те, чого ми не знаємо. */
const parseFmtp = (line: string): { head: string; params: Map<string, string> } => {
  const space = line.indexOf(' ');
  const head = space < 0 ? line : line.slice(0, space + 1);
  const rest = space < 0 ? '' : line.slice(space + 1);
  const params = new Map<string, string>();
  rest
    .split(';')
    .map((kv) => kv.trim())
    .filter(Boolean)
    .forEach((kv) => {
      const eq = kv.indexOf('=');
      if (eq < 0) params.set(kv, '');
      else params.set(kv.slice(0, eq), kv.slice(eq + 1));
    });
  return { head, params };
};

const renderFmtp = (head: string, params: Map<string, string>): string =>
  head +
  [...params.entries()].map(([k, v]) => (v === '' ? k : `${k}=${v}`)).join(';');

/**
 * Ставить у SDP параметри Opus для вибраної сходинки.
 *
 * Мунгінг робиться ПЕРЕД setLocalDescription, бо fmtp в описі говорить про
 * те, що ця сторона згодна ПРИЙМАТИ. Обидві сторони крутять одне й те саме,
 * тож обидва кодеки отримують однакові рамки.
 *
 * Чужих параметрів не чіпаємо: у fmtp може лежати те, що поставив браузер.
 */
export const tuneOpus = (sdp: string, level: AudioLevel): string => {
  if (!sdp) return sdp;
  const profile = audioProfileFor(level);
  const eol = sdp.includes('\r\n') ? '\r\n' : '\n';
  const lines = sdp.split(/\r\n|\n/);

  const payload = lines.map((l) => OPUS_RTPMAP.exec(l)?.[1]).find(Boolean);
  if (!payload) return sdp;

  const wanted: Array<[string, string]> = [
    ['useinbandfec', '1'],
    ['usedtx', profile.dtx ? '1' : '0'],
    ['stereo', profile.stereo ? '1' : '0'],
    ['sprop-stereo', profile.stereo ? '1' : '0'],
    ['maxaveragebitrate', String(profile.bitrate)],
    ['maxptime', String(profile.maxptime)],
    ['ptime', String(profile.ptime)],
    ['cbr', profile.cbr ? '1' : '0'],
  ];
  if (profile.maxplaybackrate !== null) wanted.push(['maxplaybackrate', String(profile.maxplaybackrate)]);

  const fmtpPrefix = `a=fmtp:${payload} `;
  // Чи є свій fmtp у самому описі. Якщо є — правимо його; якщо нема — свій
  // дописуємо після rtpmap. Обидва шляхи разом дали б два fmtp на один
  // payload, а такий опис недійсний.
  const hasFmtp = lines.some((l) => l.startsWith(fmtpPrefix));
  const out: string[] = [];

  for (const line of lines) {
    if (line.startsWith(fmtpPrefix)) {
      const { head, params } = parseFmtp(line);
      // Від верхньої сходинки на нижній не лишається її смуга: опис каже
      // рівно те, що ця сходинка готова приймати.
      if (profile.maxplaybackrate === null) params.delete('maxplaybackrate');
      wanted.forEach(([k, v]) => params.set(k, v));
      out.push(renderFmtp(head, params));
      out.push(`a=ptime:${profile.ptime}`, `a=maxptime:${profile.maxptime}`);
      continue;
    }
    // Свої ptime перепишемо поруч із fmtp — двох однакових атрибутів не буває.
    if (/^a=(ptime|maxptime):/i.test(line)) continue;
    out.push(line);
    if (!hasFmtp && OPUS_RTPMAP.exec(line)) {
      out.push(renderFmtp(fmtpPrefix, new Map<string, string>(wanted)));
      out.push(`a=ptime:${profile.ptime}`, `a=maxptime:${profile.maxptime}`);
    }
  }
  return out.join(eol);
};

/**
 * Стеля бітрейту на живому відправнику — без переговорів заново.
 *
 * Це єдиний важіль, який діє миттєво: setParameters не чіпає ані ICE, ані
 * DTLS, тож розмова не переривається. ptime так змінити не можна (він живе
 * в SDP), і вдавати протилежне не будемо — рушій дотягує ptime окремою
 * пропозицією і чесно каже, коли та не вдалась.
 */
export const capSender = async (
  sender: RTCRtpSender | undefined,
  level: AudioLevel,
): Promise<boolean> => {
  if (!sender) return false;
  const profile = audioProfileFor(level);
  try {
    const params = sender.getParameters();
    if (!params.encodings || params.encodings.length === 0) {
      params.encodings = [{}];
    }
    params.encodings[0].maxBitrate = profile.bitrate;
    // Підтримують не всі — ставимо як підказку, а не як розрахунок.
    (params.encodings[0] as { ptime?: number }).ptime = profile.ptime;
    await sender.setParameters(params);
    return true;
  } catch {
    return false;
  }
};

/**
 * RED (RFC 2198) — у кожному пакеті ще й копія попереднього кадру. Коштує
 * ~удвічі більше голосу, тож вмикаємо лише там, де втрати вже вибили нас
 * на економні сходинки; на кришталевій і високій вистачає власного FEC Opus.
 */
export const wantsRed = (level: AudioLevel): boolean => level === 'thrifty' || level === 'narrow';

const isRed = (codec: { mimeType: string }): boolean => codec.mimeType.toLowerCase() === 'audio/red';

/** Порядок аудіокодеків для сходинки: RED попереду або позаду Opus. Без RED — як було. */
export const audioCodecOrder = <C extends { mimeType: string }>(level: AudioLevel, codecs: readonly C[]): C[] => {
  const red = codecs.filter(isRed);
  if (red.length === 0) return [...codecs];
  const rest = codecs.filter((c) => !isRed(c));
  return wantsRed(level) ? [...red, ...rest] : [...rest, ...red];
};

/** Ставить порядок аудіокодеків на трансивери ДО createOffer/createAnswer. */
export const preferAudioCodecs = (pc: RTCPeerConnection, level: AudioLevel): void => {
  if (typeof RTCRtpReceiver === 'undefined' || typeof RTCRtpReceiver.getCapabilities !== 'function') return;
  try {
    const codecs = RTCRtpReceiver.getCapabilities('audio')?.codecs;
    if (!codecs || codecs.length === 0) return;
    const order = audioCodecOrder(level, codecs);
    for (const transceiver of pc.getTransceivers()) {
      if (transceiver.receiver.track.kind === 'audio' && typeof transceiver.setCodecPreferences === 'function') {
        transceiver.setCodecPreferences(order);
      }
    }
  } catch {
    /* WebView без setCodecPreferences — лишається вибір браузера */
  }
};
