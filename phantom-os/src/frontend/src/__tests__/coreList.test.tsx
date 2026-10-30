/**
 * core-sweep, список розмов (C1 C2 C6 C11 C15 C21 C22): новий тестувальник
 * без людей одразу бачить «Додати людину»; до відповіді вузла — «Завантаження»,
 * після відмови — рядок помилки з «Повторити»; черга видна; без вигаданих імен.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { Sidebar } from '../components/messenger/Sidebar';
import { displayTitle } from '../components/messenger/chatListFormat';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, type ContactRequest } from '../services/messengerApi';
import { selfAdded, __resetSelfAdded } from '../services/selfAdded';
import { useRequestsStore } from '../stores/requestsStore';
import { LIST_FILTER_EVENT } from '../components/messenger/ChatTypeChips';
import { uk as l3uk, en as l3en } from '../i18n/messages/parts/l3';
import { cs as l3cs } from '../i18n/messages/parts/cs/l3';
import { uk as msgUk, en as msgEn } from '../i18n/messages/parts/msgV2';
import { cs as msgCs } from '../i18n/messages/parts/cs/msgV2';
import type { Chat, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const SAVED = chat('chat_saved_notes', 'Збережене', { type: 'saved', pinned: true });
const NODE = chat('chat_nightpost', 'Nightpost', { type: 'phantom', pinned: true });

const list = (chats: Chat[], over: Record<string, unknown> = {}) => {
  const onNewChat = vi.fn();
  render(
    <PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={onNewChat} onOpenChatActions={vi.fn()} {...over} />,
  );
  return { onNewChat };
};

beforeEach(() => {
  useMessengerStore.setState({ hydrated: true, hydrateFailed: false });
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('C1 перший запуск: лише «Збережене» й Nightpost', () => {
  it('видно обидва системні рядки і під ними «Додати людину»', () => {
    const { onNewChat } = list([SAVED, NODE]);
    expect(document.querySelector('[data-chat-row="chat_saved_notes"]')).toBeTruthy();
    expect(document.querySelector('[data-chat-row="chat_nightpost"]')).toBeTruthy();
    const empty = screen.getByTestId('list-empty');
    expect(within(empty).getByText('Розмов із людьми поки немає')).toBeTruthy();
    fireEvent.click(within(empty).getByRole('button', { name: 'Додати людину' }));
    expect(onNewChat).toHaveBeenCalledTimes(1);
  });

  it('застряглий зріз «Близькі» (з «Я») без людей не ховає «Збережене» й «Додати людину»', () => {
    list([SAVED, NODE]);
    act(() => {
      window.dispatchEvent(new CustomEvent(LIST_FILTER_EVENT, { detail: 'close' }));
    });
    expect(document.querySelector('[data-chat-row="chat_saved_notes"]')).toBeTruthy();
    expect(screen.getByTestId('list-empty')).toBeTruthy();
  });

  it('є хоч одна людина — порожнього стану нема', () => {
    list([SAVED, NODE, chat('c1', 'Оксана')]);
    expect(screen.queryByTestId('list-empty')).toBeNull();
  });
});

describe('U2 порожнє коло веде до вибору людини', () => {
  it('«Додати людину» у порожніх «Друзях» відкриває вибір з розмов, а вибір кладе людину в коло', async () => {
    const setChatCircle = vi.fn().mockResolvedValue(true);
    useMessengerStore.setState({ chats: [SAVED, chat('c1', 'Оксана')], setChatCircle } as never);
    const { onNewChat } = list([SAVED, chat('c1', 'Оксана')]);
    act(() => {
      window.dispatchEvent(new CustomEvent(LIST_FILTER_EVENT, { detail: 'friends' }));
    });
    fireEvent.click(within(screen.getByTestId('empty-kind')).getByRole('button'));
    expect(onNewChat).not.toHaveBeenCalled();
    fireEvent.click(document.querySelector('[data-circle-pick="c1"]') as HTMLElement);
    await waitFor(() => expect(setChatCircle).toHaveBeenCalledWith('c1', 'friends'));
  });

  it('«Додати людину» у виборі кола веде одразу до «Додати людину», а не на «Новий чат» з тим самим рядком', () => {
    useMessengerStore.setState({ chats: [SAVED, chat('c1', 'Оксана')], isCreateChatModalOpen: false, createChatStart: null } as never);
    const { onNewChat } = list([SAVED, chat('c1', 'Оксана')]);
    act(() => {
      window.dispatchEvent(new CustomEvent(LIST_FILTER_EVENT, { detail: 'friends' }));
    });
    fireEvent.click(within(screen.getByTestId('empty-kind')).getByRole('button'));
    fireEvent.click(within(screen.getByTestId('circle-pick-sheet')).getByRole('button', { name: 'Додати людину' }));
    expect(onNewChat).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().isCreateChatModalOpen).toBe(true);
    expect(useMessengerStore.getState().createChatStart).toBe('add');
    act(() => useMessengerStore.getState().setCreateChatModalOpen(true));
    expect(useMessengerStore.getState().createChatStart).toBeNull();
  });
});

describe('C2 холодний старт', () => {
  it('до відповіді вузла — «Завантаження», а не порожній стан', () => {
    useMessengerStore.setState({ hydrated: false, hydrateFailed: false });
    list([]);
    expect(screen.getByTestId('list-loading').textContent).toContain('Завантаження');
    expect(screen.queryByTestId('list-empty')).toBeNull();
  });

  it('вузол відмовив — рядок помилки, «Повторити» тягне список знову і рядок зникає', async () => {
    useMessengerStore.setState({ chats: [], hydrated: false, hydrateFailed: false });
    vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('down'));
    const boot = vi.spyOn(messengerApi, 'bootstrap').mockRejectedValueOnce(new Error('down'));
    await act(async () => {
      await useMessengerStore.getState().hydrateFromNode({ openFirst: false });
    });
    expect(useMessengerStore.getState().hydrateFailed).toBe(true);
    list([]);
    const row = screen.getByTestId('list-load-failed');
    expect(row.textContent).toContain('Розмови не завантажилися');
    // Не стверджуємо «нікого немає», коли не знаємо.
    expect(screen.queryByTestId('list-empty')).toBeNull();
    boot.mockResolvedValueOnce([]);
    await act(async () => {
      fireEvent.click(within(row).getByRole('button', { name: 'Повторити' }));
    });
    await waitFor(() => expect(useMessengerStore.getState().hydrated).toBe(true));
    expect(useMessengerStore.getState().hydrateFailed).toBe(false);
    expect(boot).toHaveBeenCalledTimes(2);
    expect(screen.queryByTestId('list-load-failed')).toBeNull();
  });

  it('список дотягнуло інше оновлення (мережа, живий рядок) — рядок помилки зникає', async () => {
    useMessengerStore.setState({ chats: [], hydrated: false, hydrateFailed: true });
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    list([]);
    expect(screen.getByTestId('list-load-failed')).toBeTruthy();
    await act(async () => {
      await useMessengerStore.getState().refreshConversations();
    });
    expect(useMessengerStore.getState().hydrateFailed).toBe(false);
    expect(screen.queryByTestId('list-load-failed')).toBeNull();
  });

  it('відмова і порожньо — без чипів над порожньою сторінкою', () => {
    useMessengerStore.setState({ hydrated: false, hydrateFailed: true });
    list([]);
    expect(screen.queryByRole('tab', { name: 'Близькі' })).toBeNull();
    expect(screen.queryByText('Близькі')).toBeNull();
  });
});

describe('C3 додав сам — не «Нові люди»', () => {
  beforeEach(() => __resetSelfAdded());
  const stranger = () => chat('dm-x', 'Марта', { lastSelfAt: null, unreadCount: 1 });

  it('позначка після першого кадру списку виносить людину з «Нових людей»', () => {
    list([SAVED, chat('c1', 'Оксана', { circle: 'friends' }), stranger()]);
    expect(document.querySelector('[data-folder-row="requests"]')).toBeTruthy();
    act(() => selfAdded.mark('dm-x'));
    expect(document.querySelector('[data-folder-row="requests"]')).toBeNull();
    expect(document.querySelector('[data-chat-row="dm-x"]')).toBeTruthy();
  });

  it('прийняв запит у Людях — розмова позначена як моя', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'acceptRequest').mockResolvedValue({ conversation_id: 'dm-acc' } as never);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    useRequestsStore.setState({ incoming: [{ id: 'rq1', direction: 'in' } as unknown as ContactRequest], outgoing: [] });
    useRequestsStore.getState().stage('rq1', 'accept');
    useRequestsStore.getState().flush();
    vi.useRealTimers();
    await waitFor(() => expect(selfAdded.has('dm-acc')).toBe(true));
  });

  it('«Написати» з «Нової розмови» (createDirectMessage) — позначено', async () => {
    vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({ id: 'dm-new', title: 'Ія', kind: 'dm', circle: 'all' } as never);
    await useMessengerStore.getState().createDirectMessage('Ія', 'all' as never);
    expect(selfAdded.has('dm-new')).toBe(true);
  });
});

describe('C6 черга', () => {
  it('список показує, скільки листів чекає', () => {
    list([SAVED, chat('c1', 'Оксана')], { queued: 3 });
    expect(screen.getByTestId('list-queued').textContent).toBe('У черзі: 3');
  });

  it('нуля не показує', () => {
    list([SAVED, chat('c1', 'Оксана')], { queued: 0 });
    expect(screen.queryByTestId('list-queued')).toBeNull();
  });

  it('досяжність: Sidebar питає міст і передає число в список', async () => {
    vi.spyOn(messengerApi, 'queueStatus').mockResolvedValue({ queued: 2 });
    render(<Sidebar chats={[SAVED, chat('c1', 'Оксана')]} activeChatId="" onSelectChat={vi.fn()} currentUser={USER} onNewChat={vi.fn()} />);
    expect((await screen.findByTestId('list-queued')).textContent).toBe('У черзі: 2');
  });
});

describe('C11 імʼя без літер', () => {
  it.each(['.', '—', '…', '🔥', ' . '])('«%s» → «Без імені»', (raw) => {
    expect(displayTitle(raw)).toBe('Без імені');
  });
  it('справжні імена не чіпає', () => {
    expect(displayTitle('Оля')).toBe('Оля');
    expect(displayTitle('R2')).toBe('R2');
    expect(displayTitle('Оля 🔥')).toBe('Оля');
  });
});

describe('C15 порожні стани списку — мітки, не речення', () => {
  const keys = ['phoneChatList.emptyTitle', 'phoneChatList.noGroups'] as const;
  const msgKeys = ['msgV2.noUnread', 'msgV2.noClose', 'msgV2.noFriends', 'msgV2.noRequests', 'msgV2.noArchive', 'msgV2.loading', 'msgV2.loadFailed'] as const;
  it.each([
    ['uk', l3uk, msgUk],
    ['en', l3en, msgEn],
    ['cs', l3cs, msgCs],
  ] as const)('%s: без крапки в кінці, без пояснювальних рядків', (_lang, l3, msg) => {
    for (const k of keys) expect((l3 as Record<string, string>)[k]).not.toMatch(/[.!]$/);
    for (const k of msgKeys) expect((msg as Record<string, string>)[k]).not.toMatch(/[.!]$/);
    expect('phoneChatList.emptyLine' in l3).toBe(false);
    expect('phoneChatList.noGroupsLine' in l3).toBe(false);
  });

  it('«Запити» списку не носять імені запитів у Людях', () => {
    expect(msgUk['msgV2.requests']).not.toBe('Запити');
    expect(msgEn['msgV2.requests']).not.toBe('Requests');
    expect(msgCs['msgV2.requests']).not.toBe('Žádosti');
  });
});

describe('C21 без градієнтів', () => {
  it('рейка чипів не тане маскою-градієнтом', () => {
    const css = readFileSync(join(process.cwd(), 'src/styles/messenger.css'), 'utf8');
    expect(css).not.toMatch(/mask-image\s*:\s*linear-gradient/);
    expect(css).not.toMatch(/strip-fade/);
  });
});

describe('C22 Nightpost у пошуку', () => {
  it('знак Nightpost, а не бурштинова літера «P»', async () => {
    list([SAVED, NODE, chat('c1', 'Оксана')]);
    fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
    fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: 'Nightp' } });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    const row = document.querySelector('[data-search-person]') as HTMLElement;
    expect(row).toBeTruthy();
    expect(row.querySelector('[data-phantom-mark]')).toBeTruthy();
    expect(row.querySelector('span.bg-accent-soft')).toBeNull();
  });
});
