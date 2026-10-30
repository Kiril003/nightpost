import { describe, expect, it } from 'vitest';
import { messageFromNode } from '../messengerApi';

// 25.09: у розмові з PHANTOM голосове й кружок малювались файлами.
const row = (kind: string, name: string) => ({
  id: 'm1',
  author_id: 'me',
  author_name: 'Ви',
  sent_at: '2026-09-24T18:03:00',
  kind,
  body: JSON.stringify({ blob_id: 'b', key_hex: 'aa', name, size: 1000, duration: 4 }),
}) as never;

describe('рід вкладення з імені, коли міст шле file', () => {
  it('voice_*.webm стає голосовим', () => {
    const m = messageFromNode(row('file', 'voice_1790276605513.webm'), 'me');
    expect(m.type).toBe('voice');
    expect(m.voiceData?.duration).toBe(4);
  });
  it('video_note_*.webm стає кружком', () => {
    const m = messageFromNode(row('file', 'video_note_1790276581339.webm'), 'me');
    expect(m.type).toBe('video-note');
  });
  it('звичайний файл лишається файлом', () => {
    expect(messageFromNode(row('file', 'report.pdf'), 'me').type).toBe('file');
  });
});
