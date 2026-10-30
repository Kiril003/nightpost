/**
 * Сторож запису на диск.
 *
 * Стереже дві вади, знайдені разом і однаково мовчазні.
 *
 * ПЕРША — «збережено» без збереження. Сім викликів `storagePersistence.save*`
 * стояли голими промісами, а сам шар ловив кожну помилку в
 * `catch { console.warn(...) }` і повертав успішний проміс. Тобто повністю
 * переповнене сховище виглядало як вдалий запис, і людина бачила розмову
 * збереженою, коли на диску не було нічого. Додати `await` було б замало:
 * відмови в проміс ніхто не клав.
 *
 * ДРУГА — ключ, у який ніхто не читає. Писар клав чати в `phantom_chats_backup`,
 * а стор на старті читав їх через `loadSetting('phantom_chats_backup')`, тобто
 * з `phantom_setting_phantom_chats_backup`. Збережені розмови не поверталися
 * після перезапуску ЖОДНОГО разу — і жоден зелений тест цього не бачив, бо
 * ніхто не міряв круг «записав → перезапустив → прочитав».
 *
 * Мутації, які мусять червоніти:
 *   1. прибрати `throw` у `writeLocal` (повернути `console.warn`) — падають
 *      тести про речення й про глас;
 *   2. повернути `loadSetting(...)` у старт стора — падає тест про круг;
 *   3. прибрати `set({ storageFailure: ... })` з `persist` — падають обидва
 *      тести про те, що стан не бреше.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';

import { useMessengerStore } from '../stores/messengerStore';
import {
  storagePersistence,
  StorageWriteError,
  KEYS,
} from '../services/storagePersistence';
import { MessageComposer } from '../components/messenger/MessageComposer';
import type { Chat } from '../types/messenger';

const chat = (): Chat => ({
  id: 'chat_guard',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'work',
  unreadCount: 0,
  messages: [],
});

/** Сховище, яке відмовляє так, як відмовляє справжнє: переповненням. */
const quotaError = () => {
  const e = new Error('QuotaExceededError');
  e.name = 'QuotaExceededError';
  return new StorageWriteError('chats', e);
};

const composerProps = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'chat_guard',
};

beforeEach(() => {
  localStorage.clear();
  useMessengerStore.setState({
    chats: [chat()],
    activeChatId: 'chat_guard',
    storageFailure: null,
    replyingTo: null,
    editingMessage: null,
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

describe('запис, який не вдався, не вдає вдалий', () => {
  it('сховище відмовило — у сторі лишається речення, а не тиша', async () => {
    vi.spyOn(storagePersistence, 'saveChats').mockRejectedValue(quotaError());

    await useMessengerStore.getState().sendMessage('Буду о шостій');

    const said = useMessengerStore.getState().storageFailure;
    expect(said, 'відмова запису не лишила по собі жодного речення').toBeTruthy();
    // Речення мусить називати і факт, і наслідок для написаного — інакше воно
    // повідомляє про подію в програмі, а не про те, що втрачає людина.
    expect(said).toContain('не збережено');
    expect(said).toContain('зникне');
  });

  it('сховище відмовило — людина бачить речення там, де писала', async () => {
    vi.spyOn(storagePersistence, 'saveChats').mockRejectedValue(quotaError());

    await useMessengerStore.getState().sendMessage('Буду о шостій');
    render(<MessageComposer {...composerProps} />);

    const banner = screen.getByTestId('storage-failure');
    expect(banner).toBeInTheDocument();
    expect(banner.textContent).toContain('не збережено');
  });

  it('поки запис не вдався — стан не показує розмову збереженою', async () => {
    vi.spyOn(storagePersistence, 'saveChats').mockRejectedValue(quotaError());
    await useMessengerStore.getState().sendMessage('Перше');
    expect(useMessengerStore.getState().storageFailure).toBeTruthy();

    // ...і знімається лише СПРАВЖНІМ успішним записом, а не часом чи
    // перемальовуванням: інакше напис був би декорацією.
    vi.spyOn(storagePersistence, 'saveChats').mockResolvedValue(undefined);
    await useMessengerStore.getState().sendMessage('Друге');
    expect(useMessengerStore.getState().storageFailure).toBeNull();
  });

  it('успішний запис не лишає напису про відмову', async () => {
    vi.spyOn(storagePersistence, 'saveChats').mockResolvedValue(undefined);
    await useMessengerStore.getState().sendMessage('Все добре');

    expect(useMessengerStore.getState().storageFailure).toBeNull();
    render(<MessageComposer {...composerProps} />);
    expect(screen.queryByTestId('storage-failure')).toBeNull();
  });
});

describe('шар сховища каже правду про запис', () => {
  it('переповнене сховище — saveChats кидає, а не мовчить', async () => {
    // Шпигун — туди, де метод справді живе. У jsdom це прототип Storage:
    // присвоєння властивості екземпляру кладе ЕЛЕМЕНТ «setItem», і метод
    // лишався справжнім. А на Node 22+ без --localstorage-file власний
    // localStorage Node порожній, і test-setup.ts ставить замість нього
    // простий обʼєкт із setItem як ВЛАСНОЮ властивістю — шпигун на
    // Storage.prototype там не перехоплював нічого, і тест червонів, хоч код
    // кидає правильно.
    const owner: Storage = Object.prototype.hasOwnProperty.call(localStorage, 'setItem')
      ? localStorage
      : (Object.getPrototypeOf(localStorage) as Storage);
    vi.spyOn(owner, 'setItem').mockImplementation(() => {
      const e = new Error('quota');
      e.name = 'QuotaExceededError';
      throw e;
    });

    await expect(storagePersistence.saveChats([chat()])).rejects.toBeInstanceOf(
      StorageWriteError,
    );
  });

  it('переповнення розпізнається як переповнення, а не як «щось пішло не так»', () => {
    const e = new Error('quota');
    e.name = 'QuotaExceededError';
    expect(new StorageWriteError('chats', e).isQuota).toBe(true);
    expect(new StorageWriteError('chats', new Error('інше')).isQuota).toBe(false);
  });
});

describe('круг: записав → перезапустив → прочитав', () => {
  it('те, що поклав saveChats, читається тим самим ключем', async () => {
    await storagePersistence.saveChats([chat()]);

    expect(localStorage.getItem(KEYS.chats), 'писар поклав не туди').toBeTruthy();
    const back = storagePersistence.getChatsSync();
    expect(back?.[0]?.id).toBe('chat_guard');
  });

  it('свіжий старт стора піднімає збережені розмови, а не початкову сівбу', async () => {
    await storagePersistence.saveChats([{ ...chat(), title: 'Розмова власника' }]);

    // Перезапуск застосунку: модулі читаються наново, стор будується з нуля.
    vi.resetModules();
    const fresh = await import('../stores/messengerStore');
    const titles = fresh.useMessengerStore.getState().chats.map((c) => c.title);

    expect(
      titles,
      'збережена розмова не повернулась після перезапуску — писар і читач розійшлись у ключі',
    ).toContain('Розмова власника');
  });
});
