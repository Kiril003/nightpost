/**
 * Жодного технічного слова на екрані дзвінка (власник, 26.09: «людині треба
 * показувати щось гарне а не цю нісенітницю»).
 *
 * Таблиці нижче — кожна причина, яку рушій справді кладе в знімок, кожен
 * стан і кожна відмова воріт. Червоніє на коді до 26.09: картка друкувала
 * дорогу ICE («без STUN і TURN…»), кодек, RTT, «Рація: голос їде кадрами
 * через вузол», а завершення — сирий `endedReason` («Вузол не прийняв…»,
 * англійську помилку браузера).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, screen } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';
import { NO_ANSWER_NOTE, linkStallNote, signalRefusedReason, iceLostNote } from '../services/callEngine';
import {
  CALL_JARGON,
  callStateLine,
  endedLine,
  linkLine,
  roomFullLine,
  stallLine,
} from '../services/callWords';
import { tNow } from '../components/chat/tNow';
import { callGate, NO_CALL_GROUP, NO_CALL_NO_PEER, NO_CALL_ROADS } from '../services/callGate';
import { footLabel, whyLabel } from '../services/callRoom';
import type { LegView } from '../services/callRoom';

vi.mock('../services/iceRoads', async (orig) => ({
  ...(await orig<typeof import('../services/iceRoads')>()),
  iceConfig: async () => ({ iceServers: [{ urls: 'stun:stun.example:3478' }], turn: false, ttl: 0 }),
  lastKnownIce: () => ({ iceServers: [{ urls: 'stun:stun.example:3478' }], turn: false, ttl: 0 }),
  iceRoadLabel: () => 'лише STUN — без TURN за суворим NAT не встане',
}));

const CALL_BUSY = tNow('callWords.busy');
const CALL_DECLINED = tNow('callWords.declined');
const CALL_ENDED = tNow('callWords.ended');
const CALL_FAILED = tNow('callWords.failed');
const CALL_NO_ANSWER = tNow('callWords.noAnswer');
const CALL_WEAK = tNow('callWords.weak');
const CALL_RESTORED = tNow('callWords.restored');

let snap: CallSnapshot;
const listeners = new Set<() => void>();
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: (cb: () => void) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
      getSnapshot: () => snap,
      toggleMic: vi.fn(),
      toggleSpeaker: vi.fn(),
      toggleCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
    },
  };
});

/** Усе, що рушій справді пише в `endedReason` (callEngine.finish і callRadio.die). */
const ENGINE_REASONS: Array<[string, string | null]> = [
  ['завершено', CALL_ENDED],
  ['відхилено', CALL_DECLINED],
  ['співрозмовник відхилив', CALL_BUSY],
  ['співрозмовник поклав слухавку', CALL_ENDED],
  ['не приймає дзвінків', null],
  ['усі вийшли з розмови', null],
  ['звʼязок обірвався', null],
  ['доступ до мікрофона й камери не дано', null],
  ['мікрофона чи камери не знайдено', null],
  ['мікрофон або камеру зайнято іншим застосунком', null],
  ['пристрої захоплення недоступні', CALL_FAILED],
  ['не вдалося скласти пропозицію', CALL_FAILED],
  ['не вдалося прийняти дзвінок', CALL_FAILED],
  ['відповідь співрозмовника не прийнялась', CALL_FAILED],
  ["Failed to execute 'setRemoteDescription' on 'RTCPeerConnection'", CALL_FAILED],
  [signalRefusedReason('пропозицію', 'вузол співрозмовника не прийняв сигнал'), CALL_FAILED],
  [signalRefusedReason('відповідь', ''), CALL_FAILED],
  [signalRefusedReason('сигнали дзвінка', '') + iceLostNote(3), CALL_FAILED],
  [NO_ANSWER_NOTE, CALL_NO_ANSWER],
  [linkStallNote(), CALL_FAILED],
  [linkStallNote({ candidates: 0, errors: 2, lastCode: 701, lastText: 'Allocation failed' }), CALL_FAILED],
  [linkStallNote({ candidates: 0, errors: 0, lastCode: null, lastText: '' }), CALL_FAILED],
  ['Цей браузер не вміє записувати webm/opus — рація тут неможлива.', CALL_FAILED],
  ['Мікрофон не віддає звук для рації — записувач не запустився.', null],
  ['завершено — але сигнал «поклав слухавку» не доїхав, у співрозмовника може ще дзвонити', CALL_ENDED],
  ['', CALL_ENDED],
];

