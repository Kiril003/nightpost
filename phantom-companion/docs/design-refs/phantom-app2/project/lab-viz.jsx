/* lab-viz.jsx — advanced data viz chat cards:
   SankeyFlow, NetworkGraph (force-directed feel),
   YearHeatmap (contribution-style), Candlestick (financial),
   RadarChart, ECGStrip, FunnelChart. */

// ─── 1. Sankey Flow Diagram (money / energy / data) ──────────────────────────
function SankeyFlow({ title = 'твій трафік уваги · сьогодні', kicker = '· потік',
                      sources, targets, flows }) {
  const ss = sources || [
    { id: 'phantom', label: 'phantom', value: 240, color: '#F4AF25' },
    { id: 'olya',    label: 'olya',    value: 92,  color: '#FF8B6E' },
    { id: 'work',    label: 'github',  value: 120, color: '#7CC0E8' },
    { id: 'feed',    label: 'feed',    value: 38,  color: '#7E5DA4' },
  ];
  const ts = targets || [
    { id: 'focus', label: 'фокус',     color: '#7CA982' },
    { id: 'talk',  label: 'розмова',   color: '#F4AF25' },
    { id: 'skim',  label: 'скрол',     color: '#A7967D' },
    { id: 'rest',  label: 'спокій',    color: '#7E5DA4' },
  ];
  const fs = flows || [
    { s: 'phantom', t: 'talk',  v: 120 },
    { s: 'phantom', t: 'focus', v: 80 },
    { s: 'phantom', t: 'rest',  v: 40 },
    { s: 'olya',    t: 'talk',  v: 70 },
    { s: 'olya',    t: 'rest',  v: 22 },
    { s: 'work',    t: 'focus', v: 100 },
    { s: 'work',    t: 'skim',  v: 20 },
    { s: 'feed',    t: 'skim',  v: 38 },
  ];
  const total = ss.reduce((s, x) => s + x.value, 0);
  // build layout
  const W = 320, H = 200, gapY = 6;
  let yL = 0;
  const lefts = ss.map((s) => {
    const h = (s.value / total) * (H - (ss.length - 1) * gapY);
    const y = yL; yL += h + gapY;
    return { ...s, y, h };
  });
  // target totals
  const tTotals = {};
  fs.forEach((f) => { tTotals[f.t] = (tTotals[f.t] || 0) + f.v; });
  let yR = 0;
  const rights = ts.map((t) => {
    const h = ((tTotals[t.id] || 0) / total) * (H - (ts.length - 1) * gapY);
    const y = yR; yR += h + gapY;
    return { ...t, y, h };
  });
  // for each flow, build a band
  const bands = [];
  const used = { L: {}, R: {} };
  fs.forEach((f) => {
    const L = lefts.find((x) => x.id === f.s);
    const R = rights.find((x) => x.id === f.t);
    if (!L || !R) return;
    const bh = (f.v / total) * (H - (ss.length - 1) * gapY);
    const ly = L.y + (used.L[f.s] || 0);
    const ry = R.y + (used.R[f.t] || 0);
    used.L[f.s] = (used.L[f.s] || 0) + bh;
    used.R[f.t] = (used.R[f.t] || 0) + bh;
    bands.push({ ly, ry, bh, color: L.color, value: f.v });
  });
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>{kicker} · {total} хв</div>
      <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 10 }}>{title}</div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 200, display: 'block' }}>
        <defs>
          {lefts.map((L) => (
            <linearGradient key={L.id} id={`sk-${L.id}`} x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor={L.color} stopOpacity="0.5" />
              <stop offset="100%" stopColor={L.color} stopOpacity="0.2" />
            </linearGradient>
          ))}
        </defs>
        {/* flow bands */}
        {bands.map((b, i) => {
          const x0 = 70, x1 = W - 70;
          const m0 = b.ly + b.bh/2, m1 = b.ry + b.bh/2;
          const cP1x = (x0 + x1) / 2;
          const top  = `M ${x0} ${b.ly} C ${cP1x} ${b.ly}, ${cP1x} ${b.ry}, ${x1} ${b.ry}`;
          const bot  = `L ${x1} ${b.ry + b.bh} C ${cP1x} ${b.ry + b.bh}, ${cP1x} ${b.ly + b.bh}, ${x0} ${b.ly + b.bh} Z`;
          return (
            <path key={i} d={`${top} ${bot}`}
                  fill={`url(#sk-${ss[bands.indexOf(b) >= 0 ? 0 : 0].id})`}
                  fillOpacity={0.4}
                  style={{ filter: 'drop-shadow(0 0 4px rgba(244,175,37,0.1))', mixBlendMode: 'screen' }}>
              <title>{b.value} хв</title>
            </path>
          );
        })}
        {/* nodes left */}
        {lefts.map((L) => (
          <g key={L.id}>
            <rect x="60" y={L.y} width="10" height={L.h} fill={L.color}
                  rx="2" style={{ filter: `drop-shadow(0 0 4px ${L.color}88)` }} />
            <text x="56" y={L.y + L.h/2 + 3} textAnchor="end"
                  style={{ font: '500 9px var(--font-display)', fill: 'var(--ink-strong)' }}>
              {L.label}
            </text>
            <text x="56" y={L.y + L.h/2 + 13} textAnchor="end"
                  style={{ font: '300 8px var(--font-mono)', fill: 'var(--ink-tertiary)' }}>
              {L.value}хв
            </text>
          </g>
        ))}
        {/* nodes right */}
        {rights.map((R) => R.h > 0 && (
          <g key={R.id}>
            <rect x={W - 70} y={R.y} width="10" height={R.h} fill={R.color}
                  rx="2" style={{ filter: `drop-shadow(0 0 4px ${R.color}88)` }} />
            <text x={W - 56} y={R.y + R.h/2 + 3} textAnchor="start"
                  style={{ font: '500 9px var(--font-display)', fill: 'var(--ink-strong)' }}>
              {R.label}
            </text>
            <text x={W - 56} y={R.y + R.h/2 + 13} textAnchor="start"
                  style={{ font: '300 8px var(--font-mono)', fill: 'var(--ink-tertiary)' }}>
              {tTotals[R.id]}хв
            </text>
          </g>
        ))}
      </svg>
      <div className="mono" style={{
        marginTop: 4, fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
        textAlign: 'center',
      }}>· джерела уваги → що ти ними роботив ·</div>
    </div>
  );
}

