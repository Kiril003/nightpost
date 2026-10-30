import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { usePress } from '../feel/press';

if (typeof PointerEvent === 'undefined') {
  (globalThis as { PointerEvent?: unknown }).PointerEvent = class extends MouseEvent {
    pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
    }
  };
}

function Probe({ onLong }: { onLong: () => void }) {
  const press = usePress({ delay: 100, longMs: 480, onLong });
  return (
    <div data-testid="probe" {...press}>
      row
    </div>
  );
}

describe('usePress feel', () => {
  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date', 'performance'],
    });
    vi.setSystemTime(100_000);
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => '{}',
      haptic: vi.fn(),
    };
  });

  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    vi.useRealTimers();
  });

  it('sets data-pressed only after delay (absent at 99 ms, present at 100 ms)', () => {
    render(<Probe onLong={() => {}} />);
    const el = screen.getByTestId('probe');

    fireEvent.pointerDown(el, { clientX: 10, clientY: 10 });
    act(() => {
      vi.advanceTimersByTime(99);
    });
    expect(el.hasAttribute('data-pressed')).toBe(false);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(el.hasAttribute('data-pressed')).toBe(true);
  });

  it('keeps data-pressed for at least 100 ms when released at 120 ms (until 200 ms)', () => {
    render(<Probe onLong={() => {}} />);
    const el = screen.getByTestId('probe');

    fireEvent.pointerDown(el, { clientX: 10, clientY: 10 });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(el.hasAttribute('data-pressed')).toBe(true);

    act(() => {
      vi.advanceTimersByTime(20);
    });
    fireEvent.pointerUp(el);
    expect(el.hasAttribute('data-pressed')).toBe(true);

    act(() => {
      vi.advanceTimersByTime(79);
    });
    expect(el.hasAttribute('data-pressed')).toBe(true);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(el.hasAttribute('data-pressed')).toBe(false);
  });

  it('clears data-pressed on a 9 px move', () => {
    render(<Probe onLong={() => {}} />);
    const el = screen.getByTestId('probe');

    fireEvent.pointerDown(el, { clientX: 10, clientY: 10 });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(el.hasAttribute('data-pressed')).toBe(true);

    fireEvent.pointerMove(el, { clientX: 19, clientY: 10 });
    expect(el.hasAttribute('data-pressed')).toBe(false);
  });

  it('fires onLong once at 480 ms and calls PhantomBridge.haptic with long', () => {
    const onLong = vi.fn();
    render(<Probe onLong={onLong} />);
    const el = screen.getByTestId('probe');

    fireEvent.pointerDown(el, { clientX: 10, clientY: 10 });
    act(() => {
      vi.advanceTimersByTime(479);
    });
    expect(onLong).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onLong).toHaveBeenCalledTimes(1);
    const bridge = (window as unknown as { PhantomBridge: { haptic: ReturnType<typeof vi.fn> } }).PhantomBridge;
    expect(bridge.haptic).toHaveBeenCalledWith('long');
  });

  it('does not fire onLong when released at 300 ms', () => {
    const onLong = vi.fn();
    render(<Probe onLong={onLong} />);
    const el = screen.getByTestId('probe');

    fireEvent.pointerDown(el, { clientX: 10, clientY: 10 });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    fireEvent.pointerUp(el);
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(onLong).not.toHaveBeenCalled();
  });
});
