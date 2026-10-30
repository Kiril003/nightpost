/* rich-info.jsx — info chat cards:
   News (hero, digest, breaking, with-video), Weather (rich w/ hourly+week+radar),
   StockCard, SportsScore, CitationCluster, TranslationCard, Definition,
   QuoteCard, KnowledgeCard. */

// ─── 1. News Hero — single article, big image, headline, summary ─────────────
function NewsHero({ kicker = 'reuters · 14 травня · 18:42', title, summary, scene = 'city', tags = [], source = 'reuters' }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <PainterlyPhoto scene={scene} ratio="16/10" />
      <div style={{ padding: '12px 14px' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 8,
        }}>· {kicker}</div>
        <div style={{
          font: '600 15px/1.3 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.01em', marginBottom: 6, textWrap: 'balance',
        }}>{title || 'у lhasa зафіксовано перший справжній сніг — місяцем раніше нормальних строків'}</div>
        <div style={{
          font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
          marginBottom: 10, textWrap: 'pretty',
        }}>{summary || 'станції на трьох висотах фіксують від -3 до -7°c. метеорологи: причина — зміщення джет-стрім після слабкого ел-ніньо. до п\'ятниці тепло, далі знову.'}</div>
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          gap: 8, flexWrap: 'wrap',
        }}>
          <div style={{ display: 'flex', gap: 4 }}>
            {(tags.length ? tags : ['клімат', 'lhasa', 'погода']).map((t) => (
              <span key={t} className="mono" style={{
                padding: '3px 7px', borderRadius: 999, fontSize: 9,
                background: 'rgba(255,230,180,0.04)',
                border: '0.5px solid var(--glass-edge)',
                color: 'var(--ink-secondary)', letterSpacing: '0.04em',
              }}>{t}</span>
            ))}
          </div>
          <button style={{
            appearance: 'none', cursor: 'pointer',
            background: 'rgba(244,175,37,0.10)', border: '0.5px solid rgba(244,175,37,0.25)',
            color: 'var(--gold)', font: '500 10px/1 var(--font-body)',
            padding: '6px 10px', borderRadius: 999, letterSpacing: '0.04em',
          }}>читати ·  3 хв</button>
        </div>
      </div>
    </div>
  );
}

