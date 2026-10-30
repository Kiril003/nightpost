import { SAMPLES } from './samples.ts';
import { isLook, type Look } from './looks.ts';
import { WHO, WHO_LOOK, sampleFor, type Who } from './start.ts';
import type { EditPage } from './editor.ts';

export const LOCAL = 'local';
export const LOCAL_KEY = 'np.builder.local';
export const START_KEY = 'np.creator.start';

const LEAVE = new Set(['pay.wallet', 'pay.qr', 'com.reviews', 'deal.drop']);

export type Local = { page: EditPage; who: Who; edited: boolean };
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null | undefined;
type Owned = { id: string; editor?: number; updated_ms?: number | null };

export const isWho = (v: unknown): v is Who => WHO.includes(v as Who);

export function starterTemplate(who: Who, lang: string, look?: Look): EditPage {
  const s = SAMPLES.find((x) => x.handle === sampleFor(who, lang)) ?? SAMPLES[0];
  const page = structuredClone(s.page) as EditPage;
  return { v: 2, look: look ?? WHO_LOOK[who], tone: page.tone ?? 'amber', blocks: page.blocks.filter((b) => !LEAVE.has(b.kind)) };
}

export function pageName(page: EditPage): string {
  return String(page.blocks.find((b) => b.kind === 'id.name')?.data.name ?? '').trim();
}

export function readLocal(store: Store): Local | null {
  try {
    const raw = store?.getItem(LOCAL_KEY);
    const v = raw ? JSON.parse(raw) : null;
    return v && v.page?.v === 2 && Array.isArray(v.page.blocks) && isWho(v.who) ? { page: v.page, who: v.who, edited: v.edited === true } : null;
  } catch {
    return null;
  }
}

export function writeLocal(store: Store, l: Local): boolean {
  try {
    store?.setItem(LOCAL_KEY, JSON.stringify(l));
    return !!store;
  } catch {
    return false;
  }
}

export function clearLocal(store: Store): void {
  try {
    store?.removeItem(LOCAL_KEY);
  } catch {
    /* nothing to clear */
  }
}

export function lastStart(store: Store): { who: Who; look: Look } | null {
  try {
    const v = JSON.parse(store?.getItem(START_KEY) ?? 'null');
    return v && isWho(v.who) && isLook(v.look) ? { who: v.who, look: v.look } : null;
  } catch {
    return null;
  }
}

export function localStart(o: { local: Local | null; who: string | null; look: string | null; last: { who: Who; look: Look } | null; lang: string }): Local {
  if (o.local?.edited) return o.local;
  const who: Who = isWho(o.who) ? o.who : o.local?.who ?? o.last?.who ?? 'me';
  const look = isLook(o.look) ? o.look : o.last && o.last.who === who && !isWho(o.who) ? o.last.look : undefined;
  return { page: starterTemplate(who, o.lang, look), who, edited: false };
}

export function openTarget(o: { local: Local | null; signedIn: boolean; fresh: boolean; venues: Owned[] | null }): string {
  if (!o.signedIn || o.fresh || o.local?.edited || !o.venues) return LOCAL;
  const own = [...o.venues].sort((a, b) => (b.updated_ms ?? 0) - (a.updated_ms ?? 0))[0];
  return own?.id ?? LOCAL;
}

export function publishStep(local: boolean, signedIn: boolean): 'signIn' | 'upload' | 'sheet' {
  return !local ? 'sheet' : signedIn ? 'upload' : 'signIn';
}

export function placeRoute(search: URLSearchParams): string | null {
  return search.get('event') || search.get('form') ? null : entryRoute(search);
}

export function entryRoute(search: URLSearchParams): string | null {
  const venue = search.get('venue');
  if (!venue) return null;
  const rest = new URLSearchParams(search);
  rest.delete('venue');
  const q = rest.toString();
  return `/build/edit/${encodeURIComponent(venue)}${q ? `?${q}` : ''}`;
}
