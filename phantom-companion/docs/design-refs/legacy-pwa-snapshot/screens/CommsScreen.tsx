import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { Modal } from '@/components/Modal';
import { AnimatedTabs } from '@/components/AnimatedTabs';
import { Skeleton } from '@/components/Skeleton';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useT } from '@/hooks/useT';
import { useChannelLoading } from '@/hooks/useChannelLoading';
import { useCommsStore, type CommsRow as CommsRowData, type CommsTab } from '@/state/commsStore';
import { wsHub } from '@/services/wsHub';
import { useToastStore } from '@/state/toastStore';
import type { PhantomTheme } from '@/types/theme';

const TABS: CommsTab[] = ['CALL', 'SMS', 'PHANTOM', 'TG/SIG/WA'];
const ICONS: Record<CommsRowData['kind'], string> = {
  call: '↗',
  voicemail: '⊘',
  phantom: '◉',
  sms: '✉',
  tg: '⋈',
};

const LONG_PRESS_MS = 550;

function CommsRow({
  r,
  theme,
  accent,
  onOpen,
  onPlayVoicemail,
  onDial,
}: {
  r: CommsRowData;
  theme: PhantomTheme;
  accent: string;
  onOpen: (r: CommsRowData) => void;
  onPlayVoicemail: (r: CommsRowData) => void;
  onDial: (r: CommsRowData) => void;
}) {
  const isHot = r.hot;
  const isPhantom = r.kind === 'phantom';
  const left = isHot ? theme.coral : isPhantom ? accent : 'transparent';
  const t = useT();
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressFired = useRef(false);

  const startLongPress = () => {
    if (r.kind !== 'call' && r.kind !== 'voicemail') return;
    longPressFired.current = false;
    longPressTimer.current = setTimeout(() => {
      longPressFired.current = true;
      onDial(r);
    }, LONG_PRESS_MS);
  };
  const cancelLongPress = () => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  };
  const handleClick = () => {
    if (longPressFired.current) {
      longPressFired.current = false;
      return;
    }
    onOpen(r);
  };

  return (
    <GlassCard
      level="card"
      theme={theme}
      accentBorder={isPhantom}
      accent={accent}
      onClick={handleClick}
      style={{ padding: '12px 14px', position: 'relative', cursor: 'pointer' }}
      onPointerDown={startLongPress}
      onPointerUp={cancelLongPress}
      onPointerLeave={cancelLongPress}
      onPointerCancel={cancelLongPress}
    >
      {left !== 'transparent' && (
        <div style={{ position: 'absolute', left: 0, top: 12, bottom: 12, width: 3, background: left, borderRadius: 2 }} />
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div
          style={{
            width: 30,
            height: 30,
            borderRadius: 10,
            background: isPhantom ? `${accent}22` : `${theme.ink3}22`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: isPhantom ? accent : isHot ? theme.coral : theme.ink2,
            fontSize: 14,
          }}
        >
          {ICONS[r.kind]}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span
              style={{
                fontWeight: 700,
                fontSize: 14,
                color: theme.ink,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {r.name}
            </span>
            <span style={{ marginLeft: 'auto', fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>{r.time}</span>
          </div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, marginTop: 2, letterSpacing: '0.04em' }}>
            {r.duration}
            {isHot && <span style={{ color: theme.coral, fontWeight: 700 }}> · ✱HOT✱</span>}
          </div>
          <div style={{ fontSize: 12.5, color: theme.ink2, marginTop: 4, lineHeight: 1.35 }}>{r.preview}</div>
          {r.aiSummary && (
            <div
              style={{
                fontFamily: 'var(--serif)',
                fontStyle: 'italic',
                fontSize: 12.5,
                color: accent,
                marginTop: 4,
                lineHeight: 1.35,
              }}
            >
              AI · {r.aiSummary}
            </div>
          )}
          {r.kind === 'voicemail' && (
            <div style={{ marginTop: 8 }}>
              {r.audioUrl ? (
                <audio
                  controls
                  src={r.audioUrl}
                  preload="metadata"
                  onClick={(e) => e.stopPropagation()}
                  style={{ width: '100%', height: 28 }}
                />
              ) : (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onPlayVoicemail(r);
                  }}
                  style={{
                    padding: '4px 12px',
                    borderRadius: 999,
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                    fontWeight: 700,
                    letterSpacing: '0.16em',
                    textTransform: 'uppercase',
                    background: 'transparent',
                    color: theme.ink2,
                    border: `1px solid ${theme.glassBorder}`,
                  }}
                >
                  ▶ {t('comms.action.play')}
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </GlassCard>
  );
}

interface CommsScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function CommsScreen({ themeOverride, framed = false }: CommsScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { accent } = useStateAccent();
  const navigate = useNavigate();
  const t = useT();
  const isLoading = useChannelLoading('comms');

  const rows = useCommsStore((s) => s.rows);
  const activeTab = useCommsStore((s) => s.activeTab);
  const setTab = useCommsStore((s) => s.setTab);
  const enabledChannels = useCommsStore((s) => s.enabledChannels);

  const [composeOpen, setComposeOpen] = useState(false);
  const [composeTo, setComposeTo] = useState('');
  const [composeBody, setComposeBody] = useState('');

  const filtered = rows.filter((r) => {
    if (activeTab === 'CALL') return r.kind === 'call' || r.kind === 'voicemail';
    if (activeTab === 'SMS') return r.kind === 'sms';
    if (activeTab === 'PHANTOM') return r.kind === 'phantom';
    return r.kind === 'tg';
  });
  const tabEnabled = enabledChannels[activeTab];

  const handleOpenRow = (r: CommsRowData) => {
    if (r.kind === 'call' || r.kind === 'voicemail') navigate(`/call/${r.id}`);
    if (r.kind === 'phantom') {
      useToastStore.getState().push('info', t('map.toast.opened'));
    }
  };

  const handlePlayVoicemail = (r: CommsRowData) => {
    if (!wsHub.send('comms', 'fetch_audio', { id: r.id })) {
      useToastStore.getState().push('warn', t('comms.toast.playback_queued'));
    } else {
      useToastStore.getState().push('info', t('comms.toast.playback_queued'));
    }
  };

  const handleDial = (r: CommsRowData) => {
    const peer = r.phoneNumber ?? r.name;
    if (!wsHub.send('comms', 'dial', { peer })) {
      useToastStore.getState().push('warn', t('comms.toast.dial_queued'));
    } else {
      useToastStore.getState().push('success', t('comms.toast.dial', { name: peer }));
    }
  };

  const sendCompose = () => {
    if (!composeTo || !composeBody) return;
    const ok = wsHub.send('comms', 'send', {
      channel: activeTab,
      to: composeTo,
      body: composeBody,
    });
    if (!ok) useToastStore.getState().push('warn', t('comms.toast.queued'));
    else useToastStore.getState().push('success', t('comms.toast.sent', { tab: activeTab }));
    setComposeTo('');
    setComposeBody('');
    setComposeOpen(false);
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '10px 16px 8px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 18, color: theme.ink, fontWeight: 700, letterSpacing: '-0.02em' }}>
            {t('comms.title')}
          </span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, letterSpacing: '0.18em' }}>
            {t('comms.active', { n: String(rows.length) })}
          </span>
        </div>
      </div>

      <div style={{ padding: '0 12px 8px' }}>
        <AnimatedTabs options={TABS} active={activeTab} onChange={setTab} accent={accent} theme={theme} />
      </div>

      <div
        style={{
          flex: 1,
          padding: '4px 12px 12px',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minHeight: 0,
          overflowY: 'auto',
        }}
        className="ph-scroll"
      >
        {!tabEnabled ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="⊘"
              title={t('comms.disabled.title', { tab: activeTab })}
              hint={t('comms.disabled.hint')}
            />
          </GlassCard>
        ) : isLoading ? (
          <>
            {[0, 1, 2].map((i) => (
              <GlassCard key={i} level="card" theme={theme} style={{ padding: '12px 14px' }}>
                <div style={{ display: 'flex', gap: 10 }}>
                  <Skeleton theme={theme} width={30} height={30} radius={10} />
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <Skeleton theme={theme} height={12} width="60%" />
                    <Skeleton theme={theme} height={10} width="40%" />
                    <Skeleton theme={theme} height={10} width="80%" />
                  </div>
                </div>
              </GlassCard>
            ))}
            <div style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3, textAlign: 'center', letterSpacing: '0.2em', textTransform: 'uppercase', marginTop: 4 }}>
              {t('comms.skeleton.label')}
            </div>
          </>
        ) : filtered.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState theme={theme} icon="◌" title={t('comms.empty.title')} hint={t('comms.empty.hint')} />
          </GlassCard>
        ) : (
          filtered.map((r) => (
            <CommsRow
              key={r.id}
              r={r}
              theme={theme}
              accent={accent}
              onOpen={handleOpenRow}
              onPlayVoicemail={handlePlayVoicemail}
              onDial={handleDial}
            />
          ))
        )}
        {tabEnabled && !isLoading && (
          <div style={{ position: 'sticky', bottom: 0, display: 'flex', justifyContent: 'flex-end', pointerEvents: 'none' }}>
            <button
              onClick={() => setComposeOpen(true)}
              style={{
                pointerEvents: 'auto',
                width: 52,
                height: 52,
                borderRadius: 18,
                background: `radial-gradient(circle at 30% 25%, ${accent}, ${accent}cc)`,
                boxShadow: `0 8px 22px ${accent}66`,
                color: theme.isDark ? '#1a1208' : '#fff',
                fontSize: 26,
                fontWeight: 300,
                lineHeight: 1,
              }}
              aria-label={t('comms.compose.aria')}
            >
              +
            </button>
          </div>
        )}
      </div>

      <BottomNav theme={theme} accent={accent} />

      <Modal open={composeOpen} onClose={() => setComposeOpen(false)} title={t('comms.compose.title', { tab: activeTab })}>
        <input
          type="text"
          value={composeTo}
          onChange={(e) => setComposeTo(e.target.value)}
          placeholder={t('comms.compose.to')}
          style={{
            width: '100%',
            padding: '11px 14px',
            borderRadius: 12,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--mono)',
            fontSize: 12,
            marginBottom: 10,
          }}
        />
        <textarea
          value={composeBody}
          onChange={(e) => setComposeBody(e.target.value)}
          rows={5}
          placeholder={t('comms.compose.body')}
          style={{
            width: '100%',
            padding: 12,
            borderRadius: 12,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--display)',
            fontSize: 14,
            resize: 'none',
          }}
        />
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <button
            onClick={() => setComposeOpen(false)}
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
            {t('comms.compose.cancel')}
          </button>
          <button
            onClick={sendCompose}
            disabled={!composeTo || !composeBody}
            style={{
              flex: 1,
              padding: '11px 14px',
              borderRadius: 12,
              background: composeTo && composeBody ? accent : theme.glassBorder,
              color: composeTo && composeBody ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            {t('comms.compose.send')}
          </button>
        </div>
      </Modal>
    </PhoneShell>
  );
}
