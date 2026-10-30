import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';

const cropped = new File([new Uint8Array([9, 9])], 'trip.jpg', { type: 'image/jpeg' });
vi.mock('../services/photoCrop', async (orig) => ({
  ...(await orig<typeof import('../services/photoCrop')>()),
  cropImage: vi.fn(async () => cropped),
}));

import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi, messageFromNode, type NodeMessage } from '../services/messengerApi';
import * as messengerMedia from '../services/messengerMedia';
import { cropImage, cropPlan, dragFrame, FULL_FRAME } from '../services/photoCrop';
import { MAX_EDGE, prepareImage, type ImagePrepDeps } from '../services/imagePrep';
import { PHOTO_QUALITY_KEY } from '../services/photoQuality';
import type { Chat } from '../types/messenger';

const chat = (): Chat => ({ id: 'c_p04', title: 'Марта', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [] });

const props = {
  onSendMessage: () => {}, onSendVoiceMessage: () => {}, onOpenActions: () => {}, onOpenScheduler: () => {},
  replyingTo: null, onCancelReply: () => {}, editingMessage: null, onCancelEdit: () => {}, onSaveEdit: () => {},
  selectedMessagesForQuote: [], onSynthesizeMultiQuote: () => {}, onClearSelectedQuotes: () => {}, chatId: 'c_p04',
};

const photo = (name: string) => new File([new Uint8Array([1, 2, 3, 4])], name, { type: 'image/jpeg' });
const pick = (files: File[]) => {
  const input = screen.getByTestId('composer-file-input');
  Object.defineProperty(input, 'files', { value: files, configurable: true });
  fireEvent.change(input);
};

const realWire = () => {
  vi.spyOn(messengerApi, 'queueStatus').mockResolvedValue({ queued: 0 } as never);
  const encrypt = vi.spyOn(messengerMedia, 'encryptForUpload').mockResolvedValue({
    ciphertext: new Blob(['enc']), keyHex: 'aa'.repeat(32), nonceHex: 'bb'.repeat(12), sha256: 'cc'.repeat(32),
  });
  vi.spyOn(messengerApi, 'uploadFile').mockResolvedValue({ blob_id: '0123456789abcdef0123456789abcdef', size: 3, sha256: 'cc'.repeat(32), state: 'stored' } as never);
  let n = 0;
  const append = vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async () => ({ id: `srv_${(n += 1)}`, delivery_state: 'sent' }) as never);
  return { encrypt, append };
};

const realSend = (({ sendAttachment, sendAttachments }) => ({ sendAttachment, sendAttachments }))(useMessengerStore.getState());

