import { useState } from 'react';
import { Route, Navigation, MapPin, ArrowRight, Loader2, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';

/**
 * Phase 24-C — Routing tool.
 *
 * Collects an origin + destination and asks the backend to plan a route
 * (ORS → OSRM fallback via `/map/route`; BRouter is off by default).
 * An empty origin means "from my current position". The planned route
 * is drawn by `RouteLayer`; this panel only surfaces progress, the result summary, and a clear
 * affordance.
 */

export interface RoutingToolProps {
  active?: boolean;
  onToggle?: () => void;
  onPlan?: (from: string, to: string) => void;
  /** True while a plan request (geocode + route) is in flight. */
  loading?: boolean;
  /** Last failure message, shown inline. */
  error?: string | null;
  /** Preformatted summary of the active route (e.g. "12.4 км · 18 хв"). */
  summary?: string | null;
  /** Clear the active route + inputs. */
  onClear?: () => void;
}

export function RoutingTool({
  active = false,
  onToggle,
  onPlan,
  loading = false,
  error = null,
  summary = null,
  onClear,
}: RoutingToolProps): JSX.Element {
  const { t } = useTranslation();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const clear = () => {
    setFrom('');
    setTo('');
    onClear?.();
  };

  return (
    <div
      data-testid="routing-tool"
      className={`bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex flex-col gap-2 rounded-2xl p-2 text-[color:var(--ink-primary)] transition-all ${
        active ? 'w-[240px]' : 'w-[44px] overflow-hidden'
      }`}
    >
      <button
        onClick={onToggle}
        className={`min-h-[44px] min-w-[44px] flex items-center justify-center gap-2 rounded-lg transition-all ${
          active ? 'text-[color:var(--ph-text)]' : 'text-[color:var(--ink-secondary)]'
        }`}
      >
        <Route size={18} strokeWidth={2} className="ml-1" />
        {active && <span className="text-[10px] font-bold">{t('route.title')}</span>}
      </button>

      {active && (
        <div className="flex flex-col gap-3 p-1 animate-in fade-in slide-in-from-top-1">
          <div className="space-y-1">
            <div className="flex items-center gap-2 px-2 py-1.5 rounded-lg border border-[color:var(--ph-line)] bg-[color:var(--ph-raised)]">
              <MapPin size={12} className="text-[color:var(--ph-success)]" />
              <input
                type="text"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                placeholder={t('route.fromPh')}
                className="flex-1 bg-transparent text-xs outline-none"
              />
            </div>
            <div className="flex items-center gap-2 px-2 py-1.5 rounded-lg border border-[color:var(--ph-line)] bg-[color:var(--ph-raised)]">
              <Navigation size={12} className="text-[color:var(--ph-text-2)]" />
              <input
                type="text"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && to.trim() && !loading) onPlan?.(from, to);
                }}
                placeholder={t('route.toPh')}
                className="flex-1 bg-transparent text-xs outline-none"
              />
            </div>
          </div>

          <button
            onClick={() => onPlan?.(from, to)}
            disabled={!to.trim() || loading}
            className="flex min-h-[44px] w-full items-center justify-center gap-2 rounded-xl border border-[color:var(--ph-line-strong)] bg-[color:var(--ph-raised)] text-[10px] font-bold text-[color:var(--ph-text)] transition-all hover:bg-[color:var(--ph-overlay)] disabled:opacity-30"
          >
            {loading ? (
              <>
                <Loader2 size={12} className="animate-spin" />
                {t('route.building')}
              </>
            ) : (
              <>
                {t('route.build')}
                <ArrowRight size={12} />
              </>
            )}
          </button>

          {error && (
            <div className="px-2 text-[11px] leading-tight text-[color:var(--ph-danger)]" role="alert">
              {error}
            </div>
          )}

          {summary && !loading && (
            <div className="flex items-center justify-between gap-2 rounded-lg border border-[color:var(--ph-line)] bg-[color:var(--ph-raised)] px-2 py-1.5">
              <span className="text-[11px] font-semibold text-[color:var(--ink-primary)]">{summary}</span>
              <button
                onClick={clear}
                aria-label={t('route.clear')}
                className="flex min-h-[32px] min-w-[32px] items-center justify-center rounded-md text-[color:var(--ink-muted)] transition-all hover:bg-[color:var(--ph-overlay)]"
              >
                <X size={12} />
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
