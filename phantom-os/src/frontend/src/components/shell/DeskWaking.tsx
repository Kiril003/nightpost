/**
 * PHANTOM прокидається. Вікно не чекає вузла: з першого кадру видно ту саму
 * кулю, що й на Головній, і вона дихає повільно — «ще не тут». Доку нема:
 * поки вузол не відповів, вести нікуди. Слова — за тим, скільки чекаємо, і
 * чесні: через півтори хвилини кажемо, що не піднявся.
 */
import { useEffect, useState } from 'react';
import { nodeOrigin } from '../../services/nodeOrigin';
import { PhantomMark } from '../messenger/PhantomMark';
import { useDeskPhase } from './useDeskPhase';
import './shell.css';
import { tNow } from '../chat/tNow';
export interface NodeWaking {
  up: boolean;
  /** Скільки чекаємо з першого кадру. */
  waitedMs: number;
  /** Скільки вузол уже відповідає (0 — ще ні). */
  upForMs: number;
  /** Оболонка не знайшла вузла в пакеті й нічого не відповідає. */
  missing: boolean;
}

const MISSING_EVENT = 'nightpost-node-missing';
const shellSaysMissing = () => (window as { __nightpostNodeMissing?: boolean }).__nightpostNodeMissing === true;

/** Опитує `/health` вузла, доки той не відповість, і далі тримає час. */
export function useNodeWaking(): NodeWaking {
  const [t0] = useState(() => Date.now());
  const [upAt, setUpAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [missing, setMissing] = useState(shellSaysMissing);
  useEffect(() => {
    const on = () => setMissing(shellSaysMissing());
    window.addEventListener(MISSING_EVENT, on);
    return () => window.removeEventListener(MISSING_EVENT, on);
  }, []);
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      setNow(Date.now());
      if (upAt !== null) return;
      try {
        const r = await fetch(`${nodeOrigin()}/health`, { cache: 'no-store' });
        if (alive && r.ok) setUpAt(Date.now());
      } catch {
        /* вузол ще спить */
      }
    };
    void tick();
    const id = setInterval(() => void tick(), 400);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [upAt]);
  return { up: upAt !== null, waitedMs: now - t0, upForMs: upAt === null ? 0 : now - upAt, missing: missing && upAt === null };
}

export function wakingLine(waitedMs: number, missing = false): string {
  if (missing) return tNow('deskWaking.missing');
  if (waitedMs < 10_000) return tNow('deskWaking.waking');
  if (waitedMs < 90_000) return tNow('deskWaking.stillWaking');
  return tNow('deskWaking.failed');
}

export function DeskWaking({ waitedMs, missing = false }: { waitedMs: number; missing?: boolean }) {
  useDeskPhase();
  return (
    <div data-mood="calm" className="shell-root relative flex flex-col w-screen h-screen overflow-hidden bg-canvas text-f-ink">
      <header className="relative z-10 shrink-0 h-12 px-6 flex items-center gap-3">
        <span className="shell-dot w-2.5 h-2.5" aria-hidden />
        <span className="text-f-name text-f-ink">Nightpost</span>
      </header>
      <main className="relative flex-1 flex flex-col items-center justify-center pb-[84px]">
        <PhantomMark size="w-14 h-14" />
        <p className="mt-8 text-f-read text-f-ink-2" role={missing ? 'alert' : 'status'} data-testid="waking-line">
          {wakingLine(waitedMs, missing)}
        </p>
      </main>
    </div>
  );
}
