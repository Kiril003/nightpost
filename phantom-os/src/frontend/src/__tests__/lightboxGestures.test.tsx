/**
 * Жести переглядача фото: пінч-зум, подвійний дотик, панорамування,
 * свайп між слайдами й донизу на масштабі 1, Ctrl+коліщатко, плоскі плашки.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MediaLightboxModal } from '../components/messenger/MediaLightboxModal';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const photo = (i: number): Message =>
  ({
    id: `p${i}`,
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '2026-09-25T10:00:00Z',
    type: 'image',
    mediaUrl: `blob:photo-${i}`,
    text: `Підпис ${i}`,
  }) as Message;

beforeEach(() => {
  const chat = {
    id: 'c1',
    title: 'Оксана',
    type: 'dm',
    avatar: '',
    unreadCount: 0,
    messages: [photo(1), photo(2), photo(3)],
  } as unknown as Chat;
  useMessengerStore.setState({ chats: [chat], activeChatId: 'c1' });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const getStageAndWrapper = (alt = 'phantom-p1.jpg') => {
  const img = screen.getByAltText(alt);
  const wrapper = img.parentElement as HTMLElement;
  const stage = wrapper.parentElement as HTMLElement;
  return { img, wrapper, stage };
};

describe('жести переглядача фото', () => {
  it('пінч зі 100 px до 200 px виставляє data-scale="2.00" і scale(2 у transform обгортки', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage, wrapper } = getStageAndWrapper();
    expect(stage.getAttribute('data-scale')).toBe('1.00');

    const t1Start = { clientX: 150, clientY: 300, identifier: 0 };
    const t2Start = { clientX: 250, clientY: 300, identifier: 1 };
    const t1Move = { clientX: 100, clientY: 300, identifier: 0 };
    const t2Move = { clientX: 300, clientY: 300, identifier: 1 };

    fireEvent.touchStart(stage, {
      touches: [t1Start, t2Start],
      changedTouches: [t1Start, t2Start],
    });
    fireEvent.touchMove(stage, {
      touches: [t1Move, t2Move],
      changedTouches: [t1Move, t2Move],
    });
    fireEvent.touchEnd(stage, {
      touches: [],
      changedTouches: [t1Move, t2Move],
    });

    expect(stage.getAttribute('data-scale')).toBe('2.00');
    expect(wrapper.style.transform).toContain('scale(2');
  });

  it('подвійний дотик перемикає масштаб між 2.50 і 1.00', () => {
    let now = 1_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    vi.spyOn(performance, 'now').mockImplementation(() => now);

    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage } = getStageAndWrapper();

    const tap = (x: number, y: number) => {
      const touch = { clientX: x, clientY: y, identifier: 0 };
      fireEvent.touchStart(stage, { touches: [touch], changedTouches: [touch] });
      fireEvent.touchEnd(stage, { touches: [], changedTouches: [touch] });
    };

    tap(200, 300);
    now += 120;
    tap(204, 302);
    expect(stage.getAttribute('data-scale')).toBe('2.50');

    now += 500;
    tap(200, 300);
    now += 100;
    tap(200, 300);
    expect(stage.getAttribute('data-scale')).toBe('1.00');
  });

  it('емульований браузером dblclick після дотикового подвійного тапу не скасовує зум, а мишею dblclick працює', () => {
    let now = 1_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage } = getStageAndWrapper();
    const tap = () => {
      const touch = { clientX: 200, clientY: 300, identifier: 0 };
      fireEvent.touchStart(stage, { touches: [touch], changedTouches: [touch] });
      fireEvent.touchEnd(stage, { touches: [], changedTouches: [touch] });
    };
    tap();
    now += 100;
    tap();
    fireEvent.doubleClick(stage, { clientX: 200, clientY: 300 });
    expect(stage.getAttribute('data-scale')).toBe('2.50');

    now += 2_000;
    fireEvent.doubleClick(stage, { clientX: 200, clientY: 300 });
    expect(stage.getAttribute('data-scale')).toBe('1.00');
  });

  it('у збільшеному стані перетягування одним пальцем на -150 px зсуває translate, не гортає слайд і не закриває', () => {
    const onClose = vi.fn();
    render(<MediaLightboxModal isOpen onClose={onClose} mediaUrl="blob:photo-1" />);
    const { stage, wrapper } = getStageAndWrapper();

    const t1Start = { clientX: 150, clientY: 300, identifier: 0 };
    const t2Start = { clientX: 250, clientY: 300, identifier: 1 };
    const t1Move = { clientX: 100, clientY: 300, identifier: 0 };
    const t2Move = { clientX: 300, clientY: 300, identifier: 1 };

    fireEvent.touchStart(stage, { touches: [t1Start, t2Start], changedTouches: [t1Start, t2Start] });
    fireEvent.touchMove(stage, { touches: [t1Move, t2Move], changedTouches: [t1Move, t2Move] });
    fireEvent.touchEnd(stage, { touches: [], changedTouches: [t1Move, t2Move] });

    const beforeTransform = wrapper.style.transform;

    const startTouch = { clientX: 200, clientY: 300, identifier: 0 };
    const moveTouch = { clientX: 50, clientY: 300, identifier: 0 };
    fireEvent.touchStart(stage, { touches: [startTouch], changedTouches: [startTouch] });
    fireEvent.touchMove(stage, { touches: [moveTouch], changedTouches: [moveTouch] });
    fireEvent.touchEnd(stage, { touches: [], changedTouches: [moveTouch] });

    expect(wrapper.style.transform).not.toBe(beforeTransform);
    expect(wrapper.style.transform).toContain('translate3d(-350px, -300px, 0)');
    expect(screen.getByText(/1 з 3/)).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('на масштабі 1 свайп -120 px перемикає на «2 з 3» і скидає data-scale до 1.00, а свайп вниз на 200 px закриває', () => {
    const onClose = vi.fn();
    render(<MediaLightboxModal isOpen onClose={onClose} mediaUrl="blob:photo-1" />);
    const { stage } = getStageAndWrapper('phantom-p1.jpg');

    const start1 = { clientX: 200, clientY: 300, identifier: 0 };
    const move1 = { clientX: 80, clientY: 304, identifier: 0 };
    fireEvent.touchStart(stage, { touches: [start1], changedTouches: [start1] });
    fireEvent.touchMove(stage, { touches: [move1], changedTouches: [move1] });
    fireEvent.touchEnd(stage, { touches: [], changedTouches: [move1] });

    expect(screen.getByText(/2 з 3/)).toBeTruthy();
    const { stage: stage2 } = getStageAndWrapper('phantom-p2.jpg');
    expect(stage2.getAttribute('data-scale')).toBe('1.00');

    const start2 = { clientX: 200, clientY: 200, identifier: 0 };
    const move2 = { clientX: 204, clientY: 400, identifier: 0 };
    fireEvent.touchStart(stage2, { touches: [start2], changedTouches: [start2] });
    fireEvent.touchMove(stage2, { touches: [move2], changedTouches: [move2] });
    fireEvent.touchEnd(stage2, { touches: [], changedTouches: [move2] });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Ctrl+wheel з deltaY: -300 піднімає масштаб вище 1', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage } = getStageAndWrapper();
    fireEvent.wheel(stage, { ctrlKey: true, deltaY: -300, clientX: 200, clientY: 300 });
    expect(Number(stage.getAttribute('data-scale'))).toBeGreaterThan(1);
  });

  it('Ctrl+wheel гасить масштаб сторінки браузера (preventDefault спрацьовує)', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage } = getStageAndWrapper();
    const notPrevented = fireEvent.wheel(stage, { ctrlKey: true, deltaY: -100, clientX: 200, clientY: 300 });
    expect(notPrevented).toBe(false);
  });

  it('ледь розведений пінч (нижче 1.05) після відпускання повертається до 1.00', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const { stage, wrapper } = getStageAndWrapper();
    const a = { clientX: 150, clientY: 300, identifier: 0 };
    const b = { clientX: 250, clientY: 300, identifier: 1 };
    const b2 = { clientX: 253, clientY: 300, identifier: 1 };
    fireEvent.touchStart(stage, { touches: [a, b], changedTouches: [a, b] });
    fireEvent.touchMove(stage, { touches: [a, b2], changedTouches: [b2] });
    expect(stage.getAttribute('data-scale')).toBe('1.03');
    fireEvent.touchEnd(stage, { touches: [a], changedTouches: [b2] });
    fireEvent.touchEnd(stage, { touches: [], changedTouches: [a] });
    expect(stage.getAttribute('data-scale')).toBe('1.00');
    expect(wrapper.style.transform).toContain('translate3d(0px, 0px, 0)');
  });

  it('модальне вікно не містить linear-gradient у жодному inline-стилі', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:photo-1" />);
    const modal = screen.getByTestId('media-lightbox');
    const styled = [modal, ...Array.from(modal.querySelectorAll<HTMLElement>('[style]'))];
    for (const el of styled) {
      expect(el.getAttribute('style') ?? '').not.toContain('linear-gradient');
    }
  });
});
