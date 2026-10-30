import { describe, expect, it } from 'vitest';
import { mediaBox } from '../mediaBox';

describe('mediaBox', () => {
  it('ландшафт вписується по ширині, портрет — по висоті, пропорції з листа', () => {
    expect(mediaBox(2560, 1920)).toEqual({ width: 320, aspectRatio: '2560 / 1920', known: true });
    expect(mediaBox(1920, 2560)).toEqual({ width: 195, aspectRatio: '1920 / 2560', known: true });
  });
  it('малих не збільшує, але й у щілину не стискає', () => {
    expect(mediaBox(64, 64).width).toBe(64);
    expect(mediaBox(10, 4000).width).toBe(48);
  });
  it('розмірів немає — 4:3 на всю дозволену висоту', () => {
    expect(mediaBox()).toEqual({ width: 320, aspectRatio: '4 / 3', known: false });
    expect(mediaBox(0, 100).known).toBe(false);
  });
});
