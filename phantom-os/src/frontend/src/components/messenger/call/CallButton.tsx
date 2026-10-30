/**
 * Кругла кнопка дзвінка з підписом під нею: рівний тон — вимкнений
 * перемикач, світлий диск — увімкнений (мікрофон вимкнено), червоне —
 * завершити, бурштин — прийняти (єдиний головний на екрані, стиль D).
 * Колір ніколи не єдиний знак: є іконка і підпис.
 *
 * Гліф на червоному — полотно (`--ph-canvas`), не білий: #FFF на нічному
 * #FF7D71 — 2.2:1. На бурштині — `--ph-on-accent`.
 */

import React, { forwardRef } from 'react';

export const END = 'var(--ph-danger)';
export const ACCEPT = 'var(--ph-accent)';
export const ON_ACCEPT = 'var(--ph-on-accent)';
export const GLYPH = 'var(--ph-canvas)';

export type CallButtonTone = 'idle' | 'on' | 'end' | 'accept';

const toneStyle = (tone: CallButtonTone): React.CSSProperties =>
  tone === 'end'
    ? { background: END, color: GLYPH }
    : tone === 'accept'
    ? { background: ACCEPT, color: ON_ACCEPT }
    : tone === 'on'
    ? { background: 'var(--ph-text)', color: 'var(--ph-canvas)' }
    : { background: 'var(--ph-raised)', color: 'var(--ph-text)', border: '1px solid var(--ph-line)' };

export const CallButton = forwardRef<
  HTMLButtonElement,
  {
    onClick: () => void;
    label?: string;
    hint: string;
    tone: CallButtonTone;
    pressed?: boolean;
    /** 'lg' — 72 px (прийняти, відхилити, завершити); 'md' — 64 px (перемикачі в ряду). */
    size?: 'md' | 'lg';
    disabled?: boolean;
    children: React.ReactNode;
  } & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onClick' | 'children'>
>(({ onClick, label, hint, tone, pressed, size = 'lg', disabled, children, ...rest }, ref) => (
  <div className={`flex flex-col items-center gap-2 ${size === 'lg' ? 'w-24' : 'w-[76px]'}`}>
    <button
      ref={ref}
      type="button"
      onClick={onClick}
      aria-label={hint}
      aria-pressed={pressed}
      title={hint}
      disabled={disabled}
      className={`${
        size === 'lg' ? 'w-[72px] h-[72px]' : 'w-16 h-16'
      } rounded-full flex items-center justify-center transition-transform duration-instant ease-standard active:scale-95 disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line`}
      style={toneStyle(tone)}
      {...rest}
    >
      {children}
    </button>
    {label && <span className="text-caption text-center leading-tight text-ink-2 max-w-full truncate">{label}</span>}
  </div>
));
CallButton.displayName = 'CallButton';
