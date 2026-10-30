import React, { Profiler, useRef, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useEdgeStretch } from '../feel/useEdgeStretch';

const Box: React.FC = () => {
  const r = useRef<HTMLDivElement>(null);
  useEdgeStretch(r);
  return (
    <div ref={r} data-testid="scroller">
      <div data-testid="inner" />
    </div>
  );
};

const ListBox: React.FC = () => {
  const r = useRef<HTMLDivElement>(null);
  useEdgeStretch(r, { keepLayer: true });
  return (
    <div ref={r} data-testid="scroller">
      <div data-testid="inner" />
    </div>
  );
};

const LateBox: React.FC = () => {
  const r = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(false);
  useEdgeStretch(r);
  return (
    <>
      <button data-testid="show" onClick={() => setShown(true)} />
      {shown && (
        <div ref={r} data-testid="scroller">
          <div data-testid="inner" />
        </div>
      )}
    </>
  );
};

function stubMetrics(el: HTMLElement, clientHeight: number, scrollHeight: number, scrollTop = 0) {
  Object.defineProperty(el, 'clientHeight', { value: clientHeight, configurable: true, writable: true });
  Object.defineProperty(el, 'scrollHeight', { value: scrollHeight, configurable: true, writable: true });
  Object.defineProperty(el, 'scrollTop', { value: scrollTop, configurable: true, writable: true });
}

describe('useEdgeStretch', () => {
  let rafQueue: FrameRequestCallback[] = [];
  let nowMs = 1000;

  beforeEach(() => {
    rafQueue = [];
    nowMs = 1000;
    vi.spyOn(performance, 'now').mockImplementation(() => nowMs);
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => {
      rafQueue.push(cb);
      return rafQueue.length;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const stepFrames = (count: number, dt = 16) => {
    for (let i = 0; i < count; i++) {
      nowMs += dt;
      const batch = rafQueue.splice(0, rafQueue.length);
      for (const cb of batch) cb(nowMs);
    }
  };

  it('stretches from top origin when pulled down at top edge and settles back to empty transform', () => {
    render(<Box />);
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    stubMetrics(scroller, 800, 800, 0);

    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

    expect(inner.style.transformOrigin).toBe('50% 0');
    expect(inner.style.transform).toContain('scaleY(1.03');

    fireEvent.touchEnd(scroller);
    stepFrames(60);

    expect(inner.style.transform).toBe('');
    expect(inner.style.willChange).toBe('');
  });

  it('stretches from bottom origin when pulled up at bottom edge', () => {
    render(<Box />);
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    stubMetrics(scroller, 800, 2000, 1200);

    fireEvent.touchStart(scroller, { touches: [{ clientY: 500 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

    expect(inner.style.transformOrigin).toBe('50% 100%');
    expect(inner.style.transform).toContain('scaleY(1.03');
  });

  it('does not apply transform when starting mid-scroll and moving down', () => {
    render(<Box />);
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    stubMetrics(scroller, 800, 2000, 100);

    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

    expect(inner.style.transform).toBe('');
  });

  it('never writes a transform when prefers-reduced-motion is enabled', () => {
    const origMatchMedia = window.matchMedia;
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: (query: string) => ({
        matches: query.includes('reduce'),
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }),
    });

    try {
      render(<Box />);
      const scroller = screen.getByTestId('scroller');
      const inner = screen.getByTestId('inner');
      stubMetrics(scroller, 800, 800, 0);

      fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
      fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

      expect(inner.style.transform).toBe('');
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        writable: true,
        value: origMatchMedia,
      });
    }
  });

  it('causes zero React commits over 20 touchmove events', () => {
    let commits = 0;
    render(
      <Profiler
        id="stretch"
        onRender={() => {
          commits += 1;
        }}
      >
        <Box />
      </Profiler>,
    );
    const scroller = screen.getByTestId('scroller');
    stubMetrics(scroller, 800, 800, 0);

    commits = 0;
    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    for (let i = 1; i <= 20; i++) {
      fireEvent.touchMove(scroller, { touches: [{ clientY: 100 + i * 10 }] });
    }
    expect(commits).toBe(0);
  });

  it('attaches when the scroller mounts after the hook (always-mounted host that renders null first)', () => {
    render(<LateBox />);
    expect(screen.queryByTestId('scroller')).toBeNull();
    fireEvent.click(screen.getByTestId('show'));
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    stubMetrics(scroller, 800, 800, 0);

    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

    expect(inner.style.transform).toContain('scaleY(1.03');
  });

  it('removes its listeners on unmount', () => {
    const { unmount } = render(<Box />);
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    const removed: string[] = [];
    const orig = scroller.removeEventListener.bind(scroller);
    scroller.removeEventListener = ((type: string, ...rest: unknown[]) => {
      removed.push(type);
      return (orig as (...a: unknown[]) => void)(type, ...rest);
    }) as typeof scroller.removeEventListener;
    unmount();
    expect(removed.sort()).toEqual(['scroll', 'touchcancel', 'touchend', 'touchmove', 'touchstart']);
    expect(inner.style.transform).toBe('');
  });
  it('keepLayer: the list content stays on one layer — no will-change toggling per gesture (hot-fix list-fix)', () => {
    const { unmount } = render(<ListBox />);
    const scroller = screen.getByTestId('scroller');
    const inner = screen.getByTestId('inner');
    stubMetrics(scroller, 800, 800, 0);
    expect(scroller.hasAttribute('data-edge-layer')).toBe(true);
    const writes: string[] = [];
    const style = inner.style;
    const set = style.setProperty.bind(style);
    Object.defineProperty(style, 'willChange', { configurable: true, get: () => '', set: (v: string) => { writes.push(v); set('will-change', v); } });
    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });
    expect(inner.style.transform).toContain('scaleY(1.03');
    fireEvent.touchEnd(scroller);
    stepFrames(60);
    expect(inner.style.transform).toBe('');
    expect(writes).toEqual([]);
    unmount();
    expect(scroller.hasAttribute('data-edge-layer')).toBe(false);
  });
});
