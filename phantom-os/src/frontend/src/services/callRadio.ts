/**
 * Режим рації: голос їде кадрами листування, коли доріжки RTP уже немає.
 *
 * ЧОМУ КАДРАМИ. RTP — це UDP без повтору: пакет або встиг, або його нема
 * назавжди. На 25% втрат від голосу лишається каша, і жоден FEC цього не
 * витягне. Кадр листування везе TCP до вузла: він повторює втрачене сам,
 * доїжджає цілим і не вимагає домовлятись про доріжку заново — вузол уже
 * досяжний, інакше не було б навіть сигналу «дзвінок». Ціна — затримка в
 * кілька секунд. Це вже не телефонна розмова, це рація: сказав — почув.
 * Але жодне слово не губиться, а Telegram на цьому місці кладе слухавку.
 *
 * ЧОМУ НОВИЙ РЕКОРДЕР НА КОЖНІ ТРИ СЕКУНДИ. MediaRecorder із timeslice
 * віддає перший шматок із заголовком webm, а всі наступні — без нього. Такий
 * шматок не є файлом, і decodeAudioData на тому боці не візьме з нього
 * нічого. Тому не один рекордер із нарізкою, а ланцюжок коротких: кожен
 * шматок виходить самостійним webm, який приймач розбирає окремо від решти.
 * Платимо кількома мілісекундами тиші на стику і зайвим заголовком.
 */

import { REASON } from './callReasons';
import { request } from './api';

/** Три секунди — компроміс: коротше означає більше заголовків на той самий голос. */
const CHUNK_MS = 3000;
const MIME = 'audio/webm;codecs=opus';
/** 16 кбіт/с на голос — це ~6-8 КБ на шматок, тобто ~10 КБ у base64. */
const BITS_PER_SECOND = 16000;
/** Скільки чекати дірку в нумерації, перш ніж визнати шматок загубленим. */
const GAP_WAIT_MS = 4000;

/**
 * Доріжка повернулась, а в рації ще лежить хвіст. Догоняємо: паузи довші за
 * 0,3 с стискаємо до 0,1 с, решту граємо в 1,25× без зміни висоти голосу —
 * і лише тоді живий звук. Слова не губляться, а запізнення не тягнеться
 * в розмову.
 */
export const CATCHUP_RATE = 1.25;
const SILENCE_RMS = 0.01;
const SILENCE_MIN_S = 0.3;
const SILENCE_KEEP_S = 0.1;
const SILENCE_WINDOW_S = 0.02;

export function squeezeSilence(samples: Float32Array, rate: number): Float32Array {
  const win = Math.max(1, Math.round(rate * SILENCE_WINDOW_S));
  const keep = Math.round(rate * SILENCE_KEEP_S);
  const minRun = Math.round(rate * SILENCE_MIN_S);
  const out = new Float32Array(samples.length);
  let n = 0;
  let quietFrom = -1;
  const flushQuiet = (end: number) => {
    if (quietFrom < 0) return;
    const run = end - quietFrom;
    const take = run >= minRun ? keep : run;
    out.set(samples.subarray(quietFrom, quietFrom + take), n);
    n += take;
    quietFrom = -1;
  };
  for (let i = 0; i < samples.length; i += win) {
    const end = Math.min(samples.length, i + win);
    let sum = 0;
    for (let j = i; j < end; j++) sum += samples[j] * samples[j];
    const quiet = Math.sqrt(sum / (end - i)) < SILENCE_RMS;
    if (quiet) {
      if (quietFrom < 0) quietFrom = i;
      continue;
    }
    flushQuiet(i);
    out.set(samples.subarray(i, end), n);
    n += end - i;
  }
  flushQuiet(samples.length);
  return out.slice(0, n);
}

/** Скільки секунд займе догін хвоста. */
export const catchUpSeconds = (samples: Float32Array, rate: number): number =>
  squeezeSilence(samples, rate).length / rate / CATCHUP_RATE;

