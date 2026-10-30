/* atlas.jsx — professional people atlas:
   AtlasOverview (5 tabs: atlas / currents / threads / groups / wisdom),
   AtlasCanvas (orbital constellation scales to 100s),
   MeshStatusBar, plus auto-generated extra contacts for scale demo. */

// ─── 200 synthetic contacts to demonstrate scale ───────────────────────────
window.PHANTOM_PEOPLE_DENSE = (() => {
  // start from seed
  const base = window.PHANTOM_PEOPLE_SEED.map((p) => ({ ...p, ring: p.temp > 0.7 ? 0 : p.temp > 0.45 ? 1 : 2 }));
  const givenNames = ['макс','тарас','роман','юра','іван','юрій','дмитро','петро','назар','адам','остап','ярема','матвій','влад','артур','соломія','ярина','христина','наталія','ірина','юлія','світла','міла','зоя','лана','софія','діна','еля','рая','віка','маша','саша','галя','оля','ніка','іра','неля','зеня','юна','роза'];
  const roles = ['колега','читач','музикант','архітектор','лікар','бариста','програміст','актриса','викладач','сусід','перекладач','графік','фотограф','куратор','психолог','куховар','адвокат','письменник','тренер','flutist'];
  const hues = ['#F4AF25','#FF8B6E','#9DCC9F','#7CC0E8','#B49AD4','#FFD370','#A7967D','#E76F51','#67B3A1','#D9A75E'];
  const arr = [...base];
  for (let i = 0; i < 200; i++) {
    const name = givenNames[i % givenNames.length] + (i > 39 ? ' ' + ('ʼ' + Math.floor(i/40)) : '');
    const t = 0.05 + ((i * 7919) % 100) / 110;
    const seen = ['щойно','15 хв','1 г','3 г','вчора','2 дн','5 дн','3 тиж','місяць','3 міс','рік','давно'][i % 12];
    arr.push({
      id: 'p' + i,
      name,
      role: roles[i % roles.length],
      hue: hues[i % hues.length],
      initials: name.split(' ')[0][0].toUpperCase(),
      paired: i % 3 === 0,
      temp: t,
      lastSeen: seen,
      unread: i % 19 === 0 ? (i % 4) + 1 : 0,
      ring: t > 0.7 ? 0 : t > 0.45 ? 1 : 2,
      note: '',
      favourite: false,
      permissions: i % 3 === 0 ? ['draft'] : [],
      synthetic: true,
    });
  }
  return arr;
})();

// ─── MeshStatusBar — always-visible decentralized footprint ─────────────────
function MeshStatusBar({ expanded, onToggle }) {
  return (
    <button onClick={onToggle} style={{
      appearance: 'none', cursor: 'pointer', width: '100%',
      textAlign: 'left', border: 'none',
      padding: '5px 12px 5px 14px',
      background: 'rgba(0,0,0,0.18)',
      borderBottom: '0.5px solid var(--glass-edge)',
      display: 'flex', alignItems: 'center', gap: 10,
      font: '300 9px/1 var(--font-mono)', color: 'var(--ink-tertiary)',
      letterSpacing: '0.08em',
    }}>
      <span style={{
        width: 5, height: 5, borderRadius: '50%', background: 'var(--sage)',
        animation: 'phantom-pulse-glow 1.8s ease-in-out infinite',
        boxShadow: '0 0 4px var(--sage)',
      }} />
      <span style={{ color: 'var(--sage)' }}>mesh · 7/7</span>
      <span>· rtt 4мс</span>
      <span>· e2e ed25519</span>
      <span style={{ flex: 1 }} />
      <span style={{ color: 'var(--gold-soft)' }}>· {expanded ? 'звести ∧' : 'мапа ∨'}</span>
    </button>
  );
}

