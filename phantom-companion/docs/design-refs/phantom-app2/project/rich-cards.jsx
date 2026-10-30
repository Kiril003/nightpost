/* rich-cards.jsx — assorted chat content cards:
   MapPOI, PlaceCard, RecipeCard, ContactCard, ProductCard,
   PollCard, MusicNowPlaying, ComparisonCard, ColorPalette,
   KnowledgeCard, LocationLive. */

// ─── 1. Map with multiple POIs ───────────────────────────────────────────────
function MapPOI({ title = 'кав\'ярні поряд', pois }) {
  const ps = pois || [
    { x: 0.32, y: 0.62, name: 'local roasters',  dist: '120 м', rating: 4.8, open: true, kind: 'coffee' },
    { x: 0.55, y: 0.42, name: 'fika',             dist: '350 м', rating: 4.6, open: true, kind: 'coffee' },
    { x: 0.72, y: 0.78, name: 'кава прав.',       dist: '480 м', rating: 4.4, open: false, kind: 'coffee' },
    { x: 0.42, y: 0.22, name: 'lviv handmade',    dist: '620 м', rating: 4.7, open: true, kind: 'coffee', highlight: true },
  ];
  const [active, setActive] = React.useState(3);
  const sel = ps[active];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      {/* map canvas */}
      <div style={{
        height: 200, position: 'relative', overflow: 'hidden',
        background: `
          radial-gradient(ellipse 80% 60% at 50% 50%, #1a120a, #0a0805 70%),
          repeating-linear-gradient(0deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 36px),
          repeating-linear-gradient(90deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 36px)
        `,
      }}>
        <svg viewBox="0 0 300 200" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* streets */}
          <path d="M -10 70 Q 80 60 150 78 T 310 80" fill="none" stroke="rgba(167,150,125,0.15)" strokeWidth="6" />
          <path d="M 60 -10 Q 65 100 80 210" fill="none" stroke="rgba(167,150,125,0.12)" strokeWidth="5" />
          <path d="M 200 -10 Q 195 80 215 220" fill="none" stroke="rgba(167,150,125,0.12)" strokeWidth="5" />
          <path d="M -10 140 Q 100 130 200 150 T 310 145" fill="none" stroke="rgba(167,150,125,0.1)" strokeWidth="4" />
          {/* park / green */}
          <ellipse cx="60" cy="160" rx="40" ry="22" fill="rgba(124,169,130,0.08)" />
          <ellipse cx="240" cy="40" rx="34" ry="18" fill="rgba(124,169,130,0.06)" />
          {/* river */}
          <path d="M -10 110 Q 80 105 160 115 T 310 110" fill="none" stroke="rgba(124,192,232,0.15)" strokeWidth="3" />
        </svg>
        {/* POI pins */}
        {ps.map((p, i) => (
          <button key={i} onClick={() => setActive(i)} style={{
            position: 'absolute',
            left: `${p.x*100}%`, top: `${p.y*100}%`,
            transform: 'translate(-50%, -100%)',
            appearance: 'none', cursor: 'pointer', padding: 0,
            background: 'transparent', border: 'none',
            zIndex: i === active ? 10 : 5,
          }}>
            <div style={{
              position: 'relative',
              width: i === active ? 30 : 22, height: i === active ? 30 : 22,
              borderRadius: '50% 50% 50% 0',
              transform: 'rotate(-45deg)',
              background: p.open ? `radial-gradient(circle at 30% 30%, var(--gold), #7A4818)` : 'linear-gradient(135deg, #4A3F30, #1A140B)',
              border: `1px solid ${p.open ? 'var(--gold)' : 'var(--ink-faint)'}`,
              boxShadow: i === active ? '0 0 14px var(--gold-glow)' : '0 2px 4px rgba(0,0,0,0.4)',
              animation: p.highlight ? 'phantom-pulse-glow 1.6s ease-in-out infinite' : 'none',
            }}>
              <div style={{
                position: 'absolute', inset: '20% 20% 30% 20%',
                borderRadius: '50%',
                background: 'rgba(0,0,0,0.4)',
                transform: 'rotate(45deg)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <span style={{ font: '500 9px/1 var(--font-display)', color: '#fff', transform: 'rotate(0deg)' }}>☕</span>
              </div>
            </div>
            {i === active && (
              <div style={{
                position: 'absolute', left: '50%', top: '-32px',
                transform: 'translateX(-50%)',
                padding: '4px 8px', borderRadius: 999,
                background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(8px)',
                font: '500 9.5px/1 var(--font-display)', color: '#fff',
                whiteSpace: 'nowrap',
                border: '0.5px solid var(--gold-soft)',
              }}>{p.name}</div>
            )}
          </button>
        ))}
        {/* center (you) */}
        <div style={{
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 12, height: 12, borderRadius: '50%',
          background: '#7CC0E8',
          boxShadow: '0 0 0 4px rgba(124,192,232,0.25), 0 0 16px rgba(124,192,232,0.5)',
          zIndex: 4,
        }} />
        {/* compass / scale */}
        <div className="mono" style={{
          position: 'absolute', bottom: 8, left: 8, fontSize: 8,
          color: 'var(--ink-tertiary)', letterSpacing: '0.1em',
        }}>· ⊙ 500 м ·</div>
        <div className="mono" style={{
          position: 'absolute', top: 8, right: 8, fontSize: 8,
          color: 'var(--gold-soft)', letterSpacing: '0.1em',
          background: 'rgba(0,0,0,0.4)', padding: '3px 7px', borderRadius: 999,
        }}>· {ps.length} поряд</div>
      </div>
      <div style={{ padding: '10px 14px' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· {title}</div>
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          gap: 8, marginTop: 6,
        }}>
          <div>
            <div style={{ font: '500 12.5px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{sel.name}</div>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 2, letterSpacing: '0.04em' }}>
              {sel.dist} · ★ {sel.rating} · {sel.open ? 'відкрито' : 'закрито'}
            </div>
          </div>
          <button style={{
            appearance: 'none', cursor: 'pointer',
            background: 'rgba(244,175,37,0.10)', border: '0.5px solid rgba(244,175,37,0.25)',
            color: 'var(--gold)', font: '500 10px/1 var(--font-body)',
            padding: '6px 10px', borderRadius: 999,
          }}>прокласти</button>
        </div>
      </div>
    </div>
  );
}

// ─── 2. Place Card (restaurant / café detailed) ──────────────────────────────
function PlaceCard({ name = 'local roasters', kind = 'кав\'ярня',
                     rating = 4.8, reviews = 642, scene = 'food',
                     hours, badges, distance = '120 м · 2 хв пішки' }) {
  const hh = hours || [
    { d: 'нд', h: '8:00 — 19:00' },
    { d: 'пн', h: '7:30 — 21:00', today: false },
    { d: 'вт', h: '7:30 — 21:00' },
    { d: 'ср', h: '7:30 — 21:00' },
    { d: 'чт', h: '7:30 — 21:00', today: true },
    { d: 'пт', h: '7:30 — 22:00' },
    { d: 'сб', h: '8:00 — 22:00' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene={scene} ratio="16/9" />
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.6) 100%)' }} />
        <div style={{
          position: 'absolute', bottom: 12, left: 14, right: 14,
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'rgba(255,250,220,0.7)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 3,
          }}>· {kind}</div>
          <div style={{
            font: '600 17px/1.2 var(--font-display)', color: '#fff',
            letterSpacing: '-0.005em', textShadow: '0 1px 6px rgba(0,0,0,0.5)',
          }}>{name}</div>
        </div>
        {/* open badge */}
        <div style={{
          position: 'absolute', top: 10, right: 10,
          padding: '4px 9px', borderRadius: 999,
          background: 'rgba(124,169,130,0.2)', backdropFilter: 'blur(10px)',
          border: '0.5px solid rgba(157,204,159,0.4)',
        }}>
          <span className="mono" style={{
            fontSize: 8.5, color: '#9DCC9F', letterSpacing: '0.1em',
          }}>· відкрито до 21:00</span>
        </div>
      </div>
      <div style={{ padding: '12px 14px' }}>
        {/* rating + distance */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
            <span style={{ font: '500 18px/1 var(--font-display)', color: 'var(--ink-strong)', fontVariantNumeric: 'tabular-nums' }}>{rating}</span>
            <div style={{ display: 'flex', gap: 1 }}>
              {[0,1,2,3,4].map((i) => (
                <svg key={i} width="10" height="10" viewBox="0 0 10 10">
                  <path d="M5 0 L6.3 3.3 L9.5 3.5 L7 5.7 L7.8 9 L5 7.1 L2.2 9 L3 5.7 L0.5 3.5 L3.7 3.3 Z"
                        fill={i < Math.floor(rating) ? 'var(--gold)' : 'var(--ink-faint)'} />
                </svg>
              ))}
            </div>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginLeft: 4 }}>({reviews})</span>
          </div>
          <div style={{ flex: 1 }} />
          <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.04em' }}>· {distance}</span>
        </div>
        {/* badges */}
        <div style={{ display: 'flex', gap: 5, marginBottom: 12, flexWrap: 'wrap' }}>
          {(badges || ['специальне зерно', 'wi-fi', 'веганське', 'тиха зала']).map((b) => (
            <span key={b} className="mono" style={{
              padding: '3px 8px', borderRadius: 999, fontSize: 9,
              background: 'rgba(244,175,37,0.06)', color: 'var(--gold-soft)',
              border: '0.5px solid rgba(244,175,37,0.18)',
              letterSpacing: '0.04em',
            }}>· {b}</span>
          ))}
        </div>
        {/* hours */}
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', marginBottom: 5,
        }}>· години</div>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)',
          gap: 4, marginBottom: 10,
        }}>
          {hh.map((d) => (
            <div key={d.d} style={{
              padding: '4px 0', textAlign: 'center', borderRadius: 6,
              background: d.today ? 'rgba(244,175,37,0.10)' : 'transparent',
              border: `0.5px solid ${d.today ? 'var(--gold-soft)' : 'transparent'}`,
            }}>
              <div className="mono" style={{
                fontSize: 9, color: d.today ? 'var(--gold)' : 'var(--ink-secondary)',
                letterSpacing: '0.06em', textTransform: 'uppercase',
              }}>{d.d}</div>
              <div style={{
                font: '400 8.5px/1.2 var(--font-mono)', color: d.today ? 'var(--gold-soft)' : 'var(--ink-tertiary)',
                marginTop: 3, letterSpacing: '0.02em',
              }}>{d.h.replace(' — ', '–')}</div>
            </div>
          ))}
        </div>
        {/* phantom note */}
        <div style={{
          padding: '8px 10px', borderRadius: 8,
          background: 'rgba(244,175,37,0.05)',
          font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
          textWrap: 'pretty',
        }}>
          <span className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.14em', textTransform: 'uppercase', display: 'block', marginBottom: 3 }}>· phantom · note</span>
          ти був тут 12 разів. бариста знає твоє ім\'я і пам\'ятає що ти любиш без молока.
        </div>
      </div>
    </div>
  );
}

