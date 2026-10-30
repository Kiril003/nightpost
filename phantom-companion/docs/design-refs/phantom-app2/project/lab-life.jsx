/* lab-life.jsx — advanced life chat cards:
   ChessBoard (with eval bar + moves), WorldGlobe (3D-ish with pings),
   BillSplitter (receipt + friends), BoardingPass (with barcode),
   BookReader (long-form with annotations), PlantID (identification),
   SleepStagesLive (currently sleeping), MindMap (centered tree),
   StarMap (constellations overhead). */

// ─── 1. Chess Board with eval bar and move list ──────────────────────────────
function ChessBoard({ whiteToMove = false, evalScore = -0.4, moves, position }) {
  // simplified position — show a midgame
  const pos = position || {
    a1:'',  b1:'', c1:'', d1:'', e1:'', f1:'♖', g1:'♔', h1:'',
    a2:'♙', b2:'♙', c2:'', d2:'', e2:'',  f2:'♙', g2:'♙', h2:'♙',
    a3:'',  b3:'', c3:'♙', d3:'', e3:'♘', f3:'', g3:'',  h3:'',
    a4:'',  b4:'', c4:'', d4:'♙', e4:'', f4:'',  g4:'',  h4:'',
    a5:'',  b5:'', c5:'', d5:'♟', e5:'', f5:'♞',  g5:'',  h5:'',
    a6:'',  b6:'', c6:'♞', d6:'', e6:'', f6:'',  g6:'♟', h6:'',
    a7:'♟', b7:'♟', c7:'', d7:'', e7:'♟', f7:'♟', g7:'', h7:'♟',
    a8:'',  b8:'', c8:'♝', d8:'♛', e8:'', f8:'',  g8:'♚', h8:'',
  };
  const mvs = moves || [
    { n: 1, w: 'e4',  b: 'c5'  },
    { n: 2, w: 'Nf3', b: 'd6'  },
    { n: 3, w: 'd4',  b: 'cxd4'},
    { n: 4, w: 'Nxd4', b: 'Nf6'},
    { n: 5, w: 'Nc3', b: 'a6'  },
    { n: 6, w: 'Be3', b: 'e5'  },
    { n: 7, w: 'Nb3', b: 'Be7' },
    { n: 8, w: 'f3',  b: 'Be6' },
    { n: 9, w: 'Qd2', b: 'Nbd7'},
    { n: 10, w: 'O-O-O', b: '' },
  ];
  const files = ['a','b','c','d','e','f','g','h'];
  const ranks = [8,7,6,5,4,3,2,1];
  // eval bar height
  const evalPct = 50 - Math.tanh(evalScore / 4) * 48; // 0–100, 50 = even
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· партія · phantom × lichess</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>сицилійська · опен</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, justifyContent: 'flex-end' }}>
            <span style={{ width: 7, height: 7, borderRadius: '50%',
              background: whiteToMove ? '#fff' : '#1A140B',
              border: '0.5px solid var(--ink-secondary)' }} />
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)' }}>
              {whiteToMove ? 'білі' : 'чорні'} · хід
            </span>
          </div>
          <span style={{
            font: '500 12px/1 var(--font-display)',
            color: evalScore > 0 ? '#fff' : evalScore < 0 ? '#1A140B' : 'var(--ink-secondary)',
            background: evalScore > 0 ? 'rgba(255,255,255,0.12)' : evalScore < 0 ? 'rgba(0,0,0,0.35)' : 'transparent',
            padding: '3px 7px', borderRadius: 4, marginTop: 5, display: 'inline-block',
            fontVariantNumeric: 'tabular-nums',
          }}>{evalScore > 0 ? '+' : ''}{evalScore.toFixed(1)}</span>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        {/* eval bar */}
        <div style={{
          width: 8, position: 'relative',
          background: '#1A140B', borderRadius: 2,
          border: '0.5px solid var(--glass-edge)',
        }}>
          <div style={{
            position: 'absolute', left: 0, right: 0, top: 0,
            height: `${evalPct}%`,
            background: '#fff',
            transition: 'height 0.6s ease',
          }} />
          <div style={{
            position: 'absolute', left: -1, right: -1, top: '50%',
            height: 0, borderTop: '1px solid var(--gold-soft)',
          }} />
        </div>
        {/* board */}
        <div style={{
          aspectRatio: '1', flex: 1,
          display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)',
          gridTemplateRows: 'repeat(8, 1fr)',
          borderRadius: 6, overflow: 'hidden',
          border: '0.5px solid var(--glass-edge-strong)',
          boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
        }}>
          {ranks.flatMap((r) => files.map((f) => {
            const key = f + r;
            const idx = (files.indexOf(f) + r) % 2;
            const piece = pos[key];
            const isWhite = piece && '♙♘♗♖♕♔'.includes(piece);
            // highlight last move squares
            const isHighlight = key === 'd1' || key === 'c1'; // O-O-O
            return (
              <div key={key} style={{
                background: isHighlight ? 'rgba(244,175,37,0.4)' :
                            idx ? '#c9ad7b' : '#5c4128',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                position: 'relative',
                fontFamily: 'serif',
                color: isWhite ? '#fff' : '#1A140B',
                textShadow: isWhite ? '0 0 2px rgba(0,0,0,0.6)' : 'none',
                fontSize: 'clamp(14px, 4.5vw, 20px)',
                lineHeight: 1,
              }}>
                {piece}
                {f === 'a' && (
                  <span className="mono" style={{
                    position: 'absolute', left: 1.5, top: 1,
                    fontSize: 7, color: idx ? '#5c4128' : '#c9ad7b', opacity: 0.7,
                  }}>{r}</span>
                )}
                {r === 1 && (
                  <span className="mono" style={{
                    position: 'absolute', right: 1.5, bottom: 1,
                    fontSize: 7, color: idx ? '#5c4128' : '#c9ad7b', opacity: 0.7,
                  }}>{f}</span>
                )}
              </div>
            );
          }))}
        </div>
      </div>
      {/* move list */}
      <div style={{
        marginTop: 10, padding: '8px 10px', borderRadius: 8,
        background: 'rgba(0,0,0,0.15)',
        font: '400 10.5px/1.5 var(--font-mono)', color: 'var(--ink-secondary)',
        letterSpacing: '0.02em',
        display: 'flex', gap: 8, overflow: 'auto', scrollbarWidth: 'none',
      }}>
        {mvs.map((m) => (
          <span key={m.n} style={{ flexShrink: 0 }}>
            <span style={{ color: 'var(--ink-tertiary)' }}>{m.n}.</span>{' '}
            <span style={{ color: 'var(--ink-strong)' }}>{m.w}</span>{' '}
            {m.b && <span style={{ color: 'var(--ink)' }}>{m.b}</span>}
          </span>
        ))}
        <span style={{ color: 'var(--gold)', flexShrink: 0 }}>O-O-O</span>
      </div>
      {/* phantom note */}
      <div style={{
        marginTop: 8, padding: '6px 8px', borderRadius: 6,
        background: 'rgba(244,175,37,0.05)',
        font: '400 10.5px/1.4 var(--font-body)', color: 'var(--gold-soft)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, letterSpacing: '0.16em', textTransform: 'uppercase', marginRight: 5 }}>· stockfish · 28</span>
        нічия з невеликою перевагою чорних · g6 → b7 з 78% точністю · ти граєш на 4 хв за хід
      </div>
    </div>
  );
}