describe('P04 фото перед надсиланням: обрізати, якість, альбом', () => {
  beforeEach(() => {
    useMessengerStore.setState(realSend);
    try { localStorage.removeItem(PHOTO_QUALITY_KEY); } catch { /* */ }
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Кирило', avatar: '' } as never,
      chats: [chat()], activeChatId: 'c_p04', storageFailure: null, replyingTo: null, editingMessage: null,
    });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('одне фото відкриває передогляд з «Обрізати»; обрізаний файл і їде на вузол', async () => {
    const { encrypt } = realWire();
    render(<MessageComposer {...props} />);
    pick([photo('trip.jpg')]);

    expect(screen.getByTestId('attach-batch-preview')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('attach-batch-crop'));
    const sheet = screen.getByTestId('media-crop');
    fireEvent.load(sheet.querySelector('img')!);
    fireEvent.click(screen.getByTestId('media-crop-rotate'));
    fireEvent.click(screen.getByTestId('media-crop-done'));
    await waitFor(() => expect(screen.queryByTestId('media-crop')).not.toBeInTheDocument());
    expect(cropImage).toHaveBeenCalledWith(expect.objectContaining({ name: 'trip.jpg' }), FULL_FRAME, 1);

    fireEvent.click(screen.getByTestId('attach-batch-send'));
    await waitFor(() => expect(encrypt).toHaveBeenCalledTimes(1));
    expect(encrypt.mock.calls[0][0]).toBe(cropped);
  });

  it('«Оригінал» памʼятається на пристрої і доходить до стору', async () => {
    const sendAttachments = vi.fn().mockResolvedValue([]);
    useMessengerStore.setState({ sendAttachments: sendAttachments as never });
    render(<MessageComposer {...props} />);
    pick([photo('a.jpg')]);

    const group = screen.getByTestId('attach-quality');
    expect(within(group).getByRole('button', { name: 'Стиснуте' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(group).getByRole('button', { name: 'Оригінал' }));
    expect(localStorage.getItem(PHOTO_QUALITY_KEY)).toBe('original');
    fireEvent.click(screen.getByTestId('attach-batch-send'));
    await waitFor(() => expect(sendAttachments).toHaveBeenCalledTimes(1));
    expect(sendAttachments.mock.calls[0][1]).toMatchObject({ original: true });

    cleanup();
    render(<MessageComposer {...props} />);
    pick([photo('b.jpg')]);
    expect(within(screen.getByTestId('attach-quality')).getByRole('button', { name: 'Оригінал' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('оригінал не стискається: байти ті самі, розміри справжні', async () => {
    const encode = vi.fn(async (_s: unknown, w: number, _h: number, type: string) => new Blob([new Uint8Array(w > 1000 ? 10 : 2)], { type }));
    const deps: ImagePrepDeps = { decode: async () => ({ width: 4000, height: 3000, source: {} }), encode };
    const big = new File([new Uint8Array(5000)], 'cam.jpg', { type: 'image/jpeg' });

    const squeezed = await prepareImage(big, deps);
    expect(squeezed.file).not.toBe(big);
    expect(Math.max(squeezed.width!, squeezed.height!)).toBe(MAX_EDGE);

    encode.mockClear();
    const kept = await prepareImage(big, deps, { original: true });
    expect(kept.file).toBe(big);
    expect([kept.width, kept.height]).toEqual([4000, 3000]);
    expect(encode.mock.calls.every(([, w, h]) => Math.max(w, h) < MAX_EDGE)).toBe(true);
  });

  it('стор передає «оригінал» кожному фото пачки', async () => {
    const one = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ sendAttachment: one as never });
    await useMessengerStore.getState().sendAttachments([photo('a.jpg'), photo('b.jpg')], { batchId: 'b1', original: true });
    expect(one.mock.calls.map((c) => c[4]?.original)).toEqual([true, true]);
  });

  it('альбом: batch_id переживає запис на вузол і читання в адресата', async () => {
    const { append } = realWire();
    await useMessengerStore.getState().sendAttachments([photo('a.jpg'), photo('b.jpg')], { batchId: 'batch_p04' });
    const album = append.mock.calls.filter(([, req]) => String(req.body).includes('batch_p04'));
    expect(album).toHaveLength(2);
    const back = album.map(([, req], i) => messageFromNode({
      id: `r${i}`, conversation_id: 'c_p04', client_id: `r${i}`, seq: i + 1, author_id: 'peer_m', author_name: 'Марта',
      kind: req.kind, body: req.body, ciphertext: null, transport: 'peer', sent_at: '2026-10-29T12:00:00Z',
      edited_at: null, deleted_at: null, delivery_state: 'delivered',
    } as NodeMessage, 'me', 'peer_m'));
    expect(back.map((m) => m.mediaBatch)).toEqual([
      { id: 'batch_p04', index: 0, total: 2 },
      { id: 'batch_p04', index: 1, total: 2 },
    ]);
  });
});

describe('P04 рамка й поворот — чиста арифметика', () => {
  it('поворот на чверть міняє сторони, рамка ріже в пікселях повернутого знімка', () => {
    const p = cropPlan(4000, 3000, 1, { x: 0.5, y: 0, w: 0.5, h: 0.25 });
    expect([p.outW, p.outH, p.sx, p.sy]).toEqual([1500, 1000, 1500, 0]);
    expect([p.tx, p.ty, p.angle]).toEqual([3000, 0, Math.PI / 2]);
  });

  it('кут тягнеться, протилежний стоїть; рамка не виходить за знімок і не меншає за межу', () => {
    expect(dragFrame(FULL_FRAME, 'se', -0.4, -0.3)).toEqual({ x: 0, y: 0, w: 0.6, h: expect.closeTo(0.7, 5) });
    const tiny = dragFrame(FULL_FRAME, 'nw', 5, 5);
    expect(tiny.w).toBeCloseTo(0.1, 5);
    expect(tiny.x + tiny.w).toBeCloseTo(1, 5);
    const moved = dragFrame({ x: 0.2, y: 0.2, w: 0.5, h: 0.5 }, 'move', 1, -1);
    expect(moved).toEqual({ x: 0.5, y: 0, w: 0.5, h: 0.5 });
  });
});
