/* rich-calendar.jsx — calendar + time-based chat cards:
   MonthMini, DayTimeline (rich blocks with attendees),
   EventDetailCard (attendees, agenda, location, prep notes),
   TravelItinerary, FlightTracker, TaskList, ReminderCard,
   CountdownCard. */

// ─── 1. Mini Month view (12-cell grid with event dots & today highlight) ────
function MonthMini({ year = 2026, month = 4, events, title = 'травень · події' }) {
  // month: 0-indexed. firstDay weekday (Mon=0)
  const first = new Date(year, month, 1);
  const wd = (first.getDay() + 6) % 7; // make Monday = 0
  const dim = new Date(year, month + 1, 0).getDate();
  const today = 14; // simulated "today"
  const ev = events || {
    2: [{ c: '#F4AF25' }, { c: '#7E5DA4' }],
    5: [{ c: '#7CA982' }],
    8: [{ c: '#FF8B6E' }, { c: '#F4AF25' }],
    12: [{ c: '#F4AF25' }],
    14: [{ c: '#F4AF25' }, { c: '#7CA982' }, { c: '#7E5DA4' }],
    16: [{ c: '#F4AF25' }],
    19: [{ c: '#FF8B6E' }],
    22: [{ c: '#7CA982' }, { c: '#F4AF25' }],
    28: [{ c: '#7E5DA4' }],
  };
  const days = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'нд'];
  const monthNames = ['січ', 'лют', 'бер', 'кві', 'тра', 'чер', 'лип', 'сер', 'вер', 'жов', 'лис', 'гру'];

  const cells = [];
  for (let i = 0; i < wd; i++) cells.push(null);
  for (let d = 1; d <= dim; d++) cells.push(d);
  while (cells.length % 7) cells.push(null);

  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· місяць · {monthNames[month]} {year}</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <button style={{
            width: 22, height: 22, borderRadius: 6, appearance: 'none', cursor: 'pointer',
            background: 'rgba(255,230,180,0.04)', border: '0.5px solid var(--glass-edge)',
            color: 'var(--ink-secondary)', font: '300 10px/1 var(--font-mono)',
          }}>‹</button>
          <button style={{
            width: 22, height: 22, borderRadius: 6, appearance: 'none', cursor: 'pointer',
            background: 'rgba(255,230,180,0.04)', border: '0.5px solid var(--glass-edge)',
            color: 'var(--ink-secondary)', font: '300 10px/1 var(--font-mono)',
          }}>›</button>
        </div>
      </div>
      {/* weekday header */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, marginBottom: 4 }}>
        {days.map((d, i) => (
          <div key={d} className="mono" style={{
            fontSize: 8.5, color: i >= 5 ? 'var(--coral)' : 'var(--ink-tertiary)',
            letterSpacing: '0.1em', textTransform: 'uppercase',
            textAlign: 'center', padding: '4px 0',
            opacity: 0.7,
          }}>{d}</div>
        ))}
      </div>
      {/* cells */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2 }}>
        {cells.map((d, i) => {
          if (d === null) return <div key={i} />;
          const isToday = d === today;
          const isWeekend = (i % 7) >= 5;
          const dayEv = ev[d] || [];
          return (
            <button key={i} style={{
              aspectRatio: '1', appearance: 'none', cursor: 'pointer',
              borderRadius: 8,
              background: isToday ? 'var(--gold)' :
                          dayEv.length ? 'rgba(255,230,180,0.04)' : 'transparent',
              border: '0.5px solid transparent',
              padding: 2,
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'space-between',
              boxShadow: isToday ? '0 0 12px var(--gold-glow)' : 'none',
            }}>
              <span style={{
                font: `${isToday ? 600 : 400} 11px/1 var(--font-display)`,
                color: isToday ? '#1A140B' : isWeekend ? 'var(--ink-tertiary)' : 'var(--ink-strong)',
                marginTop: 4, fontVariantNumeric: 'tabular-nums',
              }}>{d}</span>
              {/* event dots */}
              <div style={{ display: 'flex', gap: 1.5, marginBottom: 3, minHeight: 3 }}>
                {dayEv.slice(0, 3).map((e, j) => (
                  <span key={j} style={{
                    width: 3, height: 3, borderRadius: '50%',
                    background: isToday ? '#1A140B' : e.c,
                    opacity: isToday ? 0.75 : 1,
                    boxShadow: isToday ? 'none' : `0 0 3px ${e.c}66`,
                  }} />
                ))}
              </div>
            </button>
          );
        })}
      </div>
      {/* legend */}
      <div style={{
        marginTop: 10, paddingTop: 10,
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', gap: 12, justifyContent: 'space-between',
      }}>
        {[
          { c: '#F4AF25', l: 'робота' },
          { c: '#7CA982', l: 'фокус' },
          { c: '#7E5DA4', l: 'особисте' },
          { c: '#FF8B6E', l: 'подорож' },
        ].map((g) => (
          <div key={g.l} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 5, height: 5, borderRadius: '50%', background: g.c, boxShadow: `0 0 3px ${g.c}88` }} />
            <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-secondary)', letterSpacing: '0.06em' }}>{g.l}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 2. Day Timeline (vertical, rich blocks with attendees & prep) ───────────
