import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GlassCard } from '@/components/GlassCard';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { isQrScannerSupported, startQrScan, type QrScanHandle } from '@/services/qrScanner';
import { performPairing, PairingError, type DeviceInfo, type PairingProgress } from '@/services/pairingClient';
import { formatDiagForClipboard, type DiagReport } from '@/services/connectionDiag';
import {
  startMdnsDiscovery,
  stopMdnsDiscovery,
  subscribeMdns,
  isMdnsAvailable,
  type DiscoveredHost,
} from '@/services/native/mdns';
import { useT } from '@/hooks/useT';

/** Best-effort DeviceInfo for the pair-claim payload. Capacitor 8 webview
 *  doesn't expose @capacitor/device by default in this project, so we
 *  parse `navigator.userAgent` for the bits the backend actually logs
 *  (model + os version). Server is permissive — anything missing falls
 *  back to "unknown" without rejecting the claim. */
function deriveDeviceInfo(): DeviceInfo {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent ?? '' : '';
  const isAndroid = /Android/i.test(ua);
  const isIOS = /iPhone|iPad|iPod/i.test(ua);
  const platform: DeviceInfo['platform'] = isAndroid ? 'android' : isIOS ? 'ios' : 'web';
  const versionMatch = ua.match(/Android\s+([\d.]+)/) ?? ua.match(/OS\s+([\d_]+)/);
  const osVersion = versionMatch?.[1]?.replace(/_/g, '.') ?? 'unknown';
  const modelMatch = ua.match(/;\s*([^;)]+)\s+Build\//) ?? ua.match(/;\s*([^;)]+)\)/);
  const model = modelMatch?.[1]?.trim() ?? 'unknown';
  return {
    name: model,
    model,
    platform,
    os_version: osVersion,
  };
}

/**
 * Pair flow rewrite (round 4 of operator feedback).
 *
 * Three fixes vs the prior shipping screen:
 *   1) Auto-start the camera scan as soon as the screen mounts and the
 *      browser supports BarcodeDetector. Earlier the camera sat behind a
 *      "Старт сканера" button which the operator missed in cluttered
 *      lighting; "0 реакції" was actually "I never tapped the start CTA".
 *   2) Permanent status banner directly under the camera card. Reports
 *      "Готую камеру…" / "Сканую…" / "Знайшов QR" / explicit error so
 *      operator never has to guess whether the camera path is alive.
 *   3) The "Підключити" CTA is now docked to the bottom of the screen
 *      (sticky), survives the soft-keyboard, and is always visible
 *      regardless of how much the operator has scrolled or how long
 *      their pasted JSON is.
 */

type CameraStatus =
  | { kind: 'idle' }
  | { kind: 'starting' }
  | { kind: 'scanning' }
  | { kind: 'found' }
  | { kind: 'error'; message: string }
  | { kind: 'unsupported' };

