import { useRef } from 'react';
import { Link } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { OrbView } from '@/components/OrbView';
import { StatePill } from '@/components/StatePill';
import { VitalsRow } from '@/components/VitalsRow';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { Skeleton } from '@/components/Skeleton';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useSensorStore } from '@/state/sensorStore';
import { useToastStore } from '@/state/toastStore';
import { useConnectionStore } from '@/state/connectionStore';
import { wsHub } from '@/services/wsHub';
import { usePullToRefresh } from '@/hooks/usePullToRefresh';
import { useT } from '@/hooks/useT';
import { useChannelLoading } from '@/hooks/useChannelLoading';
import type { PhantomTheme } from '@/types/theme';
import type { SystemStateKey } from '@/types/state';

interface PulseScreenProps {
  themeOverride?: PhantomTheme;
  stateOverride?: SystemStateKey;
  framed?: boolean;
}

export function PulseScreen({ themeOverride, stateOverride, framed = false }: PulseScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { stateKey, accent, motionScale } = useStateAccent(stateOverride);
  const quote = useSensorStore((s) => s.quote);
  const vitals = useSensorStore((s) => s.vitals);
  const connStatus = useConnectionStore((s) => s.status);
  const connError = useConnectionStore((s) => s.lastError);
  const t = useT();
  const scrollRef = useRef<HTMLDivElement>(null);
  const isLoading = useChannelLoading('sensor');

  // Polish — explicit connection banner so the operator never sees a
  // blank Pulse screen and concludes "the app is broken". The reasons
  // for empty vitals are 99% the same: WS not online, or desktop has
  // no ESP32 + no body sensors emitting on the `sensor` channel.
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
        hint: connError ? `${connError}` : 'Перевір WiFi на телефоні і phantom-os',
      };
    }
    if (connStatus === 'online' && !vitals) {
      return {
        dot: '#F59E0B',
        text: 'WS онлайн, але десктоп ще не шле vitals',
        hint: 'ESP32 не активний / body-channel порожній. /diag покаже причину.',
      };
    }
    return null;
  })();

  const { pulling, pullDist, refreshing } = usePullToRefresh(scrollRef, {
    onRefresh: () => {
      const ok = wsHub.send('agent', 'request_snapshot', {});
      if (!ok) useToastStore.getState().push('warn', t('pulse.toast.offline'));
      else useToastStore.getState().push('info', t('pulse.toast.refreshing'));
    },
  });

  const handleAction = (id: string) => {
    if (!wsHub.send('agent', 'action', { id })) {
      useToastStore.getState().push('warn', t('pulse.toast.queued'));
    }
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        ref={scrollRef}
        style={{
          flex: 1,
          padding: '8px 16px 0',
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
          minHeight: 0,
          overflowY: 'auto',
          transform: `translateY(${pullDist}px)`,
          transition: pulling ? 'none' : 'transform 0.18s ease-out',
        }}
        className="ph-scroll"
      >
        {(pulling || refreshing) && (
          <div style={{ height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center', color: theme.ink3, fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.2em', textTransform: 'uppercase' }}>
            {refreshing ? t('pulse.refresh.busy') : t('pulse.refresh.idle')}
          </div>
        )}

        {banner && (
          <Link
            to="/diag"
            style={{ textDecoration: 'none' }}
          >
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
                <div style={{ fontSize: 13, color: theme.ink, fontWeight: 600 }}>{banner.text}</div>
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

        {isLoading && (
          <GlassCard level="subtle" theme={theme} style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', gap: 8 }}>
              <Skeleton theme={theme} height={56} radius={12} />
              <Skeleton theme={theme} height={56} radius={12} />
              <Skeleton theme={theme} height={56} radius={12} />
            </div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3, textAlign: 'center', letterSpacing: '0.2em', textTransform: 'uppercase', marginTop: 4 }}>
              {t('pulse.skeleton.label')}
            </div>
          </GlassCard>
        )}

        {!isLoading && <VitalsRow theme={theme} accent={accent} />}

        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            position: 'relative',
            minHeight: 200,
          }}
        >
          <OrbView accent={accent} theme={theme} motionScale={motionScale} size={210} ringCount={2} />
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

        <GlassCard level="panel" theme={theme} accentBorder accent={accent} style={{ padding: '12px 16px' }}>
          <StatePill
            stateKey={stateKey}
            accent={accent}
            theme={theme}
            sub={`motion ×${motionScale.toFixed(2)}`}
            expanded
            style={{ width: '100%', borderRadius: 14, background: 'transparent', border: 'none', padding: 0 }}
          />
        </GlassCard>

        {quote ? (
          <GlassCard level="card" theme={theme} style={{ padding: '14px 16px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: accent,
                fontWeight: 600,
                marginBottom: 8,
              }}
            >
              {quote.horizon}
            </div>
            <div
              style={{
                fontFamily: 'var(--serif)',
                fontStyle: 'italic',
                fontSize: 16,
                lineHeight: 1.45,
                color: theme.ink,
                fontWeight: 500,
              }}
            >
              {quote.text}
            </div>
            {quote.actions.length > 0 && (
              <div style={{ display: 'flex', gap: 6, marginTop: 12, flexWrap: 'wrap' }}>
                {quote.actions.map((b) => (
                  <button
                    key={b.id}
                    onClick={() => handleAction(b.id)}
                    style={{
                      padding: '7px 11px',
                      borderRadius: 999,
                      fontFamily: 'var(--mono)',
                      fontSize: 10,
                      fontWeight: 700,
                      letterSpacing: '0.1em',
                      textTransform: 'uppercase',
                      background: b.primary ? accent : 'transparent',
                      color: b.primary ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
                      border: `1px solid ${b.primary ? accent : theme.glassBorder}`,
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
            )}
          </GlassCard>
        ) : (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="✷"
              title={t('pulse.no_quote.title')}
              hint={t('pulse.no_quote.hint')}
              compact
            />
          </GlassCard>
        )}
      </div>

      <BottomNav theme={theme} accent={accent} />
    </PhoneShell>
  );
}
