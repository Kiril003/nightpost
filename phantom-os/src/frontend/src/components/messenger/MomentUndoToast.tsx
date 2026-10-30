/** Тост відкату: «Видалено / В архіві · Скасувати». Живе один на екрані, навіть якщо змонтовано кілька хостів. */
import React, { useEffect, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { undoRemoval, useMomentUndo } from '../../services/momentsUndo';

const owners: number[] = [];
const listeners = new Set<() => void>();
let seq = 0;
const notify = () => listeners.forEach((f) => f());

export const MomentUndoToast: React.FC = () => {
  const { t } = useTranslation();
  const pending = useMomentUndo((s) => s.pending);
  const failed = useMomentUndo((s) => s.failed);
  const [id] = useState(() => ++seq);
  const [top, setTop] = useState(false);
  useEffect(() => {
    owners.push(id);
    notify();
    return () => {
      owners.splice(owners.indexOf(id), 1);
      notify();
    };
  }, [id]);
  useEffect(() => {
    const f = () => setTop(owners[owners.length - 1] === id);
    listeners.add(f);
    f();
    return () => {
      listeners.delete(f);
    };
  }, [id]);
  useEffect(() => {
    if (!failed) return undefined;
    const timer = window.setTimeout(() => useMomentUndo.setState({ failed: false }), 4000);
    return () => window.clearTimeout(timer);
  }, [failed]);
  if (!top || (!pending && !failed)) return null;
  return (
    <div className="pointer-events-none fixed inset-x-4 z-[1400] flex justify-center" style={{ bottom: 'calc(var(--sab, 0px) + 16px)' }}>
      <div
        role="status"
        data-moment-undo
        className="pointer-events-auto w-full max-w-[420px] min-h-12 pl-4 pr-2 py-2 flex items-center gap-2 rounded-card bg-raised border border-line-strong text-subhead text-ink f-toast-in"
      >
        <span className="min-w-0 flex-1">{pending ? (pending.archive ? t('momentsR.archived') : t('momentsR.removed')) : t('moments.deleteFailed')}</span>
        {pending && (
          <button
            type="button"
            data-moment-undo-run
            onClick={undoRemoval}
            className="h-11 px-3 rounded-control text-subhead font-semibold text-accent-text hover:bg-overlay"
          >
            {t('momentsR.undo')}
          </button>
        )}
      </div>
    </div>
  );
};

export default MomentUndoToast;
