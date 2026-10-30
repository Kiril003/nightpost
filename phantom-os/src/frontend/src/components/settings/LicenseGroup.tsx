import { useCallback, useEffect, useState } from 'react';
import {
  ShieldCheck,
  ShieldAlert,
  ShieldQuestion,
  KeyRound,
  Loader2,
  Lock,
  RefreshCw,
  Power,
  AlertTriangle,
} from 'lucide-react';
import {
  licenseApi,
  type Entitlement,
  type LicenseStatus,
  type LicenseReason,
} from '../../services/licenseApi';
import { ApiError } from '../../services/api';
import type { MessageKey } from '../../i18n';
import { useTranslation } from '../../i18n/useTranslation';
import { tNow } from '../chat/tNow';

const TIER_LABELS: Record<string, MessageKey> = {
  free: 'lic.tier.free',
  personal: 'lic.tier.personal',
  crew: 'lic.tier.crew',
  unit: 'lic.tier.unit',
};

const REASON_MESSAGES: Record<LicenseReason, MessageKey> = {
  not_activated: 'lic.reason.not_activated',
  corrupt_license_file: 'lic.reason.corrupt_license_file',
  bad_signature: 'lic.reason.bad_signature',
  device_mismatch: 'lic.reason.device_mismatch',
  ok: 'lic.reason.ok',
};

/** Права словами. Ключі — ті самі, що у licensing/entitlements.py.
 *
 *  `note` — застереження про те, ЧОГО ключ не дасть саме тут. Ключ активує
 *  пару (ядро на столі + симбіот у кишені), тож право, що живе лише в
 *  телефоні, з переліку не викидаємо — інакше екран применшив би куплене.
 *  Але й мовчати не можна: «Точніша модель слуху» на цьому комп'ютері лежить
 *  над звичайним вибором моделі Whisper у налаштуваннях, який має кожен і без
 *  ключа. Дзеркало на сайті — FEATURE_SURFACE у platform-site/src/lib/plans.ts.
 */
interface FeatureRow {
  label: MessageKey;
  note?: MessageKey[];
}

const PHONE_ONLY: MessageKey = 'lic.note.phoneOnly';
const IN_PROGRESS: MessageKey = 'lic.note.inProgress';

const FEATURE_LABELS: Record<string, FeatureRow> = {
  'core.chat': { label: 'lic.f.core.chat' },
  'core.map': { label: 'lic.f.core.map' },
  'core.nav': { label: 'lic.f.core.nav' },
  'core.alerts': { label: 'lic.f.core.alerts' },
  'core.vault': { label: 'lic.f.core.vault' },
  'core.voice': { label: 'lic.f.core.voice' },
  'bridge.pair': { label: 'lic.f.bridge.pair' },
  'memory.longterm': { label: 'lic.f.memory.longterm' },
  'map.terrain3d': { label: 'lic.f.map.terrain3d' },
  'voice.premium': { label: 'lic.f.voice.premium', note: [PHONE_ONLY] },
  'gnss.blackbox': { label: 'lic.f.gnss.blackbox', note: [PHONE_ONLY, IN_PROGRESS] },
  'export.reports': { label: 'lic.f.export.reports' },
  'crew.sync': { label: 'lic.f.crew.sync', note: [IN_PROGRESS] },
  'fleet.onprem': { label: 'lic.f.fleet.onprem', note: [IN_PROGRESS] },
};

function tierLabel(tier: string | null): string {
  if (!tier) return '—';
  const key = TIER_LABELS[tier];
  return key ? tNow(key) : tier;
}

/** Один рядок правди про те, ЧОМУ діє цей рівень. Без цього людина бачить
 *  «Вільний» і не розуміє, ключ зіпсувався чи проба скінчилась. */
