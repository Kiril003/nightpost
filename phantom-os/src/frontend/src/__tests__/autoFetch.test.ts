import { afterEach, describe, expect, it } from 'vitest';
import { readNet, shouldFetch } from '../services/autoFetch';

const setConnection = (conn: unknown) => {
  Object.defineProperty(navigator, 'connection', {
    value: conn,
    configurable: true,
    writable: true,
  });
};

describe('autoFetch', () => {
  afterEach(() => {
    setConnection(undefined);
  });

  it('blocks social photo on mobile', () => {
    expect(
      shouldFetch({
        kind: 'photo',
        sizeBytes: 2_400_000,
        rings: ['social'],
        net: 'mobile',
        saveData: false,
      }),
    ).toBe(false);
  });

  it('allows close voice on mobile', () => {
    expect(
      shouldFetch({
        kind: 'voice',
        sizeBytes: 48_000,
        rings: ['close'],
        net: 'mobile',
        saveData: false,
      }),
    ).toBe(true);
  });

  it('allows social photo on wifi when saveData is off', () => {
    expect(
      shouldFetch({
        kind: 'photo',
        sizeBytes: 2_400_000,
        rings: ['social'],
        net: 'wifi',
        saveData: false,
      }),
    ).toBe(true);
  });

  it('blocks close photo when saveData is enabled', () => {
    expect(
      shouldFetch({
        kind: 'photo',
        sizeBytes: 500_000,
        rings: ['close'],
        net: 'wifi',
        saveData: true,
      }),
    ).toBe(false);
  });

  it('allows close voice when saveData is enabled', () => {
    expect(
      shouldFetch({
        kind: 'voice',
        sizeBytes: 48_000,
        rings: ['close'],
        net: 'mobile',
        saveData: true,
      }),
    ).toBe(true);
  });

  it('blocks close file on mobile', () => {
    expect(
      shouldFetch({
        kind: 'file',
        sizeBytes: 120_000,
        rings: ['close'],
        net: 'mobile',
        saveData: false,
      }),
    ).toBe(false);
  });

  it('treats empty rings on mobile as untrusted', () => {
    expect(
      shouldFetch({
        kind: 'photo',
        sizeBytes: 120_000,
        rings: [],
        net: 'mobile',
        saveData: false,
      }),
    ).toBe(false);
  });

  it('returns mobile and saveData false when navigator.connection is absent', () => {
    setConnection(undefined);
    expect(readNet()).toEqual({ net: 'mobile', saveData: false });
  });
});
