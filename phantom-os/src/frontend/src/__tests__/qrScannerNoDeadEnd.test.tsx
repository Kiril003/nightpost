/**
 * FR-2 (ніч 09.10, A52): «Додати людину › Сканувати › Не зараз» — у коробці
 * «Немає дозволу на камеру…» і жодної кнопки, глухий кут. Тепер є «Дозволити
 * камеру», і вона справді питає камеру знову.
 *
 * UX-аудит №13: доки камера стартує, WebView малював сіру заглушку відео з
 * іконкою «play». Тепер — чорна рамка й порожній постер.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { BLANK_POSTER, QrScanner } from '../components/messenger/QrScanner';

const denyThenGrant = () => {
  let calls = 0;
  const getUserMedia = vi.fn(async () => {
    calls += 1;
    if (calls === 1) throw new DOMException('ні', 'NotAllowedError');
    return new Promise<MediaStream>(() => undefined); // друга спроба — камера відкривається
  });
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia, enumerateDevices: vi.fn(async () => []) },
  });
  return getUserMedia;
};

describe('сканер QR без глухого кута', () => {
  afterEach(() => vi.restoreAllMocks());

  it('відмова — є кнопка «Дозволити камеру», і вона питає камеру знову', async () => {
    const getUserMedia = denyThenGrant();
    render(<QrScanner onFound={() => {}} />);
    const again = await screen.findByTestId('qr-scanner-retry');
    expect(again.textContent).toContain('Дозволити камеру');
    const before = getUserMedia.mock.calls.length;
    fireEvent.click(again);
    await waitFor(() => expect(getUserMedia.mock.calls.length).toBeGreaterThan(before));
    expect(screen.queryByTestId('qr-scanner-retry')).toBeNull();
    expect(screen.getByTestId('qr-scanner')).toBeTruthy();
  });

  it('поки камера стартує — чорна рамка й порожній постер, не сіра заглушка WebView', () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(() => new Promise(() => undefined)), enumerateDevices: vi.fn(async () => []) },
    });
    const { container } = render(<QrScanner onFound={() => {}} />);
    const video = container.querySelector('video')!;
    expect(video.getAttribute('poster')).toBe(BLANK_POSTER);
    expect(screen.getByTestId('qr-scanner-frame').className).toContain('bg-black');
  });
});