describe('завершення — одним людським реченням', () => {
  it.each(ENGINE_REASONS)('%s', (raw, expected) => {
    for (const media of ['audio', 'video'] as const) {
      const line = endedLine(raw, media);
      expect(line).not.toMatch(CALL_JARGON);
      expect(line.length).toBeLessThanOrEqual(80);
      if (expected) expect(line).toBe(expected);
    }
  });

  it('без мікрофона — каже, що зробити', () => {
    expect(endedLine('доступ до мікрофона й камери не дано', 'audio')).toMatch(/Дозвольте/);
  });
});

const STATES: Array<Pick<CallSnapshot, 'state' | 'media' | 'stall' | 'endedReason'>> = (
  ['calling', 'ringing', 'connecting', 'ended'] as const
).flatMap((state) =>
  (['audio', 'video'] as const).flatMap((media) =>
    [
      null,
      { kind: 'no-answer' as const, note: NO_ANSWER_NOTE },
      { kind: 'no-path' as const, note: linkStallNote() },
      { kind: 'no-signal' as const, note: signalRefusedReason('сигнали дзвінка', '') + iceLostNote(2) },
    ].map((stall) => ({ state, media, stall, endedReason: state === 'ended' ? linkStallNote() : null })),
  ),
);

describe('стан дзвінка — без жодного технічного слова', () => {
  it.each(STATES.map((s) => [`${s.state}/${s.media}/${s.stall?.kind ?? '-'}`, s]))('%s', (_n, s) => {
    expect(callStateLine(s)).not.toMatch(CALL_JARGON);
  });

  it('звістка про канал', () => {
    const rows = [
      { radio: { sent: 3, delivered: 2, played: 2 } as never, radioReason: 'пряма доріжка так і не встала', linkNote: null, videoDropped: false },
      { radio: null, radioReason: 'співрозмовник перейшов на рацію', linkNote: null, videoDropped: false },
      { radio: null, radioReason: null, linkNote: 'Пряму доріжку підняти не вдалось — лишаємось на рації.', videoDropped: false },
      { radio: null, radioReason: null, linkNote: 'Канал відновлено', videoDropped: false },
      { radio: null, radioReason: null, linkNote: null, videoDropped: true },
    ];
    for (const r of rows) expect(linkLine(r) ?? '').not.toMatch(CALL_JARGON);
    expect(linkLine(rows[0])).toBe(CALL_WEAK);
    expect(linkLine(rows[3])).toBe(CALL_RESTORED);
    expect(linkLine({ radio: null, radioReason: null, linkNote: null, videoDropped: false })).toBeNull();
  });

  it('зупинка', () => {
    expect(stallLine({ kind: 'no-answer', note: NO_ANSWER_NOTE })).toMatch(/^Не відповідає/);
    expect(stallLine({ kind: 'no-path', note: linkStallNote() })).not.toMatch(CALL_JARGON);
    expect(roomFullLine(8)).not.toMatch(CALL_JARGON);
  });
});

