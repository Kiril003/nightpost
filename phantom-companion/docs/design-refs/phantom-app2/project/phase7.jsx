/* phase7.jsx — LiveChatScene.
   The fourth layer: a real, interactive live chat where phantom streams,
   calls tools inline, picks the best response form (text · card · media),
   and where the composer holds infinite powers (/, @, #, attach, ghost, voice). */

// ─── pattern library — phantom's reply playbook ─────────────────────────────
function makeReplies() {
  return [
    {
      match: (t) => /погод/i.test(t),
      build: () => ({
        intent: 'дивлюсь погоду · live · 3 джерела',
        thinking: {
          duration: 280,
          steps: [
            'розумію: «що з погодою на завтра?»',
            'координати: lhasa · висота 3656m',
            'джерела: meteorologica · noaa · radxa-станція на даху',
          ],
        },
        tools: [
          { tool: 'weather.forecast', query: 'lhasa · 24 год',
            result: '-2° → +6° · ясно після 11:00 · вітер 9 км/г',
            ms: 312, status: 'done' },
          { tool: 'air.quality', query: 'lhasa · pm2.5',
            result: '14 µg/m³ · чисто',
            ms: 92, status: 'done' },
        ],
        text: 'ранок прохолодний (-2°), потім ясно. без дощу. одягни шерсть до 11:00 — потім сонце вижене.',
        card: <WeatherRich place="lhasa" now={{ t: 2, cond: 'rain' }} />,
        suggestions: [
          'покажи радар',
          'що з погодою у kyiv?',
          'якщо мокрий сніг — як одягнутись?',
        ],
      }),
    },
    {
      match: (t) => /^\/image|намалюй|нарис/i.test(t),
      build: () => ({
        intent: 'малюю 4 варіанти · sdxl-turbo · 8 сек',
        thinking: { duration: 180, steps: ['промт чищу', 'сід рандомізую × 4', 'композиція: 60% небо · 40% гори'] },
        tools: [{ tool: 'image.generate', query: 'lhasa o 19:30, листопад, шафран та сапфір', ms: 8400, status: 'done', result: '4 × 1024px · seeded' }],
        text: 'готово · обери або зроби ще.',
        card: <ImageGenGrid prompt="lhasa o 19:30, листопад, шафран та сапфір" />,
        suggestions: ['upscale #2', 'інший стиль · ваянг', 'зберегти у vault'],
      }),
    },
    {
      match: (t) => /^\/search|новин/i.test(t),
      build: () => ({
        intent: 'шукаю · 4 джерела',
        thinking: { duration: 420, steps: ['ключове: «lhasa новини»', 'часове вікно: 24 год', 'фільтр: тільки достовірні', 'мова: en + uk'] },
        tools: [
          { tool: 'web.search', query: 'lhasa snow may 2026', result: '12 результатів · 4 надійних', ms: 412 },
          { tool: 'fetch.article', query: 'reuters.com/lhasa-snow-anomaly', result: 'розпарсено · 1.4k слів', ms: 280 },
          { tool: 'fetch.article', query: 'nasa.gov/earth-observatory', result: 'розпарсено · супутникові знімки', ms: 320 },
        ],
        text: 'головне: сніг рекордно ранній. 3 з 4 джерел погоджуються — раз на 30 років.',
        card: <NewsHero
          kicker="reuters · 14 травня · 18:42"
          title="у lhasa зафіксовано перший справжній сніг — місяцем раніше нормальних строків"
          summary="станції на трьох висотах фіксують від -3 до -7°c. метеорологи: причина — зміщення джет-стрім після слабкого ел-ніньо."
          scene="mountain"
          tags={['клімат', 'lhasa', 'погода']} />,
        suggestions: ['покажи всі джерела · citation cluster', 'як це впливає на мій переїзд?', 'зберегти у читання'],
      }),
    },
    {
      match: (t) => /^@оля|@olya/i.test(t),
      build: () => ({
        intent: 'звʼязався з її фантомом · домовляємось',
        thinking: { duration: 1800, steps: ['handshake · ed25519', 'обмін: intent + context', 'жодних твоїх слів не передано', 'її фантом узгодив запитати її'] },
        text: 'її фантом каже: вона вільна. її варіант — вечеря у тебе, не в неї. одна година.',
        card: <PhantomsHandshake them="оля" />,
        suggestions: ['прийняти · «давай»', 'інший час · потім', 'не сьогодні · «втомлений»'],
      }),
    },
    {
      match: (t) => /^\/memory|памʼят/i.test(t),
      build: () => ({
        intent: 'шукаю у твоїй памʼяті',
        thinking: { duration: 340, steps: ['vault розблоковано · biometric', 'індекс: 14 238 фото · 4 232 листи · 1 821 голос', 'критерій: ваш опис'] },
        tools: [{ tool: 'memory.semantic', query: 'фотка де світло було помаранчеве і ти усміхався', ms: 188, result: '1 хіт · IMG_3941' }],
        text: 'знайшов одне. ось — 8 жовтня 2023, llac trail, сонце за тобою.',
        card: <MemoryByFeeling />,
        suggestions: ['поряд з нею що ще?', 'покажи весь день', 'надішли мамі'],
      }),
    },
    {
      match: (t) => /^\/code|код|kotlin|js|python/i.test(t),
      build: () => ({
        intent: 'пишу · sandbox готовий',
        thinking: { duration: 220, steps: ['мова: kotlin', 'мета: state-machine для familiar mood', 'тестую runtime perform'] },
        text: 'ось — компактно, без зайвого.',
        card: <CodeBlock lang="kotlin" code={`suspend fun greet(name: String): String {
  val state = phantom.state()
  return when (state) {
    GHOST  -> "·"
    DREAM  -> "майже сплю · $name"
    FOCUS  -> "тут і зараз · $name"
    else   -> "поряд · $name"
  }
}`} onRun={() => {}} />,
        suggestions: ['покажи тести', 'переклади на rust', 'додай retry-logic'],
      }),
    },
    {
      match: (t) => /^\/calendar|календ|що сьогодні/i.test(t),
      build: () => ({
        intent: 'збираю день',
        thinking: { duration: 160, steps: ['7 подій · фільтрую шум · 3 справжніх'] },
        text: 'сьогодні · 3 події що важать.',
        card: <DayTimeline />,
        suggestions: ['скасуй standup', 'розфокусуй 14:00–17:00', 'перенеси вечерю на завтра'],
      }),
    },
    {
      match: (t) => /^\/translate|перекл/i.test(t),
      build: () => ({
        text: 'переклав. дав три варіанти · обери який звучить як ти.',
        card: <TranslationCard
          src="es → uk"
          original="«El tiempo es la sustancia de que estoy hecho. El tiempo es un río que me arrebata, pero yo soy el río.»"
          translated="«час — речовина, з якої я зроблений. час — річка, що несе мене, але я і є та річка»" />,
        suggestions: ['ще мʼякше', 'тільки оригінал · як є', 'зберегти у щоденник'],
      }),
    },
    {
      match: (t) => /карт|переїзд|lhasa.*kyiv/i.test(t),
      build: () => ({
        intent: 'будую маршрут · 3 пересадки',
        thinking: { duration: 720, steps: ['kbp → ist → pek → lxa', 'хіміч ist 2:35 · ок · кава', 'lxa → guesthouse · hotel pickup'] },
        text: '31 година · 6 ділянок · я забронював. рейси у vault.',
        card: <TravelItinerary />,
        suggestions: ['зміни на завтра', 'дай мапу', 'нагадай за 2 години до польоту'],
      }),
    },
    {
      match: (t) => /^\/remind|нагада/i.test(t),
      build: () => ({
        text: 'добре · нагадаю тобі тихо, у навушник, якщо ти у фокусі. якщо ні — підсвічу wallpaper.',
        card: <TimerWidget label="забрати посилку" totalSec={3600} elapsedSec={0} />,
        suggestions: ['зроби це повторюваним', 'без слів · лише орб', 'скасувати'],
      }),
    },
    {
      match: (t) => /^\/draw|малю/i.test(t),
      build: () => ({
        intent: 'відкриваю canvas',
        text: 'дай мені 30 секунд або просто почни сам — я доповню.',
        card: null,
        suggestions: ['я почну', 'ти почни', 'разом'],
      }),
    },
    {
      match: (t) => /^\/agent|зроби|сам/i.test(t),
      build: () => ({
        intent: 'беру задачу · буду самостійно',
        thinking: { duration: 240, steps: ['розумію: «забронюй квитки lhasa на 16 травня»', 'параметри: бізнес · 1 пасажир · ID у vault', 'дозвіл: vault·tickets за твоїми правилами'] },
        tools: [
          { tool: 'fly.search',   query: 'KBP → LXA · 16 трав', ms: 1200, result: '38 варіантів · 4 під фільтр' },
          { tool: 'fly.book',     query: 'TK 5732 + CA 4112',   ms: 4200, result: 'pnr 8F2A1Q · підтверджено · email прийшов' },
          { tool: 'vault.store',  query: 'travel-may-2026.pdf', ms: 84,    result: 'збережено · шифровано' },
        ],
        text: 'забронював. квитки у vault. карта у списку. передзвоню за 3 години до польоту якщо ти не нагадуєш сам.',
        card: <BoardingPass />,
        suggestions: ['скасуй', 'покажи договір', 'надішли мамі'],
      }),
    },
  ];
}

