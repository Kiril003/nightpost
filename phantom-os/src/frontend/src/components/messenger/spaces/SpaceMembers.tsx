import React, { useMemo, useState } from 'react';
import { ChevronRight, Shield, UserPlus } from 'lucide-react';
import type { SpaceMember, SpaceRole } from '@shared/types/space';
import { useTranslation } from '../../../i18n/useTranslation';
import { people as memberWord } from '../GroupProfileSheet';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { activeRoles } from '../../../services/spaces/permissions';
import { liveMembers, myRole, roomsOf } from '../../../services/spaces/spaceRooms';
import { postSpaceEvent } from '../../../services/spaces/spaceWire';
import type { SpacePerson } from '../../../services/spaces/createSpace';
import { Avatar } from '../kit/Avatar';
import { SectionLabel, SpaceFrame } from './SpaceFrame';
import { PeoplePickSheet } from './PeoplePickSheet';
import { useSpace } from './useSpace';

/**
 * Учасники простору (sv-members): вкладки Розмови · Учасники, чіпи фільтру за
 * ролями, рядки «Запросити» й «Ролі й дозволи», список людей за ролями.
 */
export const SpaceMembers: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t, locale } = useTranslation();
  const v = useSpace(chatId);
  const nav = useSpaceNav();
  const [roleFilter, setRoleFilter] = useState<string>('all');
  const [inviting, setInviting] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const { space, chat, meId } = v;
  const now = Date.now();
  const members = useMemo(() => (space ? liveMembers(space) : []), [space]);
  const roles = useMemo(
    () => (space ? [...space.roles].sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id)) : []),
    [space],
  );
  const roomCount = useMemo(
    () => (space ? roomsOf(space, meId, now).reduce((n, g) => n + g.rooms.length, 0) : 0),
    [space, meId], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const memberName = (id: string): string =>
    id === meId ? t('space.you') : chat?.members?.find((m) => m.id === id)?.name ?? id;

  const memberRoleLabel = (m: SpaceMember): string | null => {
    if (!space) return null;
    if (m.id === space.ownerId) return t('space.owner');
    return myRole(space, m.id, now)?.name ?? null;
  };

  const countInRole = (role: SpaceRole): number =>
    space ? members.filter((m) => activeRoles(space, m.id, now).some((r) => r.id === role.id)).length : 0;

  const sections = useMemo(() => {
    if (!space) return [];
    if (roleFilter !== 'all') {
      const r = roles.find((x) => x.id === roleFilter);
      if (!r) return [];
      const list = members.filter((m) => activeRoles(space, m.id, now).some((ar) => ar.id === r.id));
      return [{ id: r.id, label: `${r.name} · ${list.length}`, members: list }];
    }
    const grouped: { id: string; label: string; members: SpaceMember[] }[] = [];
    const placed = new Set<string>();
    for (const r of roles) {
      const list = members.filter((m) => !placed.has(m.id) && activeRoles(space, m.id, now).some((ar) => ar.id === r.id));
      if (list.length > 0) {
        list.forEach((m) => placed.add(m.id));
        grouped.push({ id: r.id, label: `${r.name} · ${list.length}`, members: list });
      }
    }
    const rest = members.filter((m) => !placed.has(m.id));
    if (rest.length > 0) {
      grouped.push({ id: '__other', label: `${t('space.tab.members')} · ${rest.length}`, members: rest });
    }
    return grouped;
  }, [space, roles, members, roleFilter, t]); // eslint-disable-line react-hooks/exhaustive-deps

  const invite = async (people: SpacePerson[]) => {
    setInviting(false);
    if (!space) return;
    setNote(null);
    try {
      for (const p of people) {
        await useMessengerStore.getState().addGroupMember(chatId, p.id);
        await postSpaceEvent(v.profileId, chatId, {
          t: 'member.add',
          memberId: p.nodeId,
          roleIds: space.defaultRoleId ? [space.defaultRoleId] : [],
        });
      }
      nav.bump();
    } catch (e) {
      setNote(e instanceof Error && e.message ? e.message : t('space.inviteFailed'));
    }
  };

  const name = space?.name ?? chat?.title ?? '';
  return (
    <SpaceFrame
      testId="space-members"
      title={name}
      subtitle={`${t('space.kind')} · ${members.length} ${memberWord(members.length, t, locale)}`}
      lead={<Avatar name={name} tone={chatId} radius="rounded-control" className="w-9 h-9" />}
      onBack={nav.pop}
    >
      <div role="tablist" className="px-4 border-b border-line flex items-center gap-6">
        <button
          type="button"
          role="tab"
          aria-selected={false}
          data-testid="space-tab-rooms"
          onClick={() => nav.swap({ s: 'home', chatId })}
          className="min-h-0 py-2.5 border-b-2 border-transparent text-subhead text-ink-2 inline-flex items-center gap-1.5"
        >
          <span>{t('space.tab.rooms')}</span>
          <span className="text-ink-3 tnum">{roomCount}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={true}
          data-testid="space-tab-members"
          className="min-h-0 py-2.5 border-b-2 border-ink text-subhead font-semibold text-ink inline-flex items-center gap-1.5"
        >
          <span>{t('space.tab.members')}</span>
          <span className="text-ink-2 font-normal tnum">{members.length}</span>
        </button>
      </div>

      {space && (
        <>
          <div role="tablist" className="px-4 pt-3 pb-1 flex items-center gap-2 overflow-x-auto">
            <button
              type="button"
              role="tab"
              aria-selected={roleFilter === 'all'}
              data-testid="space-members-filter-all"
              onClick={() => setRoleFilter('all')}
              className={`min-h-11 px-3.5 shrink-0 rounded-full text-subhead transition-colors ${
                roleFilter === 'all' ? 'bg-overlay text-ink font-medium' : 'border border-line text-ink-2'
              }`}
            >
              {t('space.forum.all')} {members.length}
            </button>
            {roles.map((r) => {
              const on = roleFilter === r.id;
              return (
                <button
                  key={r.id}
                  type="button"
                  role="tab"
                  aria-selected={on}
                  data-testid={`space-members-filter-${r.id}`}
                  onClick={() => setRoleFilter(r.id)}
                  className={`min-h-11 px-3.5 shrink-0 rounded-full text-subhead transition-colors ${
                    on ? 'bg-overlay text-ink font-medium' : 'border border-line text-ink-2'
                  }`}
                >
                  {r.name} {countInRole(r)}
                </button>
              );
            })}
          </div>

          <div className="mt-2 border-y border-line divide-y divide-line">
            <button
              type="button"
              data-testid="space-members-invite"
              onClick={() => setInviting(true)}
              className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
            >
              <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
                <UserPlus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
              </span>
              <span className="flex-1 text-headline text-ink">{t('space.invite')}</span>
            </button>
            <button
              type="button"
              data-testid="space-members-roles"
              onClick={() => nav.push({ s: 'roles', chatId })}
              className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
            >
              <span className="w-10 h-10 shrink-0 rounded-control bg-overlay text-ink-2 flex items-center justify-center">
                <Shield className="w-5 h-5" strokeWidth={1.75} aria-hidden />
              </span>
              <span className="flex-1 text-headline text-ink">{t('space.rolesAndPerms')}</span>
              <span data-testid="space-members-roles-count" className="text-subhead text-ink-2 tnum">
                {roles.length}
              </span>
              <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
            </button>
          </div>

          {note && <p className="px-4 pt-2 text-footnote text-danger">{note}</p>}

          <div className="pb-6" data-testid="space-members-list">
            {sections.map((sec) => (
              <section key={sec.id}>
                <SectionLabel>{sec.label}</SectionLabel>
                <ul className="divide-y divide-line border-y border-line">
                  {sec.members.map((m) => {
                    const who = memberName(m.id);
                    const badge = memberRoleLabel(m);
                    return (
                      <li
                        key={m.id}
                        data-testid={`space-member-${m.id}`}
                        className="min-h-[56px] px-4 py-2 flex items-center gap-3"
                      >
                        <Avatar name={who} tone={m.id} radius="rounded-full" className="w-10 h-10" />
                        <span className="min-w-0 flex-1 truncate text-headline text-ink">{who}</span>
                        {badge && (
                          <span
                            data-testid={`space-member-role-${m.id}`}
                            className="px-2.5 py-0.5 rounded-full bg-overlay text-caption text-ink-2 shrink-0"
                          >
                            {badge}
                          </span>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))}
          </div>
        </>
      )}

      <PeoplePickSheet
        open={inviting}
        picked={[]}
        exclude={members.map((m) => m.id)}
        onClose={() => setInviting(false)}
        onDone={invite}
      />
    </SpaceFrame>
  );
};
