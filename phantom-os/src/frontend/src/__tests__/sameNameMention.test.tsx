import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import * as sameName from '../services/sameName';
import type { Chat, ChatMember } from '../types/messenger';

const makeDirectChat = ({ name, ...overrides }: Partial<Chat> & { id: string; name: string }): Chat => ({
  title: name,
  avatar: '',
  type: 'dm',
  unreadCount: 0,
  circle: (overrides.circles?.[0] as Chat['circle'] | undefined) ?? 'social',
  messages: [],
  ...overrides,
});

const renderComposer = (chatMembers: ChatMember[]) =>
  render(
    <MessageComposer
      onSendMessage={vi.fn()}
      onSendVoiceMessage={vi.fn()}
      replyingTo={null}
      onCancelReply={vi.fn()}
      editingMessage={null}
      onCancelEdit={vi.fn()}
      onSaveEdit={vi.fn()}
      selectedMessagesForQuote={[]}
      onSynthesizeMultiQuote={vi.fn()}
      onClearSelectedQuotes={vi.fn()}
      chatMembers={chatMembers}
    />,
  );

describe('MessageComposer mention same-name disambiguation', () => {
  beforeEach(() => {
    useMessengerStore.setState({ chats: [] });
  });

  it('disambiguates two Olenas in the mention dropdown with @handle when neither has a ring', () => {
    const { container } = renderComposer([
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const },
      { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
    ]);

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@ol', selectionStart: 3 } });

    expect(container.textContent).toContain('Olena · @olena_k');
    expect(container.textContent).toContain('Olena · @olena_p');
    expect(container.textContent?.match(/olena_k/g)).toHaveLength(1);
    expect(container.textContent?.match(/olena_p/g)).toHaveLength(1);
  });

  it('disambiguates two Olenas by ring when one is in close', () => {
    useMessengerStore.setState({
      chats: [
        makeDirectChat({
          id: 'dm-u1',
          name: 'Olena',
          handle: 'olena_k',
          circle: 'close',
          members: [{ id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const }],
        }),
      ],
    });

    const { container } = renderComposer([
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const },
      { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
    ]);

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@ol', selectionStart: 3 } });

    expect(container.textContent).toContain('Olena · Близькі');
    expect(container.textContent).toContain('Olena · @olena_p');
  });

  it('shows no suffix and keeps the handle line when there is only one Olena', () => {
    const { container } = renderComposer([
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
    ]);

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@ol', selectionStart: 3 } });

    expect(container.textContent).toContain('Olena');
    expect(container.textContent).not.toContain('Olena · ');
    expect(container.textContent).toContain('olena_k');
  });

  it('memoizes mentionSuffixes on joined member ids rather than chatMembers array identity', () => {
    const spy = vi.spyOn(sameName, 'sameNameSuffixes');
    const membersA: ChatMember[] = [
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const },
    ];
    const { rerender } = render(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatMembers={membersA}
      />,
    );

    expect(spy).toHaveBeenCalledTimes(1);

    const membersB: ChatMember[] = [
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const },
    ];
    rerender(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatMembers={membersB}
      />,
    );

    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
  });

  it('recomputes mentionSuffixes when a member is renamed or changes handle with the same member ids', () => {
    const initialMembers: ChatMember[] = [
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Petro', handle: 'petro_p', avatar: '', role: 'member' as const },
    ];
    const { container, rerender } = render(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatMembers={initialMembers}
      />,
    );

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@ol', selectionStart: 3 } });
    expect(container.textContent).not.toContain('Olena · ');

    const renamedMembers: ChatMember[] = [
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'petro_p', avatar: '', role: 'member' as const },
    ];
    rerender(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatMembers={renamedMembers}
      />,
    );

    expect(container.textContent).toContain('Olena · @olena_k');
    expect(container.textContent).toContain('Olena · @petro_p');

    const rehandledMembers: ChatMember[] = [
      { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
      { id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const },
    ];
    rerender(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatMembers={rehandledMembers}
      />,
    );

    expect(container.textContent).toContain('Olena · @olena_k');
    expect(container.textContent).toContain('Olena · @olena_p');
  });
});
