/* surfaces.jsx — Additional core + frontier surfaces:
   Onboarding (6-step morph), Pair flow (AR camera), Vault Gate,
   Decision Diary, Motion Catalog (named animations from brief XIV),
   Time-shifted Help (past-self notes), DialogueThrum overlay.
   All copy in Familiar voice — Ukrainian ти-form. */

// ─── Familiar TTS (browser SpeechSynthesis) ──────────────────────────────────
function speakUk(text) {
  if (!window.speechSynthesis) return;
  try {
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'uk-UA'; u.rate = 0.92; u.pitch = 0.85; u.volume = 0.9;
    const voices = window.speechSynthesis.getVoices();
    const uk = voices.find((v) => v.lang.startsWith('uk')) ||
               voices.find((v) => v.lang.startsWith('ru')) ||
               voices.find((v) => v.default);
    if (uk) u.voice = uk;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(u);
  } catch (e) { /* ignore */ }
}
function playTone(freq = 440, ms = 200, type = 'sine', vol = 0.07) {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = window.__phantomAudio || (window.__phantomAudio = new Ctx());
    const osc = ctx.createOscillator(); const g = ctx.createGain();
    osc.type = type; osc.frequency.value = freq;
    g.gain.value = 0;
    osc.connect(g); g.connect(ctx.destination);
    const t = ctx.currentTime;
    g.gain.linearRampToValueAtTime(vol, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + ms / 1000);
    osc.start(t); osc.stop(t + ms / 1000 + 0.02);
  } catch (e) { /* ignore */ }
}
function chimeBell() { playTone(523, 1500, 'sine', 0.08); setTimeout(() => playTone(784, 1200, 'sine', 0.05), 120); }
function crystalNotice() { playTone(880, 400, 'triangle', 0.05); }
function spikeAlert() { playTone(247, 300, 'sawtooth', 0.06); setTimeout(() => playTone(208, 300, 'sawtooth', 0.04), 80); }

// ─── Onboarding 60-second journey ────────────────────────────────────────────
function Onboarding({ onDone, orbTier, motionScale, breathPeriod, soundOn }) {
  const [step, setStep] = React.useState(0);
  const [name, setName] = React.useState('');
  const [pin, setPin] = React.useState('');
  const [progress, setProgress] = React.useState(0);

  const next = () => setStep((s) => s + 1);

  // CreatingProfile auto-advance
  React.useEffect(() => {
    if (step !== 2) return;
    let p = 0;
    const id = setInterval(() => {
      p += 0.018;
      setProgress(p);
      if (p >= 1) { clearInterval(id); setTimeout(next, 400); }
    }, 80);
    return () => clearInterval(id);
  }, [step]);

  // Familiar narrates each step
  React.useEffect(() => {
    if (!soundOn) return;
    const lines = [
      'вітаю · як до тебе звертатись?',
      `привіт ${name || 'оператор'}`,
      null, // creating
      'спарити з phantom · ти не сам',
      'мені потрібно бачити твій екран · щоб розуміти контекст',
      'готовий · тут і зараз',
    ];
    if (lines[step]) setTimeout(() => speakUk(lines[step]), 350);
  }, [step, soundOn, name]);

  const orbState = step === 2 ? 'DIALOGUE' : step === 3 ? 'SENTINEL' : step === 5 ? 'FOCUS' : 'DREAM';
  const orbSize = step === 4 ? 200 : step === 2 ? 180 : 140;

  return (
    <div style={{
      position: 'absolute', inset: 0, overflow: 'hidden',
      display: 'flex', flexDirection: 'column',
    }}>
      <LiveWallpaper variant="sparks" state={orbState} />
      {/* dwelling orb — center, morphs across steps */}
      <div style={{
        position: 'absolute', left: '50%',
        top: step >= 4 ? '22%' : step === 2 ? '32%' : '28%',
        transform: 'translate(-50%, -50%)',
        transition: 'top 0.9s cubic-bezier(.4,.1,.2,1)',
      }}>
        <Orb state={orbState} tier={orbTier} size={orbSize}
             audio={step === 2 ? 0.35 : 0.08}
             motionScale={motionScale} breathPeriod={breathPeriod} />
      </div>

      <div style={{
        marginTop: 'auto', padding: '0 28px 50px',
        display: 'flex', flexDirection: 'column', gap: 18,
        position: 'relative', zIndex: 10,
      }}>
        {step === 0 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            <Whisper>вітаю · як до тебе звертатись?</Whisper>
            <CapsuleInput value={name} onChange={setName} placeholder="імʼя" autoFocus />
            <PrimaryButton disabled={!name.trim()} onClick={next}>далі</PrimaryButton>
          </div>
        )}
        {step === 1 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            <Whisper>привіт, {name.toLowerCase()}</Whisper>
            <Whisper sub>захисти присутність · 4 цифри</Whisper>
            <PinKeypad value={pin} onChange={setPin} onComplete={() => setTimeout(next, 250)} />
          </div>
        )}
        {step === 2 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'center' }}>
            <Whisper>дихаю глибше · творю присутність</Whisper>
            <Whisper sub>{
              progress < 0.3 ? 'зчитую сенсори' :
              progress < 0.55 ? 'звʼязую памʼять' :
              progress < 0.8 ? 'настроюю голос' :
              'майже готово'
            }</Whisper>
          </div>
        )}
        {step === 3 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <Whisper>спарити з phantom?</Whisper>
            <ul style={{
              listStyle: 'none', padding: 0, margin: 0,
              display: 'flex', flexDirection: 'column', gap: 8,
            }}>
              {[
                'живий ai з сенсорів radxa',
                'vault для ключів і памʼяті',
                'тактична карта та маршрути',
                'голос styletts2 — твій або системний',
              ].map((b) => (
                <li key={b} style={{
                  display: 'flex', alignItems: 'baseline', gap: 10,
                  font: '400 12px/1.45 var(--font-body)', color: 'var(--ink)',
                }}>
                  <span style={{ color: 'var(--gold)', flexShrink: 0 }}>·</span>
                  <span style={{ textWrap: 'pretty' }}>{b}</span>
                </li>
              ))}
            </ul>
            <Whisper sub>без pair теж працюю · на on-device моделях</Whisper>
            <div style={{ display: 'flex', gap: 8 }}>
              <PrimaryButton onClick={next}>сканувати qr</PrimaryButton>
              <GhostButton onClick={() => setStep(5)}>пізніше</GhostButton>
            </div>
          </div>
        )}
        {step === 4 && <PermissionStep onAllow={next} onSkip={next} />}
        {step === 5 && (
          <div className="bloom-in" style={{ display: 'flex', flexDirection: 'column', gap: 18, alignItems: 'center' }}>
            <Whisper>готовий</Whisper>
            <Whisper sub>тут і зараз</Whisper>
            <PrimaryButton onClick={() => { if (soundOn) chimeBell(); onDone(); }}>увійти</PrimaryButton>
          </div>
        )}
      </div>
    </div>
  );
}

