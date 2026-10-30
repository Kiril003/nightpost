/**
 * LOOK-2910, створення групи/каналу/простору як у Telegram: до назви — плитка
 * зі значком, не «Г» з підказки; вибрані люди — смужка над списком, тап прибирає;
 * неактивна головна дія — нейтральна, не вицвілий бурштин; простір — та сама
 * рядок-назва з підказкою і дія «Додати людей» замість «Нікого».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { SpaceCreate } from '../components/messenger/spaces/SpaceCreate';
import { MemberActions } from '../components/messenger/GroupRoles';
import { SwitchRow } from '../components/messenger/GroupSettingsPanel';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, ChatMember } from '../types/messenger';

vi.mock('../services/stripMetadata', () => ({ stripMetadata: async (f: File) => f }));

const OKSANA = { id: 'ct_o', display_name: 'Оксана Мельник', peer_node_id: 'p2', session_ready: true, reach: 'mutual' } as unknown as NodeContact;
const MARKO = { id: 'ct_m', display_name: 'Марко', peer_node_id: 'p3', session_ready: true, reach: 'mutual' } as unknown as NodeContact;

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

const openGroup = async () => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([OKSANA, MARKO]);
  render(
    <MemoryRouter>
      <CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByTestId('door-group'));
  return screen.findByTestId('new-group-sheet');
};

describe('нова група', () => {
  it('до назви — плитка зі значком групи, без літери з підказки; з назвою — монограма', async () => {
    const sheet = await openGroup();
    const tile = within(sheet).getByTestId('new-group-tile');
    expect(tile.textContent).toBe('');
    expect(tile.querySelector('svg')).toBeTruthy();
    fireEvent.change(within(sheet).getByRole('textbox', { name: 'Назва групи' }), { target: { value: 'Сусіди' } });
    expect(within(sheet).queryByTestId('new-group-tile')).toBeNull();
    expect(sheet.textContent).toContain('С');
  });

  it('вибрані — смужка з іменами над списком; тап по ній прибирає людину', async () => {
    const sheet = await openGroup();
    expect(within(sheet).queryByTestId('picked-strip')).toBeNull();
    fireEvent.click(await within(sheet).findByText('Оксана Мельник'));
    fireEvent.click(within(sheet).getByText('Марко'));
    const strip = within(sheet).getByTestId('picked-strip');
    expect(strip.querySelectorAll('li')).toHaveLength(2);
    expect(strip.textContent).toContain('Оксана');
    expect(strip.textContent).not.toContain('Мельник');
    fireEvent.click(within(strip).getByRole('button', { name: 'Прибрати Оксана Мельник' }));
    expect(within(sheet).getByTestId('picked-strip').querySelectorAll('li')).toHaveLength(1);
    const row = within(sheet).getByRole('checkbox', { name: /Оксана Мельник/ });
    expect(row.getAttribute('aria-checked')).toBe('false');
  });

  it('неактивне «Створити» — нейтральне, не вицвілий бурштин', async () => {
    const sheet = await openGroup();
    const go = within(sheet.ownerDocument.body).getByRole('button', { name: 'Створити' }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    expect(go.className).not.toMatch(/\bbg-accent\b/);
    expect(go.className).not.toMatch(/\bopacity-40\b/);
    expect(go.className).toMatch(/\bbg-overlay\b/);
  });
});

describe('нова група — пошук, один бурштин, фото', () => {
  it('пошук над смужкою: фільтрує за імʼям і чесно каже, коли нікого', async () => {
    const sheet = await openGroup();
    const find = await within(sheet).findByRole('textbox', { name: 'Знайти серед ваших людей' });
    fireEvent.change(find, { target: { value: 'мар' } });
    expect(within(sheet).queryByText('Оксана Мельник')).toBeNull();
    expect(within(sheet).getByText('Марко')).toBeTruthy();
    fireEvent.change(find, { target: { value: 'zzz' } });
    expect(within(sheet).getByText('Серед ваших людей такого імені немає')).toBeTruthy();
  });

  it('позначка вибраного — чорнилом, лічильник — ink-2: бурштин лише на «Створити»', async () => {
    const sheet = await openGroup();
    fireEvent.click(await within(sheet).findByText('Марко'));
    const box = within(sheet).getByRole('checkbox', { name: /Марко/ }).querySelector('[aria-hidden="true"]:last-child') as HTMLElement;
    expect(box.className).toMatch(/\bbg-ink\b/);
    expect(box.className).not.toMatch(/\bbg-accent\b/);
    expect(sheet.querySelector('.text-accent-text')).toBeNull();
  });

  it('плитка групи — кнопка фото зі значком камери; вибране фото їде в групу після створення', async () => {
    const createGroup = vi.fn().mockResolvedValue('g-new');
    const setGroupFace = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ createGroup, setGroupFace } as never);
    const sheet = await openGroup();
    const face = within(sheet).getByRole('button', { name: 'Додати фото' });
    expect(face.querySelector('.lucide-camera')).toBeTruthy();
    const input = within(sheet).getByTestId('new-group-face-input') as HTMLInputElement;
    const click = vi.spyOn(input, 'click');
    fireEvent.click(face);
    expect(click).toHaveBeenCalled();
    fireEvent.change(input, { target: { files: [new File(['x'], 'a.png', { type: 'image/png' })] } });
    await waitFor(() => expect(face.querySelector('img')).toBeTruthy());
    fireEvent.change(within(sheet).getByRole('textbox', { name: 'Назва групи' }), { target: { value: 'Сусіди' } });
    fireEvent.click(await within(sheet).findByText('Марко'));
    fireEvent.click(within(sheet.ownerDocument.body).getByRole('button', { name: 'Створити' }));
    await waitFor(() => expect(setGroupFace).toHaveBeenCalledWith('g-new', expect.stringMatching(/^data:image\/png/)));
  });
});

describe('склад: дії над учасником', () => {
  const g = { id: 'g1', title: 'Двір', type: 'group', messages: [] } as unknown as Chat;
  const m = { id: 'p2', name: 'Оксана', role: 'admin', mayGrant: true, mayRemove: true } as unknown as ChatMember;

  it('роль — сегменти одним полем; обрана чорнилом, не бурштином', () => {
    render(<MemberActions chat={g} member={m} />);
    const seg = screen.getByTestId('role-segments');
    expect(seg.className).toMatch(/\brounded-full\b/);
    const on = within(seg).getByRole('button', { pressed: true });
    expect(on.className).toMatch(/\bbg-ink\b/);
    expect(seg.querySelector('.bg-accent')).toBeNull();
  });

  it('перемикач прав — чорнильна доріжка, не бурштин', () => {
    render(<SwitchRow label="Закріплювати" on onToggle={() => {}} />);
    const track = screen.getByRole('switch').querySelector('[aria-hidden="true"]') as HTMLElement;
    expect(track.className).toMatch(/\bbg-ink\b/);
    expect(track.className).not.toMatch(/\bbg-accent\b/);
  });
});

describe('новий простір', () => {
  it('назва — плитка шаблону й підказка; люди — дія «Додати людей»; неактивна дія нейтральна', () => {
    render(
      <MemoryRouter>
        <SpaceCreate />
      </MemoryRouter>,
    );
    const input = screen.getByTestId('space-name') as HTMLInputElement;
    expect(input.placeholder).toBe('Назва простору');
    expect(input.getAttribute('aria-label')).toBe('Назва');
    expect(screen.getByTestId('space-name-tile').querySelector('svg')).toBeTruthy();
    const people = screen.getByTestId('space-people-row');
    expect(people.textContent).toBe('Додати людей');
    expect(people.textContent).not.toContain('Нікого');
    const go = screen.getByTestId('space-create-go');
    expect(go.className).not.toMatch(/disabled:opacity-50/);
    expect(go.className).toMatch(/disabled:bg-overlay/);
  });
});
