import { useEffect, useMemo, useRef, useState } from 'react';
import { Loader2, RotateCcw, Smartphone } from 'lucide-react';
import { pollQrAuth, startLinkQr } from '../../lib/account-api';
import { linkPhone, type LinkState } from '../../lib/connectPhone';
import { encodeQrMatrix, encodeQrSvgPath } from '../../lib/qr';
import type { HomeCopy } from '../../i18n/accountHome';

const ICON = { strokeWidth: 1.75 } as const;

/** Записи й повідомлення з публічних сторінок ідуть у застосунок: телефон сканує QR і читає їх своїм ключем. */
export default function ConnectPhone({ h }: { h: HomeCopy }) {
  const [state, setState] = useState<LinkState | null>(null);
  const run = useRef<{ stop: () => void } | null>(null);
  const begin = () => {
    run.current?.stop();
    run.current = linkPhone({ start: startLinkQr, poll: pollQrAuth, onChange: setState });
  };
  useEffect(() => () => run.current?.stop(), []);
  const qr = useMemo(() => {
    if (!state?.qr) return null;
    try {
      const m = encodeQrMatrix(state.qr);
      return { size: m.size, path: encodeQrSvgPath(m) };
    } catch {
      return null;
    }
  }, [state?.qr]);

  return (
    <div data-connect-phone={state?.status ?? 'idle'}>
      <div className="ml-14 border-t border-line" />
      <div className="flex min-h-[64px] items-center gap-4 py-2">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-raised text-ink-2">
          <Smartphone className="h-5 w-5" {...ICON} aria-hidden />
        </div>
        <p className="min-w-0 flex-1 truncate text-headline text-ink">{h.requestsPhone}</p>
        {state?.status === 'linked' ? (
          <span className="shrink-0 text-footnote text-ink-2">{h.phoneConnected}</span>
        ) : state === null || state.status === 'unreachable' ? (
          <button type="button" onClick={begin} className="btn-ghost inline-flex min-h-[44px] items-center">
            {state ? h.retry : h.connectPhone}
          </button>
        ) : null}
      </div>
      {state && state.status !== 'linked' && state.status !== 'unreachable' && (
        <div className="ml-14 mb-2 flex min-h-[232px] w-[232px] flex-col items-center justify-center gap-3 rounded-lg border border-line bg-white p-4">
          {state.status === 'loading' && <Loader2 className="h-6 w-6 animate-spin text-ink-3" {...ICON} aria-hidden />}
          {state.status === 'pending' && qr && (
            <svg viewBox={`-2 -2 ${qr.size + 4} ${qr.size + 4}`} className="h-48 w-48" role="img" aria-label={h.scanInApp} shapeRendering="crispEdges">
              <path d={qr.path} className="fill-black" />
            </svg>
          )}
          {state.status === 'expired' && (
            <button type="button" onClick={begin} className="btn-ghost inline-flex min-h-[44px] items-center gap-2 text-black">
              <RotateCcw className="h-4 w-4" {...ICON} aria-hidden />
              {h.newCode}
            </button>
          )}
        </div>
      )}
      {state?.status === 'pending' && <p className="pl-14 pt-1 text-footnote text-ink-2">{h.scanInApp}</p>}
      {state?.status === 'unreachable' && <p className="pl-14 pt-1 text-footnote text-ink-2">{h.stageFailed}</p>}
    </div>
  );
}
