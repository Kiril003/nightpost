/**
 * Дії Solana з розмови: «Переказати» і «Засвідчити» (гілка cloud/sol-chat-actions).
 *
 * До цього картка `SolanaCard` уже малювалась у стрічці, а дій у вебі не було:
 * маршрути мосту `/messenger/solana/{peer,pay,seal}` ніхто не кликав. Тут
 * доводиться:
 *   1. «Переказати» в меню композера — лише коли міст каже `can_pay` і моя
 *      адреса є; старий APK (404), ПК без мосту — пункту немає;
 *   2. POST /pay несе рівно {peer_id, token, amount} — те, що читає
 *      `WebMessengerSolana.payAsk`; SKR — лише в mainnet; комісії немає;
 *   3. «Засвідчити» — лише в листа з файлом, лише в розмові вдвох на телефоні;
 *      POST /seal — рівно {sha256, label, peer_id}, відбиток РОЗШИФРОВАНОГО файла;
 *   4. досяжність: рядок стрічки (buildFeedRows) → аркуш дій → аркуш печатки →
 *      міст; і картка, яку телефон пришле, малюється з того самого рядка.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import React from 'react';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

vi.mock('../services/messengerMedia', async (orig) => ({
  ...(await orig<typeof import('../services/messengerMedia')>()),
  loadMedia: vi.fn(async () => 'blob:phantom/photo'),
}));

import { MessageComposer } from '../components/messenger/MessageComposer';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { MessageBody } from '../components/messenger/feed/MessageBody';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { SolanaSheets } from '../components/messenger/solana/SolanaSheets';
const SEAL_SENTENCE = 'У мережу йде лише відбиток файла — не сам файл і не місце.';
import { amountOf, askSolana, canSeal, coinsFor } from '../services/solanaActions';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

const ME = 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH';
const SIG = '4WVSq3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgmZHT';
/** sha256("hello") */
const HELLO = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824';

type Peer = { address: string | null; can_pay: boolean; cluster: 'devnet' | 'mainnet'; seeker: boolean } | 'gone';
let peer: Peer;
let calls: Array<{ m: string; p: string; b: unknown }>;

function bridge(m: string, p: string, b: string | null): string {
  calls.push({ m, p, b: b ? JSON.parse(b) : null });
  if (p.startsWith('/messenger/solana/peer/')) {
    if (peer === 'gone') return JSON.stringify({ ok: false, status: 404, code: 'not_found', detail: 'not_found' });
    return JSON.stringify({ ok: true, status: 200, data: peer });
  }
  if (p === '/messenger/solana/pay' || p === '/messenger/solana/seal') {
    return JSON.stringify({ ok: true, status: 200, data: { opened: true } });
  }
  return JSON.stringify({ ok: true, status: 200, data: {} });
}

const withBridge = () => {
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: bridge };
};

beforeEach(() => {
  peer = { address: ME, can_pay: true, cluster: 'devnet', seeker: false };
  calls = [];
  withBridge();
});

afterEach(() => {
  act(() => askSolana(null));
  cleanup();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  vi.unstubAllGlobals();
});

const flush = () => act(async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve();
});

const oksana = { id: 'oksana', name: 'Оксана' };

const composerProps = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'oksana',
};

/** Композер, як у MessengerRoot: поруч господар аркушів. */
const drawComposer = (wherePeer: { id: string; name: string } | null = oksana) =>
  render(
    <>
      <MessageComposer {...composerProps} wherePeer={wherePeer} />
      <SolanaSheets />
    </>,
  );

const openMore = async () => {
  await flush();
  fireEvent.click(screen.getByTestId('composer-attach'));
  await flush();
};

