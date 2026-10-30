/* people-add.jsx — AddPersonFlow:
   4 cinematic steps — method, details, permissions, handshake.
   Plus permission picker as a reusable block. */

// ─── colour swatches ────────────────────────────────────────────────────────
const PERSON_HUES = [
  { v: '#F4AF25', n: 'амбер' },
  { v: '#FF8B6E', n: 'корал' },
  { v: '#9DCC9F', n: 'мох' },
  { v: '#7CC0E8', n: 'небо' },
  { v: '#B49AD4', n: 'аметист' },
  { v: '#FFD370', n: 'медовий' },
  { v: '#A7967D', n: 'пісок' },
  { v: '#E76F51', n: 'червоний' },
];

// ─── permissions catalog ────────────────────────────────────────────────────
const PERMISSIONS = [
  { k: 'draft',    l: 'писати чернетки',         d: 'phantom готує відповіді — ти підписуєш', tier: 'soft' },
  { k: 'answer',   l: 'відповідати за тебе',     d: 'на буденне · «дякую» · «отримав»', tier: 'hard' },
  { k: 'mood',     l: 'читати настрій',           d: 'аналіз твоїх повідомлень · твоєму phantom',     tier: 'soft' },
  { k: 'voice',    l: 'клон голосу',             d: 'для голосових · твоїм голосом · якщо ти зайнятий', tier: 'hard' },
  { k: 'photos',   l: 'спільні фото',             d: 'автоматично ділитись із сімейним альбомом', tier: 'soft' },
  { k: 'location', l: 'жива локація',             d: 'оля бачить де ти · 30 хв вікно',              tier: 'medium' },
  { k: 'meet',     l: 'узгоджувати зустрічі',    d: 'phantom × phantom · знайти спільний час', tier: 'soft' },
  { k: 'vault',   l: 'фрагменти vault',           d: 'тільки помічені «для оля» · 6-секунд паролі', tier: 'hard' },
  { k: 'forecast', l: 'прогноз стосунків',        d: 'phantom бачить як ваші вектори сходяться', tier: 'medium' },
];

// ─── PermissionPicker — toggleable list ─────────────────────────────────────
function PermissionPicker({ value = [], onChange }) {
  const toggle = (k) => {
    if (value.includes(k)) onChange(value.filter((x) => x !== k));
    else onChange([...value, k]);
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      {PERMISSIONS.map((p) => {
        const on = value.includes(p.k);
        const tone = p.tier === 'hard' ? '#FF8B6E' : p.tier === 'medium' ? '#F4AF25' : '#9DCC9F';
        return (
          <button key={p.k} onClick={() => toggle(p.k)} style={{
            appearance: 'none', cursor: 'pointer', width: '100%',
            textAlign: 'left', padding: '10px 12px', borderRadius: 12,
            background: on ? 'rgba(244,175,37,0.08)' : 'rgba(255,230,180,0.025)',
            border: `0.5px solid ${on ? 'var(--gold-soft)' : 'var(--glass-edge)'}`,
            display: 'grid', gridTemplateColumns: '1fr 36px',
            gap: 10, alignItems: 'center',
            transition: 'all 0.18s',
          }}>
            <div style={{ minWidth: 0 }}>
              <div style={{
                font: '500 12px/1.2 var(--font-display)',
                color: on ? 'var(--gold)' : 'var(--ink-strong)',
                marginBottom: 4,
              }}>{p.l}</div>
              <div style={{
                display: 'flex', alignItems: 'baseline', gap: 6,
                flexWrap: 'wrap',
              }}>
                <span className="mono" style={{
                  flexShrink: 0,
                  fontSize: 7.5, color: tone, letterSpacing: '0.16em',
                  textTransform: 'uppercase',
                  padding: '1px 5px', borderRadius: 999,
                  background: `${tone}1f`,
                  border: `0.5px solid ${tone}40`,
                }}>· {p.tier}</span>
                <span style={{
                  font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-tertiary)',
                  textWrap: 'pretty', minWidth: 0, flex: 1,
                }}>{p.d}</span>
              </div>
            </div>
            {/* toggle */}
            <div style={{
              width: 30, height: 18, borderRadius: 999,
              background: on ? 'var(--gold)' : 'rgba(255,230,180,0.08)',
              position: 'relative',
              transition: 'background 0.2s',
              boxShadow: on ? '0 0 8px var(--gold-glow)' : 'none',
            }}>
              <div style={{
                position: 'absolute', top: 2,
                left: on ? 14 : 2,
                width: 14, height: 14, borderRadius: '50%',
                background: on ? '#1A140B' : 'var(--ink-secondary)',
                transition: 'left 0.22s cubic-bezier(.3,.7,.4,1.2)',
              }} />
            </div>
          </button>
        );
      })}
    </div>
  );
}

