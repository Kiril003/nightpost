import { useState } from 'react';
import {
  BookOpen, Clock, HardDrive, Layers, Library, MapPin,
  Radar, Route, Sparkles,
} from 'lucide-react';
import { ScaleBar } from './ScaleBar';
import { MapRail, type RailItem } from './MapRail';
import { TacticalStatsZone } from './TacticalStatsZone';
import { SearchBar } from './SearchBar';
import { NavigationToolbar } from './NavigationToolbar';
import { WhereChip } from './WhereChip';
import { usePosition } from '../../../hooks/usePosition';
import { AttributionDrawer } from './AttributionDrawer';
import { RoutingTool } from './RoutingTool';
import { AirRaidLayer } from '../layers/AirRaidLayer';
import { NearbyPanel } from '../NearbyPanel';

import { useMapStore, type MapLayerKey } from '../../../stores/mapStore';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useMapAgentBridge } from '../../../hooks/useMapAgentBridge';
import type { RouteAlternative } from '../../../services/api';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { fmtUnit } from '../mapUnits';

/** "12.4 км · 18 хв" — відстань округлена, час у цілих хвилинах. */
function formatRouteSummary(alt: RouteAlternative, locale: string): string {
  const km = alt.distance_m / 1000;
  const distance = fmtUnit(locale, km, 'kilometer', km >= 10 ? 0 : 1);
  return `${distance} · ${fmtUnit(locale, Math.max(1, Math.round(alt.duration_s / 60)), 'minute')}`;
}

/**
 * Каркас HUD мапи для екрана 1024×600.
 *
 * До цього тут було вісім незалежних островів: стан системи (вдруге, він уже
 * є у верхній панелі), рейка на п'ятнадцять кнопок із власним скролом, три
 * горизонтальні смуги одна над одною внизу і чотири окремі речі праворуч на
 * чотирьох різних відступах. Жоден елемент не був вирівняний по жодному
 * іншому.
 *
 * Тепер це система з чотирьох місць і однієї відстані (12 px):
 *   згори   — камера ліворуч, тривога по центру, стан праворуч;
 *   рейки   — ліворуч ЩО намальовано, праворуч ЩО з цим робити;
 *   знизу   — де я, чим керую, що поруч: один рядок між рейками;
 *   панелі  — висуваються поверх, а не живуть на екрані постійно.
 */

export interface HudShellProps {
  timelineOpen: boolean;
  onToggleTimeline: () => void;
  offlineOpen: boolean;
  onToggleOffline: () => void;
  storyOpen: boolean;
  onToggleStory: () => void;

  onZoomIn: () => void;
  onZoomOut: () => void;
  onCenter: () => void;
  onAddPoi: () => void;

  bridgeAgent?: boolean;
  /**
   * Обовʼязковий навмисно. Поки він був необовʼязковим, його загубила
   * деструктуризація — і TypeScript мовчав, бо мовчати йому дозволяв
   * саме знак питання.
   */
  onOpenLibrary: () => void;
  onSearchResults?: (results: unknown) => void;
  className?: string;
}

/** Шари, що про людину: хто поруч, мої місця, мої спогади. */
const PERSON_LAYERS = new Set<MapLayerKey>(['presence', 'intel', 'facts']);

const LAYERS: Array<{ key: MapLayerKey; icon: JSX.Element; label: MessageKey; short: MessageKey }> = [
  { key: 'base', icon: <Layers size={18} strokeWidth={1.75} />, label: 'map.hud.layer.base', short: 'map.hud.layer.base' },
  { key: 'presence', icon: <Radar size={18} strokeWidth={1.75} />, label: 'map.hud.layer.presence', short: 'map.hud.layer.presenceShort' },
  { key: 'intel', icon: <MapPin size={18} strokeWidth={1.75} />, label: 'map.hud.layer.intel', short: 'map.hud.layer.intelShort' },
  { key: 'facts', icon: <Sparkles size={18} strokeWidth={1.75} />, label: 'map.hud.layer.facts', short: 'map.hud.layer.facts' },
];

