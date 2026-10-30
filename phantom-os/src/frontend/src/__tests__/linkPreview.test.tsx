/**
 * Картка посилання від відправника: веб лише показує те, що приїхало
 * зашифрованим із листом, і сам за посиланням не ходить.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import type { Chat } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(cleanup);

const PEER = 'a1b2c3d4e5f60718';
const JPEG = 'data:image/jpeg;base64,/9j/4AAQSkZJRg==';
const row = (over: Partial<NodeMessage>): NodeMessage =>
  ({ id: 'm1', conversation_id: 'peer_ok', client_id: 'c', seq: 1, author_id: PEER, author_name: 'Оксана',
    kind: 'text', body: 'читай https://news.example/a', ciphertext: null, transport: null,
    sent_at: '2026-10-02T10:00:00Z', edited_at: null, deleted_at: null, ...over }) as unknown as NodeMessage;
const card = { url: 'https://news.example/a', title: 'Новина дня', description: 'Опис', site: 'news.example', image: JPEG };

describe('картка посилання', () => {
  it('приходить полем рядка; чужа адреса чи зовнішня картинка — ні', () => {
    expect(messageFromNode(row({ link_preview: card }), 'me', PEER).linkPreview).toEqual(card);
    expect(messageFromNode(row({}), 'me', PEER).linkPreview).toBeUndefined();
    expect(messageFromNode(row({ link_preview: { ...card, url: 'http://news.example/a' } }), 'me', PEER).linkPreview).toBeUndefined();
    const remote = messageFromNode(row({ link_preview: { ...card, image: 'https://tracker.example/pixel.jpg' } }), 'me', PEER);
    expect(remote.linkPreview?.image).toBeUndefined();
  });

  it('у стрічці — під текстом, мініатюра лише з листа', () => {
    const chat = { id: 'peer_ok', title: 'Оксана', type: 'dm', peerNodeId: PEER, messages: [], unreadCount: 0 } as unknown as Chat;
    const m = messageFromNode(row({ link_preview: card }), 'me', PEER);
    const noop = () => {};
    render(
      <MemoryRouter>
        <ChatArea currentChat={chat} messages={[m]} currentUserId="me" onOpenLocation={noop} onVotePoll={noop}
          onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop} selectedMessageIds={[]}
          onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop} />
      </MemoryRouter>,
    );
    const el = screen.getByTestId('link-preview');
    expect(el.getAttribute('href')).toBe('https://news.example/a');
    expect(el.textContent).toContain('Новина дня');
    expect(el.textContent).toContain('news.example');
    expect(el.querySelector('img')?.getAttribute('src')).toBe(JPEG);
    expect(el.outerHTML).not.toMatch(/\b(text|bg|rounded)-f-/);
  });
});
