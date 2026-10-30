/* phase3.jsx — frontier surfaces (TIER C + Phase 9).
   PdfChat, CalendarWeek, EmailTriage, SmartHome, SleepDashboard,
   ImageGenResult, MemoryPalace3D, DreamJournal, NegotiationCourt,
   AmbientHealth, TypographyPage, ConversationalSettings.
   Each is compact, self-contained, designed for the phone frame. */

// shared header for full-bleed surfaces
function SurfaceHead({ kicker, title, subtitle, onClose }) {
  return (
    <div style={{ padding: '14px 16px 8px', position: 'relative', zIndex: 5 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em',
            textTransform: 'uppercase', marginBottom: 6,
          }}>{kicker}</div>
          <div style={{
            font: '600 22px/1.05 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.01em',
          }}>{title}</div>
          {subtitle && (
            <div style={{
              font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
              marginTop: 6, textWrap: 'pretty', maxWidth: 320,
            }}>{subtitle}</div>
          )}
        </div>
        <button onClick={onClose} aria-label="back" style={{
          width: 32, height: 32, borderRadius: 16,
          background: 'rgba(255,248,231,0.04)', border: '0.5px solid rgba(255,248,231,0.08)',
          color: 'var(--ink-secondary)', cursor: 'pointer', flexShrink: 0,
          fontSize: 16,
        }}>×</button>
      </div>
    </div>
  );
}

