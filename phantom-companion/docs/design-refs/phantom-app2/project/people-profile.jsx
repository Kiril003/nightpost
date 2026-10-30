/* people-profile.jsx — PersonProfile (view + edit),
   GroupBuilder (create group), GroupProfile (view + edit). */

// ─── PersonProfile — full-screen sheet ──────────────────────────────────────
function PersonProfile({ open, person, onClose, onSave, onArchive, onOpenChat }) {
  const [p, setP] = React.useState(person || {});
  React.useEffect(() => setP(person || {}), [person]);
  // synthetic relationship curve — hook must be called before any early return
  const tempPts = React.useMemo(() => Array.from({ length: 30 }, (_, i) => {
    const t = i / 29;
    return 0.5 + Math.sin(t * 5 + (p.id?.length || 0)) * 0.2 + (i === 29 ? ((p.temp || 0.5) - 0.5) : 0);
  }), [p.id, p.temp]);
  if (!open || !person) return null;

  const setField = (k, v) => setP((x) => ({ ...x, [k]: v }));
  const save = () => { onSave?.(p); onClose?.(); };

  const recentMoments = [
    { t: '14 трав · 12:14', l: `${p.name} → ти`, kind: 'msg',   d: '«ти ще біля води чи вже вдома?»' },
    { t: '14 трав · 11:01', l: 'phantom · їй',    kind: 'agent', d: 'відписав за тебе · «вдома · читаю · вечором подзвоню»' },
    { t: '13 трав · 19:42', l: `${p.name} · фото`, kind: 'media', d: 'fika · кава · вона за столом біля вікна' },
    { t: '12 трав · 22:05', l: 'спільний vault',  kind: 'vault', d: 'збережено · «вірш про переїзд» · бачить тільки вона' },
    { t: '11 трав · 09:15', l: `${p.name} → ти`,  kind: 'msg',   d: '«ти спав 4 години? phantom мене обдурив, що 7»' },
  ];
  const kindColor = { msg: '#F4AF25', agent: '#FFD370', media: '#7CC0E8', vault: '#7E5DA4' };

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 110,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      {/* atmospheric tint behind hero */}
      <div style={{
        position: 'absolute', left: 0, right: 0, top: 0, height: '50%',
        background: `radial-gradient(ellipse 80% 60% at 50% 0%, ${p.hue}22, transparent 70%)`,
        pointerEvents: 'none',
      }} />
      {/* top bar */}
      <div className="ui-chrome" style={{
        padding: '52px 16px 8px',
        display: 'flex', alignItems: 'center', gap: 8,
        position: 'relative', zIndex: 5,
      }}>
        <button onClick={onClose} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.03)', color: 'var(--ink-secondary)',
          font: '400 11px/1 var(--font-mono)', padding: '6px 10px',
          borderRadius: 999, letterSpacing: '0.04em',
        }}>← звести</button>
        <div style={{ flex: 1 }} />
        <button onClick={() => setField('favourite', !p.favourite)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          background: 'transparent', color: p.favourite ? 'var(--gold)' : 'var(--ink-tertiary)',
          font: '400 16px/1 var(--font-display)', padding: 6,
        }}>{p.favourite ? '✦' : '✧'}</button>
        <button onClick={() => onOpenChat?.(p)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          background: 'rgba(244,175,37,0.94)', color: '#1A140B',
          font: '500 11px/1 var(--font-display)', padding: '7px 12px',
          borderRadius: 999, letterSpacing: '0.04em',
          boxShadow: '0 0 12px var(--gold-glow)',
        }}>відкрити чат →</button>
      </div>
      {/* scrollable body */}
      <div style={{ flex: 1, overflow: 'auto', padding: '0 18px 32px', position: 'relative', zIndex: 5 }}>
        {/* hero */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '8px 0 18px' }}>
          <div style={{ width: 92, height: 92, flexShrink: 0, position: 'relative' }}>
            <div style={{
              position: 'absolute', inset: -10, borderRadius: '50%',
              background: `radial-gradient(circle, ${p.hue}55, transparent 70%)`,
              filter: 'blur(8px)',
            }} />
            <div style={{
              width: '100%', height: '100%', borderRadius: '50%',
              background: `radial-gradient(circle at 35% 30%, ${p.hue}, ${p.hue}55 55%, ${p.hue}22)`,
              border: '0.5px solid rgba(255,230,180,0.18)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              font: '300 38px/1 var(--font-display)', color: '#fff',
              letterSpacing: '0.02em',
              boxShadow: `0 0 32px ${p.hue}66`, position: 'relative',
            }}>
              {p.initials}
              {p.paired && (
                <span style={{
                  position: 'absolute', right: 2, bottom: 2,
                  width: 22, height: 22, borderRadius: '50%',
                  background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25)',
                  border: '3px solid var(--bg-base)',
                  boxShadow: '0 0 8px var(--gold-glow)',
                  animation: 'phantom-pulse-glow 2.4s ease-in-out infinite',
                }} />
              )}
            </div>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <input
              value={p.name || ''} onChange={(e) => setField('name', e.target.value)}
              style={{
                appearance: 'none', border: 'none', outline: 'none',
                background: 'transparent', width: '100%',
                font: '300 28px/1.05 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.02em', padding: 0,
              }} />
            <input
              value={p.role || ''} onChange={(e) => setField('role', e.target.value)}
              placeholder="роль · в одному рядку"
              style={{
                appearance: 'none', border: 'none', outline: 'none',
                background: 'transparent', width: '100%', marginTop: 3,
                font: '400 11px/1.4 var(--font-mono)', color: 'var(--ink-secondary)',
                letterSpacing: '0.02em', padding: 0,
              }} />
            <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
              <span style={{
                padding: '3px 8px', borderRadius: 999,
                background: `${tempColor(p.temp)}1f`, color: tempColor(p.temp),
                font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
                border: `0.5px solid ${tempColor(p.temp)}55`,
              }}>· {tempLabel(p.temp)}</span>
              {p.paired && (
                <span style={{
                  padding: '3px 8px', borderRadius: 999,
                  background: 'rgba(244,175,37,0.10)',
                  color: 'var(--gold-soft)',
                  font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
                  border: '0.5px solid rgba(244,175,37,0.30)',
                }}>· phantom paired</span>
              )}
              <span className="mono" style={{
                padding: '3px 8px', borderRadius: 999, fontSize: 9,
                background: 'rgba(255,230,180,0.04)',
                color: 'var(--ink-tertiary)', letterSpacing: '0.06em',
                border: '0.5px solid var(--glass-edge)',
              }}>останнє · {p.lastSeen}</span>
            </div>
          </div>
        </div>

        {/* hue picker */}
        <Section title="колір" sub="як ти про неї згадуєш">
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: 8,
          }}>
            {PERSON_HUES.map((h) => (
              <button key={h.v} onClick={() => setField('hue', h.v)}
                      style={{
                        appearance: 'none', cursor: 'pointer', border: 'none',
                        aspectRatio: '1', borderRadius: '50%',
                        background: `radial-gradient(circle at 35% 30%, ${h.v}, ${h.v}55)`,
                        outline: p.hue === h.v ? `1.5px solid var(--gold)` : 'none',
                        outlineOffset: 2,
                        boxShadow: p.hue === h.v ? `0 0 12px ${h.v}` : `0 0 6px ${h.v}66`,
                        transform: p.hue === h.v ? 'scale(1.1)' : 'scale(1)',
                        transition: 'all 0.18s',
                      }} />
            ))}
          </div>
        </Section>

        {/* relationship temperature */}
        <Section title="температура" sub={`за 30 днів · зараз ${tempLabel(p.temp)}`}>
          <div style={{
            padding: '10px 12px', borderRadius: 12,
            background: 'rgba(0,0,0,0.15)',
            border: '0.5px solid var(--glass-edge)',
          }}>
            <svg viewBox="0 0 320 50" style={{ width: '100%', height: 50, display: 'block' }}>
              <path d={`M 0 ${50 - tempPts[0]*40} ${tempPts.map((v, i) => `L ${(i/29)*320} ${50 - v*40}`).join(' ')} L 320 50 L 0 50 Z`}
                    fill={`${tempColor(p.temp)}22`} />
              <path d={`M 0 ${50 - tempPts[0]*40} ${tempPts.map((v, i) => `L ${(i/29)*320} ${50 - v*40}`).join(' ')}`}
                    fill="none" stroke={tempColor(p.temp)} strokeWidth="1.2"
                    style={{ filter: `drop-shadow(0 0 3px ${tempColor(p.temp)}66)` }} />
              <circle cx="320" cy={50 - p.temp*40} r="3" fill={tempColor(p.temp)} />
            </svg>
          </div>
        </Section>

        {/* channels */}
        <Section title="канали" sub="де ти його дістаєш">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {[
              { k: 'phone',   l: '+380 67 ··· 4421', ic: '☎', c: 'var(--ink-secondary)' },
              { k: 'msg',     l: `@${p.id}`,            ic: '✉', c: 'var(--ink-strong)' },
              { k: 'voice',   l: 'voice memo · 2 хв',   ic: '◐', c: 'var(--gold-soft)' },
              { k: 'phantom', l: p.paired ? `phantom·${p.id} · 4ms · ed25519` : 'не спарено', ic: '◊', c: p.paired ? 'var(--gold)' : 'var(--ink-tertiary)' },
            ].map((c) => (
              <div key={c.k} style={{
                display: 'grid', gridTemplateColumns: '22px 1fr 24px', gap: 8, alignItems: 'center',
                padding: '8px 10px', borderRadius: 8,
                background: 'rgba(255,230,180,0.025)',
                border: '0.5px solid var(--glass-edge)',
              }}>
                <span style={{
                  width: 20, height: 20, borderRadius: 5,
                  background: 'rgba(244,175,37,0.06)', color: c.c,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  font: '400 11px/1 var(--font-display)',
                }}>{c.ic}</span>
                <span className="mono" style={{ fontSize: 10.5, color: 'var(--ink-strong)', letterSpacing: '0.02em' }}>{c.l}</span>
                <span style={{ color: 'var(--ink-tertiary)', textAlign: 'right', font: '400 12px/1 var(--font-mono)' }}>→</span>
              </div>
            ))}
          </div>
        </Section>

        {/* permissions */}
        <Section title="що phantom може"
                 sub="ти завжди старший · змінюй коли хочеш">
          <PermissionPicker value={p.permissions || []}
                            onChange={(v) => setField('permissions', v)} />
        </Section>

        {/* phantom note */}
        <Section title="phantom · нотатки" sub="фрагменти про нього, що я зберіг">
          <div style={{
            padding: '12px 14px', borderRadius: 12,
            background: `radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.08), transparent 60%), rgba(255,230,180,0.02)`,
            border: '0.5px solid rgba(244,175,37,0.18)',
            font: '300 12.5px/1.65 Georgia, serif',
            color: 'var(--ink-strong)', fontStyle: 'italic',
            textWrap: 'pretty', letterSpacing: '0.005em',
          }}>«{p.note}»</div>
        </Section>

        {/* recent moments */}
        <Section title="недавнє" sub="5 моментів · у часі">
          <div style={{ position: 'relative', paddingLeft: 18 }}>
            <div style={{
              position: 'absolute', left: 4, top: 4, bottom: 6,
              width: 0.5, background: 'linear-gradient(to bottom, var(--gold-faint), transparent 95%)',
            }} />
            {recentMoments.map((m, i) => (
              <div key={i} style={{
                position: 'relative', marginBottom: 10,
              }}>
                <span style={{
                  position: 'absolute', left: -18, top: 5,
                  width: 9, height: 9, borderRadius: '50%',
                  background: kindColor[m.kind] || 'var(--gold)',
                  boxShadow: `0 0 4px ${(kindColor[m.kind] || '#F4AF25')}88`,
                  border: '2px solid var(--bg-base)',
                }} />
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                  <span className="mono" style={{
                    fontSize: 8.5, color: kindColor[m.kind], letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}>· {m.kind}</span>
                  <span className="mono" style={{
                    fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                  }}>{m.t}</span>
                </div>
                <div style={{
                  marginTop: 2, font: '400 11.5px/1.45 var(--font-body)',
                  color: 'var(--ink-strong)', textWrap: 'pretty',
                }}>{m.d}</div>
              </div>
            ))}
          </div>
        </Section>

        {/* danger zone */}
        <Section title="небезпечно" sub="тиха зона">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <button onClick={() => { onArchive?.(p); onClose?.(); }} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '10px 12px', borderRadius: 10,
              background: 'rgba(255,230,180,0.025)',
              border: '0.5px solid var(--glass-edge)',
              color: 'var(--ink-secondary)', font: '500 11.5px/1.4 var(--font-body)',
              textWrap: 'pretty', letterSpacing: '0.01em',
            }}>
              · перенести у памʼять
              <div style={{ font: '400 10px/1.4 var(--font-body)', color: 'var(--ink-tertiary)', marginTop: 3 }}>
                phantom не забуде · просто не виводитиме у живий список
              </div>
            </button>
            <button style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '10px 12px', borderRadius: 10,
              background: 'rgba(255,139,110,0.04)',
              border: '0.5px solid rgba(255,139,110,0.22)',
              color: 'var(--coral)', font: '500 11.5px/1.4 var(--font-body)',
              textWrap: 'pretty', letterSpacing: '0.01em',
            }}>
              · забути назавжди
              <div style={{ font: '400 10px/1.4 var(--font-body)', color: 'var(--coral)', opacity: 0.7, marginTop: 3 }}>
                видалити з vault · не можна повернути · phantom попросить підтвердити двічі
              </div>
            </button>
          </div>
        </Section>

        {/* save bar */}
        <button onClick={save} style={{
          marginTop: 18, appearance: 'none', cursor: 'pointer', border: 'none',
          width: '100%', padding: '14px', borderRadius: 14,
          background: 'var(--gold)', color: '#1A140B',
          font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
          boxShadow: '0 0 16px var(--gold-glow)',
        }}>зберегти</button>
      </div>
    </div>
  );
}

