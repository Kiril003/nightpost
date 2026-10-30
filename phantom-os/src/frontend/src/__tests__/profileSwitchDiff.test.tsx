import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useUIStore } from '../stores/uiStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

beforeEach(() => {
  localStorage.clear();
  document.documentElement.lang = 'uk';
  useSettingsStore.setState((s) => ({ ...s, values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useProfiles.setState(loadProfiles());
  useUIStore.setState({ toasts: [] });
});

afterEach(() => {
  cleanup();
  localStorage.clear();
});

const openSheet = () => {
  render(<ProfilesHost />);
  fireEvent.click(screen.getByTestId('me-profiles-open'));
};

describe('лист перемикання показує, що зміниться', () => {
  it('Особисте → Школа: розгорнута картка з рядками, кнопка окремо', () => {
    const personal = useProfiles.getState().profiles[0];
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    useProfiles.getState().setActive(personal.id);
    openSheet();

    fireEvent.click(screen.getByTestId(`profile-select-${school.id}`));
    expect(screen.getByTestId('profile-switch-diff').querySelectorAll('[data-testid^="kit-row-"]')).toHaveLength(0);
    const rows = [...screen.getByTestId(`profile-rows-${school.id}`).querySelectorAll('[data-testid^="kit-row-"]')].map((e) =>
      e.getAttribute('data-testid'),
    );
    expect(rows).toEqual(['kit-row-tabs', 'kit-row-notify', 'kit-row-wallet', 'kit-row-face']);
    expect(screen.getByTestId('kit-row-notify').textContent).toContain('08:00–16:00, далі тиша');
    const card = (id: string) => screen.getByTestId(`profile-select-${id}`).className;
    expect(card(school.id)).toContain('border-accent-line');
    expect(card(personal.id)).not.toContain('border-accent-line');
    expect(screen.getByTestId('profile-switch-go').textContent).toBe('Перейти в «Школа»');
    expect(useProfiles.getState().activeId).toBe(personal.id);

    fireEvent.click(screen.getByTestId('profile-switch-go'));
    expect(useProfiles.getState().activeId).toBe(school.id);
    expect(useUIStore.getState().toasts.map((t) => t.message)).toEqual(['«Школа»: тиша 16:00–08:00']);
    expect(screen.queryByTestId('profile-switch-diff')).toBeNull();
  });

  it('Школа → Особисте: тиша знімається — рядок «Без тиші»', () => {
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    useProfiles.getState().setActive(school.id);
    openSheet();

    const personal = useProfiles.getState().profiles[0];
    fireEvent.click(screen.getByTestId(`profile-select-${personal.id}`));
    expect(screen.getByTestId('kit-row-notify').textContent).toContain('Без тиші');
  });

  it('Особисте → Сімʼя: та сама смужка вкладок, без закреслених', () => {
    const personal = useProfiles.getState().profiles[0];
    const family = useProfiles.getState().addFromTemplate('family', 'Дім');
    useProfiles.getState().setActive(personal.id);
    openSheet();

    fireEvent.click(screen.getByTestId(`profile-select-${family.id}`));
    expect(screen.getByTestId('profile-switch-go').textContent).toBe('Перейти в «Дім»');
  });

  it('тап по поточному профілю нічого не перемикає', () => {
    const personal = useProfiles.getState().profiles[0];
    openSheet();
    fireEvent.click(screen.getByTestId(`profile-select-${personal.id}`));
    expect(screen.queryByTestId('profile-switch-diff')).toBeNull();
  });

  it('інше обличчя — окремим рядком; рядка «Помічник» немає (S7: перемикач нічого не робив)', () => {
    const personal = useProfiles.getState().profiles[0];
    const work = useProfiles.getState().addFromTemplate('work', 'Офіс');
    useProfiles.getState().update(work.id, { assistantReads: false, faces: { work: { name: 'Олена К.' } } });
    useProfiles.getState().setActive(personal.id);
    openSheet();

    fireEvent.click(screen.getByTestId(`profile-select-${work.id}`));
    expect(screen.queryByTestId('kit-row-assistant')).toBeNull();
    expect(screen.getByTestId('kit-row-face').textContent).toContain('Олена К.');
  });

  it('Робота → Школа: інші години тиші — рядок сповіщень є', () => {
    const work = useProfiles.getState().addFromTemplate('work', 'Офіс');
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    useProfiles.getState().setActive(work.id);
    openSheet();

    fireEvent.click(screen.getByTestId(`profile-select-${school.id}`));
    expect(screen.getByTestId('kit-row-notify').textContent).toContain('08:00–16:00, далі тиша');
  });

  it('обличчя береться з кола профілю, а не з його роду', () => {
    const personal = useProfiles.getState().profiles[0];
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    useProfiles.getState().update(school.id, { faces: { work: { name: 'Марта Ігорівна' } } });
    useProfiles.getState().setActive(personal.id);
    openSheet();

    fireEvent.click(screen.getByTestId(`profile-select-${school.id}`));
    expect(screen.getByTestId('kit-row-face').textContent).toContain('Марта Ігорівна');
  });
});
