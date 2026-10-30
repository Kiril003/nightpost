/* lab-ai.jsx — advanced AI-flavored chat cards:
   AIReasoningTrace (multi-step agent thinking),
   ImageGenGrid (4 variations with seeds),
   VideoGenProgress (frame strip + scrubber),
   LiveTranscription (streaming words, speaker diarization),
   VoiceCallLive (active call with both waveforms),
   BreathMeditation (animated breath guide),
   ReceiptOCR (line items extracted). */

// ─── 1. AI Reasoning Trace — agent thinking out loud ─────────────────────────
function AIReasoningTrace({ question, steps, answer }) {
  const [expanded, setExpanded] = React.useState(true);
  const [running, setRunning] = React.useState(true);
  const ss = steps || [
    { kind: 'thought', t: 'спочатку зрозуміти запит. оператор хоче знати — чи варто переїздити сьогодні чи завтра.', dur: 240 },
    { kind: 'tool', tool: 'weather.now', q: 'lhasa · наступні 6 год', result: 'дощ → сніг 22:00, -2°c, вітер 18 км/г', dur: 312 },
    { kind: 'tool', tool: 'calendar.read', q: 'завтра 15 травня', result: '3 події · standup 09:00 · фокус 13—17', dur: 88 },
    { kind: 'thought', t: 'якщо їхати зараз — мокрий сніг + сильний вітер. ризик. якщо завтра — погода краща, але треба зрушити standup.', dur: 180 },
    { kind: 'tool', tool: 'attendees.poll', q: 'standup · перенести на 12:00?', result: '5 / 6 згодні · богдан не може', dur: 1400 },
    { kind: 'branch', label: 'два варіанти', children: [
      { kind: 'option', text: 'a) їхати сьогодні · ризик · 9 год дороги в негоду', score: 0.41 },
      { kind: 'option', text: 'b) перенести · виїхати завтра о 11 · standup о 10:00 без богдана', score: 0.78 },
    ]},
    { kind: 'thought', t: 'варіант b з більшою впевненістю. з богданом синхронізую асинхронно.', dur: 96 },
  ];

  React.useEffect(() => {
    if (!running) return;
    const id = setTimeout(() => setRunning(false), 2200);
    return () => clearTimeout(id);
  }, []);

  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <button onClick={() => setExpanded((x) => !x)} style={{
        appearance: 'none', cursor: 'pointer', textAlign: 'left',
        width: '100%', padding: '10px 14px',
        background: 'transparent', border: 'none',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        borderBottom: expanded ? '0.5px solid var(--glass-edge)' : 'none',
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: 6,
        }}>
          {running && <span style={{
            width: 5, height: 5, borderRadius: '50%', background: 'var(--gold)',
            animation: 'phantom-pulse-glow 1s ease-in-out infinite',
            boxShadow: '0 0 6px var(--gold-glow)',
          }} />}
          · міркую · {ss.length} кроків {running && '·  ' + (Date.now() % 100)}мс
        </div>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>{expanded ? '∧' : '∨'}</span>
      </button>
      {question && (
        <div style={{
          padding: '8px 14px',
          font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
          background: 'rgba(244,175,37,0.04)', fontStyle: 'italic', textWrap: 'pretty',
        }}>«{question}»</div>
      )}
      {expanded && (
        <div style={{ padding: '8px 14px 12px', position: 'relative' }}>
          <div style={{ position: 'absolute', left: 22, top: 8, bottom: 4, width: 0.5,
            background: 'var(--glass-edge)' }} />
          {ss.map((s, i) => (
            <div key={i} style={{
              position: 'relative', paddingLeft: 22, marginBottom: 8,
            }}>
              {/* node */}
              <div style={{
                position: 'absolute', left: 18.5, top: 4,
                width: 7, height: 7, borderRadius: '50%',
                background: s.kind === 'tool' ? '#7CC0E8' :
                            s.kind === 'branch' ? '#B49AD4' :
                            s.kind === 'option' ? '#7CA982' : 'var(--gold)',
                boxShadow: `0 0 6px ${s.kind === 'tool' ? '#7CC0E8' :
                                       s.kind === 'branch' ? '#B49AD4' :
                                       s.kind === 'option' ? '#7CA982' : 'var(--gold-glow)'}55`,
              }} />
              {s.kind === 'thought' && (
                <>
                  <div className="mono" style={{
                    fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.16em',
                    textTransform: 'uppercase', marginBottom: 2,
                  }}>· думка · {s.dur}мс</div>
                  <div style={{
                    font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink)',
                    textWrap: 'pretty', fontStyle: 'italic',
                  }}>{s.t}</div>
                </>
              )}
              {s.kind === 'tool' && (
                <>
                  <div className="mono" style={{
                    fontSize: 8, color: '#7CC0E8', letterSpacing: '0.16em',
                    textTransform: 'uppercase', marginBottom: 3,
                    display: 'flex', alignItems: 'center', gap: 6,
                  }}>
                    · інструмент · <span style={{ color: '#E8B770' }}>{s.tool}</span>
                    <span style={{ color: 'var(--ink-tertiary)' }}>· {s.dur}мс</span>
                  </div>
                  <div style={{
                    font: '300 10.5px/1.45 var(--font-mono)', color: 'var(--ink-secondary)',
                    background: 'rgba(0,0,0,0.18)', padding: '4px 8px', borderRadius: 6,
                    border: '0.5px solid var(--glass-edge)',
                  }}>
                    <span style={{ color: 'var(--ink-tertiary)' }}>q:</span> {s.q}
                  </div>
                  <div style={{
                    marginTop: 4, font: '400 10.5px/1.45 var(--font-mono)',
                    color: '#A3D3A5', paddingLeft: 8,
                    borderLeft: '1.5px solid rgba(157,204,159,0.4)',
                  }}>→ {s.result}</div>
                </>
              )}
              {s.kind === 'branch' && (
                <>
                  <div className="mono" style={{
                    fontSize: 8, color: '#B49AD4', letterSpacing: '0.16em',
                    textTransform: 'uppercase', marginBottom: 4,
                  }}>· гілка · {s.label}</div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                    {s.children.map((c, j) => (
                      <div key={j} style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        padding: '5px 8px', borderRadius: 6,
                        background: c.score > 0.65 ? 'rgba(157,204,159,0.08)' : 'rgba(255,230,180,0.02)',
                        border: `0.5px solid ${c.score > 0.65 ? 'rgba(157,204,159,0.3)' : 'var(--glass-edge)'}`,
                        gap: 8,
                      }}>
                        <span style={{
                          font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-strong)',
                          textWrap: 'pretty', flex: 1, minWidth: 0,
                        }}>{c.text}</span>
                        <span className="mono" style={{
                          fontSize: 9, letterSpacing: '0.04em', flexShrink: 0,
                          color: c.score > 0.65 ? 'var(--sage)' : 'var(--ink-tertiary)',
                          fontVariantNumeric: 'tabular-nums',
                        }}>
                          ✓ {(c.score * 100 | 0)}%
                        </span>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      )}
      {answer !== false && (
        <div style={{
          padding: '12px 14px',
          background: 'rgba(244,175,37,0.06)',
          borderTop: '0.5px solid rgba(244,175,37,0.18)',
        }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 5,
          }}>· відповідь</div>
          <div style={{
            font: '400 12.5px/1.5 var(--font-body)', color: 'var(--ink-strong)',
            textWrap: 'pretty',
          }}>
            {answer || 'не їхати сьогодні. переніс standup на 10:00, забронював таксі на завтра о 11. богдану надішлю запис.'}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── 2. Image-Gen Variations Grid (4 with seeds and prompts) ─────────────────
function ImageGenGrid({ prompt = 'lhasa o 19:30, листопад, шафран та сапфір', variants }) {
  const vs = variants || [
    { scene: 'mountain', seed: '0x8a3f' },
    { scene: 'night',    seed: '0x14e7' },
    { scene: 'sky',      seed: '0xb921' },
    { scene: 'abstract', seed: '0x2c04' },
  ];
  const [sel, setSel] = React.useState(0);
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '10px 14px 6px' }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', marginBottom: 4,
        }}>· image-gen · 4 варіанти · 8.4с</div>
        <div style={{
          font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink)',
          fontStyle: 'italic', textWrap: 'pretty',
        }}>«{prompt}»</div>
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2,
        padding: 2,
      }}>
        {vs.map((v, i) => (
          <button key={i} onClick={() => setSel(i)} style={{
            position: 'relative', appearance: 'none', cursor: 'pointer',
            padding: 0, border: 'none', background: 'transparent',
            aspectRatio: '1', overflow: 'hidden',
            borderRadius: 6,
            outline: i === sel ? '1.5px solid var(--gold)' : 'none',
            outlineOffset: i === sel ? -1.5 : 0,
            boxShadow: i === sel ? '0 0 12px var(--gold-glow)' : 'none',
            zIndex: i === sel ? 2 : 1,
          }}>
            <PainterlyPhoto scene={v.scene} ratio="1/1" />
            <div className="mono" style={{
              position: 'absolute', bottom: 4, left: 4,
              fontSize: 7.5, color: 'rgba(255,255,255,0.8)',
              background: 'rgba(0,0,0,0.45)',
              padding: '2px 5px', borderRadius: 3, letterSpacing: '0.06em',
              backdropFilter: 'blur(6px)',
            }}>{i + 1} · seed {v.seed}</div>
            {i === sel && (
              <div className="mono" style={{
                position: 'absolute', top: 4, right: 4,
                fontSize: 7.5, color: '#1A140B',
                background: 'var(--gold)', padding: '2px 5px',
                borderRadius: 3, letterSpacing: '0.08em', fontWeight: 600,
              }}>● вибрано</div>
            )}
          </button>
        ))}
      </div>
      <div style={{
        padding: '10px 14px', display: 'flex', gap: 6,
        borderTop: '0.5px solid var(--glass-edge)',
      }}>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '8px', borderRadius: 10,
          background: 'rgba(244,175,37,0.94)', border: 'none',
          color: '#1A140B', font: '500 11px/1 var(--font-display)',
        }}>↓ зберегти #{sel+1}</button>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '8px', borderRadius: 10,
          background: 'rgba(255,230,180,0.04)', border: '0.5px solid var(--glass-edge)',
          color: 'var(--ink-strong)', font: '500 11px/1 var(--font-display)',
        }}>↻ ще 4</button>
        <button style={{
          appearance: 'none', cursor: 'pointer',
          padding: '8px 12px', borderRadius: 10,
          background: 'rgba(255,230,180,0.04)', border: '0.5px solid var(--glass-edge)',
          color: 'var(--ink-secondary)', font: '400 11px/1 var(--font-mono)',
        }}>upscale 4×</button>
      </div>
    </div>
  );
}

