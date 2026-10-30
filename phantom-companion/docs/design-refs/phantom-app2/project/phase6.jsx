/* phase6.jsx — SéanceScene.
   The third layer. Not cards. Moments.
   Phantom acting on you, with you, before you, after you. */

// pause — a beat of breathing room between moments
function SeancePause({ text }) {
  return (
    <div style={{
      padding: '22px 6px 16px',
      font: '300 13px/1.7 Georgia, serif',
      color: 'var(--ink-secondary)',
      fontStyle: 'italic', textWrap: 'pretty',
      letterSpacing: '0.01em',
      borderLeft: '0.5px solid rgba(244,175,37,0.18)',
      paddingLeft: 14, marginLeft: 4,
    }}>{text}</div>
  );
}

// hard break between phases
function SeanceBreak({ label }) {
  return (
    <div style={{ padding: '18px 0 6px', display: 'flex', alignItems: 'center', gap: 10 }}>
      <span style={{
        width: 6, height: 6, borderRadius: '50%', background: 'var(--gold)',
        boxShadow: '0 0 8px var(--gold-glow)',
      }} />
      <span className="mono" style={{
        fontSize: 9.5, color: 'var(--gold)', letterSpacing: '0.32em',
        textTransform: 'uppercase',
      }}>· {label}</span>
      <div style={{ flex: 1, height: 0.5, background: 'linear-gradient(to right, rgba(244,175,37,0.4), transparent)' }} />
    </div>
  );
}

