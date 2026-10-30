/* live-pieces.jsx — all the parts for the live chat scene:
   message rendering (user, agent, streaming text, thinking, tool calls,
   intent preview, smart replies, message actions),
   composer (text + ghost completion + slash + mention + attachments + voice). */

// ─── helpers ────────────────────────────────────────────────────────────────
function liveTime() {
  const d = new Date();
  return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
}

// tiny icon helpers (svg inline)
const I = {
  send:    <svg width="14" height="14" viewBox="0 0 14 14"><path d="M2 7L12 2L8 12L7 8L2 7Z" fill="currentColor"/></svg>,
  mic:     <svg width="14" height="14" viewBox="0 0 14 14"><rect x="5" y="2" width="4" height="7" rx="2" fill="currentColor"/><path d="M3 7a4 4 0 008 0M7 11v2" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round"/></svg>,
  plus:    <svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 2v10M2 7h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>,
  slash:   <svg width="10" height="10" viewBox="0 0 10 10"><path d="M7 1L3 9" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>,
  at:      <svg width="10" height="10" viewBox="0 0 10 10"><circle cx="5" cy="5" r="2" fill="none" stroke="currentColor" strokeWidth="0.8"/><path d="M7 5v1.5a1 1 0 002 0V5a4 4 0 10-1.4 3" stroke="currentColor" strokeWidth="0.8" fill="none"/></svg>,
  hash:    <svg width="10" height="10" viewBox="0 0 10 10"><path d="M4 1L3 9M7 1L6 9M1 4h8M1 7h7" stroke="currentColor" strokeWidth="0.9" strokeLinecap="round"/></svg>,
  attach:  <svg width="12" height="12" viewBox="0 0 12 12"><path d="M8 3v5a2 2 0 11-4 0V3a1 1 0 012 0v5" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round"/></svg>,
  camera:  <svg width="12" height="12" viewBox="0 0 12 12"><path d="M2 4h2l1-1h2l1 1h2v6H2V4z" stroke="currentColor" strokeWidth="0.8" fill="none"/><circle cx="6" cy="7" r="1.6" fill="none" stroke="currentColor" strokeWidth="0.8"/></svg>,
  gallery: <svg width="12" height="12" viewBox="0 0 12 12"><rect x="1.5" y="1.5" width="9" height="9" rx="1" fill="none" stroke="currentColor" strokeWidth="0.8"/><circle cx="4" cy="4" r="0.8" fill="currentColor"/><path d="M1.5 8L4 5.5L7 7.5L10.5 5" stroke="currentColor" strokeWidth="0.8" fill="none"/></svg>,
  file:    <svg width="12" height="12" viewBox="0 0 12 12"><path d="M3 1h4l3 3v7H3V1z" stroke="currentColor" strokeWidth="0.8" fill="none"/><path d="M7 1v3h3" stroke="currentColor" strokeWidth="0.8" fill="none"/></svg>,
  loc:     <svg width="12" height="12" viewBox="0 0 12 12"><path d="M6 1a4 4 0 014 4c0 3-4 6-4 6S2 8 2 5a4 4 0 014-4z" stroke="currentColor" strokeWidth="0.8" fill="none"/><circle cx="6" cy="5" r="1.2" fill="currentColor"/></svg>,
  draw:    <svg width="12" height="12" viewBox="0 0 12 12"><path d="M2 10l1-2 6-6 2 2-6 6-3 0z" stroke="currentColor" strokeWidth="0.8" fill="none" strokeLinejoin="round"/></svg>,
  screen:  <svg width="12" height="12" viewBox="0 0 12 12"><rect x="1.5" y="2" width="9" height="6" rx="0.5" stroke="currentColor" strokeWidth="0.8" fill="none"/><path d="M5 10h2M4 10h4" stroke="currentColor" strokeWidth="0.8"/></svg>,
  arrowUp: <svg width="10" height="10" viewBox="0 0 10 10"><path d="M5 2v6M2 5l3-3l3 3" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  x:       <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 2l6 6M8 2l-6 6" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>,
  copy:    <svg width="10" height="10" viewBox="0 0 10 10"><rect x="3" y="3" width="6" height="6" rx="0.5" fill="none" stroke="currentColor" strokeWidth="0.7"/><path d="M3 5V2a1 1 0 011-1h3" stroke="currentColor" strokeWidth="0.7" fill="none"/></svg>,
  retry:   <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 5a3 3 0 015-2.5M8 5a3 3 0 01-5 2.5M7 1v2H5M3 9V7h2" stroke="currentColor" strokeWidth="0.7" fill="none" strokeLinecap="round"/></svg>,
  fork:    <svg width="10" height="10" viewBox="0 0 10 10"><path d="M3 1v3a2 2 0 002 2h2M3 6v3M7 1v2" stroke="currentColor" strokeWidth="0.7" fill="none" strokeLinecap="round"/><circle cx="3" cy="9" r="0.8" fill="currentColor"/><circle cx="7" cy="3" r="0.8" fill="currentColor"/></svg>,
};

