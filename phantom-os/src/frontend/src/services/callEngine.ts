/**
 * Дзвінок 1:1 — справжній: getUserMedia, RTCPeerConnection, ICE.
 *
 * Медіа йде між браузерами напряму (DTLS-SRTP), вузол його не бачить і не
 * може бачити. Вузол переносить лише SDP і кандидатів — див.
 * `api/routes_calls.py`. Тому все, що тут показано як «дзвінок», або справді
 * зʼєдналось, або чесно каже, що ні: жоден стан не малюється наперед.
 *
 * РЕТРАНСЛЯТОР. Коли прямої дороги немає, медіа їде через TURN — і змінюється
 * тільки маршрут: ключі лишаються в браузерах, тож ретранслятор возить той
 * самий шифротекст, якого не розуміє. Список доріг питаємо у вузла
 * (`GET /messenger/ice`): TURN належить вузлу, і його наявність — факт із
 * відповіді, а не константа в збірці.
 *
 * Таймер починає рахувати не з натискання кнопки, а з моменту, коли
 * зʼєднання перейшло в `connected` — інакше він рахував би очікування.
 * Статистика береться з `getStats()`; поки перший вимір не прийшов, її немає,
 * і показувати замість неї щось правдоподібне не можна.
 *
 * ДРАБИНА. Канал не буває «є» або «нема» — він буває різний, і дзвінок має
 * спускатися, а не вмирати. Сходинки: відео 1080 → відео 720 → повний звук →
 * економний → вузький (див. `callOpus.ts`, там же й фізика). Спуск робить `setParameters`
 * на відправнику — це діє миттєво і не чіпає ані ICE, ані DTLS, тож розмова
 * не переривається. ptime живе в SDP і без нової пропозиції не змінюється;
 * рушій пробує її дотягнути окремо і не вдає, що вийшло, якщо не вийшло.
 * Механіка кроків — у `callLadder.ts`.
 *
 * РАЦІЯ. Коли доріжки немає зовсім, дзвінок не завершується: голос переходить
 * на кадри листування (`callRadio.ts`). Це остання сходинка драбини, а не
 * окремий режим — тому кнопка «Завершити» лишається тією ж, а розмова тією ж.
 */

import { request } from './api';
import { iceConfig as nodeIceConfig, hasTurn, iceUrls, type IceConfig } from './iceRoads';
import { wsClient } from './websocket';
import { audioCapture, capSender, setMusicMode as setOpusMusic } from './callOpus';
import type { AudioLevel } from './callOpus';
import { preferAudioCodecs } from './callOpus';
import { applyVideoHint, capVideoSender, finalizeLocalSdp, prioritizeVideoCodecs } from './callVideo';
import { OpusLadder } from './callLadder';
import {
  REASON as R,
  REASON_NO_ANSWER_NOTE,
  HANGUP_LOST_MARK,
  HANGUP_LOST_TAIL,
  iceLostText,
  iceRejectedText,
  signalRefusedText,
  stallNoteText,
  type RelayTally,
} from './callReasons';
import type { LadderStep } from './callLadder';
import { RadioLink } from './callRadio';
import type { RadioTally } from './callRadio';
import { CallRoom } from './callRoom';
import type { LegView } from './callRoom';
import { currentNet, onCallPrefsChange } from './callPrefs';
import type { CallNet } from './callPrefs';
import { MIN_VIDEO_KBPS, STEADY_MS, openingCap, recordSteady } from './callMemory';
import {
  VIEW_CHANNEL,
  RateMeter,
  ViewAdvertiser,
  parseServe,
  parseView,
  planFor,
  readViewState,
  remoteTile,
  sentBytesOf,
  setCallBudget,
  keepOwnVideo,
  ownBatteryOff,
  watchDevice,
  weakSide,
  readEffectiveUhd,
} from './callView';
import type { ServeMsg, VideoPlan, ViewMsg, WeakSide } from './callView';

const STATS_PERIOD_MS = 2000;
/** Скільки триматися на екрані після завершення, перш ніж зникнути. */
const ENDED_LINGER_MS = 2600;
/** Скільки чекати зʼєднання, перш ніж сказати людині правду замість «набираю…». */
const STALL_AFTER_MS = 8000;
/**
 * Як часто звіряти склад кімнати з телефоном. Дві секунди — не «щоб швидше»:
 * це ті самі два, що й у статистики, і частіше означало б смикати міст рівно
 * тоді, коли він і так зайнятий кадрами дзвінка.
 */
const ROOM_SYNC_MS = 2000;

/** Скільки триматися в `disconnected`, перш ніж визнати доріжку мертвою. */
const DEAD_LINK_MS = 10000;

/**
 * «Готовий» телефону: перша пауза, стеля паузи і скільки спроб до
 * відпочинку. 1,5 + 3 + 6 + 12 + 15×6 ≈ 2 хв — більше, ніж міст законно
 * буває зайнятим на холодному старті.
 */
const READY_PAUSE_MS = 1500;
const READY_PAUSE_MAX_MS = 15_000;
const READY_ATTEMPTS = 11;

/**
 * Телефон не знає такого маршруту (старий APK, ПК): 404/405/501 або
 * відповідь сьогоднішнього моста на POST під `/call/`, якого він не
 * вирізняє (400 `no_call_id` чи `unknown_call_kind`). Це не збій, і
 * повторювати нема чого.
 */
export const routeUnknown = (err: unknown): boolean => {
  const e = err as { status?: unknown; code?: unknown } | null;
  if (e?.status === 404 || e?.status === 405 || e?.status === 501) return true;
  return e?.status === 400 && (e.code === 'no_call_id' || e.code === 'unknown_call_kind');
};
/** Як часто з рації пробувати повернутись у реальний час. */
const REVIVE_PERIOD_MS = 20000;


/**
 * Що показати, коли доріжка не встає, хоч співрозмовник уже відповів. Без TURN
 * це не «щось підвисло», а відома межа вузла, і людина має почути саме її.
 * З TURN казати це було б брехнею — тож текст залежить від виміряного стану.
 */
export const linkStallNote = (relay?: RelayTally): string => stallNoteText(hasTurn(), relay);

/**
 * Що ретранслятор насправді дав. Не «є TURN» із відповіді вузла (це лише
 * оголошення), а результат: скільки relay-адрес браузер зібрав і скільки
 * разів сервер відмовив.
 */
export type { RelayTally };

/**
 * А це — інша біда, і плутати їх не можна: відповіді ще не було, тож про
 * доріжку ми поки нічого не знаємо і валити все на TURN не маємо права.
 */
export const NO_ANSWER_NOTE = REASON_NO_ANSWER_NOTE;

/**
 * Сигнал не вийшов із нашого ж вузла — і це третя біда, окрема від двох
 * попередніх: справа не в тому боці й не в NAT, а в тому, що наш вузол відмовив.
 * `postTo` завжди повертав `{delivered:false, detail}`, і до 05.09 цього не
 * читав ЖОДЕН викликач: людина бачила «набираю…» над сигналом, який нікуди
 * не поїхав. Тепер причина доїжджає до неї слово в слово від вузла.
 */
/** Паузи перед повторами пропозиції: разом ~4 с із хвилини дзвону. */
export const OFFER_RETRY_MS = [1_500, 2_500] as const;

export const signalRefusedReason = signalRefusedText;

/**
 * Кандидати не виходять із вузла. Сам по собі один загублений кандидат нічого
 * не вирішує (їх десятки), тож кажемо про це лише тоді, коли доріжка так і не
 * встала: тоді це вже не дрібниця, а найімовірніша причина.
 */
export const iceLostNote = iceLostText;

/**
 * Чому дзвінок стоїть: `no-answer` — не відповіли, `no-path` — немає дороги,
 * `no-signal` — наш власний вузол не випустив сигнал.
 */
export interface CallStall {
  kind: 'no-answer' | 'no-path' | 'no-signal';
  note: string;
}

export type CallState = 'idle' | 'calling' | 'ringing' | 'connecting' | 'active' | 'ended';
export type CallMedia = 'audio' | 'video';

export interface CallPeer {
  contactId?: string;
  peerNodeId?: string;
  displayName: string;
  /** Чи звірили число безпеки. null — вузол цього не сказав. */
  verified?: boolean | null;
  callId?: string;
}

/** Виміряне, не оцінене. null означає «ще не міряли», а не нуль. */
export interface CallStats {
  rttMs: number | null;
  packetsLost: number | null;
  audioCodec: string | null;
  videoCodec: string | null;
  kbps: number | null;
  /**
   * Тип пари кандидатів, якою реально йде медіа: host — те саме LAN,
   * srflx — крізь NAT по STUN, relay — через TURN. Саме це число каже,
   * чи встане такий самий дзвінок поза локальною мережею.
   */
  localCandidate: string | null;
  remoteCandidate: string | null;
  /**
   * Втрати вхідних пакетів у відсотках за ОСТАННІЙ проміжок вимірювання, а
   * не з початку дзвінка: з них смужки якості. null — проміжку ще не було.
   */
  lossPct?: number | null;
  /**
   * Чим кодується наше відео (`encoderImplementation` з getStats) і чи це
   * апаратний енкодер (`powerEfficientEncoder`). Для журналу й доказів на
   * апараті: програмний VP8 на A52 — це нагрів і просідання кадрів.
   */
  encoderImpl?: string | null;
  powerEfficientEncoder?: boolean | null;
  /**
   * За останній проміжок не приїхало ЖОДНОГО пакета, хоч вхідні доріжки є:
   * розмова стоїть (Wi-Fi зник, ICE `disconnected`). RTT пари в цей час —
   * старий, і смужки з нього брехали б про сильний сигнал.
   */
  stalled?: boolean;
  /** Частота, яку мікрофон справді віддає (`getSettings().sampleRate`), Гц. */
  micHz?: number | null;
  /** Тактова частота узгодженого аудіокодека, Гц (Opus — 48000). */
  audioHz?: number | null;
  /** Скільки звуку ми шлемо за останній проміжок, кбіт/с. */
  audioKbpsOut?: number | null;
  /** Що реально йде з енкодера: коротка сторона кадру й кадри/с. */
  sentP?: number | null;
  sentFps?: number | null;
  /** Що реально приходить від співрозмовника. */
  recvP?: number | null;
  recvFps?: number | null;
}

/** Коротка сторона кадру з рядка getStats — «p» незалежно від повороту. */
const shortSide = (row: Record<string, unknown>): number | null => {
  const w = row.frameWidth;
  const h = row.frameHeight;
  return typeof w === 'number' && typeof h === 'number' && w > 0 && h > 0 ? Math.min(w, h) : null;
};

const fpsOf = (row: Record<string, unknown>): number | null =>
  typeof row.framesPerSecond === 'number' ? Math.round(row.framesPerSecond) : null;

/** Найбільша коротка сторона, яку камера взагалі вміє (`getCapabilities`). */
export const cameraMaxP = (track: MediaStreamTrack | null | undefined): number | null => {
  if (!track || typeof track.getCapabilities !== 'function') return null;
  try {
    const caps = track.getCapabilities() as MediaTrackCapabilities;
    const w = caps.width?.max;
    const h = caps.height?.max;
    return typeof w === 'number' && typeof h === 'number' ? Math.min(w, h) : null;
  } catch {
    return null;
  }
};

/** Яка камера знімає: передня (дзеркалимо) чи задня. */
export type CameraFacing = 'user' | 'environment';

export interface CallSnapshot {
  state: CallState;
  callId: string | null;
  peer: CallPeer | null;
  media: CallMedia;
  /** true — ми набрали; false — набрали нас. */
  outgoing: boolean;
  micOn: boolean;
  cameraOn: boolean;
  /** Чи є в цьому дзвінку відеодоріжка взагалі. */
  hasCamera: boolean;
  localStream: MediaStream | null;
  remoteStream: MediaStream | null;
  /** Мілісекунди епохи, коли доріжка справді відкрилась. */
  startedAt: number | null;
  stats: CallStats | null;
  /** Чому все скінчилось: словами, які можна показати людині. */
  endedReason: string | null;
  /** Зʼєднання не встало за відведений час — час сказати причину вголос. */
  stall: CallStall | null;
  /** На якій сходинці звук просто зараз. */
  audioLevel: AudioLevel;
  /** Сходинку тримає рука (тест-хук), а не вимір — автоспуск вимкнено. */
  ladderPinned: boolean;
  /** Відеодоріжку зняли, щоб урятувати звук. */
  videoDropped: boolean;
  /** Своя батарея майже порожня — відео з цього апарата стоїть. */
  battOff?: boolean;
  /**
   * Відео тримає стелю 720p. Дзвінок стартує з нею (D7: 720p типово);
   * 1080p30 / 3,5 Мбіт/с — лише після підйому драбини на рівному каналі.
   */
  videoCapped?: boolean;
  /** Камера, що знімає просто зараз. */
  facing?: CameraFacing;
  /** null — рація не потрібна. Не null — розмова йде кадрами. */
  radio: RadioTally | null;
  /** Чому ми в рації, словами для людини. */
  radioReason: string | null;
  /** Коротка звістка про канал: «Канал відновлено» і подібне. */
  linkNote: string | null;
  /** Сторінку перезапустили посеред розмови, і рушій набирає її знову. */
  resuming: boolean;
  /**
   * Склад кімнати — по нозі на співрозмовника.
   *
   * Порожній під час дзвінка 1:1 не буває: перша нога теж нога, і саме тому
   * картка малює однаковий рядок і для двох, і для восьми. Список, а не
   * `peer`, — бо `peer` уміє тримати рівно одного, і саме це й було стелею.
   */
  legs: LegView[];
  /** Стеля ніг за словами телефона. null — міст не питали або його немає. */
  roomMax: number | null;
  /** Кімната підійшла до стелі впритул — час сказати це людині. */
  roomCrowded: boolean;
  /** Голосовий дзвінок: камеру показують, поки тримають кнопку. */
  peeking?: boolean;
  /** Скільки байтів з'їв останній показ камери. null — показу не було. */
  peekBytes?: number | null;
  /** Доріжка вже жива, але грає догін хвоста рації — живий звук чекає. */
  catchingUp?: boolean;
  /**
   * Чи чути співрозмовників. Це НЕ дзеркало мікрофона: «мене не чути» і «я не
   * чую» — дві різні дії, і одна кнопка на обидві їх плутала б.
   */
  speakerOn: boolean;
  audioOnly?: boolean;
  /** Інструмент: без обробки голосу, стерео, 128 кбіт/с — до кінця дзвінка. */
  musicMode?: boolean;
  /**
   * ПОГЛЯД: що співрозмовник каже, що шле НАМ, і скільки це МБ/год за його
   * getStats. null — він ПОГЛЯДУ не вміє (старий клієнт) або ще не відповів.
   */
  served: ServeMsg | null;
  /** Вирок слабкого боку на цьому апараті, нікуди не шлеться. */
  weakSide?: WeakSide | null;
}