describe('«Переказати» в меню композера', () => {
  it('є, коли міст каже can_pay і мій гаманець є', async () => {
    drawComposer();
    await openMore();
    expect(screen.getByTestId('send-money').textContent).toContain('Переказати');
    expect(calls.some((c) => c.m === 'GET' && c.p === '/messenger/solana/peer/oksana')).toBe(true);
  });

  for (const [why, p] of [
    ['у людини ще немає адреси', { address: ME, can_pay: false, cluster: 'devnet', seeker: false }],
    ['у мене немає гаманця', { address: null, can_pay: false, cluster: 'devnet', seeker: false }],
    ['старий APK без маршруту (404)', 'gone'],
  ] as const) {
    it(`немає, коли ${why}`, async () => {
      peer = p as Peer;
      drawComposer();
      await openMore();
      expect(screen.queryByTestId('send-money')).toBeNull();
    });
  }

  it('немає в групі (немає людини вдвох)', async () => {
    drawComposer(null);
    await openMore();
    expect(screen.queryByTestId('send-money')).toBeNull();
    expect(calls.some((c) => c.p.startsWith('/messenger/solana/'))).toBe(false);
  });

  it('у групі з учасниками на телефоні є send-group-money, а send-money немає', async () => {
    render(
      <>
        <MessageComposer
          {...composerProps}
          wherePeer={null}
          chatMembers={[
            { id: 'oksana', name: 'Оксана', handle: '@oksana', avatar: '', role: 'member' },
            { id: 'taras', name: 'Тарас', handle: '@taras', avatar: '', role: 'member' },
          ]}
        />
        <SolanaSheets />
      </>,
    );
    await openMore();
    expect(screen.getByTestId('send-group-money')).toBeTruthy();
    expect(screen.queryByTestId('send-money')).toBeNull();
  });

  it('немає на ПК: без мосту вузол не питаємо зовсім', async () => {
    delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
    const fetchSpy = vi.fn(async () => new Response('{}', { status: 404 }));
    vi.stubGlobal('fetch', fetchSpy);
    drawComposer();
    await openMore();
    expect(screen.queryByTestId('send-money')).toBeNull();
    expect((fetchSpy.mock.calls as unknown[][]).some(([u]) => String(u).includes('/messenger/solana/'))).toBe(false);
  });

  it('аркуш: USDC за замовчуванням, POST /pay — рівно {peer_id, token, amount}', async () => {
    drawComposer();
    await openMore();
    fireEvent.click(screen.getByTestId('send-money'));
    expect(screen.getByTestId('solana-pay-sheet')).toBeTruthy();
    expect(screen.getByTestId('solana-coin-USDC').getAttribute('aria-pressed')).toBe('true');
    expect(screen.queryByTestId('solana-coin-SKR')).toBeNull();
    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '2,50' } });
    fireEvent.click(screen.getByRole('button', { name: 'Переказати 2,5 USDC' }));
    await flush();
    const post = calls.filter((c) => c.m === 'POST' && c.p === '/messenger/solana/pay');
    expect(post).toHaveLength(1);
    expect(post[0].b).toEqual({ peer_id: 'oksana', token: 'USDC', amount: '2.5' });
    // Міст сам шле картку після підпису: веб у розмову нічого не пише.
    expect(calls.some((c) => c.m === 'POST' && c.p.includes('/messages'))).toBe(false);
    expect(screen.queryByTestId('solana-pay-sheet')).toBeNull();
  });

  it('SOL обирається; крива сума кнопку не вмикає; комісії на аркуші немає', async () => {
    drawComposer();
    await openMore();
    fireEvent.click(screen.getByTestId('send-money'));
    fireEvent.click(screen.getByTestId('solana-coin-SOL'));
    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '0' } });
    const btn = screen.getByRole('button', { name: 'Переказати' }) as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(screen.getByTestId('solana-pay-sheet').textContent?.toLowerCase()).not.toMatch(/комісі|fee|lamport/);
    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '0.001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Переказати 0,001 SOL' }));
    await flush();
    expect(calls.find((c) => c.p === '/messenger/solana/pay')?.b).toEqual({ peer_id: 'oksana', token: 'SOL', amount: '0.001' });
  });

  it('EURC стоїть другим і доїжджає до телефона як є', async () => {
    drawComposer();
    await openMore();
    fireEvent.click(screen.getByTestId('send-money'));
    const chips = Array.from(screen.getByRole('group', { name: 'Монета' }).querySelectorAll('button')).map((b) => b.textContent);
    expect(chips).toEqual(['USDC', 'EURC', 'SOL']);
    fireEvent.click(screen.getByTestId('solana-coin-EURC'));
    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Переказати 5 EURC' }));
    await flush();
    expect(calls.find((c) => c.p === '/messenger/solana/pay')?.b).toEqual({ peer_id: 'oksana', token: 'EURC', amount: '5' });
  });

  it('порядок як у Coin.on: USDC, EURC, SOL; SKR — лише коли міст каже mainnet', () => {
    expect(coinsFor('devnet')).toEqual(['USDC', 'EURC', 'SOL']);
    expect(coinsFor('mainnet')).toEqual(['USDC', 'EURC', 'SOL', 'SKR']);
  });

  it('сума як у Amounts.toUnits: кома, нуль, мінус, зайві знаки', () => {
    expect(amountOf('2,5', 'USDC')).toBe('2.5');
    expect(amountOf(' 10 ', 'USDC')).toBe('10');
    expect(amountOf('0010.100', 'USDC')).toBe('10.1');
    expect(amountOf('0', 'USDC')).toBeNull();
    expect(amountOf('0.000', 'SOL')).toBeNull();
    expect(amountOf('-1', 'USDC')).toBeNull();
    expect(amountOf('1e3', 'USDC')).toBeNull();
    expect(amountOf('0.0000001', 'USDC')).toBeNull();
    expect(amountOf('0.0000001', 'EURC')).toBeNull();
    expect(amountOf('0,000001', 'EURC')).toBe('0.000001');
    expect(amountOf('0.000000001', 'SOL')).toBe('0.000000001');
  });
});

