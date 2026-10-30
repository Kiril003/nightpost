import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { VenueSheet } from '../components/messenger/meetup/VenueSheet';
import { AT, VENUE, installBridge, removeBridge } from './meetupFixtures';

describe('VenueSheet events section', () => {
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockReturnValue(AT);
  });

  afterEach(() => {
    removeBridge();
    vi.restoreAllMocks();
  });

  it('hides past events, sorts upcoming ascending by starts, and caps at 5 rows', async () => {
    installBridge((c) => {
      if (c.m === 'GET' && c.p === '/messenger/venues/cafe-x') {
        return {
          data: {
            id: 'cafe-x',
            name: 'Cafe X',
            location: { address: VENUE.address, lat: VENUE.lat, lon: VENUE.lon },
            token: 'USDC',
            menu_version: VENUE.menuVersion,
            wallet: null,
            rating: VENUE.rating,
            page: {
              v: 1,
              blocks: [
                { type: 'title', text: 'Cafe X' },
                { type: 'event', id: 'past-1', title: 'Минула виставка', starts: '2026-10-09T17:00' },
                {
                  type: 'event',
                  id: 'past-2',
                  title: 'Ранкова кава',
                  starts: '2026-10-09T16:00',
                  ends: '2026-10-09T18:30',
                },
                {
                  type: 'event',
                  id: 'ev-4',
                  title: 'Кінопоказ',
                  starts: '2026-10-12T20:00',
                  price: '5',
                  token: 'USDC',
                },
                {
                  type: 'event',
                  id: 'ev-2',
                  title: 'Джазовий вечір',
                  starts: '2026-10-10T19:30',
                  price: '7.5',
                  token: 'USDC',
                },
                {
                  type: 'event',
                  id: 'ev-1',
                  title: 'Відкритий мікрофон',
                  starts: '2026-10-09T18:00',
                  ends: '2026-10-09T21:00',
                },
                { type: 'event', id: 'ev-5', title: 'Акустика', starts: '2026-10-13T19:00' },
                { type: 'event', id: 'ev-3', title: 'Поезія', starts: '2026-10-11T18:00' },
                { type: 'event', id: 'ev-6', title: 'Шоста подія', starts: '2026-10-14T20:00' },
              ],
            },
          },
        };
      }
      return undefined;
    });

    render(<VenueSheet open onClose={() => {}} venueId="cafe-x" snap={VENUE} />);

    await waitFor(() => {
      expect(screen.getByTestId('venue-events')).toBeTruthy();
    });

    expect(screen.queryByText('Минула виставка')).toBeNull();
    expect(screen.queryByText('Ранкова кава')).toBeNull();
    expect(screen.queryByText('Шоста подія')).toBeNull();

    const rows = screen.getAllByTestId('venue-event-row');
    expect(rows).toHaveLength(5);
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('Відкритий мікрофон'),
      expect.stringContaining('Джазовий вечір'),
      expect.stringContaining('Поезія'),
      expect.stringContaining('Кінопоказ'),
      expect.stringContaining('Акустика'),
    ]);
    expect(rows[1].textContent).toContain('19:30');
    expect(rows[1].textContent).toContain('7,50\u00A0USDC');
    expect(rows[3].textContent).toContain('20:00');
    expect(rows[3].textContent).toContain('5,00\u00A0USDC');
  });

  it('does not render the events section when there are no upcoming events', async () => {
    installBridge((c) => {
      if (c.m === 'GET' && c.p === '/messenger/venues/cafe-x') {
        return {
          data: {
            id: 'cafe-x',
            name: 'Cafe X',
            location: { address: VENUE.address, lat: VENUE.lat, lon: VENUE.lon },
            token: 'USDC',
            menu_version: VENUE.menuVersion,
            wallet: null,
            rating: VENUE.rating,
            page: {
              v: 1,
              blocks: [
                { type: 'title', text: 'Cafe X' },
                { type: 'about', text: 'Кава біля площі' },
                { type: 'event', id: 'old', title: 'Вчорашній концерт', starts: '2026-10-08T19:00' },
              ],
            },
          },
        };
      }
      return undefined;
    });

    render(<VenueSheet open onClose={() => {}} venueId="cafe-x" snap={VENUE} />);

    await waitFor(() => {
      expect(screen.getByText('Кава біля площі')).toBeTruthy();
    });

    expect(screen.queryByTestId('venue-events')).toBeNull();
    expect(screen.queryByText('Події')).toBeNull();
  });

  it('інший заклад не показує сторінку попереднього, поки своя не прийшла', async () => {
    installBridge((c) => {
      if (c.m === 'GET' && c.p === '/messenger/venues/cafe-x') {
        return {
          data: {
            id: 'cafe-x',
            name: 'Cafe X',
            location: { address: VENUE.address, lat: VENUE.lat, lon: VENUE.lon },
            token: 'USDC',
            menu_version: VENUE.menuVersion,
            wallet: null,
            rating: VENUE.rating,
            page: { v: 1, blocks: [{ type: 'title', text: 'Cafe X' }] },
          },
        };
      }
      if (c.p === '/messenger/venues/bar-y') return { status: 404, code: 'not_found' };
      return undefined;
    });

    const { rerender } = render(<VenueSheet open onClose={() => {}} venueId="cafe-x" snap={VENUE} />);
    await waitFor(() => expect(screen.getAllByText('Cafe X').length).toBeGreaterThan(0));

    rerender(<VenueSheet open onClose={() => {}} venueId="bar-y" snap={{ ...VENUE, id: 'bar-y', name: 'Bar Y' }} />);
    await waitFor(() => expect(screen.getByTestId('venue-refused')).toBeTruthy());
    expect(screen.queryByText('Cafe X')).toBeNull();
    expect(screen.getAllByText('Bar Y').length).toBeGreaterThan(0);
  });
});
