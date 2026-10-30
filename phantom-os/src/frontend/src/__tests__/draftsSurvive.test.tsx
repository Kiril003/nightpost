import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import type { Chat, UserProfile } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { PROFILES_KEY, useProfiles } from '../stores/profilesStore';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { flushDrafts, forgetProfileDrafts, keepDraft, loadDrafts } from '../services/draftVault';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const CHAT = {
  id: 'c1', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
  messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(),
} as unknown as Chat;

const composerProps = (chatId: string, initialDraft: string, onDraftChange?: (c: string, t: string) => void) => ({
  onSendMessage: vi.fn(),
  onSendVoiceMessage: vi.fn(),
  replyingTo: null,
  onCancelReply: vi.fn(),
  editingMessage: null,
  onCancelEdit: vi.fn(),
  onSaveEdit: vi.fn(),
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: vi.fn(),
  onClearSelectedQuotes: vi.fn(),
  chatId,
  initialDraft,
  onDraftChange,
});

/** Новий старт застосунку: свіжий модуль стору читає диск, як після перезапуску. Профілі на диску, як на телефоні. */
async function restart() {
  const { activeId, profiles } = useProfiles.getState();
  localStorage.setItem(PROFILES_KEY, JSON.stringify({ v: 1, activeId, previousId: null, profiles }));
  vi.resetModules();
  return (await import('../stores/messengerStore')).useMessengerStore;
}

const box = () => document.querySelector('textarea') as HTMLTextAreaElement;

describe('P02 чернетки переживають перезапуск', () => {
  beforeEach(() => {
    useMessengerStore.setState({ drafts: {} });
    flushDrafts();
    localStorage.removeItem('np.drafts.v1');
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('набране в композері повертається після нового старту, і рядок списку каже «Чернетка:»', async () => {
    render(<MessageComposer {...composerProps('c1', '', (c, t) => useMessengerStore.getState().setDraft(c, t))} />);
    fireEvent.change(box(), { target: { value: 'Завтра о сьомій?' } });
    vi.advanceTimersByTime(300);
    cleanup();

    const draft = (await restart()).getState().drafts.c1;
    expect(draft).toBe('Завтра о сьомій?');

    render(<MessageComposer {...composerProps('c1', draft)} />);
    expect(box().value).toBe('Завтра о сьомій?');
    cleanup();

    useMessengerStore.setState({ drafts: { c1: draft } });
    render(<PhoneChatList chats={[CHAT]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);
    const row = document.querySelector('[data-row-draft]') as HTMLElement;
    expect(row.textContent).toBe('Чернетка: Завтра о сьомій?');
  }, 60_000);

  it('надіслане чи стерте не воскресає: порожня чернетка знімається з диска одразу', async () => {
    useMessengerStore.getState().setDraft('c1', 'текст');
    vi.advanceTimersByTime(300);
    expect(loadDrafts(useProfiles.getState().activeId)).toEqual({ c1: 'текст' });
    useMessengerStore.setState((s) => ({ drafts: { ...s.drafts, c1: '' } }));
    expect(loadDrafts(useProfiles.getState().activeId)).toEqual({});
    expect((await restart()).getState().drafts.c1).toBeUndefined();
  }, 60_000);

  it('чернетка належить профілю: інший профіль її не бачить, свій — повертає', () => {
    const a = useProfiles.getState().activeId;
    useMessengerStore.getState().setDraft('c1', 'для роботи');
    vi.advanceTimersByTime(300);

    useProfiles.setState({ activeId: 'p-other' });
    expect(useMessengerStore.getState().drafts.c1).toBeUndefined();
    useMessengerStore.getState().setDraft('c1', 'особисте');
    vi.advanceTimersByTime(300);

    useProfiles.setState({ activeId: a });
    expect(useMessengerStore.getState().drafts.c1).toBe('для роботи');
  });

  it('стертий профіль забирає свої чернетки з диска', () => {
    keepDraft('p1', 'c1', 'щось', 0);
    keepDraft('p2', 'c1', 'інше', 0);
    forgetProfileDrafts('p1');
    expect(loadDrafts('p1')).toEqual({});
    expect(loadDrafts('p2')).toEqual({ c1: 'інше' });
  });

  it('биті дані на диску не ламають старт', () => {
    localStorage.setItem('np.drafts.v1', '{"v":1,"map":{"p":{"c1":42,"c2":"ok"}}}');
    expect(loadDrafts('p')).toEqual({ c2: 'ok' });
    localStorage.setItem('np.drafts.v1', 'not json');
    expect(loadDrafts('p')).toEqual({});
  });
});
