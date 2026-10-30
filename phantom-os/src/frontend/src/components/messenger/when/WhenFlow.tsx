/**
 * «Коли» — стопка вікон одного аркуша (власник 28.10: «та смужка з фіксованим часом»):
 * швидкий вибір → дата (місяць) → час (власне колесо, крок 5 хвилин), плюс повтор
 * і «коли буде в мережі». Назад — одне вікно; вибране не губиться між вікнами.
 * Системних полів дати й часу нема: вони малювали російські й Holo-діалоги.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CalendarDays, ChevronLeft, ChevronRight, Clock, Moon, Repeat as RepeatIcon, Sun, Radio } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { haptic } from '../../../utils/haptic';
import { PrimaryButton } from '../FormSheet';
import {
  ceilStep,
  dayKey,
  dayLabel,
  hhmm,
  monthGrid,
  monthTitle,
  parseDay,
  quickPicks,
  REPEATS,
  slotMs,
  utcOffset,
  weekdayHeads,
  weekdayLong,
  type QuickId,
  type Repeat,
  type Slot,
} from './whenModel';

export type WhenResult = { kind: 'at'; date: string; time: string; repeat: Repeat } | { kind: 'online' };
export type WhenWindow = 'quick' | 'date' | 'time' | 'repeat';

export interface WhenFlowOptions {
  onPick: (r: WhenResult) => void;
  /** Імʼя людини для «Коли … буде в мережі»; без нього рядка нема (група, «Збережене», ПК). */
  onlineName?: string | null;
  repeatable?: boolean;
  /** Швидкі рядки неактивні: ще нема що надсилати. */
  disabled?: boolean;
  initial?: Partial<Slot> & { repeat?: Repeat };
  /** Напис головної дії вікна «Час»: 'when.sendAt' або 'when.moveTo'. */
  primaryKey: MessageKey;
  now?: () => Date;
  /** Розклад змін: минулі дні й години не вимикаються. */
  allowPast?: boolean;
  /** Лише день (перехід до дати в розмові): дотик на день одразу віддає вибір; дні поза межами вимкнено. */
  dayOnly?: { min?: string; max?: string };
  daily?: boolean;
}

const QUICK_KEY: Record<QuickId, MessageKey> = {
  inHour: 'when.inHour',
  tonight: 'when.tonight',
  todayMorning: 'when.todayMorning',
  tomorrowMorning: 'when.tomorrowMorning',
  saturday: 'when.saturday',
  monday: 'when.monday',
};
const QUICK_ICON: Record<QuickId, React.ReactNode> = {
  inHour: <Clock className="w-5 h-5" strokeWidth={1.75} />,
  tonight: <Moon className="w-5 h-5" strokeWidth={1.75} />,
  todayMorning: <Sun className="w-5 h-5" strokeWidth={1.75} />,
  tomorrowMorning: <Sun className="w-5 h-5" strokeWidth={1.75} />,
  saturday: <CalendarDays className="w-5 h-5" strokeWidth={1.75} />,
  monday: <CalendarDays className="w-5 h-5" strokeWidth={1.75} />,
};
const REPEAT_KEY: Record<Exclude<Repeat, ''>, MessageKey> = {
  daily: 'when.repeatDaily',
  weekly: 'when.repeatWeekly',
  monthly: 'when.repeatMonthly',
  weekdays: 'when.repeatWeekdays',
};
const REPEAT_HINT: Record<Exclude<Repeat, ''>, MessageKey> = {
  daily: 'when.repeatDailyHint',
  weekly: 'when.repeatWeeklyHint',
  monthly: 'when.repeatMonthlyHint',
  weekdays: 'when.repeatWeekdaysHint',
};

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTES = Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0'));
const ITEM = 44;

const Lead: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">{children}</span>
);

