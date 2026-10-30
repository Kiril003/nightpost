import React, { useState } from 'react';
import { ArrowLeftRight, ChevronLeft, ChevronRight, Plus } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { Avatar } from '../kit/Avatar';
import { AddShiftSheet } from './AddShiftSheet';
import { SwapSheet } from './SwapSheet';
import { currentDateTag } from '../../chat/tNow';
import { sendTake, useWorkState } from '../../../services/workSend';
import { isWorkGroup, weekLoad, weekOf, ymd, type WorkShift, type WorkSwap } from '../../../services/workEvents';

/** «Графік змін»: тиждень, день зі змінами, вільні зміни, обміни, що чекають. */
export const ShiftRoster: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const work = useWorkState(chatId);
  const [offset, setOffset] = useState(0);
  const [picked, setPicked] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [swap, setSwap] = useState<{ mine?: WorkShift; swap?: WorkSwap } | null>(null);
  const tag = currentDateTag();

  if (!chat || !isWorkGroup(chat)) return <p data-work-empty className="px-4 py-8 text-body text-ink-3">{t('work.noChat')}</p>;
  const week = weekOf(new Date(), offset);
  const today = ymd(new Date());
  const day = picked && week.includes(picked) ? picked : week.includes(today) ? today : week[0];
  const dayShifts = work.shifts.filter((s) => s.day === day).sort((a, b) => a.from.localeCompare(b.from));
  const load = weekLoad(work.shifts, work.meId, week);
  const live = work.swaps.filter((w) => w.state === 'offered' || w.state === 'accepted');
  const dateOf = (d: string) => new Date(`${d}T00:00`);
  const nameOf = (id: string) => (id === work.meId ? t('work.me') : work.names[id] || id);

  return (
    <div data-work-roster className="flex flex-col pb-24">
      <div className="px-4 pb-2 flex items-center gap-1">
        <button type="button" aria-label={t('work.prevWeek')} onClick={() => setOffset(offset - 1)} className="w-11 h-11 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay">
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} />
        </button>
        <span className="flex-1 text-center text-callout text-ink-2 tnum">{t('work.myWeek', { h: Math.round(load.hours * 10) / 10 })}</span>
        <button type="button" aria-label={t('work.nextWeek')} onClick={() => setOffset(offset + 1)} className="w-11 h-11 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay">
          <ChevronRight className="w-5 h-5" strokeWidth={1.75} />
        </button>
      </div>
      <div role="tablist" aria-label={t('work.week')} className="px-2 grid grid-cols-7">
        {week.map((d) => {
          const has = work.shifts.some((s) => s.day === d);
          return (
            <button key={d} role="tab" aria-selected={d === day} type="button" onClick={() => setPicked(d)} className={`min-h-0 min-w-0 py-2 rounded-card flex flex-col items-center gap-0.5 ${d === day ? 'bg-raised' : ''}`}>
              <span className="text-caption text-ink-3">{dateOf(d).toLocaleDateString(tag, { weekday: 'short' })}</span>
              <span className={`text-title-3 tnum ${d === day ? 'text-ink' : 'text-ink-2'}`}>{dateOf(d).getDate()}</span>
              <span aria-hidden className={`w-1 h-1 rounded-full ${has ? 'bg-ink-2' : 'bg-transparent'}`} />
            </button>
          );
        })}
      </div>
      <div className="px-4 pt-4 flex flex-col">
        {dayShifts.length === 0 && <p data-work-empty className="py-6 text-body text-ink-3">{t('work.emptyShifts')}</p>}
        {dayShifts.map((s) => {
          const mineRow = s.who === work.meId;
          const pending = live.find((w) => w.a === s.id || w.b === s.id);
          return (
            <div key={s.id} data-work-shift={s.id} className="min-h-14 py-2 border-b border-line flex items-center gap-3">
              <span className={`w-28 shrink-0 text-callout tnum ${mineRow ? 'text-ink' : 'text-ink-2'}`}>{s.from}–{s.to}</span>
              {s.who ? (
                <>
                  <Avatar name={nameOf(s.who)} tone={s.who} className="w-8 h-8 text-footnote" />
                  <span className="flex-1 min-w-0 truncate text-body text-ink">{nameOf(s.who)} <span className="text-footnote text-ink-3">{s.role}</span></span>
                </>
              ) : (
                <>
                  <span className="shrink-0 h-6 px-2 rounded-chip bg-accent-soft text-caption text-accent-text inline-flex items-center">{t('work.openShift')}</span>
                  <span className="flex-1 min-w-0 truncate text-footnote text-ink-3">{s.role}</span>
                  <button type="button" onClick={() => sendTake(chatId, s, work.names[work.meId] ?? '')} className="h-11 px-4 rounded-full border border-line-strong text-callout text-ink active:bg-overlay">{t('work.take')}</button>
                </>
              )}
              {pending ? (
                <span className="shrink-0 h-6 px-2 rounded-chip bg-raised text-caption text-ink-2 inline-flex items-center">{t('work.swapOffered')}</span>
              ) : mineRow ? (
                <button type="button" aria-label={t('work.offerSwap')} onClick={() => setSwap({ mine: s })} className="w-11 h-11 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay">
                  <ArrowLeftRight className="w-5 h-5" strokeWidth={1.75} />
                </button>
              ) : null}
            </div>
          );
        })}
      </div>
      {live.length > 0 && (
        <div className="px-4 pt-4 flex flex-col gap-3">
          {live.map((w) => {
            const a = work.shifts.find((s) => s.id === w.a);
            const b = work.shifts.find((s) => s.id === w.b);
            if (!a || !b) return null;
            const waitsMe = (w.state === 'offered' && w.to === work.meId) || (w.state === 'accepted' && w.lead === work.meId);
            return (
              <button key={w.id} type="button" data-work-swap={w.id} onClick={() => setSwap({ swap: w })} className="w-full min-h-0 text-left rounded-card bg-surface border border-line px-4 py-3 active:bg-overlay">
                <span className="flex items-center gap-2 text-callout text-ink">
                  <ArrowLeftRight className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden />
                  <span className="flex-1 truncate">{t('work.swapWith', { name: nameOf(w.from === work.meId ? w.to : w.from) })}</span>
                  <span className="text-caption text-ink-3 tnum">{w.at}</span>
                </span>
                <span className="mt-1 block text-footnote text-ink-2 tnum">{a.day.slice(5)} {a.from}–{a.to} ⇄ {b.day.slice(5)} {b.from}–{b.to}</span>
                <span className={`mt-1 block text-footnote ${waitsMe ? 'text-accent-text' : 'text-ink-3'}`}>{waitsMe ? t('work.yourTurn') : t('work.waiting')}</span>
              </button>
            );
          })}
        </div>
      )}
      {work.canPlan(work.meId) && (
        <button
          type="button"
          onClick={() => setAdding(true)}
          className="sticky bottom-4 self-end mr-4 mt-4 h-14 px-5 min-h-0 rounded-full bg-accent text-on-accent text-headline inline-flex items-center gap-2 active:bg-accent-press"
        >
          <Plus className="w-5 h-5" strokeWidth={2} aria-hidden />
          {t('work.addShift')}
        </button>
      )}
      {adding && <AddShiftSheet chat={chat} day={day} onClose={() => setAdding(false)} />}
      {swap && <SwapSheet chatId={chatId} work={work} week={week} mine={swap.mine} swap={swap.swap} onClose={() => setSwap(null)} />}
    </div>
  );
};
