/* people-thread.jsx — 1-on-1 mediated chat with a person:
   PersonThread (panel overlay over live chat),
   with phantom-on-your-behalf bubbles, advice rail, and per-person tone hints. */

// ─── ThemMessageBubble — incoming from them (with hue accent) ───────────────
function ThemBubble({ text, time, hue }) {
  return (
    <div className="fade-in" style={{ display: 'flex', justifyContent: 'flex-start', padding: '4px 0' }}>
      <div style={{ maxWidth: '80%' }}>
        <div className="glass" style={{
          padding: '10px 14px',
          borderRadius: '18px 18px 18px 4px',
          background: `${hue}10`,
          border: `0.5px solid ${hue}33`,
        }}>
          <div style={{
            font: '400 13px/1.5 var(--font-body)', color: 'var(--ink-strong)',
            letterSpacing: '0.005em', textWrap: 'pretty',
          }}>{text}</div>
          {time && (
            <div className="mono" style={{
              marginTop: 4, fontSize: 8.5, color: hue, opacity: 0.6,
            }}>{time}</div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── YouViaPhantom — phantom answered for you ───────────────────────────────
function YouViaPhantomBubble({ text, time, hue, accepted = true }) {
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '80%' }}>
        <div className="mono" style={{
          fontSize: 7.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
          textTransform: 'uppercase', textAlign: 'right', marginBottom: 3,
          display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 5,
        }}>
          <span style={{
            width: 4, height: 4, borderRadius: '50%', background: 'var(--gold)',
            boxShadow: '0 0 4px var(--gold-glow)',
          }} />
          · phantom · відповів за тебе
        </div>
        <div className="glass" style={{
          padding: '10px 14px',
          borderRadius: '18px 18px 4px 18px',
          background: 'rgba(244,175,37,0.12)',
          border: '0.5px solid rgba(244,175,37,0.30)',
          boxShadow: '0 0 10px rgba(244,175,37,0.08)',
        }}>
          <div style={{
            font: '400 13px/1.5 var(--font-body)', color: 'var(--ink-strong)',
            letterSpacing: '0.005em', textWrap: 'pretty',
          }}>{text}</div>
          {time && (
            <div className="mono" style={{
              marginTop: 4, fontSize: 8.5, color: 'var(--gold)', opacity: 0.7,
              textAlign: 'right',
            }}>{time}</div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── PhantomAside — phantom whispering to you, not visible to them ──────────
function PhantomAside({ text, kind = 'context' }) {
  const tone = kind === 'warn' ? '#FF8B6E' : kind === 'cheer' ? '#9DCC9F' : 'var(--gold-soft)';
  return (
    <div className="fade-in" style={{
      margin: '6px 0', padding: '7px 12px',
      borderRadius: 12, background: `${tone === 'var(--gold-soft)' ? 'rgba(244,175,37,0.05)' : kind === 'warn' ? 'rgba(255,139,110,0.06)' : 'rgba(157,204,159,0.05)'}`,
      border: `0.5px dashed ${tone === 'var(--gold-soft)' ? 'rgba(244,175,37,0.25)' : kind === 'warn' ? 'rgba(255,139,110,0.25)' : 'rgba(157,204,159,0.25)'}`,
      display: 'flex', alignItems: 'baseline', gap: 8,
    }}>
      <span className="mono" style={{
        fontSize: 7.5, color: tone, letterSpacing: '0.2em',
        textTransform: 'uppercase', flexShrink: 0, paddingTop: 2,
      }}>· фантом ·</span>
      <div style={{
        font: '300 11.5px/1.55 Georgia, serif', color: 'var(--ink)',
        fontStyle: 'italic', textWrap: 'pretty', flex: 1,
        letterSpacing: '0.005em',
      }}>{text}</div>
    </div>
  );
}

// ─── PhantomDraft — phantom's draft for your approval ────────────────────────
function PhantomDraftCard({ draft, onSend, onSendAs, onEdit, onDismiss }) {
  return (
    <div className="glass accent fade-in" style={{
      margin: '10px 0', padding: '12px 14px', borderRadius: 14,
      background: `radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.10), transparent 60%), var(--glass-tint)`,
      border: '0.5px solid rgba(244,175,37,0.32)',
      boxShadow: '0 0 16px rgba(244,175,37,0.08)',
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
        textTransform: 'uppercase', marginBottom: 6,
        display: 'flex', alignItems: 'center', gap: 5,
      }}>
        <span style={{
          width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
          animation: 'phantom-pulse-glow 1.6s ease-in-out infinite',
        }} />
        · phantom · готова відповідь
      </div>
      <div style={{
        font: '400 12.5px/1.6 var(--font-body)', color: 'var(--ink-strong)',
        background: 'rgba(0,0,0,0.18)', padding: '10px 12px', borderRadius: 8,
        marginBottom: 10, textWrap: 'pretty', letterSpacing: '0.005em',
      }}>{draft}</div>
      <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
        <button onClick={onSend} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          padding: '7px 12px', borderRadius: 999,
          background: 'var(--gold)', color: '#1A140B',
          font: '500 11px/1 var(--font-display)', letterSpacing: '0.02em',
          boxShadow: '0 0 10px var(--gold-glow)',
        }}>· надіслати</button>
        <button onClick={onSendAs} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid rgba(244,175,37,0.35)',
          padding: '7px 12px', borderRadius: 999,
          background: 'transparent', color: 'var(--gold)',
          font: '500 11px/1 var(--font-display)', letterSpacing: '0.02em',
        }}>надіслати як phantom</button>
        <button onClick={onEdit} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
          padding: '7px 12px', borderRadius: 999,
          background: 'transparent', color: 'var(--ink-secondary)',
          font: '400 11px/1 var(--font-body)', letterSpacing: '0.02em',
        }}>редагувати</button>
        <button onClick={onDismiss} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          padding: '7px 8px', borderRadius: 999,
          background: 'transparent', color: 'var(--ink-tertiary)',
          font: '400 11px/1 var(--font-body)', letterSpacing: '0.02em',
        }}>×</button>
      </div>
    </div>
  );
}

