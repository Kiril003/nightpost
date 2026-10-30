/**
 * Кімната: кожен співрозмовник — окрема НОГА з окремим зʼєднанням.
 *
 * ## Навіщо це існує
 *
 * До 17.09 телефон уже вмів кімнату (`PeerCallLine.Room` у core-net, 16.09), а
 * веб — ні, і саме тут ця розбіжність ставала вадою, а не дрібницею. Рушій
 * (`callEngine`) тримає РІВНО ОДИН `RTCPeerConnection`, і його `onOffer`
 * поводився так:
 *
 *     if (callId === this.snapshot.callId && this.snapshot.state !== 'idle')
 *       await this.onReoffer(callId, data.sdp);   // ← setRemoteDescription
 *                                                 //   на ТОМУ САМОМУ pc
 *
 * Пропозиція ТРЕТЬОГО учасника приїжджає з тим самим `call_id` — телефон
 * тепер чесно заводить йому ногу й віддає кадр вебу, — і веб клав цей опис
 * поверх живої розмови з ДРУГИМ. Тобто котлінова правка не додала
 * конференції, а перенесла поломку на шар вище: третій учасник не отримував
 * «зайнято», він ЛАМАВ уже наявну розмову. Кімната мусить жити тут.
 *
 * ## Що кімната тримає і чого не тримає
 *
 * Тримає: по `RTCPeerConnection` на ногу, вхідний потік кожної ноги окремо,
 * виміряну гучність кожної (щоб сказати, ХТО говорить), стан кожної окремо
 * і причину, чому нога відпала.
 *
 * НЕ тримає: драбину, рацію і сторожа затримки. Вони живуть у рушієві й
 * стосуються ПЕРШОЇ ноги. Це названа межа, а не забута: додаткові ноги
 * отримують стелю бітрейту тієї сходинки, на якій кімната стоїть у мить
 * їхнього приходу ([capSender] + [tuneOpus] у [open]), але самі по сходинках
 * не спускаються і на рацію не переходять. Нога, що відпала, відпаде чесно —
 * з «доріжка не встала», а не мовчки.
 *
 * ## Голос: чому гучність міряється, а не вгадується
 *
 * «Хто говорить» — це не стан сигналізації, це звук. Тому на кожен вхідний
 * потік вішається `AnalyserNode` і рахується RMS. Поріг [SPEAKING_RMS] і
 * утримання [SPEAKING_HOLD_MS] — щоб підпис не миготів на паузах між словами.
 *
 * ВАЖЛИВО ПРО БРАУЗЕР: `AnalyserNode` над потоком WebRTC у Chromium дає нулі,
 * поки той самий потік не приєднаний до елемента `<audio>`/`<video>`. Тому
 * картка дзвінка ЗОБОВʼЯЗАНА тримати по елементу на ногу — без цього кімната
 * мовчатиме про те, хто говорить, і при цьому не збреше (буде `level: null`,
 * а не «ніхто не говорить»).
 */

import { tNow } from '../components/chat/tNow';
import { capSender, preferAudioCodecs } from './callOpus';
import type { AudioLevel } from './callOpus';
import { capVideoSender, finalizeLocalSdp, prioritizeVideoCodecs } from './callVideo';
import type { IceConfig } from './iceRoads';

/** Стан ноги. Ті самі чотири слова, що й `PeerCallLine.Foot` у телефоні. */
export type Foot = 'dialing' | 'ringing' | 'talking' | 'gone';

/** Гучність, з якої вважаємо, що людина говорить. RMS від 0 до 1. */
export const SPEAKING_RMS = 0.02;
/** Скільки тримати підпис «говорить» після останнього гучного виміру. */
export const SPEAKING_HOLD_MS = 600;
/** Як часто міряти гучність. 100 мс — швидше за око, дешевше за кадр. */
const LEVEL_PERIOD_MS = 100;