export function encodeWav(samples: Float32Array, rate: number): Blob {
  const data = new DataView(new ArrayBuffer(44 + samples.length * 2));
  const text = (at: number, str: string) => {
    for (let i = 0; i < str.length; i++) data.setUint8(at + i, str.charCodeAt(i));
  };
  text(0, 'RIFF');
  data.setUint32(4, 36 + samples.length * 2, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  data.setUint32(16, 16, true);
  data.setUint16(20, 1, true);
  data.setUint16(22, 1, true);
  data.setUint32(24, rate, true);
  data.setUint32(28, rate * 2, true);
  data.setUint16(32, 2, true);
  data.setUint16(34, 16, true);
  text(36, 'data');
  data.setUint32(40, samples.length * 2, true);
  samples.forEach((v, i) => {
    const c = Math.max(-1, Math.min(1, v));
    data.setInt16(44 + i * 2, c < 0 ? c * 0x8000 : c * 0x7fff, true);
  });
  return new Blob([data.buffer], { type: 'audio/wav' });
}

export interface RadioTally {
  /** Скільки шматків ми відправили. */
  sent: number;
  /** Скільки з них вузол співрозмовника прийняв. */
  delivered: number;
  /** Скільки шматків ми справді відтворили. */
  played: number;
  /** Останній почутий номер. */
  lastSeq: number | null;
  /** Номери, які так і не приїхали. */
  missing: number;
  /** Просто зараз із динаміка йде голос. */
  speaking: boolean;
}

const EMPTY: RadioTally = {
  sent: 0,
  delivered: 0,
  played: 0,
  lastSeq: null,
  missing: 0,
  speaking: false,
};

const blobToB64 = (blob: Blob): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('chunk is unreadable'));
    reader.onload = () => {
      const raw = String(reader.result ?? '');
      resolve(raw.slice(raw.indexOf(',') + 1));
    };
    reader.readAsDataURL(blob);
  });

/** Одразу ArrayBuffer: decodeAudioData бере саме його і забирає у власність. */
const b64ToBuffer = (b64: string): ArrayBuffer => {
  const binary = atob(b64);
  const buffer = new ArrayBuffer(binary.length);
  const bytes = new Uint8Array(buffer);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return buffer;
};

export interface RadioTarget {
  callId: string;
  contactId?: string;
  peerNodeId?: string;
}

/**
 * Одна рація на один дзвінок: пише свій мікрофон і відтворює чужі шматки.
 * Нічого не знає ні про RTC, ні про екран — лише про голос і номери.
 */
export class RadioLink {
  private tally: RadioTally = { ...EMPTY };
  private readonly onChange: (tally: RadioTally) => void;

  private target: RadioTarget | null = null;
  private stopped = true;
  private recorder: MediaRecorder | null = null;
  private cycleTimer: ReturnType<typeof setTimeout> | null = null;
  private speakTimer: ReturnType<typeof setInterval> | null = null;
  private sendSeq = 0;

  private ctx: AudioContext | null = null;
  private queue = new Map<number, AudioBuffer>();
  private nextSeq = 0;
  private playAt = 0;
  /** Коли ПОМІТИЛИ саме цю дірку. Не «коли приїхав останній шматок». */
  private holeSince = 0;
  private holeTimer: ReturnType<typeof setTimeout> | null = null;
  /**
   * Чи чули ми взагалі хоч щось. Перший почутий номер стає початком відліку:
   * ми могли увійти в рацію посеред чужої фрази, і чекати на шматки, яких для
   * нас ніколи не існувало, означало б мовчати вічно.
   */
  private joined = false;
  /** Номери, які вже чули, — щоб повтор кадру не зіграв двічі. */
  private seen = new Set<number>();
  /** Заплановані шматки: догін мусить зупинити те, що ще не дограло. */
  private scheduled: Array<{ source: AudioBufferSourceNode; buffer: AudioBuffer; at: number }> = [];
  private catching: HTMLAudioElement | null = null;
  private catchingUrl: string | null = null;
  private muted = false;
  private gain: GainNode | null = null;

  /**
   * Кого повідомити, що рація померла. Без цього `this.stopped = true; return`
   * нижче був найтихішою вадою в дзвінку: браузер без webm/opus гасив рацію
   * назавжди, а картка й далі показувала «розмова» з підписом, чому ми на
   * рації. Людина говорила в мікрофон, з якого вже нічого не виходило.
   */
  private readonly onDead: (why: string) => void;

  constructor(onChange: (tally: RadioTally) => void, onDead: (why: string) => void = () => {}) {
    this.onChange = onChange;
    this.onDead = onDead;
  }

  /** Рація не може працювати далі — сказати це вголос рівно один раз. */
  private die(why: string): void {
    if (this.stopped) return;
    this.stopped = true;
    this.onDead(why);
  }

  get snapshot(): RadioTally {
    return this.tally;
  }

  private bump(patch: Partial<RadioTally>): void {
    this.tally = { ...this.tally, ...patch };
    this.onChange(this.tally);
  }

  /** Вмикає рацію: з цієї миті мікрофон ріжеться на шматки й їде кадрами. */
  start(target: RadioTarget, stream: MediaStream | null): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.target = target;
    this.tally = { ...EMPTY };
    this.sendSeq = 0;
    this.nextSeq = 0;
    this.playAt = 0;
    this.holeSince = 0;
    this.joined = false;
    this.queue.clear();
    this.seen.clear();
    this.onChange(this.tally);

