/**
 * Місцевий тост для екрана месенджера — той самий вигляд, що й глобальний
 * ToastRail (§7.10), коли повідомлення належить саме цьому шару.
 * Необовʼязкова дія — привид-кнопка `accent-text`; тоді живе 6 с, інакше 4.
 */
import React, { useEffect } from 'react';

export const Toast: React.FC<{
  message: string;
  onDone: () => void;
  action?: { label: string; run: () => void };
  error?: boolean;
}> = ({ message, onDone, action }) => {
  useEffect(() => {
    const id = window.setTimeout(onDone, action ? 6000 : 4000);
    return () => window.clearTimeout(id);
  }, [message, action, onDone]);
  return (
    <div className="pointer-events-none absolute inset-x-4 z-[1300] flex justify-center" style={{ bottom: 'calc(var(--sab, 0px) + 16px)' }}>
      <div role="status" className="pointer-events-auto w-full max-w-[420px] min-h-12 pl-4 pr-2 py-2 flex items-center gap-2 rounded-card bg-raised border border-line-strong text-subhead text-ink f-toast-in">
        <span className="min-w-0 flex-1">{message}</span>
        {action && (
          <button
            type="button"
            onClick={() => {
              action.run();
              onDone();
            }}
            className="h-11 px-3 rounded-control text-subhead font-semibold text-accent-text hover:bg-overlay"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
};

export default Toast;
