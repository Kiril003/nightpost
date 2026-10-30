/**
 * Сторож кімнати.
 *
 * Міряє рівно те, що було ЗЛАМАНО й полагоджено 17.09, і жодного разу — те,
 * що «мабуть, працює»: справжнього WebRTC у jsdom немає, тож тут перевірка
 * РІШЕНЬ кімнати (кого вона заводить, кого гасить, що каже людині), а не
 * доріжки. Доріжку міряє `scripts/measure-mesh-legs.mjs` у справжньому
 * Chromium, і плутати ці два прилади не можна.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CallRoom, footLabel, whyLabel, SPEAKING_RMS } from '../services/callRoom';
import type { RoomDeps } from '../services/callRoom';
import { linkStallNote } from '../services/callEngine';
import * as iceRoads from '../services/iceRoads';

/* ── дублер зʼєднання ─────────────────────────────────────────────────────
 * jsdom не має RTCPeerConnection. Дублер відповідає рівно на те, що кімната
 * питає, і на решту падає голосно, а не тихо: мовчазний дублер приховав би
 * саме ту гілку, заради якої тест і писався.
 */
class FakePc {
  localDescription: { type: string; sdp: string } | null = null;
  remoteDescription: unknown = null;
  connectionState = 'new';
  signalingState = 'stable';
  closed = false;
  private handlers = new Map<string, Array<(e: unknown) => void>>();
  onicecandidate: ((e: unknown) => void) | null = null;

  constructor(private readonly failAt: string | null = null) {}

  addEventListener(name: string, cb: (e: unknown) => void): void {
    if (!this.handlers.has(name)) this.handlers.set(name, []);
    this.handlers.get(name)!.push(cb);
  }
  fire(name: string, e: unknown = {}): void {
    (this.handlers.get(name) ?? []).forEach((cb) => cb(e));
  }
  async setRemoteDescription(d: unknown): Promise<void> {
    if (this.failAt === 'remote') throw new Error('опис не ліг');
    this.remoteDescription = d;
  }
  async createAnswer(): Promise<{ type: string; sdp: string }> {
    return { type: 'answer', sdp: 'v=0\r\na=rtpmap:111 opus/48000/2\r\n' };
  }
  async setLocalDescription(d: { type: string; sdp: string }): Promise<void> {
    this.localDescription = d;
  }
  getSenders(): Array<{ track: { kind: string }; getParameters: () => { encodings: unknown[] }; setParameters: () => Promise<void> }> {
    return [
      {
        track: { kind: 'audio' },
        getParameters: () => ({ encodings: [{}] }),
        setParameters: async () => undefined,
      },
    ];
  }
  addTrack(): void {}
  async addIceCandidate(): Promise<void> {}
  close(): void {
    this.closed = true;
  }
}

let made: FakePc[] = [];
let failNext: string | null = null;

const deps = (over: Partial<RoomDeps> = {}): RoomDeps => ({
  ice: async () => ({ iceServers: [], turn: false, ttl: 0 }) as never,
  localStream: () => ({ getTracks: () => [{ kind: 'audio' }] }) as never,
  audioLevel: () => 'full',
  callId: () => 'c_1',
  send: async () => ({ delivered: true, detail: '' }),
  changed: () => undefined,
  ...over,
});

const OFFER = 'v=0\r\na=rtpmap:111 opus/48000/2\r\n';

