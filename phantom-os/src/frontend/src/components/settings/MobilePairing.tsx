/**
 * Phase 19 Mobile Companion — desktop-side pairing panel.
 *
 * Renders inside SettingsPanel under category id `mobile`. Owns:
 *   1. "Generate QR" CTA → POST /api/v1/pair/init → displays inline SVG.
 *   2. 60-second countdown ring; auto-clears the QR on expiry.
 *   3. Live "claimed" / "revoked" toasts via the `pair` WS channel.
 *   4. Copyable raw QR JSON drawer for the Companion app's manual-paste
 *      fallback (when the camera scan fails).
 *   5. Paired devices list with REVOKE button per row.
 *
 * Layout note: this panel must fit on the 1024×600 device alongside the
 * categories sidebar and the StatusBar header. Earlier revision used
 * 44 px icons and 220 px QR + 16 px paddings — visually rich on a 27"
 * monitor, but cramped against the actual hardware viewport. Density
 * is now tuned for the device first; spacious-screen rendering still
 * feels intentional because the column max-width caps the spread.
 *
 * Crypto, JWT minting, and ECDH live on the backend (`security/pair_crypto`
 * + `api/routes_pair.py`); this component is a thin presentational shell.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Smartphone,
  QrCode,
  RefreshCw,
  ShieldOff,
  CheckCircle2,
  Loader2,
  AlertTriangle,
  Copy,
} from 'lucide-react';
import {
  pairApi,
  ApiError,
  type PairInitResponse,
  type PairedDeviceRow,
} from '../../services/api';
import { wsClient } from '../../services/websocket';
import { useTranslation } from '../../i18n/useTranslation';
import { currentDateTag } from '../chat/tNow';

interface ClaimToast {
  kind: 'claimed' | 'revoked';
  message: string;
  ts: number;
}

const PAIR_TTL_S = 60;

function formatRelative(iso: string): string {
  const at = new Date(iso).getTime();
  if (!Number.isFinite(at)) return iso;
  const dt = Math.max(0, (Date.now() - at) / 1000);
  const rel = new Intl.RelativeTimeFormat(currentDateTag(), { numeric: 'auto', style: 'narrow' });
  if (dt < 60) return rel.format(-Math.floor(dt), 'second');
  if (dt < 3600) return rel.format(-Math.floor(dt / 60), 'minute');
  if (dt < 86_400) return rel.format(-Math.floor(dt / 3600), 'hour');
  return rel.format(-Math.floor(dt / 86_400), 'day');
}

/**
 * Countdown ring isolated from the parent so the per-second tick does
 * NOT force the QR card / JSON drawer / buttons to re-render. Earlier
 * revision drove the entire panel from `secondsLeft` state and that
 * caused two visible bugs on the device:
 *   - Click-to-select on the JSON `<pre>` looked like "0 reactions" —
 *     the browser-native text selection was nuked by every render.
 *   - The Show JSON / Copy buttons "felt frozen" because their hover
 *     and pressed states reset 1× per second.
 * The ring owns its own interval; parent only learns when the QR
 * actually expires (via `onExpire`).
 */
