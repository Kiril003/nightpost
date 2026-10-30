import { useToastStore, type ToastKind } from '@/state/toastStore';
import { useThemeContext } from '@/theme/ThemeProvider';

const ACCENT: Record<ToastKind, string> = {
  info: '#9A8A6F',
  success: '#16A34A',
  warn: '#F4AF25',
  error: '#EF4444',
};

export function Toaster() {
  const toasts = useToastStore((s) => s.toasts);
  const dismiss = useToastStore((s) => s.dismiss);
  const { theme } = useThemeContext();

  if (!toasts.length) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      aria-atomic="false"
      style={{
        position: 'fixed',
        top: 16,
        left: 0,
        right: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 8,
        zIndex: 100,
        pointerEvents: 'none',
        padding: '0 16px',
      }}
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          onClick={() => dismiss(t.id)}
          style={{
            pointerEvents: 'auto',
            background: theme.glassElevated,
            backdropFilter: 'blur(24px)',
            WebkitBackdropFilter: 'blur(24px)',
            border: `1px solid ${ACCENT[t.kind]}55`,
            borderLeft: `3px solid ${ACCENT[t.kind]}`,
            borderRadius: 12,
            padding: '10px 14px',
            color: theme.ink,
            fontFamily: 'var(--mono)',
            fontSize: 12,
            fontWeight: 500,
            maxWidth: 360,
            boxShadow: '0 12px 32px rgba(0,0,0,0.18)',
          }}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}
