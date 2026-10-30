import { beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { loadProfiles, useProfiles, PROFILES_KEY } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { removalImpact } from '../services/profileImpact';
import { useChatProfiles } from '../stores/chatProfileStore';

const click = (id: string) => fireEvent.click(screen.getByTestId(id));

function openTemplates() {
  render(<ProfilesHost />);
  click('me-profiles-open');
  click('profile-add-open');
}

function makeProfile(kind: 'business' | 'work' | 'unnamed') {
  openTemplates();
  click(`profile-template-${kind}`);
  click('profile-template-create');
  return useProfiles.getState().profiles.find((p) => p.kind === kind)!;
}

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
});

describe('profiles-r: templates with preview', () => {
  it('lists the seven owner templates first and creates nothing before «Створити»', () => {
    openTemplates();
    const kinds = Array.from(
      screen.getByTestId('profile-templates').querySelectorAll<HTMLElement>('[data-testid^="profile-template-"]'),
    ).map((b) => b.dataset.testid!.replace('profile-template-', ''));
    expect(kinds.slice(0, 8)).toEqual(['personal', 'work', 'business', 'study', 'unnamed', 'family', 'trip', 'temp']);

    const before = useProfiles.getState().profiles.length;
    click('profile-template-unnamed');
    expect(screen.getByTestId('profile-template-preview')).toHaveAttribute('data-kind', 'unnamed');
    for (const f of ['look', 'circles', 'notify']) expect(screen.getByTestId(`tpl-row-${f}`)).toBeInTheDocument();
    expect(screen.getByTestId('tpl-row-circles').textContent).toContain('Нікого');
    expect(screen.queryByTestId('tpl-row-seen')).toBeNull();
    expect(useProfiles.getState().profiles.length).toBe(before);

    click('profile-template-preview-back');
    expect(screen.getByTestId('profile-templates')).toBeInTheDocument();
    expect(useProfiles.getState().profiles.length).toBe(before);
  });

  it('creates with the typed name and the template settings', () => {
    openTemplates();
    click('profile-template-family');
    fireEvent.change(screen.getByTestId('profile-template-name'), { target: { value: 'Родина' } });
    click('profile-template-create');
    const s = useProfiles.getState();
    const p = s.profiles.find((x) => x.id === s.activeId)!;
    expect(p).toMatchObject({ kind: 'family', name: 'Родина' });
    expect(p.passGroups).toEqual(['personal', 'close', 'friends']);
  });
});

describe('profiles-r: per-profile settings', () => {
  it('shows no find-me or notify switches that nothing enforces', () => {
    const w = makeProfile('work');
    click('profile-look-back');
    click(`profile-settings-${w.id}`);
    expect(screen.queryByTestId('profile-find-nobody')).toBeNull();
    expect(screen.queryByTestId('profile-notify-oncall')).toBeNull();
  });

  it('old stored profiles without the new fields load with defaults', () => {
    const id = 'old1';
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({ v: 1, activeId: id, profiles: [{ id, name: 'Старий', kind: 'business', createdAt: 1 }] }),
    );
    const p = loadProfiles(false).profiles[0];
    expect('findBy' in p).toBe(false);
    expect(p.staff).toEqual([]);
    expect(p.biz.services).toEqual([]);
  });
});

describe('profiles-r: business card and staff', () => {
  function openBusinessScreen() {
    const b = makeProfile('business');
    click('profile-look-back');
    click(`profile-settings-${b.id}`);
    click('profile-business');
    return b;
  }

  it('edits the card locally and shows exactly what others see', () => {
    const b = openBusinessScreen();
    expect(screen.getByTestId('business-public-view').textContent).toContain('Поки порожньо');
    fireEvent.change(screen.getByTestId('biz-category'), { target: { value: 'Кав’ярня' } });
    fireEvent.change(screen.getByTestId('biz-address'), { target: { value: 'вул. Тиха, 1' } });
    fireEvent.change(screen.getByTestId('biz-phone'), { target: { value: '+380000000' } });
    click('biz-hours-toggle');
    click('biz-svc-add');
    const view = screen.getByTestId('business-public-view').textContent!;
    expect(view).toContain('Кав’ярня');
    expect(view).toContain('вул. Тиха, 1');
    expect(view).toContain('09:00–18:00');
    expect(screen.getByTestId('biz-device-only')).toBeInTheDocument();
    const stored = useProfiles.getState().profiles.find((p) => p.id === b.id)!;
    expect(stored.biz.services).toHaveLength(1);
    click(`biz-svc-remove-${stored.biz.services[0].id}`);
    expect(useProfiles.getState().profiles.find((p) => p.id === b.id)!.biz.services).toHaveLength(0);
  });

  it('invites staff by handle, rejects bad and duplicate handles, changes role, revokes', () => {
    const b = openBusinessScreen();
    expect(screen.getByTestId('staff-scope')).toBeInTheDocument();
    const staff = () => useProfiles.getState().profiles.find((p) => p.id === b.id)!.staff;

    fireEvent.change(screen.getByTestId('staff-handle'), { target: { value: 'x' } });
    click('staff-invite');
    expect(screen.getByTestId('staff-error')).toBeInTheDocument();
    expect(staff()).toEqual([]);

    fireEvent.change(screen.getByTestId('staff-handle'), { target: { value: '@Olena_K' } });
    click('staff-invite');
    expect(staff()).toMatchObject([{ handle: 'olena_k', role: 'staff' }]);

    fireEvent.change(screen.getByTestId('staff-handle'), { target: { value: 'olena_k' } });
    click('staff-invite');
    expect(staff()).toHaveLength(1);
    expect(screen.getByTestId('staff-error')).toBeInTheDocument();

    click('staff-role-olena_k');
    expect(staff()[0].role).toBe('admin');
    click('staff-revoke-olena_k');
    expect(staff()).toEqual([]);
  });
});

describe('profiles-r: close a profile', () => {
  it('asks first, says what happens to chats, and removes only on confirm', () => {
    const w = makeProfile('work');
    click('profile-look-back');
    click(`profile-settings-${w.id}`);
    click(`profile-remove-${w.id}`);
    expect(screen.getByTestId('profile-remove-confirm')).toBeInTheDocument();
    expect(useProfiles.getState().profiles.some((p) => p.id === w.id)).toBe(true);

    click('profile-remove-cancel');
    expect(useProfiles.getState().profiles.some((p) => p.id === w.id)).toBe(true);

    click(`profile-remove-${w.id}`);
    click('profile-remove-yes');
    const s = useProfiles.getState();
    expect(s.profiles.some((p) => p.id === w.id)).toBe(false);
    expect(s.profiles.find((p) => p.id === s.activeId)!.kind).toBe('personal');
  });

  it('counts chats that would lose every profile', () => {
    const w = makeProfile('work');
    const profiles = useProfiles.getState().profiles.filter((p) => p.kind !== 'personal');
    const chat = { id: 'c1', type: 'direct', circle: 'work', circles: ['work'], pinned: false, archived: false } as never;
    expect(removalImpact([chat], profiles, w.id)).toEqual({ inside: 1, orphaned: 1 });
    expect(removalImpact([chat], useProfiles.getState().profiles, w.id)).toEqual({ inside: 0, orphaned: 0 });
    useChatProfiles.getState().assign('c1', w.id);
    expect(removalImpact([chat], useProfiles.getState().profiles, w.id)).toEqual({ inside: 1, orphaned: 0 });
    expect(useMessengerStore.getState()).toBeDefined();
  });
});
