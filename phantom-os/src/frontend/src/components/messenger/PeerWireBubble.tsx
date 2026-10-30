import { PlaceRelief } from './TerrainLayer';
import React from 'react';
import { HelpCircle, MapPin, Paperclip, Route as RouteIcon } from 'lucide-react';
import type { LatLon, SharedPlace, SharedRoute } from '../../types/messenger';
import {
  distanceLabel,
  durationLabel,
  profileWord,
  routeExpired,
  type PeerWire,
} from '../../services/peerWire';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';

/**
 * Кадр меша в бульбашці: картка, чесне речення — і ніколи сирий дріт.
 *
 * Саме дріт власник побачив 04.09 у розмові з живою людиною. Числа на картці —
 * від відправника: свого ETA веб порахувати не має з чого. Координат на
 * картці немає — це техніка, людині вони нічого не кажуть (закон 26.09).
 *
 * Мініатюра шляху малюється з ламаної, яку віддав міст: SVG, без тайлів і без
 * жодного мережевого запиту — WebView телефона на цю адресу в мережу не ходить.
 * Ламаної немає — немає й мініатюри: намальована з нічого лінія була б
 * вигаданим шляхом.
 *
 * Речення про нечитаний кадр пише ХОСТ: він єдиний знає, що не зійшлося. Своє
 * ставимо лише тоді, коли він не сказав нічого.
 *
 * `relative z-10` не косметика: довге тіло ChatArea вважає «довгим листом» і
 * накриває низ градієнтом із кнопкою «Читати далі». Картка мусить лишитись
 * читабельною під ним.
 */
export const PeerWireBubble: React.FC<{ wire: PeerWire; nowMs?: number }> = ({ wire, nowMs }) => {
  const { t } = useTranslation();
  switch (wire.kind) {
    // Керівний кадр: у розмові його немає — PeerOutbox.isSpoken.
    case 'control':
      return null;

    case 'route':
      return <RouteBubble route={wire.route} nowMs={nowMs ?? Date.now()} />;

    case 'place':
      return <PlaceBubble place={wire.place} />;

    case 'broken': {
      const kept = ON_PHONE[wire.broken.of];
      if (kept) {
        return (
          <HonestCard
            testId="peer-attachment-on-phone"
            icon={<Paperclip className="h-4 w-4" strokeWidth={1.75} />}
            title={t(kept.title)}
            hint={t(kept.hint)}
          />
        );
      }
      return (
        <HonestCard
          testId={
            wire.broken.of === 'place'
              ? 'peer-place-broken'
              : wire.broken.of === 'route'
                ? 'peer-route-broken'
                : 'peer-broken'
          }
          title={
            wire.broken.of === 'place'
              ? t('peerWireBubble.placeBroken')
              : wire.broken.of === 'route'
                ? t('peerWireBubble.routeBroken')
                : t('peerWireBubble.messageBroken')
          }
          hint={t('peerWireBubble.resend')}
        />
      );
    }

    // Дріт, який міст не розібрав: рід відомий, подробиці — ні. Показуємо
    // рівно стільки, скільки знаємо, і кажемо, чого не знаємо.
    case 'raw':
      return wire.of === 'route' ? (
        <HonestCard
          testId="peer-route-raw"
          icon={<RouteIcon className="h-4 w-4" strokeWidth={1.75} />}
          title={t('peerWireBubble.route')}
          hint={t('peerWireBubble.routeRawHint')}
        />
      ) : (
        <HonestCard
          testId="peer-place-raw"
          icon={<MapPin className="h-4 w-4" strokeWidth={1.75} />}
          title={t('peerWireBubble.place')}
          hint={t('peerWireBubble.placeRawHint')}
        />
      );

    default:
      return (
        <HonestCard
          testId="peer-unknown-line"
          title={t('peerWireBubble.unknownTitle')}
          hint={t('peerWireBubble.unknownHint')}
        />
      );
  }
};

/**
 * Вкладення з телефона, байтів якого цей вузол ще не збирає. Не «не вдалося»
 * і не «надішліть ще раз»: лист цілий, просто відкривається він на телефоні.
 */
const ON_PHONE: Readonly<Record<string, { title: MessageKey; hint: MessageKey }>> = {
  photo: { title: 'peerWireBubble.photo', hint: 'peerWireBubble.opensOnPhone' },
  video: { title: 'peerWireBubble.video', hint: 'peerWireBubble.opensOnPhone' },
  voice: { title: 'peerWireBubble.voice', hint: 'peerWireBubble.listenOnPhone' },
  file: { title: 'peerWireBubble.file', hint: 'peerWireBubble.opensOnPhone' },
};

const HonestCard: React.FC<{
  testId: string;
  title: string;
  hint: string;
  icon?: React.ReactNode;
}> = ({ testId, title, hint, icon }) => (
  <div
    className="relative z-10 mt-1 rounded-control bg-raised p-3 text-ink "
    data-testid={testId}
  >
    <div className="flex items-start gap-2.5">
      <span className="mt-0.5 shrink-0 rounded-control bg-canvas p-2 text-ink-3 ">
        {icon ?? <HelpCircle className="h-4 w-4" strokeWidth={1.75} />}
      </span>
      <div className="min-w-0 flex-1">
        <h4 className="text-footnote font-semibold text-ink">{title}</h4>
        <p className="text-caption leading-snug text-ink-3">{hint}</p>
      </div>
    </div>
  </div>
);

