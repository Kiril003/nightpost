/**
 * ЧС-03 у меню довгого дотику: прочитана розмова пропонує «Позначити
 * непрочитаним», позначена — «Позначити прочитаним», і той рядок знімає крапку,
 * не смикаючи вузол, коли непрочитаних листів насправді немає.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { chatMarkUnread } from '../services/chatMarkUnread';
import type { Chat } from '../types/messenger';

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({
    id: 'c1', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const sheet = (c: Chat, onMarkRead = vi.fn(), onClose = vi.fn()) =>
  render(
    <ChatActionsSheet
      chat={c}
      onClose={onClose}
      onMarkRead={onMarkRead}
      onRename={vi.fn()}
      onClear={vi.fn()}
      onDelete={vi.fn()}
    />,
  );

describe('меню розмови: позначити непрочитаним', () => {
  beforeEach(() => chatMarkUnread.reset());
  afterEach(cleanup);

  it('прочитана розмова ставить крапку і закриває аркуш', () => {
    const onClose = vi.fn();
    const { getByText } = sheet(chat(), vi.fn(), onClose);
    fireEvent.click(getByText('Позначити непрочитаним'));
    expect(chatMarkUnread.has('c1')).toBe(true);
    expect(onClose).toHaveBeenCalled();
  });

  it('позначена розмова пропонує зняти крапку — без звернення до вузла', () => {
    chatMarkUnread.mark('c1');
    const onMarkRead = vi.fn();
    const { getByText, queryByText } = sheet(chat(), onMarkRead);
    expect(queryByText('Позначити непрочитаним')).toBeNull();
    fireEvent.click(getByText('Позначити прочитаним'));
    expect(chatMarkUnread.has('c1')).toBe(false);
    expect(onMarkRead).not.toHaveBeenCalled();
  });

  it('справжні непрочитані листи читаються через вузол, як і раніше', () => {
    const onMarkRead = vi.fn();
    const { getByText } = sheet(chat({ unreadCount: 3 }), onMarkRead);
    fireEvent.click(getByText('Позначити прочитаним'));
    expect(onMarkRead).toHaveBeenCalledTimes(1);
  });
});
