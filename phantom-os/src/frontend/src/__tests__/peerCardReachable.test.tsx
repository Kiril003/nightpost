/**
 * Картку співрозмовника видно з продукту — і вона каже правду вузла.
 *
 * Причина існування цього файла. У `UserProfileModal` півтори сотні рядків
 * «Картки контакту» простояли за пропом `viewingMember`, якого єдиний монтаж у
 * продукті ніколи не передавав. Зелені тести це не ловили: один доводив, що
 * модуль імпортовано (`reachFromEntry`), другий підставляв проп сам
 * (`callPhoneGate`). Тобто перевіряли гілку, до якої немає дверей.
 *
 * Тому тут монтується САМЕ ТЕ, що монтує застосунок — `MessengerRoot` — і
 * жодного пропа про людину ззовні не подається: розмова приходить із вузла
 * через `bootstrap`, а картку відкриває той самий дотик, що й палець власника.
 *
 * Червоніти вміє: заберіть `openSheet` з дотику до назви в `Header` — падає
 * перший тест; поверніть `list.find(...)` замість «тривога виграє» — падає
 * другий; поверніть `catch { setState('none') }` — падає третій.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact, NodeConversation } from '../services/messengerApi';

const PEER = 'a1b2c3d4e5f60718';

const CONVERSATION: NodeConversation = {
  id: 'conv_marta',
  title: 'Марта',
  kind: 'dm',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-09-04T10:00:00',
  updated_at: '2026-09-04T10:00:00',
  peer_node_id: PEER,
};

const contact = (over: Partial<NodeContact>): NodeContact => ({
  id: 'ct_1',
  peer_node_id: PEER,
  display_name: 'Марта',
  peer_address: null,
  safety_number: '111112222233333444445555566666',
  safety_number_pretty: '11111 22222 33333 44444 55555 66666',
  verified: false,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  ...over,
});

/** Той самий шлях, що й у застосунку: розмова приходить із вузла. */
const openMessenger = async () => {
  render(
    <MemoryRouter>
      <MessengerRoot />
    </MemoryRouter>,
  );
  const title = await screen.findByTitle('Профіль');
  fireEvent.click(title);
  return screen.findByRole('dialog', { name: 'Профіль: Марта' });
};

beforeEach(() => {
  // jsdom не вміє прокручувати, а стрічка з листами прокручується до низу.
  Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([CONVERSATION]);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const TECH = /шифруван|ключ|відбит|вузол|звірено з екрана|@[0-9a-f]{4}|сховок|напряму/i;
const sheetText = () => screen.getByTestId('peer-profile').textContent ?? '';

describe('двері в картку співрозмовника', () => {
  it('дотик до назви відкриває профіль — без механіки перед очима; перевірка — окремими дверима', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ verified: true, verified_by_camera: true })]);
    await openMessenger();
    const door = await screen.findByText('Особу перевірено');
    expect(sheetText()).not.toMatch(TECH);
    fireEvent.click(door);
    // Числа — лише в свідомо відкритій перевірці, і вони з вузла.
    expect(screen.getByTestId('contact-fingerprint').textContent).toContain('11');
  });

  it('при двох рядках на один вузол виграє тривога, а не старе «перевірено»', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      contact({ id: 'ct_old', verified: true }),
      contact({ id: 'ct_new', verified: false, key_changed: true }),
    ]);
    await openMessenger();
    expect(await screen.findByTestId('peer-profile-key-changed')).toBeTruthy();
    expect(screen.queryByText('Особу перевірено')).toBeNull();
  });

  it('«перевірено» не переживає зміни телефона навіть в одному рядку', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ verified: true, verified_by_camera: true, key_changed: true })]);
    await openMessenger();
    expect(await screen.findByTestId('peer-profile-key-changed')).toBeTruthy();
    expect(screen.queryByText('Особу перевірено')).toBeNull();
  });

  it('вузол мовчить — людина не бачить ні тривоги, ні технічних слів', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockRejectedValue(new Error('вузол мовчить'));
    await openMessenger();
    await waitFor(() => expect(sheetText()).toContain('Марта'));
    expect(sheetText()).not.toMatch(TECH);
    expect(screen.queryByTestId('peer-profile-key-changed')).toBeNull();
  });
});

