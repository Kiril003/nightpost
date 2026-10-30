/**
 * Куди йде звук дзвінка: телефон, динамік, бездротові чи дротові навушники
 * (P0-5, 08.10).
 *
 * Звук на Android веде апарат, не WebView: веб лише питає міст і просить
 * перемкнути. Контракт моста (для B0 / CALL-N):
 *
 *   GET  /messenger/call/audio-route
 *     → { routes: [{ id, kind: 'earpiece'|'speaker'|'bluetooth'|'wired', name? }], current: id|null }
 *       або рідний вигляд CALL-N (CallAudioRoute.describe): routes — рядки-види,
 *       `{ routes: ['earpiece', 'speaker'], current: 'earpiece' }` (id = вид)
 *   POST /messenger/call/audio-route  { route: id }
 *     → той самий вигляд, уже після перемикання,
 *       або рідний `{ ok, current }` (CallAudioRoute.choose): `ok:false` —
 *       апарат не перемкнув, лишається, що було
 *
 * Старий APK і вузол ПК цих маршрутів не знають (404; сьогоднішній міст на
 * POST відповідає 400 `no_call_id` — див. `routeUnknown`) — тоді вибору
 * немає, і картка лишає те, що було до 08.10: кнопку «Звук», що глушить
 * співрозмовника. Вдавати перемикання, якого не було, картка не має права.
 *
 * Для B0: `audio-route` (і `ready`) мусять бути в CALL_ROOM_VERBS моста,
 * інакше POST падає в handleCallSignal і веб чесно вважає маршрут відсутнім.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { request } from '../../../services/api';
import { routeUnknown } from '../../../services/callEngine';

export const AUDIO_ROUTE_PATH = '/messenger/call/audio-route';

export type AudioRouteKind = 'earpiece' | 'speaker' | 'bluetooth' | 'wired';

export interface AudioRoute {
  id: string;
  kind: AudioRouteKind;
  /** Назва пристрою від апарата (навушники Bluetooth). Немає — назва виду. */
  name?: string;
}

export interface AudioRoutes {
  routes: AudioRoute[];
  current: string | null;
}

const KINDS: ReadonlySet<string> = new Set<AudioRouteKind>(['earpiece', 'speaker', 'bluetooth', 'wired']);

/**
 * Відповідь моста → маршрути. Незнайомі види відкидаються; якщо не лишилось
 * жодного — `null` (вибору немає, а не порожнє меню). `current`, якого немає
 * серед маршрутів, — `null`: невідомо, куди йде звук, і ми так і кажемо.
 */
export function parseRoutes(raw: unknown): AudioRoutes | null {
  if (!raw || typeof raw !== 'object') return null;
  const body = raw as { routes?: unknown; current?: unknown };
  if (!Array.isArray(body.routes)) return null;
  const routes: AudioRoute[] = [];
  for (const item of body.routes) {
    // Рідний вигляд CALL-N: вид рядком, він же й id.
    if (typeof item === 'string') {
      if (KINDS.has(item) && !routes.some((r) => r.id === item)) routes.push({ id: item, kind: item as AudioRouteKind });
      continue;
    }
    if (!item || typeof item !== 'object') continue;
    const r = item as { id?: unknown; kind?: unknown; name?: unknown };
    if (typeof r.id !== 'string' || !r.id || typeof r.kind !== 'string' || !KINDS.has(r.kind)) continue;
    const route: AudioRoute = { id: r.id, kind: r.kind as AudioRouteKind };
    if (typeof r.name === 'string' && r.name.trim()) route.name = r.name.trim();
    routes.push(route);
  }
  if (routes.length === 0) return null;
  const current = typeof body.current === 'string' && routes.some((r) => r.id === body.current) ? body.current : null;
  return { routes, current };
}

/**
 * Відповідь POST без списку — рідне `{ ok, current }` (CallAudioRoute.choose).
 * `refused` — апарат сказав, що не перемкнув; `current` — новий вихід, якщо
 * він серед уже відомих маршрутів; `null` — відповідь не цього вигляду.
 */
export function parseChoice(raw: unknown, known: AudioRoutes | null): 'refused' | AudioRoutes | null {
  if (!raw || typeof raw !== 'object') return null;
  const body = raw as { ok?: unknown; current?: unknown };
  if (body.ok === false) return 'refused';
  if (body.ok !== true || !known) return null;
  if (typeof body.current !== 'string' || !known.routes.some((r) => r.id === body.current)) return null;
  return { routes: known.routes, current: body.current };
}

/** Міст відповів «такого маршруту немає» — це старий APK або ПК, не збій. */
const isMissing = routeUnknown;

/**
 * Перший GET упав не «маршруту немає», а збоєм (міст зайнятий, 504 порту):
 * перепитати. Паузи 2, 4, 8 с — і далі вибору немає до кінця дзвінка
 * (кнопка «Звук» працює завжди).
 */
const LOAD_RETRY_MS = [2000, 4000, 8000];

/** Звук іде в гучномовець — інструмент без навушників повернеться луною. */
export const onLoudspeaker = (state: RouteState): boolean =>
  state.status === 'ready' &&
  state.routes.routes.some((r) => r.id === state.routes.current && r.kind === 'speaker');

