/**
 * Скарга з меню листа (dApp Store Publisher Policy: контент людей мусить мати
 * спосіб поскаржитися). Модерації на сервері немає і не вигадуємо: міст телефона
 * блокує людину й відкриває лист у підтримку з ідентифікаторами.
 */
import { useMessengerStore } from '../stores/messengerStore';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { messengerApi } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

const direct = { id: 'c1', title: 'Оксана', type: 'direct', peerNodeId: 'oksana' } as Chat;
const theirs = { id: 'm-1', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'привіт' } as Message;
const mineMsg = { ...theirs, id: 'm-2', senderId: 'me' } as Message;

const handlers = (report?: (m: Message) => void) =>
  new Proxy({}, { get: (_t, k) => (k === 'report' ? report : () => {}) }) as MessageSheetHandlers;

const draw = (m: Message, on: MessageSheetHandlers) =>
  render(<MessageActionSheet msg={m} currentChat={direct} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);

afterEach(() => {
  cleanup();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('«Поскаржитися» в меню листа', () => {
  it('є в чужого листа і віддає сам лист', () => {
    draw(theirs, handlers(vi.fn()));
    fireEvent.click(screen.getByRole('button', { name: 'Поскаржитися' }));
    const st = useMessengerStore.getState();
    expect(st.reportPick).toBe(true);
    expect(st.selectedMessageIds).toEqual([theirs.id]);
  });

  it('немає у власного листа', () => {
    draw(mineMsg, handlers(vi.fn()));
    expect(screen.queryByRole('button', { name: 'Поскаржитися' })).toBeNull();
  });

  it('міст отримує людину й ідентифікатор листа, без тексту', async () => {
    const calls: Array<{ m: string; p: string; b: unknown }> = [];
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
      call: (m: string, p: string, b: string | null) => {
        calls.push({ m, p, b: b ? JSON.parse(b) : null });
        return JSON.stringify({ ok: true, status: 200, data: { blocked: true, mailed: true, address: 'support@phantom-os.dev' } });
      },
    };
    const r = await messengerApi.reportPeer('oksana', 'm-1');
    expect(r.blocked).toBe(true);
    expect(calls).toEqual([{ m: 'POST', p: '/messenger/contacts/oksana/report', b: { message_id: 'm-1' } }]);
  });
});