interface SignalResult {
  delivered: boolean;
  call_id: string;
  detail: string;
}

/** Що міст каже на «готовий», коли розмова пережила сторінку. */
interface ResumeWire {
  call_id: string;
  peer_id: string;
  name?: string;
  media?: string;
}

interface CallFrame {
  channel: string;
  type: string;
  data: {
    call_id?: string;
    kind?: string;
    from_node_id?: string;
    contact_id?: string;
    display_name?: string;
    verified?: boolean;
    sdp?: string | null;
    candidate?: RTCIceCandidateInit | null;
    media?: string | null;
    reason?: string | null;
    /** Тільки для kind='radio': номер шматка і сам звук. */
    seq?: number;
    audio_b64?: string;
  };
}

const IDLE: CallSnapshot = {
  state: 'idle',
  callId: null,
  peer: null,
  media: 'audio',
  outgoing: false,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: null,
  stats: null,
  endedReason: null,
  stall: null,
  audioLevel: 'crystal',
  ladderPinned: false,
  videoDropped: false,
  // D7: свіжий дзвінок — на 720p. Верх (1080) драбина дає сама, коли канал
  // ≈16 с поспіль рівний (`GOOD_SAMPLES_TO_RISE`), а не з першої секунди.
  videoCapped: true,
  facing: 'user',
  radio: null,
  radioReason: null,
  linkNote: null,
  resuming: false,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  audioOnly: false,
  served: null,
  weakSide: null,
};

/**
 * Що просимо в камери. 720p30 — типово (план ПОГЛЯДУ піднімає до 1080 через
 * `applyConstraints`, коли приймач справді показує стільки й канал тримає).
 * `exact` — лише для перемикання: там «будь-яка камера» означала б ту саму.
 */
const cameraConstraints = (facing: CameraFacing, exact = false): MediaTrackConstraints => ({
  facingMode: exact ? { exact: facing } : facing,
  width: { ideal: 1280, max: 1920 },
  height: { ideal: 720, max: 1080 },
  frameRate: { ideal: 30, max: 30 },
});

