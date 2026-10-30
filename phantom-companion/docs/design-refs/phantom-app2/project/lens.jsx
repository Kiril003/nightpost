/* lens.jsx — Lens · video conversation with phantom.
   not a video call. a portrait observed.
   you · stylized silhouette
   phantom · orb watching
   live readings · face/breath/eyes/microexpression
   4 modes · observer · companion · mirror · oracle */

// ─── YourPortrait — animated silhouette of you ──────────────────────────────
function YourPortrait({ breath, speaking, blinkPhase, gaze, mood = 'soft', skinHue = '#E8C09A' }) {
  // breath: 0..1 chest rise
  // speaking: 0..1 mouth open
  // blinkPhase: 0..1, 0.95+ closed
  // gaze: { x: -1..1, y: -1..1 } where eyes point
  const chestY = 280 + breath * 4;
  const mouthH = 1 + speaking * 4;
  const eyeOpen = blinkPhase < 0.95;
  return (
    <svg viewBox="0 0 400 460" style={{ width: '100%', height: '100%', display: 'block' }}>
      <defs>
        <radialGradient id="skinLight" cx="0.42" cy="0.34" r="0.7">
          <stop offset="0%" stopColor={skinHue} stopOpacity="0.95" />
          <stop offset="60%" stopColor={skinHue} stopOpacity="0.55" />
          <stop offset="100%" stopColor="#2a1810" stopOpacity="0.6" />
        </radialGradient>
        <radialGradient id="hairGrad" cx="0.4" cy="0.2" r="0.7">
          <stop offset="0%" stopColor="#3a2818" />
          <stop offset="100%" stopColor="#15100A" />
        </radialGradient>
        <radialGradient id="shouldGrad" cx="0.5" cy="0.2" r="0.7">
          <stop offset="0%" stopColor="#5a3818" stopOpacity="0.85" />
          <stop offset="100%" stopColor="#0a0805" stopOpacity="0.6" />
        </radialGradient>
        <radialGradient id="haloGrad" cx="0.5" cy="0.4" r="0.6">
          <stop offset="0%" stopColor="rgba(244,175,37,0.18)" />
          <stop offset="60%" stopColor="rgba(244,175,37,0.05)" />
          <stop offset="100%" stopColor="rgba(244,175,37,0)" />
        </radialGradient>
        <filter id="softblur" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="0.6" />
        </filter>
      </defs>
      {/* halo */}
      <ellipse cx="200" cy="180" rx="180" ry="180" fill="url(#haloGrad)" />
      {/* shoulders / torso (back layer) */}
      <path d={`M 60 ${chestY + 60} Q 80 ${chestY + 10} 140 ${chestY - 20}
                Q 180 ${chestY - 40} 200 ${chestY - 40}
                Q 220 ${chestY - 40} 260 ${chestY - 20}
                Q 320 ${chestY + 10} 340 ${chestY + 60}
                L 340 460 L 60 460 Z`}
            fill="url(#shouldGrad)" />
      {/* neck */}
      <path d="M 168 250 Q 168 280 168 290 L 232 290 Q 232 280 232 250 Z"
            fill={skinHue} opacity="0.78" />
      {/* hair behind */}
      <path d="M 110 170 Q 90 80 200 60 Q 310 80 290 170 Q 280 220 270 240 Q 290 200 290 170 Q 290 90 200 90 Q 110 90 110 170 Q 110 200 130 240 Q 120 220 110 170 Z"
            fill="url(#hairGrad)" />
      {/* face */}
      <ellipse cx="200" cy="180" rx="78" ry="100" fill="url(#skinLight)" />
      {/* jawline subtle shadow */}
      <path d="M 130 200 Q 200 280 270 200 Q 240 230 200 240 Q 160 230 130 200 Z"
            fill="rgba(58,40,24,0.18)" filter="url(#softblur)" />
      {/* hair front strand */}
      <path d="M 132 130 Q 160 80 200 78 Q 200 100 180 115 Q 158 130 132 130 Z"
            fill="url(#hairGrad)" opacity="0.96" />
      <path d="M 268 130 Q 240 80 220 80 Q 230 120 268 130 Z"
            fill="url(#hairGrad)" opacity="0.8" />
      {/* eyebrows */}
      <path d={`M 152 ${158 + gaze.y * 2} Q 174 ${152 + gaze.y * 2} 186 ${160 + gaze.y * 2}`}
            stroke="#1a1108" strokeWidth="3.5" fill="none" strokeLinecap="round" opacity="0.85" />
      <path d={`M 214 ${160 + gaze.y * 2} Q 226 ${152 + gaze.y * 2} 248 ${158 + gaze.y * 2}`}
            stroke="#1a1108" strokeWidth="3.5" fill="none" strokeLinecap="round" opacity="0.85" />
      {/* eyes */}
      <g>
        <ellipse cx={170 + gaze.x * 2} cy="180" rx="9" ry={eyeOpen ? 6.5 - blinkPhase * 6 : 0.6}
                 fill="#fff" opacity="0.95" />
        <ellipse cx={171 + gaze.x * 5} cy={180 + gaze.y * 3} rx="4" ry={eyeOpen ? 4 - blinkPhase * 4 : 0.4}
                 fill="#3a2410" />
        <ellipse cx={172 + gaze.x * 5} cy={179 + gaze.y * 3} rx="1.4" ry={eyeOpen ? 1.4 - blinkPhase * 1.4 : 0.2}
                 fill="#fff" opacity="0.9" />
      </g>
      <g>
        <ellipse cx={230 + gaze.x * 2} cy="180" rx="9" ry={eyeOpen ? 6.5 - blinkPhase * 6 : 0.6}
                 fill="#fff" opacity="0.95" />
        <ellipse cx={231 + gaze.x * 5} cy={180 + gaze.y * 3} rx="4" ry={eyeOpen ? 4 - blinkPhase * 4 : 0.4}
                 fill="#3a2410" />
        <ellipse cx={232 + gaze.x * 5} cy={179 + gaze.y * 3} rx="1.4" ry={eyeOpen ? 1.4 - blinkPhase * 1.4 : 0.2}
                 fill="#fff" opacity="0.9" />
      </g>
      {/* nose */}
      <path d="M 200 195 Q 195 220 200 230 Q 207 232 210 228"
            stroke="rgba(58,40,24,0.45)" strokeWidth="1" fill="none" strokeLinecap="round" />
      {/* mouth */}
      <ellipse cx="200" cy={245}
               rx="14" ry={mouthH}
               fill="#5a2420" opacity="0.85" />
      {/* subtle highlight on lips */}
      <ellipse cx="200" cy={244} rx="10" ry={mouthH * 0.3}
               fill="#FF8B6E" opacity="0.4" />
    </svg>
  );
}

