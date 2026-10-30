import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ReportSheet } from '../ReportSheet';
import { HelpCentre } from '../HelpCentre';
import { useSupportStore, type ReportTarget } from '../../../stores/supportStore';
import { useMessengerStore } from '../../../stores/messengerStore';
import { supportApi } from '../../../services/supportApi';
import { messengerApi } from '../../../services/messengerApi';

const MSG: ReportTarget = {
  targetKind: 'message',
  targetId: 'm-1',
  chatId: 'dm-1',
  messageIds: ['m-1'],
  messageText: 'Переведи 500 на картку',
  senderKey: 'vik-key',
  peerId: 'vik-node',
};

function echo() {
  return vi.spyOn(supportApi, 'submitReport').mockImplementation(async (p) => ({
    id: p.id,
    target_kind: p.target_kind,
    target_id: p.target_id,
    status: 'sent',
    reason: p.reason,
    created_at: new Date().toISOString(),
    sent_at: new Date().toISOString(),
  }));
}

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useSupportStore.getState().reset();
});

describe('скарга в приватному — це блок + чесний перелік того, що піде (REPORT-REAL)', () => {
  it('1:1: одна кнопка «Заблокувати й поскаржитися», блок іде першим, модерація бачить лише вибране', async () => {
    const order: string[] = [];
    vi.spyOn(messengerApi, 'blockPeer').mockImplementation(async () => {
      order.push('block');
      return { blocked: true };
    });
    const submit = echo();
    submit.mockImplementationOnce(async (p) => {
      order.push('report');
      return { id: p.id, target_kind: p.target_kind, target_id: p.target_id, status: 'sent', reason: p.reason } as never;
    });
    const onBlocked = vi.fn();
    render(<ReportSheet open target={MSG} onClose={vi.fn()} onBlocked={onBlocked} />);
    const sheet = screen.getByTestId('report-sheet');
    const preview = within(sheet).getByTestId('report-preview');
    expect(preview.textContent).toContain('Модерація побачить');
    expect(preview.textContent).toContain('Переведи 500 на картку');
    expect(within(preview).getByTestId('report-shared-messages').textContent).toContain('Лише вибране');

    fireEvent.click(within(sheet).getByTestId('report-reason-scam'));
    const send = within(sheet).getByTestId('report-send-btn');
    expect(send.textContent).toBe('Заблокувати й поскаржитися');
    fireEvent.click(send);

    await screen.findByTestId('report-sent-stamp');
    expect(order).toEqual(['block', 'report']);
    expect(onBlocked).toHaveBeenCalledWith(true);
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ surface: 'private', message_ids: ['m-1'] }));
    expect((screen.getByTestId('report-block-checkbox') as HTMLInputElement).checked).toBe(true);
  });

  it('скарга на людину без вибраних повідомлень: модерація не бачить жодного', () => {
    render(
      <ReportSheet open target={{ targetKind: 'person', targetId: 'vik-node', senderKey: 'vik-node', peerId: 'vik-node' }} onClose={vi.fn()} />,
    );
    const shared = screen.getByTestId('report-shared-messages');
    expect(shared.textContent).toContain('Жодного');
  });

  it('блок не вдався — скарга не йде, чернетка лишається', async () => {
    vi.spyOn(messengerApi, 'blockPeer').mockRejectedValue(new Error('node down'));
    const submit = echo();
    render(<ReportSheet open target={MSG} onClose={vi.fn()} />);
    fireEvent.click(screen.getByTestId('report-reason-spam'));
    fireEvent.click(screen.getByTestId('report-send-btn'));
    await screen.findByTestId('report-failed');
    expect(submit).not.toHaveBeenCalled();
  });

  it('публічна група: звичайна скарга без блоку, сервер знає, що це публічне', async () => {
    const block = vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });
    const submit = echo();
    render(
      <ReportSheet
        open
        target={{ targetKind: 'group', targetId: 'g-open', chatId: 'g-open', senderKey: 'g-open', surface: 'public' }}
        onClose={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('report-reason-spam'));
    expect(screen.getByTestId('report-send-btn').textContent).toBe('Надіслати скаргу');
    fireEvent.click(screen.getByTestId('report-send-btn'));
    await screen.findByTestId('report-sent-stamp');
    expect(block).not.toHaveBeenCalled();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ surface: 'public', target_kind: 'group' }));
  });
});

describe('той, кого зняли з публічного, бачить обмеження й може оскаржити', () => {
  it('Допомога показує «Обмеження» лише коли воно є, і шле оскарження з ціллю', async () => {
    useMessengerStore.setState((s) => ({
      currentUser: { ...s.currentUser, id: 'me-node' },
      chats: [{ id: 'g-mine', title: 'Відкрита', avatar: '', type: 'group', unreadCount: 0, messages: [], isPublic: true, myRole: 'owner' } as never],
    }));
    const list = vi.spyOn(supportApi, 'listNotices').mockResolvedValue([
      { id: 7, scope: 'key', target: 'me-node', reason: 'spam', decided_at: '2026-10-28T10:00:00+00:00', appeal: null },
    ]);
    const appeal = vi.spyOn(supportApi, 'appealNotice').mockResolvedValue({
      id: 7, scope: 'key', target: 'me-node', reason: 'spam', decided_at: '2026-10-28T10:00:00+00:00',
      appeal: { status: 'submitted', at: '2026-10-28T11:00:00+00:00' },
    });
    render(<HelpCentre open onClose={vi.fn()} />);
    await waitFor(() => expect(list).toHaveBeenCalledWith(['me-node', 'g-mine']));
    fireEvent.click(await screen.findByTestId('help-notices'));
    const sheet = await screen.findByTestId('notices-sheet');
    expect(sheet.textContent).toContain('Прибрано з пошуку');
    expect(sheet.textContent).toContain('Спам');
    fireEvent.click(within(sheet).getByTestId('notice-appeal-7'));
    fireEvent.change(await screen.findByTestId('appeal-text'), { target: { value: 'Це не я' } });
    fireEvent.click(screen.getByTestId('appeal-send'));
    await waitFor(() =>
      expect(appeal).toHaveBeenCalledWith(7, { target: 'me-node', reason: 'wrong_decision', text: 'Це не я' }),
    );
    expect((await screen.findByTestId('notice-appeal-state-7')).textContent).toBe('Оскарження подано');
  });

  it('немає обмежень або сервер ще старий — рядка немає', async () => {
    const list = vi.spyOn(supportApi, 'listNotices').mockRejectedValue(new Error('404'));
    render(<HelpCentre open onClose={vi.fn()} />);
    await waitFor(() => expect(list).toHaveBeenCalled());
    expect(screen.queryByTestId('help-notices')).toBeNull();
  });
});