function Whisper({ children, sub }) {
  return (
    <div style={{
      font: sub ? '300 11.5px/1.55 var(--font-mono)' : '500 18px/1.4 var(--font-display)',
      color: sub ? 'var(--ink-tertiary)' : 'var(--ink-strong)',
      letterSpacing: sub ? '0.04em' : '-0.005em',
      textAlign: 'center', textWrap: 'pretty',
    }}>{children}</div>
  );
}
function CapsuleInput({ value, onChange, placeholder, autoFocus, type = 'text' }) {
  const ref = React.useRef(null);
  React.useEffect(() => { if (autoFocus) ref.current?.focus(); }, [autoFocus]);
  return (
    <div style={{ position: 'relative' }}>
      <input ref={ref} type={type} value={value} onChange={(e) => onChange(e.target.value)}
             placeholder={placeholder}
             style={{
               width: '100%', appearance: 'none',
               background: 'rgba(255,230,180,0.04)',
               border: '0.5px solid var(--glass-edge)',
               borderRadius: 999, padding: '14px 20px',
               font: '400 15px/1 var(--font-body)',
               color: 'var(--ink-strong)', outline: 'none',
               textAlign: 'center', letterSpacing: '0.02em',
             }} />
    </div>
  );
}
function PinKeypad({ value, onChange, onComplete }) {
  const press = (k) => {
    if (k === 'del') return onChange(value.slice(0, -1));
    if (value.length >= 4) return;
    const nv = value + k;
    onChange(nv);
    if (nv.length === 4) onComplete?.();
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, alignItems: 'center' }}>
      <div style={{ display: 'flex', gap: 12 }}>
        {[0,1,2,3].map((i) => (
          <div key={i} style={{
            width: 12, height: 12, borderRadius: '50%',
            background: i < value.length ? 'var(--gold)' : 'transparent',
            border: '0.5px solid var(--glass-edge-strong)',
            boxShadow: i < value.length ? '0 0 8px var(--gold-glow)' : 'none',
            transition: 'background 0.2s, box-shadow 0.2s',
          }} />
        ))}
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(3, 56px)', gap: 12, justifyContent: 'center',
      }}>
        {['1','2','3','4','5','6','7','8','9','','0','del'].map((k, i) => (
          k ? (
            <button key={i} onClick={() => press(k)}
                    style={{
                      width: 56, height: 56, borderRadius: '50%',
                      appearance: 'none', cursor: 'pointer',
                      background: 'rgba(255,230,180,0.04)',
                      border: '0.5px solid var(--glass-edge)',
                      color: 'var(--ink-strong)',
                      font: '400 18px/1 var(--font-display)',
                    }}>{k === 'del' ? '⌫' : k}</button>
          ) : <div key={i} />
        ))}
      </div>
    </div>
  );
}
function PrimaryButton({ children, onClick, disabled }) {
  return (
    <button onClick={onClick} disabled={disabled}
            style={{
              appearance: 'none', border: 'none', cursor: disabled ? 'default' : 'pointer',
              background: disabled ? 'rgba(244,175,37,0.18)' : 'var(--gold)',
              color: '#1A140B', font: '500 13.5px/1 var(--font-body)',
              padding: '13px 22px', borderRadius: 999,
              boxShadow: disabled ? 'none' : '0 0 16px var(--gold-glow)',
              opacity: disabled ? 0.5 : 1,
              flex: 1, letterSpacing: '0.02em',
            }}>{children}</button>
  );
}
function GhostButton({ children, onClick }) {
  return (
    <button onClick={onClick}
            style={{
              appearance: 'none', cursor: 'pointer',
              background: 'transparent',
              border: '0.5px solid var(--glass-edge)',
              color: 'var(--ink-secondary)', font: '500 13.5px/1 var(--font-body)',
              padding: '13px 22px', borderRadius: 999, flex: 1,
            }}>{children}</button>
  );
}

