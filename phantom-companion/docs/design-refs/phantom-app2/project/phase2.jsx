/* phase2.jsx — Phase 2 expansions:
   • Chat-rich: ThreadRail, ChartWidget, FileCard, CodeBlock,
     VoiceMemo, CitationPill, ToolTrace
   • Presence variants: WhisperLineScene, MarginGlowScene,
     SubliminalPulseScene, FamilyHiveScene
   • SurfaceTray (long-press tray)
*/

// ─── Thread rail (top horizontal pill bar) ───────────────────────────────────
function ThreadRail({ active, onChange }) {
  const threads = [
    { id: 'now',      label: 'Зараз',   count: null },
    { id: 'olya',     label: 'Olya',    count: 2 },
    { id: 'family',   label: 'Family',  count: null },
    { id: 'whispers', label: 'Whispers', count: 5 },
    { id: 'vault',    label: 'Vault',   count: null, lock: true },
  ];
  return (
    <div className="ui-chrome" style={{
      display: 'flex', gap: 6, padding: '4px 14px 8px',
      overflow: 'auto', position: 'relative', zIndex: 8,
      scrollbarWidth: 'none',
    }}>
      {threads.map((th) => {
        const on = active === th.id;
        return (
          <button key={th.id} onClick={() => onChange(th.id)}
                  style={{
                    appearance: 'none', flexShrink: 0, cursor: 'pointer',
                    padding: '6px 12px', borderRadius: 999,
                    background: on ? 'rgba(244,175,37,0.12)' : 'transparent',
                    border: `0.5px solid ${on ? 'var(--gold-soft)' : 'var(--glass-edge)'}`,
                    color: on ? 'var(--gold)' : 'var(--ink-secondary)',
                    font: '500 11px/1 var(--font-display)',
                    letterSpacing: '0.02em',
                    display: 'flex', alignItems: 'center', gap: 5,
                    boxShadow: on ? '0 0 12px rgba(244,175,37,0.2)' : 'none',
                    transition: 'all 0.2s',
                  }}>
            {th.lock && (
              <svg width="9" height="10" viewBox="0 0 9 10" fill="none">
                <rect x="1.5" y="4.5" width="6" height="5" rx="1" stroke="currentColor" strokeWidth="0.8" />
                <path d="M3 4.5V3a1.5 1.5 0 013 0v1.5" stroke="currentColor" strokeWidth="0.8" />
              </svg>
            )}
            {th.label}
            {th.count != null && (
              <span style={{
                font: '300 9px/1 var(--font-mono)',
                color: on ? 'var(--gold-soft)' : 'var(--ink-tertiary)',
                background: on ? 'rgba(244,175,37,0.15)' : 'rgba(255,230,180,0.05)',
                padding: '2px 5px', borderRadius: 999,
              }}>{th.count}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ─── Chart widget ────────────────────────────────────────────────────────────
function ChartWidget({ title, kind = 'line', data, annotations = [] }) {
  const W = 320, H = 110, P = 8;
  const ys = data.map((d) => d.y);
  const yMin = Math.min(...ys), yMax = Math.max(...ys);
  const yR = yMax - yMin || 1;
  const xs = (i) => P + (i / (data.length - 1)) * (W - P * 2);
  const yp = (y) => H - P - ((y - yMin) / yR) * (H - P * 2);
  return (
    <div className="glass" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
        textTransform: 'uppercase', marginBottom: 8,
      }}>· графік · {kind}</div>
      <div style={{
        font: '500 13px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 8,
      }}>{title}</div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 110 }}>
        <defs>
          <linearGradient id="cgr" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--gold)" stopOpacity="0.28" />
            <stop offset="100%" stopColor="var(--gold)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {/* baseline */}
        <line x1={P} y1={H - P} x2={W - P} y2={H - P} stroke="var(--glass-edge)" strokeWidth="0.5" />
        {kind === 'bar' ? (
          data.map((d, i) => {
            const bx = xs(i) - 8;
            const by = yp(d.y);
            return <rect key={i} x={bx} y={by} width="14" height={H - P - by}
                         rx="2" fill="var(--gold)" opacity={0.7} />;
          })
        ) : (
          <>
            <path d={data.map((d, i) => `${i === 0 ? 'M' : 'L'} ${xs(i)} ${yp(d.y)}`).join(' ') + ` L ${W - P} ${H - P} L ${P} ${H - P} Z`}
                  fill="url(#cgr)" />
            <path d={data.map((d, i) => `${i === 0 ? 'M' : 'L'} ${xs(i)} ${yp(d.y)}`).join(' ')}
                  fill="none" stroke="var(--gold)" strokeWidth="1.5"
                  style={{ filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
            {data.map((d, i) => (
              <circle key={i} cx={xs(i)} cy={yp(d.y)} r="1.8" fill="var(--gold)" />
            ))}
          </>
        )}
        {annotations.map((a, i) => {
          const ix = data.findIndex((d) => d.x === a.x);
          if (ix < 0) return null;
          return (
            <g key={i}>
              <circle cx={xs(ix)} cy={yp(data[ix].y)} r="4" fill="none" stroke="var(--coral)" strokeWidth="1" />
              <text x={xs(ix)} y={yp(data[ix].y) - 8} fill="var(--coral)"
                    fontSize="8" fontFamily="var(--font-mono)" textAnchor="middle">{a.label}</text>
            </g>
          );
        })}
      </svg>
      <div style={{
        display: 'flex', justifyContent: 'space-between',
        font: '300 9px/1 var(--font-mono)', color: 'var(--ink-tertiary)',
        marginTop: 4, letterSpacing: '0.04em',
      }}>
        <span>{data[0]?.x}</span>
        <span>{data[data.length - 1]?.x}</span>
      </div>
    </div>
  );
}

// ─── File card ───────────────────────────────────────────────────────────────
function FileCard({ kind = 'photo', title, meta, extracted }) {
  return (
    <div className="glass" style={{
      padding: 10, margin: '6px 0', borderRadius: 16,
      display: 'flex', gap: 12, alignItems: 'flex-start',
    }}>
      <FileThumb kind={kind} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.12em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· {kind === 'photo' ? 'фото' : kind === 'pdf' ? 'pdf' : 'файл'} · ai-розпізнано</div>
        <div style={{
          font: '500 12.5px/1.35 var(--font-display)', color: 'var(--ink-strong)',
          marginBottom: 3, textWrap: 'pretty',
        }}>{title}</div>
        {meta && (
          <div style={{
            font: '400 10.5px/1.4 var(--font-mono)', color: 'var(--ink-secondary)',
            marginBottom: 6, letterSpacing: '0.02em',
          }}>{meta}</div>
        )}
        {extracted && (
          <div style={{
            font: '400 11px/1.4 var(--font-body)', color: 'var(--gold-soft)',
            background: 'rgba(244,175,37,0.06)',
            padding: '6px 8px', borderRadius: 8,
            border: '0.5px solid rgba(244,175,37,0.15)',
            textWrap: 'pretty',
          }}>{extracted}</div>
        )}
      </div>
    </div>
  );
}
function FileThumb({ kind }) {
  if (kind === 'pdf') {
    return (
      <div style={{
        width: 56, height: 70, borderRadius: 6,
        background: 'linear-gradient(160deg, #2a1f15, #1a1208)',
        border: '0.5px solid var(--glass-edge-strong)',
        padding: 6, display: 'flex', flexDirection: 'column', gap: 3,
      }}>
        {[0.9, 0.7, 0.85, 0.55, 0.7].map((w, i) => (
          <div key={i} style={{ height: 2, width: `${w * 100}%`, background: 'var(--ink-tertiary)', opacity: 0.5 }} />
        ))}
        <div style={{
          marginTop: 'auto', font: '500 7px/1 var(--font-mono)',
          color: 'var(--coral)', letterSpacing: '0.1em',
        }}>PDF</div>
      </div>
    );
  }
  // photo — fake receipt thumb
  return (
    <div style={{
      width: 56, height: 70, borderRadius: 6,
      background: 'linear-gradient(170deg, #f5e8d3 0%, #e8d4b0 100%)',
      border: '0.5px solid rgba(255,230,180,0.2)',
      padding: 6, display: 'flex', flexDirection: 'column', gap: 2,
      overflow: 'hidden', position: 'relative',
    }}>
      <div style={{ height: 1.5, width: '60%', background: '#3a2818', borderRadius: 1 }} />
      <div style={{ height: 1, width: '85%', background: '#5a4838', opacity: 0.7 }} />
      <div style={{ height: 1, width: '70%', background: '#5a4838', opacity: 0.7 }} />
      <div style={{ height: 1, width: '90%', background: '#5a4838', opacity: 0.7 }} />
      <div style={{ height: 1, width: '50%', background: '#5a4838', opacity: 0.7 }} />
      <div style={{ marginTop: 'auto', height: 1.5, width: '40%', background: '#3a2818', borderRadius: 1, alignSelf: 'flex-end' }} />
    </div>
  );
}

// ─── Code block ──────────────────────────────────────────────────────────────
function CodeBlock({ lang = 'kotlin', code, onRun }) {
  // very lightweight tokenization for visual flavor
  const lines = code.split('\n');
  const tokenize = (line) =>
    line.split(/(\s+|[(){}\[\];,]|".*?")/g).filter(Boolean).map((tok, i) => {
      let color = 'var(--ink)';
      if (/^(fun|val|var|class|object|return|if|else|when|import|package|private|public|override|suspend|data)$/.test(tok)) color = '#C39B5C';
      else if (/^".*"$/.test(tok)) color = '#9DCC9F';
      else if (/^[A-Z][A-Za-z0-9_]*$/.test(tok)) color = '#E8B770';
      else if (/^\/\/.*/.test(tok)) color = 'var(--ink-tertiary)';
      else if (/^[(){}\[\];,]/.test(tok)) color = 'var(--ink-tertiary)';
      return <span key={i} style={{ color }}>{tok}</span>;
    });
  return (
    <div className="glass" style={{
      padding: 0, margin: '6px 0', borderRadius: 14, overflow: 'hidden',
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '8px 12px',
        borderBottom: '0.5px solid var(--glass-edge)',
      }}>
        <span className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.12em',
        }}>· {lang}</span>
        <button onClick={onRun} style={{
          appearance: 'none', cursor: 'pointer',
          background: 'rgba(244,175,37,0.1)',
          border: '0.5px solid var(--gold-soft)',
          color: 'var(--gold)',
          font: '400 9.5px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: '4px 8px', borderRadius: 999,
        }}>▷ run · sandbox</button>
      </div>
      <pre style={{
        margin: 0, padding: '10px 12px',
        font: '400 10.5px/1.55 var(--font-mono)',
        color: 'var(--ink)',
        background: 'rgba(0,0,0,0.18)',
        overflowX: 'auto', whiteSpace: 'pre',
      }}>
        {lines.map((line, li) => (
          <div key={li} style={{ display: 'flex', gap: 12 }}>
            <span style={{ color: 'var(--ink-faint)', userSelect: 'none', flexShrink: 0, width: 14, textAlign: 'right' }}>{li + 1}</span>
            <code style={{ flex: 1 }}>{tokenize(line)}</code>
          </div>
        ))}
      </pre>
    </div>
  );
}

// ─── Voice memo ──────────────────────────────────────────────────────────────
function VoiceMemo({ duration = 23, transcript }) {
  const [playing, setPlaying] = React.useState(false);
  const [pos, setPos] = React.useState(0);
  React.useEffect(() => {
    if (!playing) return;
    let raf;
    let last = performance.now();
    const tick = (t) => {
      const dt = (t - last) / 1000;
      last = t;
      setPos((p) => {
        const n = p + dt / duration;
        if (n >= 1) { setPlaying(false); return 0; }
        return n;
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, duration]);

  const bars = React.useMemo(() => Array.from({ length: 48 }, (_, i) => {
    const t = i / 48;
    return 0.2 + Math.abs(Math.sin(t * 13)) * 0.5 + Math.abs(Math.sin(t * 7.3)) * 0.3;
  }), []);

  return (
    <div className="glass" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
        textTransform: 'uppercase', marginBottom: 8,
      }}>· голосове · {duration}с</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <button onClick={() => setPlaying((p) => !p)} style={{
          width: 32, height: 32, borderRadius: '50%',
          appearance: 'none', cursor: 'pointer', flexShrink: 0,
          background: 'var(--gold)', border: 'none',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#1A140B', boxShadow: '0 0 10px var(--gold-glow)',
        }}>
          {playing ? (
            <svg width="10" height="10" viewBox="0 0 10 10"><rect x="2" y="1" width="2" height="8" fill="currentColor" /><rect x="6" y="1" width="2" height="8" fill="currentColor" /></svg>
          ) : (
            <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 1 L8 5 L2 9 Z" fill="currentColor" /></svg>
          )}
        </button>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 1.5, height: 28 }}>
          {bars.map((b, i) => {
            const filled = i / bars.length < pos;
            return (
              <div key={i} style={{
                width: 2, height: `${b * 100}%`,
                borderRadius: 1,
                background: filled ? 'var(--gold)' : 'var(--ink-tertiary)',
                opacity: filled ? 1 : 0.45,
                transition: 'background 0.1s',
              }} />
            );
          })}
        </div>
      </div>
      {transcript && (
        <div style={{
          marginTop: 8, padding: '6px 8px', borderRadius: 8,
          background: 'rgba(255,230,180,0.04)',
          font: '400 11px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
          fontStyle: 'italic', textWrap: 'pretty',
        }}>«{transcript}»</div>
      )}
    </div>
  );
}

// ─── Citation pill ───────────────────────────────────────────────────────────
function CitationPill({ source, date }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      padding: '2px 8px', marginLeft: 6, marginRight: 2,
      borderRadius: 999,
      background: 'rgba(244,175,37,0.08)',
      border: '0.5px solid rgba(244,175,37,0.2)',
      font: '400 10px/1.3 var(--font-mono)', color: 'var(--gold)',
      letterSpacing: '0.02em', cursor: 'pointer', verticalAlign: 'baseline',
    }}>
      <svg width="7" height="7" viewBox="0 0 7 7" fill="none">
        <circle cx="3.5" cy="3.5" r="2.5" stroke="currentColor" strokeWidth="0.8" />
      </svg>
      {source} · {date}
    </span>
  );
}