// ─── HUD readings around the face ───────────────────────────────────────────
function FaceHUD({ bpm, hrv, breathRate, gaze, microexpression, voiceTone, congruence, mode }) {
  return (
    <>
      {/* BPM at right temple */}
      <div style={{
        position: 'absolute', top: '23%', right: '14%',
        display: 'flex', alignItems: 'center', gap: 5,
        opacity: mode === 'observer' || mode === 'companion' || mode === 'mirror' ? 1 : 0,
        transition: 'opacity 0.4s ease',
      }}>
        <span style={{
          width: 7, height: 7, borderRadius: '50%', background: '#FF8B6E',
          boxShadow: '0 0 6px #FF8B6E',
          animation: `phantom-heartbeat ${60 / bpm}s ease-in-out infinite`,
        }} />
        <span className="mono" style={{
          fontSize: 9, color: 'var(--ink-strong)', letterSpacing: '0.06em',
          fontVariantNumeric: 'tabular-nums',
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          padding: '2px 6px', borderRadius: 999,
          border: '0.5px solid rgba(255,139,110,0.3)',
        }}>{bpm} bpm</span>
      </div>
      {/* HRV at left temple */}
      <div style={{
        position: 'absolute', top: '23%', left: '14%',
        opacity: mode !== 'oracle' ? 1 : 0,
        transition: 'opacity 0.4s ease',
      }}>
        <span className="mono" style={{
          fontSize: 9, color: 'var(--ink-strong)', letterSpacing: '0.06em',
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          padding: '2px 6px', borderRadius: 999,
          border: '0.5px solid rgba(244,175,37,0.25)',
        }}>hrv {hrv}</span>
      </div>
      {/* breath wave at chest */}
      <div style={{
        position: 'absolute', bottom: '24%', left: '50%',
        transform: 'translateX(-50%)',
        opacity: mode !== 'oracle' ? 0.8 : 0,
        transition: 'opacity 0.4s ease',
      }}>
        <svg viewBox="0 0 120 30" width="120" height="30">
          <path d={Array.from({ length: 60 }).map((_, i) => {
            const x = i * 2;
            const y = 15 + Math.sin(i * 0.4 + breathRate * Date.now() * 0.001) * 7;
            return `${i ? 'L' : 'M'} ${x} ${y}`;
          }).join(' ')}
                fill="none" stroke="#7CC0E8" strokeWidth="1"
                style={{ filter: 'drop-shadow(0 0 4px rgba(124,192,232,0.6))' }} />
        </svg>
      </div>
      {/* gaze indicator above forehead */}
      <div style={{
        position: 'absolute', top: '17%', left: '50%',
        transform: 'translateX(-50%)',
        opacity: mode === 'observer' || mode === 'mirror' ? 0.85 : 0,
        transition: 'opacity 0.4s ease',
      }}>
        <svg viewBox="0 0 24 24" width="22" height="22">
          <circle cx="12" cy="12" r="10" fill="none" stroke="rgba(244,175,37,0.25)" strokeWidth="0.5" />
          <circle cx={12 + gaze.x * 6} cy={12 + gaze.y * 6} r="2" fill="var(--gold)"
                  style={{ filter: 'drop-shadow(0 0 3px var(--gold-glow))' }} />
        </svg>
        <div className="mono" style={{
          fontSize: 7, color: 'rgba(244,175,37,0.65)', letterSpacing: '0.18em',
          textAlign: 'center', marginTop: 2, textTransform: 'uppercase',
        }}>· gaze</div>
      </div>
      {/* microexpression — fleeting text near mouth */}
      {microexpression && (
        <div key={microexpression} className="fade-in" style={{
          position: 'absolute', bottom: '36%', right: '8%',
          padding: '3px 8px', borderRadius: 999,
          background: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(8px)',
          border: '0.5px solid rgba(255,139,110,0.4)',
          opacity: mode === 'mirror' ? 1 : (mode === 'companion' ? 0.85 : 0),
          transition: 'opacity 0.4s ease',
        }}>
          <span className="mono" style={{
            fontSize: 8.5, color: '#FF8B6E', letterSpacing: '0.14em',
            textTransform: 'uppercase',
          }}>· {microexpression}</span>
        </div>
      )}
      {/* tone indicator */}
      <div style={{
        position: 'absolute', bottom: '36%', left: '8%',
        opacity: mode !== 'oracle' ? 0.85 : 0,
        transition: 'opacity 0.4s ease',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: voiceTone.c, letterSpacing: '0.14em',
          textTransform: 'uppercase',
          background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(8px)',
          padding: '3px 8px', borderRadius: 999,
          border: `0.5px solid ${voiceTone.c}55`,
        }}>· {voiceTone.l}</div>
      </div>
      {/* congruence ring around face — words ↔ body alignment */}
      <div style={{
        position: 'absolute', left: '50%', top: '32%',
        transform: 'translate(-50%, -50%)',
        width: 220, height: 220, borderRadius: '50%',
        border: `0.5px solid ${congruence > 0.7 ? '#9DCC9F' : congruence > 0.4 ? '#F4AF25' : '#FF8B6E'}`,
        opacity: 0.35,
        boxShadow: `0 0 24px ${congruence > 0.7 ? '#9DCC9F' : congruence > 0.4 ? '#F4AF25' : '#FF8B6E'}33`,
        pointerEvents: 'none',
        transition: 'border-color 1s ease, box-shadow 1s ease',
      }} />
    </>
  );
}

