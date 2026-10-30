/**
 * W0-6 «Голосове без пастки» (ГЛ-01..04, 05_ux_design §2.6): машина жесту
 * мікрофона. Пороги — 200 мс утримання, 64 px угору (замок), 40 % ширини
 * вліво (скасування) — перевіряються тут без пальця й без мікрофона.
 */
import { describe, it, expect } from 'vitest';
import {
  VOICE_CANCEL_FRACTION,
  VOICE_HINT_MS,
  VOICE_HOLD_MS,
  VOICE_IDLE,
  VOICE_LOCK_PX,
  VOICE_MIN_MS,
  compressEnvelope,
  voiceGesture,
  voiceProgress,
  type VoiceEffect,
  type VoiceGestureEvent,
  type VoiceGestureState,
} from '../hooks/useVoiceRecorder';

const W = 400;
const X = 370;
const Y = 700;

/** Прогнати події й зібрати всі ефекти. */
const run = (events: VoiceGestureEvent[], from: VoiceGestureState = VOICE_IDLE) => {
  let state = from;
  const effects: VoiceEffect[] = [];
  for (const e of events) {
    const r = voiceGesture(state, e);
    state = r.state;
    effects.push(...r.effects);
  }
  return { state, effects };
};

const down = (t = 0): VoiceGestureEvent => ({ type: 'down', x: X, y: Y, t, width: W });
const held: VoiceGestureEvent[] = [down(0), { type: 'hold', t: VOICE_HOLD_MS }];

describe('пороги — числа з біблії', () => {
  it('200 мс / 64 px / 40 %', () => {
    expect(VOICE_HOLD_MS).toBe(200);
    expect(VOICE_LOCK_PX).toBe(64);
    expect(VOICE_CANCEL_FRACTION).toBe(0.4);
    expect(VOICE_HINT_MS).toBe(1500);
  });
});

describe('утримання (ГЛ-04)', () => {
  it('дотик коротший за 200 мс не починає запис — буфер від дотику знищено, лише підказка', () => {
    const { state, effects } = run([down(0), { type: 'up', t: 150 }]);
    expect(state.phase).toBe('idle');
    expect(effects).toEqual(['warm', 'discard', 'hint']);
    expect(effects).not.toContain('start');
    expect(effects).not.toContain('send');
    expect(state.hintUntil).toBe(150 + VOICE_HINT_MS);
  });

  it('таймер, що спрацював раніше 200 мс, запису не починає', () => {
    const { state, effects } = run([down(0), { type: 'hold', t: 199 }]);
    expect(state.phase).toBe('pressing');
    expect(effects).toEqual(['warm']);
  });

  it('утримання 200 мс → запис і гаптика «threshold»', () => {
    const { state, effects } = run(held);
    expect(state.phase).toBe('holding');
    expect(effects).toEqual(['warm', 'start', 'haptic:threshold']);
  });

  it('відпустив без замка → надіслано (confirm)', () => {
    const { state, effects } = run([...held, { type: 'up', t: VOICE_HOLD_MS + 3000 }]);
    expect(state.phase).toBe('idle');
    expect(effects).toEqual(['warm', 'start', 'haptic:threshold', 'send', 'haptic:confirm']);
  });

  it('запис коротший за пів секунди не летить — знищено, підказка', () => {
    const { state, effects } = run([...held, { type: 'up', t: VOICE_HOLD_MS + VOICE_MIN_MS - 1 }]);
    expect(state.phase).toBe('idle');
    expect(effects).not.toContain('send');
    expect(effects).toContain('discard');
    expect(effects).toContain('hint');
  });
});

describe('замок угору на 64 px (ГЛ-01)', () => {
  it('63 px — ще не замок; 64 px — замок, «threshold» рівно один раз', () => {
    const near = run([...held, { type: 'move', x: X, y: Y - 63 }]);
    expect(near.state.phase).toBe('holding');
    expect(voiceProgress(near.state).lock).toBeCloseTo(63 / 64);

    const { state, effects } = run([
      ...held,
      { type: 'move', x: X, y: Y - 64 },
      { type: 'move', x: X, y: Y - 120 },
      { type: 'move', x: X, y: Y - 10 },
    ]);
    expect(state.phase).toBe('locked');
    expect(effects.filter((e) => e === 'haptic:threshold')).toHaveLength(2); // старт + замок
    expect(effects.slice(3)).toEqual(['haptic:threshold']);
  });

  it('після замка відпустити палець — запис іде далі, нічого не надіслано', () => {
    const { state, effects } = run([...held, { type: 'move', x: X, y: Y - 70 }, { type: 'up', t: 5000 }]);
    expect(state.phase).toBe('locked');
    expect(effects).not.toContain('send');
    expect(effects).not.toContain('discard');
  });

  it('діагональ, де вліво більше, ніж угору, не замикає', () => {
    const { state } = run([...held, { type: 'move', x: X - 100, y: Y - 70 }]);
    expect(state.phase).toBe('holding');
  });
});