function PermissionStep({ onAllow, onSkip }) {
  return (
    <div className="glass hero accent bloom-in" style={{
      padding: '20px 22px', borderRadius: 22,
    }}>
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.18em',
        textTransform: 'uppercase', marginBottom: 8,
      }}>familiar просить</div>
      <div style={{
        font: '500 14.5px/1.5 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 10, textWrap: 'pretty', letterSpacing: '-0.005em',
      }}>«мені потрібно бачити твій екран — щоб розуміти контекст без зайвих запитань»</div>
      <div style={{ font: '300 10.5px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)', marginBottom: 14 }}>
        screen capture · accessibility service · sensors
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <PrimaryButton onClick={onAllow}>надати дозвіл</PrimaryButton>
        <GhostButton onClick={onSkip}>пропустити</GhostButton>
      </div>
    </div>
  );
}

// ─── Pair flow with AR camera viewport ───────────────────────────────────────
function PairScanner({ onPaired, onCancel, soundOn }) {
  const [phase, setPhase] = React.useState('scanning'); // scanning | locking | claiming | done
  const [bracketPos, setBracketPos] = React.useState({ x: 0.5, y: 0.5, scale: 1 });

  React.useEffect(() => {
    if (phase !== 'scanning') return;
    let raf;
    const start = performance.now();
    const tick = () => {
      const t = (performance.now() - start) / 1000;
      setBracketPos({
        x: 0.5 + Math.sin(t * 0.7) * 0.12,
        y: 0.5 + Math.cos(t * 0.5) * 0.08,
        scale: 1 + Math.sin(t * 1.3) * 0.05,
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    const id = setTimeout(() => setPhase('locking'), 2400);
    return () => { cancelAnimationFrame(raf); clearTimeout(id); };
  }, [phase]);

  React.useEffect(() => {
    if (phase === 'locking') {
      setBracketPos({ x: 0.5, y: 0.5, scale: 1 });
      const id = setTimeout(() => setPhase('claiming'), 700);
      return () => clearTimeout(id);
    }
    if (phase === 'claiming') {
      const id = setTimeout(() => { setPhase('done'); if (soundOn) chimeBell(); }, 1800);
      return () => clearTimeout(id);
    }
    if (phase === 'done') {
      const id = setTimeout(onPaired, 1500);
      return () => clearTimeout(id);
    }
  }, [phase, onPaired, soundOn]);

  return (
    <div style={{ position: 'absolute', inset: 0, background: '#000', overflow: 'hidden' }}>
      {/* fake camera viewport — gradient + grain */}
      <div style={{
        position: 'absolute', inset: 0,
        background: `
          radial-gradient(ellipse at 50% 50%, #1a1410, #0a0805 70%),
          repeating-linear-gradient(90deg, rgba(244,175,37,0.02) 0 1px, transparent 1px 32px)
        `,
      }} />
      {/* mock QR */}
      <div style={{
        position: 'absolute', left: `${bracketPos.x * 100}%`, top: `${bracketPos.y * 100}%`,
        transform: `translate(-50%, -50%) scale(${bracketPos.scale})`,
        width: 140, height: 140, opacity: 0.5,
        transition: phase === 'locking' ? 'all 0.6s cubic-bezier(.4,.1,.2,1)' : 'none',
      }}>
        <QRMock />
      </div>
      {/* AR brackets */}
      <ARBrackets x={bracketPos.x} y={bracketPos.y} scale={bracketPos.scale}
                  locked={phase !== 'scanning'} />

      {phase === 'claiming' && (
        <div style={{
          position: 'absolute', left: 0, right: 0, bottom: 80,
          textAlign: 'center',
          font: '500 14px/1.4 var(--font-display)',
          color: 'var(--gold)',
          letterSpacing: '0.02em',
        }}>
          handshake · ed25519 · …{Math.floor(Math.random() * 9999).toString(16).padStart(4, '0')}
        </div>
      )}
      {phase === 'done' && (
        <div className="glass hero accent bloom-in" style={{
          position: 'absolute', left: 28, right: 28, bottom: 80, padding: '16px 18px', borderRadius: 18,
        }}>
          <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em', marginBottom: 6 }}>
            ПІДКЛЮЧЕНО · phantom-os
          </div>
          <div style={{ font: '500 13px/1.4 var(--font-display)', color: 'var(--ink-strong)' }}>
            4ms rtt · 9 каналів · vault розблоковано
          </div>
        </div>
      )}

      {/* dismiss */}
      <button onClick={onCancel} style={{
        position: 'absolute', top: 24, left: 24,
        appearance: 'none', border: '0.5px solid rgba(255,230,180,0.2)',
        background: 'rgba(0,0,0,0.5)', color: 'var(--ink-strong)',
        padding: '8px 14px', borderRadius: 999, cursor: 'pointer',
        font: '400 11px/1 var(--font-body)',
        backdropFilter: 'blur(12px)',
      }}>← повернутись</button>

      <div style={{
        position: 'absolute', left: 0, right: 0, top: 80, textAlign: 'center',
        font: '300 11px/1.4 var(--font-mono)', color: 'var(--ink-secondary)',
        letterSpacing: '0.08em',
      }}>
        {phase === 'scanning' ? '· наведи на qr ·' :
         phase === 'locking' ? '· зафіксовано ·' :
         phase === 'claiming' ? '· обмін ключами ·' :
         '· готово ·'}
      </div>
    </div>
  );
}
function QRMock() {
  const cells = React.useMemo(() => Array.from({ length: 8*8 }, () => Math.random() > 0.5 ? 1 : 0), []);
  return (
    <div style={{
      width: '100%', height: '100%', background: '#fff',
      padding: 8, display: 'grid', gridTemplateColumns: 'repeat(10, 1fr)',
      gridTemplateRows: 'repeat(10, 1fr)', gap: 1, borderRadius: 4,
    }}>
      {/* finder corners */}
      {[[0,0],[0,7],[7,0]].map(([cx, cy], i) => (
        <div key={i} style={{
          gridColumn: `${cx+1} / span 3`, gridRow: `${cy+1} / span 3`,
          background: '#000', padding: 3,
        }}>
          <div style={{ background: '#fff', width: '100%', height: '100%', padding: 2 }}>
            <div style={{ background: '#000', width: '100%', height: '100%' }} />
          </div>
        </div>
      ))}
      {cells.map((c, i) => (
        <div key={i} style={{ background: c ? '#000' : '#fff', gridColumn: `${(i%8)+2}`, gridRow: `${Math.floor(i/8)+2}` }} />
      ))}
    </div>
  );
}
function ARBrackets({ x, y, scale, locked }) {
  const size = 120 * scale;
  const corner = 22;
  const stroke = locked ? 2.5 : 1.5;
  const color = locked ? '#F4AF25' : 'rgba(244,175,37,0.7)';
  const glow = locked ? '0 0 16px var(--gold-glow)' : 'none';
  return (
    <div style={{
      position: 'absolute', left: `${x * 100}%`, top: `${y * 100}%`,
      width: size, height: size,
      transform: `translate(-50%, -50%) scale(${locked ? 1.05 : 1})`,
      transition: locked ? 'all 0.5s cubic-bezier(.3,.7,.4,1)' : 'transform 0.1s',
      filter: `drop-shadow(${glow})`,
    }}>
      {[
        { top: 0, left: 0, b: ['top','left'] },
        { top: 0, right: 0, b: ['top','right'] },
        { bottom: 0, left: 0, b: ['bottom','left'] },
        { bottom: 0, right: 0, b: ['bottom','right'] },
      ].map((p, i) => (
        <div key={i} style={{
          position: 'absolute', width: corner, height: corner,
          ...p,
          borderTop: p.b.includes('top') ? `${stroke}px solid ${color}` : 'none',
          borderBottom: p.b.includes('bottom') ? `${stroke}px solid ${color}` : 'none',
          borderLeft: p.b.includes('left') ? `${stroke}px solid ${color}` : 'none',
          borderRight: p.b.includes('right') ? `${stroke}px solid ${color}` : 'none',
        }} />
      ))}
    </div>
  );
}

// ─── Vault Gate ──────────────────────────────────────────────────────────────
function VaultGate({ onClose, soundOn }) {
  const [unlocked, setUnlocked] = React.useState(false);
  const [revealed, setRevealed] = React.useState(null);
  const [copyTimer, setCopyTimer] = React.useState(null);

  const entries = [
    { id: 1, label: 'github · root', value: 'gh_3a7f9d2b', preview: '••••3a7f', kind: 'token' },
    { id: 2, label: 'phantom · root cert', value: 'PEM-encoded-key…', preview: '••••cert', kind: 'cert' },
    { id: 3, label: 'wallet · phantom', value: '0xA9d3…7b21', preview: '••••7b21', kind: 'wallet' },
    { id: 4, label: 'pgp · personal', value: '4F2A 8B7E …', preview: '••••8B7E', kind: 'pgp' },
    { id: 5, label: 'note · 14 травня', value: 'олег пише · треба передзвонити', preview: '••••стиснуто', kind: 'note' },
  ];

  const copy = (e) => {
    setRevealed(e.id);
    if (copyTimer) clearTimeout(copyTimer);
    const left = 6;
    setCopyTimer({ id: e.id, left });
    let n = left;
    const tick = setInterval(() => {
      n -= 1;
      if (n <= 0) { clearInterval(tick); setRevealed(null); setCopyTimer(null); }
      else setCopyTimer({ id: e.id, left: n });
    }, 1000);
  };

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'radial-gradient(ellipse at 30% 20%, #1A100A 0%, #060402 70%)',
      overflow: 'hidden', display: 'flex', flexDirection: 'column',
    }}>
      {/* GHOST atmosphere — gray-fade */}
      <div style={{
        position: 'absolute', inset: 0,
        background: 'radial-gradient(ellipse at 50% 30%, rgba(126,93,164,0.1), transparent 60%)',
        filter: 'blur(40px)',
      }} />
      <div style={{ padding: '60px 24px 16px', position: 'relative' }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 20,
        }}>← повернутись</button>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <div style={{
            width: 36, height: 36, borderRadius: '50%',
            background: 'radial-gradient(circle, #7E5DA4, #3B1F50)',
            boxShadow: '0 0 16px rgba(126,93,164,0.6)',
          }} />
          <div>
            <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
              VAULT · ПРИМАРА
            </div>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em', marginTop: 2 }}>
              слідів не залишаю
            </div>
          </div>
        </div>
      </div>

      {!unlocked ? (
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center',
          alignItems: 'center', gap: 20, padding: '0 28px',
        }}>
          <div style={{
            width: 80, height: 80, borderRadius: '50%',
            background: 'radial-gradient(circle, rgba(126,93,164,0.4), transparent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '0.5px solid rgba(126,93,164,0.5)',
          }}>
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="3" fill="#B49AD4" />
              <path d="M12 5l4 3v3a4 4 0 11-8 0V8l4-3z" stroke="#B49AD4" strokeWidth="1.2" fill="none" />
            </svg>
          </div>
          <div style={{
            font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)', textAlign: 'center',
          }}>підтвердь · це ти?</div>
          <div className="mono" style={{
            fontSize: 10, color: 'var(--ink-tertiary)', letterSpacing: '0.06em', textAlign: 'center',
          }}>палець на сенсорі</div>
          <button onClick={() => { setUnlocked(true); if (soundOn) crystalNotice(); }}
                  style={{
                    marginTop: 12,
                    appearance: 'none', border: '0.5px solid rgba(126,93,164,0.5)',
                    background: 'rgba(126,93,164,0.1)',
                    color: '#B49AD4',
                    font: '400 12px/1 var(--font-body)', letterSpacing: '0.04em',
                    padding: '12px 22px', borderRadius: 999, cursor: 'pointer',
                  }}>·  розпізнати  ·</button>
        </div>
      ) : (
        <div style={{ flex: 1, overflow: 'auto', padding: '0 18px 24px' }}>
          {entries.map((e) => (
            <div key={e.id} className="glass" style={{
              padding: '12px 14px', marginBottom: 8, borderRadius: 14,
              borderColor: 'rgba(126,93,164,0.25)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <div className="mono" style={{ fontSize: 9, color: 'rgba(180,154,212,0.8)', letterSpacing: '0.12em', textTransform: 'uppercase' }}>
                  · {e.kind}
                </div>
                {copyTimer?.id === e.id && (
                  <div className="mono" style={{ fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.06em' }}>
                    скопійовано · {copyTimer.left}s
                  </div>
                )}
              </div>
              <div style={{
                font: '400 13px/1.4 var(--font-body)', color: 'var(--ink-strong)', marginTop: 4,
              }}>{e.label}</div>
              <div style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                marginTop: 8, gap: 8,
              }}>
                <div className="mono" style={{
                  fontSize: 11, color: revealed === e.id ? 'var(--gold)' : 'var(--ink-tertiary)',
                  letterSpacing: '0.04em', flex: 1, minWidth: 0,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>{revealed === e.id ? e.value : e.preview}</div>
                <button onClick={() => copy(e)} style={{
                  appearance: 'none', border: '0.5px solid var(--glass-edge)',
                  background: 'transparent', color: 'var(--ink-strong)',
                  font: '400 10px/1 var(--font-body)', letterSpacing: '0.04em',
                  padding: '6px 10px', borderRadius: 999, cursor: 'pointer',
                }}>копія · 6с</button>
              </div>
            </div>
          ))}
          <div className="mono" style={{
            fontSize: 9, color: 'var(--ink-faint)', letterSpacing: '0.08em',
            textAlign: 'center', marginTop: 16,
          }}>· вихід авто через 30с тиші ·</div>
        </div>
      )}
    </div>
  );
}

// ─── Decision Diary ──────────────────────────────────────────────────────────
function DecisionDiary({ onClose }) {
  const decisions = [
    { date: '12 лют', title: 'переїхати в lhasa на зиму', context: 'кашель не пройшов 6 тижнів · потрібно гірське повітря', status: 'актуально' },
    { date: '03 бер', title: 'облишити проєкт «sentinel»', context: 'команда не зросла · enthusiasm погас', status: 'переглянути' },
    { date: '21 бер', title: 'перейти на on-device моделі', context: 'cloud-залежність ризик при відрізанні', status: 'актуально' },
    { date: '14 кві', title: 'не відповідати олегу', context: 'розмова з ним завжди забирає три дні', status: 'актуально' },
    { date: '28 кві', title: 'почати нові ритуали ранку', context: 'pomodoro не лягло · треба своє', status: 'переглянути' },
  ];
  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
      animation: 'phantom-fade-in 0.4s ease-out',
    }}>
      <LiveWallpaper variant="sparks" state="FOCUS" />
      <div style={{ padding: '60px 24px 12px', position: 'relative', zIndex: 5 }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 18,
        }}>← повернутись</button>
        <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
          ЩОДЕННИК · РІШЕННЯ
        </div>
        <div style={{
          font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
          marginTop: 6, letterSpacing: '0.04em',
        }}>· що ти вирішив · чому · чи ще актуально</div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '8px 18px 24px', position: 'relative', zIndex: 5 }}>
        {decisions.map((d, i) => {
          const stale = d.status === 'переглянути';
          return (
            <div key={i} className={stale ? 'glass accent' : 'glass'} style={{
              padding: '14px 16px', marginBottom: 10, borderRadius: 16, position: 'relative',
            }}>
              <div style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4,
              }}>
                <div className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>{d.date}</div>
                <div className="mono" style={{
                  fontSize: 9, letterSpacing: '0.1em', textTransform: 'uppercase',
                  color: stale ? 'var(--coral)' : 'var(--sage)',
                }}>· {d.status}</div>
              </div>
              <div style={{
                font: '500 13.5px/1.45 var(--font-display)', color: 'var(--ink-strong)',
                letterSpacing: '-0.005em', marginBottom: 4,
              }}>{d.title}</div>
              <div style={{
                font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
                textWrap: 'pretty',
              }}>{d.context}</div>
              {stale && (
                <div style={{
                  marginTop: 10, padding: '8px 10px',
                  borderRadius: 10,
                  background: 'rgba(255,139,110,0.06)',
                  border: '0.5px solid rgba(255,139,110,0.2)',
                  font: '400 11px/1.4 var(--font-body)', color: 'var(--coral)',
                  textWrap: 'pretty',
                }}>
                  «це рішення прийняте {d.date} · контекст з тих пір змінився · переглянути?»
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── Motion Catalog (Tweaks demo) ────────────────────────────────────────────
function MotionCatalogScene({ onClose }) {
  const [trigger, setTrigger] = React.useState({ name: null, key: 0 });
  const fire = (name) => setTrigger({ name, key: trigger.key + 1 });

  const animations = [
    { id: 'breathe.standard', label: 'breathe · 8s loop', dur: 8 },
    { id: 'pulse.audio',      label: 'pulse · audio reactive', dur: 1 },
    { id: 'ripple.tap',       label: 'ripple · tap confirmation', dur: 0.8 },
    { id: 'bloom.success',    label: 'bloom · success widening', dur: 1.2 },
    { id: 'pulse.coral',      label: 'pulse · error', dur: 0.6 },
    { id: 'spike.alert',      label: 'spike · sentinel anomaly', dur: 0.25 },
    { id: 'wash.state',       label: 'wash · state crossfade', dur: 2.0 },
    { id: 'brackets.qr',      label: 'brackets · qr lock-on', dur: 0.6 },
    { id: 'flow.route',       label: 'flow · route drawing', dur: 1.2 },
    { id: 'morph.familiar',   label: 'morph · familiar mood', dur: 3.0 },
  ];

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'var(--bg-base)',
      display: 'flex', flexDirection: 'column',
    }}>
      <LiveWallpaper variant="still" state="FOCUS" />
      <div style={{ padding: '60px 24px 12px', position: 'relative', zIndex: 5 }}>
        <button onClick={onClose} style={{
          appearance: 'none', border: 'none', background: 'transparent',
          color: 'var(--ink-tertiary)', cursor: 'pointer',
          font: '400 11px/1 var(--font-mono)', letterSpacing: '0.06em',
          padding: 0, marginBottom: 18,
        }}>← повернутись</button>
        <div className="state-label" style={{ fontSize: 12, color: 'var(--ink-strong)' }}>
          MOTION · КАТАЛОГ
        </div>
        <div style={{
          font: '300 11px/1.5 var(--font-mono)', color: 'var(--ink-tertiary)',
          marginTop: 6, letterSpacing: '0.04em',
        }}>· tap кожну, щоб запустити</div>
      </div>

      {/* Stage */}
      <div style={{
        height: 220, position: 'relative', overflow: 'hidden',
        margin: '12px 18px', borderRadius: 18,
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.04), transparent 70%)',
        border: '0.5px solid var(--glass-edge)',
      }}>
        <MotionStage trigger={trigger} />
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 18px 24px', position: 'relative', zIndex: 5 }}>
        {animations.map((a) => (
          <button key={a.id} onClick={() => fire(a.id)}
                  style={{
                    appearance: 'none', cursor: 'pointer',
                    width: '100%', textAlign: 'left',
                    padding: '12px 14px', marginBottom: 6, borderRadius: 12,
                    background: trigger.name === a.id ? 'rgba(244,175,37,0.08)' : 'transparent',
                    border: '0.5px solid var(--glass-edge)',
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    transition: 'background 0.2s',
                  }}>
            <span style={{ font: '500 12px/1 var(--font-display)', color: 'var(--ink-strong)' }}>{a.id}</span>
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-tertiary)' }}>{a.dur}s</span>
          </button>
        ))}
      </div>
    </div>
  );
}
function MotionStage({ trigger }) {
  const stageStyle = { position: 'absolute', inset: 0 };
  return (
    <div style={stageStyle}>
      {/* base orb */}
      <div style={{
        position: 'absolute', left: '50%', top: '50%',
        transform: 'translate(-50%, -50%)',
      }}>
        <Orb state={trigger.name === 'pulse.coral' ? 'GHOST' : 'FOCUS'}
             tier="filament" size={100}
             audio={trigger.name === 'pulse.audio' ? 0.6 : 0.1}
             motionScale={1} breathPeriod={8} />
      </div>

      {/* trigger-specific overlays */}
      {trigger.name === 'ripple.tap' && (
        <div key={trigger.key} style={{
          position: 'absolute', left: '50%', top: '50%',
          width: 24, height: 24, borderRadius: '50%',
          border: '1.5px solid var(--gold)',
          transform: 'translate(-50%, -50%)',
          animation: 'phantom-ripple 0.8s ease-out forwards',
        }} />
      )}
      {trigger.name === 'bloom.success' && (
        <div key={trigger.key} style={{
          position: 'absolute', inset: 0,
          background: 'radial-gradient(circle, var(--gold-glow), transparent 60%)',
          animation: 'phantom-bloom 1.2s ease-out forwards',
          opacity: 0,
        }} />
      )}
      {trigger.name === 'pulse.coral' && (
        <div key={trigger.key} style={{
          position: 'absolute', inset: 0,
          background: 'radial-gradient(circle, rgba(255,139,110,0.5), transparent 65%)',
          animation: 'phantom-coral-pulse 0.6s ease-out forwards',
          opacity: 0,
        }} />
      )}
      {trigger.name === 'wash.state' && (
        <div key={trigger.key} style={{
          position: 'absolute', inset: 0,
          background: 'radial-gradient(circle at 50% 50%, var(--gold-faint), transparent)',
          animation: 'phantom-wash 2s ease-in-out forwards',
          opacity: 0, filter: 'blur(20px)',
        }} />
      )}
      {trigger.name === 'flow.route' && (
        <svg viewBox="0 0 300 200" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <path key={trigger.key}
                d="M 30 170 C 80 140, 130 130, 170 110 S 240 70, 270 30"
                fill="none" stroke="var(--gold)" strokeWidth="2"
                strokeDasharray="400" strokeDashoffset="400"
                style={{ animation: 'phantom-flow 1.2s ease-in-out forwards', filter: 'drop-shadow(0 0 6px var(--gold-glow))' }} />
        </svg>
      )}
      {trigger.name === 'brackets.qr' && (
        <div key={trigger.key} style={{
          position: 'absolute', left: '50%', top: '50%',
          transform: 'translate(-50%, -50%)',
          animation: 'phantom-brackets 0.6s cubic-bezier(.3,.7,.4,1.4) forwards',
        }}>
          <ARBrackets x={0.5} y={0.5} scale={1} locked={true} />
          <div style={{ width: 120, height: 120 }} />
        </div>
      )}
      {trigger.name === 'spike.alert' && (
        <svg viewBox="0 0 300 200" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} key={trigger.key}>
          {[0, 60, 120, 180, 240, 300].map((a, i) => (
            <line key={i} x1="150" y1="100"
                  x2={150 + Math.cos(a * Math.PI / 180) * 80}
                  y2={100 + Math.sin(a * Math.PI / 180) * 80}
                  stroke="var(--gold)" strokeWidth="1"
                  style={{ animation: 'phantom-spike 0.25s ease-out forwards', opacity: 0,
                           filter: 'drop-shadow(0 0 4px var(--gold-glow))' }} />
          ))}
        </svg>
      )}

      {trigger.name && (
        <div className="mono" style={{
          position: 'absolute', left: 16, bottom: 12,
          fontSize: 10, color: 'var(--gold-soft)', letterSpacing: '0.1em',
        }}>· {trigger.name}</div>
      )}
    </div>
  );
}