// ─── shared Section block ───────────────────────────────────────────────────
function Section({ title, sub, children }) {
  return (
    <div style={{ marginTop: 18 }}>
      <div style={{ marginBottom: 8 }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em',
          textTransform: 'uppercase', marginBottom: 3,
        }}>· {title}</div>
        {sub && (
          <div style={{
            font: '300 10px/1.45 Georgia, serif',
            color: 'var(--ink-tertiary)', fontStyle: 'italic',
          }}>{sub}</div>
        )}
      </div>
      {children}
    </div>
  );
}

// ─── GroupBuilder — multi-step group creation ───────────────────────────────
function GroupBuilder({ open, people, onClose, onCreate }) {
  const [step, setStep] = React.useState(0);
  const [members, setMembers] = React.useState([]);
  const [name, setName] = React.useState('');
  const [hue, setHue] = React.useState('#F4AF25');
  const [phantomRole, setPhantomRole] = React.useState('ambient');
  const [vault, setVault] = React.useState(false);
  const [note, setNote] = React.useState('');

  React.useEffect(() => {
    if (!open) { setStep(0); setMembers([]); setName(''); setHue('#F4AF25'); setPhantomRole('ambient'); setVault(false); setNote(''); }
  }, [open]);

  const toggle = (id) => setMembers((m) => m.includes(id) ? m.filter((x) => x !== id) : [...m, id]);
  const create = () => {
    onCreate?.({
      id: name.toLowerCase().replace(/\s/g, '_') || `g${Date.now().toString(36)}`,
      name, hue, members, phantom: phantomRole, vault, note, activity: 0.5,
    });
  };

  if (!open) return null;
  const memObjs = members.map((id) => people.find((p) => p.id === id)).filter(Boolean);

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 110,
      background: 'rgba(6,4,2,0.78)', backdropFilter: 'blur(8px)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
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
        <div style={{ display: 'flex', gap: 4 }}>
          {[0,1,2].map((s) => (
            <span key={s} style={{
              width: s === step ? 18 : 5, height: 5, borderRadius: 999,
              background: s <= step ? hue : 'rgba(255,230,180,0.1)',
              transition: 'all 0.3s',
              boxShadow: s === step ? `0 0 6px ${hue}88` : 'none',
            }} />
          ))}
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '8px 18px 18px' }}>
        {/* ─── step 0 — pick members ─── */}
        {step === 0 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 1 · хто</div>
              <div style={{
                font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>обери людей · phantom оточить вас разом.</div>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {people.filter((p) => !p.archived).map((p) => {
                const on = members.includes(p.id);
                return (
                  <button key={p.id} onClick={() => toggle(p.id)} style={{
                    appearance: 'none', cursor: 'pointer', textAlign: 'left',
                    padding: '8px 12px', borderRadius: 12,
                    background: on ? `${p.hue}10` : 'rgba(255,230,180,0.02)',
                    border: `0.5px solid ${on ? p.hue : 'var(--glass-edge)'}`,
                    display: 'flex', alignItems: 'center', gap: 12,
                    boxShadow: on ? `0 0 8px ${p.hue}44` : 'none',
                    transition: 'all 0.15s',
                  }}>
                    <PersonAvatar p={p} size={36} withRing={false} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ font: '500 12.5px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{p.name}</div>
                      <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>{p.role}</div>
                    </div>
                    <div style={{
                      width: 18, height: 18, borderRadius: 4, flexShrink: 0,
                      background: on ? p.hue : 'transparent',
                      border: `0.5px solid ${on ? p.hue : 'var(--ink-faint)'}`,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}>
                      {on && <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 5L4 7L8 2" stroke="#1A140B" strokeWidth="1.6" fill="none" strokeLinecap="round" /></svg>}
                    </div>
                  </button>
                );
              })}
            </div>
            <button onClick={() => setStep(1)} disabled={members.length < 2}
                    style={{
                      appearance: 'none', cursor: members.length >= 2 ? 'pointer' : 'default',
                      padding: '13px', borderRadius: 14, border: 'none',
                      background: members.length >= 2 ? 'var(--gold)' : 'rgba(244,175,37,0.18)',
                      color: '#1A140B', opacity: members.length >= 2 ? 1 : 0.5,
                      font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
                      boxShadow: members.length >= 2 ? '0 0 14px var(--gold-glow)' : 'none',
                    }}>далі · {members.length} {members.length === 1 ? 'людина' : 'людей'}</button>
          </div>
        )}

        {/* ─── step 1 — configure ─── */}
        {step === 1 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 2 · образ</div>
              <div style={{
                font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>як називається ця група?</div>
            </div>
            <input
              value={name} onChange={(e) => setName(e.target.value)}
              placeholder="імʼя · сімʼя · робота · тісне коло"
              style={{
                width: '100%', appearance: 'none', border: 'none', outline: 'none',
                background: 'transparent',
                borderBottom: '0.5px solid var(--glass-edge-strong)',
                font: '400 20px/1.4 var(--font-display)', color: 'var(--ink-strong)',
                padding: '8px 0', letterSpacing: '-0.005em',
              }} />
            <div>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
                textTransform: 'uppercase', marginBottom: 8,
              }}>· колір</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: 8 }}>
                {PERSON_HUES.map((h) => (
                  <button key={h.v} onClick={() => setHue(h.v)}
                          style={{
                            appearance: 'none', cursor: 'pointer', border: 'none',
                            aspectRatio: '1', borderRadius: '50%',
                            background: `radial-gradient(circle at 35% 30%, ${h.v}, ${h.v}55)`,
                            outline: hue === h.v ? `1.5px solid var(--gold)` : 'none',
                            outlineOffset: 2,
                            boxShadow: hue === h.v ? `0 0 12px ${h.v}` : `0 0 6px ${h.v}66`,
                            transform: hue === h.v ? 'scale(1.1)' : 'scale(1)',
                            transition: 'all 0.18s',
                          }} />
                ))}
              </div>
            </div>
            <div>
              <div className="mono" style={{
                fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
                textTransform: 'uppercase', marginBottom: 8,
              }}>· роль phantom</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 4 }}>
                {[
                  { v: 'silent',  l: 'тихий',    d: 'лиш слухає' },
                  { v: 'ambient', l: 'фоновий',  d: 'підказує тобі' },
                  { v: 'active',  l: 'активний', d: 'пише summary, отвечає' },
                ].map((r) => (
                  <button key={r.v} onClick={() => setPhantomRole(r.v)} style={{
                    appearance: 'none', cursor: 'pointer',
                    padding: '8px', borderRadius: 8,
                    background: phantomRole === r.v ? `${hue}1f` : 'rgba(255,230,180,0.02)',
                    border: `0.5px solid ${phantomRole === r.v ? hue : 'var(--glass-edge)'}`,
                    display: 'flex', flexDirection: 'column', gap: 3,
                  }}>
                    <span style={{
                      font: '500 11px/1 var(--font-display)',
                      color: phantomRole === r.v ? hue : 'var(--ink-strong)',
                    }}>{r.l}</span>
                    <span className="mono" style={{
                      fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                      textAlign: 'center',
                    }}>{r.d}</span>
                  </button>
                ))}
              </div>
            </div>
            <button onClick={() => setVault((v) => !v)} style={{
              appearance: 'none', cursor: 'pointer', textAlign: 'left',
              padding: '12px 14px', borderRadius: 12,
              background: vault ? `${hue}10` : 'rgba(255,230,180,0.02)',
              border: `0.5px solid ${vault ? hue : 'var(--glass-edge)'}`,
              display: 'grid', gridTemplateColumns: '1fr 30px',
              gap: 10, alignItems: 'center',
            }}>
              <div>
                <div style={{ font: '500 12px/1 var(--font-display)', color: vault ? hue : 'var(--ink-strong)' }}>
                  спільний vault
                </div>
                <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-tertiary)', marginTop: 3 }}>
                  файли, ключі, нотатки · доступ тільки членам · phantom тримає замок
                </div>
              </div>
              <div style={{
                width: 30, height: 18, borderRadius: 999, position: 'relative',
                background: vault ? hue : 'rgba(255,230,180,0.08)',
                transition: 'background 0.2s',
                boxShadow: vault ? `0 0 8px ${hue}88` : 'none',
              }}>
                <div style={{
                  position: 'absolute', top: 2,
                  left: vault ? 14 : 2,
                  width: 14, height: 14, borderRadius: '50%',
                  background: vault ? '#1A140B' : 'var(--ink-secondary)',
                  transition: 'left 0.22s cubic-bezier(.3,.7,.4,1.2)',
                }} />
              </div>
            </button>
            <button onClick={() => setStep(2)} disabled={!name.trim()}
                    style={{
                      appearance: 'none', cursor: name.trim() ? 'pointer' : 'default',
                      padding: '13px', borderRadius: 14, border: 'none',
                      background: name.trim() ? 'var(--gold)' : 'rgba(244,175,37,0.18)',
                      color: '#1A140B', opacity: name.trim() ? 1 : 0.5,
                      font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
                      boxShadow: name.trim() ? '0 0 14px var(--gold-glow)' : 'none',
                    }}>далі · сузіря</button>
          </div>
        )}

        {/* ─── step 2 — preview constellation ─── */}
        {step === 2 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 6,
              }}>· крок 3 · сузіря</div>
              <div style={{
                font: '300 22px/1.1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>«{name}» · готова.</div>
            </div>
            <GroupConstellation hue={hue} members={memObjs} phantomRole={phantomRole} />
            <button onClick={create} style={{
              appearance: 'none', cursor: 'pointer', border: 'none',
              padding: '14px', borderRadius: 14,
              background: hue, color: '#1A140B',
              font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
              boxShadow: `0 0 16px ${hue}88`,
            }}>· створити ·</button>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── GroupConstellation — orbital visualization of group ────────────────────
function GroupConstellation({ hue = '#F4AF25', members, phantomRole = 'ambient' }) {
  return (
    <div style={{
      position: 'relative', height: 260, borderRadius: 18,
      background: `radial-gradient(ellipse at 50% 50%, ${hue}14, transparent 70%)`,
      border: '0.5px solid var(--glass-edge)',
      overflow: 'hidden',
    }}>
      <svg viewBox="0 0 320 260" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        {/* orbital rings */}
        {[60, 90, 120].map((r) => (
          <circle key={r} cx="160" cy="130" r={r} fill="none"
                  stroke={`${hue}1a`} strokeWidth="0.6" strokeDasharray="3 5" />
        ))}
        {/* lines from phantom to members */}
        {members.map((m, i) => {
          const ang = (i / members.length) * Math.PI * 2 - Math.PI / 2;
          const x = 160 + Math.cos(ang) * 86;
          const y = 130 + Math.sin(ang) * 86;
          return (
            <line key={m.id} x1="160" y1="130" x2={x} y2={y}
                  stroke={m.hue} strokeOpacity="0.4" strokeWidth="0.8"
                  strokeDasharray="3 3" />
          );
        })}
      </svg>
      {/* phantom center */}
      <div style={{
        position: 'absolute', left: '50%', top: '50%',
        transform: 'translate(-50%, -50%)',
        width: 40, height: 40, borderRadius: '50%',
        background: `radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)`,
        boxShadow: `0 0 16px var(--gold-glow)`,
        animation: 'phantom-pulse-glow 2.4s ease-in-out infinite',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <span className="mono" style={{
          fontSize: 7.5, color: '#1A140B', letterSpacing: '0.16em',
          textTransform: 'uppercase', fontWeight: 600,
        }}>· {phantomRole.slice(0, 3)} ·</span>
      </div>
      {/* member orbs */}
      {members.map((m, i) => {
        const ang = (i / members.length) * Math.PI * 2 - Math.PI / 2;
        const x = `calc(50% + ${Math.cos(ang) * 86}px)`;
        const y = `calc(50% + ${Math.sin(ang) * 86}px)`;
        return (
          <div key={m.id} style={{
            position: 'absolute', left: x, top: y,
            transform: 'translate(-50%, -50%)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          }}>
            <div style={{
              width: 28, height: 28, borderRadius: '50%',
              background: `radial-gradient(circle at 35% 30%, ${m.hue}, ${m.hue}55)`,
              border: '0.5px solid rgba(255,230,180,0.15)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              font: '500 11px/1 var(--font-display)', color: '#fff',
              boxShadow: `0 0 10px ${m.hue}88`,
              animation: `phantom-pulse-glow ${3 + i * 0.5}s ease-in-out infinite`,
            }}>{m.initials[0]}</div>
            <span style={{
              font: '500 9px/1 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '0.04em',
            }}>{m.name}</span>
          </div>
        );
      })}
    </div>
  );
}

// ─── GroupProfile — view/edit ────────────────────────────────────────────────
function GroupProfile({ open, group, people, onClose, onSave, onOpenChat, onArchive }) {
  const [g, setG] = React.useState(group || {});
  React.useEffect(() => setG(group || {}), [group]);
  if (!open || !group) return null;

  const members = (g.members || []).map((id) => people.find((p) => p.id === id)).filter(Boolean);
  const setField = (k, v) => setG((x) => ({ ...x, [k]: v }));
  const save = () => { onSave?.(g); onClose?.(); };

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 110,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      <div style={{
        position: 'absolute', left: 0, right: 0, top: 0, height: '40%',
        background: `radial-gradient(ellipse 80% 60% at 50% 0%, ${g.hue}22, transparent 70%)`,
        pointerEvents: 'none',
      }} />
      <div className="ui-chrome" style={{
        padding: '52px 16px 8px',
        display: 'flex', alignItems: 'center', gap: 8,
        position: 'relative', zIndex: 5,
      }}>
        <button onClick={onClose} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.03)', color: 'var(--ink-secondary)',
          font: '400 11px/1 var(--font-mono)', padding: '6px 10px',
          borderRadius: 999, letterSpacing: '0.04em',
        }}>← звести</button>
        <div style={{ flex: 1 }} />
        <button onClick={() => onOpenChat?.(g)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          background: g.hue, color: '#1A140B',
          font: '500 11px/1 var(--font-display)', padding: '7px 12px',
          borderRadius: 999, letterSpacing: '0.04em',
          boxShadow: `0 0 12px ${g.hue}88`,
        }}>відкрити чат →</button>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 18px 32px', position: 'relative', zIndex: 5 }}>
        {/* hero */}
        <div style={{ padding: '8px 0 14px' }}>
          <input
            value={g.name || ''} onChange={(e) => setField('name', e.target.value)}
            style={{
              width: '100%', appearance: 'none', border: 'none', outline: 'none',
              background: 'transparent',
              font: '300 30px/1.05 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '-0.02em', padding: 0,
            }} />
          <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
            <span style={{
              padding: '3px 8px', borderRadius: 999,
              background: `${g.hue}1f`, color: g.hue,
              font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
              border: `0.5px solid ${g.hue}55`,
            }}>· {members.length} людей</span>
            <span style={{
              padding: '3px 8px', borderRadius: 999,
              background: 'rgba(244,175,37,0.10)',
              color: 'var(--gold-soft)',
              font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
              border: '0.5px solid rgba(244,175,37,0.30)',
            }}>· phantom {g.phantom}</span>
            {g.vault && (
              <span style={{
                padding: '3px 8px', borderRadius: 999,
                background: 'rgba(126,93,164,0.12)',
                color: '#B49AD4',
                font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
                border: '0.5px solid rgba(126,93,164,0.30)',
              }}>· vault</span>
            )}
          </div>
        </div>
        {/* constellation */}
        <GroupConstellation hue={g.hue} members={members} phantomRole={g.phantom} />
        {/* phantom role selector */}
        <Section title="роль phantom" sub="що він робить у вашій групі">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 4 }}>
            {[
              { v: 'silent',  l: 'тихий',    d: 'лиш слухає · нічого не пише' },
              { v: 'ambient', l: 'фоновий',  d: 'тобі підказки, групі — ні' },
              { v: 'active',  l: 'активний', d: 'summary щовечора · може відповідати' },
            ].map((r) => (
              <button key={r.v} onClick={() => setField('phantom', r.v)} style={{
                appearance: 'none', cursor: 'pointer',
                padding: '10px 8px', borderRadius: 10,
                background: g.phantom === r.v ? `${g.hue}1f` : 'rgba(255,230,180,0.02)',
                border: `0.5px solid ${g.phantom === r.v ? g.hue : 'var(--glass-edge)'}`,
                display: 'flex', flexDirection: 'column', gap: 4,
              }}>
                <span style={{
                  font: '500 11.5px/1 var(--font-display)',
                  color: g.phantom === r.v ? g.hue : 'var(--ink-strong)',
                }}>{r.l}</span>
                <span className="mono" style={{
                  fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                  textWrap: 'pretty', textAlign: 'left',
                }}>{r.d}</span>
              </button>
            ))}
          </div>
        </Section>
        {/* members list */}
        <Section title="члени" sub={`${members.length} · додати або прибрати`}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {members.map((m) => (
              <div key={m.id} style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '8px 12px', borderRadius: 10,
                background: 'rgba(255,230,180,0.025)',
                border: '0.5px solid var(--glass-edge)',
              }}>
                <PersonAvatar p={m} size={28} withRing={false} />
                <div style={{ flex: 1 }}>
                  <div style={{ font: '500 12px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{m.name}</div>
                  <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>{m.role}</div>
                </div>
                <button onClick={() => setField('members', g.members.filter((id) => id !== m.id))} style={{
                  appearance: 'none', border: 'none', background: 'transparent',
                  color: 'var(--ink-tertiary)', cursor: 'pointer',
                  font: '400 11px/1 var(--font-mono)', padding: 4,
                }}>прибрати</button>
              </div>
            ))}
            <button style={{
              appearance: 'none', cursor: 'pointer', border: '0.5px dashed var(--glass-edge-strong)',
              background: 'transparent', padding: '8px 12px', borderRadius: 10,
              color: 'var(--gold-soft)', font: '400 11px/1 var(--font-body)',
              letterSpacing: '0.02em',
            }}>+ додати когось</button>
          </div>
        </Section>
        {/* shared resources */}
        <Section title="спільне" sub="що належить групі">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
            {[
              { k: 'photos', l: '42 фото',    s: 'family album', ic: '◧' },
              { k: 'notes',  l: '8 нотаток',   s: 'shared',       ic: '✎' },
              { k: 'cal',    l: '3 події',     s: 'next 7 days',  ic: '◫' },
              { k: 'vault',  l: g.vault ? 'vault on' : 'vault off', s: g.vault ? '5 елементів' : 'не відкрито', ic: '◊' },
            ].map((r) => (
              <div key={r.k} style={{
                padding: '12px', borderRadius: 12,
                background: 'rgba(255,230,180,0.02)',
                border: '0.5px solid var(--glass-edge)',
              }}>
                <div style={{ font: '400 14px/1 var(--font-display)', color: g.hue, marginBottom: 4 }}>{r.ic}</div>
                <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{r.l}</div>
                <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>{r.s}</div>
              </div>
            ))}
          </div>
        </Section>
        {/* note */}
        <Section title="phantom · нотатка" sub="як phantom розуміє цю групу">
          <div style={{
            padding: '12px 14px', borderRadius: 12,
            background: `radial-gradient(ellipse at 0% 0%, ${g.hue}18, transparent 60%), rgba(255,230,180,0.02)`,
            border: `0.5px solid ${g.hue}33`,
            font: '300 12.5px/1.65 Georgia, serif',
            color: 'var(--ink-strong)', fontStyle: 'italic',
            textWrap: 'pretty',
          }}>«{g.note}»</div>
        </Section>
        <button onClick={save} style={{
          marginTop: 18, appearance: 'none', cursor: 'pointer', border: 'none',
          width: '100%', padding: '14px', borderRadius: 14,
          background: 'var(--gold)', color: '#1A140B',
          font: '500 13px/1 var(--font-display)', letterSpacing: '0.02em',
          boxShadow: '0 0 16px var(--gold-glow)',
        }}>зберегти</button>
      </div>
    </div>
  );
}

Object.assign(window, { PersonProfile, GroupBuilder, GroupProfile, GroupConstellation, Section });
