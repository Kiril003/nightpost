/**
 * Закріплене переживає перезавантаження (перенесено з calmInbox.test.tsx,
 * коли мертве правило спокійного потоку пішло з дерева; chatPins — живе:
 * його читає messengerStore).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatPins } from '../services/chatPins';

describe('закріплене переживає перезавантаження', () => {
  beforeEach(() => chatPins.reset());

  it('вибір людини сильніший за слово вузла в обидва боки', () => {
    expect(chatPins.apply('a', false)).toBe(false);
    chatPins.set('a', true);
    expect(chatPins.apply('a', false)).toBe(true);
    chatPins.set('phantom', false);
    expect(chatPins.apply('phantom', true)).toBe(false);
    expect(JSON.parse(localStorage.getItem('phantom_pinned_chats') || '{}')).toEqual({ a: true, phantom: false });
  });
});
