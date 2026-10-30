// /notifications — scrollable inbox of assistant + proactive lines.
//
// Pulls from `/api/v1/notifications/recent` on mount and after every
// pull-to-refresh. The desktop also broadcasts `chat / message.proactive`
// over WS — when one arrives we prepend it locally so the operator
// sees instant updates without re-fetching.
//
// Filter chips: All / Proactive / Replies. The phone's last-seen
// cursor is persisted under secureStore so re-opening the inbox
// shows a "new since X" divider.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import {
  listRecentNotifications,
  type NotificationItem,
} from '@/services/notificationsClient';
import { wsHub, type WsInboundFrame } from '@/services/wsHub';
import { secureGet, secureSet } from '@/services/native/secureStore';
import { fireNotification } from '@/services/native';
import type { PhantomTheme } from '@/types/theme';

interface NotificationsScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

type Filter = 'all' | 'proactive' | 'reply';
const LAST_SEEN_KEY = 'notifications.lastSeenMs';

function relativeWhen(iso: string, now: number): string {
  const ts = new Date(iso).getTime();
  const dt = Math.max(0, now - ts);
  if (dt < 60_000) return `${Math.round(dt / 1000)}s`;
  if (dt < 3_600_000) return `${Math.round(dt / 60_000)}m`;
  if (dt < 86_400_000) return `${Math.round(dt / 3_600_000)}h`;
  return `${Math.round(dt / 86_400_000)}d`;
}

export function NotificationsScreen({ themeOverride, framed = false }: NotificationsScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const navigate = useNavigate();

  const [items, setItems] = useState<NotificationItem[]>([]);
  const [filter, setFilter] = useState<Filter>('all');
  const [loading, setLoading] = useState(false);
  const [lastSeenMs, setLastSeenMs] = useState<number>(0);
  const [now, setNow] = useState(() => Date.now());

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const next = await listRecentNotifications({ limit: 100 });
      setItems(next);
    } catch (err) {
      useToastStore.getState().push('error', `Inbox: ${(err as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      const raw = await secureGet(LAST_SEEN_KEY);
      const ts = raw ? Number(raw) : 0;
      setLastSeenMs(Number.isFinite(ts) ? ts : 0);
    })();
    void refresh();
    const tick = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(tick);
  }, [refresh]);

  // Live pre-pend: when desktop broadcasts a proactive line, splice
  // it in without a round-trip. Backend persists it too, so the next
  // refresh would also catch it — this just makes it instant.
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
      setItems((prev) => {
        if (prev.some((p) => p.id === synth.id)) return prev;
        return [synth, ...prev].slice(0, 100);
      });
      // Fire a system buzz when the screen isn't currently visible.
      if (document.hidden) {
        void fireNotification({
          title: 'PHANTOM',
          body: synth.content,
          data: { route: '/notifications', sourceId: synth.id },
        });
      }
    });
    return () => off();
  }, []);

  // Mark everything visible as read on mount + when new items arrive.
  useEffect(() => {
    if (!items.length) return;
    const newest = Math.max(...items.map((i) => new Date(i.created_at).getTime()));
    if (newest > lastSeenMs) {
      setLastSeenMs(newest);
      void secureSet(LAST_SEEN_KEY, String(newest));
    }
  }, [items, lastSeenMs]);

  const filtered = useMemo(() => {
    if (filter === 'proactive') return items.filter((i) => i.is_proactive);
    if (filter === 'reply') return items.filter((i) => !i.is_proactive && i.role === 'assistant');
    return items;
  }, [items, filter]);

  const unseenCount = useMemo(
    () => items.filter((i) => new Date(i.created_at).getTime() > lastSeenMs).length,
    [items, lastSeenMs],
  );

  const chipStyle = (active: boolean): React.CSSProperties => ({
    minHeight: 32,
    padding: '0 12px',
    borderRadius: 999,
    border: `1px solid ${active ? theme.primary : theme.glassBorder}`,
    background: active ? theme.primary : 'transparent',
    color: active ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
    fontFamily: 'var(--mono)',
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.16em',
    textTransform: 'uppercase',
  });

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '10px 16px 4px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span
            style={{
              fontSize: 26,
              fontWeight: 700,
              color: theme.ink,
              letterSpacing: '-0.02em',
            }}
          >
            Inbox
          </span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color: theme.ink3 }}>
            {items.length} · нових {unseenCount}
          </span>
        </div>
        <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
          <button onClick={() => setFilter('all')} style={chipStyle(filter === 'all')}>
            All
          </button>
          <button
            onClick={() => setFilter('proactive')}
            style={chipStyle(filter === 'proactive')}
          >
            Proactive
          </button>
          <button onClick={() => setFilter('reply')} style={chipStyle(filter === 'reply')}>
            Replies
          </button>
          <button
            onClick={() => void refresh()}
            disabled={loading}
            style={{
              ...chipStyle(false),
              marginLeft: 'auto',
              opacity: loading ? 0.5 : 1,
            }}
          >
            {loading ? '…' : 'sync'}
          </button>
        </div>
      </div>

      <div
        style={{
          flex: 1,
          padding: '8px 12px 14px',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minHeight: 0,
          overflowY: 'auto',
        }}
        className="ph-scroll"
      >
        {loading && items.length === 0 && (
          <GlassCard level="card" theme={theme} style={{ padding: 18, textAlign: 'center' }}>
            <div style={{ color: theme.ink2 }}>Читаю…</div>
          </GlassCard>
        )}
        {!loading && filtered.length === 0 && (
          <GlassCard level="card" theme={theme} style={{ padding: 22, textAlign: 'center' }}>
            <div style={{ color: theme.ink2, marginBottom: 4 }}>Inbox порожній</div>
            <div style={{ fontSize: 11, color: theme.ink3 }}>
              Тут з'являться відповіді PHANTOM і проактивні лінії
            </div>
          </GlassCard>
        )}
        {filtered.map((it) => {
          const isUnseen = new Date(it.created_at).getTime() > lastSeenMs;
          return (
            <GlassCard
              key={it.id}
              level={isUnseen ? 'card' : 'subtle'}
              theme={theme}
              accentBorder={isUnseen}
              accent={theme.primary}
              style={{ padding: '12px 14px' }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  marginBottom: 6,
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  color: theme.ink3,
                  fontWeight: 700,
                }}
              >
                <span style={{ color: it.is_proactive ? theme.primary : theme.ink3 }}>
                  {it.is_proactive ? 'proactive' : it.role}
                </span>
                {it.horizon && <span>· {it.horizon}</span>}
                <span style={{ marginLeft: 'auto' }}>{relativeWhen(it.created_at, now)} ago</span>
              </div>
              <div
                style={{
                  fontSize: 14,
                  color: theme.ink,
                  lineHeight: 1.45,
                  whiteSpace: 'pre-wrap',
                }}
              >
                {it.content}
              </div>
              {it.session_id && (
                <button
                  onClick={() => navigate(`/voice?session=${encodeURIComponent(it.session_id)}`)}
                  style={{
                    marginTop: 8,
                    minHeight: 32,
                    padding: '0 12px',
                    borderRadius: 8,
                    border: `1px solid ${theme.glassBorder}`,
                    background: 'transparent',
                    color: theme.ink2,
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    fontWeight: 700,
                    letterSpacing: '0.16em',
                    textTransform: 'uppercase',
                  }}
                >
                  open chat
                </button>
              )}
            </GlassCard>
          );
        })}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
