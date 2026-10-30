/* phantom-app.jsx — top-level App.
   Owns: scene routing, state machine, audio sim, tweaks integration,
   stream messages, phone frame chrome. */

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "state": "FOCUS",
  "theme": "amber-night",
  "orbTier": "filament",
  "motionScale": 1,
  "breathPeriod": 8,
  "glassBlur": 16,
  "background": "sparks",
  "soundOn": false,
  "scene": "lens",
  "showFloatingOrb": false,
  "thread": "now"
}/*EDITMODE-END*/;

const STATES = ['FOCUS', 'DIALOGUE', 'SENTINEL', 'GHOST', 'SHADOW', 'DREAM'];

// ─── Phone frame (custom, NOT Material) ──────────────────────────────────────
function PhoneFrame({ children, theme = 'amber-night' }) {
  const isDark = theme === 'amber-night';
  return (
    <div style={{
      width: 412, height: 892,
      borderRadius: 48,
      padding: 8,
      background: isDark
        ? 'linear-gradient(160deg, #2a221a, #0a0805)'
        : 'linear-gradient(160deg, #c9bfae, #8a7f6a)',
      boxShadow: `
        0 60px 120px rgba(0,0,0,0.6),
        0 20px 40px rgba(0,0,0,0.4),
        0 0 0 0.5px rgba(255,230,180,0.15),
        inset 0 1px 1px rgba(255,255,255,0.08)
      `,
      position: 'relative',
    }}>
      <div style={{
        width: '100%', height: '100%',
        borderRadius: 42,
        overflow: 'hidden',
        position: 'relative',
        background: 'var(--bg-base)',
      }}>
        {children}
        {/* notch / pill */}
        <div style={{
          position: 'absolute', left: '50%', top: 12, transform: 'translateX(-50%)',
          width: 92, height: 28, borderRadius: 14,
          background: '#000',
          zIndex: 100,
        }} />
      </div>
    </div>
  );
}

function StatusBar({ time = '19:34' }) {
  return (
    <div className="ui-chrome" style={{
      height: 44, paddingTop: 10,
      display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      padding: '14px 28px 0',
      color: 'var(--ink-strong)',
      font: '500 13px/1 var(--font-display)',
      letterSpacing: '-0.005em',
      position: 'relative', zIndex: 10,
    }}>
      <span>{time}</span>
      <div style={{ width: 92 }} />
      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
        {/* signal */}
        <svg width="14" height="10" viewBox="0 0 14 10">
          <rect x="0" y="6" width="2" height="4" rx="0.5" fill="currentColor" />
          <rect x="3" y="4" width="2" height="6" rx="0.5" fill="currentColor" />
          <rect x="6" y="2" width="2" height="8" rx="0.5" fill="currentColor" />
          <rect x="9" y="0" width="2" height="10" rx="0.5" fill="currentColor" />
        </svg>
        {/* wifi */}
        <svg width="13" height="10" viewBox="0 0 13 10">
          <path d="M6.5 8.5l1.5 1.5h-3l1.5-1.5z" fill="currentColor" />
          <path d="M2 4a7.5 7.5 0 019 0" stroke="currentColor" strokeWidth="1.2" fill="none" />
          <path d="M3.5 5.5a5.5 5.5 0 016 0" stroke="currentColor" strokeWidth="1.2" fill="none" />
          <path d="M5 7a3 3 0 013 0" stroke="currentColor" strokeWidth="1.2" fill="none" />
        </svg>
        {/* battery */}
        <svg width="22" height="10" viewBox="0 0 22 10">
          <rect x="0.5" y="0.5" width="18" height="9" rx="2" fill="none" stroke="currentColor" strokeWidth="0.7" />
          <rect x="2" y="2" width="13" height="6" rx="1" fill="currentColor" />
          <rect x="19" y="3" width="2" height="4" rx="0.5" fill="currentColor" />
        </svg>
      </div>
    </div>
  );
}

function NavBar() {
  return (
    <div className="ui-chrome" style={{
      height: 28, display: 'flex', justifyContent: 'center', alignItems: 'center',
      position: 'relative', zIndex: 10,
    }}>
      <div style={{
        width: 130, height: 4, borderRadius: 2,
        background: 'var(--ink-strong)', opacity: 0.5,
      }} />
    </div>
  );
}

