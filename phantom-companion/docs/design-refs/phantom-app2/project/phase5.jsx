/* phase5.jsx — AdvancedLabScene.
   The deeper/wilder set — meta AI cards, advanced data viz, and unusual
   life surfaces. Lives at scene='lab'. */

function AdvancedLabScene({ onClose }) {
  return (
    <>
      <StatusBar />
      <SurfaceHead
        kicker="· lab · далі · просунутіше"
        title="чат · лабораторія"
        subtitle="агенти · дивні візуалізації · форми, які рідко бачиш у вікні чату."
        onClose={onClose} />
      <div style={{
        flex: 1, overflow: 'auto', padding: '4px 14px 28px',
        position: 'relative', zIndex: 5, minHeight: 0,
        background: 'radial-gradient(ellipse 70% 30% at 50% 0%, rgba(244,175,37,0.04), transparent 60%)',
      }}>

        {/* ─── 1. AGENT THINKING ─────────────────────────────────────── */}
        <GallerySection
          label="агент · міркує вголос"
          sub="phantom показує власну роботу: думки, виклики інструментів, гілки рішень." />
        <YouSay text="їхати сьогодні чи завтра?" />
        <AIReasoningTrace
          question="їхати сьогодні чи завтра?"
          answer="не їхати сьогодні. переніс standup на 10:00, забронював таксі на завтра о 11. богдану надішлю запис." />

        {/* ─── 2. ГЕНЕРАЦІЯ ──────────────────────────────────────────── */}
        <GallerySection
          label="генерація · продукт"
          sub="image-gen у grid · video-gen покадрово · receipt-ocr з фото." />
        <YouSay text="накидай 4 варіанти для обкладинки" />
        <ImageGenGrid />
        <VideoGenProgress />
        <ReceiptOCR />

        {/* ─── 3. ЖИВА РОЗМОВА ───────────────────────────────────────── */}
        <GallerySection
          label="жива розмова"
          sub="phantom як партнер на лінії: чує, маркує, підказує мʼяко." />
        <LiveTranscription />
        <VoiceCallLive />
        <BreathMeditation />

        {/* ─── 4. ВІЗУАЛІЗАЦІЇ ───────────────────────────────────────── */}
        <GallerySection
          label="дані · форми"
          sub="не графіки заради графіків — кожна форма для свого питання." />
        <SankeyFlow />
        <NetworkGraph />
        <YearHeatmap />
        <Candlestick />
        <RadarChart />
        <FunnelChart />

        {/* ─── 5. ТІЛО ────────────────────────────────────────────────── */}
        <GallerySection
          label="тіло · ніч"
          sub="phantom поряд коли ти не дивишся: серце, сон, дихання." />
        <ECGStrip anomaly />
        <SleepStagesLive />

        {/* ─── 6. СВІТ ────────────────────────────────────────────────── */}
        <GallerySection
          label="простір · світ"
          sub="живий 3d-глобус peer-mesh, шахова дошка з eval-bar, mind-map переїзду." />
        <WorldGlobe />
        <ChessBoard />
        <MindMap />

        {/* ─── 7. ПОБУТ ───────────────────────────────────────────────── */}
        <GallerySection
          label="побут · речі"
          sub="чек із друзями · посадковий талон · читання книги · ідентифікація рослини." />
        <BillSplitter />
        <BoardingPass />
        <BookReader />
        <PlantID />

        {/* ─── close ──────────────────────────────────────────────────── */}
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
          }}>· phantom · далі</span>
          це другий шар — те, що рідко бачиш у чатах. підкажи: яку форму поглибити, що додати,
          чи що з цього мене мати готовим до твоїх реальних задач.
        </div>

        <div style={{ height: 60 }} />
      </div>
    </>
  );
}

Object.assign(window, { AdvancedLabScene });
