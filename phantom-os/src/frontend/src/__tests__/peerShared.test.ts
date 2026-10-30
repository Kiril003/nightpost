import { describe, it, expect } from 'vitest';
import { indexShared, linksIn, heardLine } from '../services/peerShared';
import type { Message } from '../types/messenger';

const media = { name: 'a.jpg', size: 10, mime: 'image/jpeg', sha256: 'x', blobId: 'b1', keyHex: 'k', nonceHex: 'n' };

const msg = (over: Partial<Message>): Message => ({
  id: over.id ?? Math.random().toString(36).slice(2),
  senderId: 'peer',
  senderName: 'Марта',
  senderAvatar: '',
  timestamp: '2026-09-25T10:00:00Z',
  type: 'text',
  ...over,
});

describe('спільне з людиною', () => {
  it('розкладає стрічку по вкладках, найсвіжіше першим', () => {
    const idx = indexShared(
      [
        msg({ id: 'p1', type: 'image', media }),
        msg({ id: 'f1', type: 'file', media: { ...media, name: 'звіт.pdf', mime: 'application/pdf' } }),
        msg({ id: 'v1', type: 'voice' }),
        msg({ id: 'g1', type: 'geo:point', geoPoint: { lat: 50.45, lon: 30.52, atMs: 1 } }),
        msg({ id: 't1', text: 'дивись https://phantom-os.dev/download, і ще https://www.example.org/a?b=1.' }),
        msg({ id: 'p2', type: 'image', media, isSelf: true }),
      ],
      'me',
    );
    expect(idx.media.map((m) => m.id)).toEqual(['p2', 'p1']);
    expect(idx.files.map((m) => m.id)).toEqual(['f1']);
    expect(idx.voice.map((m) => m.id)).toEqual(['v1']);
    expect(idx.places).toHaveLength(1);
    expect(idx.places[0]).toMatchObject({ msgId: 'g1', label: 'Точка на мапі' });
    expect(idx.links.map((l) => l.url)).toEqual(['https://phantom-os.dev/download', 'https://www.example.org/a?b=1']);
    expect(idx.links[1].host).toBe('example.org');
  });

  it('стерте й те, що не поїхало, — не спільне', () => {
    const idx = indexShared(
      [msg({ type: 'image', media, isDeleted: true }), msg({ type: 'image', media, status: 'failed' })],
      'me',
    );
    expect(idx.media).toHaveLength(0);
  });

  it('javascript: і голий текст — не посилання', () => {
    expect(linksIn('javascript:alert(1) і www.x.com')).toEqual([]);
    expect(linksIn('(https://a.ua)')).toEqual(['https://a.ua']);
  });

  it('те саме посилання двічі — один рядок', () => {
    const idx = indexShared([msg({ text: 'https://a.ua' }), msg({ text: 'ще раз https://a.ua' })], 'me');
    expect(idx.links).toHaveLength(1);
  });
});

describe('коли людину чули', () => {
  const now = Date.parse('2026-09-25T12:00:00Z');
  it('немає кадрів — мовчимо, а не вигадуємо «онлайн»', () => {
    expect(heardLine(null, null, now)).toBeNull();
  });
  it('поруч у мережі переважає', () => {
    expect(heardLine('2026-09-20T12:00:00Z', '2026-09-25T11:58:00Z', now)).toBe('поруч');
  });
  it('хвилини, години, дні', () => {
    expect(heardLine('2026-09-25T11:45:00Z', null, now)).toBe('на звʼязку 15 хв тому');
    expect(heardLine('2026-09-25T09:00:00Z', null, now)).toBe('на звʼязку 3 год тому');
    expect(heardLine('2026-09-22T12:00:00Z', null, now)).toBe('на звʼязку 3 дн тому');
  });
});
