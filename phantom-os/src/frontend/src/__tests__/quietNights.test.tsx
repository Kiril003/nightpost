/**
 * «Тиша вночі» на «Я»: картка малюється зі стану телефона, перемикач і
 * вибори йдуть PATCH-ем у міст; без моста картки немає.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { messengerApi, type QuietHours, type NodeContact } from '../services/messengerApi';
import { QuietNightsCard, clock, exceptLine } from '../components/messenger/QuietNights';

const q = (over: Partial<QuietHours> = {}): QuietHours => ({
  enabled: true,
  from: '23:00',
  to: '07:00',
  except: ['p_mom', 'p_oks'],
  ...over,
});

const contact = (id: string, name: string) => ({ peer_node_id: id, display_name: name }) as unknown as NodeContact;

beforeEach(() => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
    contact('p_mom', 'Мама'),
    contact('p_oks', 'Оксана'),
    contact('p_ihor', 'Ігор'),
  ]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('«Тиша вночі»: картка', () => {
  it('години й винятки людськими словами, перемикач увімкнений', async () => {
    vi.spyOn(messengerApi, 'quiet').mockResolvedValue(q());
    render(<QuietNightsCard />);
    const card = await screen.findByTestId('me-quiet');
    expect(within(card).getByText('Тиша вночі')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('me-quiet-line').textContent).toBe('23:00–7:00 · крім: Мама й Оксана'));
    expect(within(card).getByRole('switch').getAttribute('aria-checked')).toBe('true');
    expect(card.textContent).not.toMatch(/p_mom|p_oks/);
  });

  it('перемикач кличе PATCH і показує те, що сказав телефон', async () => {
    vi.spyOn(messengerApi, 'quiet').mockResolvedValue(q());
    const set = vi.spyOn(messengerApi, 'setQuiet').mockResolvedValue(q({ enabled: false }));
    render(<QuietNightsCard />);
    const sw = await screen.findByRole('switch');
    fireEvent.click(sw);
    await waitFor(() => expect(set).toHaveBeenCalledWith({ enabled: false }));
    await waitFor(() => expect(sw.getAttribute('aria-checked')).toBe('false'));
  });

  it('нема моста — нема картки', async () => {
    const get = vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('404'));
    render(<QuietNightsCard />);
    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(screen.queryByTestId('me-quiet')).toBeNull();
  });

  it('відповідь не від телефона — теж нема картки', async () => {
    vi.spyOn(messengerApi, 'quiet').mockResolvedValue({} as QuietHours);
    render(<QuietNightsCard />);
    await waitFor(() => expect(messengerApi.listContacts).toHaveBeenCalled());
    expect(screen.queryByTestId('me-quiet')).toBeNull();
  });
});

describe('«Тиша вночі»: аркуш', () => {
  it('дотик по підпису відкриває вибір годин і людей; вибір іде в міст', async () => {
    vi.spyOn(messengerApi, 'quiet').mockResolvedValue(q());
    const set = vi.spyOn(messengerApi, 'setQuiet').mockImplementation(async (p) => q(p));
    render(<QuietNightsCard />);
    fireEvent.click(await screen.findByTestId('me-quiet-open'));
    const sheet = await screen.findByTestId('quiet-sheet');

    fireEvent.click(within(sheet).getByRole('radio', { name: '22:00' }));
    await waitFor(() => expect(set).toHaveBeenCalledWith({ from: '22:00' }));

    fireEvent.click(within(sheet).getByRole('radio', { name: '8:00' }));
    await waitFor(() => expect(set).toHaveBeenCalledWith({ to: '08:00' }));

    const ihor = within(sheet).getByRole('checkbox', { name: /Ігор/ });
    expect(ihor.getAttribute('aria-checked')).toBe('false');
    fireEvent.click(ihor);
    await waitFor(() => expect(set).toHaveBeenCalledWith({ except: ['p_mom', 'p_oks', 'p_ihor'] }));
  });
});

describe('«Тиша вночі»: слова', () => {
  it('години без нуля попереду, винятки без обривів', () => {
    expect(clock('07:00')).toBe('7:00');
    expect(clock('00:00')).toBe('0:00');
    expect(clock('23:00')).toBe('23:00');
    expect(exceptLine([])).toBe('');
    expect(exceptLine(['Мама'])).toBe(' · крім: Мама');
    expect(exceptLine(['Мама', 'Оксана', 'Ігор', 'Льоша'])).toBe(' · крім: Мама, Оксана й ще 2');
  });
});
