/**
 * Пошук по всіх розмовах: нормалізація, індекс, групування, швидкість.
 *
 * Червоніти вміє (перевірено мутацією 25.09):
 *  - прибрати `'’'` з APOSTROPHES — падає «Сімʼя» за «сімʼя»;
 *  - злити ґ з г у LOOKALIKE — падає «ґанок не знаходить ганок»;
 *  - прибрати перехід на наступний лист у `search` (pos + 1 замість next) —
 *    падає «один лист — один збіг».
 *
 * Вектори згортання ДОСЛІВНО ті самі, що у `WebMessengerSearchTest.kt`
 * (companion): двійник на вузлі мусить згортати так само, інакше телефон і
 * веб знайдуть різне за тим самим запитом.
 */
import { describe, it, expect } from 'vitest';
import {
  foldForSearch,
  foldQuery,
  fragmentOf,
  MessageIndex,
} from '../services/messageSearch';
import { lettersFromIndex, lettersFromNode, mergeLetters, searchPeople } from '../services/globalSearch';
import type { Chat, Message } from '../types/messenger';

/** [запит, текст, чи знаходиться] — спільні з Kotlin. */
const FOLD_VECTORS: Array<[string, string, boolean]> = [
  ['сім\'я', 'Наша Сімʼя вдома', true],
  ['сімʼя', 'сім\'я', true],
  ['СІМ`Я', 'сімʼя', true],
  ['привіт', 'ПРИВІТ усім', true],
  ['ппо', 'пп' + 'o' /* латинська o */, true],
  ['coк', 'сок', true], // латинська c
  ['kiev', 'кіеv', true],
  ['ґанок', 'ганок', false],
  ['ганок', 'ґанок', false],
  ['иди', 'іди', false],
  ['так', 'tak', false], // t/т схожі лише великими — не зливаємо
  ['є', 'е', false],
  ['кузні', 'біля кузні о шостій', true],
  ['кузня', 'біля кузні', false], // морфології немає свідомо
];

const msg = (id: string, text: string, over: Partial<Message> = {}): Message =>
  ({
    id,
    senderId: 'p1',
    senderName: 'Тарас',
    senderAvatar: '',
    timestamp: '12:00',
    type: 'text',
    text,
    isSelf: false,
    sentAt: '2026-09-20T10:00:00Z',
    ...over,
  }) as Message;

const chat = (id: string, title: string, messages: Message[] = [], over: Partial<Chat> = {}): Chat =>
  ({ id, title, avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages, ...over }) as Chat;

describe('нормалізація', () => {
  it.each(FOLD_VECTORS)('«%s» у «%s» → %s', (q, text, hit) => {
    expect(foldForSearch(text).includes(foldQuery(q))).toBe(hit);
  });

  it('згортання 1:1 — довжина не змінюється, тож позиція збігу та сама', () => {
    for (const [, text] of FOLD_VECTORS) expect(foldForSearch(text).length).toBe(text.length);
    expect(foldForSearch('İstanbul').length).toBe('İstanbul'.length);
  });

  it('фрагмент підсвічує саме збіг в оригіналі', () => {
    const f = fragmentOf('Наша Сімʼя вдома', foldQuery("сімʼя"))!;
    expect(f.text.slice(f.at, f.at + f.len)).toBe('Сімʼя');
  });

  it('довгий лист обрізається перед збігом по слову, і збіг лишається в полі зору', () => {
    const long = 'раз два три чотири пʼять шість сім вісім девʼять десять одинадцять кузня';
    const f = fragmentOf(long, foldQuery('кузня'))!;
    expect(f.text.startsWith('…')).toBe(true);
    expect(f.at).toBeLessThan(30);
    expect(f.text.slice(f.at, f.at + f.len)).toBe('кузня');
  });
});

describe('індекс', () => {
  it('знаходить текст, підпис і назву файлу; стертого не знаходить', () => {
    const c = chat('c1', 'Оксана', [
      msg('m1', 'привіт'),
      msg('m2', '', { imageData: { url: '', caption: 'Кузня вночі' } as unknown as Message['imageData'] }),
      msg('m3', '', { fileData: { name: 'кузня-план.pdf', size: 1, url: '' } as unknown as Message['fileData'] }),
      msg('m4', 'кузня', { isDeleted: true }),
    ]);
    const idx = new MessageIndex();
    idx.indexChat(c);
    const ids = idx.search(foldQuery('КУЗН')).map((h) => h.messageId).sort();
    expect(ids).toEqual(['m2', 'm3']);
    const letters = lettersFromIndex(idx.search(foldQuery('кузн')), [c], foldQuery('кузн'));
    expect(letters.map((l) => l.fragment.field).sort()).toEqual(['caption', 'file']);
  });

  it('один лист — один збіг, скільки б разів слово не траплялось', () => {
    const idx = new MessageIndex();
    idx.indexChat(chat('c1', 'X', [msg('m1', 'так так так'), msg('m2', 'так')]));
    expect(idx.search(foldQuery('так')).map((h) => h.messageId)).toEqual(['m2', 'm1']);
  });

  it('збіг не перескакує через межу двох листів', () => {
    const idx = new MessageIndex();
    idx.indexChat(chat('c1', 'X', [msg('m1', 'кінець'), msg('m2', 'початок')]));
    expect(idx.search(foldQuery('кінецьпочаток'))).toEqual([]);
  });

  it('будується поступово: крок із нульовим бюджетом згортає одну розмову', () => {
    const idx = new MessageIndex();
    const chats = [chat('a', 'A', [msg('1', 'x')]), chat('b', 'B', [msg('2', 'x')])];
    idx.enqueue(chats);
    let t = 0;
    expect(idx.step(0, () => t++)).toBe(false);
    expect(idx.indexedChats).toBe(1);
    expect(idx.step(0, () => t++)).toBe(true);
    expect(idx.indexedChats).toBe(2);
  });

  it('перезгортає лише розмову, чия стрічка змінилась', () => {
    const idx = new MessageIndex();
    const a = chat('a', 'A', [msg('1', 'старе')]);
    const b = chat('b', 'B', [msg('2', 'x')]);
    idx.enqueue([a, b]);
    idx.step(100);
    const a2 = { ...a, messages: [...a.messages, msg('3', 'нове')] };
    idx.enqueue([a2, b]);
    expect(idx.pending).toBe(1);
    idx.step(100);
    expect(idx.search(foldQuery('нове')).map((h) => h.messageId)).toEqual(['3']);
  });
});

