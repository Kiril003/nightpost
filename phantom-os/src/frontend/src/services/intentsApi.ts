/**
 * «Мої прохання» — Наміри телефона до мого ПК (біблія месенджера, розд. 07).
 *
 * Живуть на телефоні (`IntentClient` у core-net); веб лише читає журнал через
 * міст. Поля — рівно те, що знає телефон: якими смугами прохання пішло, чи ПК
 * підтвердив отримання і що відповів. Вигадувати тут нема чого.
 */
import { request } from './api';

/** SENT → AT_PC → ANSWERED / EXPIRED (IntentJournal.kt, Phase). */
export type IntentPhase = 'SENT' | 'AT_PC' | 'ANSWERED' | 'EXPIRED';

export type IntentLane = 'relay' | 'sms';

/** Коди стану відповіді ПК — PeerIntent.Status / intents/wire.py Status. */
export const INTENT_STATUS = {
  OK: 0,
  SOURCE_SILENT: 1,
  NOT_FOUND: 2,
  REFUSED: 3,
  TIMEOUT: 4,
  UNSUPPORTED: 5,
  EXPIRED: 6,
  FAILED: 7,
} as const;

export type IntentOp = 'alerts' | 'who_online' | 'ask' | 'page_gist' | 'siren';

export interface IntentAnswer {
  rung: number;
  status: number;
  lane: IntentLane;
  at_ms: number;
  /** Текст відповіді (ШІ, суть сторінки). */
  text?: string;
  /** Розібрана відповідь «тривоги»: області під тривогою, тип у моїй, хвилини, вік даних. */
  alerts?: { oblasts: string[]; mine: boolean; since_min: number | null; age_s: number };
  /** Розібрана «хто на зв'язку». */
  presence?: { minutes_ago: number | null; lane: number; my_last_state: number | null };
  /**
   * «Сирена-маяк»: знімок тривог, ПЕРЕВІРЕНИЙ телефоном закріпленим ключем.
   * verified=false — підпис не зійшовся або ключ не закріплено: знімка не показуємо.
   */
  siren?: { verified: boolean; fresh: boolean; age_min: number | null; oblasts: string[]; observed_at: number | null };
}

export interface IntentRow {
  id: string;
  op: IntentOp;
  title: string;
  created_ms: number;
  deadline_ms: number;
  phase: IntentPhase;
  tries: { lane: IntentLane; at_ms: number }[];
  /** reply_to дозволяє сховок — тобто після SMS ще може приїхати повна відповідь. */
  relay_allowed: boolean;
  answer: IntentAnswer | null;
}

/** Екран «Без інтернету» — що вміє ця збірка і як налаштовано SMS-шлях. */
export interface IntentSetup {
  /** Збірка з сайту (field) уміє SMS; із магазину — ні. */
  sms_build: boolean;
  /** Дозволи SMS видано. */
  permitted: boolean;
  pc_paired: boolean;
  gateway_number: string;
  personal: boolean;
  quiet: boolean;
  home_gateway: boolean;
  allowed: string[];
  /** Лічильник домашнього шлюзу за сьогодні — лише у збірці з SMS. */
  ledger?: { received: number; forwarded: number; sms_sent: number; refused: number; cap: number };
}

export type IntentSetupPatch = Partial<Pick<IntentSetup, 'gateway_number' | 'personal' | 'quiet' | 'home_gateway' | 'allowed'>>;

export const intentsApi = {
  list: () => request<{ intents: IntentRow[] }>('GET', '/messenger/intents'),
  setup: () => request<IntentSetup>('GET', '/messenger/intents/setup'),
  saveSetup: (patch: IntentSetupPatch) => request<IntentSetup>('POST', '/messenger/intents/setup', patch),
  /** Єдине місце, звідки застосунок просить дозволи SMS. */
  permit: () => request<{ asked: boolean }>('POST', '/messenger/intents/permit'),
  /** Одне справжнє прохання до ПК — подивитись, якою смугою прийде відповідь. */
  test: () => request<{ id: string }>('POST', '/messenger/intents/test'),
};