// ─── 2. World Globe with live connection pings ───────────────────────────────
function WorldGlobe({ title = 'phantom · світ · live', pings, kicker = '· peer mesh' }) {
  const canvasRef = React.useRef(null);
  const ps = pings || [
    { name: 'kyiv',     lat: 50.45,  lon: 30.52, mine: true,  color: '#F4AF25' },
    { name: 'lhasa',    lat: 29.65,  lon: 91.10, color: '#F4AF25' },
    { name: 'berlin',   lat: 52.52,  lon: 13.40, color: '#7CC0E8' },
    { name: 'tokyo',    lat: 35.68,  lon: 139.69, color: '#FF8B6E' },
    { name: 'san fr',   lat: 37.77,  lon: -122.4, color: '#B49AD4' },
    { name: 'sydney',   lat: -33.86, lon: 151.21, color: '#9DCC9F' },
    { name: 'sao paulo',lat: -23.55, lon: -46.63, color: '#A7967D' },
  ];
  React.useEffect(() => {
    const c = canvasRef.current; if (!c) return;
    const ctx = c.getContext('2d');
    const W = c.width = c.clientWidth * 2;
    const H = c.height = c.clientHeight * 2;
    let raf, rot = 0;
    const cx = W/2, cy = H/2, R = Math.min(W, H) * 0.4;
    const sp = (lat, lon) => {
      const φ = lat * Math.PI/180, λ = (lon + rot*45) * Math.PI/180;
      const x = R * Math.cos(φ) * Math.sin(λ);
      const y = -R * Math.sin(φ);
      const z = R * Math.cos(φ) * Math.cos(λ);
      return { x: cx + x, y: cy + y, z, visible: z > -R*0.3 };
    };
    const drawArc = (a, b, alpha) => {
      // great-circle approximation: subdivide
      const N = 24;
      ctx.beginPath();
      for (let t = 0; t <= N; t++) {
        const lat = a.lat + (b.lat - a.lat) * (t/N);
        const lon = a.lon + (b.lon - a.lon) * (t/N);
        const p = sp(lat, lon);
        // arc lifts off the sphere a bit
        const lift = Math.sin((t/N) * Math.PI) * R * 0.18;
        if (t === 0) ctx.moveTo(p.x, p.y - lift);
        else ctx.lineTo(p.x, p.y - lift);
      }
      ctx.strokeStyle = `rgba(244,175,37,${alpha})`;
      ctx.lineWidth = 1.2;
      ctx.stroke();
    };
    const draw = () => {
      ctx.clearRect(0, 0, W, H);
      // sphere base
      const grd = ctx.createRadialGradient(cx - R*0.3, cy - R*0.3, R*0.2, cx, cy, R);
      grd.addColorStop(0, 'rgba(64,42,24,0.7)');
      grd.addColorStop(0.7, 'rgba(20,12,6,0.6)');
      grd.addColorStop(1, 'rgba(5,3,2,0.3)');
      ctx.fillStyle = grd;
      ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI*2); ctx.fill();
      // graticule
      ctx.strokeStyle = 'rgba(244,175,37,0.08)';
      ctx.lineWidth = 0.6;
      for (let lat = -60; lat <= 60; lat += 30) {
        ctx.beginPath();
        for (let lon = -180; lon <= 180; lon += 5) {
          const p = sp(lat, lon);
          if (p.visible) {
            if (lon === -180) ctx.moveTo(p.x, p.y);
            else ctx.lineTo(p.x, p.y);
          }
        }
        ctx.stroke();
      }
      for (let lon = -180; lon < 180; lon += 30) {
        ctx.beginPath(); let started = false;
        for (let lat = -90; lat <= 90; lat += 4) {
          const p = sp(lat, lon);
          if (p.visible) {
            if (!started) { ctx.moveTo(p.x, p.y); started = true; }
            else ctx.lineTo(p.x, p.y);
          } else started = false;
        }
        ctx.stroke();
      }
      // continents — schematic blobs
      ctx.fillStyle = 'rgba(157,204,159,0.10)';
      const blobs = [
        // [lat, lon, radius in deg]
        [50, 20, 30], [10, 25, 20], [-20, 25, 22], // europe + africa
        [40, 95, 35], [-25, 135, 18], // asia + au
        [40, -90, 25], [-15, -60, 28], // americas
      ];
      blobs.forEach(([la, lo, rad]) => {
        const p = sp(la, lo);
        if (p.visible) {
          const f = (p.z + R) / (R*2);
          ctx.beginPath();
          ctx.ellipse(p.x, p.y, rad * f * 1.2, rad * f * 0.9, 0, 0, Math.PI*2);
          ctx.fill();
        }
      });
      // arcs from mine to others
      const me = ps.find((p) => p.mine);
      if (me) {
        ps.forEach((p) => {
          if (p.mine) return;
          drawArc(me, p, 0.45);
        });
      }
      // pings
      ps.forEach((p) => {
        const sp_ = sp(p.lat, p.lon);
        if (!sp_.visible) return;
        // halo
        ctx.fillStyle = `${p.color}30`;
        ctx.beginPath(); ctx.arc(sp_.x, sp_.y, 12, 0, Math.PI*2); ctx.fill();
        // pulse
        const pulse = ((Date.now()/700) + p.lon * 0.01) % 1;
        ctx.strokeStyle = `${p.color}${Math.floor((1 - pulse) * 220).toString(16).padStart(2, '0')}`;
        ctx.lineWidth = 1.2;
        ctx.beginPath(); ctx.arc(sp_.x, sp_.y, 6 + pulse * 14, 0, Math.PI*2); ctx.stroke();
        // dot
        ctx.fillStyle = p.color;
        ctx.beginPath(); ctx.arc(sp_.x, sp_.y, p.mine ? 4 : 3, 0, Math.PI*2); ctx.fill();
        // label
        if (p.mine || true) {
          ctx.font = '500 16px Inter, sans-serif';
          ctx.fillStyle = `rgba(255,248,231,${0.6 + (sp_.z + R)/(R*2) * 0.4})`;
          ctx.textAlign = 'left';
          ctx.fillText(p.name, sp_.x + 8, sp_.y + 5);
        }
      });
      rot += 0.0015;
      raf = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, [ps]);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '10px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>{kicker} · {ps.length} вузлів</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        </div>
        <span style={{
          display: 'inline-flex', alignItems: 'center', gap: 4,
          padding: '3px 8px', borderRadius: 999,
          background: 'rgba(124,169,130,0.10)',
          border: '0.5px solid rgba(157,204,159,0.3)',
        }}>
          <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'var(--sage)',
            animation: 'phantom-pulse-glow 1.4s ease-in-out infinite' }} />
          <span className="mono" style={{ fontSize: 8.5, color: '#9DCC9F', letterSpacing: '0.1em' }}>· all synced</span>
        </span>
      </div>
      <div style={{
        height: 240, position: 'relative',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)',
      }}>
        <canvas ref={canvasRef} style={{ width: '100%', height: '100%', display: 'block' }} />
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8,
        padding: '10px 14px', borderTop: '0.5px solid var(--glass-edge)',
      }}>
        {[
          ['rtt avg', '47мс'],
          ['peers',   '7 / 7'],
          ['e2e',     'ed25519'],
          ['relay',   'none'],
        ].map(([k, v]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>{k}</span>
            <span style={{ font: '500 10.5px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 3. Bill Splitter ────────────────────────────────────────────────────────
function BillSplitter({ vendor = 'fika · вечеря', when = '14 травня · 21:14',
                         items, friends, total }) {
  const fs = friends || [
    { id: 'k', name: 'кирил', color: '#F4AF25' },
    { id: 'o', name: 'оля',   color: '#FF8B6E' },
    { id: 'b', name: 'богдан', color: '#7CC0E8' },
    { id: 'n', name: 'нік',   color: '#B49AD4' },
  ];
  const it = items || [
    { t: 'тайська локшина · 2', p: 18.4, by: ['k', 'o'] },
    { t: 'грузинське хінкалі',   p: 12.0, by: ['b', 'n'] },
    { t: 'вино · домашнє',       p: 14.0, by: ['k', 'o', 'b', 'n'] },
    { t: 'десерт · тірамісу',    p:  6.5, by: ['o', 'n'] },
    { t: 'чай улун · повний',    p:  4.2, by: ['k'] },
  ];
  // calc per-friend totals
  const totals = {};
  fs.forEach((f) => totals[f.id] = 0);
  it.forEach((row) => {
    const share = row.p / row.by.length;
    row.by.forEach((id) => { totals[id] += share; });
  });
  const sum = it.reduce((a, b) => a + b.p, 0);
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· розділення чека · {fs.length} людей</div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{vendor}</div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>{when}</span>
      </div>
      {/* items with chips */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {it.map((row, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '1fr 60px',
            gap: 8, alignItems: 'center',
            padding: '7px 10px', borderRadius: 8,
            background: 'rgba(255,230,180,0.02)',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <div>
              <span style={{ font: '400 11px/1.3 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{row.t}</span>
              <div style={{ display: 'flex', gap: -3, marginTop: 5 }}>
                {row.by.map((id, j) => {
                  const f = fs.find((x) => x.id === id);
                  return (
                    <span key={id} style={{
                      width: 16, height: 16, borderRadius: '50%',
                      background: `linear-gradient(135deg, ${f.color}, #1A140B)`,
                      border: '1.5px solid var(--bg-base)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      font: '500 7.5px/1 var(--font-display)', color: '#fff',
                      marginLeft: j > 0 ? -4 : 0, letterSpacing: '0.02em',
                    }}>{f.name[0]}</span>
                  );
                })}
              </div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <div style={{ font: '500 12px/1 var(--font-display)', color: 'var(--ink-strong)',
                fontVariantNumeric: 'tabular-nums' }}>{row.p.toFixed(2)}</div>
              <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', marginTop: 2,
                letterSpacing: '0.04em' }}>÷{row.by.length} = {(row.p / row.by.length).toFixed(2)}</div>
            </div>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 10, paddingTop: 10,
        borderTop: '0.5px solid var(--glass-edge-strong)',
        display: 'flex', justifyContent: 'space-between',
        font: '500 13px/1 var(--font-display)', color: 'var(--gold)',
        fontVariantNumeric: 'tabular-nums',
      }}>
        <span>усього</span>
        <span>${(total || sum).toFixed(2)}</span>
      </div>
      <div style={{
        marginTop: 10,
        display: 'grid', gridTemplateColumns: `repeat(${fs.length}, 1fr)`, gap: 6,
      }}>
        {fs.map((f) => (
          <div key={f.id} style={{
            padding: '8px 6px', borderRadius: 8,
            background: `${f.color}10`,
            borderTop: `2px solid ${f.color}`,
            textAlign: 'center',
          }}>
            <div className="mono" style={{ fontSize: 8, color: f.color, letterSpacing: '0.1em', textTransform: 'uppercase' }}>{f.name}</div>
            <div style={{ font: '500 13px/1 var(--font-display)', color: 'var(--ink-strong)',
              fontVariantNumeric: 'tabular-nums', marginTop: 4 }}>${totals[f.id].toFixed(2)}</div>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 10, padding: '7px 9px', borderRadius: 8,
        background: 'rgba(244,175,37,0.05)',
        font: '400 10.5px/1.45 var(--font-body)', color: 'var(--gold-soft)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, letterSpacing: '0.16em', textTransform: 'uppercase', marginRight: 5 }}>· phantom</span>
        богдан винен олі $9.00 з минулого тижня · можу залік?
      </div>
    </div>
  );
}

// ─── 4. Boarding Pass with QR / barcode ──────────────────────────────────────
function BoardingPass({ flight = 'TK 5732', from = 'KBP', fromCity = 'kyiv',
                          to = 'IST', toCity = 'istanbul', date = '16 трав',
                          dep = '06:55', boarding = '06:25', gate = 'B27',
                          seat = '14A', cls = 'business', name = 'KOSTENKO/KIRYL',
                          pnr = '8F2A1Q' }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `linear-gradient(160deg, rgba(244,175,37,0.10) 0%, transparent 30%), var(--glass-tint)`,
    }}>
      {/* top stub */}
      <div style={{ padding: '14px 14px 12px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em',
            textTransform: 'uppercase',
          }}>· boarding pass</div>
          <span className="mono" style={{
            padding: '3px 8px', borderRadius: 4,
            background: 'rgba(244,175,37,0.94)',
            color: '#1A140B', font: '600 9px/1 var(--font-display)', letterSpacing: '0.1em',
          }}>{cls.toUpperCase()}</span>
        </div>
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr auto 1fr',
          gap: 10, alignItems: 'baseline',
        }}>
          <div>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>відліт</span>
            <div style={{ font: '300 38px/0.9 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '-0.02em', marginTop: 4 }}>{from}</div>
            <div className="mono" style={{ fontSize: 10, color: 'var(--ink-secondary)', marginTop: 3, letterSpacing: '0.04em' }}>
              {fromCity}
            </div>
          </div>
          <div style={{ paddingTop: 10 }}>
            <svg width="34" height="14" viewBox="0 0 34 14">
              <line x1="2" y1="7" x2="32" y2="7" stroke="var(--gold)" strokeWidth="0.6" strokeDasharray="2 3" />
              <path d="M16 4 L26 7 L16 10 L19 7 Z" fill="var(--gold)" />
            </svg>
            <div className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', textAlign: 'center', marginTop: 4 }}>
              {flight}
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>приліт</span>
            <div style={{ font: '300 38px/0.9 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '-0.02em', marginTop: 4 }}>{to}</div>
            <div className="mono" style={{ fontSize: 10, color: 'var(--ink-secondary)', marginTop: 3, letterSpacing: '0.04em' }}>
              {toCity}
            </div>
          </div>
        </div>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 6,
          marginTop: 14, paddingTop: 12,
          borderTop: '0.5px solid var(--glass-edge)',
        }}>
          {[
            ['дата', date],
            ['відліт', dep],
            ['посадка', boarding],
            ['gate', gate],
            ['місце', seat],
          ].map(([k, v]) => (
            <div key={k}>
              <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em', textTransform: 'uppercase' }}>{k}</div>
              <div style={{ font: '500 13px/1 var(--font-display)', color: 'var(--ink-strong)', marginTop: 3,
                fontVariantNumeric: 'tabular-nums' }}>{v}</div>
            </div>
          ))}
        </div>
      </div>
      {/* perforation */}
      <div style={{
        position: 'relative', height: 10,
        background: 'repeating-linear-gradient(to right, var(--glass-edge) 0 5px, transparent 5px 10px)',
      }}>
        <div style={{
          position: 'absolute', left: -8, top: 0, bottom: 0,
          width: 16, borderRadius: '50%', background: 'var(--bg-base)',
        }} />
        <div style={{
          position: 'absolute', right: -8, top: 0, bottom: 0,
          width: 16, borderRadius: '50%', background: 'var(--bg-base)',
        }} />
      </div>
      {/* bottom — passenger + barcode */}
      <div style={{ padding: '12px 14px', display: 'flex', gap: 14, alignItems: 'center' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.16em', textTransform: 'uppercase' }}>пасажир</div>
          <div style={{ font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)',
            marginTop: 3, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
            {name}
          </div>
          <div style={{ display: 'flex', gap: 12, marginTop: 6 }}>
            <div>
              <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>PNR</div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--gold)', marginTop: 2, letterSpacing: '0.06em' }}>{pnr}</div>
            </div>
            <div>
              <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.12em' }}>термінал</div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--ink-strong)', marginTop: 2 }}>D · 1</div>
            </div>
          </div>
        </div>
        {/* barcode */}
        <div style={{
          width: 90, height: 90, background: '#fff', borderRadius: 4, padding: 8, flexShrink: 0,
          display: 'grid', gridTemplateColumns: 'repeat(12, 1fr)', gridTemplateRows: 'repeat(12, 1fr)', gap: 1,
        }}>
          {Array.from({ length: 144 }).map((_, i) => {
            // deterministic pseudo-QR
            const v = ((i * 7919 + i * 13) % 17) > 8;
            const corner = (i % 12 < 3 && i < 36) || (i % 12 > 8 && i < 36) || (i % 12 < 3 && i > 107);
            const cornerInner = (i % 12 >= 1 && i % 12 <= 1) || (i % 12 >= 9 && i % 12 <= 9);
            return (
              <div key={i} style={{ background: (corner && !cornerInner) || v ? '#000' : '#fff' }} />
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── 5. Book Reader with annotations ─────────────────────────────────────────
function BookReader({ title = 'Sapiens', author = 'y. n. harari',
                       page = 142, total = 412, paragraphs, annotations }) {
  const ps = paragraphs || [
    { kind: 'h2', t: 'Розділ 5 · Найбільший шахрай в історії' },
    { kind: 'p',  t: 'Ми не одомашнили пшеницю. Пшениця одомашнила нас.' },
    { kind: 'p',  t: 'Слово «одомашнити» походить від латинського domus — «дім». Хто живе в домі? Не пшениця, що зростає у дикому полі — а homo sapiens, який тепер прив\'язаний до неї.' },
    { kind: 'p',  t: 'Перш ніж погодитися на цю угоду, людина була мисливцем-збирачем — здоровою, мобільною, з різноманітним харчуванням і добрими зубами. Після — стала рабом одного зерна.', highlight: true },
    { kind: 'p',  t: 'Можна стверджувати, що еволюція оцінює успіх кількістю копій ДНК. Тоді так, sapiens перемогли — нас сім мільярдів. Але якщо мета — добробут окремої людини…' },
  ];
  const an = annotations || [
    { line: 3, t: '· перечитати · це ключове', kind: 'highlight' },
    { line: 4, t: '· розширення Малтуса · додати', kind: 'thought' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `linear-gradient(180deg, rgba(248,247,245,0.95) 0%, rgba(232,212,176,0.94) 100%)`,
      color: '#1a140b',
    }}>
      <div style={{ padding: '12px 16px 8px', borderBottom: '0.5px solid rgba(26,20,11,0.1)' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: '#7a6b56', letterSpacing: '0.22em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· {author} · ст. {page} / {total}</div>
        <div style={{ font: '500 14px/1.3 var(--font-display)', color: '#1a140b' }}>{title}</div>
      </div>
      <div style={{ padding: '12px 18px', position: 'relative' }}>
        {ps.map((p, i) => {
          if (p.kind === 'h2') return (
            <div key={i} style={{
              font: '600 14px/1.3 Georgia, serif', color: '#1a140b',
              marginBottom: 10, letterSpacing: '-0.005em',
            }}>{p.t}</div>
          );
          const note = an.find((a) => a.line === i);
          return (
            <p key={i} style={{
              font: `400 13px/1.65 Georgia, serif`,
              color: '#2a2118',
              margin: '0 0 8px 0',
              background: p.highlight ? 'rgba(244,175,37,0.28)' : 'transparent',
              padding: p.highlight ? '2px 4px' : 0,
              borderRadius: p.highlight ? 2 : 0,
              textWrap: 'pretty',
              position: 'relative',
            }}>
              {p.t}
              {note && (
                <span style={{
                  position: 'absolute', right: -8, top: 0,
                  width: 12, height: 12, borderRadius: '50%',
                  background: note.kind === 'thought' ? '#7E5DA4' : '#F4AF25',
                  border: '2px solid #f8f7f5',
                  cursor: 'pointer',
                }} />
              )}
            </p>
          );
        })}
        {/* margin annotations */}
        <div style={{
          marginTop: 12, paddingTop: 10,
          borderTop: '0.5px solid rgba(26,20,11,0.15)',
          display: 'flex', flexDirection: 'column', gap: 5,
        }}>
          {an.map((a, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'baseline', gap: 6,
              font: '400 10.5px/1.45 var(--font-body)', color: '#5d5142',
              fontStyle: 'italic',
            }}>
              <span style={{
                width: 7, height: 7, borderRadius: '50%',
                background: a.kind === 'thought' ? '#7E5DA4' : '#F4AF25',
                flexShrink: 0, marginTop: 2,
              }} />
              {a.t}
            </div>
          ))}
        </div>
      </div>
      <div style={{
        padding: '8px 16px',
        background: 'rgba(26,20,11,0.06)',
        borderTop: '0.5px solid rgba(26,20,11,0.08)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span className="mono" style={{ fontSize: 8.5, color: '#7a6b56', letterSpacing: '0.1em' }}>
          · ти читаєш 3 дні · 17 хв сьогодні
        </span>
        <span style={{ display: 'flex', gap: 6 }}>
          <button style={{
            width: 22, height: 22, borderRadius: 4, appearance: 'none', cursor: 'pointer',
            background: 'rgba(26,20,11,0.08)', border: '0.5px solid rgba(26,20,11,0.15)',
            color: '#1a140b', font: '300 10px/1 var(--font-mono)',
          }}>‹</button>
          <button style={{
            width: 22, height: 22, borderRadius: 4, appearance: 'none', cursor: 'pointer',
            background: 'rgba(26,20,11,0.08)', border: '0.5px solid rgba(26,20,11,0.15)',
            color: '#1a140b', font: '300 10px/1 var(--font-mono)',
          }}>›</button>
        </span>
      </div>
    </div>
  );
}

// ─── 6. Plant identification ─────────────────────────────────────────────────
function PlantID({ photoScene = 'forest',
                    common = 'мох звичайний', latin = 'Hylocomium splendens',
                    confidence = 0.94, taxonomy }) {
  const tx = taxonomy || [
    { l: 'царство',  v: 'plantae' },
    { l: 'тип',      v: 'bryophyta' },
    { l: 'клас',     v: 'bryopsida' },
    { l: 'порядок',  v: 'hypnales' },
    { l: 'родина',   v: 'hylocomiaceae' },
  ];
  const alts = [
    { name: 'Hylocomium pyrenaicum', conf: 0.04 },
    { name: 'Pleurozium schreberi',  conf: 0.02 },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ display: 'flex', gap: 0 }}>
        <div style={{ width: 130, position: 'relative', flexShrink: 0 }}>
          <PainterlyPhoto scene={photoScene} ratio="3/4" />
          {/* scan overlay */}
          <div style={{
            position: 'absolute', inset: 0,
            border: '1px solid rgba(157,204,159,0.4)',
            boxShadow: 'inset 0 0 24px rgba(157,204,159,0.15)',
          }}>
            {/* corner brackets */}
            {[[0,0],[1,0],[0,1],[1,1]].map(([x,y], i) => (
              <div key={i} style={{
                position: 'absolute',
                [x ? 'right' : 'left']: 4, [y ? 'bottom' : 'top']: 4,
                width: 10, height: 10,
                borderTop: !y ? '1.5px solid var(--sage)' : 'none',
                borderBottom: y ? '1.5px solid var(--sage)' : 'none',
                borderLeft: !x ? '1.5px solid var(--sage)' : 'none',
                borderRight: x ? '1.5px solid var(--sage)' : 'none',
              }} />
            ))}
          </div>
        </div>
        <div style={{ flex: 1, padding: '10px 12px' }}>
          <div className="mono" style={{
            fontSize: 9, color: '#9DCC9F', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· розпізнано · {(confidence*100|0)}%</div>
          <div style={{ font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{common}</div>
          <div style={{ font: '400 11px/1.2 var(--font-body)', color: 'var(--ink-secondary)', fontStyle: 'italic', marginTop: 2 }}>
            {latin}
          </div>
          <div style={{
            marginTop: 8, padding: '6px 8px', borderRadius: 6,
            background: 'rgba(157,204,159,0.06)',
            border: '0.5px solid rgba(157,204,159,0.2)',
          }}>
            <div style={{
              font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
              textWrap: 'pretty',
            }}>
              росте в хвойних лісах · «ялинка-мох», часто на півночі. зростає 1—2 см / рік.
            </div>
          </div>
        </div>
      </div>
      <div style={{
        padding: '10px 12px',
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 5,
        }}>· таксономія</div>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)',
          gap: 4,
        }}>
          {tx.map((t) => (
            <div key={t.l} style={{
              padding: '4px 5px', borderRadius: 5,
              background: 'rgba(255,230,180,0.04)',
              border: '0.5px solid var(--glass-edge)',
            }}>
              <div className="mono" style={{ fontSize: 7, color: 'var(--ink-tertiary)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>{t.l}</div>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-strong)', marginTop: 2 }}>{t.v}</div>
            </div>
          ))}
        </div>
        <div style={{
          marginTop: 8,
          display: 'flex', gap: 6, flexWrap: 'wrap',
        }}>
          <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.14em', paddingTop: 4 }}>
            АЛЬТ:
          </span>
          {alts.map((a) => (
            <span key={a.name} style={{
              padding: '3px 7px', borderRadius: 999,
              background: 'rgba(255,230,180,0.04)',
              border: '0.5px solid var(--glass-edge)',
              font: '400 10px/1 var(--font-body)', color: 'var(--ink-secondary)',
              fontStyle: 'italic', display: 'inline-flex', alignItems: 'center', gap: 4,
            }}>
              {a.name} <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)' }}>{(a.conf*100).toFixed(0)}%</span>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── 7. Sleep stages live (currently sleeping) ───────────────────────────────
function SleepStagesLive({ asleepFor = 184, kicker = '· phantom · поряд', wakeWindow = '06:48–07:12' }) {
  const stages = ['light', 'deep', 'rem'];
  const cur = 'deep';
  return (
    <div className="glass fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
      background: `
        radial-gradient(ellipse at 50% 30%, rgba(126,93,164,0.10), transparent 70%),
        var(--glass-tint)`,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--ink-secondary)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 6,
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        <span style={{
          width: 5, height: 5, borderRadius: '50%', background: '#B49AD4',
          animation: 'phantom-pulse-glow 3s ease-in-out infinite',
        }} />
        {kicker} · live
      </div>
      <div style={{
        font: '500 14.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', textWrap: 'pretty', marginBottom: 6,
      }}>«ти спиш {Math.floor(asleepFor/60)}г {asleepFor%60}хв · я тут»</div>
      <div style={{
        font: '300 11.5px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
        letterSpacing: '0.04em', marginBottom: 12,
      }}>зараз — глибокий сон · я мовчу.</div>
      {/* breathing visualization */}
      <div style={{
        position: 'relative', height: 60,
        background: 'rgba(0,0,0,0.18)', borderRadius: 8, overflow: 'hidden',
        border: '0.5px solid var(--glass-edge)',
        marginBottom: 10,
      }}>
        <svg viewBox="0 0 320 60" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* breath curve — slow */}
          <path d={(() => {
            const pts = [];
            for (let x = 0; x <= 320; x += 4) {
              const y = 30 + Math.sin(x * 0.04) * 14 + Math.sin(x * 0.012) * 4;
              pts.push(`${x === 0 ? 'M' : 'L'} ${x} ${y}`);
            }
            return pts.join(' ');
          })()} fill="none" stroke="#B49AD4" strokeWidth="1.2"
                 style={{ filter: 'drop-shadow(0 0 4px rgba(180,154,212,0.6))' }} />
        </svg>
        <span className="mono" style={{
          position: 'absolute', top: 4, left: 8,
          fontSize: 8, color: 'rgba(180,154,212,0.85)', letterSpacing: '0.1em',
        }}>· дихання · 11 / хв</span>
        <span className="mono" style={{
          position: 'absolute', top: 4, right: 8,
          fontSize: 8, color: 'rgba(180,154,212,0.85)', letterSpacing: '0.1em',
        }}>BPM 52 · HRV 64</span>
      </div>
      {/* stages bar */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        {stages.map((s) => (
          <div key={s} style={{
            display: 'grid', gridTemplateColumns: '40px 1fr 24px',
            gap: 6, alignItems: 'center',
          }}>
            <span className="mono" style={{
              fontSize: 8.5, color: s === cur ? '#B49AD4' : 'var(--ink-tertiary)',
              letterSpacing: '0.12em', textTransform: 'uppercase',
              fontWeight: s === cur ? 600 : 400,
            }}>{s}</span>
            <div style={{ height: 3, background: 'rgba(255,230,180,0.06)', borderRadius: 2, position: 'relative' }}>
              <div style={{
                width: s === 'light' ? '52%' : s === 'deep' ? '28%' : '20%',
                height: '100%',
                background: s === 'light' ? 'var(--gold-soft)' : s === 'deep' ? '#7E5DA4' : '#F4AF25',
                borderRadius: 2,
              }} />
              {s === cur && (
                <span style={{
                  position: 'absolute', left: '28%', top: -2, transform: 'translateX(-50%)',
                  width: 6, height: 6, borderRadius: '50%',
                  background: '#B49AD4', border: '1px solid var(--bg-base)',
                  animation: 'phantom-pulse-glow 1.4s ease-in-out infinite',
                }} />
              )}
            </div>
            <span className="mono" style={{
              fontSize: 8.5, color: 'var(--ink-tertiary)',
              fontVariantNumeric: 'tabular-nums', textAlign: 'right',
            }}>{s === 'light' ? '1:36' : s === 'deep' ? '0:52' : '0:38'}</span>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 10, padding: '7px 10px', borderRadius: 8,
        background: 'rgba(244,175,37,0.05)',
        font: '400 11px/1.45 var(--font-body)', color: 'var(--gold-soft)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, letterSpacing: '0.14em', textTransform: 'uppercase', marginRight: 5 }}>· вікно пробудження</span>
        {wakeWindow} · поряд буде тихий хвилевий тон, не дзвінок
      </div>
    </div>
  );
}

// ─── 8. Mind map ─────────────────────────────────────────────────────────────
function MindMap({ root = 'переїзд lhasa', branches }) {
  const bs = branches || [
    { label: 'логістика', color: '#F4AF25', children: ['квитки', 'permit', 'esim', 'guesthouse'] },
    { label: 'робота',    color: '#7CC0E8', children: ['remote-time', 'обладнання', 'meets uk-time'] },
    { label: 'тіло',       color: '#9DCC9F', children: ['висота · акліматизація', 'кашель', 'ліки'] },
    { label: 'люди',       color: '#FF8B6E', children: ['оля · щотижня', 'мама · щодня', 'олег · текст'] },
    { label: 'я',          color: '#B49AD4', children: ['чому я туди?', 'тиша', 'писати знов'] },
  ];
  const cx = 160, cy = 150, W = 320, H = 300;
  const angleStep = (Math.PI * 2) / bs.length;
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '10px 14px 4px' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· mind map · 5 гілок · 17 пагонів</div>
        <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{root}</div>
      </div>
      <div style={{ height: 300, position: 'relative' }}>
        <svg viewBox={`0 0 ${W} ${H}`} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* connections from root to branches and children */}
          {bs.map((b, i) => {
            const ang = i * angleStep - Math.PI / 2;
            const bx = cx + Math.cos(ang) * 80;
            const by = cy + Math.sin(ang) * 80;
            return (
              <g key={i}>
                {/* curve to branch */}
                <path d={`M ${cx} ${cy} Q ${(cx+bx)/2 + Math.sin(ang)*10} ${(cy+by)/2}, ${bx} ${by}`}
                      fill="none" stroke={b.color} strokeWidth="1.2" opacity="0.6" />
                {/* children */}
                {b.children.map((ch, j) => {
                  const offs = (j - (b.children.length - 1) / 2) * 18;
                  const childAng = ang + offs * 0.01;
                  const dist = 130 + j * 6;
                  const wpos = ang > -Math.PI/2 && ang < Math.PI/2; // right side?
                  const childX = bx + Math.cos(ang) * (dist - 80) + Math.cos(ang + Math.PI/2) * offs * 1.2;
                  const childY = by + Math.sin(ang) * (dist - 80) + Math.sin(ang + Math.PI/2) * offs * 1.2;
                  // clamp inside viewport
                  const cX = Math.max(20, Math.min(W - 20, childX));
                  const cY = Math.max(20, Math.min(H - 20, childY));
                  return (
                    <g key={j}>
                      <path d={`M ${bx} ${by} L ${cX} ${cY}`} stroke={b.color}
                            strokeWidth="0.7" opacity="0.45" fill="none" />
                      <circle cx={cX} cy={cY} r="2.5" fill={b.color} opacity="0.9" />
                      <text x={cX + (cX > cx ? 5 : -5)} y={cY + 3}
                            textAnchor={cX > cx ? 'start' : 'end'}
                            style={{ font: '400 8.5px var(--font-body)', fill: 'var(--ink-secondary)' }}>
                        {ch}
                      </text>
                    </g>
                  );
                })}
                {/* branch node */}
                <circle cx={bx} cy={by} r="14" fill={`${b.color}30`} stroke={b.color} strokeWidth="0.8"
                        style={{ filter: `drop-shadow(0 0 4px ${b.color}66)` }} />
                <text x={bx} y={by + 3} textAnchor="middle"
                      style={{ font: '500 9px var(--font-display)', fill: 'var(--ink-strong)' }}>
                  {b.label}
                </text>
              </g>
            );
          })}
          {/* root */}
          <circle cx={cx} cy={cy} r="22" fill="rgba(244,175,37,0.25)"
                  stroke="var(--gold)" strokeWidth="1.2"
                  style={{ filter: 'drop-shadow(0 0 8px var(--gold-glow))' }} />
          <text x={cx} y={cy + 1} textAnchor="middle"
                style={{ font: '600 10px var(--font-display)', fill: 'var(--gold)' }}>{root.split(' ')[0]}</text>
          <text x={cx} y={cy + 11} textAnchor="middle"
                style={{ font: '600 10px var(--font-display)', fill: 'var(--gold)' }}>{root.split(' ')[1] || ''}</text>
        </svg>
      </div>
    </div>
  );
}

Object.assign(window, {
  ChessBoard, WorldGlobe, BillSplitter, BoardingPass,
  BookReader, PlantID, SleepStagesLive, MindMap,
});
