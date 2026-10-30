import React from 'react';
import { ChevronLeft } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';

export interface EduFrameProps {
  title: string;
  subtitle?: string;
  onBack: () => void;
  trailing?: React.ReactNode;
  footer?: React.ReactNode;
  testId: string;
  children: React.ReactNode;
}

/** Повноекранний шар напрямку «Навчання»: шапка з «назад», тіло, одна дія внизу. */
export const EduFrame: React.FC<EduFrameProps> = ({ title, subtitle, onBack, trailing, footer, testId, children }) => {
  const { t } = useTranslation();
  return (
    <div
      data-testid={testId}
      className="fixed inset-0 z-[1150] flex flex-col bg-canvas text-ink font-sans"
      style={{ paddingTop: 'var(--sat)', paddingBottom: 'var(--sab)' }}
    >
      <header className="shrink-0 min-h-14 px-1 flex items-center gap-1 border-b border-line">
        <button
          type="button"
          onClick={onBack}
          aria-label={t('edu.back')}
          data-testid="edu-back"
          className="w-12 h-12 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink"
        >
          <ChevronLeft className="w-6 h-6" strokeWidth={2} aria-hidden />
        </button>
        <div className="min-w-0 flex-1">
          <h2 className="text-title-2 text-ink truncate">{title}</h2>
          {subtitle && <p className="text-footnote text-ink-2 truncate">{subtitle}</p>}
        </div>
        {trailing}
      </header>
      <div className="flex-1 min-h-0 overflow-y-auto">{children}</div>
      {footer && <div className="shrink-0 px-4 pt-3 pb-4 border-t border-line bg-canvas">{footer}</div>}
    </div>
  );
};

export const EduPrimary: React.FC<React.ButtonHTMLAttributes<HTMLButtonElement> & { testId?: string }> = ({
  testId,
  className = '',
  children,
  ...rest
}) => (
  <button
    type="button"
    data-testid={testId}
    className={`w-full h-12 min-h-0 rounded-full bg-accent text-on-accent text-headline font-semibold active:opacity-80 disabled:opacity-50 ${className}`}
    {...rest}
  >
    {children}
  </button>
);

export const Counter: React.FC<{ n: number; label: string; testId?: string }> = ({ n, label, testId }) => (
  <div data-testid={testId} className="min-w-0 flex-1 rounded-card bg-surface border border-line px-3 py-2">
    <div className="text-title-1 text-ink tnum">{n}</div>
    <div className="text-footnote text-ink-2 truncate">{label}</div>
  </div>
);

export const fieldClass =
  'w-full h-12 px-4 rounded-control bg-surface border border-line text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line';
