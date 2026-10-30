import React, { useLayoutEffect, useRef } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { dateTag } from '../../../i18n/dateTag';
import { motionFor } from '../../../feel/motionTokens';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { GiftArt, giftName, type ShelfGift } from './giftCatalogue';

/** Полиця отриманих: три в ряд — малюнок на плитці тону, назва, від кого. */
export const GiftShelf: React.FC<{ items: ShelfGift[]; onOpen: (g: ShelfGift) => void; showFrom?: boolean }> = ({ items, onOpen, showFrom = true }) => (
  <div data-testid="gift-shelf" className="grid grid-cols-3 gap-2">
    {items.map((g) => (
      <button
        key={g.key}
        type="button"
        data-testid="gift-shelf-item"
        aria-label={`${giftName(g.gift)}, ${g.from}`}
        onClick={() => onOpen(g)}
        className="min-w-0 pt-3 pb-2.5 px-1 rounded-[20px] bg-surface flex flex-col items-center active:bg-overlay transition-colors duration-instant"
      >
        <GiftArt kind={g.gift} size={64} />
        <span className="mt-2 max-w-full truncate px-0.5 text-footnote text-ink">{giftName(g.gift)}</span>
        {showFrom && <span className="max-w-full truncate px-0.5 text-caption text-ink-3">{g.from}</span>}
      </button>
    ))}
  </div>
);

/** «12 жовт.»; інший рік — з роком. */
export function giftDay(at: string | undefined, locale: string, now = Date.now()): string | null {
  const ms = at ? Date.parse(at) : NaN;
  if (!Number.isFinite(ms) || ms <= 0) return null;
  const sameYear = new Date(ms).getFullYear() === new Date(now).getFullYear();
  return new Intl.DateTimeFormat(dateTag(locale), sameYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }).format(ms);
}

/** Пружне розкриття: малюнок із 0.6 і повороту, текст підіймається слідом. Тихий рух — лише прозорість. */
export function popOpen(art: HTMLElement | null, rest: readonly (HTMLElement | null)[]): void {
  const m = motionFor('settle');
  const quiet = m.prop === 'opacity';
  if (art && typeof art.animate === 'function') {
    art.animate(
      quiet ? [{ opacity: 0 }, { opacity: 1 }] : [{ opacity: 0, transform: 'scale(0.6) rotate(-10deg)' }, { opacity: 1, transform: 'scale(1) rotate(0deg)' }],
      { duration: m.ms, easing: m.easing, fill: 'both' },
    );
  }
  rest.forEach((el, i) => {
    if (!el || typeof el.animate !== 'function') return;
    el.animate(
      quiet ? [{ opacity: 0 }, { opacity: 1 }] : [{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'translateY(0)' }],
      { duration: quiet ? m.ms : 240, delay: quiet ? 0 : 90 + i * 50, easing: quiet ? m.easing : 'cubic-bezier(0.2, 0, 0, 1)', fill: 'both' },
    );
  });
}

/** Один подарунок: що, від кого, коли, підпис; двері до листа в розмові. */
export const GiftViewSheet: React.FC<{ gift: ShelfGift | null; onClose: () => void; onChat?: (g: ShelfGift) => void }> = ({ gift, onClose, onChat }) => {
  const { t, locale } = useTranslation();
  const art = useRef<HTMLDivElement>(null);
  const name = useRef<HTMLHeadingElement>(null);
  const sub = useRef<HTMLParagraphElement>(null);
  const note = useRef<HTMLParagraphElement>(null);

  useLayoutEffect(() => {
    if (gift) popOpen(art.current, [name.current, sub.current, note.current]);
  }, [gift?.key]); // eslint-disable-line react-hooks/exhaustive-deps

  const day = gift ? giftDay(gift.at, locale) : null;
  const line = gift ? [gift.from, day].filter(Boolean).join(' · ') : '';

  return (
    <FormSheet
      open={gift != null}
      onClose={onClose}
      title={t('gifts.shelf')}
      testId="gift-view"
      footer={
        gift && onChat ? (
          <PrimaryButton testId="gift-view-chat" onClick={() => onChat(gift)}>
            {t('gifts.toChat')}
          </PrimaryButton>
        ) : undefined
      }
    >
      {gift && (
        <div className="px-6 pt-2 pb-6 flex flex-col items-center text-center">
          <div ref={art} data-testid="gift-view-art">
            <GiftArt kind={gift.gift} size={128} />
          </div>
          <h3 ref={name} className="mt-4 text-title-2 text-ink">
            {giftName(gift.gift)}
          </h3>
          {line && (
            <p ref={sub} data-testid="gift-view-from" className="mt-1 text-footnote text-ink-2">
              {line}
            </p>
          )}
          {gift.note && (
            <p ref={note} data-testid="gift-view-note" className="mt-4 text-body text-ink break-words max-w-full">
              {gift.note}
            </p>
          )}
        </div>
      )}
    </FormSheet>
  );
};
