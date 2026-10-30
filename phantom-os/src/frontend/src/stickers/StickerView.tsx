import React, { useEffect, useRef, useState } from 'react';
import type { AnimationItem } from 'lottie-web';
import { loadLottie } from './lottieRuntime';
import { loadSticker } from './pack';
import { prefersReduced } from '../feel/physics';
import { useTranslation } from '../i18n/useTranslation';

/** Кадр, де кожен стікер уже «зібраний»: його показуємо, коли рух вимкнено. */
export const POSTER_FRAME = 90;

interface Props {
  packId: string;
  id: string;
  emoji: string;
  size?: number;
  loop?: boolean;
  /** Нерухомий кадр (сітка вибору): десяток живих анімацій у сітці — зайвий жар. */
  still?: boolean;
  /** Велике емодзі: один прогін при першій появі, повтор — дотиком. Ключ пам'ятає, що вже грало. */
  onceKey?: string;
  /** Один прогін закінчився (лише з onceKey). */
  onDone?: () => void;
}

const played = new Set<string>();

export const StickerView: React.FC<Props> = ({ packId, id, emoji, size = 160, loop = true, still = false, onceKey, onDone }) => {
  const { t } = useTranslation();
  const box = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<'wait' | 'on' | 'off'>('wait');
  const live = useRef<AnimationItem | null>(null);
  const once = onceKey !== undefined;
  const done = useRef(onDone);
  done.current = onDone;

  useEffect(() => {
    let alive = true;
    let anim: AnimationItem | null = null;
    let seen: IntersectionObserver | null = null;
    setState('wait');
    Promise.all([loadSticker(packId, id), loadLottie()]).then(
      ([data, lottie]) => {
        const el = box.current;
        if (!alive || !el) return;
        if (!data) return setState('off');
        anim = lottie.loadAnimation({
          container: el,
          renderer: 'svg',
          loop: loop && !once,
          autoplay: false,
          animationData: JSON.parse(JSON.stringify(data)),
          rendererSettings: { preserveAspectRatio: 'xMidYMid meet' },
        });
        live.current = anim;
        if (once) anim.addEventListener('complete', () => done.current?.());
        setState('on');
        if (still || prefersReduced() || (once && played.has(onceKey))) return anim.goToAndStop(POSTER_FRAME, true);
        const show = () => {
          if (!once) return anim?.play();
          played.add(onceKey);
          seen?.disconnect();
          anim?.goToAndPlay(0, true);
        };
        if (typeof IntersectionObserver === 'undefined') return show();
        seen = new IntersectionObserver(([e]) => (e?.isIntersecting ? show() : anim?.pause()));
        seen.observe(el);
      },
      () => alive && setState('off'),
    );
    return () => {
      alive = false;
      seen?.disconnect();
      anim?.destroy();
      live.current = null;
    };
  }, [packId, id, loop, still, once, onceKey]);

  return (
    <div
      role="img"
      aria-label={once ? emoji : `${emoji} ${t('chatList.kind.sticker')}`}
      data-sticker={`${packId}/${id}`}
      data-sticker-state={state}
      className={`relative select-none${once ? ' mx-auto' : ''}`}
      style={{ width: size, height: size }}
      onClick={
        once
          ? () => {
              if (state === 'on' && !prefersReduced()) live.current?.goToAndPlay(0, true);
            }
          : undefined
      }
    >
      <div ref={box} aria-hidden className="absolute inset-0" />
      {state === 'off' && (
        <span aria-hidden className="absolute inset-0 flex items-center justify-center leading-none" style={{ fontSize: Math.round(size * 0.55) }}>
          {emoji}
        </span>
      )}
    </div>
  );
};
