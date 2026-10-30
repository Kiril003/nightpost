import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CommitmentCard } from '../CommitmentCard';
import { phantomSelfApi } from '../../../../services/phantomSelfApi';
import type { CommitmentCardData } from '../../../../types/messenger';

describe('CommitmentCard', () => {
  const bridgeCall = vi.fn();

  beforeEach(() => {
    bridgeCall.mockReset();
    (globalThis as unknown as { PhantomBridge?: { call: typeof bridgeCall } }).PhantomBridge = {
      call: bridgeCall,
    };
  });

  afterEach(() => {
    delete (globalThis as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('renders choose card candidates as buttons and calls the bridge route with the chosen peer id on tap', async () => {
    bridgeCall.mockImplementation((_method: string, _path: string, _body: string | null) =>
      JSON.stringify({
        ok: true,
        status: 200,
        data: {
          id: 'cmt_42',
          text: 'Надішлю Олі фото завтра',
          person: 'Оля Мельник',
          person_name: 'Оля Мельник',
          peer_id: 'peer_olia_2',
          what: 'фото',
          due_ms: 1790500000000,
          created_ms: 1790400000000,
          source_message_id: 'msg_1',
          state: 'open',
        },
      }),
    );

    const card: CommitmentCardData = {
      kind: 'choose',
      pendingId: 'pend_7',
      person: 'Оля',
      what: 'фото',
      state: 'open',
      candidates: [
        { peerId: 'peer_olia_1', name: 'Оля Коваль', hint: 'Сімʼя' },
        { peerId: 'peer_olia_2', name: 'Оля Мельник' },
      ],
    };

    render(<CommitmentCard card={card} />);

    const firstBtn = screen.getByRole('button', { name: /Оля Коваль/i });
    const secondBtn = screen.getByRole('button', { name: /Оля Мельник/i });
    expect(firstBtn).toBeTruthy();
    expect(secondBtn).toBeTruthy();
    expect(firstBtn.textContent).toContain('Сімʼя');
    expect(secondBtn.textContent).toBe('Оля Мельник');

    fireEvent.click(secondBtn);

    await waitFor(() => {
      expect(bridgeCall).toHaveBeenCalledWith(
        'POST',
        '/messenger/self/commitments',
        JSON.stringify({ choose: { pending_id: 'pend_7', peer_id: 'peer_olia_2' } }),
      );
    });
  });

  it('renders due card with Done and Open chat buttons and marks commitment done on tap', async () => {
    bridgeCall.mockImplementation(() =>
      JSON.stringify({
        ok: true,
        status: 200,
        data: { id: 'cmt_99', state: 'done' },
      }),
    );

    const onOpenChat = vi.fn();
    const card: CommitmentCardData = {
      kind: 'due',
      commitmentId: 'cmt_99',
      person: 'Оля',
      peerId: 'peer_olia_1',
      what: 'фото',
      dueMs: 1790500000000,
      state: 'open',
    };

    render(<CommitmentCard card={card} onOpenChat={onOpenChat} />);

    const doneBtn = screen.getByTestId('commitment-done');
    const openChatBtn = screen.getByTestId('commitment-open-chat');
    expect(doneBtn).toBeTruthy();
    expect(openChatBtn).toBeTruthy();

    fireEvent.click(openChatBtn);
    expect(onOpenChat).toHaveBeenCalledWith('peer_olia_1');

    fireEvent.click(doneBtn);

    await waitFor(() => {
      expect(bridgeCall).toHaveBeenCalledWith(
        'POST',
        '/messenger/self/commitments/cmt_99/done',
        JSON.stringify({}),
      );
    });
  });

  it('syncs state when card prop updates to done and rolls back when doneCommitment fails or commitmentId is missing', async () => {
    const baseCard: CommitmentCardData = {
      kind: 'saved',
      commitmentId: undefined,
      person: 'Оля',
      peerId: 'peer_olia_1',
      what: 'фото',
      state: 'open',
    };

    const { rerender } = render(<CommitmentCard card={baseCard} />);

    // Clicking Done without commitmentId must not flip state to done or call bridge
    fireEvent.click(screen.getByTestId('commitment-done'));
    expect(bridgeCall).not.toHaveBeenCalled();
    expect(screen.queryByTestId('commitment-done-state')).toBeNull();

    // When commitmentId is present but bridge fails, state rolls back to open
    bridgeCall.mockImplementation(() =>
      JSON.stringify({ ok: false, status: 500, code: 'failed', detail: '' }),
    );
    rerender(<CommitmentCard card={{ ...baseCard, commitmentId: 'cmt_55', state: 'open' }} />);
    fireEvent.click(screen.getByTestId('commitment-done'));
    await waitFor(() => {
      expect(screen.getByTestId('commitment-done')).toBeTruthy();
    });

    // External prop update (e.g. "Готово" in chat) updates rendered state to done
    rerender(<CommitmentCard card={{ ...baseCard, commitmentId: 'cmt_55', state: 'done' }} />);
    expect(screen.getByTestId('commitment-done-state')).toBeTruthy();
  });

  it('passes message_id in phantomSelfApi.remember when messageId is provided', async () => {
    bridgeCall.mockImplementation(() =>
      JSON.stringify({
        ok: true,
        status: 200,
        data: {
          id: 'f1',
          text: 'Ти працюєш інженером',
          from: 'self',
          from_label: 'від тебе',
          skill: false,
          at: '2026-09-27T10:00:00Z',
        },
      }),
    );

    await phantomSelfApi.remember('Я працюю інженером', {
      origin: 'owner_statement',
      chatId: 'peer_chat_1',
      messageId: 'msg_from_owner',
    });

    expect(bridgeCall).toHaveBeenCalledWith(
      'POST',
      '/messenger/self/facts',
      JSON.stringify({
        text: 'Я працюю інженером',
        origin: 'owner_statement',
        explicit: false,
        chat_id: 'peer_chat_1',
        message_id: 'msg_from_owner',
      }),
    );
  });

  it('renders offer tag under outgoing message and calls addDirect on tap', async () => {
    bridgeCall.mockImplementation((_method: string, _path: string, _body: string | null) =>
      JSON.stringify({
        ok: true,
        status: 200,
        data: {
          id: 'cmt_direct_1',
          text: 'Оля Коваль: скину звіт',
          person: 'Оля Коваль',
          peer_id: 'peer_olia_1',
          what: 'скину звіт',
          due_ms: 1790500000000,
          created_ms: 1790400000000,
          source_message_id: 'msg_out_42',
          state: 'open',
        },
      }),
    );

    const offerCard: CommitmentCardData = {
      kind: 'offer',
      person: 'Оля Коваль',
      peerId: 'peer_olia_1',
      what: 'скину звіт',
      dueMs: 1790500000000,
      sourceMessageId: 'msg_out_42',
      state: 'open',
    };

    render(<CommitmentCard card={offerCard} />);

    const acceptBtn = screen.getByTestId('commitment-offer-accept');
    expect(acceptBtn).toBeTruthy();
    expect(acceptBtn.textContent).toContain('У День');

    fireEvent.click(acceptBtn);

    await waitFor(() => {
      expect(bridgeCall).toHaveBeenCalledWith(
        'POST',
        '/messenger/self/commitments',
        JSON.stringify({
          person: 'Оля Коваль',
          peer_id: 'peer_olia_1',
          what: 'скину звіт',
          due_ms: 1790500000000,
          source_message_id: 'msg_out_42',
        }),
      );
    });

    expect(screen.getByTestId('commitment-offer-saved')).toBeTruthy();
  });

  it('dismisses offer tag when close button is tapped', () => {
    const offerCard: CommitmentCardData = {
      kind: 'offer',
      person: 'Оля Коваль',
      peerId: 'peer_olia_1',
      what: 'скину звіт',
      dueMs: 1790500000000,
      sourceMessageId: 'msg_out_42',
      state: 'open',
    };

    const { container } = render(<CommitmentCard card={offerCard} />);
    expect(screen.getByTestId('commitment-offer-accept')).toBeTruthy();

    const dismissBtn = screen.getByTestId('commitment-offer-dismiss');
    fireEvent.click(dismissBtn);

    expect(container.firstChild).toBeNull();
  });

  it('keeps a dismissed offer gone after the chat is reopened', () => {
    localStorage.clear();
    const offerCard: CommitmentCardData = {
      kind: 'offer',
      person: 'Оля Коваль',
      what: 'скину звіт',
      dueMs: 1790500000000,
      sourceMessageId: 'msg_out_77',
      state: 'open',
    };
    const first = render(<CommitmentCard card={offerCard} />);
    fireEvent.click(screen.getByTestId('commitment-offer-dismiss'));
    first.unmount();

    const { container } = render(<CommitmentCard card={offerCard} />);
    expect(container.firstChild).toBeNull();
  });

  it('speaks English when the app is in English', () => {
    localStorage.clear();
    localStorage.setItem('phantom_language', 'en');
    const due = new Date();
    due.setDate(due.getDate() + 1);
    due.setHours(10, 0, 0, 0);
    render(
      <CommitmentCard
        card={{ kind: 'offer', person: 'Ola', what: 'report', dueMs: due.getTime(), sourceMessageId: 'msg_en_1', state: 'open' }}
      />,
    );
    const text = screen.getByTestId('commitment-offer-accept').textContent ?? '';
    localStorage.clear();
    expect(text).toContain('Add to Day');
    expect(text).toContain('tomorrow 10:00');
    expect(text).not.toContain('завтра');
  });
});
