import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { MomentViewer, MOMENT_EXIT_MS } from '../components/messenger/MomentViewer';
import { readMoments } from '../services/moments';
import { messengerApi } from '../services/messengerApi';

const NOW = Date.now();
const list = readMoments(
  [
    {
      id: 'c',
      from_id: 'olena',
      from_name: '',
      mine: false,
      at_ms: NOW - 60_000,
      until_ms: NOW + 86_400_000,
      keep: false,
      audience: { kind: 'contacts' },
      text: 'ранок',
      seen: true,
      to_count: 0,
      media: [],
    },
  ],
  NOW,
);

const mount = (onClose = vi.fn()) => {
  render(
    <MomentViewer moments={list} startId="c" nameOf={() => 'Olena'} avatarOf={() => undefined} chatOf={() => 'olena'} onOpenChat={() => {}} onClose={onClose} />,
  );
  return { onClose, viewer: () => document.querySelector('[data-moment-viewer]') as HTMLElement };
};

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('момент відкривається й закривається рухом', () => {
  it('входить з анімацією', () => {
    const { viewer } = mount();
    expect(viewer().className).toContain('np-moment-in');
  });

  it('закриття спершу грає вихід і лише потім віддає onClose', () => {
    const { viewer, onClose } = mount();
    fireEvent.click(viewer().querySelector('[data-moment-close]')!);
    expect(onClose).not.toHaveBeenCalled();
    expect(viewer().className).toContain('np-moment-out');
    act(() => {
      vi.advanceTimersByTime(MOMENT_EXIT_MS + 20);
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Escape закриває тим самим виходом, подвійне закриття не дублює onClose', () => {
    const { viewer, onClose } = mount();
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(viewer().querySelector('[data-moment-close]')!);
    expect(viewer().className).toContain('np-moment-out');
    act(() => {
      vi.advanceTimersByTime(MOMENT_EXIT_MS + 20);
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe("відповідь на історію не заморожує її", () => {
  it("після надсилання плашка зникає сама і прогрес знову йде", async () => {
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'm1' } as never);
    const { viewer } = mount();
    const bar = () => viewer().querySelector("[data-moment-progress]") as HTMLElement;
    const input = viewer().querySelector("[data-moment-reply]") as HTMLInputElement;
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "привіт" } });
    await act(async () => {
      fireEvent.submit(input.closest("form")!);
    });
    expect(viewer().querySelector("[data-moment-toast]")).not.toBeNull();
    expect(bar().style.animationPlayState).toBe("paused");
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(viewer().querySelector("[data-moment-toast]")).toBeNull();
    expect(bar().style.animationPlayState).toBe("running");
  });
});
