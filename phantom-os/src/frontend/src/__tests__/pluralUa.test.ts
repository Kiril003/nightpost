import { describe, it, expect } from 'vitest';
import { pluralUa } from '../utils/format';

describe('українська множина — одна функція на весь фронт', () => {
  const форма = (n: number) => `${n} ${pluralUa(n, 'розмова', 'розмови', 'розмов')}`;
  it('рахує за n%10 і n%100, а не за n<5', () => {
    expect(форма(0)).toBe('0 розмов');
    expect(форма(1)).toBe('1 розмова');
    expect(форма(2)).toBe('2 розмови');
    expect(форма(5)).toBe('5 розмов');
    expect(форма(11)).toBe('11 розмов');
    expect(форма(21)).toBe('21 розмова');
    expect(форма(22)).toBe('22 розмови');
    expect(форма(25)).toBe('25 розмов');
    expect(форма(101)).toBe('101 розмова');
    expect(форма(112)).toBe('112 розмов');
  });
});
