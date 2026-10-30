/**
 * Phase-5 R1 Task E (PHASE_5_FEATURE_COMPLETION.md) — Profile management UI.
 *
 * Renders the ROOT-only post-login user CRUD: a list of every operator
 * with avatar mandala + role + last-seen, plus per-row edit / role /
 * delete actions. Wraps the existing `users_router` REST surface in
 * `routes_auth.py` (GET / PUT / DELETE / PUT/role); no backend changes.
 *
 * Gated on `useAuthStore.user.role === 'ROOT'` — non-ROOT operators
 * see an empty-state explainer instead of the controls.
 */
import { useEffect, useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useAuthStore } from '../../stores/authStore';
import { EditProfileDialog } from './EditProfileDialog';
import { PhantomIcon } from '../core/PhantomIcon';
import { useTranslation } from '../../i18n/useTranslation';
import { currentDateTag } from '../chat/tNow';

const API_PREFIX = '/api/v1';

interface ManagedUser {
  id: string;
  username: string;
  role: 'ROOT' | 'OPERATOR' | 'GUEST';
  avatar_url: string | null;
  has_rfid: boolean;
  has_pin: boolean;
  created_at: string;
  last_seen_at: string;
}

const ROLE_TINT: Record<ManagedUser['role'], string> = {
  ROOT: 'var(--coral, var(--ph-lit-red))',
  OPERATOR: 'var(--primary, var(--ph-lit-amber))',
  GUEST: 'var(--ink-muted, var(--ph-lit-c888888))',
};

async function fetchUsers(token: string): Promise<ManagedUser[]> {
  const res = await fetch(`${API_PREFIX}/users`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => ({}));
    throw new Error(typeof detail.detail === 'string' ? detail.detail : `GET /users ${res.status}`);
  }
  const body = await res.json();
  return body.users as ManagedUser[];
}

