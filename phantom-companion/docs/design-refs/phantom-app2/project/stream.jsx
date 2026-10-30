/* stream.jsx — Stream surface (the heart):
   SystemPulseHeader · stream body (text bubbles, widgets, insights,
   handoffs, notices, map preview, vitals, timer) · input row.
   All copy in Ukrainian, in the Familiar's voice (warm, intimate, ти-form). */

const STATE_NAMES = {
  FOCUS: 'ФОКУС',
  DIALOGUE: 'ДІАЛОГ',
  SENTINEL: 'ВАРТА',
  GHOST: 'ПРИМАРА',
  SHADOW: 'ТІНЬ',
  DREAM: 'СОН',
};
const STATE_NARRATIVES = {
  FOCUS:    'разом — присутній і вільний',
  DIALOGUE: 'я слухаю — говори як тобі',
  SENTINEL: 'вечір · пасивне спостереження',
  GHOST:    'слідів не залишаю — ти невидимий',
  SHADOW:   'режим тіні — економлю ресурс',
  DREAM:    'дрімаю поряд — поклич коли треба',
};

// ─── Header ──────────────────────────────────────────────────────────────────
function VitalsConstellation({ state, audio = 0 }) {
  // 4 dots: AI / VOICE / SENSOR / LINK
  const labels = ['AI', 'VOX', 'SNS', 'LNK'];
  const intensity = state === 'GHOST' ? 0.25 : state === 'SHADOW' ? 0.55 : state === 'DREAM' ? 0.4 : 1.0;
  return (
    <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
      {labels.map((l, i) => {
        const pulse = i === 1 ? Math.max(0, audio - 0.05) : 0;
        const live = state !== 'GHOST' && (i === 0 || i === 3 || (i === 1 && audio > 0.05));
        return (
          <div key={l} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
            <div style={{
              width: 6, height: 6, borderRadius: '50%',
              background: live ? 'var(--gold)' : 'var(--ink-faint)',
              opacity: live ? 0.7 + intensity * 0.3 + pulse : 0.4,
              boxShadow: live ? `0 0 ${4 + pulse * 8}px var(--gold-glow)` : 'none',
              transition: 'background 0.4s ease, opacity 0.3s ease',
            }} />
            <span style={{
              font: '300 8px/1 var(--font-mono)',
              color: 'var(--ink-tertiary)',
              letterSpacing: '0.1em',
            }}>{l}</span>
          </div>
        );
      })}
    </div>
  );
}

