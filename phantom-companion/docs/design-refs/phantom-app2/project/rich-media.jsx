/* rich-media.jsx — file / media chat cards:
   Photo (hero, EXIF), PhotoAlbum (mosaic, +N), PhotoCarousel,
   VideoCard (scrubber, chapters), AudioWaveform (chapters, transcript),
   DocumentLong (page strip + extracted highlights),
   SpreadsheetPreview, FileTransfer (multi-upload), Model3DCard,
   ImageGenInProgress (live diffusion animation). */

// ─── shared kicker ──────────────────────────────────────────────────────────
function RMKicker({ kind, sub, color = 'var(--gold-soft)' }) {
  return (
    <div className="mono" style={{
      fontSize: 9, color, letterSpacing: '0.18em',
      textTransform: 'uppercase', marginBottom: 8,
      display: 'flex', alignItems: 'center', gap: 6,
    }}>
      <span>· {kind}</span>
      {sub && <span style={{ color: 'var(--ink-tertiary)', letterSpacing: '0.1em' }}>· {sub}</span>}
    </div>
  );
}

// painterly fake photos — we don't have real assets, so compose with gradients
function PainterlyPhoto({ scene = 'mountain', children, ratio = '4/3' }) {
  const presets = {
    mountain: `
      radial-gradient(ellipse 70% 30% at 50% 22%, rgba(255,210,140,0.6), transparent 65%),
      radial-gradient(ellipse 80% 20% at 50% 75%, rgba(30,18,10,0.7), transparent 60%),
      linear-gradient(180deg, #2a1c10 0%, #6a3818 32%, #8a4818 48%, #2a1808 80%, #0a0805 100%)`,
    portrait: `
      radial-gradient(ellipse 50% 60% at 38% 35%, rgba(255,210,150,0.55), rgba(120,60,30,0.3) 50%, transparent 70%),
      linear-gradient(160deg, #1a1108 0%, #3a2818 50%, #0a0805 100%)`,
    night: `
      radial-gradient(ellipse 30% 40% at 75% 30%, rgba(244,175,37,0.5), transparent 55%),
      radial-gradient(ellipse 20% 15% at 22% 18%, rgba(255,250,220,0.35), transparent 60%),
      linear-gradient(180deg, #0a0810 0%, #1a1422 40%, #2a1a14 90%)`,
    food: `
      radial-gradient(ellipse 60% 50% at 50% 55%, rgba(255,140,80,0.5), rgba(80,30,15,0.3) 55%, transparent 75%),
      radial-gradient(ellipse 80% 80% at 50% 50%, rgba(40,22,12,0.5), transparent 60%),
      linear-gradient(160deg, #2a1810 0%, #1a0e08 100%)`,
    forest: `
      radial-gradient(ellipse 60% 70% at 35% 60%, rgba(80,140,90,0.35), transparent 65%),
      radial-gradient(ellipse 50% 50% at 75% 35%, rgba(200,180,90,0.25), transparent 60%),
      linear-gradient(180deg, #1a2218 0%, #0e1810 60%, #050805 100%)`,
    abstract: `
      conic-gradient(from 220deg at 30% 40%, #F4AF25, #E76F51, #7E5DA4, #2a1a14, #F4AF25)`,
    city: `
      linear-gradient(180deg, #0a0a14 0%, #1a1424 40%, #2a1810 70%, #0a0805 100%),
      radial-gradient(ellipse 80% 30% at 50% 70%, rgba(255,180,70,0.3), transparent 60%)`,
    receipt: 'linear-gradient(165deg, #f5e8d3 0%, #e3d0a8 100%)',
    sky: `
      linear-gradient(180deg, #f7c97a 0%, #e76f51 35%, #6a2a4a 70%, #1a0e1a 100%)`,
  };
  return (
    <div style={{
      width: '100%', aspectRatio: ratio,
      background: presets[scene] || presets.mountain,
      position: 'relative', overflow: 'hidden',
      borderRadius: 'inherit',
    }}>
      {/* film grain */}
      <div style={{
        position: 'absolute', inset: 0,
        background:
          'repeating-radial-gradient(circle at 30% 40%, rgba(255,255,255,0.015) 0 1px, transparent 1px 3px),' +
          'repeating-radial-gradient(circle at 70% 60%, rgba(0,0,0,0.02) 0 1px, transparent 1px 4px)',
        mixBlendMode: 'overlay',
      }} />
      {/* horizon for mountain/sky */}
      {(scene === 'mountain' || scene === 'sky') && (
        <svg viewBox="0 0 400 300" preserveAspectRatio="none"
             style={{ position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '55%', opacity: 0.85 }}>
          <path d="M 0 300 L 0 165 L 50 145 L 100 160 L 150 110 L 200 135 L 245 95 L 295 130 L 340 100 L 400 140 L 400 300 Z"
                fill="#080604" />
          <path d="M 0 300 L 0 210 L 60 200 L 130 215 L 220 195 L 320 220 L 400 205 L 400 300 Z"
                fill="#03020155" />
        </svg>
      )}
      {scene === 'city' && (
        <svg viewBox="0 0 400 300" preserveAspectRatio="none"
             style={{ position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '65%' }}>
          {[0, 25, 60, 85, 130, 180, 215, 260, 305, 340, 380].map((x, i) => (
            <rect key={i} x={x} y={150 + (i % 4) * 18} width={18 + (i % 3) * 6} height={150} fill="#0a0608" />
          ))}
          {Array.from({ length: 40 }).map((_, i) => (
            <rect key={i} x={(i * 17) % 380 + 4} y={170 + (i * 23) % 100}
                  width="2" height="2" fill="#FFD370" opacity={0.5 + Math.random() * 0.5} />
          ))}
        </svg>
      )}
      {children}
    </div>
  );
}