// ─── MeshTopologyMini — expanded mesh status ────────────────────────────────
function MeshTopologyMini() {
  const peers = [
    { id: 'me',     name: 'ти',         x: 0.5,  y: 0.5,  c: '#F4AF25', mine: true },
    { id: 'olya',   name: 'оля',        x: 0.18, y: 0.32, c: '#FF8B6E' },
    { id: 'oleh',   name: 'олег',       x: 0.78, y: 0.22, c: '#9DCC9F' },
    { id: 'mama',   name: 'мама',       x: 0.2,  y: 0.78, c: '#FFD370' },
    { id: 'bohdan', name: 'богдан',     x: 0.84, y: 0.65, c: '#7CC0E8' },
    { id: 'r1',     name: 'relay-tor',   x: 0.5,  y: 0.18, c: '#A7967D', relay: true },
    { id: 'r2',     name: 'relay-kyiv',  x: 0.45, y: 0.86, c: '#A7967D', relay: true },
  ];
  return (
    <div className="fade-in" style={{
      padding: '10px 14px 14px',
      background: 'rgba(0,0,0,0.28)',
      borderBottom: '0.5px solid var(--glass-edge)',
    }}>
      <div style={{
        position: 'relative', height: 160, borderRadius: 12,
        background: `radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)`,
        border: '0.5px solid var(--glass-edge)',
        overflow: 'hidden',
      }}>
        <svg viewBox="0 0 320 160" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* connect all to me */}
          {peers.filter((p) => !p.mine).map((p) => (
            <line key={p.id}
                  x1={peers.find((m) => m.mine).x * 320} y1={peers.find((m) => m.mine).y * 160}
                  x2={p.x * 320} y2={p.y * 160}
                  stroke={p.relay ? 'rgba(167,150,125,0.3)' : `${p.c}80`}
                  strokeWidth={p.relay ? 0.6 : 1}
                  strokeDasharray={p.relay ? '4 3' : '0'}
                  style={{ filter: p.relay ? 'none' : `drop-shadow(0 0 3px ${p.c}66)` }} />
          ))}
          {/* pings */}
          {peers.filter((p) => !p.mine && !p.relay).map((p) => (
            <circle key={'ping-'+p.id} r="2.5" fill={p.c}
                    style={{ filter: `drop-shadow(0 0 3px ${p.c})` }}>
              <animate attributeName="cx"
                       values={`${peers.find((m) => m.mine).x * 320};${p.x * 320}`}
                       dur="2s" repeatCount="indefinite" begin={`${(p.id.charCodeAt(0) % 10) * 0.2}s`} />
              <animate attributeName="cy"
                       values={`${peers.find((m) => m.mine).y * 160};${p.y * 160}`}
                       dur="2s" repeatCount="indefinite" begin={`${(p.id.charCodeAt(0) % 10) * 0.2}s`} />
              <animate attributeName="opacity" values="0;1;0" dur="2s" repeatCount="indefinite"
                       begin={`${(p.id.charCodeAt(0) % 10) * 0.2}s`} />
            </circle>
          ))}
        </svg>
        {/* peer dots */}
        {peers.map((p) => (
          <div key={p.id} style={{
            position: 'absolute', left: `${p.x * 100}%`, top: `${p.y * 100}%`,
            transform: 'translate(-50%, -50%)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          }}>
            <div style={{
              width: p.mine ? 18 : p.relay ? 10 : 14,
              height: p.mine ? 18 : p.relay ? 10 : 14,
              borderRadius: '50%',
              background: p.relay
                ? `rgba(167,150,125,0.4)`
                : `radial-gradient(circle at 35% 30%, ${p.c}, ${p.c}55)`,
              border: p.mine ? `1.5px solid #fff` : `0.5px solid ${p.c}`,
              boxShadow: `0 0 ${p.mine ? 12 : 6}px ${p.c}88`,
              animation: p.mine ? 'phantom-pulse-glow 2.2s ease-in-out infinite' : 'none',
            }} />
            <span className="mono" style={{
              fontSize: 7, color: p.relay ? 'var(--ink-tertiary)' : 'var(--ink-secondary)',
              letterSpacing: '0.04em', whiteSpace: 'nowrap',
            }}>{p.name}</span>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 8,
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 6,
      }}>
        {[
          ['прямі',  '4 peer-to-peer'],
          ['relay',  '2 · tor · ne-mine'],
          ['rtt',     '4ms · 12ms · 88ms'],
          ['onion',   '3 шари'],
        ].map(([k, v]) => (
          <div key={k}>
            <div className="mono" style={{ fontSize: 7, color: 'var(--ink-tertiary)', letterSpacing: '0.12em', textTransform: 'uppercase' }}>{k}</div>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-strong)', letterSpacing: '0.02em', marginTop: 2 }}>{v}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── AtlasCanvas — orbital constellation, scales to 100s ────────────────────
function AtlasCanvas({ people, onOpen, onPreview, query }) {
  const wrapRef = React.useRef(null);
  const [size, setSize] = React.useState({ w: 360, h: 360 });
  const [zoom, setZoom] = React.useState(1);
  const [rot, setRot] = React.useState(0);
  const [hover, setHover] = React.useState(null);
  const [dragging, setDragging] = React.useState(false);

  React.useEffect(() => {
    if (!wrapRef.current) return;
    const r = wrapRef.current.getBoundingClientRect();
    setSize({ w: r.width, h: r.height });
  }, []);

  const cx = size.w / 2, cy = size.h / 2;
  const maxR = Math.min(size.w, size.h) * 0.46;

  // bucket people by ring
  const rings = [[], [], []];
  people.forEach((p) => { if (!p.archived) rings[p.ring].push(p); });

  // auto-rotate while not dragging
  React.useEffect(() => {
    if (dragging) return;
    let raf;
    const tick = () => { setRot((r) => r + 0.0008); raf = requestAnimationFrame(tick); };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [dragging]);

  // pan/drag handler
  const onPointerDown = (e) => {
    setDragging(true);
    const startX = e.clientX;
    const startRot = rot;
    const move = (ev) => setRot(startRot + (ev.clientX - startX) * 0.005);
    const up = () => {
      setDragging(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  // for each ring, compute positions
  const placed = rings.flatMap((bucket, ring) => {
    const r = maxR * (0.32 + ring * 0.32) * zoom;
    return bucket.map((p, i) => {
      // golden-angle distribution within ring for even spread
      const ang = i * 2.39996 + rot * (1 + ring * 0.5) + ring;
      return {
        ...p,
        x: cx + Math.cos(ang) * r,
        y: cy + Math.sin(ang) * r,
        ring,
      };
    });
  });

  // filter for query highlight
  const matchesQuery = (p) => query && query.length > 0 &&
    p.name.toLowerCase().includes(query.toLowerCase());

  return (
    <div ref={wrapRef} onPointerDown={onPointerDown}
         style={{
           flex: 1, position: 'relative', overflow: 'hidden',
           cursor: dragging ? 'grabbing' : 'grab', minHeight: 0,
           background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)',
         }}>
      <svg width={size.w} height={size.h}
           style={{ position: 'absolute', inset: 0 }}>
        {/* concentric rings */}
        {[0.32, 0.64, 0.96].map((rPct, i) => (
          <circle key={i} cx={cx} cy={cy} r={maxR * rPct * zoom}
                  fill="none" stroke="rgba(244,175,37,0.06)" strokeWidth="0.5"
                  strokeDasharray={i === 0 ? '0' : '2 4'} />
        ))}
        {/* radial guides */}
        {[0, 60, 120, 180, 240, 300].map((deg) => {
          const ang = (deg * Math.PI) / 180;
          return (
            <line key={deg}
                  x1={cx + Math.cos(ang) * 16} y1={cy + Math.sin(ang) * 16}
                  x2={cx + Math.cos(ang) * maxR * zoom} y2={cy + Math.sin(ang) * maxR * zoom}
                  stroke="rgba(244,175,37,0.04)" strokeWidth="0.4" />
          );
        })}
        {/* ring labels */}
        {['тісне коло','помірно','далеке'].map((l, i) => (
          <text key={i} x={cx + maxR * (0.32 + i * 0.32) * zoom - 4} y={cy + 8}
                style={{ font: '300 8px var(--font-mono)', fill: 'rgba(244,175,37,0.4)', letterSpacing: '0.06em' }}>
            {l}
          </text>
        ))}
      </svg>
      {/* people dots */}
      {placed.map((p) => {
        const dim = (zoom < 0.8 && p.ring === 2) ? 0.45 : 1;
        const isMatch = matchesQuery(p);
        const sz = isMatch ? 12 : 6 + (1 - p.ring * 0.3) * 3;
        return (
          <button key={p.id}
                  onClick={(e) => { e.stopPropagation(); onOpen?.(p); }}
                  onMouseEnter={() => { setHover(p); onPreview?.(p); }}
                  onMouseLeave={() => { setHover((h) => h?.id === p.id ? null : h); onPreview?.(null); }}
                  style={{
                    position: 'absolute', left: p.x - sz/2, top: p.y - sz/2,
                    width: sz, height: sz, borderRadius: '50%', border: 'none',
                    background: `radial-gradient(circle at 35% 30%, ${p.hue}, ${p.hue}66 60%, ${p.hue}22)`,
                    boxShadow: isMatch
                      ? `0 0 12px ${p.hue}, 0 0 4px #fff`
                      : `0 0 ${4 + p.temp * 4}px ${p.hue}88`,
                    opacity: dim,
                    cursor: 'pointer', padding: 0,
                    transition: 'box-shadow 0.18s, transform 0.18s',
                    transform: hover?.id === p.id ? 'scale(1.6)' : 'scale(1)',
                    zIndex: hover?.id === p.id ? 5 : 1,
                    animation: p.paired ? `phantom-pulse-glow ${2 + (p.id.length % 4) * 0.4}s ease-in-out infinite` : 'none',
                  }} />
        );
      })}
      {/* center = you */}
      <div style={{
        position: 'absolute', left: cx - 16, top: cy - 16,
        width: 32, height: 32, borderRadius: '50%',
        background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
        border: '1.5px solid #fff', boxShadow: '0 0 18px var(--gold-glow)',
        animation: 'phantom-breathe 6s ease-in-out infinite',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        font: '500 12px/1 var(--font-display)', color: '#1A140B',
      }}>·</div>
      {/* hover tooltip */}
      {hover && (
        <div className="glass" style={{
          position: 'absolute',
          left: Math.min(size.w - 180, Math.max(8, hover.x + 16)),
          top: Math.min(size.h - 80, Math.max(8, hover.y - 30)),
          padding: '8px 12px', borderRadius: 12,
          pointerEvents: 'none',
          zIndex: 10, minWidth: 140,
          background: 'rgba(20,14,10,0.92)',
          border: `0.5px solid ${hover.hue}55`,
        }}>
          <div style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)', display: 'flex', alignItems: 'baseline', gap: 6 }}>
            {hover.name}
            <span className="mono" style={{ fontSize: 7.5, color: tempColor(hover.temp), letterSpacing: '0.1em' }}>· {tempLabel(hover.temp)}</span>
          </div>
          <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', marginTop: 3, letterSpacing: '0.04em' }}>
            {hover.role}
          </div>
          {hover.paired && (
            <div className="mono" style={{ fontSize: 7.5, color: 'var(--gold-soft)', letterSpacing: '0.1em', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ width: 4, height: 4, borderRadius: '50%', background: 'var(--gold)' }} />
              phantom paired
            </div>
          )}
        </div>
      )}
      {/* zoom controls */}
      <div style={{
        position: 'absolute', right: 10, bottom: 10,
        display: 'flex', flexDirection: 'column', gap: 4,
        background: 'rgba(20,14,10,0.7)', backdropFilter: 'blur(10px)',
        padding: 4, borderRadius: 999,
        border: '0.5px solid var(--glass-edge)',
      }}>
        {[
          { l: '+', fn: () => setZoom((z) => Math.min(2.4, z + 0.3)) },
          { l: '·', fn: () => { setZoom(1); setRot(0); } },
          { l: '−', fn: () => setZoom((z) => Math.max(0.5, z - 0.3)) },
        ].map((b) => (
          <button key={b.l} onClick={b.fn} style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 22, height: 22, borderRadius: '50%',
            background: 'transparent', color: 'var(--ink-strong)',
            font: '400 12px/1 var(--font-mono)',
          }}>{b.l}</button>
        ))}
      </div>
      {/* counter */}
      <div className="mono" style={{
        position: 'absolute', left: 12, top: 10,
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
        background: 'rgba(20,14,10,0.5)', backdropFilter: 'blur(8px)',
        padding: '3px 8px', borderRadius: 999,
        border: '0.5px solid var(--glass-edge)',
      }}>· {people.filter((p) => !p.archived).length} людей · {zoom.toFixed(1)}×</div>
    </div>
  );
}

// ─── CurrentsList — live moments happening now ──────────────────────────────
function CurrentsList({ people, onOpen }) {
  const moments = [
    { kind: 'typing',  who: 'olya',   t: 'пише тобі · 4 секунди' },
    { kind: 'near',    who: 'oleh',   t: 'у 240 м від тебе · кафе fika' },
    { kind: 'callelse',who: 'bohdan', t: 'у дзвінку · з anastasiya' },
    { kind: 'pinged',  who: 'mama',   t: 'її фантом щойно зустрів твого' },
    { kind: 'online',  who: 'anya',   t: 'онлайн · 12 хв · мовчить' },
    { kind: 'p3',      who: 'p3',     t: 'переслав тобі вірш · через мережу · ще не прочитав' },
    { kind: 'p17',     who: 'p17',    t: 'явно сумує · phantom радить почекати' },
  ];
  const kindColor = { typing: '#F4AF25', near: '#9DCC9F', callelse: '#FF8B6E', pinged: '#FFD370', online: '#7CC0E8' };
  return (
    <div style={{ padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div className="mono" style={{
        padding: '6px 4px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.28em', textTransform: 'uppercase',
      }}>· живі · щойно ·</div>
      {moments.map((m, i) => {
        const p = people.find((pp) => pp.id === m.who);
        if (!p) return null;
        const c = kindColor[m.kind] || '#F4AF25';
        return (
          <button key={i} onClick={() => onOpen?.(p)} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            padding: '10px 12px', borderRadius: 12,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            borderLeft: `2px solid ${c}`,
            display: 'flex', alignItems: 'center', gap: 12,
            position: 'relative', overflow: 'hidden',
          }}>
            <div style={{
              position: 'absolute', left: 0, right: 0, top: 0, height: 1,
              background: `linear-gradient(to right, ${c}40, transparent)`,
            }} />
            <PersonAvatar p={p} size={36} withRing={false} withPulse={m.kind === 'typing'} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                <span style={{ font: '500 12.5px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{p.name}</span>
                <span className="mono" style={{ fontSize: 8, color: c, letterSpacing: '0.16em', textTransform: 'uppercase' }}>· {m.kind}</span>
              </div>
              <div style={{
                font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
                marginTop: 3, textWrap: 'pretty',
              }}>{m.t}</div>
            </div>
            {m.kind === 'typing' && (
              <div style={{ display: 'flex', gap: 2, alignItems: 'center', height: 12 }}>
                {[0, 1, 2].map((j) => (
                  <span key={j} style={{
                    width: 4, height: 4, borderRadius: '50%', background: c,
                    animation: `phantom-pulse-glow 1.2s ease-in-out ${j * 0.18}s infinite`,
                  }} />
                ))}
              </div>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ─── ThreadsList — active conversations sorted by urgency ───────────────────
function ThreadsList({ people, onOpen }) {
  const threads = [
    { who: 'olya',   urg: 'high',  sum: 'нервова перед нотаріусом завтра · хоче ствердження не плану',                last: 'я думала ми мали говорити сьогодні · не сьогодні так?',         t: '12 хв', need: 'відповіcти зараз' },
    { who: 'mama',   urg: 'high',  sum: 'три дзвінки за тиждень без відповіді · хвилюється про твій сон',              last: 'передзвони коли встанеш',                                       t: '3 г',  need: 'передзвонити' },
    { who: 'oleh',   urg: 'med',   sum: 'вибачився за різкість на standup · ти ще не відповів',                          last: 'привіт. за вчорашнє — я був різкий. вибач.',                  t: 'вчора', need: 'відповіcти м\'яко' },
    { who: 'bohdan', urg: 'low',   sum: 'формальний звіт · phantom вже зробив summary · можна не читати',              last: 'надсилаю апдейт на тиждень · 14 пунктів',                       t: '2 г',  need: '· phantom прочитав за тебе' },
    { who: 'anya',   urg: 'low',   sum: 'довга тиша · 5 днів · не критично · сезонне',                                  last: '...',                                                            t: '5 дн', need: '· можна не зараз' },
  ];
  const colors = { high: '#FF8B6E', med: '#F4AF25', low: 'var(--ink-secondary)' };
  return (
    <div style={{ padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div className="mono" style={{
        padding: '6px 4px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.28em', textTransform: 'uppercase',
      }}>· нитки · сортовано фантомом · за нагальністю</div>
      {threads.map((th, i) => {
        const p = people.find((pp) => pp.id === th.who);
        if (!p) return null;
        return (
          <button key={i} onClick={() => onOpen?.(p)} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            padding: '10px 12px', borderRadius: 12,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 5 }}>
              <PersonAvatar p={p} size={28} withRing={false} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                  <span style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{p.name}</span>
                  <span style={{
                    padding: '1px 6px', borderRadius: 999, fontSize: 7.5,
                    background: `${colors[th.urg]}1f`, color: colors[th.urg],
                    letterSpacing: '0.14em', textTransform: 'uppercase', fontWeight: 600,
                  }}>· {th.urg}</span>
                  <span style={{ flex: 1 }} />
                  <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)' }}>{th.t}</span>
                </div>
              </div>
            </div>
            <div style={{
              font: '400 11px/1.45 var(--font-body)', color: 'var(--ink-strong)',
              marginBottom: 5, textWrap: 'pretty',
              padding: '5px 8px', borderRadius: 6,
              background: 'rgba(0,0,0,0.18)',
            }}>«{th.last}»</div>
            <div className="mono" style={{
              fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.14em',
              textTransform: 'uppercase', marginBottom: 3,
            }}>· phantom · summary</div>
            <div style={{
              font: '300 11px/1.5 Georgia, serif', color: 'var(--ink-secondary)',
              fontStyle: 'italic', textWrap: 'pretty', marginBottom: 6,
            }}>«{th.sum}»</div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="mono" style={{
                fontSize: 8.5, color: colors[th.urg], letterSpacing: '0.08em',
              }}>→ {th.need}</span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

// ─── WisdomList — phantom's recommendations ────────────────────────────────
function WisdomList({ people, onOpen }) {
  const items = [
    { kind: 'reach', who: 'mama',   t: 'досягни', l: 'два дні без дзвінка · вона знов хвилюється · добрий момент тепер коли ти прокинувся виспаним' },
    { kind: 'wait',  who: 'oleh',   t: 'зачекай', l: 'не сьогодні · післязавтра вранці · його вікно після стендапу мʼяке' },
    { kind: 'see',   who: 'olya',   t: 'побач',   l: 'вона написала «не сьогодні так?» · це питання · не відповідь' },
    { kind: 'stop',  who: 'p3',     t: 'не',       l: 'він просив тишу на тиждень · ти вже хотів написати · не пиши' },
    { kind: 'invite',who: 'anya',   t: 'запроси', l: '5 днів без розмови — вона не образилась · просто чекає коли ти запропонуєш каву' },
  ];
  const kindColor = { reach: '#9DCC9F', wait: '#F4AF25', see: '#7CC0E8', stop: '#FF8B6E', invite: '#FFD370' };
  return (
    <div style={{ padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div className="mono" style={{
        padding: '6px 4px 10px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.28em', textTransform: 'uppercase',
      }}>· phantom · кого досягнути сьогодні</div>
      <div style={{
        padding: '6px 4px 14px',
        font: '300 11.5px/1.6 Georgia, serif', color: 'var(--ink-tertiary)',
        fontStyle: 'italic', textWrap: 'pretty',
      }}>
        не алгоритм. не задача-лист. живі люди — кому потрібно щоб ти зʼявився, і кому — щоб ні.
      </div>
      {items.map((it, i) => {
        const p = people.find((pp) => pp.id === it.who);
        if (!p) return null;
        const c = kindColor[it.kind];
        return (
          <button key={i} onClick={() => onOpen?.(p)} style={{
            appearance: 'none', cursor: 'pointer', textAlign: 'left',
            padding: '12px', borderRadius: 14,
            background: `radial-gradient(ellipse at 0% 0%, ${c}1c, transparent 60%), rgba(255,230,180,0.02)`,
            border: `0.5px solid ${c}33`,
            display: 'flex', alignItems: 'center', gap: 12,
          }}>
            <PersonAvatar p={p} size={42} withRing={true} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 4 }}>
                <span style={{
                  padding: '2px 8px', borderRadius: 999, fontSize: 9,
                  background: `${c}22`, color: c, letterSpacing: '0.14em',
                  textTransform: 'uppercase', fontWeight: 600,
                }}>· {it.t} ·</span>
                <span style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{p.name}</span>
              </div>
              <div style={{
                font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
                textWrap: 'pretty',
              }}>{it.l}</div>
            </div>
          </button>
        );
      })}
    </div>
  );
}

// ─── AtlasOverview — main panel that replaces PeoplePanel ───────────────────
function AtlasOverview({
  open, onClose, people, groups,
  onOpenPerson, onOpenGroup, onEditPerson, onEditGroup,
  onAddPerson, onAddGroup, onConnect,
}) {
  const [tab, setTab] = React.useState('atlas');
  const [query, setQuery] = React.useState('');
  const [meshOpen, setMeshOpen] = React.useState(false);
  const [guideOpen, setGuideOpen] = React.useState(true);

  const dense = React.useMemo(() => {
    return (window.PHANTOM_PEOPLE_DENSE || people).filter((p) => !p.archived);
  }, [people]);

  const liveCount = people.filter((p) => !p.archived).length;

  return (
    <>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 90,
        background: open ? 'rgba(0,0,0,0.6)' : 'transparent',
        backdropFilter: open ? 'blur(6px)' : 'none',
        opacity: open ? 1 : 0, pointerEvents: open ? 'auto' : 'none',
        transition: 'all 0.32s cubic-bezier(.25,.8,.35,1)',
      }} />
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 95,
        height: '92%', transform: open ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 0.42s cubic-bezier(.3,.7,.35,1)',
        display: 'flex', flexDirection: 'column',
      }}>
        <div className="glass hero" style={{
          flex: 1, margin: '0 4px', display: 'flex', flexDirection: 'column',
          borderRadius: '28px 28px 0 0',
          borderBottom: 'none',
          background: `
            radial-gradient(ellipse at 50% 0%, rgba(244,175,37,0.14), transparent 50%),
            radial-gradient(ellipse at 100% 100%, rgba(126,93,164,0.08), transparent 60%),
            rgba(15,11,7,0.95)`,
          backdropFilter: 'blur(32px) saturate(150%)',
          minHeight: 0,
        }}>
          {/* drag handle */}
          <div style={{
            width: 40, height: 4, background: 'var(--ink-faint)',
            borderRadius: 2, margin: '8px auto 4px',
          }} />
          {/* mesh status bar — decentralized identity */}
          <MeshStatusBar expanded={meshOpen} onToggle={() => setMeshOpen((x) => !x)} />
          {meshOpen && <MeshTopologyMini />}
          {/* hero */}
          <div style={{
            padding: '14px 18px 6px',
            display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 4,
              }}>· твій круг</div>
              <div style={{
                font: '300 22px/1.05 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>атлас · {liveCount + (window.PHANTOM_PEOPLE_DENSE?.length - liveCount || 0)} людей</div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 4,
              }}>{liveCount} живих · {window.PHANTOM_PEOPLE_DENSE?.length - liveCount} у далеких орбітах</div>
            </div>
            <div style={{ display: 'flex', gap: 4 }}>
              <button onClick={onConnect} style={{
                appearance: 'none', cursor: 'pointer', border: '0.5px solid rgba(157,204,159,0.4)',
                padding: '8px 12px', borderRadius: 999,
                background: 'rgba(157,204,159,0.10)',
                color: '#9DCC9F', font: '500 10.5px/1 var(--font-display)',
                letterSpacing: '0.04em',
              }}>· звести нового</button>
              <button onClick={tab === 'groups' ? onAddGroup : onAddPerson} style={{
                appearance: 'none', cursor: 'pointer', border: 'none',
                width: 34, height: 34, borderRadius: '50%',
                background: 'rgba(244,175,37,0.94)',
                color: '#1A140B', boxShadow: '0 0 14px var(--gold-glow)',
                font: '300 22px/1 var(--font-display)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>+</button>
            </div>
          </div>
          {/* tabs */}
          <div style={{
            padding: '0 14px',
            display: 'flex', gap: 4,
            borderBottom: '0.5px solid var(--glass-edge)',
            overflow: 'auto', scrollbarWidth: 'none',
          }}>
            {[
              { id: 'atlas',    l: 'atlas',     c: dense.length },
              { id: 'currents', l: 'живі',      c: 7 },
              { id: 'threads',  l: 'нитки',     c: 5 },
              { id: 'groups',   l: 'групи',     c: groups.length },
              { id: 'wisdom',   l: 'мудрість',  c: 5 },
            ].map((t) => (
              <button key={t.id} onClick={() => setTab(t.id)} style={{
                appearance: 'none', cursor: 'pointer', border: 'none',
                padding: '10px 12px', flexShrink: 0,
                background: 'transparent',
                color: tab === t.id ? 'var(--gold)' : 'var(--ink-secondary)',
                font: '500 11px/1 var(--font-display)',
                letterSpacing: '0.02em',
                position: 'relative',
                display: 'flex', alignItems: 'center', gap: 5,
              }}>
                {t.l}
                <span style={{
                  font: '300 8.5px/1 var(--font-mono)',
                  color: tab === t.id ? 'var(--gold-soft)' : 'var(--ink-tertiary)',
                  padding: '1px 5px', borderRadius: 999,
                  background: tab === t.id ? 'rgba(244,175,37,0.10)' : 'transparent',
                }}>{t.c}</span>
                {tab === t.id && (
                  <div style={{
                    position: 'absolute', left: 12, right: 12, bottom: -0.5,
                    height: 1.5, background: 'var(--gold)',
                    boxShadow: '0 0 6px var(--gold-glow)',
                  }} />
                )}
              </button>
            ))}
          </div>
          {/* search */}
          {(tab === 'atlas' || tab === 'currents' || tab === 'threads' || tab === 'wisdom') && (
            <div style={{ padding: '8px 14px' }}>
              <div style={{
                padding: '7px 12px', borderRadius: 999,
                background: 'rgba(20,14,10,0.5)',
                border: '0.5px solid var(--glass-edge)',
                display: 'flex', alignItems: 'center', gap: 8,
              }}>
                <svg width="12" height="12" viewBox="0 0 12 12" style={{ flexShrink: 0, opacity: 0.5 }}>
                  <circle cx="5" cy="5" r="3" fill="none" stroke="var(--ink-secondary)" strokeWidth="0.8" />
                  <path d="M7 7L10 10" stroke="var(--ink-secondary)" strokeWidth="0.8" />
                </svg>
                <input
                  value={query} onChange={(e) => setQuery(e.target.value)}
                  placeholder={tab === 'atlas' ? 'підсвітити · ім\'я або фрагмент' : 'знайти'}
                  style={{
                    flex: 1, appearance: 'none', border: 'none', outline: 'none',
                    background: 'transparent', font: '400 12px/1 var(--font-body)',
                    color: 'var(--ink-strong)',
                  }} />
              </div>
            </div>
          )}
          {/* content */}
          <div style={{ flex: 1, overflow: 'auto', minHeight: 0,
            display: 'flex', flexDirection: 'column' }}>
            {tab === 'atlas' && (
              <>
                {guideOpen && <AtlasGuide onDismiss={() => setGuideOpen(false)} />}
                <AtlasCanvas people={dense} query={query} onOpen={onOpenPerson} />
              </>
            )}
            {tab === 'currents'  && <CurrentsList people={dense} onOpen={onOpenPerson} />}
            {tab === 'threads'   && <ThreadsList   people={dense} onOpen={onOpenPerson} />}
            {tab === 'groups'    && (
              <div style={{ padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 4 }}>
                {groups.map((g) => (
                  <GroupRow key={g.id} g={g} people={people}
                            onOpen={onOpenGroup}
                            onLongPress={onEditGroup} />
                ))}
              </div>
            )}
            {tab === 'wisdom'    && <WisdomList    people={dense} onOpen={onOpenPerson} />}
          </div>
        </div>
      </div>
    </>
  );
}

Object.assign(window, { AtlasOverview, MeshStatusBar, MeshTopologyMini, AtlasCanvas, CurrentsList, ThreadsList, WisdomList });