function DayTimeline({ date = 'чт · 14 травня', events }) {
  const ev = events || [
    { start: 8.5,  end: 9.5,   title: 'standup', kind: 'meeting', attendees: 6, color: '#F4AF25' },
    { start: 11.0, end: 12.5,  title: 'олег · координати', kind: 'meeting', attendees: 2, color: '#F4AF25', prep: true, location: 'caf. fika' },
    { start: 12.5, end: 13.0,  title: 'обід',  kind: 'personal', color: '#7E5DA4' },
    { start: 14.0, end: 17.0,  title: 'фокус · pr-823', kind: 'focus', color: '#7CA982' },
    { start: 17.5, end: 18.0,  title: 'дзвінок · мама', kind: 'personal', color: '#7E5DA4' },
    { start: 19.5, end: 21.0,  title: 'вечеря · ольга', kind: 'personal', color: '#7E5DA4', location: 'дома · готую' },
  ];
  const startH = 8, endH = 22, hours = endH - startH;
  const pxPerH = 38;
  const now = 19.567; // 19:34 → matches the rest of the prototype
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· день · {date}</div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>
          {ev.length} події · {ev.filter(e => e.kind === 'focus').reduce((s, e) => s + (e.end - e.start), 0).toFixed(1)} год фокусу
        </div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>· phantom прибрав 3 шуми</span>
      </div>

      <div style={{ position: 'relative', marginTop: 10, height: hours * pxPerH }}>
        {/* hour lines */}
        {Array.from({ length: hours + 1 }).map((_, i) => {
          const hour = startH + i;
          return (
            <div key={i} style={{
              position: 'absolute', left: 0, right: 0, top: i * pxPerH,
              borderTop: '0.5px solid rgba(255,230,180,0.04)',
              display: 'flex', alignItems: 'flex-start',
            }}>
              <span className="mono" style={{
                width: 26, fontSize: 8.5, color: 'rgba(167,150,125,0.5)', letterSpacing: '0.04em',
                paddingTop: 2, textAlign: 'right', flexShrink: 0,
                fontVariantNumeric: 'tabular-nums',
              }}>{hour}</span>
            </div>
          );
        })}
        {/* events */}
        {ev.map((e, i) => {
          const top = (e.start - startH) * pxPerH;
          const height = (e.end - e.start) * pxPerH - 3;
          const isPast = e.end < now;
          return (
            <div key={i} className="glass subtle" style={{
              position: 'absolute',
              left: 34, right: 0, top, height,
              padding: '6px 8px',
              borderRadius: 8,
              background: isPast ? `${e.color}10` : `${e.color}1f`,
              borderLeft: `2px solid ${e.color}`,
              opacity: isPast ? 0.55 : 1,
              overflow: 'hidden',
              transition: 'opacity 0.3s',
            }}>
              <div style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                marginBottom: height > 30 ? 3 : 0,
              }}>
                <span style={{
                  font: '500 11px/1.2 var(--font-body)', color: 'var(--ink-strong)',
                  textWrap: 'pretty', flex: 1, minWidth: 0,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>{e.title}</span>
                <span className="mono" style={{
                  fontSize: 8, color: 'var(--ink-tertiary)', flexShrink: 0, marginLeft: 6,
                  fontVariantNumeric: 'tabular-nums',
                }}>
                  {String(Math.floor(e.start)).padStart(2,'0')}:{String((e.start%1)*60|0).padStart(2,'0')}
                </span>
              </div>
              {height > 30 && (
                <div className="mono" style={{
                  fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
                  display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
                }}>
                  {e.location && <span>· {e.location}</span>}
                  {e.attendees && <span>· {e.attendees} 👤</span>}
                  {e.prep && <span style={{ color: 'var(--gold-soft)' }}>· prep готовий</span>}
                </div>
              )}
            </div>
          );
        })}
        {/* now line */}
        <div style={{
          position: 'absolute', left: 0, right: 0,
          top: (now - startH) * pxPerH,
          height: 0, borderTop: '1px solid var(--gold)',
          boxShadow: '0 0 6px var(--gold-glow)',
          display: 'flex', alignItems: 'center', gap: 4, zIndex: 5,
        }}>
          <span className="mono" style={{
            fontSize: 7.5, color: 'var(--gold)', letterSpacing: '0.1em',
            background: 'var(--bg-base)', padding: '0 4px', marginLeft: 26,
            transform: 'translateY(-50%)',
          }}>зараз</span>
          <span style={{
            width: 6, height: 6, borderRadius: '50%', background: 'var(--gold)',
            boxShadow: '0 0 6px var(--gold-glow)',
            transform: 'translateY(-50%)', marginLeft: 'auto', marginRight: -3,
          }} />
        </div>
      </div>
    </div>
  );
}

