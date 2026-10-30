import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LocationData } from '../types/messenger';

vi.mock('../services/messengerApi', () => ({
  messengerApi: {
    listBlocked: vi.fn(),
  },
}));

import { messengerApi } from '../services/messengerApi';
import { LocationSheetModal } from '../components/messenger/LocationSheetModal';
import { resetBlockedForTests } from '../services/blockedPresent';

const placeFixture: LocationData = {
  name: 'Кавʼярня на розі',
  category: 'Кавʼярня',
  address: 'вул. Хрещатик, 1',
  distance: '',
  walkingTime: '',
  coords: { lat: 50.4501, lng: 30.5234 },
};

describe('blockedPresentLine', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetBlockedForTests();
    vi.mocked(messengerApi.listBlocked).mockResolvedValue({ blocked: [] });
  });

  afterEach(() => {
    cleanup();
  });

  it('shows blocked line in LocationSheetModal only when groupMemberIds contains a blocked user', async () => {
    vi.mocked(messengerApi.listBlocked).mockResolvedValue({ blocked: ['u3'] });

    const { unmount } = render(
      <LocationSheetModal
        isOpen
        location={placeFixture}
        onClose={vi.fn()}
        groupMemberIds={['u3']}
      />,
    );

    const line = await screen.findByText('Тут є заблокована людина');
    expect(line.getAttribute('data-blocked-present')).not.toBeNull();

    unmount();

    render(
      <LocationSheetModal
        isOpen
        location={placeFixture}
        onClose={vi.fn()}
      />,
    );

    expect(document.body.querySelector('[data-blocked-present]')).toBeNull();
  });

});
