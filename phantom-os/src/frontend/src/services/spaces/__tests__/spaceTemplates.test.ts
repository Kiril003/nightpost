import { describe, it, expect } from 'vitest';
import type { SpaceEvent } from '@shared/types/space';
import { foldSpace } from '../spaceLog';
import { can } from '../permissions';
import { seedEvents, SEED_TEMPLATES } from '../spaceTemplates';

const T0 = Date.UTC(2026, 9, 25, 9, 0);
const names = (k: string) => k;

const fold = (template: Parameters<typeof seedEvents>[0], people: string[] = ['ihor']) => {
  let n = 0;
  const bodies = seedEvents(template, 'Vertical', people, names, () => `id${(n += 1)}`);
  const events: SpaceEvent[] = bodies.map((body, i) => ({ id: `e${i}`, spaceId: 'sp1', actor: 'me', lamport: i + 1, at: T0, body }));
  const r = foldSpace(events);
  if (!r.space) throw new Error('no space');
  return { space: r.space, refused: r.refused };
};

describe('шаблон простору: події, які приймає журнал', () => {
  it('кожен шаблон складається без жодної відмови', () => {
    for (const t of SEED_TEMPLATES) expect(fold(t).refused).toEqual([]);
  });

  it('клуб: оголошення й загальна, люди з роллю за замовчуванням', () => {
    const { space } = fold('club', ['ihor', 'yana']);
    expect(space.name).toBe('Vertical');
    expect(space.channels.map((c) => c.type)).toEqual(['announce', 'chat']);
    expect(space.defaultRoleId).not.toBeNull();
    expect(space.members.filter((m) => m.id !== 'me').map((m) => [m.id, m.roleIds])).toEqual([
      ['ihor', [space.defaultRoleId]],
      ['yana', [space.defaultRoleId]],
    ]);
  });

  it('в оголошеннях пише власник, учасник лише читає; в загальній пишуть усі', () => {
    const { space } = fold('club');
    const [announce, chat] = space.channels;
    expect(can(space, 'me', 'write', T0, announce.id)).toBe(true);
    expect(can(space, 'ihor', 'write', T0, announce.id)).toBe(false);
    expect(can(space, 'ihor', 'read', T0, announce.id)).toBe(true);
    expect(can(space, 'ihor', 'write', T0, chat.id)).toBe(true);
  });

  it('навчання й сусіди мають форум; з нуля — одна загальна', () => {
    expect(fold('course').space.channels.some((c) => c.type === 'forum')).toBe(true);
    expect(fold('home').space.channels.some((c) => c.type === 'forum')).toBe(true);
    expect(fold('blank').space.channels.map((c) => c.type)).toEqual(['chat']);
  });

  it('голосових кімнат шаблон не створює, доки немає дзвінків', () => {
    for (const t of SEED_TEMPLATES) expect(fold(t).space.channels.some((c) => c.type === 'voice' || c.type === 'stage')).toBe(false);
  });
});