describe('ворота й учасники — теж людською мовою', () => {
  it.each([NO_CALL_ROADS, NO_CALL_GROUP, NO_CALL_NO_PEER])('%s', (why) => {
    expect(why).not.toMatch(CALL_JARGON);
    expect(why).not.toMatch(/сітк|адрес|1:1/i);
  });

  it('ворота віддають саме ці речення', () => {
    const ok = { roads: { inbound: true }, phoneShell: false, hasPeer: true, isGroup: false };
    expect(callGate({ ...ok, roads: {}, phoneShell: true }).why).toBe(NO_CALL_ROADS);
  });

  it.each(['declined', 'busy', 'no-answer', 'no-path', 'full', 'hung-up', 'no-media', 'ice-failed-701'])('причина ноги %s', (why) => {
    expect(whyLabel(why) ?? '').not.toMatch(CALL_JARGON);
  });

  it('стан ноги', () => {
    const base = { peerId: 'p', name: 'Оля', primary: false, stream: null, level: null, speaking: false } as unknown as LegView;
    for (const foot of ['dialing', 'ringing', 'talking', 'gone'] as const)
      for (const reachable of [true, false, null])
        for (const here of [true, false])
          expect(footLabel({ ...base, foot, reachable, here, why: 'no-path' } as LegView)).not.toMatch(CALL_JARGON);
  });
});

/* ── Екран: що справді потрапляє в DOM у кожному стані ─────────────────── */

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'audio',
  outgoing: true,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: Date.now() - 65_000,
  stats: {
    rttMs: 84,
    packetsLost: 2,
    audioCodec: 'opus',
    videoCodec: null,
    kbps: 48,
    localCandidate: 'srflx',
    remoteCandidate: 'relay',
  },
  endedReason: null,
  stall: null,
  audioLevel: 'narrow',
  ladderPinned: false,
  videoDropped: false,
  radio: null,
  radioReason: null,
  linkNote: null,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  served: null,
  weakSide: null,
};

const SCREENS: Array<[string, Partial<CallSnapshot>]> = [
  ['дзвоню', { state: 'calling', startedAt: null, stats: null }],
  ['не відповідає', { state: 'calling', startedAt: null, stall: { kind: 'no-answer', note: NO_ANSWER_NOTE } }],
  ['вхідний', { state: 'ringing', outgoing: false, startedAt: null }],
  ['зʼєднуємо без дороги', { state: 'connecting', startedAt: null, stall: { kind: 'no-path', note: linkStallNote() } }],
  ['розмова', {}],
  ['розмова з ПОГЛЯДОМ', { served: { t: 'serve', v: 1, seq: 4, active: true, h: 360, fps: 24, kbps: 250, mbh: 42.4 } }],
  ['рація', { radio: { sent: 9, delivered: 8, played: 7 } as never, radioReason: 'пряма доріжка так і не встала', stats: null }],
  ['повернення не вдалось', { linkNote: 'Пряму доріжку підняти не вдалось — лишаємось на рації.' }],
  [
    'кімната',
    {
      legs: [
        { peerId: 'a', name: 'Марта', foot: 'talking', reachable: true, here: true, primary: true, stream: null, level: 0.1, speaking: true, why: null },
        { peerId: 'b', name: 'Петро', foot: 'gone', reachable: false, here: false, primary: false, stream: null, level: null, speaking: false, why: 'no-path' },
        { peerId: 'c', name: 'Оля', foot: 'talking', reachable: true, here: false, primary: false, stream: null, level: null, speaking: false, why: null },
      ] as unknown as LegView[],
      roomMax: 3,
      roomCrowded: true,
    },
  ],
  ['завершено з причиною вузла', { state: 'ended', endedReason: signalRefusedReason('пропозицію', 'peer offline') }],
  ['завершено англійською', { state: 'ended', endedReason: 'NotReadableError: Could not start audio source' }],
];