// ─── 2. Network Graph (force-directed memory network) ────────────────────────
function NetworkGraph({ title = 'graph · з ким ти зв\'язаний цього тижня', nodes, edges }) {
  const ns = nodes || [
    { id: 'ти',     x: 0,    y: 0,    r: 26, c: '#F4AF25', main: true },
    { id: 'оля',    x: -1.0, y: -0.4, r: 18, c: '#FF8B6E' },
    { id: 'олег',   x: 0.9,  y: -0.7, r: 16, c: '#7CA982' },
    { id: 'богдан', x: 0.6,  y: 0.9,  r: 14, c: '#7CC0E8' },
    { id: 'аня',    x: -1.2, y: 0.7,  r: 12, c: '#B49AD4' },
    { id: 'мама',   x: -0.4, y: -1.3, r: 12, c: '#B49AD4' },
    { id: 'нік',    x: 1.4,  y: 0.2,  r: 10, c: '#A7967D' },
    { id: 'phantom-os', x: 0.1, y: 1.2, r: 16, c: '#F4AF25', node: 'system' },
  ];
  const es = edges || [
    { a: 'ти', b: 'оля',    w: 4, kind: 'strong' },
    { a: 'ти', b: 'олег',   w: 2, kind: 'med' },
    { a: 'ти', b: 'богдан', w: 2, kind: 'med' },
    { a: 'ти', b: 'аня',    w: 1, kind: 'weak' },
    { a: 'ти', b: 'мама',   w: 2, kind: 'med' },
    { a: 'ти', b: 'нік',    w: 1, kind: 'weak' },
    { a: 'ти', b: 'phantom-os', w: 4, kind: 'system' },
    { a: 'оля', b: 'олег',  w: 1, kind: 'weak' },
    { a: 'олег', b: 'богдан', w: 1, kind: 'weak' },
  ];
  // map to viewport
  const W = 320, H = 240, cx = W/2, cy = H/2, scale = 60;
  const pos = (n) => ({ x: cx + n.x * scale, y: cy + n.y * scale });
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '10px 14px 4px' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· мережа · {ns.length} вузлів · {es.length} зв'язків</div>
        <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
      </div>
      <div style={{
        height: 240, position: 'relative',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)',
        overflow: 'hidden',
      }}>
        <svg viewBox={`0 0 ${W} ${H}`} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* edges */}
          {es.map((e, i) => {
            const A = ns.find((n) => n.id === e.a);
            const B = ns.find((n) => n.id === e.b);
            const a = pos(A), b = pos(B);
            const color = e.kind === 'strong' ? '#F4AF25' :
                          e.kind === 'med' ? 'rgba(244,175,37,0.4)' :
                          e.kind === 'system' ? 'rgba(180,150,220,0.5)' :
                          'rgba(167,150,125,0.25)';
            return (
              <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
                    stroke={color} strokeWidth={e.w * 0.5}
                    strokeDasharray={e.kind === 'system' ? '3 3' : '0'}
                    style={{ filter: e.kind === 'strong' ? 'drop-shadow(0 0 3px var(--gold-glow))' : 'none' }} />
            );
          })}
          {/* nodes */}
          {ns.map((n) => {
            const p = pos(n);
            return (
              <g key={n.id} style={{ animation: n.main ? 'phantom-pulse-glow 2.4s ease-in-out infinite' : 'none' }}>
                <circle cx={p.x} cy={p.y} r={n.r + 6} fill={n.c} opacity="0.12" />
                <circle cx={p.x} cy={p.y} r={n.r}
                        fill={`url(#nn-${n.id})`}
                        stroke={n.c} strokeWidth="0.7"
                        style={{ filter: `drop-shadow(0 0 6px ${n.c}88)` }} />
                <text x={p.x} y={p.y + n.r + 12} textAnchor="middle"
                      style={{ font: `${n.main ? 600 : 500} 9.5px var(--font-display)`,
                        fill: n.main ? 'var(--gold)' : 'var(--ink-strong)' }}>
                  {n.id}
                </text>
                <defs>
                  <radialGradient id={`nn-${n.id}`}>
                    <stop offset="0%" stopColor={n.c} />
                    <stop offset="60%" stopColor={n.c} stopOpacity="0.5" />
                    <stop offset="100%" stopColor={n.c} stopOpacity="0.2" />
                  </radialGradient>
                </defs>
              </g>
            );
          })}
        </svg>
      </div>
      <div style={{ padding: '8px 14px', borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        {[
          { l: 'сильно', c: '#F4AF25' },
          { l: 'помірно', c: 'rgba(244,175,37,0.4)' },
          { l: 'слабко', c: 'rgba(167,150,125,0.25)' },
          { l: 'система', c: 'rgba(180,150,220,0.5)' },
        ].map((g) => (
          <div key={g.l} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <span style={{ width: 12, height: 1.5, background: g.c, borderRadius: 1 }} />
            <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>{g.l}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 3. Year Heatmap (GitHub-style contributions, intent: habit) ─────────────
function YearHeatmap({ title = 'присутність · 365 днів', kicker = '· phantom · трекає',
                       legend = ['немає', 'мало', 'добре', 'багато'], data }) {
  // deterministic pseudo data
  const cells = React.useMemo(() => {
    if (data) return data;
    return Array.from({ length: 52 * 7 }, (_, i) => {
      const wd = i % 7;
      const wk = Math.floor(i / 7);
      const s = Math.sin(wk * 0.18) * 0.5 + 0.5;
      const w = wd >= 5 ? 0.4 : 1;
      const noise = ((i * 7919) % 100) / 100;
      const v = Math.max(0, Math.min(4, Math.floor((s * w + noise * 0.5) * 4.5)));
      return v;
    });
  }, [data]);
  const months = ['с', 'л', 'б', 'к', 'т', 'ч', 'л', 'с', 'в', 'ж', 'л', 'г'];
  const days = ['пн', '', 'ср', '', 'пт', '', 'нд'];
  const color = (v) => ['rgba(255,230,180,0.04)',
                        'rgba(244,175,37,0.15)',
                        'rgba(244,175,37,0.4)',
                        'rgba(244,175,37,0.7)',
                        'var(--gold)'][v];
  const totalActive = cells.filter((v) => v > 0).length;
  const streak = (() => {
    let s = 0, max = 0;
    cells.slice().reverse().forEach((v) => {
      if (v > 0) { s++; if (s > max) max = s; } else s = 0;
    });
    return max;
  })();
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>{kicker}</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <span style={{ font: '500 17px/1 var(--font-display)', color: 'var(--gold)',
            fontVariantNumeric: 'tabular-nums' }}>{streak}</span>
          <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)',
            letterSpacing: '0.06em', display: 'block', marginTop: 2 }}>днів поспіль</span>
        </div>
      </div>
      {/* month labels */}
      <div style={{
        display: 'grid', gridTemplateColumns: '18px repeat(52, minmax(0, 1fr))',
        gap: 1.5, marginBottom: 3, minWidth: 0,
      }}>
        <div />
        {Array.from({ length: 52 }).map((_, w) => (
          w % 5 === 0 ? (
            <div key={w} className="mono" style={{
              fontSize: 7, color: 'var(--ink-tertiary)', textAlign: 'left',
              gridColumn: `${w + 2} / span 5`,
            }}>
              {months[Math.floor(w / 4.33) % 12]}
            </div>
          ) : null
        )).filter(Boolean)}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '18px 1fr', gap: 2, minWidth: 0 }}>
        <div style={{ display: 'grid', gridTemplateRows: 'repeat(7, 1fr)', gap: 1.5 }}>
          {days.map((d, i) => (
            <span key={i} className="mono" style={{ fontSize: 6.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
              display: 'flex', alignItems: 'center', justifyContent: 'flex-end', paddingRight: 3 }}>
              {d}
            </span>
          ))}
        </div>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(52, minmax(0, 1fr))',
          gridTemplateRows: 'repeat(7, minmax(0, 1fr))',
          gap: 1.5, gridAutoFlow: 'column',
          minWidth: 0, aspectRatio: '52 / 7',
        }}>
          {cells.map((v, i) => (
            <div key={i} style={{
              borderRadius: 1.5, minWidth: 0,
              background: color(v),
              boxShadow: v > 2 ? `0 0 3px var(--gold-glow)` : 'none',
            }} title={`день ${i}: ${legend[Math.min(v, 3)]}`} />
          ))}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
          · {totalActive} активних днів · {(totalActive / cells.length * 100).toFixed(1)}%
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
          <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)' }}>−</span>
          {[0,1,2,3,4].map((v) => (
            <span key={v} style={{
              width: 7, height: 7, borderRadius: 1.5, background: color(v),
            }} />
          ))}
          <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)' }}>+</span>
        </div>
      </div>
    </div>
  );
}

