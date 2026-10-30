/**
 * Англійська в живому інтерфейсі месенджера (гілка cloud/r18-en).
 *
 * Суддя хакатону ставить English — і не має бачити кирилиці у стрічці розмов,
 * у полі питання й у композері. Тест рендерить три головні компоненти з
 * локаллю `en` і шукає кирилицю в тексті, у `placeholder`, `aria-label` і
 * `title`. Без англійського каталогу (підміна на uk) він червоний: другий
 * тест це доводить, рендеривши те саме з локаллю `uk`.
 *
 * Дані людей (імена, листи) у тесті латинські, щоб кирилиця могла прийти
 * лише з рядків інтерфейсу.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import { cleanup, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const CYRILLIC = /[А-Яа-яІіЇїЄєҐґ]/;

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => {
  cleanup();
  setLocale('uk');
});

const USER = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'hi', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const composerProps = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'chat_en',
};

/** Видимий текст + атрибути, які читає людина або екранний диктор. */
const humanText = (root: ParentNode): string => {
  const parts: string[] = [(root as HTMLElement).textContent ?? ''];
  root.querySelectorAll('*').forEach((el) => {
    for (const a of ['placeholder', 'aria-label', 'title', 'alt']) {
      const v = el.getAttribute(a);
      if (v) parts.push(v);
    }
  });
  return parts.join('\n');
};

const renderAll = (): string => {
  useMessengerStore.setState({ activeCircle: 'all' });
  const out: string[] = [];
  const list = render(
    <PhoneChatList
      chats={[
        chat('c1', 'Alice', { unreadCount: 2 }),
        chat('c2', 'Yard', { type: 'group' }),
        chat('chat_saved_notes', 'Saved', { type: 'saved', pinned: true }),
      ]}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      ask
    />,
  );
  out.push(humanText(list.container));
  list.unmount();
  const composer = render(<MessageComposer {...(composerProps as unknown as ComponentProps<typeof MessageComposer>)} />);
  out.push(humanText(composer.container));
  composer.unmount();
  return out.join('\n');
};

describe('англійська мова месенджера', () => {
  it('з локаллю uk інтерфейс українською (контроль: тест уміє червоніти)', () => {
    setLocale('uk');
    expect(CYRILLIC.test(renderAll())).toBe(true);
  });

  it('з локаллю en у стрічці розмов, полі питання й композері немає кирилиці', () => {
    setLocale('en');
    const text = renderAll();
    const lines = text.split('\n').filter((l) => CYRILLIC.test(l));
    expect(lines).toEqual([]);
    expect(text).toMatch(/Search or ask Nightpost/);
  });
});
