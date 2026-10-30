import { Profiler } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FormSheet } from '../components/messenger/FormSheet';

const parseTranslateY = (transform: string): number => {
  const m = /translateY\(([-\d.]+)px\)/.exec(transform);
  return m ? Number(m[1]) : NaN;
};

describe('useSheetDrag K6 feel', () => {
  let now = 1000;
  let rafQueue: Array<{ id: number; cb: FrameRequestCallback }> = [];
  let nextRafId = 1;

  beforeEach(() => {
    now = 1000;
    rafQueue = [];
    nextRafId = 1;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => {
      const id = nextRafId++;
      rafQueue.push({ id, cb });
      return id;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id: number) => {
      rafQueue = rafQueue.filter((item) => item.id !== id);
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const stepRaf = (dt = 16, maxSteps = 120) => {
    let steps = 0;
    while (rafQueue.length > 0 && steps < maxSteps) {
      now += dt;
      const batch = rafQueue.splice(0, rafQueue.length);
      for (const item of batch) item.cb(now);
      steps += 1;
    }
  };

  it('causes 0 React commits over 20 touchmove events and updates dialog transform directly', () => {
    const onClose = vi.fn();
    let commits = 0;
    render(
      <Profiler
        id="sheet"
        onRender={() => {
          commits += 1;
        }}
      >
        <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
          <div>Body</div>
        </FormSheet>
      </Profiler>,
    );

    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;
    commits = 0;

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    for (let i = 1; i <= 20; i += 1) {
      now += 8;
      fireEvent.touchMove(handle, { touches: [{ clientY: 100 + i * 5 }] });
    }

    expect(commits).toBe(0);
    expect(parseTranslateY(dialog.style.transform)).toBe(100);
  });

  it('rubber-bands upward drag of 60 px to |translateY| <= 26', () => {
    const onClose = vi.fn();
    render(
      <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
        <div>Body</div>
      </FormSheet>,
    );
    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;

    fireEvent.touchStart(handle, { touches: [{ clientY: 200 }] });
    now += 16;
    fireEvent.touchMove(handle, { touches: [{ clientY: 140 }] });

    const y = parseTranslateY(dialog.style.transform);
    expect(y).toBeLessThan(0);
    expect(Math.abs(y)).toBeLessThanOrEqual(26);
  });

  it('dismisses on slow-then-fast flick using 80 ms window velocity and calls onClose only after spring settles', () => {
    const onClose = vi.fn();
    render(
      <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
        <div>Body</div>
      </FormSheet>,
    );
    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    now += 500;
    fireEvent.touchMove(handle, { touches: [{ clientY: 130 }] });
    now += 40;
    fireEvent.touchMove(handle, { touches: [{ clientY: 190 }] });
    fireEvent.touchEnd(handle);

    expect(onClose).not.toHaveBeenCalled();
    stepRaf(16, 150);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('uses 150 ms linear transition and calls onClose after 150 ms when prefers-reduced-motion is enabled', () => {
    vi.useFakeTimers();
    const origMatchMedia = window.matchMedia;
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: query === '(prefers-reduced-motion: reduce)',
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    });

    try {
      const onClose = vi.fn();
      render(
        <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
          <div>Body</div>
        </FormSheet>,
      );
      const dialog = screen.getByRole('dialog') as HTMLElement;
      const handle = dialog.firstElementChild as HTMLElement;

      fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
      now += 16;
      fireEvent.touchMove(handle, { touches: [{ clientY: 240 }] });
      fireEvent.touchEnd(handle);

      expect(dialog.style.transition).toBe('transform 150ms linear');
      expect(onClose).not.toHaveBeenCalled();
      vi.advanceTimersByTime(149);
      expect(onClose).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        writable: true,
        value: origMatchMedia,
      });
      vi.useRealTimers();
    }
  });

  it('switches the entrance animation off on sheet and scrim so the inline transform is not overridden', () => {
    render(
      <FormSheet open onClose={vi.fn()} title="Sheet" testId="sheet-root">
        <div>Body</div>
      </FormSheet>,
    );
    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;
    const scrim = dialog.previousElementSibling as HTMLElement;

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    now += 600;
    fireEvent.touchMove(handle, { touches: [{ clientY: 130 }] });

    expect(dialog.style.animation).toBe('none');
    expect(scrim.style.animation).toBe('none');
    expect(Number(scrim.style.opacity)).toBeLessThan(1);

    fireEvent.touchEnd(handle);
    stepRaf(16, 150);
    expect(dialog.style.animation).toBe('none');
    expect(dialog.style.transform).toBe('');
  });

  it('does not dismiss when the finger rests before lifting after a fast move', () => {
    const onClose = vi.fn();
    render(
      <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
        <div>Body</div>
      </FormSheet>,
    );
    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    now += 40;
    fireEvent.touchMove(handle, { touches: [{ clientY: 150 }] });
    now += 400;
    fireEvent.touchEnd(handle);
    stepRaf(16, 150);

    expect(onClose).not.toHaveBeenCalled();
    expect(dialog.style.transform).toBe('');
  });

  it('restores the sheet when the touch is cancelled and stops its spring on unmount', () => {
    const onClose = vi.fn();
    const { unmount } = render(
      <FormSheet open onClose={onClose} title="Sheet" testId="sheet-root">
        <div>Body</div>
      </FormSheet>,
    );
    const dialog = screen.getByRole('dialog') as HTMLElement;
    const handle = dialog.firstElementChild as HTMLElement;

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    now += 16;
    fireEvent.touchMove(handle, { touches: [{ clientY: 140 }] });
    now += 400;
    fireEvent.touchCancel(handle);
    stepRaf(16, 150);
    expect(dialog.style.transform).toBe('');

    fireEvent.touchStart(handle, { touches: [{ clientY: 100 }] });
    now += 10;
    fireEvent.touchMove(handle, { touches: [{ clientY: 400 }] });
    fireEvent.touchEnd(handle);
    unmount();
    stepRaf(16, 150);
    expect(onClose).not.toHaveBeenCalled();
  });
});
