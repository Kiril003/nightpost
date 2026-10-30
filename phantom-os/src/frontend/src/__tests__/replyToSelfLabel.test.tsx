/** Свайп-відповідь на власний лист: смуга каже «Відповідь собі», а не імʼя акаунта (A52 28.10: «Відповідь: Nightpost»). */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message, MessageReplyInfo } from '../types/messenger';

afterEach(cleanup);

const msg = (over: Partial<MessageReplyInfo>): MessageReplyInfo => ({ id: 'm1', senderName: 'Nightpost', type: 'text', text: 'msgx-note', ...over });

const composer = (replyingTo: MessageReplyInfo) =>
  render(
    <MessageComposer
      onSendMessage={vi.fn()}
      onSendVoiceMessage={vi.fn()}
      replyingTo={replyingTo}
      onCancelReply={vi.fn()}
      editingMessage={null}
      onCancelEdit={vi.fn()}
      onSaveEdit={vi.fn()}
      selectedMessagesForQuote={[]}
      onSynthesizeMultiQuote={vi.fn()}
      onClearSelectedQuotes={vi.fn()}
    />,
  );

describe('смуга відповіді', () => {
  it('власний лист — «Відповідь собі»', () => {
    composer(msg({ isSelf: true }));
    expect(screen.getByTestId('composer-reply').textContent).toContain('Відповідь собі');
    expect(screen.getByTestId('composer-reply').textContent).not.toContain('Відповідь: Nightpost');
  });

  it('чужий лист — імʼя автора', () => {
    composer(msg({ isSelf: false, senderName: 'Оксана' }));
    expect(screen.getByTestId('composer-reply').textContent).toContain('Відповідь: Оксана');
  });

  it('свайп по своєму листу (startReply) несе isSelf у смугу', () => {
    useMessengerStore.getState().startReply({ id: 'm9', senderId: 'me', senderName: 'Nightpost', text: 'x', type: 'text', isSelf: true } as unknown as Message);
    expect(useMessengerStore.getState().replyingTo?.isSelf).toBe(true);
    useMessengerStore.getState().cancelReply();
  });
});