// ─── 2. News Digest — phantom curated, 3 stories ─────────────────────────────
function NewsDigest({ title = 'ранкове ·  6 травня', stories }) {
  const s = stories || [
    { kicker: 'tech',     title: 'apple розкрив m5 — більше ai-tdp, менше gpu', scene: 'abstract', minutes: 2 },
    { kicker: 'ukraine',  title: 'кабмін розширює грантову програму для it-ветеранів', scene: 'city', minutes: 4 },
    { kicker: 'world',    title: 'у японії — третій тиждень рекордної посухи', scene: 'forest', minutes: 5 },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· phantom · дайджест</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        </div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>·  6 хв читання</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {s.map((st, i) => (
          <button key={i} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            display: 'flex', gap: 10, alignItems: 'stretch',
            padding: 6, borderRadius: 10,
            background: 'transparent', border: '0.5px solid var(--glass-edge)',
          }}>
            <div style={{
              width: 64, aspectRatio: '1', borderRadius: 7, overflow: 'hidden', flexShrink: 0,
            }}>
              <PainterlyPhoto scene={st.scene} ratio="1/1" />
            </div>
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
              <div>
                <div className="mono" style={{
                  fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
                  textTransform: 'uppercase', marginBottom: 3,
                }}>· {st.kicker}</div>
                <div style={{ font: '500 12px/1.3 var(--font-display)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
                  {st.title}
                </div>
              </div>
              <div className="mono" style={{ marginTop: 6, fontSize: 8.5, color: 'var(--ink-tertiary)' }}>
                {st.minutes} хв
              </div>
            </div>
          </button>
        ))}
      </div>
      <div style={{
        marginTop: 8, padding: '6px 8px', borderRadius: 8,
        background: 'rgba(244,175,37,0.05)',
        font: '400 10.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        «один з заголовків я навмисно сховав — не варто читати перед оля. поверни сам коли захочеш»
      </div>
    </div>
  );
}

// ─── 3. Breaking News — urgent banner ────────────────────────────────────────
function BreakingNews({ title, where = 'kyiv · щойно', summary }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      border: '0.5px solid rgba(255,139,110,0.4)',
      boxShadow: '0 0 24px rgba(255,139,110,0.15)',
    }}>
      <div style={{
        height: 3,
        background: 'linear-gradient(to right, transparent, var(--coral), transparent)',
        position: 'relative', overflow: 'hidden',
      }}>
        <div style={{
          position: 'absolute', inset: 0,
          background: 'linear-gradient(to right, transparent 0%, #FFB89A 50%, transparent 100%)',
          animation: 'phantom-breaking-sweep 2.2s linear infinite',
        }} />
      </div>
      <div style={{ padding: '12px 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 6 }}>
          <span style={{
            width: 7, height: 7, borderRadius: '50%',
            background: '#FF8B6E',
            animation: 'phantom-pulse-glow 1.3s ease-in-out infinite',
            boxShadow: '0 0 8px #FF8B6E',
          }} />
          <span className="mono" style={{
            fontSize: 9, color: 'var(--coral)', letterSpacing: '0.22em',
            textTransform: 'uppercase', fontWeight: 600,
          }}>щойно ·  {where}</span>
        </div>
        <div style={{
          font: '600 14.5px/1.3 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.005em', marginBottom: 6, textWrap: 'balance',
        }}>{title || 'оголошено повітряну тривогу в трьох областях'}</div>
        {summary && (
          <div style={{
            font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>{summary}</div>
        )}
        <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
          <button style={{
            appearance: 'none', cursor: 'pointer',
            background: 'var(--coral)', border: 'none',
            color: '#1A0808', font: '500 11px/1 var(--font-body)',
            padding: '7px 12px', borderRadius: 999,
            boxShadow: '0 0 12px rgba(255,139,110,0.4)',
          }}>деталі</button>
          <button style={{
            appearance: 'none', cursor: 'pointer',
            background: 'transparent', border: '0.5px solid rgba(255,139,110,0.4)',
            color: 'var(--coral)', font: '400 11px/1 var(--font-body)',
            padding: '7px 12px', borderRadius: 999,
          }}>відкрити мапу</button>
        </div>
      </div>
    </div>
  );
}

// ─── 4. News with Video ──────────────────────────────────────────────────────
function NewsWithVideo({ title, kicker = 'bbc · 14 травня', scene = 'night', duration = '2:14', summary }) {
  const [playing, setPlaying] = React.useState(false);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene={scene} ratio="16/9" />
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 40%, rgba(0,0,0,0.75) 100%)' }} />
        <button onClick={() => setPlaying((p) => !p)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 48, height: 48, borderRadius: '50%',
          background: 'rgba(255,255,255,0.94)', color: '#1A140B',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 4px 18px rgba(0,0,0,0.5)',
        }}>
          {playing ? (
            <svg width="14" height="14" viewBox="0 0 14 14"><rect x="3" y="2" width="2.5" height="10"/><rect x="8.5" y="2" width="2.5" height="10"/></svg>
          ) : (
            <svg width="16" height="16" viewBox="0 0 14 14"><path d="M4 2 L12 7 L4 12 Z" fill="currentColor"/></svg>
          )}
        </button>
        <div className="mono" style={{
          position: 'absolute', top: 10, left: 10,
          fontSize: 8.5, color: 'rgba(255,255,255,0.85)',
          background: 'rgba(0,0,0,0.5)', padding: '3px 7px',
          borderRadius: 4, letterSpacing: '0.1em', backdropFilter: 'blur(8px)',
        }}>· відео · {duration}</div>
        <div style={{
          position: 'absolute', bottom: 0, left: 0, right: 0,
          padding: '14px',
        }}>
          <div className="mono" style={{
            fontSize: 9, color: 'rgba(255,210,140,0.85)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· {kicker}</div>
          <div style={{
            font: '500 13.5px/1.3 var(--font-display)', color: '#fff',
            letterSpacing: '-0.005em', textShadow: '0 1px 6px rgba(0,0,0,0.5)', textWrap: 'balance',
          }}>{title || 'нічна lhasa: перше відеосвідчення раннього снігу'}</div>
        </div>
      </div>
      {summary && (
        <div style={{
          padding: '10px 14px', borderTop: '0.5px solid var(--glass-edge)',
          font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
          textWrap: 'pretty',
        }}>{summary}</div>
      )}
    </div>
  );
}

// ─── 5. Weather Rich (hourly + 7-day + radar mini) ───────────────────────────
function WeatherRich({ place = 'lhasa', now = { t: 2, cond: 'rain' }, hourly, daily }) {
  const h = hourly || Array.from({ length: 24 }, (_, i) => {
    const hr = (19 + i) % 24;
    const sin = Math.sin((hr/24) * Math.PI * 2 - Math.PI/2);
    return { hr, t: Math.round(2 + sin*4 + Math.sin(i*0.5)*0.7), pop: i > 4 && i < 11 ? 0.3 + Math.sin(i*0.8)*0.3 : 0.05 };
  });
  const d = daily || [
    { day: 'ср', hi:  4, lo: -2, cond: 'rain' },
    { day: 'чт', hi:  6, lo:  0, cond: 'cloud' },
    { day: 'пт', hi:  9, lo:  1, cond: 'sun' },
    { day: 'сб', hi: 12, lo:  3, cond: 'sun' },
    { day: 'нд', hi: 11, lo:  4, cond: 'rain' },
    { day: 'пн', hi:  8, lo:  2, cond: 'cloud' },
    { day: 'вт', hi:  6, lo:  0, cond: 'rain' },
  ];
  const Icon = ({ cond, size = 14 }) => ({
    sun:   <svg width={size} height={size} viewBox="0 0 20 20"><circle cx="10" cy="10" r="4" fill="#F4AF25"/>{[0,45,90,135,180,225,270,315].map((a,i) => <line key={i} x1={10+Math.cos(a*Math.PI/180)*6} y1={10+Math.sin(a*Math.PI/180)*6} x2={10+Math.cos(a*Math.PI/180)*9} y2={10+Math.sin(a*Math.PI/180)*9} stroke="#F4AF25" strokeWidth="1.2" strokeLinecap="round"/>)}</svg>,
    cloud: <svg width={size} height={size} viewBox="0 0 20 20"><ellipse cx="10" cy="11" rx="6.5" ry="3.5" fill="#A7967D"/><circle cx="6.5" cy="9.5" r="3" fill="#A7967D"/><circle cx="12.5" cy="8" r="3.5" fill="#A7967D"/></svg>,
    rain:  <svg width={size} height={size} viewBox="0 0 20 20"><ellipse cx="10" cy="9" rx="6.5" ry="3.5" fill="#7CC0E8"/><circle cx="6.5" cy="7.5" r="3" fill="#7CC0E8"/><line x1="6" y1="14" x2="5.5" y2="17" stroke="#7CC0E8" strokeWidth="1.2" strokeLinecap="round"/><line x1="10" y1="14" x2="9.5" y2="17.5" stroke="#7CC0E8" strokeWidth="1.2" strokeLinecap="round"/><line x1="14" y1="14" x2="13.5" y2="17" stroke="#7CC0E8" strokeWidth="1.2" strokeLinecap="round"/></svg>,
  })[cond];

  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      {/* hero with current */}
      <div style={{
        padding: '14px 16px 10px',
        background: `linear-gradient(180deg,
          rgba(124,192,232,0.10) 0%,
          rgba(74,122,160,0.05) 60%,
          transparent 100%)`,
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· погода · {place}</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
          <span style={{
            font: '300 52px/0.9 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.02em', fontVariantNumeric: 'tabular-nums',
          }}>{now.t}°</span>
          <Icon cond={now.cond} size={26} />
          <div style={{ flex: 1 }} />
          <div style={{ textAlign: 'right' }}>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>відч. як</div>
            <div style={{ font: '500 14px/1 var(--font-display)', color: 'var(--ink-strong)', marginTop: 2 }}>0°</div>
          </div>
        </div>
        <div style={{
          font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
          marginTop: 6, textWrap: 'pretty',
        }}>дощ переходить у мокрий сніг до 22:00. парасоля в передпокої.</div>
      </div>
      {/* hourly */}
      <div style={{
        padding: '12px 12px 8px',
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 8,
        }}>· наступні 24 год</div>
        <div style={{
          display: 'flex', overflow: 'auto', gap: 4, scrollbarWidth: 'none',
        }}>
          {h.map((hh, i) => (
            <div key={i} style={{
              flexShrink: 0, width: 36,
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
              padding: '6px 0', borderRadius: 8,
              background: i === 0 ? 'rgba(244,175,37,0.08)' : 'transparent',
              border: `0.5px solid ${i === 0 ? 'var(--gold-soft)' : 'transparent'}`,
            }}>
              <span className="mono" style={{
                fontSize: 8.5, color: i === 0 ? 'var(--gold)' : 'var(--ink-tertiary)',
                fontVariantNumeric: 'tabular-nums',
              }}>{i === 0 ? 'зараз' : `${hh.hr}`}</span>
              <Icon cond={hh.t > 5 ? 'sun' : hh.pop > 0.2 ? 'rain' : 'cloud'} size={14} />
              <span style={{
                font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)',
                fontVariantNumeric: 'tabular-nums',
              }}>{hh.t}°</span>
              {hh.pop > 0.15 && (
                <span className="mono" style={{
                  fontSize: 7.5, color: '#7CC0E8',
                }}>{Math.round(hh.pop * 100)}%</span>
              )}
            </div>
          ))}
        </div>
      </div>
      {/* 7-day */}
      <div style={{
        padding: '10px 14px 14px',
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 8,
        }}>· 7 днів</div>
        {(() => {
          const allMin = Math.min(...d.map(x => x.lo));
          const allMax = Math.max(...d.map(x => x.hi));
          const rng = allMax - allMin || 1;
          return d.map((day, i) => (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '36px 22px 26px 1fr 26px',
              gap: 8, alignItems: 'center', padding: '5px 0',
            }}>
              <span className="mono" style={{
                fontSize: 10, color: i === 0 ? 'var(--gold)' : 'var(--ink-secondary)',
                letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: i === 0 ? 600 : 400,
              }}>{day.day}</span>
              <Icon cond={day.cond} size={14} />
              <span style={{
                font: '400 11px/1 var(--font-display)', color: 'var(--ink-tertiary)',
                fontVariantNumeric: 'tabular-nums', textAlign: 'right',
              }}>{day.lo}°</span>
              {/* temp range bar */}
              <div style={{
                position: 'relative', height: 4, background: 'rgba(255,230,180,0.06)', borderRadius: 2,
              }}>
                <div style={{
                  position: 'absolute',
                  left: `${(day.lo - allMin) / rng * 100}%`,
                  width: `${(day.hi - day.lo) / rng * 100}%`,
                  top: 0, height: '100%', borderRadius: 2,
                  background: 'linear-gradient(to right, #7CC0E8, var(--gold), var(--coral))',
                }} />
              </div>
              <span style={{
                font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)',
                fontVariantNumeric: 'tabular-nums',
              }}>{day.hi}°</span>
            </div>
          ));
        })()}
      </div>
      {/* radar mini */}
      <div style={{
        padding: '10px 14px 14px',
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <div style={{
          height: 110, borderRadius: 10, overflow: 'hidden',
          position: 'relative',
          background: 'radial-gradient(ellipse at 50% 50%, #0a1018, #050608 70%)',
        }}>
          {/* grid */}
          <svg viewBox="0 0 300 110" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
            {[20, 50, 80].map(y => (
              <line key={y} x1="0" y1={y} x2="300" y2={y} stroke="rgba(244,175,37,0.06)" strokeWidth="0.5" />
            ))}
            {[60, 120, 180, 240].map(x => (
              <line key={x} x1={x} y1="0" x2={x} y2="110" stroke="rgba(244,175,37,0.06)" strokeWidth="0.5" />
            ))}
            {/* precipitation blobs — coming toward city dot */}
            <ellipse cx="220" cy="35" rx="46" ry="18" fill="#7CC0E8" opacity="0.32" />
            <ellipse cx="200" cy="48" rx="34" ry="14" fill="#5A9EBC" opacity="0.5" />
            <ellipse cx="170" cy="42" rx="20" ry="10" fill="#3A7090" opacity="0.6" />
            {/* city marker */}
            <circle cx="100" cy="55" r="4" fill="var(--gold)" />
            <circle cx="100" cy="55" r="9" fill="none" stroke="var(--gold)" strokeWidth="0.6" opacity="0.5">
              <animate attributeName="r" values="6;12;6" dur="2.4s" repeatCount="indefinite" />
              <animate attributeName="opacity" values="0.5;0;0.5" dur="2.4s" repeatCount="indefinite" />
            </circle>
            <text x="100" y="74" textAnchor="middle"
                  style={{ font: '400 8px var(--font-mono)', fill: 'var(--gold)', letterSpacing: '0.1em' }}>lhasa</text>
            {/* arrow showing movement */}
            <path d="M 220 35 Q 160 50 110 55" stroke="rgba(124,192,232,0.6)" strokeWidth="1.2"
                  fill="none" strokeDasharray="3 3" />
          </svg>
          <div className="mono" style={{
            position: 'absolute', top: 6, left: 8,
            fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          }}>· радар · опади 30 хв ·</div>
          <div className="mono" style={{
            position: 'absolute', bottom: 6, right: 8,
            fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.1em',
          }}>фронт за 22 хв
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── 6. Stock / Crypto card with sparkline ───────────────────────────────────
function StockCard({ symbol = 'BTC', name = 'bitcoin', price = 64218, change = -1.84, period = '24h', points }) {
  const up = change >= 0;
  const pts = points || Array.from({ length: 80 }, (_, i) => {
    const t = i / 79;
    return 0.5 + Math.sin(t * 7) * 0.18 + Math.sin(t * 22) * 0.07 + (up ? t * 0.2 : -t * 0.25);
  });
  const lo = Math.min(...pts), hi = Math.max(...pts), rng = hi - lo || 1;
  const W = 320, H = 80;
  const xs = (i) => (i / (pts.length - 1)) * W;
  const ys = (v) => H - ((v - lo) / rng) * H;
  const color = up ? '#7CA982' : '#FF8B6E';

  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· {symbol} · {name}</div>
          <div style={{ font: '300 28px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.015em', fontVariantNumeric: 'tabular-nums' }}>
            ${price.toLocaleString()}
          </div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            padding: '4px 8px', borderRadius: 999,
            background: up ? 'rgba(124,169,130,0.12)' : 'rgba(255,139,110,0.12)',
            color, font: '500 11px/1 var(--font-display)',
            fontVariantNumeric: 'tabular-nums',
          }}>
            {up ? '↑' : '↓'} {Math.abs(change).toFixed(2)}%
          </div>
          <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 4, letterSpacing: '0.04em' }}>
            {period}
          </div>
        </div>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 80 }}>
        <defs>
          <linearGradient id={`stock-${symbol}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity="0.3" />
            <stop offset="100%" stopColor={color} stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={`M ${xs(0)} ${ys(pts[0])} ${pts.map((p, i) => `L ${xs(i)} ${ys(p)}`).join(' ')} L ${W} ${H} L 0 ${H} Z`}
              fill={`url(#stock-${symbol})`} />
        <path d={`M ${xs(0)} ${ys(pts[0])} ${pts.map((p, i) => `L ${xs(i)} ${ys(p)}`).join(' ')}`}
              fill="none" stroke={color} strokeWidth="1.4"
              style={{ filter: `drop-shadow(0 0 4px ${color}88)` }} />
        {/* endpoint */}
        <circle cx={xs(pts.length-1)} cy={ys(pts[pts.length-1])} r="3" fill={color} />
        <circle cx={xs(pts.length-1)} cy={ys(pts[pts.length-1])} r="6" fill="none" stroke={color} strokeOpacity="0.4">
          <animate attributeName="r" values="4;9;4" dur="2s" repeatCount="indefinite" />
          <animate attributeName="stroke-opacity" values="0.4;0;0.4" dur="2s" repeatCount="indefinite" />
        </circle>
      </svg>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)',
        gap: 8, marginTop: 8, paddingTop: 8,
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        {[
          ['vol 24h', '38.4b'],
          ['high',  '65.2k'],
          ['low',   '63.1k'],
          ['mkt',   '1.27t'],
        ].map(([k, v]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>{k}</span>
            <span style={{ font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)', fontVariantNumeric: 'tabular-nums' }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 7. Sports score / fixture ───────────────────────────────────────────────
function SportsScore({ league = 'champions league · 1/2 фінал', home = 'real madrid', away = 'man city',
                      homeScore = 2, awayScore = 1, minute = '78', live = true }) {
  return (
    <div className="glass fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}>· {league}</span>
        {live && (
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: '3px 8px', borderRadius: 999,
            background: 'rgba(255,139,110,0.12)',
            border: '0.5px solid rgba(255,139,110,0.3)',
          }}>
            <span style={{ width: 5, height: 5, borderRadius: '50%', background: '#FF8B6E',
              animation: 'phantom-pulse-glow 1.2s ease-in-out infinite' }} />
            <span className="mono" style={{ fontSize: 8.5, color: 'var(--coral)', letterSpacing: '0.1em' }}>LIVE · {minute}'</span>
          </span>
        )}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr auto 1fr', alignItems: 'center', gap: 12 }}>
        {/* home */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
          <div style={{
            width: 44, height: 44, borderRadius: '50%',
            background: 'linear-gradient(160deg, #FEBE10, #DDA20A)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '700 14px/1 var(--font-display)', color: '#fff',
            boxShadow: '0 4px 10px rgba(0,0,0,0.3)',
          }}>RM</div>
          <span style={{ font: '500 11px/1.2 var(--font-display)', color: 'var(--ink-strong)', textAlign: 'center' }}>{home}</span>
        </div>
        {/* score */}
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{
            font: '300 42px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.02em', fontVariantNumeric: 'tabular-nums',
          }}>{homeScore}</span>
          <span style={{ font: '300 18px/1 var(--font-display)', color: 'var(--ink-tertiary)' }}>:</span>
          <span style={{
            font: '300 42px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.02em', fontVariantNumeric: 'tabular-nums',
          }}>{awayScore}</span>
        </div>
        {/* away */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
          <div style={{
            width: 44, height: 44, borderRadius: '50%',
            background: 'linear-gradient(160deg, #6CABDD, #1C5391)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '700 14px/1 var(--font-display)', color: '#fff',
            boxShadow: '0 4px 10px rgba(0,0,0,0.3)',
          }}>MC</div>
          <span style={{ font: '500 11px/1.2 var(--font-display)', color: 'var(--ink-strong)', textAlign: 'center' }}>{away}</span>
        </div>
      </div>
      {/* possession / shots / xG */}
      <div style={{
        marginTop: 14, paddingTop: 12,
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', flexDirection: 'column', gap: 6,
      }}>
        {[
          { k: 'volody. (62), bel. (74)', l: 'голи', shape: 'goals' },
          { k: 'володіння', l: '63% — 37%', shape: 'bar', a: 0.63 },
          { k: 'удари', l: '14 — 9', shape: 'bar', a: 0.61 },
          { k: 'xG', l: '2.4 — 1.1', shape: 'bar', a: 0.69 },
        ].map((row, i) => (
          row.shape === 'bar' ? (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '70px 1fr 70px', gap: 8, alignItems: 'center' }}>
              <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em', textAlign: 'right' }}>{row.l.split(' — ')[0]}</span>
              <div style={{ height: 3, background: 'rgba(255,139,110,0.15)', borderRadius: 2, position: 'relative' }}>
                <div style={{ width: `${row.a*100}%`, height: '100%',
                  background: '#FEBE10', borderRadius: 2 }} />
              </div>
              <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>{row.l.split(' — ')[1]}</span>
            </div>
          ) : (
            <div key={i} style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>· {row.shape}</span>
              <span className="mono" style={{ fontSize: 9.5, color: 'var(--ink) ' }}>{row.k}</span>
            </div>
          )
        ))}
      </div>
    </div>
  );
}

// ─── 8. Citation Cluster (multi-source consensus card) ───────────────────────
function CitationCluster({ title, sources, consensus }) {
  const ss = sources || [
    { id: 1, name: 'reuters',       date: '14 травня · 16:02', kind: 'news' },
    { id: 2, name: 'nasa earth obs', date: '14 травня · 18:11', kind: 'science' },
    { id: 3, name: 'meteorologica',  date: '13 травня',         kind: 'forecast' },
    { id: 4, name: 'twitter · @lhasa_wx', date: '14 травня · 17:30', kind: 'social' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 8,
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        <svg width="10" height="10" viewBox="0 0 10 10">
          <circle cx="5" cy="5" r="4" fill="none" stroke="currentColor" strokeWidth="0.8"/>
          <circle cx="5" cy="5" r="1.5" fill="currentColor"/>
        </svg>
        · 4 джерела · phantom звірив
      </div>
      <div style={{
        font: '500 13px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        textWrap: 'balance', marginBottom: 10,
      }}>
        {title || 'три з чотирьох джерел підтверджують: сніг у lhasa нетиповий для травня з 1958 року'}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        {ss.map((s) => {
          const color = { news: '#F4AF25', science: '#7CA982', forecast: '#7CC0E8', social: '#B49AD4' }[s.kind] || '#F4AF25';
          return (
            <div key={s.id} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '6px 8px', borderRadius: 8,
              background: `${color}0E`,
              borderLeft: `2px solid ${color}`,
            }}>
              <span style={{
                width: 18, height: 18, borderRadius: 4,
                background: `linear-gradient(135deg, ${color}, ${color}44)`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '600 9px/1 var(--font-display)', color: '#fff', flexShrink: 0,
              }}>{s.name[0].toUpperCase()}</span>
              <span style={{ font: '500 11px/1.2 var(--font-body)', color: 'var(--ink-strong)', flex: 1, minWidth: 0,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.name}</span>
              <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', flexShrink: 0 }}>{s.date}</span>
            </div>
          );
        })}
      </div>
      {consensus !== false && (
        <div style={{
          marginTop: 10, padding: '8px 10px', borderRadius: 8,
          background: 'rgba(124,169,130,0.08)',
          border: '0.5px solid rgba(124,169,130,0.25)',
          font: '400 11px/1.5 var(--font-body)', color: 'var(--sage)', textWrap: 'pretty',
        }}>
          <span className="mono" style={{ fontSize: 8.5, letterSpacing: '0.14em', textTransform: 'uppercase',
            display: 'block', marginBottom: 3, color: '#9DCC9F' }}>· консенсус 3 з 4</span>
          ймовірність факту: <b style={{ color: '#B5DFB7' }}>87%</b> · одне джерело (twitter) непідтверджене
        </div>
      )}
    </div>
  );
}

// ─── 9. Translation card ─────────────────────────────────────────────────────
function TranslationCard({ src = 'en → uk', original, translated, alternatives, note }) {
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 10,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span>· переклад · {src}</span>
        <span style={{ color: 'var(--ink-tertiary)' }}>· phantom · local</span>
      </div>
      <div style={{
        font: '300 14px/1.5 var(--font-display)', color: 'var(--ink-secondary)',
        letterSpacing: '0', marginBottom: 4, textWrap: 'pretty', fontStyle: 'italic',
      }}>
        {original || '«time is the substance from which I am made. Time is a river which carries me along, but I am the river.»'}
      </div>
      <div style={{
        height: 0.5, background: 'var(--glass-edge)', margin: '10px 0',
      }} />
      <div style={{
        font: '500 14px/1.5 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', textWrap: 'pretty',
      }}>
        {translated || '«час — речовина, з якої я зроблений. час — річка, що несе мене, але я і є та річка»'}
      </div>
      {(alternatives || ['«час — це матерія, що мене створює…»', '«час — субстанція мого буття…»']).length > 0 && (
        <div style={{ marginTop: 10, paddingTop: 10, borderTop: '0.5px solid var(--glass-edge)' }}>
          <div className="mono" style={{
            fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
            textTransform: 'uppercase', marginBottom: 6,
          }}>· альтернативи</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            {(alternatives || ['«час — це матерія, що мене створює…»', '«час — субстанція мого буття…»']).map((a, i) => (
              <div key={i} style={{
                font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-secondary)', fontStyle: 'italic',
                textWrap: 'pretty', paddingLeft: 8, borderLeft: '1.5px solid var(--ink-faint)',
              }}>{a}</div>
            ))}
          </div>
        </div>
      )}
      {note && (
        <div style={{
          marginTop: 10, padding: '6px 8px', borderRadius: 6,
          background: 'rgba(244,175,37,0.06)',
          font: '400 10px/1.4 var(--font-body)', color: 'var(--gold-soft)', textWrap: 'pretty',
        }}>
          <span className="mono" style={{ fontSize: 8, letterSpacing: '0.12em' }}>· borges</span> · «новий рефрен» з нарисів, 1944
        </div>
      )}
    </div>
  );
}