const Row: React.FC<{
  icon: React.ReactNode;
  label: string;
  hint?: string;
  trailing?: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  testId?: string;
}> = ({ icon, label, hint, trailing, onClick, disabled, testId }) => (
  <button
    type="button"
    data-testid={testId}
    disabled={disabled}
    onClick={onClick}
    className="w-full min-h-14 px-4 py-1.5 flex items-center gap-3.5 text-left rounded-control hover:bg-overlay/60 active:bg-overlay disabled:opacity-40 transition-colors duration-quick ease-standard"
  >
    <Lead>{icon}</Lead>
    <span className="flex-1 min-w-0">
      <span className="block text-headline text-ink truncate">{label}</span>
      {hint && <span className="block text-footnote text-ink-3 truncate">{hint}</span>}
    </span>
    {trailing}
  </button>
);

/** Колесо з прилипанням: гортається пальцем, тиснеться рядок; вибраний — у смузі посередині. */
const Wheel: React.FC<{ label: string; values: string[]; index: number; onIndex: (i: number) => void; off?: (i: number) => boolean }> = ({
  label,
  values,
  index,
  onIndex,
  off,
}) => {
  const ref = useRef<HTMLDivElement>(null);
  const settling = useRef<number | null>(null);
  const placed = useRef(false);
  // Scroll events of our own glide must not count as picks, or 20:00 lands on whatever hour passes by first.
  const target = useRef<number | null>(null);
  useEffect(() => {
    const el = ref.current;
    const first = !placed.current;
    placed.current = true;
    if (!el || Math.round(el.scrollTop / ITEM) === index) return;
    target.current = index;
    if (!first && typeof el.scrollTo === 'function') el.scrollTo({ top: index * ITEM, behavior: 'smooth' });
    else el.scrollTop = index * ITEM;
  }, [index]);
  const onScroll = () => {
    const el = ref.current;
    if (!el) return;
    if (settling.current !== null) cancelAnimationFrame(settling.current);
    settling.current = requestAnimationFrame(() => {
      const i = Math.max(0, Math.min(values.length - 1, Math.round(el.scrollTop / ITEM)));
      if (target.current !== null) {
        if (i === target.current) target.current = null;
        return;
      }
      if (i !== index) {
        haptic('tap');
        onIndex(i);
      }
    });
  };
  return (
    <div
      ref={ref}
      role="listbox"
      aria-label={label}
      onScroll={onScroll}
      onPointerDown={() => (target.current = null)}
      onTouchStart={() => (target.current = null)}
      onWheel={() => (target.current = null)}
      className="relative w-24 overflow-y-auto no-scrollbar snap-y snap-mandatory"
      style={{ height: ITEM * 5, paddingTop: ITEM * 2, paddingBottom: ITEM * 2 }}
    >
      {values.map((v, i) => (
        <button
          key={v}
          type="button"
          role="option"
          aria-selected={i === index}
          aria-disabled={off?.(i) || undefined}
          onClick={() => {
            haptic('tap');
            onIndex(i);
          }}
          className={`w-full snap-center flex items-center justify-center tabular-nums transition-colors duration-quick ${
            i === index ? 'text-title-1 text-ink font-semibold' : 'text-title-3 text-ink-3'
          } ${off?.(i) ? 'opacity-40' : ''}`}
          style={{ height: ITEM }}
        >
          {v}
        </button>
      ))}
    </div>
  );
};

export interface WhenFlow {
  win: WhenWindow;
  /** Заголовок не-швидкого вікна; швидке називає власник аркуша. */
  title: string;
  /** Крок назад усередині; undefined — уже на швидкому вікні. */
  back?: () => void;
  quick: React.ReactNode;
  body: React.ReactNode;
  footer?: React.ReactNode;
  reset: (init?: Partial<Slot> & { repeat?: Repeat }) => void;
  /** Відкрити одне вікно окремо (напр. лише час «до»): назад з нього — до власника аркуша. */
  open: (w: WhenWindow, init?: Partial<Slot>) => void;
}