// ─── Time-shifted help (past-self note that surfaces on Stream) ──────────────
function PastSelfNote({ note, date, onDismiss }) {
  return (
    <div className="glass fade-in" style={{
      padding: '12px 14px', margin: '8px 0', borderRadius: 16,
      position: 'relative', overflow: 'hidden',
      borderColor: 'rgba(244,175,37,0.18)',
      boxShadow: '0 0 24px rgba(244,175,37,0.08), inset 0 1px 0 rgba(244,175,37,0.18)',
    }}>
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background: 'linear-gradient(135deg, rgba(244,175,37,0.06), transparent 50%)',
      }} />
      <div className="mono" style={{
        fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
        textTransform: 'uppercase', marginBottom: 6, position: 'relative',
      }}>· з минулого · {date}</div>
      <div style={{
        font: '400 12.5px/1.5 var(--font-body)', color: 'var(--ink)',
        textWrap: 'pretty', position: 'relative', fontStyle: 'italic',
      }}>«{note}»</div>
      <button onClick={onDismiss} style={{
        appearance: 'none', border: 'none', background: 'transparent',
        position: 'absolute', top: 8, right: 8,
        color: 'var(--ink-tertiary)', cursor: 'pointer', fontSize: 14, lineHeight: 1,
        padding: 4,
      }}>×</button>
    </div>
  );
}

