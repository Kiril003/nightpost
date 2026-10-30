import React, { useState } from 'react';
import { Check } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { sendSwap, sendSwapAck } from '../../../services/workSend';
import { loadAfterSwap, weekLoad, type WorkShift, type WorkState, type WorkSwap } from '../../../services/workEvents';

const MIN_REST_H = 11;
type Ws = WorkState & { meId: string };

const Tile: React.FC<{ label: string; s: WorkShift }> = ({ label, s }) => (
  <div className="flex-1 min-w-0 rounded-card bg-raised px-3 py-3">
    <span className="block text-footnote text-ink-3 truncate">{label}</span>
    <span className="block text-headline text-ink tnum">{s.day.slice(5)}</span>
    <span className="block text-callout text-ink-2 tnum">{s.from}–{s.to}</span>
  </div>
);

const Check1: React.FC<{ ok: boolean; label: string; value: string }> = ({ ok, label, value }) => (
  <div data-work-check={ok ? 'ok' : 'bad'} className="min-h-12 py-2 flex items-center gap-3 border-b border-line">
    <Check className={`w-5 h-5 shrink-0 ${ok ? 'text-success' : 'text-danger'}`} strokeWidth={2} aria-hidden />
    <span className="flex-1 text-body text-ink">{label}</span>
    <span className="text-footnote text-ink-2 tnum">{value}</span>
  </div>
);

/** Обмін змінами: скласти пропозицію зі своєї зміни або розглянути чужу (прийняти, затвердити, відхилити). */
export const SwapSheet: React.FC<{ chatId: string; work: Ws; week: string[]; mine?: WorkShift; swap?: WorkSwap; onClose: () => void }> = ({ chatId, work, week, mine, swap, onClose }) => {
  const { t } = useTranslation();
  const nameOf = (id: string) => (id === work.meId ? t('work.me') : work.names[id] || id);
  const busy = new Set(work.swaps.filter((w) => w.state === 'offered' || w.state === 'accepted').flatMap((w) => [w.a, w.b]));
  const [target, setTarget] = useState<string | null>(null);

  const give = swap ? work.shifts.find((s) => s.id === (swap.to === work.meId ? swap.b : swap.a)) : mine;
  const get = swap
    ? work.shifts.find((s) => s.id === (swap.to === work.meId ? swap.a : swap.b))
    : work.shifts.find((s) => s.id === target);
  const other = swap ? (swap.from === work.meId ? swap.to : swap.from) : get?.who ?? '';

  const rows = mine ? work.shifts.filter((s) => s.who && s.who !== work.meId && week.includes(s.day) && !busy.has(s.id) && s.id !== mine.id) : [];
  const before = weekLoad(work.shifts, work.meId, week);
  const after = give && get ? loadAfterSwap(work.shifts, work.meId, give, get, week) : null;
  const restOk = !after || after.minRestH === null || after.minRestH >= MIN_REST_H;
  const rest = (v: number | null) => (v === null ? '–' : `${Math.round(v)} ${t('work.h')}`);

  const toMe = !!swap && swap.state === 'offered' && swap.to === work.meId;
  const leadMe = !!swap && swap.state === 'accepted' && swap.lead === work.meId;
  const done = (s: 'accepted' | 'declined' | 'approved') => {
    if (swap) sendSwapAck(chatId, swap.id, s);
    onClose();
  };
  const steps = swap
    ? [
        { on: true, label: t('work.offered', { name: nameOf(swap.from) }), hint: swap.at },
        { on: swap.state !== 'offered' && swap.state !== 'declined', label: swap.to === work.meId ? t('work.youAccept') : t('work.accepts', { name: nameOf(swap.to) }) },
        ...(swap.lead ? [{ on: swap.state === 'applied', label: t('work.approves', { name: nameOf(swap.lead) }) }] : []),
        { on: swap.state === 'applied', label: t('work.rosterUpdates') },
      ]
    : [];

  return (
    <FormSheet
      open
      onClose={onClose}
      title={other ? t('work.swapWith', { name: nameOf(other) }) : t('work.offerSwap')}
      testId="work-swap-sheet"
      footer={
        swap ? (
          toMe || leadMe ? (
            <div className="flex flex-col gap-2">
              <PrimaryButton onClick={() => done(toMe ? 'accepted' : 'approved')}>{toMe ? t('work.accept') : t('work.approve')}</PrimaryButton>
              <button type="button" onClick={() => done('declined')} className="w-full h-12 min-h-0 rounded-control text-headline text-ink-2 active:bg-raised">{t('work.decline')}</button>
            </div>
          ) : (
            <p data-work-state={swap.state} className="text-callout text-ink-3 text-center">{t(`work.swapState.${swap.state}` as never)}</p>
          )
        ) : (
          <PrimaryButton
            disabled={!give || !get}
            onClick={() => {
              if (!give || !get) return;
              sendSwap(chatId, give, get, nameOf(get.who));
              onClose();
            }}
          >
            {t('work.offerSwap')}
          </PrimaryButton>
        )
      }
    >
      <div className="px-4 pb-4">
        {mine && (
          <div role="radiogroup" aria-label={t('work.swapPick')} className="mb-4">
            <span className="block text-footnote text-ink-2 mb-1">{t('work.swapPick')}</span>
            {rows.length === 0 && <p data-work-empty className="py-3 text-body text-ink-3">{t('work.noSwapTargets')}</p>}
            {rows.map((s) => (
              <button key={s.id} type="button" role="radio" aria-checked={target === s.id} onClick={() => setTarget(s.id)} className={`w-full min-h-12 min-w-0 px-3 flex items-center gap-3 text-left rounded-control ${target === s.id ? 'bg-accent-soft' : ''}`}>
                <span className="w-20 text-callout text-ink-2 tnum">{s.day.slice(5)}</span>
                <span className="w-28 text-callout text-ink tnum">{s.from}–{s.to}</span>
                <span className="flex-1 truncate text-body text-ink">{nameOf(s.who)}</span>
              </button>
            ))}
          </div>
        )}
        {give && get && (
          <>
            <div className="flex gap-3 mb-3">
              <Tile label={t('work.youTake')} s={get} />
              <Tile label={t('work.heTakes', { name: nameOf(other) })} s={give} />
            </div>
            <Check1 ok label={t('work.hoursWeek')} value={`${before.hours} ${t('work.h')} → ${after?.hours ?? before.hours} ${t('work.h')}`} />
            <Check1 ok={restOk} label={t('work.rest')} value={`${rest(after?.minRestH ?? null)} · ${t('work.min')} ${MIN_REST_H}`} />
          </>
        )}
        {steps.length > 0 && (
          <ol className="mt-4 flex flex-col gap-3" aria-label={t('work.steps')}>
            {steps.map((s, i) => (
              <li key={i} data-work-step={s.on ? 'done' : 'todo'} className="flex items-start gap-3">
                <span className={`mt-0.5 w-5 h-5 shrink-0 rounded-full flex items-center justify-center ${s.on ? 'bg-success text-on-accent' : 'border border-line-strong'}`}>
                  {s.on && <Check className="w-3.5 h-3.5" strokeWidth={2.5} aria-hidden />}
                </span>
                <span className="text-body text-ink">{s.label}{'hint' in s && s.hint ? <span className="block text-footnote text-ink-3 tnum">{s.hint}</span> : null}</span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </FormSheet>
  );
};
