/* people-panel.jsx — the people hub:
   slide-up sheet showing people, groups, recents, with search and filters.
   PersonRow, GroupRow, and the seed data store. */

// ─── seed data ──────────────────────────────────────────────────────────────
window.PHANTOM_PEOPLE_SEED = [
  { id: 'olya',   name: 'оля',     role: 'designer · phantom-os',  hue: '#FF8B6E', initials: 'О',
    paired: true, temp: 0.78, lastSeen: '12 хв', unread: 0, channels: 4,
    note: 'остання розмова — про переїзд. мʼягко.', favourite: true,
    permissions: ['draft', 'mood', 'voice'] },
  { id: 'oleh',   name: 'олег',    role: 'engineer · sentinel',     hue: '#9DCC9F', initials: 'Ол',
    paired: true, temp: 0.42, lastSeen: '2 г',  unread: 2,
    note: 'тиша третій день. ти писав о 23:51 — я зупинив.', favourite: false,
    permissions: ['draft'] },
  { id: 'bohdan', name: 'богдан',  role: 'manager',                  hue: '#7CC0E8', initials: 'Б',
    paired: true, temp: 0.55, lastSeen: 'вчора', unread: 0,
    note: 'формальний. твій тон з ним різкішає коли втомлений.', favourite: false,
    permissions: ['draft', 'meet'] },
  { id: 'mama',   name: 'мама',    role: 'сімʼя',                     hue: '#FFD370', initials: 'М',
    paired: true, temp: 0.85, lastSeen: '3 г',  unread: 1,
    note: 'дзвонила тричі за тиждень. ти не передзвонив раз.', favourite: true,
    permissions: ['draft', 'mood', 'voice', 'photos'] },
  { id: 'anya',   name: 'аня',     role: 'друг · видавець',           hue: '#B49AD4', initials: 'А',
    paired: false, temp: 0.62, lastSeen: '5 днів', unread: 0,
    note: 'без phantom · на її стороні мовчанка.', favourite: false,
    permissions: [] },
  { id: 'nick',   name: 'нік',     role: 'co-founder, давно',         hue: '#A7967D', initials: 'Н',
    paired: false, temp: 0.18, lastSeen: '3 міс', unread: 0,
    note: 'архів. ти просив його не виводити в живий список.', favourite: false,
    permissions: [], archived: true },
];

window.PHANTOM_GROUPS_SEED = [
  { id: 'family', name: 'сімʼя',         hue: '#FFD370',
    members: ['mama', 'olya'], phantom: 'ambient',
    vault: true, activity: 0.7,
    note: 'спільна тривога батька · мовчати' },
  { id: 'work',   name: 'команда phantom', hue: '#F4AF25',
    members: ['oleh', 'bohdan', 'olya'], phantom: 'active',
    vault: false, activity: 0.55,
    note: 'phantom бере мітки · робить summary щовечора' },
  { id: 'circle', name: 'тісне коло',    hue: '#7E5DA4',
    members: ['anya', 'olya', 'oleh'],   phantom: 'silent',
    vault: true, activity: 0.32,
    note: 'phantom не пише за тебе тут · тільки слухає' },
];

// ─── temperature ring colour ─────────────────────────────────────────────────
function tempColor(t) {
  if (t > 0.7)  return '#F4AF25';
  if (t > 0.45) return '#A7967D';
  if (t > 0.25) return '#7CC0E8';
  return '#FF8B6E';
}
function tempLabel(t) {
  if (t > 0.7)  return 'тепло';
  if (t > 0.45) return 'рівно';
  if (t > 0.25) return 'прохолодно';
  return 'холодно';
}