async function deleteUser(token: string, userId: string): Promise<void> {
  const res = await fetch(`${API_PREFIX}/users/${userId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok && res.status !== 204) {
    const detail = await res.json().catch(() => ({}));
    throw new Error(typeof detail.detail === 'string' ? detail.detail : `DELETE /users/${userId} ${res.status}`);
  }
}

async function setRole(token: string, userId: string, role: ManagedUser['role']): Promise<void> {
  const res = await fetch(`${API_PREFIX}/users/${userId}/role`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => ({}));
    throw new Error(typeof detail.detail === 'string' ? detail.detail : `PUT /users/${userId}/role ${res.status}`);
  }
}

export function ProfileManagementSection() {
  const { t } = useTranslation();
  const token = useAuthStore((s) => s.token);
  const me = useAuthStore((s) => s.user);
  const [users, setUsers] = useState<ManagedUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ManagedUser | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<ManagedUser | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const list = await fetchUsers(token);
      setUsers(list);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'load failed');
      setUsers([]);
    }
  }, [token]);

  useEffect(() => {
    if (me?.role === 'ROOT') void reload();
  }, [me, reload]);

  if (!me) return null;
  if (me.role !== 'ROOT') {
    return (
      <SectionFrame title={t('pm.title')}>
        <div className="playfair" style={{ fontSize: 13, color: 'var(--ink-muted)', fontStyle: 'italic' }}>
          {t('pm.rootOnly')}{' '}
          <strong style={{ color: ROLE_TINT[me.role as ManagedUser['role']] }}>{me.role}</strong>.
        </div>
      </SectionFrame>
    );
  }

  const performDelete = async (user: ManagedUser) => {
    if (!token) return;
    setBusy(true);
    try {
      await deleteUser(token, user.id);
      setConfirmDelete(null);
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'delete failed');
    } finally {
      setBusy(false);
    }
  };

  const performRoleChange = async (user: ManagedUser, role: ManagedUser['role']) => {
    if (!token) return;
    setBusy(true);
    try {
      await setRole(token, user.id, role);
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'role change failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <SectionFrame title={t('pm.title')}>
      {error && <ErrorBanner message={error} onDismiss={() => setError(null)} />}
      {users === null && (
        <div style={{ padding: 16, color: 'var(--ink-muted)', fontSize: 12 }}>
          {t('pm.loading')}
        </div>
      )}
      {users && users.length === 0 && (
        <div className="playfair" style={{ fontSize: 13, color: 'var(--ink-muted)', fontStyle: 'italic' }}>
          {t('pm.none')}
        </div>
      )}
      {users && users.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {users.map((u) => (
            <UserRow
              key={u.id}
              user={u}
              isMe={u.id === me.id}
              busy={busy}
              onEdit={() => setEditing(u)}
              onRoleChange={(role) => performRoleChange(u, role)}
              onDelete={() => setConfirmDelete(u)}
            />
          ))}
        </div>
      )}

      <EditProfileDialog
        open={editing !== null}
        user={editing}
        token={token}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          void reload();
        }}
      />

      <AnimatePresence>
        {confirmDelete && (
          <ConfirmDialog
            user={confirmDelete}
            busy={busy}
            onCancel={() => setConfirmDelete(null)}
            onConfirm={() => performDelete(confirmDelete)}
          />
        )}
      </AnimatePresence>
    </SectionFrame>
  );
}

/* ───────────────────────────────────────────────────────── User row ── */

function UserRow({
  user,
  isMe,
  busy,
  onEdit,
  onRoleChange,
  onDelete,
}: {
  user: ManagedUser;
  isMe: boolean;
  busy: boolean;
  onEdit: () => void;
  onRoleChange: (role: ManagedUser['role']) => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  const lastSeen = formatRelative(new Date(user.last_seen_at));
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '10px 12px',
        borderRadius: 12,
        background: 'var(--ph-lit-white-a55)',
        border: '1px solid var(--ph-lit-black-a5)',
      }}
    >
      <Avatar username={user.username} avatarUrl={user.avatar_url} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink-primary)' }}>
            {user.username}
          </span>
          {isMe && (
            <span
              style={{
                fontSize: 9,
                padding: '1px 6px',
                borderRadius: 'var(--radius-pill)',
                background: 'var(--ph-lit-amber-a18)',
                color: 'var(--ph-lit-amber-ink)',
                letterSpacing: '0.06em',
                fontWeight: 700,
              }}
            >
              {t('pm.you')}
            </span>
          )}
        </div>
        <div
          style={{
            fontSize: 10,
            color: 'var(--ink-muted)',
            marginTop: 2,
            display: 'flex',
            gap: 6,
            alignItems: 'center',
          }}
        >
          <span>last seen {lastSeen}</span>
          {user.has_pin && <Pill>PIN</Pill>}
          {user.has_rfid && <Pill>RFID</Pill>}
        </div>
      </div>
      <RoleSelector
        value={user.role}
        disabled={isMe || busy}
        onChange={onRoleChange}
      />
      <RowButton onClick={onEdit} icon="edit" label="Edit" disabled={busy} />
      <RowButton
        onClick={onDelete}
        icon="delete"
        label="Delete"
        disabled={isMe || busy}
        tone="alert"
      />
    </div>
  );
}

function RoleSelector({
  value,
  disabled,
  onChange,
}: {
  value: ManagedUser['role'];
  disabled: boolean;
  onChange: (role: ManagedUser['role']) => void;
}) {
  const { t } = useTranslation();
  return (
    <select
      value={value}
      disabled={disabled}
      onChange={(e) => {
        const next = e.target.value as ManagedUser['role'];
        if (next !== value) onChange(next);
      }}
      style={{
        minHeight: 36,
        padding: '4px 8px',
        borderRadius: 8,
        border: `1px solid ${ROLE_TINT[value]}`,
        background: 'var(--ph-lit-white-a70)',
        color: ROLE_TINT[value],
        fontFamily: 'var(--font-display)',
        fontSize: 11,
        fontWeight: 700,
        letterSpacing: '0.06em',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
      }}
      title={disabled ? t('pm.roleLocked') : t('pm.changeRole')}
    >
      <option value="ROOT">ROOT</option>
      <option value="OPERATOR">OPERATOR</option>
      <option value="GUEST">GUEST</option>
    </select>
  );
}

function RowButton({
  onClick,
  icon,
  label,
  disabled,
  tone = 'default',
}: {
  onClick: () => void;
  icon: string;
  label: string;
  disabled: boolean;
  tone?: 'default' | 'alert';
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      style={{
        width: 44,
        height: 44,
        borderRadius: 10,
        border: '1px solid var(--ph-lit-black-a6)',
        background: tone === 'alert' ? 'var(--ph-lit-red-a8)' : 'var(--ph-lit-white-a60)',
        color: disabled
          ? 'var(--ink-muted)'
          : tone === 'alert'
            ? 'var(--coral, var(--ph-lit-red))'
            : 'var(--ink-secondary)',
        cursor: disabled ? 'not-allowed' : 'pointer',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        opacity: disabled ? 0.4 : 1,
      }}
    >
      <PhantomIcon name={icon} size={18} aria-hidden />
    </button>
  );
}

function Avatar({ username, avatarUrl }: { username: string; avatarUrl: string | null }) {
  if (avatarUrl) {
    return (
      <img
        src={avatarUrl}
        alt={username}
        style={{ width: 36, height: 36, borderRadius: '50%', objectFit: 'cover' }}
      />
    );
  }
  return (
    <div
      style={{
        width: 36,
        height: 36,
        borderRadius: '50%',
        background: `var(--ph-person-${(username.charCodeAt(0) || 0) % 6})`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'var(--ph-lit-white)',
        fontFamily: 'var(--font-display)',
        fontSize: 14,
        fontWeight: 700,
      }}
    >
      {username.slice(0, 1).toUpperCase()}
    </div>
  );
}

function Pill({ children }: { children: React.ReactNode }) {
  return (
    <span
      style={{
        fontSize: 9,
        padding: '0 5px',
        borderRadius: 'var(--radius-pill)',
        background: 'var(--ph-lit-black-a4)',
        color: 'var(--ink-secondary)',
        letterSpacing: '0.06em',
        fontWeight: 600,
      }}
    >
      {children}
    </span>
  );
}

/* ───────────────────────────────────────────────────────── Confirm ── */

function ConfirmDialog({
  user,
  busy,
  onCancel,
  onConfirm,
}: {
  user: ManagedUser;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <motion.div
      className="fixed inset-0 flex items-center justify-center"
      style={{
        zIndex: 90,
        background: 'var(--ph-lit-ink-night-a55)',
      }}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      onClick={onCancel}
    >
      <div
        className="bg-surface border border-line"
        style={{ width: 380, padding: 20, borderRadius: 16 }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="eyebrow-amber" style={{ color: 'var(--coral, var(--ph-lit-red))' }}>
          {t('pm.deleteEyebrow')}
        </div>
        <h3 className="playfair" style={{ fontSize: 18, color: 'var(--ink-primary)', margin: '6px 0 10px' }}>
          {t('pm.deleteQ', { name: user.username })}
        </h3>
        <p style={{ fontSize: 12, color: 'var(--ink-secondary)' }}>
          {t('pm.deleteBody')}
        </p>
        <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            style={{
              flex: 1,
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
            {t('pm.cancel')}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            style={{
              flex: 1,
              minHeight: 44,
              borderRadius: 10,
              border: '1px solid var(--ph-lit-red-a50)',
              background: 'var(--ph-lit-red)',
              color: 'var(--ph-lit-white)',
              fontFamily: 'var(--font-display)',
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '0.04em',
              cursor: busy ? 'not-allowed' : 'pointer',
            }}
          >
            {busy ? t('pm.deleting') : t('pm.delete')}
          </button>
        </div>
      </div>
    </motion.div>
  );
}

/* ───────────────────────────────────────────────────────── Frame ── */

function SectionFrame({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div
      className="glass"
      style={{
        marginTop: 16,
        padding: 16,
        borderRadius: 14,
      }}
    >
      <div className="eyebrow-amber" style={{ marginBottom: 8 }}>
        {title.toUpperCase()}
      </div>
      {children}
    </div>
  );
}

function ErrorBanner({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  const { t } = useTranslation();
  return (
    <div
      role="alert"
      style={{
        marginBottom: 10,
        padding: '8px 12px',
        borderRadius: 8,
        background: 'var(--ph-lit-red-a10)',
        border: '1px solid var(--ph-lit-red-a32)',
        color: 'var(--coral-deep, var(--ph-lit-coral-deep))',
        fontSize: 12,
        display: 'flex',
        alignItems: 'center',
        gap: 8,
      }}
    >
      <span style={{ flex: 1 }}>{message}</span>
      <button
        type="button"
        onClick={onDismiss}
        aria-label={t('pm.close')}
        style={{
          width: 24,
          height: 24,
          borderRadius: 6,
          border: 'none',
          background: 'transparent',
          color: 'var(--coral-deep, var(--ph-lit-coral-deep))',
          cursor: 'pointer',
        }}
      >
        ×
      </button>
    </div>
  );
}

function formatRelative(d: Date): string {
  const diff = Date.now() - d.getTime();
  const tag = currentDateTag();
  const rel = new Intl.RelativeTimeFormat(tag, { numeric: 'auto', style: 'narrow' });
  const m = Math.floor(Math.max(0, diff) / 60000);
  if (m < 60) return rel.format(-m, 'minute');
  const h = Math.floor(m / 60);
  if (h < 24) return rel.format(-h, 'hour');
  const day = Math.floor(h / 24);
  if (day < 7) return rel.format(-day, 'day');
  return d.toLocaleDateString(tag, { day: 'numeric', month: 'short' });
}