// ─── 1. PresenceHeader — phantom + state + thread + clear ────────────────────
function LivePresenceHeader({ state, phase, thread, onMore, audio = 0 }) {
  const phaseLabel = {
    idle: 'тут і зараз',
    listening: 'слухаю',
    thinking: 'думаю',
    typing: 'пишу',
    acting: 'дію',
  }[phase] || 'тут і зараз';
  return (
    <div className="ui-chrome" style={{
      padding: '10px 14px',
      background: 'linear-gradient(to bottom, rgba(14,10,5,0.78), rgba(14,10,5,0))',
      position: 'relative', zIndex: 8,
      display: 'flex', alignItems: 'center', gap: 12,
    }}>
      {/* mini orb */}
      <div style={{ width: 28, height: 28, flexShrink: 0, position: 'relative' }}>
        <div style={{
          position: 'absolute', inset: -2, borderRadius: '50%',
          background: 'radial-gradient(circle, var(--gold-glow), transparent 70%)',
          opacity: 0.5,
        }} />
        <div style={{
          width: '100%', height: '100%', borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
          border: '0.5px solid rgba(255,230,180,0.2)',
          animation: phase === 'thinking' ? 'phantom-pulse-glow 1.4s ease-in-out infinite' :
                     phase === 'typing' ? 'phantom-pulse-glow 0.6s ease-in-out infinite' :
                     'phantom-breathe 8s ease-in-out infinite',
        }} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span className="state-label" style={{
            fontSize: 11, color: 'var(--ink-strong)',
          }}>{state || 'ДІАЛОГ'}</span>
          <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
            · #{thread || 'now'}
          </span>
        </div>
        <div className="mono" style={{
          fontSize: 9.5, color: phase === 'idle' ? 'var(--ink-tertiary)' : 'var(--gold-soft)',
          letterSpacing: '0.04em', marginTop: 2,
          display: 'flex', alignItems: 'center', gap: 5,
        }}>
          {phase !== 'idle' && (
            <span style={{
              width: 5, height: 5, borderRadius: '50%', background: 'var(--gold-soft)',
              animation: 'phantom-pulse-glow 1.2s ease-in-out infinite',
            }} />
          )}
          · {phaseLabel}
        </div>
      </div>
      <button onClick={onMore} style={{
        width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
        appearance: 'none', cursor: 'pointer', border: '0.5px solid var(--glass-edge)',
        background: 'rgba(255,230,180,0.02)', color: 'var(--ink-secondary)',
        font: '500 14px/1 var(--font-mono)',
      }}>⋯</button>
    </div>
  );
}

