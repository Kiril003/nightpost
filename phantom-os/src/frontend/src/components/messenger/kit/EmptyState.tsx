import React, { useMemo } from 'react';
import { policy, readEnv } from '../../../feel/motionTokens';

export interface EmptyStateProps {
  icon: React.ReactNode;
  label: string;
  action?: { label: string; onClick: () => void };
  testId?: string;
  /** Під рядками списку, а не на весь екран. */
  inline?: boolean;
}

export const EmptyState: React.FC<EmptyStateProps> = ({ icon, label, action, testId, inline = false }) => {
  const spec = useMemo(() => policy(readEnv()).settle, []);
  return (
    <div data-kit="empty-state" data-testid={testId} className={`${inline ? 'pt-10 pb-6' : 'min-h-full pt-[18vh]'} flex flex-col items-center px-6 text-center`}>
      <div className="max-w-[280px] flex flex-col items-center">
        <span
          data-empty-tile
          data-motion={spec.end ? 'end' : 'settle'}
          className={`mb-4 w-12 h-12 rounded-control bg-surface text-ink-2 flex items-center justify-center${spec.end ? '' : ' np-settle'}`}
          style={spec.end ? undefined : ({ '--np-ms': `${spec.ms}ms`, '--np-ease': spec.easing } as React.CSSProperties)}
          aria-hidden
        >
          {icon}
        </span>
        <h2 className="text-headline text-ink">{label}</h2>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="mt-6 touch-floor-inline h-9 px-4 rounded-full bg-ink/[.08] text-ink text-callout font-medium active:bg-overlay focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
};
