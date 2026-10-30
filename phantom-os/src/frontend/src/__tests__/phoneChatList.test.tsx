/**
 * Список розмов телефона (форма 24.09): «Збережене» — рядок, а не фільтр.
 *
 * Дефект, який тут стережемо: дотик по фільтру «Збережене» ставив
 * `activeCircle = 'saved'`, а скинути його не міг жоден елемент — решта
 * розмов зникала до перезапуску. Список телефона коло не читає: навіть із
 * застряглим станом у сторі видно все.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { kindLine, displayTitle } from '../components/messenger/chatListFormat';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('c1', 'Оксана', { unreadCount: 2 }),
  chat('chat_saved_notes', 'Збережене', { type: 'saved', pinned: true, lastKind: 'video-note', lastSnippet: 'video_note_1790093784084.webm', lastAt: '2020-01-01T00:00:00Z' }),
  chat('c2', 'Двір', { type: 'group' }),
];

const renderList = () =>
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
     
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

describe('список розмов телефона', () => {
  beforeEach(() => useMessengerStore.setState({ activeCircle: 'saved' }));

  it('застрягле коло «saved» у сторі не ховає розмов', () => {
    renderList();
    const rows = document.querySelectorAll('[data-chat-row]');
    expect(rows).toHaveLength(3);
  });

  it('«Збережене» — перший рядок, навіть найстаріший', () => {
    renderList();
    const first = document.querySelector('[data-chat-row]') as HTMLElement;
    expect(first.getAttribute('data-chat-row')).toBe('chat_saved_notes');
    // Імʼя файлу людині не показуємо — лише слово.
    expect(within(first).queryByText(/webm/)).toBeNull();
    expect(within(first).getByText(/Відеокружок/)).toBeTruthy();
  });

  it('шапка «Розмови»: поле пошуку і «Нова розмова»; до шести розмов смуги тек немає; нижньої панелі немає', () => {
    renderList();
    expect(document.querySelector('[data-pane-tab="chats"]')!.textContent).toBe('Чати');
    expect(screen.getByRole('searchbox', { name: 'Пошук у розмовах' }).closest('[data-kit="ask-field"]')!.querySelector('svg')).toBeTruthy();
    expect(screen.getByLabelText('Створити новий діалог')).toBeTruthy();
    expect(document.querySelector('[data-folder-strip]')).toBeNull();
    for (const pill of ['Кімнати', 'Особисті', 'Канали']) expect(screen.queryByText(pill)).toBeNull();
    expect(document.querySelector('nav')).toBeNull();
  });

  it('непрочитане — лічильник 22 (`h-[22px] min-w-[22px]`), ім\'я 16/600 (D)', () => {
    renderList();
    const row = document.querySelector('[data-chat-row="c1"]') as HTMLElement;
    expect(within(row).getByText('2').className).toMatch(/h-\[22px\] min-w-\[22px\]/);
    expect(within(row).getByText('Оксана').className).toContain('text-headline');
    expect(within(row).getByText('Оксана').className).toContain('font-semibold');
  });

  it('непрочитане пряме — час ink-2, бурштин лише в лічильнику; прочитане — час ink-3; превʼю text-2', () => {
    renderList();
    const q = (id: string, sel: string) => document.querySelector(`[data-chat-row="${id}"] ${sel}`) as HTMLElement;
    expect(q('c1', '[data-row-time]').className).toContain('text-ink-2');
    expect(q('c1', '[data-row-time]').className).not.toContain('accent');
    expect(q('c2', '[data-row-time]').className).toContain('text-ink-3');
    expect(q('c1', '[data-row-time]').className).toContain('tnum');
    expect(q('c1', '[data-testid="chat-preview-line"]').className).toContain('text-ink-2');
    expect(q('c2', '[data-testid="chat-preview-line"]').className).toContain('text-ink-2');
  });

  it('закріплені — окремою секцією без підпису (їх ≤ 3), решта — після неї', () => {
    renderList();
    const pinned = document.querySelector('[data-list-section="pinned"]') as HTMLElement;
    expect(within(pinned).queryByText('Закріплені')).toBeNull();
    expect(pinned.querySelector('[data-chat-row="chat_saved_notes"]')).toBeTruthy();
    const rest = document.querySelector('[data-list-section="rest"]') as HTMLElement;
    expect(rest.querySelectorAll('[data-chat-row]')).toHaveLength(2);
  });

  it('у групі автор прев\'ю — text-1 500 без тону людини (D)', () => {
    render(
      <PhoneChatList
        chats={[
          chat('g', 'Двір', { type: 'group', lastAuthor: 'Петро Іванів', lastSnippet: 'Збори о шостій' }),
          chat('g2', 'Сімʼя', { type: 'group', lastAuthor: 'me', lastSnippet: 'Буду' }),
        ]}
        currentUser={USER}
       
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    const petro = screen.getByText('Петро Іванів:', { exact: false });
    expect(petro.getAttribute('style')).toBeNull();
    expect(petro.className).toContain('font-medium');
    expect(petro.className).toContain('text-ink');
    const self = document.querySelector('[data-chat-row="g2"] [data-testid="chat-preview-line"]') as HTMLElement;
    expect(self.querySelector('[style]')).toBeNull();
  });
});

describe('слова превʼю', () => {
  it('відеокружок і голосове — з тривалістю, коли вона відома', () => {
    expect(kindLine('video-note', { videoNoteData: { duration: 1 } } as never)).toBe('Відеокружок · 0:01');
    expect(kindLine('voice', { voiceData: { duration: 12, waveform: [] } } as never)).toBe('Голосове · 0:12');
    expect(kindLine('voice', null, null)).toBe('Голосове');
    expect(kindLine('image')).toBe('Фото');
    expect(kindLine('text')).toBeNull();
  });

  it('декор-емодзі вузла зникає з назви', () => {
    expect(displayTitle('Nightpost Symbiote ⚡')).toBe('Nightpost Symbiote');
  });

  it('ні відбитка, ні хешу в назві — лише людське', () => {
    expect(displayTitle('Ноутбук · 9b10·41af')).toBe('Ноутбук');
    expect(displayTitle('Оксана')).toBe('Оксана');
    expect(displayTitle('9b1041af0c3e77d2')).toBe('Нова розмова');
  });
});