function CountdownRing({
  expiresAt,
  onExpire,
}: {
  expiresAt: number;
  onExpire: () => void;
}): JSX.Element {
  const [secondsLeft, setSecondsLeft] = useState<number>(() =>
    Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
  );
  useEffect(() => {
    const tick = () => {
      const left = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
      setSecondsLeft(left);
      if (left <= 0) onExpire();
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt, onExpire]);

  const ringPct = Math.max(0, Math.min(1, secondsLeft / PAIR_TTL_S));
  return (
    <div
      style={{
        position: 'absolute',
        top: -8,
        right: -8,
        width: 32,
        height: 32,
        borderRadius: 999,
        background: 'white',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        boxShadow: '0 2px 8px var(--ph-lit-black-a15)',
      }}
      aria-label={`expires in ${secondsLeft}s`}
    >
      <svg width="32" height="32" viewBox="0 0 32 32">
        <circle
          cx="16"
          cy="16"
          r="13"
          fill="none"
          stroke="var(--ph-lit-black-a8)"
          strokeWidth="3"
        />
        <circle
          cx="16"
          cy="16"
          r="13"
          fill="none"
          stroke={secondsLeft > 10 ? 'var(--ph-lit-amber)' : 'var(--ph-lit-red-strong)'}
          strokeWidth="3"
          strokeDasharray={`${ringPct * 81.68} 81.68`}
          strokeLinecap="round"
          transform="rotate(-90 16 16)"
          style={{ transition: 'stroke-dasharray 1s linear' }}
        />
      </svg>
      <span
        className="tabular"
        style={{
          position: 'absolute',
          fontSize: 9,
          fontWeight: 700,
          color: secondsLeft > 10 ? 'var(--ph-lit-amber-ink)' : 'var(--ph-lit-red-deep)',
        }}
      >
        {secondsLeft}
      </span>
    </div>
  );
}

// @refresh reset
/**
 * Click-to-copy raw QR JSON block. Three earlier revisions failed the
 * operator: the first relied on Ctrl-C after click-to-select (no
 * visible affordance, "0 reactions" perception); the second hid it
 * behind a "Показати JSON" toggle whose pill-button was easy to miss;
 * the third made the toggle visible but left the block stuffed in the
 * narrow right column of the QR card. This revision:
 *   - is rendered always (no toggle), full-width under the QR card;
 *   - always shows the "Клік — скопіювати" pill in the corner so the
 *     operator never has to discover hover behaviour;
 *   - on click selects all text AND copies to clipboard in one go,
 *     the pill briefly turns green "✓ Скопійовано" as confirmation;
 *   - keeps Ctrl-A / Ctrl-C working for partial selection.
 * Renders independently of the parent so the countdown tick never
 * touches its DOM tree. The `// @refresh reset` directive at the top
 * of the file forces React Fast Refresh to remount the subtree on
 * the next HMR — a defence against the operator seeing a stale bundle
 * after a soft-reload race in vite.
 */
function RawQrJsonBlock({
  text,
  onCopied,
}: {
  text: string;
  onCopied: () => void;
}): JSX.Element {
  const { t } = useTranslation();
  const [hover, setHover] = useState(false);
  const [flash, setFlash] = useState(false);

  const doCopy = useCallback(async () => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setFlash(true);
      setTimeout(() => setFlash(false), 1500);
      onCopied();
    } catch {
      /* operator can still Ctrl-A / Ctrl-C the selected text below. */
    }
  }, [text, onCopied]);

  const onClick = useCallback(
    (e: React.MouseEvent<HTMLPreElement>) => {
      const range = document.createRange();
      range.selectNodeContents(e.currentTarget);
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(range);
      void doCopy();
    },
    [doCopy]
  );

  return (
    <div style={{ position: 'relative' }}>
      <pre
        onClick={onClick}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        title={t('mp.copyTitle')}
        style={{
          margin: 0,
          padding: 12,
          borderRadius: 10,
          background: hover ? 'var(--ph-lit-amber-a10)' : 'var(--ph-lit-black-a4)',
          border: hover
            ? '1px solid var(--ph-lit-amber-a65)'
            : '1px solid var(--ph-lit-black-a8)',
          fontSize: 11.5,
          lineHeight: 1.5,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          color: 'var(--ink-primary)',
          // No maxHeight: the QR JSON payload is < 12 lines, hiding it
          // behind a scroll made the block feel like a "surprise inside"
          // and the operator wasn't sure it was complete.
          whiteSpace: 'pre',
          cursor: 'pointer',
          userSelect: 'text',
          transition: 'background 120ms ease, border-color 120ms ease, box-shadow 120ms ease',
          boxShadow: hover
            ? '0 0 0 3px var(--ph-lit-amber-a14)'
            : 'none',
        }}
      >
        {text}
      </pre>
      {/* Always-visible CTA pill — operator never has to discover the
          hover behaviour. Becomes a green "✓ Скопійовано" confirm
          on click. */}
      <div
        style={{
          position: 'absolute',
          top: 8,
          right: 10,
          padding: '3px 9px',
          borderRadius: 999,
          fontSize: 9,
          fontWeight: 700,
          letterSpacing: '0.05em',
          textTransform: 'uppercase',
          pointerEvents: 'none',
          transition: 'background 140ms ease, color 140ms ease',
          background: flash
            ? 'var(--ph-lit-green-a95)'
            : 'var(--ph-lit-amber-a95)',
          color: 'white',
          boxShadow: '0 2px 6px var(--ph-lit-black-a18)',
        }}
      >
        {flash ? t('mp.copied') : t('mp.clickCopy')}
      </div>
    </div>
  );
}

