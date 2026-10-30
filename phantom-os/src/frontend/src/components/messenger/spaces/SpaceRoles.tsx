import React, { useMemo, useState } from 'react';
import { Check, ChevronRight, Eye, Plus, Shield, Users } from 'lucide-react';
import type { RoleColor, SpacePerm, SpaceRole } from '@shared/types/space';
import { useTranslation } from '../../../i18n/useTranslation';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import {
  activeRoles,
  can,
  mayEditRole,
  mayGiveRoles,
  OWNER_RANK,
  permsOf,
  topRank,
} from '../../../services/spaces/permissions';
import { liveMembers } from '../../../services/spaces/spaceRooms';
import { postSpaceEvent } from '../../../services/spaces/spaceWire';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../kit/Avatar';
import { SectionLabel, SpaceFrame, SpacePrimary } from './SpaceFrame';
import { useSpace } from './useSpace';

interface PermSwitchSpec {
  key: 'announce' | 'stage' | 'solve' | 'moderate' | 'kittyView' | 'kittyPay' | 'roles';
  labelKey:
    | 'space.perm.announce'
    | 'space.perm.stage'
    | 'space.perm.solve'
    | 'space.perm.moderate'
    | 'space.perm.kittyView'
    | 'space.perm.kittyPay'
    | 'space.perm.roles';
  perms: readonly SpacePerm[];
}

const PERM_SWITCHES: readonly PermSwitchSpec[] = [
  { key: 'announce', labelKey: 'space.perm.announce', perms: ['announce.post'] },
  { key: 'stage', labelKey: 'space.perm.stage', perms: ['stage.open', 'stage.grant'] },
  { key: 'solve', labelKey: 'space.perm.solve', perms: ['forum.solve'] },
  { key: 'moderate', labelKey: 'space.perm.moderate', perms: ['delete.any', 'pin'] },
  { key: 'kittyView', labelKey: 'space.perm.kittyView', perms: ['kitty.view'] },
  { key: 'kittyPay', labelKey: 'space.perm.kittyPay', perms: ['kitty.pay'] },
  { key: 'roles', labelKey: 'space.perm.roles', perms: ['roles.manage'] },
];

const BASE_PERMS: readonly SpacePerm[] = ['read', 'write', 'media', 'links', 'thread.create'];

/**
 * Список ролей простору (sv-roles): ієрархія ролей з кількістю людей, створення
 * нової ролі, перехід у редактор конкретної ролі (`sv-role`).
 */