describe('екран дзвінка не показує технічних слів', () => {
  afterEach(() => cleanup());

  it.each([
    ['me', 'Слабкий звʼязок у вас'],
    ['them', 'Марта: слабкий звʼязок'],
    ['path', 'Слабкий звʼязок'],
  ] as const)('слабкий бік %s — рядок на екрані дзвінка', async (side, line) => {
    snap = { ...BASE, weakSide: side };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    await Promise.resolve();
    expect(document.querySelector('[data-call-overlay]')?.textContent ?? '').toContain(line);
  });

  it.each(SCREENS)('%s', async (_n, patch) => {
    snap = { ...BASE, ...patch };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    await Promise.resolve();
    const overlay = document.querySelector('[data-call-overlay]');
    expect(overlay).not.toBeNull();
    const text = overlay!.textContent ?? '';
    expect(text).not.toMatch(CALL_JARGON);
    expect(text).not.toMatch(/RTT|кбіт|opus|srflx|relay|кришталевий|звірено/i);
    expect(text).toContain('Марта');
  });

  /**
   * ПОГЛЯД: під таймером — скільки співрозмовник справді шле, з його
   * bytesSent. Мутація: приберіть `snapshot.served` з CallOverlay — і числа
   * не стане; без кадру `serve` (старий клієнт) числа нема й не вигадується.
   */
  it('ПОГЛЯД: «~N МБ/год» лише з виміру співрозмовника', async () => {
    snap = { ...BASE, served: { t: 'serve', v: 1, seq: 4, active: true, h: 360, fps: 24, kbps: 250, mbh: 42.4 } };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    const { unmount } = render(React.createElement(CallOverlay));
    expect(document.querySelector('[data-call-line]')?.textContent).toContain('~42 МБ/год');
    unmount();
    snap = { ...BASE, served: null };
    render(React.createElement(CallOverlay));
    expect(document.querySelector('[data-call-mbh]')).toBeNull();
    expect(document.querySelector('[data-call-line]')?.textContent).not.toMatch(/МБ/);
  });

  it('керування — великі круглі кнопки від 64 px, завершення завжди під рукою', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const end = document.querySelector('[aria-label="Завершити дзвінок"]');
    expect(end?.className).toMatch(/w-16 h-16|w-\[72px\]/);
    expect(document.querySelector('[aria-label="Вимкнути мікрофон (M)"]')).not.toBeNull();
  });

  it('27.09, як макет: імʼя без засічок, кнопки колами 72, червоне «завершити» окремо під рядом', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const name = screen.getByRole('heading', { name: 'Марта' });
    expect(name.className).not.toMatch(/font-serif/);
    const end = document.querySelector('[aria-label="Завершити дзвінок"]') as HTMLElement;
    expect(end.className).toMatch(/w-\[72px\] h-\[72px\]/);
    const mic = document.querySelector('[aria-label="Вимкнути мікрофон (M)"]') as HTMLElement;
    // Завершення — не в одному ряду з перемикачами.
    expect(mic.parentElement!.parentElement).not.toBe(end.parentElement!.parentElement);
    expect(end.parentElement!.textContent).toBe('');
  });
});

/**
 * 03.10, v1.2: екран дзвінка — лише токени теми. Червоне й зелене — з
 * `--ph-danger`/`--ph-success`, а не з лісової палітри `--call-*`; гліф на
 * них — полотно (`--ph-canvas`), не білий: #FFF на нічному #FF7D71 — 2.2:1.
 * Кільця очікування (біблія 05 §2.7: аватар без кілець і світіння) теж нема.
 * Поверніть `--call-end` чи ореол — тест червоніє.
 *
 * 17.10: тло без відео — рівне полотно. Розмите фото чи пляма тону за
 * обличчям (D7, 08.10) читались як світіння — тепер будь-яке розмиття,
 * картинка чи градієнт на картці без відео — червоне.
 */
