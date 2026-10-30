import React, { useState } from 'react';
import { CalendarClock, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { FormSheet } from '../FormSheet';
import { useWhenFlow } from '../when/WhenFlow';
import { dayKey, dayLabel, hhmm, slotMs, toSlot } from '../when/whenModel';

export interface WhenChoice {
  key: 'h1' | 'tonight' | 'tomorrow';
  at: number;
}

/** Швидкі «коли» від `nowMs`: через годину, сьогодні 21:00 (лише якщо ще далеко), завтра 09:00. */
export function whenChoices(nowMs: number): WhenChoice[] {
  const out: WhenChoice[] = [{ key: 'h1', at: nowMs + 3_600_000 }];
  const tonight = new Date(nowMs);
  tonight.setHours(21, 0, 0, 0);
  if (tonight.getTime() - nowMs > 90 * 60_000) out.push({ key: 'tonight', at: tonight.getTime() });
  const tomorrow = new Date(nowMs);
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(9, 0, 0, 0);
  out.push({ key: 'tomorrow', at: tomorrow.getTime() });
  return out;
}

/**
 * Нове нагадування: що + коли. «Коли» — ті самі вікна, що й «Надіслати пізніше» і зустріч
 * (msg-x 28.10): швидкі рядки від «зараз», місяць, колеса. Сирого `datetime-local` нема.
 */
export const CreateReminderSheet: React.FC<{
  nowMs: number;
  onClose: () => void;
  onSave: (text: string, atMs: number) => void | Promise<void>;
}> = ({ nowMs, onClose, onSave }) => {
  const { t, locale } = useTranslation();
  const choices = whenChoices(nowMs);
  const [text, setText] = useState('');
  const [at, setAt] = useState<number>(choices[choices.length - 1].at);
  const [picking, setPicking] = useState(false);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    now: () => new Date(nowMs),
    initial: toSlot(new Date(at)),
    onPick: (r) => {
      if (r.kind === 'at') {
        const ms = slotMs(r);
        if (ms !== null) setAt(ms);
      }
      setPicking(false);
      flow.reset();
    },
  });
  const now = new Date(nowMs);
  const d = new Date(at);
  const label = `${dayLabel(dayKey(d), now, locale, { today: t('when.today'), tomorrow: t('when.tomorrow') })}, ${hhmm(d)}`;
  const ready = text.trim() !== '' && at > nowMs;

  if (picking) {
    return (
      <FormSheet
        open
        onClose={onClose}
        onBack={flow.back ?? (() => setPicking(false))}
        title={flow.win === 'quick' ? t('day.createWhen') : flow.title}
        testId="create-reminder-sheet"
        footer={flow.footer}
      >
        <div data-create-when-flow>{flow.win === 'quick' ? flow.quick : flow.body}</div>
      </FormSheet>
    );
  }

  return (
    <FormSheet open onClose={onClose} title={t('day.create')} testId="create-reminder-sheet">
      <div className="px-4 flex flex-col gap-4 pb-4">
        <label className="flex flex-col gap-1">
          <span className="text-caption text-ink-2">{t('day.createWhat')}</span>
          <input
            type="text"
            autoFocus
            data-create-text
            maxLength={500}
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="h-12 px-3 rounded-control bg-raised text-body text-ink"
          />
        </label>
        <button
          type="button"
          data-create-when
          onClick={() => {
            flow.reset(toSlot(new Date(at)));
            setPicking(true);
          }}
          className="-mx-2 min-h-14 px-2 rounded-control flex items-center gap-3 text-left active:bg-overlay"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
            <CalendarClock className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </span>
          <span className="flex-1 min-w-0">
            <span className="block text-caption text-ink-2">{t('day.createWhen')}</span>
            <span className="block text-headline text-ink truncate tabular-nums" data-create-when-value>
              {label}
            </span>
          </span>
          <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
        </button>
        <button
          type="button"
          data-create-save
          disabled={!ready}
          onClick={() => void onSave(text, at)}
          className="min-h-12 rounded-control bg-accent text-headline text-on-accent disabled:opacity-40"
        >
          {t('day.createSave', { when: label })}
        </button>
      </div>
    </FormSheet>
  );
};
