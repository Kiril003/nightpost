/**
 * Документ у розмові (desk): картка листа `widget:canvas`, який поклав «У чат»
 * з документа розмови (CanvasSplitView). Назва, скільки рішень і завдань, і
 * «Відкрити» — до документа поруч зі стрічкою.
 */
import React from 'react';
import { FileText, ChevronRight } from 'lucide-react';
import { CanvasDocument } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { tNow, tPlural } from '../../chat/tNow';
interface CanvasCardEmbedProps {
  data: CanvasDocument;
  isSelf?: boolean;
  onOpenSplit?: (doc: CanvasDocument) => void;
  onUpdate?: (updated: CanvasDocument) => void;
}

/** «2 рішення · 3 завдання» — лише те, що в документі справді є. */
export const canvasSummary = (doc: CanvasDocument): string => {
  const decisions = doc.blocks?.filter((b) => b.type === 'decision').length ?? 0;
  const tasks = doc.blocks?.filter((b) => b.type === 'action-item').length ?? 0;
  const parts = [
    decisions > 0 ? `${decisions} ${tPlural(decisions, 'canvasCardEmbed.decisions')}` : '',
    tasks > 0 ? `${tasks} ${tPlural(tasks, 'canvasCardEmbed.tasks')}` : '',
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : tNow('canvasCardEmbed.document');
};

export const CanvasCardEmbed: React.FC<CanvasCardEmbedProps> = ({ data, onOpenSplit }) => (
  <button
    type="button"
    onClick={() => {
      soundFx.playTap();
      onOpenSplit?.(data);
    }}
    className="min-h-14 w-full max-w-sm mt-1 px-3 py-2.5 rounded-card bg-surface hover:bg-overlay text-left text-ink flex items-center gap-3 transition-colors duration-instant ease-standard select-none"
  >
    <span className="w-9 h-9 rounded-control bg-overlay flex items-center justify-center text-ink-2 shrink-0">
      <FileText className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
    </span>
    <span className="min-w-0 flex-1">
      <span className="block text-headline text-ink truncate">{data.title || tNow('canvasCardEmbed.document')}</span>
      <span className="block text-footnote text-ink-2 truncate">{canvasSummary(data)}</span>
    </span>
    <span className="shrink-0 flex items-center gap-0.5 text-subhead text-accent-text">
      {tNow('canvasCardEmbed.open')}
      <ChevronRight className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
    </span>
  </button>
);
