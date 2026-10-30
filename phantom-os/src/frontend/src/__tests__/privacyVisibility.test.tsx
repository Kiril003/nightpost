import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { messengerApi, type PrivacyState } from '../services/messengerApi';
import { PrivacyPage } from '../components/messenger/SettingsRoot';

beforeEach(() => {
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('offline'));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Приватність: хто бачить «у мережі» і фото', () => {
  it('стара збірка телефона, що не виконує вибір, — рядків немає', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
    render(<PrivacyPage />);
    await screen.findByText('Звіти про прочитання');
    expect(screen.queryByTestId('settings-visibility')).toBeNull();
    expect(screen.queryByText('Був(ла) в мережі')).toBeNull();
  });

  it('вибір «Ніхто» йде на телефон, а рядок показує те, що телефон підтвердив', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true, last_seen: 'all', photo: 'people' });
    const set = vi
      .spyOn(messengerApi, 'setVisibility')
      .mockImplementation(async (patch) => ({ read_receipts: true, last_seen: 'all', photo: 'people', ...patch }) as PrivacyState);
    render(<PrivacyPage />);
    const seen = await screen.findByTestId('settings-vis-last_seen');
    expect(seen.textContent).toContain('Був(ла) в мережі');
    expect(seen.textContent).toContain('Усі');
    expect(screen.getByTestId('settings-vis-photo').textContent).toContain('Мої люди');

    fireEvent.click(seen);
    const options = screen.getByTestId('settings-vis-last_seen-options');
    expect(within(options).getAllByRole('radio').map((r) => r.textContent)).toEqual(['Усі', 'Мої люди', 'Ніхто']);
    fireEvent.click(within(options).getByText('Ніхто'));
    expect(set).toHaveBeenCalledWith({ last_seen: 'nobody' });
    await waitFor(() => expect(screen.getByTestId('settings-vis-last_seen').textContent).toContain('Ніхто'));
    expect(screen.queryByTestId('settings-vis-last_seen-options')).toBeNull();
  });

  it('телефон відмовив — лишається попередній вибір', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true, last_seen: 'all', photo: 'all' });
    vi.spyOn(messengerApi, 'setVisibility').mockRejectedValue(new Error('bridge'));
    render(<PrivacyPage />);
    fireEvent.click(await screen.findByTestId('settings-vis-photo'));
    fireEvent.click(screen.getByTestId('settings-vis-photo-nobody'));
    await Promise.resolve();
    expect(screen.getByTestId('settings-vis-photo').textContent).toContain('Усі');
  });

  it('невідоме значення від телефона не стає рядком', async () => {
    vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true, last_seen: 'friends' } as unknown as PrivacyState);
    render(<PrivacyPage />);
    await screen.findByText('Звіти про прочитання');
    expect(screen.queryByTestId('settings-vis-last_seen')).toBeNull();
  });
});
