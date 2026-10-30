/**
 * Сторінка закладу в розмові: що про себе написав власник у «Білдері»,
 * години, меню, оцінка людей і «Оцінити» (підпис ключем телефона).
 *
 * Сторінку везе міст із сервера (`/messenger/venues/{id}`). Немає мосту чи
 * шляху — показуємо знімок із запрошення і кажемо, що це знімок.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { MapPin, Star } from 'lucide-react';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { formatAmount, toUnits } from '../../../services/meetup/split';
import { meetupApi, refusalWords, snapOfPage, type VenueBlock, type VenuePage } from '../../../services/meetupApi';
import { phoneBridge } from '../../../services/messengerApi';
import type { VenueRating, VenueSnap } from '../../../types/meetup';
import { ratingWord } from './words';
import { lastPaidHere } from '../../../services/money/lastPaidHere';
import { useMessengerStore } from '../../../stores/messengerStore';
import { currentDateTag, tNow } from '../../chat/tNow';

import { useTranslation } from '../../../i18n/useTranslation';
const DAY: Record<string, string> = {
  get mon() { return tNow('venueSheet.day.mon'); }, get tue() { return tNow('venueSheet.day.tue'); }, get wed() { return tNow('venueSheet.day.wed'); }, get thu() { return tNow('venueSheet.day.thu'); },
  get fri() { return tNow('venueSheet.day.fri'); }, get sat() { return tNow('venueSheet.day.sat'); }, get sun() { return tNow('venueSheet.day.sun'); },
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <section className="px-4 pt-4">
    <h3 className="pb-1 text-caption text-ink-3">{title}</h3>
    {children}
  </section>
);

const localIsoAt = (ms = Date.now()): string => {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

export const formatEventWhen = (starts: string): string => {
  const d = new Date(starts);
  if (Number.isNaN(d.getTime())) return starts;
  const day = d.toLocaleDateString(currentDateTag(), { day: 'numeric', month: 'short' });
  return `${day} · ${starts.slice(11, 16)}`;
};

export const VenueSheet: React.FC<{
  open: boolean;
  onClose: () => void;
  venueId: string;
  /** Знімок із картки — показуємо, поки (чи якщо) сторінка не приїхала. */
  snap?: VenueSnap | null;
  /** «Зустрітися тут» — є лише там, де з цього можна скласти чернетку. */
  onMeetHere?: (v: VenueSnap) => void;
}> = ({ open, onClose, venueId, snap, onMeetHere }) => {
  const { t } = useTranslation();
  const [page, setPage] = useState<VenuePage | null>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [rating, setRating] = useState<VenueRating | null>(snap?.rating ?? null);
  const [mine, setMine] = useState<number | null>(null);
  const [rateNote, setRateNote] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    let alive = true;
    setRefused(null);
    setPage(null);
    setRating(snap?.rating ?? null);
    setMine(null);
    setRateNote(null);
    if (!phoneBridge()) {
      setRefused(t('venueSheet.phoneOnly'));
      return;
    }
    meetupApi.venue(venueId).then(
      (p) => {
        if (!alive) return;
        setPage(p);
        setRating(p.rating);
      },
      (e) => alive && setRefused(refusalWords(e, 'svc.meetup.what.venuePages')),
    );
    return () => {
      alive = false;
    };
  }, [open, venueId]);

  const rate = async (stars: number) => {
    setMine(stars);
    setRateNote(null);
    try {
      const r = await meetupApi.rate(venueId, stars);
      setRating(r);
      setRateNote(t('venueSheet.rateSigned'));
    } catch (e) {
      setMine(null);
      setRateNote(refusalWords(e, 'svc.meetup.what.rate'));
    }
  };

  const chats = useMessengerStore((s) => s.chats);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const paid = useMemo(() => lastPaidHere(chats, venueId, meId), [chats, venueId, meId]);

  const name = page?.name ?? snap?.name ?? t('venueSheet.venue');
  const address = page?.address ?? snap?.address ?? '';
  const about = page?.blocks.find((b) => b.type === 'about');
  const hours = page?.blocks.find((b) => b.type === 'hours');
  const nowIso = localIsoAt();
  const events = (page?.blocks.filter((b): b is Extract<VenueBlock, { type: 'event' }> => b.type === 'event') ?? [])
    .filter((ev) => (ev.ends ?? ev.starts) >= nowIso)
    .sort((a, b) => (a.starts < b.starts ? -1 : a.starts > b.starts ? 1 : 0))
    .slice(0, 5);
  const menus = page?.blocks.filter((b) => b.type === 'menu') ?? [];
  const meetSnap = page ? snapOfPage(page) : snap ?? null;

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      title={name}
      tall
      testId="venue-sheet"
      footer={
        onMeetHere && meetSnap ? (
          <PrimaryButton
            onClick={() => {
              onMeetHere(meetSnap);
              onClose();
            }}
          >
            {t('venueSheet.meetHere')}
          </PrimaryButton>
        ) : undefined
      }
    >
      <div className="px-4 flex items-start gap-2 text-callout text-ink-2">
        <MapPin className="w-4 h-4 mt-0.5 shrink-0" strokeWidth={1.75} aria-hidden />
        <span className="min-w-0">{address || t('venueSheet.noAddress')}</span>
      </div>
      <p className="px-4 pt-1 text-footnote text-ink-3" data-testid="venue-rating">
        {ratingWord(rating)}
      </p>
      {paid && (
        <p className="px-4 pt-1 text-footnote text-ink-3" data-testid="venue-last-paid">
          {t('lastPaid.line', {
            name: paid.isMe ? t('lastPaid.you') : paid.payerName,
            date: new Date(paid.dateMs).toLocaleDateString(currentDateTag(), { day: 'numeric', month: 'short' }),
          })}
        </p>
      )}
      {refused && (
        <p className="px-4 pt-3 text-footnote text-ink-3" data-testid="venue-refused">
          {refused}
          {snap ? ` ${t('venueSheet.snapBelow')}` : ''}
        </p>
      )}

      {page && (
        <Section title={t('venueSheet.yourRating')}>
          <div className="flex gap-1" role="radiogroup" aria-label={t('venueSheet.ratingLabel')}>
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                type="button"
                role="radio"
                aria-checked={mine === n}
                aria-label={t('msg.venueSheet.starOf', { n })}
                data-testid={`venue-rate-${n}`}
                onClick={() => void rate(n)}
                className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center rounded-full active:bg-overlay"
              >
                <Star
                  className={`w-6 h-6 ${mine !== null && n <= mine ? 'text-accent fill-current' : 'text-ink-3'}`}
                  strokeWidth={1.75}
                  aria-hidden
                />
              </button>
            ))}
          </div>
          {rateNote && <p className="pt-1 text-footnote text-ink-3">{rateNote}</p>}
        </Section>
      )}

      {about && about.type === 'about' && (
        <Section title={t('venueSheet.about')}>
          <p className="text-callout text-ink whitespace-pre-line">{about.text}</p>
        </Section>
      )}

      {hours && hours.type === 'hours' && (
        <Section title={t('venueSheet.hours')}>
          {hours.rows.map((r, i) => (
            <div key={i} className="flex justify-between text-callout text-ink tnum">
              <span>{DAY[r.day] ?? r.day}</span>
              <span>
                {r.open}–{r.close}
              </span>
            </div>
          ))}
        </Section>
      )}

      {events.length > 0 && (
        <Section title={t('venueSheet.events')}>
          <div data-testid="venue-events">
            {events.map((ev) => (
              <div
                key={ev.id}
                data-testid="venue-event-row"
                className="min-h-12 py-1.5 flex items-center gap-3 border-t border-line first:border-t-0"
              >
                <span className="flex-1 min-w-0">
                  <span className="block text-callout text-ink truncate">{ev.title}</span>
                  <span className="block text-footnote text-ink-2 tnum truncate">{formatEventWhen(ev.starts)}</span>
                  {ev.note && <span className="block text-footnote text-ink-3 truncate">{ev.note}</span>}
                </span>
                {ev.price && ev.token && (
                  <span className="text-callout text-ink tnum shrink-0">
                    {formatAmount(toUnits(ev.price, ev.token), ev.token)}
                  </span>
                )}
              </div>
            ))}
          </div>
        </Section>
      )}

      {menus.map((m, i) =>
        m.type === 'menu' ? (
          <Section key={i} title={m.title || t('venueSheet.menu')}>
            {m.items.map((it) => (
              <div key={it.id} className="min-h-10 flex items-center gap-3 border-t border-line first:border-t-0">
                <span className="flex-1 min-w-0">
                  <span className="block text-callout text-ink truncate">{it.name}</span>
                  {it.note && <span className="block text-footnote text-ink-3 truncate">{it.note}</span>}
                </span>
                <span className="text-callout text-ink tnum shrink-0">{formatAmount(toUnits(it.price, m.token), m.token)}</span>
              </div>
            ))}
          </Section>
        ) : null,
      )}
      {page && menus.length === 0 && (
        <p className="px-4 pt-4 text-footnote text-ink-3">{t('venueSheet.noMenu')}</p>
      )}
      <div className="h-4" />
    </FormSheet>
  );
};
