import { tNow } from '../components/chat/tNow';
import { request } from './api';

export interface IceConfig {
  iceServers: RTCIceServer[];
  turn: boolean;
  ttl: number;
  inbound?: boolean;
  detail?: string;
}

export const NO_SERVERS: IceConfig = { iceServers: [], turn: false, ttl: 0 };

const NO_TURN_RETRY_MS = 60_000;

/**
 * «Вхідних не приймаємо» — це стан ЗАПУСКУ, а не відповідь.
 *
 * `inbound` на телефоні — це «слухач сітки вже підняв сокет?». Служба сітки
 * встає після реєстру, а сокет біндиться ще через стрибок на IO — тобто в ту
 * мить, коли веб питає вперше, чесна відповідь майже завжди `false`. Кешувати
 * її на хвилину означало зачинити дзвінки на весь сеанс через гонку в перші
 * півсекунди. Тому `false` живе рівно стільки, щоб не бити мостом підряд.
 */
const NO_INBOUND_RETRY_MS = 2_000;

let known: { config: IceConfig; until: number } | null = null;
let inFlight: Promise<IceConfig> | null = null;

/**
 * Єдина дорога до списку ICE для обох рушіїв (дзвінки й DataChannel):
 * список дає вузол (`GET /messenger/ice`), збірка не знає жодної адреси.
 * Видану пару TURN тримаємо пів її життя; без TURN перепитуємо за хвилину.
 * Відмова вузла лишає порожній список і не кешується — наступний виклик
 * спитає знову.
 */
export const iceConfig = async (): Promise<IceConfig> => {
  const now = Date.now();
  if (known && now < known.until) return known.config;
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const got = await request<IceConfig>('GET', '/messenger/ice');
      const servers = Array.isArray(got?.iceServers) ? got.iceServers : [];
      const config: IceConfig = {
        iceServers: servers,
        turn: got?.turn === true,
        ttl: Number(got?.ttl) || 0,
        ...(typeof got?.inbound === 'boolean' ? { inbound: got.inbound } : {}),
        ...(typeof got?.detail === 'string' && got.detail ? { detail: got.detail } : {}),
      };
      // `detail` без жодного сервера — це не «ICE порожній, бо так і треба»
      // (NO_TURN_RETRY_MS), це вузол/мережа кажуть ПРИЧИНУ відмови: телефон
      // міг щойно не достукатись до DNS на хвилину офлайну в метро. Кешувати
      // це на 60 с підряд із ще 30-секундним негативним кешем на телефоні —
      // до ~90 с без жодного ICE на мережі, що вже повернулась. Тож відповідь
      // із причиною й без серверів не кешується взагалі.
      if (config.iceServers.length === 0 && config.detail) {
        known = { config, until: Date.now() };
        return config;
      }
      const turnLife = config.ttl > 0 ? (config.ttl * 1000) / 2 : NO_TURN_RETRY_MS;
      known = {
        config,
        until:
          Date.now() +
          (config.inbound === false ? Math.min(NO_INBOUND_RETRY_MS, turnLife) : turnLife),
      };
      return config;
    } catch {
      known = { config: NO_SERVERS, until: Date.now() };
      return NO_SERVERS;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
};

export const lastKnownIce = (): IceConfig | null => known?.config ?? null;

export const hasTurn = (): boolean => known?.config.turn === true;

export const iceUrls = (config: IceConfig): string[] =>
  config.iceServers.flatMap((server) =>
    (Array.isArray(server.urls) ? server.urls : [server.urls]).filter(
      (u): u is string => typeof u === 'string',
    ),
  );

export const hostOf = (url: string): string => url.replace(/^\w+:/, '').split('?')[0];


const noInboundNote = (): string => tNow('ice.noInbound');

export const iceRoadLabel = (config: IceConfig | null): string => {
  if (config === null) return tNow('ice.notAsked');
  const urls = iceUrls(config);
  const stun = urls.find((u) => u.startsWith('stun:'));
  const turn = urls.find((u) => u.startsWith('turn:'));
  const parts: string[] = [];
  if (!stun && !turn) parts.push(tNow('ice.noRelays'));
  else {
    if (stun) parts.push(`STUN ${hostOf(stun)}${/\.google\.com/.test(stun) ? ' (Google)' : ''}`);
    parts.push(turn && config.turn ? `TURN ${hostOf(turn)}` : tNow('ice.noTurn'));
  }
  if (config.inbound === false) parts.push(noInboundNote());
  return parts.join(' · ');
};

export const resetIceForTest = (): void => {
  known = null;
  inFlight = null;
};
