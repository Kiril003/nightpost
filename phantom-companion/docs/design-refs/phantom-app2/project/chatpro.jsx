/* chatpro.jsx — chat-pro features for PersonThread:
   ToneShifter (5-variant rewriter), ReDecodeCard (subtext),
   PhantomEnvoy (send phantom ahead), TimeBubble (optimal delivery),
   ReactWithAnything (rich reactions), ThreadSkeleton (long-thread summary). */

// ─── ToneShifter — write a draft, see 5 variants ───────────────────────────
function ToneShifter({ value, onChange, hue = '#F4AF25' }) {
  const [open, setOpen] = React.useState(false);
  const tones = [
    { k: 'as-is',  l: 'як написав',    d: '— твоє, без правок',                t: (v) => v },
    { k: 'soft',   l: 'мʼягше',        d: '— з повагою до її настрою',         t: (v) => `${v[0]?.toUpperCase() || ''}${v.slice(1)} · обережно — як думаєш, ок?` },
    { k: 'firm',   l: 'твердіше',      d: '— без переговорів',                  t: (v) => `${v}. так і буде.` },
    { k: 'short',  l: 'коротше',       d: '— одне речення',                     t: (v) => v.split(/[.,]/)[0] + '.' },
    { k: 'poetic', l: 'красивіше',     d: '— образ замість пояснення',          t: (v) => `«${v.toLowerCase()}» — як дощ, що раптом ясно.` },
    { k: 'true',   l: 'чесніше',       d: '— те, що ти ховаєш',                  t: (v) => 'я просто втомлений · сьогодні не сьогодні.' },
  ];
  if (!value || value.length < 3) return null;
  return (
    <div style={{ marginBottom: 6 }}>
      <button onClick={() => setOpen((o) => !o)} style={{
        appearance: 'none', cursor: 'pointer', width: '100%',
        textAlign: 'left', padding: '6px 10px', borderRadius: 999,
        background: 'rgba(244,175,37,0.06)',
        border: '0.5px solid rgba(244,175,37,0.22)',
        color: 'var(--gold-soft)', font: '400 10.5px/1 var(--font-body)',
        display: 'flex', alignItems: 'center', gap: 6,
        letterSpacing: '0.04em',
      }}>
        <svg width="10" height="10" viewBox="0 0 10 10">
          <path d="M2 8L2 5L5 2L8 5L8 8L2 8z M5 2v6" stroke="currentColor" strokeWidth="0.8" fill="none" />
        </svg>
        <span>тон · {open ? 'звести' : 'переписати'}</span>
        <span style={{ flex: 1 }} />
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>5 варіантів ↓</span>
      </button>
      {open && (
        <div className="fade-in" style={{
          marginTop: 6, padding: 6, borderRadius: 12,
          background: 'rgba(0,0,0,0.22)',
          border: '0.5px solid var(--glass-edge)',
          display: 'flex', flexDirection: 'column', gap: 4,
        }}>
          {tones.map((t) => (
            <button key={t.k} onClick={() => { onChange(t.t(value)); setOpen(false); }} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '8px 10px', borderRadius: 8,
              background: 'rgba(255,230,180,0.025)',
              border: '0.5px solid var(--glass-edge)',
            }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 3 }}>
                <span className="mono" style={{ fontSize: 8, color: hue, letterSpacing: '0.16em', textTransform: 'uppercase' }}>· {t.l}</span>
                <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>{t.d}</span>
              </div>
              <div style={{
                font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-strong)',
                textWrap: 'pretty', fontStyle: 'italic',
              }}>«{t.t(value)}»</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── ReDecodeCard — tap their message to see subtext ───────────────────────
function ReDecodeCard({ original, decoded, signals, hue = '#FF8B6E' }) {
  const ss = signals || [
    { l: 'мікросигнал', d: '«ще раз?» — це третій раз. вона втомлена пояснювати.' },
    { l: 'патерн',     d: 'коли вона пише без розділових — вона збирає себе в кулак.' },
    { l: 'історія',    d: 'попередньо «не сьогодні так?» означало «я готова почути ні».' },
    { l: 'тіло',        d: 'її hrv (через airpods) знижене на 22%. стрес у плечах.' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '8px 0', borderRadius: 14,
      background: `radial-gradient(ellipse at 0% 0%, ${hue}18, transparent 60%), var(--glass-tint)`,
      border: `0.5px solid ${hue}33`,
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: hue, letterSpacing: '0.22em', textTransform: 'uppercase', marginBottom: 6,
      }}>· decoded · phantom · 92% впевненості</div>
      <div style={{
        font: '300 11.5px/1.55 Georgia, serif', color: 'var(--ink-secondary)',
        fontStyle: 'italic', textWrap: 'pretty', marginBottom: 4,
      }}>«{original}»</div>
      <div style={{
        font: '500 13px/1.55 var(--font-display)', color: 'var(--ink-strong)',
        textWrap: 'pretty', marginBottom: 10, letterSpacing: '0.005em',
      }}>→ {decoded}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
        {ss.map((s, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '80px 1fr',
            gap: 8, alignItems: 'baseline', padding: '4px 0',
          }}>
            <span className="mono" style={{ fontSize: 8.5, color: hue, letterSpacing: '0.06em' }}>· {s.l}</span>
            <span style={{ font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{s.d}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── PhantomEnvoy — send your phantom ahead to converse ────────────────────
function PhantomEnvoy({ person, onClose }) {
  const [phase, setPhase] = React.useState('travelling');
  React.useEffect(() => {
    const t1 = setTimeout(() => setPhase('talking'), 1400);
    const t2 = setTimeout(() => setPhase('returning'), 4000);
    const t3 = setTimeout(() => setPhase('done'), 5400);
    return () => { clearTimeout(t1); clearTimeout(t2); clearTimeout(t3); };
  }, []);
  const yourHue = '#F4AF25';
  const orbX = phase === 'travelling' ? '15%' :
               phase === 'talking'    ? '80%' :
               phase === 'returning'  ? '40%' : '15%';
  return (
    <div className="glass accent fade-in" style={{
      margin: '10px 0', padding: 0, borderRadius: 16, overflow: 'hidden',
      background: `radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.10), transparent 60%), var(--glass-tint)`,
      border: '0.5px solid rgba(244,175,37,0.30)',
    }}>
      <div style={{ padding: '10px 14px 6px' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· envoy · phantom як посланник</div>
        <div style={{
          font: '500 13px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        }}>фантом пішов до {person.name}.</div>
      </div>
      <div style={{
        position: 'relative', height: 88, overflow: 'hidden',
      }}>
        <svg viewBox="0 0 360 88" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* path */}
          <path d="M 50 44 Q 180 14 310 44" fill="none" stroke="rgba(244,175,37,0.4)" strokeWidth="0.8" strokeDasharray="3 3" />
          {/* you */}
          <circle cx="50" cy="44" r="10" fill={`url(#you-grad)`} />
          <defs>
            <radialGradient id="you-grad"><stop offset="0%" stopColor={yourHue} /><stop offset="100%" stopColor={`${yourHue}22`} /></radialGradient>
            <radialGradient id="them-grad"><stop offset="0%" stopColor={person.hue} /><stop offset="100%" stopColor={`${person.hue}22`} /></radialGradient>
          </defs>
          {/* them */}
          <circle cx="310" cy="44" r="10" fill="url(#them-grad)" />
        </svg>
        {/* envoy orb */}
        <div style={{
          position: 'absolute', left: orbX, top: '50%',
          transform: 'translate(-50%, -50%) scale(0.7)',
          width: 18, height: 18, borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25)',
          boxShadow: '0 0 12px var(--gold-glow)',
          animation: 'phantom-pulse-glow 1s ease-in-out infinite',
          transition: 'left 1.2s cubic-bezier(.3,.7,.4,1)',
        }} />
        {/* status */}
        <div className="mono" style={{
          position: 'absolute', left: 0, right: 0, bottom: 4, textAlign: 'center',
          fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
          textTransform: 'uppercase',
        }}>
          {phase === 'travelling' ? '· летить ·' :
           phase === 'talking'    ? '· розмова · ' + (Math.floor(performance.now() / 200) % 3 + 1) + 's' :
           phase === 'returning'  ? '· повертається ·' :
           '· повернувся ·'}
        </div>
      </div>
      {phase === 'done' && (
        <div className="fade-in" style={{
          padding: '10px 14px',
          borderTop: '0.5px solid var(--glass-edge)',
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 5,
          }}>· report ·</div>
          <div style={{
            font: '400 12px/1.55 var(--font-body)', color: 'var(--ink-strong)',
            textWrap: 'pretty', marginBottom: 8,
          }}>{person.name} ок з тим щоб ти прийшов завтра, не сьогодні. вона запитала чи ти спав. сказала «не нервуй».</div>
          <div style={{ display: 'flex', gap: 5 }}>
            <button style={{
              appearance: 'none', cursor: 'pointer', border: 'none',
              padding: '6px 10px', borderRadius: 999,
              background: 'var(--gold)', color: '#1A140B',
              font: '500 10.5px/1 var(--font-display)', letterSpacing: '0.02em',
            }}>прийняти · завтра</button>
            <button onClick={onClose} style={{
              appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
              padding: '6px 10px', borderRadius: 999,
              background: 'transparent', color: 'var(--ink-secondary)',
              font: '400 10.5px/1 var(--font-body)', letterSpacing: '0.02em',
            }}>звести</button>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── TimeBubble — schedule for optimal delivery moment ──────────────────────
function TimeBubble({ open, onClose, person, onSchedule }) {
  if (!open) return null;
  const slots = [
    { l: 'зараз',                d: 'надішли як завжди',          conf: 1.0, w: 'now' },
    { l: 'через 23 хв',           d: 'її телефон у тиші · потім перевірить', conf: 0.74, w: '23m' },
    { l: 'завтра вранці · 08:14', d: 'її «м\'яке» вікно · phantom бачить', conf: 0.91, w: '08:14' },
    { l: 'після її розмови з мамою (~21:30)', d: 'phantom скаже коли вона звільниться', conf: 0.82, w: '21:30' },
    { l: 'коли її hrv підійметься', d: 'phantom доставить коли тіло готове', conf: 0.78, w: 'hrv' },
  ];
  return (
    <div className="glass panel fade-in" style={{
      position: 'absolute', left: 12, right: 12, bottom: 110,
      padding: 6, borderRadius: 16,
      background: 'rgba(20,14,10,0.95)', backdropFilter: 'blur(24px)',
      border: '0.5px solid rgba(244,175,37,0.30)',
      zIndex: 30,
    }}>
      <div className="mono" style={{
        padding: '6px 10px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.22em', textTransform: 'uppercase',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span>· час · коли краще</span>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer', font: '300 12px/1 var(--font-mono)',
        }}>×</button>
      </div>
      {slots.map((s) => (
        <button key={s.w} onClick={() => { onSchedule?.(s); onClose?.(); }} style={{
          appearance: 'none', cursor: 'pointer', textAlign: 'left',
          width: '100%', padding: '8px 10px', borderRadius: 8,
          background: 'transparent', border: '0.5px solid transparent',
          display: 'flex', alignItems: 'baseline', gap: 8,
        }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{
              font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)',
            }}>{s.l}</div>
            <div className="mono" style={{
              fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
              marginTop: 2,
            }}>{s.d}</div>
          </div>
          <span className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.04em',
            fontVariantNumeric: 'tabular-nums',
          }}>{(s.conf * 100 | 0)}%</span>
        </button>
      ))}
    </div>
  );
}

// ─── ReactWithAnything — picker for rich reactions ──────────────────────────
function ReactWithAnything({ open, onClose, onPick }) {
  if (!open) return null;
  const items = [
    { kind: 'emoji', l: '❤︎', sub: 'тепло' },
    { kind: 'emoji', l: '✺', sub: 'звʼязок' },
    { kind: 'emoji', l: '✦', sub: 'дивно' },
    { kind: 'emoji', l: '◐', sub: 'тихо' },
    { kind: 'emoji', l: '⊙', sub: 'бачу' },
    { kind: 'photo', l: '◧', sub: 'фото · одне' },
    { kind: 'voice', l: '◐', sub: 'голосова · 4 с' },
    { kind: 'song',  l: '♪', sub: 'пісня' },
    { kind: 'mood',  l: '∿', sub: 'мій настрій' },
  ];
  return (
    <div className="glass panel fade-in" style={{
      position: 'absolute', left: 12, right: 12, bottom: 110,
      padding: 8, borderRadius: 16,
      background: 'rgba(20,14,10,0.95)', backdropFilter: 'blur(24px)',
      border: '0.5px solid rgba(244,175,37,0.30)',
      zIndex: 30,
    }}>
      <div className="mono" style={{
        padding: '4px 8px 8px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.22em', textTransform: 'uppercase',
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span>· відреагувати чим завгодно</span>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer', font: '300 12px/1 var(--font-mono)',
        }}>×</button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 4 }}>
        {items.map((i, idx) => (
          <button key={idx} onClick={() => { onPick?.(i); onClose?.(); }} style={{
            appearance: 'none', cursor: 'pointer',
            padding: '12px 8px', borderRadius: 10,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          }}>
            <span style={{ font: '400 22px/1 Georgia, serif', color: i.kind === 'emoji' ? 'var(--gold)' : 'var(--ink-strong)' }}>{i.l}</span>
            <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>{i.sub}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── Reaction bubble — display a rich reaction inline ───────────────────────
function ReactionBubble({ kind, content, from, hue }) {
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: from === 'them' ? 'flex-start' : 'flex-end',
      padding: '2px 0',
    }}>
      <div style={{
        padding: '4px 10px 4px 6px', borderRadius: 999,
        background: `${hue}10`,
        border: `0.5px solid ${hue}30`,
        display: 'flex', alignItems: 'center', gap: 5,
      }}>
        <span style={{ font: '400 14px/1 Georgia, serif', color: hue }}>{content}</span>
        <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>· {kind}</span>
      </div>
    </div>
  );
}

// ─── ThreadSkeleton — long-thread summary ───────────────────────────────────
function ThreadSkeleton({ person, daysBack = 14 }) {
  const phases = [
    { d: '14 дн тому', n: 12, t: 'початок · ти запропонував переїзд · вона зацікавлена', mood: 'curious' },
    { d: '11 дн тому', n: 8,  t: 'обговорення дат · вона хвилюється про роботу',          mood: 'anxious' },
    { d: '8 дн тому',  n: 4,  t: 'тиша · ти зайнятий · вона теж',                          mood: 'quiet' },
    { d: '4 дн тому',  n: 22, t: 'фото з фіки · теплий день · обмін піснями',              mood: 'warm' },
    { d: 'вчора',      n: 18, t: 'розмова про маму · ти прохав ради · вона дала',          mood: 'open' },
    { d: 'сьогодні',   n: 6,  t: 'про завтрашній нотаріус · вона нервує',                  mood: 'tense' },
  ];
  const moodC = { curious: '#7CC0E8', anxious: '#FF8B6E', quiet: '#A7967D', warm: '#F4AF25', open: '#9DCC9F', tense: '#FF8B6E' };
  return (
    <div className="glass" style={{
      padding: '12px 14px', margin: '8px 0', borderRadius: 14,
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
        textTransform: 'uppercase', marginBottom: 6,
      }}>· skeleton · {daysBack} днів</div>
      <div style={{
        font: '500 13px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 10,
      }}>скелет розмов з {person.name}</div>
      <div style={{ position: 'relative', paddingLeft: 18 }}>
        <div style={{
          position: 'absolute', left: 4, top: 4, bottom: 4, width: 0.5,
          background: 'linear-gradient(to bottom, var(--gold-faint), transparent)',
        }} />
        {phases.map((ph, i) => (
          <div key={i} style={{ position: 'relative', marginBottom: 10 }}>
            <span style={{
              position: 'absolute', left: -18, top: 3,
              width: 9, height: 9, borderRadius: '50%',
              background: moodC[ph.mood],
              boxShadow: `0 0 4px ${moodC[ph.mood]}88`,
              border: '2px solid var(--bg-base)',
            }} />
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
              <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>{ph.d}</span>
              <span className="mono" style={{ fontSize: 8, color: moodC[ph.mood], letterSpacing: '0.14em', textTransform: 'uppercase' }}>· {ph.mood}</span>
              <span style={{ flex: 1 }} />
              <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)' }}>{ph.n} msg</span>
            </div>
            <div style={{
              marginTop: 3, font: '400 11px/1.45 var(--font-body)',
              color: 'var(--ink-strong)', textWrap: 'pretty',
            }}>{ph.t}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, {
  ToneShifter, ReDecodeCard, PhantomEnvoy, TimeBubble,
  ReactWithAnything, ReactionBubble, ThreadSkeleton,
});
