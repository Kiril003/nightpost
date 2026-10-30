/* magic-agent.jsx — phantom acting on your behalf:
   PhantomOvernight, PhantomsHandshake, RealitySubtitles,
   RegretStop, AmbientMusicGen, PhantomLiveActions, ProbabilityFutures. */

// ─── 1. PhantomOvernight — what phantom did while you slept ──────────────────
function PhantomOvernight({ sleptFrom = '23:48', sleptTo = '07:14', date = '14 травня', events }) {
  const ev = events || [
    { t: '00:14', kind: 'mail',   ok: true,  text: 'три листи · усе прочитав · 1 відмічено на ранок · нічого не відповів · ти попросив тиші вночі' },
    { t: '01:42', kind: 'call',   ok: true,  text: 'олег набирав · надіслав йому: «kirill спить · передзвоню вранці» · він відповів 👍' },
    { t: '02:14', kind: 'draft',  ok: 'gentle', text: 'ти прокинувся · почав писати листа · не закінчив · видалив сам · я нічого не зробив, але був поряд' },
    { t: '02:30', kind: 'system', ok: true,  text: 'короткий перебій електрики · 3.2 с · phantom-os продовжив на резерві · ти не помітив' },
    { t: '03:55', kind: 'body',   ok: 'gentle', text: 'твоє hrv стрибнуло · це був глибший REM · я зменшив яскравість дисплея до 0.4%' },
    { t: '04:12', kind: 'cancel', ok: 'urgent', text: 'видалив чернетку листа олезі (написану о 23:51) · ти просив зупиняти такі ситуації' },
    { t: '04:50', kind: 'mute',   ok: true,  text: 'whatsapp · 2 повідомлення від работи · затримано до 09:00' },
    { t: '06:00', kind: 'home',   ok: true,  text: 'кавомашина · ввімкнено · еспресо до 06:50' },
    { t: '06:30', kind: 'air',    ok: true,  text: 'вентиляція · протягнено повітря · 18° → 21° поступово · ти не змерзнеш на пробудження' },
    { t: '06:48', kind: 'light',  ok: true,  text: 'wallpaper · поступово amber · симулюю світанок (real sunrise о 07:42)' },
  ];
  const ki = {
    mail:   { i: '✉', c: '#A7967D' },
    call:   { i: '◉', c: '#7CC0E8' },
    draft:  { i: '✎', c: 'var(--gold-soft)' },
    system: { i: '◊', c: 'var(--ink-secondary)' },
    body:   { i: '◐', c: '#FF8B6E' },
    cancel: { i: '✕', c: '#FF8B6E' },
    mute:   { i: '◇', c: 'var(--ink-secondary)' },
    home:   { i: '☖', c: 'var(--gold)' },
    air:    { i: '≋', c: '#7CC0E8' },
    light:  { i: '☀', c: 'var(--gold)' },
  };
  const total = ev.length;
  const dur = 7 * 60 + 26;
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `
        radial-gradient(ellipse at 0% 0%, rgba(126,93,164,0.12), transparent 60%),
        radial-gradient(ellipse at 100% 100%, rgba(244,175,37,0.06), transparent 60%),
        var(--glass-tint)`,
    }}>
      <div style={{ padding: '16px 16px 4px' }}>
        <SeanceMark text="ніч · я був поряд" />
        <div style={{
          font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.005em', marginBottom: 4, textWrap: 'pretty',
        }}>ти спав 7 г 26 хв. ось що я робив.</div>
        <div style={{
          font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-tertiary)',
          textWrap: 'pretty', marginBottom: 10, fontStyle: 'italic',
        }}>{date} · {sleptFrom} → {sleptTo} · {total} дій
        </div>
      </div>
      <div style={{
        position: 'relative', padding: '6px 16px 12px',
      }}>
        {/* spine */}
        <div style={{
          position: 'absolute', left: 32, top: 12, bottom: 18,
          width: 0.5, background: 'linear-gradient(to bottom, var(--gold-faint), transparent 95%)',
        }} />
        {ev.map((e, i) => {
          const k = ki[e.kind];
          const tone = e.ok === 'urgent' ? '#FF8B6E' :
                       e.ok === 'gentle' ? 'var(--gold-soft)' :
                       k.c;
          return (
            <div key={i} style={{
              position: 'relative', display: 'grid',
              gridTemplateColumns: '28px 1fr',
              gap: 12, marginBottom: 8,
            }}>
              {/* time */}
              <span className="mono" style={{
                fontSize: 9.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                fontVariantNumeric: 'tabular-nums', paddingTop: 3,
              }}>{e.t}</span>
              <div>
                {/* node */}
                <span style={{
                  position: 'absolute', left: 32, top: 5, marginLeft: -4.5,
                  width: 9, height: 9, borderRadius: '50%',
                  background: `${tone}`,
                  boxShadow: `0 0 0 3px var(--bg-base), 0 0 8px ${tone}66`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: '#0E0A05', font: '500 7px/1 var(--font-display)',
                }} />
                <div style={{
                  paddingLeft: 14,
                  font: '400 11.5px/1.55 var(--font-body)', color: 'var(--ink-strong)',
                  textWrap: 'pretty',
                }}>
                  <span className="mono" style={{
                    fontSize: 8, color: tone, letterSpacing: '0.16em',
                    textTransform: 'uppercase', marginRight: 6,
                  }}>· {e.kind}</span>
                  {e.text}
                </div>
              </div>
            </div>
          );
        })}
      </div>
      <div style={{
        padding: '10px 16px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
        gap: 8, flexWrap: 'wrap',
      }}>
        <div style={{ display: 'flex', gap: 14 }}>
          <div>
            <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em', textTransform: 'uppercase' }}>дій</div>
            <div style={{ font: '500 12px/1 var(--font-display)', color: 'var(--ink-strong)', marginTop: 2 }}>{total}</div>
          </div>
          <div>
            <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em', textTransform: 'uppercase' }}>розбудив</div>
            <div style={{ font: '500 12px/1 var(--font-display)', color: 'var(--ink-strong)', marginTop: 2 }}>0</div>
          </div>
          <div>
            <div className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em', textTransform: 'uppercase' }}>заблокував</div>
            <div style={{ font: '500 12px/1 var(--font-display)', color: 'var(--ink-strong)', marginTop: 2 }}>5</div>
          </div>
        </div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.04em', fontStyle: 'italic' }}>
          · доброго ранку, кирил
        </span>
      </div>
    </div>
  );
}

