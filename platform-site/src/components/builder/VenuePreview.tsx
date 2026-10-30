import { Clock, MapPin, Wallet } from 'lucide-react';
import { DAYS, TOKEN_DECIMALS, localIsoAt, type Block, type Token, type VenuePage } from '../../lib/venueBlocks';
import type { VenueRating } from '../../lib/venuesApi';
import type { BuildCopy } from '../../i18n/build';
import type { Lang } from '../../i18n/types';

const ICON = { strokeWidth: 1.75 } as const;

/**
 * Ціна для очей: щонайменше два знаки, далі — скільки дав власник. Рахуємо
 * рядком: «4.5» → «4.50», «0.000000001» лишається собою; без Number, бо
 * мінімальна одиниця SOL у float уже бреше.
 */
export function showPrice(price: string, token: Token, lang: Lang): string {
  const [whole, frac = ''] = price.split('.');
  const digits = frac.padEnd(2, '0').slice(0, TOKEN_DECIMALS[token]);
  const sep = lang === 'uk' ? ',' : '.';
  return `${whole}${sep}${digits} ${token}`;
}

function pick<T extends Block['type']>(page: VenuePage, type: T): Extract<Block, { type: T }>[] {
  return page.blocks.filter((b): b is Extract<Block, { type: T }> => b.type === type);
}

function formatEventWhen(starts: string, ends: string | undefined, lang: Lang): string {
  const d = new Date(starts);
  if (Number.isNaN(d.getTime())) return starts;
  const locale = lang === 'uk' ? 'uk-UA' : 'en-US';
  const day = d.toLocaleDateString(locale, { day: 'numeric', month: 'short' });
  const time = starts.slice(11, 16);
  if (!ends) return `${day} · ${time}`;
  const endTime = ends.slice(11, 16);
  if (ends.slice(0, 10) === starts.slice(0, 10)) return `${day} · ${time}–${endTime}`;
  const ed = new Date(ends);
  const endDay = Number.isNaN(ed.getTime()) ? ends.slice(0, 10) : ed.toLocaleDateString(locale, { day: 'numeric', month: 'short' });
  return `${day} · ${time} – ${endDay} · ${endTime}`;
}

/**
 * Сторінка закладу так, як її побачать люди: та сама ієрархія, що й `VenueSheet`
 * у чаті — назва, оцінка, адреса й години, меню. Оцінки рахує сервер із
 * підписаних пристроями голосів; немає їх — так і пишемо, а не «новий заклад».
 */
export default function VenuePreview({
  page,
  rating,
  copy,
  lang,
}: {
  page: VenuePage;
  rating: VenueRating | null;
  copy: BuildCopy;
  lang: Lang;
}) {
  const p = copy.preview;
  const title = pick(page, 'title')[0]?.text;
  const about = pick(page, 'about')[0]?.text;
  const address = pick(page, 'address')[0];
  const hours = pick(page, 'hours')[0];
  const menus = pick(page, 'menu');
  const wallet = pick(page, 'wallet')[0];
  const nowIso = localIsoAt();
  const events = pick(page, 'event')
    .filter((ev) => (ev.ends ?? ev.starts) >= nowIso)
    .sort((a, b) => (a.starts < b.starts ? -1 : a.starts > b.starts ? 1 : 0));
  const mean = rating ? rating.mean.toFixed(1).replace('.', lang === 'uk' ? ',' : '.') : null;

  const rows = hours ? [...hours.rows].sort((a, b) => DAYS.indexOf(a.day) - DAYS.indexOf(b.day)) : [];

  return (
    <article aria-label={p.title} className="overflow-hidden rounded-card border border-line bg-surface dark:bg-raised">
      <header className="border-b border-line p-5">
        <h3 className="text-title-2 text-ink break-words">{title || p.untitled}</h3>
        <p className="mt-1 text-footnote text-ink-2">{mean ? p.rating(mean, rating!.count) : p.noRating}</p>
        {about && <p className="mt-3 whitespace-pre-line text-callout text-ink-2 break-words">{about}</p>}
      </header>

      {(address || rows.length > 0) && (
        <div className="space-y-3 border-b border-line p-5 text-callout text-ink">
          {address && (
            <p className="flex items-start gap-2">
              <MapPin className="mt-[3px] h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
              <span className="break-words">{address.text}</span>
            </p>
          )}
          {rows.length > 0 && (
            <div className="flex items-start gap-2">
              <Clock className="mt-[3px] h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
              <ul className="tnum space-y-0.5">
                {rows.map((r, i) => (
                  <li key={`${r.day}-${i}`}>
                    <span className="inline-block w-9 text-ink-2">{copy.days[r.day]}</span>
                    {r.open}–{r.close}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {events.length > 0 && (
        <div className="border-b border-line p-5">
          <h4 className="mb-2 text-footnote font-semibold uppercase tracking-wide text-ink-2">{p.events}</h4>
          <ul className="divide-y divide-line">
            {events.map((ev) => (
              <li key={ev.id} className="flex items-baseline justify-between gap-4 py-2">
                <span className="min-w-0">
                  <span className="block text-body text-ink break-words">{ev.title}</span>
                  <span className="block text-footnote text-ink-2 tnum">{formatEventWhen(ev.starts, ev.ends, lang)}</span>
                  {ev.note && <span className="block text-footnote text-ink-3 break-words">{ev.note}</span>}
                </span>
                {ev.price && ev.token && (
                  <span className="tnum shrink-0 text-body text-ink">{showPrice(ev.price, ev.token, lang)}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="p-5">
        {menus.length === 0 ? (
          <p className="text-callout text-ink-2">{p.noMenu}</p>
        ) : (
          menus.map((m, i) => (
            <section key={i} className={i ? 'mt-5' : ''}>
              {m.title && <h4 className="mb-2 text-footnote font-semibold uppercase tracking-wide text-ink-2">{m.title}</h4>}
              <ul className="divide-y divide-line">
                {m.items.map((item) => (
                  <li key={item.id} className="flex items-baseline justify-between gap-4 py-2">
                    <span className="min-w-0">
                      <span className="block text-body text-ink break-words">{item.name}</span>
                      {item.note && <span className="block text-footnote text-ink-3 break-words">{item.note}</span>}
                    </span>
                    <span className="tnum shrink-0 text-body text-ink">{showPrice(item.price, m.token, lang)}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))
        )}
      </div>

      <footer className="flex items-center gap-2 border-t border-line px-5 py-3 text-footnote text-ink-2">
        <Wallet className="h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
        {wallet ? p.payVenue : p.payOrganizer}
      </footer>
    </article>
  );
}