export const SpaceRoles: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const { space, meId } = v;
  const now = Date.now();
  const members = useMemo(() => (space ? liveMembers(space) : []), [space]);
  const roles = useMemo(
    () => (space ? [...space.roles].sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id)) : []),
    [space],
  );
  const mayManage = space ? can(space, meId, 'roles.manage', now) : false;

  const createRole = async () => {
    const clean = name.trim();
    if (!space || !clean || busy || !mayManage) return;
    setBusy(true);
    try {
      const myRank = topRank(space, meId, now);
      const rank = myRank === OWNER_RANK ? 20 : Math.max(1, myRank - 1);
      const mine = permsOf(space, meId, now);
      const roleId = `r_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
      const color = (space.roles.length % 6) as RoleColor;
      const role: SpaceRole = {
        id: roleId,
        name: clean,
        color,
        rank,
        perms: BASE_PERMS.filter((p) => mine.has(p)),
        until: null,
      };
      await postSpaceEvent(v.profileId, chatId, { t: 'role.put', role });
      nav.bump();
      setName('');
      setCreating(false);
      nav.push({ s: 'role', chatId, roleId });
    } finally {
      setBusy(false);
    }
  };

  return (
    <SpaceFrame
      testId="space-roles"
      title={t('space.roles')}
      onBack={nav.pop}
      trailing={
        mayManage ? (
          <button
            type="button"
            data-testid="space-role-add"
            aria-label={t('space.role.new')}
            onClick={() => setCreating(true)}
            className="w-12 h-12 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink"
          >
            <Plus className="w-6 h-6" strokeWidth={2} aria-hidden />
          </button>
        ) : undefined
      }
    >
      {!space ? (
        <p className="px-4 py-8 text-center text-callout text-ink-3">{t('space.loading')}</p>
      ) : (
        <ul data-testid="space-roles-list" className="divide-y divide-line border-b border-line">
          {roles.map((r) => {
            const count = members.filter((m) => activeRoles(space, m.id, now).some((ar) => ar.id === r.id)).length;
            return (
              <li key={r.id}>
                <button
                  type="button"
                  data-testid={`space-role-row-${r.id}`}
                  onClick={() => nav.push({ s: 'role', chatId, roleId: r.id })}
                  className="w-full min-h-[64px] px-4 py-2.5 flex items-center gap-3 text-left active:bg-raised"
                >
                  <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
                    <Shield className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-headline text-ink">{r.name}</span>
                    <span data-testid={`space-role-count-${r.id}`} className="block truncate text-subhead text-ink-2 tnum">
                      {count}
                    </span>
                  </span>
                  <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <FormSheet
        open={creating}
        onClose={() => setCreating(false)}
        title={t('space.role.new')}
        testId="space-role-new-sheet"
        footer={
          <SpacePrimary testId="space-role-new-save" className="w-full" disabled={!name.trim() || busy} onClick={createRole}>
            {t('space.createBtn')}
          </SpacePrimary>
        }
      >
        <div className="px-4 pb-3">
          <input
            data-testid="space-role-new-name"
            aria-label={t('space.name')}
            placeholder={t('space.name')}
            value={name}
            maxLength={60}
            onChange={(e) => setName(e.target.value)}
            className="w-full h-12 px-4 rounded-control bg-raised border border-line text-body text-ink placeholder:text-ink-3 outline-none focus:border-ink"
          />
        </div>
      </FormSheet>
    </SpaceFrame>
  );
};

/**
 * Редактор однієї ролі (sv-role): люди в ролі, 7 перемикачів прав у секції
 * «Може» (зміни пишуться подією `role.put` одразу) та дія «Подивитися як <Роль>».
 */
export const SpaceRoleEdit: React.FC<{ chatId: string; roleId: string }> = ({ chatId, roleId }) => {
  const { t } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [pickingPeople, setPickingPeople] = useState(false);
  const { space, chat, meId } = v;
  const now = Date.now();
  const role = space?.roles.find((r) => r.id === roleId);
  const members = useMemo(() => (space ? liveMembers(space) : []), [space]);

  if (!space || !role) {
    return (
      <SpaceFrame testId="space-role-edit" onBack={nav.pop}>
        <p className="px-4 py-8 text-center text-callout text-ink-3">{t('space.none')}</p>
      </SpaceFrame>
    );
  }

  const editable = mayEditRole(space, meId, role.id, now);
  const myPerms = permsOf(space, meId, now);
  const roleMembers = members.filter((m) => m.roleIds.includes(role.id));

  const memberName = (id: string): string =>
    id === meId ? t('space.you') : chat?.members?.find((m) => m.id === id)?.name ?? id;

  const togglePerm = async (spec: PermSwitchSpec) => {
    if (!editable) return;
    if (!spec.perms.every((p) => myPerms.has(p))) return;
    const isOn = spec.perms.every((p) => role.perms.includes(p));
    const nextPerms: SpacePerm[] = isOn
      ? role.perms.filter((p) => !spec.perms.includes(p))
      : [...role.perms, ...spec.perms.filter((p) => !role.perms.includes(p))];
    await postSpaceEvent(v.profileId, chatId, {
      t: 'role.put',
      role: { ...role, perms: nextPerms },
    });
    nav.bump();
  };

  const toggleMemberRole = async (memberId: string) => {
    const target = space.members.find((m) => m.id === memberId);
    if (!target) return;
    const has = target.roleIds.includes(role.id);
    const nextRoleIds = has ? target.roleIds.filter((id) => id !== role.id) : [...target.roleIds, role.id];
    if (!mayGiveRoles(space, meId, memberId, nextRoleIds, now)) return;
    await postSpaceEvent(v.profileId, chatId, {
      t: 'member.roles',
      memberId,
      roleIds: nextRoleIds,
    });
    nav.bump();
  };

  const viewAsRole = () => {
    useSpaceNav.setState({ stack: [{ s: 'home', chatId }], previewRoleId: role.id });
  };

  return (
    <SpaceFrame testId="space-role-edit" title={role.name} onBack={nav.pop}>
      <div className="border-b border-line">
        <button
          type="button"
          data-testid="space-role-add-member"
          onClick={() => setPickingPeople(true)}
          className="w-full min-h-[60px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
        >
          <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
            <Users className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </span>
          <span data-testid="space-role-people-count" className="flex-1 text-headline text-ink">
            {t('space.role.people', { n: roleMembers.length })}
          </span>
          <span className="text-subhead text-ink-2">{t('space.role.addPeople')}</span>
          <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
        </button>
      </div>

      <SectionLabel>{t('space.role.can')}</SectionLabel>
      <ul className="divide-y divide-line border-y border-line" data-testid="space-role-perms">
        {PERM_SWITCHES.map((spec) => {
          const on = spec.perms.every((p) => role.perms.includes(p));
          const allowed = editable && spec.perms.every((p) => myPerms.has(p));
          return (
            <li key={spec.key}>
              <button
                type="button"
                role="switch"
                aria-checked={on}
                disabled={!allowed}
                data-testid={`space-perm-toggle-${spec.key}`}
                onClick={() => void togglePerm(spec)}
                className="w-full min-h-[56px] px-4 py-2 flex items-center justify-between gap-3 text-left active:bg-raised disabled:opacity-50"
              >
                <span className="text-headline text-ink">{t(spec.labelKey)}</span>
                <span
                  aria-hidden
                  className={`w-11 h-6 shrink-0 rounded-full p-0.5 transition-colors flex items-center ${
                    on ? 'bg-accent justify-end' : 'bg-overlay justify-start'
                  }`}
                >
                  <span className="w-5 h-5 rounded-full bg-canvas shadow-sm" />
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      <div className="mt-4 border-y border-line">
        <button
          type="button"
          data-testid="space-role-preview"
          onClick={viewAsRole}
          className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
        >
          <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
            <Eye className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </span>
          <span className="flex-1 text-headline text-ink">{t('space.role.viewAs', { role: role.name })}</span>
          <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
        </button>
      </div>

      <FormSheet
        open={pickingPeople}
        onClose={() => setPickingPeople(false)}
        title={role.name}
        testId="space-role-members-sheet"
      >
        <ul className="divide-y divide-line">
          {members.map((m) => {
            const on = m.roleIds.includes(role.id);
            const nextRoleIds = on ? m.roleIds.filter((id) => id !== role.id) : [...m.roleIds, role.id];
            const canAssign = space.members.some((sm) => sm.id === m.id) && mayGiveRoles(space, meId, m.id, nextRoleIds, now);
            const who = memberName(m.id);
            return (
              <li key={m.id}>
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  disabled={!canAssign}
                  data-testid={`space-role-member-toggle-${m.id}`}
                  onClick={() => void toggleMemberRole(m.id)}
                  className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised disabled:opacity-50"
                >
                  <Avatar name={who} tone={m.id} radius="rounded-full" className="w-10 h-10" />
                  <span className="min-w-0 flex-1 truncate text-headline text-ink">{who}</span>
                  <span
                    className={`w-6 h-6 shrink-0 rounded-full border-2 flex items-center justify-center ${
                      on ? 'border-ink bg-ink text-canvas' : 'border-line'
                    }`}
                  >
                    {on && <Check className="w-4 h-4" strokeWidth={2.5} aria-hidden />}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </FormSheet>
    </SpaceFrame>
  );
};
