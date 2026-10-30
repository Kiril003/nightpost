import { useTranslation } from '../../i18n/useTranslation';

export function TilesDownBanner({ reason, onRetry }: { reason: string; onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div
      data-testid="map-tiles-down"
      role="status"
      title={reason}
      className="absolute top-4 left-1/2 z-40 -translate-x-1/2 flex items-center gap-2 rounded-full border border-[color:var(--ph-line)] bg-[color:var(--ph-surface)] pl-4 pr-1 py-1 text-[12px] text-[color:var(--ph-text)]"
    >
      <span>{t('map.tiles.offline')}</span>
      <span aria-hidden className="text-[color:var(--ph-text-3)]">·</span>
      <button
        type="button"
        onClick={onRetry}
        className="min-h-9 px-3 rounded-full font-semibold text-[color:var(--ph-text)] hover:bg-[color:var(--ph-raised)]"
      >
        {t('map.tiles.retry')}
      </button>
    </div>
  );
}