/** Що кімната показує про одну ногу. Усе тут або виміряне, або з мосту. */
export interface LegView {
  peerId: string;
  name: string;
  foot: Foot;
  /** Мілісекунди епохи, коли нога стала такою. Тривалість рахує той, хто малює. */
  sinceMs: number;
  /** Слово причини: 'declined', 'busy', 'no-path'… null — ще жива. */
  why: string | null;
  /**
   * Чи існує зʼєднання ТУТ, у цьому вебі.
   *
   * false при живій нозі — це не помилка, а правда: телефон завів ногу, поки
   * месенджера не було на екрані, і рушій про неї не знає. Мовчати про таку
   * ногу означало б показати меншу кімнату, ніж є насправді.
   */
  here: boolean;
  /** Пряма дорога до апарата за словами мосту. null — міст не питали. */
  reachable: boolean | null;
  /** Виміряна гучність 0..1. null — потоку ще немає, і це не тиша. */
  level: number | null;
  speaking: boolean;
  /** Перша нога: та, у якої драбина, рація і статистика. */
  primary: boolean;
  stream: MediaStream | null;
}

export interface RoomSignal {
  delivered: boolean;
  detail: string;
}

/** Руки, без яких кімнаті не жити. Рушій не імпортується — було б коло. */
export interface RoomDeps {
  ice(): Promise<IceConfig>;
  localStream(): MediaStream | null;
  audioLevel(): AudioLevel;
  callId(): string | null;
  send(
    peerId: string,
    kind: 'offer' | 'answer' | 'ice' | 'hangup',
    callId: string,
    body: Record<string, unknown>,
  ): Promise<RoomSignal>;
  /** Кімната змінилась — поверхням є що перемалювати. */
  changed(): void;
}

interface Leg {
  peerId: string;
  name: string;
  foot: Foot;
  sinceMs: number;
  why: string | null;
  primary: boolean;
  pc: RTCPeerConnection | null;
  stream: MediaStream | null;
  reachable: boolean | null;
  level: number | null;
  loudAt: number;
  analyser: AnalyserNode | null;
  buf: Float32Array<ArrayBuffer> | null;
  /** Кандидати, що приїхали раніше, ніж зʼявився опис, куди їх класти. */
  pendingIce: RTCIceCandidateInit[];
  /** Пропозиція, на яку ще нема чим відповісти: мікрофон не взято. */
  deferred: string | null;
}

export class CallRoom {
  private readonly deps: RoomDeps;
  private legs = new Map<string, Leg>();
  private ctx: AudioContext | null = null;
  private meter: ReturnType<typeof setInterval> | null = null;
  /** Стеля з телефона. null — міст ще не відповів, і вигадувати число не можна. */
  private maxLegs: number | null = null;
  private crowdedAt: number | null = null;

  constructor(deps: RoomDeps) {
    this.deps = deps;
  }

  /* ── читання ────────────────────────────────────────────────────────── */

  view(): LegView[] {
    return [...this.legs.values()]
      .sort((a, b) => (a.primary === b.primary ? a.sinceMs - b.sinceMs : a.primary ? -1 : 1))
      .map((l) => ({
        peerId: l.peerId,
        name: l.name,
        foot: l.foot,
        sinceMs: l.sinceMs,
        why: l.why,
        here: l.pc !== null,
        reachable: l.reachable,
        level: l.level,
        speaking: l.foot === 'talking' && Date.now() - l.loudAt < SPEAKING_HOLD_MS,
        primary: l.primary,
        stream: l.stream,
      }));
  }

  live(): LegView[] {
    return this.view().filter((l) => l.foot !== 'gone');
  }

  /** Стеля апарата словами телефона. null — ще не питали, і це чесніше за 0. */
  ceiling(): { max: number | null; crowdedAt: number | null } {
    return { max: this.maxLegs, crowdedAt: this.crowdedAt };
  }

  has(peerId: string): boolean {
    return this.legs.has(peerId);
  }

  /** Нога з живим зʼєднанням саме тут — не «нога взагалі». */
  connectedHere(peerId: string): boolean {
    const leg = this.legs.get(peerId);
    return !!leg && leg.pc !== null && leg.foot !== 'gone';
  }