// ─── 3. Recipe Card ──────────────────────────────────────────────────────────
function RecipeCard({ name = 'мізо · 4 хвилини', time = '4 хв', servings = 1,
                      scene = 'food', ingredients, steps }) {
  const ig = ingredients || [
    { q: '1 ст. л.', t: 'паста міcо (червона)' },
    { q: '350 мл',   t: 'кипʼячена вода, охолоджена до 75°' },
    { q: '·',         t: 'жменя сухої wakame' },
    { q: '·',         t: 'дрібно: zelena cibula, тофу шовковий 50г' },
  ];
  const st = steps || [
    'у миску — wakame, залити теплою водою на 1 хв',
    'покласти тофу, посипати zelena cibula',
    'розчинити мicо у 50 мл рідини окремо',
    'злити воду з wakame, налити кип\'яток · додати міcо',
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene={scene} ratio="3/2" />
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.55) 100%)' }} />
        <div style={{ position: 'absolute', bottom: 12, left: 14, right: 14 }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'rgba(255,210,140,0.85)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 3,
          }}>· рецепт · легке</div>
          <div style={{ font: '500 15px/1.2 var(--font-display)', color: '#fff', textShadow: '0 1px 4px rgba(0,0,0,0.5)' }}>{name}</div>
        </div>
        {/* stats overlay */}
        <div style={{
          position: 'absolute', top: 10, right: 10,
          display: 'flex', gap: 6,
        }}>
          {[
            { v: time, k: 'час' },
            { v: servings, k: 'порц' },
            { v: '47', k: 'kcal' },
          ].map((s) => (
            <div key={s.k} style={{
              padding: '5px 8px', borderRadius: 8,
              background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(10px)',
              border: '0.5px solid rgba(255,255,255,0.1)',
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1,
            }}>
              <span style={{ font: '500 11px/1 var(--font-display)', color: '#fff' }}>{s.v}</span>
              <span className="mono" style={{ fontSize: 7, color: 'rgba(255,255,255,0.6)', letterSpacing: '0.1em' }}>{s.k}</span>
            </div>
          ))}
        </div>
      </div>
      <div style={{ padding: '12px 14px' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· інгредієнти</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginBottom: 12 }}>
          {ig.map((row, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '60px 1fr', gap: 8, alignItems: 'baseline' }}>
              <span className="mono" style={{ fontSize: 9.5, color: 'var(--gold-soft)', letterSpacing: '0.04em' }}>{row.q}</span>
              <span style={{ font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{row.t}</span>
            </div>
          ))}
        </div>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· кроки</div>
        <ol style={{
          listStyle: 'none', padding: 0, margin: 0,
          display: 'flex', flexDirection: 'column', gap: 5, counterReset: 'step',
        }}>
          {st.map((s, i) => (
            <li key={i} style={{
              display: 'grid', gridTemplateColumns: '20px 1fr', gap: 8, alignItems: 'baseline',
            }}>
              <span style={{
                width: 18, height: 18, borderRadius: '50%',
                background: 'rgba(244,175,37,0.10)',
                border: '0.5px solid rgba(244,175,37,0.25)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '500 9.5px/1 var(--font-display)', color: 'var(--gold)',
              }}>{i+1}</span>
              <span style={{ font: '400 11.5px/1.45 var(--font-body)', color: 'var(--ink)', textWrap: 'pretty' }}>{s}</span>
            </li>
          ))}
        </ol>
        <button style={{
          marginTop: 12, appearance: 'none', cursor: 'pointer', width: '100%',
          padding: '9px', borderRadius: 10,
          background: 'rgba(244,175,37,0.94)', border: 'none',
          color: '#1A140B', font: '500 11.5px/1 var(--font-display)',
          letterSpacing: '0.02em',
        }}>почати таймер · 4:00</button>
      </div>
    </div>
  );
}

