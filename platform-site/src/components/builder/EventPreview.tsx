import { Calendar, MapPin, Users, Wallet } from 'lucide-react';
import { showPrice } from './VenuePreview';
import { timeRange, type EventBlock } from './builderModel';
import type { BuilderACopy } from '../../i18n/builderA';
import type { Lang } from '../../i18n/types';

const ICON = { strokeWidth: 1.75 } as const;

function formatShortDate(starts: string, lang: Lang): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(starts);
  if (!m) return starts;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  if (Number.isNaN(d.getTime())) return starts;
  return d.toLocaleDateString(lang === 'uk' ? 'uk-UA' : 'en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

export default function EventPreview({
  venueName,
  address,
  event,
  going,
  capacity,
  copy,
  lang,
}: {
  venueName: string;
  address: string | null;
  event: EventBlock;
  going: number;
  capacity: number | null;
  copy: BuilderACopy;
  lang: Lang;
}) {
  const e = copy.event;
  const left = capacity !== null ? Math.max(0, capacity - going) : null;

  return (
    <article className="overflow-hidden rounded-card border border-line bg-surface p-5">
      <p className="text-footnote font-semibold text-ink-3">{e.eventLabel(venueName)}</p>
      <h3 className="mt-1 break-words text-[24px] font-bold leading-tight text-ink">{event.title}</h3>

      <div className="mt-4 divide-y divide-line">
        <div className="flex items-center gap-3.5 py-3">
          <Calendar className="h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
          <div className="min-w-0">
            <p className="text-callout font-semibold text-ink">{formatShortDate(event.starts, lang)}</p>
            <p className="tnum text-footnote text-ink-2">{timeRange(event.starts, event.ends)}</p>
          </div>
        </div>

        <div className="flex items-center gap-3.5 py-3">
          <MapPin className="h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
          <div className="min-w-0">
            <p className="break-words text-callout font-semibold text-ink">{venueName}</p>
            {address && <p className="break-words text-footnote text-ink-2">{address}</p>}
          </div>
        </div>

        <div className="flex items-center gap-3.5 py-3">
          <Users className="h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
          <div className="min-w-0">
            <p className="tnum text-callout font-semibold text-ink">{e.goingValue(going)}</p>
            {left !== null && <p className="tnum text-footnote text-ink-2">{e.placesLeft(left)}</p>}
          </div>
        </div>

        <div className="flex items-center gap-3.5 py-3">
          <Wallet className="h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
          <div className="min-w-0">
            <p className="tnum text-callout font-semibold text-ink">
              {event.price && event.token ? showPrice(event.price, event.token, lang) : e.free}
            </p>
          </div>
        </div>
      </div>
    </article>
  );
}