describe('профіль людини — сама людина, а не лише ключ', () => {
  it('фото, статус і «про себе», що приїхали кадром, стоять на екрані', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      contact({
        profile_rev: 3,
        profile_at: '2026-09-25T10:00:00Z',
        avatar_state: 'set',
        avatar_thumb: 'data:image/webp;base64,UklGRg==',
        status_emoji_state: 'set',
        status_emoji: '🌿',
        status_text_state: 'set',
        status_text: 'на дачі до неділі',
        bio_state: 'set',
        bio: 'Фотографую мости',
      }),
    ]);
    await openMessenger();
    expect(await screen.findByTestId('peer-profile-bio')).toHaveProperty('textContent', 'Фотографую мости');
    expect(screen.getByTestId('peer-profile-status').textContent).toBe('🌿 на дачі до неділі');
    const img = screen.getByTestId('peer-profile-photo').querySelector('img');
    expect(img?.getAttribute('src')).toBe('data:image/webp;base64,UklGRg==');
  });

  it('опису немає — порожньої картки «Про себе» теж немає', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({})]);
    await openMessenger();
    await screen.findByText('Особу перевірено').catch(() => null);
    await waitFor(() => expect(sheetText()).toContain('Марта'));
    expect(screen.queryByText('Про себе')).toBeNull();
  });

  it('«Спільне» рахує вкладки з самої розмови', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({})]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([
      {
        id: 'm1', conversation_id: 'conv_marta', client_id: 'c1', seq: 1, author_id: PEER, author_name: 'Марта',
        kind: 'text', body: 'ось https://phantom-os.dev/download', ciphertext: null, transport: 'p2p',
        sent_at: '2026-09-25T09:00:00', edited_at: null, deleted_at: null,
      },
    ]);
    await openMessenger();
    // Вкладки спільного, як у рендері p-info: перша непорожня вже відкрита.
    const tab = await screen.findByTestId('info-tab-links');
    expect(tab.textContent).toContain('1');
    expect(tab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText('phantom-os.dev')).toBeTruthy();
    // Порожніх вкладок немає: «Медіа 0» не малюється поруч із тим, що є.
    expect(screen.queryByTestId('info-tab-media')).toBeNull();
  });

  it('спільного немає — розділу немає, а не рядок із нулем', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({})]);
    await openMessenger();
    await waitFor(() => expect(sheetText()).toContain('Марта'));
    expect(screen.queryByTestId('peer-profile-shared')).toBeNull();
    expect(within(screen.getByTestId('peer-profile')).queryByRole('tab')).toBeNull();
  });

  it('«Заблокувати» питає згоди й кличе вузол; відповідь вузла стає станом', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: false })]);
    const block = vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });
    await openMessenger();
    fireEvent.click(await screen.findByRole('button', { name: 'Заблокувати' }));
    expect(block).not.toHaveBeenCalled();
    const confirm = screen.getByTestId('peer-profile-confirm');
    fireEvent.click(confirm.querySelector('button')!);
    await waitFor(() => expect(block).toHaveBeenCalledWith(PEER));
    expect(await screen.findByTestId('person-info-blocked')).toBeTruthy();
    expect(screen.getByTestId('person-state-unblock').textContent).toBe('Розблокувати');
  });

  it('«Поскаржитися» відкриває аркуш скарги на людину', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: false })]);
    await openMessenger();
    fireEvent.click(await screen.findByRole('button', { name: 'Поскаржитися' }));
    expect(await screen.findByTestId('report-reason-spam')).toBeTruthy();
  });
});
