import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import {
  DEFAULT_LOOK,
  applyLookToRoot,
  effectiveLook,
  readLook,
  startLookWatch,
  useLook,
} from '../services/appearance';
import {
  NEW_PROFILE_KINDS,
  HIDDEN_PROFILE_KINDS,
  PROFILE_KINDS,
  loadProfiles,
  templateDefaults,
  useProfiles,
  type Profile,
} from '../stores/profilesStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { ProfileLookEditor } from '../components/messenger/ProfileLookEditor';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import type { Chat, UserProfile } from '../types/messenger';

const pickTime = (id: string, hh: string, mm?: string) => {
  fireEvent.click(screen.getByTestId(id));
  const pick = screen.getByTestId(`${id.replace(/-(from|to)$/, '')}-sheet`);
  fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Години' })).getByRole('option', { name: hh }));
  if (mm) fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: mm }));
  fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

let stopWatch: (() => void) | undefined;

beforeEach(() => {
  localStorage.clear();
  document.documentElement.lang = 'uk';
  document.documentElement.dataset.theme = 'night';
  useProfiles.setState(loadProfiles());
  useLook.setState({ look: DEFAULT_LOOK });
  stopWatch = startLookWatch();
});

afterEach(() => {
  stopWatch?.();
  stopWatch = undefined;
  vi.useRealTimers();
  vi.restoreAllMocks();
  cleanup();
  localStorage.clear();
  useSettingsStore.setState((s) => ({
    ...s,
    values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' },
  }));
});

describe('effectiveLook & live profile look application', () => {
  it('overrides accent and wallpaper on top of global Look and falls back when absent', () => {
    const base = {
      ...DEFAULT_LOOK,
      accent: 'amber' as const,
      wallpaper: { kind: 'builtin' as const, id: 'contour' as const },
    };

    expect(effectiveLook(base, null)).toEqual(base);

    const personal: Profile = {
      ...templateDefaults('personal', 1000),
      id: 'p-personal',
      name: 'Особисте',
      look: null,
    };
    expect(effectiveLook(base, personal)).toEqual(base);

    const work: Profile = {
      ...templateDefaults('work', 1000),
      id: 'p-work',
      name: 'Робота',
      look: { accent: 'sea', wallpaper: 'sand', glyph: 'work' },
    };
    expect(effectiveLook(base, work)).toEqual({
      ...base,
      accent: 'sea',
      wallpaper: { kind: 'builtin', id: 'sand' },
      wallpaperPicked: true,
    });
    expect('theme' in effectiveLook(base, work)).toBe(false);

    const plain: Profile = {
      ...work,
      look: { wallpaper: 'none' },
    };
    expect(effectiveLook(base, plain)).toEqual({
      ...base,
      accent: 'amber',
      wallpaper: { kind: 'none' },
      wallpaperPicked: true,
    });
  });

  it('re-applies effective look to root when switching active profile or updating look', () => {
    const s = useProfiles.getState();
    const work = s.addFromTemplate('work');
    s.setProfileLook(work.id, { accent: 'sea', wallpaper: 'slate' });

    act(() => {
      useProfiles.getState().setActive(work.id);
    });

    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');
    // Головна дія — бурштин у кожному профілі (лідер 28.10); колір профілю — лише на пігулці.
    expect(document.documentElement.getAttribute('data-accent')).toBe('amber');
    expect(useLook.getState().look.wallpaper).toEqual({ kind: 'builtin', id: 'slate' });

    act(() => {
      useProfiles.getState().setProfileLook(work.id, { accent: 'rose' });
    });

    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('rose');
  });

  it('registers a single profile subscription in startLookWatch and removes it on stop', () => {
    const refreshSpy = vi.spyOn(useLook.getState(), 'refresh');
    const work = useProfiles.getState().addFromTemplate('work');

    act(() => {
      useProfiles.getState().setActive(work.id);
    });
    expect(refreshSpy).toHaveBeenCalledTimes(1);

    stopWatch?.();
    stopWatch = undefined;
    refreshSpy.mockClear();

    act(() => {
      useProfiles.getState().setProfileLook(work.id, { accent: 'iris' });
    });
    expect(refreshSpy).not.toHaveBeenCalled();
    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');
  });

  it('applyLookToRoot(readLook()) preserves active profile accent (as data-profile-accent; root accent stays global)', () => {
    const s = useProfiles.getState();
    const work = s.addFromTemplate('work');
    s.setProfileLook(work.id, { accent: 'sea' });
    act(() => {
      useProfiles.getState().setActive(work.id);
    });

    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');

    act(() => {
      applyLookToRoot(readLook());
    });

    expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');
  });
});