// ─── Global keyframes + voice-clone preview ──────────────────────────────────
const SURFACE_STYLE = `
@keyframes phantom-ripple { 0% { transform: translate(-50%,-50%) scale(0.8); opacity: 0.9; } 100% { transform: translate(-50%,-50%) scale(8); opacity: 0; } }
@keyframes phantom-bloom { 0% { opacity: 0; transform: scale(0.7); } 30% { opacity: 1; } 100% { opacity: 0; transform: scale(1.6); } }
@keyframes phantom-coral-pulse { 0% { opacity: 0; transform: scale(0.95); } 30% { opacity: 1; } 100% { opacity: 0; transform: scale(1.05); } }
@keyframes phantom-wash { 0% { opacity: 0; transform: scale(0.5); } 50% { opacity: 1; } 100% { opacity: 0; transform: scale(2); } }
@keyframes phantom-flow { to { stroke-dashoffset: 0; } }
@keyframes phantom-brackets { 0% { transform: translate(-50%,-50%) scale(1.5); opacity: 0; } 50% { opacity: 1; } 100% { transform: translate(-50%,-50%) scale(1); opacity: 1; } }
@keyframes phantom-spike { 0% { opacity: 0; transform: scaleX(0); transform-origin: 150px 100px; } 30% { opacity: 1; transform: scaleX(1); } 100% { opacity: 0; transform: scaleX(1); } }
@keyframes phantom-thrum { 0%,100% { transform: scaleY(0.2); } 50% { transform: scaleY(1); } }
`;