// ─── 4. Candlestick chart (financial) ────────────────────────────────────────
function Candlestick({ symbol = 'BTC/USD', period = '1H', candles }) {
  const cs = candles || Array.from({ length: 32 }, (_, i) => {
    const t = i / 32;
    const base = 64000 + Math.sin(t * 6) * 800 + Math.sin(t * 16) * 240 + i * 18;
    const o = base + (Math.sin(i * 0.7) * 200);
    const c = base + (Math.sin(i * 0.9 + 1.2) * 240);
    const h = Math.max(o, c) + Math.abs(Math.sin(i * 1.3)) * 180;
    const l = Math.min(o, c) - Math.abs(Math.sin(i * 1.5)) * 220;
    return { o, h, l, c };
  });
  const allH = Math.max(...cs.map((c) => c.h));
  const allL = Math.min(...cs.map((c) => c.l));
  const rng = allH - allL;
  const W = 320, H = 130, P = 4;
  const cw = (W - P*2) / cs.length;
  const yp = (v) => H - P - ((v - allL) / rng) * (H - P*2);
  const last = cs[cs.length - 1];
  const lastClose = last.c;
  const firstOpen = cs[0].o;
  const change = ((lastClose - firstOpen) / firstOpen) * 100;
  const up = change >= 0;
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· {symbol} · candle {period}</div>
          <div style={{ font: '300 22px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.015em', fontVariantNumeric: 'tabular-nums' }}>
            ${lastClose.toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
          </div>
        </div>
        <span style={{
          padding: '4px 9px', borderRadius: 999,
          background: up ? 'rgba(124,169,130,0.12)' : 'rgba(255,139,110,0.12)',
          color: up ? '#9DCC9F' : '#FF8B6E',
          font: '500 11px/1 var(--font-display)', letterSpacing: '0.04em',
        }}>
          {up ? '↑' : '↓'} {Math.abs(change).toFixed(2)}%
        </span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 130 }}>
        {/* grid */}
        {[0.25, 0.5, 0.75].map((g, i) => (
          <line key={i} x1={P} y1={P + g * (H - P*2)} x2={W - P} y2={P + g * (H - P*2)}
                stroke="rgba(255,230,180,0.04)" strokeWidth="0.5" strokeDasharray="2 4" />
        ))}
        {cs.map((c, i) => {
          const x = P + i * cw + cw / 2;
          const isUp = c.c >= c.o;
          const top = yp(c.h), bot = yp(c.l);
          const bodyTop = yp(Math.max(c.o, c.c));
          const bodyBot = yp(Math.min(c.o, c.c));
          const color = isUp ? '#9DCC9F' : '#FF8B6E';
          return (
            <g key={i}>
              <line x1={x} y1={top} x2={x} y2={bot} stroke={color} strokeWidth="0.7" />
              <rect x={x - cw*0.35} y={bodyTop}
                    width={cw*0.7} height={Math.max(1, bodyBot - bodyTop)}
                    fill={color} opacity={isUp ? 0.9 : 0.85}
                    style={{ filter: i === cs.length-1 ? `drop-shadow(0 0 4px ${color}88)` : 'none' }} />
            </g>
          );
        })}
        {/* current price marker */}
        <line x1={P} y1={yp(lastClose)} x2={W - P} y2={yp(lastClose)}
              stroke={up ? '#9DCC9F' : '#FF8B6E'} strokeWidth="0.5" strokeDasharray="3 3"
              opacity="0.6" />
      </svg>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginTop: 8,
        paddingTop: 8, borderTop: '0.5px solid var(--glass-edge)',
      }}>
        {[
          ['O', cs[0].o.toFixed(0)],
          ['H', allH.toFixed(0)],
          ['L', allL.toFixed(0)],
          ['C', lastClose.toFixed(0)],
        ].map(([k, v]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em' }}>{k}</span>
            <span style={{ font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)',
              fontVariantNumeric: 'tabular-nums' }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 5. Radar Chart (multi-axis attributes) ──────────────────────────────────
function RadarChart({ title = 'присутність · цей тиждень', axes, current, target }) {
  const as = axes || ['присутність', 'фокус', 'сон', 'рух', 'звʼязок', 'тиша'];
  const cur = current || [0.78, 0.62, 0.85, 0.40, 0.70, 0.55];
  const tg  = target  || [0.80, 0.80, 0.85, 0.65, 0.65, 0.70];
  const cx = 160, cy = 130, R = 90;
  const angle = (i) => (i / as.length) * Math.PI * 2 - Math.PI / 2;
  const pt = (i, r) => ({ x: cx + Math.cos(angle(i)) * R * r, y: cy + Math.sin(angle(i)) * R * r });
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· radar · 6 вимірів</div>
      <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 6 }}>{title}</div>
      <svg viewBox="0 0 320 260" style={{ width: '100%', height: 260, display: 'block' }}>
        {/* concentric */}
        {[0.25, 0.5, 0.75, 1].map((r, i) => (
          <polygon key={i}
            points={as.map((_, j) => `${pt(j, r).x},${pt(j, r).y}`).join(' ')}
            fill="none" stroke="rgba(255,230,180,0.08)" strokeWidth="0.5" />
        ))}
        {/* axis lines */}
        {as.map((a, i) => {
          const p = pt(i, 1);
          return <line key={i} x1={cx} y1={cy} x2={p.x} y2={p.y}
            stroke="rgba(255,230,180,0.06)" strokeWidth="0.5" />;
        })}
        {/* target polygon */}
        <polygon
          points={tg.map((v, i) => `${pt(i, v).x},${pt(i, v).y}`).join(' ')}
          fill="rgba(157,204,159,0.06)"
          stroke="rgba(157,204,159,0.4)" strokeWidth="0.8"
          strokeDasharray="3 3" />
        {/* current polygon */}
        <polygon
          points={cur.map((v, i) => `${pt(i, v).x},${pt(i, v).y}`).join(' ')}
          fill="rgba(244,175,37,0.18)"
          stroke="var(--gold)" strokeWidth="1.4"
          style={{ filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
        {/* dots */}
        {cur.map((v, i) => {
          const p = pt(i, v);
          return <circle key={i} cx={p.x} cy={p.y} r="2.5" fill="var(--gold)" />;
        })}
        {/* labels */}
        {as.map((a, i) => {
          const p = pt(i, 1.18);
          return (
            <g key={i}>
              <text x={p.x} y={p.y}
                    textAnchor={Math.abs(Math.cos(angle(i))) < 0.3 ? 'middle' : Math.cos(angle(i)) > 0 ? 'start' : 'end'}
                    dominantBaseline="middle"
                    style={{ font: '500 9.5px var(--font-display)', fill: 'var(--ink-strong)' }}>
                {a}
              </text>
              <text x={p.x} y={p.y + 11}
                    textAnchor={Math.abs(Math.cos(angle(i))) < 0.3 ? 'middle' : Math.cos(angle(i)) > 0 ? 'start' : 'end'}
                    dominantBaseline="middle"
                    style={{ font: '300 8px var(--font-mono)', fill: 'var(--gold-soft)', letterSpacing: '0.04em' }}>
                {(cur[i] * 100 | 0)}%
              </text>
            </g>
          );
        })}
      </svg>
      <div style={{ display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4 }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 12, height: 2, background: 'var(--gold)' }} />
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)' }}>· зараз</span>
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 12, height: 2, background: 'rgba(157,204,159,0.4)', borderTop: '0.5px dashed #9DCC9F' }} />
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)' }}>· ціль</span>
        </span>
      </div>
    </div>
  );
}