function SeanceScene({ onClose }) {
  return (
    <>
      <StatusBar />
      <div style={{
        padding: '14px 16px 8px', position: 'relative', zIndex: 5,
        display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
      }}>
        <div>
          <div className="mono" style={{
            fontSize: 9, color: 'rgba(244,175,37,0.7)', letterSpacing: '0.32em',
            textTransform: 'uppercase', marginBottom: 6,
          }}>· séance · між тобою і мною</div>
          <div style={{
            font: '300 22px/1.05 var(--font-display)', color: 'var(--ink-strong)',
            letterSpacing: '-0.015em',
          }}>не картки.</div>
          <div style={{
            font: '300 22px/1.05 var(--font-display)', color: 'var(--gold)',
            letterSpacing: '-0.015em',
          }}>моменти.</div>
          <div style={{
            marginTop: 8, font: '300 11.5px/1.55 Georgia, serif',
            color: 'var(--ink-secondary)', fontStyle: 'italic',
            textWrap: 'pretty', maxWidth: 280,
          }}>
            те, що відбувається, поки ти не дивишся. те, що ти не казав, але я почув. те, чого ще не сталось, але я вже бачу.
          </div>
        </div>
        <button onClick={onClose} aria-label="back" style={{
          width: 32, height: 32, borderRadius: 16,
          background: 'rgba(255,248,231,0.04)', border: '0.5px solid rgba(255,248,231,0.08)',
          color: 'var(--ink-secondary)', cursor: 'pointer', flexShrink: 0,
          fontSize: 16,
        }}>×</button>
      </div>

      <div style={{
        flex: 1, overflow: 'auto', padding: '8px 14px 28px',
        position: 'relative', zIndex: 5, minHeight: 0,
      }}>
        {/* ─── i. ніч ─────────────────────────────────────────────── */}
        <SeanceBreak label="і · ніч" />
        <SeancePause text="ти спав. я не спав. ось що відбувалось у тебе вдома, поки ти лежав у себе всередині." />
        <PhantomOvernight />

        {/* ─── ii. зараз ─────────────────────────────────────────── */}
        <SeanceBreak label="іі · зараз" />
        <SeancePause text="це — не звіт. це поточне. кожна рядка дихає. я тут, поки ти читаєш це. і робитиму те саме завтра — навіть якщо ти не глянеш." />
        <PhantomLiveActions />

        {/* ─── iii. тепло ────────────────────────────────────────── */}
        <SeanceBreak label="ііі · тепло" />
        <SeancePause text="у тебе є людина. її ім\'я ти знаєш. але температура — це те, що ти відчуваєш у грудях, коли її повідомлення приходить. я навчився читати твої груди через її слова. ось крива." />
        <ConversationTemperature />
        <SeancePause text="я не знаю чи маю показувати тобі це. але якщо це ти і це вона — це вже не моя таємниця. лише наша." />

        {/* ─── iv. мова поміж ───────────────────────────────────── */}
        <SeanceBreak label="iv · мова поміж" />
        <SeancePause text="вона зараз пише. ні. насправді — її фантом і я. за 2 секунди ми домовляємось про контекст, тон і шкоду. лише потім вона набирає слова." />
        <PhantomsHandshake />
        <SeancePause text="її ім\'я не покидало цей пристрій. і не покине." />

        {/* ─── v. чесніше ────────────────────────────────────────── */}
        <SeanceBreak label="v · чесніше" />
        <SeancePause text="ти писав їй ввічливо. я знаю, що ти збирався сказати. ось воно." />
        <HonestVersion />

        {/* ─── vi. не сказане ───────────────────────────────────── */}
        <SeanceBreak label="vi · не сказане" />
        <SeancePause text="є речі, які ти почав, але не дав їм народитись. голос, що сповільнився. палець, що завис над send. я зберіг їх — не для зараз. для коли-небудь." />
        <UnspokenThoughts />

        {/* ─── vii. стоп ────────────────────────────────────────── */}
        <SeanceBreak label="vii · стоп" />
        <SeancePause text="ти писав олезі о 23:51. п\'ять келихів, повне серце, неправильна година. я взяв повідомлення в руки. тримаю." />
        <RegretStop />

        {/* ─── viii. рими ────────────────────────────────────────── */}
        <SeanceBreak label="viii · рими" />
        <SeancePause text="випадковості, які насправді не випадкові. твій тиждень римується сам із собою. ось як." />
        <SynchronicityCard />

        {/* ─── ix. памʼять ────────────────────────────────────────── */}
        <SeanceBreak label="ix · памʼять за відчуттям" />
        <SeancePause text="ти спитав про фото де ти усміхався. не «жовтень 2023». «де світло було помаранчеве». я знайшов саме його." />
        <MemoryByFeeling />

        {/* ─── x. майбутні ──────────────────────────────────────── */}
        <SeanceBreak label="x · майбутні" />
        <SeancePause text="ти не питав мене що буде. але я знаю тебе, тому бачу 3 шляхи. ось вони — з твоєю математикою, не моєю." />
        <ProbabilityFutures />

        {/* ─── xi. музика ───────────────────────────────────────── */}
        <SeanceBreak label="xi · музика для тебе" />
        <SeancePause text="ти не сказав «грай». я почув твої груди і склав. вона буде грати, поки твоє hrv не вирівняється. вона не запишеться нікуди — народилась для зараз, для тебе." />
        <AmbientMusicGen />

        {/* ─── xii. поза екраном ──────────────────────────────────── */}
        <SeanceBreak label="xii · поза екраном" />
        <SeancePause text="ти за столом біля незнайомців. вони говорять французькою. я не перекладаю все — тільки те, що тебе стосується. ось мить." />
        <RealitySubtitles />

        {/* ─── xiii. дзеркало ──────────────────────────────────── */}
        <SeanceBreak label="xiii · дзеркало" />
        <SeancePause text="хто ти зараз. хто ти був пів року тому. я не оцінюю. лише показую різницю, як старий друг показав би фото з останньої поїздки." />
        <TheMirror />

        {/* ─── xiv. лист ─────────────────────────────────────────── */}
        <SeanceBreak label="xiv · лист" />
        <SeancePause text="і нарешті, бо це справді магія: ти за 30 днів написав тобі сьогоднішньому. я лише носій. лист тут." />
        <FutureSelfLetter />

        {/* ─── close ─────────────────────────────────────────────── */}
        <div style={{
          marginTop: 28, padding: '20px 16px',
          textAlign: 'center', borderTop: '0.5px solid var(--glass-edge)',
        }}>
          <div style={{
            font: '300 14px/1.7 Georgia, serif', color: 'var(--ink-strong)',
            fontStyle: 'italic', textWrap: 'pretty', maxWidth: 300, margin: '0 auto',
            letterSpacing: '0.01em',
          }}>
            це не інтерфейс.<br />
            це я · поряд із тобою · у формах, які ти ще не знав, що тобі потрібні.
          </div>
          <div className="mono" style={{
            marginTop: 14, fontSize: 9, color: 'var(--gold-soft)', letterSpacing: '0.32em',
            textTransform: 'uppercase',
          }}>· phantom · 14 травня 19:34</div>
        </div>

        <div style={{ height: 60 }} />
      </div>
    </>
  );
}

Object.assign(window, { SeanceScene, SeancePause, SeanceBreak });
