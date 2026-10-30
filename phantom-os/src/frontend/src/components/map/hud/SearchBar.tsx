import { Search, MapPin } from 'lucide-react';
import { useMapStore } from '../../../stores/mapStore';
import { useMemo } from 'react';
import { expandQuery } from '../../../services/translit';
import { mapApi } from '../../../services/api';
import { geocodeErrorKey } from '../../../services/geocodeError';
import { osmTypeKey } from '../../../services/osmType';
import { useTranslation } from '../../../i18n/useTranslation';

export interface SearchBarProps {
  value: string;
  onChange: (v: string) => void;
  onResults?: (results: any) => void;
  className?: string;
  /** Мапа фахівця: шукає й мережі та точки; людині — просто місця. */
  expert?: boolean;
}

export function SearchBar({ value, onChange, onResults, className = '', expert = false }: SearchBarProps) {
  const { pois, geoTaggedFacts, setCenter, setZoom, setSearchQuery, setToast } = useMapStore((s) => ({
    pois: s.pois,
    geoTaggedFacts: s.geoTaggedFacts,
    setCenter: s.setCenter,
    setZoom: s.setZoom,
    setSearchQuery: s.setSearchQuery,
    setToast: s.setToast,
  }));
  const { t, locale } = useTranslation();

  const localResults = useMemo(() => {
    const rawQueries = expandQuery(value).map((q) => q.toLowerCase());
    if (rawQueries.length === 0 || !value) return null;
    
    const matches = (haystack: string) =>
      rawQueries.some((q) => haystack.includes(q));
      
    const intel = pois
      .filter((p) => matches(p.name.toLowerCase()) || matches(p.category.toLowerCase()))
      .slice(0, 5);
      
    return { intel, total: intel.length };
  }, [value, pois]);

  /**
   * Enter у полі пошуку не шукав нічого: він тягнув «що поруч» біля старої
   * позиції і мовчки викидав сам запит. Тобто набране слово не впливало на
   * результат узагалі. Тепер це справжній пошук місця за назвою.
   */
  const handleFullSearch = async () => {
    const query = value.trim();
    if (!query || !onResults) return;
    const rawQueries = expandQuery(query).map((q) => q.toLowerCase());
    const matches = (haystack: string) => rawQueries.some((q) => haystack.includes(q));
    const matchedPois = pois
      .filter((p) => matches(p.name.toLowerCase()) || matches(p.category.toLowerCase()))
      .map((p) => ({
        id: p.id,
        name: p.name,
        category: p.category,
        lat: p.lat,
        lon: p.lon,
      }));
    const matchedRemembered = (geoTaggedFacts ?? [])
      .filter(
        (f) =>
          f.place_lat != null &&
          f.place_lon != null &&
          (matches((f.place_name ?? '').toLowerCase()) ||
            matches((f.content ?? '').toLowerCase()) ||
            matches((f.category ?? '').toLowerCase())),
      )
      .map((f) => ({
        id: f.id,
        name: f.place_name || f.content,
        category: f.category,
        lat: f.place_lat as number,
        lon: f.place_lon as number,
      }));
    try {
      const { results } = await mapApi.geocode(query, 8, { lang: locale });
      onResults({
        remembered: matchedRemembered,
        pois: matchedPois,
        osm: results.map((r, i) => ({
          id: `osm-${i}-${r.lat.toFixed(5)}-${r.lon.toFixed(5)}`,
          name: r.display_name,
          category: t(osmTypeKey(r.type)),
          lat: r.lat,
          lon: r.lon,
        })),
      });
      setSearchQuery('');
    } catch (err) {
      console.error('Пошук не вдався:', err);
      onResults({
        remembered: matchedRemembered,
        pois: matchedPois,
        osm: [],
      });
      setToast(t(geocodeErrorKey(err)));
    }
  };

  return (
    <div className={`relative flex w-full flex-col items-center ${className}`}>
      {localResults && (
        <div 
          className="absolute bottom-full mb-3 w-[440px] p-2 rounded-2xl bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text)] overflow-hidden"
          style={{ maxHeight: 300, overflowY: 'auto' }}
        >
          {localResults.total === 0 ? (
            <div className="p-4 text-center text-xs text-[color:var(--ph-text-3)]">
              {t('msearch.noneLocal')}
            </div>
          ) : (
            <div className="space-y-2">
              {localResults.intel.length > 0 && (
                <div className="space-y-1">
                  <div className="px-3 py-1 text-[9px] font-bold text-[color:var(--ph-text-3)]">{t('msearch.myPlaces')}</div>
                  {localResults.intel.map(poi => (
                    <button
                      key={poi.id}
                      onClick={() => {
                        setCenter([poi.lon, poi.lat]);
                        setZoom(17);
                        setSearchQuery('');
                      }}
                      className="w-full flex items-center gap-2 p-2 rounded-xl hover:bg-[color:var(--ph-raised)] text-left transition-colors"
                    >
                      <MapPin size={14} className="text-[color:var(--ph-text-2)]" />
                      <span className="flex-1 text-xs text-[color:var(--ph-text)] truncate font-display">{poi.name}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Ширина була зашита в 440 px. Поруч у тому ж рядку стоять керування
          й «поруч», і на 1024 вони переставали вміщатись — поле тепер
          віддає зайве сусідам. */}
      <div
        className="flex w-full items-center gap-2 px-3 bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)]"
        style={{ height: 44, borderRadius: 9999, minWidth: 200 }}
      >
        <Search size={16} strokeWidth={1.75} className="text-[color:var(--ph-text-3)]" />
        <input
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleFullSearch();
          }}
          placeholder={expert ? t('msearch.phExpert') : t('msearch.ph')}
          className="flex-1 bg-transparent outline-none border-none text-sm text-[color:var(--ph-text)] placeholder:text-[color:var(--ph-text-3)] font-display"
        />
        <button
          type="button"
          className="inline-flex items-center px-2.5 rounded-full border border-[color:var(--ph-line)] text-[color:var(--ph-text)] text-[11px] font-semibold hover:bg-[color:var(--ph-raised)] transition-colors"
          style={{ height: 28 }}
          onClick={handleFullSearch}
        >
          {t('msearch.go')}
        </button>
      </div>
    </div>
  );
}