// ─── PersonAvatar — reusable, with halo + presence ring ─────────────────────
function PersonAvatar({ p, size = 40, withRing = true, withPulse = false }) {
  const ringSize = size + 8;
  return (
    <div style={{
      width: ringSize, height: ringSize, position: 'relative', flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      {withRing && (
        <svg width={ringSize} height={ringSize} viewBox={`0 0 ${ringSize} ${ringSize}`}
             style={{ position: 'absolute', inset: 0 }}>
          <circle cx={ringSize/2} cy={ringSize/2} r={(ringSize-2)/2}
                  fill="none" stroke="rgba(255,230,180,0.06)" strokeWidth="1" />
          <circle cx={ringSize/2} cy={ringSize/2} r={(ringSize-2)/2}
                  fill="none" stroke={tempColor(p.temp)} strokeWidth="1.4"
                  strokeLinecap="round"
                  strokeDasharray={`${p.temp * Math.PI * (ringSize-2)} ${Math.PI * (ringSize-2)}`}
                  transform={`rotate(-90 ${ringSize/2} ${ringSize/2})`}
                  style={{ filter: `drop-shadow(0 0 3px ${tempColor(p.temp)}66)` }} />
        </svg>
      )}
      {/* halo glow */}
      <div style={{
        position: 'absolute', inset: 6, borderRadius: '50%',
        background: `radial-gradient(circle, ${p.hue}40, transparent 70%)`,
        filter: 'blur(4px)',
      }} />
      <div style={{
        width: size, height: size, borderRadius: '50%',
        background: `radial-gradient(circle at 35% 30%, ${p.hue}, ${p.hue}66 55%, ${p.hue}22)`,
        border: '0.5px solid rgba(255,230,180,0.18)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        font: `500 ${size * 0.4}px/1 var(--font-display)`, color: '#fff',
        letterSpacing: '0.02em', position: 'relative',
        boxShadow: `0 0 ${size * 0.3}px ${p.hue}33`,
      }}>
        {p.initials}
        {p.paired && (
          <span style={{
            position: 'absolute', right: -1, bottom: -1,
            width: size * 0.32, height: size * 0.32, borderRadius: '50%',
            background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25)',
            border: '2px solid var(--bg-base)',
            boxShadow: '0 0 6px var(--gold-glow)',
            animation: withPulse ? 'phantom-pulse-glow 2s ease-in-out infinite' : 'none',
          }} />
        )}
      </div>
    </div>
  );
}

// ─── PersonRow — list item ──────────────────────────────────────────────────
function PersonRow({ p, onOpen, onLongPress, dense = false }) {
  const pressTimer = React.useRef(null);
  const startPress = () => {
    pressTimer.current = setTimeout(() => onLongPress?.(p), 500);
  };
  const endPress = () => { if (pressTimer.current) clearTimeout(pressTimer.current); };
  return (
    <button
      onPointerDown={startPress} onPointerUp={endPress} onPointerLeave={endPress}
      onClick={() => onOpen?.(p)}
      style={{
        appearance: 'none', cursor: 'pointer', width: '100%',
        textAlign: 'left', padding: dense ? '6px 10px' : '8px 12px',
        background: 'transparent', border: '0.5px solid transparent',
        borderRadius: 14, display: 'flex', alignItems: 'center', gap: 12,
        transition: 'all 0.15s',
        position: 'relative',
      }}>
      {/* temperature stripe along left edge */}
      <div style={{
        position: 'absolute', left: 0, top: 8, bottom: 8, width: 2,
        borderRadius: 1,
        background: `linear-gradient(to bottom, ${tempColor(p.temp)}, transparent)`,
        opacity: 0.6,
      }} />
      <PersonAvatar p={p} size={dense ? 36 : 42} withPulse={p.paired && p.unread > 0} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{
            font: '500 13.5px/1.2 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.005em',
          }}>{p.name}</span>
          {p.favourite && <span style={{ color: 'var(--gold)', fontSize: 9 }}>✦</span>}
          <span style={{ flex: 1 }} />
          <span className="mono" style={{
            fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
          }}>{p.lastSeen}</span>
        </div>
        <div style={{
          marginTop: 3, display: 'flex', alignItems: 'baseline', gap: 8,
        }}>
          <span className="mono" style={{
            fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
          }}>{p.role}</span>
          <span style={{ flex: 1 }} />
          {p.unread > 0 && (
            <span style={{
              padding: '1px 6px', borderRadius: 999,
              background: 'var(--gold)', color: '#1A140B',
              font: '600 9px/1 var(--font-display)',
              boxShadow: '0 0 6px var(--gold-glow)',
            }}>{p.unread}</span>
          )}
        </div>
        {p.note && !dense && (
          <div style={{
            marginTop: 4, font: '300 10px/1.4 Georgia, serif',
            color: 'var(--ink-tertiary)', fontStyle: 'italic',
            textWrap: 'pretty', overflow: 'hidden',
            textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            opacity: 0.8,
          }}>«{p.note}»</div>
        )}
      </div>
    </button>
  );
}

