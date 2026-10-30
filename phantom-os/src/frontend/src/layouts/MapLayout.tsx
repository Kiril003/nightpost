import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { OmniMap } from '../components/map/OmniMap';
import { EASE_PHANTOM } from '../styles/motion';
import { useSettingsStore } from '../stores/settingsStore';
import { useSystemStore } from '../stores/systemStore';
import { useMapStore } from '../stores/mapStore';
import '../styles/map.css';

const DEFAULT_ZOOM = 15;

export default function MapLayout() {
  const authenticated = useSystemStore((s) => s.authenticated);
  const params = new URLSearchParams(useLocation().search);
  const latRaw = params.get('lat');
  const lonRaw = params.get('lon');
  const labelRaw = params.get('label');
  const parsedLat = latRaw !== null ? Number(latRaw) : NaN;
  const parsedLon = lonRaw !== null ? Number(lonRaw) : NaN;
  const hasSharedPoint =
    latRaw !== null &&
    lonRaw !== null &&
    Number.isFinite(parsedLat) &&
    Number.isFinite(parsedLon) &&
    Math.abs(parsedLat) <= 90 &&
    Math.abs(parsedLon) <= 180;
  const initialCenter: [number, number] | undefined = hasSharedPoint
    ? [parsedLon, parsedLat]
    : undefined;

  useEffect(() => {
    if (!hasSharedPoint) return;
    const store = useMapStore.getState();
    store.setCenter([parsedLon, parsedLat]);
    store.setTappedPlace({
      name: labelRaw?.trim() || `${parsedLat.toFixed(5)}, ${parsedLon.toFixed(5)}`,
      cls: '',
      lon: parsedLon,
      lat: parsedLat,
      distanceM: null,
    });
  }, [hasSharedPoint, parsedLat, parsedLon, labelRaw]);

  // Маршруту /login не існує — MainRouter сам показує вхід, поки немає
  // сесії. Редирект сюди лишав мапу порожньою назавжди.

  // Pull the user's preferred default zoom from the persisted settings store.
  // bootstrapSettings seeds this at startup; falls back to 15 when the store
  // hasn't loaded yet (first frame after a cold start).
  const zoomRaw = useSettingsStore((s) => s.values.ui_map_default_zoom);
  const initialZoom =
    typeof zoomRaw === 'number' && Number.isFinite(zoomRaw) ? zoomRaw : DEFAULT_ZOOM;

  if (!authenticated) {
    return (
      <div
        className="w-full h-full flex flex-col items-center justify-center"
        style={{ background: 'var(--surface-void)' }}
      >
        <div className="flex flex-col items-center gap-3">
          <div
            className="w-8 h-8 border-2 rounded-full animate-spin"
            style={{ borderColor: 'var(--accent)', borderTopColor: 'transparent' }}
          />
          <span
            className="tracking-widest font-mono text-ink-muted text-xs"
          >
            Nightpost
          </span>
        </div>
      </div>
    );
  }

  return (
    <motion.div
      className="w-full h-full flex flex-col relative overflow-hidden"
      style={{ background: 'var(--ph-canvas)' }}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.4, ease: EASE_PHANTOM as unknown as number[] }}
      data-layout="map"
    >
      <div className="relative z-10 flex flex-col w-full h-full">
        <main className="flex-1 min-h-0 relative">
          <OmniMap initialCenter={initialCenter} initialZoom={initialZoom} />
        </main>
      </div>
    </motion.div>
  );
}
