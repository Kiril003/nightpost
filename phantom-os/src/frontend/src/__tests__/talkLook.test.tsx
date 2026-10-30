/**
 * Новий вигляд розмови (макет власника 26.09): шапка, смуга живих чипів,
 * своя бурштинова бульбашка, голе фото, голосове, композер, картка місця.
 *
 * Кожна перевірка — про закон, а не про піксель: чип живе лише з даними цієї
 * розмови, зелений — лише «поруч», час на бурштині тримає 4.5:1 у всіх фазах,
 * координат на екрані немає, розшифровку видно без дотику.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
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
    },
  };
});

import { Header } from '../components/messenger/Header';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { UnifiedVoiceBubble } from '../components/messenger/UnifiedVoiceBubble';
import { GeoPointBubble } from '../components/messenger/GeoPointBubble';
import { FormattedMessageText } from '../components/messenger/FormattedMessageText';
import { useLiving } from '../services/livingPulse';
import { useMessengerStore } from '../stores/messengerStore';
import { THEMES, contrast } from '../styles/formTokens';
import type { Chat, Message, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const msg = (id: string, extra: Partial<Message> = {}): Message =>
  ({
    id,
    senderId: 'c1',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '19:10',
    type: 'text',
    text: 'привіт',
    ...extra,
  }) as Message;

const photo = (id: string, extra: Partial<Message> = {}): Message =>
  msg(id, { type: 'image', text: undefined, media: { blobId: id } as unknown as Message['media'], ...extra });

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({
    id: 'chat_o',
    title: 'Оксана',
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    messages: [],
    peerNodeId: 'c1',
    ...extra,
  }) as unknown as Chat;

const headerProps = {
  currentUser: USER,
  onOpenActions: () => {},
  onOpenSettings: () => {},
  onOpenGroupDetails: () => {},
  isSoundEnabled: true,
  onToggleSound: () => {},
  onToggleSearch: () => {},
  isSearching: false,
  onBack: () => {},
};

const renderHeader = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header {...headerProps} currentChat={c} />
    </MemoryRouter>,
  );

beforeEach(() => {
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {} } as never);
});
afterEach(cleanup);

// ── шапка ──────────────────────────────────────────────────────────────────

describe('шапка розмови (§7.1)', () => {
  it('праворуч — привиди 44: дзвінок, відео, «ще»; без кольорових кіл; відео видно й на телефоні', () => {
    renderHeader(chat());
    const video = screen.getByLabelText('Відеодзвінок');
    expect(video.className).not.toMatch(/(^|\s)hidden(\s|$)/);
    for (const b of [screen.getByLabelText('Аудіодзвінок'), video, screen.getByLabelText('Ще')]) {
      expect(b.className).toContain('rounded-full');
      expect(b.className).toContain('w-11');
      expect(b.className).toMatch(/(^|\s)text-ink(\s|$)/);
      expect(b.className).not.toMatch(/bg-raised|bg-raised/);
    }
  });

  it('«поруч» під іменем — словом, без зеленого (np 1.1: зелений лише для грошей)', () => {
    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: null, lanSeenAtMs: Date.now() } } });
    const { unmount } = renderHeader(chat());
    const near = screen.getByTestId('header-presence');
    expect(near.textContent).toBe('поруч');
    expect(near.className).not.toContain('text-success');
    unmount();

    useLiving.setState({ faces: { c1: { avatar: null, heardAtMs: Date.now() - 10 * 60_000, lanSeenAtMs: null } } });
    renderHeader(chat());
    const heard = screen.getByTestId('header-presence');
    expect(heard.textContent).toMatch(/^\d+ (хв|год|дн)\S* тому$/);
    expect(heard.className).not.toContain('text-success');
  });

  it('аватар 40: людина — коло, група — квадрат тону (лист D)', () => {
    const { unmount, container } = renderHeader(chat());
    const face = container.querySelector('[data-shared="chat-avatar"] [aria-label]') as HTMLElement;
    expect(face.className).toContain('w-10');
    expect(face.className).toContain('rounded-full');
    unmount();
    const g = renderHeader(chat({ type: 'group', title: 'Двір' }));
    const group = g.container.querySelector('[data-shared="chat-avatar"] [aria-label]') as HTMLElement;
    expect(group.className).toContain('rounded-control');
  });

  it('шапку ніколи не тонує людина: ні відтінку фото, ні сяйва', () => {
    const { container } = renderHeader(chat({ avatar: 'data:image/webp;base64,AAAA' }));
    expect(screen.queryByTestId('header-tint')).toBeNull();
    expect(container.innerHTML).not.toMatch(/blur-\[|gradient/);
  });

  it('рядка чипів «Пошук» під шапкою немає — пошук живе в «Ще»', () => {
    renderHeader(chat({ messages: [photo('p1'), msg('t')] }));
    expect(screen.queryByTestId('talk-chip-search')).toBeNull();
    expect(screen.queryByTestId('talk-chip-photos')).toBeNull();
  });
});

// ── бульбашки: своя — тихий бурштиновий тон, не заливка (§7.4) ──────────────

const FORM_CSS = readFileSync(join(process.cwd(), 'src/styles/form.css'), 'utf8');
const TOKENS = JSON.parse(readFileSync(join(process.cwd(), '../../../docs/design/system/tokens.json'), 'utf8'));

describe('своя бульбашка', () => {
  it('тон `self` з текстом `on-self`, а не суцільний бурштин; ролі тексту всередині перевизначено', () => {
    const rule = FORM_CSS.slice(FORM_CSS.indexOf('html:root .msg-bubble-self,'));
    const body = rule.slice(0, rule.indexOf('}'));
    expect(body).toContain('background-color: var(--ph-self)');
    expect(body).toContain('color: var(--ph-on-self)');
    expect(body).toContain('--ph-text-3: var(--ph-on-self-2)');
    expect(body).not.toContain('--ph-accent)');
  });

  for (const theme of THEMES) {
    it(`текст, час і ✓ на своїй бульбашці тримають 4.5:1 (${theme})`, () => {
      const c = (n: string) => TOKENS.color[theme][n].value as string;
      expect(contrast(c('on-self'), c('self'))).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c('on-self-2'), c('self'))).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c('text-3'), c('surface'))).toBeGreaterThanOrEqual(4.5);
    });
  }

  it('радіус 16; хвостовий кут 8 з боку автора лише в останньої бульбашки черги', () => {
    expect(FORM_CSS).toContain('.bubble-contour-self-single.bubble-contour-self-single { border-radius: 16px 16px 8px 16px; }');
    expect(FORM_CSS).toContain('.bubble-contour-other-single.bubble-contour-other-single { border-radius: 16px 16px 16px 8px; }');
  });

  it('чужа — `surface` без тону людини', () => {
    const rule = FORM_CSS.slice(FORM_CSS.indexOf('html:root .msg-bubble-other,'));
    const body = rule.slice(0, rule.indexOf('}'));
    expect(body).toContain('background-color: var(--ph-surface)');
    expect(body).not.toMatch(/peer|person/);
  });

  it('текст листа говорить ролями, а не сирими hex', () => {
    const { container } = render(
      <FormattedMessageText text={'Заберу **номер** https://example.com/x @Оксана'} />,
    );
    const html = container.innerHTML;
    expect(html).not.toMatch(/\[#[0-9A-Fa-f]{6}\]/);
    expect((container.firstChild as HTMLElement).className).toMatch(/text-(f-)?ink/);
  });

  it('голе фото: без полів, час на темній пігулці поверх кадру', () => {
    const rule = FORM_CSS.slice(FORM_CSS.indexOf('html:root .msg-bubble-photo .msg-meta-row {'));
    const body = rule.slice(0, rule.indexOf('}'));
    expect(body).toContain('position: absolute');
    expect(body).toMatch(/background-color: #0F0D0BA6/);
  });
});

// ── голосове ───────────────────────────────────────────────────────────────

describe('голосове', () => {
  const voice = (transcript?: string) =>
    msg('v1', {
      type: 'voice',
      text: undefined,
      voiceData: { duration: 12, waveform: Array.from({ length: 20 }, (_, i) => (i % 5) / 5), audioUrl: 'blob:x', transcript },
    });

  it('коло 40 кольору тексту з трикутником кольору бульбашки і тривалість', () => {
    render(<UnifiedVoiceBubble msg={voice()} />);
    const play = screen.getByTestId('voice-play');
    expect(play.className).toContain('w-10');
    expect(play.getAttribute('style')).toContain('--ph-voice-glyph');
    expect(play.className).toContain('bg-ink');
    expect(screen.getByTestId('voice-duration').textContent).toBe('0:12');
  });

  it('розшифровку видно одразу під хвилею, без «Розшифровка»-перемикача', () => {
    render(<UnifiedVoiceBubble msg={voice('Номер 2045')} />);
    expect(screen.getByTestId('voice-transcript').textContent).toContain('Номер 2045');
    expect(screen.queryByText('Розшифровка')).toBeNull();
  });
});

// ── картка місця ──────────────────────────────────────────────────────────

describe('картка місця', () => {
  it('назва, вік і «Маршрут» — координат на екрані немає', () => {
    render(
      <MemoryRouter>
        <GeoPointBubble point={{ lat: 50.45123, lon: 30.52345, atMs: Date.now(), label: 'Нова пошта №12', accuracyM: 8 }} />
      </MemoryRouter>,
    );
    expect(within(screen.getByTestId('geo-point-bubble')).getByTestId('card-title').textContent).toBe('Нова пошта №12');
    expect(screen.getByRole('button', { name: 'Маршрут' })).toBeTruthy();
    const card = screen.getByTestId('geo-point-bubble');
    expect(card.textContent).not.toMatch(/50\.45|30\.52|±/);
  });

  it('чужа точка без назви — «Місце», не «Моє місце»', () => {
    render(
      <MemoryRouter>
        <GeoPointBubble point={{ lat: 1, lon: 2, atMs: Date.now() }} />
      </MemoryRouter>,
    );
    expect(within(screen.getByTestId('geo-point-bubble')).getByTestId('card-title').textContent).toBe('Місце');
  });
});

// ── композер ──────────────────────────────────────────────────────────────

describe('композер', () => {
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
    chatId: 'chat_o',
  };

  it('макет 04: «+» привид 44 поруч із полем-пігулкою радіус 24; бурштин — лише «Надіслати», коли є текст', () => {
    useMessengerStore.setState({ chats: [chat()], activeChatId: 'chat_o', storageFailure: null } as never);
    render(<MessageComposer {...(composerProps as unknown as React.ComponentProps<typeof MessageComposer>)} />);
    const plus = screen.getByTestId('composer-attach');
    expect(plus.className).toContain('w-11');
    expect(plus.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(plus.className).not.toMatch(/bg-(f-)?accent/);
    const pill = document.querySelector('[data-composer-pill]') as HTMLElement;
    expect(screen.getByTestId('composer-field').className).toContain('rounded-composer');
    expect(pill.contains(plus)).toBe(true);
    expect(pill.contains(screen.getByTestId('composer-field'))).toBe(true);
    expect(screen.getByTestId('composer-textarea').getAttribute('placeholder')).toBe('Повідомлення');
    const mic = screen.getByTestId('composer-media-button');
    expect(mic.className).not.toMatch(/bg-(f-)?accent/);
    expect(screen.queryByTestId('composer-send')).toBeNull();
    fireEvent.change(screen.getByTestId('composer-textarea'), { target: { value: 'Буду о сьомій' } });
    const send = screen.getByTestId('composer-send');
    expect(send.className).toMatch(/\bbg-accent\b/);
    expect(send.className).toContain('w-11');
    expect(pill.contains(send)).toBe(true);
    expect(screen.queryByTestId('composer-media-button')).toBeNull();
  });

  it('аркуш вкладень: усі плитки одразу (без «Ще»); ні «Де я» окремо, ні «до 4 МБ»', () => {
    useMessengerStore.setState({ chats: [chat()], activeChatId: 'chat_o', storageFailure: null } as never);
    render(<MessageComposer {...(composerProps as unknown as React.ComponentProps<typeof MessageComposer>)} />);
    fireEvent.click(screen.getByTestId('composer-attach'));
    const sheet = screen.getByTestId('send-what-sheet');
    const labels = [...sheet.querySelectorAll('button')].map((b) => b.textContent);
    expect(labels.slice(0, 4)).toEqual(['Галерея', 'Камера', 'Файл', 'Місце']);
    expect(labels).not.toContain('Ще');
    expect(labels).not.toContain('Де я');
    expect(sheet.textContent).not.toMatch(/МБ/);
  });
});
