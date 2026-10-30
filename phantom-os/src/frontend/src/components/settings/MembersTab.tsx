import React, { useCallback, useEffect, useState } from 'react';
import { Loader2, Plus, Mail, Shield, User, X } from 'lucide-react';
import { request, ApiError } from '../../services/api';
import { useAuthStore } from '../../stores/authStore';
import { useTranslation } from '../../i18n/useTranslation';
import { tNow } from '../chat/tNow';
import type { MessageKey } from '../../i18n/messages/uk';

/** Те, що справді віддає GET /api/v1/tenant/members — голий масив рядків
 *  таблиці users цього простору. Пошти в моделі немає взагалі: іменем входу
 *  є `username`, і саме воно тут показується. Поле `email`, якого чекав цей
 *  компонент, не існувало ніде, крім вигаданих Alice/Bob/Charlie. */
interface Member {
  id: string;
  username: string;
  /** Доступ до пристрою: ROOT / OPERATOR / GUEST / API. */
  role: string;
  /** Роль у просторі: Owner / Admin / Member. */
  tenant_role: string;
}

const SPACE_ROLES: Record<string, MessageKey> = {
  Owner: 'settings.members.roleOwner',
  Admin: 'settings.members.roleAdmin',
  Member: 'settings.members.roleMember',
  Guest: 'settings.members.roleGuest',
};

const ACCESS_ROLES: Record<string, MessageKey> = {
  ROOT: 'settings.members.accRoot',
  OPERATOR: 'settings.members.accOperator',
  GUEST: 'settings.members.roleGuest',
  API: 'settings.members.accApi',
};

const spaceRole = (code: string): string => (SPACE_ROLES[code] ? tNow(SPACE_ROLES[code]) : code);
const accessRole = (code: string): string => (ACCESS_ROLES[code] ? tNow(ACCESS_ROLES[code]) : code);

/** Цей екран був написаний під темну тему класами, яких немає: `text-muted` у
 *  tailwind.config.ts не оголошено взагалі (є лише `ink-muted`), тож він нічого
 *  не фарбував, а `text-primary` — це бурштиновий акцент, а не чорнило. На
 *  денній кремовій темі підпис у вікні «Додати людину» виходив майже чорним на
 *  майже чорному. Тому кольори тут — з токенів, які йдуть за темою. */
const PANEL: React.CSSProperties = {
  background: 'var(--surface-raised)',
  border: '1px solid var(--glass-border)',
  boxShadow: 'var(--shadow-sm)',
};

const PANEL_MUTED: React.CSSProperties = { ...PANEL, color: 'var(--ink-muted)' };

const FIELD: React.CSSProperties = {
  background: 'var(--surface-deep)',
  border: '1px solid var(--line-default)',
  color: 'var(--ink-primary)',
};

type Load =
  | { s: 'reading' }
  | { s: 'ok' }
  | { s: 'failed'; why: string };

function failureLine(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return tNow('settings.members.noSpace');
    if (err.status === 401) return tNow('settings.members.sessionOver');
    return err.message || tNow('settings.members.listFailed');
  }
  return tNow('settings.members.coreDown');
}

