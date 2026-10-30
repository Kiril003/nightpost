/**
 * Жива смуга й присутність людей (25.09).
 *
 * Власник на A52: «месенджер візуально опустів». Попередні скарги —
 * «дуже ШІшно», потім «все зливається». Тож простір заповнюють не прикраси,
 * а ФАКТИ, які є лише в PHANTOM, і кожен — з одного джерела телефона:
 *
 *  - небо: `GET /messenger/pulse` → `sky` (вирок `AirAlertWatch`, той самий,
 *    що в шторці); `null` — тривоги в області людини немає АБО невідомо;
 *    смуга в обох випадках мовчить і «спокійно» не каже;
 *  - люди: `pulse.near` — живі точки приймача PeerWhere, лише свої;
 *  - звʼязок: `pulse.link` — смуга його не показує (26.09); вузьку дорогу
 *    (`narrow`) тихо каже рядок стану списку, поруч із чергою (02.10);
 *  - обличчя й «коли чули»: `GET /messenger/profiles` (`avatar_thumb`,
 *    `heard_at`, `lan_seen_at`).
 *
 * Тут лише чисті слова та одне спільне опитування на всі поверхні — список і
 * шапка розмови бачать ОДНУ картину, а не кожна свою.
 */
import { create } from 'zustand';
import { messengerApi, type NodePeerProfile, type NodePulse } from './messengerApi';
import { parseNodeTime } from '../components/messenger/chatListFormat';
import { tNow } from '../components/chat/tNow';
import { isFacePhotoUrl } from './peerProfile';
import { rememberPeerCaps } from './carrier/route';

export type Nearness = 'same' | 'near' | 'far' | null;
export type Precision = 'district' | 'quarter' | 'exact' | null;

export interface PulseSky {
  oblast: string;
  sinceMs: number | null;
  feedSilent: boolean;
}

export interface PulsePerson {
  peerId: string;
  name: string;
  near: Nearness;
  precision: Precision;
  atMs: number;
}

export interface Pulse {
  sky: PulseSky | null;
  near: PulsePerson[];
  /** `narrow` — дорога вузька, листи йдуть, але повільно; `narrowWord` — що сказати. */
  link: { online: boolean | null; direct: boolean; narrow?: boolean; narrowWord?: string | null };
}

export interface Face {
  avatar: string | null;
  heardAtMs: number | null;
  lanSeenAtMs: number | null;
}

const ms = (iso: string | null | undefined): number | null => {
  if (!iso) return null;
  const d = parseNodeTime(iso);
  return d ? d.getTime() : null;
};

const NEAR: Nearness[] = ['same', 'near', 'far'];
const PRECISION: Precision[] = ['district', 'quarter', 'exact'];

/** Коли старий міст каже `narrow`, але слова не дає. */
export const NARROW_WORD = 'слабкий звʼязок — текст іде першим'; // i18n-data: bridge sentinel, narrowLabel translates it

/** Слово вузької дороги мовою інтерфейсу: власний запасний рядок перекладаємо, слово від мосту лишаємо як є. */
export const narrowLabel = (word: string): string => (word === NARROW_WORD ? tNow('pulse.narrow') : word);

