/**
 * Питання, що стало Наміром до мого ПК (розд. 07), у НОВІЙ стрічці (VirtualFeed →
 * feed/MessageRow): під бульбашкою — смуга, стан і відповідь; без intentId — нічого.
 */
import { beforeAll, describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { intentsApi, type IntentRow } from '../services/intentsApi';
import { useIntentsStore } from '../stores/intentsStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useIntentsStore.setState({ rows: null });
});

const noop = () => {};
const chat = { id: 'chat_phantom_assistant', title: 'Nightpost', type: 'dm', messages: [], unreadCount: 0 } as unknown as Chat;
const msg = (id: string, over: Partial<Message> = {}): Message =>
  ({ id, senderId: 'me', senderName: 'Я', timestamp: '14:32', type: 'text', text: `питання ${id}`, isSelf: true, status: 'sent', ...over }) as unknown as Message;

const row: IntentRow = {
  id: 'a1', op: 'ask', title: 'тривоги?', created_ms: 1, deadline_ms: 2, phase: 'ANSWERED',
  tries: [{ lane: 'sms', at_ms: 1 }], relay_allowed: true,
  answer: { rung: 1, status: 0, lane: 'sms', at_ms: 2, text: 'Тривог у вашій області немає.' },
} as unknown as IntentRow;

const feed = (messages: Message[]) =>
  render(
    <MemoryRouter>
      <ChatArea currentChat={chat} messages={messages} currentUserId="me" onOpenLocation={noop} onVotePoll={noop}
        onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop} selectedMessageIds={[]}
        onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop} />
    </MemoryRouter>,
  );

describe('Намір у новій стрічці', () => {
  it('лист з intentId показує смугу й відповідь під бульбашкою; звичайний — ні', async () => {
    vi.spyOn(intentsApi, 'list').mockResolvedValue({ intents: [row] });
    const { container, findByTestId } = feed([msg('m0'), msg('m1', { intentId: 'a1' } as Partial<Message>)]);
    const trail = await findByTestId('intent-trail');
    await waitFor(() => expect(trail.textContent).toContain('Тривог у вашій області немає.'));
    expect(container.querySelectorAll('[data-msg-intent]')).toHaveLength(1);
    expect(trail.outerHTML).not.toMatch(/\b(text|bg|rounded)-f-/);
  });
});
