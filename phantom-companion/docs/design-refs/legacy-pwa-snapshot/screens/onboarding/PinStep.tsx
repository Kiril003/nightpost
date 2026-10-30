import { useState } from 'react';

import { useThemeContext } from '@/theme/ThemeProvider';
import { MIN_PIN_LENGTH } from '@/services/pinHasher';

interface PinStepProps {
  displayName: string;
  error: string | null;
  onSubmit: (pin: string) => void;
  onBack: () => void;
}

export function PinStep({ displayName, error, onSubmit, onBack }: PinStepProps) {
  const { theme } = useThemeContext();
  const [pin, setPin] = useState('');
  const [confirm, setConfirm] = useState('');

  const longEnough = pin.length >= MIN_PIN_LENGTH;
  const matches = pin === confirm;
  const valid = longEnough && matches && confirm.length > 0;

  const submit = () => {
    if (!valid) return;
    onSubmit(pin);
  };

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        padding: 28,
        gap: 18,
        justifyContent: 'center',
      }}
    >
      <div style={{ fontFamily: 'var(--mono)', fontSize: 11, opacity: 0.5, letterSpacing: '0.22em' }}>
        КРОК 2 / 3 · {displayName.toUpperCase()}
      </div>
      <div>
        <h1 style={{ fontSize: 28, fontWeight: 700, margin: 0, lineHeight: 1.1 }}>
          Встанови PIN
        </h1>
        <p style={{ marginTop: 12, color: theme.ink2, fontSize: 15, lineHeight: 1.5 }}>
          Мінімум {MIN_PIN_LENGTH} цифр. PIN захищає сесію коли біометрія недоступна
          і потрібен на кожному запуску після перезавантаження.
        </p>
      </div>

      <PinField label="PIN" value={pin} onChange={setPin} autoFocus />
      <PinField label="Підтвердь PIN" value={confirm} onChange={setConfirm} onEnter={submit} />

      <div style={{ fontSize: 12, color: theme.ink2, lineHeight: 1.4, minHeight: 18 }}>
        {pin.length === 0 && <span>Цифри або символи — тільки локально.</span>}
        {pin.length > 0 && !longEnough && (
          <span style={{ color: '#ffb86b' }}>Ще {MIN_PIN_LENGTH - pin.length} символ(и).</span>
        )}
        {longEnough && confirm.length > 0 && !matches && (
          <span style={{ color: '#ff8d8d' }}>PIN не співпадає.</span>
        )}
        {valid && <span style={{ color: '#7cf0a4' }}>OK — PIN готовий.</span>}
      </div>

      {error && <div style={{ color: '#ff8d8d', fontSize: 13, lineHeight: 1.4 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 10, marginTop: 6 }}>
        <button
          onClick={onBack}
          style={{
            flex: 0,
            padding: '14px 18px',
            borderRadius: 14,
            background: 'transparent',
            color: theme.ink2,
            border: `1px solid ${theme.glassBorder}`,
            fontFamily: 'var(--mono)',
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.2em',
            textTransform: 'uppercase',
            minHeight: 56,
            minWidth: 80,
            cursor: 'pointer',
          }}
        >
          ←
        </button>
        <button
          disabled={!valid}
          onClick={submit}
          style={{
            flex: 1,
            padding: '14px 18px',
            borderRadius: 14,
            background: valid ? theme.primary : theme.glassPanel,
            color: valid ? '#000' : theme.ink2,
            border: 'none',
            fontFamily: 'var(--mono)',
            fontSize: 13,
            fontWeight: 700,
            letterSpacing: '0.2em',
            textTransform: 'uppercase',
            minHeight: 56,
            cursor: valid ? 'pointer' : 'not-allowed',
            transition: 'background 160ms ease, color 160ms ease',
          }}
        >
          Створити профіль
        </button>
      </div>
    </div>
  );
}

interface PinFieldProps {
  label: string;
  value: string;
  onChange: (v: string) => void;
  autoFocus?: boolean;
  onEnter?: () => void;
}

function PinField({ label, value, onChange, autoFocus, onEnter }: PinFieldProps) {
  const { theme } = useThemeContext();
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 11, opacity: 0.5, letterSpacing: '0.18em' }}>
        {label.toUpperCase()}
      </span>
      <input
        type="password"
        inputMode="numeric"
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && onEnter) onEnter();
        }}
        placeholder="••••••"
        style={{
          width: '100%',
          padding: '14px 16px',
          fontSize: 22,
          textAlign: 'center',
          letterSpacing: '0.4em',
          borderRadius: 14,
          background: theme.glassPanel,
          border: `1px solid ${theme.glassBorder}`,
          color: theme.ink,
          fontFamily: 'var(--mono)',
        }}
      />
    </label>
  );
}