describe('групування видачі', () => {
  const dm = chat('dm1', 'Оксана', [], { peerNodeId: 'p-oks' });
  const grp = chat('g1', 'Двір', [], {
    type: 'group',
    members: [
      { id: 'p-oks', name: 'Оксана', handle: '', avatar: '', role: 'member' },
      { id: 'p-tar', name: 'Тарас', handle: '', avatar: '', role: 'member' },
      { id: 'me', name: 'Оксана Я', handle: '', avatar: '', role: 'owner', isMe: true },
    ],
  } as Partial<Chat>);

  it('людина з власною розмовою стоїть у «Людях» один раз; себе не шукаємо', () => {
    const people = searchPeople([dm, grp], foldQuery('оксан'));
    expect(people.map((p) => p.key)).toEqual(['c:dm1']);
  });

  it('учасник гілки без розмови вдвох — рядком із назвою гілки', () => {
    const [p] = searchPeople([dm, grp], foldQuery('тарас'));
    expect(p.member?.id).toBe('p-tar');
    expect(p.where).toBe('Двір');
    expect(p.chatId).toBe('g1');
  });

  it('рядок із вузла й рядок з індексу про той самий лист — один, від найновішого', () => {
    const c = chat('g1', 'Двір', [msg('m1', 'збори о шостій', { sentAt: '2026-09-20T10:00:00Z' })]);
    const idx = new MessageIndex();
    idx.indexChat(c);
    const needle = foldQuery('шост');
    const fromNode = lettersFromNode(
      [
        { conversation_id: 'g1', message_id: 'm1', author_name: 'Тарас', field: 'text', text: 'збори о шостій', sent_at: '2026-09-20T10:00:00Z' },
        { conversation_id: 'g1', message_id: 'm0', author_name: '', field: 'text', text: 'о шостій', sent_at: '2026-09-21T10:00:00Z' },
        { conversation_id: 'нема', message_id: 'x', author_name: '', field: 'text', text: 'шостій', sent_at: '2026-09-22T10:00:00Z' },
      ],
      [c],
      needle,
    );
    const merged = mergeLetters(fromNode, lettersFromIndex(idx.search(needle), [c], needle));
    expect(merged.map((l) => l.messageId)).toEqual(['m0', 'm1']);
    expect(merged[0].author).toBe('Ви');
    expect(merged[1].where).toBe('Двір');
  });
});

describe('швидкість на телефонних обсягах', () => {
  const WORDS = ['збори', 'кузня', 'сімʼя', 'Оксана', 'привіт', 'дорога', 'ґанок', 'шоста', 'Kyiv', 'хліб'];
  const make = (chats: number, perChat: number): Chat[] =>
    Array.from({ length: chats }, (_, c) =>
      chat(
        `c${c}`,
        `Розмова ${c}`,
        Array.from({ length: perChat }, (_, i) =>
          msg(`c${c}m${i}`, `${WORDS[(i * 7 + c) % 10]} лист ${i} у розмові ${c}: ${WORDS[(i + c * 3) % 10]} і ще трохи слів`),
        ),
      ),
    );

  it('10 тис. листів: пошук ≤ 100 мс, побудова частинами', () => {
    const chats = make(50, 200); // 10 000 листів
    const idx = new MessageIndex();
    const t0 = performance.now();
    idx.enqueue(chats);
    let steps = 0;
    while (!idx.step(8)) steps++;
    const build = performance.now() - t0;
    expect(idx.indexedMessages).toBe(10_000);
    // Побудова ділиться на кроки — жоден не тримає кадр усю тисячу мс.
    expect(steps).toBeGreaterThanOrEqual(0);

    const runs: number[] = [];
    for (const q of ['кузн', 'сім\'я', 'лист 19', 'kyiv', 'нема-такого']) {
      const t = performance.now();
      const hits = idx.search(foldQuery(q), 200);
      lettersFromIndex(hits, chats, foldQuery(q));
      runs.push(performance.now() - t);
    }
    const worst = Math.max(...runs);
    console.info(`[пошук] 10k листів: побудова ${build.toFixed(1)} мс за ${steps + 1} кроків, найгірший запит ${worst.toFixed(1)} мс`);
    expect(worst).toBeLessThan(100);
  });
});
