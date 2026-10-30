import { useEffect, useState } from 'react';
import type { Build } from './account-api';
import type { Lang } from '../i18n/types';
import { LICENSE_SERVER } from './meta';

export type { Build };

/**
 * Куди веде «взяти збірку» з будь-якої сторінки.
 *
 * Не в саме завантаження: перед кліком, який витратить сто з гаком мегабайтів
 * мобільного трафіку й поставить файл повз магазин, людина має право прочитати
 * вагу, джерело й строк життя посилання. Тому — якір на картку, де все це
 * написано поруч із кнопкою.
 */
export const BUILD_ANCHOR = '/download#android';

/**
 * Збірки, які сервер віддає БЕЗ входу.
 *
 * Ендпоїнт `GET /api/downloads` був написаний і не покликаний жодним рядком
 * сайту — тобто «вільно завантажити» лишалось обіцянкою, а головною дією
 * продукту, який можна просто взяти, стояв лист у пошту.
 *
 * Три стани, а не два. «Сервер сказав, що збірок немає» і «сервер не
 * відповів» виглядають однаково для кнопки, але це РІЗНІ речі для тексту
 * поруч: у першому випадку сторінка має право сказати «збірки ще немає», у
 * другому вона цього не знає й казати не має права.
 */
export type Downloads =
  | { state: 'asking' }
  | { state: 'ready'; builds: Build[]; packs: Pack[] }
  | { state: 'none'; packs: Pack[] }
  /**
   * Сервер має збірку, але не віддасть її без входу (`DOWNLOAD_POLICY`).
   *
   * Це був четвертий стан, склеєний із «збірки немає»: 403 малював «Збірки ще
   * немає», тобто ЄДИНИЙ випадок, коли файл точно існує, сторінка заперечувала.
   * Кабінет цю різницю знав (`me.download_policy`), публічна сторінка — ні.
   */
  | { state: 'gated' }
  | { state: 'unreachable' };

/**
 * Дорожній пакет, який сервер СПРАВДІ роздає.
 *
 * Сьогодні `/api/downloads` не називає жодного, і це навмисно: доки поле
 * порожнє, картка каже «роздача ще не відкрита». Стан роздачі приходить з
 * підписаної відповіді сервера, а не з тексту сайту й не з маніфесту в
 * дереві — інакше сторінка мала б право пообіцяти файл, якого сервер не
 * віддасть. У день, коли сервер назве пакети, картка перемкнеться сама, без
 * жодної правки тут.
 */
export interface Pack {
  id: string;
  filename: string;
  size?: number | null;
  sha256?: string | null;
  published_at?: string | null;
}

/** Один запит на завантаження сторінки: смуга CTA стоїть на шести сторінках,
 *  і кожна питала б своє. Обіцянка була резольвлена — новий компонент бере
 *  готове значення першим же рендером, тож переходи всередині SPA не мигають. */
let asked: Promise<Downloads> | null = null;
let settled: Downloads | null = null;

export function loadDownloads(): Promise<Downloads> {
  if (asked) return asked;
  // Без строку очікування «дізнаємось» триває стільки, скільки браузер
  // тримає мертвий сокет — тобто заглушка на місці кнопки могла б пульсувати
  // хвилину. Чотири секунди: після них чесніше сказати «сервер не відповів».
  asked = fetch(`${LICENSE_SERVER}/api/downloads`, { signal: AbortSignal.timeout(4000) })
    .then(async (res): Promise<Downloads> => {
      // 403 — політика роздачі закрита. Кнопки тут бути не може, але й казати
      // «збірки немає» не можна: файл є, його просто не дають без входу.
      if (!res.ok) return { state: res.status === 403 ? 'gated' : 'unreachable' };
      const body = (await res.json()) as { downloads?: Build[]; packs?: Pack[] };
      const builds = (body.downloads ?? []).filter((b) => b && b.filename && b.platform);
      const packs = (body.packs ?? []).filter((p) => p && p.id && p.filename);
      return builds.length ? { state: 'ready', builds, packs } : { state: 'none', packs };
    })
    .catch((): Downloads => ({ state: 'unreachable' }));
  const mine = asked;
  asked.then((value) => {
    // Один збій мережі не має замикати картку в «не відповів» до перезавантаження.
    if (value.state === 'unreachable') {
      if (asked === mine) asked = null;
      settled = null;
    } else {
      settled = value;
    }
  });
  return mine;
}