export function PairingScreen() {
  const { theme } = useThemeContext();
  const navigate = useNavigate();
  const t = useT();
  const videoRef = useRef<HTMLVideoElement>(null);
  const handleRef = useRef<QrScanHandle | null>(null);
  const [progress, setProgress] = useState<PairingProgress>({ step: 'idle' });
  const [paste, setPaste] = useState('');
  const [camera, setCamera] = useState<CameraStatus>({ kind: 'idle' });
  const supported = isQrScannerSupported();
  // Surfaced after `performPairing` rejects with a `PairingError` that
  // carries a `diagnostic` payload — so the operator can see WHICH URL
  // was tried + copy the trace into a chat to the engineer.
  const [diag, setDiag] = useState<DiagReport | null>(null);
  const [lastQr, setLastQr] = useState<string | null>(null);
  const [hostOverride, setHostOverride] = useState('');
  // mDNS-discovered phantom-os hosts (Android only — Kotlin
  // MdnsPlugin acquires the multicast lock and emits serviceFound).
  const [discovered, setDiscovered] = useState<DiscoveredHost[]>([]);
  const mdnsActiveRef = useRef(false);

  useEffect(() => {
    return () => {
      handleRef.current?.stop();
    };
  }, []);

  // Explicit cancel — operator taps top-left ✕ to leave the QR flow
  // without scanning. Before this fix the only escape was a hard
  // back-press that some Android shells swallow. Routes to /me when
  // a profile already exists, otherwise to onboarding.
  const handleCancelToHome = () => {
    handleRef.current?.stop();
    setCamera({ kind: 'idle' });
    setProgress({ step: 'idle' });
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem('phantom.pair.deferred', new Date().toISOString());
      } catch {
        /* ignore */
      }
    }
    navigate('/me', { replace: true });
  };

  // Auto-start the scanner. Operator no longer needs to discover and tap
  // a CTA before the camera even turns on — it lights up the moment the
  // screen mounts. We re-arm only after a user-driven "stop & restart"
  // flow (camera-error retry button or claim error).
  useEffect(() => {
    if (!supported) {
      setCamera({ kind: 'unsupported' });
      return;
    }
    void beginScan();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [supported]);

  const beginScan = async () => {
    if (!videoRef.current) return;
    setCamera({ kind: 'starting' });
    handleRef.current?.stop();
    handleRef.current = await startQrScan(
      videoRef.current,
      async (raw) => {
        handleRef.current?.stop();
        setCamera({ kind: 'found' });
        await runPairing(raw);
      },
      (err) => {
        setCamera({ kind: 'error', message: err.message });
      },
    );
    // If startQrScan returned without firing onError, getUserMedia
    // succeeded and frames are flowing. We only know we hit "scanning"
    // when video.readyState ≥ 2; an explicit poll keeps the banner in
    // sync without bloating qrScanner.ts.
    if (videoRef.current && videoRef.current.readyState < 2) {
      videoRef.current.onloadeddata = () => setCamera({ kind: 'scanning' });
    } else {
      setCamera({ kind: 'scanning' });
    }
  };

  const runPairing = async (raw: string, override?: string) => {
    setLastQr(raw);
    setDiag(null);
    try {
      await performPairing(raw, deriveDeviceInfo(), setProgress, override);
      useToastStore.getState().push('success', t('pair.toast.success'));
      navigate('/pulse', { replace: true });
    } catch (err) {
      const e = err as Error;
      setProgress({ step: 'error', message: e.message });
      useToastStore.getState().push('error', e.message);
      // Capture the diagnostic so the UI can render copy-trace +
      // manual-override surface without re-throwing the error.
      if (err instanceof PairingError && err.diagnostic) {
        setDiag(err.diagnostic);
      }
    }
  };

  const retryWithOverride = () => {
    if (!lastQr || !hostOverride.trim()) return;
    void runPairing(lastQr, hostOverride);
  };

  // mDNS lifecycle — start once on mount when the platform supports
  // it, kill on unmount. Subscribers fire as hosts come/go.
  useEffect(() => {
    if (!isMdnsAvailable()) return;
    let cancelled = false;
    const unsub = subscribeMdns((hosts) => {
      if (!cancelled) setDiscovered(hosts);
    });
    void startMdnsDiscovery().then((ok) => {
      mdnsActiveRef.current = ok;
    });
    return () => {
      cancelled = true;
      unsub();
      void stopMdnsDiscovery();
      mdnsActiveRef.current = false;
    };
  }, []);

  const useDiscoveredHost = (h: DiscoveredHost) => {
    if (!lastQr) {
      // Operator hasn't scanned yet — fill the host field for when
      // they do. Without QR we have no crypto so we can't actually
      // pair from mDNS alone.
      setHostOverride(`${h.ip}:${h.port}`);
      useToastStore.getState().push('info', `Знайдено ${h.name} — вкажи у поле host`);
      return;
    }
    void runPairing(lastQr, `${h.ip}:${h.port}`);
  };

  const copyDiag = async () => {
    if (!diag) return;
    const text = formatDiagForClipboard(diag);
    try {
      if (navigator?.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        useToastStore.getState().push('success', 'Діагностику скопійовано');
        return;
      }
    } catch {
      /* fall through to textarea */
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    useToastStore.getState().push('success', 'Діагностику скопійовано');
  };

  const stepLabel = (() => {
    switch (progress.step) {
      case 'parsing': return t('pair.step.parsing');
      case 'derive':  return t('pair.step.derive');
      case 'claim':   return t('pair.step.claim');
      case 'verify':  return t('pair.step.verify');
      case 'done':    return progress.message ?? t('pair.step.done');
      case 'error':   return progress.message ?? t('pair.step.error');
      default:        return '';
    }
  })();

  const cameraLabel = (() => {
    switch (camera.kind) {
      case 'starting':    return 'Готую камеру…';
      case 'scanning':    return 'Сканую QR…';
      case 'found':       return 'Знайшов QR — підключаю…';
      case 'error':       return `Камера: ${camera.message}`;
      case 'unsupported': return t('pair.unsupported_hint');
      default:            return '';
    }
  })();

  const cameraIsLive = camera.kind === 'starting' || camera.kind === 'scanning' || camera.kind === 'found';
  const claimDisabled =
    !paste.trim() ||
    progress.step === 'parsing' ||
    progress.step === 'derive' ||
    progress.step === 'claim' ||
    progress.step === 'verify';

  return (
    <PhoneShell theme={theme} showStatusBar={false}>
      {/* Top bar with explicit cancel/back button. Without it operator
          had no escape from the QR flow if they tapped pair by mistake
          or wanted to defer — fix for 2026-05-04 "вийти з сканування
          коду теж не можна". */}
      <PairingTopBar onCancel={handleCancelToHome} theme={theme} />
      {/* Scrollable content area — camera + status + paste field. The
          claim CTA lives in the sticky footer below this scroll region
          so it's reachable no matter how the keyboard reflows the page. */}
      <div
        style={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          padding: '20px 22px 16px',
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
        }}
      >
        <div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.22em', color: theme.ink3, fontWeight: 700, textTransform: 'uppercase' }}>
            {t('pair.title')}
          </div>
          <div style={{ fontSize: 24, fontWeight: 700, color: theme.ink, marginTop: 6, lineHeight: 1.18 }}>
            {t('pair.heading.prefix')}{' '}
            <em style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', color: theme.primary }}>PHANTOM OS</em>{' '}
            {t('pair.heading.middle')}{' '}
            <span style={{ color: theme.primary }}>{t('pair.heading.suffix')}</span>
          </div>
          <div style={{ fontSize: 13, color: theme.ink2, marginTop: 6, lineHeight: 1.5 }}>
            {t('pair.hint')}
          </div>
        </div>

        <GlassCard level="elevated" theme={theme} style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', background: '#000' }}>
            <video
              ref={videoRef}
              playsInline
              muted
              autoPlay
              style={{
                width: '100%',
                height: '100%',
                objectFit: 'cover',
                display: cameraIsLive ? 'block' : 'none',
              }}
            />
            {!cameraIsLive && (
              <div
                style={{
                  position: 'absolute',
                  inset: 0,
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 14,
                  color: theme.ink2,
                  background: theme.glassPanel,
                  padding: 16,
                  textAlign: 'center',
                }}
              >
                <div style={{ fontSize: 48, color: theme.ink3 }} aria-hidden>◱</div>
                {camera.kind === 'unsupported' ? (
                  <>
                    <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: theme.ink2, fontWeight: 600 }}>
                      {t('pair.unsupported')}
                    </div>
                    <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, maxWidth: 240 }}>
                      {t('pair.unsupported_hint')}
                    </div>
                  </>
                ) : (
                  <button
                    onClick={beginScan}
                    style={{
                      padding: '10px 18px',
                      borderRadius: 999,
                      background: theme.primary,
                      color: theme.isDark ? '#1a1208' : '#fff',
                      fontFamily: 'var(--mono)',
                      fontSize: 11,
                      fontWeight: 700,
                      letterSpacing: '0.2em',
                      textTransform: 'uppercase',
                    }}
                  >
                    {t('pair.start_scan')}
                  </button>
                )}
                {camera.kind === 'error' && (
                  <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.coral, maxWidth: 260 }}>
                    {camera.message}
                  </div>
                )}
              </div>
            )}
          </div>
        </GlassCard>

        {/* Always-on status banner — earlier the operator had no signal
            whether the camera was even running; now they see it. */}
        {cameraLabel && (
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11,
              color: camera.kind === 'error' ? theme.coral : theme.ink2,
              padding: '8px 12px',
              borderRadius: 10,
              border: `1px solid ${camera.kind === 'error' ? theme.coral + '55' : theme.glassBorder}`,
              background: theme.glassPanel,
            }}
          >
            {cameraLabel}
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <label
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.2em',
              fontWeight: 700,
              textTransform: 'uppercase',
              color: theme.ink3,
            }}
          >
            {t('pair.paste_label')}
          </label>
          <textarea
            value={paste}
            onChange={(e) => setPaste(e.target.value)}
            rows={3}
            placeholder='{"v":1,"host":"phantom.local",...}'
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11,
              padding: 10,
              borderRadius: 12,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              resize: 'none',
            }}
          />
        </div>

        {stepLabel && (
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11,
              color: progress.step === 'error' ? theme.coral : theme.ink2,
              padding: '10px 12px',
              borderRadius: 10,
              border: `1px solid ${progress.step === 'error' ? theme.coral + '55' : theme.glassBorder}`,
              background: theme.glassPanel,
            }}
          >
            {stepLabel}
          </div>
        )}

        {/* Diagnostic surface — shows up only after a `PairingError`
            with an attached connection report. Lets the operator
            (a) see exactly which URLs were tried + why they failed,
            (b) override the host (when QR's IP is from a different
            network), (c) jump to a discovered mDNS entry. */}
        {diag && (
          <GlassCard
            level="card"
            theme={theme}
            accentBorder
            accent={theme.coral}
            style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}
          >
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.18em',
                fontWeight: 700,
                textTransform: 'uppercase',
                color: theme.coral,
              }}
            >
              ◌ зв&apos;язок не вдався
            </div>
            <div
              style={{
                fontSize: 13,
                color: theme.ink,
                lineHeight: 1.45,
              }}
            >
              {diag.summary}
            </div>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                color: theme.ink3,
                background: theme.surfaceBase,
                border: `1px solid ${theme.glassBorder}`,
                borderRadius: 8,
                padding: '8px 10px',
                whiteSpace: 'pre-wrap',
                maxHeight: 140,
                overflowY: 'auto',
              }}
            >
              {diag.probes
                .map(
                  (p) =>
                    `[${p.outcome.padEnd(8)}] ${p.url}\n  ${
                      p.errorMessage ?? 'ok'
                    }  · ${Math.round(p.durationMs)}ms`,
                )
                .join('\n')}
            </div>
            <button
              onClick={() => void copyDiag()}
              style={{
                minHeight: 36,
                borderRadius: 10,
                background: 'transparent',
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink2,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              ⎘ копіювати трейс
            </button>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.16em',
                color: theme.ink2,
                marginTop: 4,
                textTransform: 'uppercase',
                fontWeight: 700,
              }}
            >
              host override
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              <input
                value={hostOverride}
                onChange={(e) => setHostOverride(e.target.value)}
                placeholder="192.168.1.5:8000"
                style={{
                  flex: 1,
                  minHeight: 38,
                  padding: '0 12px',
                  borderRadius: 10,
                  background: theme.surfaceBase,
                  border: `1px solid ${theme.glassBorder}`,
                  color: theme.ink,
                  fontFamily: 'var(--mono)',
                  fontSize: 12,
                }}
              />
              <button
                onClick={retryWithOverride}
                disabled={!hostOverride.trim()}
                style={{
                  minHeight: 38,
                  padding: '0 14px',
                  borderRadius: 10,
                  background: hostOverride.trim() ? theme.primary : theme.glassPanel,
                  color: hostOverride.trim() ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
                  border: `1px solid ${theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.16em',
                  textTransform: 'uppercase',
                }}
              >
                спробувати
              </button>
            </div>
            <div style={{ fontSize: 11, color: theme.ink3, lineHeight: 1.45 }}>
              QR показав публічну IP, а телефон зараз в іншій мережі —
              введи LAN адресу phantom-os (її видно в десктопній шапці
              застосунку). Криптографія з QR залишиться, поміняється
              лише endpoint.
            </div>
          </GlassCard>
        )}

        {/* mDNS discovery — Android-only. Shows phantom-os hosts
            that advertise `_phantom._tcp` on the local Wi-Fi.
            Backend needs python-zeroconf to publish; until that
            lands the list stays empty. */}
        {isMdnsAvailable() && discovered.length > 0 && (
          <GlassCard level="card" theme={theme} style={{ padding: '12px 14px' }}>
            <div
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 10,
                letterSpacing: '0.18em',
                fontWeight: 700,
                textTransform: 'uppercase',
                color: theme.primary,
                marginBottom: 8,
              }}
            >
              ⌬ знайдено поруч · {discovered.length}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {discovered.map((h) => (
                <button
                  key={`${h.name}-${h.ip}`}
                  onClick={() => useDiscoveredHost(h)}
                  style={{
                    textAlign: 'left',
                    minHeight: 44,
                    padding: '8px 12px',
                    borderRadius: 10,
                    background: theme.surfaceBase,
                    border: `1px solid ${theme.glassBorder}`,
                    color: theme.ink,
                    fontFamily: 'var(--sans)',
                    fontSize: 13,
                    cursor: 'pointer',
                  }}
                >
                  <div style={{ fontWeight: 600 }}>{h.name}</div>
                  <div
                    style={{
                      fontFamily: 'var(--mono)',
                      fontSize: 10,
                      color: theme.ink3,
                      marginTop: 2,
                    }}
                  >
                    {h.ip}:{h.port}
                  </div>
                </button>
              ))}
            </div>
          </GlassCard>
        )}

        {/* Reinstall-conflict hint — when phone reinstalls and the
            backend still has the previous PairedDevice for this
            user, claim will fail. Until the server adds an auto-
            revoke-on-rotation flow (TODO backend), the cleanest
            workaround is for the operator to revoke from desktop
            first. This hint surfaces that path before they hit a
            confusing 409. */}
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 10.5,
            color: theme.ink3,
            lineHeight: 1.5,
            padding: '10px 12px',
            borderRadius: 10,
            border: `1px dashed ${theme.glassBorder}`,
            background: 'transparent',
          }}
        >
          Якщо телефон уже спарювався раніше і застосунок перевстановили —
          phantom-os не дозволить новий device-JWT поки попередній
          живий. На десктопі: Settings → Pair Device → відкликати
          старий пристрій, тоді скан тут запрацює.
        </div>
      </div>

      {/* Sticky CTA — operator's complaint was "не бачу кнопку
          підтвердження"; this docks it to the bottom of the viewport
          permanently. Lives outside the scroll container so it never
          falls below the fold or the soft-keyboard inset. */}
      <div
        style={{
          flexShrink: 0,
          padding: '12px 22px calc(env(safe-area-inset-bottom, 12px) + 14px)',
          borderTop: `1px solid ${theme.glassBorder}`,
          background: theme.glassElevated,
          backdropFilter: 'blur(12px)',
        }}
      >
        <button
          onClick={() => paste.trim() && void runPairing(paste.trim())}
          disabled={claimDisabled}
          style={{
            width: '100%',
            padding: '14px 14px',
            borderRadius: 14,
            background: claimDisabled ? theme.glassBorder : theme.primary,
            color: claimDisabled ? theme.ink3 : (theme.isDark ? '#1a1208' : '#fff'),
            fontFamily: 'var(--mono)',
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.2em',
            textTransform: 'uppercase',
            border: 'none',
            cursor: claimDisabled ? 'default' : 'pointer',
            transition: 'background 140ms ease, color 140ms ease',
          }}
        >
          {t('pair.claim')}
        </button>
      </div>
    </PhoneShell>
  );
}

interface PairingTopBarProps {
  onCancel: () => void;
  theme: import('@/types/theme').PhantomTheme;
}

function PairingTopBar({ onCancel, theme }: PairingTopBarProps) {
  return (
    <div
      style={{
        height: 44,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 8px',
        flexShrink: 0,
        borderBottom: `1px solid ${theme.glassBorder}`,
        background: theme.glassPanel,
      }}
    >
      <button
        onClick={onCancel}
        aria-label="Скасувати спарювання"
        style={{
          width: 44,
          height: 44,
          background: 'transparent',
          border: 'none',
          color: theme.ink2,
          fontSize: 22,
          lineHeight: 1,
          cursor: 'pointer',
          fontFamily: 'var(--mono)',
        }}
      >
        ✕
      </button>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          letterSpacing: '0.22em',
          color: theme.ink3,
          fontWeight: 700,
          textTransform: 'uppercase',
        }}
      >
        Спарювання
      </div>
      <div style={{ width: 44 }} />
    </div>
  );
}
