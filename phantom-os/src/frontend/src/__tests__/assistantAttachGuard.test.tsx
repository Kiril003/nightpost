import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { chatApi } from '../services/api';
import { modelText } from '../services/assistantInput';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useMessageAssist } from '../components/messenger/feed/useMessageAssist';
import type { Message } from '../types/messenger';

const KEY = '0123456789abcdef'.repeat(4);
const NONCE = 'fedcba987654321001234567';
const SHA = 'abcdef0123456789'.repeat(4);
const descriptor = (extra: Record<string, unknown> = {}) =>
  JSON.stringify({ name: 'IMG_1.jpg', size: 204800, mime: 'image/jpeg', sha256: SHA, blob_id: 'b_7f3a9c', key_hex: KEY, nonce_hex: NONCE, ...extra });

const LONG_HEX = /[0-9a-f]{24,}/i;

const msg = (text: string): Message =>
  ({ id: 'm1', senderId: 'u1', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'text', text, isSelf: true }) as Message;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('опис вкладення не доходить до моделі як текст', () => {
  it('підсумок листа-вкладення не шле ключ, сіль і blob_id', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' } } as never);
    const { result } = renderHook(() => useMessageAssist(() => {}));
    await act(async () => {
      await result.current.handleToggleSummary(msg(descriptor({ caption: 'що це за міст?' })));
    });
    const sent = send.mock.calls.map((c) => String((c[0] as { content: string }).content)).join('\n');
    expect(sent).not.toMatch(/key_hex|nonce_hex|blob_id|sha256/);
    expect(sent).not.toMatch(LONG_HEX);
    expect(sent).toContain('що це за міст?');
  });

  it('вкладення без підпису до моделі не йде зовсім', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' } } as never);
    const toast = vi.fn();
    const { result } = renderHook(() => useMessageAssist(toast));
    await act(async () => {
      await result.current.handleToggleTranslate(msg(descriptor()), 'EN');
      await result.current.handleGenerateActionItems(msg(descriptor()));
    });
    expect(send).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledTimes(2);
  });

  it('звичайний текст іде як був', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' } } as never);
    const { result } = renderHook(() => useMessageAssist(() => {}));
    await act(async () => {
      await result.current.handleToggleSummary(msg('купи хліб і молоко'));
    });
    expect(String((send.mock.calls[0][0] as { content: string }).content)).toContain('купи хліб і молоко');
  });
});

describe('modelText', () => {
  it('голосове віддає розшифровку, а не опис', () => {
    expect(modelText(descriptor({ kind: 'voice', duration: 4, waveform: [1, 2], transcript: 'нагадай про хліб' }))).toBe('нагадай про хліб');
  });
  it('обрізаний чи вкладений опис із полем ключа не йде зовсім', () => {
    expect(modelText(descriptor().slice(0, 200))).toBeNull();
    expect(modelText(JSON.stringify({ __msg: JSON.parse(descriptor()) }))).toBeNull();
  });
  it('JSON без полів вкладення лишається текстом', () => {
    expect(modelText('{"a":1}')).toBe('{"a":1}');
  });
  it('екранований опис чи опис у підписі теж не йде', () => {
    expect(modelText(JSON.stringify(descriptor()))).toBeNull();
    expect(modelText(descriptor({ caption: descriptor() }))).toBeNull();
    expect(modelText('перешли blob_id b_7f3a9c')).toBeNull();
  });
});

describe('справжні листи-вкладення у стрічці', () => {
  const photo = { id: 'p1', senderId: 'u1', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'image', text: undefined, media: { blobId: 'x' } } as unknown as Message;
  const voice = { id: 'v1', senderId: 'u1', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'voice', text: undefined, voiceData: { duration: 4, waveform: [], transcript: 'нагадай про хліб' } } as unknown as Message;

  it('фото без підпису каже, чому мовчить, і нічого не шле', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' } } as never);
    const toast = vi.fn();
    const { result } = renderHook(() => useMessageAssist(toast));
    await act(async () => {
      await result.current.handleToggleSummary(photo);
    });
    expect(send).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledTimes(1);
  });

  it('голосове йде розшифровкою', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' } } as never);
    const { result } = renderHook(() => useMessageAssist(() => {}));
    await act(async () => {
      await result.current.handleToggleSummary(voice);
    });
    expect(String((send.mock.calls[0][0] as { content: string }).content)).toContain('нагадай про хліб');
  });
});

describe('розмова з помічником на ПК', () => {
  it('вставлений опис не стає запитом, а відповідь — один чесний рядок', async () => {
    const CHAT = 'chat_phantom_assistant';
    useMessengerStore.setState({
      chats: [{ id: CHAT, title: 'Nightpost', avatar: '', type: 'dm', circle: 'personal', unreadCount: 0, messages: [] }],
      activeChatId: CHAT,
    } as never);
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'row_q' } as never);
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValue({ message: { content: 'ok' }, session_id: 's1' } as never);
    await useMessengerStore.getState().sendMessage(descriptor());
    expect(send).not.toHaveBeenCalled();
    const said = useMessengerStore.getState().chats[0].messages.filter((m) => m.senderId === 'assistant_phantom');
    expect(said).toHaveLength(1);
    expect(said[0].text).not.toMatch(/key_hex|blob_id/);
  });
});
