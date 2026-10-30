import { useNavigate, useParams } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { StatePill } from '@/components/StatePill';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useCommsStore } from '@/state/commsStore';
import { useT } from '@/hooks/useT';
import type { PhantomTheme } from '@/types/theme';

interface InCallScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

interface CallActionProps {
  color: string;
  icon: string;
  label: string;
  theme: PhantomTheme;
  ai?: boolean;
  onClick?: () => void;
}

function CallAction({ color, icon, label, theme, ai = false, onClick }: CallActionProps) {
  return (
    <button
      onClick={onClick}
      style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}
    >
      <div
        style={{
          width: 64,
          height: 64,
          borderRadius: '50%',
          background: `radial-gradient(circle at 30% 25%, ${color}, ${color}cc)`,
          boxShadow: `0 10px 24px ${color}55`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#fff',
          fontSize: 24,
          fontWeight: 600,
          border: ai ? `2px dashed ${color}aa` : 'none',
        }}
      >
        {icon}
      </div>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 9.5,
          letterSpacing: '0.18em',
          textTransform: 'uppercase',
          fontWeight: 700,
          color: theme.ink2,
        }}
      >
        {label}
      </div>
    </button>
  );
}

export function InCallScreen({ themeOverride, framed = false }: InCallScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const navigate = useNavigate();
  const { peer } = useParams<{ peer?: string }>();
  const { accent } = useStateAccent('DIALOGUE');
  const t = useT();

  const peerRow = useCommsStore((s) => s.rows.find((r) => r.id === peer));
  const initials = peerRow ? peerRow.name.slice(0, 2).toUpperCase() : '··';
  const name = peerRow ? peerRow.name : peer ? decodeURIComponent(peer) : '··';

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          padding: '14px 18px 18px',
          gap: 12,
        }}
      >
        <div style={{ position: 'relative', marginTop: 2 }}>
          <div style={{ position: 'absolute', inset: -10, borderRadius: '50%', border: `4px solid ${accent}`, opacity: 0.4 }} />
          <div
            style={{
              width: 88,
              height: 88,
              borderRadius: '50%',
              background: `linear-gradient(135deg, ${accent}55, ${accent}22)`,
              border: `2px solid ${accent}`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontFamily: 'var(--serif)',
              fontStyle: 'italic',
              fontSize: 32,
              fontWeight: 600,
              color: accent,
            }}
          >
            {initials}
          </div>
        </div>

        <div style={{ textAlign: 'center', marginTop: 2 }}>
          <div style={{ fontSize: 22, fontWeight: 700, color: theme.ink, letterSpacing: '-0.01em' }}>{name}</div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, color: theme.ink2, marginTop: 4 }}>
            {peerRow?.duration ?? t('incall.incoming')}
          </div>
        </div>

        <StatePill stateKey="DIALOGUE" accent={accent} theme={theme} sub="dialogue" />

        {peerRow?.aiSummary ? (
          <GlassCard level="elevated" theme={theme} accentBorder accent={accent} style={{ width: '100%', padding: '14px 16px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 9.5,
                letterSpacing: '0.22em',
                fontWeight: 700,
                color: accent,
                textTransform: 'uppercase',
                marginBottom: 10,
              }}
            >
              {t('incall.context')}
            </div>
            <div
              style={{
                fontFamily: 'var(--serif)',
                fontStyle: 'italic',
                fontSize: 14,
                color: theme.ink,
                lineHeight: 1.4,
              }}
            >
              {peerRow.aiSummary}
            </div>
          </GlassCard>
        ) : (
          <GlassCard level="elevated" theme={theme} style={{ width: '100%', padding: 0 }}>
            <EmptyState theme={theme} icon="◌" title={t('incall.no_context.title')} hint={t('incall.no_context.hint')} compact />
          </GlassCard>
        )}

        <div style={{ flex: 1 }} />

        <div style={{ display: 'flex', gap: 24, alignItems: 'center', marginBottom: 16 }}>
          <CallAction color={theme.coral} icon="✕" label={t('incall.decline')} theme={theme} onClick={() => navigate(-1)} />
          <CallAction color={accent} icon="⚙" label={t('incall.ai')} theme={theme} ai onClick={() => navigate(-1)} />
          <CallAction color="#16A34A" icon="✓" label={t('incall.answer')} theme={theme} onClick={() => navigate(-1)} />
        </div>
      </div>
    </PhoneShell>
  );
}
