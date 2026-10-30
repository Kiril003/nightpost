/**
 * Visual Pass 2 (cardinal, calm):
 * 1. Inline timestamp in short/medium bubbles (no forced second line when 1px baseline spacer sits on the same line).
 * 2. Grouping gap: 2px (mt-0.5) within 2 minutes of same sender, 8px (mt-2) across >2 min or sender switch.
 * 3. Transfer card footer: no truncated label; hash link + time sit on a second footer row below "Confirmed".
 * 4. Avatars: deterministic 6-tone palette per chat.id (including groups), Saved bookmark in accent-text.
 * 5. Search view: hides bottom nav, full-screen layer, raised pill with accent-line focus border.
 * 6. Context & attachment sheets: portaled into document.body at z-[1200] above bottom nav, no "×" button, 48px rows.
 * 7. Today header: single 56px bar, no date line, no top-right avatar button.
 * 8. Folder strip: 40px height, active underline uses bg-accent-line, inline 16px "+" button.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { AttachSheet } from '../components/messenger/AttachSheet';
import { FormSheet } from '../components/messenger/FormSheet';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { FindMe, ThemeChoice } from '../components/messenger/MeSections';
import { MomentComposer } from '../components/messenger/MomentsStrip';
import { ToastRail } from '../components/core/ToastRail';
import { chatToneKey } from '../components/messenger/chatListFormat';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { SolanaCard } from '../components/messenger/feed/SolanaCard';
import { fitBubble, fitWidth } from '../components/messenger/feed/bubbleFit';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow, metaWidth } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { messageFromNode, messengerApi, type NodeMessage } from '../services/messengerApi';
import * as askNightpostMod from '../services/askNightpost';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';
import { useUIStore } from '../stores/uiStore';
import { toneIndex } from '../styles/formTokens';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message, UserProfile } from '../types/messenger';
import { Image as ImageIcon } from 'lucide-react';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useUIStore.setState({ toasts: [] });
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

const noop = () => {};
const USER = { id: 'me', name: 'Олег', avatar: '' } as unknown as UserProfile;

const dummyActions: FeedActions = {
  react: noop,
  openReactionPicker: noop,
  openMenu: noop,
  reply: noop,
  jumpTo: noop,
  toggleExpand: noop,
  toggleTranslate: noop,
  selectMember: noop,
  toggleSelect: noop,
  rangeTo: noop,
  rangeDrag: noop,
  copyCode: noop,
  toast: noop,
  flushQueue: noop,
  openLightbox: noop,
  openLocation: noop,
  payBillShare: noop,
  openCanvasSplit: noop,
  openThread: noop,
  touchStart: noop,
  touchMove: noop,
  touchEnd: noop,
  mouseUp: noop,
};

describe('1. inline timestamp in short bubbles (no forced second line)', () => {
  it('does not shrink the bubble when a 1px baseline spacer sits on the same line as text', () => {
    const textRect = { left: 10, right: 90, top: 100, bottom: 122 };
    const baselineSpacer = { left: 90, right: 150, top: 116, bottom: 117 };
    expect(fitWidth([textRect], baselineSpacer, 24)).toBeNull();

    const msg: Message = {
      id: 'short_hi',
      senderId: 'me',
      senderName: 'Me',
      senderAvatar: '',
      text: 'Hi Olena',
      timestamp: '09:20',
      sentAt: '2026-10-14T09:20:00Z',
      type: 'text',
      isSelf: true,
      status: 'sent',
    };
    const [row] = buildFeedRows([msg], 'me', new Date('2026-10-14T12:00:00Z'));
    const { container } = render(
      <FeedActionsContext.Provider value={dummyActions}>
        <MessageRow
          row={row}
          chatId="c1"
          currentUserId="me"
          currentUserName="Me"
          groupish={false}
          direct={true}
          phone={true}
          isSelectionMode={false}
          isSelected={false}
          isHighlighted={false}
          isSearchMatch={false}
          isActiveSearchMatch={false}
          fresh={false}
          expanded={false}
          copied={false}
        />
      </FeedActionsContext.Provider>,
    );

    const bubble = container.querySelector<HTMLElement>('.msg-bubble-fluid')!;
    const spacer = bubble.querySelector<HTMLElement>('[data-meta-space]')!;
    expect(spacer).toBeTruthy();

    spacer.getBoundingClientRect = () =>
      ({ left: 90, right: 150, top: 116, bottom: 117, width: 60, height: 1, x: 90, y: 116, toJSON: () => ({}) }) as DOMRect;
    const origCreateRange = document.createRange;
    document.createRange = () =>
      ({
        setStart: noop,
        setEndBefore: noop,
        getClientRects: () =>
          [{ left: 10, right: 90, top: 100, bottom: 122, width: 80, height: 22, x: 10, y: 100, toJSON: () => ({}) }] as unknown as DOMRectList,
      }) as unknown as Range;
    try {
      fitBubble(bubble);
      expect(bubble.style.width).toBe('');
    } finally {
      document.createRange = origCreateRange;
    }
  });

  it('assistant bubble has no signature header and keeps inline meta', () => {
    const chat = { id: 'chat_phantom_assistant', title: 'Nightpost', type: 'phantom', unreadCount: 0, messages: [] } as unknown as Chat;
    const msg: Message = {
      id: 'np_1',
      senderId: 'phantom_agent',
      senderName: 'Nightpost',
      senderAvatar: '',
      text: 'Reminder: call Daniel at 18:00.',
      timestamp: '15:14',
      sentAt: '2026-10-14T15:14:00Z',
      type: 'text',
      isSelf: false,
    };
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={chat}
          messages={[msg]}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    expect(container.textContent).toContain('Reminder: call Daniel at 18:00.');
    expect(container.querySelector('[data-phantom-signature]')).toBeNull();
    expect(container.querySelector('[data-meta-space]')).toBeTruthy();
  });
});

describe('2. grouping gap 2px vs 8px', () => {
  it('uses 2px (mt-0.5) within the 10-minute run of one sender and 8px (mt-2) after the run gap or a sender switch', () => {
    const chat = { id: 'dm_daniel', title: 'Daniel', type: 'dm', unreadCount: 0, messages: [] } as unknown as Chat;
    const msgs: Message[] = [
      { id: 'g1', senderId: 'daniel', senderName: 'Daniel', senderAvatar: '', timestamp: '09:20', sentAt: '2026-10-14T09:20:00Z', type: 'text', text: 'Hi Olena', isSelf: false },
      { id: 'g2', senderId: 'daniel', senderName: 'Daniel', senderAvatar: '', timestamp: '09:21', sentAt: '2026-10-14T09:21:00Z', type: 'text', text: 'Dinner at 8?', isSelf: false },
      { id: 'g3', senderId: 'daniel', senderName: 'Daniel', senderAvatar: '', timestamp: '09:32', sentAt: '2026-10-14T09:32:00Z', type: 'text', text: 'Still free?', isSelf: false },
      { id: 'g4', senderId: 'me', senderName: 'Me', senderAvatar: '', timestamp: '09:32', sentAt: '2026-10-14T09:32:15Z', type: 'text', text: 'Yes!', isSelf: true, status: 'sent' },
    ];
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={chat}
          messages={msgs}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    const row = (id: string) => container.querySelector(`#message-${id}`) as HTMLElement;
    expect(row('g2').className).toMatch(/(^|\s)mt-0\.5(\s|$)/);
    expect(row('g3').className).toMatch(/(^|\s)mt-2(\s|$)/);
    expect(row('g4').className).toMatch(/(^|\s)mt-2(\s|$)/);
  });
});

describe('3. transfer card footer has no hash, link or network chip', () => {
  it('places Confirmed on line 1 and the time on line 2; hash and link live in the details sheet', () => {
    useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'en' } });
    const node: NodeMessage = {
      id: 'sol1',
      conversation_id: 'c1',
      client_id: 'x',
      seq: 1,
      author_id: 'daniel',
      author_name: 'Daniel',
      kind: 'text',
      body: 'Transfer 0.05 SOL',
      ciphertext: null,
      transport: 'p2p',
      sent_at: '2026-10-14T15:20:00Z',
      edited_at: null,
      deleted_at: null,
      payment: {
        amount: '0.05',
        token: 'SOL',
        units: '50000000',
        decimals: 9,
        cluster: 'devnet',
        to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH',
        signature: '43L7q3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgfayN',
        offline_cash: false,
        status: 'confirmed',
        to_mine: true,
      },
    } as unknown as NodeMessage;
    const msg = { ...messageFromNode(node, 'me', 'daniel'), timestamp: '15:20' };
    render(<SolanaCard msg={msg} />);

    const card = screen.getByTestId('solana-card');
    expect(card.className).toContain('bg-surface');
    expect(card.className).toContain('border-line');
    expect(screen.getByTestId('solana-amount').className).toContain('text-display');

    const status = screen.getByTestId('solana-status');
    expect(status.textContent).toBe('Confirmed');
    expect(status.className).toContain('text-success');

    expect(screen.queryByRole('link')).toBeNull();
    expect(card.textContent).not.toContain('43L7…fayN');
    expect(card.textContent?.toLowerCase()).not.toContain('devnet');

    const time = screen.getByTestId('solana-card-time');
    expect(time.textContent).toBe('15:20');
    expect(status.closest('div')).not.toBe(time.parentElement);
  });
});

describe('4. avatar tone is deterministic per chat id and uses the 6 person tones', () => {
  it('assigns deterministic --ph-person-N tones to group chats and styles Saved with accent-text', () => {
    const chats: Chat[] = [
      { id: 'chat_saved_notes', title: 'Збережене', type: 'saved', pinned: true, unreadCount: 0, messages: [], lastAt: '2026-10-14T10:00:00Z' } as unknown as Chat,
      ...Array.from({ length: 36 }, (_, i) => ({
        id: `grp_studentska_${i}`,
        title: `Studentská · ${i}`,
        type: 'group',
        circle: 'all',
        avatar: '',
        unreadCount: 0,
        pinned: false,
        messages: [],
        lastSnippet: 'Okey',
        lastAt: '2026-10-14T10:00:00Z',
      })) as unknown as Chat[],
    ];
    render(
      <PhoneChatList
        chats={chats}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );

    const usedTones = new Set<string>();
    for (let i = 0; i < 36; i++) {
      const id = `grp_studentska_${i}`;
      const row = document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
      const avatar = row.querySelector('[data-row-avatar] > div') as HTMLElement;
      const expected = `var(--ph-person-${toneIndex(id)})`;
      expect(avatar.style.backgroundColor).toBe(expected);
      usedTones.add(expected);
    }
    expect(usedTones.size).toBe(6);

    const savedRow = document.querySelector('[data-chat-row="chat_saved_notes"]') as HTMLElement;
    const savedAvatar = savedRow.querySelector('[data-saved-avatar]') as HTMLElement;
    expect(savedAvatar).toBeTruthy();
    expect(savedAvatar.className).toContain('bg-raised');
    expect(savedAvatar.className).toContain('text-ink-2');
    expect(savedAvatar.className).not.toMatch(/border|accent/);
    expect(within(savedRow).queryByText(/нотатки на цьому телефоні/i)).toBeNull();
  });
});

describe('5. search covers the bottom nav and uses accent-line focus ring', () => {
  it('search opens as a full-screen layer above the nav and closes on cancel', () => {
    const chats: Chat[] = [
      { id: 'c1', title: 'Daniel', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'Hi', lastAt: '2026-10-14T10:00:00Z' } as unknown as Chat,
    ];
    render(
      <div>
        <PhoneChatList
          chats={chats}
          currentUser={USER}
          onSelectChat={noop}
          onNewChat={noop}
          onOpenChatActions={noop}
        />
      </div>,
    );

    fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
    const listRoot = screen.getByTestId('chat-list');
    expect(listRoot.getAttribute('data-search-open')).toBe('true');
    expect(listRoot.className).toContain('fixed');
    expect(listRoot.className).toContain('z-[1200]');

    const box = document.querySelector('[data-search-box]') as HTMLElement;
    expect(box.className).toContain('bg-raised');
    expect(box.className).toContain('focus-within:border-accent-line');

    fireEvent.click(screen.getByText('Скасувати'));
    expect(screen.getByTestId('chat-list').className).not.toContain('fixed');
  });
});

describe('6. sheets render above the nav and trim desktop leftovers', () => {
  it('portals ChatActionsSheet and AttachSheet to document.body at z-[1200]', () => {
    const chat = { id: 'g1', title: 'Studentská · 09:30', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat;
    const { container } = render(
      <div>
        <ChatActionsSheet
          chat={chat}
          onClose={noop}
          onMarkRead={noop}
          onTogglePin={noop}
          onToggleMute={noop}
          onToggleArchive={noop}
          onRename={noop}
          onClear={noop}
          onDelete={noop}
        />
        <AttachSheet
          tiles={[{ icon: ImageIcon, label: 'Фото', pick: noop, testId: 'tile-photo' }]}
          moreTiles={[]}
          onClose={noop}
        />
      </div>,
    );

    const contextSheet = screen.getByTestId('chat-actions-sheet');
    expect(container.contains(contextSheet)).toBe(false);
    expect(document.body.contains(contextSheet)).toBe(true);
    expect(contextSheet.className).toContain('z-[1200]');

    const attachSheet = screen.getByTestId('send-what-sheet');
    expect(container.contains(attachSheet)).toBe(false);
    expect(document.body.contains(attachSheet)).toBe(true);
    expect(attachSheet.className).toContain('z-[1200]');
  });

  it('renders ThemeChoice without the literal sun svg drawing', () => {
    const { container } = render(<ThemeChoice />);
    const autoBtn = container.querySelector('[data-theme-mode="auto"]') as HTMLElement;
    expect(autoBtn.querySelector('svg path[stroke-dasharray]')).toBeNull();
    expect(autoBtn.querySelectorAll('[data-theme-bubble]')).toHaveLength(4);
  });
});

describe('critic fixes', () => {
  const sheetHandlers: MessageSheetHandlers = {
    reply: noop,
    copyText: noop,
    forward: noop,
    edit: noop,
    react: noop,
    openReactionPicker: noop,
    speak: noop,
    openCanvas: noop,
    toast: noop,
    translate: noop,
    summarize: noop,
    actionItems: noop,
    togglePin: noop,
    save: noop,
    select: noop,
    inspect: noop,
    remove: noop,
  };

  it('1. composer field and voice pills are surface pills with a hairline on the bg bar (D)', () => {
    render(
      <MessageComposer
        onSendMessage={noop}
        onSendVoiceMessage={noop}
        replyingTo={null}
        onCancelReply={noop}
        editingMessage={null}
        onCancelEdit={noop}
        onSaveEdit={noop}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={noop}
        onClearSelectedQuotes={noop}
        chatId="c1"
      />,
    );
    const field = screen.getByTestId('composer-field');
    expect(field.className).toContain('bg-surface');
    expect(field.className).toContain('border-line');

    fireEvent.click(screen.getByTestId('composer-media-button'));
    const locked = screen.getByTestId('voice-locked');
    expect(locked.className).toContain('bg-surface');
    expect(locked.className).not.toContain('bg-raised');
  });

  it('2. MessageActionSheet registers phone back layer and closes on Escape', () => {
    const onClose = vi.fn();
    const msg: Message = {
      id: 'act_1',
      senderId: 'daniel',
      senderName: 'Daniel',
      senderAvatar: '',
      text: 'Hello',
      timestamp: '10:00',
      type: 'text',
      isSelf: false,
    };
    render(
      <MessageActionSheet
        msg={msg}
        currentUserId="me"
        speaking={false}
        onClose={onClose}
        on={sheetHandlers}
      />,
    );

    const close = usePhoneLayerStore.getState().layers['msg-act-act_1'];
    expect(close).toBeTypeOf('function');
    close();
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('3. MessageActionSheet clamps incoming left position so 320px reaction bar fits a 360px viewport', () => {
    const origWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 360 });
    const rowEl = document.createElement('div');
    rowEl.id = 'message-in_360';
    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'msg-bubble-fluid';
    bubbleEl.getBoundingClientRect = () =>
      ({ top: 180, bottom: 220, left: 52, right: 220, width: 168, height: 40, x: 52, y: 180, toJSON: () => ({}) }) as DOMRect;
    rowEl.appendChild(bubbleEl);
    document.body.appendChild(rowEl);

    try {
      const msg: Message = {
        id: 'in_360',
        senderId: 'daniel',
        senderName: 'Daniel',
        senderAvatar: '',
        text: 'Incoming group message',
        timestamp: '10:05',
        type: 'text',
        isSelf: false,
      };
      render(
        <MessageActionSheet
          msg={msg}
          currentUserId="me"
          speaking={false}
          onClose={noop}
          on={sheetHandlers}
        />,
      );
      const stack = document.body.querySelector('[data-msg-stack]') as HTMLElement;
      expect(parseFloat(stack.style.left)).toBeLessThanOrEqual(24);
    } finally {
      rowEl.remove();
      Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: origWidth });
    }
  });

  it('4. fitBubble floors multiline width to spacer width and starts range at the text paragraph', () => {
    const narrowLines = [
      { left: 10, right: 40, top: 100, bottom: 120 },
      { left: 10, right: 35, top: 120, bottom: 140 },
    ];
    const wrappedSpacer = { left: 10, right: 76, top: 142, bottom: 143 };
    expect(fitWidth(narrowLines, wrappedSpacer, 24)).toBe(90);

    const bubble = document.createElement('div');
    const quote = document.createElement('div');
    const p = document.createElement('p');
    const spacer = document.createElement('span');
    spacer.setAttribute('data-meta-space', '');
    p.appendChild(document.createTextNode('A\nB'));
    p.appendChild(spacer);
    bubble.appendChild(quote);
    bubble.appendChild(p);

    spacer.getBoundingClientRect = () =>
      ({ left: 10, right: 76, top: 142, bottom: 143, width: 66, height: 1, x: 10, y: 142, toJSON: () => ({}) }) as DOMRect;
    const setStart = vi.fn();
    const origCreateRange = document.createRange;
    document.createRange = () =>
      ({
        setStart,
        setEndBefore: noop,
        getClientRects: () =>
          narrowLines.map((r) => ({ ...r, width: r.right - r.left, height: r.bottom - r.top, x: r.left, y: r.top, toJSON: () => ({}) })) as unknown as DOMRectList,
      }) as unknown as Range;
    try {
      fitBubble(bubble);
      expect(setStart).toHaveBeenCalledWith(p, 0);
    } finally {
      document.createRange = origCreateRange;
    }
  });

  it('5. self-sent SolanaCard renders FailedMark, PendingClock, and SentTick next to timestamp', () => {
    const basePayment: NonNullable<Message['payment']> = {
      amount: '0.05',
      token: 'SOL',
      cluster: 'devnet',
      to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH',
      signature: '43L7q3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgfayN',
      offlineCash: false,
      seekerVerified: false,
      seekerSkrTier: null,
      seekerLabel: null,
      toMine: null,
      status: 'confirmed',
    };
    const makeMsg = (id: string, status: Message['status']): Message => ({
      id,
      senderId: 'me',
      senderName: 'Me',
      senderAvatar: '',
      text: 'Transfer 0.05 SOL',
      timestamp: '15:20',
      type: 'text',
      isSelf: true,
      status,
      payment: basePayment,
    });

    const { rerender, container } = render(<SolanaCard msg={makeMsg('sol_fail', 'failed')} />);
    expect(screen.getByTestId('msg-failed')).toBeTruthy();

    rerender(<SolanaCard msg={makeMsg('sol_queue', 'queued')} />);
    expect(container.querySelector('[data-msg-pending]')).toBeTruthy();

    rerender(<SolanaCard msg={makeMsg('sol_sent', 'sent')} />);
    expect(container.querySelector('[data-msg-tick]')).toBeTruthy();
  });

  it('6. search layer includes safe-area top padding and closes when voice ask starts', () => {
    vi.spyOn(askNightpostMod, 'talkToNightpost').mockReturnValue(true);
    const chats: Chat[] = [
      { id: 'c1', title: 'Daniel', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'Hi', lastAt: '2026-10-14T10:00:00Z' } as unknown as Chat,
    ];
    render(
      <PhoneChatList
        chats={chats}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
        ask={true}
      />,
    );

    fireEvent.click(screen.getByLabelText('Пошук або спитати Nightpost'));
    const listRoot = screen.getByTestId('chat-list');
    expect(listRoot.className).toContain('pt-[var(--sat)]');

    fireEvent.click(screen.getByTestId('search-ask-voice'));
    expect(screen.getByTestId('chat-list').getAttribute('data-search-open')).toBeNull();
  });

  it('7. metaWidth accounts for English edited label and group fanout counters', () => {
    expect(metaWidth(true, false)).toBeGreaterThanOrEqual(86);
    expect(metaWidth(false, true, true)).toBeGreaterThan(metaWidth(false, true, false));

    const msg: Message = {
      id: 'grp_fanout',
      senderId: 'me',
      senderName: 'Me',
      senderAvatar: '',
      text: 'See you at 8',
      timestamp: '09:20',
      sentAt: '2026-10-14T09:20:00Z',
      type: 'text',
      isSelf: true,
      isEdited: true,
      status: 'sent',
      fanTotal: 5,
      fanRead: 3,
    };
    const [row] = buildFeedRows([msg], 'me', new Date('2026-10-14T12:00:00Z'));
    const { container } = render(
      <FeedActionsContext.Provider value={dummyActions}>
        <MessageRow
          row={row}
          chatId="g1"
          currentUserId="me"
          currentUserName="Me"
          groupish={true}
          direct={false}
          phone={true}
          isSelectionMode={false}
          isSelected={false}
          isHighlighted={false}
          isSearchMatch={false}
          isActiveSearchMatch={false}
          fresh={false}
          expanded={false}
          copied={false}
        />
      </FeedActionsContext.Provider>,
    );
    const spacer = container.querySelector<HTMLElement>('[data-meta-space]')!;
    expect(parseFloat(spacer.style.width)).toBe(metaWidth(true, true, true));
  });

  it('8. ToastRail, MomentComposer, and QrFull render at z-[1300] above z-[1200] sheets', async () => {
    useUIStore.getState().toast({ kind: 'info', message: 'Saved' });
    const { container } = render(
      <>
        <ToastRail />
        <MomentComposer file={null} people={[]} chats={[]} onClose={noop} />
      </>,
    );
    const toastWrap = container.firstElementChild as HTMLElement;
    expect(toastWrap.className).toContain('z-[1300]');

    const momentComposer = document.body.querySelector('[data-moment-composer]') as HTMLElement;
    expect(momentComposer.className).toContain('z-[1300]');

    vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'n1',
      link: 'https://nightpost.app/i/test123',
    } as never);
    render(<FindMe handle="oleg" />);
    fireEvent.click(screen.getByTestId('me-code'));
    const qrModal = await screen.findByTestId('me-qr-full');
    expect(qrModal.className).toContain('z-[1300]');
  });

  it('9. chatToneKey returns chat.id for named groups and null for unnamed contacts so their avatar stays neutral', () => {
    expect(chatToneKey({ id: 'grp_named', title: 'Studentská', type: 'group' })).toBe('grp_named');
    expect(chatToneKey({ id: 'dm_unnamed', title: 'Без імені', type: 'dm', peerNodeId: 'peer_99' })).toBeNull();

    const chats: Chat[] = [
      {
        id: 'dm_unnamed',
        title: 'Без імені',
        type: 'dm',
        circle: 'all',
        avatar: '',
        peerNodeId: 'peer_99',
        unreadCount: 0,
        pinned: false,
        messages: [],
        lastSnippet: 'Привіт',
        lastAt: '2026-10-14T10:00:00Z',
      } as unknown as Chat,
    ];
    render(
      <PhoneChatList
        chats={chats}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );
    const row = document.querySelector('[data-chat-row="dm_unnamed"]') as HTMLElement;
    const avatar = row.querySelector('[data-row-avatar] > div') as HTMLElement;
    expect(avatar.hasAttribute('data-nameless')).toBe(true);
    expect(avatar.className).toContain('bg-raised');
    expect(avatar.style.backgroundColor).toBe('');
  });

  it('10. FormSheet closes on Escape even when hideTitle is true', () => {
    const onClose = vi.fn();
    render(
      <FormSheet open onClose={onClose} title="Profile" hideTitle>
        <div>Body</div>
      </FormSheet>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

