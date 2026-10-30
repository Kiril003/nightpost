/**
 * Подарунки: «+ → Подарунок» → вибір із власного каталогу й підпис →
 * картка `widget:gift` їде до вузла; отримувач бачить запаковане й відкриває
 * раз, далі — вже відкрите. Своє — одразу відкрите. Невідомий рід — чесно.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { GIFT_KIND, GIFT_KINDS, GIFT_NOTE_MAX, GiftArt, giftName, giftOf } from '../components/messenger/gifts/giftCatalogue';
import { giftOpened } from '../services/giftOpened';
import { CARD_FIELD, refuseCard } from '../services/composerCards';
import { LIVE_CARD_KINDS } from '../components/messenger/cards/cardModel';
import { cardSnippet, kindLine } from '../components/messenger/chatListFormat';
import { messengerApi, messageFromNode } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message } from '../types/messenger';
import { uk } from '../i18n/messages/parts/gifts';
import { en } from '../i18n/messages/parts/gifts';
import { cs } from '../i18n/messages/parts/cs/gifts';

const CHAT = {
  id: 'chat_o', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 0,
  members: [{ id: 'node_oksana', name: 'Оксана', handle: '', avatar: '', role: 'member' }], messages: [],
} as unknown as Chat;

const gift = (data: unknown, senderId = 'node_oksana', isSelf = false): Message =>
  ({ id: 'm1', senderId, senderName: 'Оксана', senderAvatar: '', timestamp: '10:00', type: GIFT_KIND, isSelf, giftData: data }) as unknown as Message;

const flush = () => act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); });
const setLang = (lang: string) => useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: lang } }));

let seq = 0;
let animate: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.restoreAllMocks();
  try { localStorage.clear(); } catch { /* */ }
  giftOpened.reset();
  useMessengerStore.setState({ chats: [CHAT], activeChatId: 'chat_o', currentUser: { id: 'node_me', name: 'Кирило', avatar: '' } } as never);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async () => ({ id: `srv_${++seq}`, delivery_state: 'sent' }) as never);
  animate = vi.fn(() => ({ finished: Promise.resolve(), cancel: () => {} }));
  (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
});
afterEach(() => {
  cleanup();
  setLang('uk');
  delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
  document.documentElement.classList.remove('reduce-motion');
});

describe('каталог', () => {
  it('щонайменше дванадцять власних подарунків, кожен зі своїм малюнком і назвою трьома мовами', () => {
    expect(GIFT_KINDS.length).toBeGreaterThanOrEqual(12);
    expect(new Set(GIFT_KINDS).size).toBe(GIFT_KINDS.length);
    for (const k of GIFT_KINDS) {
      const key = `gifts.kind.${k}` as keyof typeof uk;
      expect(uk[key], k).toBeTruthy();
      expect(en[key], k).toBeTruthy();
      expect(cs[key], k).toBeTruthy();
      const { container, unmount } = render(<GiftArt kind={k} size={48} />);
      expect(container.querySelector(`[data-gift-art="${k}"] svg`), k).not.toBeNull();
      unmount();
    }
    setLang('en');
    expect(giftName('coffee')).toBe('Coffee');
  });

  it('тіло читається суворо: невідоме — null, підпис обрізано', () => {
    expect(giftOf(gift({ v: 1, id: 'g1', gift: 'coffee', note: '  дякую  ' }))).toEqual({ v: 1, id: 'g1', gift: 'coffee', note: 'дякую' });
    expect(giftOf(gift({ v: 1, id: 'g1', gift: 'coffee', note: 'x'.repeat(500) }))?.note).toHaveLength(GIFT_NOTE_MAX);
    expect(giftOf(gift({ v: 1, id: '', gift: 'coffee' }))).toBeNull();
    expect(giftOf(gift({ v: 1, id: 'g1', gift: '<svg>' }))).toBeNull();
    expect(giftOf(gift(null))).toBeNull();
  });
});

describe('дріт', () => {
  it('рід widget:gift їде карткою зі своїм полем', () => {
    expect(CARD_FIELD[GIFT_KIND]).toBe('giftData');
    expect(LIVE_CARD_KINDS.has(GIFT_KIND)).toBe(true);
    expect(refuseCard(GIFT_KIND, JSON.stringify({ v: 1, id: 'g', gift: 'tea' }))).toBeNull();
  });

  it('з вузла приходить зі своїм вмістом', () => {
    const msg = messageFromNode(
      { id: 'srv_1', kind: GIFT_KIND, author_id: 'node_oksana', author_name: 'Оксана', sent_at: '2026-10-29T10:00:00', body: JSON.stringify({ v: 1, id: 'g1', gift: 'cake' }) } as never,
      'node_me',
    );
    expect(msg.type).toBe(GIFT_KIND);
    expect(msg.giftData).toMatchObject({ gift: 'cake' });
  });

  it('у переліку розмов — «Подарунок», не порожньо', () => {
    expect(kindLine(GIFT_KIND)).toBe('Подарунок');
    expect(cardSnippet(GIFT_KIND, null)).toBe('Подарунок');
    setLang('en');
    expect(kindLine(GIFT_KIND)).toBe('Gift');
  });
});

