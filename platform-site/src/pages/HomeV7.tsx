import { chainStatus } from '../lib/chainStatus';
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import '../styles/v7.css';
import { useLanguage } from '../i18n';
import {
  APK_SHA256,
  APK_URL,
  DEMO,
  FLAT,
  FLOW,
  LOOK,
  PEOPLE,
  GITHUB_URL,
  HERO_CUTS,
  homeV7,
  DEVNET_RPC,
  TX_SIG,
  TX_SLOT,
  TX_URL,
  TURN,
  type HomeV7Copy,
} from '../i18n/home-v7';
import { usePageMeta } from '../lib/meta';
import { scrubTime, turnFrame } from './v9-motion';

const CLIPS = '/v7/clips';

type Platform = 'android' | 'ios' | 'other';

function platform(): Platform {
  try {
    const ua = navigator.userAgent;
    if (/Android/i.test(ua)) return 'android';
    if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'ios';
  } catch {
    /* no navigator */
  }
  return 'other';
}

function stillMode(): boolean {
  try {
    if (new URLSearchParams(location.search).get('still') === '1') return true;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

/** Plays a muted video only while `on`; never throws on autoplay refusal. */
function usePlay(ref: React.RefObject<HTMLVideoElement>, on: boolean, restart = false) {
  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    if (on) {
      v.defaultMuted = true;
      v.muted = true;
      if (restart) {
        try {
          v.currentTime = 0;
        } catch {
          /* not seekable yet */
        }
      }
      v.play().catch(() => undefined);
    } else {
      v.pause();
    }
  }, [ref, on, restart]);
}

function useInView<T extends Element>(margin = '0px', threshold = 0): [React.RefObject<T>, boolean] {
  const ref = useRef<T>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (!('IntersectionObserver' in window)) {
      setInView(true);
      return;
    }
    const io = new IntersectionObserver(([e]) => setInView(e.isIntersecting), {
      rootMargin: margin,
      threshold,
    });
    io.observe(el);
    return () => io.disconnect();
  }, [margin, threshold]);
  return [ref, inView];
}

/* ------------------------------------------------------------------ the device */

function StatusBar() {
  return (
    <div className="v7-sb" aria-hidden>
      <span>21:08</span>
      <svg viewBox="0 0 54 12">
        <path d="M2 8.5a7 7 0 0 1 10 0M4.2 10.4a4 4 0 0 1 5.6 0" fill="none" stroke="currentColor" strokeWidth="1.4" />
        <path d="M18 11h2V8h-2zM22 11h2V6h-2zM26 11h2V3.5h-2z" fill="currentColor" />
        <rect x="34" y="2.5" width="16" height="8.5" rx="2" fill="none" stroke="currentColor" strokeWidth="1.1" />
        <rect x="35.6" y="4.1" width="10" height="5.3" rx="1" fill="currentColor" />
        <rect x="50.6" y="5" width="1.6" height="3.4" rx=".6" fill="currentColor" />
      </svg>
    </div>
  );
}

interface DeviceProps {
  clip: string;
  src?: string;
  poster?: string;
  scrub?: boolean;
  on: boolean;
  still: boolean;
  eager?: boolean;
  loop?: boolean;
  restart?: boolean;
  small?: boolean;
  label?: string;
  videoRef?: React.RefObject<HTMLVideoElement>;
  onTime?: (t: number) => void;
  children?: React.ReactNode;
}