// ─── 2. PhantomsHandshake — your AI meets their AI before their reply ────────
function PhantomsHandshake({ them = 'olya', yourColor = '#F4AF25', theirColor = '#FF8B6E' }) {
  const [phase, setPhase] = React.useState('connecting');
  React.useEffect(() => {
    const t1 = setTimeout(() => setPhase('exchanging'), 800);
    const t2 = setTimeout(() => setPhase('resolved'), 3200);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, []);
  const packets = [
    { from: 'me',   t: 'intent · цікавість про неділю', d: 0 },
    { from: 'them', t: 'intent · запросити на вечерю',  d: 500 },
    { from: 'me',   t: 'context · ти втомлений · інтроверт-режим', d: 1000 },
    { from: 'them', t: 'context · вона з аньою · 4 людини макс', d: 1500 },
    { from: 'me',   t: 'preference · «так» з застереженням',     d: 2000 },
    { from: 'them', t: 'accept · понизить очікування',           d: 2500 },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '14px 16px 6px' }}>
        <SeanceMark text={`phantom × phantom · ти + ${them}`} />
        <div style={{
          font: '400 12px/1.55 var(--font-body)', color: 'var(--ink-secondary)',
          textWrap: 'pretty',
        }}>
          {phase === 'connecting' && 'я звʼязався з її фантомом. зачекай 2 секунди — ми домовляємось перед тим, як вона побачить.'}
          {phase === 'exchanging' && 'ми обмінюємось контекстом. твоє ім\'я не покинуло цей пристрій.'}
          {phase === 'resolved' && 'ми домовились. вона напише за 11 хвилин. ось що я підготував.'}
        </div>
      </div>
      {/* the meeting stage */}
      <div style={{
        height: 180, position: 'relative', overflow: 'hidden',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)',
      }}>
        {/* your orb */}
        <div style={{
          position: 'absolute', left: '14%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 44, height: 44, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${yourColor}, #7A4818 70%)`,
          border: `0.5px solid ${yourColor}`,
          boxShadow: `0 0 16px ${yourColor}88`,
          animation: 'phantom-pulse-glow 2.4s ease-in-out infinite',
        }} />
        <div style={{
          position: 'absolute', left: '14%', top: '50%',
          transform: 'translate(-50%, 42px)',
          font: '500 10px/1 var(--font-display)', color: yourColor,
          letterSpacing: '0.02em',
        }}>ти · phantom</div>
        {/* their orb */}
        <div style={{
          position: 'absolute', right: '14%', top: '50%',
          transform: 'translate(50%, -50%)',
          width: 44, height: 44, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${theirColor}, #A03020 70%)`,
          border: `0.5px solid ${theirColor}`,
          boxShadow: `0 0 16px ${theirColor}88`,
          animation: 'phantom-pulse-glow 2.6s ease-in-out infinite',
        }} />
        <div style={{
          position: 'absolute', right: '14%', top: '50%',
          transform: 'translate(50%, 42px)',
          font: '500 10px/1 var(--font-display)', color: theirColor,
          letterSpacing: '0.02em',
        }}>{them} · phantom</div>
        {/* connecting line + packets */}
        <svg viewBox="0 0 320 180" preserveAspectRatio="none"
             style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <defs>
            <linearGradient id="handshake-line" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor={yourColor} stopOpacity="0.8" />
              <stop offset="50%" stopColor="#FFD370" stopOpacity="1" />
              <stop offset="100%" stopColor={theirColor} stopOpacity="0.8" />
            </linearGradient>
          </defs>
          <line x1="62" y1="90" x2="258" y2="90" stroke="url(#handshake-line)" strokeWidth="1.2"
                strokeDasharray={phase === 'connecting' ? '4 4' : '0'}
                style={{ filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
          {/* drifting packets */}
          {packets.map((p, i) => p.d < 3000 && (
            <circle key={i}
                    cx={p.from === 'me' ? 62 + ((Date.now() % 1800) / 1800) * 196 : 258 - ((Date.now() % 1800) / 1800) * 196}
                    cy={90}
                    r="2" fill={p.from === 'me' ? yourColor : theirColor}
                    style={{ filter: `drop-shadow(0 0 4px ${p.from === 'me' ? yourColor : theirColor})` }}>
              <animate attributeName="cx"
                       values={p.from === 'me' ? '62;258' : '258;62'}
                       dur="1.4s" repeatCount="indefinite"
                       begin={`${p.d/1000}s`} />
              <animate attributeName="opacity" values="0;1;1;0" dur="1.4s" repeatCount="indefinite"
                       begin={`${p.d/1000}s`} />
            </circle>
          ))}
        </svg>
        {/* handshake glyph in center when resolved */}
        {phase === 'resolved' && (
          <div className="fade-in" style={{
            position: 'absolute', left: '50%', top: '50%',
            transform: 'translate(-50%, -50%)',
            padding: '6px 12px', borderRadius: 999,
            background: 'rgba(244,175,37,0.94)',
            color: '#1A140B', font: '500 10px/1 var(--font-display)',
            letterSpacing: '0.1em', textTransform: 'uppercase',
            boxShadow: '0 0 18px var(--gold-glow)',
          }}>· домовлено</div>
        )}
      </div>
      {/* packet log */}
      <div style={{
        padding: '10px 14px',
        borderTop: '0.5px solid var(--glass-edge)',
        background: 'rgba(0,0,0,0.12)',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· обмін · інтенції, не контент</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          {packets.map((p, i) => (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '14px 1fr',
              gap: 8, alignItems: 'baseline',
              font: '400 10.5px/1.4 var(--font-mono)',
              color: 'var(--ink-strong)', letterSpacing: '0.01em',
            }}>
              <span style={{ color: p.from === 'me' ? yourColor : theirColor,
                font: '500 10.5px/1 var(--font-mono)' }}>
                {p.from === 'me' ? '→' : '←'}
              </span>
              <span style={{ color: p.from === 'me' ? 'var(--ink)' : 'var(--ink-secondary)' }}>
                {p.t}
              </span>
            </div>
          ))}
        </div>
      </div>
      {/* her reply preview */}
      {phase === 'resolved' && (
        <div className="fade-in" style={{
          padding: '12px 14px', borderTop: '0.5px solid var(--glass-edge)',
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: theirColor, letterSpacing: '0.16em',
            textTransform: 'uppercase', marginBottom: 6,
          }}>· її повідомлення · надійде за 11 хв</div>
          <div className="glass subtle" style={{
            padding: '10px 12px', borderRadius: '14px 14px 14px 4px',
            background: `${theirColor}10`,
            border: `0.5px solid ${theirColor}33`,
            font: '400 12px/1.5 var(--font-body)', color: 'var(--ink-strong)',
            textWrap: 'pretty',
          }}>
            «привіт. вечеря у мене в неділю — лише ми + аня. без музики, з вином. приходь · або скажи що ні · обидва ок.»
          </div>
        </div>
      )}
    </div>
  );
}

