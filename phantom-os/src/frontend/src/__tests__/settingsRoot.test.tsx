/**
 * Я › Налаштування: корінь із сімома розділами, перемикачі, що справді щось
 * вирішують, «Що знає сервер» зі звіркою з кодом, сховище з підтвердженням,
 * мова, про застосунок.
 */
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { messengerApi } from '../services/messengerApi';
import { supportApi } from '../services/supportApi';
import { notificationPrefs } from '../services/notificationPrefs';
import * as mediaCache from '../services/mediaStorageCache';
import { useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { parseBundleSource } from '../services/bundleInfo';
import { formatBytes } from '../services/storageUsage';
import { SERVER_KNOWS, ERASE_URL } from '../services/serverKnows';
import { languageSwitchable } from '../services/nativeLanguage';

const open = (onToggleSound = () => {}, sound = true) => {
  render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled={sound} onToggleSound={onToggleSound} onExportAllData={() => {}} />);
  fireEvent.click(screen.getByTestId('me-prefs'));
};

const go = async (row: string) => fireEvent.click(await screen.findByTestId(`settings-row-${row}`));

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(messengerApi, 'quiet').mockRejectedValue(new Error('no bridge'));
  vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: 'kyrylo', published: true });
  vi.spyOn(supportApi, 'listTickets').mockResolvedValue([]);
  vi.spyOn(mediaCache, 'mediaCacheSize').mockResolvedValue(3 * 1024 * 1024);
  Object.defineProperty(navigator, 'storage', { configurable: true, value: { estimate: async () => ({ usage: 48 * 1024 * 1024, quota: 1e9 }) } });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prev, true);
});

describe('налаштування: корінь і вхід із «Я»', () => {
  it('«Я» → «Налаштування» дає сім розділів у заданому порядку', async () => {
    open();
    await screen.findByTestId('settings-root');
    const ids = Array.from(document.querySelectorAll('[data-testid^="settings-row-"]')).map((e) => e.getAttribute('data-testid'));
    expect(ids).toEqual([
      'settings-row-notifications',
      'settings-row-privacy',
      'settings-row-look',
      'settings-row-language',
      'settings-row-storage',
      'settings-row-devices',
      'settings-row-about',
    ]);
  });

  it('плитки «Вигляд» і «Приватність» на «Я» відкривають свої підекрани', async () => {
    render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    fireEvent.click(screen.getByTestId('me-tile-look'));
    expect(await screen.findByTestId('me-theme')).toBeTruthy();
    cleanup();
    render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    fireEvent.click(screen.getByTestId('me-tile-privacy'));
    expect(await screen.findByTestId('settings-knows')).toBeTruthy();
  });
});

describe('налаштування: вигляд', () => {
  it('«Менше руху» ставить клас на html і пам\'ятає вибір', async () => {
    document.documentElement.classList.remove('reduce-motion');
    open();
    await go('look');
    fireEvent.click(await screen.findByLabelText('Менше руху'));
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(true);
    expect(localStorage.getItem('ph.reduceMotion')).toBe('1');
    fireEvent.click(screen.getByLabelText('Менше руху'));
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(false);
    expect(localStorage.getItem('ph.reduceMotion')).toBeNull();
  });
});

describe('налаштування: сповіщення', () => {
  it('звук повідомлень кличе перемикач застосунку', async () => {
    const toggle = vi.fn();
    open(toggle);
    await go('notifications');
    fireEvent.click(await screen.findByLabelText('Звук повідомлень'));
    expect(toggle).toHaveBeenCalledTimes(1);
  });

  it('банери дзвінків: без підтримки рядка немає, дозвіл заборонено — вимкнено й сказано чому', async () => {
    open();
    await go('notifications');
    await screen.findByTestId('settings-sound');
    expect(screen.queryByTestId('settings-call-banners')).toBeNull();
    cleanup();
    vi.stubGlobal('Notification', { permission: 'denied', requestPermission: async () => 'denied' });
    notificationPrefs.sync();
    open();
    await go('notifications');
    const row = await screen.findByTestId('settings-call-banners');
    expect(row.textContent).toContain('Заборонено в системі');
    expect(row.querySelector('[role="switch"]')).toBeNull();
  });

  it('банери дзвінків: дозвіл є — перемикач справді вмикає notificationPrefs', async () => {
    vi.stubGlobal('Notification', { permission: 'granted', requestPermission: async () => 'granted' });
    const en = vi.spyOn(notificationPrefs, 'enable');
    notificationPrefs.disable();
    open();
    await go('notifications');
    fireEvent.click(await screen.findByLabelText('Банери дзвінків'));
    expect(en).toHaveBeenCalled();
    await waitFor(() => expect(notificationPrefs.getSnapshot().effective).toBe(true));
    notificationPrefs.disable();
  });
});

