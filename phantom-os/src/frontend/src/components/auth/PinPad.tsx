import { useTranslation } from '../../i18n/useTranslation';
import { useState, useEffect, useCallback } from 'react';
import { Delete, RotateCcw } from 'lucide-react';

interface PinPadProps {
  maxLength?: number;
  onSubmit: (pin: string) => void;
  disabled?: boolean;
  error?: string;
  autoFocus?: boolean;
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];

/**
 * Цифрова клавіатура PIN — та сама, що в рідному замку застосунку
 * (phantom-companion `ui/applock/AppLockScreen.kt`): круглі клавіші 72 на
 * `raised`, цифра — `title-1` кольором тексту; «стерти» — без підкладки,
 * значок `ink-2`; крапки 12 — набрана `ink`, порожня `overlay`; рядок
 * помилки — `footnote` кольору `danger`, висота стала, щоб клавіатура не
 * стрибала. Бурштину тут немає: клавіатура — не головна дія, вона лише
 * набирає (PIN подається сам на останній цифрі).
 *
 * Тут було скло ПК: розмиті «glass»-плитки, бурштинові крапки з сяйвом і
 * підсвітка клавіш при наведенні. Уживають двоє — вхід (LoginScreen) і
 * знайомство (DeskMeet), тож обидва тепер говорять однією мовою з телефоном.
 */
export default function PinPad({
  maxLength = 6,
  onSubmit,
  disabled = false,
  error,
}: PinPadProps) {
  const { t } = useTranslation();
  const [pin, setPin] = useState('');

  const handleKey = useCallback((key: string) => {
    if (disabled) return;
    if (key === 'CLEAR') {
      setPin('');
      return;
    }
    if (key === 'DEL') {
      setPin((p) => p.slice(0, -1));
      return;
    }
    if (pin.length >= maxLength) return;
    const next = pin + key;
    setPin(next);
    if (next.length === maxLength) {
      onSubmit(next);
      setPin('');
    }
  }, [disabled, maxLength, onSubmit, pin]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      // Набір у полі (ім'я на вході з мережі) — не PIN.
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return;
      if (/^[0-9]$/.test(e.key)) {
        handleKey(e.key);
      } else if (e.key === 'Backspace') {
        handleKey('DEL');
      } else if (e.key === 'Escape') {
        handleKey('CLEAR');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [handleKey]);

  return (
    <div className="flex flex-col items-center select-none font-sans" data-pin-pad>
      <div className="flex gap-3" role="status" aria-label={t('pinPad.entered', { n: pin.length, max: maxLength })}>
        {Array.from({ length: maxLength }).map((_, i) => (
          <span
            key={i}
            aria-hidden
            data-filled={i < pin.length ? '' : undefined}
            className={`block w-3 h-3 rounded-full transition-colors duration-quick ease-standard ${
              i < pin.length ? 'bg-ink' : 'bg-overlay'
            }`}
          />
        ))}
      </div>

      <p
        role={error ? 'alert' : undefined}
        className={`mt-3 h-9 flex items-center text-footnote text-center ${error ? 'text-danger' : 'text-ink-3'}`}
      >
        {error || ' '}
      </p>

      <div className="mt-3 grid grid-cols-3 gap-x-5 gap-y-3">
        {KEYS.map((k) => (
          <PadKey key={k} onClick={() => handleKey(k)} disabled={disabled}>
            {k}
          </PadKey>
        ))}
        <PadKey onClick={() => handleKey('CLEAR')} disabled={disabled} ghost ariaLabel={t('pinPad.clearAll')}>
          <RotateCcw className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </PadKey>
        <PadKey onClick={() => handleKey('0')} disabled={disabled}>
          0
        </PadKey>
        <PadKey onClick={() => handleKey('DEL')} disabled={disabled} ghost ariaLabel={t('pinPad.deleteDigit')}>
          <Delete className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </PadKey>
      </div>
    </div>
  );
}

/* ─── Клавіша ───────────────────────────────────────────────────────────── */

function PadKey({
  children,
  onClick,
  disabled,
  ghost = false,
  ariaLabel,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  ghost?: boolean;
  ariaLabel?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className={`ph-press w-[72px] h-[72px] min-h-0 min-w-0 rounded-full flex items-center justify-center transition-colors duration-quick ease-standard disabled:opacity-40 disabled:cursor-not-allowed focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${
        ghost
          ? 'bg-transparent text-ink-2 hover:bg-raised active:bg-raised'
          : 'bg-raised text-ink text-title-1 hover:bg-overlay active:bg-overlay'
      }`}
    >
      {children}
    </button>
  );
}
