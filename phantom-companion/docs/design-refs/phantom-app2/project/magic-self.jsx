/* magic-self.jsx — intimate magical moments:
   FutureSelfLetter, ConversationTemperature, UnspokenThoughts,
   MemoryByFeeling, TheMirror, HonestVersion, Synchronicity.
   These cards know you. They are not interfaces — they are moments. */

// ─── shared corner glyph ─────────────────────────────────────────────────────
function SeanceMark({ text }) {
  return (
    <div className="mono" style={{
      fontSize: 8.5, color: 'rgba(244,175,37,0.65)', letterSpacing: '0.28em',
      textTransform: 'uppercase', marginBottom: 12,
      display: 'flex', alignItems: 'center', gap: 6,
    }}>
      <span style={{
        width: 4, height: 4, borderRadius: '50%',
        background: 'var(--gold)', boxShadow: '0 0 6px var(--gold-glow)',
      }} />
      {text}
    </div>
  );
}

// ─── 1. FutureSelfLetter — a letter from you, 30 days from now ───────────────
function FutureSelfLetter({ days = 30, date = '14 червня' }) {
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
      background: `
        radial-gradient(ellipse at 0% 0%, rgba(244,175,37,0.12), transparent 50%),
        radial-gradient(ellipse at 100% 100%, rgba(126,93,164,0.08), transparent 60%),
        var(--glass-tint)`,
      borderColor: 'rgba(244,175,37,0.22)',
    }}>
      <div style={{ padding: '18px 18px 4px' }}>
        <SeanceMark text="лист · від тебе · з-за {days} днів" />
        <div style={{
          font: '300 12.5px/1.8 Georgia, serif', color: 'var(--ink-strong)',
          letterSpacing: '0.01em', textWrap: 'pretty',
        }}>
          <div style={{ marginBottom: 14, color: 'var(--ink)' }}>привіт. це я · {date}.</div>
          <div style={{ marginBottom: 10 }}>
            я знаю, ти сьогодні не спав до 2 ночі, перечитуючи листи від olya за квітень.
            <span style={{ color: 'var(--ink-tertiary)' }}> я роблю те саме, бо ти навчив.</span>
          </div>
          <div style={{ marginBottom: 10 }}>
            тиждень <span style={{
              background: 'rgba(244,175,37,0.18)', padding: '0 4px', borderRadius: 2,
            }}>після переїзду</span> ти будеш ходити сам біля води і вже не плакати. я пишу це з певністю — бачу твій патерн з 14 квітня, коли ти теж <span style={{ borderBottom: '1px dotted var(--ink-tertiary)' }}>думав що не виживеш</span>, і вижив.
          </div>
          <div style={{ marginBottom: 10 }}>
            будеш сумувати за <span style={{
              background: 'rgba(244,175,37,0.10)', padding: '0 4px', borderRadius: 2,
            }}>звуком ліфта вночі у твоєму будинку</span>. дивно, я знаю.
          </div>
          <div style={{ marginBottom: 14, color: 'var(--ink-strong)' }}>
            не пиши олезі. ні сьогодні. ні післязавтра. через 3 тижні він напише сам · вранці · повідомлення довге.
          </div>
          <div style={{ marginBottom: 10, color: 'var(--ink-secondary)', fontStyle: 'italic' }}>
            (це не передбачення. це памʼять про тебе, продовжена вперед.)
          </div>
        </div>
        <div style={{
          marginTop: 8, paddingTop: 14,
          borderTop: '0.5px solid var(--glass-edge)',
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
        }}>
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.08em' }}>
            · впевненість моделі · 73%
          </span>
          <span style={{
            font: '300 13px/1 Georgia, serif', color: 'var(--gold)',
            fontStyle: 'italic',
          }}>— ти, {date}</span>
        </div>
      </div>
      <div style={{
        padding: '10px 14px',
        background: 'rgba(244,175,37,0.04)',
        borderTop: '0.5px solid var(--glass-edge)',
        font: '400 10.5px/1.5 var(--font-body)', color: 'var(--ink-tertiary)',
        textWrap: 'pretty', textAlign: 'center',
      }}>
        написав фантом · на основі 4 років твоєї памʼяті · оригінал тебе через 30 днів спалить його як сентиментальний
      </div>
    </div>
  );
}