export function useWhenFlow(o: WhenFlowOptions): WhenFlow {
  const { t, locale } = useTranslation();
  const nowFn = o.now ?? (() => new Date());
  const now = nowFn();
  const [win, setWin] = useState<WhenWindow>('quick');
  const [solo, setSolo] = useState(false);
  const [dir, setDir] = useState<'fwd' | 'back'>('fwd');
  const [date, setDate] = useState<string | null>(o.initial?.date ?? null);
  const [time, setTime] = useState<string | null>(o.initial?.time ?? null);
  const [repeat, setRepeat] = useState<Repeat>(o.initial?.repeat ?? '');
  const startDay = parseDay(o.initial?.date ?? '') ?? now;
  const [view, setView] = useState({ y: startDay.getFullYear(), m: startDay.getMonth() });
  const pickRef = useRef(o.onPick);
  pickRef.current = o.onPick;

  const go = useCallback((w: WhenWindow, d: 'fwd' | 'back' = 'fwd') => {
    setDir(d);
    setWin(w);
  }, []);
  const back = win === 'quick' || solo ? undefined : () => go(win === 'time' ? 'date' : 'quick', 'back');
  const open = useCallback((w: WhenWindow, init?: Partial<Slot>) => {
    if (init?.date !== undefined) setDate(init.date);
    if (init?.time !== undefined) setTime(init.time);
    const d = parseDay(init?.date ?? '');
    if (d) setView({ y: d.getFullYear(), m: d.getMonth() });
    setSolo(true);
    setDir('fwd');
    setWin(w);
  }, []);
  const reset = useCallback((init?: Partial<Slot> & { repeat?: Repeat }) => {
    const from = init ?? o.initial;
    setWin('quick');
    setSolo(false);
    setDir('fwd');
    setDate(from?.date ?? null);
    setTime(from?.time ?? null);
    setRepeat(from?.repeat ?? '');
    const d = parseDay(from?.date ?? '');
    if (d) setView({ y: d.getFullYear(), m: d.getMonth() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [o.initial?.date, o.initial?.time, o.initial?.repeat]);

  const words = { today: t('when.today'), tomorrow: t('when.tomorrow') };
  const today = dayKey(now);
  const picks = useMemo(() => quickPicks(now), [Math.floor(now.getTime() / 60_000)]); // eslint-disable-line react-hooks/exhaustive-deps

  const repeatHint = (r: Exclude<Repeat, ''>): string | undefined => {
    if (!date || !time) return undefined;
    const d = parseDay(date)!;
    return t(REPEAT_HINT[r], { time, weekday: weekdayLong(date, locale), day: d.getDate() });
  };

  const quick = (
    <div data-testid="when-quick" className={`px-2 ${dir === 'back' ? 'f-win-back' : ''}`}>
      {picks.map((p) => (
        <Row
          key={p.id}
          testId={`when-quick-${p.id}`}
          icon={QUICK_ICON[p.id]}
          label={t(QUICK_KEY[p.id])}
          hint={p.id === 'inHour' || p.id === 'tonight' || p.id === 'todayMorning' ? weekdayLong(p.date, locale) : dayLabel(p.date, now, locale, words)}
          trailing={<span className="text-headline text-ink tabular-nums">{p.time}</span>}
          disabled={o.disabled}
          onClick={() => {
            haptic('confirm');
            pickRef.current({ kind: 'at', date: p.date, time: p.time, repeat });
          }}
        />
      ))}
      {o.onlineName && (
        <Row
          testId="when-quick-online"
          icon={<Radio className="w-5 h-5" strokeWidth={1.75} />}
          label={t('when.online', { name: o.onlineName })}
          hint={t('when.onlineHint')}
          disabled={o.disabled}
          onClick={() => {
            haptic('confirm');
            pickRef.current({ kind: 'online' });
          }}
        />
      )}
      <div aria-hidden="true" className="h-px bg-line mx-2 my-1.5" />
      <Row
        testId="when-pick"
        icon={<CalendarDays className="w-5 h-5" strokeWidth={1.75} />}
        label={t('when.pick')}
        trailing={<ChevronRight className="w-5 h-5 text-ink-3" strokeWidth={1.75} />}
        onClick={() => {
          haptic('tap');
          go('date');
        }}
      />
      {o.repeatable && (
        <Row
          testId="when-repeat"
          icon={<RepeatIcon className="w-5 h-5" strokeWidth={1.75} />}
          label={t('when.repeat')}
          hint={repeat ? t(REPEAT_KEY[repeat]) : t('when.repeatNever')}
          trailing={<ChevronRight className="w-5 h-5 text-ink-3" strokeWidth={1.75} />}
          onClick={() => {
            haptic('tap');
            go('repeat');
          }}
        />
      )}
    </div>
  );

  const anim = dir === 'fwd' ? 'f-win-fwd' : 'f-win-back';
  const past0 = !o.allowPast;
  const lo = o.dayOnly?.min;
  const hi0 = o.dayOnly?.max;
  const ym = (k: string) => k.slice(0, 7);
  const viewYm = dayKey(new Date(view.y, view.m, 1)).slice(0, 7);
  const canPrev = (!past0 || view.y > now.getFullYear() || (view.y === now.getFullYear() && view.m > now.getMonth())) && (!lo || viewYm > ym(lo));
  const canNext = !hi0 || viewYm < ym(hi0);
  const shiftMonth = (n: number) => {
    if (n < 0 && !canPrev) return;
    if (n > 0 && !canNext) return;
    haptic('tap');
    setView((v) => {
      const d = new Date(v.y, v.m + n, 1);
      return { y: d.getFullYear(), m: d.getMonth() };
    });
  };
  const swipeX = useRef<number | null>(null);

  let body: React.ReactNode = null;
  let footer: React.ReactNode = undefined;
  let title = '';

  if (win === 'date') {
    title = t('when.date');
    const heads = weekdayHeads(locale);
    body = (
      <div key="date" data-testid="when-date" className={`px-3 pb-3 ${anim}`}>
        <div className="flex items-center pl-2 pr-0 pb-1">
          <span className="flex-1 text-title-2 text-ink" data-testid="when-month">
            {monthTitle(view.y, view.m, locale)}
          </span>
          <button type="button" aria-label={t('when.prevMonth')} disabled={!canPrev} onClick={() => shiftMonth(-1)} className="w-11 h-11 rounded-full flex items-center justify-center text-ink-2 disabled:opacity-30 active:bg-overlay">
            <ChevronLeft className="w-5 h-5" strokeWidth={1.75} />
          </button>
          <button type="button" aria-label={t('when.nextMonth')} disabled={!canNext} onClick={() => shiftMonth(1)} className="w-11 h-11 rounded-full flex items-center justify-center text-ink-2 disabled:opacity-30 active:bg-overlay">
            <ChevronRight className="w-5 h-5" strokeWidth={1.75} />
          </button>
        </div>
        <div className="grid grid-cols-7 text-center text-caption text-ink-3 h-7 items-center">
          {heads.map((h) => (
            <span key={h}>{h}</span>
          ))}
        </div>
        <div
          role="grid"
          className="grid grid-cols-7 gap-y-1"
          onTouchStart={(e) => (swipeX.current = e.touches[0]?.clientX ?? null)}
          onTouchEnd={(e) => {
            const from = swipeX.current;
            const to = e.changedTouches[0]?.clientX;
            swipeX.current = null;
            if (from == null || to == null || Math.abs(to - from) < 48) return;
            shiftMonth(to < from ? 1 : -1);
          }}
        >
          {monthGrid(view.y, view.m)
            .flat()
            .map((d, i) => {
              if (d === null) return <span key={`x${i}`} />;
              const key = dayKey(new Date(view.y, view.m, d));
              const past = (past0 && key < today) || (!!lo && key < lo) || (!!hi0 && key > hi0);
              const on = key === date;
              return (
                <button
                  key={key}
                  type="button"
                  data-day={key}
                  disabled={past}
                  aria-pressed={on}
                  aria-current={key === today ? 'date' : undefined}
                  onClick={() => {
                    haptic('tap');
                    setDate(key);
                    if (o.dayOnly) {
                      pickRef.current({ kind: 'at', date: key, time: time ?? '00:00', repeat: '' });
                      return;
                    }
                    if (!time || (past0 && slotMs({ date: key, time })! <= now.getTime())) {
                      setTime(key === today ? hhmm(ceilStep(new Date(now.getTime() + 60 * 60_000))) : '09:00');
                    }
                    go('time');
                  }}
                  className="h-11 flex items-center justify-center disabled:opacity-40"
                >
                  <span
                    className={`w-10 h-10 rounded-full flex items-center justify-center text-body tabular-nums ${
                      on ? 'bg-ink text-canvas font-semibold' : key === today ? 'border border-line-strong font-semibold text-ink' : 'text-ink'
                    }`}
                  >
                    {d}
                  </span>
                </button>
              );
            })}
        </div>
      </div>
    );
  } else if (win === 'time' && date) {
    title = t('when.time');
    const cur = time ?? '09:00';
    const [h, m] = cur.split(':').map(Number);
    const hi = h;
    const mi = Math.floor(m / 5);
    const isToday = past0 && date === today;
    const ms = slotMs({ date, time: cur })!;
    const past = past0 && ms <= now.getTime();
    const label = o.daily ? cur : `${dayLabel(date, now, locale, words)}, ${cur}`;
    body = (
      <div key="time" data-testid="when-time" className={`px-4 pb-2 ${anim}`}>
        {!o.daily && <p className="text-subhead text-ink-2 pb-2">{dayLabel(date, now, locale, words)}</p>}
        <div className="relative flex justify-center">
          <span aria-hidden="true" className="absolute inset-x-0 rounded-control bg-raised" style={{ top: ITEM * 2, height: ITEM }} />
          <Wheel
            label={t('when.hours')}
            values={HOURS}
            index={hi}
            off={(i) => isToday && i < now.getHours()}
            onIndex={(i) => setTime(`${HOURS[i]}:${MINUTES[mi]}`)}
          />
          <span aria-hidden="true" className="relative w-4 flex items-center justify-center text-title-1 text-ink font-semibold">
            :
          </span>
          <Wheel
            label={t('when.minutes')}
            values={MINUTES}
            index={mi}
            off={(i) => isToday && hi === now.getHours() && i * 5 <= now.getMinutes()}
            onIndex={(i) => setTime(`${HOURS[hi]}:${MINUTES[i]}`)}
          />
        </div>
        <p className={`pt-3 text-footnote ${past ? 'text-danger' : 'text-ink-3'}`} data-testid="when-time-note">
          {past ? t('when.past') : t('when.tz', { offset: utcOffset(date) })}
        </p>
      </div>
    );
    footer = (
      <PrimaryButton
        disabled={past}
        onClick={() => {
          if (past) return;
          haptic('confirm');
          pickRef.current({ kind: 'at', date, time: cur, repeat });
        }}
      >
        {t(o.primaryKey, { when: label })}
      </PrimaryButton>
    );
  } else if (win === 'repeat') {
    title = t('when.repeat');
    const opts: Repeat[] = ['', ...REPEATS];
    body = (
      <div key="repeat" role="radiogroup" aria-label={t('when.repeat')} data-testid="when-repeat-list" className={anim}>
        {opts.map((r) => (
          <button
            key={r || 'never'}
            type="button"
            role="radio"
            aria-checked={repeat === r}
            onClick={() => {
              haptic('tap');
              setRepeat(r);
              go('quick', 'back');
            }}
            className="w-full min-h-14 px-4 py-1.5 flex items-center gap-4 text-left active:bg-overlay"
          >
            <span aria-hidden="true" className={`w-[22px] h-[22px] shrink-0 rounded-full border-line-strong ${repeat === r ? 'border-[7px] border-ink' : 'border-2'}`} />
            <span className="flex-1 min-w-0">
              <span className="block text-headline text-ink">{r ? t(REPEAT_KEY[r]) : t('when.repeatNever')}</span>
              {r && repeatHint(r) && <span className="block text-footnote text-ink-3">{repeatHint(r)}</span>}
            </span>
          </button>
        ))}
      </div>
    );
  }

  return { win, title, back, quick, body, footer, reset, open };
}
