/**
 * Подарунки: власний каталог, власні малюнки. Барви — родина тонів людей
 * (`--ph-person-N`), тож у Дні й Ночі вони приглушені й без бурштину.
 */
import React from 'react';
import type { Chat, GiftData, Message } from '../../../types/messenger';
import { tNow } from '../../chat/tNow';

export const GIFT_KIND = 'widget:gift';
export const GIFT_NOTE_MAX = 140;

export const GIFT_KINDS = [
  'flowers',
  'coffee',
  'cake',
  'star',
  'balloon',
  'tea',
  'plant',
  'book',
  'candle',
  'sun',
  'cookie',
  'heart',
] as const;

export type GiftKind = (typeof GIFT_KINDS)[number];

export const isGiftKind = (k: string): k is GiftKind => (GIFT_KINDS as readonly string[]).includes(k);

const TONE: Record<GiftKind, number> = {
  flowers: 4,
  coffee: 0,
  cake: 3,
  star: 3,
  balloon: 2,
  tea: 1,
  plant: 1,
  book: 5,
  candle: 0,
  sun: 3,
  cookie: 0,
  heart: 4,
};

export const giftName = (kind: string): string => (isGiftKind(kind) ? tNow(`gifts.kind.${kind}`) : tNow('gifts.title'));

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const KIND_RE = /^[a-z][a-z0-9-]{0,23}$/;

/** Тіло подарунка, або null — це не те, що обіцяє рід. Невідомий рід із каталогу лишається: його назвемо «Подарунок». */
export function giftOf(msg: Message): GiftData | null {
  const d = msg.type === GIFT_KIND ? (msg.giftData as unknown) : undefined;
  if (!d || typeof d !== 'object') return null;
  const { id, gift, note } = d as Record<string, unknown>;
  if (typeof id !== 'string' || !ID_RE.test(id) || typeof gift !== 'string' || !KIND_RE.test(gift)) return null;
  const text = typeof note === 'string' ? note.trim().replace(/\s+/g, ' ').slice(0, GIFT_NOTE_MAX) : '';
  return { v: 1, id, gift, ...(text ? { note: text } : {}) };
}

export interface ShelfGift {
  key: string;
  chatId: string;
  msgId: string;
  gift: string;
  note?: string;
  from: string;
  at?: string;
}

/** Отримані подарунки з листів розмов: свої й криві — ні, повтор доставки — раз, найновіші першими. */
export function receivedGifts(chats: readonly Chat[], meId: string, chatId?: string): ShelfGift[] {
  const seen = new Set<string>();
  const out: ShelfGift[] = [];
  for (const c of chats) {
    if (chatId && c.id !== chatId) continue;
    for (const m of c.messages ?? []) {
      if (!m || m.type !== GIFT_KIND || m.isDeleted || m.isSelf || m.senderId === meId) continue;
      const g = giftOf(m);
      if (!g || seen.has(g.id)) continue;
      seen.add(g.id);
      out.push({ key: g.id, chatId: c.id, msgId: m.id, gift: g.gift, note: g.note, from: m.senderName || c.title, at: m.sentAt });
    }
  }
  const ts = (x: ShelfGift) => (x.at ? Date.parse(x.at) || 0 : 0);
  return out.map((x, i) => [x, i] as const).sort((a, b) => ts(b[0]) - ts(a[0]) || a[1] - b[1]).map(([x]) => x);
}

const STAR = (() => {
  const pts: string[] = [];
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? 17 : 7.5;
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    pts.push(`${(24 + r * Math.cos(a)).toFixed(1)},${(25 + r * Math.sin(a)).toFixed(1)}`);
  }
  return pts.join(' ');
})();

const SOFT = { fill: 'currentColor', fillOpacity: 0.2 } as const;

