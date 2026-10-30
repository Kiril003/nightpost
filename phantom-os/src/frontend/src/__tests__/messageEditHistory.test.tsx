import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Message } from '../types/messenger';

const history = vi.fn();
vi.mock('../services/messengerApi', () => ({ messengerApi: { messageHistory: (...a: unknown[]) => history(...a) } }));

import { MessageDetailsModal } from '../components/messenger/MessageDetailsModal';

const msg = (extra: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    chatId: 'd1',
    senderId: 'them',
    senderName: 'Оксана',
    text: 'о восьмій',
    type: 'text',
    timestamp: '19:40',
    sentAt: '2026-10-24T16:40:00Z',
    isSelf: false,
    isEdited: true,
    ...extra,
  }) as Message;

afterEach(() => {
  cleanup();
  history.mockReset();
});

describe('Історія змін', () => {
  it('змінений лист показує всі версії, новіша вгорі', async () => {
    history.mockResolvedValue({
      versions: [
        { text: 'о восьмій', at: '2026-10-24T16:45:00Z' },
        { text: 'о сьомій', at: '2026-10-24T16:42:00Z' },
        { text: 'о шостій', at: '2026-10-24T16:40:00Z' },
      ],
    });
    render(<MessageDetailsModal isOpen message={msg()} conversationId="d1" onClose={() => {}} />);
    const list = await screen.findByTestId('message-history');
    expect(history).toHaveBeenCalledWith('d1', 'm1');
    const rows = Array.from(list.querySelectorAll('li')).map((li) => li.textContent);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toBe('Заразо восьмій');
    expect(rows[1]).toContain('о сьомій');
    expect(rows[2]).toContain('о шостій');
    expect(screen.getByText('Історія змін')).toBeTruthy();
  });

  it('без збережених версій лишається чесне «Змінено: так», а незмінений лист нічого не питає', async () => {
    history.mockResolvedValue({ versions: [{ text: 'о восьмій', at: '2026-10-24T16:45:00Z' }] });
    render(<MessageDetailsModal isOpen message={msg()} conversationId="d1" onClose={() => {}} />);
    await waitFor(() => expect(history).toHaveBeenCalled());
    expect(screen.queryByTestId('message-history')).toBeNull();
    expect(document.body.textContent).toContain('Змінено');
    cleanup();
    history.mockReset();
    render(<MessageDetailsModal isOpen message={msg({ isEdited: false })} conversationId="d1" onClose={() => {}} />);
    expect(history).not.toHaveBeenCalled();
  });

  it('екран розмови передає подробицям, у якій розмові лист', () => {
    const area = readFileSync(resolve(__dirname, '../components/messenger/ChatArea.tsx'), 'utf8');
    expect(area).toMatch(/<MessageDetailsModal[\s\S]*?conversationId=\{currentChat\?\.id\}/);
  });
});