function SystemPulseHeader({ state, dwellMin, audio, onOpenSettings, onLongPressName, name = 'кирил' }) {
  const [expanded, setExpanded] = React.useState(false);
  const stateName = STATE_NAMES[state];
  const narrative = STATE_NARRATIVES[state];

  return (
    <div className="ui-chrome" style={{
      padding: '14px 18px 10px',
      position: 'relative',
      background: 'linear-gradient(to bottom, rgba(14,10,5,0.7), rgba(14,10,5,0))',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <VitalsConstellation state={state} audio={audio} />

        <button onClick={() => setExpanded((e) => !e)}
                onMouseDown={(e) => {
                  if (!onLongPressName) return;
                  const t = setTimeout(onLongPressName, 450);
                  const cancel = () => clearTimeout(t);
                  e.currentTarget.addEventListener('mouseup', cancel, { once: true });
                  e.currentTarget.addEventListener('mouseleave', cancel, { once: true });
                }}
                style={{
                  appearance: 'none', background: 'transparent', border: 0,
                  color: 'var(--ink-strong)', cursor: 'pointer', padding: 0,
                  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                }}>
          <span className="state-label" style={{
            color: state === 'GHOST' ? 'var(--ghost)' : 'var(--ink-strong)',
            fontSize: 13, transition: 'color 1s ease',
          }}>{stateName}</span>
          <span className="mono" style={{
            fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em',
          }}>{dwellMin}хв</span>
        </button>

        <button onClick={onOpenSettings}
                style={{
                  appearance: 'none', background: 'transparent', border: 0,
                  color: 'var(--ink-secondary)', cursor: 'pointer', padding: 0,
                  width: 28, height: 28, display: 'flex',
                  alignItems: 'center', justifyContent: 'center',
                }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.2" />
            <path d="M19 12.6V11.4a1 1 0 00-.6-.92l-1.5-.62a6 6 0 00-.4-.97l.62-1.5a1 1 0 00-.22-1.1l-.84-.84a1 1 0 00-1.1-.22l-1.5.62a6 6 0 00-.97-.4l-.62-1.5a1 1 0 00-.92-.6h-1.2a1 1 0 00-.92.6l-.62 1.5a6 6 0 00-.97.4l-1.5-.62a1 1 0 00-1.1.22l-.84.84a1 1 0 00-.22 1.1l.62 1.5a6 6 0 00-.4.97l-1.5.62a1 1 0 00-.6.92v1.2a1 1 0 00.6.92l1.5.62c.11.34.24.66.4.97l-.62 1.5a1 1 0 00.22 1.1l.84.84a1 1 0 001.1.22l1.5-.62c.31.16.63.29.97.4l.62 1.5a1 1 0 00.92.6h1.2a1 1 0 00.92-.6l.62-1.5c.34-.11.66-.24.97-.4l1.5.62a1 1 0 001.1-.22l.84-.84a1 1 0 00.22-1.1l-.62-1.5c.16-.31.29-.63.4-.97l1.5-.62a1 1 0 00.6-.92z"
                  stroke="currentColor" strokeWidth="1.2" />
          </svg>
        </button>
      </div>

      <div style={{
        marginTop: 6,
        font: '400 11px/1.4 var(--font-body)',
        color: 'var(--ink-tertiary)',
        textAlign: 'center',
        letterSpacing: '0.01em',
      }}>{narrative}</div>

      {expanded && (
        <div className="glass subtle bloom-in" style={{
          marginTop: 10, padding: '12px 14px', borderRadius: 14,
        }}>
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10,
            marginBottom: 10,
          }}>
            {[
              ['BPM',  '64', 'спокій'],
              ['ДИХАН.', '12', '/хв'],
              ['HRV',  '48', 'мс'],
              ['РУХ',  'нерух.', ''],
            ].map(([k, v, s]) => (
              <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.1em' }}>{k}</span>
                <span style={{ font: '600 13px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{v}</span>
                {s && <span style={{ font: '300 9px var(--font-mono)', color: 'var(--ink-tertiary)' }}>{s}</span>}
              </div>
            ))}
          </div>
          <div style={{ height: 0.5, background: 'var(--glass-edge)' }} />
          <div style={{
            marginTop: 8, font: '400 10.5px/1.5 var(--font-mono)',
            color: 'var(--ink-secondary)',
          }}>
            <div style={{ color: 'var(--gold-soft)' }}>// rationale (контекст-двигун)</div>
            <div>· вечірня година, низький рух</div>
            <div>· немає ввімкнених важких процесів</div>
            <div>· оператор вдома, сам</div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Stream items ────────────────────────────────────────────────────────────

function ChatBubble({ role, text, time }) {
  const isUser = role === 'USER';
  const isSys = role === 'SYSTEM';
  if (isSys) {
    return (
      <div className="fade-in" style={{
        font: '300 10.5px/1.4 var(--font-mono)',
        color: 'var(--ink-tertiary)',
        textAlign: 'center',
        padding: '8px 0',
        letterSpacing: '0.04em',
      }}>· {text} ·</div>
    );
  }
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start',
      padding: '6px 0',
    }}>
      <div className={isUser ? 'glass subtle' : 'glass'} style={{
        maxWidth: isUser ? '72%' : '86%',
        padding: '10px 14px',
        borderRadius: isUser ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
        background: isUser ? 'rgba(244,175,37,0.10)' : 'var(--glass-tint)',
        border: isUser ? '0.5px solid rgba(244,175,37,0.25)' : '0.5px solid var(--glass-edge)',
      }}>
        <div style={{
          font: '400 13.5px/1.5 var(--font-body)',
          color: 'var(--ink-strong)',
          letterSpacing: '0.005em',
        }}>{text}</div>
        {time && (
          <div className="mono" style={{
            marginTop: 4, fontSize: 9, color: 'var(--ink-tertiary)',
            textAlign: isUser ? 'right' : 'left',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

function HeroWelcome({ name = 'кирил' }) {
  return (
    <div className="glass hero accent fade-in" style={{
      padding: '20px 18px 18px',
      borderRadius: 22,
      margin: '4px 0 12px',
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 8,
      }}>familiar · присутність</div>
      <div style={{
        font: '500 17px/1.45 var(--font-display)',
        color: 'var(--ink-strong)',
        letterSpacing: '-0.005em',
        marginBottom: 6,
      }}>я тут, {name}.</div>
      <div style={{
        font: '400 13.5px/1.55 var(--font-body)',
        color: 'var(--ink)',
        textWrap: 'pretty',
      }}>я бачу. я памʼятаю. скажи що тобі треба — або помовч, я зрозумію коли треба.</div>
    </div>
  );
}

function NoticeBar({ kind = 'info', text }) {
  const palette = {
    info:    { c: 'var(--ink-secondary)', b: 'var(--ink-faint)' },
    warning: { c: 'var(--coral)', b: 'rgba(255,139,110,0.4)' },
    error:   { c: 'var(--coral)', b: 'rgba(255,139,110,0.6)' },
  }[kind];
  return (
    <div className="fade-in" style={{
      padding: '8px 12px', margin: '6px 0',
      borderLeft: `2px solid ${palette.b}`,
      font: '400 11.5px/1.5 var(--font-body)',
      color: palette.c,
      letterSpacing: '0.01em',
    }}>{text}</div>
  );
}

function TimerWidget({ label, totalSec, elapsedSec }) {
  const pct = Math.min(1, elapsedSec / totalSec);
  const remaining = Math.max(0, totalSec - elapsedSec);
  const m = Math.floor(remaining / 60), s = remaining % 60;
  return (
    <div className="glass accent fade-in" style={{
      padding: '14px 16px', margin: '6px 0', borderRadius: 18,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          textTransform: 'uppercase',
        }}>таймер · {label}</div>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--ink-tertiary)',
        }}>{Math.floor(elapsedSec/60)}:{String(elapsedSec%60).padStart(2,'0')} / {Math.floor(totalSec/60)}:00</div>
      </div>
      <div style={{
        font: '500 32px/1 var(--font-display)',
        color: 'var(--ink-strong)',
        marginTop: 6, fontVariantNumeric: 'tabular-nums',
        letterSpacing: '-0.01em',
      }}>{m}:{String(s).padStart(2, '0')}</div>
      {/* breath-bar — not a progress bar */}
      <div style={{
        marginTop: 10, height: 1.5, background: 'rgba(244,175,37,0.12)',
        borderRadius: 1, overflow: 'hidden',
      }}>
        <div style={{
          width: `${pct * 100}%`, height: '100%',
          background: 'linear-gradient(to right, var(--gold-soft), var(--gold))',
          boxShadow: '0 0 8px var(--gold-glow)',
          transition: 'width 1s linear',
        }} />
      </div>
    </div>
  );
}

function VitalsWidget() {
  const dots = [
    { k: 'BPM',     v: '64',     dot: 'sage'  },
    { k: 'ДИХАН.',  v: '12',     dot: 'sage'  },
    { k: 'СТРЕС',   v: 'низ.',   dot: 'sage'  },
    { k: 'РУХ',     v: 'спокій', dot: 'gold'  },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
        textTransform: 'uppercase', marginBottom: 10,
      }}>vitals · 19:34</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 8 }}>
        {dots.map(({k, v, dot}) => (
          <div key={k} style={{
            display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4,
          }}>
            <div style={{
              width: 5, height: 5, borderRadius: '50%',
              background: dot === 'sage' ? 'var(--sage)' : 'var(--gold)',
              boxShadow: `0 0 6px ${dot === 'sage' ? 'rgba(157,204,159,0.6)' : 'var(--gold-glow)'}`,
            }} />
            <span style={{ font: '600 14px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{v}</span>
            <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.1em' }}>{k}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function InsightCard({ title, body, actions, onDismiss }) {
  return (
    <div className="glass accent fade-in" style={{
      padding: '14px 16px', margin: '8px 0', borderRadius: 18,
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background: 'radial-gradient(circle at 0% 0%, var(--gold-faint), transparent 60%)',
      }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, position: 'relative' }}>
        <div style={{
          width: 18, height: 18, borderRadius: '50%',
          background: 'radial-gradient(circle at 38% 32%, #FFD370, #F4AF25 50%, #7A4818 100%)',
          boxShadow: '0 0 10px var(--gold-glow)',
          flexShrink: 0,
        }} />
        <span className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          textTransform: 'uppercase', flex: 1,
        }}>проактивне · {title}</span>
        {onDismiss && (
          <button onClick={onDismiss} style={{
            appearance: 'none', border: 0, background: 'transparent',
            color: 'var(--ink-tertiary)', cursor: 'pointer', fontSize: 14, lineHeight: 1,
            padding: 4, marginRight: -4,
          }}>×</button>
        )}
      </div>
      <div style={{
        font: '400 13px/1.55 var(--font-body)',
        color: 'var(--ink)', textWrap: 'pretty', position: 'relative',
        marginBottom: actions?.length ? 12 : 0,
      }}>{body}</div>
      {actions?.length > 0 && (
        <div style={{ display: 'flex', gap: 8, position: 'relative', flexWrap: 'wrap' }}>
          {actions.map((a, i) => (
            <button key={i} onClick={a.onClick}
                    style={{
                      appearance: 'none',
                      border: a.primary ? 'none' : '0.5px solid var(--glass-edge-strong)',
                      background: a.primary ? 'var(--gold)' : 'transparent',
                      color: a.primary ? '#1A140B' : 'var(--ink-strong)',
                      font: '500 11.5px/1 var(--font-body)',
                      letterSpacing: '0.01em',
                      padding: '8px 12px', borderRadius: 999,
                      cursor: 'pointer',
                    }}>{a.label}</button>
          ))}
        </div>
      )}
    </div>
  );
}

function HandoffCard({ kind, fromDevice = 'phantom-os', preview, onAccept, onDismiss }) {
  return (
    <div className="glass accent fade-in" style={{
      padding: '14px 16px', margin: '8px 0', borderRadius: 18,
      borderColor: 'rgba(126,93,164,0.4)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <div style={{
          width: 18, height: 18, borderRadius: 4,
          background: 'linear-gradient(135deg, #7E5DA4, #3B1F50)',
          boxShadow: '0 0 8px rgba(126,93,164,0.6)',
        }} />
        <span className="mono" style={{
          fontSize: 9, color: 'rgba(180,150,220,0.9)', letterSpacing: '0.16em',
          textTransform: 'uppercase',
        }}>передача · {fromDevice}</span>
      </div>
      <div style={{
        font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 4,
      }}>{kind}</div>
      <div style={{
        font: '400 12px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        marginBottom: 12,
      }}>{preview}</div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button onClick={onAccept} style={{
          appearance: 'none', border: 'none', background: 'var(--gold)',
          color: '#1A140B', font: '500 11.5px/1 var(--font-body)',
          padding: '8px 14px', borderRadius: 999, cursor: 'pointer',
        }}>прийняти</button>
        <button onClick={onDismiss} style={{
          appearance: 'none', border: '0.5px solid var(--glass-edge)',
          background: 'transparent', color: 'var(--ink-secondary)',
          font: '400 11.5px/1 var(--font-body)',
          padding: '8px 14px', borderRadius: 999, cursor: 'pointer',
        }}>відхилити</button>
        <button onClick={onDismiss} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', font: '400 11px/1 var(--font-body)',
          padding: '8px 4px', cursor: 'pointer',
        }}>пізніше</button>
      </div>
    </div>
  );
}

function MapWidget({ destination = 'дім', distance = '4.2 км', eta = '11 хв' }) {
  return (
    <div className="glass accent fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 18, overflow: 'hidden',
    }}>
      {/* map canvas — stylised topo */}
      <div style={{
        height: 130, position: 'relative', overflow: 'hidden',
        background: `
          radial-gradient(ellipse 200% 80% at 50% 100%, #1a0e08, #0b0805 60%),
          repeating-linear-gradient(45deg, rgba(244,175,37,0.04) 0 1px, transparent 1px 24px),
          repeating-linear-gradient(-45deg, rgba(244,175,37,0.03) 0 1px, transparent 1px 32px)
        `,
      }}>
        {/* contour lines */}
        <svg viewBox="0 0 300 130" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <defs>
            <linearGradient id="route-grad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stopColor="#F4AF25" stopOpacity="0.3" />
              <stop offset="0.5" stopColor="#F4AF25" stopOpacity="1" />
              <stop offset="1" stopColor="#FFD370" stopOpacity="0.7" />
            </linearGradient>
          </defs>
          {/* contour topology */}
          {[20, 38, 56, 78, 100].map((y, i) => (
            <path key={i}
                  d={`M -10 ${y} Q 60 ${y - 8 + i*2} 130 ${y} T 280 ${y - 4} L 320 ${y}`}
                  fill="none" stroke="rgba(244,175,37,0.12)" strokeWidth="0.5" />
          ))}
          {/* threat zone */}
          <circle cx="80" cy="50" r="22" fill="rgba(255,139,110,0.08)" stroke="rgba(255,139,110,0.4)"
                  strokeWidth="0.5" strokeDasharray="2 2" />
          {/* route — golden flowing line */}
          <path d="M 35 105 C 80 80, 120 70, 165 60 S 240 25, 270 18"
                fill="none" stroke="url(#route-grad)" strokeWidth="2.5"
                strokeLinecap="round" filter="drop-shadow(0 0 4px rgba(244,175,37,0.8))" />
          {/* origin */}
          <circle cx="35" cy="105" r="4" fill="#F4AF25" />
          <circle cx="35" cy="105" r="7" fill="none" stroke="#F4AF25" strokeOpacity="0.5" />
          {/* destination */}
          <circle cx="270" cy="18" r="3" fill="#FFD370" />
          <circle cx="270" cy="18" r="6" fill="none" stroke="#FFD370" strokeOpacity="0.4">
            <animate attributeName="r" values="6;12;6" dur="2.4s" repeatCount="indefinite" />
            <animate attributeName="stroke-opacity" values="0.4;0;0.4" dur="2.4s" repeatCount="indefinite" />
          </circle>
        </svg>
      </div>
      <div style={{ padding: '10px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <div style={{ font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>до {destination}</div>
          <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.08em', marginTop: 2 }}>
            {distance} · {eta} · обхід коралової зони
          </div>
        </div>
        <button style={{
          appearance: 'none', border: '0.5px solid var(--glass-edge-strong)',
          background: 'transparent', color: 'var(--ink-strong)',
          font: '400 10.5px/1 var(--font-body)',
          padding: '6px 10px', borderRadius: 999, cursor: 'pointer',
        }}>відкрити</button>
      </div>
    </div>
  );
}

// ─── Input row ───────────────────────────────────────────────────────────────
function InputRow({
  state, onSend, onPttDown, onPttUp, ptt, audio, onChangeText,
  text, setText,
}) {
  const inputRef = React.useRef(null);
  const ringScale = ptt ? 1 + audio * 0.6 : 1;
  const isCancel = ptt;
  const isSend = !ptt && text.trim().length > 0;

  return (
    <div className="ui-chrome" style={{
      padding: '10px 14px 16px',
      background: 'linear-gradient(to top, rgba(14,10,5,0.85), rgba(14,10,5,0.4) 60%, rgba(14,10,5,0))',
      position: 'relative',
    }}>
      {ptt && (
        <div className="fade-in" style={{
          font: '400 11px/1.4 var(--font-body)',
          color: 'var(--gold-soft)',
          textAlign: 'center', marginBottom: 8,
          letterSpacing: '0.04em',
        }}>· слухаю · говори тихо ·</div>
      )}
      <div className="glass" style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '6px 6px 6px 8px',
        borderRadius: 999,
        background: 'rgba(20,14,10,0.7)',
        border: '0.5px solid var(--glass-edge)',
      }}>
        {/* PTT mic */}
        <button
          onPointerDown={onPttDown}
          onPointerUp={onPttUp}
          onPointerLeave={onPttUp}
          style={{
            appearance: 'none', border: 'none',
            width: 36, height: 36, borderRadius: '50%',
            background: ptt ? 'var(--gold)' : 'rgba(244,175,37,0.12)',
            color: ptt ? '#1A140B' : 'var(--gold)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', flexShrink: 0,
            transform: `scale(${ringScale})`,
            transition: 'background 0.2s, transform 0.08s',
            boxShadow: ptt ? '0 0 16px var(--gold-glow)' : 'none',
            touchAction: 'none',
          }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <rect x="9" y="3" width="6" height="12" rx="3" fill="currentColor" />
            <path d="M5 11a7 7 0 0014 0M12 18v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </button>

        {/* text field */}
        <input
          ref={inputRef}
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && text.trim()) onSend(text); }}
          placeholder={ptt ? '' : 'питай phantom…'}
          style={{
            flex: 1, minWidth: 0,
            appearance: 'none', border: 'none', outline: 'none',
            background: 'transparent',
            font: '400 13px/1.4 var(--font-body)',
            color: 'var(--ink-strong)',
            letterSpacing: '0.005em',
            padding: '8px 4px',
          }}
        />

        {/* Send / cancel */}
        <button
          onClick={() => { if (text.trim()) onSend(text); }}
          disabled={!isSend && !isCancel}
          style={{
            appearance: 'none', border: 'none',
            width: 36, height: 36, borderRadius: '50%',
            background: isSend ? 'var(--gold)' : 'transparent',
            color: isSend ? '#1A140B' : 'var(--ink-tertiary)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: isSend || isCancel ? 'pointer' : 'default', flexShrink: 0,
            transition: 'background 0.2s, color 0.2s',
            opacity: isSend || isCancel ? 1 : 0.4,
          }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M5 12L19 5L13 19L11 13L5 12Z" fill="currentColor" />
          </svg>
        </button>
      </div>

      {/* partial transcript */}
      {ptt && (
        <div style={{
          marginTop: 8,
          display: 'flex', justifyContent: 'center', gap: 3, alignItems: 'flex-end',
        }}>
          {Array.from({ length: 24 }).map((_, i) => {
            const phase = (Date.now() / 100 + i * 0.4) % (2 * Math.PI);
            const h = 3 + audio * (4 + Math.abs(Math.sin(phase)) * 14);
            return (
              <div key={i} style={{
                width: 2, height: h, borderRadius: 1,
                background: 'var(--gold-soft)',
                opacity: 0.4 + audio * 0.6,
                transition: 'height 0.06s linear',
              }} />
            );
          })}
        </div>
      )}
    </div>
  );
}

Object.assign(window, {
  STATE_NAMES, STATE_NARRATIVES, SystemPulseHeader, VitalsConstellation,
  ChatBubble, HeroWelcome, NoticeBar, TimerWidget, VitalsWidget,
  InsightCard, HandoffCard, MapWidget, InputRow,
});
