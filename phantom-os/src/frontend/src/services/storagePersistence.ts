/**
 * PHANTOM OS — Unified Local Storage & IndexedDB Persistence Layer
 * Забезпечує збереження чатів, повідомлень, профілів та налаштувань між сесіями.
 *
 * Два правила цього файлу, обидва куплені знайденими вадами.
 *
 * 1. ЗАПИС, ЩО ВПАВ, КРИЧИТЬ. Раніше кожен `save*` ловив свою помилку в
 *    `catch { console.warn(...) }` і повертав успішний проміс. Це означало, що
 *    навіть повністю переповнене сховище виглядало як успішний запис: додати
 *    `await` на місці виклику не змінило б НІЧОГО, бо відмови в проміс ніхто
 *    не клав. Тепер `save*` кидає — і викликач мусить сказати про це людині.
 *
 * 2. КЛЮЧ — ОДИН НА ЧИТАЧА Й ПИСАРЯ. Ключі лежать у `KEYS` і більше ніде.
 *    Раніше писар клав чати в `phantom_chats_backup`, а стор читав їх через
 *    `loadSetting('phantom_chats_backup')`, тобто з `phantom_setting_phantom_chats_backup`
 *    — ключа, якого ніхто ніколи не писав. Збережені розмови не читались назад
 *    жодного разу; сторож `storageKeys.test.ts` тепер тримає цю пару разом.
 */

import { Chat, UserProfile, ScheduledMessage } from '../types/messenger';
import { DRAFTS_KEY } from './draftVault';

const DB_NAME = 'phantom_messenger_vault';
const DB_VERSION = 1;

/** Єдине місце, де живуть імена ключів. Читач і писар беруть їх звідси. */
export const KEYS = {
  chats: 'phantom_chats_backup',
  userProfile: 'phantom_user_profile',
  scheduled: 'phantom_scheduled_messages',
} as const;

/**
 * Відмова запису з реченням, яке можна показати людині.
 *
 * `reason` — те, що сказав браузер, коли він сказав щось осмислене. Нічого не
 * вигадуємо: якщо причини немає, лишається тільки факт «не зберіг».
 */
export class StorageWriteError extends Error {
  constructor(
    public readonly what: 'chats' | 'userProfile' | 'scheduled',
    public readonly cause: unknown,
  ) {
    super(`[Storage] write «${what}» failed`);
    this.name = 'StorageWriteError';
  }

  /** Чи це переповнене сховище — єдина причина, яку браузер називає надійно. */
  public get isQuota(): boolean {
    const e = this.cause;
    if (typeof DOMException !== 'undefined' && e instanceof DOMException) {
      return e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED';
    }
    return Boolean(e && typeof e === 'object' && (e as { name?: string }).name === 'QuotaExceededError');
  }
}

class StoragePersistence {
  private db: IDBDatabase | null = null;
  private isInitPromise: Promise<void> | null = null;

  constructor() {
    if (typeof window !== 'undefined' && 'indexedDB' in window) {
      this.isInitPromise = this.initDB();
    }
  }

