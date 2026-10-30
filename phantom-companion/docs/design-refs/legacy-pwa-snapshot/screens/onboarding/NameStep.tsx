import { useState } from 'react';

import { useThemeContext } from '@/theme/ThemeProvider';

interface NameStepProps {
  initialName: string;
  error: string | null;
  onSubmit: (displayName: string) => void;
}

export function NameStep({ initialName, error, onSubmit }: NameStepProps) {
  const { theme } = useThemeContext();
  const [name, setName] = useState(initialName);

  const trimmed = name.trim();
  const valid = trimmed.length >= 2 && trimmed.length <= 32;

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        padding: 28,
        gap: 24,
        justifyContent: 'center',
      }}
    >
      <div style={{ fontFamily: 'var(--mono)', fontSize: 11, opacity: 0.5, letterSpacing: '0.22em' }}>
        КРОК 1 / 3
      </div>
      <div>
        <h1 style={{ fontSize: 28, fontWeight: 700, margin: 0, lineHeight: 1.1 }}>Як до тебе звертатись?</h1>
        <p style={{ marginTop: 12, color: theme.ink2, fontSize: 15, lineHeight: 1.5 }}>
          Це ім'я PHANTOM використовуватиме у голосових відповідях
          і покаже у пер­ший­му меню профілів.
        </p>
      </div>

      <input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && valid) onSubmit(trimmed);
        }}
        placeholder="Кирило"
        maxLength={32}
        style={{
          width: '100%',
          padding: '16px 18px',
          fontSize: 22,
          borderRadius: 14,
          background: theme.glassPanel,
          border: `1px solid ${theme.glassBorder}`,
          color: theme.ink,
          fontFamily: 'inherit',
        }}
      />

      {error && (
        <div style={{ color: '#ff8d8d', fontSize: 13, lineHeight: 1.4 }}>{error}</div>
      )}

      <button
        disabled={!valid}
        onClick={() => onSubmit(trimmed)}
        style={{
          marginTop: 8,
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
        Далі
      </button>

      <div style={{ fontSize: 12, opacity: 0.45, textAlign: 'center', lineHeight: 1.5 }}>
        Ім'я можна змінити пізніше у Profile → Edit.
      </div>
    </div>
  );
}