export function HudShell({
  timelineOpen,
  onToggleTimeline,
  offlineOpen,
  onToggleOffline,
  storyOpen,
  onToggleStory,
  onZoomIn,
  onZoomOut,
  onCenter,
  onAddPoi,
  bridgeAgent = true,
  onOpenLibrary,
  onSearchResults,
  className = '',
}: HudShellProps): JSX.Element {
  const { t, locale } = useTranslation();
  useMapAgentBridge({ skip: !bridgeAgent });
  const [routingActive, setRoutingActive] = useState(false);
  // Мапа людини — типово. Інструменти фахівця (мережі Wi-Fi, радіотепло,
  // реєстр шарів, технічні чипи) — лише з налаштуванням
  // `ui_map_expert` (Налаштування → Усі параметри → Мапа). До 27.09 людина
  // бачила пульт оператора: 8 шарів ліворуч, 4 інструменти праворуч,
  // «Спрощена графіка», «Пн · 0°», «Приймача немає», «стан невідомий».
  const expert = useSettingsStore((s) => s.values.ui_map_expert === true);

  const tactical = useMapStore((s) => s.tactical);
  const zoom = useMapStore((s) => s.zoom);
  const center = useMapStore((s) => s.center);
  const { position, pairedDevices } = usePosition();
  const layers = useMapStore((s) => s.layers);
  const toggleLayer = useMapStore((s) => s.toggleLayer);
  const searchQuery = useMapStore((s) => s.searchQuery);
  const setSearchQuery = useMapStore((s) => s.setSearchQuery);
  const setToast = useMapStore((s) => s.setToast);
  const pois = useMapStore((s) => s.pois);
  const geoTaggedFacts = useMapStore((s) => s.geoTaggedFacts);
  const route = useMapStore((s) => s.route);
  const routing = useMapStore((s) => s.routing);
  const routeError = useMapStore((s) => s.routeError);
  const planRoute = useMapStore((s) => s.planRoute);
  const clearRoute = useMapStore((s) => s.clearRoute);

  const lat = tactical.lat ?? 50.45;

  /**
   * Скільки записів справді стоїть за кожним шаром. П'ять із семи кнопок
   * вмикали порожнечу — світились бурштином і не малювали нічого, бо
   * малювати не було чого. Кнопка мусить казати правду про свій шар.
   */
  const counts: Partial<Record<MapLayerKey, number>> = {
    presence: null as unknown as number,
    intel: pois.length,
    facts: geoTaggedFacts.length,
  };

  const layerItems: RailItem[] = [
    ...LAYERS.filter((item) => expert || PERSON_LAYERS.has(item.key)).map((item) => ({
      key: item.key,
      icon: item.icon,
      label: t(item.label),
      short: t(item.short),
      active: layers[item.key],
      count: item.key === 'base' || item.key === 'presence' ? null : counts[item.key] ?? 0,
      onClick: () => {
        const n = counts[item.key];
        if (typeof n === 'number' && n === 0) {
          setToast(t('map.hud.layerEmpty', { label: t(item.label) }));
          return;
        }
        toggleLayer(item.key);
      },
    })),
    /**
     * П'ять кнопок вище — улюблені шари. Реєстр знає ще двадцять шість, і
     * доти вони не мали жодних дверей: панель була змонтована, але
     * відкрити її не міг ніхто. Питання те саме — що на мапі, — тому
     * вхід стоїть на цій рейці, а не серед інструментів.
     */
    ...(expert
      ? [
          {
            key: 'library',
            icon: <Library size={18} strokeWidth={1.75} />,
            label: t('map.hud.layer.library'),
            short: t('map.hud.layer.libraryShort'),
            count: null,
            onClick: onOpenLibrary,
          },
        ]
      : []),
  ];

  const toolItems: RailItem[] = [
    {
      key: 'route',
      icon: <Route size={18} strokeWidth={1.75} />,
      label: t('map.hud.tool.route'),
      short: t('map.hud.tool.routeShort'),
      active: routingActive,
      onClick: () => {
        setRoutingActive((v) => !v);
      },
    },
    ...(expert
      ? [
          { key: 'time', icon: <Clock size={18} strokeWidth={1.75} />, label: t('map.hud.tool.time'), short: t('map.hud.tool.timeShort'), active: timelineOpen, onClick: onToggleTimeline },
          { key: 'story', icon: <BookOpen size={18} strokeWidth={1.75} />, label: t('map.hud.tool.story'), short: t('map.hud.tool.story'), active: storyOpen, onClick: onToggleStory },
        ]
      : []),
    { key: 'offline', icon: <HardDrive size={18} strokeWidth={1.75} />, label: t('map.hud.tool.offline'), short: t('map.hud.tool.offlineShort'), active: offlineOpen, onClick: onToggleOffline },
  ];

  return (
    <div data-testid="hud-shell" className={`pointer-events-none absolute inset-0 z-30 ${className}`}>
      <div className="absolute inset-x-3 top-3 flex items-start justify-between gap-3">
        <div />
        <div className="pointer-events-auto pt-1">
          <AirRaidLayer quietWhenUnknown={!expert} />
        </div>
        <div className="pointer-events-auto">{expert && <TacticalStatsZone />}</div>
      </div>

      <div className="pointer-events-auto absolute left-3 top-1/2 -translate-y-1/2">
        <MapRail items={layerItems} ariaLabel={t('map.hud.layersAria')} />
      </div>

      <div className="pointer-events-auto absolute right-3 top-1/2 -translate-y-1/2">
        <MapRail items={toolItems} ariaLabel={t('map.hud.toolsAria')} />
      </div>

      {/* Інструмент відкривається біля своєї кнопки на рейці, а не висить
          окремим островом посеред правого краю. */}
      {routingActive && (
        <div className="pointer-events-auto absolute right-[80px] top-1/2 -translate-y-1/2">
          <RoutingTool
            active
            onToggle={() => setRoutingActive(false)}
            onPlan={(from, to) => void planRoute(from, to)}
            loading={routing}
            error={routeError}
            summary={route ? formatRouteSummary(route.result.primary, locale) : null}
            onClear={clearRoute}
          />
        </div>
      )}
      {/* Один рядок замість трьох смуг. Живе МІЖ рейками, тому 76 px.
          Бічні колонки однакової ширини — інакше `justify-between` ставить
          «центр» будь-де, тільки не по центру. */}
      <div className="absolute bottom-[84px] left-[76px] right-[76px] flex items-end gap-3">
        {/* Атрибуція переїхала сюди з правого верху: там вона лягала на
            рейку інструментів на 54×38 px. Місце ліворуч унизу — те саме,
            де її тримає кожна мапа світу. */}
        <div className="pointer-events-auto flex w-[168px] shrink-0 flex-col items-start gap-1.5">
          <WhereChip position={position} pairedDevices={pairedDevices} />
          <ScaleBar zoom={zoom} lat={lat} />
          <AttributionDrawer />
        </div>

        <div className="pointer-events-auto flex min-w-0 flex-1 items-center justify-center gap-2">
          <SearchBar value={searchQuery} onChange={setSearchQuery} onResults={onSearchResults} expert={expert} />
          <NavigationToolbar
            onZoomIn={onZoomIn}
            onZoomOut={onZoomOut}
            onCenter={onCenter}
            onAddPoi={onAddPoi}
            fix={tactical.fix}
          />
        </div>

        {/* «Поруч» шукало біля позиції з браузера — а вона на цій машині
            за 8 км від того, що на екрані. Поруч — це поруч із тим, на що
            людина дивиться. */}
        <div className="pointer-events-auto flex w-[168px] shrink-0 justify-end">
          <NearbyPanel
            lat={center?.[1] ?? tactical.lat}
            lon={center?.[0] ?? tactical.lon}
            zoom={zoom}
            onSelect={(entry) => {
              const store = useMapStore.getState();
              if (entry.kind === 'poi') {
                store.setCenter([entry.item.lon, entry.item.lat]);
                store.setZoom(Math.max(zoom, 17));
                const existing = pois.find((p) => p.id === entry.item.id);
                if (existing) store.select({ kind: 'poi', poi: existing });
              } else if (entry.kind === 'remembered') {
                const { place_lat, place_lon } = entry.item;
                if (place_lat != null && place_lon != null) {
                  store.setCenter([place_lon, place_lat]);
                  store.setZoom(Math.max(zoom, 17));
                  store.select({ kind: 'fact', fact: { ...entry.item, place_lat, place_lon } });
                }
              } else if (entry.kind === 'osm') {
                store.setCenter([entry.item.lon, entry.item.lat]);
                store.setZoom(Math.max(zoom, 17));
                store.setTappedPlace({
                  name: entry.item.name || entry.item.type || '',
                  cls: entry.item.type?.split('=')[1] ?? entry.item.type ?? '',
                  lon: entry.item.lon,
                  lat: entry.item.lat,
                  distanceM: entry.item.distance_m ?? null,
                });
              }
            }}
          />
        </div>
      </div>
    </div>
  );
}