// ─── PersonThread — full overlay for chat with one person ────────────────────
function PersonThread({ open, person, onClose }) {
  if (!open || !person) return null;
  return <PersonThreadInner person={person} onClose={onClose} />;
}

function PersonThreadInner({ person, onClose }) {
  const [messages, setMessages] = React.useState(() => seedThread(person));
  const [draft, setDraft] = React.useState('');
  const [phantomDraft, setPhantomDraft] = React.useState(null);
  const [showRail, setShowRail] = React.useState(false);
  const [showSkeleton, setShowSkeleton] = React.useState(false);
  const [envoyOpen, setEnvoyOpen] = React.useState(false);
  const [timeOpen, setTimeOpen] = React.useState(false);
  const [reactOpen, setReactOpen] = React.useState(false);
  const [decodeFor, setDecodeFor] = React.useState(null);
  const [scheduled, setScheduled] = React.useState(null);
  const streamRef = React.useRef(null);

  // auto-scroll
  React.useEffect(() => {
    if (streamRef.current) streamRef.current.scrollTop = streamRef.current.scrollHeight;
  }, [messages, phantomDraft]);

  // generate phantom draft based on last incoming
  React.useEffect(() => {
    const last = messages[messages.length - 1];
    if (last?.role === 'them' && !phantomDraft) {
      const id = setTimeout(() => {
        setPhantomDraft({
          text: phantomDraftFor(person, last.text),
        });
      }, 900);
      return () => clearTimeout(id);
    }
  }, [messages, phantomDraft, person]);

  const send = (text, viaPhantom = false) => {
    if (!text.trim()) return;
    setMessages((m) => [...m, {
      role: viaPhantom ? 'you-phantom' : 'you',
      text: text.trim(), time: liveTime(),
    }]);
    setDraft('');
    setPhantomDraft(null);
    // simulate them replying after a bit
    setTimeout(() => {
      setMessages((m) => [...m, {
        role: 'them', text: themReplyFor(person, text), time: liveTime(),
      }]);
    }, 1800);
  };

  const onAttach = (kind) => {
    const t = liveTime();
    if (kind === 'photo' || kind === 'camera') {
      setMessages((m) => [...m, { role: 'photo', from: 'you', scene: kind === 'camera' ? 'night' : 'mountain', caption: 'тільки що · подивись', time: t }]);
    } else if (kind === 'voice') {
      setMessages((m) => [...m, { role: 'voice', from: 'you', duration: 14, transcript: 'не знаю чому · я хочу щоб ти знала · це не про сьогодні · це загалом', time: t }]);
    } else if (kind === 'file') {
      setMessages((m) => [...m, { role: 'file', from: 'you', name: 'нотаріус-договір.pdf', size: '1.2 MB', kind: 'pdf', preview: 'індексовано · 4 стор · готово', time: t }]);
    } else if (kind === 'loc') {
      setMessages((m) => [...m, { role: 'loc', from: 'you', place: 'fika · кафе', distance: '0 км', live: true, time: t }]);
    } else if (kind === 'link') {
      setMessages((m) => [...m, { role: 'link', from: 'you', url: 'arxiv.org/2503·8744', title: 'на що схожі думки коли не спиш', summary: 'нейроекспер. з ucl · дефолтна сітка чи слабшає чи блукає · 12 хв читання', scene: 'abstract', time: t }]);
    } else if (kind === 'money') {
      setMessages((m) => [...m, { role: 'money', from: 'you', amount: 240, currency: '$', note: 'квиток у lhasa · вернеш як зможеш', time: t }]);
    } else if (kind === 'video') {
      setMessages((m) => [...m, { role: 'photo', from: 'you', scene: 'sky', caption: '▷ 2:14 · phantom вже надиктував підпис', time: t }]);
    } else if (kind === 'sticker') {
      setMessages((m) => [...m, { role: 'reaction', kind: 'sticker', content: '◐', from: 'you' }]);
    }
  };

  const onVoiceComplete = ({ duration }) => {
    setMessages((m) => [...m, {
      role: 'voice', from: 'you', duration,
      transcript: '· phantom транскрибує ·',
      time: liveTime(),
    }]);
  };

  const acceptDraft = () => {
    if (!phantomDraft) return;
    send(phantomDraft.text, true);
  };

  const tone = tempColor(person.temp);

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 105,
      display: 'flex', flexDirection: 'column',
      background: 'var(--bg-base)',
      overflow: 'hidden',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      {/* atmospheric tint */}
      <div style={{
        position: 'absolute', left: 0, right: 0, top: 0, height: '40%',
        background: `radial-gradient(ellipse 100% 60% at 50% 0%, ${person.hue}1f, transparent 70%)`,
        pointerEvents: 'none',
      }} />
      {/* header */}
      <div className="ui-chrome" style={{
        padding: '52px 14px 8px',
        position: 'relative', zIndex: 5,
        background: 'linear-gradient(to bottom, rgba(14,10,5,0.78), transparent)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button onClick={onClose} style={{
            appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
            background: 'rgba(255,230,180,0.03)', color: 'var(--ink-secondary)',
            font: '400 11px/1 var(--font-mono)', padding: '6px 10px',
            borderRadius: 999, letterSpacing: '0.04em',
          }}>←</button>
          <PersonAvatar p={person} size={32} withRing={true} withPulse={person.paired} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{
              font: '500 14px/1.2 var(--font-display)', color: 'var(--ink-strong)',
              letterSpacing: '-0.005em',
            }}>{person.name}</div>
            <div className="mono" style={{
              fontSize: 9, color: tone, letterSpacing: '0.06em', marginTop: 2,
              display: 'flex', alignItems: 'center', gap: 6,
            }}>
              {person.paired && (
                <span style={{
                  width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
                  animation: 'phantom-pulse-glow 1.6s ease-in-out infinite',
                  boxShadow: '0 0 4px var(--gold-glow)',
                }} />
              )}
              {person.paired ? 'фантом · поряд' : 'без phantom'} · {tempLabel(person.temp)}
            </div>
          </div>
          <button onClick={() => setShowRail((r) => !r)} style={{
            appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
            background: showRail ? 'rgba(244,175,37,0.10)' : 'rgba(255,230,180,0.03)',
            color: showRail ? 'var(--gold)' : 'var(--ink-secondary)',
            font: '400 10px/1 var(--font-mono)', padding: '6px 10px',
            borderRadius: 999, letterSpacing: '0.06em',
          }}>{showRail ? 'звести' : '· контекст'}</button>
        </div>
      </div>

      {/* context strip — always visible, briefer */}
      <div style={{
        padding: '4px 14px 8px', position: 'relative', zIndex: 5,
        display: 'flex', gap: 4, overflow: 'auto', scrollbarWidth: 'none',
      }}>
        {(person.permissions || []).slice(0, 4).map((p) => (
          <span key={p} className="mono" style={{
            flexShrink: 0,
            padding: '3px 8px', borderRadius: 999, fontSize: 8.5,
            background: 'rgba(244,175,37,0.06)',
            color: 'var(--gold-soft)', letterSpacing: '0.04em',
            border: '0.5px solid rgba(244,175,37,0.20)',
          }}>· {p}</span>
        ))}
      </div>

      {/* messages */}
      <div ref={streamRef} style={{
        flex: 1, overflow: 'auto', padding: '4px 14px 8px',
        position: 'relative', zIndex: 5, minHeight: 0,
      }}>
        {/* skeleton toggle */}
        <button onClick={() => setShowSkeleton((s) => !s)} style={{
          appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
          background: 'rgba(255,230,180,0.025)', color: 'var(--gold-soft)',
          font: '400 10px/1 var(--font-body)', padding: '6px 10px',
          borderRadius: 999, letterSpacing: '0.04em', marginBottom: 8,
          display: 'flex', alignItems: 'center', gap: 6, width: '100%', justifyContent: 'center',
        }}>
          <svg width="9" height="9" viewBox="0 0 9 9"><path d="M1 4h7M1 6h5M1 2h6" stroke="currentColor" strokeWidth="0.7" /></svg>
          · skeleton · 14 днів {showSkeleton ? '· звести ∧' : '· розкрити ∨'}
        </button>
        {showSkeleton && <ThreadSkeleton person={person} />}
        {messages.map((m, i) => {
          if (m.role === 'system') return <SystemWhisper key={i} text={m.text} />;
          if (m.role === 'aside')  return <PhantomAside key={i} text={m.text} kind={m.kind} />;
          if (m.role === 'them') {
            const decoded = decodeFor === i;
            return (
              <React.Fragment key={i}>
                <div onContextMenu={(e) => { e.preventDefault(); setDecodeFor(decoded ? null : i); }}
                     onDoubleClick={() => setDecodeFor(decoded ? null : i)}>
                  <ThemBubble text={m.text} time={m.time} hue={person.hue} />
                </div>
                {decoded && (
                  <ReDecodeCard
                    original={m.text}
                    decoded={decodeFor === i ? decodeMessage(m.text, person) : ''}
                    hue={person.hue}
                  />
                )}
                {!decoded && i === 0 && (
                  <div style={{ display: 'flex', justifyContent: 'flex-start', marginTop: -2, marginBottom: 6 }}>
                    <button onClick={() => setDecodeFor(i)} style={{
                      appearance: 'none', cursor: 'pointer', border: '0.5px solid rgba(255,139,110,0.25)',
                      background: 'rgba(255,139,110,0.06)', color: 'var(--coral)',
                      font: '400 9.5px/1 var(--font-mono)', padding: '4px 9px',
                      borderRadius: 999, letterSpacing: '0.04em',
                    }}>· re-decode</button>
                  </div>
                )}
              </React.Fragment>
            );
          }
          if (m.role === 'you')    return <LiveUserBubble key={i} text={m.text} time={m.time} />;
          if (m.role === 'you-phantom') return <YouViaPhantomBubble key={i} text={m.text} time={m.time} hue={person.hue} />;
          if (m.role === 'envoy')  return <PhantomEnvoy key={i} person={person} onClose={() => {}} />;
          if (m.role === 'reaction') return <ReactionBubble key={i} kind={m.kind} content={m.content} from={m.from} hue={person.hue} />;
          if (m.role === 'voice')    return <VoiceMemoBubble  key={i} from={m.from} duration={m.duration} transcript={m.transcript} hue={person.hue} time={m.time} />;
          if (m.role === 'photo')    return <PhotoMessageBubble key={i} from={m.from} scene={m.scene} caption={m.caption} hue={person.hue} time={m.time} />;
          if (m.role === 'file')     return <FileMessageBubble key={i} from={m.from} name={m.name} size={m.size} kind={m.kind} preview={m.preview} hue={person.hue} time={m.time} />;
          if (m.role === 'loc')      return <LocationMessageBubble key={i} from={m.from} place={m.place} distance={m.distance} live={m.live} hue={person.hue} time={m.time} />;
          if (m.role === 'link')     return <LinkPreviewBubble key={i} from={m.from} url={m.url} title={m.title} summary={m.summary} scene={m.scene} hue={person.hue} time={m.time} />;
          if (m.role === 'money')    return <MoneyMessageBubble key={i} from={m.from} amount={m.amount} currency={m.currency} note={m.note} hue={person.hue} time={m.time} />;
          return null;
        })}
        {phantomDraft && (
          <PhantomDraftCard draft={phantomDraft.text}
                            onSend={() => { setDraft(phantomDraft.text); setPhantomDraft(null); }}
                            onSendAs={acceptDraft}
                            onEdit={() => { setDraft(phantomDraft.text); setPhantomDraft(null); }}
                            onDismiss={() => setPhantomDraft(null)} />
        )}
        {scheduled && (
          <div className="glass fade-in" style={{
            margin: '8px 0', padding: '8px 12px', borderRadius: 12,
            background: 'rgba(244,175,37,0.06)',
            border: '0.5px solid rgba(244,175,37,0.25)',
            display: 'flex', alignItems: 'center', gap: 8,
          }}>
            <span style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--gold-soft)', flex: 1 }}>
              · заплановано · {scheduled.l}
            </span>
            <button onClick={() => setScheduled(null)} style={{
              appearance: 'none', cursor: 'pointer', border: 'none', background: 'transparent',
              color: 'var(--ink-tertiary)', font: '400 11px/1 var(--font-mono)',
            }}>×</button>
          </div>
        )}
      </div>

      {/* phantom advice rail (collapsible right side) */}
      {showRail && (
        <div className="glass panel fade-in" style={{
          position: 'absolute', right: 6, top: 110, bottom: 140,
          width: 220, zIndex: 30,
          padding: '14px 14px', borderRadius: 16,
          background: 'rgba(20,14,10,0.92)',
          backdropFilter: 'blur(20px)',
          border: '0.5px solid rgba(244,175,37,0.25)',
          overflow: 'auto',
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
            textTransform: 'uppercase', marginBottom: 10,
          }}>· phantom · ради</div>
          <AdviceList person={person} />
        </div>
      )}

      {/* composer */}
      <div className="ui-chrome" style={{
        padding: '8px 14px 14px',
        background: 'linear-gradient(to top, rgba(14,10,5,0.95), rgba(14,10,5,0.5))',
        position: 'relative', zIndex: 10,
      }}>
        <ToneShifter value={draft} onChange={setDraft} hue={person.hue} />
        {/* quick actions */}
        <div style={{ display: 'flex', gap: 4, marginBottom: 6, flexWrap: 'wrap' }}>
          {[
            { k: 'envoy',  l: '✦ envoy',        c: 'var(--gold-soft)' },
            { k: 'time',   l: '◷ час',           c: '#7CC0E8' },
            { k: 'react',  l: '✺ реакція',       c: '#FF8B6E' },
          ].map((b) => (
            <button key={b.k} onClick={() => {
              if (b.k === 'envoy')  setEnvoyOpen(true);
              if (b.k === 'time')   setTimeOpen(true);
              if (b.k === 'react')  setReactOpen(true);
            }} style={{
              appearance: 'none', cursor: 'pointer', flexShrink: 0,
              padding: '5px 10px', borderRadius: 999,
              background: 'rgba(255,230,180,0.025)',
              border: `0.5px solid ${b.c}33`,
              color: b.c,
              font: '400 10px/1 var(--font-body)', letterSpacing: '0.02em',
            }}>{b.l}</button>
          ))}
        </div>
        <ProComposer
          value={draft} setValue={setDraft}
          onSend={(t) => send(t)}
          hue={person.hue}
          person={person}
          onAttach={onAttach}
          onVoiceComplete={onVoiceComplete}
        />
      </div>
      {/* overlays */}
      <TimeBubble open={timeOpen} onClose={() => setTimeOpen(false)} person={person}
                  onSchedule={(s) => { setScheduled(s); if (draft) send(draft); }} />
      <ReactWithAnything open={reactOpen} onClose={() => setReactOpen(false)}
                         onPick={(r) => {
                           setMessages((m) => [...m, { role: 'reaction', kind: r.sub, content: r.l, from: 'you' }]);
                         }} />
      {envoyOpen && (
        <div onClick={() => setEnvoyOpen(false)} style={{
          position: 'absolute', inset: 0, zIndex: 20,
          background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: 14,
        }}>
          <div onClick={(e) => e.stopPropagation()} style={{ width: '100%' }}>
            <PhantomEnvoy person={person} onClose={() => setEnvoyOpen(false)} />
          </div>
        </div>
      )}
    </div>
  );
}

