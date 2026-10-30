import React from 'react';
import { ChevronLeft } from 'lucide-react';

type BackProps = React.ButtonHTMLAttributes<HTMLButtonElement> & { [attr: `data-${string}`]: string | boolean | undefined };

export interface TopBarProps {
  title: string;
  onBack?: () => void;
  backLabel?: string;
  backProps?: BackProps;
  trailing?: React.ReactNode;
  safeTop?: boolean;
}

export const TopBar: React.FC<TopBarProps> = ({ title, onBack, backLabel, backProps, trailing, safeTop = false }) => (
  <header
    data-kit="top-bar"
    className="h-14 shrink-0 px-1 flex items-center gap-1"
    style={safeTop ? { marginTop: 'env(safe-area-inset-top)' } : undefined}
  >
    {onBack && (
      <button
        type="button"
        {...backProps}
        onClick={onBack}
        aria-label={backLabel}
        className="w-12 h-12 min-h-0 min-w-0 flex items-center justify-center rounded-full text-ink"
      >
        <ChevronLeft className="w-6 h-6" strokeWidth={2} aria-hidden />
      </button>
    )}
    <h2 className="text-title-2 text-ink">{title}</h2>
    {trailing}
  </header>
);
