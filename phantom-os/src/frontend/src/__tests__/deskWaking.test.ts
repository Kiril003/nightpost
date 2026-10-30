/**
 * Вікно ПК не чекає вузла: PHANTOM прокидається на очах, а квиток входу від
 * оболонки береться один раз, навіть якщо оболонка шле його кілька разів.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { onDoorTicket } from '../services/doorTicket';
import { useNodeWaking, wakingLine } from '../components/shell/DeskWaking';

afterEach(() => {
  delete (window as unknown as { __phantomDoorTicket?: string }).__phantomDoorTicket;
  delete (window as unknown as { __nightpostNodeMissing?: boolean }).__nightpostNodeMissing;
  vi.unstubAllGlobals();
});

describe('прокидання ПК', () => {
  it('слова чесні й за часом', () => {
    expect(wakingLine(2_000)).toBe('Прокидаюсь…');
    expect(wakingLine(30_000)).toBe('Ще прокидаюсь…');
    expect(wakingLine(95_000)).toMatch(/Не прокинувся за півтори хвилини/);
  });

  it('квиток від оболонки: той, що вже лежить, і той, що прийшов подією; двічі — ні', () => {
    const got: string[] = [];
    const w = window as unknown as { __phantomDoorTicket?: string };
    w.__phantomDoorTicket = 'раніше-за-веб';
    const stop = onDoorTicket((t) => got.push(t));
    window.dispatchEvent(new Event('phantom-door'));
    w.__phantomDoorTicket = 'новий';
    window.dispatchEvent(new Event('phantom-door'));
    window.dispatchEvent(new Event('phantom-door'));
    stop();
    expect(got).toEqual(['раніше-за-веб', 'новий']);
  });

  it('оболонка без вузла в пакеті: одразу чесне «пакет неповний», не «прокидаюсь»', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')));
    const { result, unmount } = renderHook(() => useNodeWaking());
    expect(result.current.missing).toBe(false);
    await act(async () => {
      (window as unknown as { __nightpostNodeMissing?: boolean }).__nightpostNodeMissing = true;
      window.dispatchEvent(new Event('nightpost-node-missing'));
    });
    expect(result.current.missing).toBe(true);
    expect(wakingLine(result.current.waitedMs, result.current.missing)).toBe(
      'Пакет Nightpost неповний. Перевстановіть застосунок.',
    );
    unmount();
  });

  it('прапорець, що лежав до завантаження веба, теж читається', () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')));
    (window as unknown as { __nightpostNodeMissing?: boolean }).__nightpostNodeMissing = true;
    const { result, unmount } = renderHook(() => useNodeWaking());
    expect(result.current.missing).toBe(true);
    unmount();
  });
});