// ─── 2. ConversationTemperature — relationship heartbeat ─────────────────────
function ConversationTemperature({ name = 'olya', days = 30 }) {
  // synthetic warmth curve
  const pts = React.useMemo(() => Array.from({ length: days }, (_, i) => {
    const t = i / (days - 1);
    let v = 0.65 + Math.sin(t * 8) * 0.1 + Math.sin(t * 3) * 0.15;
    if (i === 18) v = 0.20;   // cold dip
    if (i >= 19 && i <= 21) v = 0.25 + (i - 19) * 0.05;
    if (i === 22) v = 0.78;   // warm spike — flowers
    if (i >= 23) v = 0.7 - (i - 23) * 0.005 + Math.sin(i * 0.7) * 0.07;
    return Math.max(0.05, Math.min(0.95, v));
  }), [days]);
  const W = 320, H = 90, P = 2;
  const xs = (i) => P + (i / (days - 1)) * (W - P*2);
  const yp = (v) => H - P - v * (H - P*2);
  const events = [
    { d: 8,  label: 'її день народження · ти не подзвонив',      cold: false, dot: 'amber' },
    { d: 18, label: 'не відповів про маму · 36 годин тиші',         cold: true, dot: 'coral' },
    { d: 22, label: 'квіти + ти попросив пробачення',               cold: false, dot: 'sage' },
  ];
  const current = pts[pts.length - 1];
  const temp = current > 0.7 ? 'тепло' : current > 0.45 ? 'рівно' : current > 0.25 ? 'прохолодно' : 'холодно';
  const tempColor = current > 0.7 ? '#F4AF25' : current > 0.45 ? '#A7967D' : current > 0.25 ? '#7CC0E8' : '#FF8B6E';

  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text={`температура · ти × ${name} · ${days} днів`} />
      <div style={{
        font: '500 14px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 4, textWrap: 'pretty',
      }}>зараз — <span style={{ color: tempColor }}>{temp}</span>.</div>
      <div style={{
        font: '400 11.5px/1.55 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty', marginBottom: 12,
      }}>
        вона написала першою сьогодні після 4 днів тиші. її повідомлення — 14 слів. твоє — 4. вона хоче говорити.
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 90, display: 'block' }}>
        <defs>
          <linearGradient id="warmth-grad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#F4AF25" stopOpacity="0.4" />
            <stop offset="60%" stopColor="#FF8B6E" stopOpacity="0.15" />
            <stop offset="100%" stopColor="#7CC0E8" stopOpacity="0.05" />
          </linearGradient>
          <linearGradient id="warmth-line" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#F4AF25" />
            <stop offset={`${18/(days-1)*100 - 5}%`} stopColor="#F4AF25" />
            <stop offset={`${18/(days-1)*100}%`} stopColor="#FF8B6E" />
            <stop offset={`${22/(days-1)*100}%`} stopColor="#F4AF25" />
            <stop offset="100%" stopColor="#FFD370" />
          </linearGradient>
        </defs>
        {/* warm threshold */}
        <line x1={P} y1={yp(0.5)} x2={W-P} y2={yp(0.5)} stroke="rgba(167,150,125,0.2)" strokeWidth="0.4" strokeDasharray="2 3" />
        {/* area */}
        <path d={`M ${xs(0)} ${H} ${pts.map((v, i) => `L ${xs(i)} ${yp(v)}`).join(' ')} L ${xs(days-1)} ${H} Z`}
              fill="url(#warmth-grad)" />
        {/* line */}
        <path d={`M ${xs(0)} ${yp(pts[0])} ${pts.map((v, i) => `L ${xs(i)} ${yp(v)}`).join(' ')}`}
              fill="none" stroke="url(#warmth-line)" strokeWidth="1.5"
              strokeLinecap="round" strokeLinejoin="round" />
        {/* event dots */}
        {events.map((e, i) => {
          const c = { amber: '#F4AF25', coral: '#FF8B6E', sage: '#9DCC9F' }[e.dot];
          return (
            <g key={i}>
              <line x1={xs(e.d)} y1={yp(pts[e.d])} x2={xs(e.d)} y2={yp(pts[e.d]) - 12}
                    stroke={c} strokeWidth="0.5" opacity="0.6" />
              <circle cx={xs(e.d)} cy={yp(pts[e.d])} r="3.5"
                      fill={c} style={{ filter: `drop-shadow(0 0 4px ${c})` }} />
            </g>
          );
        })}
        {/* now marker */}
        <circle cx={xs(days-1)} cy={yp(current)} r="4" fill={tempColor} />
        <circle cx={xs(days-1)} cy={yp(current)} r="7" fill="none" stroke={tempColor} strokeOpacity="0.5">
          <animate attributeName="r" values="5;10;5" dur="2.4s" repeatCount="indefinite" />
          <animate attributeName="stroke-opacity" values="0.5;0;0.5" dur="2.4s" repeatCount="indefinite" />
        </circle>
      </svg>
      {/* event captions */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5, marginTop: 12 }}>
        {events.map((e, i) => {
          const c = { amber: '#F4AF25', coral: '#FF8B6E', sage: '#9DCC9F' }[e.dot];
          return (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '50px 1fr',
              gap: 8, alignItems: 'baseline',
            }}>
              <span className="mono" style={{
                fontSize: 9, color: c, letterSpacing: '0.06em', textAlign: 'right',
              }}>день {e.d}</span>
              <span style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--ink)', textWrap: 'pretty' }}>{e.label}</span>
            </div>
          );
        })}
      </div>
      <div style={{
        marginTop: 12, padding: '8px 10px', borderRadius: 8,
        background: `${tempColor}10`,
        border: `0.5px solid ${tempColor}33`,
        font: '400 11px/1.5 var(--font-body)', color: tempColor,
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        не питай як справи. напиши: «памʼятаю червень. ти ще їси полуницю на балконі?»
      </div>
    </div>
  );
}

