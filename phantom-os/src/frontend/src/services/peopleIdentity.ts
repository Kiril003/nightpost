/**
 * Хто є хто, коли імена однакові. Особа — ключ; імʼя вільне; @нік — глобальна
 * адреса з довідника (docs/IDENTITY.md). Міст віддає `handle` лише звіреним.
 */
import { currentDateTag, tNow } from '../components/chat/tNow';
import type { Chat } from '../types/messenger';
import { displayTitle, chatKind, isUnnamed } from '../components/messenger/chatListFormat';
import { normalise, normaliseForPublish, MIN_PUBLISH_LENGTH } from './peerDirectory';
import { isMachineName } from './selfName';

export const handleLabel = (handle?: string | null): string | null => {
  const h = normalise(handle);
  return h ? `@${h}` : null;
};

const isPerson = (c: Chat): boolean => chatKind(c) === 'person' && !isMyDevice(c);

const sameName = (a: Chat, b: Chat): boolean =>
  displayTitle(a.title).trim().toLocaleLowerCase('uk') === displayTitle(b.title).trim().toLocaleLowerCase('uk');

const validIso = (iso?: string): iso is string => Boolean(iso) && !Number.isNaN(new Date(iso as string).getTime());

/**
 * Друге слово біля імені для людини, чиє імʼя носить ще хтось у списку (або
 * імені немає зовсім): лише `@нік`. Дата додавання сюди НЕ йде ніколи — у
 * шапці «Новий … · додано 27 …» читалось як імʼя, обрізане посередині
 * (власник на A52, 02.10). Їй місце в картці людини: [addedLine].
 */
export const namesakeHint = (chat: Chat, chats: Chat[]): string | null => {
  if (!isPerson(chat)) return null;
  const handle = handleLabel(chat.handle);
  if (!handle) return null;
  const unnamed = isUnnamed(displayTitle(chat.title));
  const twins = chats.some((c) => c.id !== chat.id && isPerson(c) && sameName(c, chat));
  return unnamed || twins ? handle : null;
};

