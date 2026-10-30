/**
 * R6 «Я» у вигляді D: обкладинка з горизонталями, аватар 96 на межі, кола з
 * лічильниками, «Мій код» (єдиний бурштин) + «Змінити», плитки 2×2 на raised.
 * Лічильники й значення плиток — лише з даних; без тіней.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { deviceApi } from '../services/deviceApi';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';
import { GroupMoneySheet } from '../components/messenger/solana/GroupMoneySheet';
import type { Chat } from '../types/messenger';
import { DeskSidePane } from '../components/messenger/DeskSidePane';
import { useUIStore } from '../stores/uiStore';
import { solanaApi } from '../services/solanaActions';

const dm = (id: string, title: string, circles: string[]): Chat =>
  ({ id: `c_${id}`, title, type: 'dm', peerNodeId: id, messages: [], unreadCount: 0, circle: circles[0] ?? 'all', circles }) as unknown as Chat;

const open = () =>
  render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);

let prevState: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prevState = useMessengerStore.getState();
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  useMessengerStore.setState({
    currentUser: { ...prevState.currentUser, name: 'Олена', status: '', bio: '', avatar: '' },
    chats: [dm('a', 'Софія', ['close']), dm('b', 'Тарас', ['close', 'friends']), dm('c', 'Марек', ['friends'])],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prevState, true);
});

describe('R6 «Я»: вигляд D', () => {
  it('обкладинка на токенах cover / cover-line з горизонталями, аватар 96 з кільцем тла', () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ devices: [] } as never);
    open();
    const cover = screen.getByTestId('me-cover');
    expect(cover.className).toMatch(/\bbg-cover\b/);
    expect(cover.querySelector('svg')?.getAttribute('class') ?? '').toMatch(/\btext-cover-line\b/);
    expect(cover.querySelectorAll('svg path').length).toBeGreaterThanOrEqual(8);
    const av = within(screen.getByTestId('me-photo')).getByText((_, el) => /w-\[96px\]/.test(el?.getAttribute('class') ?? ''), { selector: '*' });
    expect(av).toBeTruthy();
    expect(screen.getByTestId('me-photo').className).toMatch(/ring-4/);
    expect(screen.getByTestId('me-photo').className).toMatch(/ring-canvas/);
  });

  it('кола Близькі · Друзі · Робота з лічильниками з розмов', () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ devices: [] } as never);
    open();
    const chips = within(screen.getByTestId('me-circles')).getAllByTestId('me-circle');
    expect(chips.map((c) => c.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['Близькі 2', 'Друзі 2', 'Робота 0']);
    for (const c of chips) expect(c.className).toMatch(/rounded-full/);
  });

  it('нікого в колах — ряду кіл немає', () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ devices: [] } as never);
    useMessengerStore.setState({ chats: [] });
    open();
    expect(screen.queryByTestId('me-circles')).toBeNull();
  });

  it('«Мій код» — єдина бурштинова кнопка; поруч «Змінити», що відкриває профіль', async () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ devices: [] } as never);
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ bundle: { name: 'Олена' } } as never);
    open();
    const me = screen.getByTestId('me-screen');
    const code = screen.getByTestId('me-code');
    expect(code.className).toMatch(/\bbg-accent\b/);
    expect(code.textContent).toContain('Мій код');
    expect(me.querySelectorAll('button.bg-accent').length).toBe(1);
    const edit = screen.getByTestId('me-edit');
    expect(edit.textContent).toContain('Змінити');
    expect(edit.className).toMatch(/border-line-strong/);
    expect(screen.getByTestId('me-share')).toBeTruthy();
    fireEvent.click(edit);
    expect(await screen.findByTestId('profile-sheet')).toBeTruthy();
  });

  it('плитки: Вигляд · Приватність · Мої люди на всю ширину, значення з даних', async () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({
      devices: [
        { id: 'd1', kind: 'phone', name: 'A52', online: true },
        { id: 'd2', kind: 'pc', name: 'Ноут', online: false },
      ],
    } as never);
    vi.spyOn(deviceApi, 'ladder').mockRejectedValue(new Error('no'));
    vi.spyOn(deviceApi, 'journal').mockRejectedValue(new Error('no'));
    open();
    const grid = screen.getByTestId('me-tiles');
    expect(grid.className).toMatch(/grid-cols-2/);
    const tiles = within(grid).getAllByTestId(/^me-tile-/);
    expect(tiles.map((t) => t.getAttribute('data-testid'))).toEqual(['me-tile-look', 'me-tile-privacy', 'me-tile-people']);
    for (const t of tiles) {
      expect(t.className).toMatch(/\bbg-raised\b/);
      expect(t.className).toMatch(/rounded-\[16px\]/);
    }
    expect(within(tiles[0]).getByText('Вигляд')).toBeTruthy();
    expect(within(tiles[0]).getByText('За сонцем')).toBeTruthy();
    expect(within(tiles[2]).getByText('Мої люди')).toBeTruthy();
    expect(tiles[2].className).toMatch(/col-span-2/);
    expect(tiles[2].textContent).toMatch(/3 люди/);
    expect(await screen.findByTestId('me-devices')).toBeTruthy();
  });

  it('плитки «Пристрої», що гортала до розділу під собою, немає; жодних тіней', () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue({ devices: [] } as never);
    open();
    expect(screen.queryByTestId('me-tile-devices')).toBeNull();
    expect(screen.getByTestId('me-screen').innerHTML).not.toMatch(/shadow-|blur-|gradient/);
  });
});

const amber = (root: Element) =>
  Array.from(root.querySelectorAll('*')).filter((el) =>
    (el.getAttribute('class') ?? '').split(/\s+/).some((c) => /^(bg-accent|outline-accent-line|border-accent|border-accent-line)$/.test(c)),
  );

describe('R6 гроші: аркуші у вигляді D', () => {
  it('переказ: монети — чипи D (обрана на overlay, решта контуром), поле на raised, бурштин лише в кнопці', () => {
    render(<PayMoneySheet peer={{ id: 'p', name: 'Olena' }} cluster="devnet" onClose={() => {}} />);
    const sheet = screen.getByTestId('solana-pay-sheet');
    const on = screen.getByTestId('solana-coin-USDC');
    const off = screen.getByTestId('solana-coin-SOL');
    expect(on.className).toMatch(/\bbg-overlay\b/);
    expect(on.className).toMatch(/\brounded-chip\b/);
    expect(off.className).toMatch(/\bborder-line-strong\b/);
    expect(off.className).not.toMatch(/\bbg-overlay\b/);
    expect(screen.getByTestId('solana-amount-input').className).toMatch(/\bbg-raised\b/);
    // Головна дія — єдиний бурштин; поки неактивна (порожня сума) — нейтральна (LOOK-2910).
    expect(amber(sheet).filter((el) => el.tagName !== 'BUTTON')).toEqual([]);
    expect(amber(sheet).length).toBeLessThanOrEqual(1);
  });

  it('запит у групі: позначки чорнилом, не бурштином; поля на raised', () => {
    useMessengerStore.setState({
      currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Я' },
      chats: [{ id: 'g1', title: 'Група', type: 'group', messages: [], members: [
        { id: 'me', name: 'Я', role: 'owner', isMe: true },
        { id: 'b', name: 'Богдан', role: 'member' },
        { id: 'c', name: 'Василь', role: 'member' },
      ] } as unknown as Chat],
    });
    render(<GroupMoneySheet chatId="g1" onClose={() => {}} />);
    fireEvent.click(within(screen.getByTestId('group-money-request')).getByRole('button'));
    const sheet = screen.getByTestId('group-money-sheet');
    const box = screen.getByTestId('group-request-check-b');
    expect(box.getAttribute('aria-checked')).toBe('true');
    expect(box.className).toMatch(/\bbg-ink\b/);
    expect(screen.getByTestId('group-request-amount-b').className).toMatch(/\bbg-raised\b/);
    // Головна дія — єдиний бурштин; поки неактивна (порожня сума) — нейтральна (LOOK-2910).
    expect(amber(sheet).filter((el) => el.tagName !== 'BUTTON')).toEqual([]);
    expect(amber(sheet).length).toBeLessThanOrEqual(1);
  });
});

describe('R6 гаманець на ПК: колонка у вигляді D', () => {
  it('з переказом: картка 16 px, мережа тегом-контуром, одна бурштинова кнопка', async () => {
    vi.spyOn(solanaApi, 'peer').mockResolvedValue({ address: 'Adr1', can_pay: true, cluster: 'devnet', seeker: false });
    useUIStore.setState({ deskPane: 'wallet' });
    render(<DeskSidePane activeChat={dm('a', 'Софія', ['close'])} />);
    const pay = await screen.findByTestId('desk-pane-wallet-pay');
    const pane = screen.getByTestId('desk-pane-wallet');
    expect(pay.className).toMatch(/rounded-\[16px\]/);
    expect(pane.querySelector('.bg-raised')?.className ?? '').toMatch(/rounded-\[16px\]/);
    const tag = screen.getByTestId('desk-pane-wallet-cluster');
    expect(tag.className).toMatch(/\bborder-line-strong\b/);
    expect(tag.className).toMatch(/\brounded-chip\b/);
    expect(amber(pane).length).toBe(1);
  });

  it('порожньо: значок без плитки, тихий рядок ink-3', () => {
    useUIStore.setState({ deskPane: 'wallet' });
    render(<DeskSidePane activeChat={undefined} />);
    const empty = screen.getByTestId('desk-pane-wallet-empty');
    expect(empty.querySelector('.bg-raised')).toBeNull();
    expect(empty.querySelector('p')?.className ?? '').toMatch(/\btext-ink-3\b/);
  });
});