// ─── Tool-use trace ──────────────────────────────────────────────────────────
function ToolTrace({ tool, query, ms, results }) {
  const [open, setOpen] = React.useState(false);
  return (
    <button onClick={() => setOpen((o) => !o)} style={{
      appearance: 'none', cursor: 'pointer',
      width: '100%', textAlign: 'left',
      background: 'rgba(255,230,180,0.02)',
      border: '0.5px solid var(--glass-edge)',
      borderRadius: 10, padding: '6px 10px',
      margin: '4px 0',
      display: 'flex', flexDirection: 'column', gap: 4,
      font: '400 10px/1.3 var(--font-mono)',
      color: 'var(--ink-tertiary)',
      letterSpacing: '0.04em',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <span>· phantom шукав у <span style={{ color: 'var(--gold-soft)' }}>{tool}</span></span>
        <span>{ms}ms · {results} результатів {open ? '∧' : '∨'}</span>
      </div>
      {open && (
        <div style={{ paddingLeft: 8, marginTop: 4, color: 'var(--ink-secondary)' }}>
          q: «{query}»<br/>
          tool: {tool}<br/>
          latency: {ms}ms<br/>
          hits: {results}
        </div>
      )}
    </button>
  );
}

// ─── Whisper Line standalone scene ───────────────────────────────────────────
function WhisperLineScene({ onClose, soundOn }) {
  const messages = [
    'олег чекає твою відповідь · 23 хв',
    'дощ за 30 хв · парасоля в передпокої',
    'олег чекає твою відповідь · 23 хв',
    'кави не було 3 дні · local roasters · 4 хв пішки',
  ];
  const text = messages.join('   ···   ') + '   ···   ';

  return (
    <div style={{
      position: 'absolute', inset: 0, overflow: 'hidden',
      display: 'flex', flexDirection: 'column',
      background: '#fff',
    }}>
      {/* fake other-app behind — WhatsApp-like generic chat */}
      <FakeOtherApp />

      {/* Whisper Line — 1.5px gold-faint at top */}
      <div style={{
        position: 'absolute', top: 44, left: 0, right: 0,
        height: 2, overflow: 'hidden', zIndex: 200,
        background: 'rgba(0,0,0,0.06)',
      }}>
        <div style={{
          position: 'absolute', top: 0, height: '100%',
          whiteSpace: 'nowrap',
          font: '300 8px/2 var(--font-mono)',
          color: 'rgba(244,175,37,0.85)',
          letterSpacing: '0.18em',
          animation: 'phantom-whisper-drift 38s linear infinite',
          textShadow: '0 0 6px rgba(244,175,37,0.5)',
        }}>{text}{text}{text}</div>
      </div>

      {/* fake screen-recorder badge — proves invisibility */}
      <div style={{
        position: 'absolute', top: 14, right: 80, zIndex: 201,
        display: 'flex', alignItems: 'center', gap: 4,
        padding: '3px 7px', borderRadius: 999,
        background: 'rgba(231,111,81,0.18)',
        border: '0.5px solid rgba(231,111,81,0.4)',
      }}>
        <span style={{
          width: 5, height: 5, borderRadius: '50%', background: '#E76F51',
          boxShadow: '0 0 4px #E76F51',
          animation: 'phantom-pulse-glow 1s ease-in-out infinite',
        }} />
        <span className="mono" style={{
          fontSize: 8, color: '#E76F51', letterSpacing: '0.06em',
        }}>REC</span>
      </div>

      {/* explanatory glass card overlay */}
      <div className="glass hero accent" style={{
        position: 'absolute', left: 18, right: 18, bottom: 24,
        padding: '14px 16px', borderRadius: 18, zIndex: 300,
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· whisper line · ambient</div>
        <div style={{
          font: '500 12.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
          textWrap: 'pretty', marginBottom: 6,
        }}>1.5px стрічка поверх будь-якої апки</div>
        <div style={{
          font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
          textWrap: 'pretty',
        }}>FLAG_SECURE — screen recorder бачить порожньо. бачиш тільки ти.</div>
      </div>

      <button onClick={onClose} style={{
        position: 'absolute', top: 14, left: 14, zIndex: 400,
        appearance: 'none', border: '0.5px solid rgba(0,0,0,0.15)',
        background: 'rgba(255,255,255,0.85)',
        backdropFilter: 'blur(8px)', cursor: 'pointer',
        font: '400 11px/1 var(--font-body)', color: '#222',
        padding: '7px 12px', borderRadius: 999,
      }}>← повернутись</button>
    </div>
  );
}
function FakeOtherApp() {
  // Generic fake chat app — light, NOT branded WhatsApp
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: '#ECE5DD', position: 'relative' }}>
      <div style={{
        height: 80, padding: '34px 14px 0',
        background: '#075E54', color: '#fff',
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#0e7d6f' }} />
        <div>
          <div style={{ font: '500 13px/1.1 system-ui', color: '#fff' }}>робочий чат</div>
          <div style={{ font: '300 10px/1.1 system-ui', color: 'rgba(255,255,255,0.7)' }}>online</div>
        </div>
      </div>
      <div style={{ flex: 1, padding: '12px', display: 'flex', flexDirection: 'column', gap: 6, overflow: 'hidden' }}>
        {[
          { side: 'l', text: 'привіт, де ти?', t: '14:02' },
          { side: 'r', text: 'на каві', t: '14:05' },
          { side: 'l', text: 'передзвониш?', t: '14:08' },
          { side: 'l', text: 'я думав ми о другій?', t: '14:09' },
          { side: 'r', text: 'так, через 5 хв', t: '14:11' },
          { side: 'l', text: 'окей чекаю', t: '14:12' },
        ].map((m, i) => (
          <div key={i} style={{
            alignSelf: m.side === 'r' ? 'flex-end' : 'flex-start',
            background: m.side === 'r' ? '#DCF8C6' : '#FFF',
            padding: '6px 9px', borderRadius: 8, maxWidth: '70%',
            font: '400 11px/1.35 system-ui', color: '#303030',
            boxShadow: '0 1px 0.5px rgba(0,0,0,0.13)',
          }}>
            {m.text}
            <span style={{ font: '300 8px/1 system-ui', color: '#999', marginLeft: 6 }}>{m.t}</span>
          </div>
        ))}
      </div>
      <div style={{
        height: 50, background: '#F0F0F0', display: 'flex', alignItems: 'center', padding: '0 14px', gap: 8,
      }}>
        <div style={{ flex: 1, height: 32, borderRadius: 18, background: '#fff' }} />
        <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#075E54' }} />
      </div>
    </div>
  );
}

// ─── Margin Glow standalone scene ────────────────────────────────────────────
function MarginGlowScene({ onClose, state, setState }) {
  const cfg = {
    FOCUS:    { color: '#F4AF25', label: 'gold · task pending',   rate: 4.0 },
    DIALOGUE: { color: '#F4AF25', label: 'gold · in conversation', rate: 1.4 },
    SENTINEL: { color: '#FFB870', label: 'amber · scanning',      rate: 1.8 },
    GHOST:    { color: '#7A7A7A', label: 'gray · invisible',      rate: 12 },
    SHADOW:   { color: '#A07A40', label: 'dim · low power',       rate: 8 },
    DREAM:    { color: '#FFD9A5', label: 'soft · drifting',       rate: 10 },
  };
  const c = cfg[state] || cfg.FOCUS;

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
    }}>
      {/* the glow itself — bezel-style ring inside the phone */}
      <div style={{
        position: 'absolute', inset: 6,
        borderRadius: 38, pointerEvents: 'none',
        boxShadow: `inset 0 0 60px 8px ${c.color}`,
        opacity: 0.55,
        animation: `phantom-bezel-pulse ${c.rate}s ease-in-out infinite`,
        transition: 'box-shadow 1.2s ease',
      }} />
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        boxShadow: `inset 0 0 120px 30px ${c.color}`,
        opacity: 0.18,
        animation: `phantom-bezel-pulse ${c.rate * 1.3}s ease-in-out infinite`,
      }} />

      {/* header */}
      <div style={{ padding: '60px 24px 12px', position: 'relative', zIndex: 5 }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 18,
        }}>← повернутись</button>
        <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
          MARGIN · GLOW
        </div>
        <div style={{
          font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
          marginTop: 6, letterSpacing: '0.04em',
        }}>· state без орба · колір на грані працює сам</div>
      </div>

      {/* content — current state explanation, no orb */}
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        justifyContent: 'center', padding: '0 28px', gap: 14,
        position: 'relative', zIndex: 5,
      }}>
        <div className="glass hero" style={{ padding: '18px 20px', borderRadius: 20 }}>
          <div className="mono" style={{
            fontSize: 9, color: c.color, letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 8,
            textShadow: `0 0 8px ${c.color}`,
          }}>{c.label}</div>
          <div style={{
            font: '500 15px/1.4 var(--font-display)', color: 'var(--ink-strong)',
            textWrap: 'pretty', marginBottom: 6,
          }}>колір кодує стан · ритм кодує терміновість</div>
          <div style={{
            font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>edge-lighting bezel працює над будь-якою апкою · operator вчиться читати за тиждень</div>
        </div>
      </div>

      {/* state picker */}
      <div style={{ padding: '0 18px 24px', position: 'relative', zIndex: 5 }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.1em',
          marginBottom: 8, textAlign: 'center',
        }}>· tap state · color shifts ·</div>
        <div style={{ display: 'flex', gap: 4, justifyContent: 'space-between' }}>
          {Object.keys(cfg).map((k) => (
            <button key={k} onClick={() => setState(k)} style={{
              appearance: 'none', cursor: 'pointer', flex: 1,
              padding: '8px 4px', borderRadius: 10,
              background: state === k ? `${cfg[k].color}30` : 'transparent',
              border: `0.5px solid ${state === k ? cfg[k].color : 'var(--glass-edge)'}`,
              color: state === k ? cfg[k].color : 'var(--ink-secondary)',
              font: '500 9px/1 var(--font-display)',
              letterSpacing: '0.06em',
            }}>{k}</button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Subliminal Pulse — Orb only, encoded ────────────────────────────────────
function SubliminalPulseScene({ onClose, orbTier }) {
  const patterns = [
    { id: 'task',    label: 'task pending',       state: 'FOCUS',    period: 1.5, color: 'gold' },
    { id: 'wellness',label: 'wellness reminder',  state: 'DREAM',    period: 9,   color: 'sage' },
    { id: 'warning', label: 'warning',            state: 'SENTINEL', period: 0.6, color: 'coral' },
    { id: 'vault',   label: 'vault access',       state: 'GHOST',    period: 5,   color: 'violet' },
    { id: 'calm',    label: 'all calm',           state: 'SHADOW',   period: 8,   color: 'dim' },
  ];
  const [active, setActive] = React.useState('task');
  const cur = patterns.find((p) => p.id === active);

  // override colors per pattern via custom Orb wrapper
  const orbColor = {
    sage:   { core: '#9DCC9F', mid: '#5A8060', halo: '#1A2218' },
    coral:  { core: '#FF8B6E', mid: '#A04030', halo: '#2A1010' },
    violet: { core: '#B49AD4', mid: '#5A3D74', halo: '#1A0F24' },
  }[cur.color];

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
    }}>
      <LiveWallpaper variant="still" state={cur.state} />
      <div style={{ padding: '60px 24px 12px', position: 'relative', zIndex: 5 }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 18,
        }}>← повернутись</button>
        <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
          SUBLIMINAL · ПУЛЬС
        </div>
        <div style={{
          font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
          marginTop: 6, letterSpacing: '0.04em',
        }}>· орб без тексту · ритм та колір кодують зміст</div>
      </div>

      {/* center orb */}
      <div style={{
        flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
        position: 'relative', zIndex: 5,
      }}>
        <div key={active} style={{
          position: 'relative',
          animation: `phantom-subpulse ${cur.period}s ease-in-out infinite`,
        }}>
          <Orb state={cur.state} tier={orbTier} size={180}
               audio={0.1} motionScale={1} breathPeriod={cur.period}
               colorOverride={orbColor} />
        </div>
      </div>

      {/* hint */}
      <div style={{
        textAlign: 'center', padding: '0 28px 12px', position: 'relative', zIndex: 5,
      }}>
        <div className="mono" style={{
          fontSize: 10, color: 'var(--ink-tertiary)', letterSpacing: '0.12em',
          marginBottom: 4, textTransform: 'uppercase',
        }}>· {cur.color} · {cur.period}s</div>
        <div style={{
          font: '400 11.5px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
          fontStyle: 'italic',
        }}>«{cur.label}»</div>
      </div>

      {/* pattern picker */}
      <div style={{ padding: '0 18px 24px', position: 'relative', zIndex: 5 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {patterns.map((p) => (
            <button key={p.id} onClick={() => setActive(p.id)} style={{
              appearance: 'none', cursor: 'pointer',
              padding: '10px 12px', borderRadius: 10,
              background: active === p.id ? 'rgba(244,175,37,0.08)' : 'transparent',
              border: '0.5px solid var(--glass-edge)',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <span style={{
                font: '500 12px/1 var(--font-display)',
                color: active === p.id ? 'var(--gold)' : 'var(--ink-strong)',
              }}>{p.label}</span>
              <span className="mono" style={{
                fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
              }}>{p.color} · {p.period}s</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Family Hive scene ───────────────────────────────────────────────────────
function FamilyHiveScene({ onClose, orbTier }) {
  const members = [
    { id: 'self',  name: 'кирил',   r: 0.0, theta: 0,    dist: '·',     bear: '·',   active: true,  color: '#F4AF25' },
    { id: 'olya',  name: 'оля',     r: 0.42, theta: -60, dist: '1.2km', bear: 'N',   active: false, color: '#FF8B6E' },
    { id: 'oleh',  name: 'олег',    r: 0.55, theta: 90,  dist: '4.8km', bear: 'E',   active: false, color: '#9DCC9F' },
    { id: 'mama',  name: 'мама',    r: 0.7,  theta: 200, dist: '120km', bear: 'SW',  active: false, color: '#B49AD4' },
    { id: 'anya',  name: 'аня',     r: 0.32, theta: 30,  dist: '0.8km', bear: 'NE',  active: false, color: '#7CC0E8' },
  ];
  const [selected, setSelected] = React.useState('olya');
  const sel = members.find((m) => m.id === selected);

  const cx = 165, cy = 165, R = 130;
  const pos = (m) => ({
    x: cx + Math.cos((m.theta - 90) * Math.PI / 180) * R * m.r,
    y: cy + Math.sin((m.theta - 90) * Math.PI / 180) * R * m.r,
  });

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
    }}>
      <LiveWallpaper variant="still" state="FOCUS" />
      <div style={{ padding: '60px 24px 12px', position: 'relative', zIndex: 5 }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 18,
        }}>← повернутись</button>
        <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
          HIVE · РОДИНА
        </div>
        <div style={{
          font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
          marginTop: 6, letterSpacing: '0.04em',
        }}>· trusted devices · proxemic indicator</div>
      </div>

      {/* radar */}
      <div style={{
        position: 'relative', width: 330, height: 330, margin: '8px auto',
      }}>
        {/* radar rings */}
        <svg viewBox="0 0 330 330" style={{ position: 'absolute', inset: 0 }}>
          <defs>
            <radialGradient id="hiveRing">
              <stop offset="0%" stopColor="rgba(244,175,37,0.06)" />
              <stop offset="80%" stopColor="rgba(244,175,37,0.02)" />
              <stop offset="100%" stopColor="rgba(244,175,37,0)" />
            </radialGradient>
          </defs>
          <circle cx="165" cy="165" r="130" fill="url(#hiveRing)" />
          {[40, 80, 120].map((r) => (
            <circle key={r} cx="165" cy="165" r={r} fill="none"
                    stroke="var(--glass-edge)" strokeWidth="0.5"
                    strokeDasharray="2 4" />
          ))}
          <line x1="165" y1="35" x2="165" y2="295" stroke="var(--glass-edge)" strokeWidth="0.3" />
          <line x1="35" y1="165" x2="295" y2="165" stroke="var(--glass-edge)" strokeWidth="0.3" />
          <text x="165" y="32" fill="var(--ink-tertiary)" fontSize="8" textAnchor="middle" fontFamily="var(--font-mono)">N</text>
          <text x="298" y="168" fill="var(--ink-tertiary)" fontSize="8" textAnchor="middle" fontFamily="var(--font-mono)">E</text>
          <text x="165" y="305" fill="var(--ink-tertiary)" fontSize="8" textAnchor="middle" fontFamily="var(--font-mono)">S</text>
          <text x="32" y="168" fill="var(--ink-tertiary)" fontSize="8" textAnchor="middle" fontFamily="var(--font-mono)">W</text>
          {/* bearing line to selected */}
          {sel && sel.id !== 'self' && (() => { const p = pos(sel); return (
            <line x1={cx} y1={cy} x2={p.x} y2={p.y}
                  stroke={sel.color} strokeWidth="0.7" strokeDasharray="3 3"
                  style={{ filter: `drop-shadow(0 0 4px ${sel.color})` }} />
          ); })()}
        </svg>
        {/* members */}
        {members.map((m) => {
          const p = pos(m);
          const isSelf = m.id === 'self';
          const isSel = m.id === selected;
          const size = isSelf ? 38 : isSel ? 30 : 22;
          return (
            <button key={m.id} onClick={() => setSelected(m.id)} style={{
              position: 'absolute',
              left: p.x - size / 2, top: p.y - size / 2,
              width: size, height: size, borderRadius: '50%',
              appearance: 'none', cursor: 'pointer', padding: 0,
              background: `radial-gradient(circle at 35% 30%, ${m.color}, ${m.color}50 60%, transparent)`,
              border: `0.5px solid ${m.color}`,
              boxShadow: `0 0 ${isSel ? 18 : 8}px ${m.color}`,
              animation: `phantom-hive-pulse ${isSelf ? 4 : 6 + (m.r * 4)}s ease-in-out infinite`,
              transition: 'all 0.3s',
            }} />
          );
        })}
      </div>

      {/* selected detail */}
      <div style={{ padding: '0 18px 8px', position: 'relative', zIndex: 5 }}>
        <div className="glass" style={{
          padding: '12px 14px', borderRadius: 14,
          borderColor: `${sel.color}30`,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
            <div style={{ font: '500 14px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.005em' }}>
              {sel.name}
            </div>
            <div className="mono" style={{ fontSize: 10, color: sel.color, letterSpacing: '0.06em' }}>
              · {sel.dist} {sel.bear}
            </div>
          </div>
          <div style={{
            font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>
            {sel.id === 'self' ? 'ти · тут і зараз'
              : sel.id === 'olya' ? 'вдома · працює · не турбувати до 20:00'
              : sel.id === 'oleh' ? 'у дорозі · повернеться о 19:50'
              : sel.id === 'mama' ? 'у києві · спить за 2 години'
              : 'кафе · відкрита до розмови'}
          </div>
        </div>
      </div>

      {/* voice cmd hint */}
      <div style={{ padding: '8px 18px 18px', position: 'relative', zIndex: 5 }}>
        <div className="glass" style={{
          padding: '10px 12px', borderRadius: 14,
          background: 'rgba(244,175,37,0.04)',
        }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· голос ·</div>
          <div style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-secondary)', fontStyle: 'italic' }}>
            «де олег?» → орб {sel.id === 'oleh' ? 'пульсує' : 'олега підсвітиться'} · «1.2 км north»
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Surface Tray (long-press SystemPulseHeader) ─────────────────────────────
function SurfaceTray({ open, onClose, current, onChange }) {
  const surfaces = [
    { id: 'stream',     label: 'stream' },
    { id: 'home',       label: 'home' },
    { id: 'lockscreen', label: 'lock' },
    { id: 'wallpaper',  label: 'wall' },
    { id: 'onboarding', label: 'onboarding' },
    { id: 'pair',       label: 'pair' },
    { id: 'vault',      label: 'vault' },
    { id: 'diary',      label: 'diary' },
    { id: 'motion',     label: 'motion' },
    { id: 'whisper',    label: 'whisper' },
    { id: 'glow',       label: 'glow' },
    { id: 'pulse',      label: 'pulse' },
    { id: 'hive',       label: 'hive' },
    { id: 'pdf',        label: 'pdf chat' },
    { id: 'calendar',   label: 'calendar' },
    { id: 'email',      label: 'inbox' },
    { id: 'smarthome',  label: 'home iot' },
    { id: 'sleep',      label: 'sleep' },
    { id: 'imagegen',   label: 'image gen' },
    { id: 'palace',     label: 'palace' },
    { id: 'dream',      label: 'dream' },
    { id: 'court',      label: 'court' },
    { id: 'health',     label: 'health' },
    { id: 'type',       label: 'type' },
    { id: 'gallery',    label: 'gallery ✦' },
    { id: 'lab',        label: 'lab ✦✦' },
    { id: 'seance',     label: 'séance ✦✦✦' },
    { id: 'live',       label: 'live ✦✦✦✦' },
    { id: 'lens',       label: 'lens ✦✦✦✦✦' },
  ];
  return (
    <>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 80,
        background: open ? 'rgba(0,0,0,0.4)' : 'transparent',
        backdropFilter: open ? 'blur(2px)' : 'none',
        opacity: open ? 1 : 0, pointerEvents: open ? 'auto' : 'none',
        transition: 'all 0.3s',
      }} />
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 90,
        transform: open ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 0.4s cubic-bezier(.3,.7,.4,1)',
      }}>
        <div className="glass hero accent" style={{
          margin: 12, padding: '16px 14px',
          borderRadius: '22px',
          display: 'flex', flexDirection: 'column', gap: 12,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div className="mono" style={{
              fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}>· поверхні</div>
            <button onClick={onClose} style={{
              appearance: 'none', border: 'none', background: 'transparent',
              color: 'var(--ink-tertiary)', cursor: 'pointer',
              font: '300 16px/1 var(--font-mono)', padding: 0,
            }}>×</button>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {surfaces.map((s) => {
              const on = current === s.id;
              return (
                <button key={s.id} onClick={() => { onChange(s.id); onClose(); }}
                        style={{
                          appearance: 'none', cursor: 'pointer',
                          padding: '8px 12px', borderRadius: 999,
                          background: on ? 'var(--gold)' : 'rgba(255,230,180,0.04)',
                          border: `0.5px solid ${on ? 'var(--gold)' : 'var(--glass-edge)'}`,
                          color: on ? '#1A140B' : 'var(--ink-strong)',
                          font: '500 11px/1 var(--font-display)',
                          letterSpacing: '0.02em',
                          boxShadow: on ? '0 0 14px var(--gold-glow)' : 'none',
                        }}>{s.label}</button>
              );
            })}
          </div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em',
            textAlign: 'center', marginTop: 4,
          }}>· long-press на «ВАРТА» щоб відкрити цю панель ·</div>
        </div>
      </div>
    </>
  );
}

// ─── keyframes ───────────────────────────────────────────────────────────────
const PHASE2_STYLE = `
@keyframes phantom-whisper-drift { from { transform: translateX(0); } to { transform: translateX(-50%); } }
@keyframes phantom-bezel-pulse { 0%,100% { opacity: 0.5; } 50% { opacity: 0.85; } }
@keyframes phantom-subpulse { 0%,100% { transform: scale(0.95); filter: brightness(0.85); } 50% { transform: scale(1.06); filter: brightness(1.15); } }
@keyframes phantom-hive-pulse { 0%,100% { transform: scale(1); opacity: 0.95; } 50% { transform: scale(1.08); opacity: 1; } }
`;
if (typeof document !== 'undefined' && !document.getElementById('phase2-style')) {
  const s = document.createElement('style');
  s.id = 'phase2-style';
  s.textContent = PHASE2_STYLE;
  document.head.appendChild(s);
}

Object.assign(window, {
  ThreadRail, ChartWidget, FileCard, CodeBlock, VoiceMemo, CitationPill, ToolTrace,
  WhisperLineScene, MarginGlowScene, SubliminalPulseScene, FamilyHiveScene,
  SurfaceTray,
});
