import React, { useContext, useId, useLayoutEffect } from 'react';
import { CardMetaContext } from './cardMeta';

export interface TicketCardProps {
  title: string;
  status: string;
  kind?: { label: string; icon?: React.ReactNode; tag?: React.ReactNode };
  lines?: React.ReactNode;
  perforated?: boolean;
  testId?: string;
  /** `primary` — головна дія картки: бурштин. Решта — контур. */
  action?: { label: string; onClick: () => void; primary?: boolean };
  children?: React.ReactNode;
}

/** Перфорація квитка: пунктир і два вирізи кольору тла розмови по краях. */
const Perforation: React.FC = () => (
  <div data-card-perf aria-hidden className="relative h-0 mx-3.5">
    <svg className="absolute inset-x-0 -top-px w-full h-0.5 text-line-strong" preserveAspectRatio="none">
      <line x1="0" y1="1" x2="100%" y2="1" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 4" />
    </svg>
    <span className="absolute -top-[9px] -left-[23px] w-[18px] h-[18px] rounded-full bg-wall border border-line" />
    <span className="absolute -top-[9px] -right-[23px] w-[18px] h-[18px] rounded-full bg-wall border border-line" />
  </div>
);

export const TicketCard: React.FC<TicketCardProps> = ({ title, status, kind, lines, perforated, testId, action, children }) => {
  const meta = useContext(CardMetaContext);
  const id = useId();
  const claim = meta?.claim;
  const release = meta?.release;
  useLayoutEffect(() => {
    if (!claim || !release) return;
    claim(id);
    return () => release(id);
  }, [claim, release, id]);
  const metaNode = meta && meta.owner === id ? meta.node : null;
  return (
    <section
      data-kit="ticket-card"
      data-testid={testId}
      className="relative w-[300px] max-w-full rounded-bubble bg-surface border border-line text-ink overflow-hidden select-none"
    >
      <header className="px-4 pt-3.5 pb-3">
        {kind && (
          <div data-card-kind className="mb-1.5 flex items-center gap-1.5 text-caption font-semibold text-ink-3">
            {kind.icon && (
              <span aria-hidden className="w-4 h-4 shrink-0 flex items-center justify-center [&>svg]:w-4 [&>svg]:h-4">
                {kind.icon}
              </span>
            )}
            <span className="truncate">{kind.label}</span>
            {kind.tag}
          </div>
        )}
        <h3 className="text-title-2 text-ink line-clamp-2 break-words" data-testid="card-title">
          {title}
        </h3>
        <p className="mt-0.5 text-subhead font-normal text-ink-2 break-words tnum" data-testid="card-status">
          {status}
        </p>
        {lines}
      </header>
      {perforated && <Perforation />}
      {(children || action || metaNode) && (
        <div className={`${perforated ? 'pt-2' : ''} ${children || action ? 'pb-3' : '-mt-1.5 pb-2.5'}`}>
          {children}
          {(action || metaNode) && (
            <div data-card-actions={action ? '' : undefined} className={`px-4 ${children || action ? 'pt-2' : ''} flex items-center gap-2`}>
              {action && (
                <button
                  type="button"
                  data-kit="button"
                  data-kit-variant={action.primary ? 'primary' : 'outline'}
                  onClick={(e) => {
                    e.stopPropagation();
                    action.onClick();
                  }}
                  className={`touch-floor-inline h-9 px-4 rounded-full text-subhead font-semibold ${
                    action.primary ? 'bg-accent text-on-accent active:opacity-80' : 'border border-line-strong text-ink active:bg-ink/[.06]'
                  }`}
                >
                  {action.label}
                </button>
              )}
              {metaNode && (
                <span data-card-meta className="ml-auto inline-flex items-center gap-1 text-caption text-ink-3 tnum select-none">
                  {metaNode}
                </span>
              )}
            </div>
          )}
        </div>
      )}
    </section>
  );
};