// ─── 1. Photo Hero (single, full-bleed with EXIF strip) ──────────────────────
function PhotoHero({ scene = 'mountain', title, when, where, exif }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene={scene} ratio="4/3" />
        {/* overlay caption */}
        <div style={{
          position: 'absolute', left: 0, right: 0, bottom: 0,
          padding: '20px 14px 10px',
          background: 'linear-gradient(to top, rgba(0,0,0,0.7), transparent)',
        }}>
          <div style={{
            font: '500 14px/1.25 var(--font-display)', color: '#fff',
            letterSpacing: '-0.005em', textShadow: '0 1px 6px rgba(0,0,0,0.4)',
            textWrap: 'pretty',
          }}>{title}</div>
          <div className="mono" style={{
            marginTop: 4, fontSize: 9, color: 'rgba(255,250,220,0.7)',
            letterSpacing: '0.06em',
          }}>{where} · {when}</div>
        </div>
        {/* corner tag */}
        <div className="mono" style={{
          position: 'absolute', top: 8, right: 8,
          fontSize: 8, color: 'rgba(255,255,255,0.65)',
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          padding: '3px 7px', borderRadius: 999, letterSpacing: '0.08em',
        }}>· iphone · raw ·</div>
      </div>
      {/* exif strip */}
      {exif && (
        <div style={{
          padding: '8px 12px',
          display: 'flex', gap: 12, alignItems: 'center',
          borderTop: '0.5px solid var(--glass-edge)',
        }}>
          {exif.map((e, i) => (
            <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              <span className="mono" style={{
                fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.1em',
              }}>{e.k}</span>
              <span style={{
                font: '500 10.5px/1 var(--font-display)', color: 'var(--ink-strong)',
                fontVariantNumeric: 'tabular-nums',
              }}>{e.v}</span>
            </div>
          ))}
          <div style={{ flex: 1 }} />
          <button style={{
            appearance: 'none', cursor: 'pointer',
            background: 'rgba(244,175,37,0.08)',
            border: '0.5px solid rgba(244,175,37,0.25)',
            color: 'var(--gold-soft)',
            font: '500 9.5px/1 var(--font-body)',
            padding: '5px 9px', borderRadius: 999, letterSpacing: '0.04em',
          }}>зберегти у vault</button>
        </div>
      )}
    </div>
  );
}

