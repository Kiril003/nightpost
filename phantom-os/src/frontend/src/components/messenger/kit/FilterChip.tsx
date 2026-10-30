import React from 'react';

export interface FilterChipProps extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  active?: boolean;
  count?: number;
  leading?: React.ReactNode;
  pillRef?: React.Ref<HTMLSpanElement>;
  pillClassName?: string;
  testId?: string;
  children: React.ReactNode;
}

/** Одна мова «обраного» для чипів і сегментів; власник додає лише `border`. Обране — графіт, решта — плита `raised`, без сірого контуру (LOOK-2910). */
export const chipTone = (on: boolean): string =>
  on ? 'bg-ink border-transparent text-canvas' : 'bg-raised border-transparent text-ink';

export const FilterChip = React.forwardRef<HTMLButtonElement, FilterChipProps>(
  (
    {
      active = false,
      count,
      leading,
      pillRef,
      pillClassName = '',
      testId,
      className = '',
      type = 'button',
      role = 'tab',
      children,
      ...rest
    },
    ref,
  ) => {
    const pillTone = chipTone(active);

    return (
      <button
        ref={ref}
        type={type}
        role={role}
        aria-selected={role === 'tab' ? active : undefined}
        data-kit="filter-chip"
        data-testid={testId}
        className={`touch-floor-inline relative shrink-0 min-h-11 inline-flex items-center select-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line ${className}`.trim()}
        {...rest}
      >
        <span
          ref={pillRef}
          data-kit-chip-pill
          className={`h-9 px-4 rounded-chip border text-subhead font-semibold inline-flex items-center gap-1.5 whitespace-nowrap transition-colors duration-quick ${pillTone} ${pillClassName}`.trim()}
        >
          {leading}
          <span>{children}</span>
          {count !== undefined && count > 0 && (
            <span data-tab-count className={`text-footnote font-medium tnum ${active ? 'text-canvas' : 'text-ink-3'}`}>
              {count > 99 ? '99+' : count}
            </span>
          )}
        </span>
      </button>
    );
  },
);

FilterChip.displayName = 'FilterChip';
