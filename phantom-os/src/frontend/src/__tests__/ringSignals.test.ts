import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ringSignal, signalIncoming } from '../services/ringSignals';
import { chatHush } from '../services/chatHush';
import { soundFx } from '../utils/messengerSound';
import type { Chat, Message } from '../types/messenger';

const me = { id: 'me', name: 'Me' };
const mk = (over: Partial<Chat>): Chat => ({ id: 'c1', type: 'dm', messages: [], ...over } as unknown as Chat);
const msg = { id: 'm1', text: 'hi', senderId: 'p' } as unknown as Message;

describe('ringSignals', () => {
  const haptics: string[] = [];
  beforeEach(() => {
    localStorage.clear();
    haptics.length = 0;
    chatHush.set('c1', false);
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = { haptic: (k: string) => haptics.push(k) };
    vi.restoreAllMocks();
  });

  it('personal and work differ', () => {
    expect(ringSignal(mk({ circle: 'personal' }))).not.toEqual(ringSignal(mk({ circle: 'work' })));
  });

  it('closest ring wins', () => {
    expect(ringSignal(mk({ circle: 'work', circles: ['close'] }))).toEqual(ringSignal(mk({ circle: 'close' })));
  });

  it('hushed chat gives nothing', () => {
    chatHush.set('c1', true);
    const rx = vi.spyOn(soundFx, 'playReceive');
    const ch = vi.spyOn(soundFx, 'playChime');
    expect(signalIncoming(mk({ circle: 'close' }), msg, Date.now(), me)).toBe(false);
    expect(rx).not.toHaveBeenCalled();
    expect(ch).not.toHaveBeenCalled();
    expect(haptics).toEqual([]);
  });

  it('no ring keeps today default', () => {
    const rx = vi.spyOn(soundFx, 'playReceive');
    expect(signalIncoming(mk({}), msg, Date.now(), me)).toBe(true);
    expect(rx).toHaveBeenCalledWith();
  });

  it('close person: chime and ring haptic', () => {
    const ch = vi.spyOn(soundFx, 'playChime');
    signalIncoming(mk({ circle: 'close' }), msg, Date.now(), me);
    expect(ch).toHaveBeenCalled();
    expect(haptics).toEqual(['confirm']);
  });
});
