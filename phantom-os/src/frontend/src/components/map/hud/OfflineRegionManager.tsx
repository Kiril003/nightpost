import { useEffect, useState } from 'react';
import { Trash2, Database, RefreshCw, HardDrive } from 'lucide-react';
import { mapApi, type OfflineRegion } from '../../../services/api';
import { useMapStore } from '../../../stores/mapStore';
import { useTranslation } from '../../../i18n/useTranslation';

/**
 * Regions already on disk (PMTiles) with their size; each can be deleted.
 * The node has no download endpoint, so there is no download action here.
 */

export interface OfflineRegionManagerProps {
  className?: string;
}

export function OfflineRegionManager({ className = '' }: OfflineRegionManagerProps): JSX.Element {
  const { t } = useTranslation();
  const [regions, setRegions] = useState<OfflineRegion[]>(() => useMapStore.getState().offlineRegions ?? []);
  const [used, setUsed] = useState(0);
  const [capacity, setCapacity] = useState(0);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await mapApi.getOfflineRegions();
      const next = res.regions ?? [];
      setRegions(next);
      setUsed(res.used_bytes ?? 0);
      setCapacity(res.capacity_bytes ?? 0);
      useMapStore.getState().setOfflineRegions(next);
    } catch (e) {
      console.error('Failed to load offline regions:', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const formatSize = (bytes: number) => {
    const gb = bytes / (1024 * 1024 * 1024);
    if (gb >= 1) return `${gb.toFixed(2)} GB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const pct = capacity > 0 ? (used / capacity) * 100 : 0;

  return (
    <div
      data-testid="offline-region-manager"
      className={`flex flex-col gap-3 p-4 w-[320px] rounded-2xl bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ink-primary)] ${className}`}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Database size={16} className="text-[color:var(--ph-text-2)]" />
          <span className="text-xs font-semibold">{t('offreg.title')}</span>
        </div>
        <button
          onClick={load}
          className="p-1.5 rounded-full hover:bg-[color:var(--ph-overlay)] transition-colors"
          title={t('offreg.refresh')}
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
        </button>
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between text-[12px] text-[color:var(--ink-muted)]">
          <span>{t('offreg.here')}</span>
          <span>{formatSize(used)} / {formatSize(capacity)}</span>
        </div>
        <div className="h-1.5 w-full bg-[color:var(--ph-line)] rounded-full overflow-hidden">
          <div
            className="h-full bg-[color:var(--ph-text-2)] rounded-full transition-all duration-500"
            style={{ width: `${Math.min(100, pct)}%` }}
          />
        </div>
      </div>

      <div className="max-h-[240px] overflow-y-auto space-y-2 pr-1 custom-scrollbar">
        {regions.length === 0 && !loading && (
          <div className="py-8 text-center text-[color:var(--ink-muted)] italic text-xs">
            {t('offreg.none')}
          </div>
        )}
        {regions.map((r) => (
          <div
            key={r.id}
            className="group flex items-center justify-between p-2.5 rounded-xl bg-[color:var(--ph-raised)] border border-[color:var(--ph-line)] hover:border-[color:var(--ph-line-strong)] transition-all"
          >
            <div className="flex flex-col gap-0.5">
              <span className="text-[11px] font-medium text-[color:var(--ink-primary)]">{r.name}</span>
              <div className="flex items-center gap-2 text-[11px] text-[color:var(--ink-muted)]">
                <span className="flex items-center gap-1">
                  <HardDrive size={8} />
                  {formatSize(r.size_bytes)}
                </span>
                <span>•</span>
                <span>{r.layers.join(', ')}</span>
              </div>
            </div>
            <button
              onClick={async () => {
                if (confirm(t('offreg.deleteQ', { name: r.name }))) {
                  await mapApi.deleteOfflineRegion(r.id);
                  await load();
                }
              }}
              aria-label={t('offreg.deleteAria', { name: r.name })}
              className="p-2 text-[color:var(--ink-muted)] hover:text-[color:var(--ph-danger)] hover:bg-[color:var(--ph-overlay)] rounded-lg transition-all opacity-0 group-hover:opacity-100"
            >
              <Trash2 size={14} />
            </button>
          </div>
        ))}
      </div>

    </div>
  );
}