/** Дріт → стан. Усе незнайоме — відсутність, а не здогадка. */
export function readPulse(raw: Partial<NodePulse> | null | undefined): Pulse {
  const sky = raw?.sky && typeof raw.sky.oblast === 'string' && raw.sky.oblast.trim()
    ? { oblast: raw.sky.oblast.trim(), sinceMs: ms(raw.sky.since_at), feedSilent: raw.sky.feed_silent === true }
    : null;
  const near = (Array.isArray(raw?.near) ? raw!.near : [])
    .filter((n) => n && typeof n.peer_id === 'string' && n.peer_id)
    .map((n) => ({
      peerId: n.peer_id,
      name: (n.name || '').trim(),
      near: NEAR.includes(n.near as Nearness) ? (n.near as Nearness) : null,
      precision: PRECISION.includes(n.precision as Precision) ? (n.precision as Precision) : null,
      atMs: ms(n.at) ?? 0,
    }));
  const narrow = raw?.link?.narrow === true;
  const word = typeof raw?.link?.narrow_word === 'string' ? raw.link.narrow_word.trim() : '';
  const link = {
    online: typeof raw?.link?.online === 'boolean' ? raw.link.online : null,
    direct: raw?.link?.direct === true,
    narrow,
    // Фраза — телефона; апостроф — український (ʼ), а не друкарський.
    narrowWord: narrow ? (word ? word.replace(/['’]/g, 'ʼ') : NARROW_WORD) : null,
  };
  return { sky, near, link };
}

export function readFaces(rows: Array<Partial<NodePeerProfile>> | null | undefined): Record<string, Face> {
  const out: Record<string, Face> = {};
  // Міст іншої збірки може віддати що завгодно — не масив означає «нічого».
  for (const r of Array.isArray(rows) ? rows : []) {
    const id = r?.peer_node_id;
    if (!id) continue;
    const thumb = r.avatar_state === 'set' ? (r.avatar_thumb ?? '').trim() : '';
    out[id] = {
      // Велике фото адресою телефона, інакше лише готова `data:`-мініатюра.
      avatar: isFacePhotoUrl(r.photo) ? r.photo : /^data:image\/(webp|jpeg|png);base64,/.test(thumb) ? thumb : null,
      heardAtMs: ms(r.heard_at),
      lanSeenAtMs: ms(r.lan_seen_at),
    };
  }
  return out;
}

/** «12 хв», «1 год 5 хв». Годинник, не оцінка. */
export function durationWord(fromMs: number, nowMs: number): string | null {
  const min = Math.floor((nowMs - fromMs) / 60_000);
  if (min < 0) return null;
  if (min < 1) return tNow('pulse.durNow');
  if (min < 60) return tNow('pulse.durMin', { n: min });
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (h >= 24) return tNow('pulse.durDay', { n: Math.floor(h / 24) });
  return m ? tNow('pulse.durHourMin', { h, m }) : tNow('pulse.durHour', { h });
}


/** Одне слово про те, де людина відносно вас — на рівні того, що вона дала. */
export function nearWord(p: Pick<PulsePerson, 'near' | 'precision'>): string {
  if (p.near === 'same') return tNow(p.precision === 'district' ? 'pulse.sameDistrict' : 'pulse.nearby');
  if (p.near === 'near') return tNow('pulse.close');
  if (p.near === 'far') return tNow('pulse.far');
  return tNow('pulse.sharing');
}

const LAN_FRESH_MS = 90_000;
const HEARD_MAX_MS = 7 * 86_400_000;

/**
 * Рядок під іменем у шапці розмови: що відомо про людину зараз.
 * Ніколи «у мережі»: присутність тут — факт із віком.
 */
export function presenceLine(
  person: PulsePerson | null | undefined,
  face: Face | null | undefined,
  nowMs: number,
): string | null {
  // Слово відстані першим: шапка вузька, і хвіст ріжеться, а не суть.
  if (person) return person.near ? tNow('pulse.nearSharing', { word: nearWord(person) }) : tNow('pulse.sharingToYou');
  const lan = face?.lanSeenAtMs;
  if (lan && nowMs - lan < LAN_FRESH_MS) return tNow('pulse.nearby');
  const heard = face?.heardAtMs;
  if (!heard) return null;
  const ago = nowMs - heard;
  if (ago < 0) return null;
  if (ago < 90_000) return tNow('pulse.reachedNow');
  if (ago > HEARD_MAX_MS) return null;
  // Шапка — не годинник: «5 год», а не «5 год 54 хв».
  const w = ago < 3_600_000 ? durationWord(heard, nowMs) : durationWord(nowMs - (ago - (ago % 3_600_000)), nowMs);
  if (!w) return null;
  return tNow('pulse.reachedAgo', { w });
}

export type PresenceCode = 'near' | 'sharing' | 'heard';

export function presenceCode(
  person: PulsePerson | null | undefined,
  face: Face | null | undefined,
  nowMs: number,
): PresenceCode | null {
  if (person) return person.near === 'same' || person.near === 'near' ? 'near' : 'sharing';
  const lan = face?.lanSeenAtMs;
  if (lan && nowMs - lan < LAN_FRESH_MS) return 'near';
  const heard = face?.heardAtMs;
  if (!heard) return null;
  const ago = nowMs - heard;
  return ago >= 0 && ago <= HEARD_MAX_MS ? 'heard' : null;
}

// ── одне опитування на всі поверхні ────────────────────────────────────

interface LivingState {
  pulse: Pulse | null;
  faces: Record<string, Face>;
  /** Скільки поверхонь зараз слухають; опитування живе, доки > 0. */
  listeners: number;
  refresh: () => Promise<void>;
}

const POLL_MS = 20_000;
let timer: number | null = null;

export const useLiving = create<LivingState>((set) => ({
  pulse: null,
  faces: {},
  listeners: 0,
  refresh: async () => {
    const [pulse, faces] = await Promise.allSettled([messengerApi.pulse(), messengerApi.peerProfiles()]);
    if (faces.status === 'fulfilled' && Array.isArray(faces.value)) rememberPeerCaps(faces.value);
    set((s) => ({
      // Смуга мовчить, коли міст не відповів: учорашнє «Оксана поруч» над
      // мовчазним мостом було б вигадкою. Обличчя ж лишаються — фото не
      // старіє, а «коли чули» несе власний вік.
      pulse: pulse.status === 'fulfilled' ? readPulse(pulse.value) : null,
      faces: faces.status === 'fulfilled' ? readFaces(faces.value) : s.faces,
    }));
  },
}));

/** Підписатись на опитування. Повертає відписку. */
export function listenLiving(): () => void {
  const st = useLiving.getState();
  useLiving.setState({ listeners: st.listeners + 1 });
  if (timer === null) {
    void st.refresh();
    timer = window.setInterval(() => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
      void useLiving.getState().refresh();
    }, POLL_MS);
  }
  return () => {
    const n = useLiving.getState().listeners - 1;
    useLiving.setState({ listeners: Math.max(0, n) });
    if (n <= 0 && timer !== null) {
      window.clearInterval(timer);
      timer = null;
    }
  };
}