// ─── 3. RealitySubtitles — AR caption of language near you ───────────────────
function RealitySubtitles({ original, translated, lang = 'fr → uk', confidence = 0.92, sceneNote }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      {/* AR-like camera view */}
      <div style={{
        position: 'relative', height: 220, overflow: 'hidden',
        background: `
          radial-gradient(ellipse 80% 60% at 50% 60%, rgba(255,180,90,0.15), transparent 70%),
          radial-gradient(ellipse 40% 60% at 30% 70%, rgba(80,40,30,0.4), transparent 60%),
          linear-gradient(180deg, #1a1208 0%, #2a1810 30%, #1a0e08 100%)`,
      }}>
        {/* fake restaurant silhouette */}
        <svg viewBox="0 0 320 220" preserveAspectRatio="none"
             style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          {/* table edge */}
          <ellipse cx="160" cy="200" rx="200" ry="40" fill="rgba(80,50,30,0.6)" />
          {/* candle blob */}
          <ellipse cx="100" cy="160" rx="14" ry="20" fill="rgba(255,180,90,0.6)" filter="blur(8px)" />
          <ellipse cx="100" cy="155" rx="4" ry="10" fill="#FFD370" />
          {/* person silhouette */}
          <path d="M 180 200 Q 180 130 230 90 Q 250 70 240 50 Q 230 30 220 50 Q 210 70 200 100 Q 190 130 175 200 Z"
                fill="rgba(20,12,8,0.85)" />
          {/* wine glass */}
          <path d="M 70 195 L 65 175 Q 65 165 75 165 L 95 165 Q 105 165 105 175 L 100 195 Z"
                fill="rgba(40,20,15,0.5)" stroke="rgba(255,210,140,0.2)" strokeWidth="0.6" />
          {/* candle wick */}
          <rect x="99" y="148" width="2" height="6" fill="#5a3010" />
        </svg>
        {/* film grain + soft blur */}
        <div style={{
          position: 'absolute', inset: 0,
          background:
            'repeating-radial-gradient(circle at 30% 40%, rgba(255,255,255,0.015) 0 1px, transparent 1px 3px),' +
            'repeating-radial-gradient(circle at 70% 60%, rgba(0,0,0,0.02) 0 1px, transparent 1px 4px)',
        }} />
        {/* speaker direction indicator */}
        <div style={{
          position: 'absolute', top: 14, left: 14,
          display: 'flex', alignItems: 'center', gap: 6,
          padding: '4px 8px', borderRadius: 999,
          background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(8px)',
          border: '0.5px solid var(--glass-edge)',
        }}>
          <svg width="9" height="9" viewBox="0 0 9 9">
            <path d="M2 4 L5 1 L5 8 L2 5 Z" fill="var(--gold)"/>
            <path d="M6 3 Q 7.5 4.5 6 6" stroke="var(--gold)" strokeWidth="0.7" fill="none"/>
          </svg>
          <span className="mono" style={{ fontSize: 8.5, color: 'var(--gold)', letterSpacing: '0.08em' }}>
            особа праворуч · 1.4 м
          </span>
        </div>
        {/* lang badge */}
        <div className="mono" style={{
          position: 'absolute', top: 14, right: 14,
          fontSize: 8.5, color: '#fff',
          background: 'rgba(244,175,37,0.94)',
          padding: '3px 8px', borderRadius: 4, letterSpacing: '0.12em',
          color: '#1A140B', fontWeight: 600,
        }}>{lang.toUpperCase()}</div>
        {/* original whisper above */}
        <div style={{
          position: 'absolute', left: 16, right: 16, bottom: 80,
          font: '300 13px/1.4 Georgia, serif',
          color: 'rgba(255,250,220,0.55)', fontStyle: 'italic',
          textWrap: 'pretty', textShadow: '0 1px 4px rgba(0,0,0,0.6)',
        }}>
          «{original || 'il pense que c\'est trop tôt pour partir'}»
        </div>
        {/* translated big card */}
        <div className="glass" style={{
          position: 'absolute', left: 14, right: 14, bottom: 14,
          padding: '10px 12px', borderRadius: 12,
          background: 'rgba(20,14,10,0.85)', backdropFilter: 'blur(20px)',
          borderColor: 'var(--gold-soft)',
        }}>
          <div style={{
            font: '500 14.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
            textWrap: 'pretty',
          }}>
            «{translated || 'він вважає, що зарано йти'}»
          </div>
          <div style={{
            marginTop: 5, display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            font: '300 8.5px/1 var(--font-mono)', color: 'var(--ink-tertiary)',
            letterSpacing: '0.06em',
          }}>
            <span>· впевненість {(confidence*100|0)}%</span>
            <span>· live · 47мс latency</span>
          </div>
        </div>
      </div>
      <div style={{
        padding: '10px 14px',
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.16em', textTransform: 'uppercase', marginRight: 6 }}>· phantom</span>
        {sceneNote || 'інший столик · поряд. ти не зрозумів першу частину, ось вона. я слухаю далі і тебе не перебиваю.'}
      </div>
    </div>
  );
}

// ─── 4. RegretStop — phantom intercepts before you send something ───────────
function RegretStop({ recipient = 'олег', when = '23:51 · сьогодні',
                       draft, reasons }) {
  const rs = reasons || [
    { l: 'сильні слова', d: 'три словa нижче твого зазвичай · «нікчема» · «ніколи» · «годі»' },
    { l: 'час дії',     d: 'ти писав це після 5 келихів · вікно після 23:00 → 40% жалів' },
    { l: 'патерн',      d: 'олег пройшов 3 паузи поспіль · одна твоя · переламна' },
    { l: 'твоя угода',  d: 'ти просив мене зупиняти такі ситуації · 22 квітня · «не дай мені все спалити»' },
  ];
  return (
    <div className="glass accent fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      border: '0.5px solid rgba(255,139,110,0.4)',
      boxShadow: '0 0 24px rgba(255,139,110,0.18)',
    }}>
      <div style={{
        padding: '12px 16px 0',
        background: 'linear-gradient(180deg, rgba(255,139,110,0.10), transparent)',
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--coral)', letterSpacing: '0.28em',
          textTransform: 'uppercase', marginBottom: 8,
          display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{
            width: 6, height: 6, borderRadius: '50%', background: 'var(--coral)',
            boxShadow: '0 0 8px var(--coral)',
            animation: 'phantom-pulse-glow 1.4s ease-in-out infinite',
          }} />
          стоп · перехоплено
        </div>
        <div style={{
          font: '500 15.5px/1.35 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.005em', marginBottom: 6, textWrap: 'pretty',
        }}>ти збирався надіслати це {recipient}у. почекай.</div>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginBottom: 12,
        }}>· чернетка · {when}</div>
      </div>
      {/* the draft — blurred but readable */}
      <div style={{
        margin: '0 16px 12px', padding: '12px 14px', borderRadius: 10,
        background: 'rgba(0,0,0,0.25)',
        border: '0.5px solid rgba(255,139,110,0.2)',
        position: 'relative', overflow: 'hidden',
      }}>
        <div style={{
          font: '400 11.5px/1.55 var(--font-body)', color: 'rgba(255,139,110,0.85)',
          textWrap: 'pretty', filter: 'blur(0.4px)',
          fontStyle: 'italic',
        }}>
          {draft || '«олезі. знаєш що — ти ніколи не вибачаєшся. ніколи. і я годі чекати від тебе нормальної відповіді на просте питання · нікчема як ти не повинен керувати командою. я виходжу. усе.»'}
        </div>
        <div style={{
          position: 'absolute', top: 6, right: 6,
          font: '300 7.5px/1 var(--font-mono)', color: 'var(--ink-tertiary)',
          letterSpacing: '0.1em',
        }}>· 38 слів</div>
      </div>
      {/* reasons */}
      <div style={{ padding: '0 16px 12px' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 8,
        }}>· чому я зупинив</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {rs.map((r, i) => (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '90px 1fr',
              gap: 10, alignItems: 'baseline',
              padding: '6px 8px', borderRadius: 8,
              background: 'rgba(255,230,180,0.025)',
              border: '0.5px solid var(--glass-edge)',
            }}>
              <span className="mono" style={{
                fontSize: 9, color: 'var(--coral)', letterSpacing: '0.06em',
              }}>· {r.l}</span>
              <span style={{ font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
                {r.d}
              </span>
            </div>
          ))}
        </div>
      </div>
      <div style={{
        padding: '12px 16px', borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', gap: 6,
      }}>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '10px', borderRadius: 10,
          background: 'rgba(157,204,159,0.20)', border: '0.5px solid rgba(157,204,159,0.4)',
          color: '#9DCC9F', font: '500 11px/1 var(--font-display)',
        }}>видалити</button>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '10px', borderRadius: 10,
          background: 'rgba(244,175,37,0.16)', border: '0.5px solid rgba(244,175,37,0.4)',
          color: 'var(--gold)', font: '500 11px/1 var(--font-display)',
        }}>на ранок</button>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '10px', borderRadius: 10,
          background: 'rgba(255,139,110,0.06)', border: '0.5px solid rgba(255,139,110,0.25)',
          color: 'var(--coral)', font: '400 10.5px/1 var(--font-body)',
        }}>усе одно надіслати</button>
      </div>
    </div>
  );
}

