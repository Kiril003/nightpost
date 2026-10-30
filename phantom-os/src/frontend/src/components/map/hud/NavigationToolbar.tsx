import { Minus, Plus, Crosshair, MapPin } from 'lucide-react';
import { useMapStore } from '../../../stores/mapStore';
import { useTranslation } from '../../../i18n/useTranslation';

export function NavigationToolbar({
  onZoomIn,
  onZoomOut,
  onCenter,
  onAddPoi,
  fix,
}: {
  onZoomIn: () => void;
  onZoomOut: () => void;
  onCenter: () => void;
  onAddPoi: () => void;
  fix: boolean;
}) {
  const { t } = useTranslation();
  const cardOpen = useMapStore((s) => s.tappedPlace != null);
  return (
    <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-1 px-2 h-[48px] rounded-full">
      <ToolbarButton icon={<Minus size={16} strokeWidth={1.75} />} onClick={onZoomOut} label={t('navbar.zoomOut')} />
      <ToolbarButton icon={<Plus size={16} strokeWidth={1.75} />} onClick={onZoomIn} label={t('navbar.zoomIn')} />
      <span className="w-px h-5 bg-[color:var(--ph-line)] mx-1" />
      {/* Без фіксу кнопка була вимкнена, але підпис лишався «До мого місця» —
          обіцянка, якої вона в цю мить не виконає. Сірий кружечок мовчить про
          причину: людина тисне й нічого не стається. Вимкнений контрол мусить
          називати, чого саме бракує, а не лише гаснути. */}
      <ToolbarButton
        icon={<Crosshair size={16} strokeWidth={1.75} />}
        onClick={onCenter}
        label={fix ? t('navbar.toMe') : t('navbar.noFix')}
        disabled={!fix}
      />
      <ToolbarButton 
        icon={<MapPin size={16} strokeWidth={1.75} />} 
        onClick={onAddPoi} 
        label={t('navbar.mark')} 
        accent={!cardOpen}
      />
    </div>
  );
}

function ToolbarButton({
  icon,
  onClick,
  label,
  accent = false,
  disabled = false,
}: {
  icon: React.ReactNode;
  onClick: () => void;
  label: string;
  accent?: boolean;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex items-center justify-center w-11 h-11 min-w-[44px] min-h-[44px] rounded-full transition-all active:scale-90 ${
        accent ? 'bg-[color:var(--ph-accent)] text-[color:var(--ph-on-accent)]' : 'text-ink-secondary hover:bg-[color:var(--ph-overlay)]'
      } ${disabled ? 'opacity-30' : 'opacity-100'}`}
      title={label}
    >
      {icon}
    </button>
  );
}
