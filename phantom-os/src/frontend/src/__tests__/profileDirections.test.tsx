import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { DIRECTIONS, directionOf, nativeBarTabs } from '../components/messenger/directions';
import { buildBridgePayload, loadProfiles, useProfiles, type ProfileKind } from '../stores/profilesStore';

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
});

const SHOWN: Array<[ProfileKind, string[], string[]]> = [
  ['personal', ['map', 'wallet'], []],
  ['work', ['tasks', 'shifts'], []],
  ['business', ['inbox', 'orders', 'services'], ['service', 'team']],
  ['dev', [], []],
  ['study', ['classes'], []],
  ['author', ['wallet'], []],
  ['unnamed', [], []],
];

describe('напрями профілів: вкладки й швидкі дії', () => {
  it.each(SHOWN)('%s: показано лише вкладки й дії з екраном', (kind, tabs, quick) => {
    const d = directionOf(kind);
    expect(d.shownTabs).toEqual(tabs);
    expect(d.shownQuick).toEqual(quick);
  });

  it('повний набір напрямів зафіксований, навіть де екрана ще немає', () => {
    expect(DIRECTIONS.work).toMatchObject({ tabs: ['tasks', 'shifts'], quick: ['mail-task', 'my-shift', 'swap'], wallet: false });
    expect(DIRECTIONS.business).toMatchObject({ quick: ['order', 'service', 'team'], wallet: true });
    expect(DIRECTIONS.dev).toMatchObject({ tabs: ['code'], quick: ['review', 'run-pc'], wallet: false });
    expect(DIRECTIONS.study).toMatchObject({ tabs: ['classes', 'schedule'], quick: ['assignment', 'attendance', 'grades'] });
    expect(DIRECTIONS.author).toMatchObject({ tabs: ['moments', 'pages', 'wallet'], quick: ['moment', 'drop', 'page'] });
    expect(DIRECTIONS.quiet).toMatchObject({ tabs: [], wallet: false, assistant: 'none' });
    expect(DIRECTIONS.personal).toMatchObject({ quick: ['write', 'moment', 'meet'], wallet: true });
  });

  it('смуга телефона міняється разом із профілем: Школа ховає Мапу й Гаманець', () => {
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    const personal = useProfiles.getState().profiles.find((p) => p.kind === 'personal')!;
    const bar = (id: string) => buildBridgePayload(useProfiles.getState()).profiles.find((p) => p.id === id)!.bar;
    expect(bar(personal.id)).toEqual(['map', 'wallet']);
    expect(bar(school.id)).toEqual([]);
    expect(nativeBarTabs('business')).toEqual([]);
  });

  it('новий екран напряму з’являється в смузі лише після реєстрації', async () => {
    vi.resetModules();
    const mod = await import('../components/messenger/directions');
    expect(mod.directionOf('study').shownTabs).toEqual(['classes']);
    expect(mod.directionOf('author').shownTabs).toEqual(['wallet']);
    mod.registerDirectionScreens({ tabs: ['moments'], quick: ['moment'] });
    expect(mod.directionOf('author').shownTabs).toEqual(['moments', 'wallet']);
    expect(mod.directionOf('author').shownQuick).toEqual(['moment']);
  });
});

describe('лист перемикання за рендером', () => {
  const open = () => {
    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));
  };

  it('кожен профіль — картка з рядком-підсумком і значками; вибрана розгортається', () => {
    const school = useProfiles.getState().addFromTemplate('study', 'Школа');
    const personal = useProfiles.getState().profiles.find((p) => p.kind === 'personal')!;
    open();
    expect(screen.getByTestId(`profile-summary-${personal.id}`).textContent).toBe('Розмови · Мапа · Гаманець · Я');
    expect(screen.getByTestId(`profile-summary-${school.id}`).textContent).toBe('Розмови · Класи · Я');
    expect(within(screen.getByTestId(`profile-chips-${personal.id}`)).getAllByTestId(/^chip-/)).toHaveLength(4);
    expect(screen.queryByTestId(`profile-rows-${school.id}`)).toBeNull();

    fireEvent.click(screen.getByTestId(`profile-select-${school.id}`));
    const rows = screen.getByTestId(`profile-rows-${school.id}`);
    expect(within(rows).getByTestId('kit-row-tabs').querySelectorAll('s')).toHaveLength(2);
    expect(within(rows).getByTestId('kit-row-tabs').textContent).toContain('Класи');
    expect(within(rows).getByTestId('kit-row-wallet').textContent).toContain('Немає');
    expect(within(rows).queryByTestId('kit-row-quick')).toBeNull();
    expect(screen.queryByTestId(`profile-rows-${personal.id}`)).toBeNull();
    expect(screen.getByTestId('profile-switch-go').textContent).toBe('Перейти в «Школа»');
  });

  it('бізнес показує швидкі дії, і вони ведуть на екран бізнес-профілю', () => {
    const biz = useProfiles.getState().addFromTemplate('business', 'Кав’ярня');
    useProfiles.getState().setActive(biz.id);
    open();
    expect(screen.getByTestId('profile-quick-team')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('profile-quick-service'));
    expect(screen.getByTestId('business-profile')).toBeInTheDocument();
  });

  it('чіпи шаблонів унизу створюють напрям: Навчання, Тихий', () => {
    open();
    for (const k of ['work', 'business', 'study', 'unnamed']) {
      expect(screen.getByTestId(`profile-chip-${k}`)).toBeInTheDocument();
    }
    expect(screen.queryByTestId('profile-chip-personal')).toBeNull();
    fireEvent.click(screen.getByTestId('profile-chip-study'));
    fireEvent.click(screen.getByTestId('profile-template-create'));
    expect(useProfiles.getState().profiles.some((p) => p.kind === 'study')).toBe(true);
  });
});