describe('налаштування: що знає сервер', () => {
  it('нік і ключ показані, лише коли нік опубліковано; звернення рахуються', async () => {
    vi.spyOn(supportApi, 'listTickets').mockResolvedValue([{ id: 't1' } as never, { id: 't2' } as never]);
    open();
    await go('privacy');
    expect((await screen.findByTestId('settings-knows-handle')).textContent).toContain('@kyrylo');
    expect(screen.getByTestId('settings-knows-key')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('settings-knows-support').textContent).toContain('Звернень: 2'));
  });

  it('нік не опубліковано — ключа в переліку немає', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    open();
    await go('privacy');
    await waitFor(() => expect(screen.getByTestId('settings-knows-handle').textContent).toContain('Не опубліковано'));
    expect(screen.queryByTestId('settings-knows-key')).toBeNull();
  });

  it('на телефоні рядка звернень немає — міст не обслуговує /support', async () => {
    const list = vi.spyOn(supportApi, 'listTickets');
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {};
    open();
    await go('privacy');
    expect(await screen.findByTestId('settings-knows-handle')).toBeTruthy();
    expect(screen.queryByTestId('settings-knows-support')).toBeNull();
    expect(list).not.toHaveBeenCalled();
  });

  it('сервер не відповів — так і сказано, а не вигадане «немає»', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('x'));
    vi.spyOn(supportApi, 'listTickets').mockRejectedValue(new Error('x'));
    open();
    await go('privacy');
    await waitFor(() => expect(screen.getByTestId('settings-knows-support').textContent).toContain('Сервер не відповів'));
    expect(screen.getByTestId('settings-knows-handle').textContent).toContain('Сервер не відповів');
  });

  it('«Стерти акаунт» веде на сторінку видалення на сайті', async () => {
    const win = vi.spyOn(window, 'open').mockReturnValue(null);
    open();
    await go('privacy');
    fireEvent.click(await screen.findByTestId('settings-erase'));
    expect(win).toHaveBeenCalledWith(ERASE_URL, '_blank', 'noopener');
  });

  it('кожен рядок переліку має виклик у коді клієнта й таблицю на сервері', () => {
    const front = path.resolve(__dirname, '..');
    const erase = path.resolve(front, '../../../../platform-site/server/erase.py');
    const eraseSrc = fs.readFileSync(erase, 'utf8');
    for (const row of SERVER_KNOWS) {
      const src = fs.readFileSync(path.join(front, row.source.file), 'utf8');
      expect(src, `${row.id}: у ${row.source.file} немає ${row.source.path}`).toContain(row.source.path);
      expect(eraseSrc, `${row.id}: сервер не знає таблиці ${row.table}`).toContain(`"${row.table}"`);
    }
  });
});