// ─── PDF Chat ────────────────────────────────────────────────────────────────
function PdfChatScene({ onClose }) {
  const [page, setPage] = React.useState(1);
  const [q, setQ] = React.useState('');
  const [thread, setThread] = React.useState([
    { who: 'phantom', text: 'pdf на 412 сторінок · indexed · ask anything.',
      cite: null },
    { who: 'you', text: 'у чому головна теза першого розділу?' },
    { who: 'phantom',
      text: 'теза: cooperation у sapiens — це здатність вірити у спільні фікції. розділ обґрунтовує через приклад грошей, корпорацій та націй.',
      cite: { page: 28, snippet: 'humans cooperate flexibly because they share myths' } },
  ]);
  const ask = () => {
    if (!q.trim()) return;
    setThread((m) => [...m, { who: 'you', text: q }]);
    setQ('');
    setTimeout(() => setThread((m) => [...m, {
      who: 'phantom',
      text: 'на сторінці 142 автор зв\'язує це з аграрною революцією — пшениця одомашнила нас, не навпаки.',
      cite: { page: 142, snippet: 'we did not domesticate wheat. wheat domesticated us.' },
    }]), 800);
  };
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· pdf · rag" title="Sapiens — Yuval Harari" subtitle="412 стор. · мовою english · indexed 2 хв" onClose={onClose} />
      {/* PDF preview — fake first page rendering */}
      <div style={{ padding: '0 16px 8px', display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        <div className="glass" style={{
          width: 116, height: 152, borderRadius: 6, padding: 9,
          background: 'rgba(248,247,245,0.92)', flexShrink: 0,
          display: 'flex', flexDirection: 'column', gap: 4,
        }}>
          <div style={{ height: 5, background: '#1a140b', width: '70%', borderRadius: 1 }} />
          <div style={{ height: 2, background: 'rgba(26,20,11,0.4)', width: '40%', borderRadius: 1, marginBottom: 4 }} />
          {Array.from({ length: 14 }).map((_, i) => (
            <div key={i} style={{
              height: 1.5, background: 'rgba(26,20,11,0.45)',
              width: `${75 - (i % 4) * 8}%`, borderRadius: 1,
            }} />
          ))}
          <div style={{ flex: 1 }} />
          <div className="mono" style={{ fontSize: 6, color: '#5d5142', textAlign: 'center' }}>p. {page} / 412</div>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 6, flexWrap: 'wrap' }}>
            {[1, 28, 142, 380].map((p) => (
              <button key={p} onClick={() => setPage(p)} className="mono" style={{
                padding: '4px 9px', borderRadius: 8, fontSize: 10,
                background: p === page ? 'rgba(244,175,37,0.16)' : 'rgba(255,248,231,0.04)',
                border: p === page ? '0.5px solid rgba(244,175,37,0.4)' : '0.5px solid rgba(255,248,231,0.08)',
                color: p === page ? 'var(--gold-soft)' : 'var(--ink-secondary)',
                cursor: 'pointer',
              }}>p.{p}</button>
            ))}
          </div>
          <div style={{
            font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>розпізнано: 412 стор · 78 розділів · 2 311 виносок · мова en · author: y. harari</div>
        </div>
      </div>
      {/* chat thread about pdf */}
      <div style={{ flex: 1, overflow: 'auto', padding: '6px 16px 8px', minHeight: 0 }}>
        {thread.map((m, i) => (
          <div key={i} style={{
            display: 'flex',
            justifyContent: m.who === 'you' ? 'flex-end' : 'flex-start',
            marginBottom: 8,
          }}>
            <div className="glass" style={{
              maxWidth: m.who === 'you' ? '70%' : '88%',
              padding: '10px 13px',
              borderRadius: m.who === 'you' ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
              background: m.who === 'you' ? 'rgba(244,175,37,0.14)' : undefined,
            }}>
              <div style={{
                font: '400 12.5px/1.45 var(--font-body)', color: 'var(--ink-strong)',
                textWrap: 'pretty',
              }}>{m.text}</div>
              {m.cite && (
                <button onClick={() => setPage(m.cite.page)} style={{
                  marginTop: 8, padding: '4px 8px', fontSize: 10,
                  background: 'rgba(244,175,37,0.10)', borderRadius: 8,
                  border: '0.5px solid rgba(244,175,37,0.25)',
                  color: 'var(--gold-soft)', cursor: 'pointer',
                  font: '300 10px/1 var(--font-mono)',
                  letterSpacing: '0.05em',
                }}>
                  ↳ p.{m.cite.page} · "{m.cite.snippet.slice(0, 38)}..."
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
      <div style={{ padding: '8px 14px 14px', display: 'flex', gap: 8 }}>
        <input value={q} onChange={(e) => setQ(e.target.value)}
               onKeyDown={(e) => e.key === 'Enter' && ask()}
               placeholder="запитай про цей pdf…"
               style={{
                 flex: 1, background: 'rgba(255,248,231,0.04)', border: 'none',
                 borderRadius: 22, padding: '11px 16px',
                 font: '400 13px/1 var(--font-body)', color: 'var(--ink-strong)',
                 outline: 'none',
               }} />
        <button onClick={ask} style={{
          width: 44, height: 44, borderRadius: 22,
          background: 'rgba(244,175,37,0.85)', border: 'none',
          color: '#0E0A05', font: '600 16px/1 var(--font-display)',
          cursor: 'pointer',
        }}>↑</button>
      </div>
    </>
  );
}

// ─── Calendar week ───────────────────────────────────────────────────────────
function CalendarWeekScene({ onClose }) {
  const days = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'нд'];
  const today = 3; // чт
  const events = [
    { d: 0, start: 9.0,  end: 10.0,  title: '1:1 · богдан',     kind: 'meeting', energy: 'low' },
    { d: 0, start: 14.0, end: 15.5,  title: 'design review',    kind: 'meeting', energy: 'high' },
    { d: 1, start: 10.5, end: 12.0,  title: 'фокус · pr-823',   kind: 'focus',   energy: 'high' },
    { d: 1, start: 18.5, end: 20.0,  title: 'тренування',       kind: 'personal',energy: 'med' },
    { d: 2, start: 8.5,  end: 9.5,   title: 'літак до lhasa',   kind: 'travel',  energy: 'med', conflict: true },
    { d: 2, start: 9.0,  end: 10.0,  title: 'standup',          kind: 'meeting', energy: 'low', conflict: true },
    { d: 3, start: 11.0, end: 12.5,  title: 'олег · координати',kind: 'meeting', energy: 'high' },
    { d: 3, start: 19.5, end: 21.0,  title: 'вечеря · ольга',   kind: 'personal',energy: 'med' },
    { d: 4, start: 13.0, end: 17.0,  title: 'phantom-os build', kind: 'focus',   energy: 'high' },
    { d: 5, start: 10.0, end: 12.0,  title: 'ринок',            kind: 'personal',energy: 'low' },
    { d: 6, start: 19.0, end: 20.0,  title: 'dream journal',    kind: 'personal',energy: 'low' },
  ];
  const colorOf = (k) => ({
    meeting: '#F4AF25', focus: '#7CA982', personal: '#7E5DA4', travel: '#FF8B6E',
  })[k] || '#F4AF25';

  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· week 19 · травень" title="агенда — augmented" subtitle="phantom переглянув. 1 конфлікт. 4 фокус-вікна збережено."
                   onClose={onClose} />
      {/* AI summary */}
      <div className="glass" style={{
        margin: '0 14px 8px', padding: '10px 12px', borderRadius: 12,
        borderLeft: '2px solid rgba(244,175,37,0.5)',
      }}>
        <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 5 }}>· phantom · weekly read</div>
        <div style={{ font: '400 11.5px/1.45 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
          середа: standup о 9:00 пересікається з рейсом до lhasa · перенести на вівторок? <br/>
          енергія тиждня: висока на чт-пт, низька на нд · бережи фокус.
        </div>
      </div>
      {/* week grid */}
      <div style={{ flex: 1, overflow: 'auto', padding: '0 14px 12px', minHeight: 0 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '28px repeat(7, 1fr)', gap: 2, marginBottom: 6 }}>
          <div />
          {days.map((d, i) => (
            <div key={d} className="mono" style={{
              fontSize: 10, textAlign: 'center', padding: '4px 0',
              color: i === today ? 'var(--gold)' : 'var(--ink-secondary)',
              borderBottom: i === today ? '1px solid rgba(244,175,37,0.6)' : '0.5px solid rgba(255,248,231,0.06)',
              letterSpacing: '0.1em', textTransform: 'uppercase',
            }}>{d}<div style={{ fontSize: 13, marginTop: 1, color: 'var(--ink-strong)', fontWeight: 600 }}>{12 + i}</div></div>
          ))}
        </div>
        <div style={{ position: 'relative', height: 720 }}>
          {/* hour grid */}
          {Array.from({ length: 14 }).map((_, h) => {
            const hour = 8 + h;
            return (
              <div key={h} style={{
                position: 'absolute', left: 0, right: 0, top: h * (720 / 14), height: 720 / 14,
                borderTop: '0.5px solid rgba(255,248,231,0.04)',
                display: 'grid', gridTemplateColumns: '28px repeat(7, 1fr)', gap: 2,
              }}>
                <div className="mono" style={{
                  fontSize: 8, color: 'rgba(167,150,125,0.5)', paddingTop: 2, textAlign: 'right', paddingRight: 4,
                }}>{hour}</div>
                {Array.from({ length: 7 }).map((_, c) => (
                  <div key={c} style={{
                    background: c === today ? 'rgba(244,175,37,0.04)' : 'transparent',
                  }} />
                ))}
              </div>
            );
          })}
          {/* events */}
          {events.map((e, i) => {
            const top = (e.start - 8) * (720 / 14);
            const height = (e.end - e.start) * (720 / 14) - 2;
            const colW = `calc((100% - 28px - 12px) / 7)`;
            return (
              <div key={i} className="glass" style={{
                position: 'absolute',
                left: `calc(28px + 2px + ${e.d} * (${colW} + 2px))`,
                width: colW, top, height,
                padding: '4px 5px', borderRadius: 5,
                background: `${colorOf(e.kind)}28`,
                borderLeft: `1.5px solid ${colorOf(e.kind)}`,
                outline: e.conflict ? '0.5px solid rgba(231,111,81,0.6)' : 'none',
                overflow: 'hidden',
              }}>
                <div style={{ font: '500 9.5px/1.15 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{e.title}</div>
                <div className="mono" style={{ fontSize: 7, color: 'var(--ink-secondary)', marginTop: 2 }}>
                  {`${Math.floor(e.start).toString().padStart(2,'0')}:${(e.start%1*60).toString().padStart(2,'0')}`}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}

// ─── Email triage ────────────────────────────────────────────────────────────
function EmailTriageScene({ onClose }) {
  const [emails, setEmails] = React.useState([
    { id: 1, from: 'олександр з deepwell',   subj: 'нова версія protocol — твоя думка',         time: '14 хв',  ai: 'important', preview: 'надсилаю draft. цікавлять думки про state-channel.', priority: 'reply' },
    { id: 2, from: 'github',                 subj: 'pull request #823 ready for review',         time: '32 хв', ai: 'important', preview: 'Kirill requested your review on phantom-os/companion#823', priority: 'review' },
    { id: 3, from: 'medium · weekly',        subj: 'top stories on systems design',              time: '2 год', ai: 'digest',    preview: 'curated list — 18 stories across distributed systems' },
    { id: 4, from: 'aws billing',            subj: 'invoice 0x71-may',                           time: '4 год', ai: 'digest',    preview: '$23.41 charged · usage breakdown attached' },
    { id: 5, from: 'unknown · marketing',    subj: '🔥 last chance — 50% off premium bundle',    time: '6 год', ai: 'spam',      preview: 'limited time offer don\'t miss out…' },
    { id: 6, from: 'олена · банк',           subj: 'підтвердіть транзакцію 1 247 грн',          time: '8 год', ai: 'important', preview: 'якщо не ви — заблокуємо картку.', priority: 'verify' },
    { id: 7, from: 'newsletter · xkcd',      subj: 'comic 2837',                                 time: 'вчора', ai: 'digest' },
  ]);
  const groups = ['important', 'digest', 'spam'];
  const counts = { important: emails.filter((e) => e.ai === 'important').length,
    digest: emails.filter((e) => e.ai === 'digest').length, spam: emails.filter((e) => e.ai === 'spam').length };
  const colors = { important: '#F4AF25', digest: '#A7967D', spam: '#FF8B6E' };
  const labels = { important: 'важливе', digest: 'дайджест', spam: 'шум' };

  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· inbox · ai-classified" title="пошта" subtitle="phantom розклав за тебе. 2 чекають на відповідь."
                   onClose={onClose} />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 14px 14px', minHeight: 0 }}>
        {groups.map((g) => (
          <div key={g} style={{ marginBottom: 14 }}>
            <div className="mono" style={{
              fontSize: 9, color: colors[g], letterSpacing: '0.22em',
              textTransform: 'uppercase', padding: '6px 4px',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <span>· {labels[g]}</span>
              <span style={{ color: 'var(--ink-secondary)' }}>{counts[g]}</span>
            </div>
            {emails.filter((e) => e.ai === g).map((e) => (
              <div key={e.id} className="glass" style={{
                padding: '11px 13px', marginBottom: 6, borderRadius: 12,
                borderLeft: e.priority ? `2px solid ${colors[g]}` : 'none',
                opacity: g === 'spam' ? 0.55 : 1,
                cursor: 'pointer',
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty', flex: 1, minWidth: 0 }}>
                    {e.from}
                  </div>
                  <div className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', flexShrink: 0 }}>{e.time}</div>
                </div>
                <div style={{ font: '400 11.5px/1.3 var(--font-body)', color: 'var(--ink-secondary)', marginTop: 3, textWrap: 'pretty' }}>
                  {e.subj}
                </div>
                {e.preview && (
                  <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'rgba(167,150,125,0.7)', marginTop: 5, textWrap: 'pretty' }}>
                    {e.preview}
                  </div>
                )}
                {e.priority && (
                  <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                    <button style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: 10,
                      background: 'rgba(244,175,37,0.14)',
                      border: '0.5px solid rgba(244,175,37,0.3)', color: 'var(--gold-soft)',
                      cursor: 'pointer', font: '500 10px/1 var(--font-display)',
                    }}>{e.priority === 'reply' ? 'відповісти' : e.priority === 'review' ? 'переглянути' : 'підтвердити'}</button>
                    <button style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: 10,
                      background: 'rgba(255,248,231,0.04)',
                      border: '0.5px solid rgba(255,248,231,0.08)', color: 'var(--ink-secondary)',
                      cursor: 'pointer', font: '400 10px/1 var(--font-body)',
                    }}>пізніше</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

// ─── Smart home ──────────────────────────────────────────────────────────────
function SmartHomeScene({ onClose }) {
  const [temp, setTemp] = React.useState(21.5);
  const [light, setLight] = React.useState(72);
  const [music, setMusic] = React.useState(true);
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· home · matter" title="дім" subtitle="kyiv · 12 пристроїв · усе у нормі." onClose={onClose} />
      <div style={{ padding: '0 14px 14px', display: 'flex', flexDirection: 'column', gap: 10, overflow: 'auto', flex: 1, minHeight: 0 }}>
        {/* climate */}
        <div className="glass" style={{ padding: 14, borderRadius: 16 }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 6 }}>· клімат</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <div style={{ font: '300 44px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.02em' }}>{temp.toFixed(1)}</div>
            <div className="mono" style={{ fontSize: 14, color: 'var(--ink-secondary)' }}>°C</div>
            <div style={{ flex: 1 }} />
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)' }}>setpoint 21.0</div>
          </div>
          <input type="range" min={16} max={26} step={0.5} value={temp}
                 onChange={(e) => setTemp(parseFloat(e.target.value))}
                 style={{ width: '100%', accentColor: '#F4AF25', marginTop: 6 }} />
          <div className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', marginTop: 4 }}>
            вологість 47% · вентиляція auto · підлога 22.8°
          </div>
        </div>
        {/* lights + music side by side */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <div className="glass" style={{ padding: 12, borderRadius: 14 }}>
            <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase' }}>· світло</div>
            <div style={{ position: 'relative', height: 60, marginTop: 8 }}>
              <div style={{
                position: 'absolute', inset: 0,
                background: `radial-gradient(circle at 50% 50%, rgba(244,175,37,${light/130}) 0%, transparent 70%)`,
                borderRadius: 30,
              }} />
              <div style={{
                position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)',
                font: '300 22px/1 var(--font-display)', color: 'var(--ink-strong)',
              }}>{light}%</div>
            </div>
            <input type="range" min={0} max={100} value={light}
                   onChange={(e) => setLight(parseInt(e.target.value))}
                   style={{ width: '100%', accentColor: '#F4AF25', marginTop: 4 }} />
            <div className="mono" style={{ fontSize: 8, color: 'var(--ink-secondary)', marginTop: 3 }}>
              4 групи · sunset preset
            </div>
          </div>
          <div className="glass" style={{ padding: 12, borderRadius: 14 }}>
            <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase' }}>· музика</div>
            <div style={{ font: '500 11.5px/1.3 var(--font-body)', color: 'var(--ink-strong)', marginTop: 8, textWrap: 'pretty' }}>
              C. & L.
            </div>
            <div style={{ font: '400 10px/1.3 var(--font-body)', color: 'var(--ink-secondary)' }}>sufjan stevens</div>
            <div style={{ display: 'flex', gap: 4, alignItems: 'flex-end', height: 16, marginTop: 6 }}>
              {music && Array.from({ length: 16 }).map((_, i) => (
                <div key={i} style={{
                  flex: 1, height: `${20 + Math.sin(i * 0.7 + Date.now() / 200) * 30 + 30}%`,
                  background: 'var(--gold-soft)', borderRadius: 0.5, minHeight: 2, opacity: 0.7,
                  animation: `pulseBar ${0.6 + i * 0.05}s ease-in-out infinite alternate`,
                }} />
              ))}
            </div>
            <button onClick={() => setMusic(!music)} className="mono" style={{
              marginTop: 6, padding: '4px 8px', borderRadius: 6, fontSize: 9,
              background: music ? 'rgba(244,175,37,0.14)' : 'rgba(255,248,231,0.04)',
              border: '0.5px solid rgba(244,175,37,0.25)', color: music ? 'var(--gold-soft)' : 'var(--ink-secondary)',
              cursor: 'pointer', width: '100%',
            }}>{music ? '⏸ pause' : '▶ play'}</button>
          </div>
        </div>
        {/* cameras */}
        <div className="glass" style={{ padding: 12, borderRadius: 14 }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>· камери · 4</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
            {['parad', 'kuxnia', 'balkon', 'dvir'].map((label, i) => (
              <div key={label} style={{
                aspectRatio: '16/9', borderRadius: 6, position: 'relative', overflow: 'hidden',
                background: `linear-gradient(${135 + i * 30}deg, rgba(167,150,125,0.18), rgba(45,33,21,0.4))`,
                border: '0.5px solid rgba(255,248,231,0.05)',
              }}>
                <div className="mono" style={{
                  position: 'absolute', left: 6, top: 4, fontSize: 8, color: 'var(--ink-strong)', textTransform: 'uppercase', letterSpacing: '0.1em',
                }}>{label}</div>
                <div style={{
                  position: 'absolute', right: 6, top: 5, width: 5, height: 5, borderRadius: '50%',
                  background: '#FF8B6E', boxShadow: '0 0 6px #FF8B6E',
                  animation: 'pulseRec 2s ease-in-out infinite',
                }} />
              </div>
            ))}
          </div>
        </div>
      </div>
      <style>{`
        @keyframes pulseBar { 0% { opacity: 0.4; } 100% { opacity: 0.9; } }
        @keyframes pulseRec { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }
      `}</style>
    </>
  );
}

// ─── Sleep dashboard ─────────────────────────────────────────────────────────
function SleepDashboardScene({ onClose }) {
  // simulated sleep cycles 23:00 → 07:00 (8h)
  const points = Array.from({ length: 96 }).map((_, i) => {
    const t = i / 95;
    const cycle = Math.sin(t * Math.PI * 4.2) * 0.5 + 0.5;
    const drift = Math.sin(t * Math.PI * 0.7) * 0.2;
    const noise = (Math.sin(i * 0.7) + Math.sin(i * 1.3)) * 0.04;
    return Math.max(0, Math.min(1, cycle * 0.7 + drift + 0.15 + noise));
  });
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· сон · ніч 8 травня" title="ти спав 7 год 42 хв" subtitle="3 повні цикли rem · відновлення 84 / 100" onClose={onClose} />
      <div style={{ padding: '0 14px 12px', display: 'flex', flexDirection: 'column', gap: 10, flex: 1, overflow: 'auto', minHeight: 0 }}>
        {/* recovery score circle */}
        <div className="glass" style={{ padding: 14, borderRadius: 16, display: 'flex', alignItems: 'center', gap: 14 }}>
          <svg width="72" height="72" viewBox="0 0 72 72">
            <circle cx="36" cy="36" r="30" fill="none" stroke="rgba(255,248,231,0.08)" strokeWidth="3" />
            <circle cx="36" cy="36" r="30" fill="none" stroke="#7CA982" strokeWidth="3"
                    strokeLinecap="round" strokeDasharray={`${0.84 * 188} 188`}
                    transform="rotate(-90 36 36)" />
            <text x="36" y="40" textAnchor="middle"
                  style={{ font: '300 18px var(--font-display)', fill: 'var(--ink-strong)' }}>84</text>
          </svg>
          <div style={{ flex: 1 }}>
            <div style={{ font: '500 13px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>відновлено</div>
            <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)', marginTop: 4, textWrap: 'pretty' }}>
              можеш брати важче навантаження сьогодні. але обід — раніше за 13:00.
            </div>
          </div>
        </div>
        {/* hypnogram */}
        <div className="glass" style={{ padding: 12, borderRadius: 14 }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>· hypnogram</div>
          <svg viewBox="0 0 320 90" style={{ width: '100%', height: 90, display: 'block' }}>
            <path d={`M 0,${90 - points[0] * 80} ${points.map((p, i) => `L ${i / (points.length - 1) * 320},${90 - p * 80}`).join(' ')} L 320,90 L 0,90 Z`}
                  fill="url(#sleepGrad)" />
            <path d={`M 0,${90 - points[0] * 80} ${points.map((p, i) => `L ${i / (points.length - 1) * 320},${90 - p * 80}`).join(' ')}`}
                  fill="none" stroke="#F4AF25" strokeWidth="1" strokeOpacity="0.7" />
            <defs>
              <linearGradient id="sleepGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="rgba(244,175,37,0.4)" />
                <stop offset="100%" stopColor="rgba(244,175,37,0)" />
              </linearGradient>
            </defs>
          </svg>
          <div className="mono" style={{ display: 'flex', justifyContent: 'space-between', fontSize: 8, color: 'var(--ink-secondary)', marginTop: 4 }}>
            <span>23:00</span><span>01:00</span><span>03:00</span><span>05:00</span><span>07:00</span>
          </div>
          <div style={{ display: 'flex', gap: 14, marginTop: 10, font: '400 10px/1.2 var(--font-body)', color: 'var(--ink-secondary)' }}>
            <span><b style={{ color: '#7E5DA4' }}>● </b>deep · 1:42</span>
            <span><b style={{ color: '#F4AF25' }}>● </b>rem · 1:54</span>
            <span><b style={{ color: '#A7967D' }}>● </b>light · 4:06</span>
          </div>
        </div>
        {/* recurring themes */}
        <div className="glass" style={{ padding: 12, borderRadius: 14 }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 6 }}>· повторювані теми · 7 днів</div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {[
              { tag: 'дім дитинства', n: 4 },
              { tag: 'термінал', n: 3 },
              { tag: 'літак', n: 2 },
              { tag: 'не встигаю', n: 2 },
              { tag: 'олег', n: 1 },
            ].map((t) => (
              <span key={t.tag} className="mono" style={{
                padding: '4px 9px', borderRadius: 12, fontSize: 10,
                background: `rgba(244,175,37,${0.06 + t.n * 0.04})`,
                border: '0.5px solid rgba(244,175,37,0.2)',
                color: 'var(--gold-soft)',
              }}>{t.tag} · {t.n}</span>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

// ─── Image generation result ─────────────────────────────────────────────────
function ImageGenScene({ onClose }) {
  const [selected, setSelected] = React.useState(0);
  const variations = [0, 1, 2, 3];
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· image · diffusion · 1024×1024" title="«lhasa o 19:30, листопад»" subtitle="generated 8.4s · 4 варіанти · stable-diffusion-xl-turbo" onClose={onClose} />
      <div style={{ padding: '0 14px 14px', flex: 1, overflow: 'auto', minHeight: 0 }}>
        {/* main image */}
        <div style={{
          aspectRatio: '1', borderRadius: 18, overflow: 'hidden',
          position: 'relative', marginBottom: 10,
        }}>
          {/* fake painterly image */}
          <div style={{
            position: 'absolute', inset: 0,
            background: selected === 0 ? `
              radial-gradient(ellipse 70% 40% at 30% 25%, #f4af25, transparent 60%),
              radial-gradient(ellipse 50% 30% at 70% 60%, #e76f51, transparent 60%),
              radial-gradient(ellipse 80% 30% at 50% 90%, #2a221a, transparent 60%),
              linear-gradient(180deg, #1a1108 0%, #5a3818 40%, #8a4818 60%, #2a1808 100%)
            ` : selected === 1 ? `
              radial-gradient(ellipse 60% 50% at 50% 30%, #ffb84a, transparent 65%),
              linear-gradient(180deg, #2a1d10 0%, #8a5828 50%, #1a0d05 100%)
            ` : selected === 2 ? `
              radial-gradient(ellipse 50% 35% at 25% 35%, #f4af25, transparent 70%),
              radial-gradient(ellipse 70% 40% at 70% 70%, #7e5da4, transparent 70%),
              linear-gradient(180deg, #1a0e1a 0%, #2a1818 50%, #0a0405 100%)
            ` : `
              radial-gradient(ellipse 80% 40% at 50% 20%, #f4af25, transparent 50%),
              radial-gradient(ellipse 60% 30% at 50% 70%, #2a1810, transparent 60%),
              linear-gradient(180deg, #0e0a05 0%, #3a2210 60%, #0a0805 100%)
            `,
          }} />
          {/* mountain silhouette */}
          <svg viewBox="0 0 400 200" preserveAspectRatio="none" style={{ position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '60%', opacity: 0.7 }}>
            <path d="M 0 200 L 0 130 L 50 110 L 100 125 L 150 80 L 200 100 L 250 70 L 300 95 L 350 75 L 400 105 L 400 200 Z"
                  fill="#0e0a05" />
          </svg>
          {/* meta overlay */}
          <div className="mono" style={{
            position: 'absolute', left: 10, bottom: 10, fontSize: 9,
            color: 'rgba(255,248,231,0.6)', letterSpacing: '0.1em',
            background: 'rgba(0,0,0,0.4)', padding: '3px 7px', borderRadius: 4,
          }}>{selected + 1} / 4 · seed 0x8a3f</div>
        </div>
        {/* variations strip */}
        <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
          {variations.map((v) => (
            <button key={v} onClick={() => setSelected(v)} style={{
              flex: 1, aspectRatio: '1', borderRadius: 8, padding: 0,
              border: v === selected ? '1.5px solid #F4AF25' : '0.5px solid rgba(255,248,231,0.08)',
              cursor: 'pointer', overflow: 'hidden',
              background: v === 0 ? 'linear-gradient(160deg, #f4af25, #2a1808)' :
                          v === 1 ? 'linear-gradient(160deg, #ffb84a, #1a0d05)' :
                          v === 2 ? 'linear-gradient(160deg, #f4af25, #7e5da4)' :
                                    'linear-gradient(160deg, #f4af25, #0a0805)',
            }} />
          ))}
        </div>
        {/* actions */}
        <div style={{ display: 'flex', gap: 8 }}>
          <button style={{
            flex: 1, padding: '12px', borderRadius: 14,
            background: 'rgba(244,175,37,0.85)', border: 'none',
            color: '#0E0A05', font: '500 12px/1 var(--font-display)', cursor: 'pointer',
          }}>↓ зберегти</button>
          <button style={{
            flex: 1, padding: '12px', borderRadius: 14,
            background: 'rgba(255,248,231,0.06)', border: '0.5px solid rgba(255,248,231,0.1)',
            color: 'var(--ink-strong)', font: '500 12px/1 var(--font-body)', cursor: 'pointer',
          }}>↻ ще варіанти</button>
        </div>
      </div>
    </>
  );
}

// ─── Memory Palace 3D ────────────────────────────────────────────────────────
function MemoryPalaceScene({ onClose }) {
  const canvasRef = React.useRef(null);
  const [hovered, setHovered] = React.useState(null);
  const memories = React.useMemo(() => ([
    { id: 1, label: 'олег · кафе',    date: '14 травня', x: -2, y:  0.3, z:  0,    color: '#F4AF25' },
    { id: 2, label: 'код о 3 ночі',   date: '11 травня', x: -0.5, y: 1.2, z: -1.5, color: '#7CA982' },
    { id: 3, label: 'мати в lviv',    date: '8 травня',  x:  1.5, y: -0.5, z: -0.5,color: '#FF8B6E' },
    { id: 4, label: 'phantom-os build',date:'5 травня',  x:  2.0, y:  0.8, z:  1.0,color: '#7E5DA4' },
    { id: 5, label: 'ольга · вечеря', date: '2 травня', x: -1.2, y: -0.8, z:  1.2, color: '#F4AF25' },
    { id: 6, label: 'ранок без кави', date: '28 квітня',x:  0.3, y:  1.5, z:  0.6, color: '#A7967D' },
    { id: 7, label: 'sapiens · ст 142',date:'25 квітня', x:  0.8, y: -1.3, z: -1.2,color: '#7CA982' },
    { id: 8, label: 'дощ і pr-823',   date: '20 квітня',x: -1.8, y:  0.6, z:  0.9, color: '#FF8B6E' },
  ]), []);

  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let raf, rotation = 0;
    const w = canvas.width = canvas.clientWidth * 2;
    const h = canvas.height = canvas.clientHeight * 2;

    const proj = (x, y, z) => {
      const cs = Math.cos(rotation), sn = Math.sin(rotation);
      const rx = x * cs - z * sn, rz = x * sn + z * cs;
      const f = 320 / (5 + rz);
      return { px: w / 2 + rx * f, py: h / 2 + y * f, scale: f / 80, depth: rz };
    };

    const draw = () => {
      ctx.clearRect(0, 0, w, h);
      // backdrop dust
      for (let i = 0; i < 30; i++) {
        const x = (Math.sin(i * 1.7 + rotation * 0.5) + 1) / 2 * w;
        const y = (Math.cos(i * 2.3 + rotation * 0.3) + 1) / 2 * h;
        ctx.fillStyle = `rgba(244,175,37,${0.04 + Math.sin(i + rotation) * 0.03})`;
        ctx.fillRect(x, y, 1.5, 1.5);
      }
      // grid floor
      ctx.strokeStyle = 'rgba(244,175,37,0.06)';
      ctx.lineWidth = 1;
      for (let g = -3; g <= 3; g += 0.5) {
        const a = proj(-3, 1.8, g), b = proj(3, 1.8, g);
        ctx.beginPath(); ctx.moveTo(a.px, a.py); ctx.lineTo(b.px, b.py); ctx.stroke();
        const c = proj(g, 1.8, -3), d = proj(g, 1.8, 3);
        ctx.beginPath(); ctx.moveTo(c.px, c.py); ctx.lineTo(d.px, d.py); ctx.stroke();
      }
      // sort tiles by depth (back-to-front)
      const projected = memories.map((m) => ({ ...m, ...proj(m.x, m.y, m.z) }));
      projected.sort((a, b) => a.depth - b.depth);
      for (const m of projected) {
        const size = 64 * m.scale;
        const alpha = Math.max(0.3, Math.min(1, (m.depth + 5) / 8));
        // glow
        ctx.fillStyle = `${m.color}26`;
        ctx.beginPath(); ctx.arc(m.px, m.py, size * 1.4, 0, Math.PI * 2); ctx.fill();
        // tile
        ctx.fillStyle = m.color;
        ctx.globalAlpha = alpha;
        ctx.fillRect(m.px - size / 2, m.py - size / 2, size, size);
        // label
        ctx.fillStyle = 'rgba(255,248,231,0.85)';
        ctx.font = `500 ${10 * m.scale}px Inter, sans-serif`;
        ctx.textAlign = 'center';
        ctx.fillText(m.label, m.px, m.py + size / 2 + 14 * m.scale);
        ctx.globalAlpha = 1;
      }
      rotation += 0.0035;
      raf = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, [memories]);

  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· memory palace · 3d" title="простір памʼяті" subtitle="8 збережених моментів · обертається · крокни всередину." onClose={onClose} />
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden', minHeight: 0 }}>
        <canvas ref={canvasRef} style={{ width: '100%', height: '100%', display: 'block' }} />
        {/* hint at bottom */}
        <div className="mono" style={{
          position: 'absolute', left: 0, right: 0, bottom: 14, textAlign: 'center',
          fontSize: 9, color: 'var(--ink-secondary)', letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>· tap a tile to relive ·</div>
      </div>
    </>
  );
}

// ─── Dream journal ───────────────────────────────────────────────────────────
function DreamJournalScene({ onClose }) {
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· dream journal · 9 травня · 07:14" title="ти прокинувся" subtitle="ніч tracked · 3 цикли rem · сни кластеризовано." onClose={onClose} />
      <div style={{ padding: '0 14px 14px', flex: 1, overflow: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div className="glass" style={{ padding: 14, borderRadius: 16, borderLeft: '2px solid rgba(126,93,164,0.5)' }}>
          <div className="mono" style={{ fontSize: 9, color: '#7E5DA4', letterSpacing: '0.22em', textTransform: 'uppercase', marginBottom: 8 }}>· rem · 4:42 — 5:48</div>
          <div style={{ font: '400 13px/1.5 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
            знов кухня дитинства. матір десь поруч, голос є, але не видно. ти шукаєш ключ — він у банці зі сіллю. вікно відчинене, листопад.
          </div>
          <div style={{ display: 'flex', gap: 5, marginTop: 10, flexWrap: 'wrap' }}>
            {['кухня', 'мати', 'ключ', 'листопад'].map((t) => (
              <span key={t} className="mono" style={{
                padding: '3px 8px', borderRadius: 10, fontSize: 9,
                background: 'rgba(126,93,164,0.12)', color: '#7E5DA4',
                border: '0.5px solid rgba(126,93,164,0.25)',
              }}>{t}</span>
            ))}
          </div>
        </div>
        <div className="glass" style={{ padding: 14, borderRadius: 16 }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em', textTransform: 'uppercase', marginBottom: 8 }}>· повтор · 4 ночі поспіль</div>
          <div style={{ font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)', textWrap: 'pretty' }}>
            тема <b style={{ color: 'var(--ink-strong)' }}>«дім дитинства»</b> повертається. востаннє коли так було — три тижні перед тим, як ти зважився на переїзд.
          </div>
          <button style={{
            marginTop: 10, padding: '8px 12px', borderRadius: 10, fontSize: 11,
            background: 'rgba(244,175,37,0.14)', border: '0.5px solid rgba(244,175,37,0.3)',
            color: 'var(--gold-soft)', cursor: 'pointer', font: '500 11px/1 var(--font-display)',
          }}>↳ переглянути патерн</button>
        </div>
        {/* voice memo capture */}
        <div className="glass" style={{ padding: 14, borderRadius: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
          <button style={{
            width: 44, height: 44, borderRadius: 22,
            background: 'rgba(244,175,37,0.85)', border: 'none',
            color: '#0E0A05', font: '500 16px/1 var(--font-display)', cursor: 'pointer',
          }}>●</button>
          <div style={{ flex: 1 }}>
            <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>записати ще, поки памʼять свіжа</div>
            <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)', marginTop: 3 }}>
              30 сек · transcribed · індексовано приватно
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

// ─── Negotiation court ───────────────────────────────────────────────────────
function NegotiationCourtScene({ onClose }) {
  const transcript = [
    { who: 'A', text: 'kirill пропонує: 4-денний робочий тиждень з пробним періодом 3 місяці.', time: '14:22' },
    { who: 'B', text: 'olya приймає принцип. зустрічна умова: 4 повних дні, не 4×8 годин.', time: '14:22' },
    { who: 'A', text: 'погоджується на 4 повних дні. хоче гарантію retention — не менше 3 проектів.', time: '14:23' },
    { who: 'B', text: 'olya: 3 проекти підтверджує. вимагає вечір середи на синхрон.', time: '14:23' },
    { who: '·', text: 'consensus: 4 дні · 3 проекти · сер 18:00–19:30 sync · review через 12 тижнів.', time: '14:24' },
  ];
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· negotiation · phantom × phantom" title="court" subtitle="обидва phantoms у safe-room · ваші cryptographic signatures прикладено." onClose={onClose} />
      <div style={{ padding: '0 14px 14px', flex: 1, display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
        {/* split header */}
        <div style={{ display: 'flex', gap: 10 }}>
          <div className="glass" style={{ flex: 1, padding: '10px 12px', borderRadius: 12, borderLeft: '2px solid #F4AF25' }}>
            <div className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase' }}>· party a</div>
            <div style={{ font: '500 12.5px/1.2 var(--font-body)', color: 'var(--ink-strong)', marginTop: 3 }}>kirill</div>
          </div>
          <div className="glass" style={{ flex: 1, padding: '10px 12px', borderRadius: 12, borderLeft: '2px solid #7E5DA4' }}>
            <div className="mono" style={{ fontSize: 8, color: '#7E5DA4', letterSpacing: '0.2em', textTransform: 'uppercase' }}>· party b</div>
            <div style={{ font: '500 12.5px/1.2 var(--font-body)', color: 'var(--ink-strong)', marginTop: 3 }}>olya</div>
          </div>
        </div>
        {/* transcript */}
        <div style={{ flex: 1, overflow: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {transcript.map((t, i) => (
            <div key={i} style={{
              display: 'flex', justifyContent: t.who === '·' ? 'center' : t.who === 'A' ? 'flex-start' : 'flex-end',
            }}>
              <div className="glass" style={{
                maxWidth: '78%', padding: '10px 12px', borderRadius: 12,
                background: t.who === '·' ? 'rgba(124,169,130,0.1)' :
                            t.who === 'A' ? 'rgba(244,175,37,0.07)' : 'rgba(126,93,164,0.08)',
                borderLeft: t.who === '·' ? '2px solid #7CA982' : 'none',
              }}>
                <div style={{
                  font: '400 11.5px/1.45 var(--font-body)',
                  color: t.who === '·' ? '#9DCC9F' : 'var(--ink-strong)',
                  textWrap: 'pretty',
                }}>{t.text}</div>
                <div className="mono" style={{ fontSize: 8, color: 'var(--ink-secondary)', marginTop: 4 }}>{t.time}</div>
              </div>
            </div>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button style={{
            flex: 1, padding: 12, borderRadius: 14,
            background: '#7CA982', border: 'none',
            color: '#0E0A05', font: '500 12px/1 var(--font-display)', cursor: 'pointer',
          }}>підписати</button>
          <button style={{
            flex: 1, padding: 12, borderRadius: 14,
            background: 'rgba(255,248,231,0.06)', border: '0.5px solid rgba(255,248,231,0.1)',
            color: 'var(--ink-strong)', font: '500 12px/1 var(--font-body)', cursor: 'pointer',
          }}>обговорити</button>
        </div>
      </div>
    </>
  );
}

// ─── Ambient health forecast ─────────────────────────────────────────────────
function AmbientHealthScene({ onClose }) {
  // 6 weeks of stress
  const weeks = Array.from({ length: 42 }).map((_, i) => {
    const t = i / 41;
    const past = i < 28;
    const v = past
      ? 0.4 + Math.sin(i * 0.4) * 0.15 + (i / 41) * 0.25
      : 0.55 + Math.sin(i * 0.3) * 0.10 + (i - 28) / 13 * 0.15;
    return { v: Math.max(0.1, Math.min(0.95, v)), past, day: i };
  });
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· ambient health · forecast" title="наступні 6 тижнів" subtitle="hrv-based · підвищена ймовірність вигорання тиждень 5. дій тепер." onClose={onClose} />
      <div style={{ padding: '0 14px 14px', flex: 1, overflow: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {/* curve */}
        <div className="glass" style={{ padding: 14, borderRadius: 16 }}>
          <svg viewBox="0 0 380 140" style={{ width: '100%', height: 140, display: 'block' }}>
            {/* threshold line */}
            <line x1="0" y1="42" x2="380" y2="42" stroke="rgba(231,111,81,0.4)" strokeWidth="1" strokeDasharray="2,3" />
            {/* past area */}
            <path d={`M 0,140 ${weeks.filter((w) => w.past).map((w, i) => `L ${i / 41 * 380},${140 - w.v * 100}`).join(' ')} L ${27 / 41 * 380},140 Z`}
                  fill="url(#pastFill)" />
            {/* future area */}
            <path d={`M ${28 / 41 * 380},140 ${weeks.filter((w) => !w.past).map((w, i) => `L ${(i + 28) / 41 * 380},${140 - w.v * 100}`).join(' ')} L ${380},140 Z`}
                  fill="url(#futureFill)" />
            {/* line */}
            <path d={`M 0,${140 - weeks[0].v * 100} ${weeks.map((w, i) => `L ${i / 41 * 380},${140 - w.v * 100}`).join(' ')}`}
                  fill="none" stroke="#F4AF25" strokeWidth="1.5" />
            {/* now marker */}
            <line x1={28 / 41 * 380} y1="0" x2={28 / 41 * 380} y2="140" stroke="rgba(255,248,231,0.3)" strokeWidth="0.5" strokeDasharray="2,2" />
            <text x={28 / 41 * 380} y="12" textAnchor="middle"
                  style={{ font: '400 8px JetBrains Mono', fill: 'rgba(255,248,231,0.5)' }}>зараз</text>
            <defs>
              <linearGradient id="pastFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="rgba(244,175,37,0.25)" />
                <stop offset="100%" stopColor="rgba(244,175,37,0)" />
              </linearGradient>
              <linearGradient id="futureFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="rgba(231,111,81,0.3)" />
                <stop offset="100%" stopColor="rgba(231,111,81,0)" />
              </linearGradient>
            </defs>
          </svg>
          <div className="mono" style={{ display: 'flex', justifyContent: 'space-between', fontSize: 8, color: 'var(--ink-secondary)', marginTop: 4 }}>
            <span>тиж 1</span><span>2</span><span>3</span><span>4</span><span>5</span><span>6</span>
          </div>
        </div>
        {/* interventions */}
        <div className="glass" style={{ padding: 12, borderRadius: 14, borderLeft: '2px solid #7CA982' }}>
          <div className="mono" style={{ fontSize: 9, color: '#9DCC9F', letterSpacing: '0.22em', textTransform: 'uppercase', marginBottom: 6 }}>· що допоможе</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {[
              { w: 'тиждень 3', a: 'один день без екранів — phantom візьме всі повідомлення, відповість де зможе.' },
              { w: 'тиждень 4', a: '7 годин сну мінімум · phantom вмикає shadow mode після 23:30.' },
              { w: 'тиждень 5', a: 'перенести design review на тиждень 6 · ольга погодиться.' },
            ].map((it, i) => (
              <div key={i} style={{ display: 'flex', gap: 8 }}>
                <div className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', minWidth: 70, paddingTop: 2 }}>{it.w}</div>
                <div style={{ font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-strong)', flex: 1, textWrap: 'pretty' }}>{it.a}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

// ─── Typography sample ──────────────────────────────────────────────────────
function TypographyScene({ onClose }) {
  return (
    <>
      <StatusBar />
      <SurfaceHead kicker="· typography · phantom" title="голос системи" subtitle="inter tight · inter · jetbrains mono" onClose={onClose} />
      <div style={{ padding: '0 18px 14px', flex: 1, overflow: 'auto', minHeight: 0 }}>
        <div style={{ font: '600 44px/0.95 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.02em', marginBottom: 6 }}>
          ТУТ І ЗАРАЗ
        </div>
        <div style={{ font: '300 28px/1.05 var(--font-display)', color: 'var(--ink-secondary)', letterSpacing: '-0.015em', marginBottom: 16 }}>
          присутність, не dashboard.
        </div>
        <hr style={{ border: 'none', borderTop: '0.5px solid rgba(255,248,231,0.08)', margin: '14px 0' }} />
        <div className="mono" style={{ fontSize: 10, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>display · inter tight</div>
        {[
          { w: 700, label: '700' }, { w: 600, label: '600' }, { w: 500, label: '500' }, { w: 400, label: '400' },
        ].map((s) => (
          <div key={s.w} style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 4 }}>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)', minWidth: 32 }}>{s.label}</span>
            <span style={{ font: `${s.w} 24px var(--font-display)`, color: 'var(--ink-strong)', letterSpacing: '-0.01em' }}>фантом · familiar</span>
          </div>
        ))}
        <hr style={{ border: 'none', borderTop: '0.5px solid rgba(255,248,231,0.08)', margin: '14px 0' }} />
        <div className="mono" style={{ fontSize: 10, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>body · inter</div>
        <div style={{ font: '400 13px/1.55 var(--font-body)', color: 'var(--ink-strong)', marginBottom: 12, textWrap: 'pretty' }}>
          я тут. я бачу. я памʼятаю. скажи що тобі треба — або помовч, я зрозумію коли треба.
        </div>
        <hr style={{ border: 'none', borderTop: '0.5px solid rgba(255,248,231,0.08)', margin: '14px 0' }} />
        <div className="mono" style={{ fontSize: 10, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>mono · jetbrains</div>
        <pre className="mono" style={{ font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-strong)', whiteSpace: 'pre-wrap', margin: 0 }}>
{`state    FOCUS
dwell    17m
pulse    72 bpm
link     23ms · phantom-os
vault    sealed`}</pre>
        <hr style={{ border: 'none', borderTop: '0.5px solid rgba(255,248,231,0.08)', margin: '14px 0' }} />
        <div className="mono" style={{ fontSize: 10, color: 'var(--gold-soft)', letterSpacing: '0.2em', textTransform: 'uppercase', marginBottom: 8 }}>state label · 14sp · 0.4em</div>
        <div style={{ font: '700 13px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '0.4em' }}>
          ВАРТА
        </div>
      </div>
    </>
  );
}

// ─── Conversational settings sheet (replaces the toggle grid) ────────────────
function ConvSettingsSheet({ onClose, theme, onChangeTheme }) {
  const sections = [
    { kicker: '· ai brain', title: 'gemini підключено', body: '23 запити сьогодні · ключ закінчується на 3afb · модель gemma-3-12b завантажено локально · routing adaptive', cta: 'налаштувати' },
    { kicker: '· voice',    title: 'styletts2 · твій голос', body: 'клон з 30-секундного запису · якість 4.7 / 5 · спрацьовує тільки коли ти один', cta: 'переслухати зразок' },
    { kicker: '· connection', title: 'phantom-os в мережі', body: 'channel rtt 23 ms · last sync 14 сек тому · 4 канали активні · vault encrypted', cta: 'розєднати' },
    { kicker: '· theme',    title: theme === 'amber-night' ? 'amber-night' : 'sunrise-warm', body: 'auto-switch при заході сонця · поточно kyiv · 19:34', cta: theme === 'amber-night' ? 'на світлу' : 'на темну', onClick: () => onChangeTheme(theme === 'amber-night' ? 'sunrise-warm' : 'amber-night') },
    { kicker: '· permissions', title: 'screen-share не дозволено', body: 'familiar пояснить чому це треба · ти можеш сказати ні і він не перепитає тиждень', cta: 'переглянути' },
    { kicker: '· profile',  title: 'kirill · root trust', body: 'paired · biometric on · ghost-mode panic gesture: triple-tap power', cta: 'інший профіль' },
  ];
  return (
    <div onClick={onClose} style={{
      position: 'absolute', inset: 0, zIndex: 200,
      background: 'rgba(8,5,3,0.5)',
      display: 'flex', alignItems: 'flex-end',
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width: '100%', maxHeight: '85%', overflow: 'auto',
        background: 'rgba(20,14,10,0.95)', backdropFilter: 'blur(28px)',
        WebkitBackdropFilter: 'blur(28px)',
        borderTop: '0.5px solid rgba(255,248,231,0.1)',
        borderRadius: '20px 20px 0 0',
        padding: '14px 0',
      }}>
        <div style={{ width: 36, height: 4, background: 'rgba(255,248,231,0.18)', borderRadius: 2, margin: '0 auto 12px' }} />
        <div style={{ padding: '0 18px 8px' }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em', textTransform: 'uppercase' }}>· settings</div>
          <div style={{ font: '600 22px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.01em', marginTop: 6 }}>як ми сьогодні?</div>
        </div>
        {sections.map((s, i) => (
          <div key={i} style={{ padding: '12px 18px', borderTop: i ? '0.5px solid rgba(255,248,231,0.05)' : 'none' }}>
            <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em', textTransform: 'uppercase', marginBottom: 4 }}>{s.kicker}</div>
            <div style={{ font: '500 13.5px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{s.title}</div>
            <div style={{ font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)', marginTop: 4, textWrap: 'pretty' }}>{s.body}</div>
            <button onClick={s.onClick} style={{
              marginTop: 6, padding: '5px 0', fontSize: 11,
              background: 'transparent', border: 'none', color: 'var(--gold-soft)',
              cursor: 'pointer', font: '500 11px/1 var(--font-display)',
              borderBottom: '0.5px solid rgba(244,175,37,0.4)',
            }}>{s.cta} ↗</button>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, {
  PdfChatScene, CalendarWeekScene, EmailTriageScene, SmartHomeScene,
  SleepDashboardScene, ImageGenScene, MemoryPalaceScene, DreamJournalScene,
  NegotiationCourtScene, AmbientHealthScene, TypographyScene,
  ConvSettingsSheet,
});
