import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import { uk, type MessageKey } from '../i18n/messages/uk';
import type { Chat } from '../types/messenger';

const t = (key: MessageKey): string => uk[key];

const makeChat = (over: Partial<Chat> = {}): Chat => ({
  id: 'c1',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  peerNodeId: 'peer-1',
  unreadCount: 0,
  messages: [],
  ...over,
});

afterEach(() => {
  cleanup();
});

describe('PhoneChatMenu calls door', () => {
  it('labels the row as app-wide and invokes onClose and onCallSettings', () => {
    const onClose = vi.fn();
    const onCallSettings = vi.fn();

    render(
      <PhoneChatMenu
        chat={makeChat()}
        onClose={onClose}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
        onCallSettings={onCallSettings}
      />,
    );

    const row = screen.getByText(t('callsHub.forAllChats'));
    expect(screen.queryByText(t('callSettings.title'))).toBeNull();

    fireEvent.click(row);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onCallSettings).toHaveBeenCalledTimes(1);
  });
});
