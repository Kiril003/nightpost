/**
 * Вкладення в стрічці: розшифрування лише поблизу видимого, місце під фото —
 * з пропорцій листа, своє вкладення в дорозі — передогляд і частка.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../services/messengerMedia', async (orig) => ({
  ...(await orig<typeof import('../services/messengerMedia')>()),
  loadMedia: vi.fn(async () => 'blob:plain'),
  cachedMediaUrl: vi.fn(() => undefined),
}));

import { loadMedia } from '../services/messengerMedia';
import { SecureMediaBubble } from '../components/messenger/SecureMediaBubble';
import { MediaBatchGrid } from '../components/messenger/MediaBatchGrid';
import { VideoNoteBubble } from '../components/messenger/VideoNoteBubble';
import type { Message, SecureMedia } from '../types/messenger';

const media = (over: Partial<SecureMedia> = {}): SecureMedia => ({
  name: 'a.jpg', size: 1000, mime: 'image/jpeg', sha256: 's', blobId: 'b1', keyHex: 'k', nonceHex: 'n', ...over,
});
const msg = (over: Partial<Message> = {}): Message => ({
  id: 'm1', senderId: 'peer', senderName: 'Олена', senderAvatar: '', timestamp: '', type: 'image', media: media(), ...over,
});

let ios: Array<{ cb: IntersectionObserverCallback; els: Element[] }> = [];
class FakeIO {
  r: { cb: IntersectionObserverCallback; els: Element[] };
  constructor(cb: IntersectionObserverCallback) { this.r = { cb, els: [] }; ios.push(this.r); }
  observe(el: Element) { this.r.els.push(el); }
  unobserve(el: Element) { this.r.els = this.r.els.filter((e) => e !== el); }
  disconnect() { this.r.els = []; }
}
const reveal = () =>
  act(() => {
    for (const r of ios) r.cb(r.els.map((target) => ({ target, isIntersecting: true })) as never, {} as never);
  });

describe('відкладене розшифрування', () => {
  beforeEach(() => {
    ios = [];
    vi.mocked(loadMedia).mockClear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('фото далеко від видимого — не качається; наблизилось — розшифровується', async () => {
    vi.stubGlobal('IntersectionObserver', FakeIO);
    render(<SecureMediaBubble msg={msg()} isSelf={false} />);
    await act(async () => undefined);
    expect(loadMedia).not.toHaveBeenCalled();
    await reveal();
    await act(async () => undefined);
    expect(loadMedia).toHaveBeenCalledTimes(1);
    expect(await screen.findByTestId('media-image')).toBeTruthy();
  });

  it('без IntersectionObserver — одразу, як і раніше', async () => {
    render(<SecureMediaBubble msg={msg()} isSelf={false} />);
    await act(async () => undefined);
    expect(loadMedia).toHaveBeenCalledTimes(1);
  });

  it('клітинка пачки теж чекає наближення', async () => {
    vi.stubGlobal('IntersectionObserver', FakeIO);
    render(<MediaBatchGrid items={[msg({ id: 'x1' }), msg({ id: 'x2', media: media({ blobId: 'b2' }) })]} isSelf={false} />);
    await act(async () => undefined);
    expect(loadMedia).not.toHaveBeenCalled();
    await reveal();
    await act(async () => undefined);
    expect(loadMedia).toHaveBeenCalledTimes(2);
  });

  it('кружок теж чекає наближення', async () => {
    vi.stubGlobal('IntersectionObserver', FakeIO);
    render(<VideoNoteBubble msg={msg({ type: 'video-note', videoNoteData: { duration: 3, media: media({ mime: 'video/webm' }) } })} />);
    await act(async () => undefined);
    expect(loadMedia).not.toHaveBeenCalled();
    await reveal();
    await act(async () => undefined);
    expect(loadMedia).toHaveBeenCalledTimes(1);
  });
});

describe('місце під фото', () => {
  beforeEach(() => vi.stubGlobal('IntersectionObserver', FakeIO));
  afterEach(() => vi.unstubAllGlobals());

  it('з width/height листа — коробка остаточних пропорцій ще до байтів', () => {
    render(<SecureMediaBubble msg={msg({ media: media({ width: 1920, height: 2560 }) })} isSelf={false} />);
    const box = screen.getByTestId('media-reading');
    expect(box.style.width).toBe('195px');
    expect(box.getAttribute('style')).toContain('aspect-ratio: 1920 / 2560');
  });

  it('без розмірів — 4:3', () => {
    render(<SecureMediaBubble msg={msg()} isSelf={false} />);
    expect(screen.getByTestId('media-reading').getAttribute('style')).toContain('aspect-ratio: 4 / 3');
  });
});

describe('своє вкладення в дорозі', () => {
  beforeEach(() => vi.mocked(loadMedia).mockClear());

  it('передогляд і частка, вузол не питаємо', async () => {
    render(
      <SecureMediaBubble
        msg={msg({ isSelf: true, status: 'sending', uploadProgress: 0.4, localPreviewUrl: 'blob:local', media: media({ blobId: '' }) })}
        isSelf
      />,
    );
    await act(async () => undefined);
    expect(screen.getByTestId('media-uploading')).toBeTruthy();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('40');
    expect((screen.getByRole('img') as HTMLImageElement).src).toBe('blob:local');
    expect(loadMedia).not.toHaveBeenCalled();
  });

  it('клітинка пачки в дорозі — те саме', async () => {
    render(<MediaBatchGrid items={[msg({ isSelf: true, uploadProgress: 0.7, localPreviewUrl: 'blob:l', media: media({ blobId: '' }) })]} isSelf />);
    await act(async () => undefined);
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('70');
    expect(loadMedia).not.toHaveBeenCalled();
  });

  it('кружок у дорозі грає з місцевого файла', async () => {
    const { container } = render(
      <VideoNoteBubble msg={msg({ type: 'video-note', isSelf: true, uploadProgress: 0, localPreviewUrl: 'blob:vn', videoNoteData: { duration: 2, videoUrl: 'blob:vn', media: media({ blobId: '' }) } })} />,
    );
    await act(async () => undefined);
    expect(container.querySelector('video')?.getAttribute('src')).toBe('blob:vn');
    expect(loadMedia).not.toHaveBeenCalled();
  });
});

describe('кружок на A52: чесний стан і кнопка звуку цілою', () => {
  beforeEach(() => vi.stubGlobal('IntersectionObserver', FakeIO));
  afterEach(() => vi.unstubAllGlobals());

  const note = () =>
    msg({ type: 'video-note', isSelf: true, uploadProgress: 0, localPreviewUrl: 'blob:vn', videoNoteData: { duration: 2, videoUrl: 'blob:vn', media: media({ blobId: '' }) } });

  it('запис без кадрів каже «Без зображення», а не мовчить порожнім колом', async () => {
    const { container } = render(<VideoNoteBubble msg={note()} />);
    await act(async () => undefined);
    expect(screen.queryByTestId('video-note-no-picture')).toBeNull();
    const video = container.querySelector('video') as HTMLVideoElement;
    Object.defineProperty(video, 'videoWidth', { value: 0 });
    Object.defineProperty(video, 'videoHeight', { value: 0 });
    await act(async () => { video.dispatchEvent(new Event('loadedmetadata')); });
    expect(screen.getByTestId('video-note-no-picture').textContent).toContain('Без зображення');
  });

  it('запис із кадрами — без цієї плашки', async () => {
    const { container } = render(<VideoNoteBubble msg={note()} />);
    await act(async () => undefined);
    const video = container.querySelector('video') as HTMLVideoElement;
    Object.defineProperty(video, 'videoWidth', { value: 480 });
    Object.defineProperty(video, 'videoHeight', { value: 480 });
    await act(async () => { video.dispatchEvent(new Event('loadedmetadata')); });
    expect(screen.queryByTestId('video-note-no-picture')).toBeNull();
  });

  it('кнопка звуку лежить поза круглою маскою — її не зрізає', async () => {
    render(<VideoNoteBubble msg={note()} />);
    await act(async () => undefined);
    let el: HTMLElement | null = screen.getByTestId('video-note-sound').parentElement;
    while (el && el.dataset.testid !== 'video-note-bubble') {
      expect(el.className).not.toMatch(/overflow-hidden/);
      el = el.parentElement;
    }
  });
});

describe('своє голосове в дорозі', () => {
  it('не питає вузол про порожній blobId і не каже «ще завантажується»', async () => {
    const { UnifiedVoiceBubble } = await import('../components/messenger/UnifiedVoiceBubble');
    vi.mocked(loadMedia).mockClear();
    render(
      <UnifiedVoiceBubble
        msg={msg({ type: 'voice', isSelf: true, uploadProgress: 0, localPreviewUrl: 'blob:voice', voiceData: { duration: 3, waveform: [], media: media({ blobId: '', mime: 'audio/webm' }) } })}
      />,
    );
    await act(async () => undefined);
    expect(loadMedia).not.toHaveBeenCalled();
    expect(screen.queryByText(/ще завантажується/)).toBeNull();
  });
});
