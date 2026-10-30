import { beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { DIRECTION_SCREEN_IDS, DirectionTabHost, useDirectionTab } from '../components/messenger/directions/DirectionTabHost';
import { WEB_SLOT_TABS, barSlots } from '../components/messenger/directions';
import { useMessengerStore } from '../stores/messengerStore';
import { buildBridgePayload, loadProfiles, useProfiles, type ProfileKind } from '../stores/profilesStore';

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  useDirectionTab.setState({ openId: null });
});

const SLOTS: Array<[ProfileKind, string[]]> = [
  ['personal', []],
  ['work', ['Завдання', 'Зміни']],
  ['business', ['Вхідні', 'Замовлення', 'Послуги']],
  ['dev', []],
  ['study', ['Класи']],
  ['author', []],
  ['unnamed', []],
];

describe('власні вкладки напряму у смузі телефона', () => {
  it.each(SLOTS)('%s: слоти в мості після «Розмов»', (kind, labels) => {
    expect(barSlots(kind, 'uk').map((s) => s.label)).toEqual(labels);
    const p = useProfiles.getState().addFromTemplate(kind, 'X');
    const slots = buildBridgePayload(useProfiles.getState()).profiles.find((x) => x.id === p.id)!.slots;
    if (kind !== 'personal') expect(slots.map((s) => s.label)).toEqual(labels);
  });

  it('кожна зареєстрована веб-вкладка має екран у хості; «Розкладу» немає', () => {
    expect([...WEB_SLOT_TABS].sort()).toEqual([...DIRECTION_SCREEN_IDS].sort());
    expect(barSlots('study', 'uk').map((s) => s.id)).not.toContain('schedule');
  });
});

describe('хост вкладки напряму', () => {
  const door = (id: string) => (window as unknown as { __phantomOpenDirection: (i: string) => boolean }).__phantomOpenDirection(id);

  it.each([
    ['work', 'tasks'],
    ['work', 'shifts'],
    ['business', 'inbox'],
    ['business', 'orders'],
    ['business', 'services'],
    ['study', 'classes'],
  ] as Array<[ProfileKind, string]>)('%s → %s відкривається дверима і малює екран', (kind, id) => {
    const p = useProfiles.getState().addFromTemplate(kind, 'X');
    useProfiles.getState().setActive(p.id);
    render(<DirectionTabHost />);
    expect(screen.queryByTestId('direction-tab-host')).toBeNull();
    act(() => {
      expect(door(id)).toBe(true);
    });
    expect(screen.getByTestId('direction-tab-host').getAttribute('data-tab')).toBe(id);
    act(() => {
      expect(door('')).toBe(true);
    });
    expect(screen.queryByTestId('direction-tab-host')).toBeNull();
  });

  it('T59: над «Класами» одна назва, а не дві з порожнечею між ними', () => {
    const p = useProfiles.getState().addFromTemplate('study', 'X');
    useProfiles.getState().setActive(p.id);
    render(<DirectionTabHost />);
    act(() => {
      expect(door('classes')).toBe(true);
    });
    expect(screen.getAllByText('Класи')).toHaveLength(1);
  });

  it('двері відмовляють, коли вкладки немає в активному профілі, і шар зникає при зміні профілю', () => {
    const w = useProfiles.getState().addFromTemplate('work', 'X');
    useProfiles.getState().setActive(w.id);
    render(<DirectionTabHost />);
    act(() => {
      expect(door('classes')).toBe(false);
      expect(door('tasks')).toBe(true);
    });
    expect(screen.getByTestId('direction-tab-host')).toBeInTheDocument();
    const personal = useProfiles.getState().profiles.find((p) => p.kind === 'personal')!;
    act(() => {
      useProfiles.getState().setActive(personal.id);
    });
    expect(screen.queryByTestId('direction-tab-host')).toBeNull();
  });

  it('відкритий чат (сповіщення, посилання) знімає шар, щоб розмова не лишилась під ним', () => {
    const w = useProfiles.getState().addFromTemplate('work', 'X');
    useProfiles.getState().setActive(w.id);
    render(<DirectionTabHost />);
    act(() => {
      door('tasks');
    });
    expect(screen.getByTestId('direction-tab-host')).toBeInTheDocument();
    act(() => {
      useMessengerStore.setState({ activeChatId: 'chat-from-push' });
    });
    expect(screen.queryByTestId('direction-tab-host')).toBeNull();
  });
});