/** «Додано 27 вер.» — для картки людини; рік — лише коли не цей. */
export const addedLine = (iso?: string | null): string | null => {
  if (!validIso(iso ?? undefined)) return null;
  const d = new Date(iso as string);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  const day = d.toLocaleDateString(currentDateTag(), sameYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' });
  return tNow('people.added', { day });
};

/** Давніший контакт з тим самим імʼям — той, з ким цю людину легко сплутати. */
export const earlierNamesake = (chat: Chat, chats: Chat[]): Chat | null => {
  if (!isPerson(chat) || !validIso(chat.addedAt) || isUnnamed(displayTitle(chat.title))) return null;
  const mine = new Date(chat.addedAt).getTime();
  const older = chats
    .filter((c) => c.id !== chat.id && isPerson(c) && sameName(c, chat) && validIso(c.addedAt))
    .filter((c) => new Date(c.addedAt as string).getTime() < mine)
    .sort((a, b) => new Date(a.addedAt as string).getTime() - new Date(b.addedAt as string).getTime());
  return older[0] ?? null;
};

export const namesakeNotice = (chat: Chat, chats: Chat[]): string | null => {
  const other = earlierNamesake(chat, chats);
  return other ? tNow('people.namesake', { name: displayTitle(other.title) }) : null;
};

/**
 * Назви, які дає пристрій, а не людина: «ПК-вузол», «ПК · PHANTOM OS»,
 * «Ноутбук · 6 вер., 02:48», «E2E-reply-from-PC» (власник на A52, 02.10).
 * Це механіка й тестові дані — серед людей їм не місце.
 */
const DEVICE_NAME =
  /^(пк|pc|ноутбук|laptop|desktop|комп[ʼ'’]?ютер|computer)(\b|[\s·\-–—]|$)|[-\s]вузол$|phantom\s*os|^e2e\b|[-_]reply[-_]|\bnode[-_]|^s\d[-_]/i;

export const looksLikeDevice = (title?: string | null): boolean => DEVICE_NAME.test((title || '').trim());

/** Мій пристрій: позначений рідним боком (`mine`) або з назвою пристрою в розмові вдвох. */
export const isMyDevice = (c: Pick<Chat, 'mine' | 'type' | 'title' | 'id' | 'circle'>): boolean =>
  Boolean(c.mine) || (chatKind(c as Chat) === 'person' && looksLikeDevice(c.title));

/** Мої ПК і ноутбуки — не люди; у списку вони йдуть одним рядком «Мої пристрої». */
export const myDevices = (chats: Chat[]): Chat[] => chats.filter(isMyDevice);

/**
 * Контакт з `/messenger/contacts` — мій пристрій, а не людина. Той самий вирок,
 * що й у списку розмов: позначка рідного боку на розмові з цим вузлом або
 * назва пристрою. Без цього «Ноутбук» і «ПК-вузол» стояли в «Ваших людях»
 * у новій розмові й у зборі групи (A52, 02.10).
 */
export const isOwnDeviceContact = (
  c: { id: string; peer_node_id?: string | null; display_name: string },
  chats: Chat[],
): boolean => {
  if (looksLikeDevice(c.display_name)) return true;
  const chat = chats.find((x) => x.id === c.id || (!!c.peer_node_id && x.peerNodeId === c.peer_node_id));
  return chat ? isMyDevice(chat) : false;
};

export type HandleCheck = { ok: true; handle: string } | { ok: false; why: string };


/** Те саме правило мовою інтерфейсу. */
export const handleRule = (): string => tNow('people.handleRule', { min: MIN_PUBLISH_LENGTH });

export const checkNewHandle = (raw: string): HandleCheck => {
  const handle = normaliseForPublish(raw);
  return handle ? { ok: true, handle } : { ok: false, why: handleRule() };
};

export const takenWord = (handle: string): string => tNow('people.handleTaken', { handle });

export const freeWord = (handle: string): string => tNow('handleSheet.free', { handle });

const CYR_TO_LAT: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'h', ґ: 'g', д: 'd', е: 'e', є: 'ye',
  ж: 'zh', з: 'z', и: 'y', і: 'i', ї: 'yi', й: 'y', к: 'k', л: 'l',
  м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u',
  ф: 'f', х: 'kh', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch', ю: 'yu', я: 'ya',
  ы: 'y', э: 'e', ё: 'yo', ь: '', ъ: '',
};

export const transliterateToHandle = (raw?: string | null): string => {
  if (!raw) return '';
  const lower = raw
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  let out = '';
  for (const ch of lower) {
    if (ch in CYR_TO_LAT) out += CYR_TO_LAT[ch];
    else if (/[a-z0-9]/.test(ch)) out += ch;
    else if (/[\s_-]/.test(ch)) out += '_';
  }
  return out.replace(/_+/g, '_').replace(/^_+|_+$/g, '').slice(0, 32);
};

const fitHandle = (stem: string, suffix: string): string | null => {
  const clean = stem.replace(/^_+|_+$/g, '');
  if (!clean) return null;
  const maxStem = 32 - suffix.length;
  if (maxStem <= 0) return null;
  const cut = clean.slice(0, maxStem).replace(/_+$/g, '');
  return normaliseForPublish(`${cut}${suffix}`);
};

const padNameHandle = (slug: string, tag: string): string | null => {
  const direct = fitHandle(slug, tag ? `_${tag}` : '');
  if (direct) return direct;
  const digits = tag || '1';
  const need = Math.max(0, MIN_PUBLISH_LENGTH - slug.length - 1);
  return normaliseForPublish(`${slug}_${digits.padStart(need, '0')}`.slice(0, 32));
};

export const suggestHandles = (typed: string, personName?: string | null): string[] => {
  const base = normaliseForPublish(typed) ?? transliterateToHandle(typed);
  const taken = (typed ?? '').trim().replace(/^@/, '').toLowerCase();
  const nameSlug = personName && !isMachineName(personName) ? transliterateToHandle(personName) : '';

  const raw: Array<string | null> = [
    fitHandle(base, '_1'),
    fitHandle(base, '_2'),
    nameSlug ? (normaliseForPublish(nameSlug) && nameSlug !== taken ? nameSlug : padNameHandle(nameSlug, '1')) : null,
    nameSlug ? padNameHandle(nameSlug, '2') : null,
    fitHandle(base, '_3'),
    fitHandle(base, '1'),
    fitHandle(base, '_01'),
    fitHandle(base, '_7'),
    nameSlug && base ? fitHandle(`${nameSlug}_${base}`, '') : null,
  ];

  const seen = new Set<string>(taken ? [taken] : []);
  const out: string[] = [];
  for (const item of raw) {
    const valid = item ? normaliseForPublish(item) : null;
    if (valid && !seen.has(valid)) {
      seen.add(valid);
      out.push(valid);
    }
  }
  return out;
};
