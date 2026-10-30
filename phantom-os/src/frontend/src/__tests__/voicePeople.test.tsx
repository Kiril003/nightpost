/**
 * Голосове й кружок ЛЮДЯМ (не лише PHANTOM): рід їде в самому описі вкладення,
 * бо телефон-адресат бачить лише тіло листа; на прийомі рід із опису робить
 * голосове голосовим — з хвилею, — а не файлом із кнопкою «завантажити».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { useMessengerStore } from '../stores/messengerStore';
import { messageFromNode, messengerApi, type NodeMessage } from '../services/messengerApi';
import { UnifiedVoiceBubble } from '../components/messenger/UnifiedVoiceBubble';

const until = (fn: () => void) => vi.waitFor(fn, { timeout: 5000, interval: 5 });
const file = (name: string, type: string) => {
  const bytes = new Uint8Array([9, 8, 7, 6, 5]);
  const f = new File([bytes], name, { type });
  if (typeof f.arrayBuffer !== 'function') Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer });
  return f;
};
const WAVE = [0.1, 0.5, 0.9, 0.4, 0.2, 0.7, 0.3, 0.6, 0.8, 0.1];

describe('надіслати людині', () => {
  beforeEach(() => {
    vi.spyOn(messengerApi, 'uploadFile').mockResolvedValue({ blob_id: '0123456789abcdef0123456789abcdef', sha256: 'ab' } as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'peer_oksana', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'peer_oksana',
    });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('голосове: рід voice і в листі, і в описі — з тривалістю й хвилею', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'sent' } as never);
    await useMessengerStore.getState().sendAttachment(file('voice_1.webm', 'audio/webm'), undefined, undefined, undefined, {
      kind: 'voice', voice: { duration: 4, waveform: WAVE },
    } as never);
    await until(() => expect(append).toHaveBeenCalled());
    const [, sent] = append.mock.calls[0] as [string, { kind: string; body: string }];
    expect(sent.kind).toBe('voice');
    const body = JSON.parse(sent.body);
    expect(body).toMatchObject({ kind: 'voice', duration: 4, waveform: WAVE });
    expect(body.blob_id).toBeTruthy();
    expect(body.key_hex).toBeTruthy();
  });

  it('кружок: рід video-note у описі', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n2', delivery: 'sent' } as never);
    await useMessengerStore.getState().sendAttachment(file('video_note_1.webm', 'video/webm'), undefined, undefined, undefined, {
      kind: 'video-note', videoNote: { duration: 7 },
    } as never);
    await until(() => expect(append).toHaveBeenCalled());
    const [, sent] = append.mock.calls[0] as [string, { kind: string; body: string }];
    expect(sent.kind).toBe('video-note');
    expect(JSON.parse(sent.body)).toMatchObject({ kind: 'video-note', duration: 7 });
  });

  it('фото роду в описі не несе — лише голосове й кружок', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n3', delivery: 'sent' } as never);
    vi.spyOn(await import('../services/imagePrep'), 'prepareImage').mockImplementation(async (f: File) => ({ file: f }) as never);
    await useMessengerStore.getState().sendAttachment(file('IMG.png', 'image/png'));
    await until(() => expect(append).toHaveBeenCalled());
    expect(JSON.parse((append.mock.calls[0][1] as { body: string }).body).kind).toBeUndefined();
  });
});

describe('прийняти від людини', () => {
  afterEach(cleanup);

  const row = (kind: string, extra: Record<string, unknown>): NodeMessage =>
    ({
      id: 'm1', conversation_id: 'peer_oksana', client_id: 'c', seq: 1, author_id: 'oksana', author_name: 'Оксана',
      kind, ciphertext: null, transport: 'relay', sent_at: '2026-10-02T10:00:00Z', edited_at: null, deleted_at: null,
      body: JSON.stringify({
        blob_id: '0123456789abcdef0123456789abcdef', key_hex: 'aa'.repeat(32), nonce_hex: 'bb'.repeat(12),
        sha256: 'cc'.repeat(32), size: 30_000, name: 'voice_1.webm', mime: 'audio/webm', ...extra,
      }),
    }) as unknown as NodeMessage;

  it('рядок мосту роду voice — голосове з хвилею, що грає', () => {
    const m = messageFromNode(row('voice', { kind: 'voice', duration: 4, waveform: WAVE }), 'me');
    expect(m.type).toBe('voice');
    expect(m.voiceData?.duration).toBe(4);
    expect(m.voiceData?.waveform).toEqual(WAVE);
    render(<UnifiedVoiceBubble msg={m} />);
    expect(screen.getByTestId('voice-play')).toBeTruthy();
    expect(screen.getByTestId('voice-media-bubble').querySelectorAll('span[style]').length).toBe(WAVE.length);
    expect(screen.getByTestId('voice-duration').textContent).toBe('0:04');
  });

  it('опис каже voice, а рядок — file (старий вузол): усе одно голосове', () => {
    const m = messageFromNode(row('file', { kind: 'voice', name: 'запис.webm', duration: 2, waveform: WAVE }), 'me');
    expect(m.type).toBe('voice');
  });

  it('кружок від людини — кружок', () => {
    const m = messageFromNode(row('video-note', { kind: 'video-note', name: 'video_note_1.webm', mime: 'video/webm', duration: 7 }), 'me');
    expect(m.type).toBe('video-note');
    expect(m.videoNoteData?.duration).toBe(7);
  });
});
