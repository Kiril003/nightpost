/* thread-pro.jsx — full chat composer + media bubbles for PersonThread:
   ProComposer (+ attachment menu, voice hold-to-record),
   VoiceRecordOverlay, media bubbles (photo, voice, file, loc, video, link),
   AtlasGuide inline hint. */

// ─── ProComposer ────────────────────────────────────────────────────────────
function ProComposer({ value, setValue, onSend, hue = '#F4AF25', onAttach, onVoiceComplete, person }) {
  const [menuOpen, setMenuOpen] = React.useState(false);
  const [recording, setRecording] = React.useState(false);
  const [recDur, setRecDur] = React.useState(0);
  const recRef = React.useRef(null);

  React.useEffect(() => {
    if (!recording) return;
    const id = setInterval(() => setRecDur((d) => d + 0.1), 100);
    return () => clearInterval(id);
  }, [recording]);

  const startRec = () => {
    setRecDur(0);
    setRecording(true);
  };
  const stopRec = (send) => {
    setRecording(false);
    if (send && recDur > 0.5) {
      onVoiceComplete?.({ duration: recDur });
    }
    setRecDur(0);
  };

  return (
    <div style={{ position: 'relative' }}>
      {recording && (
        <VoiceRecordOverlay duration={recDur}
                            onSend={() => stopRec(true)}
                            onCancel={() => stopRec(false)}
                            hue={hue} />
      )}
      {menuOpen && (
        <ProAttachMenu hue={hue} onPick={(k) => { onAttach?.(k); setMenuOpen(false); }}
                       onClose={() => setMenuOpen(false)} />
      )}
      <div className="glass" style={{
        display: 'flex', alignItems: 'flex-end', gap: 4,
        padding: '4px', borderRadius: 22,
        background: 'rgba(20,14,10,0.7)',
        border: `0.5px solid ${value ? hue + '4D' : 'var(--glass-edge)'}`,
        boxShadow: value ? `0 0 14px ${hue}22` : 'none',
        transition: 'all 0.2s',
      }}>
        {/* + attach */}
        <button onClick={() => setMenuOpen((o) => !o)} style={{
          appearance: 'none', cursor: 'pointer', border: 'none', flexShrink: 0,
          width: 32, height: 32, borderRadius: '50%',
          background: menuOpen ? `${hue}22` : 'transparent',
          color: menuOpen ? hue : 'var(--ink-secondary)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          font: '400 18px/1 var(--font-mono)', marginBottom: 2,
          transition: 'all 0.18s',
          transform: menuOpen ? 'rotate(45deg)' : 'rotate(0)',
        }}>+</button>
        <textarea
          value={value} onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && value.trim()) { e.preventDefault(); onSend(value); }}}
          rows={1} placeholder={`пиши ${person.name} природно…`}
          style={{
            flex: 1, appearance: 'none', border: 'none', outline: 'none',
            background: 'transparent', resize: 'none',
            font: '400 13px/1.45 var(--font-body)', color: 'var(--ink-strong)',
            padding: '8px 4px', minHeight: 32, maxHeight: 110,
          }} />
        {value.trim() ? (
          <button onClick={() => onSend(value)}
                  style={{
                    appearance: 'none', cursor: 'pointer', border: 'none', flexShrink: 0,
                    width: 32, height: 32, borderRadius: '50%',
                    background: hue, color: '#1A140B',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    marginBottom: 2, boxShadow: `0 0 10px ${hue}88`,
                  }}>
            <svg width="12" height="12" viewBox="0 0 12 12"><path d="M6 1L11 6L8 6V11L4 11V6L1 6Z" fill="currentColor"/></svg>
          </button>
        ) : (
          <button
            ref={recRef}
            onPointerDown={startRec}
            onPointerUp={() => stopRec(true)}
            onPointerLeave={() => recording && stopRec(false)}
            style={{
              appearance: 'none', cursor: 'pointer', border: 'none', flexShrink: 0,
              width: 32, height: 32, borderRadius: '50%',
              background: recording ? hue : 'rgba(244,175,37,0.10)',
              color: recording ? '#1A140B' : hue,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              marginBottom: 2,
              boxShadow: recording ? `0 0 14px ${hue}` : 'none',
              transition: 'all 0.15s',
              touchAction: 'none',
            }}>
            <svg width="14" height="14" viewBox="0 0 14 14">
              <rect x="5" y="2" width="4" height="7" rx="2" fill="currentColor" />
              <path d="M3 7a4 4 0 008 0M7 11v2" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}

// ─── ProAttachMenu — full attachment grid ────────────────────────────────────
function ProAttachMenu({ hue, onPick, onClose }) {
  const items = [
    { k: 'camera',   l: 'камера',    d: 'кадр зараз',           c: '#F4AF25', ic: '◧' },
    { k: 'photo',    l: 'фото',       d: 'з галереї',             c: '#FF8B6E', ic: '◫' },
    { k: 'voice',    l: 'голос',     d: 'довга голосова',         c: '#B49AD4', ic: '◐' },
    { k: 'file',     l: 'файл',       d: 'pdf · doc · zip',       c: '#A7967D', ic: '◇' },
    { k: 'loc',      l: 'локація',    d: 'live · 30 хв',           c: '#9DCC9F', ic: '⊚' },
    { k: 'video',    l: 'відео',     d: 'до 5 хв',                c: '#7CC0E8', ic: '▷' },
    { k: 'draw',     l: 'малюнок',   d: 'разом · canvas',        c: '#7E5DA4', ic: '✎' },
    { k: 'link',     l: 'посилання', d: 'preview live',           c: '#FFD370', ic: '⊝' },
    { k: 'money',    l: 'переказ',    d: 'phantom · vault',       c: '#67B3A1', ic: '$' },
    { k: 'event',    l: 'подія',     d: 'phantom × phantom',      c: '#E76F51', ic: '◫' },
    { k: 'contact',  l: 'контакт',   d: 'передати людину',         c: '#A7967D', ic: '✦' },
    { k: 'sticker',  l: 'sticker',    d: 'phantom anim',           c: hue, ic: '◑' },
  ];
  return (
    <div onClick={onClose} className="fade-in" style={{
      position: 'absolute', left: 0, right: 0, bottom: 'calc(100% + 8px)',
      padding: 6, borderRadius: 18, zIndex: 30,
      background: 'rgba(20,14,10,0.95)',
      backdropFilter: 'blur(24px)',
      border: '0.5px solid var(--glass-edge-strong)',
      boxShadow: '0 -8px 24px rgba(0,0,0,0.5)',
    }}>
      <div className="mono" style={{
        padding: '6px 10px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.22em', textTransform: 'uppercase',
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span>· додати · усе можна</span>
        <span style={{ color: 'var(--ink-tertiary)' }}>×</span>
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 4, padding: 4,
      }}>
        {items.map((it) => (
          <button key={it.k} onClick={(e) => { e.stopPropagation(); onPick(it.k); }} style={{
            appearance: 'none', cursor: 'pointer',
            padding: '12px 8px', borderRadius: 10,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          }}>
            <div style={{
              width: 28, height: 28, borderRadius: 8,
              background: `${it.c}1f`, color: it.c,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              font: '500 14px/1 var(--font-display)',
            }}>{it.ic}</div>
            <span style={{ font: '500 10.5px/1 var(--font-body)', color: 'var(--ink-strong)' }}>{it.l}</span>
            <span className="mono" style={{ fontSize: 7.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', textAlign: 'center' }}>{it.d}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── VoiceRecordOverlay — animated waveform while holding ───────────────────
function VoiceRecordOverlay({ duration, onSend, onCancel, hue }) {
  const mm = (s) => `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;
  const ms = String(Math.floor((duration % 1) * 10));
  return (
    <div className="fade-in" style={{
      position: 'absolute', left: 0, right: 0, bottom: 'calc(100% + 8px)',
      padding: '14px 14px 12px', borderRadius: 16,
      background: `radial-gradient(ellipse at 50% 50%, ${hue}22, transparent 70%), rgba(20,14,10,0.95)`,
      border: `0.5px solid ${hue}55`,
      boxShadow: `0 0 16px ${hue}33`,
      zIndex: 30,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{
          width: 10, height: 10, borderRadius: '50%', background: '#FF8B6E',
          boxShadow: '0 0 10px #FF8B6E',
          animation: 'phantom-pulse-glow 0.8s ease-in-out infinite',
        }} />
        <span className="mono" style={{
          fontSize: 12, color: 'var(--ink-strong)',
          fontVariantNumeric: 'tabular-nums', letterSpacing: '0.04em',
        }}>{mm(duration)}.<span style={{ color: hue }}>{ms}</span></span>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 1.5, height: 24 }}>
          {Array.from({ length: 40 }).map((_, i) => {
            const h = 0.2 + Math.abs(Math.sin(i * 0.4 + duration * 4)) * 0.7 +
                      Math.abs(Math.sin(i * 0.9 + duration * 2)) * 0.3;
            return (
              <div key={i} style={{
                flex: 1, height: `${h * 100}%`,
                background: hue, borderRadius: 1, minHeight: 2,
                opacity: 0.55 + Math.abs(Math.sin(duration * 5 + i * 0.3)) * 0.4,
              }} />
            );
          })}
        </div>
      </div>
      <div style={{
        marginTop: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
          · продовжуй тримати · відпусти — надішле
        </span>
        <span className="mono" style={{ fontSize: 8.5, color: '#FF8B6E', letterSpacing: '0.06em' }}>
          ‹ swipe щоб скасувати
        </span>
      </div>
    </div>
  );
}

// ─── Media bubbles ──────────────────────────────────────────────────────────
// VoiceMemoBubble — incoming or outgoing voice
function VoiceMemoBubble({ from, duration = 12, transcript, hue, time }) {
  const [playing, setPlaying] = React.useState(false);
  const [pos, setPos] = React.useState(0);
  React.useEffect(() => {
    if (!playing) return;
    let raf, last = performance.now();
    const tick = (t) => {
      const dt = (t - last) / 1000; last = t;
      setPos((p) => {
        const n = p + dt / duration;
        if (n >= 1) { setPlaying(false); return 0; }
        return n;
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, duration]);
  const isThem = from === 'them';
  const bars = React.useMemo(() => Array.from({ length: 32 }, (_, i) =>
    0.2 + Math.abs(Math.sin(i * 0.6)) * 0.6 + Math.abs(Math.sin(i * 1.3)) * 0.2), []);
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '78%', minWidth: 220 }}>
        <div style={{
          padding: '8px 10px', borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          background: isThem ? `${hue}10` : 'rgba(244,175,37,0.10)',
          border: `0.5px solid ${isThem ? hue + '33' : 'rgba(244,175,37,0.25)'}`,
          display: 'flex', alignItems: 'center', gap: 8,
        }}>
          <button onClick={() => setPlaying((p) => !p)} style={{
            appearance: 'none', cursor: 'pointer', border: 'none', flexShrink: 0,
            width: 30, height: 30, borderRadius: '50%',
            background: isThem ? hue : 'var(--gold)',
            color: '#1A140B',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: `0 0 8px ${isThem ? hue : 'var(--gold-glow)'}88`,
          }}>
            {playing ? (
              <svg width="9" height="9" viewBox="0 0 9 9"><rect x="2" y="1" width="2" height="7"/><rect x="5" y="1" width="2" height="7"/></svg>
            ) : (
              <svg width="10" height="10" viewBox="0 0 9 9"><path d="M2 1L7 4.5L2 8Z" fill="currentColor"/></svg>
            )}
          </button>
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 1.5, height: 22 }}>
            {bars.map((b, i) => (
              <div key={i} style={{
                width: 2, height: `${b * 100}%`, borderRadius: 1, minHeight: 2,
                background: (i / bars.length) < pos ? (isThem ? hue : 'var(--gold)') : 'var(--ink-tertiary)',
                opacity: (i / bars.length) < pos ? 1 : 0.5,
              }} />
            ))}
          </div>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)',
            fontVariantNumeric: 'tabular-nums', flexShrink: 0 }}>
            {`0:${String(Math.floor(duration)).padStart(2, '0')}`}
          </span>
        </div>
        {transcript && (
          <div style={{
            marginTop: 4, padding: '5px 10px', borderRadius: 8,
            background: 'rgba(0,0,0,0.18)',
            font: '300 10.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
            fontStyle: 'italic', textWrap: 'pretty',
            borderLeft: `1.5px solid ${isThem ? hue : 'var(--gold-soft)'}`,
          }}>«{transcript}»</div>
        )}
        {time && (
          <div className="mono" style={{
            marginTop: 4, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: isThem ? 'left' : 'right',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// PhotoMessageBubble — sent or received photo with caption
function PhotoMessageBubble({ from, scene = 'mountain', caption, hue, time }) {
  const isThem = from === 'them';
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '78%' }}>
        <div style={{
          borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          overflow: 'hidden', border: `0.5px solid ${isThem ? hue + '33' : 'rgba(244,175,37,0.25)'}`,
        }}>
          <PainterlyPhoto scene={scene} ratio="4/3" />
          {caption && (
            <div style={{
              padding: '8px 12px',
              background: isThem ? `${hue}10` : 'rgba(244,175,37,0.10)',
              font: '400 12px/1.45 var(--font-body)', color: 'var(--ink-strong)',
              textWrap: 'pretty',
            }}>{caption}</div>
          )}
        </div>
        {time && (
          <div className="mono" style={{
            marginTop: 3, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: isThem ? 'left' : 'right',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// FileMessageBubble — sent or received file
function FileMessageBubble({ from, name, size, kind = 'pdf', preview, hue, time }) {
  const isThem = from === 'them';
  const kindC = { pdf: '#FF8B6E', doc: '#7CC0E8', zip: '#A7967D', img: '#9DCC9F' };
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '78%' }}>
        <div style={{
          padding: '10px 12px',
          borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          background: isThem ? `${hue}10` : 'rgba(244,175,37,0.10)',
          border: `0.5px solid ${isThem ? hue + '33' : 'rgba(244,175,37,0.25)'}`,
          display: 'flex', alignItems: 'center', gap: 12, minWidth: 220,
        }}>
          <div style={{
            width: 38, height: 48, borderRadius: 6,
            background: `linear-gradient(160deg, ${kindC[kind]}33, ${kindC[kind]}11)`,
            border: `0.5px solid ${kindC[kind]}66`,
            display: 'flex', alignItems: 'flex-end', justifyContent: 'center',
            padding: 4, flexShrink: 0,
          }}>
            <span className="mono" style={{ fontSize: 8, color: kindC[kind], fontWeight: 600, letterSpacing: '0.06em' }}>{kind.toUpperCase()}</span>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{
              font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)',
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            }}>{name}</div>
            <div className="mono" style={{
              fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.04em',
              marginTop: 3,
            }}>{size}{preview ? ` · ${preview}` : ''}</div>
          </div>
          <button style={{
            appearance: 'none', cursor: 'pointer', border: 'none',
            width: 26, height: 26, borderRadius: '50%',
            background: 'rgba(244,175,37,0.10)',
            color: 'var(--gold)', flexShrink: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <svg width="10" height="10" viewBox="0 0 10 10"><path d="M5 1v6M2 4l3 3l3 -3M1 9h8" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        </div>
        {time && (
          <div className="mono" style={{
            marginTop: 3, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: isThem ? 'left' : 'right',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// LocationMessageBubble — sent or received live location
function LocationMessageBubble({ from, place = 'lhasa', distance = '4.2 км', live = true, hue, time }) {
  const isThem = from === 'them';
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '78%', minWidth: 220 }}>
        <div style={{
          borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          overflow: 'hidden', border: `0.5px solid ${isThem ? hue + '33' : 'rgba(157,204,159,0.30)'}`,
        }}>
          <div style={{
            height: 120, position: 'relative',
            background: `
              radial-gradient(ellipse 80% 60% at 50% 50%, #1a120a, #0a0805 70%),
              repeating-linear-gradient(0deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 32px),
              repeating-linear-gradient(90deg, rgba(244,175,37,0.025) 0 1px, transparent 1px 32px)
            `,
          }}>
            <svg viewBox="0 0 300 120" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
              <path d="M -10 60 Q 80 50 150 65 T 320 70" fill="none" stroke="rgba(167,150,125,0.18)" strokeWidth="4" />
              <path d="M 100 -10 Q 110 60 105 130" fill="none" stroke="rgba(167,150,125,0.12)" strokeWidth="3" />
            </svg>
            <div style={{
              position: 'absolute', left: '50%', top: '50%',
              transform: 'translate(-50%, -50%)',
              width: 14, height: 14, borderRadius: '50%',
              background: live ? '#9DCC9F' : '#A7967D',
              boxShadow: `0 0 0 4px ${live ? 'rgba(157,204,159,0.25)' : 'rgba(167,150,125,0.2)'}, 0 0 12px ${live ? '#9DCC9F' : '#A7967D'}`,
              animation: live ? 'phantom-pulse-glow 1.8s ease-in-out infinite' : 'none',
            }} />
            <div className="mono" style={{
              position: 'absolute', top: 8, left: 8, fontSize: 8,
              color: live ? '#9DCC9F' : 'var(--ink-tertiary)', letterSpacing: '0.14em',
              padding: '3px 7px', borderRadius: 999,
              background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(6px)',
              border: live ? '0.5px solid rgba(157,204,159,0.3)' : '0.5px solid var(--glass-edge)',
            }}>· {live ? 'live · 27 хв' : 'статична'}</div>
          </div>
          <div style={{
            padding: '8px 12px', display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            background: isThem ? `${hue}10` : 'rgba(157,204,159,0.08)',
          }}>
            <div>
              <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{place}</div>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 2 }}>{distance} від тебе</div>
            </div>
            <button style={{
              appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge-strong)',
              padding: '5px 9px', borderRadius: 999,
              background: 'transparent', color: 'var(--ink-strong)',
              font: '400 10px/1 var(--font-body)',
            }}>прокласти</button>
          </div>
        </div>
        {time && (
          <div className="mono" style={{
            marginTop: 3, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: isThem ? 'left' : 'right',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// LinkPreviewBubble — sent or received link with rich preview
function LinkPreviewBubble({ from, url, title, summary, scene = 'city', hue, time }) {
  const isThem = from === 'them';
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '78%' }}>
        <div style={{
          borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          overflow: 'hidden', border: `0.5px solid ${isThem ? hue + '33' : 'rgba(244,175,37,0.25)'}`,
        }}>
          <PainterlyPhoto scene={scene} ratio="2/1" />
          <div style={{
            padding: '8px 12px',
            background: isThem ? `${hue}10` : 'rgba(244,175,37,0.10)',
          }}>
            <div className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.12em', marginBottom: 3 }}>{url}</div>
            <div style={{ font: '500 12px/1.3 var(--font-display)', color: 'var(--ink-strong)', marginBottom: 3, textWrap: 'pretty' }}>{title}</div>
            <div style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-tertiary)', textWrap: 'pretty' }}>{summary}</div>
          </div>
        </div>
        {time && (
          <div className="mono" style={{
            marginTop: 3, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: isThem ? 'left' : 'right',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// MoneyMessageBubble
function MoneyMessageBubble({ from, amount, currency = '$', note, hue, time }) {
  const isThem = from === 'them';
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: isThem ? 'flex-start' : 'flex-end', padding: '4px 0',
    }}>
      <div style={{ maxWidth: '60%' }}>
        <div style={{
          padding: '12px 14px',
          borderRadius: isThem ? '16px 16px 16px 4px' : '16px 16px 4px 16px',
          background: `radial-gradient(ellipse at 0% 0%, ${hue}22, transparent 60%), rgba(244,175,37,0.10)`,
          border: `0.5px solid ${isThem ? hue + '33' : 'rgba(244,175,37,0.25)'}`,
          textAlign: 'center',
        }}>
          <div className="mono" style={{
            fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 5,
          }}>· переказ · phantom · ed25519</div>
          <div style={{
            font: '300 28px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.015em', marginBottom: 4,
          }}>{currency}{amount}</div>
          {note && (
            <div style={{
              font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
              fontStyle: 'italic', textWrap: 'pretty',
            }}>«{note}»</div>
          )}
        </div>
        {time && (
          <div className="mono" style={{
            marginTop: 3, fontSize: 8, color: 'var(--ink-tertiary)',
            textAlign: 'center',
          }}>{time}</div>
        )}
      </div>
    </div>
  );
}

// ─── AtlasGuide — onboarding hint overlay for first time atlas opens ────────
function AtlasGuide({ onDismiss }) {
  return (
    <div className="glass accent fade-in" style={{
      position: 'absolute', left: 14, right: 14, top: 70, zIndex: 30,
      padding: '14px 16px', borderRadius: 16,
      background: `radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.18), transparent 60%), rgba(20,14,10,0.95)`,
      backdropFilter: 'blur(20px)',
      border: '0.5px solid rgba(244,175,37,0.4)',
      boxShadow: '0 0 24px rgba(244,175,37,0.18)',
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.22em',
        textTransform: 'uppercase', marginBottom: 6,
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span>· як читати atlas</span>
        <button onClick={onDismiss} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer', font: '300 12px/1 var(--font-mono)',
        }}>×</button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {[
          { l: 'центр',     d: 'ти · золотий орб дихає' },
          { l: 'кільця',     d: 'тісне коло · помірне · далеке — за температурою стосунку' },
          { l: 'крапка',     d: 'людина · колір = її hue · pulse = phantom paired' },
          { l: 'тап',         d: 'відкрити чат' },
          { l: 'наведи',      d: 'превʼю з ім\'ям і станом' },
          { l: 'drag',        d: 'обертати атлас рукою' },
          { l: 'zoom +/-',    d: 'наблизити до тісного · вийти до далеких' },
          { l: 'search',      d: 'підсвітити імʼя у сузірʼї' },
        ].map((r, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '60px 1fr',
            gap: 10, alignItems: 'baseline',
          }}>
            <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.08em' }}>· {r.l}</span>
            <span style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>{r.d}</span>
          </div>
        ))}
      </div>
      <button onClick={onDismiss} style={{
        marginTop: 10, appearance: 'none', cursor: 'pointer', border: 'none',
        width: '100%', padding: '8px', borderRadius: 10,
        background: 'var(--gold)', color: '#1A140B',
        font: '500 11px/1 var(--font-display)', letterSpacing: '0.02em',
      }}>зрозумів · далі</button>
    </div>
  );
}

Object.assign(window, {
  ProComposer, ProAttachMenu, VoiceRecordOverlay,
  VoiceMemoBubble, PhotoMessageBubble, FileMessageBubble,
  LocationMessageBubble, LinkPreviewBubble, MoneyMessageBubble,
  AtlasGuide,
});
