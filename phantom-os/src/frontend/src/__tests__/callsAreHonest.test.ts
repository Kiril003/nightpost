/**
 * Дзвінок не оголошує себе успішним, не мовчить про відмову і не публікує SDP.
 *
 * Кожне твердження тут ЧЕРВОНІЄ на коді, що стояв до 05.09. Перевірено
 * мутацією — спосіб повернути стару поведінку виписаний біля кожного блоку,
 * щоб сторожа можна було перевірити, а не повірити йому.
 *
 * ЧОМУ ЦЕ ОКРЕМИЙ ФАЙЛ, А НЕ РЯДОК У БАНДЛІ. `bundlePatterns` стереже ВМІСТ
 * зібраного бандла телефона — там ловляться літерали. Тут же ловиться
 * ПОВЕДІНКА: таймер, який малює «на звʼязку», у бандлі виглядає як звичайний
 * `setTimeout` і жодним взірцем не відрізняється від сотні чесних. Тому
 * поведінку доводимо поведінкою, а межу мешу — обходом дерева.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { stallLine } from '../services/callWords';

/* ────────────────────────────────────────────────────────────────────────
   Знаряддя: обхід `src/` без тестів. Той самий, що в noFabricatedPresence —
   свідомо, щоб «нуль порушень» не міг означати «нуль прочитаних файлів».
   ──────────────────────────────────────────────────────────────────────── */

const ROOT = join(process.cwd(), 'src');

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      out.push(...walk(p));
      continue;
    }
    if (!/\.(ts|tsx)$/.test(name)) continue;
    if (/\.(test|spec)\.tsx?$/.test(name)) continue;
    out.push(p);
  }
  return out;
};

/**
 * Рядок КОДУ, а не коментаря. Це принципово: саме в коментарях цього дерева
 * описано, що тут стояло раніше — тобто взірці, які сторож шукає, законно
 * живуть у поясненнях поруч із виправленням. Блокові коментарі (у тому числі
 * JSX `{/* … *\/}`) гасимо цілком, зберігаючи нумерацію рядків.
 */
const codeLines = (file: string): Array<{ n: number; text: string }> => {
  const raw = readFileSync(file, 'utf8');
  // Замінюємо тіло блокового коментаря пробілами, лишаючи переводи рядків.
  const blanked = raw.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  return blanked
    .split('\n')
    .map((line, i) => ({ n: i + 1, text: line.replace(/\/\/.*$/, '') }))
    .filter(({ text }) => text.trim().length > 0);
};

const scan = (re: RegExp): string[] => {
  const hits: string[] = [];
  const files = walk(ROOT);
  expect(files.length).toBeGreaterThan(200);
  for (const file of files) {
    for (const { n, text } of codeLines(file)) {
      if (re.test(text)) hits.push(`${relative(ROOT, file)}:${n}: ${text.trim()}`);
    }
  }
  return hits;
};

/* ────────────────────────────────────────────────────────────────────────
   7. БЕЗПЕКА. Сигнали дзвінка не їдуть у меш — і це стоїть у коді, а не
   тримається на порожній `VITE_MESH_BROKER_URL`.
   ──────────────────────────────────────────────────────────────────────── */

