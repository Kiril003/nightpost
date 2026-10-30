import { useThemeContext } from '@/theme/ThemeProvider';

interface PairOfferStepProps {
  displayName: string;
  onStartPair: () => void;
  onSkip: () => void;
}

// Final onboarding step. The user has a profile + PIN; the only thing
// left is to bind it to a phantom-os host. We hand off to the existing
// `/pair` route rather than embedding QR scanning in this module — the
// PairingScreen has 336 LOC of camera + jsQR + retry logic that we'd
// otherwise duplicate, and Phase 2 will add a "/pair?from=onboarding"
// query so the host knows to bounce back into the launcher on success.
export function PairOfferStep({ displayName, onStartPair, onSkip }: PairOfferStepProps) {
  const { theme } = useThemeContext();
  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        padding: 28,
        gap: 22,
        justifyContent: 'center',
      }}
    >
      <div style={{ fontFamily: 'var(--mono)', fontSize: 11, opacity: 0.5, letterSpacing: '0.22em' }}>
        КРОК 3 / 3 · {displayName.toUpperCase()}
      </div>
      <div>
        <h1 style={{ fontSize: 28, fontWeight: 700, margin: 0, lineHeight: 1.1 }}>
          Підв'язати до PHANTOM
        </h1>
        <p style={{ marginTop: 12, color: theme.ink2, fontSize: 15, lineHeight: 1.5 }}>
          Скануй QR-код зі свого PHANTOM (десктопний UI → Settings →
          Pair Device). Без парингу застосунок працюватиме у демо-режимі
          з обмеженим функціоналом.
        </p>
      </div>

      <button
        onClick={onStartPair}
        style={{
          padding: '16px 18px',
          borderRadius: 14,
          background: theme.primary,
          color: '#000',
          border: 'none',
          fontFamily: 'var(--mono)',
          fontSize: 13,
          fontWeight: 700,
          letterSpacing: '0.2em',
          textTransform: 'uppercase',
          minHeight: 60,
          cursor: 'pointer',
        }}
      >
        Сканувати QR
      </button>

      <button
        onClick={onSkip}
        style={{
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
          cursor: 'pointer',
        }}
      >
        Пізніше
      </button>

      <div style={{ fontSize: 12, opacity: 0.45, textAlign: 'center', lineHeight: 1.5 }}>
        Парування захищене HKDF-SHA256 + Ed25519 + AES-GCM.
        Жодних API-ключів не надсилається.
      </div>
    </div>
  );
}