export function MobilePairing(): JSX.Element {
  const { t } = useTranslation();
  const [qr, setQr] = useState<PairInitResponse | null>(null);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [devices, setDevices] = useState<PairedDeviceRow[]>([]);
  const [devicesLoading, setDevicesLoading] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [toast, setToast] = useState<ClaimToast | null>(null);

  /* ── Initial device list + WS subscription ───────────────────────── */
  const refreshDevices = useCallback(async () => {
    setDevicesLoading(true);
    try {
      const rows = await pairApi.listDevices(false);
      setDevices(rows);
    } catch (err) {
      // Non-fatal: keep prior list, surface the error in a banner.
      setError(err instanceof Error ? err.message : 'Failed to load devices');
    } finally {
      setDevicesLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshDevices();
  }, [refreshDevices]);

  useEffect(() => {
    const offClaimed = wsClient.on('pair', (msg) => {
      if (msg.type === 'claimed') {
        const name =
          (msg.data?.device_name as string) ||
          (msg.data?.device_model as string) ||
          'phone';
        setToast({
          kind: 'claimed',
          message: `${name} paired successfully`,
          ts: Date.now(),
        });
        setQr(null);
        void refreshDevices();
      } else if (msg.type === 'revoked') {
        setToast({
          kind: 'revoked',
          message: 'Device revoked',
          ts: Date.now(),
        });
        void refreshDevices();
      }
    });
    return () => offClaimed();
  }, [refreshDevices]);

  /* ── Toast auto-dismiss ──────────────────────────────────────────── */
  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(id);
  }, [toast]);

  /* Countdown lives inside <CountdownRing>. Parent only needs the
     absolute expiry timestamp so the child can compute seconds-left
     locally without re-rendering this whole tree on every tick. */
  const expiresAt = useMemo(
    () => (qr ? Date.now() + qr.expires_in_seconds * 1000 : 0),
    [qr]
  );
  const handleQrExpire = useCallback(() => {
    setQr(null);
  }, []);

  /* ── Actions ─────────────────────────────────────────────────────── */
  const handleGenerate = useCallback(async () => {
    setError(null);
    setGenerating(true);
    try {
      const resp = await pairApi.init();
      setQr(resp);
    } catch (err) {
      const msg =
        err instanceof ApiError && err.status === 403
          ? 'ROOT trust required to pair a device.'
          : err instanceof Error
            ? err.message
            : 'Failed to start pairing';
      setError(msg);
    } finally {
      setGenerating(false);
    }
  }, []);

  const handleRevoke = useCallback(
    async (id: string) => {
      setRevokingId(id);
      try {
        await pairApi.revoke(id, 'revoked from desktop');
        await refreshDevices();
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Revoke failed');
      } finally {
        setRevokingId(null);
      }
    },
    [refreshDevices]
  );

  const qrJsonText = useMemo(() => {
    if (!qr) return '';
    // Companion app accepts the raw `qr` payload object; we serialise
    // pretty so the operator can eyeball the cert pin / nonce while
    // copying. The phone's manual-paste field tolerates both compact
    // and pretty-printed JSON.
    return JSON.stringify(qr.qr, null, 2);
  }, [qr]);

  /* ── Render ──────────────────────────────────────────────────────── */
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        paddingBottom: 8,
        // Cap width so on a wide monitor the panel doesn't sprawl edge-to-edge.
        maxWidth: 720,
      }}
    >
      {/* Hero — compact one-row layout */}
      <div
        className="glass"
        style={{
          padding: 10,
          display: 'flex',
          gap: 10,
          alignItems: 'center',
        }}
      >
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 10,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
            color: 'white',
            boxShadow: '0 3px 10px var(--ph-lit-amber-a30)',
            flexShrink: 0,
          }}
          aria-hidden
        >
          <Smartphone size={18} strokeWidth={1.75} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="micro-label" style={{ fontSize: 9 }}>
            MOBILE COMPANION
          </div>
          <div
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--ink-primary)',
              lineHeight: 1.2,
            }}
          >
            {t('mp.title')}
          </div>
          <div
            style={{
              fontSize: 10,
              color: 'var(--ink-muted)',
              marginTop: 2,
              lineHeight: 1.4,
            }}
          >
            {t('mp.sub')}
          </div>
        </div>
        <button
          type="button"
          onClick={handleGenerate}
          disabled={generating}
          style={{
            minHeight: 32,
            padding: '0 12px',
            borderRadius: 999,
            background: generating
              ? 'var(--ph-lit-black-a4)'
              : 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
            border: 'none',
            color: generating ? 'var(--ink-muted)' : 'white',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.05em',
            textTransform: 'uppercase',
            cursor: generating ? 'default' : 'pointer',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 5,
            boxShadow: generating
              ? 'none'
              : '0 3px 10px var(--ph-lit-amber-a35)',
            flexShrink: 0,
          }}
        >
          {generating ? (
            <Loader2 size={12} strokeWidth={1.75} className="animate-spin" />
          ) : qr ? (
            <RefreshCw size={12} strokeWidth={1.75} />
          ) : (
            <QrCode size={12} strokeWidth={1.75} />
          )}
          {qr ? t('mp.newQr') : t('mp.makeQr')}
        </button>
      </div>

      {/* Banners stack — error and toast share the same compact row style. */}
      {error && (
        <div
          style={{
            padding: '7px 10px',
            borderRadius: 10,
            border: '1px solid var(--ph-lit-red-strong-a30)',
            background: 'var(--ph-lit-red-strong-a8)',
            color: 'var(--ph-lit-red-deep)',
            fontSize: 11,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <AlertTriangle size={12} strokeWidth={1.75} />
          {error}
        </div>
      )}
      {toast && (
        <div
          style={{
            padding: '7px 10px',
            borderRadius: 10,
            border:
              toast.kind === 'claimed'
                ? '1px solid var(--ph-lit-green-a30)'
                : '1px solid var(--ph-lit-amber-a30)',
            background:
              toast.kind === 'claimed'
                ? 'var(--ph-lit-green-a8)'
                : 'var(--ph-lit-amber-a8)',
            color: toast.kind === 'claimed' ? 'var(--ph-lit-green)' : 'var(--ph-lit-amber-deep)',
            fontSize: 11,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <CheckCircle2 size={12} strokeWidth={1.75} />
          {toast.message}
        </div>
      )}

      {/* QR canvas — 180 px QR (was 220), endpoint badges row, JSON drawer */}
      {qr && (
        <div className="glass" style={{ padding: 12 }}>
          <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
            <div
              style={{
                position: 'relative',
                width: 180,
                height: 180,
                borderRadius: 12,
                padding: 6,
                background: 'white',
                boxShadow: '0 4px 16px var(--ph-lit-black-a10)',
                flexShrink: 0,
              }}
            >
              <img
                src={qr.qr_svg_data_url}
                alt="Pairing QR code"
                style={{
                  width: '100%',
                  height: '100%',
                  imageRendering: 'pixelated',
                  display: 'block',
                }}
              />
              <CountdownRing
                expiresAt={expiresAt}
                onExpire={handleQrExpire}
              />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="eyebrow-amber" style={{ fontSize: 9 }}>
                {t('mp.aim')}
              </div>
              <div
                style={{
                  fontSize: 13,
                  fontWeight: 600,
                  color: 'var(--ink-primary)',
                  marginTop: 2,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {qr.qr.host}{' '}
                <span
                  className="tabular"
                  style={{
                    fontSize: 10,
                    color: 'var(--ink-muted)',
                    marginLeft: 6,
                    fontWeight: 500,
                  }}
                >
                  {qr.qr.ip}:{qr.qr.port}
                </span>
              </div>
              <div
                style={{
                  fontSize: 10,
                  color: 'var(--ink-muted)',
                  marginTop: 4,
                  lineHeight: 1.4,
                }}
              >
                {t('mp.howBefore')}{' '}
                <strong style={{ color: 'var(--ink-secondary)' }}>JSON</strong>{' '}
                {t('mp.howAfter')}
              </div>
              <div
                style={{
                  marginTop: 6,
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: 5,
                }}
              >
                <span
                  className="tabular"
                  style={{
                    fontSize: 9,
                    padding: '2px 7px',
                    borderRadius: 999,
                    background: 'var(--ph-lit-amber-a12)',
                    color: 'var(--ph-lit-amber-deep)',
                    fontWeight: 600,
                    letterSpacing: '0.04em',
                  }}
                >
                  pair_id · {qr.pair_id.slice(0, 8)}…
                </span>
                <span
                  className="tabular"
                  style={{
                    fontSize: 9,
                    padding: '2px 7px',
                    borderRadius: 999,
                    background:
                      qr.qr.server_cert_sha256 === 'dev-no-pin'
                        ? 'var(--ph-lit-amber-a12)'
                        : 'var(--ph-lit-green-a12)',
                    color:
                      qr.qr.server_cert_sha256 === 'dev-no-pin'
                        ? 'var(--ph-lit-amber-deep)'
                        : 'var(--ph-lit-green)',
                    fontWeight: 600,
                    letterSpacing: '0.04em',
                  }}
                  title={qr.qr.server_cert_sha256}
                >
                  {qr.qr.server_cert_sha256 === 'dev-no-pin'
                    ? t('mp.noPin')
                    : t('mp.pinned')}
                </span>
              </div>

            </div>
          </div>
        </div>
      )}

      {/* Raw JSON block — full-width, always visible while a QR is live.
          The Companion app's PairScreen manual-paste field accepts this
          object verbatim, so the operator just clicks the block and the
          payload is in their clipboard ready to paste on the phone. */}
      {qr && (
        <div className="glass" style={{ padding: 12 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              marginBottom: 8,
            }}
          >
            <Copy size={12} strokeWidth={1.75} style={{ color: 'var(--ph-lit-amber-deep)' }} />
            <span className="eyebrow-amber" style={{ fontSize: 9 }}>
              {t('mp.rawJson')}
            </span>
            <span style={{ flex: 1 }} />
            <span
              className="tabular"
              style={{
                fontSize: 9,
                color: 'var(--ink-muted)',
                fontWeight: 500,
              }}
            >
              {t('mp.forCompanion')}
            </span>
          </div>
          <RawQrJsonBlock text={qrJsonText} onCopied={() => undefined} />
        </div>
      )}

      {/* Paired devices list */}
      <div className="glass" style={{ padding: 10 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            marginBottom: 8,
          }}
        >
          <Smartphone
            size={12}
            strokeWidth={1.75}
            style={{ color: 'var(--ph-lit-amber-deep)' }}
          />
          <span className="eyebrow-amber" style={{ fontSize: 9 }}>
            PAIRED DEVICES
          </span>
          <span
            className="tabular"
            style={{
              fontSize: 9,
              padding: '1px 6px',
              borderRadius: 999,
              background:
                devices.length > 0
                  ? 'var(--ph-lit-green-a18)'
                  : 'var(--ph-lit-black-a6)',
              color: devices.length > 0 ? 'var(--ph-lit-green)' : 'var(--ink-muted)',
              fontWeight: 700,
            }}
          >
            {devices.length}
          </span>
          <span style={{ flex: 1 }} />
          <button
            type="button"
            onClick={() => void refreshDevices()}
            disabled={devicesLoading}
            style={{
              minHeight: 24,
              padding: '3px 8px',
              borderRadius: 999,
              background: 'transparent',
              border: '1px solid var(--ph-lit-black-a10)',
              fontSize: 9,
              color: 'var(--ink-muted)',
              cursor: devicesLoading ? 'default' : 'pointer',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
            title="Refresh"
          >
            {devicesLoading ? (
              <Loader2 size={10} strokeWidth={1.75} className="animate-spin" />
            ) : (
              <RefreshCw size={10} strokeWidth={1.75} />
            )}
            Refresh
          </button>
        </div>

        {devices.length === 0 && !devicesLoading && (
          <div
            style={{
              padding: '14px 0',
              textAlign: 'center',
              fontSize: 10.5,
              color: 'var(--ink-muted)',
            }}
          >
            {t('mp.none')}
          </div>
        )}

        {devices.map((d) => (
          <div
            key={d.id}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '7px 9px',
              borderRadius: 8,
              border: '1px solid var(--ph-lit-black-a6)',
              marginBottom: 4,
              background: 'var(--ph-lit-white-a40)',
            }}
          >
            <div
              style={{
                width: 26,
                height: 26,
                borderRadius: 7,
                background: 'var(--ph-lit-amber-a18)',
                color: 'var(--ph-lit-amber-deep)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
              aria-hidden
            >
              <Smartphone size={13} strokeWidth={1.75} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div
                style={{
                  fontSize: 11.5,
                  fontWeight: 600,
                  color: 'var(--ink-primary)',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {d.device_name || d.device_model || '—'}
              </div>
              <div
                className="tabular"
                style={{
                  fontSize: 9.5,
                  color: 'var(--ink-muted)',
                  marginTop: 1,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {d.platform}
                {d.platform_version ? ` · ${d.platform_version}` : ''} ·{' '}
                {formatRelative(d.paired_at)} · seen{' '}
                {formatRelative(d.last_seen_at)}
              </div>
            </div>
            <button
              type="button"
              onClick={() => void handleRevoke(d.id)}
              disabled={revokingId === d.id}
              style={{
                minHeight: 26,
                padding: '3px 9px',
                borderRadius: 999,
                border: '1px solid var(--ph-lit-red-strong-a30)',
                background:
                  revokingId === d.id
                    ? 'var(--ph-lit-red-strong-a6)'
                    : 'transparent',
                color: 'var(--ph-lit-red-deep)',
                fontSize: 9.5,
                fontWeight: 600,
                cursor: revokingId === d.id ? 'default' : 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                flexShrink: 0,
              }}
            >
              {revokingId === d.id ? (
                <Loader2 size={10} strokeWidth={1.75} className="animate-spin" />
              ) : (
                <ShieldOff size={10} strokeWidth={1.75} />
              )}
              Revoke
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
