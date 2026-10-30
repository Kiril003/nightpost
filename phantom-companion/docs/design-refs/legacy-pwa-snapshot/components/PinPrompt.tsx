import { useState } from 'react';
import { useThemeContext } from '@/theme/ThemeProvider';
import { Modal } from './Modal';

interface PinPromptProps {
  open: boolean;
  title?: string;
  hint?: string;
  onCancel: () => void;
  onSubmit: (pin: string) => void;
}

export function PinPrompt({ open, title = 'Enter PIN', hint, onCancel, onSubmit }: PinPromptProps) {
  const { theme } = useThemeContext();
  const [pin, setPin] = useState('');

  return (
    <Modal open={open} onClose={onCancel} title={title}>
      {hint && <div style={{ fontSize: 13, color: theme.ink2, marginBottom: 12, lineHeight: 1.4 }}>{hint}</div>}
      <input
        type="password"
        inputMode="numeric"
        autoFocus
        value={pin}
        onChange={(e) => setPin(e.target.value)}
        placeholder="••••"
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
      <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
        <button
          onClick={() => {
            setPin('');
            onCancel();
          }}
          style={{
            flex: 1,
            padding: '11px 14px',
            borderRadius: 12,
            background: 'transparent',
            color: theme.ink2,
            border: `1px solid ${theme.glassBorder}`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          Cancel
        </button>
        <button
          onClick={() => {
            const p = pin;
            setPin('');
            if (p) onSubmit(p);
          }}
          disabled={!pin}
          style={{
            flex: 1,
            padding: '11px 14px',
            borderRadius: 12,
            background: pin ? theme.primary : theme.glassBorder,
            color: pin ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          Unlock
        </button>
      </div>
    </Modal>
  );
}