export function useDownloads(): Downloads {
  const [value, setValue] = useState<Downloads>(settled ?? { state: 'asking' });
  useEffect(() => {
    let alive = true;
    loadDownloads().then((next) => {
      if (alive) setValue(next);
    });
    return () => {
      alive = false;
    };
  }, []);
  return value;
}

/**
 * Пакети, які сервер назвав. Порожньо — роздачі немає, і так і буде написано.
 *
 * «Сервер не відповів» теж дає порожньо, і це правильно: не знаємо — не
 * обіцяємо. Різницю несе сама картка, яка в цьому стані взагалі не малює
 * рядка про пакети.
 */
export function packsFor(downloads: Downloads): Pack[] {
  return downloads.state === 'ready' || downloads.state === 'none' ? downloads.packs : [];
}

export function buildFor(downloads: Downloads, platform: string): Build | null {
  if (downloads.state !== 'ready') return null;
  return downloads.builds.find((b) => b.platform === platform) ?? null;
}

/**
 * Чи те, що роздається, — складальна збірка.
 *
 * Ознака читається з відповіді СЕРВЕРА (`phantom-field-…-debug.apk`,
 * `0.1.0-tier1+…-debug`), бо так її ставить сам флейвор Gradle. Прапорця в
 * дереві сайту тут бути не може: він означав би, що в день релізу попередження
 * знімають руками — а день, коли його забудуть зняти, настане раніше за день,
 * коли його забудуть поставити.
 */
export function isDebugBuild(build: Build | null): boolean {
  if (!build) return false;
  return /(^|[-._+])debug([-._+]|$)/i.test(`${build.version ?? ''} ${(build.filename ?? '').replace(/\.apk$/i, '')}`);
}

/**
 * Підписане посилання на файл. Живе `expires_in` секунд і НЕ іменне — сервер
 * так і задумано, і кабінет про це попереджає тим самим рядком.
 */
export async function requestDownloadLink(platform: string): Promise<string> {
  const res = await fetch(`${LICENSE_SERVER}/api/download/link`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ platform }),
  });
  if (!res.ok) throw new Error(`download link ${res.status}`);
  const body = (await res.json()) as { url?: string };
  if (!body.url) throw new Error('download link: no url');
  return body.url;
}

/**
 * Ділимо на 1024², а не на мільйон.
 *
 * Число тут існує рівно для того, щоб збігтися з тим, яке людина побачить у
 * своєму завантажувачі, — а Chrome, Firefox і завантажувач Android рахують
 * мегабайт двійковим. На мільйон вийшло б 163 проти їхніх 155: розбіжність у
 * вісім мегабайтів рівно там, де ми обіцяємо, що жодного числа з голови.
 */
export function megabytes(size: number | null | undefined): string | null {
  if (typeof size !== 'number' || size <= 0) return null;
  return String(Math.round(size / 1024 / 1024));
}

/**
 * Дата викладення, як її прочитає людина. `null` — показувати нічого.
 *
 * Малюємо в UTC, а не в зоні читача: це один файл з однією датою, і той самий
 * рядок у Києві й у Ванкувері не має називати два різні дні. Читач звіряє її
 * не з власним годинником, а з нашою відповіддю.
 */
export function publishedOn(iso: string | null | undefined, lang: Lang): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat(lang === 'uk' ? 'uk-UA' : 'en-GB', {
    day: '2-digit',
    month: lang === 'uk' ? '2-digit' : 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(at);
}

/**
 * Скільки повних діб збірка лежить. `null` — вік не показуємо.
 *
 * Вік — єдине число на цій сторінці, яке рахується з ГОДИННИКА ЧИТАЧА, і тому
 * єдине, яке може збрехати не з нашої вини. Відстає годинник — різниця виходить
 * від'ємною, і «−2 дні тому» читач припише нам, а не своєму телефону. У такому
 * разі мовчимо про вік; сама дата лишається, вона наша й від годинника читача
 * не залежить.
 */
export function buildAgeDays(iso: string | null | undefined, now: number = Date.now()): number | null {
  if (!iso) return null;
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return null;
  const days = Math.floor((now - at) / 86_400_000);
  return days < 0 ? null : days;
}
