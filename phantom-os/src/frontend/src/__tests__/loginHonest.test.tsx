/**
 * Вхід перевіряє PIN. До 26.09 `LoginScreen.submitPin` ловив БУДЬ-ЯКУ
 * помилку — зокрема 401 на невірний PIN — і пускав в інтерфейс із
 * «local_demo_token» і роллю ROOT. А коли ядро мовчало, пропонував чотири
 * вигадані профілі («kiril», «alex»…).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

const loginPin = vi.fn();
const picker = vi.fn();

async function mount() {
  vi.resetModules();
  vi.doMock('../services/api', () => ({ authApi: { picker, loginPin }, ApiError }));
  const { default: LoginScreen } = await import('../components/auth/LoginScreen');
  const { useSystemStore } = await import('../stores/systemStore');
  const { useAuthStore } = await import('../stores/authStore');
  useSystemStore.setState({ authenticated: false });
  render(
    <MemoryRouter>
      <LoginScreen />
    </MemoryRouter>,
  );
  return { useSystemStore, useAuthStore };
}

async function typePin() {
  const one = await screen.findByRole('button', { name: '1' });
  for (let i = 0; i < 6; i++) fireEvent.click(one);
}

beforeEach(() => {
  loginPin.mockReset();
  picker.mockReset();
  picker.mockResolvedValue([{ id: 'u1', username: 'phantom', avatar_url: '' }]);
});

describe('вхід без брехні', () => {
  it('невірний PIN — не впускає і каже про це', async () => {
    loginPin.mockRejectedValue(new ApiError(401, 'BAD_PIN', 'bad'));
    const { useSystemStore, useAuthStore } = await mount();
    await typePin();
    expect(await screen.findByText('Не той PIN.')).toBeTruthy();
    expect(useSystemStore.getState().authenticated).toBe(false);
    expect(useAuthStore.getState().token).not.toBe('local_demo_token');
  });

  it('блокування ядра — чекати, скільки воно сказало', async () => {
    loginPin.mockRejectedValue(new ApiError(429, 'LOCKED_OUT', 'Too many failed login attempts. Try again in 42 s.'));
    const { useAuthStore } = await mount();
    const t0 = Date.now();
    await typePin();
    expect(await screen.findByText('Забагато спроб. Зачекайте трохи.')).toBeTruthy();
    const until = useAuthStore.getState().lockedUntil ?? 0;
    expect(until - t0).toBeGreaterThanOrEqual(41_000);
    expect(until - t0).toBeLessThan(44_000);
  });

  it('ядро мовчить — жодних вигаданих профілів', async () => {
    picker.mockRejectedValue(new TypeError('fetch failed'));
    await mount();
    expect(await screen.findByText('Nightpost не відповідає')).toBeTruthy();
    const text = document.body.textContent ?? '';
    for (const fake of ['kiril', 'kyrylo', 'alex']) expect(text).not.toContain(fake);
  });

  describe('браузер з мережі: вузол не віддає список людей (404)', () => {
    beforeEach(() => {
      picker.mockReset();
      picker.mockRejectedValue(new ApiError(404, '', 'Not found'));
    });

    it('показує поле імені й PIN, а не «не відповідає»', async () => {
      await mount();
      expect(await screen.findByLabelText("Ім'я")).toBeTruthy();
      expect(screen.queryByText('Nightpost не відповідає')).toBeNull();
    });

    it('імʼя чиститься як на вузлі, і цифри в полі не йдуть у PIN', async () => {
      loginPin.mockResolvedValue({ user: { id: 'u1', username: 'kiril' }, token: 't', expires_at: '' });
      const { useSystemStore } = await mount();
      const field = (await screen.findByLabelText("Ім'я")) as HTMLInputElement;
      fireEvent.change(field, { target: { value: '  @Kiril ' } });
      fireEvent.keyDown(field, { key: '7' });
      await typePin();
      expect(loginPin).toHaveBeenCalledTimes(1);
      expect(loginPin).toHaveBeenCalledWith('kiril', '111111');
      await vi.waitFor(() => expect(useSystemStore.getState().authenticated).toBe(true));
    });

    it('чуже імʼя і чужий PIN — один і той самий текст', async () => {
      for (const err of [new ApiError(401, 'PIN_INVALID', 'Invalid username or PIN'), new ApiError(403, 'PIN_DEFAULT_REMOTE_FORBIDDEN', 'x')]) {
        loginPin.mockReset();
        loginPin.mockRejectedValue(err);
        await mount();
        const field = await screen.findByLabelText("Ім'я");
        fireEvent.change(field, { target: { value: 'хтось' } });
        await typePin();
        expect(await screen.findByText("Не те ім'я або PIN.")).toBeTruthy();
        document.body.innerHTML = '';
      }
    });

    it('Enter у полі імені віддає клавіатуру PIN-у', async () => {
      await mount();
      const field = (await screen.findByLabelText("Ім'я")) as HTMLInputElement;
      field.focus();
      fireEvent.change(field, { target: { value: 'kiril' } });
      fireEvent.keyDown(field, { key: 'Enter' });
      expect(document.activeElement).not.toBe(field);
    });

    it('без імені PIN не набирається', async () => {
      await mount();
      await screen.findByLabelText("Ім'я");
      const one = screen.getByRole('button', { name: '1' }) as HTMLButtonElement;
      expect(one.disabled).toBe(true);
    });

    it('вузол мовчить на вході — форма лишається', async () => {
      loginPin.mockRejectedValue(new TypeError('fetch failed'));
      await mount();
      const field = await screen.findByLabelText("Ім'я");
      fireEvent.change(field, { target: { value: 'kiril' } });
      await typePin();
      expect(await screen.findByText('Nightpost не відповідає — спробуйте ще раз.')).toBeTruthy();
      expect((screen.getByLabelText("Ім'я") as HTMLInputElement).value).toBe('kiril');
    });
  });
});
