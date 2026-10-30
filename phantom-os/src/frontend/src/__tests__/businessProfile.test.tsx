import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { loadProfiles, useProfiles, type ProfileKind } from '../stores/profilesStore';
import { meetupApi, type VenuePage } from '../services/meetupApi';
import { businessFaceOf, isBusinessKind, shortWallet } from '../services/businessFace';

const PAGE: VenuePage = {
  id: 'v1',
  name: 'Кава на розі',
  blocks: [
    { type: 'hours', rows: [{ day: 'mon', open: '08:00', close: '20:00' }] },
    {
      type: 'menu',
      token: 'USDC',
      items: [
        { id: 'a', name: 'Еспресо', price: '2' },
        { id: 'b', name: 'Флет-вайт', price: '3' },
        { id: 'c', name: 'Сніданок', price: '6' },
        { id: 'd', name: 'Чай', price: '1.5' },
      ],
    },
  ],
  address: 'вул. Тиха, 1',
  lat: 1,
  lon: 2,
  token: 'USDC',
  menuVersion: '1',
  wallet: 'So1anaWa11etAddre55xyz',
  rating: null,
};

const MONDAY_10 = new Date(2026, 9, 19, 10, 0);
const MONDAY_21 = new Date(2026, 9, 19, 21, 0);

function openBusiness(kind: ProfileKind) {
  render(<ProfilesHost />);
  fireEvent.click(screen.getByTestId('me-profiles-open'));
  fireEvent.click(screen.getByTestId('profile-add-open'));
  fireEvent.click(screen.getByTestId(`profile-template-${kind}`));
  fireEvent.click(screen.getByTestId('profile-template-create'));
  const created = useProfiles.getState().profiles.find((p) => p.kind === kind)!;
  fireEvent.click(screen.getByTestId(`profile-settings-${created.id}`));
  return created;
}

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
});

afterEach(() => vi.restoreAllMocks());

describe('business face from the place page', () => {
  it('takes services, wallet and today hours only from the page', () => {
    const face = businessFaceOf(PAGE, MONDAY_10);
    expect(face.services.map((s) => s.name)).toEqual(['Еспресо', 'Флет-вайт', 'Сніданок', 'Чай']);
    expect(face.today).toEqual({ open: '08:00', close: '20:00' });
    expect(face.openNow).toBe(true);
    expect(businessFaceOf(PAGE, MONDAY_21).openNow).toBe(false);
    expect(face.wallet).toBe(PAGE.wallet);
    expect(shortWallet(PAGE.wallet!)).toBe('So1a…5xyz');
  });

  it('a page with nothing filled gives nothing', () => {
    const face = businessFaceOf({ ...PAGE, blocks: [], wallet: null }, MONDAY_10);
    expect(face).toMatchObject({ services: [], today: null, openNow: false, wallet: null });
  });

  it('only Author, Place and Organisation are business profiles', () => {
    expect(['author', 'place', 'org'].every((k) => isBusinessKind(k as ProfileKind))).toBe(true);
    expect(['personal', 'work', 'family', 'temp'].some((k) => isBusinessKind(k as ProfileKind))).toBe(false);
  });
});

describe('business profile screen', () => {
  it('opens from profile settings with honest empty states before a page is linked', () => {
    for (const kind of ['place', 'org'] as const) {
      cleanup();
      localStorage.clear();
      useProfiles.setState(loadProfiles());
      const created = openBusiness(kind);
      expect(created.kind).toBe(kind);
      fireEvent.click(screen.getByTestId('profile-business'));
      expect(screen.getByTestId('business-profile')).toBeInTheDocument();
      for (const id of ['business-services', 'business-bookings', 'business-payments', 'business-numbers']) {
        expect(within(screen.getByTestId(id)).queryAllByRole('button').filter((b) => b.dataset.testid !== 'business-pick' && b.dataset.testid !== 'biz-svc-add')).toHaveLength(0);
      }
      expect(screen.getByTestId('business-team')).toBeInTheDocument();
      expect(screen.getByTestId('business-staff-none')).toBeInTheDocument();
      expect(screen.getByTestId('business-pick')).toBeInTheDocument();
    }
  });

  it('a work profile has no business door', () => {
    openBusiness('work');
    expect(screen.queryByTestId('profile-business')).toBeNull();
  });

  it('linking a nearby place shows its real services and wallet and survives a reload', async () => {
    vi.spyOn(meetupApi, 'near').mockResolvedValue([
      { id: 'v1', name: 'Кава на розі', address: 'вул. Тиха, 1', lat: 1, lon: 2, rating: null, menuVersion: '1', wallet: null },
    ]);
    const venue = vi.spyOn(meetupApi, 'venue').mockResolvedValue(PAGE);
    const place = openBusiness('place');
    fireEvent.click(screen.getByTestId('profile-business'));
    await act(async () => fireEvent.click(screen.getByTestId('business-pick')));
    await act(async () => fireEvent.click(screen.getByTestId('business-near-v1')));

    expect(venue).toHaveBeenCalledWith('v1');
    await waitFor(() => expect(screen.getByTestId('business-name')).toHaveTextContent('Кава на розі'));
    const services = screen.getByTestId('business-services');
    expect(within(services).getByText('Еспресо')).toBeInTheDocument();
    expect(within(services).queryByText('Чай')).toBeNull();
    fireEvent.click(screen.getByTestId('business-services-all'));
    expect(within(services).getByText('Чай')).toBeInTheDocument();
    expect(within(screen.getByTestId('business-payments')).getByText('So1a…5xyz')).toBeInTheDocument();

    expect(loadProfiles(false).profiles.find((p) => p.id === place.id)?.venueId).toBe('v1');

    fireEvent.click(screen.getByTestId('business-unlink'));
    expect(useProfiles.getState().profiles.find((p) => p.id === place.id)?.venueId).toBeNull();
    expect(screen.getByTestId('business-pick')).toBeInTheDocument();
  });

  it('a refusal from the phone is shown in its own words', async () => {
    vi.spyOn(meetupApi, 'near').mockRejectedValue(new Error('Місце ще не визначено'));
    openBusiness('business');
    fireEvent.click(screen.getByTestId('profile-business'));
    await act(async () => fireEvent.click(screen.getByTestId('business-pick')));
    expect(screen.getByTestId('business-near-error')).toHaveTextContent('Місце ще не визначено');
  });
});
