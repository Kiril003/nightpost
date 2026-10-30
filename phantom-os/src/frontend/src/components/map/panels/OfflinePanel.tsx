import { OfflineRegionManager } from '../hud/OfflineRegionManager';
import { X } from 'lucide-react';

export interface OfflinePanelProps {
  open: boolean;
  onClose: () => void;
}

export function OfflinePanel({ open, onClose }: OfflinePanelProps): JSX.Element | null {
  if (!open) return null;

  return (
    <div
      className="absolute inset-0 z-40 bg-[color:var(--ph-scrim)] flex justify-start pointer-events-auto"
      onClick={onClose}
    >
      <div 
        className="relative self-start pointer-events-auto ml-[72px] mt-20 animate-in zoom-in-95 duration-200"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          onClick={onClose}
          className="absolute -top-3 -right-3 z-10 p-2 rounded-full bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ink-muted)] hover:text-[color:var(--ink-primary)] transition-all active:scale-90"
        >
          <X size={14} />
        </button>
        <OfflineRegionManager className="border border-[color:var(--ph-line)]" />
      </div>
    </div>
  );
}