// ── «Засвідчити» ──

const direct: Chat = { id: 'c1', title: 'Оксана', type: 'direct', peerNodeId: 'oksana' } as Chat;
const group: Chat = { id: 'g1', title: 'Група', type: 'group', peerNodeId: 'g' } as Chat;

const photo = (over: Partial<Message> = {}): Message =>
  ({
    id: 'm-photo',
    senderId: 'oksana',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '10:00',
    sentAt: '2026-10-02T10:00:00Z',
    type: 'image',
    media: { name: 'pidpys.jpg', size: 5, mime: 'image/jpeg', sha256: 'c'.repeat(64), blobId: 'b1', keyHex: '', nonceHex: '' },
    ...over,
  }) as Message;

const textMsg: Message = { id: 'm-text', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'привіт' } as Message;

const handlers = new Proxy({}, { get: () => () => {} }) as MessageSheetHandlers;

const drawSheet = (m: Message, chat: Chat = direct) =>
  render(
    <>
      <MessageActionSheet msg={m} currentChat={chat} currentUserId="me" speaking={false} onClose={() => {}} on={handlers} />
      <SolanaSheets />
    </>,
  );

describe('«Засвідчити» в меню фото/файла', () => {
  it('лише для листа з файлом', () => {
    expect(canSeal(photo(), direct)).toBe(true);
    expect(canSeal(textMsg, direct)).toBe(false);
    expect(canSeal(photo({ isDeleted: true }), direct)).toBe(false);
    expect(canSeal(photo({ attachmentState: 'upload_failed' }), direct)).toBe(false);
    drawSheet(textMsg);
    expect(screen.queryByRole('button', { name: 'Засвідчити' })).toBeNull();
  });

  it('немає в групі і на ПК', () => {
    expect(canSeal(photo(), group)).toBe(false);
    delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
    expect(canSeal(photo(), direct)).toBe(false);
    drawSheet(photo());
    expect(screen.queryByRole('button', { name: 'Засвідчити' })).toBeNull();
  });

  it('досяжність: рядок стрічки → аркуш дій → печатка → міст → картка печатки', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new TextEncoder().encode('hello'))));
    const [row] = buildFeedRows([photo()], 'me');
    drawSheet(row.msg);
    fireEvent.click(screen.getByRole('button', { name: 'Засвідчити' }));
    const sheet = screen.getByTestId('solana-seal-sheet');
    expect(sheet.textContent).toContain(SEAL_SENTENCE);
    expect(sheet.textContent).toContain('pidpys.jpg');
    fireEvent.click(screen.getAllByRole('button', { name: 'Засвідчити' }).at(-1)!);
    await waitFor(() => expect(calls.some((c) => c.p === '/messenger/solana/seal')).toBe(true));
    const post = calls.filter((c) => c.m === 'POST' && c.p === '/messenger/solana/seal');
    expect(post).toHaveLength(1);
    expect(post[0].b).toEqual({ sha256: HELLO, label: 'pidpys.jpg', peer_id: 'oksana' });
    await waitFor(() => expect(screen.queryByTestId('solana-seal-sheet')).toBeNull());
    cleanup();

    // Телефон після підпису шле картку сам — вона приходить звичайним рядком.
    const said = messageFromNode(
      {
        id: 'm-seal', conversation_id: 'c1', client_id: 'x', seq: 2, author_id: 'me', author_name: 'Я',
        kind: 'text', body: 'Засвідчено в мережі: відбиток файла 2cf24d…', ciphertext: null,
        transport: 'p2p', sent_at: '2026-10-02T10:01:00Z', edited_at: null, deleted_at: null,
        seal: { sha256: HELLO, signature: SIG, cluster: 'devnet', status: 'pending' },
      } as unknown as NodeMessage,
      'me',
      'oksana',
    );
    const [card] = buildFeedRows([said], 'me');
    const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
    render(
      <FeedActionsContext.Provider value={actions}>
        <MessageBody row={card} phone currentUserId="me" currentUserName="Я" isActiveSearchMatch={false} expanded={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
    expect(screen.getByTestId('solana-card').textContent).toContain('Відбиток файла');
  });
});

// ── дзеркало з Kotlin: поля, які міст справді читає ──

const KT = join(process.cwd(), '../../../phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerSolana.kt');

describe.skipIf(!existsSync(KT))('контракт з WebMessengerSolana.kt', () => {
  const src = existsSync(KT) ? readFileSync(KT, 'utf8') : '';
  it('pay читає peer_id, token, amount; seal — sha256, label, peer_id', () => {
    for (const k of ['peer_id', 'token', 'amount', 'sha256', 'label']) expect(src).toContain(`"${k}"`);
    expect(src).toMatch(/"peer" && method == "GET"/);
  });
});

void React;