// ─── GroupRow — group constellation preview ─────────────────────────────────
function GroupRow({ g, people, onOpen, onLongPress }) {
  const members = g.members.map((mid) => people.find((p) => p.id === mid)).filter(Boolean);
  const pressTimer = React.useRef(null);
  return (
    <button
      onPointerDown={() => { pressTimer.current = setTimeout(() => onLongPress?.(g), 500); }}
      onPointerUp={() => clearTimeout(pressTimer.current)}
      onPointerLeave={() => clearTimeout(pressTimer.current)}
      onClick={() => onOpen?.(g)}
      style={{
        appearance: 'none', cursor: 'pointer', width: '100%',
        textAlign: 'left', padding: '10px 12px',
        background: 'transparent', border: '0.5px solid transparent',
        borderRadius: 14, display: 'flex', alignItems: 'center', gap: 12,
        position: 'relative',
      }}>
      {/* group constellation */}
      <div style={{
        width: 48, height: 48, flexShrink: 0, position: 'relative',
      }}>
        {/* phantom center */}
        <div style={{
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          width: 14, height: 14, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, #FFD370, #F4AF25)`,
          boxShadow: '0 0 8px var(--gold-glow)',
          zIndex: 2,
        }} />
        {/* orbits */}
        {members.slice(0, 4).map((m, i) => {
          const ang = (i / Math.min(members.length, 4)) * Math.PI * 2 - Math.PI / 2;
          const x = 24 + Math.cos(ang) * 18;
          const y = 24 + Math.sin(ang) * 18;
          return (
            <React.Fragment key={m.id}>
              <svg width="48" height="48" viewBox="0 0 48 48"
                   style={{ position: 'absolute', inset: 0 }}>
                <line x1="24" y1="24" x2={x} y2={y}
                      stroke={m.hue} strokeOpacity="0.4" strokeWidth="0.6" />
              </svg>
              <div style={{
                position: 'absolute', left: x - 6, top: y - 6,
                width: 12, height: 12, borderRadius: '50%',
                background: `radial-gradient(circle at 35% 30%, ${m.hue}, ${m.hue}55)`,
                border: '0.5px solid rgba(255,230,180,0.12)',
                boxShadow: `0 0 4px ${m.hue}88`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '500 6.5px/1 var(--font-display)', color: '#fff',
                animation: `phantom-pulse-glow ${3 + i * 0.4}s ease-in-out infinite`,
              }}>{m.initials[0]}</div>
            </React.Fragment>
          );
        })}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{
            font: '500 13px/1.2 var(--font-display)', color: 'var(--ink-strong)',
          }}>{g.name}</span>
          <span style={{ flex: 1 }} />
          <span className="mono" style={{
            fontSize: 8.5, color: g.hue, letterSpacing: '0.06em',
          }}>· {g.phantom}</span>
        </div>
        <div style={{
          marginTop: 4, font: '400 10px/1.3 var(--font-body)',
          color: 'var(--ink-tertiary)',
        }}>
          {members.map((m) => m.name).join(' · ')}
          {g.vault && <span style={{ color: 'var(--gold-soft)', marginLeft: 5 }}>· vault</span>}
        </div>
        {g.note && (
          <div style={{
            marginTop: 3, font: '300 10px/1.4 Georgia, serif',
            color: 'var(--ink-tertiary)', fontStyle: 'italic',
            opacity: 0.85,
          }}>«{g.note}»</div>
        )}
      </div>
    </button>
  );
}