function Device({ clip, src, poster, scrub, on, still, eager, loop = true, restart, small, label, videoRef, onTime, children }: DeviceProps) {
  const own = useRef<HTMLVideoElement>(null);
  const ref = videoRef ?? own;
  const [loaded, setLoaded] = useState(false);
  usePlay(ref, on && !still, restart);
  return (
    <div className={`v7-device${small ? ' v7-device--s' : ''}`} role={label ? 'img' : undefined} aria-label={label}>
      <div className="v7-screen">
        <StatusBar />
        <img
          className="v7-poster"
          src={poster ?? `${CLIPS}/${clip}.webp`}
          alt=""
          decoding="async"
          loading={eager ? 'eager' : 'lazy'}
          fetchPriority={eager ? 'high' : 'auto'}
          data-hide={loaded && (on || scrub) ? '1' : undefined}
        />
        {!still && (
          <video
            ref={ref}
            className="v7-video"
            muted
            playsInline
            loop={scrub ? false : loop}
            preload={eager || scrub ? 'auto' : 'none'}
            aria-hidden
            onPlaying={scrub ? undefined : () => setLoaded(true)}
            onSeeked={scrub ? () => setLoaded(true) : undefined}
            onTimeUpdate={onTime ? (e) => onTime(e.currentTarget.currentTime) : undefined}
          >
            <source src={src ?? `${CLIPS}/${clip}.mp4`} type="video/mp4" />
          </video>
        )}
        <img className="v7-glass" src="/v7/device/glass.webp" alt="" aria-hidden decoding="async" />
      </div>
      <img
        className="v7-frame"
        src={small ? '/v7/device/frame-s.webp' : '/v7/device/frame.webp'}
        alt=""
        aria-hidden
        decoding="async"
        loading={eager ? 'eager' : 'lazy'}
      />
      {children}
    </div>
  );
}

/* ------------------------------------------------------------------ chrome */


/* ------------------------------------------------------------------ 1. hero */

/** The rendered phone turns from 3/4 into the live device; its last frame is the device pose. */
function HeroTurn({ onDone }: { onDone: () => void }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [state, setState] = useState<'load' | 'play' | 'done'>('load');
  useEffect(() => {
    let raf = 0;
    let dead = false;
    const cv = canvas.current;
    const g = cv?.getContext('2d');
    const imgs = Array.from({ length: TURN.frames }, (_, i) => {
      const im = new Image();
      im.src = `${TURN.dir}/${String(i + 1).padStart(4, '0')}.webp`;
      return im;
    });
    const draw = (f: number) => {
      if (!cv || !g) return;
      g.clearRect(0, 0, cv.width, cv.height);
      g.drawImage(imgs[f - 1], 0, 0, cv.width, cv.height);
    };
    const finish = () => {
      if (dead) return;
      setState('done');
      onDone();
    };
    imgs[0].decode().then(() => !dead && draw(1), () => undefined);
    const ready = Promise.all(imgs.map((im) => im.decode())).then(() => true, () => false);
    const slow = new Promise<boolean>((r) => window.setTimeout(() => r(false), 3000));
    Promise.race([ready, slow]).then((ok) => {
      if (dead) return;
      if (!ok || !g) return finish();
      setState('play');
      const t0 = performance.now();
      const step = (now: number) => {
        const f = turnFrame(now - t0, TURN.ms, TURN.frames);
        draw(f);
        if (f < TURN.frames) raf = requestAnimationFrame(step);
        else finish();
      };
      raf = requestAnimationFrame(step);
    });
    return () => {
      dead = true;
      cancelAnimationFrame(raf);
    };
  }, [onDone]);
  return <canvas ref={canvas} className="v9-turn" width={720} height={1440} data-state={state} aria-hidden />;
}

/* six apps that the phone replaces */

const APP_GLYPH = [
  'M4 5h16v11H9l-5 4z',
  'M12 21s-6-5.7-6-11a6 6 0 0 1 12 0c0 5.3-6 11-6 11zM12 10m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0',
  'M4 6h16v14H4zM4 10h16M8 3v5M16 3v5',
  'M4 5h16v14H4zM4 15l5-5 4 4 3-3 4 4M15 9m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0',
  'M3 10l9-6 9 6M5 10v8M9 10v8M15 10v8M19 10v8M3 20h18',
  'M6 3h12v18H6zM9 7h6M9 11h2M13 11h2M9 15h2M13 15h2',
];
// Where each tile sits beside the phone: side (−1 left, 1 right), height (0…1 of the phone), tilt.
const APP_POS: [number, number, number][] = [
  [-1, 0.16, -8],
  [1, 0.24, 6],
  [-1, 0.47, 5],
  [1, 0.55, -6],
  [-1, 0.78, -4],
  [1, 0.86, 7],
];