// ─── 2. Photo Album (asymmetric mosaic with +N) ──────────────────────────────
function PhotoAlbum({ count = 12, scenes = ['mountain', 'portrait', 'night', 'food', 'forest'], title }) {
  const showCount = 5;
  return (
    <div className="glass fade-in" style={{
      padding: 10, margin: '6px 0', borderRadius: 16,
    }}>
      <RMKicker kind="альбом" sub={`${count} фото · ${count * 2.4 | 0} mb`} />
      {title && (
        <div style={{
          font: '500 12.5px/1.35 var(--font-display)', color: 'var(--ink-strong)',
          marginBottom: 8, textWrap: 'pretty',
        }}>{title}</div>
      )}
      <div style={{
        display: 'grid', gap: 4,
        gridTemplateColumns: '1.4fr 1fr 1fr',
        gridTemplateRows: 'repeat(2, 78px)',
      }}>
        <div style={{
          gridRow: '1 / span 2', borderRadius: 8, overflow: 'hidden',
        }}>
          <PainterlyPhoto scene={scenes[0]} ratio="auto" />
        </div>
        {[1, 2, 3].map((i) => (
          <div key={i} style={{ borderRadius: 8, overflow: 'hidden', position: 'relative' }}>
            <PainterlyPhoto scene={scenes[i] || 'abstract'} ratio="auto" />
          </div>
        ))}
        <div style={{ borderRadius: 8, overflow: 'hidden', position: 'relative' }}>
          <PainterlyPhoto scene={scenes[4] || 'forest'} ratio="auto" />
          {count > showCount && (
            <div style={{
              position: 'absolute', inset: 0,
              background: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(2px)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexDirection: 'column', gap: 1,
            }}>
              <span style={{ font: '300 22px/1 var(--font-display)', color: '#fff' }}>+{count - showCount}</span>
              <span className="mono" style={{ fontSize: 8, color: 'rgba(255,255,255,0.6)', letterSpacing: '0.1em' }}>далі</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── 3. Photo Carousel (horizontal scroller) ─────────────────────────────────
function PhotoCarousel({ scenes = ['mountain', 'sky', 'portrait', 'night', 'forest', 'city'], title = 'lhasa · травень' }) {
  const [idx, setIdx] = React.useState(0);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <div style={{ display: 'flex', overflow: 'auto', scrollSnapType: 'x mandatory', scrollbarWidth: 'none' }}
             onScroll={(e) => {
               const w = e.currentTarget.clientWidth;
               setIdx(Math.round(e.currentTarget.scrollLeft / w));
             }}>
          {scenes.map((s, i) => (
            <div key={i} style={{ flex: '0 0 100%', scrollSnapAlign: 'start' }}>
              <PainterlyPhoto scene={s} ratio="3/2" />
            </div>
          ))}
        </div>
        {/* dots */}
        <div style={{
          position: 'absolute', left: 0, right: 0, bottom: 10,
          display: 'flex', justifyContent: 'center', gap: 5,
        }}>
          {scenes.map((_, i) => (
            <div key={i} style={{
              width: i === idx ? 16 : 5, height: 5, borderRadius: 999,
              background: i === idx ? 'var(--gold)' : 'rgba(255,255,255,0.45)',
              transition: 'width 0.3s, background 0.3s',
              boxShadow: i === idx ? '0 0 6px var(--gold-glow)' : 'none',
            }} />
          ))}
        </div>
        {/* count badge */}
        <div className="mono" style={{
          position: 'absolute', top: 10, right: 10,
          fontSize: 9, color: 'rgba(255,255,255,0.8)',
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          padding: '3px 8px', borderRadius: 999,
        }}>{idx + 1} / {scenes.length}</div>
      </div>
      <div style={{ padding: '10px 12px' }}>
        <div style={{ font: '500 12.5px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        <div className="mono" style={{ marginTop: 3, fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
          ai-розпізнано: {scenes.length} кадрів · 3 локації · 2 особи
        </div>
      </div>
    </div>
  );
}

// ─── 4. Video Card (poster, play, scrubber, chapters) ────────────────────────
function VideoCard({ title = 'розмова з olya · 5 травня', duration = 412, scene = 'portrait', chapters }) {
  const [pos, setPos] = React.useState(0.18);
  const [playing, setPlaying] = React.useState(false);
  const mm = (s) => `${Math.floor(s/60)}:${String(s%60|0).padStart(2,'0')}`;
  React.useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => setPos((p) => (p + 1/duration) % 1), 1000);
    return () => clearInterval(id);
  }, [playing, duration]);

  const chs = chapters || [
    { t: 0.04, label: 'привіт' },
    { t: 0.24, label: 'про переїзд' },
    { t: 0.51, label: 'роботa' },
    { t: 0.82, label: 'плани' },
  ];

  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene={scene} ratio="16/9" />
        {/* dim wash */}
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.6) 100%)' }} />
        {/* big play */}
        <button onClick={() => setPlaying((p) => !p)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 56, height: 56, borderRadius: '50%',
          background: 'rgba(255,255,255,0.94)',
          color: '#1A140B',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          backdropFilter: 'blur(12px)',
          boxShadow: '0 6px 24px rgba(0,0,0,0.4)',
        }}>
          {playing ? (
            <svg width="16" height="16" viewBox="0 0 16 16"><rect x="4" y="3" width="3" height="10" fill="currentColor"/><rect x="9" y="3" width="3" height="10" fill="currentColor"/></svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 16 16"><path d="M4 3 L13 8 L4 13 Z" fill="currentColor"/></svg>
          )}
        </button>
        {/* HD badge */}
        <div className="mono" style={{
          position: 'absolute', top: 10, left: 10,
          fontSize: 8, color: 'rgba(255,255,255,0.85)',
          background: 'rgba(0,0,0,0.45)', padding: '3px 7px',
          borderRadius: 4, letterSpacing: '0.1em', backdropFilter: 'blur(6px)',
        }}>4K · 60fps</div>
        {/* duration */}
        <div className="mono" style={{
          position: 'absolute', bottom: 14, right: 12,
          fontSize: 10, color: '#fff', letterSpacing: '0.04em',
          fontVariantNumeric: 'tabular-nums',
        }}>{mm(pos * duration)} / {mm(duration)}</div>
      </div>
      {/* scrubber w/ chapter ticks */}
      <div style={{ padding: '10px 12px 4px' }}>
        <div style={{ position: 'relative', height: 18, cursor: 'pointer' }}
             onClick={(e) => {
               const r = e.currentTarget.getBoundingClientRect();
               setPos((e.clientX - r.left) / r.width);
             }}>
          {/* track */}
          <div style={{
            position: 'absolute', left: 0, right: 0, top: 8, height: 2,
            background: 'rgba(255,230,180,0.12)', borderRadius: 2,
          }} />
          {/* fill */}
          <div style={{
            position: 'absolute', left: 0, top: 8, height: 2, width: `${pos*100}%`,
            background: 'linear-gradient(to right, var(--gold-soft), var(--gold))',
            borderRadius: 2, boxShadow: '0 0 6px var(--gold-glow)',
          }} />
          {/* chapter ticks */}
          {chs.map((c, i) => (
            <div key={i} style={{
              position: 'absolute', left: `${c.t*100}%`, top: 5, width: 1, height: 8,
              background: 'rgba(255,255,255,0.4)',
            }} />
          ))}
          {/* knob */}
          <div style={{
            position: 'absolute', left: `${pos*100}%`, top: 4, width: 10, height: 10,
            transform: 'translateX(-5px)', borderRadius: '50%',
            background: 'var(--gold)', boxShadow: '0 0 8px var(--gold-glow)',
          }} />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginTop: 6 }}>
          <div style={{ font: '500 12px/1.3 var(--font-display)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{title}</div>
        </div>
        {/* chapters */}
        <div style={{ display: 'flex', gap: 5, marginTop: 8, flexWrap: 'wrap' }}>
          {chs.map((c, i) => {
            const active = pos >= c.t && (i === chs.length-1 || pos < chs[i+1].t);
            return (
              <button key={i} onClick={() => setPos(c.t)} style={{
                appearance: 'none', cursor: 'pointer',
                padding: '4px 8px', borderRadius: 999,
                background: active ? 'rgba(244,175,37,0.16)' : 'rgba(255,230,180,0.04)',
                border: `0.5px solid ${active ? 'var(--gold-soft)' : 'var(--glass-edge)'}`,
                color: active ? 'var(--gold)' : 'var(--ink-secondary)',
                font: '400 10px/1 var(--font-body)', letterSpacing: '0.02em',
                display: 'flex', alignItems: 'center', gap: 4,
              }}>
                <span className="mono" style={{ fontSize: 9, opacity: 0.65 }}>{mm(c.t * duration)}</span>
                {c.label}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── 5. Audio / Podcast / Voice (rich waveform + chapters + transcript) ──────
function AudioWaveform({ title = 'podcast · the daily', duration = 1842, subtitle = 'on attention · 8 травня', highlights }) {
  const [playing, setPlaying] = React.useState(false);
  const [pos, setPos] = React.useState(0.34);
  React.useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => setPos((p) => Math.min(1, p + 1/duration)), 1000);
    return () => clearInterval(id);
  }, [playing, duration]);
  const mm = (s) => `${Math.floor(s/60)}:${String(s%60|0).padStart(2,'0')}`;

  // generate deterministic waveform
  const bars = React.useMemo(() =>
    Array.from({ length: 90 }, (_, i) => {
      const t = i / 90;
      return 0.18 + Math.abs(Math.sin(t*18 + 1)) * 0.55 + Math.abs(Math.sin(t*4.7)) * 0.27;
    }), []);

  const hs = highlights || [
    { t: 0.12, q: 'увага — це валюта' },
    { t: 0.55, q: 'у тиші народжуються рішення' },
    { t: 0.81, q: 'не плутай зайнятість з присутністю' },
  ];

  return (
    <div className="glass fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <RMKicker kind="podcast" sub={`${Math.floor(duration/60)} хв`} />
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginBottom: 10 }}>
        <div style={{
          width: 64, height: 64, borderRadius: 10, flexShrink: 0,
          background: `
            radial-gradient(circle at 35% 28%, #F4AF25, transparent 55%),
            linear-gradient(160deg, #2a1810, #0a0805)`,
          border: '0.5px solid var(--glass-edge-strong)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <svg width="22" height="22" viewBox="0 0 22 22">
            <circle cx="11" cy="11" r="9" fill="none" stroke="var(--gold)" strokeWidth="0.8" />
            <circle cx="11" cy="11" r="1.5" fill="var(--gold)" />
          </svg>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{title}</div>
          <div className="mono" style={{ marginTop: 3, fontSize: 9.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
            {subtitle}
          </div>
        </div>
      </div>
      {/* waveform + scrubber */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
        <button onClick={() => setPlaying((p) => !p)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none', flexShrink: 0,
          width: 38, height: 38, borderRadius: '50%',
          background: 'var(--gold)', color: '#1A140B',
          boxShadow: '0 0 14px var(--gold-glow)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          {playing ? (
            <svg width="11" height="11" viewBox="0 0 11 11"><rect x="2" y="1" width="2.5" height="9"/><rect x="6.5" y="1" width="2.5" height="9"/></svg>
          ) : (
            <svg width="12" height="12" viewBox="0 0 12 12"><path d="M3 1 L10 6 L3 11 Z" fill="currentColor"/></svg>
          )}
        </button>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 1.2, height: 32, cursor: 'pointer' }}
             onClick={(e) => {
               const r = e.currentTarget.getBoundingClientRect();
               setPos((e.clientX - r.left) / r.width);
             }}>
          {bars.map((b, i) => {
            const filled = i / bars.length < pos;
            const isHl = hs.some(h => Math.abs(h.t - i/bars.length) < 0.01);
            return (
              <div key={i} style={{
                flex: 1, height: `${Math.max(8, b * 100)}%`,
                borderRadius: 0.5, minHeight: 2,
                background: filled ? (isHl ? '#FFD370' : 'var(--gold)') : 'var(--ink-tertiary)',
                opacity: filled ? 1 : 0.45,
                boxShadow: isHl ? '0 0 4px var(--gold-glow)' : 'none',
              }} />
            );
          })}
        </div>
        <div className="mono" style={{ fontSize: 9.5, color: 'var(--ink-secondary)', minWidth: 64, textAlign: 'right',
          fontVariantNumeric: 'tabular-nums' }}>
          {mm(pos * duration)} / {mm(duration)}
        </div>
      </div>
      {/* highlights — AI pull-quotes */}
      <div style={{ marginTop: 10, borderTop: '0.5px solid var(--glass-edge)', paddingTop: 10 }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· phantom · 3 фрагменти варті повернутись</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {hs.map((h, i) => (
            <button key={i} onClick={() => setPos(h.t)} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              background: pos > h.t - 0.02 && pos < h.t + 0.04 ? 'rgba(244,175,37,0.06)' : 'transparent',
              border: '0.5px solid transparent',
              borderLeft: '2px solid var(--gold-soft)',
              padding: '4px 8px', borderRadius: 6,
              display: 'flex', alignItems: 'baseline', gap: 8,
            }}>
              <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', minWidth: 32 }}>{mm(h.t * duration)}</span>
              <span style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--ink)', fontStyle: 'italic', textWrap: 'pretty', flex: 1 }}>
                «{h.q}»
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── 6. Document long-form preview (multi-page strip + highlights) ───────────
function DocumentLong({ title = 'design-brief-v3.pdf', pages = 24, when = '14 травня', extracted }) {
  const [active, setActive] = React.useState(2);
  const ex = extracted || [
    { kind: 'title', text: 'PHANTOM Familiar · v3 brief' },
    { kind: 'h2', text: '3. Тонкощі присутності' },
    { kind: 'p', text: 'orb не повинен жадати погляду. він — повітря. зміна стану — як зміна світла на стіні: помічаєш, коли треба.' },
    { kind: 'quote', text: '«не питай дозволу — будь поруч»' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <RMKicker kind="документ · pdf · indexed" sub={`${pages} сторінок · ${when}`} />
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        {/* big page preview */}
        <div style={{
          width: 96, aspectRatio: '4/5.6', borderRadius: 4, padding: 8,
          background: 'rgba(248,247,245,0.94)', flexShrink: 0,
          border: '0.5px solid rgba(255,230,180,0.2)',
          boxShadow: '0 4px 10px rgba(0,0,0,0.3)',
          display: 'flex', flexDirection: 'column', gap: 3,
        }}>
          {ex.map((line, i) => (
            <div key={i} style={{
              height: line.kind === 'title' ? 4 : line.kind === 'h2' ? 3 : line.kind === 'quote' ? 2.5 : 1.5,
              width: line.kind === 'title' ? '70%' : line.kind === 'h2' ? '55%' : line.kind === 'quote' ? '80%' : i % 3 === 0 ? '95%' : '85%',
              background: line.kind === 'title' ? '#1a140b' : line.kind === 'h2' ? '#3a2818' : '#5d5142',
              borderRadius: 0.5,
              opacity: line.kind === 'quote' ? 0.8 : 0.85,
            }} />
          ))}
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={'a'+i} style={{
              height: 1.2, width: `${82 - (i*5)}%`,
              background: '#5d5142', opacity: 0.6, borderRadius: 0.5,
            }} />
          ))}
          <div style={{ flex: 1 }} />
          <div className="mono" style={{ fontSize: 5, color: '#5d5142', textAlign: 'center' }}>p. {active+1} / {pages}</div>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ font: '500 12.5px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 4, textWrap: 'pretty' }}>{title}</div>
          {/* extracted summary */}
          <div style={{
            background: 'rgba(244,175,37,0.06)',
            border: '0.5px solid rgba(244,175,37,0.18)',
            borderRadius: 8, padding: '6px 8px', marginBottom: 8,
            font: '400 10.5px/1.4 var(--font-body)', color: 'var(--gold-soft)', textWrap: 'pretty',
          }}>
            <span className="mono" style={{ fontSize: 8, letterSpacing: '0.14em', textTransform: 'uppercase', display: 'block', marginBottom: 2 }}>· phantom · читав</span>
            теза: «familiar ≠ assistant». 24 сторінки про присутність, ритми та чому орб має дихати.
          </div>
          {/* page strip */}
          <div style={{ display: 'flex', gap: 3, overflow: 'auto', scrollbarWidth: 'none' }}>
            {Array.from({ length: Math.min(pages, 10) }).map((_, i) => (
              <button key={i} onClick={() => setActive(i)} style={{
                appearance: 'none', cursor: 'pointer', flexShrink: 0,
                width: 26, aspectRatio: '4/5.6',
                borderRadius: 3, padding: 3,
                background: i === active ? 'rgba(244,175,37,0.94)' : 'rgba(248,247,245,0.5)',
                border: i === active ? 'none' : '0.5px solid var(--glass-edge)',
                display: 'flex', flexDirection: 'column', gap: 1.2,
                boxShadow: i === active ? '0 0 8px var(--gold-glow)' : 'none',
              }}>
                {[0.6, 0.45, 0.55, 0.4, 0.5].map((w, j) => (
                  <div key={j} style={{ height: 1, width: `${w*100}%`, background: i === active ? '#1A140B' : '#5d5142', opacity: 0.7 }} />
                ))}
              </button>
            ))}
            {pages > 10 && (
              <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', alignSelf: 'center', paddingLeft: 4 }}>
                +{pages - 10}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── 7. Spreadsheet preview (mini table + sparkline) ─────────────────────────
function SpreadsheetPreview({ title = 'витрати · травень.xlsx', sheets = ['витрати', 'категорії', 'місяць'], rows }) {
  const data = rows || [
    ['дата',     'категорія',   'опис',           'сума', 'spark'],
    ['01.05',   'кафе',         'local roasters', '4.20',  [3,5,4,6,8,4,5]],
    ['03.05',   'продукти',     'metro',         '47.10', [12,18,14,11,15,17,13]],
    ['05.05',   'транспорт',    'таксі · аеропорт','22.00', [5,3,4,7,6,4,5]],
    ['08.05',   'підписки',     'plus',          '12.00', [12,12,12,12,12,12,12]],
    ['12.05',   'кафе',         'fika',           '6.80',  [3,5,4,6,8,4,5]],
    ['14.05',   'кафе',         'anna · кава',    '4.20',  [3,5,4,6,8,4,5]],
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '10px 12px 6px', borderBottom: '0.5px solid var(--glass-edge)' }}>
        <RMKicker kind="таблиця · xlsx" sub={`${data.length-1} рядків · phantom прочитав`} />
        <div style={{ font: '500 12.5px/1.3 var(--font-display)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{title}</div>
        <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
          {sheets.map((s, i) => (
            <span key={s} className="mono" style={{
              padding: '3px 7px', borderRadius: 6, fontSize: 9,
              background: i === 0 ? 'rgba(244,175,37,0.14)' : 'rgba(255,230,180,0.04)',
              color: i === 0 ? 'var(--gold)' : 'var(--ink-tertiary)',
              border: '0.5px solid var(--glass-edge)',
              letterSpacing: '0.04em',
            }}>{s}</span>
          ))}
        </div>
      </div>
      <div style={{ overflow: 'auto' }}>
        <table style={{
          width: '100%', borderCollapse: 'collapse',
          font: '400 10.5px/1 var(--font-mono)', color: 'var(--ink)',
        }}>
          <tbody>
            {data.map((row, ri) => (
              <tr key={ri} style={{
                background: ri === 0 ? 'rgba(255,230,180,0.04)' : ri % 2 ? 'transparent' : 'rgba(0,0,0,0.08)',
                color: ri === 0 ? 'var(--gold-soft)' : 'var(--ink)',
              }}>
                {row.map((cell, ci) => (
                  <td key={ci} style={{
                    padding: '6px 8px',
                    borderTop: ri ? '0.5px solid var(--glass-edge)' : 'none',
                    fontSize: ri === 0 ? 8.5 : 10,
                    letterSpacing: ri === 0 ? '0.12em' : '0.02em',
                    textTransform: ri === 0 ? 'uppercase' : 'none',
                    textAlign: ci === 3 ? 'right' : 'left',
                    fontVariantNumeric: 'tabular-nums',
                    whiteSpace: 'nowrap',
                  }}>
                    {Array.isArray(cell) ? (
                      <svg width="56" height="14" viewBox="0 0 56 14" style={{ display: 'block' }}>
                        <polyline
                          points={cell.map((v, i) => `${(i/(cell.length-1))*54+1},${14 - (v/Math.max(...cell))*12-1}`).join(' ')}
                          fill="none" stroke="var(--gold)" strokeWidth="1" />
                      </svg>
                    ) : ci === 3 && ri > 0 ? (
                      <span style={{ color: 'var(--ink-strong)' }}>{cell}</span>
                    ) : cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div style={{
        padding: '8px 12px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge-strong)',
      }}>
        <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.1em' }}>
          · сума травня · 96.30 $
        </span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--coral)' }}>
          ↑ 12% vs квітень
        </span>
      </div>
    </div>
  );
}

// ─── 8. File Transfer / multi-upload (live progress) ─────────────────────────
function FileTransfer({ files }) {
  const fs = files || [
    { name: 'IMG_0421.HEIC', size: '2.4 MB', kind: 'photo', progress: 1.0,  scene: 'mountain' },
    { name: 'IMG_0422.HEIC', size: '2.1 MB', kind: 'photo', progress: 1.0,  scene: 'forest' },
    { name: 'voice-memo-03.m4a', size: '4.8 MB', kind: 'audio', progress: 0.72 },
    { name: 'sapiens-ch1.pdf', size: '12 MB', kind: 'pdf', progress: 0.34 },
  ];
  const total = fs.length;
  const done = fs.filter(f => f.progress >= 1).length;
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
        <RMKicker kind="передача" sub={`${done}/${total} · vault → phantom`} />
        <span className="mono" style={{ fontSize: 9, color: 'var(--sage)', letterSpacing: '0.08em' }}>
          {done < total ? '· шифрую · ed25519 ·' : '· готово ·'}
        </span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {fs.map((f, i) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '6px 8px', borderRadius: 10,
            background: f.progress >= 1 ? 'rgba(157,204,159,0.04)' : 'rgba(244,175,37,0.04)',
            border: `0.5px solid ${f.progress >= 1 ? 'rgba(157,204,159,0.15)' : 'rgba(244,175,37,0.15)'}`,
          }}>
            {/* thumb / icon */}
            <div style={{
              width: 36, height: 36, borderRadius: 6, overflow: 'hidden', flexShrink: 0,
              background: f.kind === 'photo' ? 'transparent' :
                          f.kind === 'audio' ? 'rgba(126,93,164,0.18)' :
                          'rgba(255,139,110,0.12)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              position: 'relative',
            }}>
              {f.kind === 'photo' && <PainterlyPhoto scene={f.scene || 'mountain'} ratio="1/1" />}
              {f.kind === 'audio' && <svg width="14" height="14" viewBox="0 0 14 14"><path d="M5 2v10M9 4v6M3 5v4M11 5v4M7 1v12" stroke="#B49AD4" strokeWidth="1.4" strokeLinecap="round"/></svg>}
              {f.kind === 'pdf' && <span className="mono" style={{ fontSize: 8, color: 'var(--coral)', fontWeight: 600 }}>PDF</span>}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 6 }}>
                <span style={{ font: '500 11px/1.2 var(--font-body)', color: 'var(--ink-strong)',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{f.name}</span>
                <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', flexShrink: 0 }}>{f.size}</span>
              </div>
              <div style={{ height: 2, background: 'rgba(255,230,180,0.06)', borderRadius: 1, marginTop: 5, overflow: 'hidden' }}>
                <div style={{
                  width: `${f.progress*100}%`, height: '100%',
                  background: f.progress >= 1
                    ? 'linear-gradient(to right, var(--sage), #B5DFB7)'
                    : 'linear-gradient(to right, var(--gold-soft), var(--gold))',
                  boxShadow: f.progress < 1 ? '0 0 6px var(--gold-glow)' : 'none',
                  transition: 'width 0.4s ease',
                }} />
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 3 }}>
                <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
                  {f.progress >= 1 ? '· verified · sha256' : `· ${(f.progress*100|0)}%`}
                </span>
                {f.progress < 1 && (
                  <span className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)' }}>
                    {((1-f.progress)*8 | 0)+1}с
                  </span>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 9. 3D model card (orbiting wireframe) ───────────────────────────────────
function Model3DCard({ title = 'lhasa-roof.glb', polys = '12 432', size = '4.2 MB' }) {
  const canvasRef = React.useRef(null);
  React.useEffect(() => {
    const c = canvasRef.current; if (!c) return;
    const ctx = c.getContext('2d');
    const W = c.width = c.clientWidth * 2;
    const H = c.height = c.clientHeight * 2;
    let raf, rot = 0;
    // build a stepped pagoda mesh
    const pts = [];
    const lvls = 6;
    for (let l = 0; l < lvls; l++) {
      const y = (l - lvls/2) * 0.45;
      const r = 1.3 - l * 0.15;
      const seg = 6;
      for (let i = 0; i < seg; i++) {
        const a = (i/seg) * Math.PI * 2;
        pts.push({ x: Math.cos(a)*r, y, z: Math.sin(a)*r, lvl: l, i });
      }
    }
    pts.push({ x: 0, y: -lvls*0.45/2 - 0.5, z: 0, lvl: -1 });
    const proj = (p) => {
      const cs = Math.cos(rot), sn = Math.sin(rot);
      const rx = p.x*cs - p.z*sn, rz = p.x*sn + p.z*cs;
      const f = W*0.32 / (4 + rz);
      return { x: W/2 + rx*f, y: H/2 + p.y*f, z: rz };
    };
    const draw = () => {
      ctx.clearRect(0,0,W,H);
      const ppts = pts.map(p => ({ ...p, ...proj(p) }));
      // edges within level
      ctx.strokeStyle = 'rgba(244,175,37,0.7)';
      ctx.lineWidth = 1.5;
      const lvlGroups = {};
      ppts.forEach(p => { if (p.lvl >= 0) (lvlGroups[p.lvl] ||= []).push(p); });
      Object.values(lvlGroups).forEach(grp => {
        for (let i = 0; i < grp.length; i++) {
          const a = grp[i], b = grp[(i+1)%grp.length];
          ctx.globalAlpha = 0.4 + (a.z + b.z + 8)/16 * 0.5;
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        }
      });
      // verticals between levels (close points)
      for (let l = 0; l < 5; l++) {
        const a = lvlGroups[l], b = lvlGroups[l+1];
        if (!a || !b) continue;
        for (let i = 0; i < a.length; i++) {
          ctx.globalAlpha = 0.3;
          ctx.beginPath(); ctx.moveTo(a[i].x, a[i].y); ctx.lineTo(b[i].x, b[i].y); ctx.stroke();
        }
      }
      // spire
      const top = ppts[ppts.length-1];
      const tip = lvlGroups[0]?.[0];
      if (top && tip) {
        ctx.globalAlpha = 0.8;
        ctx.beginPath(); ctx.moveTo(top.x, top.y);
        lvlGroups[0].forEach(p => ctx.lineTo(p.x, p.y));
        ctx.stroke();
      }
      // glow dots
      ctx.globalAlpha = 1;
      ppts.forEach(p => {
        ctx.fillStyle = '#F4AF25';
        ctx.beginPath(); ctx.arc(p.x, p.y, 1.4, 0, Math.PI*2); ctx.fill();
      });
      rot += 0.006;
      raf = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, []);
  return (
    <div className="glass fade-in" style={{ padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden' }}>
      <div style={{ height: 180, position: 'relative',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)' }}>
        <canvas ref={canvasRef} style={{ width: '100%', height: '100%', display: 'block' }} />
        <div className="mono" style={{
          position: 'absolute', top: 8, left: 8, fontSize: 8,
          color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
        }}>· obj · {polys} polys</div>
        <div className="mono" style={{
          position: 'absolute', top: 8, right: 8, fontSize: 8,
          color: 'var(--gold-soft)', letterSpacing: '0.14em',
          background: 'rgba(0,0,0,0.4)', padding: '2px 6px', borderRadius: 4,
        }}>3D · live</div>
        {/* control hint */}
        <div className="mono" style={{
          position: 'absolute', bottom: 8, left: 0, right: 0, textAlign: 'center',
          fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.12em',
        }}>· обертається · pinch для масштабу ·</div>
      </div>
      <div style={{ padding: '10px 12px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        borderTop: '0.5px solid var(--glass-edge)' }}>
        <div>
          <div style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
          <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 2 }}>{size} · glb</div>
        </div>
        <button style={{
          appearance: 'none', cursor: 'pointer',
          background: 'rgba(244,175,37,0.10)', border: '0.5px solid rgba(244,175,37,0.3)',
          color: 'var(--gold)', font: '400 10px/1 var(--font-body)',
          padding: '6px 10px', borderRadius: 999,
        }}>відкрити в ar</button>
      </div>
    </div>
  );
}

// ─── 10. Image-gen in progress (diffusion animation) ─────────────────────────
function ImageGenInProgress({ prompt = 'lhasa o 19:30, листопад, шафран та сапфір', step = 18, total = 30 }) {
  const [s, setS] = React.useState(step);
  React.useEffect(() => {
    const id = setInterval(() => setS((x) => x < total ? x + 1 : x), 320);
    return () => clearInterval(id);
  }, [total]);
  const pct = s / total;
  return (
    <div className="glass fade-in" style={{ padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden' }}>
      <div style={{ position: 'relative', aspectRatio: '1' }}>
        {/* progressive denoising — blurred to sharp */}
        <div style={{
          position: 'absolute', inset: 0,
          background: `
            radial-gradient(ellipse 60% 40% at 30% 28%, rgba(244,175,37,${0.4 + pct*0.4}), transparent 60%),
            radial-gradient(ellipse 50% 30% at 75% 65%, rgba(231,111,81,${0.2 + pct*0.3}), transparent 60%),
            linear-gradient(180deg, #1a1108 0%, ${pct > 0.5 ? '#5a3818' : '#3a2812'} 40%, #2a1808 80%, #0a0805 100%)`,
          filter: `blur(${(1-pct) * 30}px)`,
          transition: 'filter 0.3s ease, background 0.3s ease',
        }} />
        {/* mountain only sharpens after step 60% */}
        {pct > 0.5 && (
          <svg viewBox="0 0 400 300" preserveAspectRatio="none"
               style={{ position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '55%',
                 opacity: (pct - 0.5)*2, transition: 'opacity 0.4s' }}>
            <path d="M 0 300 L 0 165 L 50 145 L 100 160 L 150 110 L 200 135 L 245 95 L 295 130 L 340 100 L 400 140 L 400 300 Z"
                  fill="#080604" />
          </svg>
        )}
        {/* noise overlay — fades out */}
        <div style={{
          position: 'absolute', inset: 0,
          background:
            'repeating-radial-gradient(circle at 30% 40%, rgba(255,255,255,0.08) 0 1px, transparent 1px 2px),' +
            'repeating-radial-gradient(circle at 70% 60%, rgba(0,0,0,0.07) 0 1px, transparent 1px 3px)',
          opacity: 1 - pct,
          mixBlendMode: 'overlay',
        }} />
        {/* scanline */}
        <div style={{
          position: 'absolute', left: 0, right: 0,
          top: `${pct*100}%`, height: 1,
          background: 'linear-gradient(to right, transparent, var(--gold), transparent)',
          boxShadow: '0 0 12px var(--gold-glow)',
          transition: 'top 0.3s',
        }} />
        {/* corner step */}
        <div className="mono" style={{
          position: 'absolute', top: 10, left: 10,
          fontSize: 8.5, color: 'rgba(255,255,255,0.85)',
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          padding: '3px 7px', borderRadius: 4, letterSpacing: '0.08em',
        }}>· diffusion · step {s} / {total}</div>
      </div>
      <div style={{ padding: '10px 12px' }}>
        <div style={{ font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink)', fontStyle: 'italic', textWrap: 'pretty' }}>
          «{prompt}»
        </div>
        <div style={{ height: 2, background: 'rgba(255,230,180,0.08)', borderRadius: 1, marginTop: 8, overflow: 'hidden' }}>
          <div style={{ width: `${pct*100}%`, height: '100%',
            background: 'linear-gradient(to right, var(--gold-soft), var(--gold))',
            boxShadow: '0 0 6px var(--gold-glow)',
            transition: 'width 0.3s ease',
          }} />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 5 }}>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>sdxl-turbo · seed 0x8a3f</span>
          <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)' }}>{(pct*100|0)}%</span>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  PainterlyPhoto, PhotoHero, PhotoAlbum, PhotoCarousel,
  VideoCard, AudioWaveform, DocumentLong, SpreadsheetPreview,
  FileTransfer, Model3DCard, ImageGenInProgress, RMKicker,
});
