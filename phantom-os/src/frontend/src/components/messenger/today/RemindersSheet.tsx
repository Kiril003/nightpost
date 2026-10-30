import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Bell, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import {
  cancelPhoneReminder,
  listPhoneReminders,
  snoozePhoneReminder,
  type PhoneReminder,
} from '../../../services/dayApi';
import {
  cancelReminder,
  didntMeet,
  readLetterReminders,
  type LetterReminder,
} from '../../../services/letterReminders';
import { dateTag } from '../../../i18n/dateTag';
import { REMINDERS_CHANGED, REMINDERS_OPEN, takeRemindersOpen } from '../../../services/remindersBus';
import type { MessageKey } from '../../../i18n/messages/uk';
import { FormSheet } from '../FormSheet';
import { useWhenFlow } from '../when/WhenFlow';
import { slotMs, toSlot } from '../when/whenModel';

export interface RemindersSheetProps {
  items: PhoneReminder[];
  onSnooze: (id: string, minutes?: number) => void | Promise<void>;
  onCancel: (id: string) => void | Promise<void>;
  onClose: () => void;
  nearItems?: LetterReminder[];
  onDidntMeet?: (id: string) => void | Promise<void>;
  onCancelNear?: (id: string) => void | Promise<void>;
}

export function formatReminderTime(atMs: number, locale: string, nowMs: number = Date.now()): string {
  const target = new Date(atMs);
  const now = new Date(nowMs);
  const sameDay =
    target.getFullYear() === now.getFullYear() &&
    target.getMonth() === now.getMonth() &&
    target.getDate() === now.getDate();
  const loc = dateTag(locale);
  const timeStr = new Intl.DateTimeFormat(loc, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(target);
  if (sameDay) return timeStr;
  const dayStr = new Intl.DateTimeFormat(loc, {
    weekday: 'short',
    day: 'numeric',
  }).format(target);
  return `${dayStr} · ${timeStr}`;
}

export interface SnoozeChoice {
  key: string;
  minutes: number;
  at: number;
}

/** Варіанти відкладення від `nowMs`: +10 хв, +1 год, сьогодні ввечері (21:00, якщо ще не пізно), завтра 09:00. */
export function snoozeChoices(nowMs: number): SnoozeChoice[] {
  const out: SnoozeChoice[] = [
    { key: 'm10', minutes: 10, at: nowMs + 10 * 60_000 },
    { key: 'h1', minutes: 60, at: nowMs + 60 * 60_000 },
  ];
  const tonight = new Date(nowMs);
  tonight.setHours(21, 0, 0, 0);
  if (tonight.getTime() - nowMs > 90 * 60_000) {
    out.push({ key: 'tonight', minutes: Math.round((tonight.getTime() - nowMs) / 60_000), at: tonight.getTime() });
  }
  const tomorrow = new Date(nowMs);
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(9, 0, 0, 0);
  out.push({ key: 'tomorrow', minutes: Math.round((tomorrow.getTime() - nowMs) / 60_000), at: tomorrow.getTime() });
  return out;
}

const dayKey = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};

