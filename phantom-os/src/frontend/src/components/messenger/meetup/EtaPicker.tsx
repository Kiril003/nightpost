/**
 * «Коли буду» — час прибуття на зустріч. Кілька готових варіантів біля часу
 * зустрічі й свій час; вибір стає позначкою `eta:<хвилини Unix>` на картці.
 * Свій час — колеса годин зі спільних вікон «Коли» (msg-x), без системного поля.
 */
import React, { useState } from 'react';
import { Clock } from 'lucide-react';
import { FormSheet } from '../FormSheet';
import { hhmm } from './words';
import { useTranslation } from '../../../i18n/useTranslation';
import { useWhenFlow } from '../when/WhenFlow';
import { WhenRow } from '../when/WhenRow';
import { dayKey } from '../when/whenModel';

const OFFSETS = [-10, 0, 10, 20, 30, 45];

/** «ГГ:ХХ» біля зустрічі → мс; після півночі — наступна доба, а не ранок дня зустрічі. */
export function etaMs(at: number, time: string): number | null {
  const m = /^(\d{2}):(\d{2})$/.exec(time);
  if (!m) return null;
  const d = new Date(at);
  const t = new Date(d.getFullYear(), d.getMonth(), d.getDate(), Number(m[1]), Number(m[2])).getTime();
  return t < at - 12 * 3600_000 ? t + 86_400_000 : t;
}

export const EtaPicker: React.FC<{
  open: boolean;
  /** Час зустрічі, мс. */
  at: number;
  /** Моє поточне прибуття, мс, якщо вже ставив. */
  current: number | null;
  onPick: (ms: number) => void;
  onClose: () => void;
}> = ({ open, at, current, onPick, onClose }) => {
  const { t } = useTranslation();
  const [own, setOwn] = useState(false);
  const pick = (ms: number) => {
    onPick(ms);
    onClose();
  };
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    allowPast: true,
    onPick: (r) => {
      setOwn(false);
      flow.reset();
      if (r.kind !== 'at') return;
      const ms = etaMs(at, r.time);
      if (ms !== null) pick(ms);
    },
  });
  if (own) {
    return (
      <FormSheet open={open} onClose={onClose} onBack={flow.back ?? (() => setOwn(false))} title={t('etaPicker.ownTime')} testId="eta-picker" footer={flow.footer}>
        <div data-eta-own-flow>{flow.body}</div>
      </FormSheet>
    );
  }
  const preset = current !== null && OFFSETS.some((off) => Math.floor(current / 60_000) === Math.floor((at + off * 60_000) / 60_000));
  return (
    <FormSheet open={open} onClose={onClose} title={t('etaPicker.title')} testId="eta-picker">
      <p className="px-4 pb-3 text-footnote text-ink-3">{t('etaPicker.meetingAt', { time: hhmm(at) })}</p>
      <div className="px-4 grid grid-cols-3 gap-2">
        {OFFSETS.map((off) => {
          const ms = at + off * 60_000;
          const on = current !== null && Math.floor(current / 60_000) === Math.floor(ms / 60_000);
          return (
            <button
              key={off}
              type="button"
              data-testid={`eta-option-${off}`}
              aria-pressed={on}
              onClick={() => pick(ms)}
              className={`h-12 min-h-0 rounded-control text-callout tnum transition-colors duration-instant ${
                on ? 'bg-accent-soft text-ink font-semibold' : 'bg-surface text-ink active:bg-overlay'
              }`}
            >
              {hhmm(ms)}
            </button>
          );
        })}
      </div>
      <div className="px-4 pt-3 pb-4">
        <WhenRow
          testId="eta-own"
          icon={<Clock className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
          label={t('etaPicker.ownTime')}
          value={current !== null && !preset ? hhmm(current) : t('etaPicker.pickTime')}
          empty={current === null || preset}
          onClick={() => {
            flow.open('time', { date: dayKey(new Date(at)), time: hhmm(current ?? at) });
            setOwn(true);
          }}
        />
      </div>
    </FormSheet>
  );
};
