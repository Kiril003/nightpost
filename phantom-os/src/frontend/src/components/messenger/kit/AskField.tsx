import React from 'react';
import { Mic, Search } from 'lucide-react';

export interface AskFieldProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'onChange'> {
  value: string;
  onChange: (value: string) => void;
  label: string;
  placeholder?: string;
  autoFocus?: boolean;
  onVoice?: () => void;
  voiceLabel?: string;
  voiceTestId?: string;
  inputRef?: React.Ref<HTMLInputElement>;
  bar?: boolean;
}

export const AskField: React.FC<AskFieldProps> = ({
  value,
  onChange,
  label,
  placeholder,
  autoFocus = false,
  onVoice,
  voiceLabel,
  voiceTestId,
  inputRef,
  bar = false,
  ...rest
}) => (
  <div
    data-kit="ask-field"
    className={`flex-1 min-w-0 ${bar ? 'h-12 pr-0' : 'h-11 pr-1'} rounded-control bg-raised border border-transparent focus-within:border-accent-line flex items-center gap-2 pl-4`}
    {...rest}
  >
    <Search className={`w-5 h-5 shrink-0 ${bar ? 'text-ink-2' : 'text-ink-3'}`} strokeWidth={1.75} />
    <input
      ref={inputRef}
      autoFocus={autoFocus}
      type="search"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      aria-label={label}
      placeholder={placeholder}
      className={`flex-1 min-w-0 h-10 truncate bg-transparent text-body text-ink ${bar ? 'placeholder:text-ink-2' : 'placeholder:text-ink-3'} focus:outline-none focus-visible:outline-none [&::-webkit-search-cancel-button]:appearance-none`}
    />
    {onVoice && (
      <button
        type="button"
        data-testid={voiceTestId}
        onClick={onVoice}
        aria-label={voiceLabel}
        className={`touch-floor-inline ${bar ? 'w-12 h-12' : 'w-10 h-10'} shrink-0 rounded-full text-ink-2 flex items-center justify-center active:bg-overlay transition-colors duration-quick`}
      >
        <Mic className="w-5 h-5" strokeWidth={1.75} />
      </button>
    )}
  </div>
);
