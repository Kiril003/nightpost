import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ReportPickBar } from '../components/messenger/ReportPickBar';
import { REPORT_PICK_MAX, useMessengerStore } from '../stores/messengerStore';
import { useSupportStore } from '../stores/supportStore';
import type { Chat, Message } from '../types/messenger';

const msgs: Message[] = Array.from({ length: 12 }, (_, i) =>
  ({ id: `m${i}`, senderId: 'peer', senderName: 'Віктор', senderAvatar: '', timestamp: '18:21', type: 'text', isSelf: false, text: `лист ${i}` }) as Message,
);
const chat = { id: 'c1', title: 'Віктор', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: msgs, peerNodeId: 'peer' } as unknown as Chat;

beforeEach(() => useMessengerStore.getState().clearSelection());
afterEach(cleanup);

describe('L7: скарга на кілька листів', () => {
  it('лічильник «N з 10», «Далі» віддає всі вибрані листи у скаргу', () => {
    const st = useMessengerStore.getState();
    st.beginReportPick('m1');
    st.toggleSelectMessage('m3');
    render(<ReportPickBar chat={chat} />);
    expect(screen.getByTestId('report-pick-count').textContent).toBe('Вибрано 2 з 10');
    fireEvent.click(screen.getByTestId('report-pick-next'));
    const target = useSupportStore.getState().activeTarget;
    expect(target?.messageIds).toEqual(['m1', 'm3']);
    expect(useMessengerStore.getState().reportPick).toBe(false);
  });

  it('понад 10 не вибирається, показано «Максимум 10»', () => {
    const st = useMessengerStore.getState();
    st.beginReportPick('m0');
    for (let i = 1; i < 12; i++) useMessengerStore.getState().toggleSelectMessage(`m${i}`);
    expect(useMessengerStore.getState().selectedMessageIds).toHaveLength(REPORT_PICK_MAX);
    render(<ReportPickBar chat={chat} />);
    expect(screen.getByTestId('report-pick-max').textContent).toContain('Максимум 10');
  });

  it('звичайний вибір кількох не обмежений десятьма', () => {
    const st = useMessengerStore.getState();
    st.setMultiSelectMode(true);
    for (let i = 0; i < 12; i++) useMessengerStore.getState().toggleSelectMessage(`m${i}`);
    expect(useMessengerStore.getState().selectedMessageIds).toHaveLength(12);
  });
});