// ─── 6. ECG strip (continuous heart waveform) ────────────────────────────────
function ECGStrip({ bpm = 64, anomaly, kicker = '· серце · live · sensor radxa-bio' }) {
  const W = 320, H = 80;
  const pts = React.useMemo(() => {
    // synthetic ECG: P, Q, R, S, T per beat
    const beats = 6;
    const beatLen = W / beats;
    const arr = [];
    for (let x = 0; x <= W; x += 0.5) {
      const phase = (x % beatLen) / beatLen;
      let y = H * 0.55;
      // baseline noise
      y += Math.sin(x * 0.4) * 0.5;
      // P
      if (phase > 0.05 && phase < 0.18) y -= Math.sin((phase - 0.05) / 0.13 * Math.PI) * 6;
      // Q
      if (phase > 0.18 && phase < 0.22) y += Math.sin((phase - 0.18) / 0.04 * Math.PI) * 4;
      // R
      if (phase > 0.22 && phase < 0.28) y -= Math.sin((phase - 0.22) / 0.06 * Math.PI) * 32;
      // S
      if (phase > 0.28 && phase < 0.34) y += Math.sin((phase - 0.28) / 0.06 * Math.PI) * 14;
      // T
      if (phase > 0.4 && phase < 0.58) y -= Math.sin((phase - 0.4) / 0.18 * Math.PI) * 10;
      // anomaly mid screen
      if (anomaly && x > W * 0.55 && x < W * 0.6 && phase > 0.22 && phase < 0.28) {
        y -= 12;
      }
      arr.push([x, y]);
    }
    return arr;
  }, [anomaly]);
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
      borderColor: anomaly ? 'rgba(255,139,110,0.3)' : undefined,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
        <span className="mono" style={{
          fontSize: 9, color: anomaly ? 'var(--coral)' : 'var(--gold-soft)',
          letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>{kicker}</span>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
          <span style={{
            font: '300 22px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.015em', fontVariantNumeric: 'tabular-nums',
          }}>{bpm}</span>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.08em' }}>bpm</span>
        </div>
      </div>
      <div style={{
        position: 'relative',
        background: `
          radial-gradient(ellipse at 50% 50%, rgba(124,169,130,0.04), transparent 70%),
          repeating-linear-gradient(0deg, rgba(157,204,159,0.04) 0 1px, transparent 1px 8px),
          repeating-linear-gradient(90deg, rgba(157,204,159,0.04) 0 1px, transparent 1px 8px)
        `,
        borderRadius: 8, overflow: 'hidden',
      }}>
        <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 80, display: 'block' }}>
          <polyline
            points={pts.map(([x,y]) => `${x},${y}`).join(' ')}
            fill="none"
            stroke={anomaly ? '#FF8B6E' : '#9DCC9F'}
            strokeWidth="1.2"
            strokeLinejoin="round"
            style={{ filter: `drop-shadow(0 0 4px ${anomaly ? '#FF8B6E' : '#9DCC9F'}88)` }} />
          {/* scanning indicator at right */}
          <line x1={W - 1} y1="0" x2={W - 1} y2={H} stroke="var(--gold)" strokeWidth="1">
            <animate attributeName="x1" values={`0;${W}`} dur="4s" repeatCount="indefinite" />
            <animate attributeName="x2" values={`0;${W}`} dur="4s" repeatCount="indefinite" />
          </line>
        </svg>
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginTop: 8,
        paddingTop: 8, borderTop: '0.5px solid var(--glass-edge)',
      }}>
        {[
          ['HRV',  '48 мс'],
          ['SpO₂', '98%'],
          ['RR',   '12'],
          ['T°',   '36.6'],
        ].map(([k, v]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>{k}</span>
            <span style={{ font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)',
              fontVariantNumeric: 'tabular-nums' }}>{v}</span>
          </div>
        ))}
      </div>
      {anomaly && (
        <div style={{
          marginTop: 8, padding: '6px 8px', borderRadius: 6,
          background: 'rgba(255,139,110,0.08)',
          border: '0.5px solid rgba(255,139,110,0.25)',
          font: '400 10.5px/1.4 var(--font-body)', color: 'var(--coral)',
          textWrap: 'pretty',
        }}>
          <span className="mono" style={{ fontSize: 8, letterSpacing: '0.14em', textTransform: 'uppercase', marginRight: 5 }}>· аномалія</span>
          PVC (передчасне скорочення) о 19:32 · ймовірно кофеїн · якщо повториться &gt; 5/хв — попереджу
        </div>
      )}
    </div>
  );
}

