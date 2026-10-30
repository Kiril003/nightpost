import { Bookmark, Loader2, Navigation, X } from 'lucide-react';
import { useMapStore } from '../../stores/mapStore';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { fmtDistance } from './mapUnits';

/** Tile class id → catalogue key. */
const CLASS_KEY: Record<string, MessageKey> = {
  restaurant: 'map.cls.restaurant',
  fast_food: 'map.cls.fastFood',
  cafe: 'map.cls.cafe',
  bar: 'map.cls.bar',
  beer: 'map.cls.beer',
  ice_cream: 'map.cls.iceCream',
  bakery: 'map.cls.bakery',
  sushi: 'map.cls.sushi',
  shop: 'map.cls.shop',
  grocery: 'map.cls.grocery',
  clothing_store: 'map.cls.clothingStore',
  alcohol_shop: 'map.cls.alcoholShop',
  butcher: 'map.cls.butcher',
  florist: 'map.cls.florist',
  furniture: 'map.cls.furniture',
  gift: 'map.cls.gift',
  hairdresser: 'map.cls.hairdresser',
  laundry: 'map.cls.laundry',
  music: 'map.cls.music',
  suitcase: 'map.cls.suitcase',
  car: 'map.cls.car',
  bicycle: 'map.cls.bicycle',
  bank: 'map.cls.bank',
  bus: 'map.cls.bus',
  railway: 'map.cls.railway',
  aerialway: 'map.cls.aerialway',
  airport: 'map.cls.airport',
  ferry_terminal: 'map.cls.ferryTerminal',
  harbor: 'map.cls.harbor',
  fuel: 'map.cls.fuel',
  parking: 'map.cls.parking',
  hospital: 'map.cls.hospital',
  pharmacy: 'map.cls.pharmacy',
  doctors: 'map.cls.doctors',
  dentist: 'map.cls.dentist',
  veterinary: 'map.cls.veterinary',
  town_hall: 'map.cls.townHall',
  police: 'map.cls.police',
  fire_station: 'map.cls.fireStation',
  post: 'map.cls.post',
  embassy: 'map.cls.embassy',
  prison: 'map.cls.prison',
  school: 'map.cls.school',
  college: 'map.cls.college',
  library: 'map.cls.library',
  place_of_worship: 'map.cls.placeOfWorship',
  park: 'map.cls.park',
  garden: 'map.cls.garden',
  playground: 'map.cls.playground',
  pitch: 'map.cls.pitch',
  golf: 'map.cls.golf',
  stadium: 'map.cls.stadium',
  swimming: 'map.cls.swimming',
  tennis: 'map.cls.tennis',
  campsite: 'map.cls.campsite',
  picnic_site: 'map.cls.picnicSite',
  museum: 'map.cls.museum',
  art_gallery: 'map.cls.artGallery',
  theatre: 'map.cls.theatre',
  cinema: 'map.cls.cinema',
  attraction: 'map.cls.attraction',
  monument: 'map.cls.monument',
  castle: 'map.cls.castle',
  zoo: 'map.cls.zoo',
  aquarium: 'map.cls.aquarium',
  lodging: 'map.cls.lodging',
  cemetery: 'map.cls.cemetery',
  toilets: 'map.cls.toilets',
  drinking_water: 'map.cls.drinkingWater',
  information: 'map.cls.information',
  shelter: 'map.cls.shelter',
};

export interface TappedPlace {
  name: string;
  cls: string;
  lon: number;
  lat: number;
  /** Відстань у метрах — лише коли ми справді знаємо, звідки міряти. */
  distanceM: number | null;
}

/**
 * Картка місця, на яке тапнули.
 *
 * Мапа малювала тисячі точок міста і жодна з них не відповідала на дотик —
 * це й читалось як «кнопки нічого не роблять». Тепер точка веде до дії:
 * маршрут туди або збереження в свої місця.
 */
export function PlaceCard({
  place,
  onClose,
  origin,
}: {
  place: TappedPlace | null;
  onClose: () => void;
  /** Звідки рахувати маршрут, коли супутників немає. */
  origin: { lat: number; lon: number; label: string } | null;
}) {
  const { t, locale } = useTranslation();
  const routeToPoint = useMapStore((s) => s.routeToPoint);
  const routing = useMapStore((s) => s.routing);
  const routeError = useMapStore((s) => s.routeError);
  const savePOI = useMapStore((s) => s.savePOI);
  const setToast = useMapStore((s) => s.setToast);

  if (!place) return null;

  const kind = CLASS_KEY[place.cls] ? t(CLASS_KEY[place.cls]) : t('map.card.place');

  return (
    <aside
      role="dialog"
      aria-label={t('map.card.aria', { name: place.name || kind })}
      className="pointer-events-auto absolute bottom-[140px] left-1/2 z-[45] w-[320px] -translate-x-1/2 rounded-2xl p-3 bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text)]"
    >
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <h3 className="font-display truncate text-[15px] font-bold text-[color:var(--ink-primary)]">
            {place.name || kind}
          </h3>
          <p className="mt-0.5 text-[11px] text-[color:var(--ink-muted)]">
            {place.name ? kind : t('map.card.unnamed')}
            {place.distanceM != null && ` · ${t('map.card.away', { dist: fmtDistance(locale, place.distanceM) })}`}
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('map.card.close')}
          className="flex h-11 w-11 min-h-[44px] min-w-[44px] items-center justify-center rounded-full text-[color:var(--ph-text-3)] hover:bg-[color:var(--ph-raised)]"
        >
          <X size={16} strokeWidth={1.75} />
        </button>
      </div>

      {routeError && (
        <p role="alert" className="mt-2 text-[11px] leading-tight text-[color:var(--ph-danger)]">
          {routeError}
        </p>
      )}

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          disabled={routing}
          onClick={() =>
            void routeToPoint(
              { lat: place.lat, lon: place.lon, label: place.name || kind },
              origin ?? undefined,
            )
          }
          className="flex min-h-[44px] flex-1 items-center justify-center gap-2 rounded-xl bg-[color:var(--ph-accent)] text-[12px] font-bold text-[color:var(--ph-on-accent)] transition-all active:scale-95 disabled:opacity-40"
        >
          {routing ? <Loader2 size={14} className="animate-spin" /> : <Navigation size={14} />}
          {routing ? t('map.card.routing') : t('map.card.routeHere')}
        </button>
        <button
          type="button"
          aria-label={t('map.card.saveAria')}
          onClick={async () => {
            const saved = await savePOI({
              lat: place.lat, lon: place.lon,
              name: place.name || kind,
              category: 'saved', notes: '', icon: '📍', is_secret: false,
            });
            setToast(saved ? t('map.card.saved', { name: saved.name }) : t('map.card.saveFailed'));
          }}
          className="flex h-11 w-11 min-h-[44px] min-w-[44px] items-center justify-center rounded-xl border border-[color:var(--ph-line)] text-[color:var(--ph-text-2)] transition-all active:scale-95 hover:bg-[color:var(--ph-raised)]"
        >
          <Bookmark size={16} strokeWidth={1.75} />
        </button>
      </div>
    </aside>
  );
}
