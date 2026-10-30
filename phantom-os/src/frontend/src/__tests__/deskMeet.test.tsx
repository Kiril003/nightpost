/**
 * Перший запуск і «Я» (26.09). Ручки першого запуску були в ядрі, але
 * інтерфейс їх не кликав: власник ніколи не казав PHANTOM свого імені й не
 * знав свого PIN; змінити PIN чи ім'я було ніде.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { authApi } from '../services/api';
import { useAuthStore } from '../stores/authStore';
import { useSystemStore } from '../stores/systemStore';
import { useSettingsStore } from '../stores/settingsStore';
import { DeskMeet } from '../components/shell/DeskMeet';
import { DeskSettings } from '../components/shell/DeskSettings';

const USER = {
  id: 'u1', username: 'phantom', role: 'ROOT', avatar_url: null, rfid_uid_hash: null, pin_hash: null,
  last_seen_at: '', created_at: '', preferences: { display_name: 'Кирило' }, behavioral_model: {},
} as never;

function typePin(pin: string) {
  for (const d of pin) fireEvent.click(screen.getByRole('button', { name: d }));
}

beforeEach(() => {
  useSystemStore.setState({ authenticated: false });
});
afterEach(() => vi.restoreAllMocks());

describe('Знайомство', () => {
  it('ім\'я → PIN двічі → вузол приймає власника, людина всередині', async () => {
    const claim = vi.spyOn(authApi, 'claim').mockResolvedValue({ user: USER, token: 't', expires_at: '2099-01-01' } as never);
    const done = vi.fn();
    render(<DeskMeet onDone={done} />);
    expect(screen.getByText('Привіт. Я Nightpost. Як вас звати?')).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Ваше ім'я"), { target: { value: 'Кирило' } });
    fireEvent.click(screen.getByRole('button', { name: 'Далі' }));
    expect(await screen.findByText('Кирило, придумайте PIN із шести цифр.')).toBeTruthy();
    typePin('135790');
    expect(await screen.findByText('Ще раз, щоб не помилитись.')).toBeTruthy();
    typePin('135790');
    await waitFor(() => expect(claim).toHaveBeenCalledWith('Кирило', '135790'));
    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(useSystemStore.getState().authenticated).toBe(true);
    expect(useAuthStore.getState().token).toBe('t');
  });

  it('PIN не збігся — нічого не відправлено, просить ще раз', async () => {
    const claim = vi.spyOn(authApi, 'claim');
    render(<DeskMeet onDone={() => {}} />);
    fireEvent.change(screen.getByLabelText("Ваше ім'я"), { target: { value: 'Оля' } });
    fireEvent.click(screen.getByRole('button', { name: 'Далі' }));
    await screen.findByText('Оля, придумайте PIN із шести цифр.');
    typePin('111111');
    await screen.findByText('Ще раз, щоб не помилитись.');
    typePin('222222');
    expect(await screen.findByText('Не збіглось — придумайте ще раз.')).toBeTruthy();
    expect(claim).not.toHaveBeenCalled();
  });
});

describe('Налаштування → Я', () => {
  beforeEach(() => {
    useSettingsStore.setState({ values: {}, loaded: true });
    useAuthStore.setState({ user: USER });
  });

  const mount = () => render(<MemoryRouter><DeskSettings /></MemoryRouter>);

  it('ім\'я пишеться у вузол', async () => {
    const patch = vi.spyOn(authApi, 'patchMe').mockResolvedValue({ ...(USER as object), preferences: { display_name: 'Кир' } } as never);
    mount();
    const input = screen.getByLabelText('Як до вас звертатись') as HTMLInputElement;
    expect(input.value).toBe('Кирило');
    fireEvent.change(input, { target: { value: 'Кир' } });
    fireEvent.blur(input);
    await waitFor(() => expect(patch).toHaveBeenCalledWith({ display_name: 'Кир' }));
  });

  it('зміна PIN: поточний не той — сказано, новий не збігся — не відправлено', async () => {
    const { ApiError } = await import('../services/api');
    const change = vi.spyOn(authApi, 'changePin').mockRejectedValue(new ApiError(401, 'PIN_INVALID', 'x'));
    mount();
    fireEvent.click(screen.getByRole('button', { name: 'Змінити PIN' }));
    fireEvent.change(screen.getByLabelText('Поточний PIN'), { target: { value: '1111' } });
    fireEvent.change(screen.getByLabelText('Новий PIN'), { target: { value: '2468' } });
    fireEvent.change(screen.getByLabelText('Ще раз новий'), { target: { value: '2469' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Новий PIN не збігся.')).toBeTruthy();
    expect(change).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Ще раз новий'), { target: { value: '2468' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Поточний PIN не той.')).toBeTruthy();
    expect(change).toHaveBeenCalledWith('1111', '2468');
  });
});

describe('Налаштування → Запуск (напоготові)', () => {
  it('лише у вікні Nightpost; читає й пише налаштування оболонки', async () => {
    vi.resetModules();
    const invoke = vi.fn(async (cmd: string, args?: { ready: boolean }) => (cmd === 'desk_ready_get' ? true : args?.ready));
    vi.doMock('@tauri-apps/api/core', () => ({ invoke }));
    vi.doMock('../services/nodeOrigin', async (orig) => ({ ...(await orig<object>()), inDeskShell: () => true }));
    const { DeskSettings: Fresh } = await import('../components/shell/DeskSettings');
    const { useSettingsStore: store } = await import('../stores/settingsStore');
    store.setState({ values: {}, loaded: true });
    render(<MemoryRouter><Fresh /></MemoryRouter>);
    const toggle = await screen.findByRole('switch', { name: 'Тримати Nightpost напоготові' });
    expect(toggle.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(toggle);
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('desk_ready_set', { ready: false }));
    await waitFor(() => expect(toggle.getAttribute('aria-checked')).toBe('false'));
    vi.doUnmock('@tauri-apps/api/core');
    vi.doUnmock('../services/nodeOrigin');
  });

  it('у браузері перемикача нема', () => {
    useSettingsStore.setState({ values: {}, loaded: true });
    render(<MemoryRouter><DeskSettings /></MemoryRouter>);
    expect(screen.queryByText('Тримати Nightpost напоготові')).toBeNull();
  });
});