// ─── 2. ThinkingBar — collapsible thinking trace inside agent message ────────
function ThinkingBar({ steps, expanded: e0 = false, duration = 320 }) {
  const [expanded, setExpanded] = React.useState(e0);
  if (!steps || steps.length === 0) return null;
  return (
    <div style={{
      padding: '6px 10px', borderRadius: 8,
      background: 'rgba(0,0,0,0.18)',
      border: '0.5px solid var(--glass-edge)',
      marginBottom: 6,
    }}>
      <button onClick={() => setExpanded((x) => !x)} style={{
        appearance: 'none', cursor: 'pointer',
        background: 'transparent', border: 'none',
        width: '100%', textAlign: 'left', padding: 0,
        display: 'flex', alignItems: 'center', gap: 6,
        color: 'var(--ink-tertiary)',
      }}>
        <span className="mono" style={{
          fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}>· think · {steps.length} кроків · {duration}мс</span>
        <span style={{ flex: 1 }} />
        <span className="mono" style={{ fontSize: 9 }}>{expanded ? '∧' : '∨'}</span>
      </button>
      {expanded && (
        <div style={{
          marginTop: 6, paddingTop: 6, borderTop: '0.5px solid var(--glass-edge)',
          display: 'flex', flexDirection: 'column', gap: 3,
        }}>
          {steps.map((s, i) => (
            <div key={i} style={{
              font: '400 10.5px/1.45 var(--font-mono)', color: 'var(--ink-secondary)',
              fontStyle: 'italic', textWrap: 'pretty',
              display: 'flex', alignItems: 'baseline', gap: 6,
            }}>
              <span style={{ color: 'var(--gold-soft)', flexShrink: 0 }}>·</span>{s}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── 3. ToolCallInline — live tool call visible in message ───────────────────
function ToolCallInline({ tool, query, result, status = 'done', ms = 312 }) {
  const isLoading = status === 'loading';
  return (
    <div style={{
      margin: '6px 0', padding: '8px 10px', borderRadius: 8,
      background: 'rgba(124,192,232,0.05)',
      border: '0.5px solid rgba(124,192,232,0.2)',
      borderLeft: '2px solid #7CC0E8',
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: '#7CC0E8', letterSpacing: '0.16em',
        textTransform: 'uppercase', marginBottom: 4,
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        {isLoading && <span style={{
          width: 5, height: 5, borderRadius: '50%', background: '#7CC0E8',
          animation: 'phantom-pulse-glow 0.8s ease-in-out infinite',
        }} />}
        · tool · <span style={{ color: 'var(--ink-strong)' }}>{tool}</span>
        <span style={{ flex: 1 }} />
        <span style={{ color: 'var(--ink-tertiary)' }}>{isLoading ? 'виконується…' : `${ms}мс`}</span>
      </div>
      <div style={{
        font: '300 10px/1.45 var(--font-mono)', color: 'var(--ink-secondary)',
        letterSpacing: '0.01em', textWrap: 'pretty',
      }}>
        <span style={{ color: 'var(--ink-tertiary)' }}>q:</span> {query}
      </div>
      {result && !isLoading && (
        <div style={{
          marginTop: 4, paddingLeft: 8,
          borderLeft: '1.5px solid rgba(157,204,159,0.4)',
          font: '400 10.5px/1.45 var(--font-mono)',
          color: '#A3D3A5', textWrap: 'pretty',
        }}>→ {result}</div>
      )}
    </div>
  );
}

// ─── 4. IntentPreview — "I'll do X" before phantom responds ──────────────────
function IntentPreview({ intent }) {
  return (
    <div className="fade-in" style={{
      display: 'flex', justifyContent: 'flex-start', padding: '4px 0',
    }}>
      <div style={{
        padding: '6px 12px', borderRadius: 999,
        background: 'rgba(244,175,37,0.06)',
        border: '0.5px solid rgba(244,175,37,0.20)',
        font: '400 10.5px/1.4 var(--font-body)',
        color: 'var(--gold-soft)',
        letterSpacing: '0.01em',
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        <span style={{
          width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
          animation: 'phantom-pulse-glow 1s ease-in-out infinite',
        }} />
        {intent}
      </div>
    </div>
  );
}

// ─── 5. StreamingText — token-by-token reveal ────────────────────────────────
function StreamingText({ text, shown }) {
  const visible = text.slice(0, shown);
  const done = shown >= text.length;
  return (
    <span style={{
      font: '400 13px/1.55 var(--font-body)', color: 'var(--ink-strong)',
      letterSpacing: '0.005em', textWrap: 'pretty',
    }}>
      {visible}
      {!done && (
        <span style={{
          display: 'inline-block', verticalAlign: 'middle',
          width: 7, height: 13, marginLeft: 2,
          background: 'var(--gold)',
          animation: 'phantom-caret 0.8s steps(2, end) infinite',
        }} />
      )}
    </span>
  );
}

// ─── 6. LiveUserBubble ───────────────────────────────────────────────────────
function LiveUserBubble({ text, time, attachments, onEdit, onFork, onRetry }) {
  const [hover, setHover] = React.useState(false);
  return (
    <div className="fade-in"
         onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
         style={{
           display: 'flex', justifyContent: 'flex-end',
           padding: '4px 0', position: 'relative',
         }}>
      <div style={{ maxWidth: '78%', position: 'relative' }}>
        {attachments && attachments.length > 0 && (
          <div style={{
            display: 'flex', gap: 4, marginBottom: 4, justifyContent: 'flex-end',
            flexWrap: 'wrap',
          }}>
            {attachments.map((a, i) => (
              <div key={i} style={{
                padding: '3px 7px 3px 5px', borderRadius: 999,
                background: 'rgba(244,175,37,0.08)',
                border: '0.5px solid rgba(244,175,37,0.20)',
                display: 'flex', alignItems: 'center', gap: 4,
                font: '400 9.5px/1 var(--font-mono)', color: 'var(--gold-soft)',
                letterSpacing: '0.04em',
              }}>
                <span style={{ opacity: 0.7 }}>{a.icon || '◐'}</span>{a.name}
              </div>
            ))}
          </div>
        )}
        <div className="glass subtle" style={{
          padding: '10px 14px',
          borderRadius: '18px 18px 4px 18px',
          background: 'rgba(244,175,37,0.12)',
          border: '0.5px solid rgba(244,175,37,0.25)',
        }}>
          <div style={{
            font: '400 13px/1.5 var(--font-body)', color: 'var(--ink-strong)',
            letterSpacing: '0.005em', textWrap: 'pretty',
          }}>{text}</div>
          {time && (
            <div className="mono" style={{
              marginTop: 4, fontSize: 8.5, color: 'rgba(244,175,37,0.55)',
              textAlign: 'right',
            }}>{time}</div>
          )}
        </div>
        {hover && (
          <div style={{
            position: 'absolute', right: 0, top: -14,
            display: 'flex', gap: 2, zIndex: 5,
          }}>
            {[
              { i: I.copy,  l: 'copy', fn: () => {} },
              { i: I.retry, l: 'retry', fn: onRetry },
              { i: I.fork,  l: 'fork',  fn: onFork },
            ].map((b) => (
              <button key={b.l} onClick={b.fn}
                      style={{
                        appearance: 'none', cursor: 'pointer',
                        width: 22, height: 22, borderRadius: 6,
                        background: 'var(--bg-base)',
                        border: '0.5px solid var(--glass-edge)',
                        color: 'var(--ink-tertiary)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                      }}>
                {b.i}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── 7. LiveAgentBubble — phantom message with rich content + actions ────────
function LiveAgentBubble({ thinking, intent, tools, text, shown, card, suggestions,
                            time, status, onAction, badge }) {
  const [hover, setHover] = React.useState(false);
  const showCard = card && (!text || shown >= text.length);
  return (
    <div className="fade-in"
         onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
         style={{ padding: '4px 0', position: 'relative' }}>
      {intent && <IntentPreview intent={intent} />}
      {(thinking || tools || text || card) && (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          {/* tiny phantom dot */}
          <div style={{
            width: 22, height: 22, borderRadius: '50%', flexShrink: 0, marginTop: 4,
            background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
            boxShadow: '0 0 6px var(--gold-glow)',
            animation: status === 'streaming' ? 'phantom-pulse-glow 0.6s ease-in-out infinite' :
                       status === 'thinking' ? 'phantom-pulse-glow 1.4s ease-in-out infinite' :
                       'none',
          }} />
          <div style={{ flex: 1, minWidth: 0, maxWidth: 'calc(100% - 30px)' }}>
            <div className="glass" style={{
              padding: '10px 14px',
              borderRadius: '18px 18px 18px 4px',
              background: 'var(--glass-tint)',
              border: '0.5px solid var(--glass-edge)',
            }}>
              {badge && (
                <div className="mono" style={{
                  fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
                  textTransform: 'uppercase', marginBottom: 6,
                }}>{badge}</div>
              )}
              {thinking && <ThinkingBar steps={thinking.steps} duration={thinking.duration} />}
              {tools && tools.map((t, i) => (
                <ToolCallInline key={i} {...t} />
              ))}
              {text && <StreamingText text={text} shown={shown == null ? text.length : shown} />}
              {time && shown >= (text?.length || 0) && (
                <div className="mono" style={{
                  marginTop: 6, fontSize: 8.5, color: 'var(--ink-tertiary)',
                }}>{time}</div>
              )}
            </div>
            {showCard && (
              <div style={{ marginTop: 6 }}>{card}</div>
            )}
            {hover && shown >= (text?.length || 0) && (
              <div style={{
                marginTop: 4, display: 'flex', gap: 4, flexWrap: 'wrap',
              }}>
                {[
                  { l: 'copy', fn: () => onAction?.('copy') },
                  { l: 'retry · інший кут', fn: () => onAction?.('retry') },
                  { l: 'fork', fn: () => onAction?.('fork') },
                  { l: 'до vault', fn: () => onAction?.('vault') },
                  { l: 'до phantom-os', fn: () => onAction?.('phantom-os') },
                ].map((a) => (
                  <button key={a.l} onClick={a.fn} style={{
                    appearance: 'none', cursor: 'pointer',
                    padding: '3px 7px', borderRadius: 999,
                    background: 'rgba(255,230,180,0.03)',
                    border: '0.5px solid var(--glass-edge)',
                    color: 'var(--ink-tertiary)',
                    font: '400 9.5px/1 var(--font-body)', letterSpacing: '0.02em',
                  }}>{a.l}</button>
                ))}
              </div>
            )}
            {suggestions && shown >= (text?.length || 0) && (
              <div style={{ marginTop: 8, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                {suggestions.map((s, i) => (
                  <button key={i} onClick={() => onAction?.('suggest', s)} style={{
                    appearance: 'none', cursor: 'pointer',
                    padding: '7px 11px', borderRadius: 999,
                    background: 'rgba(244,175,37,0.06)',
                    border: '0.5px solid rgba(244,175,37,0.20)',
                    color: 'var(--gold-soft)',
                    font: '400 11px/1 var(--font-body)', letterSpacing: '0.02em',
                    display: 'flex', alignItems: 'center', gap: 5,
                  }}>
                    <span style={{ color: 'var(--gold)', opacity: 0.7 }}>→</span>{s}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── 8. System notice (whispers between messages) ────────────────────────────
function SystemWhisper({ text }) {
  return (
    <div className="fade-in" style={{
      font: '300 10px/1.4 var(--font-mono)',
      color: 'var(--ink-tertiary)',
      textAlign: 'center', padding: '6px 0',
      letterSpacing: '0.04em', textWrap: 'pretty',
    }}>· {text} ·</div>
  );
}

// ─── 9. SlashPalette — / command picker ─────────────────────────────────────
function SlashPalette({ filter, onPick, onClose }) {
  const cmds = [
    { c: 'image',     d: 'намалюй щось',                ic: '◧', accent: '#F4AF25' },
    { c: 'search',    d: 'інтернет · 3 джерела',         ic: '⌕', accent: '#7CC0E8' },
    { c: 'memory',    d: 'твоя памʼять · vault',          ic: '◉', accent: '#7E5DA4' },
    { c: 'remind',    d: 'нагадай · час або умова',      ic: '◐', accent: '#F4AF25' },
    { c: 'translate', d: 'переклади',                     ic: '⇆', accent: '#9DCC9F' },
    { c: 'code',      d: 'напиши код · sandbox',          ic: '⟨⟩', accent: '#A7967D' },
    { c: 'math',      d: 'обчисли · покажи кроки',         ic: '∑', accent: '#A7967D' },
    { c: 'draw',      d: 'намалюй · canvas · разом',      ic: '✎', accent: '#FF8B6E' },
    { c: 'meet',      d: 'створи зустріч · з phantom-ами', ic: '◫', accent: '#F4AF25' },
    { c: 'vault',     d: 'поклади у сховище',                ic: '◊', accent: '#7E5DA4' },
    { c: 'thread',    d: 'нова нитка · з контекстом',         ic: '⊕', accent: '#A7967D' },
    { c: 'silent',    d: 'режим ghost · нічого не зберігати', ic: '◌', accent: '#7E5DA4' },
    { c: 'rewind',    d: 'повернутись · до моменту',          ic: '↶', accent: '#7CC0E8' },
    { c: 'voice',     d: 'тільки голосом',                   ic: '◐', accent: '#F4AF25' },
    { c: 'agent',     d: 'дай задачу · фантом сам зробить', ic: '⚙', accent: '#9DCC9F' },
  ].filter((c) => !filter || c.c.startsWith(filter));
  return (
    <div className="glass panel fade-in" style={{
      position: 'absolute', left: 14, right: 14, bottom: 'calc(100% + 8px)',
      padding: 6, borderRadius: 14,
      maxHeight: 280, overflow: 'auto',
      borderColor: 'rgba(244,175,37,0.3)',
      boxShadow: '0 -8px 24px rgba(0,0,0,0.5)',
      zIndex: 20,
    }}>
      <div className="mono" style={{
        padding: '4px 8px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.18em', textTransform: 'uppercase',
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span>· команди · {cmds.length}</span>
        <span style={{ color: 'var(--ink-tertiary)' }}>esc</span>
      </div>
      {cmds.map((c, i) => (
        <button key={c.c} onClick={() => onPick(c.c)} style={{
          appearance: 'none', cursor: 'pointer', width: '100%',
          textAlign: 'left', padding: '6px 8px', borderRadius: 8,
          background: i === 0 ? 'rgba(244,175,37,0.08)' : 'transparent',
          border: '0.5px solid transparent',
          display: 'grid', gridTemplateColumns: '18px 60px 1fr',
          gap: 8, alignItems: 'baseline',
        }}>
          <span style={{
            width: 18, height: 18, borderRadius: 4,
            background: `${c.accent}1f`, color: c.accent,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '500 10px/1 var(--font-display)',
          }}>{c.ic}</span>
          <span className="mono" style={{
            fontSize: 10.5, color: 'var(--gold)', letterSpacing: '0.02em',
          }}>/{c.c}</span>
          <span style={{
            font: '400 11px/1.3 var(--font-body)', color: 'var(--ink-secondary)',
            textWrap: 'pretty',
          }}>{c.d}</span>
        </button>
      ))}
    </div>
  );
}

// ─── 10. MentionPalette — @ people picker ────────────────────────────────────
function MentionPalette({ filter, onPick }) {
  const people = [
    { id: 'olya',   name: 'оля',    role: 'designer',  paired: true,  color: '#FF8B6E', initials: 'О' },
    { id: 'oleh',   name: 'олег',   role: 'engineer',  paired: true,  color: '#7CA982', initials: 'Ол' },
    { id: 'bohdan', name: 'богдан', role: 'manager',   paired: true,  color: '#7CC0E8', initials: 'Б' },
    { id: 'anya',   name: 'аня',    role: 'друг',       paired: false, color: '#B49AD4', initials: 'А' },
    { id: 'mama',   name: 'мама',   role: 'сімʼя',      paired: true,  color: '#FFD370', initials: 'М' },
  ].filter((p) => !filter || p.name.toLowerCase().startsWith(filter.toLowerCase()) || p.id.startsWith(filter.toLowerCase()));
  return (
    <div className="glass panel fade-in" style={{
      position: 'absolute', left: 14, right: 14, bottom: 'calc(100% + 8px)',
      padding: 6, borderRadius: 14,
      maxHeight: 240, overflow: 'auto',
      borderColor: 'rgba(255,139,110,0.3)',
      zIndex: 20,
    }}>
      <div className="mono" style={{
        padding: '4px 8px', fontSize: 8.5, color: '#FF8B6E',
        letterSpacing: '0.18em', textTransform: 'uppercase',
      }}>· звʼязати · фантом × фантом · {people.length}</div>
      {people.map((p) => (
        <button key={p.id} onClick={() => onPick(p)} style={{
          appearance: 'none', cursor: 'pointer', width: '100%',
          textAlign: 'left', padding: '8px', borderRadius: 8,
          background: 'transparent', border: '0.5px solid transparent',
          display: 'flex', alignItems: 'center', gap: 10,
        }}>
          <span style={{
            width: 26, height: 26, borderRadius: '50%',
            background: `radial-gradient(circle at 35% 30%, ${p.color}, ${p.color}55)`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            font: '500 11px/1 var(--font-display)', color: '#fff',
            boxShadow: `0 0 6px ${p.color}66`,
          }}>{p.initials}</span>
          <div style={{ flex: 1 }}>
            <div style={{ font: '500 12px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{p.name}</div>
            <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em', marginTop: 2 }}>{p.role}</div>
          </div>
          {p.paired && (
            <span className="mono" style={{
              fontSize: 8, color: '#9DCC9F', letterSpacing: '0.1em',
              padding: '2px 6px', borderRadius: 999,
              background: 'rgba(157,204,159,0.08)',
              border: '0.5px solid rgba(157,204,159,0.25)',
            }}>· phantom</span>
          )}
        </button>
      ))}
    </div>
  );
}

// ─── 11. AttachmentMenu — + button menu ──────────────────────────────────────
function AttachmentMenu({ onPick }) {
  const items = [
    { k: 'camera',  l: 'камера',       d: 'phantom бачить', ic: I.camera, c: '#F4AF25' },
    { k: 'photo',   l: 'фото',         d: 'з галереї',       ic: I.gallery, c: '#FF8B6E' },
    { k: 'file',    l: 'файл',         d: 'будь-який формат', ic: I.file,    c: '#A7967D' },
    { k: 'loc',     l: 'локація',       d: 'зараз / live',    ic: I.loc,     c: '#7CA982' },
    { k: 'draw',    l: 'малюнок',       d: 'разом',           ic: I.draw,    c: '#7E5DA4' },
    { k: 'screen',  l: 'екран',         d: 'що ти бачиш',     ic: I.screen,  c: '#7CC0E8' },
  ];
  return (
    <div className="glass panel fade-in" style={{
      position: 'absolute', left: 14, bottom: 'calc(100% + 8px)',
      padding: 6, borderRadius: 14,
      borderColor: 'rgba(244,175,37,0.3)',
      zIndex: 20,
      width: 260,
    }}>
      <div className="mono" style={{
        padding: '4px 8px', fontSize: 8.5, color: 'var(--gold-soft)',
        letterSpacing: '0.18em', textTransform: 'uppercase',
      }}>· додати ·</div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4 }}>
        {items.map((it) => (
          <button key={it.k} onClick={() => onPick(it.k)} style={{
            appearance: 'none', cursor: 'pointer',
            padding: '8px 10px', borderRadius: 8,
            background: 'transparent', border: '0.5px solid var(--glass-edge)',
            display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3,
            textAlign: 'left',
          }}>
            <div style={{
              width: 22, height: 22, borderRadius: 5,
              background: `${it.c}1f`, color: it.c,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              marginBottom: 2,
            }}>{it.ic}</div>
            <span style={{ font: '500 11px/1.2 var(--font-body)', color: 'var(--ink-strong)' }}>{it.l}</span>
            <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>{it.d}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── 12. SmartSuggestions — anticipatory chips above composer ───────────────
function SmartSuggestions({ items, onPick }) {
  if (!items?.length) return null;
  return (
    <div style={{
      display: 'flex', gap: 4, marginBottom: 6, overflow: 'auto',
      scrollbarWidth: 'none',
    }}>
      {items.map((s, i) => (
        <button key={i} onClick={() => onPick(s)} style={{
          flexShrink: 0, appearance: 'none', cursor: 'pointer',
          padding: '6px 11px', borderRadius: 999,
          background: 'rgba(255,230,180,0.04)',
          border: '0.5px solid var(--glass-edge)',
          color: 'var(--ink-strong)', font: '400 11px/1 var(--font-body)',
          letterSpacing: '0.01em',
          display: 'flex', alignItems: 'center', gap: 5,
        }}>
          <span style={{ color: 'var(--gold-soft)', opacity: 0.6, fontSize: 9 }}>{s.kind === 'cmd' ? '/' : s.kind === 'mention' ? '@' : '·'}</span>
          {s.text}
        </button>
      ))}
    </div>
  );
}

// ─── 13. VoiceMode — full overlay voice conversation ────────────────────────
function VoiceMode({ onClose, audio = 0.4, transcribed = '', phantomSaying }) {
  const bars = React.useMemo(() => Array.from({ length: 80 }, (_, i) => Math.sin(i * 0.4) * 0.5 + 0.5), []);
  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 100,
      background: 'radial-gradient(ellipse at 50% 40%, #1a1108, #06040200)',
      backdropFilter: 'blur(8px)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.3s ease-out',
    }}>
      <div style={{ padding: '60px 22px 0', display: 'flex', justifyContent: 'space-between' }}>
        <span className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.32em',
          textTransform: 'uppercase',
        }}>· voice · live ·</span>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.04em',
        }}>звести × </button>
      </div>
      <div style={{
        flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
        position: 'relative',
      }}>
        <div style={{
          width: 220, height: 220, borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
          boxShadow: `0 0 ${30 + audio * 60}px var(--gold-glow)`,
          transform: `scale(${0.9 + audio * 0.18})`,
          transition: 'all 0.18s ease',
          animation: 'phantom-breathe 4s ease-in-out infinite',
        }} />
        {/* concentric pulse rings */}
        {[0, 0.4, 0.8].map((d, i) => (
          <div key={i} style={{
            position: 'absolute',
            width: 220 + i * 60, height: 220 + i * 60, borderRadius: '50%',
            border: '0.5px solid rgba(244,175,37,0.2)',
            animation: `phantom-pulse-ring 3s ease-out ${d}s infinite`,
            opacity: 0.5,
          }} />
        ))}
      </div>
      <div style={{
        padding: '20px 24px 60px', textAlign: 'center',
      }}>
        <div style={{
          font: '300 16px/1.5 Georgia, serif', color: 'var(--ink-strong)',
          fontStyle: 'italic', textWrap: 'pretty', minHeight: 40,
        }}>{phantomSaying || transcribed || '· слухаю ·'}</div>
        <div style={{
          marginTop: 18, display: 'flex', alignItems: 'center', justifyContent: 'center',
          gap: 1.5, height: 28,
        }}>
          {bars.map((b, i) => (
            <div key={i} style={{
              width: 2, height: `${10 + b * audio * 60}%`,
              background: 'var(--gold)', borderRadius: 1, opacity: 0.7,
            }} />
          ))}
        </div>
        <div className="mono" style={{
          marginTop: 18, fontSize: 9.5, color: 'var(--ink-tertiary)',
          letterSpacing: '0.16em',
        }}>· говори природно · я перебивати не буду ·</div>
      </div>
    </div>
  );
}

// ─── 14. Composer — text + ghost + slash + mention + attach + voice ──────────
function Composer({
  value, setValue, onSend, onVoiceOpen, attachments = [], onRemoveAttach,
  onSlashCommand, onMention, ghost, onAcceptGhost, suggestions, onSuggestionPick,
  onAttachPick, phase, audio = 0,
}) {
  const [palette, setPalette] = React.useState(null); // 'slash' | 'mention' | 'attach' | null
  const inputRef = React.useRef(null);
  const onChange = (e) => {
    const v = e.target.value;
    setValue(v);
    // detect / or @ at end of last word
    const last = v.split(/\s/).pop();
    if (last.startsWith('/')) setPalette('slash');
    else if (last.startsWith('@')) setPalette('mention');
    else setPalette(null);
  };
  const onKey = (e) => {
    if (e.key === 'Escape') setPalette(null);
    else if (e.key === 'Tab' && ghost) { e.preventDefault(); onAcceptGhost?.(); }
    else if (e.key === 'Enter' && !e.shiftKey && value.trim()) {
      e.preventDefault(); onSend(value); setPalette(null);
    }
  };
  const pickSlash = (cmd) => {
    onSlashCommand?.(cmd, value);
    setPalette(null);
    inputRef.current?.focus();
  };
  const pickMention = (p) => {
    onMention?.(p);
    setPalette(null);
    inputRef.current?.focus();
  };
  const lastWord = value.split(/\s/).pop();
  const filter = lastWord.startsWith('/') ? lastWord.slice(1)
               : lastWord.startsWith('@') ? lastWord.slice(1) : '';

  return (
    <div className="ui-chrome" style={{
      padding: '8px 14px 14px',
      background: 'linear-gradient(to top, rgba(14,10,5,0.95), rgba(14,10,5,0.7) 70%, rgba(14,10,5,0))',
      position: 'relative', zIndex: 10,
    }}>
      {/* palettes — float above */}
      {palette === 'slash'   && <SlashPalette  filter={filter} onPick={pickSlash}  onClose={() => setPalette(null)} />}
      {palette === 'mention' && <MentionPalette filter={filter} onPick={pickMention} />}
      {palette === 'attach'  && <AttachmentMenu onPick={(k) => { onAttachPick?.(k); setPalette(null); }} />}

      {/* smart suggestions row */}
      {suggestions && !palette && (
        <SmartSuggestions items={suggestions} onPick={(s) => onSuggestionPick?.(s)} />
      )}

      {/* attachments row */}
      {attachments.length > 0 && (
        <div style={{
          display: 'flex', gap: 4, marginBottom: 6, flexWrap: 'wrap',
        }}>
          {attachments.map((a, i) => (
            <div key={i} className="glass subtle" style={{
              padding: '4px 6px 4px 4px', borderRadius: 8,
              display: 'flex', alignItems: 'center', gap: 6,
              borderColor: 'rgba(244,175,37,0.25)',
              background: 'rgba(244,175,37,0.06)',
            }}>
              {/* mini thumb */}
              <div style={{
                width: 24, height: 24, borderRadius: 4, overflow: 'hidden', flexShrink: 0,
                background: a.kind === 'photo' ? 'linear-gradient(135deg, #f4af25, #2a1808)' :
                            a.kind === 'pdf' ? 'rgba(255,139,110,0.2)' :
                            'rgba(124,192,232,0.2)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '500 8px/1 var(--font-mono)', color: 'var(--ink-strong)',
              }}>{a.kind === 'pdf' ? 'PDF' : a.kind === 'photo' ? '◧' : '◐'}</div>
              <span style={{
                font: '400 10.5px/1 var(--font-body)', color: 'var(--ink-strong)',
                maxWidth: 90, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              }}>{a.name}</span>
              <button onClick={() => onRemoveAttach?.(i)} style={{
                appearance: 'none', border: 'none', background: 'transparent',
                color: 'var(--ink-tertiary)', cursor: 'pointer', padding: 2,
                display: 'flex',
              }}>{I.x}</button>
            </div>
          ))}
        </div>
      )}

      {/* the input field */}
      <div className="glass" style={{
        display: 'flex', alignItems: 'flex-end', gap: 6,
        padding: '4px 4px 4px 6px', borderRadius: 22,
        background: 'rgba(20,14,10,0.7)',
        border: `0.5px solid ${value ? 'rgba(244,175,37,0.30)' : 'var(--glass-edge)'}`,
        boxShadow: value ? '0 0 14px rgba(244,175,37,0.10)' : 'none',
        transition: 'all 0.2s',
      }}>
        <button onClick={() => setPalette((p) => p === 'attach' ? null : 'attach')} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
          background: palette === 'attach' ? 'rgba(244,175,37,0.15)' : 'transparent',
          color: palette === 'attach' ? 'var(--gold)' : 'var(--ink-secondary)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          marginBottom: 2,
        }}>{I.plus}</button>

        {/* the text field — with ghost completion overlay */}
        <div style={{ flex: 1, position: 'relative', minHeight: 32 }}>
          <textarea
            ref={inputRef} value={value} onChange={onChange} onKeyDown={onKey}
            rows={1} placeholder="що в тебе на думці, кирил?"
            style={{
              width: '100%', appearance: 'none', border: 'none', outline: 'none',
              background: 'transparent', resize: 'none',
              font: '400 13px/1.45 var(--font-body)',
              color: 'var(--ink-strong)', letterSpacing: '0.005em',
              padding: '8px 4px',
              minHeight: 32, maxHeight: 120,
            }} />
          {/* ghost completion */}
          {ghost && (
            <div style={{
              position: 'absolute', left: 4, top: 8,
              pointerEvents: 'none',
              font: '400 13px/1.45 var(--font-body)',
              color: 'transparent', letterSpacing: '0.005em',
              padding: '0 4px',
              maxWidth: '100%',
              overflow: 'hidden',
              whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            }}>
              <span style={{ color: 'transparent' }}>{value}</span>
              <span style={{ color: 'rgba(244,175,37,0.55)', fontStyle: 'italic' }}>{ghost}</span>
              <span className="mono" style={{
                marginLeft: 6, padding: '1px 4px', borderRadius: 3,
                background: 'rgba(244,175,37,0.10)',
                color: 'var(--gold)', fontSize: 8.5, letterSpacing: '0.06em',
                fontStyle: 'normal',
              }}>tab</span>
            </div>
          )}
        </div>

        {/* voice button */}
        <button onClick={onVoiceOpen} style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
          background: 'rgba(244,175,37,0.06)', color: 'var(--gold)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          marginBottom: 2,
        }}>{I.mic}</button>

        {/* send button */}
        <button onClick={() => value.trim() && onSend(value)} disabled={!value.trim()}
                style={{
                  appearance: 'none', cursor: value.trim() ? 'pointer' : 'default', border: 'none',
                  width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                  background: value.trim() ? 'var(--gold)' : 'transparent',
                  color: value.trim() ? '#1A140B' : 'var(--ink-tertiary)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  marginBottom: 2, opacity: value.trim() ? 1 : 0.4,
                  boxShadow: value.trim() ? '0 0 10px var(--gold-glow)' : 'none',
                  transition: 'all 0.2s',
                }}>
          {I.arrowUp}
        </button>
      </div>

      {/* mini hints under composer */}
      <div className="mono" style={{
        marginTop: 6, padding: '0 8px', display: 'flex', gap: 12,
        fontSize: 8, color: 'rgba(167,150,125,0.45)', letterSpacing: '0.06em',
      }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>{I.slash}/cmd</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>{I.at}@person</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>{I.hash}#thread</span>
        <span style={{ flex: 1 }} />
        <span>shift+enter — новий рядок</span>
      </div>
    </div>
  );
}

// keyframes — pulse ring for voice mode
if (typeof document !== 'undefined' && !document.getElementById('live-style')) {
  const s = document.createElement('style');
  s.id = 'live-style';
  s.textContent = `
    @keyframes phantom-pulse-ring {
      0% { transform: scale(0.95); opacity: 0.6; }
      100% { transform: scale(1.4); opacity: 0; }
    }
  `;
  document.head.appendChild(s);
}

Object.assign(window, {
  liveTime, I,
  LivePresenceHeader, ThinkingBar, ToolCallInline, IntentPreview,
  StreamingText, LiveUserBubble, LiveAgentBubble, SystemWhisper,
  SlashPalette, MentionPalette, AttachmentMenu, SmartSuggestions,
  VoiceMode, Composer,
});
