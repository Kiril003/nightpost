import React from 'react';
import { AlertCircle, Check, Clock } from 'lucide-react';

export type StatusChipKind = 'neutral' | 'pending' | 'done' | 'failed';

export interface StatusChipProps {
  kind?: StatusChipKind;
  on?: 'canvas' | 'surface';
  children: React.ReactNode;
  testId?: string;
}

export const StatusChip: React.FC<StatusChipProps> = ({
  kind = 'neutral',
  on = 'canvas',
  children,
  testId,
}) => {
  const bgClass = on === 'surface' ? 'bg-overlay' : 'bg-raised';

  return (
    <span
      data-kit="chip"
      data-kind={kind}
      data-testid={testId}
      className={`inline-flex items-center gap-1 h-6 px-2 rounded-chip text-caption text-ink-2 ${bgClass}`}
    >
      {kind === 'pending' ? <Clock className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden /> : null}
      {kind === 'done' ? <Check className="w-3.5 h-3.5 shrink-0" strokeWidth={1.75} aria-hidden /> : null}
      {kind === 'failed' ? (
        <AlertCircle className="w-3.5 h-3.5 shrink-0 text-danger" strokeWidth={1.75} aria-hidden />
      ) : null}
      {children}
    </span>
  );
};
