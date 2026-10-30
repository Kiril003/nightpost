import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Sidebar } from '../components/messenger/Sidebar';
import { chatFromNode, messengerApi, type NodeConversation } from '../services/messengerApi';
import { ApiError } from '../services/api';
import { useMessengerStore } from '../stores/messengerStore';
import { uk } from '../i18n/messages/uk';
import type { Chat } from '../types/messenger';

const makeNode = (overrides: Partial<NodeConversation>): NodeConversation => ({
  id: 'g1',
  title: 'Штаб',
  kind: 'group',
  circle: 'inner',
  handle: '',
  avatar: '',
  pinned: false,
  archived: false,
  created_at: '2023-11-14T22:13:20Z',
  updated_at: '2023-11-14T22:15:00Z',
  ...overrides,
});

describe('groupDeleteLeave', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(messengerApi, 'queueStatus').mockResolvedValue({ queued: 0 });
  });

  const renderSidebarWith = (nodes: NodeConversation[], patch: Partial<Chat> = {}) => {
    const chats: Chat[] = nodes.map((n) => ({ ...chatFromNode(n), lastKind: 'text' as const, lastSnippet: 'Привіт', ...patch }));
    useMessengerStore.setState({ chats, activeChatId: '' });
    const currentUser = useMessengerStore.getState().currentUser;
    return render(
      <Sidebar
        chats={chats}
        activeChatId=""
        onSelectChat={vi.fn()}
        currentUser={currentUser}
        onNewChat={vi.fn()}
      />,
    );
  };

  it('shows leave-and-delete for a group, calls leaveGroup before deleteConversation, and removes the chat', async () => {
    const leaveSpy = vi.spyOn(messengerApi, 'leaveGroup').mockResolvedValue({ left: true, told: 2 });
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));

    const sheet = screen.getByTestId('chat-actions-sheet');
    expect(sheet.textContent).not.toContain('співрозмовника');

    const menuLeaveBtn = screen.getByText('Вийти й видалити');
    fireEvent.click(menuLeaveBtn);

    expect(sheet.textContent).toContain('Група зникне з цього телефона, а ви з неї вийдете.');
    expect(sheet.textContent).not.toContain('співрозмовника');

    const confirmBtn = screen.getByRole('button', { name: 'Вийти й видалити' });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledWith('g1');
    });

    expect(leaveSpy).toHaveBeenCalledWith('g1');
    expect(leaveSpy.mock.invocationCallOrder[0]).toBeLessThan(deleteSpy.mock.invocationCallOrder[0]);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'g1')).toBeUndefined();
  });

  it('does not call deleteConversation and keeps the group when leaveGroup rejects', async () => {
    vi.spyOn(messengerApi, 'leaveGroup').mockRejectedValue(new Error('network error'));
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText('Вийти й видалити'));
    fireEvent.click(screen.getByRole('button', { name: 'Вийти й видалити' }));

    await screen.findByText('Не вдалося вийти з групи');

    expect(deleteSpy).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().chats.some((c) => c.id === 'g1')).toBe(true);
  });

  it('deletes a group the node says you already left without a second leave', async () => {
    const leaveSpy = vi
      .spyOn(messengerApi, 'leaveGroup')
      .mockRejectedValue(new ApiError(409, 'not_in_group', 'not in group'));
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText('Вийти й видалити'));
    fireEvent.click(screen.getByRole('button', { name: 'Вийти й видалити' }));

    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledWith('g1');
    });
    expect(leaveSpy).toHaveBeenCalledTimes(1);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'g1')).toBeUndefined();
  });

  it('retries only the delete after a leave went through but the delete failed', async () => {
    const leaveSpy = vi.spyOn(messengerApi, 'leaveGroup').mockResolvedValue({ left: true, told: 2 });
    const deleteSpy = vi
      .spyOn(messengerApi, 'deleteConversation')
      .mockRejectedValueOnce(new Error('disk'))
      .mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText('Вийти й видалити'));
    fireEvent.click(screen.getByRole('button', { name: 'Вийти й видалити' }));
    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledTimes(1);
    });
    expect(useMessengerStore.getState().chats.some((c) => c.id === 'g1')).toBe(true);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText('Вийти й видалити'));
    fireEvent.click(screen.getByRole('button', { name: 'Вийти й видалити' }));
    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledTimes(2);
    });
    expect(leaveSpy).toHaveBeenCalledTimes(1);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'g1')).toBeUndefined();
  });

  it('shows group clear confirmation without mentioning a single peer', () => {
    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText(uk['chatActionsSheet.clearHistory']));

    const sheet = screen.getByTestId('chat-actions-sheet');
    expect(sheet.textContent).toContain('В інших учасників лишиться');
    expect(sheet.textContent).not.toContain('співрозмовника');
  });

  it('keeps DM delete wording and calls deleteConversation without leaveGroup', async () => {
    const leaveSpy = vi.spyOn(messengerApi, 'leaveGroup').mockResolvedValue({ left: true, told: 1 });
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'dm1', title: 'Олена', kind: 'direct', handle: 'olena' })]);

    fireEvent.contextMenu(screen.getByText('Олена'));

    const deleteDoor = screen.getByText('Видалити розмову');
    expect(deleteDoor).toBeInTheDocument();
    fireEvent.click(deleteDoor);

    fireEvent.click(screen.getByRole('button', { name: uk['chatActionsSheet.delete'] }));

    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledTimes(1);
    });

    expect(deleteSpy).toHaveBeenCalledWith('dm1');
    expect(leaveSpy).not.toHaveBeenCalled();
  });

  it('offers a plain delete for a group you already left and never calls leave', async () => {
    const leaveSpy = vi.spyOn(messengerApi, 'leaveGroup').mockResolvedValue({ left: true, told: 0 });
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })], { standing: 'left' });

    fireEvent.contextMenu(screen.getByText('Штаб'));
    const sheet = screen.getByTestId('chat-actions-sheet');
    expect(sheet.textContent).not.toContain('Вийти й видалити');
    fireEvent.click(screen.getByText('Видалити розмову'));
    expect(sheet.textContent).toContain('Група зникне з цього телефона.');
    expect(sheet.textContent).not.toContain('вийдете');
    fireEvent.click(screen.getByRole('button', { name: 'Видалити' }));

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledWith('g1'));
    expect(leaveSpy).not.toHaveBeenCalled();
  });

  it('runs one leave and one delete when the confirm button is tapped twice', async () => {
    let release: () => void = () => {};
    const leaveSpy = vi.spyOn(messengerApi, 'leaveGroup').mockImplementation(
      () => new Promise((resolve) => { release = () => resolve({ left: true, told: 1 }); }),
    );
    const deleteSpy = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue({ deleted: true });

    renderSidebarWith([makeNode({ id: 'g1', title: 'Штаб', kind: 'group' })]);

    fireEvent.contextMenu(screen.getByText('Штаб'));
    fireEvent.click(screen.getByText('Вийти й видалити'));
    const confirmBtn = screen.getByRole('button', { name: 'Вийти й видалити' });
    fireEvent.click(confirmBtn);
    fireEvent.click(confirmBtn);
    release();

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledTimes(1));
    expect(leaveSpy).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Не вдалося вийти з групи')).toBeNull();
  });
});
