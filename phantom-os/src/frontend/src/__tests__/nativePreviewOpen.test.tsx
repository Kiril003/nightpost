import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Message, SecureMedia } from '../types/messenger';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';
import { NATIVE_CHUNK_RAW } from '../services/preview/nativePreview';

vi.mock('../services/messengerMedia', async () => {
  const actual = await vi.importActual<typeof import('../services/messengerMedia')>('../services/messengerMedia');
  return { ...actual, cachedMediaUrl: vi.fn(() => 'blob:fixture') };
});

const saveMedia = vi.fn(async () => true);
vi.mock('../services/saveMedia', () => ({ saveMedia: (...a: unknown[]) => saveMedia(...(a as [])) }));

type Bridge = {
  previewBegin: ReturnType<typeof vi.fn>;
  previewChunk: ReturnType<typeof vi.fn>;
  previewOpen: ReturnType<typeof vi.fn>;
};

const fileMessage = (name: string, mime: string, size: number, type: 'file' | 'image' = 'file'): Message =>
  ({
    id: `msg-${name}`,
    senderId: 'self',
    text: '',
    timestamp: '12:00',
    status: 'sent',
    type,
    media: { blobId: 'b1', key: 'k', nonce: 'n', sha256: 'h', mime, size, name } as unknown as SecureMedia,
  }) as Message;

const bytesOf = (size: number) => {
  const out = new Uint8Array(size);
  for (let i = 0; i < size; i += 1) out[i] = (i * 31 + 7) & 0xff;
  return out;
};

const decode = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

let bridge: Bridge;

beforeEach(() => {
  saveMedia.mockClear();
  bridge = {
    previewBegin: vi.fn(() => 'pv1'),
    previewChunk: vi.fn(() => true),
    previewOpen: vi.fn(() => true),
  };
  (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge = bridge;
});

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge;
  vi.unstubAllGlobals();
});

describe('PDF and HEIC open in the phone viewer', () => {
  it('streams a PDF to the native viewer in ordered pieces of at most 1 MiB and opens it', async () => {
    const size = NATIVE_CHUNK_RAW * 2 + 1234;
    const bytes = bytesOf(size);
    vi.stubGlobal('fetch', vi.fn(async () => ({ blob: async () => new Blob([bytes]) })));
    render(<SecureMediaBubble msg={fileMessage('plan.pdf', 'application/pdf', size)} isSelf={true} />);

    fireEvent.click(screen.getByTestId('media-file-open'));

    await waitFor(() => expect(bridge.previewOpen).toHaveBeenCalledWith('pv1'));
    expect(bridge.previewBegin).toHaveBeenCalledWith('pdf', 'plan.pdf', 'application/pdf', size);
    const calls = bridge.previewChunk.mock.calls as [string, number, string][];
    expect(calls.map((c) => c[1])).toEqual([0, 1, 2]);
    expect(calls.every((c) => c[0] === 'pv1' && c[2].length <= 1024 * 1024)).toBe(true);
    const joined = calls.flatMap((c) => [...decode(c[2])]);
    expect(joined.length).toBe(size);
    expect(joined.every((b, i) => b === bytes[i])).toBe(true);
    expect(saveMedia).not.toHaveBeenCalled();
    expect(screen.queryByTestId('file-preview-sheet')).toBeNull();
  });

  it('sends a HEIC photo as kind heic', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ blob: async () => new Blob([bytesOf(2000)]) })));
    render(<SecureMediaBubble msg={fileMessage('IMG_1.HEIC', 'image/heic', 2000)} isSelf={true} />);

    fireEvent.click(screen.getByTestId('media-file-open'));

    await waitFor(() => expect(bridge.previewOpen).toHaveBeenCalled());
    expect(bridge.previewBegin.mock.calls[0][0]).toBe('heic');
  });

  it('falls back to Save when the phone refuses a piece, and never opens', async () => {
    bridge.previewChunk.mockReturnValueOnce(false);
    vi.stubGlobal('fetch', vi.fn(async () => ({ blob: async () => new Blob([bytesOf(5000)]) })));
    render(<SecureMediaBubble msg={fileMessage('plan.pdf', 'application/pdf', 5000)} isSelf={true} />);

    fireEvent.click(screen.getByTestId('media-file-open'));

    await waitFor(() => expect(saveMedia).toHaveBeenCalledWith('blob:fixture', 'plan.pdf', 'application/pdf'));
    expect(bridge.previewOpen).not.toHaveBeenCalled();
  });

  it('falls back to Save when the phone declines to begin', async () => {
    bridge.previewBegin.mockReturnValueOnce('');
    vi.stubGlobal('fetch', vi.fn(async () => ({ blob: async () => new Blob([bytesOf(5000)]) })));
    render(<SecureMediaBubble msg={fileMessage('plan.pdf', 'application/pdf', 5000)} isSelf={true} />);

    fireEvent.click(screen.getByTestId('media-file-open'));

    await waitFor(() => expect(saveMedia).toHaveBeenCalled());
    expect(bridge.previewChunk).not.toHaveBeenCalled();
  });

  it('shows no Open for a PDF without the phone viewer or above its cap', () => {
    delete (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge;
    const { unmount } = render(<SecureMediaBubble msg={fileMessage('plan.pdf', 'application/pdf', 5000)} isSelf={true} />);
    expect(screen.queryByTestId('media-file-open')).toBeNull();
    unmount();

    (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge = bridge;
    render(<SecureMediaBubble msg={fileMessage('huge.pdf', 'application/pdf', 201 * 1024 * 1024)} isSelf={true} />);
    expect(screen.queryByTestId('media-file-open')).toBeNull();
    expect(screen.getByLabelText('Зберегти')).toBeTruthy();
  });

  it('offers the phone viewer for a gallery HEIC that arrives as a photo, busy while it streams', async () => {
    let finish: () => void = () => {};
    bridge.previewOpen.mockImplementation(() => true);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise((resolve) => { finish = () => resolve({ blob: async () => new Blob([bytesOf(3000)]) }); })),
    );
    render(<SecureMediaBubble msg={fileMessage('IMG_2.HEIC', 'image/heic', 3000, 'image')} isSelf={true} />);

    const open = screen.getByTestId('media-file-open');
    fireEvent.click(open);
    expect(open.getAttribute('aria-busy')).toBe('true');
    expect((open as HTMLButtonElement).disabled).toBe(true);
    finish();

    await waitFor(() => expect(bridge.previewOpen).toHaveBeenCalledWith('pv1'));
    expect(bridge.previewBegin.mock.calls[0][0]).toBe('heic');
    await waitFor(() => expect(open.getAttribute('aria-busy')).toBe('false'));
  });
});
