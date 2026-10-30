import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../../messenger/feed/MessageActionSheet';
import { PeerProfileSheet } from '../../messenger/PeerProfileSheet';
import { GroupProfileSheet } from '../../messenger/GroupProfileSheet';
import { MeScreen } from '../../messenger/MeScreen';
import { ReportSheet, ReportSheetHost } from '../ReportSheet';
import { ReportPickBar } from '../../messenger/ReportPickBar';
import { MyReports } from '../MyReports';
import {
  canAppealReport,
  reportStatusLabel,
  useSupportStore,
  type ReportStatus,
} from '../../../stores/supportStore';
import { supportApi } from '../../../services/supportApi';
import { messengerApi } from '../../../services/messengerApi';
import { phantomSelfApi } from '../../../services/phantomSelfApi';
import type { Chat, Message } from '../../../types/messenger';

const CHAT: Chat = {
  id: 'chat-1',
  title: 'Скелі по пʼятницях',
  avatar: '',
  type: 'group',
  circle: 'friends',
  unreadCount: 0,
  messages: [],
  peerNodeId: 'vik-node',
};

const FOREIGN_MSG: Message = {
  id: 'msg-foreign-1',
  senderId: 'vik-key-99',
  senderName: 'Віктор Гриб',
  senderAvatar: '',
  timestamp: '18:21',
  type: 'text',
  text: 'Забрати тут: gear-bonus.example/claim',
  isSelf: false,
};

const OWN_MSG: Message = {
  ...FOREIGN_MSG,
  id: 'msg-own-1',
  senderId: 'me',
  senderName: 'Я',
  isSelf: true,
};

const makeHandlers = (): MessageSheetHandlers =>
  new Proxy({}, { get: (_t, k) => (k === 'report' ? undefined : () => {}) }) as MessageSheetHandlers;

function setOnline(online: boolean) {
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    get: () => online,
  });
}

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  localStorage.clear();
  useSupportStore.getState().reset();
  setOnline(true);
});