describe('надіслати з «+»', () => {
  const props = {
    onSendMessage: () => {}, onSendVoiceMessage: () => {}, onOpenActions: () => {}, onOpenScheduler: () => {},
    replyingTo: null, onCancelReply: () => {}, editingMessage: null, onCancelEdit: () => {}, onSaveEdit: () => {},
    selectedMessagesForQuote: [], onSynthesizeMultiQuote: () => {}, onClearSelectedQuotes: () => {}, chatId: 'chat_o',
  };

  it('«+ → Подарунок» → кава з підписом їде карткою widget:gift', async () => {
    render(<MessageComposer {...props} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(within(screen.getByTestId('send-what-sheet')).getByTestId('send-gift').textContent).toContain('Подарунок');
    fireEvent.click(screen.getByTestId('send-gift'));
    await flush();
    const sheet = screen.getByTestId('gift-pick');
    const send = within(sheet).getByTestId('gift-send') as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fireEvent.click(within(sheet).getByTestId('gift-pick-coffee'));
    fireEvent.change(within(sheet).getByTestId('gift-note'), { target: { value: 'За вчора' } });
    expect(send.disabled).toBe(false);
    fireEvent.click(send);
    await flush();
    const append = vi.mocked(messengerApi.appendMessage);
    expect(append).toHaveBeenCalledTimes(1);
    const body = append.mock.calls[0][1] as { kind: string; body: string };
    expect(body.kind).toBe(GIFT_KIND);
    expect(JSON.parse(body.body)).toMatchObject({ v: 1, gift: 'coffee', note: 'За вчора' });
    await waitFor(() => expect(screen.queryByTestId('gift-pick')).toBeNull());
  });

  it('без підпису підпис не їде', async () => {
    render(<MessageComposer {...props} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    fireEvent.click(screen.getByTestId('send-gift'));
    await flush();
    fireEvent.click(screen.getByTestId('gift-pick-flowers'));
    fireEvent.click(screen.getByTestId('gift-send'));
    await flush();
    const body = vi.mocked(messengerApi.appendMessage).mock.calls[0][1] as { body: string };
    expect(JSON.parse(body.body)).not.toHaveProperty('note');
  });
});

describe('отриманий подарунок', () => {
  it('запакований → «Відкрити» → коробка хитається й знімається, подарунок пружно зʼявляється, текст підіймається', async () => {
    render(<MessageCard msg={gift({ v: 1, id: 'g1', gift: 'flowers', note: 'З днем' })} meId="node_me" meName="Кирило" />);
    const card = screen.getByTestId('card-gift');
    expect(card.dataset.state).toBe('closed');
    expect(screen.queryByTestId('gift-note-text')).toBeNull();
    expect(screen.queryByTestId('gift-name')).toBeNull();
    fireEvent.click(screen.getByTestId('gift-open'));
    await flush();
    expect(screen.getByTestId('card-gift').dataset.state).toBe('open');
    expect(screen.getByTestId('gift-name').textContent).toBe('Квіти');
    expect(screen.getByTestId('gift-note-text').textContent).toBe('З днем');
    const by = (id: string) =>
      JSON.stringify(animate.mock.calls.filter((_, i) => (animate.mock.contexts[i] as HTMLElement).dataset?.testid === id).map((c) => (c as unknown as [Keyframe[]])[0]));
    expect((animate.mock.contexts[0] as HTMLElement).dataset.testid).toBe('gift-wrap');
    expect(by('gift-wrap')).toContain('rotate(-7deg)');
    expect(by('gift-art')).toContain('scale(0.6)');
    expect(by('gift-name')).toContain('translateY(6px)');
    expect(by('gift-note-text')).toContain('translateY(6px)');
  });

  it('відкривається один раз: наступного разу вже відкритий і без руху', () => {
    giftOpened.add('g1');
    render(<MessageCard msg={gift({ v: 1, id: 'g1', gift: 'flowers' })} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('card-gift').dataset.state).toBe('open');
    expect(screen.queryByTestId('gift-open')).toBeNull();
    expect(animate).not.toHaveBeenCalled();
  });

  it('відкритий раз — памʼятає й після нового монтування', async () => {
    const { unmount } = render(<MessageCard msg={gift({ v: 1, id: 'g2', gift: 'tea' })} meId="node_me" meName="Кирило" />);
    fireEvent.click(screen.getByTestId('gift-open'));
    await flush();
    unmount();
    render(<MessageCard msg={gift({ v: 1, id: 'g2', gift: 'tea' })} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('card-gift').dataset.state).toBe('open');
  });

  it('менше руху — лише проявлення, без масштабу', async () => {
    document.documentElement.classList.add('reduce-motion');
    render(<MessageCard msg={gift({ v: 1, id: 'g3', gift: 'cake' })} meId="node_me" meName="Кирило" />);
    fireEvent.click(screen.getByTestId('gift-open'));
    await flush();
    const frames = animate.mock.calls[0][0] as Keyframe[];
    expect(frames.every((f) => f.transform === undefined)).toBe(true);
    expect(frames.map((f) => f.opacity)).toEqual([0, 1]);
    expect(animate.mock.contexts.some((el) => (el as HTMLElement).dataset?.testid === 'gift-wrap')).toBe(false);
    for (const [f] of animate.mock.calls as unknown as [Keyframe[]][]) expect(JSON.stringify(f)).not.toContain('transform');
  });

  it('свій подарунок — одразу відкритий', () => {
    render(<MessageCard msg={gift({ v: 1, id: 'g4', gift: 'star' }, 'node_me', true)} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('card-gift').dataset.state).toBe('open');
    expect(screen.getByTestId('gift-name').textContent).toBe('Зірка');
  });

  it('рід, якого ця збірка не знає, — «Подарунок», а не порожнеча', () => {
    render(<MessageCard msg={gift({ v: 1, id: 'g5', gift: 'rocket' }, 'node_me', true)} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('gift-name').textContent).toBe('Подарунок');
  });

  it('криве тіло — чесна рамка', () => {
    render(<MessageCard msg={gift({ v: 1, gift: 'tea' })} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('card-unreadable')).toBeTruthy();
  });
});
