/**
 * BACK у розмові на телефоні мусить повертати до списку, а не викидати з
 * месенджера. Хост іде на `webView.goBack()` лише коли в історії є запис —
 * тож перевіряємо саме записи: відкритий шар кладе один, popstate знімає
 * верхній, закритий дотиком забирає свій сам.
 *
 * Червоніє мутацією: заберіть pushState з хука — перший тест падає.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { usePhoneBackStack, type BackLayer } from '../hooks/usePhoneBackStack';

afterEach(() => vi.restoreAllMocks());

const pop = () => window.dispatchEvent(new PopStateEvent('popstate'));

describe('usePhoneBackStack', () => {
  it('відкрита розмова кладе один запис в історію, BACK її закриває', () => {
    const push = vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    const close = vi.fn();
    const layers = (open: boolean): BackLayer[] => [{ open, close }];
    const { rerender } = renderHook(({ open }) => usePhoneBackStack(layers(open), true), {
      initialProps: { open: false },
    });
    expect(push).not.toHaveBeenCalled();
    rerender({ open: true });
    expect(push).toHaveBeenCalledTimes(1);
    pop();
    expect(close).toHaveBeenCalledTimes(1);
    pop();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('вікно поверх розмови закривається першим', () => {
    vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    const closeChat = vi.fn();
    const closeModal = vi.fn();
    renderHook(() =>
      usePhoneBackStack(
        [
          { open: true, close: closeChat },
          { open: true, close: closeModal },
        ],
        true,
      ),
    );
    pop();
    expect(closeModal).toHaveBeenCalledTimes(1);
    expect(closeChat).not.toHaveBeenCalled();
  });

  it('шар, закритий дотиком, забирає свій запис і ковтає свій popstate', () => {
    vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
    const close = vi.fn();
    const { rerender } = renderHook(({ open }) => usePhoneBackStack([{ open, close }], true), {
      initialProps: { open: true },
    });
    rerender({ open: false });
    expect(go).toHaveBeenCalledWith(-1);
    pop();
    expect(close).not.toHaveBeenCalled();
  });

  it('на ПК не чіпає історію взагалі', () => {
    const push = vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    renderHook(() => usePhoneBackStack([{ open: true, close: () => {} }], false));
    expect(push).not.toHaveBeenCalled();
  });
});

describe('BACK, яким кермує телефон (__phantomBack)', () => {
  const w = window as unknown as { __phantomBack?: () => boolean };

  // Шар, відкритий без жесту (тап по сповіщенню), Chromium для «Назад»
  // пропускає — хост мусить мати спосіб закрити його не через історію.
  it('закриває верхній шар і каже true; без шарів — false', () => {
    vi.spyOn(window.history, 'pushState').mockImplementation(() => {});
    const closeChat = vi.fn();
    const closeModal = vi.fn();
    const { rerender, unmount } = renderHook(({ chat, modal }) =>
      usePhoneBackStack([{ open: chat, close: closeChat }, { open: modal, close: closeModal }], true), {
      initialProps: { chat: true, modal: true },
    });
    expect(w.__phantomBack?.()).toBe(true);
    expect(closeModal).toHaveBeenCalledTimes(1);
    expect(closeChat).not.toHaveBeenCalled();
    rerender({ chat: true, modal: false });
    expect(w.__phantomBack?.()).toBe(true);
    expect(closeChat).toHaveBeenCalledTimes(1);
    rerender({ chat: false, modal: false });
    expect(w.__phantomBack?.()).toBe(false);
    unmount();
    expect(w.__phantomBack).toBeUndefined();
  });

  it('поза телефоном (enabled=false) мосту нічого не віддає', () => {
    renderHook(() => usePhoneBackStack([{ open: true, close: vi.fn() }], false));
    expect(w.__phantomBack).toBeUndefined();
  });
});
