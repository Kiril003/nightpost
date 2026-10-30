import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { PASS_NONE, loadProfiles, useProfiles } from '../stores/profilesStore';
import { loadChatProfiles, useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';

const pickTime = (id: string, hh: string, mm?: string) => {
  fireEvent.click(screen.getByTestId(id));
  const pick = screen.getByTestId(`${id.replace(/-(from|to)$/, '')}-sheet`);
  fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Години' })).getByRole('option', { name: hh }));
  if (mm) fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: mm }));
  fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
};

function openWithWorkProfile() {
  render(<ProfilesHost />);
  fireEvent.click(screen.getByTestId('me-profiles-open'));
  fireEvent.click(screen.getByTestId('profile-add-open'));
  fireEvent.click(screen.getByTestId('profile-template-work'));
  fireEvent.click(screen.getByTestId('profile-template-create'));
  const work = useProfiles.getState().profiles.find((p) => p.kind === 'work');
  expect(work).toBeDefined();
  return work!;
}

describe('ProfilesSheet per-profile settings (profiles-w3)', () => {
  beforeEach(() => {
    localStorage.clear();
    useProfiles.setState(loadProfiles());
    useChatProfiles.setState(loadChatProfiles());
    useMessengerStore.setState({ chats: [] } as never);
  });

  it('1. opens profile-settings for a row and returns to the list on back', () => {
    const work = openWithWorkProfile();

    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    expect(screen.getByTestId('profile-settings')).toBeInTheDocument();
    expect(screen.queryByTestId(`profile-row-${work.id}`)).toBeNull();

    fireEvent.click(screen.getByTestId('profile-settings-back'));
    expect(screen.queryByTestId('profile-settings')).toBeNull();
    expect(screen.getByTestId(`profile-row-${work.id}`)).toBeInTheDocument();
  });

  it('2. renames the profile on blur and keeps the old name when cleared', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));

    const input = screen.getByTestId('profile-name') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Офіс' } });
    fireEvent.blur(input);

    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.name).toBe('Офіс');

    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);

    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.name).toBe('Офіс');
    expect(input.value).toBe('Офіс');

    fireEvent.change(input, { target: { value: 'Студія' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.name).toBe('Студія');
  });

  it('3. toggles passGroups and aria-pressed on profile-pass-friends, clearing PASS_NONE', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));

    const friendsChip = screen.getByTestId('profile-pass-friends');
    expect(friendsChip).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(friendsChip);
    expect(friendsChip).toHaveAttribute('aria-pressed', 'true');
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.passGroups).toContain(
      'friends',
    );

    fireEvent.click(friendsChip);
    expect(friendsChip).toHaveAttribute('aria-pressed', 'false');
    expect(
      useProfiles.getState().profiles.find((p) => p.id === work.id)?.passGroups,
    ).not.toContain('friends');

    act(() => {
      useProfiles.getState().update(work.id, { passGroups: [PASS_NONE] });
    });
    expect(screen.getByTestId('profile-pass-work')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(friendsChip);
    const updatedGroups =
      useProfiles.getState().profiles.find((p) => p.id === work.id)?.passGroups ?? [];
    expect(updatedGroups).toEqual(['friends']);

    fireEvent.click(friendsChip);
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.passGroups).toEqual([
      PASS_NONE,
    ]);

    act(() => {
      useProfiles.getState().update(work.id, { passGroups: [] });
    });
    for (const g of ['personal', 'close', 'friends', 'social', 'work']) {
      expect(screen.getByTestId(`profile-pass-${g}`)).toHaveAttribute('aria-pressed', 'true');
    }
  });

  it('4. edits work template quiet hours (20:00–08:00) on the shared when-wheels, does not render profile-autoday, and hides them when turned off', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));

    expect(document.querySelector('input[type="time"]')).toBeNull();
    expect(screen.getByTestId('profile-quiet-from').textContent).toContain('20:00');
    expect(screen.getByTestId('profile-quiet-to').textContent).toContain('08:00');
    expect(screen.queryByTestId('profile-autoday')).toBeNull();

    pickTime('profile-quiet-from', '21', '30');
    expect(screen.queryByTestId('profile-quiet-sheet')).toBeNull();
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toEqual({
      from: '21:30',
      to: '08:00',
    });
    expect(screen.getByTestId('profile-quiet-from').textContent).toContain('21:30');
    pickTime('profile-quiet-to', '07');
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toEqual({
      from: '21:30',
      to: '07:00',
    });

    fireEvent.click(screen.getByTestId('profile-quiet'));
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toBeNull();
    expect(screen.queryByTestId('profile-quiet-from')).toBeNull();
    expect(screen.queryByTestId('profile-quiet-to')).toBeNull();
  });

  it('4b. the hour wheel opens on the saved hour and its own glide there does not pick the hours it passes', () => {
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => {
      cb(0);
      return 0;
    });
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    fireEvent.click(screen.getByTestId('profile-quiet-from'));
    const pick = screen.getByTestId('profile-quiet-sheet');
    const hours = within(pick).getByRole('listbox', { name: 'Години' });
    expect(hours.scrollTop).toBe(20 * 44);
    hours.scrollTop = 4 * 44;
    fireEvent.scroll(hours);
    fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
    expect(useProfiles.getState().profiles.find((p) => p.id === work.id)?.quiet).toEqual({ from: '20:00', to: '08:00' });
    vi.restoreAllMocks();
  });

  it('4c. daily hours sheet names the section and speaks of a time of day, not «today»', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    fireEvent.click(screen.getByTestId('profile-quiet-from'));
    const pick = screen.getByTestId('profile-quiet-sheet');
    expect(within(pick).getByText('Тихі години · З')).toBeTruthy();
    expect(within(pick).queryByText(/Сьогодні/)).toBeNull();
    expect(within(pick).getByRole('button', { name: 'Обрати · 20:00' })).toBeTruthy();
  });

  it('5. S7: no wallet label field until the owner decides what it does', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    expect(screen.getByTestId('profile-settings')).toBeTruthy();
    expect(screen.queryByTestId('profile-wallet')).toBeNull();
  });

  it('6. S7: no «assistant reads» switch — the assistant context is built natively and ignores it', () => {
    const work = openWithWorkProfile();
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    expect(screen.queryByTestId('profile-assistant')).toBeNull();
  });

  it('6b. S7: unread on a profile row counts only its own chats, not the assistant present in every profile', () => {
    const work = useProfiles.getState().addFromTemplate('work');
    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));
    useChatProfiles.setState({ map: { w1: work.id }, migratedAt: 1 });
    act(() => {
      useMessengerStore.setState({
        chats: [
          { id: 'w1', title: 'w1', type: 'dm', circle: 'all', unreadCount: 2, messages: [] },
          { id: 'bot', title: 'bot', type: 'phantom', circle: 'all', unreadCount: 5, messages: [] },
        ],
      } as never);
    });
    expect(screen.getByTestId(`profile-row-${work.id}`).textContent).toContain('2 нових');
    expect(screen.getByTestId(`profile-row-${work.id}`).textContent).not.toContain('7 нових');
  });

  it('7. orders active profile first, followed by highest-uses non-active profile', () => {
    const personal = useProfiles.getState().profiles[0];
    const work = useProfiles.getState().addFromTemplate('work');
    const hobby = useProfiles.getState().addFromTemplate('hobby');
    const family = useProfiles.getState().addFromTemplate('family');
    const study = useProfiles.getState().addFromTemplate('study');

    useProfiles.setState((s) => ({
      ...s,
      activeId: hobby.id,
      profiles: s.profiles.map((p) => {
        if (p.id === personal.id) return { ...p, uses: 2 };
        if (p.id === work.id) return { ...p, uses: 25 };
        if (p.id === hobby.id) return { ...p, uses: 1 };
        if (p.id === family.id) return { ...p, uses: 9 };
        if (p.id === study.id) return { ...p, uses: 0 };
        return p;
      }),
    }));

    render(<ProfilesHost />);
    fireEvent.click(screen.getByTestId('me-profiles-open'));

    const sheet = screen.getByTestId('profiles-sheet');
    const rows = Array.from(sheet.querySelectorAll('[data-testid^="profile-row-"]'));
    expect(rows.map((r) => r.getAttribute('data-testid'))).toEqual([
      `profile-row-${hobby.id}`,
      `profile-row-${work.id}`,
      `profile-row-${family.id}`,
      `profile-row-${personal.id}`,
      `profile-row-${study.id}`,
    ]);
  });

  it('8. contains no gradient, shadow or blur inside profiles-sheet in list or settings view', () => {
    const work = openWithWorkProfile();
    const sheet = screen.getByTestId('profiles-sheet');

    const assertClean = (root: HTMLElement) => {
      const nodes = [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))];
      for (const el of nodes) {
        const cls = el.getAttribute('class') ?? '';
        const style = el.getAttribute('style') ?? '';
        expect(cls).not.toMatch(/gradient|shadow|blur/i);
        expect(style).not.toMatch(/gradient|shadow|blur/i);
      }
    };

    assertClean(sheet);
    fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
    assertClean(screen.getByTestId('profiles-sheet'));
  });
});
