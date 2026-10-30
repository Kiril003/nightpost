/**
 * R3 Chat, style D (d-merged chat sheet): desk wall quieter than the phone's,
 * day pill on s2, group sender name inside the bubble in the person tone,
 * incoming bubble with a hairline, square tone avatar for groups in the header
 * with the member count in text-3, composer [+] [surface field] [mic] as ghosts.
 */
import React from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { ChatWallpaper } from '../components/messenger/ChatWallpaper';
import { ChatArea } from '../components/messenger/ChatArea';
import { Header } from '../components/messenger/Header';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { DEFAULT_LOOK, normalizeLook, useLook } from '../services/appearance';
import { NAME_CAP, fitBubble } from '../components/messenger/feed/bubbleFit';
import { useLiving } from '../services/livingPulse';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

const STYLES = join(__dirname, '..', 'styles');
const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '');
const WALL = strip(readFileSync(join(STYLES, 'wallpaper.css'), 'utf8'));
const FORM = strip(readFileSync(join(STYLES, 'form.css'), 'utf8'));
const ruleOf = (css: string, selector: string) => {
  const at = css.indexOf(selector);
  expect(at, selector).toBeGreaterThanOrEqual(0);
  const rest = css.slice(at);
  return rest.slice(0, rest.indexOf('}'));
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
beforeEach(() => {
  useLook.setState({ look: DEFAULT_LOOK });
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {}, storageFailure: null } as never);
});
afterEach(() => {
  cleanup();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('wall: the desk pane is quieter than the phone', () => {
  it('the layer says which surface it is on', () => {
    useLook.setState({ look: normalizeLook({ wallpaper: { kind: 'builtin', id: 'contour' }, wallpaperPicked: true }) });
    render(<ChatWallpaper chatId="c" />);
    expect(screen.getByTestId('chat-wallpaper').getAttribute('data-surface')).toBe('desk');
    cleanup();
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    render(<ChatWallpaper chatId="c" />);
    expect(screen.getByTestId('chat-wallpaper').getAttribute('data-surface')).toBe('phone');
    useLook.setState({ look: DEFAULT_LOOK });
  });

  it('phone keeps the 384 tile and the plain dim; desk scales the tile and lifts the dim', () => {
    expect(ruleOf(WALL, '.ph-wallpaper-tile {')).toContain('background-size: 384px 384px');
    expect(ruleOf(WALL, '.ph-wallpaper-scrim {')).toContain('opacity: var(--wp-dim)');
    expect(ruleOf(WALL, ".ph-wallpaper[data-surface='desk'] .ph-wallpaper-tile {")).toContain('background-size: 640px 640px');
    const desk = ruleOf(WALL, ".ph-wallpaper[data-surface='desk'][data-kind='builtin'] .ph-wallpaper-scrim {");
    expect(desk).toMatch(/opacity: calc\(var\(--wp-dim\) \+ \(1 - var\(--wp-dim\)\) \* 0\.\d+\)/);
  });

  it('day pill sits on s2 (raised) over the wall', () => {
    expect(ruleOf(WALL, "[data-wallpaper='on'] .ph-day-pill {")).toContain('background-color: var(--ph-raised)');
  });
});

describe('bubbles', () => {
  it('incoming bubble carries the hairline, mine stays borderless', () => {
    expect(ruleOf(FORM, 'html:root .msg-bubble-other,')).toContain('border-color: var(--ph-line)');
    expect(ruleOf(FORM, 'html:root .msg-bubble-self,')).toContain('border-color: transparent');
  });

  const noop = () => {};
  const group = { id: 'g1', title: 'Dinner', avatar: '', type: 'group', circle: 'friends', unreadCount: 0, messages: [] } as unknown as Chat;
  const from = (id: string, who: string, text: string): Message =>
    ({ id, senderId: who.toLowerCase(), senderName: who, senderAvatar: '', timestamp: '09:05', type: 'text', text, isSelf: false, status: 'sent' }) as unknown as Message;
  const feed = (messages: Message[]) =>
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={group}
          messages={messages}
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

  it('group: sender name 13/semibold in the person tone INSIDE the text bubble, once per run', () => {
    const { container } = feed([from('a1', 'Sofia', 'I am in'), from('a2', 'Sofia', 'see you'), from('b1', 'Marek', 'late')]);
    const names = [...container.querySelectorAll('[data-msg-sender]')] as HTMLElement[];
    expect(names.map((n) => n.textContent)).toEqual(['Sofia', 'Marek']);
    for (const n of names) {
      expect(n.closest('.msg-bubble-fluid')).not.toBeNull();
      expect(n.className).toContain('text-footnote');
      expect(n.className).toContain('font-semibold');
      expect(n.style.color).toMatch(/var\(--ph-person-\d-name\)/);
    }
  });
});

describe('Saved, empty: no plate over the wall, a label instead of prose', () => {
  it('the empty state carries no stage plate and reads as a label with the write action', () => {
    const noop = () => {};
    const saved = { id: 'chat_saved_notes', title: 'Saved', avatar: '', type: 'saved', unreadCount: 0, messages: [] } as unknown as Chat;
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={saved}
          messages={[]}
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
    const empty = screen.getByTestId('thread-empty');
    expect(empty.className).not.toContain('ph-stage-card');
    const label = empty.querySelector('p')!.textContent!.trim();
    expect(label.split(/[.!?]\s/).length).toBe(1);
    expect(label.length).toBeLessThanOrEqual(24);
    expect(empty.querySelector('button')).not.toBeNull();
  });
});

describe('long sender name: one line, never wider than the text past the cap', () => {
  it('the name inside the bubble adds no width of its own and ends in an ellipsis', () => {
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={{ id: 'g2', title: 'G', avatar: '', type: 'group', circle: 'friends', unreadCount: 0, messages: [] } as unknown as Chat}
          messages={[{ id: 'n1', senderId: 'x', senderName: 'Олександра-Вікторія Костянтинопольська', senderAvatar: '', timestamp: '09:05', type: 'text', text: 'Ок', isSelf: false, status: 'sent' } as unknown as Message]}
          currentUserId="me"
          onOpenLocation={() => {}}
          onVotePoll={() => {}}
          onPayBillShare={() => {}}
          onAddReaction={() => {}}
          onReplyMessage={() => {}}
          selectedMessageIds={[]}
          onToggleSelectMessage={() => {}}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={() => {}}
        />
      </MemoryRouter>,
    );
    const name = container.querySelector('.msg-bubble-fluid [data-msg-sender]') as HTMLElement;
    for (const c of ['w-0', 'min-w-full', 'truncate']) expect(name.className.split(/\s+/)).toContain(c);
  });

  it('fitBubble lets the name widen the bubble only up to the cap; the wrapped text sets the width', () => {
    const bubble = document.createElement('div');
    bubble.innerHTML = '<p data-msg-sender>Name</p><p><span>text</span><span data-meta-space></span></p>';
    document.body.appendChild(bubble);
    const name = bubble.querySelector('[data-msg-sender]') as HTMLElement;
    Object.defineProperty(name, 'scrollWidth', { value: 400 });
    const spacer = bubble.querySelector('[data-meta-space]') as HTMLElement;
    spacer.getBoundingClientRect = () => ({ left: 0, right: 40, top: 24, bottom: 40, width: 40, height: 16 }) as DOMRect;
    const range = { setStart: () => {}, setEndBefore: () => {}, getClientRects: () => [{ left: 0, right: 100, top: 0, bottom: 20, width: 100 }] };
    const spy = vi.spyOn(document, 'createRange').mockReturnValue(range as unknown as Range);
    fitBubble(bubble);
    expect(bubble.style.width).toBe('100px');
    expect(bubble.style.minWidth).toBe(`${NAME_CAP}px`);
    spy.mockRestore();
    bubble.remove();
  });
});

const ME = { id: 'me', name: 'Me', avatar: '' } as unknown as UserProfile;
const header = (c: Partial<Chat>) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={{ id: 'g', title: 'Dinner', type: 'group', circle: 'all', avatar: '', unreadCount: 0, messages: [], ...c } as unknown as Chat}
        currentUser={ME}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

describe('header', () => {
  it('group: square tone avatar and the member count in text-3', () => {
    const members = ['me', 'a', 'b', 'c', 'd'].map((id) => ({ id, name: id.toUpperCase() }));
    const { container } = header({ members } as never);
    const av = container.querySelector('[data-shared="chat-avatar"] > *') as HTMLElement;
    expect(av.className).toContain('rounded-control');
    expect(av.className).not.toContain('rounded-full');
    const line = screen.getByTestId('header-plain-line');
    expect(line.textContent).toBe('5 учасників');
    expect(container.querySelector('[data-talk-status]')!.className).toContain('text-ink-3');
  });

  it('a person stays a circle', () => {
    const { container } = header({ id: 'o', title: 'Oksana', type: 'dm', peerNodeId: 'c1' } as never);
    const av = container.querySelector('[data-shared="chat-avatar"] > *') as HTMLElement;
    expect(av.className).toContain('rounded-full');
  });
});

describe('composer: + / field / mic', () => {
  const props = {
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
    chatId: 'chat_o',
  } as unknown as React.ComponentProps<typeof MessageComposer>;
  const CHAT = { id: 'chat_o', title: 'O', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;

  it('bar on bg with a top line; + and mic are ghosts in text-1; the field is surface with a hairline', () => {
    useMessengerStore.setState({ chats: [CHAT], activeChatId: 'chat_o' } as never);
    const { container } = render(<MessageComposer {...props} />);
    const bar = container.querySelector('[data-floating-composer]') as HTMLElement;
    expect(bar.className).toContain('bg-canvas');
    expect(bar.className).toContain('border-line');
    const plus = screen.getByTestId('composer-attach');
    const mic = screen.getByTestId('composer-media-button');
    for (const b of [plus, mic]) {
      expect(b.className).not.toMatch(/(^|\s)bg-(raised|ink\/)/);
      expect(b.className).toMatch(/(^|\s)text-ink(\s|$)/);
    }
    const field = screen.getByTestId('composer-field');
    expect(field.className).toContain('bg-surface');
    expect(field.className).toContain('border-line');
    expect(field.className).not.toContain('bg-raised');
  });
});
