import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import * as circlesApi from '../services/circlesApi';
import { messengerApi } from '../services/messengerApi';
import { DEFAULT_CIRCLES, useCirclesStore } from '../stores/circlesStore';
import { CIRCLE_RIGHTS_KEY, ENFORCED_CAPS, lostCaps, personRights, useCircleRights } from '../stores/circleRightsStore';
import { useMessengerStore } from '../stores/messengerStore';
import { PeopleScreen } from '../components/messenger/PeopleScreen';
import type { Chat } from '../types/messenger';

const dm = (id: string, title: string, circles: string[]): Chat =>
  ({ id: `c_${id}`, title, type: 'dm', peerNodeId: id, messages: [], unreadCount: 0, circle: circles[0], circles }) as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  localStorage.removeItem(CIRCLE_RIGHTS_KEY);
  useCircleRights.setState({ overrides: {} });
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(circlesApi, 'listCirclesWithMeta').mockResolvedValue({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useCirclesStore.setState({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useMessengerStore.setState({
    currentUser: { ...prev.currentUser, name: 'Кирило', status: 'Тут', bio: 'Біо' },
    chats: [dm('taras', 'Тарас', ['friends']), dm('sofia', 'Софія', ['friends'])],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

describe('rights model', () => {
  it('editor and see-as show only the enforced set', async () => {
    expect([...ENFORCED_CAPS]).toEqual(['place', 'moments']);
    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByText('Друзі'));
    fireEvent.click(await screen.findByText('Права'));
    const el = await screen.findByTestId('rights-screen');
    expect(within(el).getByTestId('right-place')).toBeTruthy();
    expect(within(el).getByTestId('right-moments')).toBeTruthy();
    expect(within(el).queryByTestId('right-status')).toBeNull();
    expect(within(el).queryByTestId('right-phone')).toBeNull();
  });

  it('lostCaps lists only what stops', () => {
    const friends = personRights(['friends'], DEFAULT_CIRCLES, {});
    const social = personRights(['social'], DEFAULT_CIRCLES, {});
    expect(lostCaps(friends, social)).toEqual(['moments']);
    expect(lostCaps(social, friends)).toEqual([]);
  });
});

describe('Люди › права, як мене бачать, підтвердження переходу', () => {
  it('rights editor writes the circle right and see-as follows it', async () => {
    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByText('Друзі'));
    fireEvent.click(await screen.findByText('Права'));
    const screenEl = await screen.findByTestId('rights-screen');
    expect(within(screenEl).getByTestId('right-moments').textContent).toBe('Так');
    fireEvent.click(within(screenEl).getByText('Моменти'));
    const sheet = await screen.findByTestId('rights-sheet');
    expect((within(sheet).getByTestId('rights-apply') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(sheet).getByTestId('right-opt-no'));
    expect(within(sheet).getByTestId('rights-changes').textContent).toBe('Зміниться для 2');
    fireEvent.click(within(sheet).getByTestId('rights-apply'));
    await waitFor(() => expect(within(screenEl).getByTestId('right-moments').textContent).toBe('Ні'));
    expect(JSON.parse(localStorage.getItem(CIRCLE_RIGHTS_KEY)!)).toEqual({ friends: { moments: 'no' } });

    fireEvent.click(screen.getByLabelText('Назад'));
    fireEvent.click(await screen.findByText('Як мене бачать'));
    const see = await screen.findByTestId('seeas-screen');
    expect(within(see).getByTestId('seeas-hidden-moments')).toBeTruthy();
    expect(within(see).getByTestId('seeas-card').textContent).toContain('Кирило');
    fireEvent.click(within(see).getByTestId('seeas-chip-social'));
    expect(within(see).queryByTestId('seeas-visible-status')).toBeNull();
    expect(within(see).queryByTestId('seeas-hidden-status')).toBeNull();
    expect(within(see).queryByTestId('seeas-hidden-phone')).toBeNull();
  });

  it('moving to a narrower circle asks first and lists what stops; a safe move goes at once', async () => {
    const join = vi.spyOn(circlesApi, 'joinCircle').mockResolvedValue(['social']);
    const leave = vi.spyOn(circlesApi, 'leaveCircle').mockResolvedValue([]);
    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByText('Друзі'));
    fireEvent.click(await screen.findByText('Тарас'));
    let sheet = await screen.findByTestId('person-actions-sheet');
    fireEvent.click(within(sheet).getByText('Перемістити в коло'));
    fireEvent.click(within(sheet).getByText('Знайомі'));
    const confirm = await screen.findByTestId('move-confirm');
    expect(join).not.toHaveBeenCalled();
    expect(within(confirm).getByTestId('move-lost-moments')).toBeTruthy();
    expect(within(confirm).queryByTestId('move-lost-status')).toBeNull();
    fireEvent.click(screen.getByTestId('move-confirm-btn'));
    await waitFor(() => expect(join).toHaveBeenCalledWith('social', 'c_taras'));
    await waitFor(() => expect(leave).toHaveBeenCalledWith('friends', 'c_taras'));

    join.mockClear();
    fireEvent.click(await screen.findByText('Софія'));
    sheet = await screen.findByTestId('person-actions-sheet');
    fireEvent.click(within(sheet).getByText('Перемістити в коло'));
    fireEvent.click(within(sheet).getByText('Близькі'));
    await waitFor(() => expect(join).toHaveBeenCalledWith('close', 'c_sofia'));
    expect(screen.queryByTestId('move-confirm')).toBeNull();
  });

  it('requests with nothing pending stay an honest empty state', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [], outgoing: [] });
    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByTestId('people-requests-row'));
    expect(await screen.findByTestId('requests-empty')).toBeTruthy();
  });
});
