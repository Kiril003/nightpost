/**
 * Банер людини: до шести фото, гортаються пальцем, самі не крутяться.
 *
 * Фото, що ще в дорозі, — порожниста крапка й «2/6» поруч; жодного
 * спінера чи мерехтіння. Приїхало — проявляється за 200 мс. Відео-банер:
 * перший кадр — фото, а петлю сторінка просить у людини (лише Wi‑Fi) і
 * проявляє поверх кадру, коли та заграла.
 */
import React, { useEffect, useRef, useState } from 'react';
import { messengerApi, type PeerBannerRow } from '../../services/messengerApi';
import { readNet, shouldFetch } from '../../services/autoFetch';

/** Фокус 0..255 → `object-position` у відсотках. */
export const focalPosition = (fx: number, fy: number): string =>
  `${Math.round((Math.min(255, Math.max(0, fx)) / 255) * 100)}% ${Math.round((Math.min(255, Math.max(0, fy)) / 255) * 100)}%`;

/** Безпечні адреси: лише свої (`/webmsgr/_banners/…`) або `data:`/`blob:`. */
const SAFE = /^(\/(?![/\\])|data:image\/|blob:)/i;

/** Банер є, коли приїхав опис і хоч одне фото з нього; інакше — нічого. */
export const bannerShown = (b: PeerBannerRow | null | undefined): b is PeerBannerRow =>
  Boolean(b && b.items.some((i) => i.url && SAFE.test(i.url)));

const POLL_MS = 3000;
const POLL_TRIES = 40;

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

/** Петля банера як blob-URL: прохання людині, далі чекаємо, доки телефон складе й звірить байти. */
function useBannerVideo(video: PeerBannerRow['video'], peerId: string | undefined): string | undefined {
  const [src, setSrc] = useState<string>();
  const blobId = video?.blob_id;
  const ready = video?.ready ?? false;
  const size = video?.size ?? 0;
  useEffect(() => {
    setSrc(undefined);
    if (!blobId || !peerId || reducedMotion()) return;
    if (!shouldFetch({ kind: 'video', sizeBytes: size, rings: [], ...readNet() })) return;
    let alive = true;
    let url: string | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async (n: number) => {
      try {
        const res = await fetch(messengerApi.fileUrl(blobId));
        if (res.ok) {
          const bytes = await res.blob();
          if (!alive) return;
          url = URL.createObjectURL(new Blob([bytes], { type: 'video/mp4' }));
          setSrc(url);
          return;
        }
      } catch {
        /* телефон ще складає — спробуємо згодом */
      }
      if (alive && n + 1 < POLL_TRIES) timer = setTimeout(() => void load(n + 1), POLL_MS);
    };
    void (async () => {
      if (!ready) {
        try {
          if ((await messengerApi.pullBannerVideo(peerId)).state === 'none') return;
        } catch {
          /* вузол без петель (ПК) — лишається перший кадр */
          return;
        }
      }
      if (alive) void load(0);
    })();
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
      if (url) URL.revokeObjectURL(url);
    };
  }, [blobId, ready, size, peerId]);
  return src;
}

export const BannerCarousel: React.FC<{ banner: PeerBannerRow; overlay?: React.ReactNode; height?: number; peerId?: string }> = ({
  banner,
  overlay,
  height = 210,
  peerId,
}) => {
  const [at, setAt] = useState(0);
  const [playing, setPlaying] = useState(false);
  const videoSrc = useBannerVideo(banner.video_still ? banner.video : null, peerId);
  const [loaded, setLoaded] = useState<Record<number, boolean>>({});
  const strip = useRef<HTMLDivElement>(null);
  const items = banner.items;
  const arrived = items.filter((i) => i.url && SAFE.test(i.url)).length;
  const onScroll = () => {
    const el = strip.current;
    if (!el || el.clientWidth === 0) return;
    setAt(Math.round(el.scrollLeft / el.clientWidth));
  };
  const shown = items.map((it, k) => ({ ...it, k })).filter((i) => i.url && SAFE.test(i.url));
  return (
    <div className="relative overflow-hidden bg-raised" style={{ height }} data-testid="peer-banner">
      <div
        ref={strip}
        onScroll={onScroll}
        className="absolute inset-0 flex overflow-x-auto snap-x snap-mandatory no-scrollbar"
      >
        {shown.map((i) => (
          <img
            key={i.k}
            src={i.url as string}
            alt=""
            draggable={false}
            onLoad={() => setLoaded((l) => ({ ...l, [i.k]: true }))}
            className={`w-full h-full shrink-0 snap-center object-cover transition-opacity duration-200 motion-reduce:transition-none ${loaded[i.k] ? 'opacity-100' : 'opacity-0'}`}
            style={{ objectPosition: focalPosition(i.fx, i.fy) }}
            data-testid="peer-banner-photo"
          />
        ))}
      </div>
      {videoSrc && (
        <video
          key={videoSrc}
          src={videoSrc}
          muted
          loop
          autoPlay
          playsInline
          aria-hidden="true"
          onPlaying={() => setPlaying(true)}
          className={`absolute inset-0 w-full h-full object-cover pointer-events-none transition-opacity duration-300 motion-reduce:transition-none ${playing ? 'opacity-100' : 'opacity-0'}`}
          style={{ objectPosition: focalPosition(items[0]?.fx ?? 128, items[0]?.fy ?? 128) }}
          data-testid="peer-banner-video"
        />
      )}
      {overlay}
      {items.length > 1 && (
        <div
          className="ph-on-photo absolute right-3 bottom-3 flex items-center gap-1.5 px-2 py-1.5 rounded-full"
          data-testid="peer-banner-dots"
          role="img"
          aria-label={`${Math.min(at, shown.length - 1) + 1}/${items.length}`}
        >
          {items.map((i, k) => {
            const here = Boolean(i.url && SAFE.test(i.url));
            const idx = shown.findIndex((s) => s.k === k);
            const on = here && idx === Math.min(at, shown.length - 1);
            return (
              <i
                key={k}
                aria-hidden="true"
                data-wait={here ? undefined : ''}
                className={`block h-1.5 rounded-full transition-[width,opacity] duration-200 motion-reduce:transition-none ${on ? 'w-4 opacity-100' : 'w-1.5'} ${here ? 'bg-current' : 'border border-current opacity-60'} ${here && !on ? 'opacity-[.85]' : ''}`}
              />
            );
          })}
          {arrived < items.length && (
            <span className="ml-0.5 text-caption tnum" data-testid="peer-banner-count">
              {arrived}/{items.length}
            </span>
          )}
        </div>
      )}
    </div>
  );
};