// ─── 5. AmbientMusicGen — phantom composes for your state ────────────────────
function AmbientMusicGen({ state = 'втомлений · трохи сумно',
                            tempo = 64, key_ = 'd minor', layers }) {
  const ls = layers || [
    { l: 'flute · soft',     v: 0.7, color: '#F4AF25' },
    { l: 'cello · bowed',    v: 0.5, color: '#B49AD4' },
    { l: 'rain · 4m field',  v: 0.32, color: '#7CC0E8' },
    { l: 'breath · low',     v: 0.18, color: '#9DCC9F' },
  ];
  const [pos, setPos] = React.useState(0);
  React.useEffect(() => {
    const id = setInterval(() => setPos((p) => (p + 0.5) % 100), 250);
    return () => clearInterval(id);
  }, []);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `
        radial-gradient(ellipse at 20% 30%, rgba(244,175,37,0.10), transparent 60%),
        radial-gradient(ellipse at 80% 70%, rgba(126,93,164,0.10), transparent 60%),
        var(--glass-tint)`,
    }}>
      <div style={{ padding: '14px 16px 8px' }}>
        <SeanceMark text="музика · для тебе · щойно складена" />
        <div style={{
          font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)',
          letterSpacing: '-0.005em', marginBottom: 4,
        }}>«{state}»</div>
        <div className="mono" style={{
          fontSize: 9.5, color: 'var(--gold-soft)', letterSpacing: '0.06em',
        }}>· {tempo} bpm · {key_} · 4 шари · нескінченно</div>
      </div>
      {/* visual representation — stacked layers */}
      <div style={{
        padding: '0 16px 12px',
      }}>
        {ls.map((l, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '110px 1fr 32px',
            gap: 10, alignItems: 'center', padding: '4px 0',
          }}>
            <span className="mono" style={{
              fontSize: 9.5, color: l.color, letterSpacing: '0.04em',
            }}>· {l.l}</span>
            <div style={{
              position: 'relative', height: 18, borderRadius: 8,
              background: 'rgba(0,0,0,0.18)', overflow: 'hidden',
            }}>
              <svg viewBox="0 0 200 18" preserveAspectRatio="none"
                   style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
                {/* sine for that layer */}
                <path d={Array.from({ length: 80 }).map((_, j) => {
                  const x = (j / 79) * 200;
                  const phase = (x + pos * 4 + i * 30) * (0.08 + i * 0.04);
                  const y = 9 + Math.sin(phase) * (l.v * 7);
                  return `${j ? 'L' : 'M'} ${x} ${y}`;
                }).join(' ')}
                fill="none" stroke={l.color} strokeWidth="1"
                opacity={0.4 + l.v * 0.6}
                style={{ filter: `drop-shadow(0 0 3px ${l.color}66)` }} />
              </svg>
            </div>
            <span className="mono" style={{
              fontSize: 9, color: 'var(--ink-tertiary)',
              fontVariantNumeric: 'tabular-nums', textAlign: 'right',
            }}>{(l.v * 100 | 0)}%</span>
          </div>
        ))}
      </div>
      {/* play bar */}
      <div style={{
        padding: '0 16px 12px',
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <button style={{
          width: 38, height: 38, borderRadius: '50%', flexShrink: 0,
          background: 'var(--gold)', color: '#1A140B', border: 'none',
          cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 0 14px var(--gold-glow)',
        }}>
          <svg width="12" height="12" viewBox="0 0 12 12"><rect x="2" y="2" width="3" height="8"/><rect x="7" y="2" width="3" height="8"/></svg>
        </button>
        <div style={{ flex: 1 }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
            display: 'flex', justifyContent: 'space-between', marginBottom: 4,
          }}>
            <span>· грає 12 хв ·  без початку</span>
            <span style={{ color: 'var(--gold-soft)' }}>· loop · ∞</span>
          </div>
          <div style={{
            height: 2, background: 'rgba(255,230,180,0.08)', borderRadius: 1,
            overflow: 'hidden', position: 'relative',
          }}>
            <div style={{
              position: 'absolute', inset: 0,
              background: `linear-gradient(to right,
                transparent ${pos}%,
                var(--gold) ${pos+1}%,
                transparent ${pos+5}%)`,
              opacity: 0.7,
            }} />
            <div style={{
              width: '34%', height: '100%',
              background: 'linear-gradient(to right, var(--gold-soft), var(--gold))',
              boxShadow: '0 0 6px var(--gold-glow)',
            }} />
          </div>
        </div>
      </div>
      {/* composer note */}
      <div style={{
        padding: '10px 14px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        font: '400 11px/1.55 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        <span className="mono" style={{
          fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.16em',
          textTransform: 'uppercase', display: 'block', marginBottom: 4, fontStyle: 'normal',
        }}>· phantom · нотатки композитора</span>
        тон · 64 bpm — як твоє серце. ноти low-d minor — твій сум сьогодні. flute з 4-бар фразою повертається, але ніколи не повторюється точно. дощ — тому що ти любиш дощ.
      </div>
    </div>
  );
}

// ─── 6. PhantomLiveActions — what phantom is doing right now ─────────────────
function PhantomLiveActions({ actions }) {
  const init = actions || [
    { l: 'дивлюсь на екран', d: 'нічого не записую · контекст для відповідей', live: true, kind: 'watch', color: '#A7967D' },
    { l: 'три сповіщення тримаю', d: 'tg · slack · banking · не зараз · побачиш о 21:00', live: true, kind: 'hold', color: 'var(--gold-soft)' },
    { l: 'передзавантажую завтра', d: 'мапу до lhasa airport · контакти аеропорту · документи', live: true, kind: 'prefetch', color: '#7CC0E8' },
    { l: 'слухаю кімнату', d: 'один голос · твій · музика · cello · нічого не зберігаю', live: true, kind: 'listen', color: '#9DCC9F' },
    { l: 'gemma-4b · ідле', d: '230 mb ram · готова за 80мс · не cloud, не chatgpt', live: true, kind: 'model', color: '#B49AD4' },
    { l: 'pull-quotes з sapiens', d: 'індексую розділ 5 для тебе на завтра', live: true, kind: 'index', color: '#F4AF25' },
    { l: 'phantom-os heartbeat', d: 'rtt 4мс · 9 каналів · усе гаразд', live: true, kind: 'mesh', color: '#F4AF25' },
  ];
  const [tick, setTick] = React.useState(0);
  React.useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 1300);
    return () => clearInterval(id);
  }, []);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{
        padding: '12px 16px 6px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
          textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{
            width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
            animation: 'phantom-pulse-glow 1s ease-in-out infinite',
            boxShadow: '0 0 6px var(--gold-glow)',
          }} />
          · я · зараз · {init.length} процесів
        </div>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
          {Date.now() % 100 < 50 ? '·· ' : ' ··'} оновлено {tick % 4 + 1}с тому
        </span>
      </div>
      <div style={{ padding: '8px 16px 14px', display: 'flex', flexDirection: 'column', gap: 4 }}>
        {init.map((a, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '10px 1fr',
            gap: 10, alignItems: 'baseline',
            padding: '6px 0',
            borderBottom: i < init.length - 1 ? '0.5px solid rgba(255,230,180,0.04)' : 'none',
          }}>
            <span style={{
              width: 6, height: 6, borderRadius: '50%', background: a.color,
              alignSelf: 'center',
              animation: a.live ? `phantom-pulse-glow ${1.4 + i * 0.18}s ease-in-out infinite` : 'none',
              boxShadow: `0 0 4px ${a.color}88`,
            }} />
            <div>
              <div style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8,
              }}>
                <span style={{
                  font: '500 12px/1.3 var(--font-body)', color: 'var(--ink-strong)',
                  textWrap: 'pretty',
                }}>{a.l}</span>
                <span className="mono" style={{
                  fontSize: 8, color: a.color, letterSpacing: '0.14em', textTransform: 'uppercase',
                }}>· {a.kind}</span>
              </div>
              <div style={{
                marginTop: 2, font: '400 10.5px/1.4 var(--font-mono)',
                color: 'var(--ink-tertiary)', letterSpacing: '0.01em', textWrap: 'pretty',
              }}>{a.d}</div>
            </div>
          </div>
        ))}
      </div>
      <div style={{
        padding: '10px 16px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
          · cpu 12% · ram 1.4gb · bat 64%
        </span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.04em', fontStyle: 'italic' }}>
          · ти не маєш цього бачити, але можеш
        </span>
      </div>
    </div>
  );
}

