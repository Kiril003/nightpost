/**
 * Профіль співрозмовника для ЕКРАНА — з трьома станами, які видно.
 *
 * **Чому це окремий файл, а не `??  ''` у компоненті.** Контракт
 * (`docs/PROFILE_WIRE_CONTRACT.md`, розділ «Явне очищення vs ще не знаємо»)
 * вимагає розрізняти три речі, і вимагає саме тому, що переплутати їх —
 * означає збрехати про людину:
 *
 *  - **не приїхало** — співрозмовник на старій збірці, або кадр іще в дорозі.
 *    Екран мусить сказати «ще не знаємо», а не намалювати порожнє поле.
 *  - **прибрано** — людина стерла це в себе. Порожньо, але ВІДОМО.
 *  - **значення** — ось воно.
 *
 * Плюс четвертий, який додає дріт: **не прочиталось** — байти приїхали, але
 * це не WebP і не JPEG, тобто не те, що кадр дозволив везти. Малювати його як
 * «не приїхало» означало б чекати на кадр, який уже прийшов.
 *
 * Якби ці чотири зводились у порожній рядок десь у JSX, довести, що вони
 * виглядають ПО-РІЗНОМУ, було б нічим. Тут вони — тип, і тест їх звіряє.
 */

import { tNow } from '../components/chat/tNow';
import type { NodeContact, NodePeerProfile, WireFieldState } from './messengerApi';

/** Один стан поля, готовий до показу. */
export type ProfileFieldView =
  /** Ще не приїхало. `say` — що написати сірим замість значення. */
  | { state: 'absent'; say: string }
  /** Людина прибрала це в себе. Порожньо, але відомо. */
  | { state: 'cleared'; say: string }
  /** Байти приїхали, але рід не той, що дозволяє дріт. */
  | { state: 'unreadable'; say: string }
  | { state: 'set'; value: string };

export interface PeerProfileView {
  peerId: string;
  name: string;
  /** Найбільший застосований rev. 0 — жодного кадру не було. */
  rev: number;
  /** Коли кадр приїхав. null — не приїжджав. */
  arrivedAt: string | null;
  /**
   * Чи ми взагалі колись отримували профіль цієї людини.
   *
   * Окремо від полів: людина може мати кадр, у якому ВСЕ стерте, — і це не
   * те саме, що «кадру не було». Перше — вибір людини, друге — наше незнання.
   */
  everArrived: boolean;
  avatar: ProfileFieldView;
  /** Скільки байтів важить мініатюра. 0 — її немає. */
  avatarBytes: number;
  statusEmoji: ProfileFieldView;
  statusText: ProfileFieldView;
  bio: ProfileFieldView;
}

// Слова читаються в мить показу, щоб мова діяла без перезапуску.
const SAY_ABSENT_KEY = 'profileWire.absent';
const SAY_CLEARED_KEY = 'profileWire.cleared';

/** Речення під аватаром. Кожен стан — своє, жодне не мовчить. */
const SAY_AVATAR = {
  get absent() { return tNow('profileWire.avatarAbsent'); },
  get cleared() { return tNow('profileWire.avatarCleared'); },
  get unreadable() { return tNow('profileWire.avatarUnreadable'); },
};

function fieldOf(state: WireFieldState | undefined, value: string | null | undefined): ProfileFieldView {
  // Поля немає в JSON узагалі — це хост старої збірки, тобто рівно те саме
  // незнання, що й тег, якого не було в кадрі.
  if (state === undefined) return { state: 'absent', say: tNow(SAY_ABSENT_KEY) };
  if (state === 'absent') return { state: 'absent', say: tNow(SAY_ABSENT_KEY) };
  if (state === 'cleared') return { state: 'cleared', say: tNow(SAY_CLEARED_KEY) };
  const v = (value ?? '').trim();
  // Хост сказав «set», але значення порожнє — довіряємо тому, що бачимо, і
  // кажемо «прибрано», а не малюємо порожнечу під написом «ось воно».
  if (!v) return { state: 'cleared', say: tNow(SAY_CLEARED_KEY) };
  return { state: 'set', value: v };
}