const SnoozeSheet: React.FC<{ item: PhoneReminder; locale: string; onPick: (minutes: number) => void; onClose: () => void }> = ({ item, locale, onPick, onClose }) => {
  const { t } = useTranslation();
  const [now] = useState(() => Date.now());
  const [other, setOther] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  const choices = [...snoozeChoices(now), ...(other !== null ? [{ key: 'other', minutes: Math.max(1, Math.round((other - now) / 60_000)), at: other }] : [])];
  const [sel, setSel] = useState('h1');
  const chosen = choices.find((c) => c.key === sel) ?? choices[0];
  const hhmm = (ms: number) => formatReminderTime(ms, locale, ms);
  // «Інший час» — спільні вікна «Коли» (msg-x): місяць і колеса, без системних полів.
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    now: () => new Date(now),
    onPick: (r) => {
      if (r.kind === 'at') {
        const ms = slotMs(r);
        if (ms !== null && ms > now) {
          setOther(ms);
          setSel('other');
        }
      }
      setPicking(false);
      flow.reset();
    },
  });
  if (picking) {
    return (
      <FormSheet open onClose={onClose} onBack={flow.back ?? (() => setPicking(false))} title={flow.win === 'quick' ? t('day.snoozeBtn') : flow.title} testId="snooze-sheet" footer={flow.footer}>
        <div data-snooze-flow>{flow.win === 'quick' ? flow.quick : flow.body}</div>
      </FormSheet>
    );
  }
  return (
    <FormSheet open onClose={onClose} title={t('day.snoozeBtn')} testId="snooze-sheet">
      <p className="px-4 pb-2 text-footnote text-ink-2 truncate">{item.text}</p>
      <ul>
        {choices.map((c) => (
          <li key={c.key}>
            <button
              type="button"
              role="radio"
              aria-checked={sel === c.key}
              data-snooze-choice={c.key}
              onClick={() => setSel(c.key)}
              className="w-full min-h-14 px-4 flex items-center gap-3 text-left active:bg-overlay"
            >
              <span className="min-w-0 flex-1 flex flex-col">
                <span className="text-body text-ink">{t(`day.snoozeOpt.${c.key}` as MessageKey)}</span>
                <span className="text-footnote text-ink-2 tnum">{t('day.until', { time: new Intl.DateTimeFormat(dateTag(locale), { hour: '2-digit', minute: '2-digit', hour12: false, ...(dayKey(c.at) === dayKey(now) ? {} : { weekday: 'short' }) }).format(c.at) })}</span>
              </span>
              <span aria-hidden className={`w-[22px] h-[22px] shrink-0 rounded-full ${sel === c.key ? 'border-[7px] border-ink' : 'border-2 border-line-strong'}`} />
            </button>
          </li>
        ))}
        <li>
          <button
            type="button"
            data-snooze-other
            onClick={() => {
              flow.reset(toSlot(new Date(chosen.at)));
              setPicking(true);
            }}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left text-body text-ink active:bg-overlay"
          >
            <span className="flex-1">{t('day.snoozeOpt.other')}</span>
            <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
          </button>
        </li>
      </ul>
      <div className="p-4 flex flex-col gap-2">
        <button type="button" data-snooze-go onClick={() => onPick(chosen.minutes)} className="min-h-12 rounded-full bg-accent text-subhead font-medium text-on-accent">
          {t('day.snoozeTo', { time: hhmm(chosen.at) })}
        </button>
        <button type="button" onClick={onClose} className="min-h-12 rounded-full text-subhead text-ink-2">
          {t('day.cancel')}
        </button>
      </div>
    </FormSheet>
  );
};