// ─── 3. Video-Gen Progress (frame strip materializing) ───────────────────────
function VideoGenProgress({ prompt = 'дрон над монастирем drepung, ранок, туман', frames = 16, ready = 9 }) {
  const [r, setR] = React.useState(ready);
  React.useEffect(() => {
    const id = setInterval(() => setR((x) => x < frames ? x + 1 : x), 700);
    return () => clearInterval(id);
  }, [frames]);
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '6px 0', borderRadius: 16,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 4,
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span>· video-gen · 5s · 24fps</span>
        <span style={{ color: 'var(--ink-tertiary)' }}>· {r} / {frames} кадрів</span>
      </div>
      <div style={{
        font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink)',
        fontStyle: 'italic', textWrap: 'pretty', marginBottom: 10,
      }}>«{prompt}»</div>
      <div style={{
        display: 'grid', gridTemplateColumns: `repeat(${frames}, 1fr)`,
        gap: 2, marginBottom: 10,
      }}>
        {Array.from({ length: frames }).map((_, i) => {
          const done = i < r;
          const next = i === r;
          const sceneOpts = ['mountain', 'sky', 'forest', 'night', 'mountain'];
          return (
            <div key={i} style={{
              aspectRatio: '16/9', borderRadius: 2, overflow: 'hidden',
              position: 'relative',
              outline: next ? '1px solid var(--gold)' : 'none',
            }}>
              {done ? (
                <PainterlyPhoto scene={sceneOpts[i % sceneOpts.length]} ratio="auto" />
              ) : (
                <div style={{
                  width: '100%', height: '100%',
                  background: next
                    ? `repeating-linear-gradient(45deg, rgba(244,175,37,0.18) 0 3px, rgba(244,175,37,0.04) 3px 6px)`
                    : `repeating-linear-gradient(0deg, rgba(255,230,180,0.04) 0 1px, transparent 1px 3px)`,
                  animation: next ? 'phantom-frame-pulse 1.4s ease-in-out infinite' : 'none',
                }} />
              )}
            </div>
          );
        })}
      </div>
      <div style={{
        height: 2, background: 'rgba(255,230,180,0.06)', borderRadius: 1, overflow: 'hidden', marginBottom: 8,
      }}>
        <div style={{
          width: `${r/frames*100}%`, height: '100%',
          background: 'linear-gradient(to right, var(--gold-soft), var(--gold))',
          boxShadow: '0 0 6px var(--gold-glow)',
          transition: 'width 0.4s ease',
        }} />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>sora-turbo · 720p</span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)' }}>{(r/frames*100|0)}% · {((frames-r)*0.7)|0}с</span>
      </div>
    </div>
  );
}

