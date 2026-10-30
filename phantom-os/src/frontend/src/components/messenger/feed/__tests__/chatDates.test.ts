import { describe, expect, it } from 'vitest';
import { chatToText, dayBounds, firstMessageOnOrAfter } from '../chatDates';
import type { Message } from '../../../../types/messenger';

const ANN = 'Ann';
const mk = (id: string, sentAt: string, text = id, over: Partial<Message> = {}): Message =>
  ({ id, sentAt, text, timestamp: '', senderId: 'a', senderName: ANN, senderAvatar: '', type: 'text', ...over }) as Message;

const msgs = [mk('a', '2026-10-01T12:00:00'), mk('b', '2026-10-05T12:00:00'), mk('c', '2026-10-09T12:00:00')];

describe('chatDates', () => {
  it('finds the first message on or after a day', () => {
    expect(firstMessageOnOrAfter(msgs, '2026-10-05')?.id).toBe('b');
    expect(firstMessageOnOrAfter(msgs, '2026-10-06')?.id).toBe('c');
    expect(firstMessageOnOrAfter(msgs, '2026-10-30')).toBeUndefined();
  });
  it('bounds', () => {
    expect(dayBounds(msgs)).toEqual({ min: '2026-10-01', max: '2026-10-09' });
    expect(dayBounds([])).toBeNull();
  });
  it('exports text lines', () => {
    const t = chatToText('Ann', [mk('a', '2026-10-01T12:00:00', 'hi'), mk('p', '2026-10-01T12:01:00', '', { type: 'image' })], 'media');
    expect(t).toContain('Ann: hi');
    expect(t).toContain('[media: image]');
  });
});