// ─── AdviceList — phantom's contextual suggestions for this person ──────────
function AdviceList({ person }) {
  const advice = [
    { l: 'недавнє · з нею',  d: 'остання її повідомлення нервова. короткі речення. вона хоче не розмови, а присутності.' },
    { l: 'твій патерн',       d: 'у розмовах з нею ти зазвичай переходиш на «вибач». цього разу — не варто.' },
    { l: 'хороший хід',       d: '«я тут. не біжи. розкажи що страшно.» — твій тон з нею у квітні працював.' },
    { l: 'час',                d: 'не пиши до 22:00. вона саме на йозі. ти прокинеш її.' },
    { l: 'не пиши',            d: '«не хвилюйся». це її дратує. ти знаєш.' },
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {advice.map((a, i) => (
        <div key={i} style={{
          padding: '8px 10px', borderRadius: 8,
          background: 'rgba(255,230,180,0.025)',
          border: '0.5px solid var(--glass-edge)',
        }}>
          <div className="mono" style={{
            fontSize: 7.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· {a.l}</div>
          <div style={{
            font: '300 10.5px/1.5 Georgia, serif',
            color: 'var(--ink-strong)', fontStyle: 'italic',
            textWrap: 'pretty',
          }}>«{a.d}»</div>
        </div>
      ))}
    </div>
  );
}

// ─── seed conversations per person ──────────────────────────────────────────
function seedThread(p) {
  if (p.id === 'olya') return [
    { role: 'system', text: 'нитка · оля · phantom поряд' },
    { role: 'aside', kind: 'context', text: 'вона написала 12 хв тому · нервова перед нотаріусом завтра · 3 повідомлення скасувала' },
    { role: 'them', text: 'привіт. ти ще біля води?', time: '19:22' },
    { role: 'photo', from: 'them', scene: 'sky', caption: 'небо тут · я ходжу й дивлюсь', time: '19:23' },
    { role: 'them', text: 'я думала ми мали говорити сьогодні · не сьогодні так?', time: '19:24' },
    { role: 'voice', from: 'them', duration: 18, transcript: 'я просто… не знаю чому · мабуть просто хвилюсь · вибач що нав\'язую', time: '19:25' },
    { role: 'you',  text: 'тут. читаю. за пів години — повний.', time: '19:30' },
    { role: 'them', text: 'добре. вибач що нервую. просто я хвилююсь, ти знаєш.', time: '19:31' },
    { role: 'aside', kind: 'warn', text: 'вона хоче ствердження, не план. почни з «я з тобою», а не «що зробимо»' },
    { role: 'file', from: 'them', name: 'нотаріус-протокол-чорнетка.pdf', size: '480 KB', kind: 'pdf', preview: 'phantom прочитав · 4 правки', time: '19:32' },
    { role: 'loc', from: 'them', place: 'своя вулиця · 200 м', distance: '4.2 км', live: true, time: '19:33' },
    { role: 'link', from: 'them', url: 'arxiv.org/2503·8744', title: 'на що схожі думки коли не спиш', summary: 'нейроекспер. з ucl · дефолтна сітка чи блукає · вона додала «прочитай якщо встигнеш»', scene: 'abstract', time: '19:33' },
  ];
  if (p.id === 'oleh') return [
    { role: 'system', text: 'нитка · олег · 3 дні тиші' },
    { role: 'aside', kind: 'context', text: 'остання розмова 11 травня. ти писав о 23:51, я зупинив. ще не відповідав.' },
    { role: 'them', text: 'привіт. за вчорашнє — я був різкий на standup. вибач.', time: '14:20' },
  ];
  if (p.id === 'mama') return [
    { role: 'system', text: 'нитка · мама · сьогодні' },
    { role: 'aside', kind: 'cheer', text: 'вона дзвонила 3 рази за тиждень. ти не передзвонив раз. сьогодні — добрий момент.' },
    { role: 'them', text: 'котику, ти спав сьогодні нормально?', time: '08:14' },
    { role: 'photo', from: 'them', scene: 'food', caption: 'тут роблю борщ. передай олі що чекаю.', time: '08:15' },
    { role: 'them', text: 'передзвони коли встанеш', time: '08:16' },
    { role: 'voice', from: 'them', duration: 32, transcript: 'котику · мені трохи тривожно · я не хвилюю тебе тиснути · просто скажи що ти живий', time: '11:42' },
  ];
  return [
    { role: 'system', text: `нитка · ${p.name}` },
    { role: 'aside', kind: 'context', text: `phantom щойно зʼєднався. поки що тиша.` },
  ];
}

function phantomDraftFor(p, theirLast) {
  if (p.id === 'olya') return 'я з тобою. не біжи зараз. завтра ми разом увійдемо до нотаріуса і вийдемо разом. усе.';
  if (p.id === 'oleh') return 'нічого. я знаю що ти втомлений. зустрінемось у середу за кавою — не на стендапі, окремо.';
  if (p.id === 'mama') return 'мамо, спав 7 з половиною. зараз дочитую — за годину передзвоню. ти ж знаєш я завжди передзвонюю.';
  return 'тут. слухаю. можемо поговорити коли тобі зручно.';
}

function themReplyFor(p, yourText) {
  if (p.id === 'olya') return 'дякую. серце трохи відпускає від того що ти просто тут.';
  if (p.id === 'oleh') return 'окей. до середи.';
  if (p.id === 'mama') return 'котику, дякую. чекаю.';
  return '·';
}

function decodeMessage(text, p) {
  if (p.id === 'olya') return 'я не хочу плану. я хочу почути що ти зі мною. і боюсь що ти не зрозумієш.';
  if (p.id === 'oleh') return 'я знаю що був неправий. я не хочу довгих розмов · просто хочу побачити що ти все ще тут.';
  if (p.id === 'mama') return 'мені тривожно. дзвонила тричі. передзвони — не пиши.';
  return 'те, що вона сказала, і те, що мала на увазі — не одне й те саме. phantom бачить шарово.';
}

Object.assign(window, {
  PersonThread, ThemBubble, YouViaPhantomBubble, PhantomAside, PhantomDraftCard,
  AdviceList,
});