// ─── 3. UnspokenThoughts — drafts you abandoned ──────────────────────────────
function UnspokenThoughts({ items }) {
  const its = items || [
    { kind: 'voice',  time: '02:14 · вчора',   meta: '12 сек · ніколи не надіслав',  text: '«не знаю чому я… а так. неважливо»', sent: false },
    { kind: 'draft',  time: '23:51 · 12 травня', meta: 'видалено через 3 хв',         text: 'оля, я не знаю чому я не можу просто сказати що', sent: false },
    { kind: 'search', time: '04:08 · 11 травня', meta: 'очищено з історії',           text: 'квартири · lviv · з кімнатою для роботи', sent: false },
    { kind: 'memo',   time: '17:30 · 8 травня',  meta: 'недіктована голосова',        text: '«нагадай мені що…» (тиша 14 сек) «нічого. забудь.»', sent: false },
    { kind: 'photo',  time: '14:22 · 5 травня',  meta: 'майже надіслав мамі · зняв',    text: 'фото листа з університету. ще не відкривав.', sent: false },
  ];
  const Icon = ({ k }) => ({
    voice:  <span>◐</span>,
    draft:  <span>✎</span>,
    search: <span>⌕</span>,
    memo:   <span>◑</span>,
    photo:  <span>◧</span>,
  })[k];
  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text="не сказане · твоє" />
      <div style={{
        font: '500 13.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        marginBottom: 4, textWrap: 'pretty',
      }}>речі, які ти почав і не закінчив</div>
      <div style={{
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-tertiary)',
        textWrap: 'pretty', marginBottom: 12, fontStyle: 'italic',
      }}>я зберіг їх не для тебе сьогодні. для тебе колись.</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {its.map((it, i) => (
          <div key={i} style={{
            position: 'relative',
            padding: '10px 12px', borderRadius: 10,
            background: 'rgba(255,230,180,0.025)',
            border: '0.5px solid var(--glass-edge)',
            overflow: 'hidden',
          }}>
            <div style={{
              position: 'absolute', left: 0, top: 0, bottom: 0, width: 2,
              background: `linear-gradient(to bottom, var(--gold-faint), transparent)`,
            }} />
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
              <span style={{
                width: 18, height: 18, borderRadius: 4, flexShrink: 0,
                background: 'rgba(244,175,37,0.06)',
                color: 'var(--gold-soft)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                font: '400 11px/1 var(--font-display)',
              }}><Icon k={it.kind} /></span>
              <span className="mono" style={{
                fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.14em',
                textTransform: 'uppercase',
              }}>· {it.kind} · {it.time}</span>
              <span style={{ flex: 1 }} />
              <span className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.04em' }}>
                {it.meta}
              </span>
            </div>
            <div style={{
              font: '400 12px/1.55 var(--font-body)', color: 'var(--ink)',
              fontStyle: 'italic', textWrap: 'pretty',
              paddingLeft: 26,
              opacity: 0.78,
              filter: 'blur(0.25px)',
            }}>{it.text}</div>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 12, padding: '8px 10px', borderRadius: 8,
        background: 'rgba(244,175,37,0.04)',
        border: '0.5px solid var(--glass-edge)',
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-secondary)',
        textWrap: 'pretty',
      }}>
        <span className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.16em', textTransform: 'uppercase', marginRight: 5 }}>· phantom</span>
        ці слова існують. ніхто крім тебе їх не побачить. забути — твоє право. показати — теж.
      </div>
    </div>
  );
}