// ─── 3. Event Detail (full attendees, agenda, location, prep) ────────────────
function EventDetailCard({ title = 'олег · координати по phantom-os build', when = 'чт · 14 травня · 11:00 — 12:30',
                           where = 'caf. fika · вул. ярославів вал 8', attendees, agenda, prep, conflict }) {
  const att = attendees || [
    { name: 'kirill',  role: 'host',     status: 'going', initials: 'K' },
    { name: 'олег',    role: 'engineer', status: 'going', initials: 'О' },
  ];
  const ag = agenda || [
    { time: '11:00', t: 'розблокування PR-823 · 30 хв' },
    { time: '11:30', t: 'розподіл задач · 30 хв' },
    { time: '12:00', t: 'на наступний тиждень · 30 хв' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
      borderLeft: '2px solid var(--gold)',
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· подія · через 4 год</div>
      <div style={{
        font: '600 14.5px/1.3 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 4, textWrap: 'pretty',
      }}>{title}</div>
      {conflict && (
        <div style={{
          marginTop: 6, padding: '5px 8px', borderRadius: 6,
          background: 'rgba(255,139,110,0.08)',
          border: '0.5px solid rgba(255,139,110,0.2)',
          font: '400 10.5px/1.4 var(--font-body)', color: 'var(--coral)',
          textWrap: 'pretty',
        }}>
          <span className="mono" style={{ fontSize: 8, letterSpacing: '0.12em', marginRight: 6 }}>· конфлікт</span>
          збігається з «літак до lhasa» · переглянути?
        </div>
      )}
      {/* time + location grid */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 12px', marginTop: 10,
        font: '400 11px/1.4 var(--font-body)',
      }}>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', alignSelf: 'baseline', paddingTop: 1 }}>· коли</span>
        <span style={{ color: 'var(--ink-strong)' }}>{when}</span>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', alignSelf: 'baseline', paddingTop: 1 }}>· де</span>
        <span style={{ color: 'var(--ink-strong)' }}>{where}</span>
      </div>
      {/* attendees */}
      <div style={{ marginTop: 12, paddingTop: 10, borderTop: '0.5px solid var(--glass-edge)' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· учасники · {att.length}</div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {att.map((a, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '4px 8px 4px 4px', borderRadius: 999,
              background: 'rgba(255,230,180,0.04)',
              border: '0.5px solid var(--glass-edge)',
            }}>
              <div style={{
                width: 20, height: 20, borderRadius: '50%',
                background: `linear-gradient(135deg, ${i === 0 ? '#F4AF25' : '#7E5DA4'}, ${i === 0 ? '#7A4818' : '#3B1F50'})`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '500 10px/1 var(--font-display)', color: '#fff',
              }}>{a.initials}</div>
              <span style={{ font: '500 10.5px/1 var(--font-body)', color: 'var(--ink-strong)' }}>{a.name}</span>
              <span className="mono" style={{ fontSize: 8, color: 'var(--sage)', letterSpacing: '0.04em' }}>· {a.status}</span>
            </div>
          ))}
        </div>
      </div>
      {/* agenda */}
      <div style={{ marginTop: 12, paddingTop: 10, borderTop: '0.5px solid var(--glass-edge)' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.14em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· порядок</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {ag.map((row, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '42px 1fr', gap: 8, alignItems: 'baseline' }}>
              <span className="mono" style={{
                fontSize: 9.5, color: 'var(--gold-soft)', letterSpacing: '0.06em',
                fontVariantNumeric: 'tabular-nums',
              }}>{row.time}</span>
              <span style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{row.t}</span>
            </div>
          ))}
        </div>
      </div>
      {/* prep — phantom's brief */}
      {prep !== false && (
        <div style={{
          marginTop: 12, padding: '8px 10px',
          background: 'rgba(244,175,37,0.06)',
          border: '0.5px solid rgba(244,175,37,0.2)',
          borderRadius: 8,
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.14em',
            textTransform: 'uppercase', marginBottom: 5,
          }}>· phantom · prep на 30 сек</div>
          <ul style={{
            margin: 0, paddingLeft: 14,
            font: '400 11px/1.5 var(--font-body)', color: 'var(--ink)',
          }}>
            <li style={{ textWrap: 'pretty', marginBottom: 2 }}>останньо ви бачились 28 квітня · обговорювали міграцію auth</li>
            <li style={{ textWrap: 'pretty', marginBottom: 2 }}>олег пропустив 2 standup поспіль · не питай чому, він сам скаже</li>
            <li style={{ textWrap: 'pretty' }}>принеси чек за каву минулого тижня — він винен $4.20</li>
          </ul>
        </div>
      )}
    </div>
  );
}

