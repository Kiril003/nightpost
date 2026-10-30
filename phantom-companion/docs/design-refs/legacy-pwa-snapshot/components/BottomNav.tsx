import { NavLink, useLocation } from 'react-router-dom';
import { GlassCard } from './GlassCard';
import type { PhantomTheme } from '@/types/theme';

// Nav v4 — Chat-first. Operator complaint 2026-05-04 "як мені з ним
// спілкуватися?" — there was no primary text-chat surface, only Voice
// (PTT) + Now (dashboard). Replacing Now with Chat as the front door.
// Now-dashboard remains reachable from the UtilityDrawer for vitals.
//
// 5 primary destinations:
//   Chat   — text + paste + image + share-target landing
//   Voice  — push-to-talk (existing)
//   Map    — phone + paired hosts on one tactical map
//   Vault  — server cards + GHOST records
//   Me     — profile manager + settings
//
// Senses / Driver / Ambient / Diag / Now-dashboard live in the
// UtilityDrawer.

interface NavTab {
  id: string;
  label: string;
  icon: string;
  to: string;
}

const TABS: NavTab[] = [
  { id: 'chat', label: 'Чат', icon: '✉', to: '/chat' },
  { id: 'voice', label: 'Voice', icon: '◐', to: '/voice' },
  { id: 'map', label: 'Map', icon: '◇', to: '/map' },
  { id: 'vault', label: 'Vault', icon: '◈', to: '/vault' },
  { id: 'me', label: 'Me', icon: '◍', to: '/me' },
];

interface BottomNavProps {
  theme: PhantomTheme;
  accent: string;
}

export function BottomNav({ theme, accent }: BottomNavProps) {
  const location = useLocation();

  return (
    <div
      style={{
        padding: '6px 10px 10px',
        flexShrink: 0,
      }}
    >
      <GlassCard
        level="card"
        theme={theme}
        style={{ borderRadius: 26, padding: '6px 4px' }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'stretch',
          }}
        >
          {TABS.map((t) => {
            const active =
              location.pathname === t.to ||
              location.pathname.startsWith(t.to + '/');
            return (
              <NavLink
                key={t.id}
                to={t.to}
                style={{
                  flex: 1,
                  textDecoration: 'none',
                  minWidth: 0,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 2,
                    padding: '8px 0 6px',
                    position: 'relative',
                    minHeight: 50,
                  }}
                >
                  {active && (
                    <div
                      style={{
                        position: 'absolute',
                        top: 0,
                        left: '24%',
                        right: '24%',
                        height: 2,
                        background: accent,
                        borderRadius: 2,
                        boxShadow: `0 0 8px ${accent}`,
                      }}
                    />
                  )}
                  <div
                    style={{
                      fontSize: 16,
                      color: active ? accent : theme.ink3,
                      lineHeight: 1,
                    }}
                  >
                    {t.icon}
                  </div>
                  <div
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 9,
                      letterSpacing: '0.16em',
                      textTransform: 'uppercase',
                      fontWeight: 700,
                      color: active ? theme.ink : theme.ink3,
                    }}
                  >
                    {t.label}
                  </div>
                </div>
              </NavLink>
            );
          })}
        </div>
      </GlassCard>
    </div>
  );
}
