import { describe, it, expect, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen, within, cleanup } from '@testing-library/react';
import { buildFeedRows, dateLabelOf } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { fitBubble } from '../components/messenger/feed/bubbleFit';
import { useSettingsStore, LANGUAGE_SETTING_KEY } from '../stores/settingsStore';
import type { Message } from '../types/messenger';

const noop = () => {};
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

afterEach(() => {
  cleanup();
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

describe('messenger visual remediation logic', () => {
  it('formats date dividers in the active locale and invalidates cache when locale changes', () => {
    const today = new Date('2026-10-14T12:00:00Z');
    const msg: Message = {
      id: 'm_date_1',
      senderId: 'peer',
      senderName: 'Daniel',
      senderAvatar: '',
      text: 'Hello',
      timestamp: '10:00',
      sentAt: '2026-10-12T10:00:00Z',
      type: 'text',
      isSelf: false,
    };

    useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
    expect(dateLabelOf(msg, today)).toBe('12 жовтня');

    useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'en' } });
    expect(dateLabelOf(msg, today)).toBe('12 October');
  });

  it('keeps a 21-char short sentence on a single-line short bubble without crushing width', () => {
    const msg: Message = {
      id: 'm_short_21',
      senderId: 'me',
      senderName: 'Me',
      senderAvatar: '',
      text: 'Send 5 USDC to Daniel',
      timestamp: '02:02',
      sentAt: '2026-10-12T02:02:00Z',
      type: 'text',
      isSelf: true,
      status: 'sent',
    };

    const rows = buildFeedRows([msg], 'me', new Date('2026-10-14T12:00:00Z'));
    expect(rows).toHaveLength(1);
    expect(rows[0].density).toBe('short');
    expect(rows[0].densityClass).toBe('msg-density-short');

    const { container } = render(
      <FeedActionsContext.Provider value={dummyActions}>
        <MessageRow
          row={rows[0]}
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
    expect(spacer).not.toBeNull();

    spacer.getBoundingClientRect = () =>
      ({ left: 150, right: 210, top: 116, bottom: 117, width: 60, height: 1, x: 150, y: 116, toJSON: () => ({}) }) as DOMRect;
    const origCreateRange = document.createRange;
    document.createRange = () =>
      ({
        setStart: noop,
        setEndBefore: noop,
        getClientRects: () =>
          [
            { left: 10, right: 150, top: 100, bottom: 122, width: 140, height: 22, x: 10, y: 100, toJSON: () => ({}) },
          ] as unknown as DOMRectList,
      }) as unknown as Range;
    try {
      fitBubble(bubble);
      expect(bubble.style.width).toBe('');
    } finally {
      document.createRange = origCreateRange;
    }

    const css = readFileSync(join(process.cwd(), 'src/styles/messenger.css'), 'utf8');
    expect(css).toMatch(/\.msg-density-short\s*\{[\s\S]*?max-width:\s*min\(360px,\s*100%\)/);
  });

  it('omits repeated in-bubble Nightpost signature headers in 1-on-1 assistant conversation', () => {
    const messages: Message[] = [
      {
        id: 'u1',
        senderId: 'me',
        senderName: 'Me',
        senderAvatar: '',
        text: 'Send 5 USDC to Daniel',
        timestamp: '02:02',
        sentAt: '2026-10-12T02:02:00Z',
        type: 'text',
        isSelf: true,
      },
      {
        id: 'a1',
        senderId: 'phantom',
        senderName: 'Nightpost',
        senderAvatar: '',
        text: 'Prepared a 5 USDC transfer to Daniel.',
        timestamp: '02:02',
        sentAt: '2026-10-12T02:02:10Z',
        type: 'text',
        isSelf: false,
      },
      {
        id: 'u2',
        senderId: 'me',
        senderName: 'Me',
        senderAvatar: '',
        text: 'Where is Olena?',
        timestamp: '10:15',
        sentAt: '2026-10-14T10:15:00Z',
        type: 'text',
        isSelf: true,
      },
      {
        id: 'a2',
        senderId: 'phantom',
        senderName: 'Nightpost',
        senderAvatar: '',
        text: 'Olena is sharing her location — nearby.',
        timestamp: '10:15',
        sentAt: '2026-10-14T10:15:05Z',
        type: 'text',
        isSelf: false,
      },
    ];

    const rows = buildFeedRows(messages, 'me', new Date('2026-10-14T12:00:00Z'));
    const { container } = render(
      <FeedActionsContext.Provider value={dummyActions}>
        <div>
          {rows.map((row) => (
            <MessageRow
              key={row.key}
              row={row}
              chatId="chat_phantom_assistant"
              currentUserId="me"
              currentUserName="Me"
              groupish={false}
              direct={false}
              assistant={true}
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
          ))}
        </div>
      </FeedActionsContext.Provider>,
    );

    expect(container.querySelectorAll('[data-phantom-signature]')).toHaveLength(0);
  });

  it('renders the message timestamp inside SolanaCard and suppresses the external meta chip', () => {
    const msg: Message = {
      id: 'm_solana_1',
      senderId: 'peer',
      senderName: 'Daniel',
      senderAvatar: '',
      text: '5 USDC',
      timestamp: '15:20',
      sentAt: '2026-10-12T15:20:00Z',
      type: 'text',
      isSelf: false,
      payment: {
        amount: '5',
        token: 'USDC',
        to: '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin',
        toMine: true,
        signature: '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d',
        status: 'confirmed',
        cluster: 'mainnet',
        offlineCash: false,
        seekerVerified: false,
        seekerSkrTier: null,
        seekerLabel: null,
      },
    };

    const [row] = buildFeedRows([msg], 'me', new Date('2026-10-14T12:00:00Z'));
    const { container } = render(
      <FeedActionsContext.Provider value={dummyActions}>
        <MessageRow
          row={row}
          chatId="c_daniel"
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

    const card = screen.getByTestId('solana-card');
    expect(within(card).getByText('15:20')).toBeTruthy();
    expect(container.querySelector('[data-msg-meta-chip]')).toBeNull();
  });
});
