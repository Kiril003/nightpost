/* connect.jsx — FirstConnectFlow with 5 methods (decentralized intro):
   NEAR (BLE radar), QR, CHIRP (audio), VOUCH (mutual intro), STRANGER (resonance).
   Each is a ritual — no central server, no usernames. */

// ─── NearRadar — BLE-style proximity discovery ──────────────────────────────
function NearRadar({ onPick }) {
  const [sweep, setSweep] = React.useState(0);
  React.useEffect(() => {
    let raf;
    const start = performance.now();
    const tick = () => {
      setSweep(((performance.now() - start) / 4000) * 360 % 360);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);
  const peers = [
    { id: 'n1', name: 'fika · бариста', ang: 38,  dist: 0.42, c: '#9DCC9F', paired: true, signal: -52 },
    { id: 'n2', name: 'ніна · поряд',    ang: 142, dist: 0.6,  c: '#7E5DA4', paired: true, signal: -64 },
    { id: 'n3', name: 'невідомий-7',     ang: 220, dist: 0.84, c: '#A7967D', paired: true, signal: -78 },
    { id: 'n4', name: 'olya · 240 м',    ang: 290, dist: 0.95, c: '#FF8B6E', paired: true, signal: -82 },
  ];
  return (
    <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em', textTransform: 'uppercase', marginBottom: 6 }}>· near · ble · радар</div>
        <div style={{ font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>фантоми поряд з тобою.</div>
        <div style={{ font: '300 11px/1.55 Georgia, serif', color: 'var(--ink-tertiary)', fontStyle: 'italic', marginTop: 5, textWrap: 'pretty' }}>
          без імен. без серверів. лише радіо-привіт між пристроями.
        </div>
      </div>
      <div style={{
        position: 'relative', aspectRatio: '1', borderRadius: 18, overflow: 'hidden',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(124,169,130,0.08), transparent 70%)',
        border: '0.5px solid rgba(157,204,159,0.18)',
      }}>
        <svg viewBox="0 0 300 300" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* concentric rings */}
          {[40, 80, 120, 145].map((r, i) => (
            <circle key={r} cx="150" cy="150" r={r} fill="none"
                    stroke="rgba(157,204,159,0.15)" strokeWidth="0.5"
                    strokeDasharray={i === 3 ? '0' : '2 4'} />
          ))}
          {/* cross */}
          <line x1="0" y1="150" x2="300" y2="150" stroke="rgba(157,204,159,0.08)" strokeWidth="0.4" />
          <line x1="150" y1="0" x2="150" y2="300" stroke="rgba(157,204,159,0.08)" strokeWidth="0.4" />
          {/* sweep cone */}
          <defs>
            <radialGradient id="sweepGrad" cx="150" cy="150" r="145" gradientUnits="userSpaceOnUse">
              <stop offset="0%" stopColor="rgba(157,204,159,0.55)" />
              <stop offset="100%" stopColor="rgba(157,204,159,0)" />
            </radialGradient>
          </defs>
          <path d={`M 150 150 L ${150 + Math.cos((sweep - 30) * Math.PI / 180) * 145} ${150 + Math.sin((sweep - 30) * Math.PI / 180) * 145} A 145 145 0 0 1 ${150 + Math.cos(sweep * Math.PI / 180) * 145} ${150 + Math.sin(sweep * Math.PI / 180) * 145} Z`}
                fill="url(#sweepGrad)" opacity="0.6" />
          {/* distance ticks */}
          <text x="155" y="115" style={{ font: '300 8px var(--font-mono)', fill: 'rgba(157,204,159,0.4)', letterSpacing: '0.06em' }}>40м</text>
          <text x="155" y="75" style={{ font: '300 8px var(--font-mono)', fill: 'rgba(157,204,159,0.4)', letterSpacing: '0.06em' }}>100м</text>
          <text x="155" y="35" style={{ font: '300 8px var(--font-mono)', fill: 'rgba(157,204,159,0.4)', letterSpacing: '0.06em' }}>250м</text>
        </svg>
        {/* center = you */}
        <div style={{
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 14, height: 14, borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25)',
          boxShadow: '0 0 10px var(--gold-glow)',
          animation: 'phantom-breathe 4s ease-in-out infinite',
        }} />
        {/* peers */}
        {peers.map((p) => {
          const a = (p.ang * Math.PI) / 180;
          const r = p.dist * 145;
          const x = 150 + Math.cos(a) * r;
          const y = 150 + Math.sin(a) * r;
          const diff = Math.abs(((sweep - p.ang + 540) % 360) - 180) - 180;
          const lit = Math.abs(diff) < 40;
          return (
            <button key={p.id} onClick={() => onPick?.(p)} style={{
              position: 'absolute', left: `calc(${(x/300)*100}% - 8px)`, top: `calc(${(y/300)*100}% - 8px)`,
              appearance: 'none', cursor: 'pointer', border: 'none',
              width: 16, height: 16, borderRadius: '50%', padding: 0,
              background: `radial-gradient(circle at 35% 30%, ${p.c}, ${p.c}55)`,
              boxShadow: lit ? `0 0 14px ${p.c}, 0 0 4px #fff` : `0 0 4px ${p.c}88`,
              transition: 'box-shadow 0.3s',
            }} title={p.name} />
          );
        })}
      </div>
      {/* peer list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {peers.map((p) => (
          <button key={p.id} onClick={() => onPick?.(p)} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            padding: '8px 12px', borderRadius: 10,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            <div style={{
              width: 24, height: 24, borderRadius: '50%',
              background: `radial-gradient(circle at 35% 30%, ${p.c}, ${p.c}55)`,
              boxShadow: `0 0 6px ${p.c}88`,
            }} />
            <span style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)', flex: 1 }}>{p.name}</span>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>{p.signal} дБ</span>
            <span style={{ color: 'var(--gold-soft)' }}>→</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── QRHandshake — show / scan QR ───────────────────────────────────────────
function QRHandshake({ onPick }) {
  const [mode, setMode] = React.useState('show'); // show | scan
  const cells = React.useMemo(() => Array.from({ length: 21*21 }, (_, i) => (i * 7919 % 17) > 8), []);
  return (
    <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em', textTransform: 'uppercase', marginBottom: 6 }}>· qr · одна камера, два phantom</div>
        <div style={{ font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>одне з вас показує — друге сканує.</div>
      </div>
      <div style={{ display: 'flex', gap: 5 }}>
        {[{ k: 'show', l: 'показати' }, { k: 'scan', l: 'сканувати' }].map((t) => (
          <button key={t.k} onClick={() => setMode(t.k)} style={{
            appearance: 'none', cursor: 'pointer', flex: 1,
            padding: '8px', borderRadius: 10,
            background: mode === t.k ? 'rgba(244,175,37,0.10)' : 'rgba(255,230,180,0.025)',
            border: `0.5px solid ${mode === t.k ? 'var(--gold-soft)' : 'var(--glass-edge)'}`,
            color: mode === t.k ? 'var(--gold)' : 'var(--ink-secondary)',
            font: '500 11px/1 var(--font-display)', letterSpacing: '0.04em',
          }}>{t.l}</button>
        ))}
      </div>
      {mode === 'show' ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, padding: '14px 0' }}>
          <div style={{
            width: 220, height: 220, background: '#fff', padding: 14, borderRadius: 12,
            display: 'grid', gridTemplateColumns: 'repeat(21, 1fr)', gridTemplateRows: 'repeat(21, 1fr)',
            gap: 0, boxShadow: '0 0 40px rgba(244,175,37,0.25)',
          }}>
            {cells.map((c, i) => {
              const x = i % 21, y = Math.floor(i / 21);
              const isCornerFrame = (x < 7 && y < 7) || (x > 13 && y < 7) || (x < 7 && y > 13);
              let cornerFill = '#fff';
              if (isCornerFrame) {
                const ux = x % 14, uy = y % 14;
                const cx = (x > 13 ? 17 : 3);
                const cy = (y > 13 ? 17 : 3);
                const dx = Math.abs(x - cx), dy = Math.abs(y - cy);
                const d = Math.max(dx, dy);
                cornerFill = d <= 1 ? '#000' : d === 2 ? '#fff' : d === 3 ? '#000' : '#fff';
              }
              return (
                <div key={i} style={{ background: isCornerFrame ? cornerFill : (c ? '#000' : '#fff') }} />
              );
            })}
          </div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.14em', textAlign: 'center',
          }}>· твій pubkey · ed25519:p4nt0m·······7b21</div>
          <div style={{
            font: '300 11.5px/1.55 Georgia, serif', color: 'var(--ink-tertiary)',
            fontStyle: 'italic', textWrap: 'pretty', textAlign: 'center', maxWidth: 280,
          }}>
            покажи це другому. його камера навчиться твого підпису · твоя — його, коли він покаже у відповідь.
          </div>
        </div>
      ) : (
        <div style={{
          aspectRatio: '1', borderRadius: 18, overflow: 'hidden',
          background: `radial-gradient(ellipse at 50% 50%, #1a1108, #0a0805 70%)`,
          position: 'relative',
          border: '0.5px solid var(--glass-edge)',
        }}>
          {/* brackets */}
          {[[0,0],[1,0],[0,1],[1,1]].map(([x,y], i) => (
            <div key={i} style={{
              position: 'absolute',
              [x ? 'right' : 'left']: '22%', [y ? 'bottom' : 'top']: '22%',
              width: 22, height: 22,
              borderTop: !y ? '2px solid var(--gold)' : 'none',
              borderBottom: y ? '2px solid var(--gold)' : 'none',
              borderLeft: !x ? '2px solid var(--gold)' : 'none',
              borderRight: x ? '2px solid var(--gold)' : 'none',
              filter: 'drop-shadow(0 0 4px var(--gold-glow))',
            }} />
          ))}
          <div className="mono" style={{
            position: 'absolute', left: 0, right: 0, bottom: 24, textAlign: 'center',
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          }}>· наведи на qr ·</div>
        </div>
      )}
      <button onClick={() => onPick?.({ kind: 'qr' })} style={{
        appearance: 'none', cursor: 'pointer', border: 'none',
        padding: '13px', borderRadius: 14,
        background: 'var(--gold)', color: '#1A140B',
        font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
        boxShadow: '0 0 14px var(--gold-glow)',
      }}>· звести</button>
    </div>
  );
}