function VoiceClonePreview({ soundOn }) {
  const samples = [
    'так?',
    'я тут',
    'почув',
    'тут і зараз',
  ];
  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {samples.map((s) => (
        <button key={s} onClick={() => soundOn && speakUk(s)}
                style={{
                  appearance: 'none', border: '0.5px solid var(--glass-edge-strong)',
                  background: 'rgba(244,175,37,0.06)',
                  color: 'var(--gold)',
                  font: '400 10.5px/1 var(--font-body)',
                  padding: '6px 10px', borderRadius: 999, cursor: 'pointer',
                  letterSpacing: '0.02em',
                }}>· {s}</button>
      ))}
    </div>
  );
}

// ─── DialogueThrum ── visible halo around orb when Familiar speaks ──────────
function DialogueThrum({ active }) {
  if (!active) return null;
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 100,
      display: 'flex', justifyContent: 'center', gap: 3, alignItems: 'flex-end',
      pointerEvents: 'none', zIndex: 30,
    }}>
      {Array.from({ length: 32 }).map((_, i) => (
        <div key={i} style={{
          width: 2, height: 16, borderRadius: 1,
          background: 'var(--gold)',
          transformOrigin: 'bottom',
          opacity: 0.5,
          filter: 'drop-shadow(0 0 4px var(--gold-glow))',
          animation: `phantom-thrum ${0.6 + (i % 4) * 0.1}s ease-in-out ${i * 0.03}s infinite`,
        }} />
      ))}
    </div>
  );
}

// inject keyframes once
if (typeof document !== 'undefined' && !document.getElementById('phantom-surface-style')) {
  const s = document.createElement('style');
  s.id = 'phantom-surface-style';
  s.textContent = SURFACE_STYLE;
  document.head.appendChild(s);
}

Object.assign(window, {
  Onboarding, PairScanner, VaultGate, DecisionDiary, MotionCatalogScene,
  PastSelfNote, VoiceClonePreview, DialogueThrum, speakUk, chimeBell,
  crystalNotice, spikeAlert,
});
