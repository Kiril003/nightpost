import { beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { ProfileRemoveConfirm } from '../components/messenger/ProfileRemoveConfirm';
import { loadProfiles, useProfiles, PROFILES_KEY } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';

const pickTime = (id: string, hh: string, mm?: string) => {
  fireEvent.click(screen.getByTestId(id));
  const pick = screen.getByTestId(`${id.replace(/-(from|to)$/, '')}-sheet`);
  fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Години' })).getByRole('option', { name: hh }));
  if (mm) fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: mm }));
  fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
};

const click = (id: string) => fireEvent.click(screen.getByTestId(id));

function openBusinessScreen() {
  render(<ProfilesHost />);
  click('me-profiles-open');
  click('profile-add-open');
  click('profile-template-business');
  click('profile-template-create');
  const b = useProfiles.getState().profiles.find((p) => p.kind === 'business')!;
  click('profile-look-back');
  click(`profile-settings-${b.id}`);
  click('profile-business');
  return b;
}

function loadWith(profile: Record<string, unknown>) {
  localStorage.setItem(
    PROFILES_KEY,
    JSON.stringify({ v: 1, activeId: 'b1', profiles: [{ id: 'b1', name: 'Б', kind: 'business', createdAt: 1, ...profile }] }),
  );
  return loadProfiles(false).profiles[0];
}

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
});

describe('profiles-fix: business card is stable across reload', () => {
  it('keeps a priced service with an empty name and drops invalid hours', () => {
    const p = loadWith({
      biz: { services: [{ id: 's1', name: '', price: '50' }, { id: 's2', name: '', price: '' }], hours: { from: '', to: '18:00' } },
    });
    expect(p.biz.services.map((s) => s.id)).toEqual(['s1']);
    expect(p.biz.hours).toBeNull();
    expect(loadWith({ biz: { hours: { from: '09:00', to: '18:30' } } }).biz.hours).toEqual({ from: '09:00', to: '18:30' });
  });

  it('inputs cap length at the same limits the loader uses', () => {
    openBusinessScreen();
    const max = (id: string) => screen.getByTestId(id).getAttribute('maxlength');
    expect([max('biz-category'), max('biz-address'), max('biz-phone'), max('biz-email')]).toEqual(['40', '120', '32', '80']);
  });

  it('business hours open the shared when-wheels, not a system time field', () => {
    const b = openBusinessScreen();
    click('biz-hours-toggle');
    expect(document.querySelector('input[type="time"]')).toBeNull();
    pickTime('biz-hours-from', '08', '30');
    pickTime('biz-hours-to', '20');
    expect(useProfiles.getState().profiles.find((p) => p.id === b.id)!.biz.hours).toEqual({ from: '08:30', to: '20:00' });
    expect(screen.getByTestId('biz-hours-toggle').textContent).toContain('08:30–20:00');
  });

  it('«Додати послугу» starts with an empty name and a placeholder', () => {
    const b = openBusinessScreen();
    click('biz-svc-add');
    const svc = useProfiles.getState().profiles.find((p) => p.id === b.id)!.biz.services;
    expect(svc).toHaveLength(1);
    expect(svc[0].name).toBe('');
    const input = screen.getByTestId(`biz-svc-${svc[0].id}`).querySelector('input')!;
    expect(input.getAttribute('placeholder')).toBe('Послуга');
  });
});

