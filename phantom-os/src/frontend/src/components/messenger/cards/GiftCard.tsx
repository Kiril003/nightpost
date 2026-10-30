/**
 * Подарунок у розмові: отримувач бачить запакований і відкриває раз;
 * своє — одразу відкрите. Вибір — власний каталог і короткий підпис.
 */
import React, { useLayoutEffect, useRef, useState } from 'react';
import { Gift } from 'lucide-react';
import type { Message } from '../../../types/messenger';
import { useMessengerStore } from '../../../stores/messengerStore';
import { soundFx } from '../../../utils/messengerSound';
import { useTranslation } from '../../../i18n/useTranslation';
import { motionFor } from '../../../feel/motionTokens';
import { giftOpened } from '../../../services/giftOpened';
import { haptic } from '../../../utils/haptic';
import { popOpen } from '../gifts/GiftShelf';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { TicketCard } from '../kit/TicketCard';
import { GIFT_KIND, GIFT_KINDS, GIFT_NOTE_MAX, GiftArt, giftName, giftOf, type GiftKind } from '../gifts/giftCatalogue';

/** Коробка хитається й знімається вгору; чекаємо кінця, далі — сам подарунок. */
const SHAKE: Keyframe[] = [
  { transform: 'rotate(0deg) scale(1)', opacity: 1 },
  { transform: 'rotate(-7deg) scale(1.04)', offset: 0.18 },
  { transform: 'rotate(6deg) scale(1.06)', offset: 0.4 },
  { transform: 'rotate(-3deg) scale(1.06)', offset: 0.6 },
  { transform: 'rotate(0deg) translateY(-4px) scale(1.1)', opacity: 1, offset: 0.78 },
  { transform: 'rotate(0deg) translateY(-10px) scale(1.18)', opacity: 0 },
];

const unbox = async (box: HTMLElement | null): Promise<void> => {
  if (!box || typeof box.animate !== 'function' || motionFor('settle').prop === 'opacity') return;
  try {
    await box.animate(SHAKE, { duration: 460, easing: 'ease-in-out', fill: 'forwards' }).finished;
  } catch {
    /* скасовано — просто відкриваємо */
  }
};

export const GiftCard: React.FC<{ msg: Message; meId: string }> = ({ msg, meId }) => {
  const { t } = useTranslation();
  const g = giftOf(msg);
  const mine = msg.isSelf || msg.senderId === meId;
  const [open, setOpen] = useState(() => mine || !g || giftOpened.has(g.id));
  const [opening, setOpening] = useState(false);
  const justOpened = useRef(false);
  const box = useRef<HTMLDivElement>(null);
  const art = useRef<HTMLDivElement>(null);
  const name = useRef<HTMLHeadingElement>(null);
  const note = useRef<HTMLParagraphElement>(null);

  useLayoutEffect(() => {
    if (!open || !justOpened.current) return;
    justOpened.current = false;
    popOpen(art.current, [name.current, note.current]);
  }, [open]);

  if (!g) return <TicketCard testId="card-unreadable" title={t('gifts.title')} status={t('messageCard.unreadable')} />;

  const unwrap = async () => {
    if (opening) return;
    soundFx.playTap();
    giftOpened.add(g.id);
    setOpening(true);
    await unbox(box.current);
    haptic('success');
    justOpened.current = true;
    setOpen(true);
  };

  return (
    <section
      data-testid="card-gift"
      data-state={open ? 'open' : opening ? 'opening' : 'closed'}
      className="w-[240px] max-w-full rounded-bubble bg-surface border border-line text-ink overflow-hidden select-none"
    >
      <div className="px-4 pt-3.5 flex items-center gap-1.5 text-caption font-semibold text-ink-3">
        <Gift className="w-4 h-4" strokeWidth={1.75} aria-hidden />
        <span className="truncate">{t('gifts.title')}</span>
      </div>
      <div className="px-4 pt-3 pb-4 flex flex-col items-center text-center">
        {open ? (
          <>
            <div key="art" ref={art} data-testid="gift-art">
              <GiftArt kind={g.gift} size={112} />
            </div>
            <h3 ref={name} data-testid="gift-name" className="mt-3 text-title-2 text-ink">
              {giftName(g.gift)}
            </h3>
            {g.note && (
              <p ref={note} data-testid="gift-note-text" className="mt-1 text-body text-ink-2 break-words max-w-full">
                {g.note}
              </p>
            )}
          </>
        ) : (
          <>
            <div key="wrap" ref={box} data-testid="gift-wrap">
              <GiftArt kind="wrap" size={112} />
            </div>
            <button
              type="button"
              data-testid="gift-open"
              disabled={opening}
              onClick={(e) => {
                e.stopPropagation();
                void unwrap();
              }}
              className={`mt-3 touch-floor-inline h-9 px-5 rounded-full border border-line-strong text-ink text-subhead font-semibold active:bg-ink/[.06] transition-opacity duration-instant ${opening ? 'opacity-0' : ''}`}
            >
              {t('gifts.open')}
            </button>
          </>
        )}
      </div>
    </section>
  );
};

/** Вибір подарунка з каталогу й підпис до нього. */
export const GiftPickSheet: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const { t } = useTranslation();
  const sendCard = useMessengerStore((s) => s.sendCard);
  const [pick, setPick] = useState<GiftKind | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const close = () => {
    setPick(null);
    setNote('');
    setRefusal(null);
    onClose();
  };

  const send = async () => {
    if (!pick || busy) return;
    soundFx.playTap();
    setBusy(true);
    const id = `g_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
    const text = note.trim().slice(0, GIFT_NOTE_MAX);
    const no = await sendCard(GIFT_KIND, { v: 1, id, gift: pick, ...(text ? { note: text } : {}) });
    setBusy(false);
    if (no) setRefusal(no);
    else close();
  };

  return (
    <FormSheet
      open={open}
      onClose={close}
      title={t('gifts.pickTitle')}
      testId="gift-pick"
      footer={
        <PrimaryButton testId="gift-send" disabled={!pick || busy} onClick={() => void send()}>
          {t('gifts.send')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-1 grid grid-cols-4 gap-1.5 select-none">
        {GIFT_KINDS.map((k) => (
          <button
            key={k}
            type="button"
            data-testid={`gift-pick-${k}`}
            aria-pressed={pick === k}
            onClick={() => {
              soundFx.playTap();
              setPick(k);
            }}
            className={`min-w-0 py-2 rounded-control flex flex-col items-center gap-1.5 transition-colors duration-instant ${
              pick === k ? 'bg-overlay' : 'active:bg-overlay'
            }`}
          >
            <GiftArt kind={k} size={56} />
            <span className={`max-w-full truncate px-0.5 text-caption ${pick === k ? 'text-ink font-semibold' : 'text-ink-2'}`}>{giftName(k)}</span>
          </button>
        ))}
      </div>
      <div className="px-4 pt-2 pb-3">
        <input
          data-testid="gift-note"
          value={note}
          maxLength={GIFT_NOTE_MAX}
          onChange={(e) => setNote(e.target.value)}
          placeholder={t('gifts.note')}
          aria-label={t('gifts.note')}
          className="w-full h-12 px-3 rounded-control bg-raised border border-line-strong text-body text-ink placeholder:text-ink-3 focus:outline-none focus-visible:border-accent-line"
        />
        {refusal && <p className="pt-2 text-caption text-danger">{refusal}</p>}
      </div>
    </FormSheet>
  );
};