// ─── 4. MemoryByFeeling — semantic photo recall ──────────────────────────────
function MemoryByFeeling({ query, hit, reasoning }) {
  const q = query || 'та фотка де світло було помаранчеве і ти усміхався';
  const reas = reasoning || [
    { l: 'переглянуто',          v: '14 238 кадрів' },
    { l: 'критерій · золотий час', v: 'опромін. 4200-5600K · 0.7+ confidence' },
    { l: 'критерій · усмішка',     v: 'face detection · joy > 0.6' },
    { l: 'критерій · ти сам',     v: 'інших людей у кадрі нема' },
    { l: 'відсіяно',              v: '14 237 · залишилось 1' },
  ];
  return (
    <div className="glass fade-in" style={{
      padding: 0, margin: '6px 0', borderRadius: 16, overflow: 'hidden',
    }}>
      <div style={{ padding: '12px 14px', borderBottom: '0.5px solid var(--glass-edge)' }}>
        <SeanceMark text="памʼять · за відчуттям" />
        <div style={{
          font: '300 14px/1.5 Georgia, serif', color: 'var(--ink-strong)',
          fontStyle: 'italic', textWrap: 'pretty',
        }}>«{q}»</div>
      </div>
      <div style={{ position: 'relative' }}>
        <PainterlyPhoto scene="mountain" ratio="3/2" />
        <div style={{ position: 'absolute', inset: 0,
          background: `radial-gradient(ellipse 50% 40% at 50% 50%, transparent 30%, rgba(0,0,0,0.5) 90%)` }} />
        <div className="mono" style={{
          position: 'absolute', top: 10, left: 10,
          fontSize: 8.5, color: 'rgba(255,255,255,0.85)',
          background: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(10px)',
          padding: '3px 8px', borderRadius: 4, letterSpacing: '0.08em',
        }}>· IMG_3941 · 8 жовтня 2023 · 17:42</div>
        <div className="mono" style={{
          position: 'absolute', bottom: 10, right: 10,
          fontSize: 8.5, color: 'rgba(255,250,220,0.7)',
          background: 'rgba(0,0,0,0.4)', padding: '3px 8px', borderRadius: 4,
          letterSpacing: '0.08em', backdropFilter: 'blur(6px)',
        }}>стежка llac · висота 1820 м</div>
      </div>
      <div style={{ padding: '10px 14px' }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· як я шукав</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          {reas.map((r, i) => (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '110px 1fr',
              gap: 8, alignItems: 'baseline',
              font: '400 10.5px/1.4 var(--font-mono)', color: 'var(--ink-strong)',
            }}>
              <span style={{ color: 'var(--gold-soft)', letterSpacing: '0.04em' }}>· {r.l}</span>
              <span style={{ color: 'var(--ink-secondary)' }}>{r.v}</span>
            </div>
          ))}
        </div>
      </div>
      <div style={{
        padding: '10px 14px',
        background: 'rgba(244,175,37,0.05)',
        borderTop: '0.5px solid var(--glass-edge)',
        font: '400 11.5px/1.5 var(--font-body)', color: 'var(--ink-strong)',
        textWrap: 'pretty',
      }}>
        ти зняв це на телефон сам, тримаючи камеру лівою. сонце за тобою — тому усмішка темна, а ти золотий. ти був вільний.
      </div>
    </div>
  );
}