// ─── HandshakeStage — animated meeting of two orbs ──────────────────────────
function HandshakeStage({ yourHue = '#F4AF25', theirHue, name }) {
  const [phase, setPhase] = React.useState('approach');
  React.useEffect(() => {
    const t1 = setTimeout(() => setPhase('touch'), 1100);
    const t2 = setTimeout(() => setPhase('bond'), 2300);
    const t3 = setTimeout(() => setPhase('done'), 3600);
    return () => { clearTimeout(t1); clearTimeout(t2); clearTimeout(t3); };
  }, []);
  const yourX = phase === 'approach' ? '12%' : phase === 'touch' ? '38%' : '42%';
  const theirX = phase === 'approach' ? '88%' : phase === 'touch' ? '62%' : '58%';
  const showRing = phase === 'bond' || phase === 'done';
  return (
    <div style={{
      position: 'relative', height: 220, overflow: 'hidden',
      background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.06), transparent 65%)',
      borderRadius: 18,
    }}>
      {/* connection ring on bond */}
      {showRing && (
        <div className="fade-in" style={{
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 110, height: 110, borderRadius: '50%',
          border: `1px solid ${yourHue}`,
          boxShadow: `0 0 14px ${yourHue}66, inset 0 0 14px ${theirHue || yourHue}40`,
        }} />
      )}
      {/* link between */}
      <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <defs>
          <linearGradient id="handshake-link" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor={yourHue} />
            <stop offset="100%" stopColor={theirHue || '#FFD370'} />
          </linearGradient>
        </defs>
        <line x1={yourX} y1="50%" x2={theirX} y2="50%"
              stroke="url(#handshake-link)" strokeWidth="1.2"
              strokeDasharray={phase === 'approach' ? '4 4' : '0'}
              opacity={phase === 'approach' ? 0.4 : 1}
              style={{ filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
        {/* travelling packet */}
        {phase !== 'approach' && (
          <>
            <circle r="2.5" fill="#FFD370">
              <animate attributeName="cx" values={`${yourX};${theirX};${yourX}`} dur="2s" repeatCount="indefinite" />
              <animate attributeName="cy" values="50%;50%;50%" dur="2s" repeatCount="indefinite" />
            </circle>
          </>
        )}
      </svg>
      {/* your orb */}
      <div style={{
        position: 'absolute', left: yourX, top: '50%',
        transform: 'translate(-50%, -50%)',
        width: 52, height: 52, borderRadius: '50%',
        background: `radial-gradient(circle at 35% 30%, ${yourHue}, ${yourHue}66 50%, ${yourHue}22 90%)`,
        border: `0.5px solid ${yourHue}`,
        boxShadow: `0 0 18px ${yourHue}88`,
        animation: 'phantom-pulse-glow 2s ease-in-out infinite',
        transition: 'left 1.1s cubic-bezier(.3,.7,.35,1)',
      }} />
      <div style={{
        position: 'absolute', left: yourX, top: 'calc(50% + 36px)',
        transform: 'translate(-50%, 0)',
        font: '500 9.5px/1 var(--font-display)', color: yourHue,
        letterSpacing: '0.02em',
        transition: 'left 1.1s cubic-bezier(.3,.7,.35,1)',
      }}>ти</div>
      {/* their orb */}
      <div style={{
        position: 'absolute', left: theirX, top: '50%',
        transform: 'translate(-50%, -50%)',
        width: 52, height: 52, borderRadius: '50%',
        background: `radial-gradient(circle at 35% 30%, ${theirHue || '#FFD370'}, ${theirHue || '#FFD370'}66 50%)`,
        border: `0.5px solid ${theirHue || '#FFD370'}`,
        boxShadow: `0 0 18px ${theirHue || '#FFD370'}88`,
        animation: 'phantom-pulse-glow 2.2s ease-in-out infinite',
        transition: 'left 1.1s cubic-bezier(.3,.7,.35,1)',
      }} />
      <div style={{
        position: 'absolute', left: theirX, top: 'calc(50% + 36px)',
        transform: 'translate(-50%, 0)',
        font: '500 9.5px/1 var(--font-display)', color: theirHue || '#FFD370',
        letterSpacing: '0.02em',
        transition: 'left 1.1s cubic-bezier(.3,.7,.35,1)',
      }}>{name || 'новий'}</div>
      {/* status caption */}
      <div className="mono" style={{
        position: 'absolute', left: 0, right: 0, top: 16, textAlign: 'center',
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em',
        textTransform: 'uppercase',
      }}>
        {phase === 'approach' ? '· наближаюсь ·' :
         phase === 'touch' ? '· перевіряю ключі ·' :
         phase === 'bond'  ? '· звʼязок встановлено ·' :
         '· готово ·'}
      </div>
      {phase === 'done' && (
        <div className="fade-in" style={{
          position: 'absolute', left: 0, right: 0, bottom: 22, textAlign: 'center',
        }}>
          <div style={{
            display: 'inline-block', padding: '5px 12px',
            borderRadius: 999, background: 'rgba(244,175,37,0.94)',
            color: '#1A140B', font: '600 9.5px/1 var(--font-display)',
            letterSpacing: '0.16em', textTransform: 'uppercase',
            boxShadow: '0 0 16px var(--gold-glow)',
          }}>спарено · ed25519</div>
        </div>
      )}
    </div>
  );
}