describe('налаштування: сховище', () => {
  it('показує реальні розміри, а очищення кешу — лише після підтвердження', async () => {
    const clear = vi.spyOn(mediaCache, 'clearMediaCache').mockResolvedValue();
    open();
    await go('storage');
    expect((await screen.findByTestId('settings-storage-used')).textContent).toContain('48 МБ');
    expect(screen.getByTestId('settings-storage-media').textContent).toContain('3.0 МБ');
    fireEvent.click(screen.getByTestId('settings-storage-clear'));
    expect(clear).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Скасувати'));
    expect(clear).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('settings-storage-clear'));
    fireEvent.click(screen.getByTestId('settings-storage-do'));
    await waitFor(() => expect(clear).toHaveBeenCalledTimes(1));
  });

  it('браузер не каже розмір — рядка нема, а не нуль', async () => {
    Object.defineProperty(navigator, 'storage', { configurable: true, value: undefined });
    vi.spyOn(mediaCache, 'mediaCacheSize').mockResolvedValue(null);
    open();
    await go('storage');
    await screen.findByTestId('settings-storage');
    expect(screen.queryByTestId('settings-storage-used')).toBeNull();
    expect(screen.queryByTestId('settings-storage-clear')).toBeNull();
  });

  it('formatBytes', () => {
    expect(formatBytes(512)).toBe('512 Б');
    expect(formatBytes(2048)).toBe('2 КБ');
    expect(formatBytes(48 * 1024 * 1024)).toBe('48 МБ');
  });
});

describe('налаштування: мова', () => {
  it('у браузері перемикач ставить мову в сторі', async () => {
    const set = vi.spyOn(useSettingsStore.getState(), 'setLanguage').mockResolvedValue();
    open();
    await go('language');
    fireEvent.click((await screen.findAllByRole('radio')).find((r) => r.getAttribute('data-locale') === 'cs')!);
    expect(set).toHaveBeenCalledWith('cs');
  });

  it('у телефоні без setLanguage у мості перемикача немає: мова — значення, а не двері', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { getLanguage: () => 'uk' };
    expect(languageSwitchable()).toBe(false);
    open();
    const row = await screen.findByTestId('settings-row-language');
    expect(row.tagName).toBe('DIV');
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { getLanguage: () => 'uk', setLanguage: vi.fn() };
    expect(languageSwitchable()).toBe(true);
  });
});

describe('налаштування: про застосунок', () => {
  it('збірка береться з печатки бандла; без печатки рядка немає', async () => {
    const text = 'web_sha=695203ac52f2\nweb_short=695203ac\nsynced_at=2026-10-23T12:21:39+03:00\n';
    expect(parseBundleSource(text)?.short).toBe('695203ac');
    expect(parseBundleSource('<!doctype html>')).toBeNull();
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, text: async () => text })));
    open();
    await go('about');
    expect((await screen.findByTestId('settings-about-build')).textContent).toContain('695203ac');
    cleanup();
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, text: async () => '' })));
    open();
    await go('about');
    await screen.findByTestId('settings-about');
    expect(screen.queryByTestId('settings-about-build')).toBeNull();
  });

  it('умови й політика відкривають сайт, «Допомога» — центр допомоги', async () => {
    const win = vi.spyOn(window, 'open').mockReturnValue(null);
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, text: async () => '' })));
    open();
    await go('about');
    fireEvent.click(await screen.findByTestId('settings-about-legal'));
    expect(win).toHaveBeenCalledWith('https://phantom-os.dev/legal', '_blank', 'noopener');
    fireEvent.click(screen.getByTestId('settings-about-privacy'));
    expect(win).toHaveBeenCalledWith('https://phantom-os.dev/privacy', '_blank', 'noopener');
    fireEvent.click(screen.getByTestId('settings-about-help'));
    expect(await screen.findByRole('dialog', { name: 'Допомога' })).toBeTruthy();
  });
});

describe('налаштування: крок назад і видимий перемикач', () => {
  it('Escape / системне «Назад» на підсторінці повертає до кореня, а не закриває все', async () => {
    open();
    await go('look');
    expect(screen.queryByTestId('settings-root')).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(await screen.findByTestId('settings-root')).toBeTruthy();
  });

  it('вимкнений перемикач має доріжку, видну на картці', async () => {
    open();
    await go('look');
    const off = Array.from(document.querySelectorAll('[role="switch"][aria-checked="false"]'));
    expect(off.length).toBeGreaterThan(0);
    for (const sw of off) expect(sw.firstElementChild?.className).not.toMatch(/\bbg-raised\b/);
  });
});