  pcOf(peerId: string): RTCPeerConnection | null {
    return this.legs.get(peerId)?.pc ?? null;
  }

  /** Усі живі зʼєднання кімнати — драбині, щоб стеля лягла на КОЖНУ ногу. */
  allPcs(): RTCPeerConnection[] {
    return [...this.legs.values()]
      .filter((l) => l.foot !== 'gone' && l.pc)
      .map((l) => l.pc as RTCPeerConnection);
  }

  /* ── ведення ────────────────────────────────────────────────────────── */

  /**
   * Перша нога. Зʼєднання будує РУШІЙ (там драбина, рація й статистика), а
   * кімната його лише спостерігає: `addEventListener`, а не `onXxx=`, щоб не
   * збити обробники рушія.
   */
  adopt(peerId: string, name: string, pc: RTCPeerConnection, foot: Foot): void {
    const leg = this.put(peerId, name, foot, true);
    leg.pc = pc;
    this.watch(leg, pc);
    this.deps.changed();
  }

  /**
   * Нова нога: пропозиція з ТИМ САМИМ номером дзвінка від ІНШОГО апарата.
   * Ось вона, конференція — те, що старий `onOffer` клав поверх живої розмови.
   */
  async open(peerId: string, name: string, offerSdp: string): Promise<string | null> {
    const callId = this.deps.callId();
    const stream = this.deps.localStream();
    if (!callId || !stream) return null;

    const level = this.deps.audioLevel();
    const pc = new RTCPeerConnection({ iceServers: (await this.deps.ice()).iceServers });
    const leg = this.put(peerId, name, 'ringing', false);
    leg.pc = pc;
    this.watch(leg, pc);

    pc.onicecandidate = (e) => {
      if (!e.candidate) return;
      void this.deps.send(peerId, 'ice', callId, { candidate: e.candidate.toJSON() });
    };

    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: offerSdp });
      stream.getTracks().forEach((t) => pc.addTrack(t, stream));
      prioritizeVideoCodecs(pc);
      preferAudioCodecs(pc, level);
      const answer = await pc.createAnswer();
      await pc.setLocalDescription({ type: 'answer', sdp: finalizeLocalSdp(answer.sdp ?? '', level) });
      // Сходинка кімнати лягає на нову ногу ОДРАЗУ: нога, що прийшла на
      // вузький канал і почала з 64 кбіт/с, забрала б у решти саме те, чого
      // їм бракує.
      await capSender(pc.getSenders().find((s) => s.track?.kind === 'audio'), level);
      await capVideoSender(pc.getSenders().find((s) => s.track?.kind === 'video'));
      await this.drain(leg);
      this.setFoot(peerId, 'talking');
      return pc.localDescription?.sdp ?? answer.sdp ?? null;
    } catch {
      // Нога, яка не встала, лишається в кімнаті ЗІ СЛОВОМ, а не зникає:
      // людина має бачити, що цей учасник не доїхав, а не недолічитись його.
      this.gone(peerId, 'no-path');
      return null;
    }
  }

  /**
   * Учасник прийшов раніше, ніж узяли слухавку.
   *
   * Мікрофона ще немає, відповісти нема чим — але нога ВИДИМА вже зараз, зі
   * станом «дзвонить». Альтернатива (мовчки відкинути пропозицію) показала б
   * людині кімнату з двох, коли в ній уже троє, і третій зник би без сліду.
   */
  defer(peerId: string, name: string, offerSdp: string): void {
    const leg = this.put(peerId, name, 'ringing', false);
    leg.deferred = offerSdp;
    this.deps.changed();
  }

  /**
   * Слухавку взяли — тепер відкладені пропозиції можна підняти.
   *
   * Повертає ноги, яким треба надіслати відповідь: сам кадр шле рушій, у
   * якого лежить дорога до вузла.
   */
  async openDeferred(): Promise<Array<{ peerId: string; sdp: string }>> {
    const out: Array<{ peerId: string; sdp: string }> = [];
    for (const leg of [...this.legs.values()]) {
      const sdp = leg.deferred;
      if (!sdp || leg.foot === 'gone') continue;
      leg.deferred = null;
      const answer = await this.open(leg.peerId, leg.name, sdp);
      if (answer) out.push({ peerId: leg.peerId, sdp: answer });
    }
    return out;
  }

  /** Та сама нога пропонує заново: новий ptime або підйом доріжки. */
  async reoffer(peerId: string, sdp: string): Promise<string | null> {
    const leg = this.legs.get(peerId);
    const callId = this.deps.callId();
    if (!leg?.pc || !callId) return null;
    try {
      const level = this.deps.audioLevel();
      await leg.pc.setRemoteDescription({ type: 'offer', sdp });
      preferAudioCodecs(leg.pc, level);
      const answer = await leg.pc.createAnswer();
      await leg.pc.setLocalDescription({
        type: 'answer',
        sdp: finalizeLocalSdp(answer.sdp ?? '', level),
      });
      await this.drain(leg);
      return leg.pc.localDescription?.sdp ?? null;
    } catch {
      return null;
    }
  }

  async answer(peerId: string, sdp: string): Promise<void> {
    const leg = this.legs.get(peerId);
    if (!leg?.pc || leg.pc.signalingState !== 'have-local-offer') return;
    try {
      await leg.pc.setRemoteDescription({ type: 'answer', sdp });
      await this.drain(leg);
      this.setFoot(peerId, 'talking');
    } catch {
      this.gone(peerId, 'no-path');
    }
  }

  async ice(peerId: string, candidate: RTCIceCandidateInit): Promise<void> {
    const leg = this.legs.get(peerId);
    if (!leg?.pc) return;
    if (!leg.pc.remoteDescription) {
      leg.pendingIce.push(candidate);
      return;
    }
    try {
      await leg.pc.addIceCandidate(candidate);
    } catch {
      /* один кандидат, що не ліг, ще нічого не вирішує */
    }
  }

  /**
   * Вихід ОДНІЄЇ ноги, решта кімнати жива.
   *
   * `mine` — це ми женемо ногу (тоді їй треба надіслати «поклав») чи вона сама
   * пішла (тоді кадр уже прийшов, і слати назад нічого). Плутати не можна:
   * зайвий «поклав» на вже мертву ногу — це кадр у нікуди, а пропущений
   * означає, що на тому боці далі йде розмова з нами.
   */
  async drop(peerId: string, why: string, mine: boolean): Promise<void> {
    const leg = this.legs.get(peerId);
    if (!leg || leg.foot === 'gone') return;
    const callId = this.deps.callId();
    if (mine && callId) await this.deps.send(peerId, 'hangup', callId, { reason: why });
    this.gone(peerId, why);
  }

  /** Кімнати більше немає: закрити все, нічого не лишити тікати. */
  closeAll(): void {
    this.legs.forEach((leg) => this.shut(leg));
    this.legs.clear();
    if (this.meter) clearInterval(this.meter);
    this.meter = null;
    const ctx = this.ctx;
    this.ctx = null;
    if (ctx) void ctx.close().catch(() => undefined);
    this.maxLegs = null;
    this.crowdedAt = null;
    this.deps.changed();
  }

  /**
   * Правда телефона про кімнату — поверх нашої.
   *
   * Рушій знає ЛИШЕ ті зʼєднання, які збудував сам. Ноги, заведені поки
   * WebView не було на екрані, у ньому не існують, і без цієї звірки картка
   * домальовувала б кімнату з власної памʼяті. Тому міст тут — джерело
   * складу і стелі, а веб — джерело звуку й зʼєднань.
   */
  sync(bridge: {
    legs: Array<{ peer_id: string; name?: string; foot: string; since_ms?: number; why?: string | null; reachable?: boolean }>;
    max_legs?: number;
    crowded_at?: number;
  }): void {
    this.maxLegs = typeof bridge.max_legs === 'number' ? bridge.max_legs : this.maxLegs;
    if (typeof bridge.crowded_at === 'number') this.crowdedAt = bridge.crowded_at;
    const seen = new Set<string>();
    for (const raw of bridge.legs ?? []) {
      const foot = FEET.includes(raw.foot as Foot) ? (raw.foot as Foot) : 'gone';
      seen.add(raw.peer_id);
      const leg =
        this.legs.get(raw.peer_id) ?? this.put(raw.peer_id, raw.name || raw.peer_id, foot, false);
      if (raw.name) leg.name = raw.name;
      leg.reachable = typeof raw.reachable === 'boolean' ? raw.reachable : leg.reachable;
      if (raw.why !== undefined) leg.why = raw.why ?? leg.why;
      if (typeof raw.since_ms === 'number' && raw.since_ms > 0) leg.sinceMs = raw.since_ms;
      // Телефон каже «ноги немає» — значить немає, хоч би що думав рушій:
      // кадр «поклав» міг приїхати тоді, коли екрана не було.
      if (foot === 'gone' && leg.foot !== 'gone') this.gone(raw.peer_id, leg.why ?? 'hung-up');
      else if (leg.foot !== 'talking' && foot !== 'gone') leg.foot = foot;
    }
    // Нога, якої телефон не знає, а ми знаємо, лишається: вона могла зʼявитись
    // мілісекунду тому і ще не доїхати до мосту. Викидати її було б гірше за
    // зайвий рядок — людина побачила б, як співрозмовник блимає.
    this.deps.changed();
  }

  /* ── нутрощі ────────────────────────────────────────────────────────── */

  private put(peerId: string, name: string, foot: Foot, primary: boolean): Leg {
    const had = this.legs.get(peerId);
    if (had) {
      had.name = name || had.name;
      had.foot = foot;
      had.sinceMs = Date.now();
      had.why = null;
      if (primary) had.primary = true;
      return had;
    }
    const leg: Leg = {
      peerId,
      name: name || peerId,
      foot,
      sinceMs: Date.now(),
      why: null,
      primary,
      pc: null,
      stream: null,
      reachable: null,
      level: null,
      loudAt: 0,
      analyser: null,
      buf: null,
      pendingIce: [],
      deferred: null,
    };
    this.legs.set(peerId, leg);
    return leg;
  }

  private setFoot(peerId: string, foot: Foot): void {
    const leg = this.legs.get(peerId);
    if (!leg || leg.foot === foot || leg.foot === 'gone') return;
    leg.foot = foot;
    leg.sinceMs = Date.now();
    this.deps.changed();
  }

  private gone(peerId: string, why: string): void {
    const leg = this.legs.get(peerId);
    if (!leg || leg.foot === 'gone') return;
    leg.foot = 'gone';
    leg.why = why;
    leg.sinceMs = Date.now();
    this.shut(leg);
    this.deps.changed();
  }

  private shut(leg: Leg): void {
    try {
      leg.analyser?.disconnect();
    } catch {
      /* вузол уже відʼєднаний */
    }
    leg.analyser = null;
    leg.buf = null;
    leg.level = null;
    // Першу ногу гасить рушій (`teardownPc`): там ще драбина й статистика,
    // і закрити її тут означало б два господарі одного зʼєднання.
    if (!leg.primary) {
      try {
        leg.pc?.close();
      } catch {
        /* уже закрите */
      }
    }
    leg.pc = null;
  }

  private async drain(leg: Leg): Promise<void> {
    const queued = leg.pendingIce;
    leg.pendingIce = [];
    for (const c of queued) {
      try {
        await leg.pc?.addIceCandidate(c);
      } catch {
        /* не ліг — не привід валити ногу */
      }
    }
  }

  /** Спостереження за чужим (першим) або власним зʼєднанням. */
  private watch(leg: Leg, pc: RTCPeerConnection): void {
    pc.addEventListener('track', (e) => {
      const ev = e as RTCTrackEvent;
      const stream = leg.stream ?? new MediaStream();
      const add = (t: MediaStreamTrack) => {
        if (!stream.getTrackById(t.id)) stream.addTrack(t);
      };
      if (ev.track) add(ev.track);
      ev.streams?.[0]?.getTracks().forEach(add);
      leg.stream = stream;
      this.listen(leg, stream);
      this.deps.changed();
    });
    pc.addEventListener('connectionstatechange', () => {
      if (pc.connectionState === 'connected') this.setFoot(leg.peerId, 'talking');
      // Додаткову ногу, чиє зʼєднання впало, гасимо самі. Першу веде рушій —
      // у нього рація й сторожі, і поспішити тут означало б обірвати розмову
      // за секунду до того, як вона сама полагодиться.
      else if (pc.connectionState === 'failed' && !leg.primary) this.gone(leg.peerId, 'no-path');
    });
  }

  /**
   * Вимірювач гучності. Один `AudioContext` на кімнату: по одному на ногу
   * упирається в стелю браузера (шість контекстів на сторінку) саме там, де
   * учасників стає багато.
   */
  private listen(leg: Leg, stream: MediaStream): void {
    if (leg.analyser || !stream.getAudioTracks().length) return;
    if (typeof AudioContext === 'undefined') return;
    try {
      this.ctx = this.ctx ?? new AudioContext();
      const analyser = this.ctx.createAnalyser();
      analyser.fftSize = 512;
      this.ctx.createMediaStreamSource(stream).connect(analyser);
      leg.analyser = analyser;
      leg.buf = new Float32Array(new ArrayBuffer(analyser.fftSize * 4));
    } catch {
      // Гучності не буде — і кімната скаже про це `level: null`, а не нулем,
      // бо «не міряли» і «тиша» — різні речі.
      return;
    }
    if (!this.meter) this.meter = setInterval(() => this.sweep(), LEVEL_PERIOD_MS);
  }

  private sweep(): void {
    let touched = false;
    const now = Date.now();
    this.legs.forEach((leg) => {
      if (!leg.analyser || !leg.buf) return;
      leg.analyser.getFloatTimeDomainData(leg.buf);
      let sum = 0;
      for (let i = 0; i < leg.buf.length; i++) sum += leg.buf[i] * leg.buf[i];
      const rms = Math.sqrt(sum / leg.buf.length);
      const was = now - leg.loudAt < SPEAKING_HOLD_MS;
      leg.level = rms;
      if (rms >= SPEAKING_RMS) leg.loudAt = now;
      if (was !== now - leg.loudAt < SPEAKING_HOLD_MS) touched = true;
    });
    if (touched) this.deps.changed();
  }
}

