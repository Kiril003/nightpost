import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import { chatFromNode, messengerApi, type NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { uk } from '../i18n/messages/uk';

const makeNode = (overrides: Partial<NodeConversation>): NodeConversation => ({
  id: 'dm1',
  title: 'Олена',
  kind: 'direct',
  circle: 'inner',
  handle: 'olena',
  avatar: '',
  pinned: false,
  archived: false,
  created_at: '2023-11-14T22:13:20Z',
  updated_at: '2023-11-14T22:15:00Z',
  ...overrides,
});

describe('clearToastInChat', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useUIStore.setState({ toasts: [] });
  });

  it('queues a cleared toast in useUIStore when clearing history succeeds', async () => {
    vi.spyOn(messengerApi, 'clearConversation').mockResolvedValue({ cleared: 3, blobs: 0 });
    const dm = chatFromNode(makeNode({ id: 'dm1', title: 'Олена', kind: 'direct' }));
    useMessengerStore.setState({ chats: [dm], activeChatId: dm.id });
    const onClose = vi.fn();

    render(
      <PhoneChatMenu
        chat={dm}
        onClose={onClose}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Очистити історію'));
    fireEvent.click(screen.getByRole('button', { name: uk['phoneChatMenu.clear'] }));

    await waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });

    expect(useUIStore.getState().toasts.some((t) => t.message === uk['sidebar.cleared'])).toBe(true);
  });

  it('queues no toast and shows the failure note when clearConversation rejects', async () => {
    vi.spyOn(messengerApi, 'clearConversation').mockRejectedValue(new Error('failed'));
    const dm = chatFromNode(makeNode({ id: 'dm1', title: 'Олена', kind: 'direct' }));
    useMessengerStore.setState({ chats: [dm], activeChatId: dm.id });

    render(
      <PhoneChatMenu
        chat={dm}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Очистити історію'));
    fireEvent.click(screen.getByRole('button', { name: uk['phoneChatMenu.clear'] }));

    await screen.findByText(uk['phoneChatMenu.clearFailed']);
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });

  it('shows clearSentenceGroup in the clear confirmation for a group chat', () => {
    const group = chatFromNode(makeNode({ id: 'g1', title: 'Штаб', kind: 'group', handle: '' }));
    useMessengerStore.setState({ chats: [group], activeChatId: group.id });

    render(
      <PhoneChatMenu
        chat={group}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Очистити історію'));
    expect(screen.getByText(uk['chatActionsSheet.clearSentenceGroup'])).toBeInTheDocument();
  });
});
