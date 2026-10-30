import { Loader2, MonitorSmartphone } from 'lucide-react';

/**
 * Пристрої й місця — однією деталлю на обидві двері кабінету.
 *
 * Раніше той самий рядок малювали двічі: портал за ключем і вкладка за
 * поштою. Дві копії однієї речі розходяться завжди — питання лише коли.
 */
export interface SeatDevice {
  fingerprint: string;
  name: string;
  activated_at: string;
}

export interface DeviceListLabels {
  title: string;
  empty: string;
  activated: string;
  release: string;
  releasing: string;
  free: (n: number) => string;
}

const ICON = { strokeWidth: 1.75 } as const;

/** Місця як клітинки: зайняте — суцільне, вільне — пунктир. Понад 24 — смуга. */
export function SeatGrid({ used, max }: { used: number; max: number }) {
  if (max > 24) {
    const pct = Math.min(100, (used / max) * 100);
    return (
      <div className="mt-4 h-1.5 w-full overflow-hidden rounded-full bg-overlay" aria-hidden>
        <span className="block h-full rounded-full bg-accent-line" style={{ width: `${pct}%` }} />
      </div>
    );
  }
  return (
    <div className="mt-4 flex flex-wrap gap-1.5" aria-hidden>
      {Array.from({ length: max }).map((_, i) => (
        <span
          key={i}
          className={`h-6 w-6 rounded-chip border ${
            i < used ? 'border-transparent bg-accent-line' : 'border-dashed border-line-strong'
          }`}
        />
      ))}
    </div>
  );
}

export function DeviceList({
  devices,
  max,
  labels,
  hint,
  releasing,
  onRelease,
}: {
  devices: SeatDevice[];
  max: number;
  labels: DeviceListLabels;
  hint?: string;
  releasing: string | null;
  onRelease: (fingerprint: string) => void;
}) {
  const free = Math.max(0, max - devices.length);

  return (
    <section className="card p-5 sm:p-6">
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="text-headline text-ink">{labels.title}</h2>
        <span className="tnum text-footnote text-ink-2">
          {devices.length}/{max}
        </span>
      </div>

      {devices.length === 0 ? (
        <p className="mt-3 text-callout text-ink-2">{labels.empty}</p>
      ) : (
        <ul className="mt-2 divide-y divide-line">
          {devices.map((device) => (
            <li
              key={device.fingerprint}
              className="flex flex-col gap-3 py-3 sm:flex-row sm:items-center sm:justify-between"
            >
              <div className="flex min-w-0 items-start gap-3">
                <MonitorSmartphone className="mt-0.5 h-5 w-5 shrink-0 text-ink-2" {...ICON} aria-hidden />
                <div className="min-w-0">
                  <p className="break-words text-callout font-semibold text-ink">
                    {device.name || device.fingerprint.slice(0, 12)}
                  </p>
                  {/* Хвіст відбитка — щоб розрізнити два однаково названі пристрої. */}
                  <p className="text-footnote text-ink-2">
                    <span className="font-mono">…{device.fingerprint.slice(-8)}</span> · {labels.activated}{' '}
                    <span className="tnum">{device.activated_at.slice(0, 10)}</span>
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => onRelease(device.fingerprint)}
                disabled={releasing !== null}
                className="btn-secondary w-full sm:w-auto"
              >
                {releasing === device.fingerprint ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" {...ICON} aria-hidden />
                    {labels.releasing}
                  </>
                ) : (
                  labels.release
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

      {free > 0 && devices.length > 0 && <p className="mt-2 text-footnote text-ink-2">{labels.free(free)}</p>}
      {hint && <p className="mt-4 border-t border-line pt-4 text-footnote text-ink-2">{hint}</p>}
    </section>
  );
}