function findReply(text, lib) {
  for (const r of lib) if (r.match(text)) return r.build();
  return {
    text: 'почув. розкажи більше — або скинь файл, або підкажи що зробити.',
    suggestions: ['покажи /команди', 'дай мені тиху годину', '@олі: чи ти вільна?'],
  };
}

// ─── ghost-text predictor for common starts ─────────────────────────────────
function computeGhost(value) {
  if (!value || value.length < 2) return null;
  const lib = [
    ['нагадай ', 'мені о 18:00 забрати посилку якщо погода добра'],
    ['що з ', 'погодою на завтра?'],
    ['/image ', 'захід сонця над lhasa, листопад, шафран та сапфір'],
    ['покажи ', 'мій день'],
    ['скільки ', 'я витратив цього тижня?'],
    ['переклади ', 'цей абзац українською · мʼягко'],
    ['чи ', 'олег ще не відповів?'],
    ['як ', 'я виглядав 6 місяців тому?'],
  ];
  for (const [pre, suf] of lib) {
    if (pre.startsWith(value)) return pre.slice(value.length) + suf;
    if (value.startsWith(pre) && pre.length < value.length + 8) {
      return ''; // already started typing past prefix
    }
  }
  return null;
}

// ─── streaming engine — char-by-char with realistic jitter ──────────────────
function useStreamingMessages(messages, setMessages) {
  React.useEffect(() => {
    const idx = messages.findIndex((m) => m.role === 'agent' && m.streaming);
    if (idx < 0) return;
    const m = messages[idx];
    if (m.shown >= (m.text?.length || 0)) {
      // done streaming
      setMessages((ms) => ms.map((mm, i) => i === idx ? { ...mm, streaming: false } : mm));
      return;
    }
    // randomize step: 1–4 chars per tick
    const step = 1 + Math.floor(Math.random() * 4);
    const delay = 22 + Math.random() * 30;
    const id = setTimeout(() => {
      setMessages((ms) => ms.map((mm, i) => i === idx ? { ...mm, shown: Math.min(mm.shown + step, mm.text.length) } : mm));
    }, delay);
    return () => clearTimeout(id);
  }, [messages, setMessages]);
}

