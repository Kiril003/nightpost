/**
 * A5: слова помічника ростуть у розмові, поки йдуть; наприкінці лишається одна бульбашка;
 * поразка — знак на питанні, а не відповідь.
 */
import { beforeAll, describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import { useAssistantLive, type AssistantLiveEvent } from '../services/assistantLive';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  useAssistantLive.setState({ lines: {} });
});

const noop = () => {};
const PHANTOM = 'peer_phantom_agent';

const nodeRow = (over: Record<string, unknown> = {}): NodeMessage =>
  ({
    id: 'q1', conversation_id: PHANTOM, client_id: 'q1', seq: 1, author_id: 'me', author_name: 'Я',
    kind: 'text', body: 'What is the capital of Czechia', ciphertext: null, transport: 'p2p',
    sent_at: '2026-10-15T01:00:00Z', edited_at: null, deleted_at: null, delivery_state: 'delivered',
    ...over,
  }) as unknown as NodeMessage;

const row = (over: Record<string, unknown> = {}) => messageFromNode(nodeRow(over), 'me', PHANTOM);

const seed = (messages: Message[]) =>
  useMessengerStore.setState({
    chats: [
      {
        id: PHANTOM, title: 'Nightpost', avatar: '', type: 'phantom', circle: 'all', pinned: true,
        unreadCount: 0, messages, peerNodeId: PHANTOM,
      } as unknown as Chat,
    ],
    currentUser: { id: 'me', name: 'Я' },
  } as never);

const Harness = () => {
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === PHANTOM));
  return (
    <MemoryRouter>
      <ChatArea
        currentChat={chat}
        messages={chat?.messages ?? []}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>
  );
};

const live = (e: Partial<AssistantLiveEvent>) =>
  act(() => {
    const door = (window as unknown as { __phantomAssistantLive?: (e: AssistantLiveEvent) => void }).__phantomAssistantLive;
    expect(door, 'the page has no door for live words').toBeTypeOf('function');
    door!({ chat_id: PHANTOM, message_id: 'm1', question_id: 'q1', text: '', ...e } as AssistantLiveEvent);
  });

const bubbles = (c: HTMLElement) => Array.from(c.querySelectorAll('[id^="message-"]')).map((n) => n.id);

describe('живі слова помічника', () => {
  it('стрічка показує, як росте відповідь, і наприкінці лишається одна бульбашка', () => {
    seed([row()]);
    const { container, queryByTestId } = render(<Harness />);

    live({ phase: 'thinking' });
    expect(queryByTestId('typing-bubble')).not.toBeNull();
    expect(bubbles(container)).toEqual(['message-q1']);

    live({ phase: 'writing', text: 'Prague' });
    expect(container.querySelector('#message-m1')?.textContent).toContain('Prague');
    expect(queryByTestId('typing-bubble')).toBeNull();

    live({ phase: 'writing', text: 'Prague is the' });
    expect(container.querySelector('#message-m1')?.textContent).toContain('Prague is the');
    expect(bubbles(container)).toEqual(['message-q1', 'message-m1']);

    live({ phase: 'done', text: 'Prague is the capital.' });
    expect(bubbles(container)).toEqual(['message-q1', 'message-m1']);

    act(() => {
      seed([row(), row({ id: 'm1', client_id: 'm1', seq: 2, author_id: PHANTOM, author_name: 'Nightpost', body: 'Prague is the capital.' })]);
    });
    expect(bubbles(container)).toEqual(['message-q1', 'message-m1']);
    expect(container.querySelector('#message-m1')?.textContent).toContain('Prague is the capital.');
    expect(container.querySelector('[data-live]')).toBeNull();
  });

  it('поразка — знак на питанні з «Повторити», обірвані слова не стають відповіддю', () => {
    seed([row()]);
    const { container, getByTestId } = render(<Harness />);

    live({ phase: 'thinking' });
    live({ phase: 'writing', text: 'Par' });
    live({ phase: 'failed', text: 'Par', reason: 'Немає мережі' });

    const mark = getByTestId('msg-failed');
    expect(mark.closest('#message-q1')).not.toBeNull();
    expect(mark.textContent).toBe('Немає мережі · Повторити');
    expect(container.querySelector('#message-m1')?.getAttribute('data-live')).toBe('failed');
    expect(getByTestId('live-cut').textContent).toBe('Обірвано');
    expect(useMessengerStore.getState().chats[0].messages.map((m) => m.id)).toEqual(['q1']);
  });

  it('кінець без слів не лишає ні крапок, ні порожньої бульбашки', () => {
    seed([row()]);
    const { container, queryByTestId } = render(<Harness />);
    live({ phase: 'thinking' });
    live({ phase: 'done' });
    expect(queryByTestId('typing-bubble')).toBeNull();
    expect(bubbles(container)).toEqual(['message-q1']);
  });

  it('рядок, що росте, тримає час першого слова', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-10-15T23:59:59Z'));
      live({ phase: 'writing', text: 'Prague' });
      const first = useAssistantLive.getState().lines[PHANTOM].at;
      vi.setSystemTime(new Date('2026-10-16T00:00:01Z'));
      live({ phase: 'writing', text: 'Prague is the' });
      expect(useAssistantLive.getState().lines[PHANTOM].at).toBe(first);
      live({ message_id: 'm2', question_id: 'q2', phase: 'writing', text: 'Next' });
      expect(useAssistantLive.getState().lines[PHANTOM].at).toBe(Date.now());
    } finally {
      vi.useRealTimers();
    }
  });

  it('живий рядок ще не лист: довгий дотик не відкриває дій, на питанні — відкриває', () => {
    seed([row()]);
    const { container, queryByRole } = render(<Harness />);
    live({ phase: 'writing', text: 'Prague is' });
    fireEvent.contextMenu(container.querySelector('#message-m1 .msg-bubble-fluid')!);
    expect(queryByRole('dialog')).toBeNull();
    fireEvent.contextMenu(container.querySelector('#message-q1 .msg-bubble-fluid')!);
    expect(queryByRole('dialog')).not.toBeNull();
  });

  it('живий рядок іншої розмови тут не малюється', () => {
    seed([row()]);
    const { container, queryByTestId } = render(<Harness />);
    live({ chat_id: 'peer_someone', phase: 'writing', text: 'hello' });
    expect(queryByTestId('typing-bubble')).toBeNull();
    expect(bubbles(container)).toEqual(['message-q1']);
  });
});