// ─── 4. Contact Card ─────────────────────────────────────────────────────────
function ContactCard({ name = 'оля костенко', role = 'designer · phantom-os',
                       hue = '#FF8B6E', initials = 'ОК',
                       channels, lastActive = '· 12 хв тому' }) {
  const ch = channels || [
    { kind: 'phone',   v: '+380 67 832 4421', icon: '☎' },
    { kind: 'message', v: '@olya_k', icon: '✉' },
    { kind: 'voice',   v: 'voice memo · 2 хв', icon: '◐' },
    { kind: 'phantom', v: 'phantom·olya · спарено · 4ms', icon: '◊' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <div style={{
          width: 52, height: 52, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${hue}, ${hue}55 60%, ${hue}22)`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          border: '0.5px solid rgba(255,230,180,0.12)',
          boxShadow: `0 0 16px ${hue}33`,
          position: 'relative', flexShrink: 0,
        }}>
          <span style={{ font: '500 18px/1 var(--font-display)', color: '#fff', letterSpacing: '0.02em' }}>{initials}</span>
          {/* presence dot */}
          <span style={{
            position: 'absolute', right: 0, bottom: 2,
            width: 11, height: 11, borderRadius: '50%',
            background: 'var(--sage)',
            border: '2px solid var(--bg-base)',
          }} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ font: '500 14px/1.2 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.005em' }}>{name}</div>
          <div className="mono" style={{ fontSize: 9.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 3 }}>{role}</div>
          <div className="mono" style={{ fontSize: 8.5, color: 'var(--sage)', letterSpacing: '0.06em', marginTop: 3 }}>
            · онлайн {lastActive}
          </div>
        </div>
        <button style={{
          appearance: 'none', cursor: 'pointer',
          background: 'rgba(244,175,37,0.10)', border: '0.5px solid rgba(244,175,37,0.25)',
          color: 'var(--gold)', font: '500 10px/1 var(--font-body)',
          padding: '8px 12px', borderRadius: 999,
        }}>відкрити нитку</button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {ch.map((c, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '24px 1fr auto', gap: 8, alignItems: 'center',
            padding: '6px 8px', borderRadius: 8,
            background: 'rgba(255,230,180,0.02)',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <span style={{
              width: 18, height: 18, borderRadius: 4,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              background: 'rgba(244,175,37,0.08)',
              color: 'var(--gold-soft)',
              font: '400 10px/1 var(--font-display)',
            }}>{c.icon}</span>
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-strong)', letterSpacing: '0.02em',
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.v}</span>
            <button style={{
              appearance: 'none', cursor: 'pointer',
              background: 'transparent', border: 'none',
              color: 'var(--ink-tertiary)', font: '400 9px/1 var(--font-mono)',
              padding: '4px 6px', borderRadius: 6,
            }}>→</button>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 10, padding: '6px 8px', borderRadius: 8,
        background: 'rgba(244,175,37,0.04)',
        font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.14em' }}>· phantom · </span>
        останнє: розмова про переїзд · 12 травня · «вона була нервова»
      </div>
    </div>
  );
}

// ─── 5. Poll / Vote card ─────────────────────────────────────────────────────
function PollCard({ question = 'що замовляємо на вечерю?', options }) {
  const init = options || [
    { t: 'тайська локшина',  v: 4, by: ['оля', 'олег', 'аня', 'ти'] },
    { t: 'грузинське',        v: 2, by: ['мама', 'аня'] },
    { t: 'sushi',              v: 1, by: ['олег'] },
    { t: 'не хочу їсти',       v: 0, by: [] },
  ];
  const [opts, setOpts] = React.useState(init);
  const [voted, setVoted] = React.useState(null);
  const total = opts.reduce((s, o) => s + o.v, 0);
  const lead = opts.reduce((m, o, i) => o.v > opts[m].v ? i : m, 0);

  const cast = (i) => {
    if (voted !== null) return;
    setVoted(i);
    setOpts((arr) => arr.map((o, j) => j === i ? { ...o, v: o.v + 1, by: [...o.by, 'ти'] } : o));
  };
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
        <span className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}>· опитування · сімейна група</span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>· {total} голосів</span>
      </div>
      <div style={{
        font: '500 13.5px/1.3 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 10, textWrap: 'pretty',
      }}>{question}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {opts.map((o, i) => {
          const pct = total ? o.v / total : 0;
          const isLead = i === lead && o.v > 0;
          const myVote = voted === i;
          return (
            <button key={i} onClick={() => cast(i)} disabled={voted !== null && !myVote}
                    style={{
                      appearance: 'none', cursor: voted === null ? 'pointer' : 'default',
                      textAlign: 'left', padding: '8px 10px', borderRadius: 8,
                      background: 'rgba(255,230,180,0.02)',
                      border: `0.5px solid ${myVote ? 'var(--gold-soft)' : isLead ? 'rgba(244,175,37,0.3)' : 'var(--glass-edge)'}`,
                      position: 'relative', overflow: 'hidden',
                    }}>
              {/* bar fill */}
              <div style={{
                position: 'absolute', left: 0, top: 0, bottom: 0,
                width: `${pct*100}%`,
                background: isLead ? 'rgba(244,175,37,0.10)' : 'rgba(255,230,180,0.04)',
                transition: 'width 0.4s ease',
              }} />
              <div style={{
                position: 'relative', display: 'flex', justifyContent: 'space-between',
                alignItems: 'center', gap: 8,
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0 }}>
                  <span style={{
                    width: 14, height: 14, borderRadius: '50%',
                    background: myVote ? 'var(--gold)' : 'transparent',
                    border: `1px solid ${myVote ? 'var(--gold)' : 'var(--ink-faint)'}`,
                    flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    boxShadow: myVote ? '0 0 6px var(--gold-glow)' : 'none',
                  }}>
                    {myVote && <span style={{ width: 6, height: 6, background: '#1A140B', borderRadius: '50%' }} />}
                  </span>
                  <span style={{
                    font: '400 12px/1.3 var(--font-body)',
                    color: isLead ? 'var(--ink-strong)' : 'var(--ink)',
                    textWrap: 'pretty', flex: 1, minWidth: 0,
                  }}>{o.t}</span>
                </div>
                <span className="mono" style={{
                  fontSize: 10, color: isLead ? 'var(--gold)' : 'var(--ink-tertiary)',
                  letterSpacing: '0.04em', flexShrink: 0,
                  fontVariantNumeric: 'tabular-nums',
                }}>{Math.round(pct*100)}% · {o.v}</span>
              </div>
              {/* voter chips */}
              {o.by.length > 0 && (
                <div style={{
                  position: 'relative', display: 'flex', gap: -4, marginTop: 5, paddingLeft: 22,
                }}>
                  {o.by.slice(0, 5).map((n, j) => (
                    <span key={j} style={{
                      width: 16, height: 16, borderRadius: '50%',
                      background: `linear-gradient(135deg, #${['F4AF25','7E5DA4','7CA982','FF8B6E','7CC0E8'][j % 5]}, #1A140B)`,
                      border: '1.5px solid var(--bg-base)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      font: '500 7.5px/1 var(--font-display)', color: '#fff',
                      marginLeft: j > 0 ? -5 : 0,
                      letterSpacing: '0.02em',
                    }}>{n[0]}</span>
                  ))}
                </div>
              )}
            </button>
          );
        })}
      </div>
      <div className="mono" style={{
        marginTop: 8, fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em',
        textAlign: 'right',
      }}>· закриється о 19:00 · {voted !== null ? 'ти проголосував' : 'обери варіант'}</div>
    </div>
  );
}

