import { validatePage, type VenuePage } from '../../lib/venueBlocks';
import type { Venue } from '../../lib/venuesApi';
import { cleanText, draftFromPage, isBlankDish, pageFromDraft, type Draft, type DraftProblem } from './draft';

/*
 * Стан редактора закладу — чисті функції, якими живе `Editor` у `pages/Build.tsx`.
 *
 * Одне правило на все: те, що власник набрав і ще не зберіг, не зникає мовчки.
 * Ні від повторного завантаження закладу, ні від «Зняти з публікації», ні від
 * сесії, що скінчилась посеред правок.
 */

export interface EditorState {
  /** Заклад, яким його востаннє віддав сервер. */
  venue: Venue | null;
  draft: Draft | null;
  /** У чернетці є те, чого сервер ще не бачив. */
  dirty: boolean;
  /** Чернетку повернуто з вкладки після нового входу. */
  restored: boolean;
}

export type EditorAction = 'save' | 'publish' | 'unpublish';

export const initialEditor = (): EditorState => ({ venue: null, draft: null, dirty: false, restored: false });

/**
 * Заклад прийшов із сервера. Брудна чернетка лишається як є — оновлюється лише
 * те, що сказав сервер (стан публікації, версія). `stash` — чернетка, відкладена
 * у вкладці, коли сесія скінчилась посеред правок.
 */
export function loaded(s: EditorState, venue: Venue, stash: Draft | null): EditorState {
  if (s.dirty && s.draft) return { ...s, venue };
  if (stash) return { venue, draft: stash, dirty: true, restored: true };
  return { venue, draft: draftFromPage(venue.page, venue.name), dirty: false, restored: false };
}

export function edited(s: EditorState, draft: Draft): EditorState {
  return { ...s, draft, dirty: true, restored: false };
}

/**
 * Сервер прийняв. Після збереження чи публікації чернетка — це те, що сервер
 * записав. «Зняти з публікації» чернетки не торкалось, тож і після нього вона
 * лишається такою, якою була, з усіма незбереженими правками.
 */
export function saved(s: EditorState, venue: Venue, kind: EditorAction, sent?: VenuePage): EditorState {
  if (kind === 'unpublish') return { ...s, venue };
  const page = venue.page ?? sent ?? null;
  const fresh = draftFromPage(page, venue.name);
  return { venue, draft: s.draft ? keepUnsent(s.draft, fresh, page) : fresh, dirty: false, restored: false };
}

/**
 * Розділ меню з назвою, але без страв, і монета меню без жодної страви на сервер
 * не йдуть (блоку немає), тож після збереження їх беремо з того, що набрав власник.
 */
function keepUnsent(prev: Draft, fresh: Draft, page: VenuePage | null): Draft {
  const hasMenu = page?.blocks.some((b) => b.type === 'menu') ?? false;
  const sentMenus = hasMenu ? fresh.menus : [];
  const filled = prev.menus.filter((m) => m.items.some((i) => !isBlankDish(i)));
  if (filled.length !== sentMenus.length) return fresh;
  let n = 0;
  const menus = prev.menus.flatMap((m) =>
    m.items.some((i) => !isBlankDish(i)) ? [sentMenus[n++]] : cleanText(m.title) ? [m] : [],
  );
  return { ...fresh, token: hasMenu ? fresh.token : prev.token, menus: menus.length ? menus : fresh.menus };
}

export type EditorRequest = { page: VenuePage; published: boolean } | { problem: DraftProblem };

/**
 * Що послати серверові на кнопку. «Зняти з публікації» шле останню ЗБЕРЕЖЕНУ
 * сторінку: незбережені правки не мають ні тихо потрапити на сервер, ні
 * заблокувати зняття закладу помилкою в якійсь ціні.
 */
export function requestFor(kind: EditorAction, draft: Draft, current: Venue): EditorRequest {
  if (kind === 'unpublish') return current.page ? { page: current.page, published: false } : { problem: 'bad_page' };
  const publish = kind === 'publish' || current.published;
  const out = pageFromDraft(draft, { publish });
  if (out.problem) return { problem: out.problem };
  if (!validatePage(out.page, { publish }).ok) return { problem: 'bad_page' };
  return { page: out.page, published: publish };
}

// ── Відкладена чернетка: сесія скінчилась, правки — ні ───────────────────────

type TabStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const stashKey = (id: string) => `phantom.build.draft.${id}`;

/** `sessionStorage` вкладки, якщо браузер його дає (приватне вікно чи заборона — `null`). */
export function tabStore(): TabStore | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/** Відкласти чернетку закладу до нового входу. Лише ця вкладка, лише цей заклад. */
export function stashDraft(store: TabStore | null, id: string, draft: Draft): void {
  try {
    store?.setItem(stashKey(id), JSON.stringify({ v: 1, draft }));
  } catch {
    // Сховище вкладки недоступне — тоді лишається попередження браузера при виході.
  }
}

export function hasStash(store: TabStore | null, id: string): boolean {
  try {
    return Boolean(store?.getItem(stashKey(id)));
  } catch {
    return false;
  }
}

/** Забрати відкладену чернетку (один раз: забрана — стерта). */
export function takeStash(store: TabStore | null, id: string): Draft | null {
  try {
    const raw = store?.getItem(stashKey(id));
    if (!store || !raw) return null;
    store.removeItem(stashKey(id));
    const parsed: unknown = JSON.parse(raw);
    const draft = (parsed as { draft?: unknown } | null)?.draft;
    return isDraft(draft) ? draft : null;
  } catch {
    return null;
  }
}

function isDraft(v: unknown): v is Draft {
  if (typeof v !== 'object' || v === null) return false;
  const d = v as Record<string, unknown>;
  const strings = ['title', 'about', 'address', 'point', 'wallet'].every((k) => typeof d[k] === 'string');
  const fieldsAreStrings = (o: unknown, keys: string[]) =>
    typeof o === 'object' && o !== null && keys.every((k) => typeof (o as Record<string, unknown>)[k] === 'string');
  const menus =
    Array.isArray(d.menus) &&
    d.menus.every((m) => {
      const items = (m as Record<string, unknown> | null)?.items;
      return (
        fieldsAreStrings(m, ['key', 'title']) &&
        Array.isArray(items) &&
        items.every((i) => fieldsAreStrings(i, ['key', 'id', 'name', 'price', 'note']))
      );
    });
  const events =
    d.events === undefined ||
    (Array.isArray(d.events) &&
      d.events.every(
        (e) =>
          fieldsAreStrings(e, ['key', 'id', 'title', 'starts', 'ends', 'note', 'price']) &&
          [undefined, 'USDC', 'SOL'].includes((e as Record<string, unknown>).token as string | undefined),
      ));
  if (d.events === undefined && typeof d === 'object') d.events = [];
  return strings && menus && events && Array.isArray(d.hours) && (d.token === 'USDC' || d.token === 'SOL');
}