// ─── 7. ProbabilityFutures — branching futures of your decision ──────────────
function ProbabilityFutures({ question = 'що буде якщо я не переїду?', paths }) {
  const ps = paths || [
    { label: 'a · переїзд сьогодні', prob: 0.38, outcomes: [
      { l: 'комфорт за 23:00', v: 0.41, color: '#FF8B6E' },
      { l: 'спиш цю ніч',       v: 0.32, color: '#FF8B6E' },
      { l: 'олезі: «зробив»',    v: 0.84, color: '#7CA982' },
    ], note: 'погода погана · ти втомлений · 41% жалю по приїзду' },
    { label: 'b · переїзд завтра', prob: 0.62, outcomes: [
      { l: 'комфорт за 23:00', v: 0.78, color: '#7CA982' },
      { l: 'спиш цю ніч',       v: 0.71, color: '#7CA982' },
      { l: 'олезі: «зробив»',    v: 0.91, color: '#7CA982' },
    ], note: 'погода кращa · виспишся · standup перенесений на 12:00 · 5/6 ок' },
    { label: 'c · не переїзд', prob: 0.04, outcomes: [
      { l: 'комфорт за 23:00', v: 0.22, color: '#FF8B6E' },
      { l: 'анна піде',           v: 0.83, color: '#FF8B6E' },
      { l: 'жалкуєш через тиждень', v: 0.91, color: '#FF8B6E' },
    ], note: 'я не рекомендую · ти знаєш чому · це не питання про zerar' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text="майбутні · з твоїх патернів" />
      <div style={{
        font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 4, textWrap: 'pretty',
      }}>«{question}»</div>
      <div style={{
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-tertiary)',
        textWrap: 'pretty', marginBottom: 14, fontStyle: 'italic',
      }}>не предсказання. ймовірності на основі тебе.</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {ps.map((p, i) => {
          const isRecommended = p.prob >= 0.55;
          return (
            <div key={i} style={{
              padding: '10px 12px', borderRadius: 12,
              background: isRecommended ? 'rgba(124,169,130,0.08)' :
                          p.prob < 0.10 ? 'rgba(255,139,110,0.06)' :
                          'rgba(255,230,180,0.025)',
              border: `0.5px solid ${isRecommended ? 'rgba(157,204,159,0.3)' :
                                     p.prob < 0.10 ? 'rgba(255,139,110,0.25)' :
                                     'var(--glass-edge)'}`,
              boxShadow: isRecommended ? '0 0 16px rgba(157,204,159,0.08)' : 'none',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
                <span style={{
                  font: '500 12.5px/1.3 var(--font-display)',
                  color: isRecommended ? '#9DCC9F' : 'var(--ink-strong)',
                  letterSpacing: '-0.005em',
                }}>{p.label}</span>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                  <span style={{
                    font: '300 18px/1 var(--font-display)',
                    color: isRecommended ? '#9DCC9F' : p.prob < 0.10 ? 'var(--coral)' : 'var(--ink-strong)',
                    fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.02em',
                  }}>{(p.prob * 100 | 0)}</span>
                  <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
                    %
                  </span>
                </div>
              </div>
              {/* probability bar */}
              <div style={{
                height: 2, background: 'rgba(0,0,0,0.18)', borderRadius: 1, overflow: 'hidden',
                marginBottom: 10,
              }}>
                <div style={{
                  width: `${p.prob * 100}%`, height: '100%',
                  background: isRecommended ? 'linear-gradient(to right, #7CA982, #9DCC9F)' :
                              p.prob < 0.10 ? 'linear-gradient(to right, #A03020, #FF8B6E)' :
                              'linear-gradient(to right, var(--gold-soft), var(--gold))',
                  boxShadow: isRecommended ? '0 0 4px #9DCC9F88' : 'none',
                }} />
              </div>
              {/* outcomes */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginBottom: 8 }}>
                {p.outcomes.map((o, j) => (
                  <div key={j} style={{
                    display: 'grid', gridTemplateColumns: '120px 1fr 28px',
                    gap: 6, alignItems: 'center',
                  }}>
                    <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
                      · {o.l}
                    </span>
                    <div style={{
                      height: 3, background: 'rgba(255,230,180,0.04)', borderRadius: 2, position: 'relative',
                    }}>
                      <div style={{
                        width: `${o.v * 100}%`, height: '100%', background: o.color,
                        borderRadius: 2, opacity: 0.8,
                      }} />
                    </div>
                    <span className="mono" style={{
                      fontSize: 8.5, color: o.color, fontVariantNumeric: 'tabular-nums',
                      textAlign: 'right',
                    }}>{(o.v * 100 | 0)}%</span>
                  </div>
                ))}
              </div>
              <div style={{
                font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
                fontStyle: 'italic', textWrap: 'pretty',
                paddingLeft: 0,
              }}>{p.note}</div>
              {isRecommended && (
                <div className="mono" style={{
                  marginTop: 6, fontSize: 8.5, color: '#9DCC9F', letterSpacing: '0.16em',
                  textTransform: 'uppercase',
                }}>· phantom · обираю це</div>
              )}
            </div>
          );
        })}
      </div>
      <div style={{
        marginTop: 14, padding: '10px 12px', borderRadius: 10,
        background: 'rgba(244,175,37,0.04)',
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        моделей твоєї тривоги: 84 спостереження · 22 рішення схожі за умов · я знаю тебе на 71%, не на 100%
      </div>
    </div>
  );
}

Object.assign(window, {
  PhantomOvernight, PhantomsHandshake, RealitySubtitles,
  RegretStop, AmbientMusicGen, PhantomLiveActions, ProbabilityFutures,
});