// ─── AudioChirp — sub-audible handshake by sound ────────────────────────────
function AudioChirp({ onPick }) {
  const [listening, setListening] = React.useState(false);
  React.useEffect(() => {
    if (!listening) return;
    const id = setTimeout(() => onPick?.({ kind: 'chirp' }), 2400);
    return () => clearTimeout(id);
  }, [listening, onPick]);
  return (
    <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em', textTransform: 'uppercase', marginBottom: 6 }}>· chirp · sub-19kHz</div>
        <div style={{ font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>звуковий привіт.</div>
        <div style={{ font: '300 11px/1.55 Georgia, serif', color: 'var(--ink-tertiary)', fontStyle: 'italic', marginTop: 5, textWrap: 'pretty' }}>
          твій телефон видає тиху високу ноту · їхній чує і відповідає · радіус ≈ 3 м.
        </div>
      </div>
      <div style={{
        aspectRatio: '2/1', borderRadius: 18, position: 'relative', overflow: 'hidden',
        background: `radial-gradient(ellipse at 50% 50%, rgba(126,93,164,0.12), transparent 70%)`,
        border: '0.5px solid rgba(126,93,164,0.20)',
      }}>
        {/* sound waves */}
        <svg viewBox="0 0 400 200" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <path d={Array.from({ length: 100 }).map((_, i) => {
            const x = (i / 99) * 400;
            const y = 100 + Math.sin(i * 0.4) * (listening ? 50 : 8) * Math.exp(-Math.abs(i - 50) * 0.04);
            return `${i ? 'L' : 'M'} ${x} ${y}`;
          }).join(' ')}
                fill="none" stroke="#B49AD4" strokeWidth="1.4"
                style={{ filter: 'drop-shadow(0 0 4px rgba(180,154,212,0.6))' }} />
          {listening && [0, 1, 2].map((i) => (
            <circle key={i} cx="200" cy="100" r="0" fill="none" stroke="#B49AD4" strokeWidth="1">
              <animate attributeName="r" values="20;120;120" dur="2s" repeatCount="indefinite" begin={`${i * 0.4}s`} />
              <animate attributeName="opacity" values="0.8;0;0" dur="2s" repeatCount="indefinite" begin={`${i * 0.4}s`} />
            </circle>
          ))}
        </svg>
        {/* hz label */}
        <div className="mono" style={{
          position: 'absolute', top: 10, left: 10,
          fontSize: 8, color: 'rgba(180,154,212,0.85)', letterSpacing: '0.14em',
        }}>· 18.4 кГц · нечутно</div>
      </div>
      <button onClick={() => setListening((l) => !l)} style={{
        appearance: 'none', cursor: 'pointer', border: 'none',
        padding: '13px', borderRadius: 14,
        background: listening ? '#B49AD4' : 'rgba(126,93,164,0.20)',
        color: listening ? '#1a0e1a' : '#B49AD4',
        font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
        border: listening ? 'none' : '0.5px solid rgba(126,93,164,0.4)',
        boxShadow: listening ? '0 0 16px rgba(180,154,212,0.6)' : 'none',
      }}>{listening ? '· слухаю · говорить' : '· почати'}</button>
    </div>
  );
}

// ─── VouchFlow — mutual introduction through a friend ──────────────────────
function VouchFlow({ people, onPick }) {
  const paired = (people || window.PHANTOM_PEOPLE_SEED).filter((p) => p.paired && !p.archived).slice(0, 5);
  return (
    <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em', textTransform: 'uppercase', marginBottom: 6 }}>· vouch · через спільного</div>
        <div style={{ font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>скажи свого. фантом запитає його.</div>
        <div style={{ font: '300 11px/1.55 Georgia, serif', color: 'var(--ink-tertiary)', fontStyle: 'italic', marginTop: 5, textWrap: 'pretty' }}>
          твій pubkey пройде через підпис друга · нова людина побачить що ти не випадковий.
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {paired.map((p) => (
          <button key={p.id} onClick={() => onPick?.({ kind: 'vouch', via: p })} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            padding: '10px 12px', borderRadius: 12,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            display: 'flex', alignItems: 'center', gap: 12,
          }}>
            <PersonAvatar p={p} size={36} withRing={false} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ font: '500 12.5px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>через · {p.name}</div>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 3 }}>
                її коло · ~24 паровані phantom-и
              </div>
            </div>
            <span style={{ color: 'var(--gold-soft)' }}>→</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── StrangerSearch — phantom finds compatible souls in global mesh ─────────
function StrangerSearch({ onPick }) {
  const [phase, setPhase] = React.useState('searching');
  React.useEffect(() => {
    const t = setTimeout(() => setPhase('found'), 2800);
    return () => clearTimeout(t);
  }, []);
  const candidates = [
    { id: 's1', tag: 'переклад · поезія · туман', dist: 'mesh-3 hops · seoul', match: 0.84, c: '#B49AD4' },
    { id: 's2', tag: 'писати · вночі · одинак',    dist: 'mesh-2 hops · krakow', match: 0.71, c: '#9DCC9F' },
    { id: 's3', tag: 'ремесло · кераміка',           dist: 'mesh-4 hops · porto',  match: 0.62, c: '#FFD370' },
  ];
  return (
    <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em', textTransform: 'uppercase', marginBottom: 6 }}>· stranger · резонанс</div>
        <div style={{ font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>фантом шукає схожі душі.</div>
        <div style={{ font: '300 11px/1.55 Georgia, serif', color: 'var(--ink-tertiary)', fontStyle: 'italic', marginTop: 5, textWrap: 'pretty' }}>
          без імен · без обличь · лише вектори інтересів. знайдеш — потім вирішите чи зустрітись.
        </div>
      </div>
      <div style={{
        aspectRatio: '1', borderRadius: 18, position: 'relative', overflow: 'hidden',
        background: `radial-gradient(ellipse at 50% 50%, rgba(126,93,164,0.10), transparent 70%)`,
        border: '0.5px solid rgba(126,93,164,0.20)',
      }}>
        <svg viewBox="0 0 300 300" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* light constellations */}
          {Array.from({ length: 80 }).map((_, i) => {
            const x = ((i * 7919) % 280) + 10;
            const y = ((i * 13 * 31) % 280) + 10;
            const r = 0.6 + (i % 5) * 0.3;
            return <circle key={i} cx={x} cy={y} r={r} fill="rgba(255,230,180,0.5)" />;
          })}
          {/* center beacon */}
          <circle cx="150" cy="150" r="12" fill="url(#beacon)" />
          <defs>
            <radialGradient id="beacon">
              <stop offset="0%" stopColor="#F4AF25" />
              <stop offset="100%" stopColor="#F4AF25" stopOpacity="0" />
            </radialGradient>
          </defs>
          {phase === 'searching' && [0, 1, 2].map((i) => (
            <circle key={i} cx="150" cy="150" r="20" fill="none" stroke="rgba(244,175,37,0.5)" strokeWidth="0.6">
              <animate attributeName="r" values="20;140;140" dur="3.6s" repeatCount="indefinite" begin={`${i * 1.2}s`} />
              <animate attributeName="opacity" values="0.8;0;0" dur="3.6s" repeatCount="indefinite" begin={`${i * 1.2}s`} />
            </circle>
          ))}
          {/* found candidates */}
          {phase === 'found' && candidates.map((c, i) => {
            const ang = (i * 120 - 90) * Math.PI / 180;
            const x = 150 + Math.cos(ang) * 90;
            const y = 150 + Math.sin(ang) * 90;
            return (
              <g key={c.id}>
                <line x1="150" y1="150" x2={x} y2={y} stroke={c.c} strokeWidth="0.8" strokeOpacity="0.5" strokeDasharray="3 3" />
                <circle cx={x} cy={y} r="6" fill={c.c} style={{ filter: `drop-shadow(0 0 4px ${c.c})` }} />
                <text x={x} y={y + 18} textAnchor="middle" style={{ font: '400 8px var(--font-mono)', fill: 'var(--ink-secondary)', letterSpacing: '0.04em' }}>
                  {(c.match * 100 | 0)}%
                </text>
              </g>
            );
          })}
        </svg>
        <div className="mono" style={{
          position: 'absolute', left: 0, right: 0, bottom: 14, textAlign: 'center',
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em', textTransform: 'uppercase',
        }}>
          {phase === 'searching' ? '· phantom шукає · 47k mesh nodes ·' : '· 3 резонансу ·'}
        </div>
      </div>
      {phase === 'found' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {candidates.map((c) => (
            <button key={c.id} onClick={() => onPick?.({ kind: 'stranger', anon: c })} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '10px 12px', borderRadius: 12,
              background: `${c.c}10`,
              border: `0.5px solid ${c.c}33`,
              display: 'flex', alignItems: 'center', gap: 10,
            }}>
              <div style={{
                width: 24, height: 24, borderRadius: '50%',
                background: `radial-gradient(circle at 35% 30%, ${c.c}, ${c.c}55)`,
                boxShadow: `0 0 6px ${c.c}88`,
              }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ font: '500 11.5px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{c.tag}</div>
                <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 3 }}>
                  {c.dist}
                </div>
              </div>
              <span className="mono" style={{ fontSize: 11, color: c.c, fontVariantNumeric: 'tabular-nums' }}>
                {(c.match * 100 | 0)}%
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── FirstConnectFlow — method picker + dispatched ritual ───────────────────
function FirstConnectFlow({ open, onClose, onConnect, people }) {
  const [method, setMethod] = React.useState(null);
  React.useEffect(() => { if (!open) setMethod(null); }, [open]);

  if (!open) return null;

  const methods = [
    { k: 'near',     l: 'NEAR',     d: 'ble · радар · 3 м',     c: '#9DCC9F', ic: '⊙' },
    { k: 'qr',       l: 'QR',        d: 'дві камери, два phantom',  c: '#F4AF25', ic: '◫' },
    { k: 'chirp',    l: 'CHIRP',    d: 'звук · нечутно · 3 м',  c: '#B49AD4', ic: '◐' },
    { k: 'vouch',    l: 'VOUCH',    d: 'через спільного друга',     c: '#7CC0E8', ic: '⊕' },
    { k: 'stranger', l: 'STRANGER', d: 'резонанс · без імен',     c: '#FFD370', ic: '✦' },
  ];
  const pick = (p) => { onConnect?.({ method, payload: p }); onClose?.(); };

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 120,
      background: 'rgba(6,4,2,0.82)', backdropFilter: 'blur(10px)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      <div style={{ padding: '52px 18px 8px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={method ? () => setMethod(null) : onClose} style={{
          appearance: 'none', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.03)', cursor: 'pointer',
          color: 'var(--ink-secondary)', font: '400 11px/1 var(--font-mono)',
          padding: '6px 10px', borderRadius: 999, letterSpacing: '0.04em',
        }}>{method ? '← інший спосіб' : '× звести'}</button>
        <div style={{ flex: 1 }} />
        <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em' }}>
          · перший контакт ·
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '8px 18px 18px' }}>
        {!method && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div style={{
                font: '300 26px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.02em', marginBottom: 8,
              }}>як вони · твого фантома · вперше побачать?</div>
              <div style={{
                font: '300 11.5px/1.6 Georgia, serif', color: 'var(--ink-tertiary)',
                fontStyle: 'italic', textWrap: 'pretty',
              }}>
                ми не маємо серверу. ми не маємо ні твого імені, ні їхнього. лише пристрої, що звуть один одного.
              </div>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              {methods.map((m) => (
                <button key={m.k} onClick={() => setMethod(m.k)} style={{
                  appearance: 'none', cursor: 'pointer', textAlign: 'left',
                  padding: '14px', borderRadius: 14,
                  background: `radial-gradient(ellipse at 0% 0%, ${m.c}1c, transparent 60%), rgba(255,230,180,0.02)`,
                  border: `0.5px solid ${m.c}30`,
                  display: 'flex', alignItems: 'center', gap: 14,
                }}>
                  <div style={{
                    width: 38, height: 38, borderRadius: 10,
                    background: `${m.c}1f`, color: m.c,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    font: '500 18px/1 var(--font-display)',
                    boxShadow: `0 0 12px ${m.c}44`,
                  }}>{m.ic}</div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '0.04em' }}>{m.l}</div>
                    <div className="mono" style={{ fontSize: 10, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 3 }}>{m.d}</div>
                  </div>
                  <span style={{ color: m.c }}>→</span>
                </button>
              ))}
            </div>
          </div>
        )}
        {method === 'near'     && <NearRadar onPick={pick} />}
        {method === 'qr'       && <QRHandshake onPick={pick} />}
        {method === 'chirp'    && <AudioChirp onPick={pick} />}
        {method === 'vouch'    && <VouchFlow people={people} onPick={pick} />}
        {method === 'stranger' && <StrangerSearch onPick={pick} />}
      </div>
    </div>
  );
}

Object.assign(window, { FirstConnectFlow, NearRadar, QRHandshake, AudioChirp, VouchFlow, StrangerSearch });
