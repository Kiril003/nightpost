/**
 * Досяжність «Спитати Nightpost» зі справжніх входів: День, Гроші, Людина,
 * розмова, група, композер. Кожен вхід відкриває спільний аркуш зі своєю
 * поверхнею; розмова з самим Nightpost і «Збережене» кнопки не мають.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
      listContacts: vi.fn(async () => []),
      listConversations: vi.fn(async () => []),
      listBlocked: vi.fn(async () => ({ blocked: [] })),
    },
  };
});

import { Header } from '../components/messenger/Header';
import { DaySheet } from '../components/messenger/today/DaySheet';
import { MoneyCard } from '../components/messenger/cards/MoneyCard';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { useAskSheet } from '../stores/askSheetStore';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { DayItem } from '../services/dayApi';
import type { Chat, Message, UserProfile } from '../types/messenger';

const ME = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const text = (id: string, body: string, from = 'p1', name = 'Петро'): Message =>
  ({ id, senderId: from, senderName: name, senderAvatar: '', timestamp: '10:00', type: 'text', text: body, isSelf: from === 'me' }) as unknown as Message;

const chat = (over: Partial<Chat> = {}): Chat =>
  ({ id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1', ...over }) as unknown as Chat;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Кирило' } });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  act(() => useAskSheet.getState().close());
  vi.restoreAllMocks();
});

const opened = () => useAskSheet.getState().ctx;

const drawHeader = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={c}
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

describe('входи', () => {
  it('День: знак у шапці дня → поверхня day з подіями', () => {
    const d = new Date();
    d.setHours(23, 0, 0, 0);
    const items: DayItem[] = [{ id: 'e', kind: 'event', title: 'Тренування', atMs: d.getTime(), endMs: null, allDay: false, peerId: null, overdue: false }];
    const noop = vi.fn();
    render(
      <DaySheet open items={items} calendar="granted" nowMs={d.getTime() - 3_600_000} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onAskCalendar={noop} />,
    );
    fireEvent.click(screen.getByTestId('ask-trigger-day'));
    expect(opened()?.surface).toBe('day');
    expect(opened()?.facts).toContain('Тренування');
  });

  it('Гроші: картка гаманця → поверхня wallet', () => {
    const msg = {
      id: 'm_money', senderId: 'peer_phantom_agent', senderName: 'Nightpost', senderAvatar: '', timestamp: '10:00', type: 'widget:money', isSelf: false,
      moneyData: { v: 1, at: Date.now(), wallet: true, balances: [{ coin: 'SOL', amount: '1.24' }], week: [{ peer: 'p', name: 'Оксана', coin: 'SOL', amount: '0.1' }], splits: [], test: true },
    } as unknown as Message;
    render(<MoneyCard msg={msg} meId="me" />);
    fireEvent.click(screen.getByTestId('ask-trigger-wallet'));
    expect(opened()?.surface).toBe('wallet');
    expect(opened()?.facts).toContain('1.24 SOL');
  });

  it('Людина: дія «Спитати» в профілі → поверхня person', async () => {
    useMessengerStore.setState({ chats: [chat({ id: 'conv_ok', peerNodeId: 'peer1', messages: [text('1', 'Так, чекаю біля входу', 'peer1', 'Оксана')] })] });
    render(<PeerProfileSheet peerNodeId="peer1" name="Оксана" chatId="conv_ok" onClose={() => {}} />);
    const ask = await screen.findByTestId('peer-action-ask');
    expect(ask.getAttribute('aria-label')).toContain('Спитати');
    fireEvent.click(ask);
    expect(opened()?.surface).toBe('person');
    expect(opened()?.label).toBe('Оксана');
  });

  it('Розмова 1:1: знак у шапці → chat', () => {
    drawHeader(chat({ messages: [text('1', 'Привіт')] }));
    fireEvent.click(screen.getByTestId('ask-trigger-chat'));
    expect(opened()?.surface).toBe('chat');
  });

  it('Група: знак у шапці → group', () => {
    drawHeader(chat({ id: 'g1', title: 'Двір', type: 'group', unreadCount: 5, messages: [text('1', 'Збори'), text('2', 'Вода')] }));
    fireEvent.click(screen.getByTestId('ask-trigger-chat'));
    expect(opened()?.surface).toBe('group');
    expect(opened()?.label).toBe('Двір · 5 непрочитаних');
  });

  it('розмова з Nightpost і «Збережене» — без кнопки', () => {
    drawHeader(chat({ id: 'chat_phantom', title: 'Nightpost', type: 'phantom' as Chat['type'] }));
    expect(screen.queryByTestId('ask-trigger-chat')).toBeNull();
    cleanup();
    drawHeader(chat({ id: 'chat_saved_notes', title: 'Збережене', type: 'saved' as Chat['type'] }));
    expect(screen.queryByTestId('ask-trigger-chat')).toBeNull();
  });

  it('Композер: «+» → «Чернетка Nightpost» → compose з чернеткою, «Вставити» кладе текст', () => {
    render(
      <MessageComposer
        onSendMessage={vi.fn()}
        onSendVoiceMessage={vi.fn()}
        replyingTo={null}
        onCancelReply={vi.fn()}
        editingMessage={null}
        onCancelEdit={vi.fn()}
        onSaveEdit={vi.fn()}
        selectedMessagesForQuote={[]}
        onSynthesizeMultiQuote={vi.fn()}
        onClearSelectedQuotes={vi.fn()}
        chatId="chat_o"
        initialDraft="Біжу"
        lastPeerMessage={{ id: 'x', text: 'Так, чекаю біля входу' }}
      />,
    );
    fireEvent.click(screen.getByTestId('composer-attach'));
    const item = screen.getByTestId('send-ask');
    expect(item.textContent).toContain('Чернетка Nightpost');
    fireEvent.click(item);
    expect(opened()?.surface).toBe('compose');
    expect(opened()?.draft).toBe('Біжу');
    expect(opened()?.facts).toContain('чекаю біля входу');
    act(() => useAskSheet.getState().onInsert?.('Буду за 5 хв'));
    expect((screen.getByTestId('composer-field').querySelector('textarea') as HTMLTextAreaElement).value).toBe('Буду за 5 хв');
  });

  it('аркуш змонтовано один раз у корені месенджера', () => {
    const root = readFileSync(join(process.cwd(), 'src/components/messenger/MessengerRoot.tsx'), 'utf8');
    expect(root.match(/<AskSheetHost\s*\/>/g)).toHaveLength(1);
  });
});