export const RemindersSheet: React.FC<RemindersSheetProps> = ({
  items,
  onSnooze,
  onCancel,
  onClose,
  nearItems = [],
  onDidntMeet,
  onCancelNear,
}) => {
  const { t, locale } = useTranslation();
  const [snoozing, setSnoozing] = useState<PhoneReminder | null>(null);
  const sorted = [...items].sort((a, b) => a.atMs - b.atMs);
  const nowMs = Date.now();
  const heading = (ms: number) => {
    if (ms <= nowMs || dayKey(ms) === dayKey(nowMs)) return t('day.groupToday');
    if (dayKey(ms) === dayKey(nowMs + 86_400_000)) return t('day.groupTomorrow');
    return new Intl.DateTimeFormat(dateTag(locale), { weekday: 'long', day: 'numeric' }).format(ms);
  };

  return (
    <>
    <FormSheet open onClose={onClose} title={t('day.remindersTitle')} testId="reminders-sheet">
      {items.length === 0 && nearItems.length === 0 ? (
        <p className="px-4 py-6 text-subhead text-ink-2">{t('day.empty')}</p>
      ) : (
        <ul className="divide-y divide-line">
          {sorted.map((item, i) => {
            const h = heading(item.atMs);
            const showHead = i === 0 || heading(sorted[i - 1].atMs) !== h;
            return (
              <React.Fragment key={item.id}>
                {showHead && (
                  <li data-reminder-group className="px-4 pt-3 pb-1 text-footnote text-ink-2 border-b-0">{h}</li>
                )}
                <li data-reminder-row className="px-4 py-2 flex flex-col gap-1 border-b-0">
                  <div className="flex items-center gap-3 min-h-12">
                    <span className="w-14 shrink-0 text-footnote text-ink-3 tnum">{formatReminderTime(item.atMs, locale).split(' · ').pop()}</span>
                    <span aria-hidden className="w-[3px] self-stretch rounded-sm bg-accent-line" />
                    <span className="min-w-0 flex-1 text-body text-ink break-words">{item.text}</span>
                  </div>
                  <div className="pl-[68px] flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setSnoozing(item)}
                      className="min-h-12 px-3 rounded-control text-subhead text-ink active:bg-overlay"
                    >
                      {t('day.snoozeBtn')}
                    </button>
                    <button
                      type="button"
                      onClick={() => void onCancel(item.id)}
                      className="min-h-12 px-3 rounded-control text-subhead text-ink-2 active:bg-overlay"
                    >
                      {t('day.cancel')}
                    </button>
                  </div>
                </li>
              </React.Fragment>
            );
          })}
          {nearItems.map((item) => (
            <li key={item.id} className="px-4 py-3 flex flex-col gap-2">
              <div className="flex items-baseline justify-between gap-3">
                <span className="min-w-0 flex-1 text-body text-ink break-words">{item.text}</span>
                <span className="shrink-0 text-footnote text-ink-2 tnum">
                  {t('chats14.onMeet')}
                </span>
              </div>
              <div className="flex items-center gap-2">
                {item.firedDay && (
                  <button
                    type="button"
                    data-testid="near-didnt-meet"
                    onClick={() => void onDidntMeet?.(item.id)}
                    className="min-h-12 px-3 rounded-control bg-surface text-subhead text-ink hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick"
                  >
                    {t('chats14.didntMeet')}
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => void onCancelNear?.(item.id)}
                  className="min-h-12 px-3 rounded-control bg-surface text-subhead text-ink-2 hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick"
                >
                  {t('day.cancel')}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </FormSheet>
    {snoozing && (
      <SnoozeSheet
        item={snoozing}
        locale={locale}
        onClose={() => setSnoozing(null)}
        onPick={(m) => {
          const id = snoozing.id;
          setSnoozing(null);
          void onSnooze(id, m);
        }}
      />
    )}
    </>
  );
};

const readNearReminders = (): LetterReminder[] => {
  try {
    return readLetterReminders().filter((r) => r.trigger === 'near');
  } catch {
    return [];
  }
};

export function useRemindersState(enabled: boolean = true, refreshKey?: string) {
  const [items, setItems] = useState<PhoneReminder[]>([]);
  const [nearItems, setNearItems] = useState<LetterReminder[]>(() => (enabled ? readNearReminders() : []));
  const seq = useRef(0);

  const load = useCallback(async () => {
    const mine = ++seq.current;
    setNearItems(readNearReminders());
    let next: PhoneReminder[] = [];
    try {
      next = await listPhoneReminders();
    } catch {
      next = [];
    }
    if (mine === seq.current) {
      setItems(Array.isArray(next) ? next : []);
      setNearItems(readNearReminders());
    }
  }, []);

  useEffect(() => {
    if (!enabled) return;
    void load();
  }, [enabled, load, refreshKey]);

  const handleSnooze = useCallback(
    async (id: string, minutes = 10) => {
      try {
        await snoozePhoneReminder(id, minutes);
      } catch {
        // міст нагадувань недоступний
      }
      await load();
    },
    [load],
  );

  const handleCancel = useCallback(
    async (id: string) => {
      try {
        await cancelPhoneReminder(id);
      } catch {
        // міст нагадувань недоступний
      }
      await load();
    },
    [load],
  );

  const handleDidntMeet = useCallback(
    (id: string) => {
      didntMeet(id);
      void load();
    },
    [load],
  );

  const handleCancelNear = useCallback(
    async (id: string) => {
      await cancelReminder({ where: 'page', id });
      await load();
    },
    [load],
  );

  return {
    items,
    nearItems,
    load,
    handleSnooze,
    handleCancel,
    handleDidntMeet,
    handleCancelNear,
  };
}

export interface RemindersSummary {
  total: number;
  overdue: string | null;
}

export const RemindersLine: React.FC<{ refreshKey?: string; line?: boolean; onSummary?: (s: RemindersSummary) => void }> = ({
  refreshKey,
  line = true,
  onSummary,
}) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const {
    items,
    nearItems,
    load,
    handleSnooze,
    handleCancel,
    handleDidntMeet,
    handleCancelNear,
  } = useRemindersState(true, refreshKey);

  useEffect(() => {
    if (takeRemindersOpen()) setOpen(true);
  }, [refreshKey]);

  useEffect(() => {
    const reload = () => void load();
    const onOpen = () => {
      if (takeRemindersOpen()) {
        void load();
        setOpen(true);
      }
    };
    const onVisibility = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'visible') reload();
    };
    window.addEventListener(REMINDERS_CHANGED, reload);
    window.addEventListener(REMINDERS_OPEN, onOpen);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener(REMINDERS_CHANGED, reload);
      window.removeEventListener(REMINDERS_OPEN, onOpen);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [load]);

  const total = items.length + nearItems.length;
  const overdue = items.filter((x) => x.atMs <= Date.now()).sort((a, b) => a.atMs - b.atMs)[0]?.text ?? null;
  const onSummaryRef = useRef(onSummary);
  onSummaryRef.current = onSummary;
  useEffect(() => {
    onSummaryRef.current?.({ total, overdue });
  }, [total, overdue]);

  if (total === 0 && !open) return null;

  return (
    <>
      {line && total > 0 && (
        <button
          type="button"
          data-testid="reminders-line"
          onClick={() => setOpen(true)}
          className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left bg-canvas hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick"
        >
          <span className="w-12 h-12 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
            <Bell className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </span>
          <span className="min-w-0 flex-1 truncate text-subhead text-ink">
            {t('day.reminders', { n: total })}
          </span>
        </button>
      )}
      {open && (
        <RemindersSheet
          items={items}
          nearItems={nearItems}
          onSnooze={handleSnooze}
          onCancel={handleCancel}
          onDidntMeet={handleDidntMeet}
          onCancelNear={handleCancelNear}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
};
