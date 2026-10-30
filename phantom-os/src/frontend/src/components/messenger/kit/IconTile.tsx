import React from 'react';
import type { LucideIcon } from 'lucide-react';

export interface IconTileProps {
  icon: LucideIcon;
  label: string;
  onClick: () => void;
  testId?: string;
  /** Тон людини 0..5: плитка залита тоном з білим знаком, а не беж на бежі. */
  tone?: number;
}

export const IconTile: React.FC<IconTileProps> = ({ icon: Icon, label, onClick, testId, tone }) => (
  <button
    type="button"
    data-kit="icon-tile"
    data-testid={testId}
    onClick={onClick}
    className="min-w-0 flex flex-col items-center gap-2 rounded-control py-1 group focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
  >
    <span
      data-kit-tile-face
      data-tone={tone}
      className={`w-14 h-14 rounded-card ${tone == null ? 'bg-overlay text-ink' : ''} flex items-center justify-center group-active:scale-[0.97] transition-transform duration-instant`}
      style={tone == null ? undefined : { backgroundColor: `var(--ph-person-${tone})`, color: `var(--ph-person-${tone}-ink)` }}
    >
      <Icon className="w-6 h-6" strokeWidth={1.75} aria-hidden />
    </span>
    <span data-kit-tile-label className="w-full line-clamp-2 break-words leading-tight text-caption text-ink-2 text-center">
      {label}
    </span>
  </button>
);
