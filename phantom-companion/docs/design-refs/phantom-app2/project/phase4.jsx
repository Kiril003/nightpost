/* phase4.jsx — RichShowcaseScene.
   Long scroll-stream demoing all the new chat content types,
   organised into thematic sections with Familiar voiceover bubbles
   between them. Lives at scene='gallery'. */

// ─── Section divider ─────────────────────────────────────────────────────────
function GallerySection({ label, sub }) {
  return (
    <div className="ui-chrome" style={{
      padding: '14px 4px 8px', margin: '12px 0 2px',
      borderTop: '0.5px solid var(--glass-edge)',
    }}>
      <div className="mono" style={{
        fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.28em',
        textTransform: 'uppercase',
      }}>· {label}</div>
      {sub && (
        <div style={{
          marginTop: 4, font: '300 11px/1.45 var(--font-mono)',
          color: 'var(--ink-tertiary)', letterSpacing: '0.02em',
          textWrap: 'pretty',
        }}>{sub}</div>
      )}
    </div>
  );
}

// A compact Familiar narrator bubble — gives texture between cards
function FamiliarSay({ text }) {
  return (
    <div className="fade-in" style={{ padding: '4px 0' }}>
      <div className="glass" style={{
        maxWidth: '86%', padding: '8px 12px',
        borderRadius: '14px 14px 14px 4px',
        background: 'var(--glass-tint)',
        border: '0.5px solid var(--glass-edge)',
      }}>
        <div style={{
          font: '400 12.5px/1.5 var(--font-body)',
          color: 'var(--ink-strong)', textWrap: 'pretty',
        }}>{text}</div>
      </div>
    </div>
  );
}

// You bubble — short user prompt
function YouSay({ text }) {
  return (
    <div className="fade-in" style={{ display: 'flex', justifyContent: 'flex-end', padding: '4px 0' }}>
      <div className="glass subtle" style={{
        maxWidth: '72%', padding: '8px 12px',
        borderRadius: '14px 14px 4px 14px',
        background: 'rgba(244,175,37,0.10)',
        border: '0.5px solid rgba(244,175,37,0.25)',
      }}>
        <div style={{ font: '400 12.5px/1.5 var(--font-body)', color: 'var(--ink-strong)' }}>{text}</div>
      </div>
    </div>
  );
}