function entitlementLine(ent: Entitlement): string {
  switch (ent.reason) {
    case 'trial':
      return ent.trial_days_left === 1
        ? tNow('lic.ent.trialLast')
        : tNow('lic.ent.trialDays', { n: ent.trial_days_left ?? 0 });
    case 'offline_too_long':
      return tNow('lic.ent.offlineTooLong');
    case 'updates_expired':
      return tNow('lic.ent.updatesExpired', { date: ent.updates_until ?? '—' });
    case 'not_activated':
      return tNow('lic.ent.noKey');
    case 'device_mismatch':
      return tNow('lic.reason.device_mismatch');
    case 'bad_signature':
      return tNow('lic.reason.bad_signature');
    case 'corrupt_license_file':
      return tNow('lic.ent.corrupt');
    default:
      return ent.grace_days_left === null
        ? tNow('lic.ent.valid')
        : tNow('lic.ent.validGrace', { n: ent.grace_days_left });
  }
}

function fingerprintShort(fp: string | null): string {
  if (!fp) return '—';
  return fp.length > 12 ? `${fp.slice(0, 12)}…` : fp;
}

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return tNow('lic.err.unknown');
}

export function LicenseGroup() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<LicenseStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [licenseKey, setLicenseKey] = useState('');
  const [deviceName, setDeviceName] = useState('phantom-os');
  const [activating, setActivating] = useState(false);
  const [activateError, setActivateError] = useState<string | null>(null);

  const [revalidating, setRevalidating] = useState(false);

  const [deactivateOpen, setDeactivateOpen] = useState(false);
  const [deactivateKey, setDeactivateKey] = useState('');
  const [deactivating, setDeactivating] = useState(false);
  const [deactivateError, setDeactivateError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await licenseApi.status();
      setStatus(res);
    } catch (err) {
      setLoadError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleActivate = useCallback(async () => {
    if (licenseKey.trim().length < 10 || activating) return;
    setActivating(true);
    setActivateError(null);
    try {
      const res = await licenseApi.activate(licenseKey.trim(), deviceName.trim() || 'phantom-os');
      setStatus(res);
      setLicenseKey('');
    } catch (err) {
      setActivateError(errorMessage(err));
    } finally {
      setActivating(false);
    }
  }, [licenseKey, deviceName, activating]);

  const handleRevalidate = useCallback(async () => {
    setRevalidating(true);
    try {
      const res = await licenseApi.revalidate();
      setStatus(res);
    } catch (err) {
      setLoadError(errorMessage(err));
    } finally {
      setRevalidating(false);
    }
  }, []);

  const handleDeactivate = useCallback(async () => {
    if (deactivateKey.trim().length < 10 || deactivating) return;
    setDeactivating(true);
    setDeactivateError(null);
    try {
      const res = await licenseApi.deactivate(deactivateKey.trim());
      setStatus(res);
      setDeactivateOpen(false);
      setDeactivateKey('');
    } catch (err) {
      setDeactivateError(errorMessage(err));
    } finally {
      setDeactivating(false);
    }
  }, [deactivateKey, deactivating]);

  const ent = status?.entitlement ?? null;

  const badge = (() => {
    if (loading) return { label: t('lic.badge.checking'), color: 'var(--ink-muted)', bg: 'var(--ph-lit-black-a5)' };
    if (!status) return { label: t('lic.badge.unknown'), color: 'var(--ink-muted)', bg: 'var(--ph-lit-black-a5)' };
    if (status.valid) return { label: t('lic.badge.active'), color: 'var(--ph-lit-green)', bg: 'var(--ph-lit-green-a12)' };
    if (ent?.reason === 'trial') {
      return { label: t('lic.badge.trial'), color: 'var(--ph-lit-blue)', bg: 'var(--ph-lit-blue-a12)' };
    }
    if (status.reason === 'not_activated') {
      return { label: t('lic.badge.inactive'), color: 'var(--ph-lit-amber-deep)', bg: 'var(--ph-lit-amber-a14)' };
    }
    return { label: t('lic.badge.error'), color: 'var(--ph-lit-red-strong)', bg: 'var(--ph-lit-red-strong-a10)' };
  })();

  const BadgeIcon = loading
    ? Loader2
    : status?.valid
      ? ShieldCheck
      : status?.reason === 'not_activated'
        ? ShieldQuestion
        : ShieldAlert;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, paddingBottom: 8, maxWidth: 720 }}>
      <div className="glass" style={{ padding: 12 }} data-testid="license-status-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
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
            <ShieldCheck size={18} strokeWidth={1.75} />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="micro-label" style={{ fontSize: 9 }}>
              {t('lic.tierLabel')}
            </div>
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink-primary)', lineHeight: 1.2 }}>
              {tierLabel(ent?.tier ?? null)}
            </div>
          </div>
          <span
            data-testid="license-badge"
            style={{
              minHeight: 32,
              padding: '4px 12px',
              borderRadius: 999,
              background: badge.bg,
              color: badge.color,
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.03em',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              flexShrink: 0,
            }}
          >
            <BadgeIcon size={13} strokeWidth={1.75} className={loading ? 'animate-spin' : undefined} />
            {badge.label}
          </span>
        </div>

        {status && !status.valid && status.reason !== 'not_activated' && (
          <div
            style={{
              marginTop: 8,
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
            {t(REASON_MESSAGES[status.reason])}
          </div>
        )}

        {loadError && (
          <div
            style={{
              marginTop: 8,
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
            {loadError}
          </div>
        )}

        {ent && (
          <div style={{ marginTop: 8, fontSize: 11.5, color: 'var(--ink-secondary)', lineHeight: 1.45 }}>
            {entitlementLine(ent)}
          </div>
        )}

        {status?.valid && (
          <div
            style={{
              marginTop: 10,
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 8,
            }}
          >
            <InfoTile label={t('lic.tile.id')} value={status.license_id ?? '—'} mono />
            <InfoTile label={t('lic.tile.updates')} value={status.updates_until ?? '—'} mono />
            <InfoTile label={t('lic.tile.fingerprint')} value={fingerprintShort(status.fingerprint)} mono title={status.fingerprint ?? undefined} />
          </div>
        )}
      </div>

      {ent && <FeatureBoard ent={ent} />}

      {!status?.valid && (
        <div className="glass" style={{ padding: 12 }} data-testid="license-activate-form">
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
            <KeyRound size={12} strokeWidth={1.75} style={{ color: 'var(--ph-lit-amber-deep)' }} />
            <span className="eyebrow-amber" style={{ fontSize: 9 }}>
              {t('lic.activation')}
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <input
              type="text"
              value={licenseKey}
              onChange={(e) => setLicenseKey(e.target.value.toUpperCase())}
              placeholder="PHTM-XXXXX-XXXXX-XXXXX-XXXXX"
              aria-label={t('lic.keyAria')}
              spellCheck={false}
              style={{
                minHeight: 44,
                width: '100%',
                padding: '0 12px',
                borderRadius: 10,
                color: 'var(--ink-primary)',
                background: 'var(--surface-deep)',
                border: '1px solid var(--line-default)',
                fontFamily: 'var(--font-mono)',
                fontSize: 13,
                outline: 'none',
              }}
            />
            <input
              type="text"
              value={deviceName}
              onChange={(e) => setDeviceName(e.target.value)}
              placeholder="phantom-os"
              aria-label={t('lic.deviceAria')}
              style={{
                minHeight: 44,
                width: '100%',
                padding: '0 12px',
                borderRadius: 10,
                color: 'var(--ink-primary)',
                background: 'var(--surface-deep)',
                border: '1px solid var(--line-default)',
                fontFamily: 'var(--font-display)',
                fontSize: 13,
                outline: 'none',
              }}
            />
            <button
              type="button"
              onClick={() => void handleActivate()}
              disabled={licenseKey.trim().length < 10 || activating}
              style={{
                minHeight: 44,
                padding: '0 16px',
                borderRadius: 999,
                background:
                  licenseKey.trim().length < 10 || activating
                    ? 'var(--ph-lit-black-a4)'
                    : 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
                border: 'none',
                color: licenseKey.trim().length < 10 || activating ? 'var(--ink-muted)' : 'white',
                fontSize: 12,
                fontWeight: 700,
                letterSpacing: '0.05em',
                textTransform: 'uppercase',
                cursor: licenseKey.trim().length < 10 || activating ? 'default' : 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 6,
              }}
            >
              {activating ? <Loader2 size={14} strokeWidth={1.75} className="animate-spin" /> : <KeyRound size={14} strokeWidth={1.75} />}
              {t('lic.activate')}
            </button>
            {activateError && (
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
                {activateError}
              </div>
            )}
          </div>
        </div>
      )}

      {status?.valid && (
        <div className="glass" style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              onClick={() => void handleRevalidate()}
              disabled={revalidating}
              style={{
                minHeight: 44,
                flex: 1,
                padding: '0 14px',
                borderRadius: 999,
                background: 'var(--ph-lit-black-a5)',
                border: '1px solid var(--ph-lit-black-a10)',
                color: 'var(--ink-secondary)',
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: '0.04em',
                cursor: revalidating ? 'default' : 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 6,
              }}
            >
              {revalidating ? <Loader2 size={13} strokeWidth={1.75} className="animate-spin" /> : <RefreshCw size={13} strokeWidth={1.75} />}
              {t('lic.verify')}
            </button>
            <button
              type="button"
              onClick={() => setDeactivateOpen((v) => !v)}
              style={{
                minHeight: 44,
                flex: 1,
                padding: '0 14px',
                borderRadius: 999,
                background: 'var(--ph-lit-red-strong-a8)',
                border: '1px solid var(--ph-lit-red-strong-a30)',
                color: 'var(--ph-lit-red-deep)',
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: '0.04em',
                cursor: 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 6,
              }}
            >
              <Power size={13} strokeWidth={1.75} />
              {t('lic.deactivate')}
            </button>
          </div>

          {deactivateOpen && (
            <div
              style={{
                padding: 10,
                borderRadius: 10,
                border: '1px solid var(--ph-lit-red-strong-a30)',
                background: 'var(--ph-lit-red-strong-a6)',
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
              }}
              data-testid="license-deactivate-confirm"
            >
              <div style={{ fontSize: 11, color: 'var(--ph-lit-red-deep)', lineHeight: 1.4 }}>
                {t('lic.deactivateHint')}
              </div>
              <input
                type="text"
                value={deactivateKey}
                onChange={(e) => setDeactivateKey(e.target.value.toUpperCase())}
                placeholder="PHTM-XXXXX-XXXXX-XXXXX-XXXXX"
                aria-label={t('lic.deactivateKeyAria')}
                spellCheck={false}
                style={{
                  minHeight: 44,
                  width: '100%',
                  padding: '0 12px',
                  borderRadius: 10,
                  color: 'var(--ink-primary)',
                  background: 'var(--surface-deep)',
                  border: '1px solid var(--ph-lit-red-strong-a25)',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 13,
                  outline: 'none',
                }}
              />
              <button
                type="button"
                onClick={() => void handleDeactivate()}
                disabled={deactivateKey.trim().length < 10 || deactivating}
                style={{
                  minHeight: 44,
                  padding: '0 16px',
                  borderRadius: 999,
                  background:
                    deactivateKey.trim().length < 10 || deactivating ? 'var(--ph-lit-black-a6)' : 'var(--ph-lit-red-strong)',
                  border: 'none',
                  color: deactivateKey.trim().length < 10 || deactivating ? 'var(--ink-muted)' : 'white',
                  fontSize: 12,
                  fontWeight: 700,
                  letterSpacing: '0.05em',
                  textTransform: 'uppercase',
                  cursor: deactivateKey.trim().length < 10 || deactivating ? 'default' : 'pointer',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                }}
              >
                {deactivating ? <Loader2 size={14} strokeWidth={1.75} className="animate-spin" /> : <Power size={14} strokeWidth={1.75} />}
                {t('lic.deactivateConfirm')}
              </button>
              {deactivateError && (
                <div
                  style={{
                    padding: '7px 10px',
                    borderRadius: 10,
                    border: '1px solid var(--ph-lit-red-strong-a30)',
                    background: 'var(--ph-lit-red-strong-a10)',
                    color: 'var(--ph-lit-red-deep)',
                    fontSize: 11,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                  }}
                >
                  <AlertTriangle size={12} strokeWidth={1.75} />
                  {deactivateError}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Що відкрито, а що ні — списком, без «оновись і дізнаєшся». */
function FeatureBoard({ ent }: { ent: Entitlement }) {
  const { t } = useTranslation();
  const open = new Set(ent.features);
  const rows = Object.entries(FEATURE_LABELS);
  const locked = rows.filter(([key]) => !open.has(key));
  const noted = rows.filter(([, row]) => row.note);

  return (
    <div className="glass" style={{ padding: 12 }} data-testid="license-features">
      <div className="micro-label" style={{ fontSize: 9, marginBottom: 8 }}>
        {t('lic.whatsOpen')}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0,1fr))', gap: 6 }}>
        {rows.map(([key, row]) => {
          const on = open.has(key);
          return (
            <div
              key={key}
              style={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 7,
                fontSize: 11.5,
                color: on ? 'var(--ink-primary)' : 'var(--ink-muted)',
                minWidth: 0,
              }}
            >
              {on ? (
                <ShieldCheck
                  size={12}
                  strokeWidth={2}
                  style={{ color: 'var(--ph-lit-green)', flexShrink: 0, alignSelf: 'center' }}
                />
              ) : (
                <Lock
                  size={12}
                  strokeWidth={2}
                  style={{ color: 'var(--ink-faint)', flexShrink: 0, alignSelf: 'center' }}
                />
              )}
              <span style={{ minWidth: 0 }}>
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{t(row.label)}</span>
                {row.note && (
                  <span
                    style={{
                      marginLeft: 5,
                      padding: '1px 6px',
                      borderRadius: 999,
                      border: '1px solid var(--line-default)',
                      background: 'var(--ph-lit-black-a4)',
                      color: 'var(--ink-muted)',
                      fontSize: 9.5,
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {row.note.map((n) => t(n)).join(' · ')}
                  </span>
                )}
              </span>
            </div>
          );
        })}
      </div>
      {noted.length > 0 && (
        <div style={{ marginTop: 9, fontSize: 11, color: 'var(--ink-muted)', lineHeight: 1.5 }}>
          <div>{t('lic.noteExplain.phoneOnly', { note: t(PHONE_ONLY) })}</div>
          <div>{t('lic.noteExplain.inProgress', { note: t(IN_PROGRESS) })}</div>
        </div>
      )}
      {locked.length > 0 && (
        <div style={{ marginTop: 6, fontSize: 11, color: 'var(--ink-muted)', lineHeight: 1.45 }}>
          {t('lic.lockedExplain')}
        </div>
      )}
    </div>
  );
}

function InfoTile({
  label,
  value,
  mono,
  title,
}: {
  label: string;
  value: string;
  mono?: boolean;
  title?: string;
}) {
  return (
    <div
      style={{
        padding: '7px 9px',
        borderRadius: 8,
        border: '1px solid var(--ph-lit-black-a6)',
        background: 'var(--ph-lit-white-a40)',
        minWidth: 0,
      }}
      title={title}
    >
      <div style={{ fontSize: 8.5, color: 'var(--ink-muted)', fontWeight: 700, letterSpacing: '0.06em' }}>
        {label}
      </div>
      <div
        className={mono ? 'tabular' : undefined}
        style={{
          fontSize: 11.5,
          color: 'var(--ink-primary)',
          fontWeight: 600,
          marginTop: 2,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          fontFamily: mono ? 'var(--font-mono)' : undefined,
        }}
      >
        {value}
      </div>
    </div>
  );
}
