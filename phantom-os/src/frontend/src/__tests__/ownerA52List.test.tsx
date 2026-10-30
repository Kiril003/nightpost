/**
 * Відгук власника з A52 (02.10) на список розмов — кожен пункт як вимір.
 *
 * 1. «Ви: Цей застосунок ще не вміє показати цей …» — службові листи не стають
 *    превʼю; береться останній людський лист, а немає — рядка немає.
 * 2. «ПК-вузол», «ПК · PHANTOM OS», «Ноутбук · 6 вер., 02:48»,
 *    «E2E-reply-from-PC» — це пристрої/тести: один рядок «Мої пристрої»,
 *    дата ніколи не в імені.
 * 3. Аватар угорі ліворуч відкриває Профіль через рідну оболонку.
 * 4. Жодних вигаданих розділів («Важливе», «Тихі групи»), фільтри не навʼязані.
 *
 * Червоніти вміє: приберіть фільтр serviceNotice з превʼю, `looksLikeDevice`
 * з peopleIdentity, `navigateTo('profile')` зі шапки — падає відповідний тест.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { displayTitle } from '../components/messenger/chatListFormat';
import { isHiddenMessage, lastHumanMessage } from '../services/serviceNotice';
import { looksLikeDevice } from '../services/peopleIdentity';
import type { Chat, Message, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra }) as unknown as Chat;
const msg = (id: string, text: string, extra: Partial<Message> = {}): Message =>
  ({ id, type: 'text', text, isSelf: false, senderName: 'Оксана', timestamp: '00:24', ...extra }) as Message;
const NOTICE = 'Цей застосунок ще не вміє показати цей вміст (solana:swap).';

const renderList = (chats: Chat[]) =>
  render(<PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('1. службові листи', () => {
  it('речення рідного боку і керівний кадр — приховані; лист людини — ні', () => {
    expect(isHiddenMessage(msg('a', NOTICE))).toBe(true);
    expect(isHiddenMessage(msg('b', 'Картка «Таблиця» приїхала без вмісту — показувати нічого.'))).toBe(true);
    expect(isHiddenMessage(msg('c', 'Дякую!'))).toBe(false);
    expect(lastHumanMessage([msg('h', 'Буду о сьомій'), msg('s', NOTICE)])?.id).toBe('h');
  });

  it('прев\'ю бере останній людський лист замість «Цей застосунок ще не вміє…»', () => {
    renderList([
      chat('o', 'Оксана', {
        lastSnippet: NOTICE,
        lastAuthor: 'me',
        messages: [msg('h', 'Буду о сьомій'), msg('s', NOTICE, { isSelf: true })],
      }),
    ]);
    const line = document.querySelector('[data-chat-row="o"] [data-testid="chat-preview-line"]') as HTMLElement;
    expect(line.textContent).toBe('Буду о сьомій');
    expect(document.body.textContent).not.toContain('ще не вміє');
  });

  it('людського листа немає — рядка прев\'ю немає зовсім, і жодного «Ви:»', () => {
    renderList([chat('t', 'Тарас', { lastSnippet: NOTICE, lastAuthor: 'me' })]);
    expect(document.querySelector('[data-chat-row="t"] [data-testid="chat-preview-line"]')).toBeNull();
    expect(document.body.textContent).not.toContain('Ви:');
  });
});

describe('2. мої пристрої — не люди', () => {
  it('назви пристроїв і тестів упізнаються; імена людей — ні', () => {
    for (const t of ['ПК-вузол', 'ПК · Nightpost', 'Ноутбук · 6 вер., 02:48', 'E2E-reply-from-PC', 'S4-node-reply-to-S4phone'])
      expect(looksLikeDevice(t), t).toBe(true);
    for (const t of ['Оксана', 'Пкаленко Ігор', 'Мама', 'Двір на Костянтинівській']) expect(looksLikeDevice(t), t).toBe(false);
  });

  it('дата, приклеєна до імені, відрізається', () => {
    expect(displayTitle('Ноутбук · 6 вер., 02:48')).toBe('Ноутбук');
    expect(displayTitle('Новий контакт · 27 вер., 16:04')).toBe('Без імені');
  });

  it('пристрої зібрано в один рядок «Мої пристрої», серед людей їх немає', () => {
    renderList([chat('o', 'Оксана'), chat('pc', 'ПК-вузол'), chat('nb', 'Ноутбук · 6 вер., 02:48'), chat('e2e', 'E2E-reply-from-PC')]);
    const rows = [...document.querySelectorAll('[data-list-section] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(rows).toEqual(['o']);
    const folder = document.querySelector('[data-folder-row="devices"]') as HTMLElement;
    expect(within(folder).getByText('Мої пристрої')).toBeTruthy();
    expect(folder.textContent).not.toMatch(/\d{2}:\d{2}/);
  });
});

describe('3. профіль — аватар 36 у шапці «Сьогодні» (np 2.1, замінює рішення 02.10)', () => {
  it('на телефоні аватара в шапці немає: «Я» — вкладка нижньої панелі (L6)', () => {
    renderList([chat('o', 'Оксана')]);
    expect(screen.queryByLabelText('Налаштування')).toBeNull();
    expect(screen.queryByLabelText('Я')).toBeNull();
  });

  it('на ПК у списку аватара себе немає — «Я» живе в слоті острова (17.10)', () => {
    render(<PhoneChatList desk chats={[chat('o', 'Оксана')]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);
    expect(screen.queryByLabelText('Я')).toBeNull();
  });
});

describe('4. без вигаданих розділів', () => {
  it('ні «Важливе», ні «Тихі групи», ні сегментів за замовчуванням; Збережене закріплене, Nightpost за часом без простроченого', () => {
    renderList([
      chat('g', 'Двір', { type: 'group', unreadCount: 3, letters30d: 40, myLetters30d: 0 } as Partial<Chat>),
      chat('o', 'Оксана', { unreadCount: 1 }),
      chat('chat_saved_notes', 'Збережене', { type: 'saved' }),
      chat('phantom', 'Nightpost', { type: 'phantom' }),
      chat('x1', 'Іра'),
      chat('x2', 'Тарас'),
    ]);
    expect(screen.queryByText('Важливе')).toBeNull();
    expect(document.querySelector('[data-quiet-groups]')).toBeNull();
    const tabs = [...document.querySelectorAll('[data-filter-tab]')].map((t) => t.getAttribute('data-filter-tab'));
    expect(tabs).toEqual(['all', 'unread', 'close', 'friends', 'group']);
    const pinned = [...document.querySelectorAll('[data-list-section="pinned"] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(pinned).toEqual(['chat_saved_notes']);
    expect(document.querySelector('[data-list-section="rest"] [data-chat-row="phantom"]')).toBeTruthy();
  });

  it('архів — один рядок «Архів» унизу «Усі» (10 §2), не вкладка', () => {
    renderList([chat('o', 'Оксана'), chat('old', 'Старий чат', { archived: true }), chat('x1', 'Іра'), chat('x2', 'Тарас'), chat('x3', 'Влад'), chat('x4', 'Софія')]);
    expect(document.querySelector('[data-filter-tab="archive"]')).toBeNull();
    const folder = document.querySelector('[data-folder-row="archive"]') as HTMLElement;
    expect(within(folder).getByText('Архів')).toBeTruthy();
    fireEvent.click(folder.querySelector('button') ?? folder);
    const rows = [...document.querySelectorAll('[data-phantom-list] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(rows).toEqual(['old']);
  });
});