/** Адреса фото, яку віддає лише свій телефон. Інше в `photo` — не фото. */
export const isFacePhotoUrl = (u: string | null | undefined): u is string =>
  typeof u === 'string' && /^\/webmsgr\/_faces\/(?:[0-9a-f]{32}|me(?:-[A-Za-z0-9_-]+)?)\?v=\d+(?:&p=[A-Za-z0-9_-]*)?$/.test(u);

export const profilePhotosOf = (raw: unknown): Record<string, string> =>
  raw && typeof raw === 'object'
    ? Object.fromEntries(Object.entries(raw as Record<string, unknown>).filter((e): e is [string, string] => isFacePhotoUrl(e[1] as string)))
    : {};

function avatarOf(row: Partial<NodeContact>): ProfileFieldView {
  if (isFacePhotoUrl(row.photo)) return { state: 'set', value: row.photo };
  const state = row.avatar_state;
  if (state === undefined || state === 'absent') return { state: 'absent', say: SAY_AVATAR.absent };
  if (state === 'cleared') return { state: 'cleared', say: SAY_AVATAR.cleared };
  if (state === 'unreadable') return { state: 'unreadable', say: SAY_AVATAR.unreadable };
  const url = (row.avatar_thumb ?? '').trim();
  // Хост сказав «set», а адреси немає — це не «ось фото».
  if (!url) return { state: 'unreadable', say: SAY_AVATAR.unreadable };
  return { state: 'set', value: url };
}

/** Рядок хоста → стан екрана. Один прохід, жодного здогаду в компоненті. */
export function readPeerProfile(row: Partial<NodeContact> & Partial<NodePeerProfile>): PeerProfileView {
  const rev = typeof row.profile_rev === 'number' ? row.profile_rev : 0;
  return {
    peerId: row.peer_node_id ?? row.id ?? '',
    name: row.display_name ?? '',
    rev,
    arrivedAt: row.profile_at ?? null,
    everArrived: rev > 0,
    avatar: avatarOf(row),
    avatarBytes: typeof row.avatar_bytes === 'number' ? row.avatar_bytes : 0,
    statusEmoji: fieldOf(row.status_emoji_state, row.status_emoji),
    statusText: fieldOf(row.status_text_state, row.status_text),
    bio: fieldOf(row.bio_state, row.bio),
  };
}

/** Мапа за ідентифікатором вузла — те, чим користуються перелік і шапка. */
export function profileIndex(
  rows: Array<Partial<NodeContact> & Partial<NodePeerProfile>>,
): Map<string, PeerProfileView> {
  const out = new Map<string, PeerProfileView>();
  for (const row of rows) {
    const view = readPeerProfile(row);
    if (view.peerId) out.set(view.peerId, view);
  }
  return out;
}

/** Значення, якщо воно є. `null` — будь-який з трьох інших станів. */
export function valueOf(field: ProfileFieldView): string | null {
  return field.state === 'set' ? field.value : null;
}

/**
 * Один рядок статусу для переліку розмов і шапки чату: емодзі + текст.
 *
 * `null` — сказати нема чого, і тоді поверхня НЕ малює порожнього рядка й не
 * підставляє замість нього «в мережі»: присутність і статус — різні факти,
 * і перший тут ніхто не міряв.
 */
export function statusLine(view: PeerProfileView): string | null {
  const emoji = valueOf(view.statusEmoji);
  const text = valueOf(view.statusText);
  if (!emoji && !text) return null;
  return [emoji, text].filter(Boolean).join(' ');
}

/**
 * Аватар для `<img src>`, або `null`.
 *
 * `null` повертається і для «не приїхало», і для «прибрано», і для «не
 * прочиталось» — бо зображення в усіх трьох випадках немає. Відрізняє їх не
 * ця функція, а підпис поруч ([PeerProfileView.avatar]), і саме тому вони
 * не злиті в один виклик.
 */
export function avatarSrc(view: PeerProfileView): string | null {
  return valueOf(view.avatar);
}
