import React from 'react';
import { ChevronLeft, X } from 'lucide-react';

export interface SheetFrameProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'title'> {
  title: string;
  crown?: boolean;
  hideTitle?: boolean;
  tall?: boolean;
  onBack?: () => void;
  backLabel?: string;
  onClose?: () => void;
  closeLabel?: string;
  handleProps?: React.HTMLAttributes<HTMLDivElement>;
  footer?: React.ReactNode;
}

export const SheetFrame: React.FC<SheetFrameProps> = ({
  title,
  crown = false,
  hideTitle = false,
  tall = false,
  onBack,
  backLabel,
  onClose,
  closeLabel,
  handleProps,
  footer,
  className = '',
  children,
  ...rest
}) => (
  <div
    data-kit="sheet-frame"
    className={`bg-canvas text-ink border-t border-x-0 border-b-0 border-line-strong rounded-t-sheet md:rounded-sheet md:border flex flex-col ${
      tall ? 'h-[calc(100%-48px)] md:h-[84vh]' : 'max-h-[calc(100%-48px)]'
    } ${className}`.trim()}
    {...rest}
  >
    <div data-kit-sheet-handle className="shrink-0 pt-2 pb-2 flex justify-center md:hidden" {...handleProps} aria-hidden>
      <span className="w-9 h-1 rounded-full bg-line-strong" />
    </div>
    <div
      className={`shrink-0 min-h-12 pt-1 pb-3 flex items-center gap-1 ${onBack ? 'pl-2' : 'pl-5'} ${onClose && !hideTitle ? 'pr-3' : 'pr-5'} ${hideTitle && !onBack ? 'hidden' : ''}`}
      {...handleProps}
    >
      {onBack && (
        <button
          type="button"
          onClick={onBack}
          aria-label={backLabel}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center text-ink-2 rounded-full hover:bg-overlay active:bg-overlay"
        >
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} />
        </button>
      )}
      <h2 className={`flex-1 min-w-0 truncate text-ink ${crown ? 'text-display' : 'text-title-1'} ${hideTitle ? 'sr-only' : ''}`}>{title}</h2>
      {onClose && !hideTitle && (
        <button
          type="button"
          data-sheet-close
          onClick={onClose}
          aria-label={closeLabel}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 self-start flex items-center justify-center rounded-full text-ink-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-ink-2"
        >
          <span className="w-8 h-8 rounded-full bg-raised flex items-center justify-center">
            <X className="w-5 h-5" strokeWidth={1.75} />
          </span>
        </button>
      )}
    </div>
    <div className="flex-1 min-h-0 overflow-y-auto no-scrollbar">{children}</div>
    {footer && <div className="shrink-0 px-5 pt-3 pb-[calc(var(--sab)+16px)]">{footer}</div>}
    {!footer && <div className="shrink-0 h-[calc(var(--sab)+8px)]" />}
  </div>
);