describe('ProfilesSheet v3 carousel, templates, and editors', () => {
  it('renders carousel cards for 3 profiles and switches active through the diff', () => {
    const s = useProfiles.getState();
    const personal = s.profiles[0];
    const work = s.addFromTemplate('work');
    const family = s.addFromTemplate('family');

    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));

    const cardPersonal = screen.getByTestId(`profile-select-${personal.id}`);
    const cardWork = screen.getByTestId(`profile-select-${work.id}`);
    const cardFamily = screen.getByTestId(`profile-select-${family.id}`);

    expect(cardPersonal).toBeInTheDocument();
    expect(cardWork).toBeInTheDocument();
    expect(cardFamily).toBeInTheDocument();
    expect(cardPersonal.textContent).toContain('Зараз');

    fireEvent.click(cardWork);
    expect(useProfiles.getState().activeId).toBe(personal.id);
    fireEvent.click(screen.getByTestId('profile-switch-go'));
    expect(useProfiles.getState().activeId).toBe(work.id);
    expect(screen.getByTestId(`profile-select-${work.id}`).textContent).toContain('Зараз');
  });

  it('«Новий профіль» → template → creates new active profile and opens the look editor', () => {
    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));
    fireEvent.click(screen.getByTestId('profile-add-open'));

    expect(screen.getByTestId('profile-templates')).toBeInTheDocument();
    const workCard = screen.getByTestId('profile-template-work');
    expect(workCard.textContent).not.toMatch(/09:00|18:00|Пн/);
    fireEvent.click(workCard);
    fireEvent.click(screen.getByTestId('profile-template-create'));

    const state = useProfiles.getState();
    const active = state.profiles.find((p) => p.id === state.activeId);
    expect(active?.kind).toBe('work');
    expect(active?.look).toEqual({ accent: 'sea', glyph: 'work' });
    expect('schedule' in (active ?? {})).toBe(false);

    expect(screen.getByTestId('profile-look-editor')).toBeInTheDocument();
    expect(screen.getByTestId('profile-look-preview')).toHaveAttribute('data-accent', 'sea');

    fireEvent.click(screen.getByTestId('profile-look-back'));
    expect(screen.queryByTestId('profile-look-editor')).toBeNull();
    expect(screen.getByTestId(`profile-select-${active!.id}`)).toBeInTheDocument();
  });

  it('lists v3 templates first and keeps all previously creatable kinds reachable, relabeling family on language switch', () => {
    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));
    fireEvent.click(screen.getByTestId('profile-add-open'));

    const container = screen.getByTestId('profile-templates');
    const buttons = Array.from(container.querySelectorAll<HTMLElement>('[data-testid^="profile-template-"]'));
    const renderedKinds = buttons.map((b) => b.dataset.testid!.replace('profile-template-', ''));

    expect(renderedKinds.slice(0, NEW_PROFILE_KINDS.length)).toEqual([...NEW_PROFILE_KINDS]);
    for (const kind of PROFILE_KINDS) {
      if (HIDDEN_PROFILE_KINDS.includes(kind)) expect(renderedKinds).not.toContain(kind);
      else expect(renderedKinds).toContain(kind);
    }

    const familyBtn = screen.getByTestId('profile-template-family');
    const familyCardTitle = familyBtn.querySelector('.text-headline')?.textContent;
    expect(familyCardTitle).toBe('Сімʼя');

    fireEvent.click(familyBtn);
    fireEvent.click(screen.getByTestId('profile-template-create'));
    const familyId = useProfiles.getState().activeId;
    const familyProfile = useProfiles.getState().profiles.find((p) => p.id === familyId);
    expect(familyProfile?.kind).toBe('family');
    expect(familyProfile?.name).toBe(familyCardTitle);

    act(() => {
      useSettingsStore.setState((s) => ({
        ...s,
        values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' },
      }));
    });
    expect(useProfiles.getState().profiles.find((p) => p.id === familyId)?.name).toBe('Family');

    act(() => {
      useSettingsStore.setState((s) => ({
        ...s,
        values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'cs' },
      }));
    });
    expect(useProfiles.getState().profiles.find((p) => p.id === familyId)?.name).toBe('Rodina');

    act(() => {
      useSettingsStore.setState((s) => ({
        ...s,
        values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' },
      }));
    });
    expect(useProfiles.getState().profiles.find((p) => p.id === familyId)?.name).toBe('Сімʼя');
  });

  it('opens quiet editor from «Тиша» row with working quiet controls', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    useProfiles.getState().setActive(work.id);

    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));
    const quietRow = screen.getByTestId('profile-schedule-open');
    expect(quietRow.textContent).toContain('Тиша');
    fireEvent.click(quietRow);

    const editor = screen.getByTestId('profile-schedule-editor');
    expect(editor).toBeInTheDocument();
    expect(editor.querySelector('.text-title-2')?.textContent).toBe('Тиша');
    expect(screen.queryByTestId('profile-schedule-auto')).toBeNull();
    expect(screen.queryByTestId('profile-schedule-day-0')).toBeNull();
    expect(screen.queryByTestId('profile-schedule-from')).toBeNull();
    expect(screen.queryByTestId('profile-schedule-to')).toBeNull();

    expect(editor.querySelector('input[type="time"]')).toBeNull();
    pickTime('profile-schedule-quiet-from', '21', '30');
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toEqual({
      from: '21:30',
      to: '08:00',
    });

    fireEvent.click(screen.getByTestId('profile-schedule-quiet'));
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toBeNull();
  });

  it('renders each carousel card with its own accent-soft and accent-text in day and night while the work profile (sea) is active and the root stays amber', () => {
    const stylesheets = [
      'src/styles/tokens.css',
      'src/styles/phantom/tokens.css',
      'src/styles/form.css',
      'src/styles/identity.css',
      'src/styles/wallpaper.css',
    ];
    const styleEl = document.createElement('style');
    styleEl.textContent = stylesheets
      .map((p) => readFileSync(join(process.cwd(), p), 'utf8'))
      .join('\n');
    document.head.appendChild(styleEl);

    const resolveToken = (cs: CSSStyleDeclaration, rootCs: CSSStyleDeclaration, prop: string): string => {
      const raw = cs.getPropertyValue(prop).trim();
      const m = /^var\((--[a-z0-9-]+)\)$/.exec(raw);
      return m ? rootCs.getPropertyValue(m[1]).trim() : raw;
    };

    try {
      const s = useProfiles.getState();
      const personal = s.profiles[0];
      s.setProfileLook(personal.id, { accent: 'amber' });
      const work = s.addFromTemplate('work');
      const family = s.addFromTemplate('family');
      act(() => {
        useProfiles.getState().setActive(work.id);
      });

      expect(document.documentElement.getAttribute('data-profile-accent')).toBe('sea');

      render(<ProfilesHost />);
      fireEvent.click(screen.getByTestId('me-profiles-open'));

      const amberCard = screen.getByTestId(`profile-row-${personal.id}`);
      const roseCard = screen.getByTestId(`profile-row-${family.id}`);
      expect(amberCard).toHaveAttribute('data-accent', 'amber');
      expect(roseCard).toHaveAttribute('data-accent', 'rose');

      // Night
      document.documentElement.dataset.theme = 'night';
      const rootNight = window.getComputedStyle(document.documentElement);
      const amberNight = window.getComputedStyle(amberCard);
      const roseNight = window.getComputedStyle(roseCard);

      expect(amberNight.getPropertyValue('--ph-accent-soft').trim()).toContain('color-mix');
      expect(roseNight.getPropertyValue('--ph-accent-soft').trim()).toContain('color-mix');

      const rootNightText = resolveToken(rootNight, rootNight, '--ph-accent-text');
      const amberNightText = resolveToken(amberNight, rootNight, '--ph-accent-text');
      const roseNightText = resolveToken(roseNight, rootNight, '--ph-accent-text');

      // Корінь — бурштин у кожному профілі (лідер 28.10), тож бурштинова картка збігається з коренем.
      expect(amberNightText).toBe(rootNightText);
      expect(roseNightText).not.toBe(rootNightText);
      expect(roseNightText).not.toBe(amberNightText);

      // Day
      document.documentElement.dataset.theme = 'day';
      const rootDay = window.getComputedStyle(document.documentElement);
      const amberDay = window.getComputedStyle(amberCard);
      const roseDay = window.getComputedStyle(roseCard);

      expect(amberDay.getPropertyValue('--ph-accent-soft').trim()).toContain('color-mix');
      expect(roseDay.getPropertyValue('--ph-accent-soft').trim()).toContain('color-mix');

      const rootDayText = resolveToken(rootDay, rootDay, '--ph-accent-text');
      const amberDayText = resolveToken(amberDay, rootDay, '--ph-accent-text');
      const roseDayText = resolveToken(roseDay, rootDay, '--ph-accent-text');

      expect(amberDayText).toBe(rootDayText);
      expect(roseDayText).not.toBe(rootDayText);
      expect(amberDayText).not.toBe(amberNightText);
      expect(roseDayText).not.toBe(roseNightText);
    } finally {
      styleEl.remove();
    }
  });
});