const FEET: Foot[] = ['dialing', 'ringing', 'talking', 'gone'];

/** Слово причини — людською мовою. Закритий словник плюс чесне «інакше». */
export const whyLabel = (why: string | null): string | null => {
  if (!why) return null;
  switch (why) {
    case 'declined':
      return tNow('callRoom.declined');
    case 'busy':
      return tNow('callRoom.busy');
    case 'no-answer':
      return tNow('callRoom.noAnswer');
    case 'calls-off':
      return tNow('callRoom.callsOff');
    case 'no-path':
      return tNow('callRoom.noPath');
    case 'full':
    case 'room-full':
      return tNow('callRoom.full');
    case 'hung-up':
      return tNow('callRoom.hungUp');
    case 'no-media':
      return tNow('callRoom.noMedia');
    // Слово рушія, якого ми не знаємо. Сире слово людині нічого не каже
    // (власник, 26.09), а вигадувати причину не можна — тож кажемо лише
    // те, що точно правда: людина не долучилась.
    default:
      return tNow('callRoom.didNotJoin');
  }
};

/** Стан ноги — людською мовою. */
export const footLabel = (leg: LegView): string => {
  if (leg.foot === 'gone') return whyLabel(leg.why) ?? tNow('callRoom.hungUp');
  if (leg.foot === 'talking') return tNow('callRoom.inCall');
  if (leg.foot === 'dialing') return leg.reachable === false ? tNow('callRoom.noPath') : tNow('callRoom.dialing');
  return leg.reachable === false ? tNow('callRoom.noPath') : tNow('callRoom.ringing');
};
