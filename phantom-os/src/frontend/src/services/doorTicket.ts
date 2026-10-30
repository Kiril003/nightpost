/**
 * Квиток `#door=…` з адресного рядка — вхід одним рухом зі скрипта запуску.
 *
 * Фрагмент не їде в HTTP-запиті, тож його не бачить ні dev-сервер, ні вузол,
 * ні логи; знімається синхронно на імпорті, до першого рендера, а
 * `replaceState` переписує сам запис історії. Квиток одноразовий (вузол,
 * `security/door.py`).
 */

const KEY = 'door';

function grab(): string | null {
  if (typeof window === 'undefined') return null;
  const raw = window.location.hash;
  if (!raw || raw.length < 2) return null;
  let ticket: string | null = null;
  try {
    ticket = new URLSearchParams(raw.slice(1)).get(KEY);
  } catch {
    return null;
  }
  if (!ticket) return null;
  try {
    const { pathname, search } = window.location;
    window.history.replaceState(null, '', `${pathname}${search}`);
  } catch {
    /* історія недоступна — квиток однаково згорить після обміну */
  }
  return ticket;
}

let pending: string | null = grab();

/** Квиток, знятий з адреси на старті. Віддається рівно один раз. */
export function takeDoorTicket(): string | null {
  const ticket = pending;
  pending = null;
  return ticket;
}

/** Лише для тестів: підкласти квиток, ніби він приїхав з адреси. */
export function __setDoorTicket(ticket: string | null): void {
  pending = ticket;
}

/**
 * Квиток від настільної оболонки (src-tauri/src/main.rs). Вікно відкривається
 * одразу, ще до вузла, тож фрагмента `#door=` у адресі немає: оболонка кладе
 * квиток у `window.__phantomDoorTicket` і шле подію `phantom-door`, щойно
 * вузол відповів. Той самий квиток двічі не віддаємо — він одноразовий.
 */
export function onDoorTicket(cb: (ticket: string) => void): () => void {
  if (typeof window === 'undefined') return () => undefined;
  const w = window as unknown as { __phantomDoorTicket?: unknown };
  const seen = new Set<string>();
  const fire = () => {
    const t = w.__phantomDoorTicket;
    if (typeof t === 'string' && t && !seen.has(t)) {
      seen.add(t);
      cb(t);
    }
  };
  fire();
  window.addEventListener('phantom-door', fire);
  return () => window.removeEventListener('phantom-door', fire);
}
