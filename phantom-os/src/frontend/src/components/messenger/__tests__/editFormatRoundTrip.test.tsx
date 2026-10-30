/**
 * Правка зберігає форматування: у композер повертається розмітка, панель
 * формату працює й у правці, а вузол і стрічка отримують ту саму розмітку.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MessageComposer } from '../MessageComposer';
import { FormattedMessageText } from '../FormattedMessageText';
import { useMessengerStore } from '../../../stores/messengerStore';
import { messengerApi, type NodeMessage } from '../../../services/messengerApi';
import type { Message } from '../../../types/messenger';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const said = '**жирний** і ||таємне||\n```\nкод  з відступом\n```';
const mine: Message = { id: 'm1', text: said, isSelf: true, timestamp: '10:00', status: 'sent' } as Message;

const props = (overrides: Partial<React.ComponentProps<typeof MessageComposer>> = {}) =>
  ({
    onSendMessage: vi.fn(),
    onSendVoiceMessage: vi.fn(),
    replyingTo: null,
    onCancelReply: vi.fn(),
    editingMessage: mine,
    onCancelEdit: vi.fn(),
    onSaveEdit: vi.fn(),
    selectedMessagesForQuote: [],
    onSynthesizeMultiQuote: vi.fn(),
    onClearSelectedQuotes: vi.fn(),
    chatId: 'chat_edit',
    ...overrides,
  }) satisfies React.ComponentProps<typeof MessageComposer>;

describe('правка й форматування', () => {
  it('у композер повертається розмітка, а не голий текст, і панель формату працює в правці', () => {
    const onSaveEdit = vi.fn();
    render(<MessageComposer {...props({ onSaveEdit })} />);
    const ta = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    expect(ta.value).toBe(said);
    expect(screen.getByTestId('composer-edit').textContent).not.toContain('**');

    const at = ta.value.indexOf('і');
    ta.focus();
    ta.setSelectionRange(at, at + 1);
    fireEvent.select(ta, { target: { selectionStart: at, selectionEnd: at + 1 } });
    fireEvent.click(screen.getByLabelText('Курсив'));
    expect(ta.value).toBe(said.replace(' і ', ' _і_ '));

    fireEvent.keyDown(ta, { key: 'Enter' });
    expect(onSaveEdit).toHaveBeenCalledWith('m1', said.replace(' і ', ' _і_ '));
  });

  it('вузол отримує розмітку як є, а стрічка малює її, а не маркери', async () => {
    const next = '**жирний** і _тепер_ `код`';
    const edit = vi.spyOn(messengerApi, 'editMessage').mockResolvedValue({
      id: 'm1', body: next, edited_at: '2026-10-20T10:00:00Z',
    } as NodeMessage);
    useMessengerStore.setState({
      chats: [{ id: 'peer_a', title: 'Оля', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [mine] }] as never,
    });
    await useMessengerStore.getState().editMessage('m1', `  ${next}  `);
    expect(edit).toHaveBeenCalledWith('peer_a', 'm1', next);
    await waitFor(() => expect(useMessengerStore.getState().chats[0].messages[0].isEdited).toBe(true));
    const settled = useMessengerStore.getState().chats[0].messages[0].text ?? '';
    expect(settled).toBe(next);
    const { container } = render(<FormattedMessageText text={settled} />);
    expect(container.querySelector('strong')?.textContent).toBe('жирний');
    expect(container.querySelector('em')?.textContent).toBe('тепер');
    expect(container.querySelector('code')?.textContent).toBe('код');
    expect(container.textContent).not.toMatch(/[*_`]/);
  });

  it('підпис фото: вузол відповідає описом файла, а в стрічку лягає підпис, не JSON', async () => {
    const photo = { ...mine, id: 'p1', type: 'image', text: 'море', media: { url: '', mime: 'image/jpeg' } } as unknown as Message;
    const body = JSON.stringify({ blob_id: 'a'.repeat(32), key_hex: '00ff', sha256: 'ab', size: 10, name: 'p.jpg', mime: 'image/jpeg', caption: '**море** вранці' });
    vi.spyOn(messengerApi, 'editMessage').mockResolvedValue({
      id: 'p1', kind: 'image', body, edited_at: '2026-10-20T10:00:00Z',
    } as NodeMessage);
    useMessengerStore.setState({
      chats: [{ id: 'peer_a', title: 'Оля', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [photo] }] as never,
    });
    await useMessengerStore.getState().editMessage('p1', '**море** вранці');
    const m = useMessengerStore.getState().chats[0].messages[0];
    expect([m.text, m.isEdited]).toEqual(['**море** вранці', true]);
  });
});