export type RouteState =
  /** Ще не питали або чекаємо відповіді. */
  | { status: 'unknown' }
  /** Вибору немає: старий APK, ПК, один-єдиний вихід або міст мовчить. */
  | { status: 'unsupported' }
  | { status: 'ready'; routes: AudioRoutes; busy: boolean };

/**
 * Маршрути звуку для ЖИВОГО дзвінка `callId`. Питає міст один раз на дзвінок,
 * коли `enabled`; `refresh()` — перепитати (меню відкрили: могли під'єднатись
 * навушники). `choose(id)` — один POST; 404 — назад до кнопки «Звук».
 */
export function useAudioRoutes(callId: string | null, enabled: boolean) {
  const [state, setState] = useState<RouteState>({ status: 'unknown' });
  const forCall = useRef<string | null>(null);
  const live = useRef(true);
  /**
   * Номер останнього запиту. Відповідь старшого запиту (GET, що виїхав до
   * POST) не перезаписує свіжішу правду: інакше «Динамік» блимнув би назад.
   */
  const seq = useRef(0);
  /** Останній список маршрутів, що його показує картка (для рідного `{ ok, current }`). */
  const known = useRef<AudioRoutes | null>(null);

  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);

  /** Відповідь моста → стан; одного виходу замало для вибору. */
  const accept = useCallback((raw: unknown, call: string | null, my: number): boolean => {
    if (!live.current || forCall.current !== call || my !== seq.current) return true;
    const parsed = parseRoutes(raw);
    if (!parsed) return false;
    if (parsed.routes.length < 2) {
      known.current = null;
      setState({ status: 'unsupported' });
      return true;
    }
    known.current = parsed;
    setState({ status: 'ready', routes: parsed, busy: false });
    return true;
  }, []);

  const retry = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (retry.current) clearTimeout(retry.current);
    },
    [],
  );

  const load = useCallback(
    async (call: string | null, attempt = 0): Promise<void> => {
      const my = ++seq.current;
      const mine = () => live.current && forCall.current === call && my === seq.current;
      try {
        const raw = await request<unknown>('GET', AUDIO_ROUTE_PATH);
        if (!accept(raw, call, my)) setState({ status: 'unsupported' });
      } catch (err) {
        if (!mine()) return;
        // 404 / 400 no_call_id — маршруту немає: кнопка «Звук», як до 08.10.
        // Збій (міст зайнятий) — ще не відповідь: поки «невідомо», картка
        // теж показує «Звук», але за паузу перепитує.
        if (isMissing(err) || attempt >= LOAD_RETRY_MS.length) {
          setState({ status: 'unsupported' });
          return;
        }
        retry.current = setTimeout(() => {
          retry.current = null;
          if (live.current && forCall.current === call) void load(call, attempt + 1);
        }, LOAD_RETRY_MS[attempt]);
      }
    },
    [accept],
  );

  const wasEnabled = useRef(false);

  useEffect(() => {
    const turningOn = enabled && !wasEnabled.current;
    wasEnabled.current = enabled;
    if (!enabled || !callId) return;
    if (forCall.current === callId) {
      if (turningOn) void load(callId);
      return;
    }
    forCall.current = callId;
    known.current = null;
    setState({ status: 'unknown' });
    void load(callId);
  }, [callId, enabled, load]);

  const refresh = useCallback(() => {
    const call = forCall.current;
    const my = ++seq.current;
    void (async () => {
      try {
        const raw = await request<unknown>('GET', AUDIO_ROUTE_PATH);
        accept(raw, call, my);
      } catch (err) {
        if (isMissing(err) && live.current && forCall.current === call && my === seq.current) {
          setState({ status: 'unsupported' });
        }
      }
    })();
  }, [accept]);

  const choose = useCallback(
    async (id: string): Promise<void> => {
      const call = forCall.current;
      const my = ++seq.current;
      setState((s) => (s.status === 'ready' ? { ...s, busy: true } : s));
      try {
        const raw = await request<unknown>('POST', AUDIO_ROUTE_PATH, { route: id });
        if (accept(raw, call, my)) return;
        if (!live.current || forCall.current !== call || my !== seq.current) return;
        const choice = parseChoice(raw, known.current);
        if (choice && choice !== 'refused') {
          known.current = choice;
          setState({ status: 'ready', routes: choice, busy: false });
          return;
        }
        // Апарат не перемкнув (`ok:false`) або відповів не тим виглядом —
        // лишається, що було, кнопка знову жива; перепитати правду.
        setState((s) => (s.status === 'ready' ? { ...s, busy: false } : s));
        refresh();
      } catch (err) {
        if (!live.current || forCall.current !== call || my !== seq.current) return;
        if (isMissing(err)) {
          setState({ status: 'unsupported' });
          return;
        }
        // Перемкнути не вийшло — лишається те, що було; перепитати апарат.
        setState((s) => (s.status === 'ready' ? { ...s, busy: false } : s));
        refresh();
      }
    },
    [accept, refresh],
  );

  return { state, choose, refresh };
}