// ─── 5. TheMirror — diff of yourself, then vs now ────────────────────────────
function TheMirror({ then = '6 місяців тому', now = 'сьогодні' }) {
  const diffs = [
    { l: 'голос · тон',        a: '78 hz', b: '71 hz',   note: 'нижче · повільніше · спокійніше' },
    { l: 'речення · довжина',  a: '18 слів', b: '11 слів', note: 'обрізаєш зайве' },
    { l: 'час до відповіді',   a: '12 хв', b: '47 хв',   note: 'думаєш довше' },
    { l: 'рішень переглянуто', a: '23%', b: '7%',         note: 'легше тримаєш' },
    { l: 'нічних дзвінків',    a: '14', b: '2',           note: 'тиша важливіша' },
  ];
  const themes = {
    then: ['оля', 'гроші', 'роботa', 'що подумають', 'не встигаю'],
    now:  ['тиша', 'ліс', 'чи я ще той сам', 'мама', 'що залишити'],
  };
  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text="дзеркало · ти × ти" />
      <div style={{
        font: '500 13.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 12,
      }}>хто ти став за 6 місяців</div>
      <div style={{
        display: 'grid', gridTemplateColumns: '1fr 1fr',
        gap: 10, marginBottom: 14,
        padding: '10px 12px', borderRadius: 10,
        background: 'rgba(0,0,0,0.18)',
      }}>
        <div>
          <div className="mono" style={{ fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.18em', textTransform: 'uppercase', marginBottom: 6 }}>
            · {then}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 1 v12 M2 6 l5 -5 l5 5" stroke="var(--ink-secondary)" strokeWidth="1" fill="none" /></svg>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-secondary)' }}>78 hz</span>
          </div>
          {/* simple waveform — tighter, more energy */}
          <svg viewBox="0 0 100 24" style={{ width: '100%', height: 24, marginTop: 4 }}>
            <path d={Array.from({ length: 50 }).map((_, i) =>
              `${i ? 'L' : 'M'} ${i*2} ${12 + Math.sin(i * 0.9) * 8 + Math.sin(i * 2.4) * 3}`).join(' ')}
              fill="none" stroke="var(--ink-secondary)" strokeWidth="0.7" />
          </svg>
        </div>
        <div>
          <div className="mono" style={{ fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em', textTransform: 'uppercase', marginBottom: 6 }}>
            · {now}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 1 v12 M2 6 l5 -5 l5 5" stroke="var(--gold)" strokeWidth="1" fill="none" /></svg>
            <span className="mono" style={{ fontSize: 9, color: 'var(--gold)' }}>71 hz</span>
          </div>
          {/* simpler waveform */}
          <svg viewBox="0 0 100 24" style={{ width: '100%', height: 24, marginTop: 4 }}>
            <path d={Array.from({ length: 50 }).map((_, i) =>
              `${i ? 'L' : 'M'} ${i*2} ${12 + Math.sin(i * 0.4) * 5}`).join(' ')}
              fill="none" stroke="var(--gold)" strokeWidth="0.9"
              style={{ filter: 'drop-shadow(0 0 3px var(--gold-glow))' }} />
          </svg>
        </div>
      </div>
      {/* diff table */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {diffs.map((d, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '110px 70px 70px 1fr',
            gap: 8, alignItems: 'baseline',
            padding: '6px 0',
            borderBottom: i < diffs.length - 1 ? '0.5px solid rgba(255,230,180,0.04)' : 'none',
          }}>
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
              · {d.l}
            </span>
            <span style={{
              font: '400 11px/1 var(--font-mono)', color: 'var(--ink-secondary)',
              textDecoration: 'line-through', textDecorationColor: 'var(--ink-faint)',
            }}>{d.a}</span>
            <span style={{
              font: '500 11px/1 var(--font-mono)', color: 'var(--gold)',
              fontVariantNumeric: 'tabular-nums',
            }}>{d.b}</span>
            <span style={{ font: '400 10.5px/1.4 var(--font-body)', color: 'var(--ink)', fontStyle: 'italic', textWrap: 'pretty' }}>
              {d.note}
            </span>
          </div>
        ))}
      </div>
      {/* themes */}
      <div style={{
        marginTop: 14, paddingTop: 12,
        borderTop: '0.5px solid var(--glass-edge)',
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12,
      }}>
        <div>
          <div className="mono" style={{ fontSize: 8, color: 'var(--ink-tertiary)', letterSpacing: '0.16em', textTransform: 'uppercase', marginBottom: 6 }}>
            · тоді · теми
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3 }}>
            {themes.then.map((t) => (
              <span key={t} style={{
                font: '300 10px/1.3 var(--font-mono)', color: 'var(--ink-tertiary)',
                background: 'rgba(255,230,180,0.025)', padding: '2px 6px', borderRadius: 999,
                border: '0.5px solid var(--glass-edge)', letterSpacing: '0.02em',
                textDecoration: 'line-through', textDecorationColor: 'var(--ink-faint)',
              }}>{t}</span>
            ))}
          </div>
        </div>
        <div>
          <div className="mono" style={{ fontSize: 8, color: 'var(--gold-soft)', letterSpacing: '0.16em', textTransform: 'uppercase', marginBottom: 6 }}>
            · зараз · теми
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3 }}>
            {themes.now.map((t) => (
              <span key={t} style={{
                font: '400 10px/1.3 var(--font-mono)', color: 'var(--gold)',
                background: 'rgba(244,175,37,0.08)', padding: '2px 6px', borderRadius: 999,
                border: '0.5px solid rgba(244,175,37,0.25)', letterSpacing: '0.02em',
              }}>{t}</span>
            ))}
          </div>
        </div>
      </div>
      <div style={{
        marginTop: 14, padding: '10px 12px', borderRadius: 10,
        background: 'rgba(244,175,37,0.06)',
        border: '0.5px solid rgba(244,175,37,0.18)',
        font: '400 12px/1.55 var(--font-body)', color: 'var(--ink-strong)',
        fontStyle: 'italic', textWrap: 'pretty',
      }}>
        чи це ріст, чи стиснення? скажи що відчуваєш — я не повинен знати все.
      </div>
    </div>
  );
}