// ─── 6. Music Now Playing ────────────────────────────────────────────────────
function MusicNowPlaying({ track = 'Carrie & Lowell', artist = 'sufjan stevens', album = 'Carrie & Lowell · 2015',
                           pos = 142, duration = 226 }) {
  const [playing, setPlaying] = React.useState(true);
  const [p, setP] = React.useState(pos);
  React.useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => setP((x) => Math.min(duration, x + 1)), 1000);
    return () => clearInterval(id);
  }, [playing, duration]);
  const mm = (s) => `${Math.floor(s/60)}:${String(s%60|0).padStart(2,'0')}`;
  const pct = p / duration;
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `
        radial-gradient(ellipse at 0% 0%, rgba(126,93,164,0.15), transparent 60%),
        radial-gradient(ellipse at 100% 100%, rgba(244,175,37,0.08), transparent 60%),
        var(--glass-tint)`,
    }}>
      <div style={{ padding: '14px 14px', display: 'flex', gap: 12, alignItems: 'center' }}>
        {/* cover art */}
        <div style={{
          width: 76, height: 76, borderRadius: 10, flexShrink: 0,
          background: `
            radial-gradient(ellipse 70% 80% at 30% 30%, #c79a7a 0%, #7a5040 35%, #3a2a20 65%, #0a0805 100%)`,
          position: 'relative', overflow: 'hidden',
          border: '0.5px solid rgba(255,230,180,0.15)',
          boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
        }}>
          {/* subtle silhouette */}
          <svg viewBox="0 0 76 76" style={{ position: 'absolute', inset: 0, opacity: 0.4 }}>
            <ellipse cx="38" cy="38" rx="14" ry="18" fill="rgba(40,20,10,0.5)" />
            <rect x="22" y="50" width="32" height="30" rx="2" fill="rgba(40,20,10,0.4)" />
          </svg>
          {/* vinyl rotating */}
          <div style={{
            position: 'absolute', right: -22, top: '50%', transform: 'translateY(-50%)',
            width: 60, height: 60, borderRadius: '50%',
            background: 'radial-gradient(circle, #1a1108 0%, #0a0805 70%)',
            border: '0.5px solid rgba(255,230,180,0.2)',
            animation: playing ? 'phantom-vinyl 5s linear infinite' : 'none',
          }}>
            {/* grooves */}
            {[24, 19, 14].map((r) => (
              <div key={r} style={{
                position: 'absolute', inset: '50%', width: r*2, height: r*2,
                transform: `translate(-${r}px, -${r}px)`,
                borderRadius: '50%', border: '0.5px solid rgba(244,175,37,0.08)',
              }} />
            ))}
            <div style={{
              position: 'absolute', left: '50%', top: '50%', width: 14, height: 14,
              transform: 'translate(-50%, -50%)',
              borderRadius: '50%', background: 'var(--gold)',
            }} />
          </div>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'rgba(180,150,220,0.85)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· зараз грає</div>
          <div style={{
            font: '500 13.5px/1.3 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.005em', textWrap: 'pretty',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{track}</div>
          <div className="mono" style={{
            fontSize: 10, color: 'var(--ink-secondary)', letterSpacing: '0.02em',
            marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{artist}</div>
        </div>
      </div>
      <div style={{ padding: '0 14px 12px' }}>
        {/* scrubber */}
        <div style={{ position: 'relative', height: 4, background: 'rgba(255,230,180,0.06)', borderRadius: 2, marginBottom: 4 }}>
          <div style={{
            width: `${pct*100}%`, height: '100%', borderRadius: 2,
            background: 'linear-gradient(to right, #B49AD4, var(--gold))',
            boxShadow: '0 0 4px var(--gold-glow)',
          }} />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between',
          font: '300 9px/1 var(--font-mono)', color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
          fontVariantNumeric: 'tabular-nums', marginBottom: 10 }}>
          <span>{mm(p)}</span>
          <span>-{mm(duration - p)}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <button style={{ appearance: 'none', cursor: 'pointer', border: 'none', background: 'transparent',
            color: 'var(--ink-tertiary)', padding: 6 }}>
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M3 2v10M11 2L4 7L11 12Z" fill="currentColor"/></svg>
          </button>
          <button onClick={() => setPlaying((x) => !x)} style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 42, height: 42, borderRadius: '50%',
            background: 'var(--gold)', color: '#1A140B',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 0 14px var(--gold-glow)',
          }}>
            {playing ? (
              <svg width="13" height="13" viewBox="0 0 13 13"><rect x="2" y="2" width="3" height="9"/><rect x="8" y="2" width="3" height="9"/></svg>
            ) : (
              <svg width="14" height="14" viewBox="0 0 13 13"><path d="M3 2 L11 6.5 L3 11 Z" fill="currentColor"/></svg>
            )}
          </button>
          <button style={{ appearance: 'none', cursor: 'pointer', border: 'none', background: 'transparent',
            color: 'var(--ink-tertiary)', padding: 6 }}>
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M11 2v10M3 2L10 7L3 12Z" fill="currentColor"/></svg>
          </button>
          <div style={{ flex: 1 }} />
          <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.08em' }}>· {album}</span>
        </div>
      </div>
    </div>
  );
}