beforeEach(() => {
  made = [];
  failNext = null;
  (globalThis as Record<string, unknown>).RTCPeerConnection = function () {
    const pc = new FakePc(failNext);
    made.push(pc);
    return pc;
  } as never;
  // jsdom не має MediaStream. Дублер тримає рівно те, що кімната кличе —
  // складати з нього справжній потік не треба й не можна: справжній звук
  // міряє `scripts/measure-mesh-legs.mjs` у справжньому Chromium.
  (globalThis as Record<string, unknown>).MediaStream = class {
    private tracks: Array<{ id: string }> = [];
    getTrackById(id: string) {
      return this.tracks.find((t) => t.id === id) ?? null;
    }
    addTrack(t: { id: string }) {
      this.tracks.push(t);
    }
    getAudioTracks() {
      return this.tracks;
    }
  } as never;
  // AudioContext навмисно відсутній: кімната мусить пережити браузер, у якому
  // виміряти гучність нема чим, і сказати про це `level: null`, а не нулем.
  delete (globalThis as Record<string, unknown>).AudioContext;
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('кімната тримає ноги поруч, а не по черзі', () => {
  it('друга нога з ТИМ САМИМ номером дзвінка не чіпає першої', async () => {
    const room = new CallRoom(deps());
    const first = new FakePc();
    room.adopt('peer-a', 'Іван', first as never, 'talking');

    const sdp = await room.open('peer-b', 'Марта', OFFER);

    expect(sdp).toBeTruthy();
    // ОСЬ ВОНА, ВАДА, ЯКОЇ БІЛЬШЕ НЕМАЄ: до 17.09 опис другого учасника
    // лягав `setRemoteDescription` на зʼєднання ПЕРШОГО.
    expect(first.remoteDescription).toBeNull();
    expect(room.live().map((l) => l.peerId)).toEqual(['peer-a', 'peer-b']);
    expect(room.allPcs()).toHaveLength(2);
  });

  it('вихід одного забирає ЙОГО ногу, решта розмови жива', async () => {
    const room = new CallRoom(deps());
    room.adopt('peer-a', 'Іван', new FakePc() as never, 'talking');
    await room.open('peer-b', 'Марта', OFFER);
    await room.open('peer-c', 'Тарас', OFFER);
    expect(room.live()).toHaveLength(3);

    await room.drop('peer-b', 'hung-up', false);

    expect(room.live().map((l) => l.peerId)).toEqual(['peer-a', 'peer-c']);
    // Нога не зникає з екрана — вона лишається зі словом причини.
    const gone = room.view().find((l) => l.peerId === 'peer-b');
    expect(gone?.foot).toBe('gone');
    expect(gone?.why).toBe('hung-up');
  });

  it('«поклав» від нас летить САМЕ тій нозі, яку ми знімаємо', async () => {
    const sent: Array<[string, string]> = [];
    const room = new CallRoom(
      deps({
        send: async (peerId, kind) => {
          sent.push([peerId, kind]);
          return { delivered: true, detail: '' };
        },
      }),
    );
    room.adopt('peer-a', 'Іван', new FakePc() as never, 'talking');
    await room.open('peer-b', 'Марта', OFFER);

    await room.drop('peer-b', 'hung-up', true);
    expect(sent).toEqual([['peer-b', 'hangup']]);

    // А нозі, що пішла САМА, кадр назад не летить: він у нікуди, і на тому
    // боці розмови з нами вже немає.
    sent.length = 0;
    await room.drop('peer-a', 'hung-up', false);
    expect(sent).toEqual([]);
  });

  it('нога, що не встала, лишається зі словом «не дійшло»', async () => {
    failNext = 'remote';
    const room = new CallRoom(deps());
    const sdp = await room.open('peer-x', 'Хтось', OFFER);

    expect(sdp).toBeNull();
    const leg = room.view().find((l) => l.peerId === 'peer-x');
    expect(leg?.foot).toBe('gone');
    expect(leg?.why).toBe('no-path');
    expect(footLabel(leg!)).toBe('недоступний');
  });

  it('учасник, що прийшов ДО слухавки, видимий і піднімається потім', async () => {
    let stream: unknown = null;
    const room = new CallRoom(deps({ localStream: () => stream as never }));

    room.defer('peer-late', 'Пізній', OFFER);
    // Мікрофона ще немає — відповісти нема чим, але нога вже ВИДИМА.
    expect(room.live().map((l) => l.peerId)).toEqual(['peer-late']);
    expect(room.live()[0].foot).toBe('ringing');

    stream = { getTracks: () => [{ kind: 'audio' }] };
    const raised = await room.openDeferred();
    expect(raised.map((r) => r.peerId)).toEqual(['peer-late']);
    expect(room.connectedHere('peer-late')).toBe(true);
  });
});

describe('кімната не вигадує того, чого не міряла', () => {
  it('без AudioContext гучність — null, а не нуль', async () => {
    const room = new CallRoom(deps());
    await room.open('peer-b', 'Марта', OFFER);
    made[0].fire('track', { track: { id: 't1', kind: 'audio' }, streams: [] });
    // «Не міряли» і «тиша» — різні речі, і нуль сказав би друге замість першого.
    expect(room.view()[0].level).toBeNull();
    expect(room.view()[0].speaking).toBe(false);
  });

  it('стеля кімнати — з телефона, і поки його не спитали, її немає', async () => {
    const room = new CallRoom(deps());
    expect(room.ceiling().max).toBeNull();

    room.sync({ legs: [], max_legs: 8, crowded_at: 6 });
    expect(room.ceiling()).toEqual({ max: 8, crowdedAt: 6 });
  });

  it('нога з мосту, якої рушій не будував, видима як «не тут»', () => {
    const room = new CallRoom(deps());
    room.sync({
      legs: [{ peer_id: 'peer-z', name: 'Зоя', foot: 'talking', since_ms: 111, reachable: true }],
      max_legs: 8,
    });
    const leg = room.view()[0];
    // Телефон тримає цю ногу; зʼєднання в цьому вебі для неї не існує.
    expect(leg.here).toBe(false);
    expect(footLabel(leg)).toBe('у розмові');
  });

  it('нога без прямої дороги названа «недоступний», а не «дзвонить»', () => {
    const room = new CallRoom(deps());
    room.sync({ legs: [{ peer_id: 'p', foot: 'dialing', reachable: false }] });
    expect(footLabel(room.view()[0])).toBe('недоступний');
  });

  it('невідоме слово причини не виходить на екран сирим і не вигадує причину', () => {
    expect(whyLabel('declined')).toBe('відхилив');
    // Сире слово рушія людині нічого не каже; вигадана причина — брехня.
    // Лишається єдина правда: людина не долучилась.
    expect(whyLabel('ice-failed-701')).toBe('не долучився');
    expect(whyLabel(null)).toBeNull();
  });

  it('поріг «говорить» — не нуль: тиша не має вмикати підпис', () => {
    expect(SPEAKING_RMS).toBeGreaterThan(0);
  });
});

describe('про ретранслятор кажемо виміряне, а не оголошене', () => {
  it('TURN оголошений, але не дав адрес і відмовив — так і кажемо', () => {
    vi.spyOn(iceRoads, 'hasTurn').mockReturnValue(true);
    const note = linkStallNote({ candidates: 0, errors: 3, lastCode: 701, lastText: 'Allocation failed' });
    // Стара версія на цьому місці обіцяла «ще кілька секунд» — і обіцяла їх
    // до кінця дзвінка, який не встане ніколи.
    expect(note).not.toContain('кілька секунд');
    expect(note).toContain('жодної адреси');
    expect(note).toContain('701');
  });

  it('TURN дав адреси — обіцянка знову правдива', () => {
    vi.spyOn(iceRoads, 'hasTurn').mockReturnValue(true);
    expect(linkStallNote({ candidates: 4, errors: 0, lastCode: null, lastText: '' })).toContain(
      'кілька секунд',
    );
  });

  it('TURN немає взагалі — межа названа, а не списана на ретранслятор', () => {
    vi.spyOn(iceRoads, 'hasTurn').mockReturnValue(false);
    expect(linkStallNote({ candidates: 0, errors: 9, lastCode: 701, lastText: '' })).toContain(
      'Без TURN-сервера',
    );
  });
});

/**
 * Друга половина дзеркала контракту.
 *
 * Перша половина — `WebMessengerCallReachTest."кімната віддає вебу рівно ті
 * поля, які він читає"` у дереві телефона: вона стежить, щоб міст ці поля
 * СЛАВ. Ця стежить, щоб веб їх ЧИТАВ. Разом вони ловлять розбіжність з обох
 * боків; одна без одної ловить лише половину.
 *
 * Читати чуже дерево звідси не можна: на воротах його немає, і звірка не
 * почервоніла б, а тихо пропустилась — тобто перестала б бути звіркою.
 */
describe('контракт кімнати з мостом', () => {
  it('читаються всі поля, які шле міст, і жодне не губиться дорогою', () => {
    const room = new CallRoom(deps());
    room.sync({
      legs: [
        { peer_id: 'p1', name: 'Іван', foot: 'talking', since_ms: 1700, why: null, reachable: true },
        { peer_id: 'p2', name: 'Марта', foot: 'dialing', since_ms: 1800, why: null, reachable: false },
      ],
      max_legs: 8,
      crowded_at: 6,
    });

    const [a, b] = room.view();
    expect(a.peerId).toBe('p1');
    expect(a.name).toBe('Іван');
    expect(a.foot).toBe('talking');
    expect(a.sinceMs).toBe(1700);
    expect(a.reachable).toBe(true);
    expect(b.reachable).toBe(false);
    expect(room.ceiling()).toEqual({ max: 8, crowdedAt: 6 });
  });

  it('нога, яку міст поховав, ховається й тут — навіть якщо рушій її будував', async () => {
    const room = new CallRoom(deps());
    await room.open('p1', 'Іван', OFFER);
    expect(room.connectedHere('p1')).toBe(true);

    // Кадр «поклав» міг приїхати тоді, коли месенджера не було на екрані:
    // телефон про це знає, рушій — ні. Правда за телефоном.
    room.sync({ legs: [{ peer_id: 'p1', foot: 'gone', why: 'declined' }] });

    expect(room.live()).toHaveLength(0);
    expect(room.view()[0].why).toBe('declined');
    expect(room.connectedHere('p1')).toBe(false);
  });

  it('свіжа нога не зникає від звірки, у якій міст її ще не бачить', async () => {
    const room = new CallRoom(deps());
    await room.open('p-new', 'Щойно', OFFER);
    // Нога зʼявилась мілісекунду тому й до мосту ще не доїхала. Викинути її
    // означало б показати людині, як співрозмовник блимає.
    room.sync({ legs: [], max_legs: 8 });
    expect(room.live().map((l) => l.peerId)).toEqual(['p-new']);
  });
});