// ─── 7. Funnel chart ─────────────────────────────────────────────────────────
function FunnelChart({ title = 'воронка тижня · 6 → 1', steps }) {
  const ss = steps || [
    { l: 'ідеї', v: 142, c: 'var(--ink)' },
    { l: 'записав', v: 38, c: '#A7967D' },
    { l: 'розробив', v: 14, c: '#7CC0E8' },
    { l: 'спробував', v: 4, c: 'var(--gold-soft)' },
    { l: 'спрацювало', v: 1, c: 'var(--gold)' },
  ];
  const max = Math.max(...ss.map((s) => s.v));
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· funnel</div>
      <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 12 }}>{title}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center' }}>
        {ss.map((s, i) => {
          const w = 50 + (s.v / max) * 50;
          const prevV = i > 0 ? ss[i-1].v : null;
          const conv = prevV ? (s.v / prevV * 100) : null;
          return (
            <React.Fragment key={i}>
              <div style={{
                width: `${w}%`, padding: '10px 14px',
                background: `${s.c === 'var(--gold)' ? 'rgba(244,175,37,0.25)' :
                              s.c === 'var(--gold-soft)' ? 'rgba(224,153,42,0.18)' :
                              s.c === '#7CC0E8' ? 'rgba(124,192,232,0.15)' :
                              s.c === '#A7967D' ? 'rgba(167,150,125,0.15)' :
                              'rgba(255,230,180,0.06)'}`,
                borderLeft: `2px solid ${s.c === 'var(--ink)' ? 'var(--ink-secondary)' : s.c}`,
                borderRadius: 6,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              }}>
                <span style={{ font: '500 11.5px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{s.l}</span>
                <span style={{
                  font: '500 12px/1 var(--font-display)',
                  color: s.c === 'var(--ink)' ? 'var(--ink-secondary)' : s.c,
                  fontVariantNumeric: 'tabular-nums',
                }}>{s.v}</span>
              </div>
              {conv !== null && (
                <span className="mono" style={{
                  fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.1em',
                  marginTop: -1, marginBottom: -1,
                }}>↓ {conv.toFixed(0)}%</span>
              )}
            </React.Fragment>
          );
        })}
      </div>
    </div>
  );
}

Object.assign(window, { SankeyFlow, NetworkGraph, YearHeatmap, Candlestick, RadarChart, ECGStrip, FunnelChart });
