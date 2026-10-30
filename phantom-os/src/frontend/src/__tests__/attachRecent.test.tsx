/**
 * TG-look 3B: аркуш вкладень починається з «Галереї» (системний вибір, кілька),
 * далі — фото, які людина вже надіслала з цього застосунку: лише свої, лише
 * локальні, новіші першими, до 30. Немає таких — лише «Галерея», без підробок.
 * Повтор такого фото йде тим самим описом медіа, без нового завантаження.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Camera } from 'lucide-react';

vi.mock('../services/messengerMedia', async (orig) => {
  const real = await orig<typeof import('../services/messengerMedia')>();
  return { ...real, cachedMediaUrl: vi.fn(() => undefined), loadMedia: vi.fn(() => Promise.reject(new Error('not here'))), encryptForUpload: vi.fn() };
});

import { AttachSheet } from '../components/messenger/AttachSheet';
import { recentSentPhotos, RECENT_PHOTOS_MAX } from '../components/messenger/attachRecent';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import * as media from '../services/messengerMedia';
import type { Chat, Message, SecureMedia } from '../types/messenger';

const md = (n: number, over: Partial<SecureMedia> = {}): SecureMedia => ({
  name: `p${n}.jpg`, size: 1200, mime: 'image/jpeg', sha256: `sha${n}`, blobId: `blob${n}`,
  keyHex: 'k', nonceHex: 'n', thumb: `data:image/jpeg;base64,T${n}`, ...over,
});

const photo = (n: number, sentAt: string, over: Partial<Message> = {}): Message => ({
  id: `m${n}`, senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '10:00', sentAt,
  type: 'image', isSelf: true, media: md(n), text: `підпис ${n}`, ...over,
});

const chat = (id: string, messages: Message[]): Chat =>
  ({ id, title: id, avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages }) as unknown as Chat;

const CHATS = (): Chat[] => [
  chat('a', [
    photo(1, '2026-10-20T10:00:00Z'),
    photo(2, '2026-10-22T10:00:00Z', { isSelf: false, senderId: 'peer' }),
    photo(3, '2026-10-24T10:00:00Z', { isDeleted: true }),
    photo(4, '2026-10-25T10:00:00Z', { ephemeral: { expiresAt: 0, durationSeconds: 10 } } as Partial<Message>),
    photo(5, '2026-10-26T10:00:00Z', { status: 'failed' }),
    photo(6, '2026-10-27T10:00:00Z', { type: 'file', media: md(6, { name: 'a.pdf', mime: 'application/pdf' }) }),
  ]),
  chat('b', [
    photo(7, '2026-10-23T10:00:00Z'),
    photo(8, '2026-10-28T10:00:00Z'),
    photo(9, '2026-10-21T10:00:00Z', { media: md(1, { blobId: 'blob1-again' }) }),
  ]),
];

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('recentSentPhotos', () => {
  it('only own sent photos, newest first, one per picture', () => {
    expect(recentSentPhotos(CHATS()).map((m) => m.id)).toEqual(['m8', 'm7', 'm9']);
  });

  it(`caps at ${30}`, () => {
    const many = Array.from({ length: 45 }, (_, i) => photo(100 + i, new Date(Date.UTC(2026, 9, 1, 0, i)).toISOString()));
    const out = recentSentPhotos([chat('c', many)]);
    expect(RECENT_PHOTOS_MAX).toBe(30);
    expect(out).toHaveLength(30);
    expect(out[0].id).toBe('m144');
  });
});

describe('AttachSheet gallery row', () => {
  const other = [{ icon: Camera, label: 'Камера', pick: () => {}, testId: 'send-camera' }];

  it('opens with «Галерея» first, then recent sent photos; tap re-sends that message', () => {
    useMessengerStore.setState({ chats: CHATS() } as never);
    const pick = vi.fn();
    const onRecent = vi.fn();
    const onClose = vi.fn();
    render(<AttachSheet tiles={other} moreTiles={[]} onClose={onClose} gallery={{ pick, onRecent }} />);
    const row = screen.getByTestId('attach-recent');
    const items = Array.from(row.children) as HTMLElement[];
    expect(items[0].getAttribute('data-testid')).toBe('send-photo');
    expect(items[0].textContent).toBe('Галерея');
    expect(items.slice(1).map((el) => el.getAttribute('data-recent'))).toEqual(['m8', 'm7', 'm9']);
    expect((items[1].querySelector('img') as HTMLImageElement).src).toContain('T8');

    fireEvent.click(items[0]);
    expect(pick).toHaveBeenCalledTimes(1);
    fireEvent.click(items[2]);
    expect(onRecent).toHaveBeenCalledWith(expect.objectContaining({ id: 'm7' }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('no sent photos → «Галерея» is the first tile of the grid, no lone row, no placeholders', () => {
    useMessengerStore.setState({ chats: [chat('a', [photo(2, '2026-10-22T10:00:00Z', { isSelf: false })])] } as never);
    const pick = vi.fn();
    render(<AttachSheet tiles={other} moreTiles={[]} onClose={() => {}} gallery={{ pick, onRecent: () => {} }} />);
    expect(screen.queryByTestId('attach-recent')).toBeNull();
    const tiles = Array.from(screen.getByTestId('send-what-sheet').querySelectorAll('[data-kit-tile-face]')).map((f) => f.closest('[data-testid]')?.getAttribute('data-testid'));
    expect(tiles).toEqual(['send-photo', 'send-camera']);
    expect(screen.getByTestId('send-photo').textContent).toBe('Галерея');
    fireEvent.click(screen.getByTestId('send-photo'));
    expect(pick).toHaveBeenCalledTimes(1);
  });

  it('a photo with no local picture yet is not shown until it decrypts locally', async () => {
    const noThumb = photo(8, '2026-10-28T10:00:00Z', { media: md(8, { thumb: undefined }) });
    const gone = photo(7, '2026-10-27T10:00:00Z', { media: md(7, { thumb: undefined }) });
    vi.mocked(media.loadMedia).mockImplementation((m) => (m.blobId === 'blob8' ? Promise.resolve('blob:local8') : Promise.reject(new Error('not here'))));
    useMessengerStore.setState({ chats: [chat('b', [noThumb, gone])] } as never);
    render(<AttachSheet tiles={other} moreTiles={[]} onClose={() => {}} gallery={{ pick: () => {}, onRecent: () => {} }} />);
    const row = screen.getByTestId('attach-recent');
    expect(row.children).toHaveLength(1);
    await waitFor(() => expect(row.querySelector('[data-recent="m8"] img')?.getAttribute('src')).toBe('blob:local8'));
    await waitFor(() => expect(vi.mocked(media.loadMedia)).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'blob7' })));
    expect(row.querySelector('[data-recent="m7"]')).toBeNull();
    expect(row.children).toHaveLength(2);
  });
});

describe('re-send of a recent photo', () => {
  beforeEach(() => {
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1' } as never);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  });

  it('reuses the sent media descriptor: no upload, no caption, no forwarded label', async () => {
    useMessengerStore.setState({ chats: [...CHATS(), chat('target', [])], activeChatId: 'target' } as never);
    const src = photo(7, '2026-10-23T10:00:00Z', { mediaBatch: { id: 'b1', index: 1, total: 3 } });
    await useMessengerStore.getState().forwardMessage(src, 'target', { asOwn: true });

    expect(vi.mocked(media.encryptForUpload)).not.toHaveBeenCalled();
    const [to, req] = vi.mocked(messengerApi.appendMessage).mock.calls[0];
    expect(to).toBe('target');
    const body = JSON.parse(req.body as string);
    expect(body.blob_id).toBe('blob7');
    expect(body.caption).toBeUndefined();
    expect(body.batch_id).toBeUndefined();
    const sent = useMessengerStore.getState().chats.find((c) => c.id === 'target')!.messages[0];
    expect(sent.forwardFrom).toBeUndefined();
    expect(sent.text).toBeUndefined();
    expect(sent.mediaBatch).toBeUndefined();
    expect(sent.sentAt).not.toBe(src.sentAt);
  });
});