// ─── the scene ──────────────────────────────────────────────────────────────
function LiveChatScene({ onClose }) {
  const replyLib = React.useMemo(makeReplies, []);
  const [messages, setMessages] = React.useState(() => ([
    { role: 'system', text: 'новий день · 14 травня · 19:34 · phantom·v3.1 · ти + я' },
    { role: 'agent', text: 'привіт, кирил. слухаю.', shown: 22, streaming: false, time: '19:34' },
  ]));
  const [draft, setDraft] = React.useState('');
  const [phase, setPhase] = React.useState('idle');
  const [voiceOpen, setVoiceOpen] = React.useState(false);
  const [attachments, setAttachments] = React.useState([]);
  const [autoAudio, setAutoAudio] = React.useState(0.3);
  const streamRef = React.useRef(null);

  // ───── people / groups / overlays ──────────────────────────────────────
  const [people, setPeople] = React.useState(() => window.PHANTOM_PEOPLE_SEED.map((p) => ({ ...p })));
  const [groups, setGroups] = React.useState(() => window.PHANTOM_GROUPS_SEED.map((g) => ({ ...g })));
  const [activeThread, setActiveThread] = React.useState({ kind: 'now', id: null });
  const [panelOpen, setPanelOpen] = React.useState(false);
  const [addPersonOpen, setAddPersonOpen] = React.useState(false);
  const [groupBuilderOpen, setGroupBuilderOpen] = React.useState(false);
  const [profileFor, setProfileFor] = React.useState(null);
  const [groupProfileFor, setGroupProfileFor] = React.useState(null);
  const [threadFor, setThreadFor] = React.useState(null);
  const [connectFlowOpen, setConnectFlowOpen] = React.useState(false);

  // update people / groups
  const upsertPerson = (p) => setPeople((arr) => arr.some((x) => x.id === p.id) ?
    arr.map((x) => x.id === p.id ? { ...x, ...p } : x) : [p, ...arr]);
  const archivePerson = (p) => setPeople((arr) => arr.map((x) => x.id === p.id ? { ...x, archived: true } : x));
  const upsertGroup = (g) => setGroups((arr) => arr.some((x) => x.id === g.id) ?
    arr.map((x) => x.id === g.id ? { ...x, ...g } : x) : [g, ...arr]);

  // active person for header pill
  const activePerson = activeThread.kind === 'person' ?
    people.find((p) => p.id === activeThread.id) : null;

  useStreamingMessages(messages, setMessages);

  // auto-scroll to bottom on new message / streaming
  React.useEffect(() => {
    if (streamRef.current) {
      streamRef.current.scrollTop = streamRef.current.scrollHeight;
    }
  }, [messages]);

  // animated audio for voice mode
  React.useEffect(() => {
    if (!voiceOpen) return;
    let raf, t = 0;
    const tick = () => {
      t += 0.06;
      setAutoAudio(0.3 + Math.abs(Math.sin(t * 1.4)) * 0.4 + Math.random() * 0.1);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [voiceOpen]);

  const ghost = computeGhost(draft);

  const suggestions = React.useMemo(() => {
    const last = messages[messages.length - 1];
    if (draft.length > 0) return null;
    if (last?.role === 'agent' && !last.streaming) {
      // anticipate next moves
      return [
        { text: 'намалюй місяць', kind: 'cmd' },
        { text: '@олі', kind: 'mention' },
        { text: 'збережи розмову у vault', kind: 'plain' },
      ];
    }
    return null;
  }, [messages, draft]);

  // ───── send a user message and orchestrate phantom reply ────────────────
  const sendUser = (text, atts = []) => {
    if (!text.trim() && !atts.length) return;
    setMessages((ms) => [...ms, {
      role: 'user', text: text.trim(), time: liveTime(),
      attachments: atts.length ? atts : null,
    }]);
    setDraft('');
    setAttachments([]);
    setPhase('thinking');
    setTimeout(() => orchestrate(text), 220);
  };

  // run multi-step phantom reply
  const orchestrate = (userText) => {
    const reply = findReply(userText, replyLib);
    const agentMsg = {
      role: 'agent', streaming: false,
      intent: reply.intent || null,
      thinking: null, tools: [],
      text: '', shown: 0, card: null,
      suggestions: null, time: null,
    };
    // step 1: push msg with intent only
    setMessages((ms) => [...ms, agentMsg]);

    // step 2: after a beat, attach thinking
    const delays = [];
    const after = (ms, fn) => { const id = setTimeout(fn, ms); delays.push(id); };

    let cursor = 200;
    if (reply.intent) cursor = 600;

    if (reply.thinking) {
      after(cursor, () => setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
        { ...m, thinking: reply.thinking } : m)));
      cursor += reply.thinking.duration + 200;
    }

    if (reply.tools) {
      reply.tools.forEach((t, idx) => {
        // first show as loading
        after(cursor, () => setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
          { ...m, tools: [...m.tools, { ...t, status: 'loading' }] } : m)));
        after(cursor + t.ms, () => setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
          { ...m, tools: m.tools.map((tt, j) => j === idx ? { ...t, status: 'done' } : tt) } : m)));
        cursor += t.ms + 200;
      });
    }

    // step 3: start streaming text
    if (reply.text) {
      after(cursor, () => setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
        { ...m, text: reply.text, shown: 0, streaming: true, intent: null } : m)));
      cursor += reply.text.length * 32; // approx total stream time
    }

    // step 4: drop card after streaming
    if (reply.card) {
      after(cursor, () => setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
        { ...m, card: reply.card } : m)));
    }

    // step 5: attach suggestions + time
    after(cursor + 200, () => {
      setMessages((arr) => arr.map((m, i) => i === arr.length - 1 ?
        { ...m, suggestions: reply.suggestions, time: liveTime() } : m));
      setPhase('idle');
    });
  };

  // ─── attachment menu picker ──────────────────────────────────────────────
  const onAttachPick = (kind) => {
    const names = {
      camera:  { name: 'кадр з камери · щойно', kind: 'photo' },
      photo:   { name: 'IMG_4221.HEIC',          kind: 'photo' },
      file:    { name: 'brief-v3.pdf',           kind: 'pdf' },
      loc:     { name: 'lhasa · 3656m',          kind: 'loc' },
      draw:    { name: 'sketch · canvas',        kind: 'draw' },
      screen:  { name: 'screen · live',           kind: 'screen' },
    };
    const a = names[kind];
    if (!a) return;
    if (kind === 'photo') {
      // simulate dropping a photo with phantom auto-analysis
      sendUser('фото · подивись що тут', [{ ...a, icon: '◧' }]);
      // override reply with a photo analysis
      setTimeout(() => {
        setMessages((ms) => {
          const last = ms[ms.length - 1];
          if (last?.role !== 'agent') return ms;
          return ms;
        });
      }, 100);
    } else if (kind === 'file') {
      setAttachments((a2) => [...a2, { ...a, icon: '◇' }]);
    } else {
      setAttachments((a2) => [...a2, { ...a, icon: '·' }]);
    }
  };

  const onSlashCommand = (cmd) => {
    setDraft('/' + cmd + ' ');
  };

  const onMention = (p) => {
    setDraft((d) => {
      // replace last @word with @name
      const parts = d.split(/\s/);
      parts[parts.length - 1] = '@' + p.id;
      return parts.join(' ') + ' ';
    });
  };

  const acceptGhost = () => {
    if (ghost) setDraft(draft + ghost);
  };

  const onSuggestionPick = (s) => setDraft((d) => (d ? d + ' ' : '') + s.text);

  const onMsgAction = (action, payload) => {
    if (action === 'suggest') {
      sendUser(payload);
    } else if (action === 'retry') {
      // remove last agent msg and re-orchestrate with last user
      setMessages((ms) => {
        const lastUserIdx = ms.map((m, i) => m.role === 'user' ? i : -1).filter((i) => i >= 0).pop();
        const userMsg = ms[lastUserIdx];
        if (!userMsg) return ms;
        setTimeout(() => orchestrate(userMsg.text), 100);
        return ms.slice(0, ms.length - 1);
      });
    }
  };

  // ──── render ───────────────────────────────────────────────────────────────
  return (
    <>
      <LiveWallpaper variant="sparks" state={phase === 'thinking' ? 'DIALOGUE' : 'FOCUS'} />
      <StatusBar />
      {/* presence header (custom — with thread pill) */}
      <div className="ui-chrome" style={{
        padding: '10px 14px',
        background: 'linear-gradient(to bottom, rgba(14,10,5,0.78), rgba(14,10,5,0))',
        position: 'relative', zIndex: 8,
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <button onClick={onClose} aria-label="back" style={{
          appearance: 'none', cursor: 'pointer', border: 'none',
          width: 28, height: 28, borderRadius: '50%',
          background: 'rgba(255,230,180,0.04)',
          border: '0.5px solid var(--glass-edge)',
          color: 'var(--ink-secondary)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          font: '400 13px/1 var(--font-mono)', flexShrink: 0,
        }}>←</button>
        {/* mini orb pulsing with phase */}
        <div style={{
          width: 24, height: 24, flexShrink: 0, borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #FFD370, #F4AF25 50%, #7A4818 100%)',
          border: '0.5px solid rgba(255,230,180,0.2)',
          boxShadow: '0 0 8px var(--gold-glow)',
          animation: phase === 'thinking' ? 'phantom-pulse-glow 1.2s ease-in-out infinite' :
                     'phantom-breathe 8s ease-in-out infinite',
        }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="state-label" style={{ fontSize: 11, color: 'var(--ink-strong)' }}>ДІАЛОГ</div>
          <div className="mono" style={{
            fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.04em', marginTop: 2,
            display: 'flex', alignItems: 'center', gap: 4,
          }}>
            {phase !== 'idle' && (
              <span style={{
                width: 4, height: 4, borderRadius: '50%', background: 'var(--gold)',
                animation: 'phantom-pulse-glow 1.2s ease-in-out infinite',
              }} />
            )}
            · {phase === 'thinking' ? 'думаю' : 'тут і зараз'}
          </div>
        </div>
        <ThreadPill thread="now" person={activePerson} onOpen={() => setPanelOpen(true)} />
      </div>
      <div ref={streamRef} style={{
        flex: 1, overflow: 'auto', minHeight: 0,
        padding: '6px 14px 12px', position: 'relative', zIndex: 5,
        scrollBehavior: 'smooth',
      }}>
        {messages.map((m, i) => {
          if (m.role === 'system') {
            return <SystemWhisper key={i} text={m.text} />;
          }
          if (m.role === 'user') {
            return <LiveUserBubble key={i} text={m.text} time={m.time}
                                    attachments={m.attachments} />;
          }
          return <LiveAgentBubble key={i}
                                  intent={m.intent}
                                  thinking={m.thinking}
                                  tools={m.tools?.length ? m.tools : null}
                                  text={m.text || null} shown={m.shown || 0}
                                  card={m.card}
                                  suggestions={m.suggestions}
                                  time={m.time}
                                  status={m.streaming ? 'streaming' :
                                          (m.thinking && !m.text) ? 'thinking' : 'idle'}
                                  onAction={onMsgAction} />;
        })}
      </div>
      <Composer
        value={draft} setValue={setDraft}
        onSend={(t) => sendUser(t, attachments)}
        onVoiceOpen={() => setVoiceOpen(true)}
        attachments={attachments}
        onRemoveAttach={(i) => setAttachments((a) => a.filter((_, j) => j !== i))}
        onSlashCommand={onSlashCommand}
        onMention={onMention}
        ghost={ghost}
        onAcceptGhost={acceptGhost}
        suggestions={suggestions}
        onSuggestionPick={onSuggestionPick}
        onAttachPick={onAttachPick}
        phase={phase}
      />
      {voiceOpen && (
        <VoiceMode
          onClose={() => setVoiceOpen(false)}
          audio={autoAudio}
          phantomSaying="· я тебе слухаю · говори природно ·"
        />
      )}
      {/* ─── people overlays ───────────────────────────────────── */}
      <AtlasOverview
        open={panelOpen} onClose={() => setPanelOpen(false)}
        people={people} groups={groups}
        onOpenPerson={(p) => { setPanelOpen(false); setThreadFor(p); }}
        onOpenGroup={(g) => { setPanelOpen(false); setGroupProfileFor(g); }}
        onEditPerson={(p) => { setPanelOpen(false); setProfileFor(p); }}
        onEditGroup={(g) => { setPanelOpen(false); setGroupProfileFor(g); }}
        onAddPerson={() => setAddPersonOpen(true)}
        onAddGroup={() => setGroupBuilderOpen(true)}
        onConnect={() => setConnectFlowOpen(true)}
      />
      <FirstConnectFlow open={connectFlowOpen} people={people}
                        onClose={() => setConnectFlowOpen(false)}
                        onConnect={(result) => {
                          setConnectFlowOpen(false);
                          setAddPersonOpen(true);
                        }} />
      <AddPersonFlow open={addPersonOpen}
                     onClose={() => setAddPersonOpen(false)}
                     onCreate={(p) => { upsertPerson(p); setAddPersonOpen(false); setProfileFor(p); }} />
      <GroupBuilder open={groupBuilderOpen} people={people}
                    onClose={() => setGroupBuilderOpen(false)}
                    onCreate={(g) => { upsertGroup(g); setGroupBuilderOpen(false); setGroupProfileFor(g); }} />
      <PersonProfile open={!!profileFor} person={profileFor}
                     onClose={() => setProfileFor(null)}
                     onSave={(p) => upsertPerson(p)}
                     onArchive={(p) => archivePerson(p)}
                     onOpenChat={(p) => { setProfileFor(null); setThreadFor(p); }} />
      <GroupProfile open={!!groupProfileFor} group={groupProfileFor} people={people}
                    onClose={() => setGroupProfileFor(null)}
                    onSave={(g) => upsertGroup(g)}
                    onOpenChat={(g) => { setGroupProfileFor(null); /* future: open group thread */ }} />
      <PersonThread open={!!threadFor} person={threadFor}
                    onClose={() => setThreadFor(null)} />
    </>
  );
}

Object.assign(window, { LiveChatScene, useStreamingMessages, computeGhost });
