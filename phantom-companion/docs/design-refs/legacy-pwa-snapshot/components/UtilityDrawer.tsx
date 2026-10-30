// Utility drawer — slides in from the right edge.
//
// Holds the secondary destinations that don't deserve a permanent
// bottom-nav slot: /senses (camera), /driver (desktop control),
// /ambient (always-on), /diag (status board), /showcase (component
// gallery, dev-only when devMode flag is on). Open via a hamburger
// button in the global status bar.
//
// Closes on outside-tap, Escape key, or after the operator follows
// a link inside the drawer.

import { useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import type { PhantomTheme } from '@/types/theme';

export interface UtilityDrawerEntry {
  to: string;
  icon: string;
  label: string;
  hint?: string;
  devOnly?: boolean;
}

const ENTRIES: UtilityDrawerEntry[] = [
  // Standalone — works without phantom-os.
  { to: '/notes', icon: '≡', label: 'Notes', hint: 'Markdown, локально' },
  { to: '/timer', icon: '⏱', label: 'Timer', hint: 'Pomodoro / countdown / alarm' },
  { to: '/utils', icon: '⚙', label: 'Utilities', hint: 'Калькулятор, конвертер, паролі, QR' },
  { to: '/clipboard', icon: '⎘', label: 'Clipboard', hint: 'Історія буфера' },
  { to: '/translate', icon: '⇄', label: 'Translate', hint: 'Переклад (MyMemory)' },
  { to: '/voice-memos', icon: '🎙', label: 'Voice memos', hint: 'Quick-capture audio' },
  { to: '/habits', icon: '◐', label: 'Habits + mood', hint: 'Streaks і настрій' },
  { to: '/senses', icon: '◑', label: 'Senses', hint: 'Камера, FaceMesh, OCR' },
  { to: '/diag', icon: '⊕', label: 'Diagnostics', hint: 'Статус усіх шарів' },
  // Paired-only — require phantom-os.
  { to: '/driver', icon: '◬', label: 'Driver', hint: 'Друкувати в десктоп' },
  { to: '/ambient', icon: '◌', label: 'Ambient', hint: 'Always-on glance' },
  { to: '/notifications', icon: '◓', label: 'Inbox (full)', hint: 'Повна історія' },
  { to: '/comms', icon: '◔', label: 'Comms', hint: 'Дзвінки + повідомлення' },
  { to: '/showcase', icon: '◰', label: 'Showcase', hint: 'Компоненти (dev)', devOnly: true },
];

interface UtilityDrawerProps {
  open: boolean;
  onClose: () => void;
  theme: PhantomTheme;
}

export function UtilityDrawer({ open, onClose, theme }: UtilityDrawerProps) {
  const location = useLocation();
  const devMode =
    typeof localStorage !== 'undefined' && localStorage.getItem('phantom.devMode') === '1';

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Auto-close when user navigates away.
  useEffect(() => {
    if (open) onClose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  return (
    <>
      <div
        onClick={onClose}
        aria-hidden
        style={{
          position: 'fixed',
          inset: 0,
          background: open ? 'rgba(0,0,0,0.45)' : 'transparent',
          opacity: open ? 1 : 0,
          pointerEvents: open ? 'auto' : 'none',
          transition: 'opacity 0.18s ease-out',
          zIndex: 40,
        }}
      />
      <aside
        role="dialog"
        aria-label="Utility drawer"
        style={{
          position: 'fixed',
          top: 0,
          right: 0,
          bottom: 0,
          width: 'min(82vw, 320px)',
          background: theme.surfaceDeep,
          borderLeft: `1px solid ${theme.glassBorder}`,
          boxShadow: '-12px 0 32px rgba(0,0,0,0.45)',
          transform: open ? 'translateX(0)' : 'translateX(105%)',
          transition: 'transform 0.22s cubic-bezier(.2,.85,.4,1)',
          zIndex: 41,
          display: 'flex',
          flexDirection: 'column',
          color: theme.ink,
          paddingTop: 'env(safe-area-inset-top, 0px)',
          paddingBottom: 'env(safe-area-inset-bottom, 0px)',
        }}
      >
        <div
          style={{
            padding: '14px 16px 10px',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 700,
            }}
          >
            Інструменти
          </div>
          <button
            onClick={onClose}
            aria-label="Close drawer"
            style={{
              marginLeft: 'auto',
              width: 32,
              height: 32,
              borderRadius: 8,
              background: 'transparent',
              color: theme.ink2,
              fontSize: 18,
              border: `1px solid ${theme.glassBorder}`,
            }}
          >
            ×
          </button>
        </div>

        <nav
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: '4px 12px 12px',
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
          }}
        >
          {ENTRIES.filter((e) => !e.devOnly || devMode).map((e) => (
            <Link
              key={e.to}
              to={e.to}
              style={{
                textDecoration: 'none',
                color: theme.ink,
                padding: '12px 12px',
                borderRadius: 12,
                border: `1px solid ${theme.glassBorder}`,
                background: 'rgba(255,255,255,0.03)',
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                minHeight: 56,
              }}
            >
              <div
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: 8,
                  background: theme.primary + '22',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 16,
                  color: theme.primary,
                  flexShrink: 0,
                }}
              >
                {e.icon}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    fontSize: 14,
                    fontWeight: 600,
                  }}
                >
                  {e.label}
                </div>
                {e.hint && (
                  <div
                    style={{
                      fontSize: 11,
                      color: theme.ink3,
                      marginTop: 2,
                      lineHeight: 1.35,
                    }}
                  >
                    {e.hint}
                  </div>
                )}
              </div>
            </Link>
          ))}
        </nav>

        <div
          style={{
            padding: '10px 16px',
            fontFamily: 'var(--mono)',
            fontSize: 9,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            color: theme.ink3,
            borderTop: `1px solid ${theme.glassBorder}`,
            opacity: 0.7,
          }}
        >
          PHANTOM Companion
        </div>
      </aside>
    </>
  );
}