// ─── AddPersonFlow — full modal with 4 steps ────────────────────────────────
function AddPersonFlow({ open, onClose, onCreate }) {
  const [step, setStep] = React.useState(0);
  const [method, setMethod] = React.useState(null);
  const [name, setName] = React.useState('');
  const [role, setRole] = React.useState('');
  const [hue, setHue] = React.useState(PERSON_HUES[0].v);
  const [paired, setPaired] = React.useState(true);
  const [perms, setPerms] = React.useState(['draft', 'mood']);

  // suggestion library — phantom proposes someone you mentioned recently
  const suggestion = {
    id: 'nina',
    name: 'ніна',
    hint: '· ти згадав її 3× за тиждень · контакт не доданий · додати?',
    role: 'old friend · кінорежисер',
    hue: '#7E5DA4',
  };

  React.useEffect(() => {
    if (!open) {
      setStep(0); setMethod(null); setName(''); setRole(''); setHue(PERSON_HUES[0].v);
      setPaired(true); setPerms(['draft', 'mood']);
    }
  }, [open]);

  const reset = () => { setStep(0); setMethod(null); setName(''); setRole(''); };

  const create = () => {
    onCreate?.({
      id: name.toLowerCase().replace(/\s/g, '_') || Date.now().toString(36),
      name, role: role || 'без ролі',
      hue, initials: (name[0] || '?').toUpperCase(),
      paired, temp: paired ? 0.55 : 0.4, lastSeen: 'щойно', unread: 0,
      note: paired ? 'phantom · щойно спарено' : 'без phantom',
      permissions: perms,
      favourite: false,
    });
  };

  if (!open) return null;

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 110,
      background: 'rgba(6,4,2,0.78)', backdropFilter: 'blur(8px)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      {/* header */}
      <div style={{
        padding: '54px 18px 8px',
        display: 'flex', alignItems: 'center', gap: 8,
      }}>
        <button onClick={step > 0 ? () => setStep(step - 1) : onClose} style={{
          appearance: 'none', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.03)', cursor: 'pointer',
          color: 'var(--ink-secondary)', font: '400 11px/1 var(--font-mono)',
          padding: '6px 10px', borderRadius: 999, letterSpacing: '0.04em',
        }}>{step > 0 ? '← назад' : '× звести'}</button>
        <div style={{ flex: 1 }} />
        {/* step indicator */}
        <div style={{ display: 'flex', gap: 4 }}>
          {[0,1,2,3].map((s) => (
            <span key={s} style={{
              width: s === step ? 20 : 5, height: 5, borderRadius: 999,
              background: s <= step ? 'var(--gold)' : 'rgba(255,230,180,0.1)',
              transition: 'all 0.3s',
              boxShadow: s === step ? '0 0 6px var(--gold-glow)' : 'none',
            }} />
          ))}
        </div>
      </div>
      <div style={{
        flex: 1, overflow: 'auto', padding: '8px 18px 18px',
      }}>
        {/* ─── step 0 — method ─── */}
        {step === 0 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 1 · як</div>
              <div style={{
                font: '300 24px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>звідки приходить ця людина?</div>
            </div>
            {/* phantom suggest card — special */}
            <button onClick={() => {
              setMethod('suggest');
              setName(suggestion.name);
              setRole(suggestion.role);
              setHue(suggestion.hue);
              setStep(1);
            }} className="glass accent" style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '14px 16px', borderRadius: 16,
              background: `radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.12), transparent 60%), var(--glass-tint)`,
              border: '0.5px solid rgba(244,175,37,0.3)',
              boxShadow: '0 0 18px rgba(244,175,37,0.10)',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
                <div style={{
                  width: 36, height: 36, borderRadius: '50%',
                  background: `radial-gradient(circle at 35% 30%, ${suggestion.hue}, #1A140B 90%)`,
                  border: '0.5px solid rgba(255,230,180,0.18)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  font: '500 16px/1 var(--font-display)', color: '#fff',
                  boxShadow: `0 0 10px ${suggestion.hue}88`,
                }}>{suggestion.name[0].toUpperCase()}</div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="mono" style={{
                    fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
                    textTransform: 'uppercase', marginBottom: 2,
                  }}>· phantom · пропонує</div>
                  <div style={{ font: '500 14px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>
                    {suggestion.name}
                  </div>
                </div>
                <span className="mono" style={{
                  fontSize: 9, color: 'var(--gold)', letterSpacing: '0.06em',
                }}>→</span>
              </div>
              <div style={{
                font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
                textWrap: 'pretty', fontStyle: 'italic',
              }}>{suggestion.hint}</div>
            </button>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
              {[
                { k: 'qr',     l: 'qr-код',    d: 'звести двох phantom-ів',     ic: '◫', c: '#F4AF25' },
                { k: 'paste',  l: 'хендл',     d: '@username · посилання',      ic: '@', c: '#7CC0E8' },
                { k: 'manual', l: 'руками',    d: 'імʼя · контакт · сам',        ic: '✎', c: '#9DCC9F' },
                { k: 'import', l: 'з адресної', d: 'імпорт усіх з телефону',     ic: '⥢', c: '#B49AD4' },
              ].map((m) => (
                <button key={m.k} onClick={() => { setMethod(m.k); setStep(1); }} style={{
                  appearance: 'none', cursor: 'pointer', textAlign: 'left',
                  padding: '14px 14px', borderRadius: 14,
                  background: 'rgba(255,230,180,0.025)',
                  border: '0.5px solid var(--glass-edge)',
                  display: 'flex', flexDirection: 'column', gap: 6,
                  transition: 'all 0.18s',
                }}>
                  <div style={{
                    width: 28, height: 28, borderRadius: 8,
                    background: `${m.c}1f`, color: m.c,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    font: '500 14px/1 var(--font-display)', marginBottom: 4,
                  }}>{m.ic}</div>
                  <span style={{ font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{m.l}</span>
                  <span className="mono" style={{
                    fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                  }}>{m.d}</span>
                </button>
              ))}
            </div>
            <div style={{
              marginTop: 8, padding: '10px 12px', borderRadius: 10,
              background: 'rgba(255,230,180,0.025)',
              border: '0.5px solid var(--glass-edge)',
              font: '300 11px/1.55 Georgia, serif', color: 'var(--ink-tertiary)',
              fontStyle: 'italic', textWrap: 'pretty',
            }}>
              «людина не контакт. людина — це лінія, що з\'являється у твоєму житті і десь зникає. phantom лиш тримає її форму.»
            </div>
          </div>
        )}

        {/* ─── step 1 — details ─── */}
        {step === 1 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 2 · хто вона / він</div>
              <div style={{
                font: '300 24px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>дай їй обличчя.</div>
            </div>
            {/* preview avatar */}
            <div style={{ display: 'flex', justifyContent: 'center', padding: '8px 0' }}>
              <div style={{
                width: 96, height: 96, borderRadius: '50%',
                background: `radial-gradient(circle at 35% 30%, ${hue}, ${hue}55 55%, ${hue}22)`,
                border: '0.5px solid rgba(255,230,180,0.18)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '300 42px/1 var(--font-display)', color: '#fff',
                letterSpacing: '0.02em',
                boxShadow: `0 0 30px ${hue}66`,
                transition: 'all 0.35s cubic-bezier(.3,.7,.4,1)',
              }}>{(name[0] || '?').toUpperCase()}</div>
            </div>
            {/* name */}
            <div>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
                textTransform: 'uppercase', marginBottom: 5,
              }}>· імʼя</div>
              <input
                value={name} onChange={(e) => setName(e.target.value)}
                placeholder="як її / його звати?"
                style={{
                  width: '100%', appearance: 'none', border: 'none', outline: 'none',
                  background: 'transparent',
                  borderBottom: '0.5px solid var(--glass-edge-strong)',
                  font: '400 18px/1.4 var(--font-display)', color: 'var(--ink-strong)',
                  padding: '8px 0', letterSpacing: '-0.005em',
                }} />
            </div>
            {/* role */}
            <div>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
                textTransform: 'uppercase', marginBottom: 5,
              }}>· роль · в одному рядку</div>
              <input
                value={role} onChange={(e) => setRole(e.target.value)}
                placeholder="напр. дочка маминої подруги · ‹твій тон›"
                style={{
                  width: '100%', appearance: 'none', border: 'none', outline: 'none',
                  background: 'transparent',
                  borderBottom: '0.5px solid var(--glass-edge)',
                  font: '400 13px/1.4 var(--font-body)', color: 'var(--ink-strong)',
                  padding: '8px 0', letterSpacing: '0.005em',
                }} />
            </div>
            {/* hue picker */}
            <div>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
                textTransform: 'uppercase', marginBottom: 8,
              }}>· колір · як ти про неї згадуєш</div>
              <div style={{
                display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: 8,
              }}>
                {PERSON_HUES.map((h) => (
                  <button key={h.v} onClick={() => setHue(h.v)}
                          aria-label={h.n}
                          style={{
                            appearance: 'none', cursor: 'pointer', border: 'none',
                            aspectRatio: '1', borderRadius: '50%',
                            background: `radial-gradient(circle at 35% 30%, ${h.v}, ${h.v}55)`,
                            outline: hue === h.v ? `1.5px solid var(--gold)` : 'none',
                            outlineOffset: 2,
                            boxShadow: hue === h.v ? `0 0 12px ${h.v}` : `0 0 6px ${h.v}66`,
                            transition: 'all 0.18s',
                            transform: hue === h.v ? 'scale(1.1)' : 'scale(1)',
                          }} />
                ))}
              </div>
            </div>
            {/* pair toggle */}
            <button onClick={() => setPaired((p) => !p)} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '12px 14px', borderRadius: 12,
              background: paired ? 'rgba(244,175,37,0.08)' : 'rgba(255,230,180,0.02)',
              border: `0.5px solid ${paired ? 'rgba(244,175,37,0.30)' : 'var(--glass-edge)'}`,
              display: 'grid', gridTemplateColumns: '1fr 30px',
              gap: 10, alignItems: 'center',
            }}>
              <div>
                <div style={{
                  display: 'flex', alignItems: 'center', gap: 6, marginBottom: 3,
                }}>
                  <span style={{ font: '500 12px/1 var(--font-display)', color: paired ? 'var(--gold)' : 'var(--ink-strong)' }}>
                    у них є phantom
                  </span>
                  {paired && (
                    <span style={{
                      width: 6, height: 6, borderRadius: '50%',
                      background: 'var(--gold)',
                      boxShadow: '0 0 4px var(--gold-glow)',
                      animation: 'phantom-pulse-glow 1.6s ease-in-out infinite',
                    }} />
                  )}
                </div>
                <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-tertiary)', textWrap: 'pretty' }}>
                  {paired ? 'phantoms домовлятимуться поміж себе перед твоїм очима' : 'без phantom · ти пишеш сам, як завжди'}
                </div>
              </div>
              <div style={{
                width: 30, height: 18, borderRadius: 999, position: 'relative',
                background: paired ? 'var(--gold)' : 'rgba(255,230,180,0.08)',
                transition: 'background 0.2s',
                boxShadow: paired ? '0 0 8px var(--gold-glow)' : 'none',
              }}>
                <div style={{
                  position: 'absolute', top: 2,
                  left: paired ? 14 : 2,
                  width: 14, height: 14, borderRadius: '50%',
                  background: paired ? '#1A140B' : 'var(--ink-secondary)',
                  transition: 'left 0.22s cubic-bezier(.3,.7,.4,1.2)',
                }} />
              </div>
            </button>
            <button onClick={() => setStep(2)} disabled={!name.trim()}
                    style={{
                      appearance: 'none', cursor: name.trim() ? 'pointer' : 'default',
                      padding: '13px', borderRadius: 14, border: 'none',
                      background: name.trim() ? 'var(--gold)' : 'rgba(244,175,37,0.18)',
                      color: '#1A140B',
                      font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
                      boxShadow: name.trim() ? '0 0 14px var(--gold-glow)' : 'none',
                      opacity: name.trim() ? 1 : 0.5,
                      transition: 'all 0.18s',
                    }}>далі · дозволи</button>
          </div>
        )}

        {/* ─── step 2 — permissions ─── */}
        {step === 2 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 3 · влада</div>
              <div style={{
                font: '300 24px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em', marginBottom: 8,
              }}>що phantom може робити для {name}?</div>
              <div style={{
                font: '300 11.5px/1.55 Georgia, serif', color: 'var(--ink-tertiary)',
                fontStyle: 'italic', textWrap: 'pretty',
              }}>усе можна змінити пізніше. ти завжди старший.</div>
            </div>
            <PermissionPicker value={perms} onChange={setPerms} />
            <button onClick={() => setStep(3)} style={{
              appearance: 'none', cursor: 'pointer', border: 'none',
              padding: '13px', borderRadius: 14,
              background: 'var(--gold)', color: '#1A140B',
              font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
              boxShadow: '0 0 14px var(--gold-glow)',
            }}>звести · {paired ? 'phantom × phantom' : 'додати'}</button>
          </div>
        )}

        {/* ─── step 3 — handshake ─── */}
        {step === 3 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 4 · ритуал</div>
              <div style={{
                font: '300 24px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>знайомлю {name} · з тобою.</div>
            </div>
            <HandshakeStage yourHue="#F4AF25" theirHue={hue} name={name} />
            {/* summary */}
            <div className="glass" style={{ padding: '12px 14px', borderRadius: 14 }}>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
                textTransform: 'uppercase', marginBottom: 8,
              }}>· що домовлено</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {[
                  ['імʼя', name || '—'],
                  ['роль', role || '—'],
                  ['phantom', paired ? 'спарено · ed25519' : 'без — ручне спілкування'],
                  ['дозволи', perms.length ? perms.join(' · ') : '—'],
                ].map(([k, v]) => (
                  <div key={k} style={{
                    display: 'grid', gridTemplateColumns: '70px 1fr',
                    gap: 8, alignItems: 'baseline', padding: '3px 0',
                  }}>
                    <span className="mono" style={{
                      fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.1em',
                      textTransform: 'uppercase',
                    }}>· {k}</span>
                    <span style={{
                      font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-strong)',
                      letterSpacing: '0.01em', textWrap: 'pretty',
                    }}>{v}</span>
                  </div>
                ))}
              </div>
            </div>
            <button onClick={create} style={{
              appearance: 'none', cursor: 'pointer', border: 'none',
              padding: '14px', borderRadius: 14,
              background: 'var(--gold)', color: '#1A140B',
              font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
              boxShadow: '0 0 16px var(--gold-glow)',
            }}>·  додати {name} ·</button>
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { PERSON_HUES, PERMISSIONS, PermissionPicker, HandshakeStage, AddPersonFlow });
