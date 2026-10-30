import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { uk, type MessageKey } from '../i18n/messages/uk';
import type { Chat } from '../types/messenger';

const t = (key: MessageKey): string => uk[key];

const makeChat = (over: Partial<Chat> = {}): Chat => ({
  id: 'c1',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  unreadCount: 0,
  messages: [],
  ...over,
});

afterEach(() => {
  cleanup();
});

describe('ChatActionsSheet back navigation', () => {
  it('returns to the menu from rename, clear, and delete via the FormSheet back button', () => {
    const onClose = vi.fn();
    const onMarkRead = vi.fn();
    const onRename = vi.fn();
    const onClear = vi.fn();
    const onDelete = vi.fn();

    render(
      <ChatActionsSheet
        chat={makeChat()}
        onClose={onClose}
        onMarkRead={onMarkRead}
        onRename={onRename}
        onClear={onClear}
        onDelete={onDelete}
      />,
    );

    const steps: readonly MessageKey[] = [
      'chatActionsSheet.rename',
      'chatActionsSheet.clearHistory',
      'chatActionsSheet.deleteChat',
    ];

    for (const stepKey of steps) {
      expect(screen.queryByLabelText(t('formSheet.back'))).toBeNull();
      fireEvent.click(screen.getByText(t(stepKey)));

      const backBtn = screen.getByLabelText(t('formSheet.back'));
      fireEvent.click(backBtn);

      expect(screen.getByText(t('chatActionsSheet.rename'))).toBeTruthy();
      expect(screen.getByText(t('chatActionsSheet.clearHistory'))).toBeTruthy();
      expect(screen.getByText(t('chatActionsSheet.deleteChat'))).toBeTruthy();
    }

    expect(onRename).not.toHaveBeenCalled();
    expect(onClear).not.toHaveBeenCalled();
    expect(onDelete).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