describe('profiles-fix: one services list, honest labels', () => {
  it('has a single «Послуги» section and the card editor holds no services', () => {
    openBusinessScreen();
    expect(screen.getAllByText('Послуги')).toHaveLength(1);
    expect(within(screen.getByTestId('business-services')).getByTestId('biz-svc-add')).toBeInTheDocument();
    expect(within(screen.getByTestId('business-card')).queryByTestId('biz-svc-add')).toBeNull();
  });

  it('names the preview and the local staff honestly', () => {
    const b = openBusinessScreen();
    expect(within(screen.getByTestId('business-view')).getByText('Перегляд картки')).toBeInTheDocument();
    expect(screen.getByTestId('staff-scope').textContent).toBe('Працівник бачить лише доручені розмови');
    expect(within(screen.getByTestId('business-team')).queryByText('Лише на цьому пристрої')).toBeNull();
    expect(within(screen.getByTestId('business-team')).queryByText('Власник')).toBeNull();
    fireEvent.change(screen.getByTestId('staff-handle'), { target: { value: 'olena_k' } });
    click('staff-invite');
    expect(screen.getByTestId('staff-revoke-olena_k').getAttribute('aria-label')).toBe('Прибрати');
    expect(useProfiles.getState().profiles.find((p) => p.id === b.id)!.staff).toHaveLength(1);
  });

  it('staff handle follows the directory rule: no dots', () => {
    const b = openBusinessScreen();
    expect(useProfiles.getState().addStaff(b.id, 'a.b.c', 'staff')).toBe('bad');
    expect(useProfiles.getState().addStaff(b.id, 'abc_1', 'staff')).toBe('ok');
  });
});

describe('profiles-fix: remove copy', () => {
  it('removing Personal does not say chats stay in Personal', () => {
    const s = useProfiles.getState();
    s.addFromTemplate('work');
    const personal = useProfiles.getState().profiles.find((p) => p.kind === 'personal')!;
    render(<ProfileRemoveConfirm profile={personal} onCancel={() => {}} onDone={() => {}} />);
    expect(screen.getByTestId('profile-remove-keep').textContent).not.toContain('«Особисте»');
  });

  it('orphan line points to the «Інші розмови» folder', () => {
    const w = useProfiles.getState().addFromTemplate('work');
    useMessengerStore.setState({
      chats: [{ id: 'c1', type: 'direct', circle: 'work', circles: ['work'], pinned: false, archived: false, messages: [] }] as never,
    });
    const rest = useProfiles.getState().profiles.filter((p) => p.kind !== 'personal');
    useProfiles.setState({ profiles: rest, activeId: w.id });
    render(<ProfileRemoveConfirm profile={w} onCancel={() => {}} onDone={() => {}} />);
    expect(screen.getByTestId('profile-remove-orphan').textContent).toContain('Інші розмови');
  });
});

describe('profiles-fix: one fallback rule', () => {
  it('after an active temp profile expires, Personal wins over the first', () => {
    const now = Date.now();
    const w = useProfiles.getState().addFromTemplate('work');
    const t = useProfiles.getState().addFromTemplate('temp');
    const personal = useProfiles.getState().profiles.find((p) => p.kind === 'personal')!;
    useProfiles.setState({
      profiles: [w, t, personal].map((p) => (p.id === t.id ? { ...p, expiresAt: now - 1 } : p)),
      activeId: t.id,
    });
    useProfiles.getState().sweepExpired(now);
    expect(useProfiles.getState().activeId).toBe(personal.id);
  });

  it('a second Personal cannot be created', () => {
    const before = useProfiles.getState().profiles.filter((p) => p.kind === 'personal').length;
    useProfiles.getState().addFromTemplate('personal');
    expect(useProfiles.getState().profiles.filter((p) => p.kind === 'personal')).toHaveLength(before);
  });
});

describe('profiles-fix: створення профілю підтверджується', () => {
  it('після «Створити» людина бачить плашку з назвою нового профілю', async () => {
    const { useUIStore } = await import('../stores/uiStore');
    useUIStore.setState({ toasts: [] });
    render(<ProfilesHost />);
    click('me-profiles-open');
    click('profile-add-open');
    click('profile-template-business');
    click('profile-template-create');
    const b = useProfiles.getState().profiles.find((p) => p.kind === 'business')!;
    const msgs = useUIStore.getState().toasts.map((x) => x.message);
    expect(msgs.some((m) => m.includes(b.name))).toBe(true);
  });
});