// ─── 4. Live Transcription (streaming words, speaker labels, sentiment) ──────
function LiveTranscription({ title = 'розмова з olya · live', speakers, paragraphs }) {
  const sps = speakers || [
    { id: 'A', name: 'оля',    color: '#FF8B6E' },
    { id: 'B', name: 'кирил',  color: '#F4AF25' },
  ];
  const ps = paragraphs || [
    { sp: 'A', t: '00:14', text: 'тож, ми йдемо завтра до нотаріуса?', sentiment: 'neutral', confidence: 0.96 },
    { sp: 'B', t: '00:17', text: 'так. в одинадцять. документи я вже зібрав.', sentiment: 'positive', confidence: 0.98 },
    { sp: 'A', t: '00:23', text: 'добре. я хвилююсь, чесно.', sentiment: 'anxious', confidence: 0.91 },
    { sp: 'B', t: '00:27', text: 'розумію. але це лише папір. ми давно вирішили.', sentiment: 'reassure', confidence: 0.93 },
    { sp: 'A', t: '00:34', text: 'знаю · знаю. просто завтра — це вже', sentiment: 'anxious', confidence: 0.88, streaming: true },
  ];
  const sentColor = { positive: '#9DCC9F', neutral: 'var(--ink-tertiary)', anxious: '#FF8B6E', reassure: '#7CC0E8' };
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{
        padding: '10px 14px', borderBottom: '0.5px solid var(--glass-edge)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div className="mono" style={{
          fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{
            width: 6, height: 6, borderRadius: '50%', background: 'var(--coral)',
            animation: 'phantom-pulse-glow 1.2s ease-in-out infinite',
            boxShadow: '0 0 6px var(--coral)',
          }} />
          · live · {title}
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          {sps.map((s) => (
            <span key={s.id} style={{
              display: 'inline-flex', alignItems: 'center', gap: 4,
              padding: '2px 7px 2px 4px', borderRadius: 999,
              background: `${s.color}1f`,
              border: `0.5px solid ${s.color}66`,
            }}>
              <span style={{ width: 5, height: 5, borderRadius: '50%', background: s.color }} />
              <span className="mono" style={{ fontSize: 8.5, color: s.color, letterSpacing: '0.04em' }}>{s.name}</span>
            </span>
          ))}
        </div>
      </div>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {ps.map((p, i) => {
          const sp = sps.find((s) => s.id === p.sp);
          return (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '40px 1fr', gap: 8 }}>
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 1 }}>
                <span className="mono" style={{
                  fontSize: 9.5, color: sp.color, letterSpacing: '0.06em',
                }}>{sp.name}</span>
                <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)' }}>{p.t}</span>
              </div>
              <div>
                <div style={{
                  font: '400 12px/1.5 var(--font-body)',
                  color: 'var(--ink-strong)', textWrap: 'pretty',
                }}>
                  {p.text}
                  {p.streaming && (
                    <span style={{
                      display: 'inline-block',
                      width: 5, height: 12, marginLeft: 4,
                      background: 'var(--gold)',
                      verticalAlign: 'middle',
                      animation: 'phantom-caret 0.9s steps(2, end) infinite',
                    }} />
                  )}
                </div>
                <div style={{ display: 'flex', gap: 8, marginTop: 3, alignItems: 'center' }}>
                  <span style={{
                    display: 'inline-flex', alignItems: 'center', gap: 3,
                    padding: '1px 6px', borderRadius: 999, fontSize: 8,
                    background: `${sentColor[p.sentiment]}1c`,
                    color: sentColor[p.sentiment],
                  }} className="mono">
                    · {p.sentiment}
                  </span>
                  <span className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
                    · confidence {(p.confidence*100|0)}%
                  </span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
      <div style={{
        padding: '8px 14px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        font: '400 10.5px/1.4 var(--font-body)', color: 'var(--gold-soft)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{
          fontSize: 8, letterSpacing: '0.18em', textTransform: 'uppercase',
          color: 'var(--gold)', marginRight: 5,
        }}>· phantom</span>
        вона нервується. не поспішай — дай їй договорити перед тим як ствердити.
      </div>
    </div>
  );
}