// ─── 4. Travel Itinerary (multi-leg horizontal flow) ─────────────────────────
function TravelItinerary({ title = 'kyiv → lhasa · 16 травня', legs }) {
  const ls = legs || [
    { kind: 'taxi',   from: 'дім', to: 'kbp · т1', dep: '04:20', arr: '05:10', code: 'uber · plus' },
    { kind: 'flight', from: 'kbp', to: 'ist',       dep: '06:55', arr: '09:40', code: 'TK 5732',  duration: '2 г 45 хв' },
    { kind: 'wait',   from: 'ist · transit',                        dep: '09:40', arr: '12:15', duration: '2 г 35 хв' },
    { kind: 'flight', from: 'ist', to: 'pek',       dep: '12:15', arr: '02:30', code: 'TK 20',    duration: '9 г 15 хв · +1' },
    { kind: 'flight', from: 'pek', to: 'lxa',       dep: '07:40', arr: '11:50', code: 'CA 4112',  duration: '4 г 10 хв' },
    { kind: 'taxi',   from: 'lxa', to: 'guesthouse', dep: '12:30', arr: '13:30', code: 'hotel pickup' },
  ];
  const Icon = ({ kind, size = 14 }) => ({
    taxi:   <svg width={size} height={size} viewBox="0 0 16 16"><rect x="2" y="6" width="12" height="5" rx="1" fill="currentColor"/><circle cx="5" cy="12" r="1.5" fill="currentColor"/><circle cx="11" cy="12" r="1.5" fill="currentColor"/></svg>,
    flight: <svg width={size} height={size} viewBox="0 0 16 16"><path d="M14 6L9 7L7 2H5L6 7L2 8.5L2 10L6 9.5L7 13H8.5L10 9L14 8Z" fill="currentColor" transform="rotate(28 8 8)"/></svg>,
    wait:   <svg width={size} height={size} viewBox="0 0 16 16"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.2"/><path d="M8 4v4l3 1" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" fill="none"/></svg>,
  })[kind];
  const color = (k) => ({ taxi: '#A7967D', flight: '#F4AF25', wait: '#7E5DA4' })[k] || '#F4AF25';

  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
      }}>· маршрут</div>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12,
      }}>
        <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        <span className="mono" style={{ fontSize: 9.5, color: 'var(--ink-tertiary)' }}>· 31 г · 6 ділянок</span>
      </div>
      <div style={{ position: 'relative', paddingLeft: 22 }}>
        {/* spine */}
        <div style={{
          position: 'absolute', left: 9, top: 6, bottom: 6, width: 0.5,
          background: 'linear-gradient(to bottom, var(--gold-faint), var(--ink-faint))',
        }} />
        {ls.map((l, i) => {
          const c = color(l.kind);
          return (
            <div key={i} style={{
              position: 'relative', display: 'flex', flexDirection: 'column', gap: 2,
              padding: '6px 0 10px', borderBottom: i < ls.length-1 ? '0.5px solid rgba(255,230,180,0.04)' : 'none',
            }}>
              {/* node */}
              <div style={{
                position: 'absolute', left: -18, top: 8,
                width: 18, height: 18, borderRadius: '50%',
                background: `${c}1f`, border: `1px solid ${c}`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: c, boxShadow: `0 0 6px ${c}66`,
              }}>
                <Icon kind={l.kind} size={9} />
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 6 }}>
                <span style={{ font: '500 11.5px/1.2 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
                  {l.from}{l.to ? ` → ${l.to}` : ''}
                </span>
                <span className="mono" style={{
                  fontSize: 9, color: 'var(--ink-tertiary)', flexShrink: 0,
                  fontVariantNumeric: 'tabular-nums',
                }}>{l.dep} → {l.arr}</span>
              </div>
              <div className="mono" style={{ fontSize: 8.5, color: c, letterSpacing: '0.06em' }}>
                · {l.code || l.kind}{l.duration ? ` · ${l.duration}` : ''}
              </div>
            </div>
          );
        })}
      </div>
      {/* phantom note */}
      <div style={{
        marginTop: 10, padding: '8px 10px', borderRadius: 8,
        background: 'rgba(244,175,37,0.05)',
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.14em', textTransform: 'uppercase', display: 'block', marginBottom: 3 }}>· phantom · note</span>
        ist transit 2:35 — встигнеш на каву в каф «aydin». pek-стоянка ночується в готелі через термінал 3 (вже забронював).
      </div>
    </div>
  );
}

