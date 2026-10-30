import type { ChannelType, SpaceEventBody, SpacePerm, SpaceTemplate } from '@shared/types/space';
import type { MessageKey } from '../../i18n/messages/uk';

/**
 * Шаблони екрана «Новий простір» (рендер sv-create) — порядок і склад.
 * Голосових кімнат шаблон не дає, доки немає дзвінків у кімнатах (G1).
 */
export const SEED_TEMPLATES = ['club', 'course', 'team', 'home', 'blank'] as const satisfies readonly SpaceTemplate[];
export type SeedTemplate = (typeof SEED_TEMPLATES)[number];

type Room = [cat: 'info' | 'talk', type: ChannelType, nameKey: MessageKey];

export const SEED_ROOMS: Record<SeedTemplate, Room[]> = {
  club: [['info', 'announce', 'space.ch.announce'], ['talk', 'chat', 'space.ch.general']],
  course: [['info', 'announce', 'space.ch.announce'], ['talk', 'forum', 'space.ch.questions'], ['talk', 'chat', 'space.ch.general']],
  team: [['info', 'announce', 'space.ch.announce'], ['talk', 'chat', 'space.ch.general'], ['talk', 'forum', 'space.ch.talk']],
  home: [['info', 'announce', 'space.ch.announce'], ['talk', 'chat', 'space.ch.general'], ['talk', 'forum', 'space.ch.help']],
  blank: [['talk', 'chat', 'space.ch.general']],
};

const MEMBER_PERMS: SpacePerm[] = ['read', 'write', 'media', 'links', 'thread.create'];

/** Події нового простору: власник — той, хто їх підпише; `people` входять з роллю «Учасник». */
export function seedEvents(
  template: SeedTemplate,
  name: string,
  people: readonly string[],
  t: (key: MessageKey) => string,
  mkId: () => string,
): SpaceEventBody[] {
  const rooms = SEED_ROOMS[template];
  const roleId = mkId();
  const cats = { info: mkId(), talk: mkId() };
  const used = (['info', 'talk'] as const).filter((c) => rooms.some((r) => r[0] === c));
  return [
    { t: 'space.create', name, icon: null, template },
    { t: 'role.put', role: { id: roleId, name: t('space.role.member'), color: 0, rank: 10, perms: MEMBER_PERMS, until: null } },
    { t: 'space.edit', defaultRoleId: roleId },
    ...used.map((c, order): SpaceEventBody => ({ t: 'category.put', category: { id: cats[c], name: t(`space.cat.${c}`), order, lockedTo: [] } })),
    ...rooms.map(([c, type, key], order): SpaceEventBody => ({
      t: 'channel.put',
      channel: { id: mkId(), categoryId: cats[c], type, name: t(key), order, overrides: [] },
    })),
    ...people.map((memberId): SpaceEventBody => ({ t: 'member.add', memberId, roleIds: [roleId] })),
  ];
}
