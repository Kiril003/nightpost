import React from 'react';

export interface ListRowProps extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'title'> {
  leading?: React.ReactNode;
  title: React.ReactNode;
  meta?: React.ReactNode;
  time?: string;
  timeStrong?: boolean;
  subtitle?: React.ReactNode;
  badge?: React.ReactNode;
  density?: 'phone' | 'desk';
}

export const ListRow = React.forwardRef<HTMLButtonElement, ListRowProps>(
  (
    { leading, title, meta, time, timeStrong = false, subtitle, badge, density = 'phone', className = '', type = 'button', ...rest },
    ref,
  ) => (
    <button
      ref={ref}
      type={type}
      data-kit="list-row"
      className={`w-full ${density === 'desk' ? 'h-[60px] rounded-control' : 'h-[72px] ph-list-row'} px-4 flex items-center gap-3 text-left bg-canvas active:bg-overlay hover:bg-overlay/60 transition-colors duration-quick ease-standard focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-line ${className}`.trim()}
      {...rest}
    >
      {leading}
      <span className="min-w-0 flex-1 self-stretch flex flex-col justify-center">
        <span className="flex items-center gap-2">
          <span data-kit-row-title className="min-w-0 flex-1 truncate text-headline text-ink">
            {title}
          </span>
          {meta}
          {time && <span data-row-time className={`shrink-0 text-footnote tnum ${timeStrong ? 'text-ink-2' : 'text-ink-3'}`}>{time}</span>}
        </span>
        {(subtitle !== undefined || badge) && (
          <span className="mt-0.5 flex items-center gap-2">
            {subtitle !== undefined && (
              <span data-kit-row-subtitle className="min-w-0 flex-1 truncate text-subhead font-normal text-ink-2">
                {subtitle}
              </span>
            )}
            {badge}
          </span>
        )}
      </span>
    </button>
  ),
);

ListRow.displayName = 'ListRow';

/** Лічильник 22: бурштин — до трьох головних; решта — графіт на полотні, приглушені — тихіша плита, але теж видна. */
export const RowBadge: React.FC<{ n: number; lit?: boolean; quiet?: boolean; label: string }> = ({ n, lit = false, quiet = false, label }) => (
  <span
    data-kit="row-badge"
    data-badge-tone={lit ? 'lit' : quiet ? 'quiet' : 'ink'}
    className={`shrink-0 h-[22px] min-w-[22px] px-1.5 rounded-full text-footnote font-semibold tnum flex items-center justify-center ${
      lit ? 'bg-accent text-on-accent' : quiet ? 'bg-line-strong text-ink' : 'bg-ink-2 text-canvas'
    }`}
    aria-label={label}
  >
    {n > 99 ? '99+' : n}
  </span>
);