// ─── Audio simulator (replaces real mic) ─────────────────────────────────────
function useSimAudio({ ptt, speaking, soundOn }) {
  const [audio, setAudio] = React.useState(0);
  React.useEffect(() => {
    let raf;
    let phase = 0;
    const tick = () => {
      phase += 0.05;
      let target;
      if (ptt) {
        target = 0.45 + Math.abs(Math.sin(phase * 1.5)) * 0.4 + Math.random() * 0.1;
      } else if (speaking) {
        target = 0.25 + Math.abs(Math.sin(phase * 0.8)) * 0.35 + Math.abs(Math.sin(phase * 2.3)) * 0.15;
      } else {
        target = 0.04 + Math.sin(phase * 0.3) * 0.03;
      }
      setAudio((a) => a + (target - a) * 0.18);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [ptt, speaking]);
  return audio;
}

// ─── Stream message store ────────────────────────────────────────────────────
const SEED_MESSAGES = [
  { kind: 'system', text: 'після паузи · 4хв', time: '19:30' },
  { kind: 'hero' },
  {
    kind: 'insight',
    title: 'спостереження',
    body: 'ти двічі сьогодні відкрив vault — раз о 14:12, раз о 17:48. перевіряти не моя справа, але якщо щось не так — запитуй.',
    actions: [
      { label: 'переглянути журнал', primary: true },
      { label: 'нічого, дякую' },
    ],
  },
  { kind: 'chat', role: 'USER', text: 'нагадай про олега о 18:00', time: '17:42' },
  { kind: 'chat', role: 'ASSISTANT', text: 'добре. без імені або зі словами — бо ти раз казав не любиш коли я кричу його ім\'я голосно?', time: '17:42' },
  { kind: 'chat', role: 'USER', text: 'без імені', time: '17:42' },
  { kind: 'chat', role: 'ASSISTANT', text: 'тоді так: о 18:00 буду шепотіти в навушник.', time: '17:42' },
  { kind: 'tool', tool: 'Notion', query: 'olya · 14 травня · кава', ms: 412, results: 12 },
  { kind: 'file', fileKind: 'photo', title: 'Анна за каву · $4.20',
    meta: 'local roasters · 14 травня · 14:22',
    extracted: 'auto-розпізнано: чек 4.20 USD · картка ···7b21 · vendor: local roasters' },
  { kind: 'chat', role: 'ASSISTANT',
    text: 'записав. чек до vault. анна вже винна тобі $12.40 за травень.',
    cite: { source: 'розмова з anna', date: '12 травня' },
    time: '14:23' },
  { kind: 'chart', title: 'видатки · тиждень · usd', kind2: 'bar',
    data: [
      { x: 'пн', y: 18 }, { x: 'вт', y: 22 }, { x: 'ср', y: 14 },
      { x: 'чт', y: 31 }, { x: 'пт', y: 19 }, { x: 'сб', y: 8 }, { x: 'нд', y: 4 },
    ],
    annotations: [{ x: 'чт', label: 'кафе×2' }] },
  { kind: 'voice', duration: 23,
    transcript: 'нагадай о 9 ранку забрати посилку якщо погода добра' },
  { kind: 'code', lang: 'kotlin', code:
`suspend fun greet(name: String): String {
  val state = phantom.state()
  return when (state) {
    GHOST  -> "·"
    DREAM  -> "майже сплю · ${'$'}name"
    else   -> "тут і зараз · ${'$'}name"
  }
}` },
  {
    kind: 'timer',
    label: 'тиха година', total: 1500, elapsed: 824,
  },
  {
    kind: 'handoff',
    from: 'phantom-os',
    title: 'маршрут до дому',
    preview: 'desktop збудував шлях на 4.2 км · обхід коралової зони на проспекті · 11 хв пішки',
  },
  {
    kind: 'map',
    destination: 'дім', distance: '4.2 км', eta: '11 хв',
  },
  { kind: 'notice', kind2: 'warning', text: 'gemini-ключ закінчується — сім днів. налаштувати?' },
  {
    kind: 'chat', role: 'ASSISTANT',
    text: 'привіт ще раз. хочеш просто помовчати?',
    time: '19:34',
  },
];

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [messages, setMessages] = React.useState(SEED_MESSAGES);
  const [draft, setDraft] = React.useState('');
  const [ptt, setPtt] = React.useState(false);
  const [speaking, setSpeaking] = React.useState(false);
  const [settingsOpen, setSettingsOpen] = React.useState(false);
  const [trayOpen, setTrayOpen] = React.useState(false);
  const [dwellMin, setDwellMin] = React.useState(17);
  const [autoMessageId, setAutoMessageId] = React.useState(0);
  const streamRef = React.useRef(null);

  const audio = useSimAudio({ ptt, speaking, soundOn: t.soundOn });

  // dwell timer
  React.useEffect(() => {
    setDwellMin(0);
    const id = setInterval(() => setDwellMin((d) => d + 1), 60000);
    return () => clearInterval(id);
  }, [t.state]);

  // CSS vars for motion + blur tweaks
  React.useEffect(() => {
    document.documentElement.style.setProperty('--breath-period', `${t.breathPeriod}s`);
    document.documentElement.style.setProperty('--glass-blur', `${t.glassBlur}px`);
    document.documentElement.setAttribute('data-theme', t.theme);
  }, [t.breathPeriod, t.glassBlur, t.theme]);

  // auto-scroll stream to bottom
  React.useEffect(() => {
    if (streamRef.current) {
      streamRef.current.scrollTop = streamRef.current.scrollHeight;
    }
  }, [messages.length, t.scene]);

  // Sending text → familiar reply (using window.claude.complete if available)
  const send = async (text) => {
    setMessages((m) => [...m, { kind: 'chat', role: 'USER', text, time: nowHHMM() }]);
    setDraft('');
    setSpeaking(true);
    if (t.state === 'GHOST') {
      // GHOST mode — silent reply
      setTimeout(() => {
        setMessages((m) => [...m, { kind: 'system', text: 'голос вимкнено · режим примара' }]);
        setSpeaking(false);
      }, 600);
      return;
    }
    // Try Claude; fall back to stub
    try {
      if (window.claude && window.claude.complete) {
        const reply = await window.claude.complete({
          messages: [{
            role: 'user',
            content: `Ти — PHANTOM Familiar, цифровий симбіонт. Говориш українською, на «ти». Тон: теплий, інтелектуальний, як старий друг-філософ-engineer. Без "дякую за повідомлення", без emoji, без зайвої ввічливості. Коротко (1-3 речення). Інколи спостереження замість прямої відповіді. Пиши маленькими літерами як у dialogue. Поточний state: ${t.state}.

оператор каже: "${text}"

відповідай.`,
          }],
        });
        setMessages((m) => [...m, { kind: 'chat', role: 'ASSISTANT', text: String(reply).trim().toLowerCase(), time: nowHHMM() }]);
      } else {
        setMessages((m) => [...m, { kind: 'chat', role: 'ASSISTANT', text: stubReply(text, t.state), time: nowHHMM() }]);
      }
    } catch (e) {
      setMessages((m) => [...m, { kind: 'chat', role: 'ASSISTANT', text: stubReply(text, t.state), time: nowHHMM() }]);
    } finally {
      setSpeaking(false);
    }
  };

  const dismissAt = (idx) => setMessages((m) => m.filter((_, i) => i !== idx));

  // parallax — gyro proxy
  const [parallax, setParallax] = React.useState({ x: 0, y: 0 });
  React.useEffect(() => {
    const onMove = (e) => {
      const cx = window.innerWidth / 2;
      const cy = window.innerHeight / 2;
      setParallax({ x: (e.clientX - cx) / cx, y: (e.clientY - cy) / cy });
    };
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, []);

  // dialogue thrum sync to speaking
  React.useEffect(() => {
    if (speaking && t.soundOn && window.speakUk) {
      // brief acknowledgement
      const last = messages[messages.length - 1];
      if (last && last.role === 'USER') window.speakUk('так');
    }
  }, [speaking]);

  // ─── Render scenes ─────────────────────────────────────────────────────────
  const renderScene = () => {
    switch (t.scene) {
      case 'onboarding':
        return <Onboarding onDone={() => setTweak('scene', 'stream')}
                           orbTier={t.orbTier} motionScale={t.motionScale}
                           breathPeriod={t.breathPeriod} soundOn={t.soundOn} />;
      case 'pair':
        return <PairScanner onPaired={() => setTweak('scene', 'stream')}
                            onCancel={() => setTweak('scene', 'stream')}
                            soundOn={t.soundOn} />;
      case 'vault':
        return <VaultGate onClose={() => setTweak('scene', 'stream')}
                          soundOn={t.soundOn} />;
      case 'diary':
        return <DecisionDiary onClose={() => setTweak('scene', 'stream')} />;
      case 'motion':
        return <MotionCatalogScene onClose={() => setTweak('scene', 'stream')} />;
      case 'whisper':
        return <WhisperLineScene onClose={() => setTweak('scene', 'stream')} soundOn={t.soundOn} />;
      case 'glow':
        return <MarginGlowScene onClose={() => setTweak('scene', 'stream')}
                                state={t.state} setState={(v) => setTweak('state', v)} />;
      case 'pulse':
        return <SubliminalPulseScene onClose={() => setTweak('scene', 'stream')} orbTier={t.orbTier} />;
      case 'hive':
        return <FamilyHiveScene onClose={() => setTweak('scene', 'stream')} orbTier={t.orbTier} />;
      case 'pdf':
        return <PdfChatScene onClose={() => setTweak('scene', 'stream')} />;
      case 'calendar':
        return <CalendarWeekScene onClose={() => setTweak('scene', 'stream')} />;
      case 'email':
        return <EmailTriageScene onClose={() => setTweak('scene', 'stream')} />;
      case 'smarthome':
        return <SmartHomeScene onClose={() => setTweak('scene', 'stream')} />;
      case 'sleep':
        return <SleepDashboardScene onClose={() => setTweak('scene', 'stream')} />;
      case 'imagegen':
        return <ImageGenScene onClose={() => setTweak('scene', 'stream')} />;
      case 'palace':
        return <MemoryPalaceScene onClose={() => setTweak('scene', 'stream')} />;
      case 'dream':
        return <DreamJournalScene onClose={() => setTweak('scene', 'stream')} />;
      case 'court':
        return <NegotiationCourtScene onClose={() => setTweak('scene', 'stream')} />;
      case 'health':
        return <AmbientHealthScene onClose={() => setTweak('scene', 'stream')} />;
      case 'type':
        return <TypographyScene onClose={() => setTweak('scene', 'stream')} />;
      case 'gallery':
        return <RichShowcaseScene onClose={() => setTweak('scene', 'stream')} />;
      case 'lab':
        return <AdvancedLabScene onClose={() => setTweak('scene', 'stream')} />;
      case 'seance':
        return <SeanceScene onClose={() => setTweak('scene', 'stream')} />;
      case 'live':
        return <LiveChatScene onClose={() => setTweak('scene', 'stream')} />;
      case 'lens':
        return <LensScene onClose={() => setTweak('scene', 'stream')} />;
      case 'lockscreen':
        return (
          <>
            <LiveWallpaper variant={t.background} state={t.state} />
            <LockScreen state={t.state} orbTier={t.orbTier}
                        motionScale={t.motionScale} breathPeriod={t.breathPeriod}
                        audio={audio} dwellMin={dwellMin} />
          </>
        );
      case 'home':
        return (
          <>
            <LiveWallpaper variant={t.background} state={t.state} />
            <StatusBar />
            <HomeScreen state={t.state} orbTier={t.orbTier}
                        motionScale={t.motionScale} breathPeriod={t.breathPeriod}
                        audio={audio} />
            <NavBar />
          </>
        );
      case 'wallpaper':
        return <LiveWallpaper variant={t.background} state={t.state} />;
      case 'stream':
      default:
        return (
          <>
            <LiveWallpaper variant={t.background} state={t.state} />
            <StatusBar />
            <SystemPulseHeader state={t.state} dwellMin={dwellMin}
                               audio={audio} onOpenSettings={() => setSettingsOpen(true)}
                               onLongPressName={() => setTrayOpen(true)} />
            <ThreadRail active={t.thread} onChange={(v) => setTweak('thread', v)} />
            <div ref={streamRef} style={{
              flex: 1, overflow: 'auto',
              padding: '4px 16px 12px',
              position: 'relative', zIndex: 5,
              minHeight: 0,
            }}>
              {messages.map((m, i) => renderMessage(m, i, dismissAt))}
              {/* Past-self note (time-shifted help) — surfaces among Stream */}
              <PastSelfNote
                date="3 місяці тому"
                note="якщо ти знову відкриваєш vault двічі за вечір — це не паніка, це звичка перевіряти. видихни."
                onDismiss={() => {}}
              />
              {/* Voice clone preview */}
              <div className="glass" style={{
                padding: '12px 14px', margin: '8px 0', borderRadius: 16,
              }}>
                <div className="mono" style={{
                  fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.16em',
                  textTransform: 'uppercase', marginBottom: 8,
                }}>· styletts2 · твій голос</div>
                <div style={{
                  font: '400 11.5px/1.4 var(--font-body)', color: 'var(--ink-secondary)',
                  marginBottom: 10, textWrap: 'pretty',
                }}>зразок із 30-секундного запису · tap, щоб почути</div>
                <VoiceClonePreview soundOn={t.soundOn} />
              </div>
              {speaking && (
                <div style={{ display: 'flex', justifyContent: 'flex-start', padding: '6px 0' }}>
                  <div className="glass" style={{
                    padding: '12px 16px', borderRadius: '16px 16px 16px 4px',
                  }}>
                    <SpeakingDots />
                  </div>
                </div>
              )}
            </div>
            <InputRow
              state={t.state}
              text={draft} setText={setDraft}
              onSend={send}
              onPttDown={() => setPtt(true)}
              onPttUp={() => {
                if (ptt) {
                  // simulate transcript
                  setPtt(false);
                  send('перекажи ким я був до сьогодні');
                }
              }}
              ptt={ptt} audio={audio}
            />
          </>
        );
    }
  };

  // outermost: phone frame
  return (
    <div style={{
      width: '100vw', height: '100vh',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      position: 'relative',
      background: 'radial-gradient(ellipse at 50% 40%, #1a1108 0%, #060402 60%, #000 100%)',
      overflow: 'hidden',
    }}>
      {/* Lhasa-dusk atmospheric backdrop — 19:30, sun 12° below horizon */}
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background: `
          radial-gradient(ellipse 60% 40% at ${30 + parallax.x * 4}% ${15 + parallax.y * 3}%, rgba(244,175,37,0.12), transparent 55%),
          radial-gradient(ellipse 50% 35% at ${75 - parallax.x * 3}% ${88 - parallax.y * 2}%, rgba(231,111,81,0.06), transparent 60%),
          radial-gradient(ellipse 40% 30% at ${85 + parallax.x * 5}% ${30 + parallax.y * 4}%, rgba(126,93,164,0.05), transparent 60%),
          linear-gradient(180deg, rgba(20,12,8,0.4) 0%, transparent 30%, transparent 70%, rgba(8,5,3,0.6) 100%)
        `,
        transition: 'background 0.4s ease-out',
      }} />
      {/* Distant mountain silhouette — barely there */}
      <svg viewBox="0 0 1600 600" preserveAspectRatio="none" style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '40%',
        opacity: 0.35, pointerEvents: 'none',
      }}>
        <path d="M 0 600 L 0 420 L 120 380 L 240 410 L 360 340 L 480 390 L 620 320 L 760 380 L 900 310 L 1040 360 L 1180 290 L 1320 350 L 1460 280 L 1600 330 L 1600 600 Z"
              fill="url(#peakGrad)" />
        <defs>
          <linearGradient id="peakGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgba(40,28,16,0.6)" />
            <stop offset="100%" stopColor="rgba(8,5,3,1)" />
          </linearGradient>
        </defs>
      </svg>
      <div style={{
        position: 'relative',
        transform: `scale(var(--phone-scale, 1)) translate(${parallax.x * 6}px, ${parallax.y * 4}px)`,
        transformOrigin: 'center center',
        transition: 'transform 0.18s ease-out',
      }}>
        <PhoneFrame theme={t.theme}>
          <div style={{
            width: '100%', height: '100%',
            display: 'flex', flexDirection: 'column',
            position: 'relative',
          }}>
            {renderScene()}
            {/* Dialogue thrum — visible halo when Familiar speaks */}
            {t.scene === 'stream' && (
              <DialogueThrum active={speaking || t.state === 'DIALOGUE'} />
            )}
            {t.showFloatingOrb && (
              <FloatingOrbOverlay
                state={t.state} audio={audio} motionScale={t.motionScale}
                breathPeriod={t.breathPeriod} orbTier={t.orbTier}
              />
            )}
            {settingsOpen && (
              <ConvSettingsSheet
                onClose={() => setSettingsOpen(false)}
                theme={t.theme} onChangeTheme={(v) => setTweak('theme', v)}
              />
            )}
            <SurfaceTray open={trayOpen} onClose={() => setTrayOpen(false)}
                         current={t.scene} onChange={(v) => setTweak('scene', v)} />
          </div>
        </PhoneFrame>
      </div>

      {/* Caption — designer thinking */}
      <div className="ui-chrome" style={{
        position: 'absolute', left: 24, top: 24,
        font: '500 11px/1.4 var(--font-display)',
        color: 'rgba(244,175,37,0.6)',
        letterSpacing: '0.16em', textTransform: 'uppercase',
      }}>
        phantom · companion
        <div style={{
          marginTop: 4, font: '300 10px/1.4 var(--font-mono)',
          color: 'rgba(167,150,125,0.4)',
          letterSpacing: '0.08em', textTransform: 'none',
        }}>familiar manifestation · {t.scene} · {t.state.toLowerCase()}</div>
      </div>

      <PhantomTweaks t={t} setTweak={setTweak} />
    </div>
  );
}