    this.ensureCtx();
    this.cycle(stream);
    // Індикатор «говорить…» іде за розкладом відтворення, а не за приходом
    // кадру: кадр міг приїхати наперед і чекати своєї черги.
    this.speakTimer = setInterval(() => {
      const ctx = this.ctx;
      const speaking = !!ctx && ctx.currentTime < this.playAt - 0.05;
      if (speaking !== this.tally.speaking) this.bump({ speaking });
    }, 400);
  }

  stop(): void {
    this.stopped = true;
    this.target = null;
    if (this.cycleTimer) clearTimeout(this.cycleTimer);
    this.cycleTimer = null;
    if (this.holeTimer) clearTimeout(this.holeTimer);
    this.holeTimer = null;
    if (this.speakTimer) clearInterval(this.speakTimer);
    this.speakTimer = null;
    try {
      this.recorder?.stop();
    } catch {
      /* уже зупинений */
    }
    this.recorder = null;
    this.queue.clear();
    this.scheduled = [];
    if (this.catching) {
      this.catching.onended = null;
      this.catching.onerror = null;
      this.catching.pause();
    }
    this.catching = null;
    if (this.catchingUrl) URL.revokeObjectURL(this.catchingUrl);
    this.catchingUrl = null;
    this.gain = null;
    void this.ctx?.close().catch(() => undefined);
    this.ctx = null;
  }

  setMuted(muted: boolean): void {
    this.muted = Boolean(muted);
    if (this.gain) this.gain.gain.value = this.muted ? 0 : 1;
    if (this.catching) this.catching.muted = this.muted;
  }

  /**
   * Доріжка повернулась: передачу гасимо, а недограний хвіст і чергу граємо
   * стиснутими (`squeezeSilence`, 1,25×, `preservesPitch`). `onDone` — коли
   * хвіст дограв і можна вмикати живий звук. Хвоста немає — false, одразу живий.
   */
  catchUp(onDone: () => void, muted = false): boolean {
    const ctx = this.ctx;
    const now = ctx?.currentTime ?? 0;
    const parts: Float32Array[] = [];
    let rate = 0;
    const take = (buffer: AudioBuffer, from: number) => {
      const start = Math.max(0, Math.round(from * buffer.sampleRate));
      if (start >= buffer.length) return;
      rate = rate || buffer.sampleRate;
      if (buffer.sampleRate === rate) parts.push(buffer.getChannelData(0).subarray(start));
    };
    for (const { source, buffer, at } of this.scheduled) {
      if (at + buffer.duration <= now) continue;
      try {
        source.stop();
      } catch {
        /* уже дограв */
      }
      take(buffer, now - at);
    }
    [...this.queue.keys()].sort((a, b) => a - b).forEach((seq) => take(this.queue.get(seq)!, 0));
    this.scheduled = [];
    this.queue.clear();
    const total = parts.reduce((n, p) => n + p.length, 0);
    if (!total || !rate) {
      this.stop();
      return false;
    }
    const joined = new Float32Array(total);
    parts.reduce((at, p) => (joined.set(p, at), at + p.length), 0);

    this.stop();
    const url = URL.createObjectURL(encodeWav(squeezeSilence(joined, rate), rate));
    const el = new Audio(url);
    el.playbackRate = CATCHUP_RATE;
    el.preservesPitch = true;
    el.muted = muted;
    const done = () => {
      this.stop();
      onDone();
    };
    el.onended = done;
    el.onerror = done;
    this.catching = el;
    this.catchingUrl = url;
    void el.play().catch(done);
    return true;
  }

  /** Кнопка динаміка під час догону — та сама, що й для живого звуку. */
  muteCatchUp(muted: boolean): void {
    if (this.catching) this.catching.muted = muted;
  }

  /* ── передача ───────────────────────────────────────────────────────── */

  private cycle(stream: MediaStream | null): void {
    if (this.stopped) return;
    const track = stream?.getAudioTracks()[0];
    if (!track || track.readyState !== 'live') return;

    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(new MediaStream([track]), {
        mimeType: MIME,
        audioBitsPerSecond: BITS_PER_SECOND,
      });
    } catch {
      // Браузер не вміє webm/opus — рація тут неможлива, і вдавати не будемо.
      this.die(REASON.radioNoWebm);
      return;
    }

    const parts: Blob[] = [];
    recorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) parts.push(event.data);
    };
    recorder.onstop = () => {
      // Наступний рекордер стартує ПЕРШИМ ділом: усе, що між stop і start, —
      // це тиша, якої людина не сказала.
      this.cycle(stream);
      if (parts.length) void this.ship(new Blob(parts, { type: MIME }));
    };

    this.recorder = recorder;
    try {
      recorder.start();
    } catch {
      this.die(REASON.radioMicSilent);
      return;
    }
    this.cycleTimer = setTimeout(() => {
      try {
        recorder.stop();
      } catch {
        /* міг уже зупинитись сам */
      }
    }, CHUNK_MS);
  }

  private async ship(blob: Blob): Promise<void> {
    const target = this.target;
    if (!target || this.stopped) return;
    const seq = this.sendSeq;
    this.sendSeq += 1;

    let audio: string;
    try {
      audio = await blobToB64(blob);
    } catch {
      return;
    }
    this.bump({ sent: this.tally.sent + 1 });

    try {
      const result = await request<{ delivered: boolean }>(
        'POST',
        '/messenger/call/radio',
        {
          call_id: target.callId,
          seq,
          audio_b64: audio,
          contact_id: target.contactId ?? null,
          peer_node_id: target.peerNodeId ?? null,
        },
      );
      if (result.delivered) this.bump({ delivered: this.tally.delivered + 1 });
    } catch {
      // Один шматок не доїхав — розмова через це не вмирає. Повтору немає
      // свідомо: секунда голосу, доставлена через хвилину, це вже не розмова.
    }
  }

  /* ── прийом ─────────────────────────────────────────────────────────── */

  private ensureCtx(): AudioContext | null {
    if (this.ctx) {
      if (this.ctx.state === 'suspended') void this.ctx.resume().catch(() => undefined);
      return this.ctx;
    }
    try {
      const Ctor =
        window.AudioContext ??
        (window as unknown as { webkitAudioContext?: typeof AudioContext })
          .webkitAudioContext;
      if (!Ctor) return null;
      this.ctx = new Ctor();
      if (this.ctx.state === 'suspended') void this.ctx.resume().catch(() => undefined);
      return this.ctx;
    } catch {
      return null;
    }
  }

  /** Кладе шматок у чергу за номером і програє все, що вже можна програти. */
  async receive(seq: number, audioB64: string): Promise<void> {
    if (this.stopped || !audioB64) return;
    if (this.seen.has(seq) || seq < this.nextSeq) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;

    let buffer: AudioBuffer;
    try {
      buffer = await ctx.decodeAudioData(b64ToBuffer(audioB64));
    } catch {
      // Зіпсований шматок — це та сама втрата, і рахуємо його так само.
      this.seen.add(seq);
      return;
    }
    if (this.stopped) return;

    this.seen.add(seq);
    this.queue.set(seq, buffer);
    // Перший почутий шматок задає початок відліку. Інакше той, хто ввійшов у
    // рацію другим, вічно чекав би на номер нуль, якого йому ніхто не слав.
    if (!this.joined) {
      this.joined = true;
      this.nextSeq = seq;
    }
    this.bump({ lastSeq: seq });
    this.drain();
  }

  private drain(): void {
    const ctx = this.ctx;
    if (!ctx) return;

    for (;;) {
      const buffer = this.queue.get(this.nextSeq);
      if (!buffer) {
        const ahead = [...this.queue.keys()].filter((s) => s > this.nextSeq);
        if (!ahead.length) {
          this.holeSince = 0;
          return;
        }
        // Дірку чекаємо обмежений час — але відлік іде від МОМЕНТУ ДІРКИ, а не
        // від останнього приходу. Інакше потік, який іде щотри секунди, вічно
        // відсовував би термін очікування, і не зіграло б нічого.
        const now = Date.now();
        if (!this.holeSince) this.holeSince = now;
        if (now - this.holeSince < GAP_WAIT_MS) {
          if (!this.holeTimer) {
            this.holeTimer = setTimeout(() => {
              this.holeTimer = null;
              this.drain();
            }, GAP_WAIT_MS - (now - this.holeSince) + 50);
          }
          return;
        }
        const next = Math.min(...ahead);
        this.bump({ missing: this.tally.missing + (next - this.nextSeq) });
        this.nextSeq = next;
        this.holeSince = 0;
        continue;
      }

      this.holeSince = 0;
      this.queue.delete(this.nextSeq);
      this.nextSeq += 1;

      const source = ctx.createBufferSource();
      source.buffer = buffer;
      if (typeof ctx.createGain === 'function') {
        if (!this.gain) {
          this.gain = ctx.createGain();
          this.gain.gain.value = this.muted ? 0 : 1;
          this.gain.connect(ctx.destination);
        }
        source.connect(this.gain);
      } else {
        source.connect(ctx.destination);
      }
      // Кожен наступний шматок ставимо в чергу впритул до попереднього —
      // так між ними немає ані тиші, ані накладання.
      const at = Math.max(ctx.currentTime, this.playAt);
      try {
        source.start(at);
      } catch {
        continue;
      }
      this.playAt = at + buffer.duration;
      this.scheduled = this.scheduled.filter((p) => p.at + p.buffer.duration > ctx.currentTime);
      this.scheduled.push({ source, buffer, at });
      this.bump({ played: this.tally.played + 1, speaking: true });
    }
  }
}
