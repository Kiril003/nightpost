import { useEffect, useMemo, useRef, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { Modal } from '@/components/Modal';
import { PinPrompt } from '@/components/PinPrompt';
import { AudioRecordModal } from '@/components/AudioRecordModal';
import { Banner } from '@/components/Banner';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useCompanionControlStore } from '@/state/companionControlStore';
import { vaultDelete, vaultGetAll, vaultPut, type StoredRecord } from '@/services/vaultStorage';
import { sealNote, openNote, sealBytes, openBytes } from '@/services/vaultCrypto';
import { listCards, isUnpairedError } from '@/services/vaultSync';
import { useVaultStore } from '@/state/vaultStore';
import { VaultCardAI } from '@/components/VaultCardAI';
import { useT } from '@/hooks/useT';
import type { PhantomTheme } from '@/types/theme';

interface VaultScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

const GHOST_GREEN = '#16A34A';

interface SessionRecord extends StoredRecord {
  unlockedBody?: string;
  unlockedAudioUrl?: string;
  unlockedMime?: string;
}

interface PendingAudio {
  blob: Blob;
  mime: string;
  durationMs: number;
}

function formatDuration(ms: number): string {
  const total = Math.max(1, Math.round(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export function VaultScreen({ themeOverride, framed = false }: VaultScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const t = useT();
  const [records, setRecords] = useState<SessionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [pinTarget, setPinTarget] = useState<
    | { kind: 'unlock'; record: SessionRecord }
    | { kind: 'create_note' }
    | { kind: 'create_audio'; pending: PendingAudio }
    | null
  >(null);
  const [composeOpen, setComposeOpen] = useState(false);
  const [composeText, setComposeText] = useState('');
  const [audioOpen, setAudioOpen] = useState(false);
  // Bumped whenever the desktop pushes lock_vault — used as a reset
  // key on every VaultCardAI so its open/history state goes away too.
  const [cardAiResetKey, setCardAiResetKey] = useState(0);
  const [lockBanner, setLockBanner] = useState(false);
  const vaultLocked = useCompanionControlStore((s) => s.vaultLocked);
  const setVaultLocked = useCompanionControlStore((s) => s.setVaultLocked);
  // Latest records ref so the lock effect can revoke object URLs
  // without re-running every time records change.
  const recordsRef = useRef<SessionRecord[]>(records);
  recordsRef.current = records;

  useEffect(() => {
    void reload();
    void refreshServerCards();
    // Cleanup any URL.createObjectURL on unmount.
    return () => {
      recordsRef.current.forEach(
        (r) => r.unlockedAudioUrl && URL.revokeObjectURL(r.unlockedAudioUrl),
      );
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Desktop pushed `lock_vault` over WS → companion-control flipped
  // companionControlStore.vaultLocked. Re-lock every open record
  // (revoke audio URLs, drop unlocked bodies), reset every
  // VaultCardAI expander via a remount key, surface a banner, then
  // clear the flag so the next lock_vault verb fires again.
  useEffect(() => {
    if (!vaultLocked) return;
    setRecords((rs) => {
      rs.forEach((r) => r.unlockedAudioUrl && URL.revokeObjectURL(r.unlockedAudioUrl));
      return rs.map((r) => ({
        ...r,
        unlockedBody: undefined,
        unlockedAudioUrl: undefined,
        unlockedMime: undefined,
      }));
    });
    setCardAiResetKey((k) => k + 1);
    setLockBanner(true);
    setVaultLocked(false);
  }, [vaultLocked, setVaultLocked]);

  // Polish — pull the server-side card library on mount + on demand.
  // Failures while unpaired are silenced (the user can't fix them and
  // a noisy toast on every Vault open would be wrong).
  const refreshServerCards = async () => {
    try {
      const cards = await listCards();
      useVaultStore.getState().setServerCards(cards);
    } catch (err) {
      if (!isUnpairedError(err)) {
        useToastStore.getState().push('warn', `Vault sync: ${(err as Error).message}`);
      }
    }
  };

  const reload = async () => {
    setLoading(true);
    try {
      const list = await vaultGetAll();
      setRecords(list);
    } catch (err) {
      useToastStore.getState().push('error', t('vault.toast.load_failed', { msg: (err as Error).message }));
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = () => {
    if (!composeText.trim()) return;
    setPinTarget({ kind: 'create_note' });
  };

  const handleUnlock = (rec: SessionRecord) => {
    if (rec.unlockedBody || rec.unlockedAudioUrl) {
      // Already unlocked → re-lock for safety. Revoke any object URL first.
      if (rec.unlockedAudioUrl) URL.revokeObjectURL(rec.unlockedAudioUrl);
      setRecords((rs) =>
        rs.map((r) =>
          r.id === rec.id
            ? { ...r, unlockedBody: undefined, unlockedAudioUrl: undefined, unlockedMime: undefined }
            : r,
        ),
      );
      return;
    }
    setPinTarget({ kind: 'unlock', record: rec });
  };

  const onAudioSeal = async (blob: Blob, mime: string, durationMs: number) => {
    setPinTarget({ kind: 'create_audio', pending: { blob, mime, durationMs } });
  };

  const onPin = async (pin: string) => {
    if (!pinTarget) return;
    try {
      if (pinTarget.kind === 'create_note') {
        const sealed = await sealNote(pin, composeText);
        const rec: StoredRecord = {
          id: `vault-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
          kind: 'note',
          label: composeText.slice(0, 30),
          when: Date.now(),
          iv: sealed.iv,
          ciphertext: sealed.ciphertext,
          size: null,
        };
        await vaultPut(rec);
        setComposeText('');
        setComposeOpen(false);
        await reload();
        useToastStore.getState().push('success', t('vault.toast.sealed'));
      } else if (pinTarget.kind === 'create_audio') {
        const { blob, mime, durationMs } = pinTarget.pending;
        const buf = new Uint8Array(await blob.arrayBuffer());
        const sealed = await sealBytes(pin, buf);
        const dur = formatDuration(durationMs);
        const rec: StoredRecord = {
          id: `vault-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
          kind: 'audio',
          label: t('vault.audio.preview', { dur }),
          when: Date.now(),
          iv: sealed.iv,
          ciphertext: sealed.ciphertext,
          size: `${dur} · ${mime || 'audio/webm'}`,
        };
        await vaultPut(rec);
        await reload();
        useToastStore.getState().push('success', t('vault.toast.audio_sealed', { dur }));
      } else if (pinTarget.kind === 'unlock') {
        const r = pinTarget.record;
        if (r.kind === 'audio') {
          const bytes = await openBytes(pin, { iv: r.iv, ciphertext: r.ciphertext });
          const mimeFromSize = (r.size?.split('·')[1] ?? 'audio/webm').trim();
          const blob = new Blob([new Uint8Array(bytes)], { type: mimeFromSize });
          const url = URL.createObjectURL(blob);
          setRecords((rs) =>
            rs.map((x) =>
              x.id === r.id ? { ...x, unlockedAudioUrl: url, unlockedMime: mimeFromSize } : x,
            ),
          );
        } else {
          const body = await openNote(pin, { iv: r.iv, ciphertext: r.ciphertext });
          setRecords((rs) => rs.map((x) => (x.id === r.id ? { ...x, unlockedBody: body } : x)));
        }
        useToastStore.getState().push('success', t('vault.toast.unlocked'));
      }
    } catch (err) {
      useToastStore.getState().push('error', t('vault.toast.bad_pin'));
      console.error(err);
    } finally {
      setPinTarget(null);
    }
  };

  const headerCount = useMemo(() => {
    if (records.length === 1) return t('vault.count.one', { n: '1' });
    return t('vault.count.many', { n: String(records.length) });
  }, [records.length, t]);

  const serverCards = useVaultStore((s) => s.serverCards);
  const serverCardsLoaded = useVaultStore((s) => s.serverCardsLoaded);

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '10px 16px 6px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: GHOST_GREEN, boxShadow: `0 0 8px ${GHOST_GREEN}` }} />
          <span style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.22em', fontWeight: 700, color: GHOST_GREEN }}>
            {t('vault.local_only')}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginTop: 6 }}>
          <span style={{ fontSize: 26, fontWeight: 700, color: theme.ink, letterSpacing: '-0.02em' }}>{t('vault.title')}</span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color: theme.ink3 }}>{headerCount}</span>
        </div>
      </div>

      <div
        style={{ flex: 1, padding: '8px 12px 14px', display: 'flex', flexDirection: 'column', gap: 10, position: 'relative', minHeight: 0, overflowY: 'auto' }}
        className="ph-scroll"
      >
        {lockBanner && (
          <Banner
            theme={theme}
            kind="warn"
            title="vault замкнено"
            message="phantom-os попросив масувати всі картки. Підтверди PIN щоб знову відкрити."
            autoHideMs={3500}
            onDismiss={() => setLockBanner(false)}
          />
        )}
        {/* Polish — server-side card library section. Lives above the
            GHOST records because it's the most-touched surface
            (logins / payments / API keys) while GHOST is sensitive. */}
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '4px 4px 0' }}>
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
            особистий vault
          </span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>
            {serverCardsLoaded ? `${serverCards.length} cards` : 'sync…'}
          </span>
          <button
            onClick={() => void refreshServerCards()}
            style={{
              marginLeft: 'auto',
              minHeight: 28,
              padding: '0 10px',
              borderRadius: 8,
              border: `1px solid ${theme.glassBorder}`,
              background: 'transparent',
              color: theme.ink3,
              fontFamily: 'var(--mono)',
              fontSize: 9,
              fontWeight: 700,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
            }}
          >
            sync
          </button>
        </div>

        {serverCardsLoaded && serverCards.length === 0 && (
          <GlassCard level="subtle" theme={theme} style={{ padding: '12px 14px' }}>
            <div style={{ fontSize: 12, color: theme.ink3, lineHeight: 1.45 }}>
              На phantom-os ще немає особистих карток (логіни, паролі,
              API-ключі). Створи на десктопі — тут одразу з'являться.
            </div>
          </GlassCard>
        )}

        {serverCards.map((card) => {
          const fieldEntries = Object.entries(card.fields);
          return (
            <GlassCard key={card.id} level="card" theme={theme} style={{ padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    color: theme.ink3,
                    fontWeight: 700,
                  }}
                >
                  {card.kind}
                </div>
                <div style={{ flex: 1, fontSize: 14, color: theme.ink, fontWeight: 600 }}>
                  {card.label}
                </div>
              </div>
              {fieldEntries.length > 0 && (
                <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {fieldEntries.map(([name, value]) => {
                    const isSecret = card.field_kinds[name] === 'secret';
                    return (
                      <div
                        key={name}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          fontSize: 12,
                          fontFamily: isSecret ? 'var(--mono)' : 'var(--sans)',
                        }}
                      >
                        <span
                          style={{
                            fontFamily: 'var(--mono)',
                            fontSize: 9,
                            color: theme.ink3,
                            letterSpacing: '0.14em',
                            minWidth: 80,
                          }}
                        >
                          {name}
                        </span>
                        <span style={{ color: isSecret ? theme.ink3 : theme.ink }}>
                          {isSecret ? '••••••••' : value}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
              {card.tags.length > 0 && (
                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 6 }}>
                  {card.tags.map((tag) => (
                    <span
                      key={tag}
                      style={{
                        fontFamily: 'var(--mono)',
                        fontSize: 9,
                        padding: '2px 7px',
                        borderRadius: 999,
                        border: `1px solid ${theme.glassBorder}`,
                        color: theme.ink3,
                        letterSpacing: '0.12em',
                        textTransform: 'uppercase',
                      }}
                    >
                      {tag}
                    </span>
                  ))}
                </div>
              )}
              <VaultCardAI
                key={`${card.id}-${cardAiResetKey}`}
                cardId={card.id}
                cardLabel={card.label}
              />
            </GlassCard>
          );
        })}

        <div style={{ height: 6 }} />

        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '4px 4px 0' }}>
          <span
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: GHOST_GREEN,
              fontWeight: 700,
            }}
          >
            ghost · local only
          </span>
        </div>

        {loading ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState theme={theme} icon="…" title={t('vault.reading')} compact />
          </GlassCard>
        ) : records.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="◇"
              title={t('vault.empty.title')}
              hint={t('vault.empty.hint')}
              cta={
                <div style={{ display: 'flex', gap: 8 }}>
                  <button
                    onClick={() => setComposeOpen(true)}
                    style={{
                      padding: '10px 18px',
                      borderRadius: 999,
                      background: GHOST_GREEN,
                      color: '#fff',
                      fontFamily: 'var(--mono)',
                      fontSize: 11,
                      fontWeight: 700,
                      letterSpacing: '0.18em',
                      textTransform: 'uppercase',
                    }}
                  >
                    {t('vault.cta.note')}
                  </button>
                  <button
                    onClick={() => setAudioOpen(true)}
                    style={{
                      padding: '10px 18px',
                      borderRadius: 999,
                      background: 'transparent',
                      color: GHOST_GREEN,
                      border: `1px solid ${GHOST_GREEN}`,
                      fontFamily: 'var(--mono)',
                      fontSize: 11,
                      fontWeight: 700,
                      letterSpacing: '0.18em',
                      textTransform: 'uppercase',
                    }}
                  >
                    {t('vault.cta.audio')}
                  </button>
                </div>
              }
            />
          </GlassCard>
        ) : (
          records.map((it) => (
            <GlassCard key={it.id} level="card" theme={theme} style={{ padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.22em', color: GHOST_GREEN }}>
                  {it.kind.toUpperCase()}
                </span>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>
                  · {new Date(it.when).toLocaleString()}
                </span>
                <button
                  onClick={() => handleUnlock(it)}
                  style={{ marginLeft: 'auto', fontSize: 14, color: theme.ink3 }}
                  aria-label={it.unlockedBody || it.unlockedAudioUrl ? t('vault.lock_aria') : t('vault.unlock_aria')}
                >
                  {it.unlockedBody || it.unlockedAudioUrl ? '🔓' : '🔒'}
                </button>
              </div>
              {it.kind === 'audio' && it.unlockedAudioUrl ? (
                <audio
                  controls
                  src={it.unlockedAudioUrl}
                  style={{ width: '100%', marginTop: 10 }}
                  preload="metadata"
                />
              ) : it.unlockedBody ? (
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontStyle: 'italic',
                    fontSize: 14,
                    color: theme.ink,
                    marginTop: 8,
                    lineHeight: 1.4,
                  }}
                >
                  {it.unlockedBody}
                </div>
              ) : (
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontStyle: 'italic',
                    fontSize: 14,
                    color: theme.ink2,
                    marginTop: 8,
                    lineHeight: 1.4,
                    filter: 'blur(2.4px)',
                    userSelect: 'none',
                  }}
                  aria-hidden
                >
                  {it.label || '████ ███ ████ ███████'}
                </div>
              )}
              <div style={{ marginTop: 10, display: 'flex', gap: 6 }}>
                <button
                  onClick={async () => {
                    if (it.unlockedAudioUrl) URL.revokeObjectURL(it.unlockedAudioUrl);
                    await vaultDelete(it.id);
                    await reload();
                    useToastStore.getState().push('info', t('vault.toast.deleted'));
                  }}
                  style={{
                    padding: '4px 10px',
                    borderRadius: 999,
                    fontFamily: 'var(--mono)',
                    fontSize: 9.5,
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                    fontWeight: 700,
                    background: 'transparent',
                    color: theme.coral,
                    border: `1px solid ${theme.coral}55`,
                  }}
                >
                  {t('vault.delete')}
                </button>
              </div>
            </GlassCard>
          ))
        )}
        {records.length > 0 && (
          <div style={{ position: 'sticky', bottom: 0, display: 'flex', justifyContent: 'flex-end', gap: 8, pointerEvents: 'none' }}>
            <button
              onClick={() => setAudioOpen(true)}
              style={{
                pointerEvents: 'auto',
                width: 52,
                height: 52,
                borderRadius: 18,
                background: 'transparent',
                border: `2px solid ${GHOST_GREEN}`,
                color: GHOST_GREEN,
                fontSize: 22,
                fontWeight: 400,
                lineHeight: 1,
                backdropFilter: 'blur(12px)',
                WebkitBackdropFilter: 'blur(12px)',
              }}
              aria-label={t('vault.cta.audio')}
            >
              🎙
            </button>
            <button
              onClick={() => setComposeOpen(true)}
              style={{
                pointerEvents: 'auto',
                width: 52,
                height: 52,
                borderRadius: 18,
                background: `radial-gradient(circle at 30% 25%, ${GHOST_GREEN}, #0e7a36)`,
                boxShadow: `0 8px 22px ${GHOST_GREEN}66`,
                color: '#fff',
                fontSize: 26,
                fontWeight: 300,
                lineHeight: 1,
              }}
              aria-label={t('vault.cta.note')}
            >
              +
            </button>
          </div>
        )}
      </div>

      <BottomNav theme={theme} accent={GHOST_GREEN} />

      <Modal open={composeOpen} onClose={() => setComposeOpen(false)} title={t('vault.compose.title')}>
        <textarea
          value={composeText}
          onChange={(e) => setComposeText(e.target.value)}
          rows={6}
          placeholder={t('vault.compose.placeholder')}
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
            {t('vault.compose.cancel')}
          </button>
          <button
            onClick={handleCreate}
            disabled={!composeText.trim()}
            style={{
              flex: 1,
              padding: '11px 14px',
              borderRadius: 12,
              background: composeText.trim() ? GHOST_GREEN : theme.glassBorder,
              color: composeText.trim() ? '#fff' : theme.ink3,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            {t('vault.compose.seal')}
          </button>
        </div>
      </Modal>

      <AudioRecordModal
        open={audioOpen}
        onClose={() => setAudioOpen(false)}
        onSeal={onAudioSeal}
        onError={(err) =>
          useToastStore.getState().push('error', t('vault.toast.audio_failed', { msg: err.message }))
        }
      />

      <PinPrompt
        open={!!pinTarget}
        title={pinTarget?.kind === 'unlock' ? t('vault.pin.unlock.title') : t('vault.pin.create.title')}
        hint={pinTarget?.kind === 'unlock' ? t('vault.pin.unlock.hint') : t('vault.pin.create.hint')}
        onCancel={() => setPinTarget(null)}
        onSubmit={onPin}
      />
    </PhoneShell>
  );
}