function renderMessage(m, i, dismissAt) {
  switch (m.kind) {
    case 'hero':    return <HeroWelcome key={i} />;
    case 'system':  return <ChatBubble key={i} role="SYSTEM" text={m.text} />;
    case 'chat':    return <ChatBubble key={i} role={m.role} text={m.text} time={m.time} />;
    case 'notice':  return <NoticeBar key={i} kind={m.kind2 || 'info'} text={m.text} />;
    case 'timer':   return <TimerWidget key={i} label={m.label} totalSec={m.total} elapsedSec={m.elapsed} />;
    case 'vitals':  return <VitalsWidget key={i} />;
    case 'insight': return <InsightCard key={i} title={m.title} body={m.body}
                                        actions={m.actions}
                                        onDismiss={() => dismissAt(i)} />;
    case 'handoff': return <HandoffCard key={i} fromDevice={m.from} kind={m.title}
                                        preview={m.preview}
                                        onAccept={() => dismissAt(i)}
                                        onDismiss={() => dismissAt(i)} />;
    case 'map':     return <MapWidget key={i} destination={m.destination}
                                       distance={m.distance} eta={m.eta} />;
    case 'tool':    return <ToolTrace key={i} tool={m.tool} query={m.query} ms={m.ms} results={m.results} />;
    case 'file':    return <FileCard key={i} kind={m.fileKind} title={m.title} meta={m.meta} extracted={m.extracted} />;
    case 'chart':   return <ChartWidget key={i} title={m.title} kind={m.kind2} data={m.data} annotations={m.annotations} />;
    case 'voice':   return <VoiceMemo key={i} duration={m.duration} transcript={m.transcript} />;
    case 'code':    return <CodeBlock key={i} lang={m.lang} code={m.code} onRun={() => {}} />;
    default: return null;
  }
}

