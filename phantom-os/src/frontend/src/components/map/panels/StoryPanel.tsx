import { useState, useEffect } from 'react';
import { Sparkles, X, History } from 'lucide-react';
import { mapApi } from '../../../services/api';
import { useTranslation } from '../../../i18n/useTranslation';

/**
 * Phase 24-H — Story Panel.
 *
 * The map's "consciousness". Displays agent insights, lessons learned
 * in the current area, and mission narrative. Connects the visual
 * state to the system's episodic memory.
 */

export interface MapEvent {
  id: string;
  timestamp: string;
  type: 'insight' | 'lesson' | 'alert' | 'fact';
  content: string;
  lat?: number;
  lon?: number;
  importance: number;
}

export interface StoryPanelProps {
  open: boolean;
  onClose: () => void;
  viewportCenter?: [number, number]; // lat, lon
}

export function StoryPanel({ open, onClose, viewportCenter }: StoryPanelProps) {
  const { t } = useTranslation();
  const [events, setEvents] = useState<MapEvent[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open) {
      fetchNarrative();
    }
  }, [open, viewportCenter]);

  const fetchNarrative = async () => {
    setLoading(true);
    try {
      // Fetch combined insights: Geo-tagged facts + distilled lessons
      const factsRes = await mapApi.getGeoTaggedFacts(20);
      
      const mappedEvents: MapEvent[] = factsRes.facts.map((f: any) => ({
        id: f.id,
        timestamp: f.created_at,
        type: 'fact',
        content: f.content,
        lat: f.place_lat,
        lon: f.place_lon,
        importance: f.importance || 1
      }));

      // Sort by recency
      mappedEvents.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
      setEvents(mappedEvents);
    } catch (err) {
      console.error('Failed to fetch map narrative:', err);
    } finally {
      setLoading(false);
    }
  };

  if (!open) return null;

  return (
    <div className="absolute top-20 right-[84px] w-80 max-h-[calc(100vh-230px)] flex flex-col bg-[color:var(--ph-surface)] text-[color:var(--ph-text)] border border-[color:var(--ph-line)] rounded-2xl overflow-hidden z-20">
      <div className="p-4 border-b border-[color:var(--ph-line)] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <History size={16} className="text-[color:var(--ph-text-2)]" />
          <div className="text-sm font-semibold">{t('story.title')}</div>
        </div>
        <button
          onClick={onClose}
          aria-label={t('story.close')}
          className="p-2 rounded-xl hover:bg-[color:var(--ph-raised)] text-[color:var(--ph-text-3)] transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      {/* Narrative Body */}
      <div className="flex-1 overflow-y-auto p-4 space-y-6 custom-scrollbar">
        {loading && events.length === 0 ? (
          <div className="py-20 text-center text-sm text-[color:var(--ph-text-3)]">{t('story.recalling')}</div>
        ) : events.length === 0 ? (
          <div className="py-20 text-center space-y-3 text-[color:var(--ph-text-3)]">
            <History size={32} className="mx-auto" />
            <div className="text-sm px-10">{t('story.empty')}</div>
          </div>
        ) : (
          <div className="space-y-4">
            {events.map((ev) => (
              <NarrativeCard key={ev.id} event={ev} />
            ))}
          </div>
        )}
      </div>

    </div>
  );
}

function NarrativeCard({ event }: { event: MapEvent }) {
  const isHighImportance = event.importance > 3;

  return (
    <div 
      className={`p-4 rounded-2xl border ${
        isHighImportance ? 'border-[color:var(--ph-text-2)]' : 'border-[color:var(--ph-line)]'
      }`}
    >
      <div className="flex items-start gap-3">
        <div className={`mt-1 w-1.5 h-1.5 rounded-full shrink-0 ${
          isHighImportance ? 'bg-[color:var(--ph-text)]' : 'bg-[color:var(--ph-line)]'
        }`} />
        <div className="flex-1 min-w-0">
          <div className="text-xs text-[color:var(--ph-text-3)] mb-1 flex items-center justify-between">
            <span className="tabular-nums">
              {new Date(event.timestamp).toLocaleTimeString('uk-UA', { hour: '2-digit', minute: '2-digit' })}
            </span>
            {event.type === 'lesson' && <Sparkles size={10} className="text-[color:var(--ph-text-2)]" />}
          </div>
          <div className="text-sm leading-relaxed text-[color:var(--ph-text)]">
            {event.content}
          </div>
        </div>
      </div>
    </div>
  );
}