function Hero({ c, still }: { c: HomeV7Copy; still: boolean }) {
  const [os] = useState(platform);
  const video = useRef<HTMLVideoElement>(null);
  const bar = useRef<HTMLOListElement>(null);
  const [part, setPart] = useState(0);
  const [paused, setPaused] = useState(false);
  const [box, inView] = useInView<HTMLElement>();
  const [stage, stageSeen] = useInView<HTMLDivElement>('0px', 0.6);
  const [join, setJoin] = useState(false);
  const [turned, setTurned] = useState(still);
  const onTurned = useCallback(() => setTurned(true), []);
  useEffect(() => {
    if (stageSeen && turned) setJoin(true);
  }, [stageSeen, turned]);
  const total = HERO_CUTS[HERO_CUTS.length - 1];

  const onTime = useCallback((t: number) => {
    let i = 0;
    while (i < HERO_CUTS.length - 2 && t >= HERO_CUTS[i + 1]) i++;
    setPart((p) => (p === i ? p : i));
    const a = HERO_CUTS[i];
    const b = HERO_CUTS[i + 1];
    bar.current?.style.setProperty('--seg', Math.min(1, Math.max(0, (t - a) / (b - a))).toFixed(3));
  }, []);

  const seek = (i: number) => {
    const v = video.current;
    setPart(i);
    if (!v) return;
    try {
      v.currentTime = HERO_CUTS[i] + 0.35;
    } catch {
      /* metadata not loaded yet */
    }
    if (paused) {
      setPaused(false);
    }
    v.play().catch(() => undefined);
  };

  return (
    <section className="v7-hero" id="top" ref={box}>
      <div className="v7-hero-copy">
        <h1 className="v7-h1">
          <span>{c.hero.a}</span> <em>{c.hero.b}</em>
        </h1>
        <p className="v7-lede">{c.hero.sub}</p>
        <div className="v7-ctas" data-os={os}>
          <a className={`v7-btn v7-btn--get${os === 'ios' ? ' v7-btn--ghost' : ''}`} href={os === 'android' ? APK_URL : '#get'}>
            <DownloadIcon />
            {c.hero.get}
            <small>{c.hero.getNote}</small>
          </a>
          <a className={os === 'ios' ? 'v7-btn' : 'v7-btn v7-btn--ghost'} href="#demo">
            <PlayIcon />
            {c.hero.watch}
            <small>{c.hero.watchNote}</small>
          </a>
        </div>
        {os === 'ios' && <p className="v7-ios">{c.hero.ios}</p>}
        <ul className="v7-status">
          {c.hero.status.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ul>
      </div>
      <div className="v7-hero-device">
        <div className="v7-hero-stage" ref={stage} data-turn={turned ? undefined : '1'}>
          {!still && (
            <div className="v7-tiles" aria-hidden data-go={join ? '1' : undefined}>
              {c.hero.apps.map((name, i) => (
                <div
                  key={name}
                  className="v7-tile"
                  style={{ '--x': APP_POS[i][0], '--y': APP_POS[i][1], '--r': `${APP_POS[i][2]}deg`, '--d': `${i * 70}ms` } as CSSProperties}
                >
                  <svg viewBox="0 0 24 24">
                    <path d={APP_GLYPH[i]} />
                  </svg>
                  <span>{name}</span>
                </div>
              ))}
            </div>
          )}
          <Device
            clip="hero"
            on={inView && !paused && turned}
            still={still}
            eager
            videoRef={video}
            onTime={onTime}
            label={c.hero.chapters.join(', ')}
          >
            {!still && <HeroTurn onDone={onTurned} />}
          </Device>
        </div>
        {!still && (
          <div className="v7-chapters">
            <ol ref={bar} style={{ '--n': c.hero.chapters.length } as CSSProperties}>
              {c.hero.chapters.map((name, i) => (
                <li key={name} data-state={i < part ? 'done' : i === part ? 'now' : undefined}>
                  <button
                    type="button"
                    onClick={() => seek(i)}
                    style={
                      {
                        '--w': (HERO_CUTS[i + 1] - HERO_CUTS[i]) / total,
                      } as CSSProperties
                    }
                  >
                    <span className="v7-chap-bar" />
                    <span className="v7-chap-name">{name}</span>
                  </button>
                </li>
              ))}
            </ol>
            <button
              type="button"
              className="v7-pause"
              aria-label={paused ? c.hero.play : c.hero.pause}
              onClick={() => setPaused((p) => !p)}
            >
              {paused ? <PlayIcon /> : <PauseIcon />}
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ scroll-scrubbed recordings */

/** Maps scroll through a tall section onto a recording: each segment between `bounds` gets an equal share of the
 *  scroll, so every app state holds the screen for the same distance. Returns the index of the current segment. */
function useScrub(box: React.RefObject<HTMLElement>, video: React.RefObject<HTMLVideoElement>, bounds: readonly number[], on: boolean) {
  const [seg, setSeg] = useState(0);
  useEffect(() => {
    const el = box.current;
    const v = video.current;
    if (!el || !on) return;
    const n = bounds.length - 1;
    let raf = 0;
    let want = 0;
    let primed = false;
    const seek = () => {
      if (!v || v.seeking || v.readyState < 1) return;
      if (Math.abs(v.currentTime - want) > 0.02) v.currentTime = want;
    };
    const frame = () => {
      raf = 0;
      const r = el.getBoundingClientRect();
      const run = Math.max(1, r.height - window.innerHeight);
      const p = Math.min(1, Math.max(0, -r.top / run));
      el.style.setProperty('--p', p.toFixed(4));
      const k = Math.min(n - 1, Math.floor(p * n));
      want = scrubTime(p, bounds, v ? v.duration : NaN);
      setSeg((s) => (s === k ? s : k));
      if (v && !primed && r.top < window.innerHeight && r.bottom > 0) {
        primed = true;
        v.defaultMuted = true;
        v.muted = true;
        v.play().then(
          () => {
            v.pause();
            seek();
          },
          () => undefined,
        );
      }
      seek();
    };
    const ask = () => {
      if (!raf) raf = requestAnimationFrame(frame);
    };
    v?.addEventListener('seeked', seek);
    v?.addEventListener('loadedmetadata', seek);
    frame();
    window.addEventListener('scroll', ask, { passive: true });
    window.addEventListener('resize', ask);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('scroll', ask);
      window.removeEventListener('resize', ask);
      v?.removeEventListener('seeked', seek);
      v?.removeEventListener('loadedmetadata', seek);
    };
  }, [box, video, bounds, on]);
  return seg;
}

/* ------------------------------------------------------------------ 2. people: scroll moves through the circles */

const PEOPLE_BOUNDS = [...PEOPLE.marks, PEOPLE.dur];

function People({ c, still }: { c: HomeV7Copy; still: boolean }) {
  const box = useRef<HTMLElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const seg = useScrub(box, video, PEOPLE_BOUNDS, !still);
  return (
    <section className={`v9-people${still ? ' v9-still' : ''}`} id="how" ref={box} aria-labelledby="people-h">
      <div className="v9-sticky">
        <header className="v9-people-head">
          <p className="v7-eyebrow">{c.people.eyebrow}</p>
          <h2 id="people-h" className="v7-h2">
            {c.people.head}
          </h2>
          <ol className="v9-chips" aria-hidden>
            {c.people.chips.map((name, i) => (
              <li key={name} data-on={i === seg ? '1' : undefined}>
                {name}
              </li>
            ))}
          </ol>
        </header>
        <div className="v9-people-phone">
          <Device clip="" src={PEOPLE.src} poster={PEOPLE.poster} scrub on={false} still={still} videoRef={video} label={c.people.head} />
        </div>
        <p className="v7-body v9-people-line">{c.people.line}</p>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ 3. one flow: plan → place → split */

function Flow({ c, still }: { c: HomeV7Copy; still: boolean }) {
  const box = useRef<HTMLElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const seg = useScrub(box, video, FLOW.cuts, !still);
  return (
    <section className={`v9-flow${still ? ' v9-still' : ''}`} ref={box} aria-labelledby="flow-h">
      <div className="v9-sticky">
        <header className="v9-flow-head">
          <p className="v7-eyebrow">{c.flow.eyebrow}</p>
          <h2 id="flow-h" className="v7-h2">
            {c.flow.head}
          </h2>
        </header>
        <div className="v9-flow-phone">
          <Device clip="" src={FLOW.src} poster={FLOW.poster} scrub on={false} still={still} videoRef={video} label={c.flow.head} />
        </div>
        <ol className="v9-stages">
          {c.flow.stages.map((s, i) => (
            <li key={s.name} data-state={i < seg ? 'done' : i === seg ? 'now' : undefined} style={{ '--i': i } as CSSProperties}>
              <span className="v9-stage-bar" aria-hidden />
              <p className="v7-step-n">
                <span>{String(i + 1).padStart(2, '0')}</span> {s.name}
              </p>
              <h3 className="v7-h3">{s.head}</h3>
              <p className="v7-body">{s.line}</p>
            </li>
          ))}
        </ol>
        <p key={seg} className="v9-stage-now" aria-hidden>
          <b>{c.flow.stages[seg].head}</b> {c.flow.stages[seg].line}
        </p>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ 4. assistant: the request types itself, then the phone runs it */

function Ask({ c, still }: { c: HomeV7Copy; still: boolean }) {
  const [box, seen] = useInView<HTMLElement>('0px 0px -30% 0px');
  const [typed, setTyped] = useState(still ? c.ask.prompt.length : 0);
  const done = typed >= c.ask.prompt.length;
  useEffect(() => {
    setTyped(still ? c.ask.prompt.length : 0);
  }, [c.ask.prompt, still]);
  useEffect(() => {
    if (!seen || done) return;
    const t = window.setTimeout(() => setTyped((n) => n + 1), typed === 0 ? 500 : 55 + Math.random() * 45);
    return () => window.clearTimeout(t);
  }, [seen, typed, done]);
  return (
    <section className="v9-ask" ref={box} aria-labelledby="ask-h">
      <div className="v9-ask-copy">
        <p className="v7-eyebrow">{c.ask.eyebrow}</p>
        <h2 id="ask-h" className="v7-h2">
          {c.ask.head}
        </h2>
        <p className="v9-prompt" aria-label={c.ask.prompt}>
          <span aria-hidden>{c.ask.prompt.slice(0, typed)}</span>
          <i className="v9-caret" data-done={done ? '1' : undefined} aria-hidden />
          <span className="v9-send" data-on={done ? '1' : undefined} aria-hidden>
            <svg viewBox="0 0 24 24">
              <path d="M12 19V5M6 11l6-6 6 6" />
            </svg>
          </span>
        </p>
        <p className="v7-body">{c.ask.line}</p>
      </div>
      <div className="v9-ask-phone">
        <Device clip="b11" on={seen && done} still={still} eager={seen} restart loop={false} label={c.ask.head} />
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ 5. your look: the app's own switch repaints the page */

function Look({ c, look, setLook }: { c: HomeV7Copy; look: 'day' | 'night'; setLook: (l: 'day' | 'night') => void }) {
  return (
    <section className="v9-look" aria-labelledby="look-h">
      <div className="v9-look-copy">
        <p className="v7-eyebrow">{c.look.eyebrow}</p>
        <h2 id="look-h" className="v7-h2">
          {c.look.head}
        </h2>
        <p className="v7-body">{c.look.line}</p>
        <div className="v9-switch" role="radiogroup" aria-label={c.look.label}>
          {(['day', 'night'] as const).map((k) => (
            <button key={k} type="button" role="radio" aria-checked={look === k} onClick={() => setLook(k)}>
              <span className={`v9-swatch v9-swatch--${k}`} aria-hidden />
              {c.look[k]}
            </button>
          ))}
        </div>
      </div>
      <div className="v9-look-phone">
        <div className="v7-device" role="img" aria-label={`${c.look.head} ${c.look[look]}`}>
          <div className="v7-screen">
            <StatusBar />
            {(['day', 'night'] as const).map((k) => (
              <img key={k} className="v7-poster v9-look-shot" src={LOOK[k]} alt="" loading="lazy" decoding="async" data-hide={look === k ? undefined : '1'} />
            ))}
            <img className="v7-glass" src="/v7/device/glass.webp" alt="" aria-hidden decoding="async" />
          </div>
          <img className="v7-frame" src="/v7/device/frame.webp" alt="" aria-hidden decoding="async" loading="lazy" />
        </div>
      </div>
    </section>
  );
}

function Also({ c }: { c: HomeV7Copy }) {
  return (
    <section className="v7-also v9-also" aria-label={c.also.head}>
      <h3 className="v7-eyebrow">{c.also.head}</h3>
      <ul>
        {c.also.items.map((t) => (
          <li key={t}>{t}</li>
        ))}
      </ul>
    </section>
  );
}

/* ------------------------------------------------------------------ 4. Solana */

type Chain = {
  status: 'checking' | 'finalized' | 'unconfirmed' | 'unknown';
  slot: number | null;
};

async function rpc<T>(method: string, params: unknown[], signal: AbortSignal): Promise<T> {
  const res = await fetch(DEVNET_RPC, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
    signal,
  });
  const j = (await res.json()) as { result?: T; error?: unknown };
  if (j.error || j.result === undefined) throw new Error('rpc');
  return j.result;
}

/** Asks public devnet for the receipt's transaction status, then follows the current slot while visible. */
function useDevnet(active: boolean): Chain {
  const [chain, setChain] = useState<Chain>({ status: 'checking', slot: null });
  const asked = useRef(false);
  useEffect(() => {
    if (!active) return;
    const ac = new AbortController();
    let timedOut = false;
    const limit = window.setTimeout(() => {
      timedOut = true;
      ac.abort();
    }, 8000);
    if (!asked.current) {
      rpc<{ value: ({ confirmationStatus?: string; err: unknown } | null)[] }>(
        'getSignatureStatuses',
        [[TX_SIG], { searchTransactionHistory: true }],
        ac.signal,
      )
        .then((r) => {
          asked.current = true;
          setChain((x) => ({
            ...x,
            status: chainStatus(r.value[0]),
          }));
        })
        .catch(() => {
          if (ac.signal.aborted && !timedOut) return;
          asked.current = true;
          setChain((x) => ({ ...x, status: 'unknown' }));
        });
    }
    const slot = () => {
      if (document.hidden) return;
      rpc<number>('getSlot', [{ commitment: 'confirmed' }], ac.signal)
        .then((n) => setChain((x) => ({ ...x, slot: n })))
        .catch(() => undefined);
    };
    slot();
    const t = window.setInterval(slot, 2400);
    return () => {
      ac.abort();
      window.clearTimeout(limit);
      window.clearInterval(t);
    };
  }, [active]);
  return chain;
}

function Solana({ c }: { c: HomeV7Copy }) {
  const { lang } = useLanguage();
  const [ref, seen] = useInView<HTMLDivElement>('0px 0px -20% 0px');
  const [live, near] = useInView<HTMLDivElement>('200px 0px');
  const [step, setStep] = useState(0);
  useEffect(() => {
    if (seen && !step) setStep(stillMode() ? 2 : 1);
  }, [seen, step]);
  useEffect(() => {
    if (step !== 1) return;
    const t = window.setTimeout(() => setStep(2), 1100);
    return () => window.clearTimeout(t);
  }, [step]);
  const chain = useDevnet(near);
  const fmt = (n: number) => n.toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-US');
  const r = c.sol.receipt;
  return (
    <section className="v7-sol" id="solana" aria-labelledby="sol-h">
      <div className="v7-sol-grid" ref={live}>
        <div className="v7-sol-copy">
          <p className="v7-eyebrow">{c.sol.eyebrow}</p>
          <h2 id="sol-h" className="v7-h2">
            {c.sol.head}
          </h2>
          <p className="v7-body">{c.sol.line}</p>
          <dl className="v7-facts">
            {c.sol.facts.map((f) => (
              <div key={f.head}>
                <dt>{f.head}</dt>
                <dd>{f.line}</dd>
              </div>
            ))}
          </dl>
        </div>
        <div className="v7-receipt-col" ref={ref}>
          <div className="v7-confirm" data-step={step} aria-hidden>
            <svg viewBox="0 0 48 48">
              <circle className="v7-confirm-track" cx="24" cy="24" r="21" />
              <circle className="v7-confirm-ring" cx="24" cy="24" r="21" />
              <path className="v7-confirm-tick" d="M16 24.5l5.5 5.5L32.5 19" />
            </svg>
            <span>{r.confirm}</span>
          </div>
          <div className="v7-receipt-slot">
            <article className="v7-receipt" data-printed={step === 2 ? '1' : undefined}>
              <header>
                <span>{r.title}</span>
                <span className="v7-net">{r.net}</span>
              </header>
              <dl>
                {r.rows.map(([k, v]) => (
                  <div key={k}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
              <div className="v7-memo">
                <span>SPL Memo</span>
                <code>{r.memo}</code>
              </div>
              <div className="v7-chain" aria-live="polite">
                <span className="v7-chain-head">{r.live.head}</span>
                {chain.status === 'unknown' ? <p>{r.live.unknown}</p> : chain.status === 'unconfirmed' ? (
                  <p>{r.live.unconfirmed}</p>
                ) : (
                  <dl>
                    <div>
                      <dt>{r.live.state}</dt>
                      <dd data-ok={chain.status === 'finalized' ? '1' : undefined}>
                        {chain.status === 'finalized' ? r.live.finalized : r.live.checking}
                      </dd>
                    </div>
                    <div>
                      <dt>{r.live.now}</dt>
                      <dd key={chain.slot ?? 0} className="v7-tick">
                        {chain.slot ? fmt(chain.slot) : '—'}
                      </dd>
                    </div>
                    {chain.slot ? (
                      <div>
                        <dt>{r.live.later}</dt>
                        <dd>{fmt(chain.slot - TX_SLOT)}</dd>
                      </div>
                    ) : null}
                  </dl>
                )}
              </div>
              <a className="v7-receipt-link" href={TX_URL} target="_blank" rel="noreferrer">
                {r.open} <ArrowIcon />
              </a>
              <p className="v7-receipt-foot">{r.foot}</p>
            </article>
          </div>
        </div>
      </div>
      <div className="v7-sol-status">
        <p>
          <span className="v7-live" aria-hidden />
          {c.sol.status}
        </p>
        <p className="v7-muted">{c.sol.next}</p>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ 5. demo + numbers */

function Proof({ c }: { c: HomeV7Copy }) {
  return (
    <section className="v7-proof" aria-label={c.proof.head}>
      <ul className="v7-stats">
        {c.proof.stats.map((s) => (
          <li key={s.t}>
            <b>{s.n}</b>
            <span>{s.t}</span>
          </li>
        ))}
      </ul>
      <p className="v7-proof-links">
        <a href="#demo">
          <PlayIcon /> {c.proof.play}
        </a>
        <a href={GITHUB_URL} target="_blank" rel="noreferrer">
          {c.proof.links.github} <ArrowIcon />
        </a>
        <a href={TX_URL} target="_blank" rel="noreferrer">
          {c.proof.links.chain} <ArrowIcon />
        </a>
      </p>
    </section>
  );
}

/** Every link to #demo opens the film in a dialog; the video loads only once it is open. */
function DemoDialog({ c }: { c: HomeV7Copy }) {
  const box = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const show = () => {
      const d = box.current;
      if (!d || d.open) return;
      d.showModal();
      setOpen(true);
    };
    const onClick = (e: MouseEvent) => {
      const a = (e.target as Element | null)?.closest?.('a[href="#demo"]');
      if (!a) return;
      e.preventDefault();
      show();
    };
    document.addEventListener('click', onClick);
    if (window.location.hash === '#demo') show();
    return () => document.removeEventListener('click', onClick);
  }, []);
  return (
    <dialog
      className="v7-demo"
      id="demo"
      ref={box}
      aria-labelledby="demo-h"
      onClose={() => setOpen(false)}
      onClick={(e) => {
        if (e.target === e.currentTarget) box.current?.close();
      }}
    >
      <div className="v7-demo-bar">
        <h2 id="demo-h">{c.proof.head}</h2>
        <button type="button" className="v7-demo-x" onClick={() => box.current?.close()}>
          {c.proof.close}
        </button>
      </div>
      <div className="v7-film">
        {open && (
          <video src={DEMO.src} poster={DEMO.poster} controls autoPlay playsInline preload="auto" aria-label={c.proof.play} />
        )}
      </div>
      <p className="v7-demo-note">
        {c.proof.line}
      </p>
    </dialog>
  );
}

/* ------------------------------------------------------------------ 7. download */

function Get({ c }: { c: HomeV7Copy }) {
  const [getOs] = useState(platform);
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard
      ?.writeText(APK_SHA256)
      .then(() => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1800);
      })
      .catch(() => undefined);
  };
  return (
    <section className="v7-get" id="get" aria-labelledby="get-h">
      <div className="v7-get-copy">
        <h2 id="get-h" className="v7-h1 v7-h1--get">
          {c.get.head}
        </h2>
        <p className="v7-body">{c.get.line}</p>
        <div className="v7-ctas">
          <a className="v7-btn v7-btn--lg" href={APK_URL}>
            <DownloadIcon />
            {c.get.button}
          </a>
        </div>
        {getOs === 'ios' && <p className="v7-ios">{c.hero.ios}</p>}
        <ol className="v7-install">
          {c.get.steps.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ol>
        <div className="v7-sha">
          <span>{c.get.sha}</span>
          <code>{APK_SHA256}</code>
          <button type="button" onClick={copy}>
            {copied ? c.get.copied : c.get.copy}
          </button>
        </div>
        <p className="v7-muted">
          <Link to="/download">{c.get.more}</Link>
        </p>
        <p className="v7-get-next">{c.get.next}</p>
      </div>
      <img className="v9-flat" src={FLAT} alt="" aria-hidden width="720" height="540" loading="lazy" decoding="async" />
      <figure className="v7-qr">
        <img src="/v7/qr-apk.svg" alt={c.get.scan} width="220" height="220" loading="lazy" />
        <figcaption>{c.get.scan}</figcaption>
      </figure>
    </section>
  );
}


/* ------------------------------------------------------------------ icons */

function DownloadIcon() {
  return (
    <svg className="v7-ico" viewBox="0 0 24 24" aria-hidden>
      <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
    </svg>
  );
}
function PlayIcon() {
  return (
    <svg className="v7-ico v7-ico--fill" viewBox="0 0 24 24" aria-hidden>
      <path d="M8 5.5v13l10.5-6.5z" />
    </svg>
  );
}
function PauseIcon() {
  return (
    <svg className="v7-ico v7-ico--fill" viewBox="0 0 24 24" aria-hidden>
      <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" />
    </svg>
  );
}
function ArrowIcon() {
  return (
    <svg className="v7-ico v7-ico--sm" viewBox="0 0 24 24" aria-hidden>
      <path d="M7 17L17 7M9 7h8v8" />
    </svg>
  );
}

/* ------------------------------------------------------------------ page */

export default function HomeV7() {
  const { lang } = useLanguage();
  const c = homeV7[lang];
  usePageMeta(c.meta);
  const [still] = useState(stillMode);
  const [look, setLook] = useState<'day' | 'night'>('day');
  return (
    <div className="v7" data-still={still ? '1' : undefined} data-look={look}>
      <a className="v7-skip" href="#get">
        {c.nav.get}
      </a>
      <div>
        <Hero c={c} still={still} />
        <People c={c} still={still} />
        <Flow c={c} still={still} />
        <Ask c={c} still={still} />
        <Look c={c} look={look} setLook={setLook} />
        <Also c={c} />
        <Solana c={c} />
        <Proof c={c} />
        <Get c={c} />
      </div>
      <DemoDialog c={c} />
    </div>
  );
}
