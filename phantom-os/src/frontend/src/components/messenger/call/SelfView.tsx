/**
 * Своє відео у відеодзвінку — віконце, яке можна перетягнути в будь-який
 * кут. Під час тягання React не рендерить нічого: позиція пишеться прямо в
 * `transform` (компоновник, без перерахунку розмітки), а відпущене віконце
 * доїжджає до найближчого кута тією ж властивістю. Клавіатурою кути
 * перебираються стрілками — тягати пальцем не єдиний спосіб.
 */

import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';

export type Corner = 'tl' | 'tr' | 'bl' | 'br';

const W = 112;
const H = 160;
const MARGIN = 16;
const SNAP = 'transform 280ms cubic-bezier(0.2, 0.8, 0.2, 1)';

interface Insets {
  /** Скільки зверху зайнято шапкою (з вирізом екрана). */
  top: number;
  /** Скільки знизу зайнято панеллю кнопок. */
  bottom: number;
}

const cornerXY = (corner: Corner, box: { w: number; h: number }, insets: Insets): [number, number] => {
  const x = corner.endsWith('l') ? MARGIN : Math.max(MARGIN, box.w - W - MARGIN);
  const y = corner.startsWith('t') ? insets.top + MARGIN : Math.max(insets.top + MARGIN, box.h - H - insets.bottom - MARGIN);
  return [x, y];
};

const nearest = (x: number, y: number, box: { w: number; h: number }): Corner =>
  `${y + H / 2 < box.h / 2 ? 't' : 'b'}${x + W / 2 < box.w / 2 ? 'l' : 'r'}` as Corner;

export const SelfView: React.FC<{
  insets: Insets;
  children: React.ReactNode;
}> = ({ insets, children }) => {
  const { t } = useTranslation();
  const { top, bottom } = insets;
  const ref = useRef<HTMLDivElement | null>(null);
  const [corner, setCorner] = useState<Corner>('tr');
  const drag = useRef<{ id: number; dx: number; dy: number; moved: boolean } | null>(null);
  /** Остання позиція, куди віконце поставлене (або їде) — щоб рендер її не смикав. */
  const placed = useRef<string>('');

  const move = useCallback((x: number, y: number, animate: boolean) => {
    const el = ref.current;
    if (!el) return;
    const target = `translate3d(${x}px, ${y}px, 0)`;
    if (placed.current === target) return;
    placed.current = target;
    el.style.transition = animate ? SNAP : 'none';
    el.style.transform = target;
  }, []);

  const box = useCallback(() => {
    const parent = ref.current?.offsetParent as HTMLElement | null;
    return {
      w: parent?.clientWidth || (typeof window !== 'undefined' ? window.innerWidth : 390),
      h: parent?.clientHeight || (typeof window !== 'undefined' ? window.innerHeight : 844),
    };
  }, []);

  const place = useCallback(
    (c: Corner, animate: boolean) => {
      const [x, y] = cornerXY(c, box(), { top, bottom });
      move(x, y, animate);
    },
    [box, move, top, bottom],
  );

  // Рендер посеред тягання (знімок рушія оновлюється щосекунди) віконце не чіпає.
  useLayoutEffect(() => {
    if (!drag.current) place(corner, false);
  }, [corner, place]);

  useEffect(() => {
    const onResize = () => {
      if (!drag.current) place(corner, false);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [corner, place]);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el) return;
    const [x, y] = cornerXY(corner, box(), { top, bottom });
    drag.current = { id: e.pointerId, dx: e.clientX - x, dy: e.clientY - y, moved: false };
    try {
      el.setPointerCapture(e.pointerId);
    } catch {
      /* jsdom і старі WebView без захоплення — тягнемо й так */
    }
  };

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    const el = ref.current;
    if (!d || !el || d.id !== e.pointerId) return;
    const b = box();
    const x = Math.min(Math.max(0, e.clientX - d.dx), b.w - W);
    const y = Math.min(Math.max(0, e.clientY - d.dy), b.h - H);
    d.moved = true;
    move(x, y, false);
  };

  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    drag.current = null;
    if (!d || d.id !== e.pointerId) return;
    if (!d.moved) return;
    const b = box();
    const x = Math.min(Math.max(0, e.clientX - d.dx), b.w - W);
    const y = Math.min(Math.max(0, e.clientY - d.dy), b.h - H);
    // До кута — анімацією; стан лише запамʼятовує, куди приїхали, і рендер
    // ту саму позицію вдруге не ставить.
    const next = nearest(x, y, b);
    place(next, true);
    if (next !== corner) setCorner(next);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const v = corner[0];
    const h = corner[1];
    let next: Corner | null = null;
    if (e.key === 'ArrowLeft') next = `${v}l` as Corner;
    else if (e.key === 'ArrowRight') next = `${v}r` as Corner;
    else if (e.key === 'ArrowUp') next = `t${h}` as Corner;
    else if (e.key === 'ArrowDown') next = `b${h}` as Corner;
    if (!next || next === corner) return;
    e.preventDefault();
    place(next, true);
    setCorner(next);
  };

  return (
    <div
      ref={ref}
      data-call-self
      data-call-self-corner={corner}
      role="group"
      tabIndex={0}
      aria-label={t('call.self')}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      onKeyDown={onKeyDown}
      className="absolute left-0 top-0 z-20 overflow-hidden rounded-sheet bg-raised cursor-grab active:cursor-grabbing focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
      style={{
        width: W,
        height: H,
        touchAction: 'none',
        willChange: 'transform',
        border: '1px solid var(--ph-line-strong)',
      }}
    >
      {children}
    </div>
  );
};
