/**
 * Кружок і його запис — 03.10, мовою v1.2.
 *
 * Над відео — темна пігулка `.ph-on-photo` і тло `--ph-scrim`, а не
 * `bg-black/*` з `text-white` (уночі вони ще трималися, а вдень значок паузи
 * `text-ink-2` ставав темним на чорному). Запис: помилка камери — людським
 * реченням за `err.name`, ніколи сирим `err.message` англійською («Not
 * supported» на A52), і поки камери нема — лише «Скасувати», без таймера,
 * замка й «Надіслати», які нічого б не зробили.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('../services/messengerMedia', async (orig) => ({
  ...(await orig<typeof import('../services/messengerMedia')>()),
  loadMedia: vi.fn(async () => 'blob:plain'),
  cachedMediaUrl: vi.fn(() => undefined),
}));

import { VideoNoteBubble } from '../components/messenger/VideoNoteBubble';
import { VideoNoteRecorder } from '../components/messenger/VideoNoteRecorder';
import type { Message, SecureMedia } from '../types/messenger';

const media = (over: Partial<SecureMedia> = {}): SecureMedia => ({
  name: 'n.webm', size: 1000, mime: 'video/webm', sha256: 's', blobId: '', keyHex: 'k', nonceHex: 'n', ...over,
});
const note = (): Message =>
  ({
    id: 'v1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'video-note', isSelf: true,
    uploadProgress: 0, localPreviewUrl: 'blob:vn', videoNoteData: { duration: 2, videoUrl: 'blob:vn', media: media() },
  }) as Message;

afterEach(() => cleanup());

describe('кружок у стрічці', () => {
  it('пауза й кнопка звуку — на .ph-on-photo / scrim, без bg-black і text-white', async () => {
    const { container } = render(<VideoNoteBubble msg={note()} />);
    await act(async () => undefined);
    expect(container.querySelector('video')).not.toBeNull();
    expect(container.innerHTML).not.toMatch(/bg-black|text-white/);
    expect(screen.getByTestId('video-note-sound').className).toContain('ph-on-photo');
    expect(container.querySelector('.bg-scrim')).not.toBeNull();
  });
});

describe('запис кружка: помилка камери', () => {
  const realMedia = navigator.mediaDevices;
  const failWith = (err: unknown) =>
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(() => Promise.reject(err)) },
    });

  beforeEach(() => {
    vi.stubGlobal('MediaRecorder', class { static isTypeSupported() { return true; } });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: realMedia });
  });

  const named = (name: string, message = 'whatever') => Object.assign(new Error(message), { name });

  it.each([
    ['Error(Not supported)', new Error('Not supported'), 'Камера недоступна на цьому пристрої'],
    ['NotSupportedError', named('NotSupportedError', 'Not supported'), 'Камера недоступна на цьому пристрої'],
    ['NotAllowedError', named('NotAllowedError', 'Permission denied'), 'Дозвольте камеру в налаштуваннях'],
    ['NotFoundError', named('NotFoundError', 'Requested device not found'), 'Камеру не знайдено'],
    ['OverconstrainedError', named('OverconstrainedError', 'Constraints could be not satisfied'), 'Камеру не знайдено'],
  ])('%s → людське речення, без сирого message', async (_n, err, said) => {
    failWith(err);
    render(<VideoNoteRecorder onSend={() => {}} onCancel={() => {}} />);
    await act(async () => undefined);
    await act(async () => undefined);
    expect(await screen.findByText(said)).toBeTruthy();
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/Not supported|Permission denied|not found|Constraints/i);
  });

  it('поки камери нема — лише «Скасувати»: без таймера, замка, «Надіслати» і рожевого', async () => {
    failWith(new Error('Not supported'));
    render(<VideoNoteRecorder onSend={() => {}} onCancel={() => {}} />);
    await act(async () => undefined);
    await screen.findByText('Камера недоступна на цьому пристрої');
    expect(screen.getByRole('button', { name: /^Скасувати/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Надіслати відео-нотатку' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Заблокувати запис|Розблокувати/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Перемкнути камеру' })).toBeNull();
    expect(document.body.textContent).not.toMatch(/\/ 1:00/);
    const dialog = screen.getByRole('dialog', { name: 'Запис відео-нотатки' });
    expect(dialog.innerHTML).not.toMatch(/rose-|bg-black|text-xs/);
  });

  it('тло запису — суцільне полотно теми, а не напівпрозорий scrim: стрічка не просвічує під «Скасувати»', async () => {
    failWith(new Error('Not supported'));
    render(<VideoNoteRecorder onSend={() => {}} onCancel={() => {}} />);
    await act(async () => undefined);
    const dialog = screen.getByRole('dialog', { name: 'Запис відео-нотатки' });
    expect(dialog.style.backgroundColor).toBe('var(--ph-canvas)');
    expect(dialog.getAttribute('style') ?? '').not.toMatch(/scrim|rgba|#/);
  });
});
