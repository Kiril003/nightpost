/**
 * «Вийти» на ПК живе в «Я» (17.10): верхня смуга більше не несе другої копії себе.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DeskSettings } from '../components/shell/DeskSettings';
import { useAuthStore } from '../stores/authStore';
import { useSystemStore } from '../stores/systemStore';
import { useSettingsStore } from '../stores/settingsStore';
import * as api from '../services/api';

afterEach(() => vi.restoreAllMocks());

describe('Я › Вийти', () => {
  it('signs out from the «Я» section', () => {
    vi.spyOn(api, 'request').mockResolvedValue({} as never);
    useSettingsStore.setState({ loaded: true });
    useSystemStore.setState({ authenticated: true });
    useAuthStore.setState({ user: { id: 'u1', username: 'olena', role: 'ROOT', preferences: {} } as never, token: 't' } as never);
    render(
      <MemoryRouter>
        <DeskSettings />
      </MemoryRouter>,
    );
    const me = screen.getByRole('region', { name: 'Я' });
    fireEvent.click(me.querySelector('[data-testid="desk-sign-out"]') as HTMLElement);
    expect(useAuthStore.getState().user).toBeNull();
    expect(useSystemStore.getState().authenticated).toBe(false);
  });
});
