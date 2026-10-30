import { beforeEach, describe, expect, it } from 'vitest';
import { applyBootstrapLanguage } from '../services/settingsBootstrap';
import { buildBridgePayload, loadProfiles, PROFILES_KEY, useProfiles } from '../stores/profilesStore';
import { cleanKit, kitDiff, kitForKind } from '../services/profileKit';

describe('набір профілю (11-spaces §7)', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.lang = 'uk';
    applyBootstrapLanguage();
    useProfiles.setState(loadProfiles());
  });

  it('новий робочий профіль має вкладку «Задачі» і швидку дію «Задача»', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    expect(work.kit.tabs).toContain('tasks');
    expect(work.kit.quick).toContain('task');
    expect(work.kit.wallet).toBe('work');
    const school = useProfiles.getState().addFromTemplate('study');
    expect(school.kit.tabs).toEqual(['classes', 'schedule']);
    expect(school.kit.wallet).toBeNull();
  });

  it('старий профіль без набору отримує набір свого роду', () => {
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({ v: 1, activeId: 'w', profiles: [{ id: 'w', name: 'Робота', kind: 'work' }] }),
    );
    const { profiles } = loadProfiles();
    expect(profiles[0].kit).toEqual(kitForKind('work'));
  });

  it('невідома вкладка й дія відкидаються, решта лишається', () => {
    const kit = cleanKit({ tabs: ['tasks', 'casino', 'tasks', 'map', 'day', 'wallet'], quick: ['task', 'x'], notify: 'loud', wallet: null }, 'work');
    expect(kit.tabs).toEqual(['tasks', 'map', 'day']);
    expect(kit.quick).toEqual(['task']);
    expect(kit.notify).toBe('hours');
    expect(kit.wallet).toBeNull();
  });

  it('міст несе набір, а версію лишає ту, яку знає телефон', () => {
    const state = useProfiles.getState();
    const payload = buildBridgePayload(state);
    expect(payload.v).toBe(2);
    expect(payload.profiles[0].kit).toEqual(state.profiles[0].kit);
  });

  it('міст несе вигляд профілю: акцент і знак — для «Я» на телефоні', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    const payload = buildBridgePayload(useProfiles.getState());
    const row = payload.profiles.find((p) => p.id === work.id)!;
    expect(row.accent).toBe('sea');
    expect(row.glyph).toBe('work');
  });

  it('різниця Особисте → Школа: закреслені й нові вкладки, без рядка для незмінного', () => {
    const rows = kitDiff(kitForKind('personal'), kitForKind('study'));
    expect(rows.find((r) => r.field === 'tabs')).toEqual({
      field: 'tabs',
      gone: ['map', 'day', 'wallet'],
      added: ['classes', 'schedule'],
    });
    expect(kitDiff(kitForKind('work'), kitForKind('work'))).toEqual([]);
    expect(kitDiff(kitForKind('personal'), kitForKind('family'))).toEqual([]);
  });

  it('бізнес тримає чотири вкладки, решта — три', () => {
    const four = { tabs: ['inbox', 'orders', 'bookings', 'shifts'] };
    expect(cleanKit(four, 'business').tabs).toEqual(['inbox', 'orders', 'bookings', 'shifts']);
    expect(cleanKit(four, 'personal').tabs).toEqual(['inbox', 'orders', 'bookings']);
  });
});