// ─── 5. Flight Tracker (live route + status) ─────────────────────────────────
function FlightTracker({ flight = 'TK 5732', from = 'KBP', to = 'IST', progress = 0.42,
                          dep = '06:55', arr = '09:40', altitude = '10 668 м', speed = '837 км/г', delay = 0 }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{
        padding: '12px 14px',
        background: `linear-gradient(135deg,
          rgba(244,175,37,0.08) 0%,
          transparent 60%)`,
      }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <span className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.22em',
            textTransform: 'uppercase',
          }}>· рейс · {flight}</span>
          <span style={{
            padding: '3px 8px', borderRadius: 999,
            background: delay > 0 ? 'rgba(255,139,110,0.12)' : 'rgba(124,169,130,0.12)',
            color: delay > 0 ? 'var(--coral)' : 'var(--sage)',
            font: '500 9.5px/1 var(--font-display)', letterSpacing: '0.04em',
          }}>{delay > 0 ? `затримка ${delay} хв` : '· за розкладом'}</span>
        </div>
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr auto 1fr',
          gap: 12, marginTop: 10, alignItems: 'baseline',
        }}>
          <div>
            <span style={{ font: '500 28px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>{from}</span>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 4 }}>kyiv · {dep}</div>
          </div>
          <div style={{ paddingTop: 8 }}>
            <svg width="32" height="14" viewBox="0 0 32 14">
              <line x1="2" y1="7" x2="30" y2="7" stroke="var(--gold)" strokeWidth="0.6" strokeDasharray="2 2" />
              <path d="M16 4 L24 7 L16 10 L18 7 Z" fill="var(--gold)" transform="translate(0,0)" />
            </svg>
          </div>
          <div style={{ textAlign: 'right' }}>
            <span style={{ font: '500 28px/1 var(--font-display)', color: 'var(--ink-strong)', letterSpacing: '-0.015em' }}>{to}</span>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 4 }}>istanbul · {arr}</div>
          </div>
        </div>
      </div>
      {/* live route */}
      <div style={{
        height: 140, position: 'relative', overflow: 'hidden',
        background: `radial-gradient(ellipse at 50% 70%, #1a1108, #0a0805 70%)`,
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        {/* mock map — curved earth flight route */}
        <svg viewBox="0 0 380 140" preserveAspectRatio="none" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <defs>
            <linearGradient id="ftroute" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor="#F4AF25" stopOpacity="0.95" />
              <stop offset={`${progress*100}%`} stopColor="#FFD370" stopOpacity="0.95" />
              <stop offset={`${progress*100+0.01}%`} stopColor="#F4AF25" stopOpacity="0.25" />
              <stop offset="100%" stopColor="#F4AF25" stopOpacity="0.18" />
            </linearGradient>
          </defs>
          {/* country lines / coastline scribble */}
          <path d="M 0 90 Q 80 80 130 95 T 250 88 T 380 100" fill="none" stroke="rgba(167,150,125,0.15)" strokeWidth="0.7" />
          <path d="M 30 120 Q 130 115 230 125 T 380 130" fill="none" stroke="rgba(167,150,125,0.1)" strokeWidth="0.7" />
          {/* route curve */}
          <path d="M 30 95 Q 190 20 350 50" fill="none" stroke="url(#ftroute)" strokeWidth="1.6"
                strokeLinecap="round" filter="drop-shadow(0 0 4px var(--gold-glow))" />
          {/* origin */}
          <circle cx="30" cy="95" r="3" fill="var(--gold)" />
          <text x="30" y="108" fill="var(--gold-soft)" fontSize="7" textAnchor="middle" fontFamily="var(--font-mono)">{from}</text>
          {/* destination */}
          <circle cx="350" cy="50" r="3" fill="var(--gold)" opacity="0.5" />
          <text x="350" y="44" fill="var(--ink-secondary)" fontSize="7" textAnchor="middle" fontFamily="var(--font-mono)">{to}</text>
          {/* plane pos at progress */}
          {(() => {
            // quadratic bezier P(t) = (1-t)^2 P0 + 2(1-t)t P1 + t^2 P2
            const t = progress, P0 = [30, 95], P1 = [190, 20], P2 = [350, 50];
            const x = (1-t)*(1-t)*P0[0] + 2*(1-t)*t*P1[0] + t*t*P2[0];
            const y = (1-t)*(1-t)*P0[1] + 2*(1-t)*t*P1[1] + t*t*P2[1];
            // angle along tangent
            const dx = 2*(1-t)*(P1[0]-P0[0]) + 2*t*(P2[0]-P1[0]);
            const dy = 2*(1-t)*(P1[1]-P0[1]) + 2*t*(P2[1]-P1[1]);
            const ang = Math.atan2(dy, dx) * 180 / Math.PI;
            return (
              <g transform={`translate(${x} ${y}) rotate(${ang})`}>
                <circle r="8" fill="var(--gold)" opacity="0.2" />
                <circle r="14" fill="none" stroke="var(--gold)" strokeWidth="0.6" opacity="0.6">
                  <animate attributeName="r" values="10;18;10" dur="2.4s" repeatCount="indefinite" />
                  <animate attributeName="opacity" values="0.6;0;0.6" dur="2.4s" repeatCount="indefinite" />
                </circle>
                <path d="M -6 0 L 6 0 L 4 -3 M 6 0 L 4 3" stroke="#1A140B" strokeWidth="1.2" fill="none" />
                <path d="M -6 0 L 6 0" stroke="#FFD370" strokeWidth="2" />
              </g>
            );
          })()}
        </svg>
      </div>
      <div style={{
        padding: '10px 14px', borderTop: '0.5px solid var(--glass-edge)',
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8,
      }}>
        {[
          ['пройдено', `${(progress*100|0)}%`],
          ['висота', altitude],
          ['швидк.', speed],
          ['ETA', '+1 г 12 хв'],
        ].map(([k, v]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.12em', textTransform: 'uppercase' }}>{k}</span>
            <span style={{ font: '500 11px/1 var(--font-display)', color: 'var(--ink-strong)', fontVariantNumeric: 'tabular-nums' }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── 6. Task List with priority + checkboxes ─────────────────────────────────
function TaskListCard({ title = 'до п\'ятниці', tasks }) {
  const init = tasks || [
    { t: 'відповісти олександру про protocol', p: 'now', done: false, est: '8 хв', from: 'inbox' },
    { t: 'підписати travel docs · lhasa',     p: 'now', done: true,  est: '4 хв', from: 'phantom' },
    { t: 'код-рев pr-823 від ольги',         p: 'today', done: false, est: '30 хв', from: 'github' },
    { t: 'забрати посилку якщо погода',       p: 'today', done: false, est: '20 хв', from: 'reminder' },
    { t: 'почитати «sapiens» ст. 140-160',    p: 'week', done: false, est: '45 хв', from: 'reading' },
    { t: 'дзвонити мамі',                      p: 'week', done: false, est: '15 хв', from: 'phantom' },
  ];
  const [ts, setTs] = React.useState(init);
  const toggle = (i) => setTs((arr) => arr.map((x, j) => j === i ? { ...x, done: !x.done } : x));
  const groups = [
    { id: 'now',   l: 'зараз', c: 'var(--gold)' },
    { id: 'today', l: 'сьогодні', c: 'var(--gold-soft)' },
    { id: 'week',  l: 'до п\'ятниці', c: 'var(--ink-secondary)' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· задачі · phantom · 6</div>
          <div style={{ font: '500 13px/1.3 var(--font-display)', color: 'var(--ink-strong)' }}>{title}</div>
        </div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>{ts.filter(t => t.done).length} / {ts.length}</span>
      </div>
      {groups.map((g) => {
        const items = ts.filter((t) => t.p === g.id);
        if (!items.length) return null;
        return (
          <div key={g.id} style={{ marginTop: 8 }}>
            <div className="mono" style={{
              fontSize: 8, color: g.c, letterSpacing: '0.18em',
              textTransform: 'uppercase', marginBottom: 4,
            }}>· {g.l}</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {items.map((t) => {
                const idx = ts.indexOf(t);
                return (
                  <button key={idx} onClick={() => toggle(idx)} style={{
                    appearance: 'none', cursor: 'pointer', textAlign: 'left',
                    background: t.done ? 'rgba(124,169,130,0.05)' : 'rgba(255,230,180,0.02)',
                    border: '0.5px solid var(--glass-edge)',
                    borderRadius: 8, padding: '8px 10px',
                    display: 'flex', alignItems: 'center', gap: 10,
                  }}>
                    <span style={{
                      width: 16, height: 16, borderRadius: 4, flexShrink: 0,
                      background: t.done ? 'var(--sage)' : 'transparent',
                      border: `1px solid ${t.done ? 'var(--sage)' : g.c}`,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}>
                      {t.done && <svg width="9" height="9" viewBox="0 0 9 9"><path d="M2 5L4 7L8 2" stroke="#0E0A05" strokeWidth="1.4" fill="none" strokeLinecap="round" /></svg>}
                    </span>
                    <span style={{
                      flex: 1, font: '400 12px/1.3 var(--font-body)',
                      color: t.done ? 'var(--ink-tertiary)' : 'var(--ink-strong)',
                      textWrap: 'pretty',
                      textDecoration: t.done ? 'line-through' : 'none',
                      textDecorationColor: 'var(--sage)',
                    }}>{t.t}</span>
                    <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', flexShrink: 0, letterSpacing: '0.04em' }}>
                      {t.est}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ─── 7. Countdown card (event in N days) ─────────────────────────────────────
function CountdownCard({ title = 'lhasa · 16 травня', kicker = '· через 2 дні', sublines }) {
  const days = 2, hours = 8, minutes = 26;
  const sl = sublines || [
    'квитки збережені у vault',
    'погода: -2° · сніг · одягни шерсть',
    'phantom-os буде онлайн через esim',
  ];
  return (
    <div className="glass accent fade-in" style={{
      padding: '14px 16px', margin: '6px 0', borderRadius: 16,
      background: `
        radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.10), transparent 50%),
        var(--glass-tint)`,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 6,
      }}>{kicker}</div>
      <div style={{
        font: '500 14px/1.3 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 10,
      }}>{title}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginBottom: 14 }}>
        {[
          { v: days, l: 'дні' },
          { v: hours, l: 'год' },
          { v: minutes, l: 'хв' },
        ].map((x, i) => (
          <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'baseline', gap: 2 }}>
            <span style={{
              font: '300 34px/0.9 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '-0.025em', fontVariantNumeric: 'tabular-nums',
            }}>{String(x.v).padStart(2, '0')}</span>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>{x.l}</span>
          </div>
        ))}
        <div style={{ flex: 1 }} />
        {/* tiny circular progress */}
        <svg width="48" height="48" viewBox="0 0 48 48" style={{ flexShrink: 0 }}>
          <circle cx="24" cy="24" r="20" fill="none" stroke="rgba(255,230,180,0.08)" strokeWidth="2" />
          <circle cx="24" cy="24" r="20" fill="none" stroke="var(--gold)" strokeWidth="2"
                  strokeLinecap="round" strokeDasharray={`${0.65 * 126} 126`}
                  transform="rotate(-90 24 24)"
                  style={{ filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
        </svg>
      </div>
      <ul style={{
        listStyle: 'none', padding: 0, margin: 0,
        display: 'flex', flexDirection: 'column', gap: 4,
      }}>
        {sl.map((s, i) => (
          <li key={i} style={{
            display: 'flex', alignItems: 'baseline', gap: 8,
            font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>
            <span style={{ color: 'var(--gold-soft)', flexShrink: 0 }}>·</span>{s}
          </li>
        ))}
      </ul>
    </div>
  );
}

Object.assign(window, {
  MonthMini, DayTimeline, EventDetailCard, TravelItinerary,
  FlightTracker, TaskListCard, CountdownCard,
});
