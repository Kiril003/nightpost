/**
 * «Спільне» з людиною — з тієї стрічки, що вже лежить у розмові.
 *
 * Вузол окремо медіа не рахує, і питати його нема про що: історія розмови
 * вдвох приходить цілком (`listMessages(id)` без `after_seq`). Тож тут не
 * лічильник, а розбір — кожен пункт веде назад у свій лист.
 *
 * Стерте (`isDeleted`) і те, що не поїхало (`failed`), сюди не йде: «спільне»
 * — це те, що є в обох, а не те, що людина намагалась надіслати.
 */
import type { Message } from '../types/messenger';
import { currentDateTag, tNow } from '../components/chat/tNow';
import { cardFacts } from './cardFacts';

export type SharedTab = 'media' | 'files' | 'voice' | 'links' | 'places' | 'payments' | 'meetups';

export interface SharedLink {
  url: string;
  host: string;
  msgId: string;
  at: string;
  mine: boolean;
}

export interface SharedPlaceItem {
  msgId: string;
  at: string;
  mine: boolean;
  label: string;
  lat: number;
  lon: number;
}

/** Рахунок, витрати, переказ чи зустріч — рядок із назвою і сумою, місцем чи днем. */
export interface SharedCardItem {
  msgId: string;
  at: string;
  mine: boolean;
  title: string;
  sub: string;
}

export interface SharedIndex {
  media: Message[];
  files: Message[];
  voice: Message[];
  links: SharedLink[];
  places: SharedPlaceItem[];
  payments: SharedCardItem[];
  meetups: SharedCardItem[];
}

export const SHARED_TABS: Array<{ key: SharedTab; label: string; empty: string }> = [
  // Підписи читаються в мить показу (getter), щоб мова діяла без перезапуску.
  { key: 'media', get label() { return tNow('shared.media'); }, get empty() { return tNow('shared.mediaEmpty'); } },
  { key: 'files', get label() { return tNow('shared.files'); }, get empty() { return tNow('shared.filesEmpty'); } },
  { key: 'voice', get label() { return tNow('shared.voice'); }, get empty() { return tNow('shared.voiceEmpty'); } },
  { key: 'links', get label() { return tNow('shared.links'); }, get empty() { return tNow('shared.linksEmpty'); } },
  { key: 'places', get label() { return tNow('shared.places'); }, get empty() { return tNow('shared.placesEmpty'); } },
  { key: 'payments', get label() { return tNow('shared.payments'); }, get empty() { return tNow('shared.paymentsEmpty'); } },
  { key: 'meetups', get label() { return tNow('shared.meetups'); }, get empty() { return tNow('shared.meetupsEmpty'); } },
];

// Лише http(s): `javascript:` чи `file:` у тексті — не посилання, а пастка.
const URL_RE = /\bhttps?:\/\/[^\s<>"'«»]+/gi;
// Кома чи крапка в кінці речення — не частина адреси.
const TRAIL_RE = /[.,;:!?)\]]+$/;

export function linksIn(text: string): string[] {
  const out: string[] = [];
  for (const raw of text.match(URL_RE) ?? []) {
    const url = raw.replace(TRAIL_RE, '');
    try {
      const u = new URL(url);
      if (u.protocol === 'http:' || u.protocol === 'https:') out.push(url);
    } catch {
      /* не адреса — пропускаємо */
    }
  }
  return out;
}

const alive = (m: Message): boolean => !m.isDeleted && m.status !== 'failed';

const isVisual = (m: Message): boolean =>
  (m.type === 'image' && Boolean(m.media || m.mediaUrl || m.imageData)) || m.type === 'video-note';

export function indexShared(messages: Message[], selfId: string): SharedIndex {
  const out: SharedIndex = { media: [], files: [], voice: [], links: [], places: [], payments: [], meetups: [] };
  const seenUrl = new Set<string>();
  // Найсвіжіше — першим: так гортають «Спільне» всюди.
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const m = messages[i];
    if (!m || !alive(m)) continue;
    const mine = Boolean(m.isSelf) || m.senderId === selfId;
    const at = m.sentAt || m.timestamp;
    if (isVisual(m)) out.media.push(m);
    else if (m.type === 'file' && (m.media || m.fileData || m.fileName)) out.files.push(m);
    else if (m.type === 'voice') out.voice.push(m);

    const venue = m.type === 'location' && m.locationData ? m.locationData : null;
    const point = m.geoPoint ?? m.place ?? (venue ? { lat: venue.coords?.lat, lon: venue.coords?.lng } : null);
    if (point && Number.isFinite(point.lat) && Number.isFinite(point.lon)) {
      out.places.push({
        msgId: m.id,
        at,
        mine,
        label:
          (m.geoPoint?.label || venue?.name || '').trim() ||
          (m.type === 'geo:point' ? tNow('shared.mapPoint') : tNow('chatList.kind.place')),
        lat: point.lat as number,
        lon: point.lon as number,
      });
    }

    const card = cardFacts(m);
    if (card) out[card.group].push({ msgId: m.id, at, mine, title: card.title, sub: card.sub });

    if (m.text) {
      for (const url of linksIn(m.text)) {
        if (seenUrl.has(url)) continue;
        seenUrl.add(url);
        let host = url;
        try {
          host = new URL(url).host.replace(/^www\./, '');
        } catch {
          /* лишаємо як є */
        }
        out.links.push({ url, host, msgId: m.id, at, mine });
      }
    }
  }
  return out;
}

export function sharedCount(index: SharedIndex, tab: SharedTab): number {
  return index[tab].length;
}

/**
 * Коли людину востаннє чули — будь-яким кадром. Не «у мережі»: присутності
 * пристрій не міряє, і вигадане «онлайн» тут було б брехнею.
 */
export function heardLine(heardAt: string | null | undefined, lanSeenAt: string | null | undefined, now = Date.now()): string | null {
  const lan = lanSeenAt ? Date.parse(lanSeenAt) : NaN;
  if (Number.isFinite(lan) && now - lan < 5 * 60_000) return tNow('shared.heardNear');
  const t = heardAt ? Date.parse(heardAt) : NaN;
  if (!Number.isFinite(t)) return null;
  const min = Math.max(0, Math.round((now - t) / 60_000));
  if (min < 2) return tNow('shared.heardNow');
  if (min < 60) return tNow('shared.heardMin', { n: min });
  const h = Math.round(min / 60);
  if (h < 24) return tNow('shared.heardHour', { n: h });
  const d = Math.round(h / 24);
  if (d < 30) return tNow('shared.heardDay', { n: d });
  return tNow('shared.heardDate', { date: new Date(t).toLocaleDateString(currentDateTag()) });
}