  private async initDB(): Promise<void> {
    return new Promise((resolve) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = (event: IDBVersionChangeEvent) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains('chats')) {
          db.createObjectStore('chats', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('messages')) {
          const msgStore = db.createObjectStore('messages', { keyPath: 'id' });
          msgStore.createIndex('chatId', 'chatId', { unique: false });
        }
        if (!db.objectStoreNames.contains('user_profile')) {
          db.createObjectStore('user_profile', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('scheduled')) {
          db.createObjectStore('scheduled', { keyPath: 'id' });
        }
      };

      request.onsuccess = (event) => {
        this.db = (event.target as IDBOpenDBRequest).result;
        resolve();
      };

      request.onerror = (event) => {
        console.warn('[Storage] IndexedDB open error, falling back to LocalStorage:', event);
        resolve();
      };
    });
  }

  public async ensureReady(): Promise<void> {
    if (this.isInitPromise) {
      await this.isInitPromise;
    }
  }

  /**
   * Головний запис. Саме його читає стор на старті, тому саме його відмова
   * означає «написане не збережено» — і саме вона кидає.
   */
  private writeLocal(what: 'chats' | 'userProfile' | 'scheduled', key: string, value: unknown): void {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      throw new StorageWriteError(what, e);
    }
  }

  /**
   * Дзеркало в IndexedDB — не джерело правди, тож його відмова не є втратою:
   * байти вже лежать у localStorage, звідки їх читає старт. Але чекаємо на
   * `oncomplete`, а не на повернення з функції: раніше метод вважав запис
   * зробленим, поки транзакція ще навіть не почала комітитись.
   */
  private async mirrorToIdb(
    storeName: string,
    write: (store: IDBObjectStore) => void,
  ): Promise<void> {
    await this.ensureReady();
    if (!this.db) return;
    try {
      const tx = this.db.transaction(storeName, 'readwrite');
      write(tx.objectStore(storeName));
      await new Promise<void>((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
      });
    } catch (e) {
      // Свідомо не кидаємо: правда вже на диску в localStorage.
      console.warn(`[Storage] дзеркало «${storeName}» не оновилось:`, e);
    }
  }

  /* ─── Чати ─────────────────────────────────────────────────────────────── */

  public async saveChats(chats: Chat[]): Promise<void> {
    this.writeLocal('chats', KEYS.chats, chats);
    await this.mirrorToIdb('chats', (store) => {
      for (const chat of chats) store.put(chat);
    });
  }

  public async loadChats(): Promise<Chat[] | null> {
    try {
      await this.ensureReady();
      if (this.db) {
        return new Promise((resolve) => {
          const tx = this.db!.transaction('chats', 'readonly');
          const store = tx.objectStore('chats');
          const request = store.getAll();
          request.onsuccess = () => {
            if (request.result && request.result.length > 0) {
              resolve(request.result);
            } else {
              resolve(this.loadChatsFromLocalStorage());
            }
          };
          request.onerror = () => resolve(this.loadChatsFromLocalStorage());
        });
      }
      return this.loadChatsFromLocalStorage();
    } catch {
      return this.loadChatsFromLocalStorage();
    }
  }

  /**
   * Синхронне читання чатів на старті — той самий ключ, що й у `saveChats`.
   *
   * Стор раніше брав їх через `loadSetting('phantom_chats_backup')`, тобто з
   * `phantom_setting_phantom_chats_backup`. Такого ключа не писав ніхто, тож
   * жодна збережена розмова не поверталась після перезапуску.
   */
  public getChatsSync(): Chat[] | null {
    return this.loadChatsFromLocalStorage();
  }

  private loadChatsFromLocalStorage(): Chat[] | null {
    try {
      const raw = localStorage.getItem(KEYS.chats);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      }
    } catch {}
    return null;
  }

  public clearStorage(): void {
    try {
      localStorage.removeItem(KEYS.chats);
      localStorage.removeItem(KEYS.userProfile);
      localStorage.removeItem(DRAFTS_KEY);
      if (this.db) {
        const tx = this.db.transaction(['chats', 'messages'], 'readwrite');
        tx.objectStore('chats').clear();
        tx.objectStore('messages').clear();
      }
    } catch {}
  }

  /* ─── Профіль користувача ─────────────────────────────────────────────── */

  public async saveUserProfile(user: UserProfile): Promise<void> {
    this.writeLocal('userProfile', KEYS.userProfile, user);
    await this.mirrorToIdb('user_profile', (store) => store.put(user));
  }

  public getUserProfileSync(): UserProfile | null {
    try {
      const raw = localStorage.getItem(KEYS.userProfile);
      if (raw) return JSON.parse(raw);
    } catch {}
    return null;
  }

  public async loadUserProfile(): Promise<UserProfile | null> {
    return this.getUserProfileSync();
  }

  /* ─── Заплановані повідомлення ────────────────────────────────────────── */

  public async saveScheduledMessages(list: ScheduledMessage[]): Promise<void> {
    this.writeLocal('scheduled', KEYS.scheduled, list);
    await this.mirrorToIdb('scheduled', (store) => {
      store.clear();
      for (const item of list) store.put(item);
    });
  }

  /** Синхронне читання на старті — той самий ключ, що й у писаря. */
  public getScheduledSync(): ScheduledMessage[] | null {
    try {
      const raw = localStorage.getItem(KEYS.scheduled);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) return parsed;
      }
    } catch {}
    return null;
  }

  public async loadScheduledMessages(): Promise<ScheduledMessage[] | null> {
    return this.getScheduledSync();
  }

  /* ─── Загальні налаштування ────────────────────────────────────────────── */

  public saveSetting(key: string, value: any): void {
    try {
      localStorage.setItem(`phantom_setting_${key}`, JSON.stringify(value));
    } catch {}
  }

  public loadSetting<T>(key: string, defaultValue: T): T {
    try {
      const raw = localStorage.getItem(`phantom_setting_${key}`);
      if (raw !== null) return JSON.parse(raw);
    } catch {}
    return defaultValue;
  }

  /**
   * Реальний розмір того, що лежить у `localStorage` під ключами PHANTOM —
   * рахунок, не число з голови.
   *
   * У вкладці «Сховище» профілю раніше стояло `142 KB локально` — літерал,
   * який не мінявся НІКОЛИ, навіть після «Очистити кеш»: показувало одне й
   * те саме число і до, і після очищення. Тепер рахуємо самі байти ключів
   * `phantom_*`, тож після `clearStorage()` число справді падає.
   */
  public getLocalUsageBytes(): number {
    let total = 0;
    try {
      const enc = new TextEncoder();
      for (let i = 0; i < localStorage.length; i += 1) {
        const key = localStorage.key(i);
        if (!key || !key.startsWith('phantom_')) continue;
        const value = localStorage.getItem(key);
        if (value === null) continue;
        total += enc.encode(key).length + enc.encode(value).length;
      }
    } catch {
      // Немає доступу до сховища — 0, не вигадане число.
    }
    return total;
  }
}

export const storagePersistence = new StoragePersistence();