// ─── 7. Comparison Card (table of 2-3 options) ───────────────────────────────
function ComparisonCard({ title = 'два варіанти переїзду', items, criteria }) {
  const ig = items || [
    { name: 'lhasa',  badge: 'тепер', accent: '#F4AF25' },
    { name: 'leh',    badge: 'альт', accent: '#7CC0E8' },
  ];
  const cr = criteria || [
    { l: 'висота',         a: '3 656 м', b: '3 524 м' },
    { l: 'температ. травень', a: '-2 / 14°', b: '0 / 16°' },
    { l: 'інтернет',       a: '40 mbps', b: '12 mbps', winner: 'a' },
    { l: 'віза',           a: 'permit · 2 тиж', b: 'simple visa · 5 днів', winner: 'b' },
    { l: 'медицина',       a: 'обмежено', b: 'добре', winner: 'b' },
    { l: 'літак з kbp',    a: '31 г · 3 пересадки', b: '24 г · 2 пересадки', winner: 'b' },
    { l: 'mood',           a: 'містичне', b: 'просторе' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· порівняння</div>
      <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 10 }}>{title}</div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 12 }}>
        {ig.map((it) => (
          <div key={it.name} style={{
            padding: '10px 10px', borderRadius: 10,
            background: `${it.accent}10`,
            borderLeft: `2px solid ${it.accent}`,
          }}>
            <div className="mono" style={{ fontSize: 8.5, color: it.accent, letterSpacing: '0.16em', textTransform: 'uppercase' }}>· {it.badge}</div>
            <div style={{ font: '500 14px/1.2 var(--font-display)', color: 'var(--ink-strong)', marginTop: 3 }}>{it.name}</div>
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        {cr.map((row, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '90px 1fr 1fr', gap: 6, alignItems: 'baseline',
            padding: '5px 0',
            borderBottom: i < cr.length-1 ? '0.5px solid rgba(255,230,180,0.04)' : 'none',
          }}>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>· {row.l}</span>
            <span style={{
              font: '400 11px/1.3 var(--font-body)',
              color: row.winner === 'a' ? 'var(--gold-soft)' : 'var(--ink)',
              fontWeight: row.winner === 'a' ? 500 : 400,
              textWrap: 'pretty',
            }}>
              {row.winner === 'a' && '✓ '}{row.a}
            </span>
            <span style={{
              font: '400 11px/1.3 var(--font-body)',
              color: row.winner === 'b' ? '#7CC0E8' : 'var(--ink)',
              fontWeight: row.winner === 'b' ? 500 : 400,
              textWrap: 'pretty',
            }}>
              {row.winner === 'b' && '✓ '}{row.b}
            </span>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 10, padding: '8px 10px', borderRadius: 8,
        background: 'rgba(244,175,37,0.05)',
        font: '400 10.5px/1.5 var(--font-body)', color: 'var(--gold-soft)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        «4 з 7 leh, але lhasa переважує там, де ти його шукаєш — тиша та висота. вирішуєш сам.»
      </div>
    </div>
  );
}

// ─── 8. Color Palette card ───────────────────────────────────────────────────
function ColorPalette({ title = 'phantom · amber-night', kicker = '· палітра',
                        colors }) {
  const cs = colors || [
    { name: 'gold',       v: '#F4AF25', usage: '·     primary glow', cont: '#1A140B' },
    { name: 'gold-soft',  v: '#E0992A', usage: '·     hover · ranges' },
    { name: 'ink-strong', v: '#FFF8E7', usage: '·     text — головне', cont: '#1A140B' },
    { name: 'bg-base',    v: '#0E0A05', usage: '·     поверхня · ніч', cont: '#FFF8E7' },
    { name: 'coral',      v: '#FF8B6E', usage: '·     помилки' },
    { name: 'sage',       v: '#9DCC9F', usage: '·     ствердження' },
    { name: 'vault',      v: '#7E5DA4', usage: '·     ghost · vault' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>{kicker}</div>
      <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 10 }}>{title}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {cs.map((c) => (
          <div key={c.name} style={{
            display: 'grid', gridTemplateColumns: '44px 1fr auto',
            gap: 10, alignItems: 'center',
            padding: '6px', borderRadius: 8,
            background: 'rgba(255,230,180,0.02)',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <div style={{
              width: 44, height: 28, borderRadius: 5,
              background: c.v,
              border: '0.5px solid rgba(255,255,255,0.1)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              boxShadow: `0 0 8px ${c.v}33`,
            }}>
              <span style={{ font: '500 8px/1 var(--font-mono)', color: c.cont || '#fff', opacity: 0.85, letterSpacing: '0.04em' }}>Aa</span>
            </div>
            <div>
              <div style={{ font: '500 11px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{c.name}</div>
              <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 1 }}>{c.usage}</div>
            </div>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', letterSpacing: '0.06em' }}>{c.v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 9. Knowledge Card (concept + related links) ─────────────────────────────
function KnowledgeCard({ topic = 'циркадний ритм', summary, key_facts, related }) {
  const kf = key_facts || [
    'регулюється супрахіазматичним ядром',
    'синхронізується світлом — найсильніше синім',
    'збій → 30% ризик депресії в 3-річному вікні',
  ];
  const rel = related || [
    { t: 'мелатонін',     k: 'biology' },
    { t: 'jet lag',       k: 'lifestyle' },
    { t: 'sleep debt',     k: 'health' },
    { t: 'light therapy', k: 'method' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 6,
      }}>· знання · уточнено з 3 джерел</div>
      <div style={{
        font: '600 15px/1.3 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 6,
      }}>{topic}</div>
      <div style={{
        font: '400 11.5px/1.55 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty', marginBottom: 10,
      }}>
        {summary || 'внутрішній 24-годинний цикл — гени cry/per/bmal/clock у кожній клітині. керує сном, температурою, гормонами. збивається легко, налагоджується довше.'}
      </div>
      <div style={{
        padding: '8px 10px', borderRadius: 8,
        background: 'rgba(244,175,37,0.04)',
        border: '0.5px solid rgba(244,175,37,0.12)',
        marginBottom: 10,
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· ключове</div>
        <ul style={{
          margin: 0, padding: 0, listStyle: 'none',
          display: 'flex', flexDirection: 'column', gap: 2,
        }}>
          {kf.map((k, i) => (
            <li key={i} style={{
              font: '400 11px/1.4 var(--font-body)', color: 'var(--ink)',
              display: 'flex', alignItems: 'baseline', gap: 6, textWrap: 'pretty',
            }}>
              <span style={{ color: 'var(--gold)', flexShrink: 0 }}>·</span>{k}
            </li>
          ))}
        </ul>
      </div>
      <div>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', marginBottom: 5,
        }}>· повʼязане</div>
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
          {rel.map((r, i) => (
            <button key={i} style={{
              appearance: 'none', cursor: 'pointer',
              padding: '4px 9px', borderRadius: 999,
              background: 'rgba(255,230,180,0.04)',
              border: '0.5px solid var(--glass-edge)',
              color: 'var(--ink-strong)', font: '400 10px/1 var(--font-body)',
              display: 'flex', alignItems: 'center', gap: 5,
            }}>
              <span style={{ color: 'var(--gold-soft)' }}>→</span>{r.t}
              <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>· {r.k}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── 10. Live location share (friend pulse on tiny map) ──────────────────────
function LocationLive({ name = 'оля', when = 'оновлено · 12 сек тому', battery = 64,
                        moving = 'пішки · 4 км/г', eta }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      {/* mini map */}
      <div style={{
        height: 130, position: 'relative', overflow: 'hidden',
        background: `
          radial-gradient(ellipse 80% 60% at 50% 50%, #1a120a, #050402 80%),
          repeating-linear-gradient(0deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 32px),
          repeating-linear-gradient(90deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 32px)
        `,
      }}>
        <svg viewBox="0 0 300 130" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* streets */}
          <path d="M 0 60 Q 90 50 150 65 T 300 70" fill="none" stroke="rgba(167,150,125,0.15)" strokeWidth="5" />
          <path d="M 100 0 Q 110 80 105 140" fill="none" stroke="rgba(167,150,125,0.12)" strokeWidth="4" />
          <path d="M 220 -10 Q 215 80 230 140" fill="none" stroke="rgba(167,150,125,0.12)" strokeWidth="4" />
          {/* trail */}
          <path d="M 180 30 Q 150 50 130 60 T 90 75 L 70 85"
                fill="none" stroke="#FF8B6E" strokeWidth="1.3"
                strokeDasharray="3 3" opacity="0.7" />
        </svg>
        {/* friend pulse */}
        <div style={{
          position: 'absolute', left: '23%', top: '65%',
          transform: 'translate(-50%, -50%)',
        }}>
          <div style={{
            position: 'absolute', inset: -10, width: 28, height: 28,
            borderRadius: '50%', background: 'radial-gradient(circle, rgba(255,139,110,0.4), transparent 60%)',
            animation: 'phantom-pulse-glow 1.8s ease-in-out infinite',
          }} />
          <div style={{
            width: 18, height: 18, borderRadius: '50%',
            background: 'linear-gradient(135deg, #FF8B6E, #A03020)',
            border: '2px solid #fff',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '500 9px/1 var(--font-display)', color: '#fff',
            boxShadow: '0 4px 10px rgba(0,0,0,0.5)',
          }}>{name[0]}</div>
        </div>
        {/* destination — your home */}
        <div style={{
          position: 'absolute', left: '63%', top: '21%',
          transform: 'translate(-50%, -50%)',
          width: 12, height: 12, borderRadius: '50%',
          background: 'var(--gold)',
          boxShadow: '0 0 0 4px rgba(244,175,37,0.2)',
        }} />
        <div className="mono" style={{
          position: 'absolute', top: 8, left: 8,
          fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
        }}>· live · 30 хв тривання</div>
      </div>
      {/* status */}
      <div style={{ padding: '10px 14px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{
              width: 22, height: 22, borderRadius: '50%',
              background: 'linear-gradient(135deg, #FF8B6E, #A03020)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              font: '500 10px/1 var(--font-display)', color: '#fff',
            }}>{name[0]}</span>
            <div>
              <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{name} · поряд</div>
              <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>{when}</div>
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className="mono" style={{ fontSize: 10, color: 'var(--gold)', fontVariantNumeric: 'tabular-nums' }}>
              {eta || '8 хв'}
            </div>
            <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>
              до тебе
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 12, marginTop: 4 }}>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', letterSpacing: '0.04em' }}>· {moving}</span>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', letterSpacing: '0.04em' }}>· {battery}% батарея</span>
        </div>
      </div>
    </div>
  );
}

// keyframes
if (typeof document !== 'undefined' && !document.getElementById('rich-cards-style')) {
  const s = document.createElement('style');
  s.id = 'rich-cards-style';
  s.textContent = `
    @keyframes phantom-vinyl { from { transform: translateY(-50%) rotate(0deg); } to { transform: translateY(-50%) rotate(360deg); } }
  `;
  document.head.appendChild(s);
}

Object.assign(window, {
  MapPOI, PlaceCard, RecipeCard, ContactCard, PollCard,
  MusicNowPlaying, ComparisonCard, ColorPalette, KnowledgeCard, LocationLive,
});