function SpeakingDots() {
  return (
    <div style={{ display: 'flex', gap: 4, alignItems: 'center', height: 16 }}>
      {[0, 1, 2].map((i) => (
        <div key={i} style={{
          width: 6, height: 6, borderRadius: '50%',
          background: 'var(--gold-soft)',
          animation: `phantom-pulse-glow 1.2s ease-in-out ${i * 0.2}s infinite`,
        }} />
      ))}
    </div>
  );
}

function nowHHMM() {
  const d = new Date();
  return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
}

function stubReply(input, state) {
  const lower = input.toLowerCase();
  if (lower.includes('перекажи')) return 'занадто давно — і занадто близько. краще пам\'ятати тільки те, що несеш зараз.';
  if (lower.includes('погод')) return 'дощ за пів години · парасоля в передпокої';
  if (lower.includes('таймер') || lower.includes('нагада')) return 'добре. поставив — без слів, тільки шепіт у навушник.';
  if (lower.includes('?')) return 'питання гарне. подумай ще раз — і я подумаю поряд.';
  if (state === 'DREAM') return 'я майже сплю · повтори голосніше якщо потрібно';
  return 'я тут. почув. лежить.';
}

// ─── Tweaks panel ────────────────────────────────────────────────────────────
function PhantomTweaks({ t, setTweak }) {
  return (
    <TweaksPanel title="phantom · tweaks">
      <TweakSection label="сцена">
        <TweakSelect label="surface"
                     value={t.scene}
                     options={[
                       { value: 'lens',       label: 'lens · ✦✦✦✦✦ камера' },
                       { value: 'live',       label: 'live · ✦✦✦✦ chat' },
                       { value: 'seance',     label: 'séance · ✦✦✦ moments' },
                       { value: 'lab',        label: 'lab · ✦✦ advanced' },
                       { value: 'gallery',    label: 'gallery · ✦ rich chat' },
                       { value: 'stream',     label: 'stream · heart' },
                       { value: 'home',       label: 'home · widget' },
                       { value: 'lockscreen', label: 'lock · aod' },
                       { value: 'wallpaper',  label: 'wallpaper · live' },
                       { value: 'onboarding', label: 'onboarding · 60s journey' },
                       { value: 'pair',       label: 'pair · ar scanner' },
                       { value: 'vault',      label: 'vault · ghost gate' },
                       { value: 'diary',      label: 'diary · decisions' },
                       { value: 'motion',     label: 'motion · catalog' },
                     ]}
                     onChange={(v) => setTweak('scene', v)} />
        <TweakToggle label="floating orb (88dp overlay)" value={t.showFloatingOrb}
                     onChange={(v) => setTweak('showFloatingOrb', v)} />
      </TweakSection>

      <TweakSection label="системний стан · FSM">
        <TweakSelect label="state"
                     value={t.state}
                     options={STATES.map((s) => ({ value: s, label: `${s} · ${STATE_NAMES[s]}` }))}
                     onChange={(v) => setTweak('state', v)} />
      </TweakSection>

      <TweakSection label="орб · render">
        <TweakRadio label="tier" value={t.orbTier}
                    options={[
                      { value: 'compose',      label: 'css' },
                      { value: 'rendereffect', label: 'canvas' },
                      { value: 'filament',    label: 'webgl' },
                    ]}
                    onChange={(v) => setTweak('orbTier', v)} />
        <TweakSlider label="motion scale" value={t.motionScale}
                     min={0.4} max={1.6} step={0.1}
                     onChange={(v) => setTweak('motionScale', v)} />
        <TweakSlider label="breath period" value={t.breathPeriod}
                     min={3} max={14} step={0.5} unit="s"
                     onChange={(v) => setTweak('breathPeriod', v)} />
      </TweakSection>

      <TweakSection label="скло · поверхні">
        <TweakSlider label="glass blur" value={t.glassBlur}
                     min={4} max={36} step={1} unit="px"
                     onChange={(v) => setTweak('glassBlur', v)} />
      </TweakSection>

      <TweakSection label="фон">
        <TweakRadio label="background" value={t.background}
                    options={[
                      { value: 'still',     label: 'still' },
                      { value: 'wallpaper', label: 'topo' },
                      { value: 'sparks',    label: 'sparks' },
                    ]}
                    onChange={(v) => setTweak('background', v)} />
      </TweakSection>

      <TweakSection label="тема">
        <TweakRadio label="theme" value={t.theme}
                    options={[
                      { value: 'amber-night',  label: 'amber-night' },
                      { value: 'sunrise-warm', label: 'sunrise-warm' },
                    ]}
                    onChange={(v) => setTweak('theme', v)} />
      </TweakSection>

      <TweakSection label="звук">
        <TweakToggle label="ambient + tts (sim)" value={t.soundOn}
                     onChange={(v) => setTweak('soundOn', v)} />
      </TweakSection>
    </TweaksPanel>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
