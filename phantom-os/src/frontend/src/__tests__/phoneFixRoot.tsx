/** Справжній корінь месенджера з рядками вузла — як на телефоні (збірка 19, 10.10). */
import { afterEach, beforeAll, beforeEach, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { chatArchive } from '../services/chatArchive';

export const conv = (id: string, title: string, kind: string, extra: Partial<NodeConversation> = {}): NodeConversation =>
  ({
    id, title, kind, circle: 'all', handle: null, avatar: null, pinned: kind === 'phantom', archived: false,
    created_at: '2026-10-10T05:00:00', updated_at: '2026-10-10T07:00:00', ...extra,
  }) as NodeConversation;

export const mountRoot = async (rows: NodeConversation[]) => {
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue(rows);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  render(
    <MemoryRouter>
      <MessengerRoot />
    </MemoryRouter>,
  );
  const list = await screen.findByTestId('chat-list');
  await waitFor(() => expect(list.querySelector(`[data-chat-row="${rows[rows.length - 1].id}"]`)).toBeTruthy());
  return list;
};

export const previewLine = (id: string) =>
  document.querySelector(`[data-chat-row="${id}"] [data-testid="chat-preview-line"]`) as HTMLElement | null;

export const useRootHooks = () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });
  beforeEach(() => chatArchive.reset());
  afterEach(() => {
    vi.restoreAllMocks();
    chatArchive.reset();
  });
};
