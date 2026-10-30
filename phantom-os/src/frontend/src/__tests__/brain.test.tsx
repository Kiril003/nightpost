/**
 * Розум PHANTOM (27.09). Без моделі PHANTOM не відповідає — розмова казала
 * «Готовий», а ключ можна було дати лише файлом .env. Тепер: Налаштування →
 * «Розум PHANTOM» (ключ + справжня перевірка), а розмова чесно веде туди.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { brainApi } from '../services/api';
import { useAuthStore } from '../stores/authStore';
import { useSettingsStore } from '../stores/settingsStore';
import { DeskSettings } from '../components/shell/DeskSettings';

const OWNER = { id: 'u1', username: 'phantom', role: 'ROOT', preferences: { display_name: 'Кирило' } } as never;

beforeEach(() => {
  useSettingsStore.setState({ values: {}, loaded: true });
  useAuthStore.setState({ user: OWNER });
});
afterEach(() => vi.restoreAllMocks());

describe('Налаштування → Розум Nightpost', () => {
  it('без ключа — чесно каже; ключ зберігається й перевіряється', async () => {
    vi.spyOn(brainApi, 'state').mockResolvedValue({ ready: false, provider: null, has_key: false });
    const set = vi.spyOn(brainApi, 'setGeminiKey').mockResolvedValue({ key: 'ai_gemini_api_key', value: '••', requires_restart: false });
    vi.spyOn(brainApi, 'check').mockResolvedValue({ ok: false, reason: 'invalid_key', provider: 'gemini' });
    render(<MemoryRouter><DeskSettings /></MemoryRouter>);
    expect(await screen.findByText(/поки не може відповідати/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Ключ Gemini'), { target: { value: '  AIza-bad  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    await waitFor(() => expect(set).toHaveBeenCalledWith('AIza-bad'));
    expect(await screen.findByText(/Google не прийняв цей ключ/)).toBeTruthy();
  });

  it('гість розділу не бачить', async () => {
    const state = vi.spyOn(brainApi, 'state');
    useAuthStore.setState({ user: { ...(OWNER as object), role: 'GUEST' } as never });
    render(<MemoryRouter><DeskSettings /></MemoryRouter>);
    await new Promise((r) => setTimeout(r, 10));
    expect(screen.queryByText('Розум Nightpost')).toBeNull();
    expect(state).not.toHaveBeenCalled();
  });
});