describe('Report flow (W2)', () => {
  it('shows «Поскаржитися» in MessageActionSheet for a foreign message and hides it for own message', () => {
    const { unmount } = render(
      <MessageActionSheet
        msg={FOREIGN_MSG}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={makeHandlers()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Поскаржитися' })).toBeTruthy();
    unmount();

    render(
      <MessageActionSheet
        msg={OWN_MSG}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={makeHandlers()}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Поскаржитися' })).toBeNull();
  });

  it('from MessageActionSheet: picking a reason → note → preview → send calls supportApi and shows sent stamp, and undo inside 5 s window cancels', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const submitSpy = vi.spyOn(supportApi, 'submitReport').mockImplementation(async (payload) => ({
      id: payload.id,
      target_kind: payload.target_kind,
      target_id: payload.target_id,
      chat_id: payload.chat_id ?? null,
      message_ids: payload.message_ids ?? null,
      reason: payload.reason,
      note: payload.note ?? null,
      status: 'sent',
      created_at: new Date().toISOString(),
      sent_at: new Date().toISOString(),
    }));
    const blockSpy = vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });

    render(
      <MessageActionSheet
        msg={FOREIGN_MSG}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={makeHandlers()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Поскаржитися' }));
    cleanup();
    render(
      <>
        <ReportPickBar chat={{ ...CHAT, messages: [FOREIGN_MSG] }} />
        <ReportSheetHost />
      </>,
    );
    fireEvent.click(screen.getByTestId('report-pick-next'));

    const sheet = await screen.findByTestId('report-sheet');
    fireEvent.click(within(sheet).getByText('Шахрайство'));

    const noteInput = within(sheet).getByTestId('report-note-input');
    fireEvent.change(noteInput, { target: { value: 'Фальшивий розіграш призу' } });
    expect(within(sheet).getByTestId('report-note-counter').textContent).toBe('24 / 500');

    const preview = within(sheet).getByTestId('report-preview');
    expect(preview.textContent).toContain('Модерація побачить');
    expect(preview.textContent).toContain('Шахрайство');
    expect(preview.textContent).toContain('Фальшивий розіграш призу');
    expect(preview.textContent).toContain('Забрати тут: gear-bonus.example/claim');
    expect(preview.textContent).toContain('vik-key-99');

    fireEvent.click(within(sheet).getByTestId('report-send-btn'));

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(1));
    expect(submitSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        target_kind: 'message',
        target_id: 'msg-foreign-1',
        chat_id: 'chat-1',
        message_ids: ['msg-foreign-1'],
        message_text: 'Забрати тут: gear-bonus.example/claim',
        sender_key: 'vik-key-99',
        reason: 'scam',
        note: 'Фальшивий розіграш призу',
      }),
    );

    const stamp = await screen.findByTestId('report-sent-stamp');
    expect(stamp.textContent).toContain('Надіслано');
    expect(useSupportStore.getState().reports).toHaveLength(1);
    const createdId = useSupportStore.getState().reports[0].id;

    expect(blockSpy).toHaveBeenCalledWith('vik-node');
    expect((within(sheet).getByTestId('report-block-checkbox') as HTMLInputElement).checked).toBe(true);

    expect(within(sheet).queryByTestId('report-undo-btn')).toBeNull();
    expect(useSupportStore.getState().undoReport(createdId)).toBe(false);
    expect(useSupportStore.getState().reports).toHaveLength(1);
  });

  it('disallows undoReport after the 5 s window expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.spyOn(supportApi, 'submitReport').mockImplementation(async (payload) => ({
      id: payload.id,
      target_kind: payload.target_kind,
      target_id: payload.target_id,
      reason: payload.reason,
      status: 'sent',
      created_at: new Date().toISOString(),
    }));

    const report = await useSupportStore.getState().submitReport({
      targetKind: 'person',
      targetId: 'peer-1',
      reason: 'spam',
    });
    expect(useSupportStore.getState().reports).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(5001);
    });

    expect(useSupportStore.getState().undoReport(report.id)).toBe(false);
    expect(useSupportStore.getState().reports).toHaveLength(1);
  });

  it('offline path queues the report with honest copy and flush() sends when back online', async () => {
    setOnline(false);
    const submitSpy = vi.spyOn(supportApi, 'submitReport').mockImplementation(async (payload) => ({
      id: payload.id,
      target_kind: payload.target_kind,
      target_id: payload.target_id,
      reason: payload.reason,
      note: payload.note ?? null,
      status: 'sent',
      created_at: new Date().toISOString(),
      sent_at: new Date().toISOString(),
    }));

    render(
      <ReportSheet
        open
        onClose={vi.fn()}
        target={{
          targetKind: 'message',
          targetId: FOREIGN_MSG.id,
          chatId: CHAT.id,
          messageIds: [FOREIGN_MSG.id],
          messageText: FOREIGN_MSG.text,
          senderKey: FOREIGN_MSG.senderId,
        }}
      />,
    );

    const sheet = screen.getByTestId('report-sheet');
    fireEvent.click(within(sheet).getByText('Спам'));
    fireEvent.change(within(sheet).getByTestId('report-note-input'), {
      target: { value: 'Масова розсилка' },
    });
    fireEvent.click(within(sheet).getByTestId('report-send-btn'));

    const queued = await screen.findByTestId('report-queued');
    expect(queued.textContent).toContain('У черзі');
    expect(sheet.textContent).toContain('Надішлемо, коли буде звʼязок');
    expect(submitSpy).not.toHaveBeenCalled();
    expect(useSupportStore.getState().outbox).toHaveLength(1);
    expect(useSupportStore.getState().reports[0].status).toBe('queued');

    setOnline(true);
    await act(async () => {
      await useSupportStore.getState().flush();
    });

    expect(submitSpy).toHaveBeenCalledTimes(1);
    expect(useSupportStore.getState().outbox).toHaveLength(0);
    expect(useSupportStore.getState().reports[0].status).toBe('sent');
  });

  it('failure shakes once, keeps the draft, and retries via «Спробувати ще»', async () => {
    let attempts = 0;
    const submitSpy = vi.spyOn(supportApi, 'submitReport').mockImplementation(async (payload) => {
      attempts += 1;
      if (attempts === 1) {
        throw new Error('network_error');
      }
      return {
        id: payload.id,
        target_kind: payload.target_kind,
        target_id: payload.target_id,
        reason: payload.reason,
        note: payload.note ?? null,
        status: 'sent',
        created_at: new Date().toISOString(),
        sent_at: new Date().toISOString(),
      };
    });

    render(
      <ReportSheet
        open
        onClose={vi.fn()}
        target={{
          targetKind: 'message',
          targetId: FOREIGN_MSG.id,
          chatId: CHAT.id,
          messageIds: [FOREIGN_MSG.id],
          messageText: FOREIGN_MSG.text,
          senderKey: FOREIGN_MSG.senderId,
        }}
      />,
    );

    const sheet = screen.getByTestId('report-sheet');
    fireEvent.click(within(sheet).getByText('Образи'));
    const noteInput = within(sheet).getByTestId('report-note-input') as HTMLTextAreaElement;
    fireEvent.change(noteInput, { target: { value: 'Збережений чорновик' } });

    fireEvent.click(within(sheet).getByTestId('report-send-btn'));

    const errBanner = await screen.findByTestId('report-failed');
    expect(errBanner.textContent).toContain('Не надіслано');
    expect(sheet.querySelector('[data-shake="true"]')).toBeTruthy();
    expect((within(sheet).getByTestId('report-note-input') as HTMLTextAreaElement).value).toBe('Збережений чорновик');

    const retryBtn = within(sheet).getByRole('button', { name: 'Спробувати ще' });
    fireEvent.click(retryBtn);

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId('report-sent-stamp')).toBeTruthy();
  });

  it('store & MyReports: status mapping text is honest and a decided report offers one appeal', () => {
    const expected: Record<ReportStatus, string> = {
      queued: 'В черзі',
      sent: 'Надіслано',
      reviewing: 'Розглядаємо',
      action: 'Вжито заходів',
      dismissed: 'Без змін',
      failed: 'Не прийнято',
    };
    (Object.keys(expected) as ReportStatus[]).forEach((st) => {
      expect(reportStatusLabel(st, 'uk')).toBe(expected[st]);
    });

    const dismissedReport = {
      id: 'rep-dismissed-1',
      targetKind: 'person' as const,
      targetId: 'vik-node',
      targetName: 'Віктор Гриб',
      reason: 'scam' as const,
      createdAt: Date.now() - 60_000,
      status: 'dismissed' as const,
    };
    expect(canAppealReport(dismissedReport)).toBe(true);

    useSupportStore.setState({
      reports: [
        dismissedReport,
        {
          id: 'rep-action-2',
          targetKind: 'group',
          targetId: 'chat-1',
          targetName: 'Скелі по пʼятницях',
          reason: 'spam',
          createdAt: Date.now() - 120_000,
          status: 'action',
        },
      ],
    });

    render(<MyReports open onClose={vi.fn()} />);
    const list = screen.getByTestId('my-reports-sheet');
    expect(within(list).getByText('Без змін')).toBeTruthy();
    expect(within(list).getByText('Вжито заходів')).toBeTruthy();
    expect(within(list).getAllByText('Оскаржити')).toHaveLength(2);
  });

  it('entry points: PeerProfileSheet, GroupProfileSheet, and MeScreen reach ReportSheet and MyReports', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({
      id: 'chat-1',
      title: 'Скелі по пʼятницях',
      members: [],
      addable: [],
      max_members: 32,
      authoritative: false,
      standing: 'in',
      my_role: 'member',
      owner_id: 'other',
      powers: {},
    } as never);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);

    const { unmount: unmountPeer } = render(
      <PeerProfileSheet peerNodeId="vik-node" name="Віктор Гриб" chatId="chat-1" onClose={vi.fn()} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Поскаржитися' }));
    expect(await screen.findByTestId('report-sheet')).toBeTruthy();
    unmountPeer();
    useSupportStore.getState().reset();

    const { unmount: unmountGroup } = render(
      <GroupProfileSheet chat={CHAT} onClose={vi.fn()} onSelectMember={vi.fn()} onTogglePinChat={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId('info-more'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Поскаржитися на групу' }));
    expect(await screen.findByTestId('report-sheet')).toBeTruthy();
    unmountGroup();
    useSupportStore.getState().reset();

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    fireEvent.click(screen.getByTestId('me-my-reports'));
    const myReports = await screen.findByTestId('my-reports-sheet');
    expect(within(myReports).getByText('Скарг немає')).toBeTruthy();
  });
});
