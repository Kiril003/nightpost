import React from 'react';
import { ArrowLeft, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';

/** Повноекранний шар простору: шапка з «назад» чи «×», тіло, одна дія внизу. */
export const SpaceFrame: React.FC<{
  testId: string;
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  lead?: React.ReactNode;
  trailing?: React.ReactNode;
  onBack: () => void;
  closeIcon?: boolean;
  footer?: React.ReactNode;
  children: React.ReactNode;
}> = ({ testId, title, subtitle, lead, trailing, onBack, closeIcon = false, footer, children }) => {
  const { t } = useTranslation();
  const Icon = closeIcon ? X : ArrowLeft;
  return (
    <div data-testid={testId} className="fixed inset-0 z-[1150] flex flex-col bg-canvas text-ink font-sans" style={{ paddingTop: 'var(--sat)', paddingBottom: 'var(--sab)' }}>
      <header className={`shrink-0 min-h-14 px-1 flex items-center gap-2 ${subtitle || lead ? 'border-b border-line' : ''}`}>
        <button
          type="button"
          onClick={onBack}
          aria-label={closeIcon ? t('space.close') : t('space.back')}
          data-testid="space-back"
          className="w-12 h-12 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink"
        >
          <Icon className="w-6 h-6" strokeWidth={2} aria-hidden />
        </button>
        {lead}
        {title && (
          <div className="min-w-0 flex-1">
            <h2 className="text-title-2 text-ink truncate">{title}</h2>
            {subtitle && <p data-testid="space-subtitle" className="text-footnote text-ink-2 truncate">{subtitle}</p>}
          </div>
        )}
        {trailing}
      </header>
      <div className="flex-1 min-h-0 overflow-y-auto">{children}</div>
      {footer && <div className="shrink-0 px-4 pt-3 pb-4 border-t border-line bg-canvas">{footer}</div>}
    </div>
  );
};

export const SpacePrimary: React.FC<React.ButtonHTMLAttributes<HTMLButtonElement> & { testId?: string }> = ({ testId, className = '', children, ...rest }) => (
  <button
    type="button"
    data-testid={testId}
    className={`h-12 min-h-0 rounded-full bg-accent text-on-accent text-headline font-semibold flex items-center justify-center gap-2 active:opacity-80 disabled:bg-overlay disabled:text-ink-3 disabled:active:opacity-100 ${className}`}
    {...rest}
  >
    {children}
  </button>
);

export const SectionLabel: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="px-4 pt-4 pb-1 text-footnote text-ink-2">{children}</p>
);
