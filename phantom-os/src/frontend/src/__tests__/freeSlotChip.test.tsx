import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { lastPeerMessageOf } from '../components/messenger/ChatArea';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { dayApi, type DayState } from '../services/dayApi';
import { formatSlotRange } from '../services/freeSlots';
import type { Message } from '../types/messenger';

vi.mock('../services/dayApi', () => ({
  dayApi: {
    today: vi.fn(),
  },
}));

vi.mock('../services/voiceApi', () => ({
  voiceApi: {
    transcribe: vi.fn(),
  },
}));

const at = (h: number, m = 0, dayOffset = 0) =>
  new Date(2025, 8, 25 + dayOffset, h, m, 0, 0).getTime();

const expectedRange = formatSlotRange({ startMs: at(9, 0), endMs: at(17, 30) });

const dayWithEvent: DayState = {
  calendar: 'granted',
  items: [
    {
      id: 'ev-1',
      kind: 'event',
      title: 'Sync',
      atMs: at(18, 0),
      endMs: at(19, 30),
      allDay: false,
      peerId: null,
      overdue: false,
    },
  ],
};

function renderComposer(
  overrides: Partial<React.ComponentProps<typeof MessageComposer>> = {},
) {
  const onSendMessage = vi.fn();
  const onDraftChange = vi.fn();
  const utils = render(
    <MessageComposer
      onSendMessage={onSendMessage}
      onSendVoiceMessage={vi.fn()}
      replyingTo={null}
      onCancelReply={vi.fn()}
      editingMessage={null}
      onCancelEdit={vi.fn()}
      onSaveEdit={vi.fn()}
      selectedMessagesForQuote={[]}
      onSynthesizeMultiQuote={vi.fn()}
      onClearSelectedQuotes={vi.fn()}
      chatId="c1"
      onDraftChange={onDraftChange}
      {...overrides}
    />,
  );
  return { ...utils, onSendMessage, onDraftChange };
}

describe('MessageComposer free-slot chip', () => {
  beforeEach(() => {
    if (!Element.prototype.scrollTo) {
      Element.prototype.scrollTo = () => {};
    }
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2025, 8, 25, 9, 0, 0, 0));
    localStorage.clear();
    vi.mocked(dayApi.today).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('extracts the real send time (createdAt/timestamp) in lastPeerMessageOf instead of null', () => {
    const iso = new Date(at(9, 0)).toISOString();
    const withTimestamp: Message = {
      id: 'm-ts',
      senderId: 'peer-1',
      senderName: 'Олена',
      senderAvatar: '',
      text: 'коли ти вільний?',
      timestamp: iso,
      type: 'text',
    };
    expect(lastPeerMessageOf([withTimestamp], 'me')).toEqual({
      id: 'm-ts',
      text: 'коли ти вільний?',
      sentAtIso: iso,
    });

    const createdIso = new Date(at(8, 30)).toISOString();
    const withCreatedAt = {
      ...withTimestamp,
      id: 'm-created',
      createdAt: createdIso,
      timestamp: '08:30',
    } as Message;
    expect(lastPeerMessageOf([withCreatedAt], 'me')).toEqual({
      id: 'm-created',
      text: 'коли ти вільний?',
      sentAtIso: createdIso,
    });
  });

  it('shows the chip for a time ask and inserts the slot into the textarea without sending', async () => {
    vi.mocked(dayApi.today).mockResolvedValue(dayWithEvent);

    const { onSendMessage } = renderComposer({
      lastPeerMessage: {
        id: 'm1',
        text: 'when are you free?',
        sentAtIso: new Date(at(9, 0)).toISOString(),
      },
    });

    const chip = await screen.findByTestId('composer-free-slot');
    fireEvent.click(chip);

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    expect(textarea.value).toContain(expectedRange);
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
  });

  it('appends the slot to existing draft text and saves the combined draft via onDraftChange', async () => {
    vi.mocked(dayApi.today).mockResolvedValue(dayWithEvent);

    const { onDraftChange } = renderComposer({
      chatId: 'c1',
      initialDraft: 'Вже пишу,',
      lastPeerMessage: { id: 'm6', text: 'when are you free?' },
    });

    const chip = await screen.findByTestId('composer-free-slot');
    fireEvent.click(chip);

    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;
    expect(textarea.value).toContain('Вже пишу,');
    expect(textarea.value).toContain(expectedRange);
    expect(onDraftChange).toHaveBeenCalledTimes(1);
    const [savedChatId, savedDraft] = onDraftChange.mock.calls[0];
    expect(savedChatId).toBe('c1');
    expect(savedDraft).toContain('Вже пишу,');
    expect(savedDraft).toContain(expectedRange);
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
  });

  it('never reshows a chip already used for message X after switching chats and coming back, and keys on text', async () => {
    vi.mocked(dayApi.today).mockResolvedValue(dayWithEvent);

    const { rerender } = renderComposer({
      chatId: 'c1',
      lastPeerMessage: { id: 'm-keep', text: 'when are you free?' },
    });

    const chip = await screen.findByTestId('composer-free-slot');
    fireEvent.click(chip);
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
    expect(dayApi.today).toHaveBeenCalledTimes(1);

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
        chatId="c2"
        initialDraft=""
        lastPeerMessage={{ id: 'm-other', text: 'привіт' }}
      />,
    );
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();

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
        chatId="c1"
        initialDraft=""
        lastPeerMessage={{ id: 'm-keep', text: 'when are you free?' }}
      />,
    );
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
    expect(dayApi.today).toHaveBeenCalledTimes(1);

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
        chatId="c2"
        initialDraft=""
        lastPeerMessage={{ id: 'm-other', text: 'давай завтра?' }}
      />,
    );
    const chipInC2 = await screen.findByTestId('composer-free-slot');
    expect(chipInC2.textContent).toContain(expectedRange);
  });

  it('hides the chip when the Day is empty', async () => {
    vi.mocked(dayApi.today).mockResolvedValue({ items: [], calendar: 'granted' });

    renderComposer({
      lastPeerMessage: { id: 'm2', text: 'when are you free?' },
    });

    await waitFor(() => expect(dayApi.today).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
  });

  it('hides the chip when dayApi.today resolves null or rejects', async () => {
    vi.mocked(dayApi.today).mockResolvedValueOnce(null);

    const { unmount } = renderComposer({
      lastPeerMessage: { id: 'm3', text: 'коли ти вільний?' },
    });

    await waitFor(() => expect(dayApi.today).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
    unmount();

    vi.mocked(dayApi.today).mockRejectedValueOnce(new Error('offline'));
    renderComposer({
      lastPeerMessage: { id: 'm4', text: 'коли ти вільний?' },
    });

    await waitFor(() => expect(dayApi.today).toHaveBeenCalledTimes(2));
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
  });

  it('does not call dayApi.today when the peer message is not a time ask', () => {
    renderComposer({
      lastPeerMessage: { id: 'm5', text: 'ок' },
    });

    expect(dayApi.today).not.toHaveBeenCalled();
    expect(screen.queryByTestId('composer-free-slot')).toBeNull();
  });
});