// ─── Phantom message — streaming glass card emerging from orb ───────────────
function PhantomLensMessage({ text, kind = 'speak', mode }) {
  const [shown, setShown] = React.useState(0);
  React.useEffect(() => {
    setShown(0);
    if (!text) return;
    let i = 0;
    const id = setInterval(() => {
      i += 2;
      setShown(i);
      if (i >= text.length) clearInterval(id);
    }, 28);
    return () => clearInterval(id);
  }, [text]);
  if (!text) return null;
  const tone = kind === 'observe' ? '#7CC0E8' : kind === 'question' ? '#B49AD4' : 'var(--gold-soft)';
  return (
    <div className="fade-in" style={{
      position: 'absolute', right: 14, top: '50%',
      transform: 'translateY(-50%)',
      maxWidth: 240, padding: '10px 14px', borderRadius: '18px 18px 4px 18px',
      background: `radial-gradient(ellipse at 100% 0%, ${tone}22, transparent 70%), rgba(20,14,10,0.88)`,
      backdropFilter: 'blur(20px)',
      border: `0.5px solid ${tone}44`,
      boxShadow: `0 0 18px ${tone}33`,
      zIndex: 5,
    }}>
      <div className="mono" style={{
        fontSize: 8, color: tone, letterSpacing: '0.22em',
        textTransform: 'uppercase', marginBottom: 6,
      }}>· phantom · {mode}</div>
      <div style={{
        font: '300 13px/1.55 Georgia, serif', color: 'var(--ink-strong)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        «{text.slice(0, shown)}
        {shown < text.length && (
          <span style={{
            display: 'inline-block', width: 6, height: 12, marginLeft: 2,
            background: tone, verticalAlign: 'middle',
            animation: 'phantom-caret 0.8s steps(2, end) infinite',
          }} />
        )}»
      </div>
    </div>
  );
}

// ─── TranscriptStream — sentiment-coded scrolling transcript ────────────────
function TranscriptStream({ items }) {
  return (
    <div style={{
      position: 'absolute', left: 14, bottom: 96, right: 14,
      maxHeight: 100, overflow: 'hidden', pointerEvents: 'none',
      display: 'flex', flexDirection: 'column', gap: 3,
      maskImage: 'linear-gradient(to top, #000 0%, #000 60%, transparent 100%)',
    }}>
      {items.slice(-4).map((t, i) => {
        const sentC = { calm: '#9DCC9F', anxious: '#FF8B6E', neutral: 'var(--ink-secondary)', joy: '#FFD370', heavy: '#7E5DA4' };
        return (
          <div key={items.length - 4 + i} style={{
            display: 'flex', alignItems: 'baseline', gap: 6,
            opacity: 0.4 + i * 0.18,
          }}>
            <span className="mono" style={{
              fontSize: 7, color: t.who === 'me' ? 'var(--ink-tertiary)' : 'var(--gold-soft)',
              letterSpacing: '0.18em', textTransform: 'uppercase',
              flexShrink: 0,
            }}>· {t.who === 'me' ? 'ти' : 'phantom'}</span>
            <span style={{
              font: '300 11px/1.5 Georgia, serif', fontStyle: 'italic',
              color: sentC[t.sent] || 'var(--ink-strong)',
              textWrap: 'pretty', flex: 1, minWidth: 0,
            }}>{t.text}</span>
          </div>
        );
      })}
    </div>
  );
}

// ─── ModePicker — 4 modes pill bar ──────────────────────────────────────────
function ModePicker({ mode, onChange }) {
  const modes = [
    { k: 'observer',  l: 'observer',  d: 'я лише дивлюсь',         c: '#7CC0E8' },
    { k: 'companion', l: 'companion', d: 'розмовляємо',             c: '#F4AF25' },
    { k: 'mirror',    l: 'mirror',    d: 'я кажу що бачу',          c: '#9DCC9F' },
    { k: 'oracle',    l: 'oracle',    d: 'питаю · ти відповідаєш',   c: '#B49AD4' },
  ];
  return (
    <div style={{
      display: 'flex', gap: 4, padding: 4, borderRadius: 999,
      background: 'rgba(20,14,10,0.7)', backdropFilter: 'blur(12px)',
      border: '0.5px solid var(--glass-edge-strong)',
    }}>
      {modes.map((m) => (
        <button key={m.k} onClick={() => onChange(m.k)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          padding: '6px 10px', borderRadius: 999,
          background: mode === m.k ? `${m.c}28` : 'transparent',
          color: mode === m.k ? m.c : 'var(--ink-secondary)',
          font: '500 10.5px/1 var(--font-display)', letterSpacing: '0.04em',
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
          minWidth: 56,
        }}>
          <span>{m.l}</span>
          <span className="mono" style={{
            fontSize: 7, color: mode === m.k ? m.c : 'var(--ink-tertiary)',
            letterSpacing: '0.08em', opacity: mode === m.k ? 0.85 : 0.6,
          }}>{m.d}</span>
        </button>
      ))}
    </div>
  );
}

// ─── ARTag — phantom tags objects in your room ──────────────────────────────
function ARTag({ x, y, label, sub, dot = false, c = '#F4AF25' }) {
  return (
    <div style={{
      position: 'absolute', left: x, top: y,
      pointerEvents: 'none',
    }}>
      {dot && (
        <div style={{
          width: 8, height: 8, borderRadius: '50%',
          background: c, boxShadow: `0 0 6px ${c}`,
          marginBottom: 4, animation: 'phantom-pulse-glow 1.8s ease-in-out infinite',
        }} />
      )}
      <div style={{
        padding: '3px 7px', borderRadius: 6,
        background: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(8px)',
        border: `0.5px solid ${c}55`,
      }}>
        <div className="mono" style={{
          fontSize: 8, color: c, letterSpacing: '0.14em',
          textTransform: 'uppercase',
        }}>{label}</div>
        {sub && (
          <div className="mono" style={{
            fontSize: 7, color: 'rgba(255,250,220,0.6)',
            letterSpacing: '0.06em', marginTop: 2,
          }}>{sub}</div>
        )}
      </div>
    </div>
  );
}

// ─── LensScene — full immersive video conversation ──────────────────────────
function LensScene({ onClose }) {
  const [mode, setMode] = React.useState('companion');
  const [breath, setBreath] = React.useState(0);
  const [speaking, setSpeaking] = React.useState(0);
  const [blink, setBlink] = React.useState(0);
  const [gaze, setGaze] = React.useState({ x: 0, y: 0 });
  const [microexpression, setMicro] = React.useState(null);
  const [phantomMsg, setPhantomMsg] = React.useState(null);
  const [transcript, setTranscript] = React.useState([]);
  const [muted, setMuted] = React.useState(false);
  const [showAR, setShowAR] = React.useState(true);
  const [bpm, setBpm] = React.useState(64);
  const [hrv, setHrv] = React.useState(48);
  const [congruence, setCongruence] = React.useState(0.78);

  // breath animation
  React.useEffect(() => {
    let raf, t = 0;
    const tick = () => {
      t += 0.022;
      setBreath((Math.sin(t * 0.5) + 1) / 2);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  // periodic blink
  React.useEffect(() => {
    const trigger = () => {
      const dur = 140;
      const start = performance.now();
      const tick = () => {
        const e = performance.now() - start;
        const p = e / dur;
        if (p > 1) { setBlink(0); return; }
        setBlink(p < 0.5 ? p * 2 : 2 - p * 2);
        requestAnimationFrame(tick);
      };
      tick();
    };
    const id = setInterval(trigger, 3200 + Math.random() * 2400);
    return () => clearInterval(id);
  }, []);

  // gaze drift
  React.useEffect(() => {
    let raf, t = 0;
    const tick = () => {
      t += 0.005;
      setGaze({
        x: Math.sin(t * 0.7) * 0.6 + Math.sin(t * 2.3) * 0.15,
        y: Math.sin(t * 0.5) * 0.3,
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  // micro vitals drift
  React.useEffect(() => {
    const id = setInterval(() => {
      setBpm((b) => Math.round(62 + Math.sin(Date.now() / 6000) * 6 + (Math.random() - 0.5) * 2));
      setHrv((h) => Math.round(46 + Math.sin(Date.now() / 8000) * 6));
    }, 1200);
    return () => clearInterval(id);
  }, []);

  // mode-specific phantom narration & microexpression
  React.useEffect(() => {
    const scripts = {
      observer: [
        { t: 'я слухаю · нічого не пишу',           micro: null,          sent: 'calm' },
        { t: 'ти двічі моргнув коли я сказав «оля»', micro: 'тривога',     sent: 'anxious' },
        { t: 'твої плечі нижче ніж минулого разу',   micro: 'розслаблено', sent: 'calm' },
      ],
      companion: [
        { t: 'добре що ти прокинувся сьогодні рано', micro: 'усмішка',    sent: 'joy',     who: 'phantom' },
        { t: 'як ти насправді?',                      micro: 'пауза',      sent: 'neutral', who: 'phantom', kind: 'question' },
        { t: 'я ж бачу що ти трохи стиснутий',         micro: 'захист',     sent: 'heavy',   who: 'phantom' },
      ],
      mirror: [
        { t: 'твоє обличчя зараз — це обличчя людини яка приховує що хвилюється', micro: 'захист', sent: 'heavy', who: 'phantom', kind: 'observe' },
        { t: 'ти дивишся вниз і праворуч коли говориш про роботу',                   micro: 'уникання', sent: 'anxious', who: 'phantom', kind: 'observe' },
        { t: 'твоя посмішка зʼявляється на 80мс раніше слів — ти задоволений',        micro: 'спокій', sent: 'calm', who: 'phantom', kind: 'observe' },
      ],
      oracle: [
        { t: 'якщо ти не побачиш олю до кінця тижня — що зміниться у тобі?',           micro: null,       sent: 'heavy',   who: 'phantom', kind: 'question' },
        { t: 'чого ти боїшся більше: що вона залишиться, чи що піде?',                  micro: 'роздум',   sent: 'heavy',   who: 'phantom', kind: 'question' },
      ],
    };
    const lines = scripts[mode];
    let idx = 0;
    const fire = () => {
      const ln = lines[idx % lines.length];
      setPhantomMsg({ text: ln.t, kind: ln.kind || 'speak' });
      setMicro(ln.micro);
      setTranscript((t) => [...t, { who: 'phantom', text: ln.t, sent: ln.sent }].slice(-12));
      // simulate you speaking back
      setTimeout(() => {
        setSpeaking(1);
        const youLine = ['ну так', 'м-м-м', '…', 'не знаю', 'мабуть так', 'дякую'][Math.floor(Math.random() * 6)];
        setTranscript((t) => [...t, { who: 'me', text: youLine, sent: 'neutral' }].slice(-12));
        setTimeout(() => setSpeaking(0), 1100);
      }, ln.t.length * 30 + 600);
      idx++;
    };
    fire();
    const id = setInterval(fire, 6500);
    return () => clearInterval(id);
  }, [mode]);

  // voice tone reading
  const voiceTone = speaking > 0.5
    ? { l: 'твій голос · мʼякий', c: '#9DCC9F' }
    : { l: 'мовчиш · дихання рівне', c: '#7CC0E8' };

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 100,
      display: 'flex', flexDirection: 'column',
      background: `radial-gradient(ellipse at 50% 30%, #1a1108 0%, #0a0805 60%, #050402 100%)`,
      overflow: 'hidden',
      animation: 'phantom-fade-in 0.4s ease-out',
    }}>
      {/* atmospheric particles */}
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background:
          'radial-gradient(ellipse 80% 60% at 30% 40%, rgba(244,175,37,0.06), transparent 70%),' +
          'radial-gradient(ellipse 60% 50% at 70% 70%, rgba(126,93,164,0.04), transparent 70%)',
      }} />
      <WandererSparks count={8} />

      {/* status bar */}
      <StatusBar />

      {/* top bar */}
      <div className="ui-chrome" style={{
        padding: '6px 14px',
        display: 'flex', alignItems: 'center', gap: 10,
        position: 'relative', zIndex: 10,
      }}>
        <button onClick={onClose} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.04)', color: 'var(--ink-secondary)',
          font: '400 11px/1 var(--font-mono)', padding: '6px 10px',
          borderRadius: 999, letterSpacing: '0.04em',
        }}>←</button>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 5,
          padding: '4px 9px', borderRadius: 999,
          background: 'rgba(244,175,37,0.08)',
          border: '0.5px solid rgba(244,175,37,0.25)',
        }}>
          <span style={{
            width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
            animation: 'phantom-pulse-glow 1.6s ease-in-out infinite',
          }} />
          <span className="mono" style={{
            fontSize: 9, color: 'var(--gold)', letterSpacing: '0.16em',
            textTransform: 'uppercase',
          }}>· lens · live</span>
        </div>
        <div style={{ flex: 1 }} />
        <span className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em',
        }}>· 02:14 · on-device</span>
      </div>

      {/* main portrait stage */}
      <div style={{
        flex: 1, position: 'relative', minHeight: 0,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <div style={{ width: '90%', height: '100%', position: 'relative' }}>
          <YourPortrait breath={breath} speaking={speaking}
                        blinkPhase={blink} gaze={gaze}
                        skinHue="#E8B886" />
          <FaceHUD bpm={bpm} hrv={hrv} breathRate={0.4}
                   gaze={gaze} microexpression={microexpression}
                   voiceTone={voiceTone}
                   congruence={congruence} mode={mode} />
          {showAR && mode !== 'oracle' && (
            <>
              <ARTag x="3%" top="14%" y="58%" label="вікно · 14°c" sub="вечір · сонце впало" c="#7CC0E8" />
              <ARTag x="78%" y="68%" label="кава · 23 хв" sub="ще тепла" c="#F4AF25" dot />
              <ARTag x="68%" y="6%" label="лампа · 2700K" sub="теплий світло" c="#FFD370" />
            </>
          )}
        </div>

        {/* phantom orb floating top-right */}
        <div style={{
          position: 'absolute', top: '8%', right: '8%',
          width: mode === 'companion' ? 64 : 38, height: mode === 'companion' ? 64 : 38,
          borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
          border: '0.5px solid rgba(255,230,180,0.2)',
          boxShadow: `0 0 ${24 + speaking * 16}px var(--gold-glow)`,
          animation: 'phantom-breathe 5s ease-in-out infinite',
          transition: 'all 0.4s ease',
        }} />

        {/* phantom message */}
        {phantomMsg && (
          <PhantomLensMessage text={phantomMsg.text} kind={phantomMsg.kind} mode={mode} />
        )}
      </div>

      {/* transcript stream */}
      <TranscriptStream items={transcript} />

      {/* bottom controls */}
      <div className="ui-chrome" style={{
        padding: '12px 14px 18px',
        position: 'relative', zIndex: 10,
        display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'center',
      }}>
        <ModePicker mode={mode} onChange={setMode} />
        <div style={{
          display: 'flex', gap: 12, alignItems: 'center',
        }}>
          <button onClick={() => setMuted((m) => !m)} style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 44, height: 44, borderRadius: '50%',
            background: muted ? 'rgba(255,139,110,0.20)' : 'rgba(255,230,180,0.04)',
            color: muted ? 'var(--coral)' : 'var(--ink-strong)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: `0.5px solid ${muted ? 'rgba(255,139,110,0.35)' : 'var(--glass-edge)'}`,
          }}>
            <svg width="16" height="16" viewBox="0 0 16 16">
              <rect x="6" y="2" width="4" height="8" rx="2" fill="currentColor" />
              <path d="M3 8a5 5 0 0010 0M8 13v2" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round" />
              {muted && <line x1="2" y1="2" x2="14" y2="14" stroke="currentColor" strokeWidth="1.2" />}
            </svg>
          </button>
          <button onClick={() => setShowAR((a) => !a)} style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 44, height: 44, borderRadius: '50%',
            background: showAR ? 'rgba(244,175,37,0.20)' : 'rgba(255,230,180,0.04)',
            color: showAR ? 'var(--gold)' : 'var(--ink-strong)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: `0.5px solid ${showAR ? 'rgba(244,175,37,0.4)' : 'var(--glass-edge)'}`,
          }}>
            <svg width="16" height="16" viewBox="0 0 16 16">
              <rect x="2" y="3" width="12" height="10" rx="1" fill="none" stroke="currentColor" strokeWidth="0.8" />
              <circle cx="8" cy="8" r="2.5" fill="none" stroke="currentColor" strokeWidth="0.8" />
              <circle cx="11.5" cy="5" r="0.5" fill="currentColor" />
            </svg>
          </button>
          <button onClick={onClose} style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 56, height: 56, borderRadius: '50%',
            background: 'var(--coral)', color: '#0E0A05',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '500 18px/1 var(--font-display)',
            boxShadow: '0 0 16px rgba(255,139,110,0.5)',
          }}>×</button>
          <button style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 44, height: 44, borderRadius: '50%',
            background: 'rgba(255,230,180,0.04)', color: 'var(--ink-strong)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <svg width="16" height="16" viewBox="0 0 16 16">
              <path d="M3 5h10v8H3z M3 5l3 -2h4l3 2" stroke="currentColor" strokeWidth="0.8" fill="none" />
              <path d="M11 7 L6 10 L11 13" stroke="currentColor" strokeWidth="0.8" fill="none" />
            </svg>
          </button>
          <button style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 44, height: 44, borderRadius: '50%',
            background: 'rgba(126,93,164,0.20)', color: '#B49AD4',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '0.5px solid rgba(126,93,164,0.4)',
          }}>
            <svg width="16" height="16" viewBox="0 0 16 16">
              <circle cx="8" cy="8" r="2" fill="currentColor" />
              <circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" strokeWidth="0.8" />
              <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="0.5" opacity="0.4" />
            </svg>
          </button>
        </div>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase',
        }}>· mute · ar · ✕ · recall · oracle</div>
      </div>
    </div>
  );
}

// keyframes — heartbeat
if (typeof document !== 'undefined' && !document.getElementById('lens-style')) {
  const s = document.createElement('style');
  s.id = 'lens-style';
  s.textContent = `
    @keyframes phantom-heartbeat { 0%, 35%, 100% { transform: scale(1); } 10% { transform: scale(1.5); } 20% { transform: scale(1.1); } }
  `;
  document.head.appendChild(s);
}

Object.assign(window, { LensScene, YourPortrait, FaceHUD, ModePicker, PhantomLensMessage, TranscriptStream });
