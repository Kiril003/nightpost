import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { act, cleanup, renderHook } from '@testing-library/react';
import { messengerApi } from '../../services/messengerApi';
import { registerPhoneLayer, usePhoneLayerStore } from '../../stores/phoneLayerStore';
import { callCoversScreen } from '../../stores/callChromeStore';
import { MODAL_CLOSE_SETTLE_MS, useModalChrome } from '../useModalChrome';

let spy: MockInstance<typeof messengerApi.chromeHidden>;

beforeEach(() => {
  usePhoneLayerStore.setState({ layers: {} });
  vi.useFakeTimers();
  spy = vi.spyOn(messengerApi, 'chromeHidden').mockResolvedValue(undefined as never);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('нижня смуга ховається під модальним шаром', () => {
  it('відкритий аркуш шле true одразу, закритий — false після короткої паузи', () => {
    renderHook(() => useModalChrome(false));
    expect(spy).not.toHaveBeenCalled();
    let off = () => {};
    act(() => {
      off = registerPhoneLayer('sheet:x', true, () => {});
    });
    expect(spy).toHaveBeenLastCalledWith(true);
    act(() => off());
    expect(spy).toHaveBeenCalledTimes(1);
    act(() => {
      vi.advanceTimersByTime(MODAL_CLOSE_SETTLE_MS + 10);
    });
    expect(spy).toHaveBeenLastCalledWith(false);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('аркуш, що змінює аркуш, не блимає смугою', () => {
    renderHook(() => useModalChrome(false));
    let a = () => {};
    act(() => {
      a = registerPhoneLayer('sheet:a', true, () => {});
    });
    act(() => {
      a();
      registerPhoneLayer('sheet:b', true, () => {});
      vi.advanceTimersByTime(MODAL_CLOSE_SETTLE_MS + 10);
    });
    expect(spy.mock.calls.map((c) => c[0])).toEqual([true]);
  });

  it('пошук у списку — не модальний шар', () => {
    renderHook(() => useModalChrome(false));
    act(() => {
      registerPhoneLayer('list:search', true, () => {});
    });
    expect(spy).not.toHaveBeenCalled();
  });

  it('прапорці стору месенджера теж рахуються', () => {
    const { rerender } = renderHook(({ open }) => useModalChrome(open), { initialProps: { open: false } });
    rerender({ open: true });
    expect(spy).toHaveBeenLastCalledWith(true);
  });

  it('MessengerRoot підключає хук', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../../components/messenger/MessengerRoot.tsx'), 'utf8');
    expect(src).toContain('useModalChrome(');
  });

  it('повноекранний дзвінок ховає смугу, згорнута пігулка й тиша — ні', () => {
    expect(callCoversScreen('idle', false)).toBe(false);
    expect(callCoversScreen('calling', false)).toBe(true);
    expect(callCoversScreen('ringing', false)).toBe(true);
    expect(callCoversScreen('active', false)).toBe(true);
    expect(callCoversScreen('active', true)).toBe(false);
  });

  it('CallOverlay пише стан у стор, MessengerRoot віддає його хуку без стеку BACK', () => {
    const dir = path.resolve(__dirname, '../../components/messenger');
    const overlay = fs.readFileSync(path.join(dir, 'CallOverlay.tsx'), 'utf8');
    expect(overlay).toContain('useCallChromeStore.setState({ covers })');
    expect(overlay).not.toContain('registerPhoneLayer');
    const root = fs.readFileSync(path.join(dir, 'MessengerRoot.tsx'), 'utf8');
    expect(root).toMatch(/useModalChrome\(\s*callCovers \|\|/);
  });
});
