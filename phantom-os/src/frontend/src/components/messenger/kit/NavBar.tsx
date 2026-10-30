import React from 'react';

export interface NavItem {
  key: string;
  label: string;
  shortLabel?: string;
  icon: React.ReactNode;
  badge?: number;
  active: boolean;
  onClick: () => void;
}

export interface NavBarProps {
  items: NavItem[];
  layout?: 'bar' | 'island';
  className?: string;
  label?: string;
  testId?: string;
  typeScale?: { label?: string; badge?: string };
}

export function NavBar({ items, layout = 'bar', label, testId, typeScale = {}, className = '' }: NavBarProps) {
  const labelType = typeScale.label ?? 'text-caption';
  const badgeType = typeScale.badge ?? 'text-caption';

  if (layout === 'island') {
    return (
      <nav data-kit="nav-island" data-testid={testId} aria-label={label} className={`flex gap-0.5 p-1 rounded-full bg-surface border border-line ${className}`}>
        {items.map((it) => (
          <button
            key={it.key}
            type="button"
            aria-label={it.label}
            title={it.label}
            aria-current={it.active ? 'page' : undefined}
            onClick={it.onClick}
            className={`relative w-[88px] h-[52px] rounded-full flex flex-col items-center justify-center gap-0.5 font-medium transition-colors duration-instant ease-standard ${labelType} ${
              it.active ? 'bg-raised text-ink' : 'text-ink-2 hover:text-ink hover:bg-raised/60'
            }`}
          >
            {it.icon}
            <span data-kit-nav-label className="truncate max-w-[80px]">{it.shortLabel ?? it.label}</span>
            {Boolean(it.badge) && (
              <span data-kit-nav-badge className={`absolute top-1 left-[52px] min-w-[18px] h-[18px] px-1 rounded-full bg-accent text-on-accent ${badgeType} text-center tabular-nums`}>
                {it.badge}
              </span>
            )}
          </button>
        ))}
      </nav>
    );
  }

  return (
    <nav data-kit="nav-bar" data-testid={testId} aria-label={label} className="h-16 shrink-0 flex bg-surface border-t border-line">
      {items.map((it) => (
        <button
          key={it.key}
          type="button"
          aria-label={it.label}
          aria-current={it.active ? 'page' : undefined}
          onClick={it.onClick}
          className="flex-1 min-w-0 min-h-[48px] flex flex-col items-center justify-center gap-1"
        >
          <span
            data-kit-nav-pill
            className={`relative w-16 h-8 rounded-full flex items-center justify-center transition-colors duration-instant ease-standard ${
              it.active ? 'bg-raised text-ink' : 'text-ink-2'
            }`}
          >
            {it.icon}
            {Boolean(it.badge) && (
              <span data-kit-nav-badge className={`absolute -top-1 right-2 min-w-[18px] h-[18px] px-1 rounded-full bg-accent text-on-accent ${badgeType} text-center tabular-nums`}>
                {it.badge}
              </span>
            )}
          </span>
          <span data-kit-nav-label className={`${labelType} truncate max-w-full ${it.active ? 'text-ink' : 'text-ink-2'}`}>
            {it.shortLabel ?? it.label}
          </span>
        </button>
      ))}
    </nav>
  );
}
