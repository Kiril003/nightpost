import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { openMeetupAsk } from '../services/nativeMeetupDraft';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/nativeMeetupDraft', async (orig) => ({ ...(await orig<object>()), openMeetupAsk: vi.fn() }));

const fn = vi.fn();
const on = Object.fromEntries(
  ['reply', 'copyText', 'forward', 'edit', 'react', 'openReactionPicker', 'speak', 'openCanvas', 'toast', 'translate', 'summarize', 'actionItems', 'togglePin', 'bookmark', 'select', 'inspect', 'remove'].map((k) => [k, fn]),
) as unknown as MessageSheetHandlers;

const msg = { id: 'm1', senderId: 'a', senderName: 'Олена', text: 'Йдемо в кіно', timestamp: '10:00', status: 'sent', type: 'text', senderAvatar: '' } as Message;
const reply = { id: 'r', senderId: 'b', senderName: 'Петро', text: 'так', timestamp: '10:01', status: 'sent', type: 'text', senderAvatar: '', replyTo: { id: 'm1', senderName: 'Олена', text: '' } } as Message;
const group = (messages: Message[]) => ({ id: 'g', title: 'Група', type: 'group', avatar: '', unreadCount: 0, circle: 'friends', messages }) as Chat;

const show = (chat: Chat) =>
  render(<MessageActionSheet msg={msg} currentChat={chat} currentUserId="me" speaking={false} onClose={vi.fn()} on={on} />);

describe('forkPlan sheet row', () => {
  afterEach(cleanup);

  it('shows «Новий план» and opens the draft with the repliers', () => {
    show(group([msg, reply]));
    fireEvent.click(screen.getByText('Новий план'));
    expect(openMeetupAsk).toHaveBeenCalledWith(expect.objectContaining({ kind: 'meetup', chatId: 'g', args: expect.objectContaining({ people: ['Петро'] }) }));
  });

  it('hidden without repliers', () => {
    show(group([msg]));
    expect(screen.queryByText('Новий план')).toBeNull();
  });
});
