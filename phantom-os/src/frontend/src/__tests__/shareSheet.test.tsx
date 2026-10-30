/**
 * Аркуш «Надіслати» (list-fix share): сітка 4 колонки, Збережене першим —
 * значком, дотик ставить позначку, «Надіслати · N» вимкнено при нулі, кожен
 * адресат отримує окрему копію з підписом. Досяжність — із дії «Переслати»
 * в розмові (SelectionBar) крізь справжній MessengerRoot.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { RecipientPicker } from '../components/messenger/recipients/RecipientPicker';
import { ForwardMessageModal } from '../components/messenger/ForwardMessageModal';
import { ShareSheet } from '../components/messenger/share/ShareSheet';
import { SelectionBar } from '../components/messenger/SelectionBar';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import { messengerApi } from '../services/messengerApi';
import type { Chat, Message, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const ago = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title, type: 'dm', circle: 'friends', messages: [], unreadCount: 0, lastAt: ago(10), ...extra }) as unknown as Chat;

const CHATS = [
  chat('olya', 'Оля Коваль', { lastAt: ago(1) }),
  chat('yana', 'Яна', { lastAt: ago(2) }),
  chat('chat_saved_notes', 'Збережене', { type: 'saved', lastAt: ago(500) } as Partial<Chat>),
  chat('marko', 'Марко', { lastAt: ago(3) }),
  chat('lake', 'На озеро', { type: 'group', lastAt: ago(4) } as Partial<Chat>),
];

const photo = {
  id: 'p1',
  senderId: 'p1',
  senderName: 'Оля',
  type: 'image',
  text: '',
  imageData: { url: 'data:image/png;base64,AA==' },
  timestamp: '18:40',
  isSelf: false,
  status: 'sent',
} as unknown as Message;

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const tiles = () => [...document.querySelectorAll('[data-pick-row]')].map((b) => b.getAttribute('data-pick-row'));
const sendBtn = () => document.querySelector('[data-share-send]') as HTMLButtonElement;

describe('сітка адресатів', () => {
  it('Збережене першим і значком, не літерою; далі свіжі', () => {
    render(<RecipientPicker chats={CHATS} selected={[]} onChange={() => {}} />);
    expect(document.querySelector('[data-pick-grid]')).toBeTruthy();
    expect(tiles()).toEqual(['chat_saved_notes', 'olya', 'yana', 'marko', 'lake']);
    const saved = document.querySelector('[data-pick-row="chat_saved_notes"]')!;
    expect(saved.querySelector('[data-pick-icon="saved"] svg')).toBeTruthy();
    expect(saved.querySelector('[data-pick-icon="saved"]')!.textContent).toBe('');
    expect(saved.textContent).toBe('Збережене');
  });

  it('дотик ставить позначку, ще дотик знімає', () => {
    let ids: string[] = [];
    const { rerender } = render(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    fireEvent.click(document.querySelector('[data-pick-row="yana"]')!);
    expect(ids).toEqual(['yana']);
    rerender(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    const tile = document.querySelector('[data-pick-row="yana"]')!;
    expect(tile.getAttribute('aria-pressed')).toBe('true');
    expect(tile.querySelector('[data-pick-check]')).toBeTruthy();
    expect(tile.querySelector('[data-pick-ring]')).toBeTruthy();
    expect(document.querySelector('[data-pick-row="olya"] [data-pick-check]')).toBeNull();
    fireEvent.click(tile);
    expect(ids).toEqual([]);
  });

  it('пошук звужує сітку, вибране лишається', () => {
    let ids: string[] = ['olya'];
    const { rerender } = render(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    fireEvent.change(document.querySelector('[data-pick-search]')!, { target: { value: 'мар' } });
    expect(tiles()).toEqual(['marko']);
    fireEvent.click(document.querySelector('[data-pick-row="marko"]')!);
    rerender(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    expect(ids).toEqual(['olya', 'marko']);
  });
});

describe('аркуш «Надіслати» з пересилання', () => {
  const mount = (go = vi.fn()) =>
    render(
      <ForwardMessageModal isOpen onClose={() => {}} chats={CHATS} currentChatId="olya" messagesToForward={[photo]} onConfirmForward={go} />,
    );

  it('заголовок, рядок того, що йде, і «Надіслати» вимкнено при нулі', () => {
    mount();
    const sheet = screen.getByTestId('forward-message');
    expect(sheet.querySelector('[role="dialog"]')!.getAttribute('aria-label')).toBe('Надіслати');
    expect(sheet.querySelector('[data-share-preview]')!.textContent).toContain('Фото');
    expect(sheet.querySelector('[data-share-preview] img')).toBeTruthy();
    expect(sheet.querySelector('[data-share-caption]')!.getAttribute('placeholder')).toBe('Підпис');
    expect(tiles()).not.toContain('olya');
    expect(sendBtn().disabled).toBe(true);
    expect(sendBtn().textContent).toBe('Надіслати');
    fireEvent.click(document.querySelector('[data-pick-row="yana"]')!);
    expect(sendBtn().disabled).toBe(false);
    expect(sendBtn().textContent).toBe('Надіслати · 1');
  });

  it('троє й підпис — три окремі копії, підпис кожному', () => {
    const go = vi.fn();
    mount(go);
    for (const id of ['chat_saved_notes', 'yana', 'lake']) fireEvent.click(document.querySelector(`[data-pick-row="${id}"]`)!);
    fireEvent.change(document.querySelector('[data-share-caption]')!, { target: { value: 'Ринок у суботу' } });
    expect(sendBtn().textContent).toBe('Надіслати · 3');
    fireEvent.click(sendBtn());
    expect(go).toHaveBeenCalledTimes(3);
    expect(go).toHaveBeenCalledWith('chat_saved_notes', { caption: 'Ринок у суботу' });
    expect(go).toHaveBeenCalledWith('yana', { caption: 'Ринок у суботу' });
    expect(go).toHaveBeenCalledWith('lake', { caption: 'Ринок у суботу' });
  });
});

describe('аркуш без предмета — для вибору розмов у списку', () => {
  it('відкривається з вибраними, текст іде кожному окремо', () => {
    const send = vi.fn();
    useMessengerStore.setState({ chats: CHATS, sendMessage: send } as never);
    const close = vi.fn();
    render(<ShareSheet chats={CHATS} initialSelected={['olya', 'marko']} onClose={close} />);
    expect(document.querySelector('[data-pick-row="olya"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(sendBtn().disabled).toBe(true);
    fireEvent.change(document.querySelector('[data-share-caption]')!, { target: { value: 'Привіт, {імʼя}' } });
    fireEvent.click(sendBtn());
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenCalledWith('Привіт, Оля', { to: 'olya' });
    expect(send).toHaveBeenCalledWith('Привіт, Марко', { to: 'marko' });
    expect(close).toHaveBeenCalled();
  });
});

describe('досяжність: «Переслати» в розмові → аркуш → три копії з підписом', () => {
  it('SelectionBar.forward відкриває аркуш у MessengerRoot, і він шле кожному', { timeout: 30000 }, async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: {} }),
    };
    const boot = vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    const forwardMessage = vi.fn().mockResolvedValue(undefined);
    const sendMessage = vi.fn();
    const room = chat('olya', 'Оля Коваль', { messages: [photo] } as Partial<Chat>);
    try {
      render(
        <MemoryRouter>
          <MessengerRoot />
        </MemoryRouter>,
      );
      await waitFor(() => expect(boot).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 50));
      useMessengerStore.setState({
        chats: [room, ...CHATS.slice(1)],
        forwardMessage,
        sendMessage,
        currentUser: { id: 'me', name: 'Я', avatar: '' } as unknown as UserProfile,
        selectedMessageIds: ['p1'],
        multiSelectMode: true,
        selectionAnchorId: 'p1',
      } as never);
      render(<SelectionBar chat={room} />);
      fireEvent.click(document.querySelector('[data-sel-action="forward"]')!);
      await screen.findByTestId('forward-message', {}, { timeout: 15000 });
      for (const id of ['chat_saved_notes', 'yana', 'marko']) fireEvent.click(document.querySelector(`[data-pick-row="${id}"]`)!);
      fireEvent.change(document.querySelector('[data-share-caption]')!, { target: { value: 'Глянь' } });
      fireEvent.click(sendBtn());
      await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(3));
      expect(forwardMessage).toHaveBeenCalledTimes(3);
      expect(forwardMessage.mock.calls.map((c) => c[1])).toEqual(['chat_saved_notes', 'yana', 'marko']);
      expect(forwardMessage.mock.calls[0][0].id).toBe('p1');
      expect(sendMessage).toHaveBeenCalledWith('Глянь', { to: 'chat_saved_notes' });
      expect(sendMessage).toHaveBeenCalledWith('Глянь', { to: 'yana' });
      expect(sendMessage).toHaveBeenCalledWith('Глянь', { to: 'marko' });
    } finally {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
      useMessengerStore.setState({ selectedMessageIds: [], multiSelectMode: false, selectionAnchorId: null, isForwardModalOpen: false } as never);
    }
  });
});