/** Точність словом, не метрами: «±12 м» — техніка. */
const accuracyWord = (m: number): MessageKey =>
  m <= 50 ? 'peerWireBubble.accExact' : m <= 300 ? 'peerWireBubble.accBlock' : 'peerWireBubble.accApprox';

const PlaceBubble: React.FC<{ place: SharedPlace }> = ({ place }) => {
  const { t } = useTranslation();
  return (
  <div
    className="relative z-10 mt-1 w-[260px] max-w-full overflow-hidden rounded-card bg-surface text-ink"
    data-testid="peer-place-card"
  >
    <PlaceRelief lat={place.lat} lon={place.lon} />
    <div className="flex items-center gap-2.5 px-3 py-2.5">
      <MapPin className="h-4 w-4 shrink-0 text-ink-2" strokeWidth={1.75} />
      <div className="min-w-0 flex-1">
        <h4 className="text-subhead font-semibold text-ink">{t('peerWireBubble.place')}</h4>
        {place.accuracyM !== undefined && (
          <p className="text-caption text-ink-3">{t(accuracyWord(place.accuracyM))}</p>
        )}
      </div>
    </div>
  </div>
  );
};

const RouteBubble: React.FC<{ route: SharedRoute; nowMs: number }> = ({ route, nowMs }) => {
  const { t } = useTranslation();
  const expired = routeExpired(route, nowMs);
  const facts = [
    distanceLabel(route.distanceM),
    durationLabel(route.durationS),
    profileWord(route.profile),
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <div
      className={`relative z-10 mt-1 rounded-control border p-3 text-ink ${
        expired ? 'border-line bg-surface opacity-75' : 'border-line bg-raised'
      }`}
      data-testid="peer-route-card"
    >
      <div className="flex items-center gap-2.5">
        <span
          className={`shrink-0 rounded-control p-2 ${
            expired ? 'bg-canvas text-ink-3' : 'bg-canvas text-ink-2'
          }`}
        >
          <RouteIcon className="h-4 w-4" strokeWidth={1.75} />
        </span>
        <div className="min-w-0 flex-1">
          <h4 className="text-footnote font-semibold text-ink">
            {expired ? t('peerWireBubble.routeExpired') : t('peerWireBubble.route')}
          </h4>
          <p className="text-caption text-ink-3" data-testid="peer-route-facts">
            {facts}
          </p>
        </div>
      </div>

      <RouteSpark shape={route.shape} muted={expired} />
    </div>
  );
};

/**
 * Мініатюра шляху: та сама ламана, тільки без мапи під нею.
 *
 * Мапи тут нема навмисно — не через WebView, а тому що полотно малює ATLAS, і
 * друга жива мапа в стрічці порушила б закон «одна поверхня» (той самий вибір,
 * що в `GeoPointBubble`). Це силует шляху, а не мапа: масштаб і північ на ньому
 * не читаються й не обіцяються.
 */
const RouteSpark: React.FC<{ shape: LatLon[]; muted: boolean }> = ({ shape, muted }) => {
  const { t } = useTranslation();
  if (shape.length < 2) return null;
  const W = 200;
  const H = 40;
  const PAD = 3;

  // Довгота стискається за широтою, інакше шлях «схід-захід» виглядає довшим,
  // ніж він є. Дрібниця на 300 метрах, помітна на 30 кілометрах.
  const midLat = (shape.reduce((sum, p) => sum + p.lat, 0) / shape.length) * (Math.PI / 180);
  const kx = Math.max(Math.cos(midLat), 0.1);
  const xs = shape.map((p) => p.lon * kx);
  const ys = shape.map((p) => -p.lat);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const span = Math.max(maxX - minX, maxY - minY, 1e-9);
  const scale = Math.min(W - 2 * PAD, H - 2 * PAD) / span;
  const offX = (W - 2 * PAD - (maxX - minX) * scale) / 2;
  const offY = (H - 2 * PAD - (maxY - minY) * scale) / 2;
  const at = (i: number): [number, number] => [
    PAD + offX + (xs[i] - minX) * scale,
    PAD + offY + (ys[i] - minY) * scale,
  ];
  const line = shape.map((_, i) => at(i).map((v) => v.toFixed(1)).join(',')).join(' ');
  const [sx, sy] = at(0);
  const [ex, ey] = at(shape.length - 1);
  // Ролі теми, не числа: стала з малої літери обминала денний сторож
  // dayTheme, і вдень силует лишався нічним.
  const ink = muted ? 'var(--ph-text-3)' : 'var(--ph-accent)';

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="mt-2 h-[40px] w-full rounded-control bg-canvas "
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-label={t('peerWireBubble.sparkAlt')}
      data-testid="peer-route-spark"
    >
      <polyline
        points={line}
        fill="none"
        stroke={ink}
        strokeWidth={1.75}
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
      <circle cx={sx} cy={sy} r={2.5} fill={ink} />
      <circle cx={ex} cy={ey} r={2.5} fill="none" stroke={ink} strokeWidth={1.5} />
    </svg>
  );
};
