// /now — primary dashboard. Replaces /pulse + /notifications glance.
//
// Three layers, top to bottom:
//   1. Pulse hero — orb + state pill + vitals row OR connection
//      banner if backend has nothing to show.
//   2. Latest 3 inbox items — proactive lines / replies. Tap → full
//      `/inbox` (alias of /notifications).
//   3. Quick actions row — speak (PTT shortcut), drive (open Driver),
//      capture (Senses), vault (Vault). Reflects the operator's
//      most-touched verbs without forcing them through the drawer.
//
// Empty states are explicit — operator never sees a blank dashboard
// with no explanation.

import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { OrbView } from '@/components/OrbView';
import { StatePill } from '@/components/StatePill';
import { VitalsRow } from '@/components/VitalsRow';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { WeatherCard } from '@/components/WeatherCard';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useSensorStore } from '@/state/sensorStore';
import { useConnectionStore } from '@/state/connectionStore';
import { useMapStore } from '@/state/mapStore';
import { useDeviceGeolocation } from '@/hooks/useDeviceGeolocation';
import {
  listRecentNotifications,
  type NotificationItem,
} from '@/services/notificationsClient';
import { wsHub, type WsInboundFrame } from '@/services/wsHub';
import type { PhantomTheme } from '@/types/theme';
import type { SystemStateKey } from '@/types/state';

interface NowScreenProps {
  themeOverride?: PhantomTheme;
  stateOverride?: SystemStateKey;
  framed?: boolean;
}

const QUICK_ACTIONS = [
  { to: '/voice', icon: '◐', label: 'Voice' },
  { to: '/notes', icon: '≡', label: 'Notes' },
  { to: '/timer', icon: '⏱', label: 'Timer' },
  { to: '/senses', icon: '◑', label: 'Senses' },
  { to: '/vault', icon: '◈', label: 'Vault' },
  { to: '/driver', icon: '◬', label: 'Drive' },
];

/** Standalone capabilities the operator can use right now even
 *  without phantom-os. Surfaced when connection is unpaired/offline
 *  so they don't see a blank screen with just "не пейрнуто". */
const STANDALONE_CARDS = [
  { to: '/notes', icon: '≡', label: 'Нотатки', hint: 'Markdown, локально' },
  { to: '/timer', icon: '⏱', label: 'Таймер', hint: 'Pomodoro / countdown' },
  { to: '/map', icon: '◇', label: 'Мапа', hint: 'Пошук, маршрут, POI' },
  { to: '/vault', icon: '◈', label: 'Vault', hint: 'Зашифровані картки' },
  { to: '/senses', icon: '◑', label: 'Senses', hint: 'Камера, OCR' },
  { to: '/chat', icon: '✉', label: 'Чат', hint: 'Якщо ввести Gemini ключ' },
];

function relativeWhen(iso: string, now: number): string {
  const ts = new Date(iso).getTime();
  const dt = Math.max(0, now - ts);
  if (dt < 60_000) return `${Math.round(dt / 1000)}s`;
  if (dt < 3_600_000) return `${Math.round(dt / 60_000)}m`;
  if (dt < 86_400_000) return `${Math.round(dt / 3_600_000)}h`;
  return `${Math.round(dt / 86_400_000)}d`;
}