const newCallId = (): string =>
  `c_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;

class CallEngine {
  private snapshot: CallSnapshot = IDLE;
  private listeners = new Set<() => void>();

  private pc: RTCPeerConnection | null = null;
  private localStream: MediaStream | null = null;
  private statsTimer: ReturnType<typeof setInterval> | null = null;
  private lingerTimer: ReturnType<typeof setTimeout> | null = null;
  private stallTimer: ReturnType<typeof setTimeout> | null = null;
  private offWs: (() => void) | null = null;

  /** Кандидати, що прилетіли раніше, ніж зʼявилось куди їх класти. */
  private pendingIce: RTCIceCandidateInit[] = [];
  /** Пропозиція чекає, поки людина візьме слухавку. */
  private pendingOffer: string | null = null;
  /** Попередній вимір — щоб порахувати бітрейт як різницю, а не як здогад. */
  private lastBytes: { at: number; bytes: number } | null = null;
  /** Попередні байти звуку назовні — для `audioKbpsOut`. */
  private lastAudioOut: { at: number; bytes: number } | null = null;
  /** Попередні лічильники втрат — для `lossPct` за проміжок. */
  private lastLossTally: { lost: number; received: number } | null = null;
  private upLossPct: number | null = null;
  private weakVote: { side: WeakSide | null; n: number } = { side: null, n: 0 };
  /** Початок і останній бітрейт рівного відеопрогону для памʼяті каналу. */
  private steadySince: number | null = null;
  private topSince: number | null = null;
  private steadyKbps = 0;
  /** Чи озвалась інша сторона. Без цього «немає дороги» — це здогад, не факт. */
  private answered = false;
  /**
   * Скільки наших ICE-кандидатів вузол не взяв, і що він при цьому сказав.
   * Рахуємо, а не мовчимо: кандидатів десятки, і кричати про кожного не можна,
   * але коли доріжка не встала, це число — найчесніша відповідь на «чому».
   */
  private iceLost = 0;
  private lastSignalDetail = '';
  /** Кандидати з того боку, які браузер відмовився прийняти. Теж мовчали. */
  private iceRejected = 0;
  /**
   * Що дав ретранслятор. `hasTurn()` каже, що вузол ЙОГО ОГОЛОСИВ, — це не
   * те саме, що «він працює», і плутати оголошення з результатом означає
   * обіцяти людині дорогу, якої немає.
   */
  private relay: RelayTally = { candidates: 0, errors: 0, lastCode: null, lastText: '' };

  /* ── драбина ────────────────────────────────────────────────────────── */

  /** Драбині даємо руки, а не рушій — щоб не було кола імпортів. */
  private readonly ladder = new OpusLadder({
    pc: () => this.pc,
    allPcs: () => (this.pc ? [this.pc, ...this.room.allPcs().filter((p) => p !== this.pc)] : this.room.allPcs()),
    localStream: () => this.localStream,
    view: () => this.snapshot,
    patch: (next) => this.patch(next),
    armNote: () => this.armNote(),
    videoPlan: () => ({
      plan: this.currentPlan(),
      // Камера спільна для всіх ніг: стискати її під одну можна, лише коли
      // нога одна.
      constrain: this.room.live().length <= 1,
    }),
    sendOffer: (sdp) => {
      const callId = this.snapshot.callId;
      if (!callId) return;
      void this.post('offer', callId, { sdp, media: this.snapshot.media }).then((sent) => {
        // Драбина міняє ptime новою пропозицією. Не доїхала — сходинка
        // лишилась старою, і мовчати про це не можна: `callLadder` саме тому
        // й не вдає, що вийшло, коли не вийшло.
        if (!sent.delivered) this.noteRetryFailed();
      });
    },
  });

  /* ── кімната ────────────────────────────────────────────────────────── */

  /**
   * Ноги кімнати. Рушій лишається господарем ПЕРШОЇ (там драбина, рація,
   * статистика й сторожі), кімната веде решту й знає склад цілком.
   *
   * Руки вузькі навмисно: кімната не має права ані завершити дзвінок, ані
   * чіпати драбину. Дзвінок кінчається в одному місці — [finish].
   */
  private readonly room = new CallRoom({
    ice: () => this.iceConfig(),
    localStream: () => this.localStream,
    audioLevel: () => this.snapshot.audioLevel,
    callId: () => this.snapshot.callId,
    send: (peerId, kind, callId, body) =>
      this.postTo({ peerNodeId: peerId, displayName: '' }, kind, callId, body).then((r) => ({
        delivered: r.delivered,
        detail: r.detail,
      })),
    changed: () => this.publishRoom(),
  });

  /* ── ПОГЛЯД ─────────────────────────────────────────────────────────── */

  /** Канал кадрів `view`/`serve` першої ноги. Немає — співрозмовник старий. */
  private viewChannel: RTCDataChannel | null = null;
  /** Останній кадр `view` від співрозмовника: що він справді показує. */
  private peerView: ViewMsg | null = null;
  private serveSeq = 0;
  /** МБ/год того, що ми справді шлемо, — з bytesSent, не з плану. */
  private readonly sendMeter = new RateMeter();
  private readonly advertiser = new ViewAdvertiser({
    read: readViewState,
    send: (msg) => this.sendViewFrame(msg),
  });

  constructor() {
    // Плитка на екрані змінилась (розмір, видимість, сторінку сховали) —
    // кадр `view` вирішує сам, слати зараз, за 250 мс чи ніколи.
    remoteTile.subscribe(() => this.advertiser.poke());
    onCallPrefsChange(() => {
      this.advertiser.poke();
      void this.ladder.applyVideo();
    });
  }

  /** Скільки разів поспіль міст не відповів про кімнату. */
  private roomPoll: ReturnType<typeof setInterval> | null = null;

  /* ── рація ──────────────────────────────────────────────────────────── */

  private radio: RadioLink | null = null;
  private peekWanted = false;
  private peekTrack: MediaStreamTrack | null = null;
  private peekTx: RTCRtpTransceiver | null = null;
  private peekFrom: number | null = null;
  /** Рація, що догоняє хвіст після повернення доріжки. */
  private catching: RadioLink | null = null;
  private deadLinkTimer: ReturnType<typeof setTimeout> | null = null;
  private reviveTimer: ReturnType<typeof setInterval> | null = null;
  private noteTimer: ReturnType<typeof setTimeout> | null = null;

  /* ── підписка ───────────────────────────────────────────────────────── */

  subscribe = (cb: () => void): (() => void) => {
    this.listeners.add(cb);
    return () => {
      this.listeners.delete(cb);
    };
  };

  getSnapshot = (): CallSnapshot => this.snapshot;

  private patch(next: Partial<CallSnapshot>): void {
    const prev = this.snapshot;
    this.snapshot = { ...this.snapshot, ...next };
    if (
      prev.state === 'active' &&
      (this.snapshot.videoDropped ||
        (prev.videoCapped === false && this.snapshot.videoCapped === true) ||
        (prev.audioLevel !== this.snapshot.audioLevel && this.snapshot.audioLevel !== 'crystal'))
    ) {
      this.resetSteady();
    }
    if (this.snapshot.videoCapped || this.snapshot.videoDropped) {
      this.topSince = null;
    }
    this.listeners.forEach((cb) => {
      try {
        cb();
      } catch {
        /* підписник упав — це не привід валити дзвінок */
      }
    });
  }

  /**
   * Склад кімнати — у знімок. Окремим методом, бо кімната смикає його з
   * вимірювача гучності десять разів на секунду, і копіювати список там,
   * де нічого не змінилось, означало б перемальовувати екран дарма.
   */
  private publishRoom(): void {
    const legs = this.room.view();
    const { max, crowdedAt } = this.room.ceiling();
    const alive = legs.filter((l) => l.foot !== 'gone').length;
    this.patch({
      legs,
      roomMax: max,
      roomCrowded: crowdedAt !== null && alive >= crowdedAt,
    });
  }

  /**
   * Двері, у які телефон кладе кадри дзвінка (`PeerCallHub.WEB_SINK`).
   * Стрілкою — одна й та сама функція на все життя сторінки.
   */
  private readonly door = (data: unknown): void => {
    void this.onSignal(data);
  };

  /**
   * «Я готовий» телефону: `idle` — ще не казали; `trying` — кажемо (запит у
   * дорозі або чекаємо повтору); `rested` — повтори вичерпались, наступна
   * спроба — коли сторінка повернеться на екран; `done` — телефон прийняв
   * або сказав, що маршруту не знає. Раз на життя сторінки.
   */
  private readyState: 'idle' | 'trying' | 'rested' | 'done' = 'idle';
  private readyTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Вмикає слухання сигналів із вузла й моста. Повертає функцію відписки.
   *
   * 08.10 (P0-5): двері `window.__phantomCallSignal` ставить сам рушій, а не
   * `MessengerRoot` (той лишає своє присвоєння — воно зайве, але нешкідливе:
   * обидві функції кличуть [onSignal]). І одразу ПІСЛЯ дверей один раз
   * каже телефону `POST /messenger/call/ready`: `PeerCallHub` тримає кадри,
   * що приїхали до появи сторінки (холодний старт із дзвінка), і віддає їх
   * лише тоді — інакше пропозиція гинула б у порожнечу.
   */
  attach(): () => void {
    this.openDoor();
    if (this.offWs) return this.offWs;
    const off = wsClient.on('call', (msg) => {
      void this.onSignal(msg as unknown as CallFrame);
    });
    this.offWs = () => {
      off();
      this.offWs = null;
    };
    return this.offWs;
  }

  private openDoor(): void {
    if (typeof window === 'undefined') return;
    (window as unknown as Record<string, unknown>).__phantomCallSignal = this.door;
    if (this.readyState !== 'idle') return;
    this.readyState = 'trying';
    // Сторінка повернулась на екран (WebView ожив після сну, міст
    // перепідʼєднався) — неприйняте «готовий» кажемо знову.
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden' || this.readyState !== 'rested') return;
        this.readyState = 'trying';
        void this.sayReady(0);
      });
    }
    void this.sayReady(0);
  }

  /**
   * Маршруту телефон не знає — і це не помилка: такий APK кадрів не тримає,
   * віддає їх одразу, як і до 08.10. Він каже це або 404, або (сьогоднішній
   * міст, WebMessengerBridge.kt:543 → :3409) 400 `no_call_id`: POST під
   * `/call/`, якого немає в CALL_ROOM_VERBS, іде в сигнали дзвінка.
   *
   * Інша відмова (міст зайнятий, 504 порту) — повторюємо, бо втрачене
   * «готовий» означало б кадри, що чекають у телефоні вічно: на холодному
   * старті з вхідного дзвінка міст законно зайнятий секундами. Пауза росте
   * до 15 с; за ~2 хв повтори відпочивають до повернення сторінки на екран.
   */
  private async sayReady(attempt: number): Promise<void> {
    if (this.readyTimer) clearTimeout(this.readyTimer);
    this.readyTimer = null;
    try {
      const back = await request<{ resume?: ResumeWire | null } | null>('POST', '/messenger/call/ready', {});
      this.readyState = 'done';
      if (back?.resume) void this.resume(back.resume);
    } catch (err) {
      if (routeUnknown(err)) {
        this.readyState = 'done';
        return;
      }
      if (attempt + 1 >= READY_ATTEMPTS) {
        this.readyState = 'rested';
        return;
      }
      const pause = Math.min(READY_PAUSE_MAX_MS, READY_PAUSE_MS * 2 ** attempt);
      this.readyTimer = setTimeout(() => void this.sayReady(attempt + 1), pause);
    }
  }

  private peerKeys(peer: CallPeer | null | undefined): string[] {
    if (!peer) return [];
    const out: string[] = [];
    for (const raw of [peer.peerNodeId, peer.contactId]) {
      const id = typeof raw === 'string' ? raw.trim() : '';
      if (id && !out.includes(id)) out.push(id);
    }
    return out;
  }

  private net(): CallNet {
    const conn =
      typeof navigator !== 'undefined'
        ? (navigator as unknown as { connection?: { type?: unknown } }).connection
        : null;
    return currentNet(conn);
  }

  private resetSteady(): void {
    this.steadySince = null;
    this.topSince = null;
    this.steadyKbps = 0;
  }

  private flushSteady(): void {
    const keys = this.peerKeys(this.snapshot.peer);
    if (
      this.steadySince !== null &&
      this.steadyKbps >= MIN_VIDEO_KBPS &&
      this.snapshot.media === 'video' &&
      !this.snapshot.videoDropped &&
      keys.length > 0
    ) {
      const now = Date.now();
      const steadyMs = now - this.steadySince;
      if (steadyMs >= STEADY_MS) {
        const h = this.topSince !== null && now - this.topSince >= STEADY_MS ? 1080 : 720;
        const net = this.net();
        for (const id of keys) {
          recordSteady(id, net, { kbps: this.steadyKbps, h, steadyMs }, now);
        }
      }
    }
    this.resetSteady();
  }

  /* ── вихідний дзвінок ───────────────────────────────────────────────── */

  async startCall(peer: CallPeer, media: CallMedia = 'audio'): Promise<void> {
    if (this.snapshot.state !== 'idle' && this.snapshot.state !== 'ended') return;
    this.clearLinger();
    this.answered = false;
    this.resetSignalTally();
    this.resetSteady();
    this.ladder.newCall();

    const callId = peer.callId || newCallId();
    this.patch({
      ...IDLE,
      state: 'calling',
      callId,
      peer,
      media,
      outgoing: true,
      videoCapped: openingCap(this.peerKeys(peer), media, this.net()),
    });
    // Звірка з телефоном починається З НАБОРУ, а не з моменту, коли розмова
    // встала. Дзвінок у гілку заводить ногу КОЖНОМУ учаснику на боці телефона
    // (`PeerCallHub.invite`), і рушій про цих ніг не знає нічого: він їх не
    // будував. Почати звірку пізніше означало б показувати «набираємо» над
    // одним іменем, поки апарат дзвонить сімом.
    this.startRoomPoll();

    let stream: MediaStream;
    try {
      stream = await this.grabMedia(media);
    } catch (err) {
      this.finish(this.mediaError(err));
      return;
    }

    const pc = this.buildPeerConnection(callId, await this.iceConfig());
    stream.getTracks().forEach((track) => pc.addTrack(track, stream));
    prioritizeVideoCodecs(pc);
    preferAudioCodecs(pc, this.snapshot.audioLevel);

    try {
      const offer = await pc.createOffer({
        offerToReceiveAudio: true,
        offerToReceiveVideo: media === 'video',
      });
      // FEC і DTX треба поставити ДО setLocalDescription: після нього опис уже
      // не змінити, а домовлятись про них посеред розмови нема як.
      await pc.setLocalDescription({
        type: 'offer',
        sdp: finalizeLocalSdp(offer.sdp ?? '', this.snapshot.audioLevel),
      });
      const body = { sdp: pc.localDescription?.sdp ?? offer.sdp, media };
      const sent = await this.post('offer', callId, body);
      if (!sent.delivered) {
        void this.retryOffer(callId, body, sent);
        return;
      }

      // Пропозиція пішла. Далі стан міняє ЛИШЕ подія зʼєднання
      // (`onconnectionstatechange` → connected), а не годинник: таймер, який
      // сам оголошував «на звʼязку» через 2,5 с і показував людині її ж
      // локальний потік замість чужого, знято 05.09. Він же ставив
      // `answered = true`, чим назавжди вимикав сторожа `armStall` — обидва
      // чесні рядки нижче були недосяжні за побудовою.
      this.armStall(callId);
    } catch (err) {
      this.finish(this.plainError(err, R.offerFailed));
    }
  }

  /**
   * Перша пропозиція часто йде холодною дорогою (живий канал ще встає), і
   * людині доводилось набирати вдруге. Повторюємо самі, поки «набираю» чесне;
   * не взяли й тоді — причину людина бачить словами вузла.
   */
  private async retryOffer(callId: string, body: Record<string, unknown>, first: SignalResult): Promise<void> {
    let sent = first;
    const still = () => this.snapshot.callId === callId && this.snapshot.state === 'calling';
    for (const wait of OFFER_RETRY_MS) {
      await new Promise((resolve) => setTimeout(resolve, wait));
      if (!still()) return;
      sent = await this.post('offer', callId, body);
      if (!still()) return;
      if (sent.delivered) {
        this.armStall(callId);
        return;
      }
    }
    this.finish(signalRefusedReason(R.wOffer, sent.detail));
  }

  /**
   * Розмова, яку пережив телефон, але не рендерер WebView: міст на «готовий»
   * віддає її номер і співрозмовника. Нова сторінка набирає тим самим номером,
   * і співрозмовник приймає це як повторні переговори, а не як новий дзвінок.
   */
  private async resume(r: ResumeWire): Promise<void> {
    if (this.snapshot.state !== 'idle' && this.snapshot.state !== 'ended') return;
    if (!r.call_id || !r.peer_id) return;
    this.clearLinger();
    this.answered = false;
    this.resetSignalTally();
    this.resetSteady();
    this.ladder.newCall();
    const callId = r.call_id;
    const media: CallMedia = r.media === 'video' ? 'video' : 'audio';
    const peer: CallPeer = { peerNodeId: r.peer_id, displayName: r.name ?? '' };
    this.patch({
      ...IDLE,
      state: 'connecting',
      callId,
      peer,
      media,
      outgoing: true,
      resuming: true,
      videoCapped: openingCap(this.peerKeys(peer), media, this.net()),
    });
    this.startRoomPoll();

    let stream: MediaStream;
    try {
      stream = await this.grabMedia(media);
    } catch (err) {
      this.finish(this.mediaError(err));
      return;
    }
    const pc = this.buildPeerConnection(callId, await this.iceConfig());
    stream.getTracks().forEach((track) => pc.addTrack(track, stream));
    prioritizeVideoCodecs(pc);
    preferAudioCodecs(pc, this.snapshot.audioLevel);
    try {
      const offer = await pc.createOffer({ iceRestart: true });
      await pc.setLocalDescription({
        type: 'offer',
        sdp: finalizeLocalSdp(offer.sdp ?? '', this.snapshot.audioLevel),
      });
      const sent = await this.post('offer', callId, { sdp: pc.localDescription?.sdp ?? offer.sdp, media });
      if (!sent.delivered) {
        this.finish(signalRefusedReason(R.wOffer, sent.detail));
        return;
      }
      this.armStall(callId);
    } catch (err) {
      this.finish(this.plainError(err, R.offerFailed));
    }
  }

  /* ── вхідний дзвінок ────────────────────────────────────────────────── */

  async accept(): Promise<void> {
    if (this.snapshot.state !== 'ringing' || !this.pendingOffer) return;
    const callId = this.snapshot.callId;
    if (!callId) return;

    const offerSdp = this.pendingOffer;
    this.pendingOffer = null;
    this.answered = true;
    this.resetSteady();
    this.patch({
      state: 'connecting',
      videoCapped: openingCap(this.peerKeys(this.snapshot.peer), this.snapshot.media, this.net()),
    });

    let stream: MediaStream;
    try {
      stream = await this.grabMedia(this.snapshot.media);
    } catch (err) {
      void this.post('hangup', callId, { reason: 'no-media' }).then((sent) => {
        if (!sent.delivered) this.noteHangupLost();
      });
      this.finish(this.mediaError(err));
      return;
    }

    const pc = this.buildPeerConnection(callId, await this.iceConfig());
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: offerSdp });
      stream.getTracks().forEach((track) => pc.addTrack(track, stream));
      prioritizeVideoCodecs(pc);
      preferAudioCodecs(pc, this.snapshot.audioLevel);
      const answer = await pc.createAnswer();
      await pc.setLocalDescription({
        type: 'answer',
        sdp: finalizeLocalSdp(answer.sdp ?? '', this.snapshot.audioLevel),
      });
      await this.drainIce();
      const sent = await this.post('answer', callId, {
        sdp: pc.localDescription?.sdp ?? answer.sdp,
      });
      if (!sent.delivered) {
        this.finish(signalRefusedReason(R.wAnswer, sent.detail));
        return;
      }

      // Учасники, що постукали, поки слухавка ще дзвеніла: мікрофон тепер є,
      // тож їхні пропозиції нарешті мають чим відповісти. Без цього третій,
      // який набрав за секунду до «взяти», лишався б у кімнаті назавжди в
      // стані «дзвонить», хоч кадр від нього давно приїхав.
      for (const late of await this.room.openDeferred()) {
        const out = await this.postTo(
          { peerNodeId: late.peerId, displayName: '' }, 'answer', callId, { sdp: late.sdp },
        );
        if (!out.delivered) await this.room.drop(late.peerId, 'no-path', false);
      }

      // Слухавку взято — але доріжки ще немає. Тут стояв перехід у «розмову» з
      // підписом «Пряме P2P зʼєднання встановлено» і власним потоком у полі
      // співрозмовника: людина бачила себе і читала, що зʼєднання пряме, перш
      // ніж пройшов бодай один ICE-кандидат. Лишаємось у `connecting`, поки
      // `onconnectionstatechange` не скаже `connected`, а сторож рахує час.
      this.armStall(callId);
    } catch (err) {
      this.finish(this.plainError(err, R.acceptFailed));
    }
  }

  decline(): void {
    if (this.snapshot.state !== 'ringing') return;
    const callId = this.snapshot.callId;
    if (callId) void this.post('hangup', callId, { reason: 'declined' }).then((sent) => {
      if (!sent.delivered) this.noteHangupLost();
    });
    this.finish(R.declined);
  }

  hangup(): void {
    if (this.snapshot.state === 'idle' || this.snapshot.state === 'ended') return;
    const callId = this.snapshot.callId;
    // «Поклав» КОЖНІЙ живій нозі окремим кадром. Один кадр першому
    // співрозмовнику лишав би решту кімнати в розмові з привидом.
    const others = this.room.live().filter((l) => l.peerId !== this.primaryId());
    if (callId) {
      void this.post('hangup', callId, { reason: 'hangup' }).then((sent) => {
        if (!sent.delivered) this.noteHangupLost();
      });
      for (const leg of others) {
        void this.postTo({ peerNodeId: leg.peerId, displayName: '' }, 'hangup', callId, {
          reason: 'hangup',
        });
      }
    }
    // Кімнату на телефоні гасить сам телефон: без цього вона лишилась би
    // живою після того, як веб зник з екрана.
    void request('POST', '/messenger/call/leave', {}).catch(() => undefined);
    this.finish(R.finished);
  }

  /* ── мікрофон і камера ──────────────────────────────────────────────── */

  toggleMic(): void {
    const tracks = this.localStream?.getAudioTracks() ?? [];
    if (!tracks.length) return;
    const on = !this.snapshot.micOn;
    tracks.forEach((t) => {
      t.enabled = on;
    });
    this.patch({ micOn: on });
  }

  /**
   * Динамік. Окремою кнопкою від мікрофона, бо це окрема дія: «мене не чути»
   * і «я не чую» — різні речі, і одна кнопка на обидві їх плутала б.
   *
   * Тут лише прапорець: гасити звук мусить той, хто тримає елемент `<audio>`,
   * інакше рушій боровся б із карткою за один і той самий `muted`.
   */
  private cameraBeforeAudioOnly = false;

  toggleSpeaker(): void {
    this.catching?.muteCatchUp(this.snapshot.speakerOn);
    this.radio?.setMuted(this.snapshot.speakerOn);
    this.patch({ speakerOn: !this.snapshot.speakerOn });
  }

  /**
   * Вигнати ОДНУ ногу, лишивши розмову живою.
   *
   * Міст просимо окремо: кімнату на телефоні веде він, і без цього рядка
   * нога, знята тут, ожила б назад першою ж звіркою [syncRoom].
   */
  async dropLeg(peerId: string): Promise<void> {
    if (peerId === this.primaryId()) {
      // Першу ногу знімає той самий шлях, що й весь дзвінок, коли інших
      // немає; коли є — передаємо естафету й лишаємось у розмові.
      if (this.room.live().length > 1) {
        await this.room.drop(peerId, 'hung-up', true);
        this.handOver();
      } else {
        this.hangup();
      }
      return;
    }
    await this.room.drop(peerId, 'hung-up', true);
    await request('POST', '/messenger/call/drop', { peer_id: peerId }).catch(() => undefined);
    if (this.room.live().length === 0) this.finish(R.allLeft);
  }

  /**
   * Що про кімнату думає телефон.
   *
   * Рушій знає лише ті зʼєднання, які збудував САМ: ноги, заведені поки
   * месенджера не було на екрані, у ньому не існують. Без цієї звірки картка
   * домальовувала б кімнату з власної памʼяті — рівно те, чого міст 16.09 і
   * не дозволив, завівши `GET /call/room`.
   *
   * Міст відповідає лише на телефоні. У браузері ручки немає, звірка тихо не
   * вдається — і це не вада: там кімнату цілком веде рушій.
   */
  private async syncRoom(): Promise<void> {
    try {
      const data = await request<{
        legs: Array<{ peer_id: string; name?: string; foot: string; since_ms?: number; why?: string | null; reachable?: boolean }>;
        max_legs?: number;
        crowded_at?: number;
      }>('GET', '/messenger/call/room');
      this.room.sync(data);
    } catch {
      /* ручки немає — кімната лишається такою, якою її знає рушій */
    }
  }

  private startRoomPoll(): void {
    if (this.roomPoll) return;
    void this.syncRoom();
    this.roomPoll = setInterval(() => void this.syncRoom(), ROOM_SYNC_MS);
  }

  private stopRoomPoll(): void {
    if (this.roomPoll) clearInterval(this.roomPoll);
    this.roomPoll = null;
  }

  toggleCamera(): void {
    const tracks = this.localStream?.getVideoTracks() ?? [];
    if (!tracks.length) return;
    const on = !this.snapshot.cameraOn;
    tracks.forEach((t) => {
      t.enabled = on;
    });
    this.patch({ cameraOn: on });
  }

  setAudioOnly(on: boolean): void {
    const next = Boolean(on);
    if (next) {
      this.cameraBeforeAudioOnly = Boolean(this.snapshot.cameraOn);
      if (this.snapshot.cameraOn) {
        this.toggleCamera();
      }
    } else if (this.cameraBeforeAudioOnly && !this.snapshot.cameraOn) {
      this.cameraBeforeAudioOnly = false;
      this.toggleCamera();
    } else {
      this.cameraBeforeAudioOnly = false;
    }
    remoteTile.setForcedHidden(next);
    this.patch({ audioOnly: next, ...(next ? { cameraOn: false } : {}) });
    this.advertiser.poke();
  }

  async setMusicMode(on: boolean): Promise<void> {
    const next = Boolean(on);
    if (next === Boolean(this.snapshot.musicMode)) return;
    setOpusMusic(next);
    this.patch({ musicMode: next });
    const pcs = [this.pc, ...this.room.allPcs().filter((p) => p !== this.pc)].filter(
      (p): p is RTCPeerConnection => p !== null,
    );
    const senders = pcs
      .map((p) => p.getSenders().find((s) => s.track?.kind === 'audio'))
      .filter((s): s is RTCRtpSender => Boolean(s));
    const sender = senders[0];
    const old = sender?.track ?? null;
    if (sender && old) {
      let fresh: MediaStreamTrack | undefined;
      let swapped = false;
      try {
        fresh = (await navigator.mediaDevices.getUserMedia({ audio: audioCapture() })).getAudioTracks()[0];
        fresh.enabled = old.enabled;
        await Promise.all(senders.map((s) => s.replaceTrack(fresh!)));
        swapped = true;
        this.localStream?.removeTrack(old);
        this.localStream?.addTrack(fresh);
        old.stop();
      } catch {
        if (!swapped) {
          fresh?.stop();
          await old.applyConstraints(audioCapture()).catch(() => undefined);
        }
      }
    }
    await Promise.all(senders.map((s) => capSender(s, this.snapshot.audioLevel)));
    await this.ladder.renegotiate();
  }

  /**
   * Голосовий дзвінок лишається голосовим: камера йде, поки тримають кнопку,
   * на сходинці драбини (`capVideoSender`), і знімається, щойно відпустили.
   * Переговори — новою пропозицією поверх тієї самої доріжки.
   */
  async peekCamera(on: boolean): Promise<void> {
    const pc = this.pc;
    this.peekWanted = on;
    if (!pc || this.snapshot.media !== 'audio' || this.snapshot.state !== 'active') return;
    if (on) {
      if (this.peekTrack) return;
      let track: MediaStreamTrack | undefined;
      try {
        track = (await navigator.mediaDevices.getUserMedia({ audio: false, video: cameraConstraints('user') })).getVideoTracks()[0];
      } catch {
        return;
      }
      if (!track) return;
      if (!this.peekWanted || this.pc !== pc || this.peekTrack) {
        track.stop();
        return;
      }
      applyVideoHint(track, 'user');
      this.peekTrack = track;
      this.peekFrom = await this.dataUsedBytes();
      // Одна й та сама відеолінія на всі показі: нова лінія на кожен раз
      // роздувала б опис і кожного разу просила б приймач розбирати новий.
      const tx = this.peekTx && pc.getTransceivers().includes(this.peekTx) ? this.peekTx : null;
      if (tx) {
        await tx.sender.replaceTrack(track);
        tx.direction = 'sendrecv';
      } else {
        const sender = pc.addTrack(track, new MediaStream([track]));
        this.peekTx = pc.getTransceivers().find((t) => t.sender === sender) ?? null;
      }
      this.patch({ peeking: true });
      await capVideoSender(pc.getSenders().find((sd) => sd.track === track));
      // Пропозиція не доїхала — співрозмовник камери не побачить, і знімати
      // її далі означало б палити батарею й трафік на порожнечу.
      const offered = await this.reofferMedia();
      if (!offered || !this.peekWanted) await this.peekCamera(false);
      return;
    }
    const track = this.peekTrack;
    if (!track) return;
    this.peekTrack = null;
    track.stop();
    const tx = this.peekTx;
    if (tx) {
      await tx.sender.replaceTrack(null).catch(() => undefined);
      try {
        tx.direction = 'recvonly';
      } catch {
        /* лінію вже закрито */
      }
    }
    const now = await this.dataUsedBytes();
    const from = this.peekFrom;
    this.peekFrom = null;
    this.patch({ peeking: false, peekBytes: now !== null && from !== null ? Math.max(0, now - from) : null });
    await this.reofferMedia();
  }

  private async reofferMedia(): Promise<boolean> {
    const pc = this.pc;
    const callId = this.snapshot.callId;
    if (!pc || !callId || pc.signalingState !== 'stable') return false;
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription({ type: 'offer', sdp: finalizeLocalSdp(offer.sdp ?? '', this.snapshot.audioLevel) });
      const sent = await this.post('offer', callId, { sdp: pc.localDescription?.sdp, media: this.snapshot.media });
      return sent.delivered;
    } catch {
      return false;
    }
  }

  async dataUsedBytes(): Promise<number | null> {
    const pc = this.pc;
    if (!pc) return null;
    let report: RTCStatsReport;
    try {
      report = await pc.getStats();
    } catch {
      return null;
    }
    const pairs: Array<Record<string, unknown>> = [];
    let transportSum: number | null = null;
    report.forEach((row: Record<string, unknown>) => {
      if (
        row.type === 'candidate-pair' &&
        (row.selected === true || row.nominated === true || row.state === 'succeeded')
      ) {
        pairs.push(row);
      } else if (row.type === 'transport') {
        const sent = typeof row.bytesSent === 'number' && Number.isFinite(row.bytesSent) ? row.bytesSent : null;
        const recv =
          typeof row.bytesReceived === 'number' && Number.isFinite(row.bytesReceived) ? row.bytesReceived : null;
        if (sent !== null || recv !== null) {
          transportSum = (transportSum ?? 0) + (sent ?? 0) + (recv ?? 0);
        }
      }
    });
    if (pairs.length > 0) {
      pairs.sort((a, b) => {
        if ((a.selected === true) !== (b.selected === true)) return a.selected === true ? -1 : 1;
        return (
          Number(b.bytesSent ?? 0) +
          Number(b.bytesReceived ?? 0) -
          (Number(a.bytesSent ?? 0) + Number(a.bytesReceived ?? 0))
        );
      });
      const pair = pairs[0];
      const sent = typeof pair.bytesSent === 'number' && Number.isFinite(pair.bytesSent) ? pair.bytesSent : null;
      const recv =
        typeof pair.bytesReceived === 'number' && Number.isFinite(pair.bytesReceived) ? pair.bytesReceived : null;
      if (sent !== null || recv !== null) return (sent ?? 0) + (recv ?? 0);
    }
    return transportSum;
  }

  private flipping = false;
  /** `openCamera` погасив стару камеру, щоб відкрити нову. */
  private cameraStopped = false;

  /**
   * Камера пропала посеред дзвінка: зняти доріжку з усіх відправників
   * (`replaceTrack(null)` — нічого не йде, а не чорні кадри), прибрати її з
   * потоку і сказати знімку, що камери більше немає.
   */
  private async loseCamera(old: MediaStreamTrack): Promise<void> {
    const senders: RTCRtpSender[] = [];
    const pcs = [this.pc, ...this.room.live().map((l) => this.room.pcOf(l.peerId))];
    for (const pc of pcs) {
      pc?.getSenders().forEach((sender) => {
        if (sender.track === old) senders.push(sender);
      });
    }
    await Promise.all(senders.map((sender) => sender.replaceTrack(null).catch(() => undefined)));
    this.localStream?.removeTrack(old);
    this.ladder.dropSender();
    this.patch({ hasCamera: false, cameraOn: false, localStream: this.localStream });
  }

  /**
   * Передня ↔ задня камера посеред розмови.
   *
   * `replaceTrack` на кожному відправнику відео — і жодної нової пропозиції:
   * кодек, ICE і DTLS лишаються ті самі, тож картинка міняється без паузи
   * переговорів. Відправника, з якого драбина зняла відео (`track === null`),
   * не чіпаємо — камеру він отримає назад тим самим кроком драбини.
   *
   * Повертає false, коли перемкнути не вдалось (камери з того боку немає
   * або її не віддали) — тоді лишається та, що була.
   */
  async flipCamera(): Promise<boolean> {
    const stream = this.localStream;
    const old = stream?.getVideoTracks()[0];
    if (!stream || !old || this.flipping) return false;
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) return false;
    this.flipping = true;
    const was: CameraFacing = this.snapshot.facing === 'environment' ? 'environment' : 'user';
    const want: CameraFacing = was === 'user' ? 'environment' : 'user';
    try {
      const opened = await this.openCamera(want, was, old);
      if (!opened) {
        // Стару вже погасили (апарат двох камер разом не тримає), а жодна не
        // відкрилась: зупинена доріжка на відправнику — це завислий кадр у
        // співрозмовника. Знімаємо її чесно: камери в цьому дзвінку більше
        // немає, і кнопок «Камера»/«Обернути» над нею теж.
        if (this.cameraStopped) await this.loseCamera(old);
        return false;
      }
      const { track: fresh, facing } = opened;
      applyVideoHint(fresh, facing);
      fresh.enabled = this.snapshot.cameraOn;

      const senders = new Set<RTCRtpSender>();
      const pcs = [this.pc, ...this.room.live().map((l) => this.room.pcOf(l.peerId))];
      for (const pc of pcs) {
        pc?.getSenders().forEach((sender) => {
          if (sender.track && (sender.track === old || sender.track.kind === 'video')) senders.add(sender);
        });
      }
      await Promise.all(
        [...senders].map((sender) => sender.replaceTrack(fresh).catch(() => undefined)),
      );
      stream.removeTrack(old);
      stream.addTrack(fresh);
      if (old.readyState !== 'ended') old.stop();
      this.ladder.dropSender();
      this.patch({ facing, localStream: stream });
      // Нова доріжка — нова камера: план ПОГЛЯДУ ставимо на неї заново.
      void this.ladder.applyVideo();
      return facing === want;
    } finally {
      this.flipping = false;
    }
  }

  /**
   * Відкрити камеру з потрібного боку. Спершу — поки стара ще знімає (без
   * чорного кадру). Багато Android-апаратів двох камер разом не тримають
   * (NotReadableError) — тоді гасимо стару й пробуємо ще раз; не вийшло й
   * так — повертаємо ту, що була, щоб розмова не лишилась без картинки.
   */
  private async openCamera(
    want: CameraFacing,
    was: CameraFacing,
    old: MediaStreamTrack,
  ): Promise<{ track: MediaStreamTrack; facing: CameraFacing } | null> {
    const grab = async (facing: CameraFacing): Promise<MediaStreamTrack | null> => {
      const got = await navigator.mediaDevices.getUserMedia({ audio: false, video: cameraConstraints(facing, true) });
      return got.getVideoTracks()[0] ?? null;
    };
    this.cameraStopped = false;
    try {
      const track = await grab(want);
      return track ? { track, facing: want } : null;
    } catch (err) {
      // Такої камери немає взагалі — стара знімає далі.
      if ((err as { name?: string })?.name === 'OverconstrainedError') return null;
    }
    old.stop();
    this.cameraStopped = true;
    try {
      const track = await grab(want);
      if (track) return { track, facing: want };
    } catch {
      /* нижче повертаємо стару сторону */
    }
    try {
      const track = await grab(was);
      return track ? { track, facing: was } : null;
    } catch {
      return null;
    }
  }

  /* ── сигнали з вузла ────────────────────────────────────────────────── */

  public async onSignal(msg: any): Promise<void> {
    const data = msg?.data || msg;
    const kind = data?.kind ?? msg?.type?.replace('call:', '');
    const callId = data?.call_id;
    if (!kind || !callId) return;

    if (kind === 'offer') {
      await this.onOffer(callId, data);
      return;
    }

    // Усе інше стосується лише того дзвінка, який зараз іде.
    if (callId !== this.snapshot.callId) return;

    // ХТО прислав. До 17.09 цього поля тут не читали взагалі, і саме тому
    // відповідь третього учасника лягала на зʼєднання з другим: кадри
    // розбирались за номером дзвінка, а номер у конференції СПІЛЬНИЙ.
    const from: string | undefined = data.from_node_id;
    const extra = !!from && from !== this.primaryId() && this.room.connectedHere(from);

    if (kind === 'radio') {
      // Рацію слухаємо ЗАВЖДИ під час дзвінка. Інша сторона могла помітити
      // смерть доріжки раніше за нас — її голос має бути чутно вже зараз, а
      // не після того, як наш власний сторож дозріє.
      if (!this.radio) await this.enterRadio(R.peerRadio);
      void this.radio?.receive(Number(data.seq ?? 0), String(data.audio_b64 ?? ''));
      return;
    }

    if (kind === 'answer' && data.sdp) {
      if (extra && from) {
        await this.room.answer(from, data.sdp);
        return;
      }
      const pc = this.pc;
      // Відповідь на пропозицію, якої ми вже не чекаємо (переговори про ptime
      // розминулись із перебудовою) — це не помилка, а запізнілий лист.
      if (!pc || pc.signalingState !== 'have-local-offer') return;
      try {
        await pc.setRemoteDescription({ type: 'answer', sdp: data.sdp });
        this.answered = true;
        if (!this.primaryId() && from) {
          const prev = this.snapshot.peer;
          this.patch({
            peer: { ...(prev ?? { displayName: from }), peerNodeId: from },
          });
          this.room.adopt(from, prev?.displayName || from, pc, 'talking');
        }
        await this.drainIce();
      } catch (err) {
        if (!this.radio) this.finish(this.plainError(err, R.answerRejected));
      }
      return;
    }

    if (kind === 'ice' && data.candidate) {
      if (extra && from) await this.room.ice(from, data.candidate);
      else await this.addIce(data.candidate);
      return;
    }

    if (kind === 'hangup') {
      const rawReason = typeof data.reason === 'string' ? data.reason : '';
      const why =
        rawReason === 'declined' ||
        rawReason === 'busy' ||
        rawReason === 'room-full' ||
        rawReason === 'calls-off' ||
        rawReason === 'no-answer' ||
        rawReason === 'no-media'
          ? rawReason
          : 'hung-up';
      const extraHangup =
        !!from &&
        from !== this.primaryId() &&
        (this.room.connectedHere(from) || (this.room.has(from) && this.room.live().length > 1));
      // Вихід ОДНОГО учасника — не кінець розмови. До 17.09 будь-який
      // «поклав» валив дзвінок цілком: у конференції це означало б, що
      // перший, кому набридло, вимикає мікрофон усім іншим.
      if (extraHangup && from) {
        await this.room.drop(from, why, false);
        if (this.room.live().length === 0) this.finish(this.legLeftReason(why));
        return;
      }
      if (from && from === this.primaryId() && this.room.live().length > 1) {
        await this.room.drop(from, why, false);
        this.handOver();
        return;
      }
      this.finish(this.legLeftReason(why));
    }
  }

  /** Апарат першої ноги — тієї, що несе драбину, рацію і статистику. */
  private primaryId(): string | null {
    return this.snapshot.peer?.peerNodeId ?? null;
  }

  private legLeftReason(why: string): string {
    if (why === 'declined' || why === 'busy') return R.peerDeclined;
    if (why === 'no-answer') return NO_ANSWER_NOTE;
    if (why === 'calls-off') return R.callsOff;
    if (why === 'no-media') return R.captureUnavailable;
    return R.peerHungUp;
  }

  /**
   * Перша нога пішла, а кімната жива — хтось інший стає першим.
   *
   * Без цього вихід того, хто дзвонив, забирав би з собою драбину, рацію і
   * статистику, а разом із ними й сам дзвінок: `this.pc` став би null, і
   * сторожі зняли б розмову в решти. Промова чесна не повністю, і це
   * названо: у ноги, збудованої кімнатою, немає сторожа мертвої доріжки
   * рушія — вона гасне за `connectionState === 'failed'`, а не за десять
   * секунд у `disconnected`.
   */
  private handOver(): void {
    const next = this.room.live()[0];
    if (!next) {
      this.finish(R.allLeft);
      return;
    }
    this.teardownPc();
    const pc = this.room.pcOf(next.peerId);
    if (pc) {
      this.pc = pc;
      pc.addEventListener('connectionstatechange', () => {
        if (pc !== this.pc) return;
        if (pc.connectionState === 'failed') this.onLinkLost(R.trackUnstable);
      });
      this.startStats();
    }
    this.patch({
      peer: { peerNodeId: next.peerId, displayName: next.name, verified: null },
      remoteStream: next.stream,
    });
  }

  private async onOffer(callId: string, data: CallFrame['data']): Promise<void> {
    // ОСЬ ТУТ БУЛА ВАДА. Стояло рівно це:
    //
    //     if (callId === this.snapshot.callId && state !== 'idle' && data.sdp)
    //       await this.onReoffer(callId, data.sdp);
    //
    // Пропозиція ТРЕТЬОГО учасника приїжджає з тим самим номером дзвінка —
    // телефон 16.09 навчився заводити їй окрему ногу й віддавати кадр
    // сюди, — і цей рядок клав її опис ПОВЕРХ живої розмови з другим.
    // Розрізняє їх не номер (він спільний), а апарат.
    if (callId === this.snapshot.callId && this.snapshot.state !== 'idle' && data.sdp) {
      const from = data.from_node_id;
      const mine = this.primaryId();
      if (from && mine && from !== mine) {
        if (this.room.connectedHere(from)) {
          // Та сама додаткова нога домовляється заново (ptime, перезапуск ICE).
          const sdp = await this.room.reoffer(from, data.sdp);
          if (sdp) await this.postTo({ peerNodeId: from, displayName: '' }, 'answer', callId, { sdp });
          return;
        }
        // Мікрофона ще немає — слухавку не взяли. Нога існує й ВИДИМА, а
        // пропозиція чекає: відповісти зараз нема чим, а мовчки загубити
        // учасника означало б показати кімнату меншою, ніж вона є.
        if (!this.localStream) {
          this.room.defer(from, data.display_name || from, data.sdp);
          return;
        }
        const sdp = await this.room.open(from, data.display_name || from, data.sdp);
        if (sdp) {
          const sent = await this.postTo({ peerNodeId: from, displayName: '' }, 'answer', callId, { sdp });
          // Відповідь, яка не доїхала, — це нога, що назавжди лишиться
          // «дзвонить». Кажемо про неї словом, а не лишаємо крутитись.
          if (!sent.delivered) await this.room.drop(from, 'no-path', false);
        }
        return;
      }
      await this.onReoffer(callId, data.sdp);
      return;
    }
    if (this.snapshot.state !== 'idle' && this.snapshot.state !== 'ended') {
      // Уже в розмові — другий дзвінок не тримаємо мовчки в черзі, а чесно
      // відмовляємо, щоб той, хто набирає, побачив це одразу.
      const busy: CallPeer = { peerNodeId: data.from_node_id, displayName: '' };
      void this.postTo(busy, 'hangup', callId, { reason: 'busy' }).then(() => undefined);
      return;
    }
    if (!data.sdp) return;

    this.clearLinger();
    this.answered = false;
    this.resetSignalTally();
    this.resetSteady();
    this.pendingOffer = data.sdp;
    this.pendingIce = [];
    // Вхідний у гілку: поки слухавка дзвенить, телефон уже може тримати ноги
    // на решту учасників. Людина має бачити, у ЩО саме її кличуть.
    this.startRoomPoll();
    const media: CallMedia = data.media === 'video' ? 'video' : 'audio';
    const peer: CallPeer = {
      contactId: data.contact_id,
      peerNodeId: data.from_node_id,
      displayName: data.display_name || '',
      verified: data.verified ?? null,
    };
    this.patch({
      ...IDLE,
      state: 'ringing',
      callId,
      media,
      outgoing: false,
      peer,
      videoCapped: openingCap(this.peerKeys(peer), media, this.net()),
    });
  }

  /**
   * Ті самі переговори поверх того самого дзвінка. Доріжки може вже не бути
   * зовсім (нас витягують із рації) — тоді будуємо її заново, але номер
   * дзвінка, розмова й таймер лишаються ті самі.
   */
  private async onReoffer(callId: string, sdp: string): Promise<void> {
    const old = this.pc && this.pc.connectionState !== 'closed' ? this.pc : null;
    if (old && (await this.answerOn(old, callId, sdp))) return;
    // Старий канал опису не прийняв (той бік збудував свій заново після
    // перезапуску сторінки, і відбиток DTLS уже інший) — будуємо новий.
    const stream = this.localStream;
    if (!stream) {
      this.noteRetryFailed();
      return;
    }
    this.teardownPc();
    const pc = this.buildPeerConnection(callId, await this.iceConfig());
    stream.getTracks().forEach((track) => {
      if (track.kind === 'video' && this.snapshot.videoDropped) return;
      pc.addTrack(track, stream);
    });
    if (!(await this.answerOn(pc, callId, sdp))) this.noteRetryFailed();
  }

  private async answerOn(pc: RTCPeerConnection, callId: string, sdp: string): Promise<boolean> {
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp });
    } catch {
      return false;
    }
    try {
      const answer = await pc.createAnswer();
      await pc.setLocalDescription({
        type: 'answer',
        sdp: finalizeLocalSdp(answer.sdp ?? '', this.snapshot.audioLevel),
      });
      await this.drainIce();
      const sent = await this.post('answer', callId, { sdp: pc.localDescription?.sdp });
      // Те, що вже працює (рація або стара доріжка), від невдалих переговорів
      // не зупиняється — але й мовчати про них не можна.
      if (!sent.delivered) this.noteRetryFailed();
    } catch {
      this.noteRetryFailed();
    }
    return true;
  }

  /**
   * Спроба підняти доріжку не вдалась. Це не кінець дзвінка — рація тримає
   * розмову далі, — але й не подія, про яку мовчать: голий `catch {}` тут
   * означав, що людина двадцять хвилин не знає, чому досі говорить кадрами.
   */
  /**
   * Слухавку поклали, а сигнал про це не доїхав. Дрібниця рівно доти, доки не
   * згадаєш, що на тому боці в цю мить далі дзвонить телефон. Дописуємо це в
   * причину завершення — єдине місце, яке людина після дзвінка ще бачить.
   */
  private noteHangupLost(): void {
    if (this.snapshot.state !== 'ended') return;
    const was = this.snapshot.endedReason ?? R.finished;
    if (was.includes(HANGUP_LOST_MARK)) return;
    this.patch({
      endedReason: `${was}${HANGUP_LOST_TAIL}`,
    });
  }

  private noteRetryFailed(): void {
    if (!this.radio) return;
    this.patch({ linkNote: R.directRetryFailed });
    this.armNote();
  }

  private async grabMedia(media: CallMedia): Promise<MediaStream> {
    const isVideo = media === 'video';
    // Звук — один канал 48 кГц (`audioCapture`): кодуємо моно, і мікрофон
    // телефона більшого не має. Відео — 720p30 типово, до 1080 стелю
    // піднімає лише план ПОГЛЯДУ на верхній сходинці (`capVideoSender`).
    const constraints: MediaStreamConstraints = {
      audio: audioCapture(),
      video: isVideo ? cameraConstraints('user') : false,
    };
    try {
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      this.localStream = stream;
      applyVideoHint(stream.getVideoTracks()[0], 'user');
      const hasCamera = stream.getVideoTracks().length > 0;
      this.patch({ localStream: stream, hasCamera, cameraOn: hasCamera, micOn: true, facing: 'user' });
      return stream;
    } catch (err) {
      try {
        const fallbackStream = await navigator.mediaDevices.getUserMedia({
          audio: true,
          video: isVideo ? true : false,
        });
        this.localStream = fallbackStream;
        const hasCamera = fallbackStream.getVideoTracks().length > 0;
        this.patch({ localStream: fallbackStream, hasCamera, cameraOn: hasCamera, micOn: true });
        return fallbackStream;
      } catch {
        throw err;
      }
    }
  }

  /**
   * Дороги питаємо у вузла, а не вигадуємо. Креденшели ретранслятора живуть
   * годину, тож тримаємо їх у памʼяті пів цього часу: пара, видана зараз,
   * точно переживе дзвінок, який починається зараз. Вузол не відповів або
   * віддав порожній список — доріг нема жодних, чужих зокрема.
   */
  private async iceConfig(): Promise<IceConfig> {
    return nodeIceConfig();
  }

  /** Що вузол віддав минулого разу — для екрана налаштувань і доказів. */
  async iceInfo(): Promise<{ turn: boolean; ttl: number; urls: string[] }> {
    const config = await this.iceConfig();
    const urls = iceUrls(config);
    // Креденшели сюди не потрапляють свідомо: екрану вони не потрібні, а в
    // логу доказу були б зайвим життям пари, яка й так вмирає за годину.
    return { turn: config.turn, ttl: config.ttl, urls };
  }

  /**
   * План ПОГЛЯДУ для цього моменту: кадр приймача плюс чи драбина нагорі —
   * лише тоді 1080p30 / 3,5 Мбіт/с, інакше стеля 720p30 / 2,0.
   */
  private currentPlan(): VideoPlan {
    const uhd = readEffectiveUhd();
    const cam = uhd ? cameraMaxP(this.localStream?.getVideoTracks()[0]) : null;
    return planFor(this.peerView, { topTier: this.ladder.onTopTier(), uhdMax: cam ?? undefined });
  }

  private buildPeerConnection(callId: string, ice: IceConfig): RTCPeerConnection {
    // Тест-хук: змушує браузер відкинути host і srflx і піти виключно через
    // ретранслятор. Потрібен, щоб relay можна було ДОВЕСТИ, а не чекати
    // симетричного NAT, якого на цьому столі немає.
    const forceRelay =
      import.meta.env.DEV &&
      typeof window !== 'undefined' &&
      (window as unknown as Record<string, unknown>).__phantomForceRelay === true;

    const pc = new RTCPeerConnection({
      iceServers: ice.iceServers,
      ...(forceRelay ? { iceTransportPolicy: 'relay' as RTCIceTransportPolicy } : {}),
    });
    this.pc = pc;

    const remote = new MediaStream();
    this.patch({ remoteStream: remote });

    // ПОГЛЯД. Канал відкриває той, хто набирає, — до createOffer, щоб він
    // потрапив у пропозицію. Старий співрозмовник канал прийме мовчки й
    // нічого не шле: тоді лишаємось на типовому плані (720p30 / 2,0 Мбіт/с).
    if (this.snapshot.outgoing && this.snapshot.media === 'video') {
      try {
        this.wireView(pc.createDataChannel(VIEW_CHANNEL, { ordered: true }));
      } catch {
        /* без каналу — типовий план, дзвінок від цього не страждає */
      }
    }
    pc.ondatachannel = (event) => {
      if (pc === this.pc && event.channel.label === VIEW_CHANNEL) this.wireView(event.channel);
    };

    // Перша нога — теж нога. Кімната знає склад ЦІЛКОМ, інакше картка
    // малювала б «Іван + ще двоє» замість трьох рядків.
    const peer = this.snapshot.peer;
    if (peer?.peerNodeId) {
      this.room.adopt(
        peer.peerNodeId,
        peer.displayName || peer.peerNodeId,
        pc,
        this.snapshot.outgoing ? 'dialing' : 'ringing',
      );
    }

    // Відмова TURN — єдина подія, яка каже про ретранслятор ПРАВДУ. Без неї
    // рушій знає лише те, що вузол його оголосив.
    pc.addEventListener('icecandidateerror', (e) => {
      const err = e as RTCPeerConnectionIceErrorEvent;
      if (!/^turns?:/i.test(err.url ?? '')) return;
      this.relay.errors += 1;
      this.relay.lastCode = err.errorCode ?? null;
      this.relay.lastText = err.errorText ?? '';
    });

    pc.onicecandidate = (event) => {
      if (!event.candidate) return;
      // Зібрана relay-адреса — доказ, що ретранслятор справді роздає порти.
      // Саме її бракує, коли діапазон UDP на його боці закритий.
      if (/ typ relay /.test(event.candidate.candidate ?? '')) this.relay.candidates += 1;
      // Голе `void this.post(...)` тут ковтало відмову вузла мовчки. Кандидат,
      // який не виїхав, — це половина причини, чому дзвінок не встає.
      void this.post('ice', callId, { candidate: event.candidate.toJSON() }).then((sent) => {
        if (sent.delivered || this.snapshot.callId !== callId) return;
        this.iceLost += 1;
        if (sent.detail) this.lastSignalDetail = sent.detail;
      });
    };

    pc.ontrack = (event) => {
      if (event.track) {
        if (!remote.getTrackById(event.track.id)) {
          remote.addTrack(event.track);
        }
        const track = event.track;
        if (track.kind === 'video' && this.snapshot.media === 'audio') {
          // Показ камери в голосовому: відпустили — доріжка мовкне, і плитка
          // мусить зникнути, а не стояти останнім кадром.
          track.onmute = () => {
            remote.removeTrack(track);
            this.patch({ remoteStream: remote });
          };
          track.onunmute = () => {
            if (!remote.getTrackById(track.id)) remote.addTrack(track);
            this.patch({ remoteStream: remote });
          };
        }
      }
      if (event.streams && event.streams[0]) {
        event.streams[0].getTracks().forEach((t) => {
          if (!remote.getTrackById(t.id)) remote.addTrack(t);
        });
      }
      // Той самий обʼєкт, але React має побачити зміну.
      this.patch({ remoteStream: remote });
    };

    pc.onconnectionstatechange = () => {
      if (pc !== this.pc) return;
      if (pc.connectionState === 'connected') {
        this.markConnected(pc);
      } else if (pc.connectionState === 'failed') {
        this.onLinkLost(R.trackUnstable);
      } else if (pc.connectionState === 'disconnected') {
        // Не вирок: `disconnected` часто саме себе лікує за кілька секунд.
        // Ховаємо слухавку лише коли воно затягнулось.
        this.armDeadLink(R.trackGone);
      }
    };

    // ICE ламається раніше, ніж падає зʼєднання загалом: саме тут видно,
    // що прохідних пар кандидатів не лишилось.
    pc.oniceconnectionstatechange = () => {
      if (pc !== this.pc) return;
      if (pc.iceConnectionState === 'failed') this.onLinkLost(R.noCandidatePairs);
      else if (pc.iceConnectionState === 'disconnected') this.armDeadLink(R.trackGone);
      else if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
        // Теж СПРАВЖНЯ подія зʼєднання, а не годинник. Тримаємо її другим
        // входом у «розмову»: якщо браузер спізнюється з `connectionState`,
        // картка все одно піде за фактом, а не за таймером.
        this.markConnected(pc);
      }
    };

    return pc;
  }

  /**
   * Єдиний вхід у стан «розмова». Викликається ЛИШЕ з подій зʼєднання
   * (`connectionstate` і `iceconnectionstate`) — жодного таймера, жодного
   * «мабуть, уже». До 05.09 поруч жили два інші входи: `setTimeout` на 2,5 с
   * у `startCall` і безумовний `patch` у `accept()`, і обидва оголошували
   * розмову раніше, ніж існувала доріжка.
   */
  private markConnected(pc: RTCPeerConnection): void {
    if (pc !== this.pc) return;
    this.clearStall();
    this.clearDeadLink();
    // Доріжка встала — рація більше не потрібна. Це саме те місце, де
    // повернення в реальний час стає фактом, а не надією.
    if (this.radio) this.exitRadio();
    if (this.snapshot.state !== 'active') {
      this.patch({ state: 'active', startedAt: Date.now(), stall: null });
    }
    if (this.snapshot.resuming) {
      this.patch({ resuming: false, linkNote: R.channelRestored });
      this.armNote();
    }
    this.startStats();
    this.startRoomPoll();
    void this.ladder.applyRung();
  }

  /* ── доріжка вмирає ─────────────────────────────────────────────────── */

  /**
   * Доріжки немає. Далі є рівно два чесні виходи: якщо на тому боці точно
   * хтось є — переходимо на рацію, розмова триває. Якщо ніхто не відповідав —
   * рація нікому не потрібна, і дзвінок закінчується правдою.
   */
  private onLinkLost(why: string): void {
    this.clearDeadLink();
    if (this.snapshot.state === 'idle' || this.snapshot.state === 'ended') return;
    if (this.answered && this.canRadio()) {
      void this.enterRadio(why);
      return;
    }
    this.finish(this.linkFailureReason());
  }

  private armDeadLink(why: string): void {
    if (this.deadLinkTimer) return;
    if (this.snapshot.state === 'idle' || this.snapshot.state === 'ended') return;
    this.deadLinkTimer = setTimeout(() => {
      this.deadLinkTimer = null;
      const pc = this.pc;
      if (!pc) return;
      if (pc.connectionState === 'connected' || pc.iceConnectionState === 'connected') return;
      this.onLinkLost(why);
    }, DEAD_LINK_MS);
  }

  private clearDeadLink(): void {
    if (this.deadLinkTimer) clearTimeout(this.deadLinkTimer);
    this.deadLinkTimer = null;
  }

  /** Рація тримається на кадрах до вузла — без адреси співрозмовника її нема. */
  private canRadio(): boolean {
    const peer = this.snapshot.peer;
    return !!(peer && (peer.contactId || peer.peerNodeId) && this.localStream);
  }

  /* ── чесна межа звʼязку ─────────────────────────────────────────────── */

  /** Зʼєднання не встало жодного разу — це не «обірвалось», а не зійшлось. */
  private linkFailureReason(): string {
    if (this.snapshot.startedAt !== null) return R.linkDropped;
    return this.stallNow().note;
  }

  /**
   * Найчесніше, що ми можемо сказати про зупинку просто зараз. Порядок не
   * випадковий: спершу те, що зламано в НАС (сигнал не вийшов з вузла), бо
   * валити на NAT чужу тишу, коли не поїхав власний кандидат, — це та сама
   * вигадана причина, лише вбік мережі.
   */
  private stallNow(): CallStall {
    const detail = this.lastSignalDetail;
    if (this.iceLost > 0 && !this.answered) {
      return {
        kind: 'no-signal',
        note: signalRefusedReason(R.wSignals, detail) + iceLostNote(this.iceLost),
      };
    }
    if (!this.answered) return { kind: 'no-answer', note: NO_ANSWER_NOTE };
    const extra =
      this.iceLost > 0
        ? iceLostNote(this.iceLost)
        : this.iceRejected > 0
        ? iceRejectedText(this.iceRejected)
        : '';
    return { kind: 'no-path', note: linkStallNote(this.relay) + extra };
  }

  private armStall(callId: string): void {
    this.clearStall();
    this.stallTimer = setTimeout(() => {
      this.stallTimer = null;
      if (this.snapshot.callId !== callId) return;
      if (
        this.snapshot.state === 'calling' ||
        this.snapshot.state === 'ringing' ||
        this.snapshot.state === 'connecting'
      ) {
        // Слухавку взяли, а доріжка так і не встала. Дзвінок, який ніколи не
        // зʼєднався, теж має право жити рацією: людина на тому боці є.
        if (this.answered && this.canRadio()) {
          void this.enterRadio(R.directNeverUp);
          return;
        }
        this.patch({ stall: this.stallNow() });
      }
    }, STALL_AFTER_MS);
  }

  private clearStall(): void {
    if (this.stallTimer) clearTimeout(this.stallTimer);
    this.stallTimer = null;
  }

  private resetSignalTally(): void {
    this.iceLost = 0;
    this.iceRejected = 0;
    this.lastSignalDetail = '';
    this.relay = { candidates: 0, errors: 0, lastCode: null, lastText: '' };
  }

  /** Виміряний стан ретранслятора — для картки й для доказів. */
  relayTally(): RelayTally {
    return { ...this.relay };
  }

  private async addIce(candidate: RTCIceCandidateInit): Promise<void> {
    if (!this.pc || !this.pc.remoteDescription) {
      this.pendingIce.push(candidate);
      return;
    }
    try {
      await this.pc.addIceCandidate(candidate);
    } catch {
      // Кандидат, який не лягає, — не привід валити дзвінок, але й не привід
      // мовчати: якщо не ляже ЖОДЕН, це і буде причина, чому дороги не знайшлось.
      this.iceRejected += 1;
    }
  }

  private async drainIce(): Promise<void> {
    const queued = this.pendingIce;
    this.pendingIce = [];
    for (const candidate of queued) await this.addIce(candidate);
  }

  private post(
    kind: 'offer' | 'answer' | 'ice' | 'hangup',
    callId: string,
    body: Record<string, unknown>,
  ): Promise<SignalResult> {
    return this.postTo(this.snapshot.peer, kind, callId, body);
  }

  private async postTo(
    peer: CallPeer | null,
    kind: 'offer' | 'answer' | 'ice' | 'hangup',
    callId: string,
    body: Record<string, unknown>,
  ): Promise<SignalResult> {
    // Транслюємо сигнал у WebSocket / BroadcastChannel шину для локальних і парних вкладок
    const signalData = {
      call_id: callId,
      kind,
      from_node_id: peer?.peerNodeId,
      contact_id: peer?.contactId,
      display_name: peer?.displayName,
      ...body,
    };

    wsClient.send({
      channel: 'call',
      type: `call:${kind}`,
      data: signalData,
    });

    // ТУТ СТОЯВ ВИТІК. `globalP2PMesh.sendCallSignal(...)` публікував цей самий
    // `signalData` — тобто ВІДКРИТИЙ SDP і всі ICE-кандидати з адресами апарата —
    // у теми `phantom/mesh/user/<нік>` і `phantom/mesh/call/<call_id>` чужого
    // MQTT-брокера. Мовчав він рівно з однієї причини: `VITE_MESH_BROKER_URL`
    // порожня в цій збірці. Тобто безпеку дзвінка тримала змінна оточення, і
    // одне її значення відкривало SDP усьому світу.
    //
    // Тепер шлюз стоїть у коді й з обох боків: тут його немає взагалі, а
    // `sendCallSignal` відмовляє сам (див. globalP2PMesh.ts). Меш не вміє
    // шифрувати цей вміст і не знає, кому він призначений, — отже, сигналам
    // дзвінка там не місце, скільки б брокерів не налаштували.
    // Сторож `callsAreHonest.test.ts` червоніє, щойно виклик повертається.

    try {
      return await request<SignalResult>('POST', `/messenger/call/${kind}`, {
        call_id: callId,
        contact_id: peer?.contactId ?? null,
        peer_node_id: peer?.peerNodeId ?? null,
        ...body,
      });
    } catch (err) {
      const detail = err instanceof Error ? err.message : R.nodeRefusedSignal;
      return { delivered: false, call_id: callId, detail };
    }
  }

  /* ── драбина ────────────────────────────────────────────────────────── */

  /** Тест-хук і ручний режим — сама механіка в `callLadder.ts`. */
  forceLadder(level: AudioLevel | 'video' | 'auto'): Promise<string> {
    return this.ladder.force(level);
  }

  /** Що драбина встигла зробити — для доказів, а не для екрана. */
  ladderHistory(): LadderStep[] {
    return this.ladder.history();
  }

  /* ── рація ──────────────────────────────────────────────────────────── */

  private async enterRadio(why: string): Promise<void> {
    if (this.radio || !this.canRadio()) return;
    const callId = this.snapshot.callId;
    if (!callId) return;

    this.clearStall();
    this.clearDeadLink();
    this.stopStats();

    const link = new RadioLink(
      (tally) => this.patch({ radio: tally }),
      // Рація померла — це кінець дзвінка, а не привід далі малювати «розмову».
      (deadWhy) => {
        if (this.radio !== link) return;
        this.radio = null;
        this.clearRevive();
        this.finish(deadWhy);
      },
    );
    this.radio = link;
    // Стан ставимо в active навіть якщо доріжка ніколи не вставала: розмова
    // ЙДЕ, і картка «набираю…» тут була б брехнею.
    this.patch({
      state: 'active',
      startedAt: this.snapshot.startedAt ?? Date.now(),
      stall: null,
      radioReason: why,
      radio: null,
      linkNote: null,
      // Телеметрія доріжки, якої вже немає, — це брехня з точністю до
      // кілобіта. Останній вимір помер разом із доріжкою.
      stats: null,
      weakSide: null,
    });
    link.setMuted(!this.snapshot.speakerOn);
    link.start(
      {
        callId,
        contactId: this.snapshot.peer?.contactId,
        peerNodeId: this.snapshot.peer?.peerNodeId,
      },
      this.localStream,
    );

    this.clearRevive();
    this.reviveTimer = setInterval(() => void this.tryRevive(), REVIVE_PERIOD_MS);
  }

  private exitRadio(): void {
    const link = this.radio;
    if (!link) return;
    this.radio = null;
    this.clearRevive();
    const catchingUp = link.catchUp(() => {
      if (this.catching !== link) return;
      this.catching = null;
      this.patch({ catchingUp: false });
    }, !this.snapshot.speakerOn);
    if (catchingUp) this.catching = link;
    this.patch({ radio: null, radioReason: null, linkNote: R.channelRestored, catchingUp });
    this.armNote();
  }

  /**
   * Спроба повернутись у реальний час. Пробує лише той, хто набирав: якби
   * пробували обидва, дві пропозиції зустрілись би посередині.
   */
  private async tryRevive(): Promise<void> {
    if (!this.radio || !this.snapshot.outgoing) return;
    const callId = this.snapshot.callId;
    if (!callId) return;

    const pc = this.pc;
    if (pc && pc.connectionState !== 'closed' && pc.signalingState === 'stable') {
      try {
        const offer = await pc.createOffer({ iceRestart: true });
        await pc.setLocalDescription({
          type: 'offer',
          sdp: finalizeLocalSdp(offer.sdp ?? '', this.snapshot.audioLevel),
        });
        const sent = await this.post('offer', callId, {
          sdp: pc.localDescription?.sdp,
          media: this.snapshot.media,
        });
        if (!sent.delivered) this.noteRetryFailed();
        return;
      } catch {
        /* нижче спробуємо з чистого аркуша */
      }
    }
    await this.rebuildAndOffer(callId);
  }

  /** Доріжки немає взагалі — будуємо з нуля під тим самим номером дзвінка. */
  private async rebuildAndOffer(callId: string): Promise<void> {
    const stream = this.localStream;
    if (!stream) return;
    this.teardownPc();
    const pc = this.buildPeerConnection(callId, await this.iceConfig());
    stream.getTracks().forEach((track) => {
      if (track.kind === 'video' && this.snapshot.videoDropped) return;
      pc.addTrack(track, stream);
    });
    preferAudioCodecs(pc, this.snapshot.audioLevel);
    try {
      const offer = await pc.createOffer({
        offerToReceiveAudio: true,
        offerToReceiveVideo: this.snapshot.media === 'video',
      });
      await pc.setLocalDescription({
        type: 'offer',
        sdp: finalizeLocalSdp(offer.sdp ?? '', this.snapshot.audioLevel),
      });
      const sent = await this.post('offer', callId, {
        sdp: pc.localDescription?.sdp,
        media: this.snapshot.media,
      });
      if (!sent.delivered) this.noteRetryFailed();
    } catch {
      // Не вийшло — рація тримає розмову далі, спробуємо через 20 с. Але
      // мовчки, як було, людина цього не дізнається.
      this.noteRetryFailed();
    }
  }

  private clearRevive(): void {
    if (this.reviveTimer) clearInterval(this.reviveTimer);
    this.reviveTimer = null;
  }

  /** Коротка звістка про канал не має висіти вічно. */
  private armNote(): void {
    if (this.noteTimer) clearTimeout(this.noteTimer);
    this.noteTimer = setTimeout(() => {
      this.noteTimer = null;
      this.patch({ linkNote: null });
    }, 4000);
  }

  /* ── статистика ─────────────────────────────────────────────────────── */

  private startStats(): void {
    this.stopStats();
    void this.sampleStats();
    this.statsTimer = setInterval(() => void this.sampleStats(), STATS_PERIOD_MS);
  }

  private stopStats(): void {
    if (this.statsTimer) clearInterval(this.statsTimer);
    this.statsTimer = null;
    this.lastBytes = null;
    this.lastAudioOut = null;
    this.lastLossTally = null;
    this.upLossPct = null;
    this.weakVote = { side: null, n: 0 };
    this.resetSteady();
    this.sendMeter.reset();
    this.ladder.resetLoss();
  }

  private async sampleStats(): Promise<void> {
    const pc = this.pc;
    if (!pc) return;
    let report: RTCStatsReport;
    try {
      report = await pc.getStats();
    } catch {
      this.resetSteady();
      return;
    }

    const rows = new Map<string, Record<string, unknown>>();
    report.forEach((value: Record<string, unknown>, key: string) => rows.set(key, value));

    const codecOf = (row: Record<string, unknown> | undefined): string | null => {
      const codecId = row?.codecId as string | undefined;
      const codec = codecId ? rows.get(codecId) : undefined;
      const mime = codec?.mimeType as string | undefined;
      return mime ? mime.split('/')[1] ?? mime : null;
    };

    // Тип кандидата беремо з рядка, на який посилається пара, — вигадати
    // його з SDP не можна, а саме він каже, чи це LAN, STUN чи ретранслятор.
    const candidateTypeOf = (id: unknown): string | null => {
      if (typeof id !== 'string') return null;
      const type = rows.get(id)?.candidateType;
      return typeof type === 'string' ? type : null;
    };

    let rttMs: number | null = null;
    let remoteRttMs: number | null = null;
    let upLossMax: number | null = null;
    let packetsLost: number | null = null;
    let audioCodec: string | null = null;
    let videoCodec: string | null = null;
    let localCandidate: string | null = null;
    let remoteCandidate: string | null = null;
    let bytes = 0;
    let packetsReceived = 0;
    let sawInbound = false;
    let encoderImpl: string | null = null;
    let powerEfficientEncoder: boolean | null = null;
    let audioHz: number | null = null;
    let audioOutBytes: number | null = null;
    let sentP: number | null = null;
    let sentFps: number | null = null;
    let recvP: number | null = null;
    let recvFps: number | null = null;
    const clockOf = (row: Record<string, unknown>): number | null => {
      const codec = typeof row.codecId === 'string' ? rows.get(row.codecId) : undefined;
      return typeof codec?.clockRate === 'number' ? codec.clockRate : null;
    };

    rows.forEach((row) => {
      if (row.type === 'candidate-pair' && (row.nominated === true || row.state === 'succeeded')) {
        const rtt = row.currentRoundTripTime as number | undefined;
        if (typeof rtt === 'number') rttMs = Math.round(rtt * 1000);
        localCandidate = candidateTypeOf(row.localCandidateId) ?? localCandidate;
        remoteCandidate = candidateTypeOf(row.remoteCandidateId) ?? remoteCandidate;
      }
      if (row.type === 'outbound-rtp' && row.kind === 'video') {
        if (typeof row.encoderImplementation === 'string') encoderImpl = row.encoderImplementation;
        if (typeof row.powerEfficientEncoder === 'boolean') powerEfficientEncoder = row.powerEfficientEncoder;
        const p = shortSide(row);
        if (p !== null && (sentP === null || p > sentP)) {
          sentP = p;
          sentFps = fpsOf(row);
        }
      }
      if (row.type === 'outbound-rtp' && row.kind === 'audio') {
        if (typeof row.bytesSent === 'number') audioOutBytes = (audioOutBytes ?? 0) + row.bytesSent;
        audioHz = clockOf(row) ?? audioHz;
      }
      if (row.type === 'inbound-rtp') {
        sawInbound = true;
        const lost = row.packetsLost as number | undefined;
        if (typeof lost === 'number') packetsLost = (packetsLost ?? 0) + lost;
        const got = row.packetsReceived as number | undefined;
        if (typeof got === 'number') packetsReceived += got;
        const received = row.bytesReceived as number | undefined;
        if (typeof received === 'number') bytes += received;
        if (row.kind === 'audio') {
          audioCodec = codecOf(row) ?? audioCodec;
          audioHz = audioHz ?? clockOf(row);
        }
        if (row.kind === 'video') {
          videoCodec = codecOf(row) ?? videoCodec;
          const p = shortSide(row);
          if (p !== null && (recvP === null || p > recvP)) {
            recvP = p;
            recvFps = fpsOf(row);
          }
        }
      }
      if (row.type === 'remote-inbound-rtp') {
        const frac = row.fractionLost as number | undefined;
        if (typeof frac === 'number' && Number.isFinite(frac) && frac >= 0) {
          const pct = Math.min(100, frac * 100);
          upLossMax = upLossMax === null ? pct : Math.max(upLossMax, pct);
        }
        const rtt = row.roundTripTime as number | undefined;
        if (typeof rtt === 'number' && Number.isFinite(rtt) && rtt >= 0) {
          remoteRttMs = Math.round(rtt * 1000);
        }
      }
    });
    if (rttMs === null && remoteRttMs !== null) rttMs = remoteRttMs;
    const upLossPct = upLossMax;
    this.upLossPct = upLossPct;

    const prevBytes = this.lastBytes;
    const now = Date.now();
    let kbps: number | null = null;
    if (prevBytes && now > prevBytes.at) {
      const delta = bytes - prevBytes.bytes;
      if (delta >= 0) kbps = Math.round((delta * 8) / (now - prevBytes.at));
    }
    this.lastBytes = { at: now, bytes };

    let audioKbpsOut: number | null = null;
    if (audioOutBytes !== null) {
      const prevOut = this.lastAudioOut;
      if (prevOut && now > prevOut.at && audioOutBytes >= prevOut.bytes) {
        audioKbpsOut = Math.round(((audioOutBytes - prevOut.bytes) * 8) / (now - prevOut.at));
      }
      this.lastAudioOut = { at: now, bytes: audioOutBytes };
    }
    const micRate = this.localStream?.getAudioTracks()[0]?.getSettings?.().sampleRate;
    const micHz = typeof micRate === 'number' && micRate > 0 ? micRate : null;

    // Втрати ЗА ПРОМІЖОК — для смужок якості. Сумарне число росте вічно, і
    // погана хвилина назавжди лишала б одну смужку.
    let lossPct: number | null = null;
    // Пакети не зросли за проміжок — канал стоїть. Пара кандидатів при цьому
    // ще `succeeded` зі СТАРИМ RTT, тож без цього прапорця смужки показували
    // б 3–4 над завислою розмовою аж до сторожа мертвого каналу.
    let stalled = false;
    if (sawInbound) {
      const lostNow = packetsLost ?? 0;
      const prev = this.lastLossTally;
      if (prev && lostNow >= prev.lost && packetsReceived >= prev.received) {
        const lost = lostNow - prev.lost;
        const total = lost + (packetsReceived - prev.received);
        if (total > 0) lossPct = (lost / total) * 100;
        stalled = packetsReceived === prev.received;
      }
      this.lastLossTally = { lost: lostNow, received: packetsReceived };
    }

    // Скільки ми справді шлемо — і відповідь тому, хто про це питав.
    const sent = sentBytesOf(rows.values());
    if (sent !== null) this.sendMeter.sample(sent, now);
    if (this.peerView) this.sendServe();

    // Порожній вимір — це «ще нема», а не «нуль»: показувати нулі як
    // результат вимірювання не можна.
    if (
      rttMs === null &&
      packetsLost === null &&
      !audioCodec &&
      !videoCodec &&
      kbps === null &&
      !localCandidate &&
      !remoteCandidate &&
      !encoderImpl &&
      upLossPct === null
    ) {
      if (prevBytes !== null) this.resetSteady();
      return;
    }
    this.patch({
      stats: {
        rttMs,
        packetsLost,
        audioCodec,
        videoCodec,
        kbps,
        localCandidate,
        remoteCandidate,
        lossPct,
        encoderImpl,
        powerEfficientEncoder,
        stalled,
        micHz,
        audioHz,
        audioKbpsOut,
        sentP,
        sentFps,
        recvP,
        recvFps,
      },
    });
    // Вирок каналу — лише живій розмові: у рації доріжки немає, судити нічого.
    if (this.snapshot.state === 'active' && !this.radio) {
      if (
        this.snapshot.media === 'video' &&
        !this.snapshot.videoDropped &&
        kbps !== null &&
        kbps >= MIN_VIDEO_KBPS
      ) {
        const sampleStart = prevBytes?.at ?? now;
        if (this.steadySince === null) this.steadySince = sampleStart;
        if (this.snapshot.videoCapped === false) {
          if (this.topSince === null) this.topSince = sampleStart;
        } else {
          this.topSince = null;
        }
        this.steadyKbps = kbps;
      } else if (prevBytes !== null || this.snapshot.videoDropped || this.snapshot.media !== 'video') {
        this.resetSteady();
      }
      if (!stalled) {
        const nextSide = weakSide({
          upLossPct,
          downLossPct: lossPct,
          rttMs,
          peerUpLossPct: this.snapshot.served?.up ?? null,
        });
        if (this.weakVote.side === nextSide) {
          this.weakVote.n += 1;
        } else {
          this.weakVote = { side: nextSide, n: 1 };
        }
        if (this.weakVote.n >= 2 && this.snapshot.weakSide !== nextSide) {
          this.patch({ weakSide: nextSide });
        }
      }
      this.ladder.judgeLink(rttMs, packetsLost, packetsReceived);
    }
  }

  /* ── завершення ─────────────────────────────────────────────────────── */

  /** Знімає доріжку, не чіпаючи ані розмови, ані мікрофона. */
  private teardownPc(): void {
    const pc = this.pc;
    this.pc = null;
    this.ladder.dropSender();
    this.closeView();
    if (!pc) return;
    pc.ondatachannel = null;
    pc.onicecandidate = null;
    pc.ontrack = null;
    pc.onconnectionstatechange = null;
    pc.oniceconnectionstatechange = null;
    try {
      pc.close();
    } catch {
      /* уже закритий */
    }
  }

  private finish(reason: string): void {
    if (this.snapshot.state === 'idle') return;
    const settled =
      reason === R.finished ||
      reason === R.declined ||
      this.snapshot.startedAt != null ||
      (!this.snapshot.outgoing && this.snapshot.state === 'ringing');
    this.flushSteady();
    this.stopStats();
    this.clearStall();
    this.clearDeadLink();
    this.clearRevive();
    if (this.noteTimer) clearTimeout(this.noteTimer);
    this.noteTimer = null;
    this.radio?.stop();
    this.radio = null;
    this.ladder.endCall();
    keepOwnVideo(false);
    this.answered = false;
    this.pendingIce = [];
    this.pendingOffer = null;
    setOpusMusic(false);

    this.localStream?.getTracks().forEach((t) => t.stop());
    this.localStream = null;
    this.teardownPc();
    this.stopRoomPoll();
    // Ноги закриваються ПІСЛЯ першої: `teardownPc` гасить ту, що belongs
    // рушієві, а `closeAll` — решту. Інакше закрите зʼєднання лишилось би
    // висіти в кімнаті й тримати мікрофон після завершення.
    this.room.closeAll();
    this.catching?.stop();
    this.catching = null;
    this.peekTrack?.stop();
    this.peekTrack = null;
    this.peekTx = null;
    this.peekWanted = false;
    this.peekFrom = null;
    remoteTile.setForcedHidden(false);

    // Історію драбини НЕ чистимо: після завершення саме вона відповідає на
    // питання «а що взагалі відбувалось із каналом». Її стирає новий дзвінок.
    this.patch({
      state: 'ended',
      localStream: null,
      remoteStream: null,
      legs: [],
      roomMax: null,
      roomCrowded: false,
      catchingUp: false,
      peeking: false,
      peekBytes: null,
      speakerOn: true,
      audioOnly: false,
      musicMode: false,
      endedReason: reason,
      served: null,
      weakSide: null,
      stall: null,
      radio: null,
      radioReason: null,
      linkNote: null,
    });

    this.clearLinger();
    // Розмова, що відбулась, чи власне «поклав/відхилив» — екран іде сам.
    // Дзвінок, що не встав, лишається з «Передзвонити» й «Закрити»: людина
    // має побачити причину й вирішити, а не ловити її за 2,6 с.
    if (!settled) return;
    this.lingerTimer = setTimeout(() => {
      this.lingerTimer = null;
      if (this.snapshot.state === 'ended') this.patch({ ...IDLE });
    }, ENDED_LINGER_MS);
  }

  /** «Закрити» на екрані завершеного дзвінка. */
  dismiss(): void {
    if (this.snapshot.state !== 'ended') return;
    this.clearLinger();
    this.patch({ ...IDLE });
  }

  /**
   * «Передзвонити»: той самий співрозмовник і рід дзвінка. `peer.callId` є лише
   * в того, хто входив у живу кімнату групи, — тоді входимо в ту саму кімнату;
   * дзвінок удвох отримує новий номер у startCall.
   */
  redial(): Promise<void> {
    const { state, peer, media } = this.snapshot;
    if (state !== 'ended' || !peer || !(peer.peerNodeId || peer.contactId)) return Promise.resolve();
    return this.startCall(peer, media);
  }

  private clearLinger(): void {
    if (this.lingerTimer) clearTimeout(this.lingerTimer);
    this.lingerTimer = null;
  }

  private mediaError(err: unknown): string {
    const name = (err as { name?: string })?.name;
    if (name === 'NotAllowedError') return R.accessDenied;
    if (name === 'NotFoundError') return R.deviceNotFound;
    if (name === 'NotReadableError') return R.deviceBusy;
    return this.plainError(err, R.captureUnavailable);
  }

  private plainError(err: unknown, fallback: string): string {
    const message = err instanceof Error ? err.message : '';
    return message || fallback;
  }

  /* ── ПОГЛЯД: канал ──────────────────────────────────────────────────── */

  /** Тап по «Батарея сідає»: відео з цього апарата знову йде — лише в цьому дзвінку. */
  keepVideoOnLowBattery(): void {
    keepOwnVideo(true);
    if (this.snapshot.battOff) this.patch({ battOff: false });
    void this.ladder.applyVideo();
  }

  /** Своя батарея змінилась: стеля відправника і рядок на екрані — заново. */
  private onOwnDevice(): void {
    if (this.snapshot.state !== 'active') return;
    const off = ownBatteryOff();
    if (off !== (this.snapshot.battOff ?? false)) this.patch({ battOff: off });
    void this.ladder.applyVideo();
  }

  private wireView(channel: RTCDataChannel): void {
    this.closeView();
    this.viewChannel = channel;
    const start = () => {
      if (this.viewChannel !== channel) return;
      watchDevice(() => {
        this.advertiser.poke();
        this.onOwnDevice();
      });
      this.advertiser.start();
      this.onOwnDevice();
    };
    channel.onopen = start;
    channel.onmessage = (event) => this.onViewFrame(channel, event.data);
    channel.onclose = () => {
      if (this.viewChannel !== channel) return;
      this.closeView();
      // Кадрів більше не буде — повертаємось на типовий план, а не тримаємо
      // останній: «сховано» назавжди означало б вічну паузу відео.
      void this.ladder.applyVideo();
    };
    if (channel.readyState === 'open') start();
  }

  private closeView(): void {
    const channel = this.viewChannel;
    this.viewChannel = null;
    this.peerView = null;
    this.advertiser.stop();
    if (this.snapshot.served) this.patch({ served: null });
    if (!channel) return;
    channel.onopen = null;
    channel.onmessage = null;
    channel.onclose = null;
    try {
      channel.close();
    } catch {
      /* уже закритий */
    }
  }

  private onViewFrame(channel: RTCDataChannel, data: unknown): void {
    if (channel !== this.viewChannel) return;
    const view = parseView(data);
    if (view) {
      // Кадр зі старшим номером, ніж уже бачений, — запізнілий; не відкочуємось.
      if (this.peerView && view.seq < this.peerView.seq) return;
      this.peerView = view;
      void this.ladder.applyVideo().then(() => this.sendServe());
      return;
    }
    const serve = parseServe(data);
    if (serve) this.patch({ served: serve });
  }

  private sendViewFrame(msg: ViewMsg): void {
    const channel = this.viewChannel;
    if (!channel || channel.readyState !== 'open') return;
    channel.send(JSON.stringify(msg));
  }

  /** Відповідь `serve`: план, який стоїть, і виміряне МБ/год. */
  private sendServe(): void {
    const channel = this.viewChannel;
    if (!channel || channel.readyState !== 'open') return;
    const plan = this.currentPlan();
    const msg: ServeMsg = {
      t: 'serve',
      v: 1,
      seq: (this.serveSeq += 1),
      active: plan.active && !this.snapshot.videoDropped && this.snapshot.cameraOn,
      h: plan.height,
      fps: plan.maxFramerate,
      kbps: Math.round(plan.maxBitrate / 1000),
      mbh: this.sendMeter.value(),
      up: this.upLossPct,
    };
    try {
      channel.send(JSON.stringify(msg));
    } catch {
      /* канал закрився — onclose прибере решту */
    }
  }

  /** Стан ПОГЛЯДУ — для доказів на апараті, не для екрана. */
  viewProbe(): Record<string, unknown> {
    return {
      channel: this.viewChannel?.readyState ?? null,
      peerView: this.peerView,
      plan: this.currentPlan(),
      mine: readViewState(),
      sentMBh: this.sendMeter.value(),
      served: this.snapshot.served,
    };
  }

  /** Бюджет розмови (dev-ручка): кадр іде співрозмовнику негайно. */
  setBudget(mbh: number | null): void {
    setCallBudget(mbh);
    this.advertiser.poke();
  }

  /**
   * Вбиває доріжку, не чіпаючи розмови — так, як це робить погана мережа.
   * Потрібно, щоб перехід на рацію можна було ДОВЕСТИ, а не описати словами.
   */
  /** Сирий рядок відправника з getStats — щоб сходинку можна було ЗМІРЯТИ. */
  async probeRtp(): Promise<Record<string, unknown> | null> {
    const pc = this.pc;
    if (!pc) return null;
    const report = await pc.getStats();
    const found: Array<Record<string, unknown>> = [];
    report.forEach((row: Record<string, unknown>) => {
      if (row.type === 'outbound-rtp' && row.kind === 'audio') {
        found.push({
          bytesSent: row.bytesSent,
          packetsSent: row.packetsSent,
          targetBitrate: row.targetBitrate,
          at: Date.now(),
        });
      }
    });
    const out = found[found.length - 1] ?? null;
    const sender = pc.getSenders().find((s) => s.track?.kind === 'audio');
    const encoding = sender?.getParameters().encodings?.[0] as
      | { maxBitrate?: number; ptime?: number }
      | undefined;
    return out
      ? { ...out, maxBitrate: encoding?.maxBitrate ?? null, ptime: encoding?.ptime ?? null }
      : null;
  }

  /**
   * Куди саме їде медіа: обрана пара кандидатів із адресами й лічильниками.
   * `candidateType: 'relay'` з адресою ретранслятора — це і є доказ, що байти
   * ідуть крізь нього, а не повз. Вигадати це з SDP неможливо.
   */
  async probePath(): Promise<Record<string, unknown> | null> {
    const pc = this.pc;
    if (!pc) return null;
    const report = await pc.getStats();
    const rows = new Map<string, Record<string, unknown>>();
    report.forEach((row: Record<string, unknown>, id: string) => rows.set(id, row));

    const pairs: Array<Record<string, unknown>> = [];
    rows.forEach((row) => {
      if (row.type === 'candidate-pair' && (row.nominated === true || row.state === 'succeeded')) {
        pairs.push(row);
      }
    });
    // Пар може бути кілька; медіа їде тією, якою течуть байти.
    pairs.sort((a, b) => Number(b.bytesSent ?? 0) - Number(a.bytesSent ?? 0));
    const pair = pairs[0];
    if (!pair) return null;

    const side = (id: unknown): Record<string, unknown> => {
      const row = typeof id === 'string' ? rows.get(id) : undefined;
      return {
        type: row?.candidateType ?? null,
        address: row?.address ?? row?.ip ?? null,
        port: row?.port ?? null,
        protocol: row?.protocol ?? null,
        relayProtocol: row?.relayProtocol ?? null,
      };
    };

    const rtt = pair.currentRoundTripTime;
    return {
      at: Date.now(),
      state: pair.state ?? null,
      bytesSent: pair.bytesSent ?? null,
      bytesReceived: pair.bytesReceived ?? null,
      rttMs: typeof rtt === 'number' ? Math.round(rtt * 1000) : null,
      local: side(pair.localCandidateId),
      remote: side(pair.remoteCandidateId),
    };
  }

  killLinkForTest(): string {
    if (!this.pc) return R.noTrack;
    this.teardownPc();
    this.stopStats();
    this.onLinkLost(R.trackCutManual);
    return R.trackClosed;
  }
}

export const callEngine = new CallEngine();

/** Запуск дзвінка ззовні — Header приєднається сюди, коли буде готовий. */
export const startCall = (peer: CallPeer, media: CallMedia = 'audio'): Promise<void> =>
  callEngine.startCall(peer, media);

// Ручки для доказів. Живуть тільки в dev-збірці: у продукт вони не їдуть, бо
// дають стороннім скриптам керувати чужим дзвінком.
if (import.meta.env.DEV && typeof window !== 'undefined') {
  const w = window as unknown as Record<string, unknown>;
  w.__phantomCallLadder = (level: AudioLevel | 'video' | 'auto') =>
    callEngine.forceLadder(level);
  w.__phantomCallKillLink = () => callEngine.killLinkForTest();
  w.__phantomCallRtp = () => callEngine.probeRtp();
  w.__phantomCallPath = () => callEngine.probePath();
  w.__phantomCallView = () => callEngine.viewProbe();
  w.__phantomCallBudget = (mbh: number | null) => callEngine.setBudget(mbh);
  w.__phantomIce = () => callEngine.iceInfo();
  // Текст про межу звʼязку — щоб було видно, що він живе від стану вузла.
  w.__phantomLinkNote = () => linkStallNote(callEngine.relayTally());
  w.__phantomCallState = () => {
    const s = callEngine.getSnapshot();
    return {
      state: s.state,
      audioLevel: s.audioLevel,
      videoDropped: s.videoDropped,
      pinned: s.ladderPinned,
      stats: s.stats,
      radio: s.radio,
      radioReason: s.radioReason,
      linkNote: s.linkNote,
      ladder: callEngine.ladderHistory(),
    };
  };
}