describe('ProfileLookEditor', () => {
  it('picking a swatch calls store and changes the preview accent, without rendering a theme control', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    useProfiles.getState().setActive(work.id);

    render(<ProfileLookEditor profileId={work.id} onBack={() => {}} />);

    expect(screen.queryByTestId('profile-theme-default')).toBeNull();
    expect(screen.queryByTestId('profile-theme-day')).toBeNull();
    expect(screen.queryByTestId('profile-theme-night')).toBeNull();
    expect(screen.queryByTestId('profile-theme-sun')).toBeNull();

    const preview = screen.getByTestId('profile-look-preview');
    expect(preview).toHaveAttribute('data-accent', 'sea');
    expect(screen.getByTestId('profile-swatch-sea')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('profile-swatch-rose'));

    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.look?.accent).toBe('rose');
    expect(screen.getByTestId('profile-swatch-rose')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('profile-swatch-sea')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('profile-look-preview')).toHaveAttribute('data-accent', 'rose');
  });
});

describe('PhoneChatList header: one profile switch beside the pane tabs', () => {
  const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
  const noop = () => {};

  it('one profile shows no switch; several put the active profile beside the tabs, and «Керувати профілями» opens the manager', () => {
    const { container, unmount } = render(
      <PhoneChatList
        chats={[] as Chat[]}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );
    expect(container.querySelector('[data-profile-slot]')).toBeNull();
    expect(screen.queryByTestId('chats-profile-title')).toBeNull();
    unmount();

    const work = useProfiles.getState().addFromTemplate('work');
    useProfiles.getState().setActive(work.id);

    const view = render(
      <>
        <PhoneChatList
          chats={[] as Chat[]}
          currentUser={USER}
          onSelectChat={noop}
          onNewChat={noop}
          onOpenChatActions={noop}
        />
        <ProfilesHost showTrigger={false} />
      </>,
    );

    const slot = view.container.querySelector('[data-profile-slot]')!;
    const button = screen.getByTestId('chats-profile-title');
    expect(slot.contains(button)).toBe(true);
    expect(view.container.querySelectorAll('[data-testid="chats-profile-title"]')).toHaveLength(1);
    expect(button).toHaveAccessibleName(/Робота/);
    expect(view.container.querySelector('[data-profile-pill]')).toBeNull();
    expect(screen.queryByTestId('chats-profile-avatar')).toBeNull();

    fireEvent.click(button);
    expect(screen.getByTestId('chats-profile-sheet')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('chats-profile-manage'));
    expect(screen.getByTestId('profiles-sheet')).toBeInTheDocument();
  });
});
