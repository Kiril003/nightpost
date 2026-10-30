import React, { useEffect, useState } from 'react';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { useTranslation } from '../../../i18n/useTranslation';
import { dayWord, hhmm } from './words';

const DAYS = [0, 1, 2, 3, 4, 5, 6] as const;

const TIMES: string[] = [];
for (let h = 8; h <= 23; h++) {
  const hh = String(h).padStart(2, '0');
  TIMES.push(`${hh}:00`, `${hh}:30`);
}

const capital = (s: string) => s.charAt(0).toLocaleUpperCase() + s.slice(1);

const dayAt = (baseMs: number, offsetDays: number): number => {
  const d = new Date(baseMs);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + offsetDays).getTime();
};

const slotMs = (baseMs: number, offsetDays: number, hm: string): number | null => {
  const m = /^(\d{2}):(\d{2})$/.exec(hm);
  if (!m) return null;
  const d = new Date(baseMs);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + offsetDays, Number(m[1]), Number(m[2]), 0, 0).getTime();
};

const dayIndexOf = (targetMs: number, nowMs: number): number => {
  const diff = Math.round((dayAt(targetMs, 0) - dayAt(nowMs, 0)) / 86_400_000);
  return diff >= 0 && diff <= 6 ? diff : 0;
};

export const PlanMoveSheet: React.FC<{
  open: boolean;
  at: number;
  onPick: (ms: number) => void;
  onClose: () => void;
}> = ({ open, at, onPick, onClose }) => {
  const { t } = useTranslation();
  const now = Date.now();
  const [day, setDay] = useState(() => dayIndexOf(at, now));
  const [time, setTime] = useState(() => hhmm(at));

  useEffect(() => {
    if (!open) return;
    const cur = Date.now();
    setDay(dayIndexOf(at, cur));
    setTime(hhmm(at));
  }, [open, at]);

  const pickedMs = slotMs(now, day, time);
  const canSave = pickedMs !== null && pickedMs > now && Math.floor(pickedMs / 60_000) !== Math.floor(at / 60_000);

  const save = () => {
    if (!canSave || pickedMs === null) return;
    onPick(pickedMs);
    onClose();
  };

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      title={t('meetupOrganiser.move')}
      testId="plan-move"
      footer={
        <div data-testid="plan-move-save">
          <PrimaryButton onClick={save} disabled={!canSave}>
            {t('meetupOrganiser.move')}
          </PrimaryButton>
        </div>
      }
    >
      <div className="px-4 pt-2 pb-3 flex gap-2 overflow-x-auto no-scrollbar">
        {DAYS.map((i) => {
          const on = day === i;
          return (
            <button
              key={i}
              type="button"
              data-testid={`plan-move-day-${i}`}
              aria-pressed={on}
              onClick={() => setDay(i)}
              className={`touch-floor-inline h-10 px-4 shrink-0 rounded-full text-callout transition-colors duration-instant ${
                on ? 'bg-accent text-on-accent font-semibold' : 'bg-surface text-ink active:bg-overlay'
              }`}
            >
              {capital(dayWord(dayAt(now, i), now))}
            </button>
          );
        })}
      </div>
      <div className="px-4 pb-2 grid grid-cols-4 gap-2">
        {TIMES.map((tm) => {
          const ms = slotMs(now, day, tm) ?? 0;
          const past = ms <= now;
          const on = time === tm && !past;
          return (
            <button
              key={tm}
              type="button"
              data-testid={`plan-move-time-${tm}`}
              aria-pressed={on}
              disabled={past}
              onClick={() => {
                if (!past) setTime(tm);
              }}
              className={`touch-floor-inline h-10 rounded-full text-callout tnum transition-colors duration-instant disabled:opacity-40 ${
                on ? 'bg-accent text-on-accent font-semibold' : 'bg-surface text-ink active:bg-overlay'
              }`}
            >
              {tm}
            </button>
          );
        })}
      </div>
    </FormSheet>
  );
};
