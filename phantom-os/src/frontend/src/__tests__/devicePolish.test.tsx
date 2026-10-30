/**
 * Прохід по A52 (02.10): три вади, які лідер побачив на апараті.
 *  1. вузька дорога — тихим рядком стану списку, не банером;
 *  2. час і годинник у короткій бульбашці стояли НАД текстом;
 *  3. «Новий контакт · додано …» у шапці (тест — identityNicknames).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const api = vi.hoisted(() => ({
  pulse: vi.fn(),
  peerProfiles: vi.fn(),
}));
vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return { ...real, messengerApi: { ...real.messengerApi, ...api } };
});

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { NARROW_WORD, readPulse, useLiving } from '../services/livingPulse';
import { metaWidth } from '../components/messenger/feed/MessageRow';
import { Header } from '../components/messenger/Header';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const CHATS = [
  {
    id: 'c1', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), peerNodeId: 'c1',
  },
] as unknown as Chat[];

const renderList = (queued = 0) =>
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
      queued={queued}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

beforeEach(() => {
  api.peerProfiles.mockReset().mockResolvedValue([]);
  useLiving.setState({ pulse: null, faces: {} });
});

describe('вузька дорога', () => {
  it('дріт: narrow + фраза телефона; друкарський апостроф стає ʼ', () => {
    const p = readPulse({ sky: null, near: [], link: { online: true, direct: false, narrow: true, narrow_word: "слабкий зв'язок — текст іде першим" } });
    expect(p.link.narrow).toBe(true);
    expect(p.link.narrowWord).toBe('слабкий звʼязок — текст іде першим');
    // Старий міст: narrow без слова — своє слово, а не мовчання.
    expect(readPulse({ sky: null, near: [], link: { online: true, direct: false, narrow: true } }).link.narrowWord).toBe(NARROW_WORD);
    // Не вузька — слова немає, навіть якщо міст його дав.
    expect(readPulse({ sky: null, near: [], link: { online: true, direct: false, narrow: false, narrow_word: 'х' } }).link.narrowWord).toBeNull();
  });

  it('дорога звичайна — ні рядка, ні значка', async () => {
    api.pulse.mockReset().mockResolvedValue({ sky: null, near: [], link: { online: true, direct: true, narrow: false } });
    renderList();
    await vi.waitFor(() => expect(useLiving.getState().pulse).not.toBeNull());
    expect(screen.queryByTestId('link-narrow-line')).toBeNull();
    expect(screen.queryByTestId('link-narrow-icon')).toBeNull();
  });
});

describe('час у кінці останнього рядка', () => {
  it('годинник «надсилається» займає місце в розпірнику так само, як галочка', () => {
    expect(metaWidth(false, true)).toBeGreaterThan(metaWidth(false, false));
  });

  it('підлога дотику на телефоні не роздуває `.touch-floor-inline` (рядок часу був 48 px заввишки)', () => {
    const css = readFileSync(resolve(__dirname, '../styles/messenger.css'), 'utf8');
    const floor = css.match(/body :is\(button[^{]*\):is\(\.min-h-0, \.min-w-0\)[^{]*\{/);
    expect(floor?.[0]).toContain(':not(.touch-floor-inline)');
  });
});

describe('шапка й картка людини без імені', () => {
  const unnamed = (id: string, title: string, addedAt: string) =>
    ({
      id, title, avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 0, messages: [],
      peerNodeId: id, addedAt,
    }) as unknown as Chat;

  it('«Без імені» без «· додано …»; дата — у картці, і там теж не «Вузол 4d839df7»', () => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    const a = unnamed('n1', 'Вузол 4d839df7', '2026-09-27T00:49:00Z');
    const b = unnamed('n2', 'Новий контакт', '2026-09-27T16:04:00Z');
    useMessengerStore.setState({ chats: [a, b], typingStatus: {} });
    const noop = () => {};
    const { container } = render(
      <MemoryRouter>
        <Header
          currentChat={a}
          currentUser={USER}
          onOpenSettings={noop}
          onOpenGroupDetails={noop}
          isSoundEnabled
          onToggleSound={noop}
          onToggleSearch={noop}
          isSearching={false}
        />
      </MemoryRouter>,
    );
    const head = container.querySelector('header')!;
    expect(head.textContent).toContain('Без імені');
    expect(head.textContent).not.toMatch(/додано|Вузол|Новий контакт/);
    fireEvent.click(head.querySelector('[title="Профіль"]')!);
    expect(screen.getByTestId('peer-profile-name').textContent).toBe('Без імені');
    expect(screen.getByTestId('peer-profile-added').textContent).toMatch(/^Додано 27 вер\./);
  });
});

describe('BACK знімає аркуш вкладень, а не розмову', () => {
  it('відкритий аркуш стоїть у стеку BACK; закритий — зникає з нього', async () => {
    const { AttachSheet } = await import('../components/messenger/AttachSheet');
    const { usePhoneLayerStore } = await import('../stores/phoneLayerStore');
    const onClose = vi.fn();
    const { unmount } = render(<AttachSheet tiles={[]} moreTiles={[]} onClose={onClose} />);
    const keys = Object.keys(usePhoneLayerStore.getState().layers).filter((k) => k.startsWith('attach'));
    expect(keys).toHaveLength(1);
    usePhoneLayerStore.getState().layers[keys[0]]();
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
    expect(Object.keys(usePhoneLayerStore.getState().layers).filter((k) => k.startsWith('attach'))).toHaveLength(0);
  });
});

describe('BACK закриває пошук у списку, а не застосунок', () => {
  it('відкритий пошук стоїть у стеку BACK; BACK його закриває', async () => {
    const { usePhoneLayerStore } = await import('../stores/phoneLayerStore');
    api.pulse.mockReset().mockResolvedValue({ sky: null, near: [], link: { online: true, direct: true } });
    renderList();
    expect(usePhoneLayerStore.getState().layers['list:search']).toBeUndefined();
    fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
    const close = usePhoneLayerStore.getState().layers['list:search'];
    expect(close).toBeTypeOf('function');
    await act(async () => close());
    expect(usePhoneLayerStore.getState().layers['list:search']).toBeUndefined();
    expect(screen.getByLabelText('Пошук у розмовах')).toBeTruthy();
  });
});

describe('«Збережене» на телефоні — з мікрофоном: сховок нотаток тримає голосове й кружок', () => {
  const props = {
    onSendMessage: () => {}, onSendVoiceMessage: () => {}, onOpenActions: () => {}, onOpenScheduler: () => {},
    replyingTo: null, onCancelReply: () => {}, editingMessage: null, onCancelEdit: () => {}, onSaveEdit: () => {},
    selectedMessagesForQuote: [], onSynthesizeMultiQuote: () => {}, onClearSelectedQuotes: () => {},
  };
  const saved = { id: 'chat_saved_notes', title: 'Збережене', type: 'saved', circle: 'saved', avatar: '', unreadCount: 0, pinned: true, messages: [] } as unknown as Chat;
  const person = { id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], peerNodeId: 'o1' } as unknown as Chat;

  type ComposerProps = React.ComponentProps<typeof MessageComposer>;
  // 02.10 мікрофон тут ховали, бо міст відмовляв роду voice. Тепер міст
  // кладе голосове й кружок у сховок нотаток так само, як фото, — і мертвої
  // кнопки «Надіслати» замість мікрофона більше не треба.
  it('у «Збереженому» мікрофон є, як і в людини; набране слово — «Надіслати»', () => {
    const w = window as unknown as { PhantomBridge?: unknown };
    w.PhantomBridge = { call: () => JSON.stringify({ ok: false, status: 404 }) };
    try {
      useMessengerStore.setState({ chats: [saved, person], activeChatId: 'chat_saved_notes', storageFailure: null } as never);
      const one = render(<MessageComposer {...(props as unknown as ComposerProps)} chatId="chat_saved_notes" />);
      expect(screen.getByTestId('composer-media-button')).toBeTruthy();
      expect(screen.queryByTestId('composer-send')).toBeNull();
      fireEvent.change(screen.getByTestId('composer-textarea'), { target: { value: 'нотатка' } });
      expect((screen.getByTestId('composer-send') as HTMLButtonElement).disabled).toBe(false);
      one.unmount();
      render(<MessageComposer {...(props as unknown as ComposerProps)} chatId="chat_o" />);
      expect(screen.getByTestId('composer-media-button')).toBeTruthy();
    } finally {
      delete w.PhantomBridge;
    }
  });
});

describe('наведення не прилипає на дотику', () => {
  it('tailwind будує `hover:` лише під (hover: hover)', () => {
    const config = readFileSync(resolve(__dirname, '../../tailwind.config.ts'), 'utf8');
    expect(config).toMatch(/future:\s*\{\s*hoverOnlyWhenSupported:\s*true\s*\}/);
  });
});