export function NowScreen({ themeOverride, stateOverride, framed = false }: NowScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  // Wire phone GPS so WeatherCard + StandaloneGrid (and any future
  // location-based widget) gets a fix without each screen subscribing
  // to navigator.geolocation independently.
  useDeviceGeolocation();
  const deviceLocation = useMapStore((s) => s.deviceLocation);
  const { stateKey, accent, motionScale } = useStateAccent(stateOverride);

  const vitals = useSensorStore((s) => s.vitals);
  const quote = useSensorStore((s) => s.quote);
  const connStatus = useConnectionStore((s) => s.status);
  const [inbox, setInbox] = useState<NotificationItem[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const lastFetchedRef = useRef(0);

  // Pull recent inbox once on mount, then again every 60s. Lighter
  // than /inbox's full pull (limit=3) so this stays cheap.
  useEffect(() => {
    const tick = async () => {
      if (connStatus !== 'online' && connStatus !== 'connecting') return;
      if (Date.now() - lastFetchedRef.current < 30_000) return;
      lastFetchedRef.current = Date.now();
      try {
        const items = await listRecentNotifications({ limit: 3 });
        setInbox(items);
      } catch {
        // silent — banner handles connection problems
      }
    };
    void tick();
    const id = setInterval(() => void tick(), 60_000);
    const clock = setInterval(() => setNow(Date.now()), 30_000);
    return () => {
      clearInterval(id);
      clearInterval(clock);
    };
  }, [connStatus]);

  // Live prepend on proactive broadcasts.
  useEffect(() => {
    const off = wsHub.subscribe('chat', (frame: WsInboundFrame) => {
      if (frame.type !== 'message.proactive') return;
      const data = frame.data as { id?: string; text?: string; horizon?: string };
      if (!data?.text) return;
      const synth: NotificationItem = {
        id: data.id ?? `live-${Date.now()}`,
        session_id: '',
        role: 'assistant',
        content: data.text,
        response_form: 'text',
        created_at: new Date().toISOString(),
        meta: null,
        is_proactive: true,
        horizon: data.horizon ?? null,
      };
      setInbox((prev) => {
        if (prev.some((p) => p.id === synth.id)) return prev;
        return [synth, ...prev].slice(0, 3);
      });
    });
    return () => off();
  }, []);

  const banner = (() => {
    if (connStatus === 'unpaired') {
      return { dot: '#9CA3AF', text: 'Не підв\'язано до phantom-os', hint: 'Сканувати QR' };
    }
    if (connStatus === 'connecting') {
      return { dot: '#F59E0B', text: 'Підключаюсь до десктопа…', hint: null };
    }
    if (connStatus === 'offline') {
      return {
        dot: '#EF4444',
        text: 'Десктоп недосяжний',
        hint: 'Перевір WiFi і phantom-os',
      };
    }
    return null;
  })();

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          padding: '6px 14px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          minHeight: 0,
          overflowY: 'auto',
        }}
      >
        {/* Standalone-capabilities grid — replaces the wall of nothing
            that the operator used to see when phantom-os was offline.
            Surfaces local-only verbs: Notes, Timer, Map, Vault, Senses,
            Chat (works only with direct API key — UI hints at /me). */}
        {connStatus !== 'online' && (
          <GlassCard level="card" theme={theme} accentBorder accent={accent} style={{ padding: '12px 14px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.22em',
                color: accent,
                fontWeight: 700,
                textTransform: 'uppercase',
                marginBottom: 8,
              }}
            >
              ◇ standalone · {connStatus}
            </div>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(3, 1fr)',
                gap: 6,
              }}
            >
              {STANDALONE_CARDS.map((c) => (
                <Link key={c.to} to={c.to} style={{ textDecoration: 'none' }}>
                  <div
                    style={{
                      padding: '10px 8px',
                      borderRadius: 10,
                      background: theme.glassPanel,
                      border: `1px solid ${theme.glassBorder}`,
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: 4,
                      minHeight: 72,
                    }}
                  >
                    <div style={{ fontSize: 22, color: theme.ink }}>{c.icon}</div>
                    <div
                      style={{
                        fontFamily: 'var(--mono)',
                        fontSize: 10,
                        letterSpacing: '0.16em',
                        color: theme.ink,
                        fontWeight: 700,
                        textTransform: 'uppercase',
                      }}
                    >
                      {c.label}
                    </div>
                    <div style={{ fontSize: 9, color: theme.ink3, textAlign: 'center', lineHeight: 1.2 }}>
                      {c.hint}
                    </div>
                  </div>
                </Link>
              ))}
            </div>
          </GlassCard>
        )}

        {/* Weather — pure local API (Open-Meteo) so it shows up
            regardless of phantom-os pairing as soon as we have a
            phone GPS fix. */}
        {deviceLocation && (
          <WeatherCard theme={theme} lat={deviceLocation.lat} lon={deviceLocation.lon} />
        )}

        {banner && (
          <Link to="/diag" style={{ textDecoration: 'none' }}>
            <GlassCard
              level="subtle"
              theme={theme}
              style={{ padding: '10px 12px', display: 'flex', gap: 10, alignItems: 'flex-start' }}
            >
              <span
                aria-hidden
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  background: banner.dot,
                  boxShadow: `0 0 8px ${banner.dot}88`,
                  flexShrink: 0,
                  marginTop: 4,
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, color: theme.ink, fontWeight: 600 }}>
                  {banner.text}
                </div>
                {banner.hint && (
                  <div style={{ fontSize: 11, color: theme.ink3, marginTop: 2, lineHeight: 1.4 }}>
                    {banner.hint}
                  </div>
                )}
              </div>
              <div style={{ fontSize: 10, color: theme.ink3, fontFamily: 'var(--mono)' }}>/diag →</div>
            </GlassCard>
          </Link>
        )}

        {vitals && <VitalsRow theme={theme} accent={accent} />}

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            position: 'relative',
            minHeight: 180,
          }}
        >
          <OrbView accent={accent} theme={theme} motionScale={motionScale} size={170} ringCount={2} />
          <div
            style={{
              position: 'absolute',
              bottom: -2,
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.2em',
              color: theme.ink3,
              textTransform: 'uppercase',
            }}
          >
            breathing · {stateKey.toLowerCase()}
          </div>
        </div>

        <GlassCard
          level="panel"
          theme={theme}
          accentBorder
          accent={accent}
          style={{ padding: '10px 14px' }}
        >
          <StatePill
            stateKey={stateKey}
            accent={accent}
            theme={theme}
            sub={`motion ×${motionScale.toFixed(2)}`}
            expanded
            style={{ width: '100%', borderRadius: 14, background: 'transparent', border: 'none', padding: 0 }}
          />
        </GlassCard>

        {quote && (
          <GlassCard level="card" theme={theme} style={{ padding: '12px 14px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 9,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: accent,
                fontWeight: 700,
                marginBottom: 6,
              }}
            >
              {quote.horizon}
            </div>
            <div
              style={{
                fontFamily: 'var(--serif)',
                fontStyle: 'italic',
                fontSize: 15,
                lineHeight: 1.45,
                color: theme.ink,
                fontWeight: 500,
              }}
            >
              {quote.text}
            </div>
          </GlassCard>
        )}

        {/* Inbox preview — last 3 items */}
        <div style={{ display: 'flex', alignItems: 'baseline', padding: '0 4px' }}>
          <span
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 700,
            }}
          >
            Inbox
          </span>
          <Link
            to="/inbox"
            style={{
              marginLeft: 'auto',
              fontFamily: 'var(--mono)',
              fontSize: 10,
              color: theme.primary,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
              textDecoration: 'none',
              fontWeight: 700,
            }}
          >
            відкрити →
          </Link>
        </div>

        {inbox.length === 0 && connStatus === 'online' && (
          <GlassCard level="subtle" theme={theme} style={{ padding: '12px 14px' }}>
            <div style={{ fontSize: 12, color: theme.ink3, lineHeight: 1.45 }}>
              Поки тиша. Тут зʼявляться відповіді PHANTOM і проактивні лінії.
            </div>
          </GlassCard>
        )}
        {inbox.map((it) => (
          <Link key={it.id} to="/inbox" style={{ textDecoration: 'none', color: theme.ink }}>
            <GlassCard level="subtle" theme={theme} style={{ padding: '10px 12px' }}>
              <div
                style={{
                  display: 'flex',
                  gap: 8,
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  color: theme.ink3,
                  fontWeight: 700,
                  marginBottom: 4,
                }}
              >
                <span style={{ color: it.is_proactive ? theme.primary : theme.ink3 }}>
                  {it.is_proactive ? 'proactive' : it.role}
                </span>
                <span style={{ marginLeft: 'auto' }}>{relativeWhen(it.created_at, now)} ago</span>
              </div>
              <div
                style={{
                  fontSize: 13,
                  color: theme.ink,
                  lineHeight: 1.4,
                  display: '-webkit-box',
                  WebkitBoxOrient: 'vertical',
                  WebkitLineClamp: 2,
                  overflow: 'hidden',
                }}
              >
                {it.content}
              </div>
            </GlassCard>
          </Link>
        ))}

        {/* Quick actions */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(4, 1fr)',
            gap: 8,
            paddingBottom: 6,
          }}
        >
          {QUICK_ACTIONS.map((a) => (
            <Link
              key={a.to}
              to={a.to}
              style={{ textDecoration: 'none', color: theme.ink }}
            >
              <GlassCard
                level="subtle"
                theme={theme}
                style={{
                  padding: '12px 6px',
                  textAlign: 'center',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 4,
                  alignItems: 'center',
                  minHeight: 64,
                  justifyContent: 'center',
                }}
              >
                <div style={{ fontSize: 18, color: theme.primary, lineHeight: 1 }}>{a.icon}</div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    letterSpacing: '0.16em',
                    textTransform: 'uppercase',
                    color: theme.ink2,
                    fontWeight: 700,
                  }}
                >
                  {a.label}
                </div>
              </GlassCard>
            </Link>
          ))}
        </div>
      </div>

      <BottomNav theme={theme} accent={accent} />
    </PhoneShell>
  );
}