export function MembersTab() {
  const { t } = useTranslation();
  const [members, setMembers] = useState<Member[]>([]);
  const [load, setLoad] = useState<Load>({ s: 'reading' });
  const me = useAuthStore((s) => s.user);

  const [showInviteModal, setShowInviteModal] = useState(false);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<'Admin' | 'Member'>('Member');
  const [inviting, setInviting] = useState(false);
  const [inviteError, setInviteError] = useState<string | null>(null);

  const fetchMembers = useCallback(async () => {
    try {
      const data = await request<Member[]>('GET', '/tenant/members');
      setMembers(Array.isArray(data) ? data : []);
      setLoad({ s: 'ok' });
    } catch (err) {
      setMembers([]);
      setLoad({ s: 'failed', why: failureLine(err) });
    }
  }, []);

  useEffect(() => {
    void fetchMembers();
  }, [fetchMembers]);

  const handleInvite = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!inviteEmail || inviting) return;
      setInviting(true);
      setInviteError(null);
      try {
        await request('POST', '/tenant/members/invite', {
          email: inviteEmail,
          tenant_role: inviteRole,
        });
        // Список перечитуємо з ядра. Тут раніше дописувався вигаданий рядок
        // «про всяк випадок, якщо API його не зберіг» — після перезавантаження
        // людина зникала, бо існувала лише в пам'яті вкладки.
        await fetchMembers();
        setShowInviteModal(false);
        setInviteEmail('');
        setInviteRole('Member');
      } catch (err) {
        setInviteError(
          err instanceof ApiError && err.status === 400
            ? t('settings.members.nameTaken')
            : failureLine(err),
        );
      } finally {
        setInviting(false);
      }
    },
    [inviteEmail, inviteRole, inviting, fetchMembers],
  );

  if (load.s === 'reading') {
    return (
      <div className="flex items-center justify-center p-8">
        <Loader2 className="w-6 h-6 animate-spin" style={{ color: 'var(--ink-muted)' }} />
      </div>
    );
  }

  const alone = load.s === 'ok' && members.length <= 1;

  return (
    <div className="flex flex-col gap-6 w-full max-w-4xl">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold" style={{ color: 'var(--ink-primary)' }}>
            {t('settings.members.title')}
          </h2>
          <p className="text-sm" style={{ color: 'var(--ink-muted)' }}>
            {t('settings.members.intro')}
          </p>
        </div>
        <button
          onClick={() => setShowInviteModal(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-xl transition-all shadow-sm text-sm font-medium"
          style={{
            background: 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
            border: 'none',
            color: 'white',
            minHeight: 44,
          }}
        >
          <Plus size={16} />
          {t('settings.members.add')}
        </button>
      </div>

      <div className="flex flex-col gap-3">
        {load.s === 'failed' && (
          <div className="p-4 rounded-2xl text-sm" style={PANEL_MUTED}>
            {load.why}
          </div>
        )}

        {members.map((member) => {
          const isMe = me?.id === member.id;
          return (
            <div
              key={member.id}
              className="flex items-center justify-between p-4 rounded-2xl"
              style={PANEL}
            >
              <div className="flex items-center gap-4">
                <div
                  className="w-10 h-10 rounded-full flex items-center justify-center overflow-hidden"
                  style={{
                    background: 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
                    color: 'white',
                  }}
                >
                  <User size={18} />
                </div>
                <div className="flex flex-col">
                  <span className="text-sm font-medium" style={{ color: 'var(--ink-primary)' }}>
                    {member.username}
                    {isMe && (
                      <span className="font-normal" style={{ color: 'var(--ink-muted)' }}>
                        {' '}
                        · {t('settings.members.you')}
                      </span>
                    )}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--ink-muted)' }}>
                    {accessRole(member.role)}
                  </span>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <span
                  className="text-xs font-medium px-2.5 py-1 rounded-full"
                  style={{
                    border: '1px solid var(--line-default)',
                    color:
                      member.tenant_role === 'Owner'
                        ? 'var(--primary-deep)'
                        : member.tenant_role === 'Admin'
                          ? 'var(--signal-info)'
                          : 'var(--ink-muted)',
                  }}
                >
                  {spaceRole(member.tenant_role)}
                </span>
              </div>
            </div>
          );
        })}

        {alone && (
          <div className="p-4 rounded-2xl text-sm" style={PANEL_MUTED}>
            {t('settings.members.alone')}
          </div>
        )}
      </div>

      {showInviteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div
            className="rounded-2xl shadow-2xl w-full max-w-md overflow-hidden relative"
            style={PANEL}
          >
            <button
              onClick={() => setShowInviteModal(false)}
              className="absolute top-4 right-4 transition-colors"
              style={{ color: 'var(--ink-muted)' }}
              aria-label={t('settings.members.close')}
            >
              <X size={20} />
            </button>

            <div className="p-6">
              <h3 className="text-xl font-semibold mb-1" style={{ color: 'var(--ink-primary)' }}>
                {t('settings.members.addTitle')}
              </h3>
              {/* Ядро не має поштового відправника: /invite лише створює рядок
                  користувача. Обіцяти лист — обіцяти те, чого не станеться. */}
              <p className="text-sm mb-6" style={{ color: 'var(--ink-muted)' }}>
                {t('settings.members.addNote')}
              </p>

              <form onSubmit={handleInvite} className="flex flex-col gap-4">
                <div className="flex flex-col gap-1.5">
                  <label
                    className="text-xs font-medium uppercase tracking-wider"
                    style={{ color: 'var(--ink-muted)' }}
                  >
                    {t('settings.members.email')}
                  </label>
                  <div className="relative">
                    <Mail
                      size={16}
                      className="absolute left-3 top-1/2 -translate-y-1/2"
                      style={{ color: 'var(--ink-muted)' }}
                    />
                    <input
                      type="email"
                      required
                      value={inviteEmail}
                      onChange={(e) => setInviteEmail(e.target.value)}
                      placeholder={t('settings.members.emailPh')}
                      className="w-full rounded-xl py-2.5 pl-9 pr-4 text-sm focus:outline-none transition-all"
                      style={{ ...FIELD, minHeight: 44 }}
                    />
                  </div>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label
                    className="text-xs font-medium uppercase tracking-wider"
                    style={{ color: 'var(--ink-muted)' }}
                  >
                    {t('settings.members.roleLabel')}
                  </label>
                  <div className="relative">
                    <Shield
                      size={16}
                      className="absolute left-3 top-1/2 -translate-y-1/2"
                      style={{ color: 'var(--ink-muted)' }}
                    />
                    <select
                      value={inviteRole}
                      onChange={(e) => setInviteRole(e.target.value as 'Admin' | 'Member')}
                      className="w-full rounded-xl py-2.5 pl-9 pr-4 text-sm focus:outline-none transition-all appearance-none cursor-pointer"
                      style={{ ...FIELD, minHeight: 44 }}
                    >
                      <option value="Member">{t('settings.members.optMember')}</option>
                      <option value="Admin">{t('settings.members.optAdmin')}</option>
                    </select>
                  </div>
                </div>

                {inviteError && (
                  <div
                    className="px-3 py-2 rounded-xl text-xs"
                    style={{
                      border: '1px solid var(--ph-lit-red-strong-a30)',
                      background: 'var(--ph-lit-red-strong-a8)',
                      color: 'var(--ph-lit-red-deep)',
                    }}
                  >
                    {inviteError}
                  </div>
                )}

                <button
                  type="submit"
                  disabled={inviting || !inviteEmail}
                  className="mt-2 w-full py-2.5 font-medium text-sm rounded-xl transition-all flex items-center justify-center gap-2 disabled:opacity-50"
                  style={{
                    minHeight: 44,
                    background:
                      inviting || !inviteEmail
                        ? 'var(--ph-lit-black-a6)'
                        : 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
                    border: 'none',
                    color: inviting || !inviteEmail ? 'var(--ink-muted)' : 'white',
                  }}
                >
                  {inviting ? <Loader2 size={16} className="animate-spin" /> : t('settings.members.addBtn')}
                </button>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
