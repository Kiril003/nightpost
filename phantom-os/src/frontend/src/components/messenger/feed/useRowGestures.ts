/**
 * Жести рядка на дотику: подвійний дотик — серце, довгий — аркуш дій, свайп
 * ліворуч — відповісти (будь-який рядок, як у Telegram). Свайп рухає бульбашку напряму через DOM (без жодного
 * рендеру React), повертає пружиною; поріг відповіді дає гаптику посеред жесту.
 */
import { useRef } from 'react';
import type React from 'react';
import type { Message } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { SNAP, prefersReduced, rb, runSpring, velocity80, type Sample } from '../../../feel/physics';
import { createPress, type PressController } from '../../../feel/press';
import { haptic } from '../../../utils/haptic';

interface GestureDeps {
  react: (msgId: string, emoji: string) => void;
  openMenu: (msg: Message) => void;
  reply: (msg: Message) => void;
}

function shapeOffset(raw: number): number {
  const abs = Math.abs(Math.min(0, raw));
  if (abs === 0) return 0;
  return -(abs <= 64 ? abs : 64 + rb(abs - 64, 64));
}

export function useRowGestures({ react, openMenu, reply }: GestureDeps) {
  const touchStartXRef = useRef<number>(0);
  const touchStartYRef = useRef<number>(0);
  const isTouchSwipingRef = useRef<boolean>(false);
  const lastTapRef = useRef<Record<string, number>>({});
  const swipeOffsetRef = useRef<number>(0);
  const swipeBubbleElRef = useRef<HTMLElement | null>(null);
  const pressRef = useRef<PressController | null>(null);
  const activeMsgRef = useRef<Message | null>(null);
  const crossedThresholdRef = useRef<boolean>(false);
  const samplesRef = useRef<Sample[]>([]);
  const stopSnapRef = useRef<(() => void) | null>(null);

  const depsRef = useRef({ openMenu });
  depsRef.current = { openMenu };

  if (!pressRef.current) {
    pressRef.current = createPress({
      delay: 0,
      longMs: 420,
      mark: false,
      scale: true,
      onLong: () => {
        if (!isTouchSwipingRef.current && activeMsgRef.current) {
          soundFx.playTap();
          depsRef.current.openMenu(activeMsgRef.current);
        }
      },
    });
  }

  const handleTouchStart = (msg: Message, e: React.TouchEvent) => {
    const touch = e.touches[0];
    if (stopSnapRef.current) {
      stopSnapRef.current();
      stopSnapRef.current = null;
    }
    touchStartXRef.current = touch.clientX;
    touchStartYRef.current = touch.clientY;
    isTouchSwipingRef.current = false;
    swipeOffsetRef.current = 0;
    crossedThresholdRef.current = false;
    samplesRef.current = [{ t: Date.now(), y: 0 }];
    const bubbleEl = e.currentTarget as HTMLElement;
    swipeBubbleElRef.current = bubbleEl;
    activeMsgRef.current = msg;

    const now = Date.now();
    const lastTap = lastTapRef.current[msg.id] || 0;
    if (now - lastTap < 300) {
      pressRef.current?.cancel();
      lastTapRef.current[msg.id] = 0;
      // Напису тут немає: смайл під рядком і є відповіддю, а «Реакцію додано»
      // казалось і тоді, коли кадр не виходив із апарата.
      react(msg.id, '❤️');
      return;
    }
    lastTapRef.current[msg.id] = now;

    pressRef.current?.down(bubbleEl, touch.clientX, touch.clientY);
  };

  const handleTouchMove = (_msg: Message, e: React.TouchEvent) => {
    const touch = e.touches[0];
    const diffX = touch.clientX - touchStartXRef.current;
    const diffY = touch.clientY - touchStartYRef.current;

    if (Math.abs(diffY) > 12 && !isTouchSwipingRef.current) {
      pressRef.current?.cancel();
      return;
    }

    if (Math.abs(diffX) > 16 || isTouchSwipingRef.current) {
      pressRef.current?.cancel({ snap: true });
      isTouchSwipingRef.current = true;
      const shaped = shapeOffset(diffX);
      const prevAbs = Math.abs(swipeOffsetRef.current);
      const nextAbs = Math.abs(shaped);
      if (!crossedThresholdRef.current && prevAbs < 44 && nextAbs >= 44) {
        crossedThresholdRef.current = true;
        haptic('threshold');
      } else if (crossedThresholdRef.current && nextAbs < 44) {
        crossedThresholdRef.current = false;
        haptic('release');
      }
      swipeOffsetRef.current = shaped;
      samplesRef.current.push({ t: Date.now(), y: shaped });
      if (samplesRef.current.length > 24) {
        samplesRef.current.shift();
      }
      const el = swipeBubbleElRef.current || (e.currentTarget as HTMLElement);
      if (el) {
        el.style.transform = `translateX(${shaped}px)`;
        el.style.transition = 'none';
        const indicator = el.querySelector('.msg-swipe-reply-indicator') as HTMLElement | null;
        if (indicator) {
          indicator.style.opacity = String(Math.min(1, nextAbs / 25));
          indicator.style.transform = `translateY(-50%) scale(${Math.min(1.1, nextAbs / 35)})`;
          indicator.style.display = nextAbs > 15 ? 'flex' : 'none';
          // Взведено — коло стає бурштиновим: відпусти, і буде відповідь.
          indicator.dataset.armed = String(crossedThresholdRef.current);
        }
      }
    } else {
      pressRef.current?.move(touch.clientX, touch.clientY);
    }
  };

  const handleTouchEnd = (msg: Message) => {
    pressRef.current?.up();
    const offset = swipeOffsetRef.current;
    const el = swipeBubbleElRef.current;
    const wasSwiping = isTouchSwipingRef.current;
    if (wasSwiping) samplesRef.current.push({ t: Date.now(), y: offset });
    const v0 = velocity80(samplesRef.current);

    if (el) {
      const indicator = el.querySelector('.msg-swipe-reply-indicator') as HTMLElement | null;
      if (indicator) {
        indicator.style.opacity = '0';
        indicator.style.display = 'none';
      }
      if (wasSwiping && offset !== 0) {
        if (prefersReduced()) {
          el.style.transition = 'transform 120ms linear';
          el.style.transform = '';
        } else {
          el.style.transition = 'none';
          const stop = runSpring({
            ...SNAP,
            from: offset,
            to: 0,
            v0,
            onFrame: (v) => {
              el.style.transform = `translateX(${v}px)`;
            },
            onDone: () => {
              el.style.transform = '';
              stopSnapRef.current = null;
            },
          });
          stopSnapRef.current = () => {
            stop();
            el.style.transform = '';
          };
        }
      } else if (wasSwiping) {
        el.style.transform = '';
      }
      swipeBubbleElRef.current = null;
    }
    if (wasSwiping && Math.abs(offset) >= 44) {
      soundFx.playTap();
      reply(msg);
    }
    swipeOffsetRef.current = 0;
    isTouchSwipingRef.current = false;
    crossedThresholdRef.current = false;
    samplesRef.current = [];
  };

  return { handleTouchStart, handleTouchMove, handleTouchEnd };
}