describe('сигнали дзвінка не виходять у меш', () => {
  /**
   * Мутація, що має це завалити: поверніть у `callEngine.postTo` рядок
   * `globalP2PMesh.sendCallSignal(...)`. До 05.09 він там стояв і публікував
   * відкритий SDP та всі ICE-кандидати в теми чужого MQTT-брокера.
   */
  it('жоден файл продукту не викликає sendCallSignal', () => {
    expect(scan(/\bsendCallSignal\s*\(/)).toEqual([]);
  });

  /**
   * Ворота стоять і з того боку теж: метод видалено цілком, тож дописати в
   * нього тіло неможливо, не повернувши спершу саму назву — а її ловить
   * попереднє твердження.
   * Мутація: додайте назад `public sendCallSignal(...) { this.publish(...) }`.
   */
  it('меш не має жодного шляху, яким сигнал дзвінка виходить назовні', () => {
    const src = readFileSync(join(ROOT, 'services/globalP2PMesh.ts'), 'utf8');
    // Публікації лишились лише для листування й присутності — не для дзвінка.
    const publishTopics = [...src.matchAll(/this\.publish\(`([^`]+)`/g)].map((m) => m[1]);
    expect(publishTopics.length).toBeGreaterThan(0);
    for (const topic of publishTopics) expect(topic).not.toContain('/call');
  });

  /**
   * Приймальний бік того самого витоку: пакет із чужого брокера не сміє
   * потрапляти в рушій дзвінків — меш не підписує пакетів і не звіряє,
   * хто їх надіслав.
   * Мутація: поверніть `globalP2PMesh.onCallSignal(s => callEngine.onSignal(s))`.
   */
  it('меш не годує рушій дзвінків вхідними сигналами', () => {
    expect(scan(/onCallSignal\s*\(/)).toEqual([]);
  });

  /** Тема, у яку публікувався `call_id` разом із SDP, не згадується ніде. */
  it('теми phantom/mesh/call/ у продукті немає', () => {
    expect(scan(/phantom\/mesh\/call\//)).toEqual([]);
  });
});

/* ────────────────────────────────────────────────────────────────────────
   4 і 5. Показ екрана, який не надсилав, і «Huddle», який нічого не робив.
   ──────────────────────────────────────────────────────────────────────── */

describe('поверхонь, що вдавали роботу, більше немає', () => {
  /**
   * Мутація: поверніть `getDisplayMedia` у CallOverlay. Захоплення без
   * жодного `addTrack`/`replaceTrack` у відправника — це показ екрана самому
   * собі під написом «Трансляція екрана 1080p 60fps».
   */
  it('картка дзвінка не захоплює екран, поки не вміє його надсилати', () => {
    const overlay = readFileSync(join(ROOT, 'components/messenger/CallOverlay.tsx'), 'utf8');
    const code = overlay
      .split('\n')
      .filter((l) => {
        const t = l.trim();
        return !(t.startsWith('*') || t.startsWith('//') || t.startsWith('/*'));
      })
      .join('\n');
    expect(code).not.toMatch(/getDisplayMedia/);
    expect(code).not.toMatch(/1080p/);
  });

  /** Мутація: поверніть будь-який із двох файлів і змонтуйте його в шапці. */
  it('студії Huddle немає ні у файлах, ні в шапці', () => {
    expect(existsSync(join(ROOT, 'components/messenger/TeamHuddleBar.tsx'))).toBe(false);
    expect(existsSync(join(ROOT, 'components/messenger/TeamHuddleStudio.tsx'))).toBe(false);
    expect(scan(/TeamHuddle(Bar|Studio)/)).toEqual([]);
    // «● LIVE» над кімнатою, у якій ніколи нікого не було.
    expect(scan(/P2P Mesh аудіо\/відео простір/)).toEqual([]);
  });
});

/* ────────────────────────────────────────────────────────────────────────
   3. Печатка безпеки не друкується наперед.
   ──────────────────────────────────────────────────────────────────────── */

describe('значки довіри не друкуються безумовно', () => {
  /**
   * Мутація: поверніть у TrustRow рядок «Post-Quantum E2EE Звірено ✓» /
   * «E2EE DTLS-SRTP Захищено». Обидва друкувались на екрані «набираю…», де
   * не узгоджено ще нічого, і в рації, де DTLS немає за побудовою.
   */
  it('«Post-Quantum» не згадується у продукті жодного разу', () => {
    expect(scan(/Post-Quantum|PostQuantum/i)).toEqual([]);
  });

  it('літерала «64kbps» біля сходинки звуку немає', () => {
    expect(scan(/64\s*kbps/i)).toEqual([]);
  });

  /**
   * 26.09 рядок про DTLS-SRTP прибрано з екрана зовсім: навіть правдивий,
   * він людині нічого не каже (закон власника). Мутація: поверніть
   * `MediaCryptoRow` — і це слово знову стоятиме на екрані дзвінка.
   */
  it('картка дзвінка не друкує печаток шифрування й звірки', () => {
    const overlay = readFileSync(join(ROOT, 'components/messenger/CallOverlay.tsx'), 'utf8');
    const code = overlay.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    expect(code).not.toMatch(/DTLS|SRTP|MediaCryptoRow|TrustRow|IceRoadRow|AUDIO_LEVEL_LABEL/);
  });
});

/* ────────────────────────────────────────────────────────────────────────
   2. Чотири чесні поля рушія доходять до людини.
   ──────────────────────────────────────────────────────────────────────── */

describe('причина відмови доходить до екрана', () => {
  /**
   * Мутація: замініть `snapshot.endedReason || 'Дзвінок завершено'` назад на
   * сталий літерал. Рушій рахував `endedReason` у `finish()`, і до 05.09 його
   * не читав ЖОДЕН компонент — картка завжди писала одне й те саме.
   */
  it('CallOverlay читає endedReason, stall, radioReason і linkNote (через callWords)', () => {
    const overlay = readFileSync(join(ROOT, 'components/messenger/CallOverlay.tsx'), 'utf8');
    for (const field of ['endedReason', 'stall', 'radioReason', 'linkNote']) {
      expect(overlay).toContain(`snapshot.${field}`);
    }
  });

  /**
   * Мутація: поверніть у `startCall`/`accept` голе `await this.post(...)`.
   * `postTo` завжди повертав `{delivered:false, detail}`, і жоден викликач
   * цього не читав — відмова вузла була невидима.
   */
  it('рушій читає delivered у кожного відправника сигналу', () => {
    const engine = readFileSync(join(ROOT, 'services/callEngine.ts'), 'utf8');
    // Голого `await this.post(` не лишилось: кожен виклик або зберігає
    // результат, або явно розбирає його в `.then`.
    const bare = engine
      .split('\n')
      .filter((l) => /^\s*(await|void) this\.post\(/.test(l))
      .filter((l) => !/\)\.then\(/.test(l));
    expect(bare).toEqual([]);
    expect(engine).toContain('signalRefusedReason');
  });
});

/* ────────────────────────────────────────────────────────────────────────
   1. ГОЛОВНЕ — поведінка. Дзвінок не стає «розмовою» за годинником.
   ──────────────────────────────────────────────────────────────────────── */

/** Доріжка, якої вистачає рушієві: він її лише перелічує і зупиняє. */
const fakeTrack = (kind: 'audio' | 'video') => ({
  kind,
  id: `t_${kind}`,
  enabled: true,
  readyState: 'live',
  stop: () => undefined,
});

class FakeMediaStream {
  private tracks: Array<ReturnType<typeof fakeTrack>>;
  constructor(tracks: Array<ReturnType<typeof fakeTrack>> = []) {
    this.tracks = tracks;
  }
  getTracks() {
    return this.tracks;
  }
  getAudioTracks() {
    return this.tracks.filter((t) => t.kind === 'audio');
  }
  getVideoTracks() {
    return this.tracks.filter((t) => t.kind === 'video');
  }
  getTrackById() {
    return null;
  }
  addTrack(t: ReturnType<typeof fakeTrack>) {
    this.tracks.push(t);
  }
  addEventListener() {}
  removeEventListener() {}
}

/**
 * `RTCPeerConnection`, який НІКОЛИ не зʼєднується. Це і є умова вимірювання:
 * якщо картка каже «на звʼязку» над таким обʼєктом, вона каже це не з події.
 */
class NeverConnectingPC {
  connectionState = 'new';
  iceConnectionState = 'new';
  signalingState = 'stable';
  localDescription: { sdp: string } | null = null;
  remoteDescription: { sdp: string } | null = null;
  onicecandidate: unknown = null;
  ontrack: unknown = null;
  onconnectionstatechange: unknown = null;
  oniceconnectionstatechange: unknown = null;
  /**
   * Справжній `RTCPeerConnection` — це `EventTarget`, і слухачів на ньому
   * вішають не лише через `onXxx=`. Дублер цього не вмів, і коли кімната
   * (`callRoom`) почала спостерігати за зʼєднанням через `addEventListener`
   * (інакше вона збила б обробники рушія), сім тестів упали на дублері, а не
   * на рушієві. Дублер, бідніший за те, що він дублює, ловить не ваду, а себе.
   *
   * «Ніколи не зʼєднується» від цього не змінилось: слухачі приймаються й
   * нікуди не кличуться, бо подій тут не буває — у тому й суть цього дублера.
   */
  private listeners = new Map<string, Array<(e: unknown) => void>>();
  addEventListener(name: string, cb: (e: unknown) => void) {
    if (!this.listeners.has(name)) this.listeners.set(name, []);
    this.listeners.get(name)!.push(cb);
  }
  removeEventListener(name: string, cb: (e: unknown) => void) {
    const list = this.listeners.get(name);
    if (list) this.listeners.set(name, list.filter((x) => x !== cb));
  }
  addTrack() {
    return {};
  }
  getSenders() {
    return [];
  }
  async createOffer() {
    return { type: 'offer', sdp: 'v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n' };
  }
  async createAnswer() {
    return { type: 'answer', sdp: 'v=0\r\no=- 2 2 IN IP4 0.0.0.0\r\n' };
  }
  async setLocalDescription(d: { sdp: string }) {
    this.localDescription = d;
    this.signalingState = 'have-local-offer';
  }
  async setRemoteDescription(d: { sdp: string }) {
    this.remoteDescription = d;
  }
  async addIceCandidate() {}
  async getStats() {
    return new Map();
  }
  close() {
    this.connectionState = 'closed';
  }
}

/** Що вузол відповідає на сигнал. Міняється в межах одного тесту. */
let signalReply: { delivered: boolean; detail: string } = { delivered: true, detail: '' };
/** Відповіді саме на пропозиції, по черзі; порожньо — діє [signalReply]. */
let offerReplies: Array<{ delivered: boolean; detail: string }> = [];
let offerPosts = 0;

vi.mock('../services/api', () => ({
  request: vi.fn(async (_m: string, path: string) => {
    if (path.startsWith('/messenger/ice')) return { iceServers: [], turn: false, ttl: 0 };
    if (path === '/messenger/call/offer') {
      offerPosts += 1;
      const next = offerReplies.shift();
      if (next) return { ...next, call_id: 'x' };
    }
    return { delivered: signalReply.delivered, call_id: 'x', detail: signalReply.detail };
  }),
  ApiError: class extends Error {},
}));

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => undefined, send: () => undefined },
}));

const PEER = { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: false };

describe('дзвінок не оголошує себе успішним', () => {
  let engine: typeof import('../services/callEngine');

  beforeEach(async () => {
    vi.useFakeTimers();
    signalReply = { delivered: true, detail: '' };
    offerReplies = [];
    offerPosts = 0;
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = NeverConnectingPC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: async () => new FakeMediaStream([fakeTrack('audio')]),
      },
    });
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /**
   * ЦЕНТРАЛЬНЕ ТВЕРДЖЕННЯ. До 05.09 у `startCall` стояв `setTimeout(..., 2500)`,
   * який без відповіді, без remote description і без жодного ICE ставив
   * `state:'active'` і клав у `remoteStream` ВЛАСНИЙ локальний потік.
   *
   * Мутація, що завалює цей тест: поверніть той таймер.
   */
  it('не стає «розмовою» за таймером і не показує людині її ж потік', async () => {
    await engine.callEngine.startCall(PEER, 'audio');
    await vi.advanceTimersByTimeAsync(3000);

    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('calling');
    expect(s.startedAt).toBeNull();
    expect(s.remoteStream).not.toBe(s.localStream);
    expect(s.linkNote).toBeNull();
  });

  /**
   * НАСЛІДОК ДРУГОГО ПОРЯДКУ. Той самий таймер ставив `answered = true`, тож
   * сторож `armStall` (умова `calling|ringing|connecting`) не спрацьовував
   * НІКОЛИ — і два вже написані чесні рядки були недосяжні за побудовою.
   * Ось доказ, що вони досяжні.
   */
  it('через 8 секунд каже вголос, що слухавку не беруть', async () => {
    await engine.callEngine.startCall(PEER, 'audio');
    await vi.advanceTimersByTimeAsync(8100);

    const s = engine.callEngine.getSnapshot();
    expect(s.stall).not.toBeNull();
    expect(s.stall?.kind).toBe('no-answer');
    expect(s.stall?.note).toBe(engine.NO_ANSWER_NOTE);
    engine.callEngine.hangup();
  });

  /**
   * Другий із двох заглушених рядків: співрозмовник ВІДПОВІВ, а доріжка не
   * встала. Коли рація неможлива (вузлової адреси для кадрів немає), причина
   * — межа вузла без TURN, і назвати її треба саме так.
   *
   * Стара поведінка завалює це двічі: `accept()` одразу ставив `active`, тож
   * `armStall` навіть не викликався, а якби й викликався — його умова
   * `calling|ringing|connecting` над станом `active` не спрацювала б.
   */
  it('після відповіді, коли рація неможлива, називає межу без TURN', async () => {
    // Пропозиція без `from_node_id`: адреси для кадрів рації немає, отже
    // єдиний чесний вихід — сказати, чому доріжка не встала.
    await engine.callEngine.onSignal({
      data: {
        kind: 'offer',
        call_id: 'c_noaddr',
        sdp: 'v=0\r\no=- 3 3 IN IP4 0.0.0.0\r\n',
        display_name: 'Невідомий вузол',
      },
    });
    await engine.callEngine.accept();
    await vi.advanceTimersByTimeAsync(8100);

    const s = engine.callEngine.getSnapshot();
    expect(s.state).not.toBe('active');
    expect(s.stall?.kind).toBe('no-path');
    expect(s.stall?.note).toContain('Без TURN-сервера');
    engine.callEngine.hangup();
  });

  /**
   * РАЦІЯ, ЩО ПОМЕРЛА МОВЧКИ. Коли співрозмовник відповів і адреса є, рушій
   * свідомо переходить на кадри — розмова триває, і це чесно. Але якщо
   * браузер не вміє webm/opus, `RadioLink` до 05.09 робив
   * `this.stopped = true; return` і замовкав НАЗАВЖДИ, поки картка й далі
   * малювала «розмову»: людина говорила в мікрофон, з якого нічого не
   * виходило.
   *
   * Мутація: приберіть `onDead` з конструктора `RadioLink` (поверніть голе
   * `this.stopped = true`) — і стан застрягне в `active` назавжди.
   */
  it('коли рація неможлива, дзвінок не вдає розмову, а закінчується', async () => {
    // Браузер без запису webm/opus — саме та умова, що гасила рацію мовчки.
    (globalThis as unknown as Record<string, unknown>).MediaRecorder = function () {
      throw new Error('webm/opus не підтримується');
    };
    await engine.callEngine.startCall(PEER, 'audio');
    await engine.callEngine.onSignal({
      data: {
        kind: 'answer',
        call_id: engine.callEngine.getSnapshot().callId,
        sdp: 'v=0\r\no=- 4 4 IN IP4 0.0.0.0\r\n',
      },
    });
    await vi.advanceTimersByTimeAsync(8100);

    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('ended');
    expect(s.endedReason).toContain('webm/opus');
  });

  /**
   * `accept()` робив те саме, ще й із підписом «Пряме P2P зʼєднання
   * встановлено» — над зʼєднанням, у якого ще не було жодного кандидата.
   * Мутація: поверніть той `patch({state:'active', remoteStream: stream})`.
   */
  it('accept() не оголошує зʼєднання встановленим', async () => {
    await engine.callEngine.onSignal({
      data: {
        kind: 'offer',
        call_id: 'c_incoming',
        sdp: 'v=0\r\no=- 4 4 IN IP4 0.0.0.0\r\n',
        from_node_id: 'peer-node',
        display_name: 'Марта',
      },
    });
    expect(engine.callEngine.getSnapshot().state).toBe('ringing');

    await engine.callEngine.accept();
    await vi.advanceTimersByTimeAsync(3000);

    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('connecting');
    expect(s.startedAt).toBeNull();
    expect(s.remoteStream).not.toBe(s.localStream);
    expect(s.linkNote).toBeNull();
    engine.callEngine.hangup();
  });

  /**
   * ДОКАЗ НА ЕКРАНІ, а не лише у знімку стану. Рушій міг рахувати `stall`
   * скільки завгодно чесно — до 05.09 його не читав жоден компонент, тож
   * людина не бачила нічого, крім «Встановлення захищеного каналу…».
   *
   * Мутація: приберіть блок `snapshot.stall &&` з CallOverlay — і на екрані
   * знову не лишиться причини.
   */
  it('чесний рядок справді потрапляє в DOM картки дзвінка', async () => {
    const { render, screen, cleanup, act } = await import('@testing-library/react');
    const React = (await import('react')).default;
    const { CallOverlay } = await import('../components/messenger/CallOverlay');

    await engine.callEngine.startCall(PEER, 'audio');
    render(React.createElement(CallOverlay));
    // `findBy*` тут не годиться: воно чекає на СПРАВЖНЬОМУ годиннику, а він
    // підмінений. Двигаємо підроблений час усередині act і читаємо синхронно.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(8100);
    });

    // Причина — словами людини, а не рушія: «вузол» і «слухавку не бере»
    // живуть у журналі, на екрані — «Не відповідає…».
    const note = screen.getByText(stallLine({ kind: 'no-answer', note: engine.NO_ANSWER_NOTE }));
    expect(note).toBeTruthy();
    expect(document.body.textContent).not.toContain(engine.NO_ANSWER_NOTE);
    expect(note.closest('[data-call-stall]')?.getAttribute('data-call-stall')).toBe('no-answer');

    // І жодної печатки безпеки над зʼєднанням, якого ще немає.
    expect(document.querySelector('[data-call-crypto]')).toBeNull();
    expect(document.body.textContent).not.toContain('Post-Quantum');
    expect(document.body.textContent).not.toContain('Захищено');

    engine.callEngine.hangup();
    cleanup();
  });

  // Збірка 18: рушій вписував «Невідомий вузол» мимо мови застосунку.
  it('вхідний без імені на англійській — «Contact», а не «Невідомий вузол»', async () => {
    const { render, cleanup, act } = await import('@testing-library/react');
    const React = (await import('react')).default;
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    const { useSettingsStore, LANGUAGE_SETTING_KEY } = await import('../stores/settingsStore');
    useSettingsStore.getState().applyRemote(LANGUAGE_SETTING_KEY, 'en');

    await engine.callEngine.onSignal({
      data: { kind: 'offer', call_id: 'c_noname', from_node_id: 'f0e1d2c3b4a59687', sdp: 'v=0\r\n' },
    });
    expect(engine.callEngine.getSnapshot().peer?.displayName).toBe('');
    render(React.createElement(CallOverlay));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10);
    });
    expect(document.querySelector('h2')?.textContent).toBe('Contact');
    expect(document.body.textContent).not.toMatch(/Невідомий|вузол/);

    engine.callEngine.hangup();
    cleanup();
    useSettingsStore.getState().applyRemote(LANGUAGE_SETTING_KEY, 'uk');
  });

  /**
   * 2. Відмову вузла людина бачить словами вузла. Мутація: поверніть голе
   * `await this.post('offer', ...)` — і дзвінок «набиратиме» сигнал, який
   * нікуди не поїхав.
   */
  it('відмову вузла показує словами, а не «набираю…»', async () => {
    signalReply = { delivered: false, detail: 'вузол співрозмовника не прийняв сигнал' };
    await engine.callEngine.startCall(PEER, 'audio');
    await vi.advanceTimersByTimeAsync(6_000);

    const s = engine.callEngine.getSnapshot();
    expect(offerPosts).toBe(3);
    expect(s.state).toBe('ended');
    expect(s.endedReason).toContain('вузол співрозмовника не прийняв сигнал');
  });

  /**
   * Холодна дорога: перша пропозиція не пройшла (живий канал ще
   * перепідключався), друга — пройшла. Людина не мусить набирати вдруге.
   */
  it('перша пропозиція не пройшла — рушій сам шле ще раз, і дзвінок дзвонить', async () => {
    offerReplies = [{ delivered: false, detail: 'вузол співрозмовника не прийняв сигнал' }];
    await engine.callEngine.startCall(PEER, 'audio');
    await vi.advanceTimersByTimeAsync(50);
    expect(engine.callEngine.getSnapshot().state).toBe('calling');

    await vi.advanceTimersByTimeAsync(2_000);
    expect(offerPosts).toBe(2);
    expect(engine.callEngine.getSnapshot().state).toBe('calling');
    await vi.advanceTimersByTimeAsync(6_000);
    expect(offerPosts).toBe(2);
    engine.callEngine.hangup();
  });

  it('поклав слухавку, поки рушій повторює, — повтору вже немає', async () => {
    signalReply = { delivered: false, detail: 'вузол співрозмовника не прийняв сигнал' };
    await engine.callEngine.startCall(PEER, 'audio');
    await vi.advanceTimersByTimeAsync(50);
    engine.callEngine.hangup();
    await vi.advanceTimersByTimeAsync(6_000);
    expect(offerPosts).toBe(1);
  });
});

describe('у «розмову» веде тільки подія зʼєднання', () => {
  /**
   * Структурний доказ на додачу до поведінкового: у всьому рушії існує рівно
   * ОДНЕ місце, яке ставить `state: 'active'` за межами рації, і воно —
   * `markConnected`, що викликається лише з `onconnectionstatechange` і
   * `oniceconnectionstatechange`.
   *
   * Мутація: додайте `this.patch({ state: 'active' })` у будь-який
   * `setTimeout` — і цей рахунок перестане сходитись.
   */
  it('стан «active» ставиться рівно у двох місцях: подія і рація', () => {
    const engineSrc = readFileSync(join(ROOT, 'services/callEngine.ts'), 'utf8');
    const blanked = engineSrc.replace(/\/\*[\s\S]*?\*\//g, '');
    const sets = [...blanked.matchAll(/state:\s*'active'/g)];
    // 1) markConnected — за подією зʼєднання; 2) enterRadio — розмова кадрами.
    expect(sets.length).toBe(2);

    // І жоден із них не стоїть усередині setTimeout.
    const timers = [...blanked.matchAll(/setTimeout\(([\s\S]{0,600}?)\n {4}\}/g)];
    for (const [, body] of timers) expect(body).not.toMatch(/state:\s*'active'/);
  });
});

/* ────────────────────────────────────────────────────────────────────────
   6. Підказка описує СВОЮ умову.
   ──────────────────────────────────────────────────────────────────────── */

describe('підказка про дзвінок не бреше про свою умову', () => {
  /**
   * Мутація: поверніть `const canCall = !isGroup` разом із текстом «Дзвінки
   * лише зі звіреними вузловими контактами». Умова говорила про звірку, а
   * перевіряла «не група»; `hasPeer` поруч обчислювався і не використовувався.
   */
  it('умова кнопки — це рівно те, що написано в підказці', () => {
    // 16.09 умова переїхала з приватних констант `Header.tsx` у
    // `services/callGate.ts`: ДРУГА слухавка (картка співрозмовника) не
    // питала нічого й кидала подію в нікуди, а слухач мовчки її ковтав.
    // Тест іде за умовою, а не стереже її колишню адресу — інакше він
    // забороняв би прибирати дублікат, тобто стеріг би саме ваду.
    const gate = readFileSync(join(ROOT, 'services/callGate.ts'), 'utf8');
    expect(gate).toMatch(/phoneShell \? roads\?\.inbound === true : roads\?\.inbound !== false/);
    // Три причини, і кожна названа окремим реченням: злиті в одне, вони
    // перестали б казати людині, що саме чинити.
    expect(gate).toMatch(/NO_CALL_ROADS/);
    expect(gate).toMatch(/NO_CALL_GROUP/);
    expect(gate).toMatch(/NO_CALL_NO_PEER/);
    // Обіцянки звірки в підказці більше немає — бо звірка й не перевіряється.
    expect(scan(/Дзвінки лише зі звіреними/)).toEqual([]);
  });

  /**
   * Обидві слухавки — одні ворота.
   *
   * Мутація: поверніть у `UserProfileModal.handleCall` голий
   * `window.dispatchEvent` без перевірки — падає тут і в profileModalWire.
   */
  it('обидві слухавки питають ті самі ворота', () => {
    for (const file of ['components/messenger/Header.tsx', 'components/messenger/PeerProfileSheet.tsx']) {
      expect(readFileSync(join(ROOT, file), 'utf8'), file).toContain('callGate(');
    }
  });

  /**
   * Вигаданої адреси теж немає: `node_${chat.id}` і `'node_direct_peer'`
   * підставлялись, коли вузлового співрозмовника не існує, — і дзвінок «їхав»
   * на адресу, якої немає.
   */
  it('адреса співрозмовника не вигадується з ідентифікатора розмови', () => {
    expect(scan(/node_direct_peer|`node_\$\{currentChat\.id\}`/)).toEqual([]);
  });

  /**
   * Рушій дзвінків підключений до слухача `phantom:start-call` і не використовує вигаданих адрес.
   */
  it('слухач phantom:start-call зареєстрований і слухає події дзвінка', () => {
    const root = readFileSync(join(ROOT, 'components/messenger/MessengerRoot.tsx'), 'utf8');
    const at = root.indexOf("window.addEventListener('phantom:start-call'");
    expect(at).toBeGreaterThan(0);
    expect(root).toContain('callEngine.startCall');
  });
});
