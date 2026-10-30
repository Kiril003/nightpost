import React from 'react';
import { CalendarClock, ChevronRight, X } from 'lucide-react';

/** Рядок «Коли / Строк / Початок»: підпис, обране значення, дотик відкриває вікна «Коли». */
export const WhenRow: React.FC<{
  label: string;
  value: string;
  empty?: boolean;
  onClick: () => void;
  onClear?: () => void;
  clearLabel?: string;
  icon?: React.ReactNode;
  testId?: string;
}> = ({ label, value, empty, onClick, onClear, clearLabel, icon, testId }) => (
  <div className="-mx-2 flex items-center">
    <button
      type="button"
      data-testid={testId}
      onClick={onClick}
      className="flex-1 min-w-0 min-h-14 px-2 rounded-control flex items-center gap-3 text-left active:bg-overlay"
    >
      <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
        {icon ?? <CalendarClock className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
      </span>
      <span className="flex-1 min-w-0">
        <span className="block text-caption text-ink-2">{label}</span>
        <span className={`block text-headline truncate tabular-nums ${empty ? 'text-ink-3' : 'text-ink'}`} data-when-value>
          {value}
        </span>
      </span>
      <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
    </button>
    {onClear && (
      <button type="button" aria-label={clearLabel} onClick={onClear} className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2 rounded-full active:bg-overlay">
        <X className="w-5 h-5" strokeWidth={1.75} />
      </button>
    )}
  </div>
);
