/**
 * «Прочитано» (25.09): read_at / fan_read з мосту → ✓✓; перемикач приватності
 * й список заблокованих у налаштуваннях телефона.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { messageFromNode, messengerApi } from '../services/messengerApi';
import type { NodeMessage } from '../services/messengerApi';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';

const row = (over: Partial<NodeMessage>): NodeMessage => ({
  id: 'm1', conversation_id: 'c1', client_id: 'x', seq: 1, author_id: 'me', author_name: 'Я', kind: 'text',
  body: 'привіт', ciphertext: null, transport: 'p2p', sent_at: '2026-09-25T10:00:00Z', edited_at: null, deleted_at: null,
  delivery_state: 'delivered', ...over,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('прочитано з мосту', () => {
  it('read_at стає readAt; без поля — невідомо', () => {
    expect(messageFromNode(row({ read_at: '2026-09-25T10:05:00Z' }), 'me', 'peer').readAt).toBe('2026-09-25T10:05:00Z');
    expect(messageFromNode(row({}), 'me', 'peer').readAt).toBeUndefined();
  });
  it('fan_read — лише під власним груповим рядком', () => {
    expect(messageFromNode(row({ fan_total: 3, fan_delivered: 3, fan_read: 2 }), 'me').fanRead).toBe(2);
    expect(messageFromNode(row({ author_id: 'other', fan_total: 3, fan_read: 2 }), 'me').fanRead).toBeUndefined();
  });
});

describe('налаштування: приватність', () => {
  // Приватність живе в «Звук і приватність» усередині «Я».
  const open = () => {
    render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    fireEvent.click(screen.getByTestId('me-prefs'));
    fireEvent.click(screen.getByTestId('settings-row-privacy'));
  };

  it('перемикач звітів кличе міст і показує його відповідь', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
    const set = vi.spyOn(messengerApi, 'setPrivacy').mockResolvedValue({ read_receipts: false });
    open();
    const sw = await screen.findByText('Звіти про прочитання');
    fireEvent.click(screen.getByLabelText('Звіти про прочитання'));
    void sw;
    await waitFor(() => expect(set).toHaveBeenCalledWith(false));
    await waitFor(() => expect(screen.getByLabelText('Звіти про прочитання').getAttribute('aria-checked')).toBe('false'));
  });

  it('заблоковані — з іменами й розблокуванням', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'privacy').mockRejectedValue(new Error('старий міст'));
    vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: ['p1'] });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([{ peer_node_id: 'p1', display_name: 'Ігор' } as never]);
    const un = vi.spyOn(messengerApi, 'unblockPeer').mockResolvedValue({ blocked: false });
    open();
    fireEvent.click((await screen.findByText('Заблоковані')).closest('button')!);
    expect(screen.getByText('Ігор')).toBeTruthy();
    fireEvent.click(screen.getByText('Розблокувати'));
    await waitFor(() => expect(un).toHaveBeenCalledWith('p1'));
    await waitFor(() => expect(screen.queryByText('Ігор')).toBeNull());
    // Старий міст без /privacy — перемикача немає, а не «вимкнено».
    expect(screen.queryByText('Звіти про прочитання')).toBeNull();
  });
});