describe('скасування вліво на 40 % ширини (ГЛ-02)', () => {
  it('39 % — ще ні; 40 % — запис знищено, гаптика «reject», нічого не надіслано', () => {
    const near = run([...held, { type: 'move', x: X - W * 0.39, y: Y }]);
    expect(near.state.phase).toBe('holding');
    expect(voiceProgress(near.state).cancel).toBeCloseTo(0.39 / 0.4);

    const { state, effects } = run([...held, { type: 'move', x: X - W * 0.4, y: Y }, { type: 'up', t: 4000 }]);
    expect(state.phase).toBe('idle');
    expect(effects).toEqual(['warm', 'start', 'haptic:threshold', 'discard', 'haptic:reject']);
    expect(effects).not.toContain('send');
  });

  it('рух управо чи вниз нічого не робить', () => {
    const { state } = run([...held, { type: 'move', x: X + 200, y: Y + 200 }]);
    expect(state.phase).toBe('holding');
    expect(voiceProgress(state)).toEqual({ lock: 0, cancel: 0 });
  });
});

describe('замок: пауза, «Стоп» → перегляд, надіслати / видалити (ГЛ-03)', () => {
  const locked = [...held, { type: 'move', x: X, y: Y - 64 } as VoiceGestureEvent];

  it('пауза й продовження', () => {
    const { state, effects } = run([...locked, { type: 'pause' }, { type: 'resume' }]);
    expect(state.phase).toBe('locked');
    expect(effects.slice(-2)).toEqual(['pause', 'resume']);
  });

  it('«Стоп» → перегляд; «Надіслати» шле, «Видалити» знищує', () => {
    const preview = run([...locked, { type: 'stop' }]);
    expect(preview.state.phase).toBe('preview');
    expect(preview.effects.at(-1)).toBe('stop');

    const sent = run([{ type: 'send' }], preview.state);
    expect(sent.state.phase).toBe('idle');
    expect(sent.effects).toEqual(['send', 'haptic:confirm']);

    const gone = run([{ type: 'delete' }], preview.state);
    expect(gone.state.phase).toBe('idle');
    expect(gone.effects).toEqual(['discard', 'haptic:reject']);
  });

  it('«Стоп» з паузи теж веде в перегляд', () => {
    expect(run([...locked, { type: 'pause' }, { type: 'stop' }]).state.phase).toBe('preview');
  });

  it('надіслати можна й одразу із замка, без перегляду', () => {
    const { state, effects } = run([...locked, { type: 'send' }]);
    expect(state.phase).toBe('idle');
    expect(effects.slice(-2)).toEqual(['send', 'haptic:confirm']);
  });
});

describe('без пастки', () => {
  it('система забрала палець під час запису — запис лишається в замку, не губиться і не летить', () => {
    const { state, effects } = run([...held, { type: 'interrupt' }]);
    expect(state.phase).toBe('locked');
    expect(effects).not.toContain('send');
    expect(effects).not.toContain('discard');
  });

  it('мікрофон не дався — назад у спокій', () => {
    expect(run([...held, { type: 'fail' }]).state.phase).toBe('idle');
  });

  it('доступна дорога: клавіатура / зчитувач / миша — запис без утримання, одразу в замку', () => {
    const { state, effects } = run([{ type: 'activate' }]);
    expect(state.phase).toBe('locked');
    expect(effects).toEqual(['start', 'haptic:threshold']);
  });

  it('другий дотик, поки видно підказку, — запис без утримання; після неї — знову підказка', () => {
    const first = run([down(0), { type: 'up', t: 100 }]);
    const again = run([down(600), { type: 'up', t: 700 }], first.state);
    expect(again.state.phase).toBe('locked');
    expect(again.effects).toEqual(['warm', 'start', 'haptic:threshold']);

    const late = run([down(100 + VOICE_HINT_MS + 1), { type: 'up', t: 100 + VOICE_HINT_MS + 50 }], first.state);
    expect(late.state.phase).toBe('idle');
    expect(late.effects).toEqual(['warm', 'discard', 'hint']);
  });
});

describe('хвиля', () => {
  it('стискання за піком; порожньо — значить порожньо', () => {
    expect(compressEnvelope([], 32)).toEqual([]);
    expect(compressEnvelope([0.1, 0.9, 0.2, 0.3], 2)).toEqual([0.9, 0.3]);
    expect(compressEnvelope([0.5], 4)).toHaveLength(4);
  });
});

describe('media-24: запис від дотику', () => {
  it('дотик відкриває мікрофон одразу — warm, ще до 200 мс', () => {
    expect(voiceGesture(VOICE_IDLE, down(0)).effects).toEqual(['warm']);
  });

  it('система забрала палець до утримання — буфер знищено, нічого не лишилось', () => {
    const { state, effects } = run([down(0), { type: 'interrupt' }]);
    expect(state.phase).toBe('idle');
    expect(effects).toEqual(['warm', 'discard']);
  });

  it('другий дотик у вже йдучому записі мікрофон заново не відкриває', () => {
    const { effects } = run([...held, down(400)]);
    expect(effects.filter((e) => e === 'warm')).toHaveLength(1);
  });
});
