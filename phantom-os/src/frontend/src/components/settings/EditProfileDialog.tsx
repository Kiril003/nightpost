/**
 * Phase-5 R1 Task E — EditProfileDialog.
 *
 * Modal for ROOT-only edit of an existing profile. Wraps PUT
 * `/api/v1/users/{user_id}` (avatar_url, pin, rfid_uid, preferences).
 * Username is intentionally read-only after creation (audit IDB-2 — the
 * username is the long-term identity key, renaming would orphan
 * tactical/strategic memory rows).
 */
import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { PhantomIcon } from '../core/PhantomIcon';
import { useAuthStore } from '../../stores/authStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useTranslation } from '../../i18n/useTranslation';

const API_PREFIX = '/api/v1';

interface EditableUser {
  id: string;
  username: string;
  role: 'ROOT' | 'OPERATOR' | 'GUEST';
  avatar_url: string | null;
  has_rfid: boolean;
  has_pin: boolean;
}

interface EditProfileDialogProps {
  open: boolean;
  user: EditableUser | null;
  token: string | null;
  onClose: () => void;
  onSaved: () => void;
}

async function patchUser(
  token: string,
  userId: string,
  body: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${API_PREFIX}/users/${userId}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => ({}));
    throw new Error(typeof detail.detail === 'string' ? detail.detail : `PUT failed (${res.status})`);
  }
}