describe('екран дзвінка — мовою v1.2', () => {
  afterEach(() => cleanup());
  const PHOTO = 'data:image/webp;base64,UklGRgAAAABXRUJQ';

  const withPhoto = async () => {
    const { useMessengerStore } = await import('../stores/messengerStore');
    useMessengerStore.setState({
      chats: [{ id: 'd1', title: 'Марта', type: 'dm', circle: 'all', peerNodeId: BASE.peer!.peerNodeId, avatar: PHOTO, unreadCount: 0, messages: [] }],
    } as never);
    return () => useMessengerStore.setState({ chats: [] } as never);
  };

  it('«Завершити» — var(--ph-danger) з гліфом полотна; без білого', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const end = document.querySelector('[aria-label="Завершити дзвінок"]') as HTMLElement;
    expect(end.style.background).toBe('var(--ph-danger)');
    expect(end.style.color).toBe('var(--ph-canvas)');
    expect(document.querySelector('[data-call-overlay]')!.innerHTML).not.toMatch(/--call-|#FFFFFF|text-white/i);
  });

  it('вхідний: «Відхилити» — danger з гліфом полотна, «Прийняти» — бурштин (стиль D)', async () => {
    snap = { ...BASE, state: 'ringing', outgoing: false, startedAt: null };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const decline = document.querySelector('[aria-label="Відхилити дзвінок"]') as HTMLElement;
    const accept = document.querySelector('[aria-label="Прийняти дзвінок"]') as HTMLElement;
    expect(decline.style.background).toBe('var(--ph-danger)');
    expect(accept.style.background).toBe('var(--ph-accent)');
    expect(decline.style.color).toBe('var(--ph-canvas)');
    expect(accept.style.color).toBe('var(--ph-on-accent)');
  });

  it('згорнута пігулка: «Завершити» теж danger без text-white', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.click(screen.getByRole('button', { name: 'Згорнути дзвінок' }));
    const pill = document.querySelector('[data-call-pill]') as HTMLElement;
    const end = pill.querySelector('[aria-label="Завершити дзвінок"]') as HTMLElement;
    expect(end.style.background).toBe('var(--ph-danger)');
    expect(end.className).not.toMatch(/text-white/);
    expect(pill.innerHTML).not.toMatch(/--call-/);
  });

  it('без фото — та сама рівна площина, жодної плями тону за обличчям', async () => {
    snap = { ...BASE, state: 'calling', startedAt: null };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const overlay = document.querySelector('[data-call-overlay]') as HTMLElement;
    expect(overlay.innerHTML).not.toMatch(/radial-gradient|blur\(|gradient\(/);
    expect((overlay.querySelector('[data-call-backdrop]') as HTMLElement).children.length).toBe(0);
  });

  it('без відео тло рівне — ні розмиття, ні градієнта, ні картинки, з фото чи без; без ореолу очікування', async () => {
    const restore = await withPhoto();
    try {
      for (const state of ['calling', 'ringing', 'active'] as const) {
        snap = { ...BASE, state, startedAt: state === 'active' ? BASE.startedAt : null };
        const { CallOverlay } = await import('../components/messenger/CallOverlay');
        const { unmount } = render(React.createElement(CallOverlay));
        const overlay = document.querySelector('[data-call-overlay]') as HTMLElement;
        expect(overlay.querySelector('img')?.getAttribute('src'), state).toBe(PHOTO);
        const soft = [...overlay.querySelectorAll<HTMLElement>('[style]')].filter((el) =>
          /blur\(|background-image|gradient/.test(el.getAttribute('style') ?? ''),
        );
        expect(soft.map((el) => el.getAttribute('style')), state).toEqual([]);
        expect(overlay.innerHTML, state).not.toMatch(/radial-gradient|blur\(/);
        const backdrop = overlay.querySelector('[data-call-backdrop]') as HTMLElement;
        expect(backdrop.getAttribute('aria-hidden'), state).toBe('true');
        expect(backdrop.className, state).toContain('bg-canvas');
        expect(backdrop.children.length, state).toBe(0);
        const face = overlay.querySelector('img')!.closest('[data-call-face]') as HTMLElement;
        expect(face, state).not.toBeNull();
        expect(face.children.length, state).toBe(1);
        unmount();
      }
    } finally {
      restore();
    }
  });
});
