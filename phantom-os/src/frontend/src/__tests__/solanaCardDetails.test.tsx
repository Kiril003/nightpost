/**
 * Мережа й відбиток транзакції — не на картці переказу, а в «Подробицях»
 * (UI-audit 14.10 №3): картка в розмові читається як квитанція, а devnet,
 * хеш і посилання в оглядач лежать за дотиком до «Подробиць».
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import { messageFromNode } from '../services/messengerApi';
import type { NodeMessage } from '../services/messengerApi';
import { MessageDetailsModal } from '../components/messenger/MessageDetailsModal';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { MessageBody } from '../components/messenger/feed/MessageBody';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Chat, Message } from '../types/messenger';

vi.mock('../utils/haptic', () => ({ haptic: vi.fn(() => true) }));

const TO = 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH';
const SIG = '4WVSq3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgmZHT';
const SHA = '3f2a9c0b1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e';

const PAY = {
  amount: '2.5', token: 'USDC', units: '2500000', decimals: 6, cluster: 'devnet',
  to: TO, signature: SIG, offline_cash: false, status: 'confirmed', to_mine: false,
};

const row = (over: Record<string, unknown>): NodeMessage =>
  ({
    id: 'm1', conversation_id: 'c1', client_id: 'x', seq: 1, author_id: 'me', author_name: 'Я',
    kind: 'text', body: '2.5 USDC to Оксана · Confirmed', ciphertext: null,
    transport: 'p2p', sent_at: '2026-10-02T10:00:00Z', edited_at: null, deleted_at: null,
    ...over,
  }) as NodeMessage;

const payMsg = (p: Record<string, unknown> = {}) => messageFromNode(row({ payment: { ...PAY, ...p } }), 'me', 'peer');
const sealMsg = () =>
  messageFromNode(row({ seal: { sha256: SHA, signature: SIG, cluster: 'devnet', status: 'confirmed', block_time: 1759406700 } }), 'me', 'peer');
const textMsg = () => messageFromNode(row({ body: 'Привіт' }), 'me', 'peer');

const open = (m: Message) => render(<MessageDetailsModal isOpen message={m} onClose={() => {}} />);

afterEach(() => cleanup());

describe('Подробиці переказу', () => {
  it('devnet: мережа словом і відбиток транзакції посиланням в оглядач', () => {
    open(payMsg());
    const body = document.body.textContent ?? '';
    expect(body).toContain('Мережа');
    expect(body).toContain('Devnet');
    const link = screen.getByRole('link', { name: /Перевірити в мережі/ }) as HTMLAnchorElement;
    expect(link.href).toBe(`https://explorer.solana.com/tx/${SIG}?cluster=devnet`);
    expect(link.rel).toContain('noopener');
    expect(link.textContent).toBe('4WVS…mZHT');
  });

  it('mainnet: посилання без параметра, мережа названа', () => {
    open(payMsg({ cluster: 'mainnet' }));
    expect(document.body.textContent).toContain('Mainnet');
    expect((screen.getByRole('link', { name: /Перевірити в мережі/ }) as HTMLAnchorElement).href).toBe(`https://explorer.solana.com/tx/${SIG}`);
  });

  it('печатка файла має ті самі рядки', () => {
    open(sealMsg());
    expect(document.body.textContent).toContain('Devnet');
    expect(screen.getByRole('link', { name: /Перевірити в мережі/ })).toBeTruthy();
  });

  it('готівка, що ще не в мережі, — мережа є, а шукати транзакцію ніде', () => {
    open(payMsg({ offline_cash: true, status: 'pending' }));
    expect(document.body.textContent).toContain('Devnet');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('звичайний лист — без рядків мережі й транзакції', () => {
    open(textMsg());
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/Мережа|Devnet|Транзакція/);
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe('досяжність: картка в стрічці → «Ще» → «Подробиці» → відбиток', () => {
  const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
  const direct = { id: 'c1', title: 'Оксана', type: 'direct', circle: 'friends', messages: [], unreadCount: 0, pinned: false } as unknown as Chat;

  it('на картці в рядку стрічки його немає, а через меню дій він доступний', () => {
    const m = payMsg();
    const [r] = buildFeedRows([m], 'me');
    const feed = render(
      <FeedActionsContext.Provider value={actions}>
        <MessageBody row={r} phone currentUserId="me" currentUserName="Я" isActiveSearchMatch={false} expanded={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
    expect(feed.container.querySelector('a')).toBeNull();
    expect(feed.container.textContent?.toLowerCase()).not.toContain('devnet');
    cleanup();

    const inspect = vi.fn();
    const on = new Proxy({ inspect }, { get: (t, k) => (k in t ? (t as Record<string | symbol, unknown>)[k] : () => {}) }) as unknown as MessageSheetHandlers;
    render(<MessageActionSheet msg={m} currentChat={direct} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);
    fireEvent.click(screen.getByRole('button', { name: 'Ще' }));
    fireEvent.click(screen.getByRole('button', { name: 'Подробиці' }));
    expect(inspect).toHaveBeenCalledWith(m);
    cleanup();

    open(inspect.mock.calls[0][0] as Message);
    expect(within(document.body).getByRole('link', { name: /Перевірити в мережі/ })).toBeTruthy();
  });
});