export function EditProfileDialog({
  open,
  user,
  token,
  onClose,
  onSaved,
}: EditProfileDialogProps) {
  const { t } = useTranslation();
  const [avatarUrl, setAvatarUrl] = useState('');
  const [newPin, setNewPin] = useState('');
  const [newPinConfirm, setNewPinConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open && user) {
      setAvatarUrl(user.avatar_url ?? '');
      setNewPin('');
      setNewPinConfirm('');
      setError(null);
    }
  }, [open, user]);

  if (!user) return null;

  const submit = async () => {
    setError(null);
    if (!token) {
      setError(t('settings.editProfile.sessionLost'));
      return;
    }
    const body: Record<string, unknown> = {};
    const trimmedAvatar = avatarUrl.trim();
    if (trimmedAvatar !== (user.avatar_url ?? '')) {
      body.avatar_url = trimmedAvatar || null;
    }
    if (newPin.length > 0) {
      if (newPin.length < 4 || newPin.length > 12) {
        setError(t('settings.editProfile.pinLen'));
        return;
      }
      if (newPin !== newPinConfirm) {
        setError(t('settings.editProfile.pinMismatch'));
        return;
      }
      body.pin = newPin;
    }
    if (Object.keys(body).length === 0) {
      onClose();
      return;
    }
    setBusy(true);
    try {
      await patchUser(token, user.id, body);
      if (user.id === useAuthStore.getState().user?.id && 'avatar_url' in body) {
        useMessengerStore.getState().updateCurrentUser({ avatar: trimmedAvatar });
      }
      onSaved();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 flex items-center justify-center"
          style={{
            zIndex: 85,
            background: 'var(--ph-lit-ink-night-a55)',
          }}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
          onClick={onClose}
        >
          <motion.div
            className="bg-surface border border-line"
            style={{
              width: 460,
              padding: 24,
              borderRadius: 20,
            }}
            initial={{ scale: 0.94, y: 12 }}
            animate={{ scale: 1, y: 0 }}
            exit={{ scale: 0.94, y: 12 }}
            transition={{ duration: 0.22 }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-label={t('settings.editProfile.aria')}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <span className="eyebrow-amber">{t('settings.editProfile.eyebrow')}</span>
              <button
                type="button"
                onClick={onClose}
                aria-label={t('settings.editProfile.close')}
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: 10,
                  background: 'var(--ph-lit-black-a4)',
                  border: '1px solid var(--ph-lit-black-a6)',
                  color: 'var(--ink-muted)',
                  cursor: 'pointer',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <PhantomIcon name="close" size={16} aria-hidden />
              </button>
            </div>

            <h2 className="playfair" style={{ fontSize: 22, color: 'var(--ink-primary)', margin: '4px 0 14px' }}>
              {user.username}
            </h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <ReadOnlyRow label="USERNAME" value={user.username} hint={t('settings.editProfile.usernameHint')} />
              <ReadOnlyRow label={t('settings.editProfile.role')} value={user.role} hint={t('settings.editProfile.roleHint')} />
              <Field
                label={t('settings.editProfile.avatarUrl')}
                value={avatarUrl}
                onChange={setAvatarUrl}
                placeholder="https:// …"
              />
              <div style={{ borderTop: '1px solid var(--ph-lit-black-a6)', marginTop: 6, paddingTop: 12 }}>
                <div className="micro-label" style={{ fontSize: 9, marginBottom: 6 }}>
                  {t('settings.editProfile.changePin')}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <Field
                    label={t('settings.editProfile.newPin')}
                    value={newPin}
                    onChange={(v) => setNewPin(v.replace(/\D/g, '').slice(0, 12))}
                    placeholder="••••••"
                    type="password"
                    inputMode="numeric"
                  />
                  <Field
                    label={t('settings.editProfile.repeatPin')}
                    value={newPinConfirm}
                    onChange={(v) => setNewPinConfirm(v.replace(/\D/g, '').slice(0, 12))}
                    placeholder="••••••"
                    type="password"
                    inputMode="numeric"
                  />
                </div>
              </div>
              {error && (
                <div
                  role="alert"
                  style={{
                    padding: '8px 10px',
                    borderRadius: 8,
                    background: 'var(--ph-lit-red-a10)',
                    border: '1px solid var(--ph-lit-red-a32)',
                    color: 'var(--coral-deep, var(--ph-lit-coral-deep))',
                    fontSize: 12,
                    fontFamily: 'var(--font-display)',
                  }}
                >
                  {error}
                </div>
              )}
              <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                <button
                  type="button"
                  onClick={onClose}
                  disabled={busy}
                  style={{
                    minWidth: 120,
                    minHeight: 44,
                    borderRadius: 10,
                    border: '1px solid var(--ph-lit-black-a8)',
                    background: 'var(--ph-lit-white-a60)',
                    color: 'var(--ink-secondary)',
                    fontFamily: 'var(--font-display)',
                    fontSize: 12,
                    fontWeight: 600,
                    cursor: busy ? 'not-allowed' : 'pointer',
                  }}
                >
                  {t('settings.editProfile.cancel')}
                </button>
                <button
                  type="button"
                  onClick={submit}
                  disabled={busy}
                  style={{
                    flex: 1,
                    minHeight: 44,
                    borderRadius: 10,
                    border: '1px solid var(--ph-lit-amber-a55)',
                    background: busy
                      ? 'var(--ph-lit-amber-a18)'
                      : 'var(--primary)',
                    color: busy ? 'var(--ink-muted)' : 'var(--ph-lit-white)',
                    fontFamily: 'var(--font-display)',
                    fontSize: 13,
                    fontWeight: 700,
                    letterSpacing: '0.04em',
                    cursor: busy ? 'not-allowed' : 'pointer',
                  }}
                >
                  {busy ? t('settings.editProfile.saving') : t('settings.editProfile.save')}
                </button>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

function ReadOnlyRow({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <span className="micro-label" style={{ fontSize: 9 }}>{label}</span>
      <div
        style={{
          padding: '10px 12px',
          borderRadius: 10,
          border: '1px solid var(--ph-lit-black-a4)',
          background: 'var(--ph-lit-black-a3)',
          fontSize: 13,
          color: 'var(--ink-secondary)',
          fontFamily: 'var(--font-display)',
        }}
      >
        {value}
      </div>
      {hint && (
        <div
          className="playfair"
          style={{ fontSize: 10, color: 'var(--ink-muted)', fontStyle: 'italic' }}
        >
          {hint}
        </div>
      )}
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
  inputMode,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: string;
  inputMode?: 'text' | 'numeric' | 'decimal';
}) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span className="micro-label" style={{ fontSize: 9 }}>
        {label.toUpperCase()}
      </span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        inputMode={inputMode}
        style={{
          padding: '10px 12px',
          borderRadius: 10,
          border: '1px solid var(--ph-lit-black-a8)',
          background: 'var(--ph-lit-white-a65)',
          fontSize: 14,
          color: 'var(--ink-primary)',
          fontFamily: type === 'password' || inputMode === 'numeric' ? 'var(--font-mono)' : 'var(--font-display)',
          minHeight: 44,
        }}
      />
    </label>
  );
}
