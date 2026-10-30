/**
 * Core sweep (core-chat): службові рядки листа не виходять до людини ніде,
 * де її текст показується вдруге — список, пошук, копіювання, цитата (C8–C10,
 * C24); підписи мовою інтерфейсу (C15–C17); тестові гроші підписані (C23).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { plainText } from '../services/plainText';
import { withTag } from '../services/workEvents';
import { encodeOrder, encodeStatus, type Order } from '../services/business/orders';
import { composeEdu } from '../services/edu/eduWire';
import { plainSnippet } from '../components/messenger/chatListFormat';
import { searchableFields } from '../services/messageSearch';
import { lettersFromNode } from '../services/globalSearch';
import { quoteBlock } from '../services/msgSelection';
import { letterText } from '../services/letterActions';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { SolanaCard } from '../components/messenger/feed/SolanaCard';
import { OrderCard } from '../components/messenger/directions/OrderCard';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { OLENA, billMsg, installBridge, removeBridge } from './meetupFixtures';
import { mediaLimitLabel } from '../services/messengerMedia';
import { noTasksReply } from '../components/messenger/feed/useMessageAssist';
import { translate } from '../i18n';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

const setLocale = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

const WORK = withTag('Зробити звіт до пʼятниці', 'task', { id: 't1', st: 'todo' });
const ORDER: Order = { v: 1, id: 'A-217', items: [{ name: 'Кава', qty: 2, cents: 5000 }], currency: 'USDC', pay: 'devnet', pickup: '', code: '' };
const ORD = encodeOrder(ORDER, (k) => ({ order: 'Замовлення', total: 'Разом', pickup: 'Забрати' })[k]);
const ORS = encodeStatus('A-217', 'ready', 'Замовлення A-217: Готове');
const EDU = composeEdu(['Завдання · Лабораторна 4', 'До пт 23:59'], { k: 'assign', id: 'a1', t: 'Лаб', due: null, note: '', at: 1 });
const LEAKS = /\[np:|PH-ORD:|PH-ORS:|⟦edu1:/;

const msg = (id: string, text: string, over: Partial<Message> = {}): Message =>
  ({ id, senderId: 'a', senderName: 'Олена', text, timestamp: '10:00', status: 'sent', type: 'text', senderAvatar: '', ...over }) as Message;

afterEach(() => {
  cleanup();
  setLocale('uk');
});

describe('один простий текст листа (plainText)', () => {
  it('знімає позначку роботи, рядки картки й мітку навчання, лишає слова', () => {
    expect(plainText(WORK)).toBe('Зробити звіт до пʼятниці');
    expect(plainText(ORS)).toBe('Замовлення A-217: Готове');
    expect(plainText(ORD)).not.toMatch(LEAKS);
    expect(plainText(ORD)).toContain('Кава');
    expect(plainText(EDU)).toBe('Завдання · Лабораторна 4\nДо пт 23:59');
    expect(plainText('привіт\nяк ти')).toBe('привіт\nяк ти');
  });

  it('рядок людини, що лише починається з [np:, лишається', () => {
    expect(plainText('план\n[np: купити хліб і молоко')).toBe('план\n[np: купити хліб і молоко');
    expect(plainText('план\n[np:todo buy milk')).toBe('план\n[np:todo buy milk');
  });

  it('позначка, обрізана на 90 знаках у рядку вузла, теж не видна', () => {
    expect(plainText(WORK.slice(0, WORK.length - 6))).toBe('Зробити звіт до пʼятниці');
  });
});

describe('C8: превʼю в списку розмов без службових рядків', () => {
  const USER = { id: 'me', name: 'Я', avatar: '' } as unknown as UserProfile;
  const chat = (id: string, title: string, lastSnippet: string): Chat =>
    ({ id, title, type: 'dm', circle: 'friends', messages: [], unreadCount: 0, lastSnippet, lastAt: new Date().toISOString(), lastSelfAt: new Date().toISOString() }) as unknown as Chat;

  beforeEach(() => localStorage.clear());

  it('рядок списку показує слова, а не [np:…] / PH-ORS: / ⟦edu1', () => {
    render(
      <PhoneChatList
        chats={[chat('w', 'Робота', WORK), chat('o', 'Кавʼярня', ORS), chat('e', 'Клас', EDU), chat('cut', 'Обрізане', WORK.slice(0, WORK.length - 6))]}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    const list = document.querySelector('[data-phantom-list]')!.textContent!;
    expect(list).not.toMatch(LEAKS);
    expect(list).toContain('Зробити звіт до пʼятниці');
    expect(list).toContain('Замовлення A-217: Готове');
  });

  it('plainSnippet (список, цитата, закріплене) не несе позначок', () => {
    expect(plainSnippet(WORK)).toBe('Зробити звіт до пʼятниці');
    expect(plainSnippet(ORD)).not.toMatch(LEAKS);
  });
});

describe('C9: пошук індексує те, що видно в бульбашці', () => {
  it('поле тексту листа без позначок — «np» чи «PH-ORD» не знаходиться', () => {
    for (const t of [WORK, ORD, ORS, EDU]) {
      for (const [, v] of searchableFields(msg('m', t))) expect(v).not.toMatch(LEAKS);
    }
  });

  it('знахідка вузла показує фрагмент без позначки', () => {
    const chats = [{ id: 'c1', title: 'Робота', messages: [] } as unknown as Chat];
    const out = lettersFromNode(
      [{ conversation_id: 'c1', message_id: 'm1', text: WORK, field: 'text', author_name: 'Олена', sent_at: '' } as never],
      chats,
      'звіт',
    );
    expect(out).toHaveLength(1);
    expect(out[0].fragment.text).not.toMatch(LEAKS);
  });

  it('автор без імені — мовою інтерфейсу, не «Ви» українською', () => {
    setLocale('en');
    const chats = [{ id: 'c1', title: 'Робота', messages: [] } as unknown as Chat];
    const out = lettersFromNode([{ conversation_id: 'c1', message_id: 'm1', text: 'звіт', field: 'text', sent_at: '' } as never], chats, 'звіт');
    expect(out[0].author).toBe('You');
  });
});

describe('C10 / C24: копіювання й цитата без позначок', () => {
  const copied: string[] = [];
  const fn = vi.fn();
  const on = Object.fromEntries(
    ['reply', 'forward', 'edit', 'react', 'openReactionPicker', 'speak', 'openCanvas', 'toast', 'translate', 'summarize', 'actionItems', 'togglePin', 'bookmark', 'select', 'inspect', 'remove'].map((k) => [k, fn]),
  ) as unknown as MessageSheetHandlers;
  on.copyText = (t: string) => void copied.push(t);
  const dm = { id: 'd', title: 'Олена', type: 'dm', avatar: '', unreadCount: 0, circle: 'friends', messages: [] } as unknown as Chat;

  it('«Копіювати» кладе в буфер слова листа, а не PH-ORD:/[np:]', () => {
    for (const text of [WORK, ORD]) {
      copied.length = 0;
      render(<MessageActionSheet msg={msg('m1', text)} currentChat={dm} currentUserId="me" speaking={false} onClose={vi.fn()} on={on} />);
      fireEvent.click(screen.getByText(translate('messageActionSheet.copy', 'uk')));
      expect(copied).toHaveLength(1);
      expect(copied[0]).not.toMatch(LEAKS);
      expect(document.body.textContent).not.toMatch(LEAKS);
      cleanup();
    }
  });

  it('цитата у відповіді показує слова, а не PH-ORD:/[np:]', () => {
    for (const quoted of [ORD, WORK]) {
      const reply = msg('r1', 'так', { replyTo: { id: 'm1', senderName: 'Олена', text: quoted } as Message['replyTo'] });
      const [row] = buildFeedRows([reply], 'me', new Date('2026-10-15T15:00:00Z'));
      const { container } = render(
        <FeedActionsContext.Provider value={new Proxy({}, { get: () => () => {} }) as FeedActions}>
          <MessageRow
            row={row} chatId="d" currentUserId="me" currentUserName="Я" groupish={false} direct phone
            isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
            isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
          />
        </FeedActionsContext.Provider>,
      );
      expect(container.textContent).toContain(quoted === WORK ? 'Зробити звіт' : 'Кава');
      expect(container.textContent).not.toMatch(LEAKS);
      cleanup();
    }
  });

  it('вибір кількох і дії з листа теж беруть простий текст', () => {
    expect(quoteBlock([msg('m1', WORK)])).not.toMatch(LEAKS);
    expect(letterText(msg('m1', ORD))).not.toMatch(LEAKS);
  });
});

describe('C15: порожня розмова — підпис, не речення', () => {
  it('без крапки в кінці у трьох мовах', () => {
    for (const l of ['uk', 'en', 'cs'] as const) {
      expect(translate('chatArea.noLetters', l)).not.toMatch(/\.$/);
      expect(translate('chatArea.savedNotes', l)).not.toMatch(/\.$/);
    }
  });
});

describe('C16: межа вкладення чеською — чеською', () => {
  it('mediaLimitLabel не віддає «до … МБ» чеській мові', () => {
    setLocale('cs');
    expect(mediaLimitLabel()).toMatch(/^až \d/);
    setLocale('en');
    expect(mediaLimitLabel()).toMatch(/^up to \d/);
    setLocale('uk');
    expect(mediaLimitLabel()).toMatch(/^до \d/);
  });
});

describe('C17: жодних українських слів поза каталогом', () => {
  it('чипи відкладеного й мова відповідей помічника — з каталогу', async () => {
    const { readFileSync } = await import('node:fs');
    const { join } = await import('node:path');
    const src = (p: string) => readFileSync(join(process.cwd(), 'src', p), 'utf8');
    expect(src('components/messenger/ScheduledMessagesDrawer.tsx')).not.toMatch(/'Сьогодні ввечері'|'Завтра зранку'|'Обрати час'/);
    expect(src('components/messenger/feed/useMessageAssist.ts')).not.toMatch(/речення українською/);
    expect(src('stores/messengerStore.ts')).not.toMatch(/'Співрозмовник'|'Користувач'/);
    expect(translate('coreChat.schedPick', 'cs')).toBe('Vybrat čas');
  });
});

describe('C17: «завдань немає» будь-якою мовою відповіді', () => {
  it('НЕМАЄ, None., žádné — не завдання; справжній рядок — завдання', () => {
    for (const r of ['НЕМАЄ', 'немає.', 'None', 'None.', 'No tasks.', 'žádné']) expect(noTasksReply(r), r).toBe(true);
    expect(noTasksReply('Call the bank')).toBe(false);
  });
});

describe('C23: тестові гроші підписані на картці', () => {
  const PAY = { amount: '2.5', token: 'USDC', cluster: 'devnet', to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH', signature: 'sig', offlineCash: false, seekerVerified: false, seekerSkrTier: null, seekerLabel: null, status: 'pending', toMine: false } as NonNullable<Message['payment']>;

  it('переказ у devnet має «Тестові платежі», у mainnet — ні', () => {
    render(<SolanaCard msg={msg('p1', '', { payment: PAY })} />);
    expect(screen.getByTestId('test-pay-tag').textContent).toBe('Тестові платежі');
    cleanup();
    render(<SolanaCard msg={msg('p2', '', { payment: { ...PAY, cluster: 'mainnet' } })} />);
    expect(screen.queryByTestId('test-pay-tag')).toBeNull();
  });

  it('рахунок, який людина ще не платила, підписано мережею її гаманця', async () => {
    const MARKS = { 'dish:latte': [OLENA], 'dish:cake': [OLENA] };
    const shown = async (cluster: string) => {
      installBridge((c) =>
        c.p.startsWith('/messenger/solana/peer/') ? { data: { address: 'Bx1', can_pay: true, cluster, seeker: false } } : undefined,
      );
      const bill = billMsg({ cardMarks: MARKS });
      useMessengerStore.setState({ chats: [{ id: 'grp', type: 'group', title: 'Cafe X', messages: [bill], unreadCount: 0 } as never], activeChatId: 'grp' } as never);
      render(<BillCard msg={bill} meId={OLENA} />);
      await act(async () => {
        for (let i = 0; i < 6; i++) await Promise.resolve();
      });
      expect(screen.getByTestId('bill-card-pay')).toBeTruthy();
      const tag = screen.queryByTestId('test-pay-tag');
      cleanup();
      removeBridge();
      return tag;
    };
    expect(await shown('devnet')).not.toBeNull();
    expect(await shown('mainnet')).toBeNull();
  });

  it('оплачене замовлення лишається підписаним як тестове', () => {
    const card = msg('o1', ORD, { isSelf: true });
    const paid = msg('p', 'ok', { payment: { ...PAY, ref: 'A-217', status: 'confirmed', amount: '100.0', cluster: 'devnet' } });
    useMessengerStore.setState({ chats: [{ id: 'biz', title: 'Кавʼярня', type: 'dm', messages: [card, paid] } as unknown as Chat] } as never);
    render(<OrderCard msg={card} />);
    expect(screen.getByText(translate('bizDir.order.testPay', 'uk'))).toBeTruthy();
  });
});