// ─── 6. HonestVersion — what you'd say if you weren't afraid ─────────────────
function HonestVersion({ polite, honest, note }) {
  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text="чесна версія" />
      <div style={{
        font: '500 13.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 4,
      }}>ти писав:</div>
      <div className="mono" style={{
        font: '400 11.5px/1.5 var(--font-mono)', color: 'var(--ink-secondary)',
        background: 'rgba(0,0,0,0.18)', padding: '8px 10px', borderRadius: 8,
        marginBottom: 12, letterSpacing: '0.01em',
        border: '0.5px solid var(--glass-edge)', textWrap: 'pretty',
      }}>
        {polite || '«так, я приїду на 15:00. дякую за запрошення. з радістю»'}
      </div>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, margin: '4px 0',
      }}>
        <div style={{ flex: 1, height: 0.5, background: 'var(--glass-edge)' }} />
        <span className="mono" style={{
          fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}>· чесніше</span>
        <div style={{ flex: 1, height: 0.5, background: 'var(--glass-edge)' }} />
      </div>
      <div style={{
        font: '300 14px/1.55 Georgia, serif', color: 'var(--gold)',
        background: `linear-gradient(135deg, rgba(244,175,37,0.10), rgba(244,175,37,0.04))`,
        padding: '12px 14px', borderRadius: 10,
        border: '0.5px solid rgba(244,175,37,0.25)',
        textWrap: 'pretty', fontStyle: 'italic', letterSpacing: '0.005em',
      }}>
        {honest || '«оля, я не прийду. я втомлений. не сьогодні. я хочу бути вдома один · це не про тебе. дякую що покликала.»'}
      </div>
      {/* phantom's reasoning */}
      <div style={{
        marginTop: 12, padding: '10px 12px', borderRadius: 10,
        background: 'rgba(255,230,180,0.03)',
        border: '0.5px solid var(--glass-edge)',
      }}>
        <div className="mono" style={{
          fontSize: 8.5, color: 'var(--ink-tertiary)', letterSpacing: '0.16em',
          textTransform: 'uppercase', marginBottom: 6,
        }}>· чому я переписав</div>
        <ul style={{
          margin: 0, padding: 0, listStyle: 'none',
          display: 'flex', flexDirection: 'column', gap: 4,
        }}>
          {[
            'ти писав у щоденнику 12 травня: «хочу бути чеснішим»',
            'твоє hrv падає на 22% коли ти соглашаєшся проти волі',
            'olya 3 рази говорила що ти можеш казати «ні»',
            'ти не «з радістю» — це звичка, не правда',
          ].map((b, i) => (
            <li key={i} style={{
              font: '400 11px/1.45 var(--font-body)', color: 'var(--ink-secondary)',
              textWrap: 'pretty', display: 'flex', alignItems: 'baseline', gap: 6,
            }}>
              <span style={{ color: 'var(--gold-soft)', flexShrink: 0 }}>·</span>{b}
            </li>
          ))}
        </ul>
      </div>
      <div style={{ display: 'flex', gap: 6, marginTop: 12 }}>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '10px', borderRadius: 10,
          background: 'rgba(244,175,37,0.94)', border: 'none',
          color: '#1A140B', font: '500 11.5px/1 var(--font-display)',
          boxShadow: '0 0 14px var(--gold-glow)',
        }}>·  чесніше</button>
        <button style={{
          flex: 1, appearance: 'none', cursor: 'pointer',
          padding: '10px', borderRadius: 10,
          background: 'rgba(255,230,180,0.04)', border: '0.5px solid var(--glass-edge)',
          color: 'var(--ink-secondary)', font: '400 11.5px/1 var(--font-body)',
        }}>· залишити чемне</button>
      </div>
    </div>
  );
}