// ─── 10. Definition / Word card ──────────────────────────────────────────────
function DefinitionCard({ word = 'φάντασμα', translit = 'phántasma', lang = 'грецька · ст.',
                          defs, etymology }) {
  const ds = defs || [
    { pos: 'n.', text: 'образ, видіння; те, що з\'являється — і одночасно те, що бачать.' },
    { pos: 'n.', text: 'привид; нематеріальна присутність.' },
    { pos: 'n.', text: 'фантазія, уявне — на відміну від існуючого.' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '14px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 8,
      }}>· {lang}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 6 }}>
        <span style={{
          font: '500 24px/1 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.005em',
        }}>{word}</span>
        <span className="mono" style={{ fontSize: 11, color: 'var(--ink-tertiary)' }}>· {translit}</span>
        <button style={{
          appearance: 'none', border: 'none', background: 'transparent',
          marginLeft: 'auto', cursor: 'pointer', color: 'var(--gold-soft)',
          display: 'flex', alignItems: 'center', gap: 4,
          font: '400 10px/1 var(--font-body)', letterSpacing: '0.04em',
        }}>
          <svg width="12" height="12" viewBox="0 0 12 12">
            <path d="M3 4v4l3 2V2z" fill="currentColor"/>
            <path d="M8 4 a3 3 0 010 4" stroke="currentColor" strokeWidth="0.8" fill="none"/>
          </svg>
          вимова
        </button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 10 }}>
        {ds.map((d, i) => (
          <div key={i} style={{ display: 'flex', gap: 8 }}>
            <span style={{
              font: '300 10px/1.4 var(--font-mono)', color: 'var(--gold-soft)',
              fontStyle: 'italic', flexShrink: 0, minWidth: 18,
            }}>{i+1}. {d.pos}</span>
            <span style={{
              font: '400 12px/1.5 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty', flex: 1,
            }}>{d.text}</span>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 12, paddingTop: 10,
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 5 }}>· етимологія</div>
        <div style={{ font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)', textWrap: 'pretty' }}>
          {etymology || 'від φαίνω — «з\'являтися, ставати видимим». корінь bha- · той самий що в латин. fari «говорити», звідки нашe «фабула», «банальний», «профан». все, що виходить з тіні у світло — phántasma.'}
        </div>
      </div>
    </div>
  );
}

