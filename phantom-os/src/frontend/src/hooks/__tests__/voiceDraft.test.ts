import { describe, expect, it } from 'vitest';
import { VOICE_IDLE, voiceGesture, type VoiceGestureEvent, type VoiceGestureState } from '../useVoiceRecorder';
import {
  applyVoiceDraftEffect,
  loadVoiceDraft,
  voiceDraftKey,
  type VoiceDraft,
  type VoiceDraftStorage,
} from '../../services/voiceDraft';

const memStorage = (): VoiceDraftStorage & { map: Map<string, VoiceDraft> } => {
  const map = new Map<string, VoiceDraft>();
  return {
    map,
    get: async (k) => map.get(k) ?? null,
    set: async (k, d) => void map.set(k, d),
    del: async (k) => void map.delete(k),
  };
};

const run = (events: VoiceGestureEvent[]) => {
  let state: VoiceGestureState = VOICE_IDLE;
  let effects: string[] = [];
  for (const e of events) ({ state, effects } = voiceGesture(state, e));
  return { state, effects };
};

const locked: VoiceGestureEvent[] = [{ type: 'activate' }];
const snap = async () => ({ chunks: [new Blob(['a'])], mime: 'audio/webm', durationMs: 7300, envelope: [0.1, 0.4] });

describe('voiceDraft', () => {
  it('фон під час замка → пауза і ефект keep; чернетка лишається з тривалістю', async () => {
    const { state, effects } = run([...locked, { type: 'interrupt', background: true }]);
    expect(state.phase).toBe('paused');
    expect(effects).toContain('keep');
    const st = memStorage();
    for (const fx of effects) await applyVoiceDraftEffect(st, 'c1', fx as never, snap);
    const d = await loadVoiceDraft(st, 'c1');
    expect(d?.durationMs).toBe(7300);
    expect(st.map.has(voiceDraftKey('c1'))).toBe(true);
  });

  it('звичайний interrupt у замку нічого не паузить', () => {
    expect(run([...locked, { type: 'interrupt' }]).state.phase).toBe('locked');
  });

  it('send і discard стирають чернетку', async () => {
    for (const fx of ['send', 'discard'] as const) {
      const st = memStorage();
      await applyVoiceDraftEffect(st, 'c1', 'keep', snap);
      expect(await loadVoiceDraft(st, 'c1')).not.toBeNull();
      await applyVoiceDraftEffect(st, 'c1', fx, snap);
      expect(await loadVoiceDraft(st, 'c1')).toBeNull();
    }
  });

  it('restore з чернетки веде в перегляд', () => {
    expect(run([{ type: 'restore' }]).state.phase).toBe('preview');
  });

  it('сховище кидає помилку → тиша', async () => {
    const bad: VoiceDraftStorage = { get: async () => { throw new Error('x'); }, set: async () => { throw new Error('x'); }, del: async () => { throw new Error('x'); } };
    await applyVoiceDraftEffect(bad, 'c1', 'keep', snap);
    expect(await loadVoiceDraft(bad, 'c1')).toBeNull();
  });
});