// ─── The showcase scene ─────────────────────────────────────────────────────
function RichShowcaseScene({ onClose }) {
  return (
    <>
      <StatusBar />
      <SurfaceHead
        kicker="· gallery · все що може зʼявитись у потоці"
        title="чат · збагачений"
        subtitle="файли · медіа · новини · календар · знання · присутність. прокрути."
        onClose={onClose} />
      <div style={{
        flex: 1, overflow: 'auto', padding: '4px 14px 28px',
        position: 'relative', zIndex: 5, minHeight: 0,
        background: 'radial-gradient(ellipse 70% 30% at 50% 0%, rgba(244,175,37,0.04), transparent 60%)',
      }}>

        {/* ─── 1. FILES & MEDIA ───────────────────────────────────────── */}
        <GallerySection label="файли · медіа" sub="фото, відео, голос, документ. без зайвого хрому — лиш суть і дрібка контексту." />
        <YouSay text="дивись що я зняв сьогодні з даху" />
        <PhotoHero
          scene="mountain"
          title="лhasа · перший справжній сніг травня — рано на 4 тижні"
          when="14 травня · 17:42"
          where="дах guesthouse"
          exif={[
            { k: 'iso',     v: '160' },
            { k: 'f',       v: '1.8' },
            { k: 'sec',     v: '1/240' },
            { k: 'lens',    v: '24mm' },
          ]} />
        <FamiliarSay text="зберіг у vault. експозиція трохи затемна — зробити проявлення?" />

        <PhotoAlbum
          count={37}
          title="lhasa · травень · 37 фото"
          scenes={['mountain', 'sky', 'portrait', 'night', 'forest']}
        />

        <PhotoCarousel
          scenes={['sky', 'mountain', 'forest', 'city', 'night', 'portrait']}
          title="ранок · пагода · вечір" />

        <FamiliarSay text="а ось відео розмови з olya — поставив главки автоматично, ти зможеш повернутись до «про переїзд» без скролу." />
        <VideoCard
          title="розмова з olya · 5 травня"
          scene="portrait"
          duration={412} />

        <AudioWaveform
          title="podcast · the daily — on attention"
          duration={1842}
          subtitle="ezra klein · 8 травня"
          highlights={[
            { t: 0.12, q: 'увага — це не ресурс, це валюта' },
            { t: 0.41, q: 'у тиші народжуються рішення яких бракує day-to-day' },
            { t: 0.78, q: 'не плутай зайнятість з присутністю' },
          ]} />

        <DocumentLong
          title="design-brief-v3.pdf"
          pages={24}
          when="14 травня" />

        <SpreadsheetPreview
          title="витрати · травень.xlsx"
          sheets={['витрати', 'категорії', 'місяць']} />

        <FileTransfer
          files={[
            { name: 'IMG_0421.HEIC', size: '2.4 MB', kind: 'photo', progress: 1.0, scene: 'mountain' },
            { name: 'IMG_0422.HEIC', size: '2.1 MB', kind: 'photo', progress: 1.0, scene: 'sky' },
            { name: 'voice-memo-03.m4a', size: '4.8 MB', kind: 'audio', progress: 0.72 },
            { name: 'sapiens-ch1.pdf', size: '12 MB', kind: 'pdf', progress: 0.34 },
          ]} />

        <Model3DCard
          title="lhasa-roof.glb"
          polys="12 432"
          size="4.2 MB" />

        <YouSay text="намалюй для слайдів — lhasa у листопаді, шафран та сапфір" />
        <ImageGenInProgress
          prompt="lhasa o 19:30, листопад, шафран та сапфір"
          step={18}
          total={30} />

        {/* ─── 2. НОВИНИ ──────────────────────────────────────────────── */}
        <GallerySection label="новини" sub="одна головна, дайджест, breaking, з відео — phantom фільтрує, ти читаєш суть." />
        <NewsHero
          kicker="reuters · 14 травня · 18:42"
          title="у lhasa зафіксовано перший справжній сніг — місяцем раніше нормальних строків"
          summary="станції на трьох висотах фіксують від -3 до -7°c. метеорологи: причина — зміщення джет-стрім після слабкого ел-ніньо. до п'ятниці тепло, далі знову."
          scene="mountain"
          tags={['клімат', 'lhasa', 'погода']} />
        <NewsDigest title="ранкове · 14 травня · 3 хв" />
        <BreakingNews
          title="оголошено повітряну тривогу в трьох областях"
          where="kyiv · щойно"
          summary="одеса · миколаїв · херсон. phantom-os увімкнув whisper-line · сповіщення вимкнено для зосереджених сцен." />
        <NewsWithVideo
          title="нічна lhasa: перше відеосвідчення раннього снігу"
          kicker="bbc · 14 травня"
          scene="night"
          duration="2:14"
          summary="монастир drepung вкритий тонким шаром. ченці кажуть — востаннє таке у 1996." />
        <FamiliarSay text="три з чотирьох джерел підтверджують ту саму подію. одне — twitter — пройшло без верифікації." />
        <CitationCluster
          title="три з чотирьох джерел підтверджують: сніг у lhasa нетиповий для травня з 1958 року" />

        {/* ─── 3. ПОГОДА · ФІНАНСИ · СПОРТ ────────────────────────────── */}
        <GallerySection label="погода · ринки · спорт" sub="живі чисельні дані з ритмом і характером — не голий dashboard." />
        <WeatherRich
          place="lhasa"
          now={{ t: 2, cond: 'rain' }} />
        <StockCard symbol="BTC" name="bitcoin" price={64218} change={-1.84} period="24 г" />
        <StockCard symbol="UAH" name="долар → гривня" price={41.42} change={0.32} period="тиж" />
        <SportsScore />

        {/* ─── 4. МОВА · ЗНАННЯ · ЦИТАТА ──────────────────────────────── */}
        <GallerySection label="мова · знання · цитата" sub="phantom допомагає розуміти, не лише знати." />
        <YouSay text="переклади борхеса українською, мʼягко" />
        <TranslationCard
          src="es → uk"
          original="«El tiempo es la sustancia de que estoy hecho. El tiempo es un río que me arrebata, pero yo soy el río.»"
          translated="«час — речовина, з якої я зроблений. час — річка, що несе мене, але я і є та річка»"
          alternatives={[
            '«час — це матерія, що мене створює; час — потік, який мене забирає, але потік — це я»',
            '«час — субстанція мого буття. час — річка, що тягне мене, проте я — сама річка»',
          ]}
          note />
        <DefinitionCard
          word="φάντασμα"
          translit="phántasma"
          lang="грецька · ст." />
        <KnowledgeCard
          topic="циркадний ритм" />
        <QuoteCard
          author="borges"
          work="нові розслідування · 1952" />

        {/* ─── 5. КАЛЕНДАР · ЧАС ──────────────────────────────────────── */}
        <GallerySection label="календар · час" sub="місяць · день · подія · подорож · політ. phantom знає не лише коли — а й як підготувати." />
        <MonthMini />
        <DayTimeline />
        <EventDetailCard prep />
        <FamiliarSay text="ще одна подія — конфліктує з нею. показую — глянь сам." />
        <EventDetailCard
          title="standup"
          when="ср · 15 травня · 09:00 — 09:30"
          where="meet.google.com/abc-defg-hij"
          attendees={[
            { name: 'kirill',     status: 'going', initials: 'K' },
            { name: 'оля',        status: 'going', initials: 'О' },
            { name: 'олег',       status: 'tent.', initials: 'Ол' },
            { name: 'богдан',     status: 'going', initials: 'Б' },
            { name: 'аня',        status: 'going', initials: 'А' },
            { name: 'нік',        status: 'going', initials: 'Н' },
          ]}
          agenda={[
            { time: '09:00', t: 'статус минулого тижня · 10 хв' },
            { time: '09:10', t: 'блокери · 10 хв' },
            { time: '09:20', t: 'демо · 10 хв' },
          ]}
          conflict
          prep={false} />
        <TravelItinerary />
        <FlightTracker
          flight="TK 5732"
          from="KBP"
          to="IST"
          progress={0.42}
          dep="06:55"
          arr="09:40" />
        <TaskListCard />
        <CountdownCard />

        {/* ─── 6. МІСЦЯ · РУХ · ЛЮДИ ──────────────────────────────────── */}
        <GallerySection label="місця · рух · люди" sub="мапа з контекстом · кав'ярні · близькі. orb знає де всі — без переслідування." />
        <MapPOI title="кавʼярні поряд" />
        <PlaceCard />
        <LocationLive
          name="оля"
          when="оновлено · 12 сек тому"
          battery={64}
          moving="пішки · 4 км/г"
          eta="8 хв" />
        <ContactCard />

        {/* ─── 7. РЕЧІ В ЧАТІ · РЕЦЕПТ · ОПИТУВАННЯ · МУЗИКА ──────────── */}
        <GallerySection label="ще речі в чаті" sub="рецепт · опитування · музика · порівняння · палітра — phantom стає поверхнею, не апкою." />
        <RecipeCard />
        <PollCard />
        <MusicNowPlaying />
        <ComparisonCard />
        <ColorPalette />

        {/* ─── close ─── */}
        <div style={{
          marginTop: 18, padding: '14px 14px', borderRadius: 14,
          background: 'rgba(244,175,37,0.04)',
          border: '0.5px solid rgba(244,175,37,0.15)',
          font: '400 12px/1.55 var(--font-body)', color: 'var(--ink-secondary)',
          textWrap: 'pretty', fontStyle: 'italic',
        }}>
          <span className="mono" style={{
            fontSize: 8.5, color: 'var(--gold-soft)', letterSpacing: '0.18em',
            textTransform: 'uppercase', fontStyle: 'normal',
            display: 'block', marginBottom: 4,
          }}>· phantom</span>
          ось і вся палітра форм у яких я можу зʼявитись. кожен елемент — не картка, а присутність моменту. скажи, який тип розширити — або просто пиши далі, я підлаштуюсь під розмову.
        </div>

        <div style={{ height: 60 }} />
      </div>
    </>
  );
}

Object.assign(window, { RichShowcaseScene, GallerySection, FamiliarSay, YouSay });