// ─── PeoplePanel — main slide-up sheet ──────────────────────────────────────
function PeoplePanel({
  open, onClose, people, groups,
  onOpenPerson, onOpenGroup, onEditPerson, onEditGroup,
  onAddPerson, onAddGroup,
}) {
  const [tab, setTab] = React.useState('people');
  const [filter, setFilter] = React.useState('all');
  const [query, setQuery] = React.useState('');

  const filtered = React.useMemo(() => {
    let ps = people.filter((p) => !p.archived);
    if (filter === 'paired')   ps = ps.filter((p) => p.paired);
    if (filter === 'fav')      ps = ps.filter((p) => p.favourite);
    if (filter === 'recent')   ps = ps.filter((p) => /хв|год|дн/i.test(p.lastSeen));
    if (filter === 'unread')   ps = ps.filter((p) => p.unread > 0);
    if (query)                 ps = ps.filter((p) => p.name.toLowerCase().includes(query.toLowerCase()));
    return ps;
  }, [people, filter, query]);

  const archived = people.filter((p) => p.archived);

  return (
    <>
      {/* dim background */}
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 90,
        background: open ? 'rgba(0,0,0,0.55)' : 'transparent',
        backdropFilter: open ? 'blur(4px)' : 'none',
        opacity: open ? 1 : 0, pointerEvents: open ? 'auto' : 'none',
        transition: 'all 0.35s cubic-bezier(.25,.8,.35,1)',
      }} />
      {/* sheet */}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 95,
        height: '88%', transform: open ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 0.4s cubic-bezier(.3,.7,.35,1)',
        display: 'flex', flexDirection: 'column',
      }}>
        <div className="glass hero" style={{
          flex: 1, margin: '0 4px', display: 'flex', flexDirection: 'column',
          borderRadius: '28px 28px 0 0',
          borderBottom: 'none',
          background: `
            radial-gradient(ellipse at 50% 0%, rgba(244,175,37,0.12), transparent 50%),
            radial-gradient(ellipse at 100% 100%, rgba(126,93,164,0.08), transparent 60%),
            rgba(15,11,7,0.92)`,
          backdropFilter: 'blur(28px) saturate(140%)',
        }}>
          {/* drag handle */}
          <div style={{
            width: 40, height: 4, background: 'var(--ink-faint)',
            borderRadius: 2, margin: '10px auto 6px',
          }} />
          {/* header */}
          <div style={{
            padding: '8px 18px 6px', display: 'flex',
            alignItems: 'flex-start', justifyContent: 'space-between',
          }}>
            <div>
              <div className="mono" style={{
                fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.28em',
                textTransform: 'uppercase', marginBottom: 4,
              }}>· твій круг</div>
              <div style={{
                font: '300 26px/1 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.015em',
              }}>люди <span style={{ color: 'var(--ink-tertiary)' }}>·</span> групи</div>
            </div>
            <button onClick={tab === 'people' ? onAddPerson : onAddGroup} style={{
              appearance: 'none', cursor: 'pointer', border: 'none',
              width: 38, height: 38, borderRadius: '50%',
              background: 'rgba(244,175,37,0.94)',
              color: '#1A140B', boxShadow: '0 0 14px var(--gold-glow)',
              font: '300 22px/1 var(--font-display)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>+</button>
          </div>
          {/* search + tabs */}
          <div style={{ padding: '6px 14px 0' }}>
            <div className="glass subtle" style={{
              padding: '8px 12px', borderRadius: 999,
              display: 'flex', alignItems: 'center', gap: 8,
              background: 'rgba(20,14,10,0.5)',
            }}>
              <svg width="13" height="13" viewBox="0 0 13 13" style={{ flexShrink: 0, opacity: 0.5 }}>
                <circle cx="5.5" cy="5.5" r="3.5" fill="none" stroke="var(--ink-secondary)" strokeWidth="1" />
                <path d="M8 8L11 11" stroke="var(--ink-secondary)" strokeWidth="1" />
              </svg>
              <input
                value={query} onChange={(e) => setQuery(e.target.value)}
                placeholder="знайти · по імені, обличчю, рядку"
                style={{
                  flex: 1, appearance: 'none', border: 'none', outline: 'none',
                  background: 'transparent', font: '400 12.5px/1 var(--font-body)',
                  color: 'var(--ink-strong)', letterSpacing: '0.01em',
                }} />
            </div>
            {/* tabs */}
            <div style={{
              display: 'flex', gap: 0, marginTop: 12,
              borderBottom: '0.5px solid var(--glass-edge)',
            }}>
              {[
                { id: 'people', l: 'люди',  c: people.filter((p) => !p.archived).length },
                { id: 'groups', l: 'групи', c: groups.length },
                { id: 'memory', l: 'у памʼяті', c: archived.length },
              ].map((t) => (
                <button key={t.id} onClick={() => setTab(t.id)} style={{
                  appearance: 'none', cursor: 'pointer', border: 'none',
                  flex: 1, padding: '10px 0',
                  background: 'transparent',
                  color: tab === t.id ? 'var(--gold)' : 'var(--ink-secondary)',
                  font: '500 12px/1 var(--font-display)',
                  letterSpacing: '0.02em',
                  position: 'relative',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                }}>
                  {t.l}
                  <span style={{
                    font: '300 9px/1 var(--font-mono)',
                    color: tab === t.id ? 'var(--gold-soft)' : 'var(--ink-tertiary)',
                  }}>{t.c}</span>
                  {tab === t.id && (
                    <div style={{
                      position: 'absolute', left: '20%', right: '20%', bottom: -0.5,
                      height: 1.5, background: 'var(--gold)',
                      boxShadow: '0 0 6px var(--gold-glow)',
                    }} />
                  )}
                </button>
              ))}
            </div>
          </div>
          {/* content */}
          <div style={{
            flex: 1, overflow: 'auto', padding: '8px 10px 24px',
            minHeight: 0,
          }}>
            {tab === 'people' && (
              <>
                {/* filter chips */}
                <div style={{
                  display: 'flex', gap: 4, padding: '6px 4px 10px',
                  overflow: 'auto', scrollbarWidth: 'none',
                }}>
                  {[
                    { k: 'all',    l: 'усі' },
                    { k: 'fav',    l: '✦ улюблені' },
                    { k: 'paired', l: '· phantom' },
                    { k: 'recent', l: 'недавні' },
                    { k: 'unread', l: 'непрочитані' },
                  ].map((f) => (
                    <button key={f.k} onClick={() => setFilter(f.k)} style={{
                      appearance: 'none', cursor: 'pointer', flexShrink: 0,
                      padding: '5px 10px', borderRadius: 999,
                      background: filter === f.k ? 'rgba(244,175,37,0.12)' : 'transparent',
                      border: `0.5px solid ${filter === f.k ? 'var(--gold-soft)' : 'var(--glass-edge)'}`,
                      color: filter === f.k ? 'var(--gold)' : 'var(--ink-secondary)',
                      font: '400 10.5px/1 var(--font-body)', letterSpacing: '0.01em',
                    }}>{f.l}</button>
                  ))}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  {filtered.map((p) => (
                    <PersonRow key={p.id} p={p}
                               onOpen={onOpenPerson}
                               onLongPress={onEditPerson} />
                  ))}
                  {filtered.length === 0 && (
                    <div style={{
                      padding: '40px 20px', textAlign: 'center',
                      font: '300 13px/1.7 Georgia, serif',
                      color: 'var(--ink-tertiary)', fontStyle: 'italic',
                    }}>
                      нікого тут.<br />
                      <span style={{ color: 'var(--gold-soft)' }}>+ додати — або змінити фільтр</span>
                    </div>
                  )}
                </div>
              </>
            )}
            {tab === 'groups' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '6px 4px' }}>
                {groups.map((g) => (
                  <GroupRow key={g.id} g={g} people={people}
                            onOpen={onOpenGroup}
                            onLongPress={onEditGroup} />
                ))}
              </div>
            )}
            {tab === 'memory' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '6px 4px' }}>
                <div className="mono" style={{
                  padding: '14px 8px 6px', fontSize: 9, color: 'var(--ink-tertiary)',
                  letterSpacing: '0.18em', textTransform: 'uppercase',
                }}>· люди, з якими ти більше не пишеш</div>
                <div style={{
                  padding: '6px 12px', font: '300 11px/1.55 Georgia, serif',
                  color: 'var(--ink-tertiary)', fontStyle: 'italic', textWrap: 'pretty',
                }}>
                  phantom не забуває. ти не пишеш — це теж тип присутності. вони тут, коли захочеш повернути.
                </div>
                {archived.map((p) => (
                  <div key={p.id} style={{ opacity: 0.7 }}>
                    <PersonRow p={p} onOpen={onOpenPerson} onLongPress={onEditPerson} />
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

// ─── thread pill in header (replaces #now) ──────────────────────────────────
function ThreadPill({ thread, person, onOpen }) {
  return (
    <button onClick={onOpen} style={{
      appearance: 'none', cursor: 'pointer', border: 'none',
      background: 'rgba(255,230,180,0.04)',
      padding: '4px 10px 4px 6px', borderRadius: 999,
      display: 'flex', alignItems: 'center', gap: 5,
      border: '0.5px solid var(--glass-edge)',
    }}>
      {person ? (
        <PersonAvatar p={person} size={20} withRing={false} />
      ) : (
        <span style={{
          width: 8, height: 8, borderRadius: '50%',
          background: 'var(--gold)', boxShadow: '0 0 4px var(--gold-glow)',
          marginRight: 2,
        }} />
      )}
      <span style={{
        font: '500 10.5px/1 var(--font-body)', color: 'var(--ink-strong)',
        letterSpacing: '0.02em',
      }}>{person ? person.name : `#${thread}`}</span>
      <svg width="8" height="8" viewBox="0 0 8 8" style={{ marginLeft: 2, opacity: 0.5 }}>
        <path d="M2 3l2 2l2 -2" stroke="var(--ink-secondary)" strokeWidth="1" fill="none" strokeLinecap="round" />
      </svg>
    </button>
  );
}

Object.assign(window, {
  tempColor, tempLabel, PersonAvatar, PersonRow, GroupRow, PeoplePanel, ThreadPill,
});