// ─── 5. Voice Call Live (both waveforms, mute, hold) ─────────────────────────
function VoiceCallLive({ name = 'оля', duration = 184, audio = 0.5, theirs = 0.6 }) {
  const [d, setD] = React.useState(duration);
  React.useEffect(() => {
    const id = setInterval(() => setD((x) => x + 1), 1000);
    return () => clearInterval(id);
  }, []);
  const mm = (s) => `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`;
  const bars = React.useMemo(() => Array.from({ length: 40 }, () => Math.random()), []);
  return (
    <div className="glass accent fade-in" style={{
      padding: '14px 14px', margin: '6px 0', borderRadius: 16,
      background: `
        radial-gradient(ellipse at 30% 20%, rgba(157,204,159,0.10), transparent 60%),
        var(--glass-tint)`,
      borderColor: 'rgba(157,204,159,0.3)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
          <span style={{
            width: 6, height: 6, borderRadius: '50%', background: 'var(--sage)',
            animation: 'phantom-pulse-glow 1.4s ease-in-out infinite',
            boxShadow: '0 0 6px var(--sage)',
          }} />
          <span className="mono" style={{
            fontSize: 9, color: '#9DCC9F', letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}>· розмова · повз phantom-os ·</span>
        </div>
        <span className="mono" style={{
          fontSize: 11, color: 'var(--ink-strong)', letterSpacing: '0.04em',
          fontVariantNumeric: 'tabular-nums',
        }}>{mm(d)}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
        <div style={{
          width: 56, height: 56, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, #FF8B6E, #A03020 60%)`,
          border: '0.5px solid rgba(255,230,180,0.15)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0,
          boxShadow: theirs > 0.3 ? '0 0 0 4px rgba(255,139,110,0.2), 0 0 16px rgba(255,139,110,0.3)' : 'none',
          transition: 'box-shadow 0.2s',
        }}>
          <span style={{ font: '500 20px/1 var(--font-display)', color: '#fff' }}>{name[0]}</span>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ font: '500 14px/1.2 var(--font-display)', color: 'var(--ink-strong)' }}>{name}</div>
          <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', marginTop: 3 }}>
            kbp · ясно · добра якість
          </div>
        </div>
      </div>
      {/* both waveforms — facing each other */}
      <div style={{
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12,
        padding: '10px 0', borderTop: '0.5px solid var(--glass-edge)',
        borderBottom: '0.5px solid var(--glass-edge)',
      }}>
        <div>
          <div className="mono" style={{ fontSize: 8, color: '#FF8B6E', letterSpacing: '0.14em',
            textTransform: 'uppercase', marginBottom: 5 }}>· оля</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 1, height: 26, justifyContent: 'flex-end' }}>
            {bars.map((b, i) => (
              <div key={i} style={{
                width: 2, height: `${(8 + b * theirs * 70)}%`,
                background: '#FF8B6E', opacity: 0.7,
                borderRadius: 1,
              }} />
            ))}
          </div>
        </div>
        <div>
          <div className="mono" style={{ fontSize: 8, color: '#F4AF25', letterSpacing: '0.14em',
            textTransform: 'uppercase', marginBottom: 5 }}>· ти</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 1, height: 26 }}>
            {bars.map((b, i) => (
              <div key={i} style={{
                width: 2, height: `${(8 + b * audio * 70)}%`,
                background: 'var(--gold)', opacity: 0.7,
                borderRadius: 1,
              }} />
            ))}
          </div>
        </div>
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 6, marginTop: 12,
      }}>
        {[
          { l: 'mute', i: '◐', c: 'var(--ink-strong)' },
          { l: 'hold', i: '⏸', c: 'var(--ink-strong)' },
          { l: 'transcribe', i: 'T', c: 'var(--gold-soft)' },
          { l: 'кінець', i: '✕', c: '#fff', bg: 'var(--coral)' },
        ].map((b) => (
          <button key={b.l} style={{
            appearance: 'none', cursor: 'pointer',
            padding: '8px', borderRadius: 10,
            background: b.bg || 'rgba(255,230,180,0.04)',
            border: b.bg ? 'none' : '0.5px solid var(--glass-edge)',
            color: b.c, font: '500 10px/1.2 var(--font-body)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
          }}>
            <span style={{ fontSize: 14 }}>{b.i}</span>
            <span style={{ fontSize: 9, letterSpacing: '0.04em' }}>{b.l}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── 6. Breath Meditation (animated breath guide) ────────────────────────────
function BreathMeditation({ pattern = '4-7-8', cycle = 4, totalCycles = 6 }) {
  const [phase, setPhase] = React.useState('in');
  const [progress, setProgress] = React.useState(0);
  const [done, setDone] = React.useState(0);
  React.useEffect(() => {
    const phases = [
      { p: 'in',   d: 4 },
      { p: 'hold', d: 7 },
      { p: 'out',  d: 8 },
    ];
    let pi = 0, t = 0, raf;
    const tick = () => {
      t += 1/60;
      const cur = phases[pi];
      setPhase(cur.p);
      setProgress(t / cur.d);
      if (t >= cur.d) {
        t = 0; pi = (pi + 1) % phases.length;
        if (pi === 0) setDone((d) => d + 1);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  const scale = phase === 'in' ? 0.55 + progress * 0.45
              : phase === 'hold' ? 1.0
              : 1.0 - progress * 0.45;
  const label = phase === 'in' ? 'вдих' : phase === 'hold' ? 'тримай' : 'видих';
  const sub = phase === 'in' ? '4с' : phase === 'hold' ? '7с' : '8с';

  return (
    <div className="glass fade-in" style={{
      padding: '16px 14px', margin: '6px 0', borderRadius: 16,
      background: `
        radial-gradient(ellipse at 50% 30%, rgba(124,192,232,0.08), transparent 65%),
        var(--glass-tint)`,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <span className="mono" style={{
          fontSize: 9, color: '#7CC0E8', letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}>· дихання · {pattern}</span>
        <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)' }}>
          цикл {done + 1} / {totalCycles}
        </span>
      </div>
      <div style={{
        height: 180, position: 'relative',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        {/* breath circle */}
        <div style={{
          position: 'absolute',
          width: 140, height: 140, borderRadius: '50%',
          background: `radial-gradient(circle, rgba(124,192,232,0.3), rgba(124,192,232,0.05) 60%, transparent 75%)`,
          transform: `scale(${scale})`,
          transition: 'transform 0.06s linear',
          filter: 'blur(2px)',
        }} />
        <div style={{
          position: 'absolute',
          width: 100, height: 100, borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, rgba(124,192,232,0.6), rgba(74,122,160,0.3) 60%, transparent)`,
          border: '0.5px solid rgba(124,192,232,0.4)',
          transform: `scale(${scale})`,
          transition: 'transform 0.06s linear',
          boxShadow: `0 0 ${30 * scale}px rgba(124,192,232,0.4)`,
        }} />
        {/* label center */}
        <div style={{
          position: 'relative',
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          textAlign: 'center', zIndex: 2,
        }}>
          <div style={{
            font: '300 22px/1 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '0.01em',
          }}>{label}</div>
          <div className="mono" style={{ fontSize: 10, color: 'var(--ink-secondary)', letterSpacing: '0.08em' }}>
            · {sub} ·
          </div>
        </div>
        {/* phase ring */}
        <svg viewBox="0 0 200 200" style={{ position: 'absolute', inset: 'auto', width: 200, height: 200, pointerEvents: 'none' }}>
          <circle cx="100" cy="100" r="90" fill="none" stroke="rgba(255,230,180,0.06)" strokeWidth="1.5" />
          <circle cx="100" cy="100" r="90" fill="none"
                  stroke="#7CC0E8" strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeDasharray={`${progress * 565} 565`}
                  transform="rotate(-90 100 100)"
                  style={{ filter: 'drop-shadow(0 0 4px #7CC0E8)' }} />
        </svg>
      </div>
      <div style={{
        marginTop: 6, display: 'flex', justifyContent: 'center', gap: 5,
      }}>
        {['in', 'hold', 'out'].map((p) => (
          <div key={p} style={{
            padding: '3px 8px', borderRadius: 999,
            background: phase === p ? 'rgba(124,192,232,0.2)' : 'rgba(255,230,180,0.02)',
            border: `0.5px solid ${phase === p ? '#7CC0E8' : 'var(--glass-edge)'}`,
            font: '400 9px/1 var(--font-mono)',
            color: phase === p ? '#7CC0E8' : 'var(--ink-tertiary)',
            letterSpacing: '0.06em',
          }}>{p === 'in' ? 'вдих 4' : p === 'hold' ? 'тримай 7' : 'видих 8'}</div>
        ))}
      </div>
      <div style={{
        marginTop: 10, padding: '6px 8px', borderRadius: 6,
        background: 'rgba(124,192,232,0.04)',
        font: '400 10.5px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
        textAlign: 'center', textWrap: 'pretty',
      }}>
        твій HRV знизився за останню годину · 4-7-8 поверне його за 6 циклів
      </div>
    </div>
  );
}

// ─── 7. Receipt OCR (line items extracted from photo) ────────────────────────
function ReceiptOCR({ vendor = 'local roasters', when = '14 травня · 14:22',
                       address = 'вул. ярославів вал 11', items, total }) {
  const it = items || [
    { q: '1', t: 'cappuccino · double', p: '2.40', match: 0.99 },
    { q: '1', t: 'croissant · almond',  p: '1.80', match: 0.97 },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ display: 'flex', gap: 0 }}>
        {/* receipt photo */}
        <div style={{
          width: 100, flexShrink: 0, position: 'relative',
          background: 'linear-gradient(165deg, #f5e8d3 0%, #d8c598 100%)',
          padding: 8,
        }}>
          <div style={{ height: 2.5, width: '55%', background: '#1a140b', marginBottom: 4 }} />
          <div style={{ height: 1, width: '80%', background: '#5d5142', opacity: 0.7, marginBottom: 2 }} />
          <div style={{ height: 1, width: '60%', background: '#5d5142', opacity: 0.7, marginBottom: 6 }} />
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} style={{ height: 0.8, width: `${85 - i*4}%`, background: '#5d5142', opacity: 0.6, marginBottom: 2 }} />
          ))}
          <div style={{ height: 2, width: '70%', background: '#3a2818', marginTop: 8 }} />
          {/* OCR scan line */}
          <div style={{
            position: 'absolute', left: 0, right: 0, top: '60%',
            height: 1, background: 'linear-gradient(to right, transparent, #F4AF25, transparent)',
            boxShadow: '0 0 8px var(--gold-glow)',
            animation: 'phantom-ocr-scan 2.4s ease-in-out infinite',
          }} />
          {/* corner crop markers */}
          {[[0,0],[1,0],[0,1],[1,1]].map(([x,y], i) => (
            <div key={i} style={{
              position: 'absolute',
              [x ? 'right' : 'left']: 4, [y ? 'bottom' : 'top']: 4,
              width: 8, height: 8,
              borderTop: !y ? '1px solid var(--gold)' : 'none',
              borderBottom: y ? '1px solid var(--gold)' : 'none',
              borderLeft: !x ? '1px solid var(--gold)' : 'none',
              borderRight: x ? '1px solid var(--gold)' : 'none',
              filter: 'drop-shadow(0 0 3px var(--gold-glow))',
            }} />
          ))}
        </div>
        {/* extracted */}
        <div style={{ flex: 1, padding: '10px 12px' }}>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', marginBottom: 4,
          }}>· чек · ocr розпізнано</div>
          <div style={{
            font: '500 12.5px/1.2 var(--font-display)', color: 'var(--ink-strong)',
          }}>{vendor}</div>
          <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', marginTop: 2, letterSpacing: '0.04em' }}>
            {address} · {when}
          </div>
          <div style={{
            marginTop: 10, paddingTop: 8,
            borderTop: '0.5px solid var(--glass-edge)',
          }}>
            {it.map((row, i) => (
              <div key={i} style={{
                display: 'grid', gridTemplateColumns: '16px 1fr 36px 16px',
                gap: 6, alignItems: 'baseline', padding: '3px 0',
                font: '400 11px/1.3 var(--font-mono)', color: 'var(--ink-strong)',
                fontVariantNumeric: 'tabular-nums',
              }}>
                <span style={{ color: 'var(--ink-tertiary)' }}>{row.q}×</span>
                <span style={{ fontFamily: 'var(--font-body)' }}>{row.t}</span>
                <span style={{ textAlign: 'right' }}>{row.p}</span>
                <span title={`${(row.match*100|0)}%`} style={{
                  fontSize: 8, color: row.match > 0.95 ? 'var(--sage)' : 'var(--gold-soft)',
                  textAlign: 'right',
                }}>✓</span>
              </div>
            ))}
          </div>
          <div style={{
            marginTop: 6, paddingTop: 6,
            borderTop: '0.5px solid var(--glass-edge-strong)',
            display: 'flex', justifyContent: 'space-between',
            font: '500 12px/1 var(--font-display)', color: 'var(--gold)',
            fontVariantNumeric: 'tabular-nums',
          }}>
            <span>усього</span>
            <span>{total || '4.20'}</span>
          </div>
        </div>
      </div>
      <div style={{
        padding: '6px 12px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'flex', justifyContent: 'space-between',
      }}>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-secondary)', letterSpacing: '0.06em' }}>
          · vault · кафе · травень
        </span>
        <span className="mono" style={{ fontSize: 8.5, color: 'var(--gold-soft)' }}>· категоризовано</span>
      </div>
    </div>
  );
}

// ─── keyframes ────────────────────────────────────────────────────────────────
if (typeof document !== 'undefined' && !document.getElementById('lab-ai-style')) {
  const s = document.createElement('style');
  s.id = 'lab-ai-style';
  s.textContent = `
    @keyframes phantom-frame-pulse { 0%,100% { opacity: 0.3; } 50% { opacity: 0.8; } }
    @keyframes phantom-caret { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
    @keyframes phantom-ocr-scan { 0% { top: 8%; } 50% { top: 92%; } 100% { top: 8%; } }
  `;
  document.head.appendChild(s);
}

Object.assign(window, {
  AIReasoningTrace, ImageGenGrid, VideoGenProgress,
  LiveTranscription, VoiceCallLive, BreathMeditation, ReceiptOCR,
});
