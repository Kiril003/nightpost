import React, { useEffect, useRef, useState } from 'react';
import { prefersReduced } from '../feel/physics';
import { stickerForEmoji } from './pack';
import { StickerView } from './StickerView';

const SIZE = 72;
let seq = 0;

const fly = (emoji: string) => (stickerForEmoji(emoji) && !prefersReduced() ? `burst-${++seq}` : null);

/**
 * Нова реакція — її живий двійник з набору раз злітає над пігулкою.
 * Нова = лічильник підріс або пігулка зʼявилась на вже показаному листі (`armed`);
 * реакції, що були при відкритті чату, стоять тихо.
 */
export const ReactionBurst: React.FC<{ emoji: string; count: number; armed: boolean }> = ({ emoji, count, armed }) => {
  const last = useRef(count);
  const [key, setKey] = useState<string | null>(() => (armed ? fly(emoji) : null));
  useEffect(() => {
    if (count > last.current) {
      const k = fly(emoji);
      if (k) setKey(k);
    }
    last.current = count;
  }, [count, emoji]);
  useEffect(() => {
    if (!key) return;
    const t = window.setTimeout(() => setKey(null), 2400);
    return () => window.clearTimeout(t);
  }, [key]);
  const d = key ? stickerForEmoji(emoji) : null;
  if (!d) return null;
  return (
    <span
      aria-hidden
      data-reaction-burst={`${d.packId}/${d.id}`}
      className="pointer-events-none absolute left-1/2 bottom-full -translate-x-1/2 z-20"
      style={{ width: SIZE, height: SIZE }}
    >
      <StickerView key={key} packId={d.packId} id={d.id} emoji={d.emoji} size={SIZE} onceKey={key!} onDone={() => setKey(null)} />
    </span>
  );
};