const ART: Record<GiftKind | 'wrap', React.ReactNode> = {
  flowers: (
    <>
      <path d="M15 27 L24 44 L33 27 Z" {...SOFT} />
      <path d="M24 30 V20 M24 30 L17 21 M24 30 L31 21" />
      <circle cx="24" cy="15" r="5" {...SOFT} />
      <circle cx="15" cy="18" r="4.5" {...SOFT} />
      <circle cx="33" cy="18" r="4.5" {...SOFT} />
      <path d="M15 27 L24 44 L33 27" />
    </>
  ),
  coffee: (
    <>
      <path d="M12 20 H32 V30 a8 8 0 0 1 -8 8 H20 a8 8 0 0 1 -8 -8 Z" {...SOFT} />
      <path d="M12 20 H32 V30 a8 8 0 0 1 -8 8 H20 a8 8 0 0 1 -8 -8 Z" />
      <path d="M32 23 h3 a4 4 0 0 1 0 8 h-3" />
      <path d="M8 42 H36" />
      <path d="M18 9 c-2 3 2 4 0 7 M24 9 c-2 3 2 4 0 7" />
    </>
  ),
  cake: (
    <>
      <path d="M10 30 H38 V41 H10 Z" {...SOFT} />
      <path d="M14 22 H34 V30 H14 Z" {...SOFT} />
      <path d="M10 30 H38 V41 H10 Z M14 22 H34 V30 H14 Z" />
      <path d="M10 35 c3 2 5 2 7 0 s4 -2 7 0 s4 2 7 0 s4 -2 7 0" />
      <path d="M24 22 V15" />
      <path d="M24 7 c3 3 3 6 0 7 c-3 -1 -3 -4 0 -7 Z" fill="currentColor" />
    </>
  ),
  star: (
    <>
      <polygon points={STAR} {...SOFT} />
      <polygon points={STAR} />
    </>
  ),
  balloon: (
    <>
      <ellipse cx="24" cy="18" rx="11" ry="13" {...SOFT} />
      <ellipse cx="24" cy="18" rx="11" ry="13" />
      <path d="M22 31 h4 l-2 3 Z" fill="currentColor" />
      <path d="M24 34 c-3 3 3 5 0 8 s2 3 1 4" />
      <path d="M19 12 a6 6 0 0 1 4 -3" />
    </>
  ),
  tea: (
    <>
      <path d="M11 22 H33 V28 a11 11 0 0 1 -22 0 Z" {...SOFT} />
      <path d="M11 22 H33 V28 a11 11 0 0 1 -22 0 Z" />
      <path d="M33 25 l5 -4 v4 l-5 6" />
      <path d="M11 26 l-4 -2" />
      <path d="M16 22 c0 -4 3 -6 6 -6 s6 2 6 6" />
      <circle cx="22" cy="14" r="1.8" fill="currentColor" />
    </>
  ),
  plant: (
    <>
      <path d="M14 30 H34 L31 43 H17 Z" {...SOFT} />
      <path d="M14 30 H34 L31 43 H17 Z" />
      <path d="M24 30 V16" />
      <path d="M24 22 c-8 0 -11 -5 -11 -10 c6 0 11 3 11 10 Z" {...SOFT} />
      <path d="M24 22 c-8 0 -11 -5 -11 -10 c6 0 11 3 11 10 Z" />
      <path d="M24 19 c7 0 10 -5 10 -10 c-6 0 -10 3 -10 10 Z" {...SOFT} />
      <path d="M24 19 c7 0 10 -5 10 -10 c-6 0 -10 3 -10 10 Z" />
    </>
  ),
  book: (
    <>
      <path d="M24 14 c-5 -3 -10 -3 -16 -2 V38 c6 -1 11 -1 16 2 c5 -3 10 -3 16 -2 V12 c-6 -1 -11 -1 -16 2 Z" {...SOFT} />
      <path d="M24 14 c-5 -3 -10 -3 -16 -2 V38 c6 -1 11 -1 16 2 c5 -3 10 -3 16 -2 V12 c-6 -1 -11 -1 -16 2 Z M24 14 V40" />
      <path d="M12 19 c3 -0.5 6 0 8 1 M12 25 c3 -0.5 6 0 8 1 M28 20 c2 -1 5 -1.5 8 -1 M28 26 c2 -1 5 -1.5 8 -1" />
    </>
  ),
  candle: (
    <>
      <path d="M18 20 H30 V40 H18 Z" {...SOFT} />
      <path d="M18 20 H30 V40 H18 Z" />
      <path d="M12 40 H36" />
      <path d="M24 20 V16" />
      <path d="M24 6 c4 4 4 8 0 9.5 c-4 -1.5 -4 -5.5 0 -9.5 Z" fill="currentColor" />
      <path d="M22 25 v6" />
    </>
  ),
  sun: (
    <>
      <circle cx="24" cy="24" r="8.5" {...SOFT} />
      <circle cx="24" cy="24" r="8.5" />
      <path d="M24 7 v5 M24 36 v5 M7 24 h5 M36 24 h5 M12 12 l3.5 3.5 M32.5 32.5 L36 36 M36 12 l-3.5 3.5 M15.5 32.5 L12 36" />
    </>
  ),
  cookie: (
    <>
      <circle cx="24" cy="24" r="15" {...SOFT} />
      <path d="M39 24 a15 15 0 1 1 -9 -13.8 a3.5 3.5 0 0 0 4.5 4.5 a3.5 3.5 0 0 0 4.5 4.5 a15 15 0 0 1 0 4.8 Z" />
      <circle cx="18" cy="20" r="1.8" fill="currentColor" />
      <circle cx="26" cy="29" r="1.8" fill="currentColor" />
      <circle cx="17" cy="30" r="1.5" fill="currentColor" />
      <circle cx="29" cy="19" r="1.4" fill="currentColor" />
    </>
  ),
  heart: (
    <>
      <path d="M24 40 C10 31 7 23 9 17 c2 -6 10 -8 15 -2 c5 -6 13 -4 15 2 c2 6 -1 14 -15 23 Z" {...SOFT} />
      <path d="M24 40 C10 31 7 23 9 17 c2 -6 10 -8 15 -2 c5 -6 13 -4 15 2 c2 6 -1 14 -15 23 Z" />
    </>
  ),
  wrap: (
    <>
      <path d="M9 20 H39 V27 H9 Z M12 27 H36 V42 H12 Z" {...SOFT} />
      <path d="M9 20 H39 V27 H9 Z M12 27 H36 V42 H12 Z" />
      <path d="M24 20 V42" />
      <path d="M24 20 c-4 -6 -12 -8 -12 -3 c0 3 6 3 12 3 Z M24 20 c4 -6 12 -8 12 -3 c0 3 -6 3 -12 3 Z" />
    </>
  ),
};

/** Малюнок подарунка на плитці його тону. `wrap` — запакований, тон нейтральний. */
export const GiftArt: React.FC<{ kind: string; size: number; className?: string }> = ({ kind, size, className = '' }) => {
  const known = kind === 'wrap' || isGiftKind(kind);
  const art = known ? ART[kind as GiftKind | 'wrap'] : ART.wrap;
  const style: React.CSSProperties =
    kind === 'wrap' || !known
      ? { width: size, height: size, background: 'var(--ph-raised)', color: 'var(--ph-text-2)' }
      : { width: size, height: size, background: `var(--ph-person-${TONE[kind as GiftKind]})`, color: `var(--ph-person-${TONE[kind as GiftKind]}-ink)` };
  return (
    <span data-gift-art={kind} aria-hidden className={`inline-flex shrink-0 items-center justify-center rounded-[22%] ${className}`} style={style}>
      <svg
        viewBox="0 0 48 48"
        width={Math.round(size * 0.68)}
        height={Math.round(size * 0.68)}
        fill="none"
        stroke="currentColor"
        strokeWidth={2.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {art}
      </svg>
    </span>
  );
};
