/**
 * «Що знає сервер» — перелік того, що клієнт справді передає серверові Nightpost.
 * Кожен рядок називає виклик клієнта (`source`) і таблицю сервера (`table`, з
 * ERASE_MAP/KEPT у platform-site/server/erase.py). Тест звіряє обидва боки:
 * рядок без виклику в коді чи без таблиці на сервері не проходить.
 */
export type KnownId = 'handle' | 'key' | 'support';

export interface KnownRow {
  id: KnownId;
  source: { file: string; path: string };
  table: string;
}

export const SERVER_KNOWS: readonly KnownRow[] = [
  { id: 'handle', source: { file: 'services/messengerApi.ts', path: '/messenger/directory/me' }, table: 'directory_handles' },
  { id: 'key', source: { file: 'services/messengerApi.ts', path: '/messenger/directory/me' }, table: 'directory_handles' },
  { id: 'support', source: { file: 'services/supportApi.ts', path: '/support/tickets' }, table: 'threads' },
];

/** Адреса сторінки видалення акаунта на сайті. */
export const ERASE_URL = 'https://phantom-os.dev/delete-account';
export const LEGAL_URL = 'https://phantom-os.dev/legal';
export const PRIVACY_URL = 'https://phantom-os.dev/privacy';
