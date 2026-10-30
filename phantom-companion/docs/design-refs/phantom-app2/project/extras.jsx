/* extras.jsx — additional surfaces:
   · LockScreen (AOD complication)
   · HomeScreen (vitals widget, app icons)
   · FloatingOrbOverlay (over another app)
   · LiveWallpaper (background variants)
   · SettingsSheet (conversational, not toggle-grid) */

// ─── Live Wallpaper backgrounds ──────────────────────────────────────────────
function WandererSparks({ count = 14, color = 'var(--gold)' }) {
  // wandering orbital sparks — particle drift
  const sparks = React.useMemo(() => Array.from({ length: count }).map((_, i) => ({
    id: i,
    x: Math.random() * 100,
    y: Math.random() * 100,
    dx: (Math.random() - 0.5) * 60,
    dy: (Math.random() - 0.5) * 60,
    rot: (Math.random() - 0.5) * 60,
    size: 1 + Math.random() * 2,
    delay: Math.random() * 12,
    dur: 12 + Math.random() * 18,
  })), [count]);
  return (
    <>
      {sparks.map((s) => (
        <div key={s.id} style={{
          position: 'absolute',
          left: `${s.x}%`, top: `${s.y}%`,
          width: s.size, height: s.size,
          borderRadius: '50%',
          background: color,
          boxShadow: `0 0 ${4 + s.size * 2}px var(--gold-glow)`,
          animation: `phantom-spark-drift ${s.dur}s linear ${s.delay}s infinite`,
          '--dx': `${s.dx}px`, '--dy': `${s.dy}px`, '--rot': `${s.rot}deg`,
          opacity: 0,
        }} />
      ))}
    </>
  );
}

function LiveWallpaper({ variant = 'sparks', state = 'FOCUS' }) {
  // breathing radial bloom synced to state color
  const stateColor = {
    FOCUS:    '#5a3010',
    DIALOGUE: '#6a3818',
    SENTINEL: '#5a2a08',
    GHOST:    '#1a1a1a',
    SHADOW:   '#3a2210',
    DREAM:    '#3a2818',
  }[state];
  return (
    <div style={{
      position: 'absolute', inset: 0, overflow: 'hidden',
      background: 'var(--bg-base)',
      transition: 'background 1.5s ease',
    }}>
      <div style={{
        position: 'absolute', left: '-30%', top: '-20%', width: '160%', height: '120%',
        background: `radial-gradient(ellipse at 30% 30%,
          ${stateColor} 0%,
          rgba(244,175,37,0.04) 30%,
          transparent 65%)`,
        filter: 'blur(40px)',
        animation: 'phantom-breathe var(--breath-period) ease-in-out infinite',
        transition: 'background 1.5s ease',
      }} />
      <div style={{
        position: 'absolute', right: '-20%', bottom: '-30%', width: '120%', height: '120%',
        background: `radial-gradient(ellipse at 70% 70%,
          rgba(126,93,164,0.10) 0%,
          transparent 60%)`,
        filter: 'blur(50px)',
        animation: 'phantom-breathe calc(var(--breath-period) * 1.3) ease-in-out infinite',
      }} />
      {variant === 'sparks' && <WandererSparks count={14} />}
      {variant === 'wallpaper' && (
        <>
          {/* topographic contour rings — subtle */}
          <svg viewBox="0 0 412 892" preserveAspectRatio="xMidYMid slice"
               style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', opacity: 0.18 }}>
            {[60, 120, 180, 240, 300, 360, 420].map((r, i) => (
              <circle key={i} cx="206" cy="446" r={r} fill="none"
                      stroke="rgba(244,175,37,0.3)" strokeWidth="0.5"
                      strokeDasharray={i % 2 ? '0' : '2 6'} />
            ))}
          </svg>
        </>
      )}
    </div>
  );
}