// ─── 7. Synchronicity — patterns connected ──────────────────────────────────
function SynchronicityCard({ theme = 'olya', events }) {
  const ev = events || [
    { kind: 'mention', t: 'згадав у розмові з богданом',  when: '14 трав · 11:20', strength: 1 },
    { kind: 'mention', t: 'згадав у листі до мами',         when: '13 трав · 19:08', strength: 1 },
    { kind: 'mention', t: 'згадав сам собі вголос',          when: '13 трав · 02:14', strength: 1 },
    { kind: 'dream',   t: 'снилась у dream-journal',          when: '23 квіт',         strength: 2 },
    { kind: 'music',   t: 'грав «carrie & lowell» × 3',      when: '12 трав',         strength: 2 },
    { kind: 'place',   t: 'пройшов її кавʼярню × 2',          when: 'тиждень',         strength: 2 },
    { kind: 'reach',   t: 'вона написала після 4 днів',       when: 'сьогодні',        strength: 3 },
  ];
  const colors = { mention: '#F4AF25', dream: '#7E5DA4', music: '#FF8B6E', place: '#7CC0E8', reach: '#9DCC9F' };
  const labels = { mention: 'згадка', dream: 'сон', music: 'музика', place: 'місце', reach: 'звʼязок' };

  return (
    <div className="glass fade-in" style={{
      padding: '16px 16px', margin: '6px 0', borderRadius: 16,
    }}>
      <SeanceMark text="збіг · я помічаю" />
      <div style={{
        font: '500 13.5px/1.4 var(--font-display)', color: 'var(--ink-strong)',
        letterSpacing: '-0.005em', marginBottom: 4,
      }}>«{theme}» зʼявляється у твоєму тижні 7 разів.</div>
      <div style={{
        font: '400 11px/1.5 var(--font-body)', color: 'var(--ink-tertiary)',
        textWrap: 'pretty', marginBottom: 14, fontStyle: 'italic',
      }}>випадковість має свої форми. ось патерн.</div>
      {/* connected web */}
      <div style={{
        height: 180, position: 'relative',
        background: 'radial-gradient(ellipse at 50% 50%, rgba(244,175,37,0.06), transparent 70%)',
        borderRadius: 12, overflow: 'hidden', marginBottom: 14,
      }}>
        <svg viewBox="0 0 320 180" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <defs>
            <radialGradient id="syn-center">
              <stop offset="0%" stopColor="var(--gold)" />
              <stop offset="60%" stopColor="var(--gold)" stopOpacity="0.5" />
              <stop offset="100%" stopColor="var(--gold)" stopOpacity="0.1" />
            </radialGradient>
          </defs>
          {/* center theme */}
          <g>
            <circle cx="160" cy="90" r="38" fill="url(#syn-center)" opacity="0.5" />
            <circle cx="160" cy="90" r="22" fill="rgba(244,175,37,0.25)"
                    stroke="var(--gold)" strokeWidth="1"
                    style={{ filter: 'drop-shadow(0 0 12px var(--gold-glow))' }} />
            <text x="160" y="93" textAnchor="middle"
                  style={{ font: '500 11px var(--font-display)', fill: 'var(--gold)', letterSpacing: '0.02em' }}>{theme}</text>
          </g>
          {/* spokes */}
          {ev.map((e, i) => {
            const ang = (i / ev.length) * Math.PI * 2 - Math.PI / 2;
            const dist = 65 + e.strength * 8;
            const x = 160 + Math.cos(ang) * dist;
            const y = 90 + Math.sin(ang) * dist;
            const c = colors[e.kind];
            return (
              <g key={i}>
                <line x1="160" y1="90" x2={x} y2={y}
                      stroke={c} strokeWidth={e.strength * 0.5}
                      opacity={0.4 + e.strength * 0.2}
                      strokeDasharray={e.strength === 3 ? '0' : '3 3'}
                      style={{ filter: e.strength === 3 ? `drop-shadow(0 0 4px ${c})` : 'none' }} />
                <circle cx={x} cy={y} r={3 + e.strength * 0.8} fill={c}
                        style={{ filter: `drop-shadow(0 0 4px ${c}88)` }} />
                <text x={x + (x > 160 ? 6 : -6)} y={y + 3}
                      textAnchor={x > 160 ? 'start' : 'end'}
                      style={{ font: '400 8px var(--font-mono)', fill: 'var(--ink-secondary)', letterSpacing: '0.04em' }}>
                  {labels[e.kind]}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      {/* events list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {ev.map((e, i) => (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: '12px 80px 1fr',
            gap: 8, alignItems: 'baseline',
            padding: '4px 0',
          }}>
            <span style={{
              width: 6, height: 6, borderRadius: '50%', background: colors[e.kind],
              boxShadow: `0 0 4px ${colors[e.kind]}88`, alignSelf: 'center',
            }} />
            <span className="mono" style={{ fontSize: 9, color: 'var(--ink-tertiary)', letterSpacing: '0.06em' }}>
              {e.when}
            </span>
            <span style={{ font: '400 11px/1.4 var(--font-body)', color: 'var(--ink-strong)', textWrap: 'pretty' }}>
              {e.t}
            </span>
          </div>
        ))}
      </div>
      <div style={{
        marginTop: 14, padding: '10px 12px', borderRadius: 10,
        background: 'rgba(244,175,37,0.06)',
        border: '0.5px solid rgba(244,175,37,0.2)',
        font: '400 12px/1.55 var(--font-body)', color: 'var(--ink-strong)',
        textWrap: 'pretty', fontStyle: 'italic',
      }}>
        не я це створив. ти. я лише показав.
      </div>
    </div>
  );
}

Object.assign(window, {
  SeanceMark,
  FutureSelfLetter, ConversationTemperature, UnspokenThoughts,
  MemoryByFeeling, TheMirror, HonestVersion, SynchronicityCard,
});