// ─── 11. Quote card (literary pull) ──────────────────────────────────────────
function QuoteCard({ text, author = 'borges', work = 'нові розслідування · 1952', kicker = 'phantom · знайшов у читанні' }) {
  return (
    <div className="glass fade-in" style={{
      padding: '18px 18px', margin: '6px 0', borderRadius: 16,
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={{
        position: 'absolute', top: -10, left: -4,
        font: '300 78px/1 Georgia, serif',
        color: 'var(--gold-faint)', userSelect: 'none',
      }}>«</div>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 14, position: 'relative',
      }}>· {kicker}</div>
      <div style={{
        font: '300 16px/1.55 Georgia, serif', color: 'var(--ink-strong)',
        textWrap: 'pretty', marginBottom: 14, fontStyle: 'italic',
        position: 'relative', letterSpacing: '0.005em',
      }}>
        {text || 'якщо я кожну ніч пишу той самий сон, це означає, що я не пишу — я переписую. час, як і memory, — лиш ілюзія повторення.'}
      </div>
      <div style={{
        display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 8,
      }}>
        <div style={{ height: 0.5, flex: 1, background: 'var(--glass-edge)' }} />
        <span style={{
          font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '0.01em',
        }}>— {author}</span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>· {work}</span>
      </div>
    </div>
  );
}

// keyframes
if (typeof document !== 'undefined' && !document.getElementById('rich-info-style')) {
  const s = document.createElement('style');
  s.id = 'rich-info-style';
  s.textContent = `
    @keyframes phantom-breaking-sweep { 0% { transform: translateX(-100%); } 100% { transform: translateX(200%); } }
  `;
  document.head.appendChild(s);
}

Object.assign(window, {
  NewsHero, NewsDigest, BreakingNews, NewsWithVideo,
  WeatherRich, StockCard, SportsScore, CitationCluster,
  TranslationCard, DefinitionCard, QuoteCard,
});
