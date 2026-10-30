import { Search, MapPin, Navigation, Landmark, Heart, X, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { tNow, currentDateTag } from '../../chat/tNow';

/**
 * Phase 24-G — Search Results Panel.
 *
 * Displays unified results from SearchBar (Remembered, OSM, POIs).
 */

export interface SearchResult {
  id: string;
  name: string;
  category: string;
  lat: number;
  lon: number;
  type?: string;
  distance_m?: number;
  tags?: Record<string, string>;
}

export interface SearchResultsPanelProps {
  open: boolean;
  onClose: () => void;
  results: {
    remembered: SearchResult[];
    osm: SearchResult[];
    pois: SearchResult[];
  };
  onSelect: (res: SearchResult) => void;
}

export function SearchResultsPanel({ open, onClose, results, onSelect }: SearchResultsPanelProps) {
  const { t } = useTranslation();
  if (!open) return null;

  const total = results.remembered.length + results.osm.length + results.pois.length;

  return (
    <div className="absolute top-20 left-[84px] w-80 max-h-[calc(100vh-230px)] flex flex-col bg-[color:var(--ph-surface)] text-[color:var(--ph-text)] border border-[color:var(--ph-line)] rounded-2xl overflow-hidden z-20">
      <div className="p-4 border-b border-[color:var(--ph-line)] flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Search size={14} className="text-[color:var(--ph-text-2)]" />
          <span className="text-sm font-semibold">{t('sres.title', { n: total })}</span>
        </div>
        <button
          onClick={onClose}
          aria-label={t('sres.close')}
          className="p-1 rounded-full hover:bg-[color:var(--ph-raised)] text-[color:var(--ph-text-3)] transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar">
        {total === 0 ? (
          <div className="py-20 text-center space-y-2 text-[color:var(--ph-text-3)]">
            <Search size={32} className="mx-auto" />
            <div className="text-sm">{t('sres.none')}</div>
          </div>
        ) : (
          <div className="p-2 space-y-6">
            {results.pois.length > 0 && (
              <ResultSection 
                title={t('sres.saved')} 
                icon={<Heart size={10} />} 
                items={results.pois} 
                onSelect={onSelect}
              />
            )}
            
            {results.remembered.length > 0 && (
              <ResultSection 
                title={t('sres.memory')}
                icon={<Landmark size={10} />}
                items={results.remembered}
                onSelect={onSelect}
              />
            )}

            {results.osm.length > 0 && (
              <ResultSection 
                title={t('sres.map')}
                icon={<MapPin size={10} />}
                items={results.osm}
                onSelect={onSelect}
              />
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function ResultSection({ title, icon, items, onSelect }: {
  title: string,
  icon: React.ReactNode,
  items: SearchResult[],
  onSelect: (res: SearchResult) => void,
}) {
  return (
    <div className="space-y-1">
      <div className="px-2 flex items-center gap-1.5 text-xs font-semibold text-[color:var(--ph-text-2)] mb-2">
        {icon}
        <span>{title}</span>
      </div>
      <div className="space-y-0.5">
        {items.map((item) => (
          <button
            key={item.id}
            onClick={() => onSelect(item)}
            className="w-full text-left p-2.5 rounded-xl hover:bg-[color:var(--ph-raised)] transition-colors flex items-center gap-3"
          >
            <div className="w-8 h-8 rounded-lg bg-[color:var(--ph-raised)] flex items-center justify-center text-[color:var(--ph-text-3)] shrink-0">
              <Navigation size={14} />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm text-[color:var(--ph-text)] truncate">{item.name}</div>
              <div className="flex items-center gap-2 mt-0.5">
                <span className="text-xs text-[color:var(--ph-text-3)] truncate">{item.type || item.category}</span>
                {item.distance_m != null && (
                  <span className="text-xs text-[color:var(--ph-text-3)] tabular-nums shrink-0">
                    {item.distance_m > 1000 ? tNow('peerWire.km', { n: new Intl.NumberFormat(currentDateTag(), { maximumFractionDigits: 1 }).format(item.distance_m / 1000) }) : tNow('peerWire.meters', { n: item.distance_m })}
                  </span>
                )}
              </div>
            </div>
            <ChevronRight size={12} className="text-[color:var(--ph-text-3)] shrink-0" />
          </button>
        ))}
      </div>
    </div>
  );
}
