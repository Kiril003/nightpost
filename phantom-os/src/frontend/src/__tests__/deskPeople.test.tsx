/**
 * Налаштування → Люди (26.09): власник додає адміна чи гостя з PIN, міняє
 * роль, прибирає людину. До того ручки /users були, екрана — ні.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { peopleApi } from '../services/api';
import { useAuthStore } from '../stores/authStore';
import { useSettingsStore } from '../stores/settingsStore';
import { DeskSettings } from '../components/shell/DeskSettings';

const person = (id: string, role: string, name: string, username = id) =>
  ({ id, username, role, preferences: { display_name: name } }) as never;

const OWNER = person('u-owner', 'ROOT', 'Кирило', 'phantom');
const mount = () => render(<MemoryRouter><DeskSettings /></MemoryRouter>);

beforeEach(() => {
  useSettingsStore.setState({ values: {}, loaded: true });
  useAuthStore.setState({ user: OWNER });
});
afterEach(() => vi.restoreAllMocks());

describe('Люди', () => {
  it('власник бачить людей словами ролей; себе не прибирає', async () => {
    vi.spyOn(peopleApi, 'list').mockResolvedValue({ users: [OWNER, person('u2', 'GUEST', 'Оксана')] });
    mount();
    expect(await screen.findByText('Кирило — це ви')).toBeTruthy();
    expect(screen.getByText('Власник')).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Гість' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getAllByRole('button', { name: 'Прибрати' })).toHaveLength(1);
  });

  it('додати адміна з PIN — ім\'я для входу з імені', async () => {
    vi.spyOn(peopleApi, 'list').mockResolvedValue({ users: [OWNER] });
    const add = vi.spyOn(peopleApi, 'add').mockResolvedValue(person('u3', 'OPERATOR', 'Taras'));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'Додати людину' }));
    fireEvent.change(screen.getByLabelText("Ім'я людини"), { target: { value: 'Taras Bondar' } });
    fireEvent.change(screen.getByLabelText('PIN людини'), { target: { value: '12a34' } });
    fireEvent.click(screen.getByRole('radio', { name: 'Адмін' }));
    fireEvent.click(screen.getByRole('button', { name: 'Додати' }));
    await waitFor(() =>
      expect(add).toHaveBeenCalledWith({ username: 'taras-bondar', display_name: 'Taras Bondar', role: 'OPERATOR', pin: '1234' }),
    );
  });

  it('роль і прибирання — з підтвердженням', async () => {
    vi.spyOn(peopleApi, 'list').mockResolvedValue({ users: [OWNER, person('u2', 'GUEST', 'Оксана')] });
    const setRole = vi.spyOn(peopleApi, 'setRole').mockResolvedValue(person('u2', 'OPERATOR', 'Оксана'));
    const remove = vi.spyOn(peopleApi, 'remove').mockResolvedValue(undefined);
    mount();
    fireEvent.click(await screen.findByRole('radio', { name: 'Адмін' }));
    await waitFor(() => expect(setRole).toHaveBeenCalledWith('u2', 'OPERATOR'));
    fireEvent.click(screen.getByRole('button', { name: 'Прибрати' }));
    expect(remove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Прибрати Оксана' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith('u2'));
  });

  it('не власник розділу «Люди» не бачить', async () => {
    const list = vi.spyOn(peopleApi, 'list');
    useAuthStore.setState({ user: person('u2', 'GUEST', 'Оксана') });
    mount();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('Люди')).toBeNull();
    expect(list).not.toHaveBeenCalled();
  });
});

describe('імена людей', () => {
  it('українське ім\'я → логін латинкою, не «person-…»', async () => {
    const { translit } = await import('../components/shell/DeskSettings');
    expect(translit('оксана')).toBe('oksana');
    expect(translit('юрій щербак')).toBe('iurii shcherbak');
  });
});

describe('гість у Налаштуваннях', () => {
  it('бачить своє «Я», але не перемикачі вузла, яких не змінить', () => {
    useSettingsStore.setState({ values: { ai_response_language: 'auto', voice_mode: 'off' }, loaded: true });
    useAuthStore.setState({ user: person('u2', 'GUEST', 'Оксана') });
    render(<MemoryRouter><DeskSettings /></MemoryRouter>);
    expect(screen.getByText('Як до вас звертатись')).toBeTruthy();
    expect(screen.queryByText('Мова відповідей')).toBeNull();
    expect(screen.queryByText('Усі параметри')).toBeNull();
    expect(screen.getByText(/змінює власник або адмін/)).toBeTruthy();
  });
});