// ─── Floating Orb overlay ────────────────────────────────────────────────────
function FloatingOrbOverlay({ state, audio, motionScale, breathPeriod, orbTier, onTap, onLongPress }) {
  // 88dp orb in bottom-right — visible above current screen
  const [pos, setPos] = React.useState({ right: 16, bottom: 100 });
  const draggingRef = React.useRef(null);

  const onPointerDown = (e) => {
    const startX = e.clientX, startY = e.clientY;
    const startRight = pos.right, startBottom = pos.bottom;
    let moved = false;
    const move = (ev) => {
      const dx = ev.clientX - startX, dy = ev.clientY - startY;
      if (Math.hypot(dx, dy) > 6) moved = true;
      if (moved) {
        setPos({
          right: Math.max(8, startRight - dx),
          bottom: Math.max(60, startBottom - dy),
        });
      }
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  return (
    <div onPointerDown={onPointerDown}
         style={{
           position: 'absolute',
           right: pos.right, bottom: pos.bottom,
           width: 72, height: 72,
           pointerEvents: 'auto',
           filter: state === 'GHOST' ? 'opacity(0.4)' : 'none',
           transition: 'filter 1s ease',
           zIndex: 50,
         }}>
      <div style={{
        position: 'absolute', inset: -4, borderRadius: '50%',
        background: 'radial-gradient(circle, var(--gold-glow) 0%, transparent 70%)',
        filter: 'blur(8px)',
        opacity: state === 'GHOST' ? 0 : 0.6,
      }} />
      <Orb state={state} tier={orbTier} size={72} audio={audio}
           motionScale={motionScale} breathPeriod={breathPeriod}
           onTap={onTap} onLongPress={onLongPress} />
    </div>
  );
}

// ─── Lock Screen / AOD ───────────────────────────────────────────────────────
function LockScreen({ state, orbTier, motionScale, breathPeriod, audio, time = '19:34', dwellMin = 17 }) {
  const [insightIdx, setInsightIdx] = React.useState(0);
  const insights = [
    'ти просив нагадати про олега о 18:00',
    'двічі сьогодні відкривав vault — все добре?',
    'дощ за пів години · парасолю в передпокої',
  ];
  React.useEffect(() => {
    const id = setInterval(() => setInsightIdx((i) => (i + 1) % insights.length), 5000);
    return () => clearInterval(id);
  }, []);

  return (
    <div style={{
      position: 'absolute', inset: 0,
      display: 'flex', flexDirection: 'column',
      padding: '60px 28px 40px',
      color: 'var(--ink-strong)',
    }}>
      {/* time */}
      <div style={{
        font: '300 78px/0.95 var(--font-display)',
        letterSpacing: '-0.025em',
        color: 'var(--ink-strong)',
        textShadow: '0 0 30px rgba(0,0,0,0.6)',
      }}>{time}</div>
      <div className="mono" style={{
        marginTop: 4, fontSize: 11, color: 'var(--ink-secondary)',
        letterSpacing: '0.06em',
      }}>середа · 14 травня</div>

      <div style={{ flex: 1 }} />

      {/* AOD complication — orb + state + dwell + rotating insight */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24,
      }}>
        <Orb state={state} tier={orbTier} size={56} audio={audio}
             motionScale={motionScale} breathPeriod={breathPeriod} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
            <span className="state-label" style={{ fontSize: 11, color: 'var(--ink-strong)' }}>
              {STATE_NAMES[state]}
            </span>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>
              {dwellMin}хв
            </span>
          </div>
          <div key={insightIdx} className="fade-in" style={{
            font: '400 12px/1.4 var(--font-body)',
            color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>
            {insights[insightIdx]}
          </div>
        </div>
      </div>

      <div className="mono" style={{
        textAlign: 'center', fontSize: 9, color: 'var(--ink-tertiary)',
        letterSpacing: '0.16em', textTransform: 'uppercase',
      }}>проведи знизу — фантом</div>
    </div>
  );
}

// ─── Home screen — vitals widget + dock icons (placeholder app) ─────────────
function HomeScreen({ state, orbTier, motionScale, breathPeriod, audio }) {
  const apps = [
    { n: 'фантом',    g: 'phantom' },
    { n: 'каме́ра',    g: 'cam'    },
    { n: 'мапи',      g: 'map'    },
    { n: 'термінал',  g: 'term'   },
    { n: 'нотатки',   g: 'notes'  },
    { n: 'месенджер', g: 'msg'    },
    { n: 'музика',    g: 'mus'    },
    { n: 'сигнал',    g: 'sig'    },
  ];
  const iconBg = (g) => ({
    phantom: 'radial-gradient(circle at 38% 32%, #FFD370, #F4AF25 50%, #7A4818 100%)',
    cam:     'linear-gradient(135deg, #2a2118, #15100a)',
    map:     'linear-gradient(135deg, #1a2a18, #0a1108)',
    term:    'linear-gradient(135deg, #1a1410, #0a0805)',
    notes:   'linear-gradient(135deg, #1a160e, #0a0805)',
    msg:     'linear-gradient(135deg, #2a1820, #150a14)',
    mus:     'linear-gradient(135deg, #2a1818, #150a0a)',
    sig:     'linear-gradient(135deg, #1a1a2a, #0a0a14)',
  }[g]);

  return (
    <div style={{
      position: 'absolute', inset: 0,
      display: 'flex', flexDirection: 'column',
      padding: '60px 22px 40px',
    }}>
      {/* time */}
      <div style={{
        font: '300 50px/1 var(--font-display)',
        letterSpacing: '-0.02em',
        color: 'var(--ink-strong)',
      }}>19:34</div>
      <div className="mono" style={{
        marginTop: 2, fontSize: 10, color: 'var(--ink-secondary)',
        letterSpacing: '0.06em',
      }}>середа · 14 травня · lhasa · +2°c</div>

      {/* vitals widget — 4×2 form */}
      <div className="glass" style={{
        marginTop: 24, padding: '16px 18px', borderRadius: 22,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
          <Orb state={state} tier={orbTier} size={40} audio={audio}
               motionScale={motionScale} breathPeriod={breathPeriod} />
          <div style={{ flex: 1 }}>
            <div className="state-label" style={{ fontSize: 11, color: 'var(--ink-strong)', marginBottom: 4 }}>
              {STATE_NAMES[state]}
            </div>
            <div style={{ font: '400 11px/1.3 var(--font-body)', color: 'var(--ink-tertiary)' }}>
              {STATE_NARRATIVES[state]}
            </div>
          </div>
          <button style={{
            appearance: 'none', border: '0.5px solid var(--glass-edge-strong)',
            background: 'rgba(244,175,37,0.08)', color: 'var(--gold)',
            width: 32, height: 32, borderRadius: '50%',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer',
          }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <rect x="9" y="3" width="6" height="12" rx="3" fill="currentColor" />
              <path d="M5 11a7 7 0 0014 0M12 18v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 8,
        }}>
          {[
            ['BPM',  '64'],
            ['HRV',  '48'],
            ['SNS',  '88'],
            ['LNK',  'on'],
          ].map(([k, v]) => (
            <div key={k} style={{
              padding: '6px 0',
              borderTop: '0.5px solid rgba(255,230,180,0.06)',
              display: 'flex', flexDirection: 'column', gap: 1,
            }}>
              <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.1em' }}>{k}</div>
              <div style={{ font: '500 14px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{v}</div>
            </div>
          ))}
        </div>
      </div>

      {/* app grid */}
      <div style={{
        marginTop: 28,
        display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 18,
      }}>
        {apps.map((a, i) => (
          <div key={i} style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
          }}>
            <div style={{
              width: 56, height: 56, borderRadius: 16,
              background: iconBg(a.g),
              border: '0.5px solid rgba(255,230,180,0.08)',
              boxShadow: a.g === 'phantom' ? '0 0 16px var(--gold-glow)' : 'inset 0 1px 0 rgba(255,255,255,0.04)',
            }} />
            <span style={{ font: '400 10px/1 var(--font-body)', color: 'var(--ink)' }}>{a.n}</span>
          </div>
        ))}
      </div>

      <div style={{ flex: 1 }} />
    </div>
  );
}

// ─── Settings Sheet ──────────────────────────────────────────────────────────
function SettingsSheet({ onClose, theme, onChangeTheme }) {
  const sections = [
    {
      label: 'AI · мозок',
      lines: [
        ['Gemini', 'підключено · 23 запити сьогодні · ключ ...3afb'],
        ['Gemma локально', '4B · завантажено · 2.1 GB'],
        ['маршрут', 'адаптивно — cloud коли можна, on-device коли не можна'],
      ],
    },
    {
      label: 'голос',
      lines: [
        ['рушій TTS', 'StyleTTS2 · клон активний'],
        ['клонований голос', '30-сек запис від 12 травня'],
        ['мова', 'українська · ти-форма'],
      ],
    },
    {
      label: 'тема',
      lines: [
        ['зараз', theme === 'amber-night' ? 'amber-night · вечір' : 'sunrise-warm · день'],
        ['авто', 'за часом доби'],
      ],
    },
    {
      label: 'звʼязок',
      lines: [
        ['phantom-os', 'спарено · 4ms RTT'],
        ['канал', '9 активних · агент.* / vault.* / map.* / handoff.*'],
      ],
    },
    {
      label: 'дозволи',
      lines: [
        ['екран', 'надано · familiar має контекст'],
        ['камера · мікрофон · gps', 'усі надано'],
        ['передпокій сповіщень', 'тиша по замовчуванню — наказати говорити явно'],
      ],
    },
    {
      label: 'оператор',
      lines: [
        ['ти', 'кирил · root-trust'],
        ['профілі', '1 активний · 0 в архіві'],
      ],
    },
  ];

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'rgba(6,4,2,0.6)',
      backdropFilter: 'blur(8px)',
      display: 'flex', flexDirection: 'column', justifyContent: 'flex-end',
      animation: 'phantom-fade-in 0.3s ease-out',
    }} onClick={onClose}>
      <div onClick={(e) => e.stopPropagation()}
           className="glass hero" style={{
             padding: '20px 22px 32px',
             borderRadius: '24px 24px 0 0',
             maxHeight: '85%',
             overflow: 'auto',
             borderBottom: 'none',
           }}>
        <div style={{
          width: 36, height: 3, background: 'var(--ink-faint)',
          borderRadius: 2, margin: '0 auto 16px',
        }} />
        <div className="state-label" style={{ fontSize: 11, color: 'var(--ink-strong)', marginBottom: 16 }}>
          НАЛАШТУВАННЯ
        </div>
        {sections.map((s, i) => (
          <div key={i} style={{ marginBottom: 18 }}>
            <div className="mono" style={{
              fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
              textTransform: 'uppercase', marginBottom: 8,
            }}>· {s.label}</div>
            {s.lines.map(([k, v], j) => (
              <div key={j} style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                padding: '6px 0', gap: 12,
                borderBottom: j < s.lines.length - 1 ? '0.5px solid rgba(255,230,180,0.05)' : 'none',
              }}>
                <span style={{ font: '400 12px/1.4 var(--font-body)', color: 'var(--ink-strong)', flexShrink: 0 }}>{k}</span>
                <span style={{
                  font: '400 11px/1.4 var(--font-body)',
                  color: 'var(--ink-tertiary)', textAlign: 'right',
                  letterSpacing: '0.005em',
                }}>{v}</span>
              </div>
            ))}
          </div>
        ))}
        <button onClick={onClose} style={{
          appearance: 'none', border: '0.5px solid var(--glass-edge-strong)',
          background: 'transparent', color: 'var(--ink-strong)',
          font: '500 12px/1 var(--font-body)',
          padding: '10px 16px', borderRadius: 999, cursor: 'pointer',
          width: '100%', marginTop: 8,
        }}>повернутись</button>
      </div>
    </div>
  );
}

Object.assign(window, {
  WandererSparks, LiveWallpaper, FloatingOrbOverlay,
  LockScreen, HomeScreen, SettingsSheet,
});
