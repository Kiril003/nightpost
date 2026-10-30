/**
 * ToastRail — глобальний тост (дизайн-система §7.10).
 *
 * Знизу, 16 над композером/навігацією, до 420 завширшки; `raised`, тінь e2,
 * радіус `card`, текст `subhead`. Один за раз: новий заступає старий. 4 с.
 * Без іконок, крім `circle-alert` для помилки. Поява — підйом на 8 і
 * прозорість за 240 мс, зникнення — згасання за 180 мс. Дотик закриває.
 */
import { useEffect } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { CircleAlert } from 'lucide-react';
import { useUIStore, type Toast } from '../../stores/uiStore';

const DISMISS_MS = 4000;
const ACTION_DISMISS_MS = 6000;

export function ToastRail() {
  const toasts = useUIStore((s) => s.toasts);
  // Один за раз (§7.10): показуємо найсвіжіший; старіші доживають своє мовчки.
  const shown = toasts.length ? toasts[toasts.length - 1] : null;
  return (
    <div
      className="pointer-events-none fixed inset-x-4 z-[1300] flex justify-center"
      style={{ bottom: 'calc(var(--sab, 0px) + 80px)' }}
      aria-live="polite"
    >
      <AnimatePresence initial={false} mode="wait">
        {shown && <ToastItem key={shown.id} toast={shown} />}
      </AnimatePresence>
      {/* Старіші тости теж мусять зникнути за свій час. */}
      {toasts.slice(0, -1).map((t) => (
        <Expire key={t.id} toast={t} />
      ))}
    </div>
  );
}

function Expire({ toast }: { toast: Toast }) {
  const dismissToast = useUIStore((s) => s.dismissToast);
  useEffect(() => {
    const id = window.setTimeout(() => dismissToast(toast.id), DISMISS_MS);
    return () => window.clearTimeout(id);
  }, [toast.id, dismissToast]);
  return null;
}

function ToastItem({ toast }: { toast: Toast }) {
  const dismissToast = useUIStore((s) => s.dismissToast);
  const reduce = useReducedMotion();

  useEffect(() => {
    const id = window.setTimeout(() => dismissToast(toast.id), toast.action ? ACTION_DISMISS_MS : DISMISS_MS);
    return () => window.clearTimeout(id);
  }, [toast.id, toast.action, dismissToast]);

  const enter = reduce ? { opacity: 0 } : { opacity: 0, y: 8 };
  const show = { opacity: 1, y: 0, transition: { duration: reduce ? 0.15 : 0.24, ease: [0, 0, 0, 1] } };
  const leave = { opacity: 0, transition: { duration: reduce ? 0.15 : 0.18, ease: [0.3, 0, 1, 1] } };

  if (toast.action) {
    const action = toast.action;
    return (
      <motion.div
        role={toast.kind === 'error' ? 'alert' : 'status'}
        className="pointer-events-auto relative overflow-hidden w-full max-w-[420px] min-h-12 pl-4 pr-1 flex items-center gap-2 rounded-card bg-raised shadow-e2 text-subhead text-ink"
        initial={enter}
        animate={show}
        exit={leave}
      >
        <button type="button" onClick={() => dismissToast(toast.id)} className="min-w-0 flex-1 min-h-11 text-left">
          {toast.message}
        </button>
        <button
          type="button"
          data-testid="toast-action"
          onClick={() => {
            dismissToast(toast.id);
            action.run();
          }}
          className="shrink-0 min-h-11 min-w-11 px-3 rounded-card text-subhead font-medium text-accent-text"
        >
          {action.label}
        </button>
        <span aria-hidden data-testid="toast-timer" className="f-toast-timer absolute left-0 bottom-0 h-0.5 w-full bg-accent" style={{ animationDuration: `${ACTION_DISMISS_MS}ms` }} />
      </motion.div>
    );
  }

  return (
    <motion.button
      type="button"
      role={toast.kind === 'error' ? 'alert' : 'status'}
      onClick={() => dismissToast(toast.id)}
      className="pointer-events-auto w-full max-w-[420px] min-h-12 px-4 py-3 flex items-center gap-2 rounded-card bg-raised shadow-e2 text-subhead text-ink text-left"
      initial={enter}
      animate={show}
      exit={leave}
    >
      {toast.kind === 'error' && <CircleAlert aria-hidden className="w-4 h-4 shrink-0 text-danger" strokeWidth={1.75} />}
      <span className="min-w-0 flex-1">{toast.message}</span>
    </motion.button>
  );
}
