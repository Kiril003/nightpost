/**
 * Модель стрічки (`feed/feedModel.ts`): групування, роздільник дня і —
 * головне для швидкості — ТОТОЖНІСТЬ рядків між рендерами.
 *
 * `MessageRow` — `React.memo`. Якщо вхідний лист народжує нові обʼєкти для
 * всіх рядків, memo не спрацьовує ніколи, і лист знову коштує О(вся розмова).
 * Тут доводиться, що новий лист міняє рівно ті рядки, чиє сусідство змінилось.
 */
import { describe, it, expect } from 'vitest';
import { buildFeedRows, reconcileRows } from '../components/messenger/feed/feedModel';
import type { Message } from '../types/messenger';

const msg = (i: number, sender: 'me' | 'peer', extra: Partial<Message> = {}): Message =>
  ({
    id: `m${i}`,
    senderId: sender,
    senderName: sender === 'me' ? 'Я' : 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'text',
    isSelf: sender === 'me',
    text: `лист ${i}`,
    ...extra,
  }) as Message;

const talk = (n: number) => Array.from({ length: n }, (_, i) => msg(i, i % 3 === 0 ? 'me' : 'peer'));

describe('рядки стрічки', () => {
  it('черга відправника: перший і останній у черзі, контур бульбашки', () => {
    const rows = buildFeedRows([msg(0, 'peer'), msg(1, 'peer'), msg(2, 'peer'), msg(3, 'me')], 'me');
    expect(rows.map((r) => [r.isFirstInGroup, r.isLastInGroup])).toEqual([
      [true, false],
      [false, false],
      [false, true],
      [true, true],
    ]);
    expect(rows[1].contourClass).toBe('bubble-contour-other-middle');
    expect(rows[3].contourClass).toBe('bubble-contour-self-single');
  });

  it('новий день рве чергу і ставить роздільник рівно один раз', () => {
    const today = new Date('2026-10-01T12:00:00Z');
    const rows = buildFeedRows(
      [
        msg(0, 'peer', { sentAt: '2026-09-30T10:00:00Z' }),
        msg(1, 'peer', { sentAt: '2026-10-01T09:00:00Z' }),
        msg(2, 'peer', { sentAt: '2026-10-01T09:01:00Z' }),
      ],
      'me',
      today,
    );
    expect(rows.map((r) => r.showDateDivider)).toEqual([true, true, false]);
    expect(rows[0].isLastInGroup).toBe(true);
    expect(rows[1].isFirstInGroup).toBe(true);
  });

  it('пачка медіа — один рядок на першому елементі, з усіма елементами по порядку', () => {
    const batch = (i: number, index: number) =>
      msg(i, 'me', { type: 'image', mediaBatch: { id: 'b1', index, total: 3 } } as Partial<Message>);
    const rows = buildFeedRows([batch(0, 0), batch(2, 2), batch(1, 1)], 'me');
    expect(rows).toHaveLength(1);
    expect(rows[0].batchItems?.map((m) => m.id)).toEqual(['m0', 'm1', 'm2']);
  });

  it('лічильник гілки рахує відповіді на лист', () => {
    const rows = buildFeedRows(
      [msg(0, 'peer'), msg(1, 'me', { replyTo: { id: 'm0', senderName: 'Оксана', text: 'x' } } as Partial<Message>)],
      'me',
    );
    expect(rows[0].threadCount).toBe(1);
  });
});

describe('тотожність рядків — на ній тримається memo', () => {
  it('нічого не змінилось — той самий масив', () => {
    const messages = talk(50);
    const a = reconcileRows(null, buildFeedRows(messages, 'me'));
    const b = reconcileRows(a, buildFeedRows([...messages], 'me'));
    expect(b).toBe(a);
  });

  it('вхідний лист у розмові на 600: новими стають лише новий рядок і його сусід', () => {
    const messages = talk(600);
    const before = reconcileRows(null, buildFeedRows(messages, 'me'));
    const after = reconcileRows(before, buildFeedRows([...messages, msg(600, 'peer')], 'me'));
    const fresh = after.filter((r, i) => r !== before[i]);
    // Сусід міняється, лише якщо новий лист продовжив його чергу (хвіст зсунувся).
    expect(fresh.length).toBeLessThanOrEqual(2);
    expect(fresh[fresh.length - 1].key).toBe('m600');
  });

  it('правка одного листа будить лише його рядок', () => {
    const messages = talk(100);
    const before = reconcileRows(null, buildFeedRows(messages, 'me'));
    const edited = messages.map((m) => (m.id === 'm40' ? { ...m, text: 'виправлено', isEdited: true } : m));
    const after = reconcileRows(before, buildFeedRows(edited, 'me'));
    expect(after.filter((r, i) => r !== before[i]).map((r) => r.key)).toEqual(['m40']);
  });
});
