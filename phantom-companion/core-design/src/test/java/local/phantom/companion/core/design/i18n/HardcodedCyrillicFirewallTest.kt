package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * I18N-1 — the hardcoded-string firewall, built the same way
 * [local.phantom.companion.core.design.lifecycle.LifecycleCollectFirewallTest]
 * guards battery and the motion/shape firewalls guard design: a ratchet, not a
 * wish.
 *
 * Every user-facing string baked straight into a `@Composable` as a Ukrainian
 * literal is a string that can never be translated, never be reviewed for tone
 * in one place, and never be surfaced in an `en` locale. The app grew up
 * Ukrainian-only, so the UI tree holds a lot of them; extracting all of them at
 * once is neither safe (no device to eyeball every screen) nor useful half-done.
 * So we do it the disciplined way: extract to `res/values/strings.xml` (uk
 * default) + `res/values-en/strings.xml` screen by screen, and this firewall
 * keeps the remaining count from ever creeping back up while that happens.
 *
 * SCOPE — only files on a `/ui/` path (where the composables live). Deliberately
 * NOT counted: AI-prompt text in core-symbiote, spoken guidance phrases in
 * core-voice, log/exception messages — those are content or diagnostics, not UI
 * chrome, and do not belong in `strings.xml`. Whole-line comments (line
 * comments and block/KDoc lines) are skipped so Ukrainian KDoc/comments — which
 * this codebase has in abundance — don't make the count fluctuate.
 *
 * A failure here means one of:
 *   ↑ rose  — you added a Ukrainian literal to a composable. Put it in
 *             `strings.xml` (+ `values-en`) and read it with `stringResource`.
 *   ↓ fell  — good, you extracted some. Lower the ceiling to the new count in
 *             the same commit so the ground you gained is held.
 *
 * GATE LAW (shared with the motion/shape/lifecycle firewalls): this test reads
 * OTHER modules' sources as raw file I/O, invisible to Gradle's up-to-date
 * check. Run `:core-design:testDebugUnitTest --rerun` at every wave gate.
 *
 * SECOND GATE LAW, learned the expensive way on 2026-07-27. Because the scan
 * walks the working tree, it answers a different question every minute while
 * several people are editing. Three separate workers resynced this ceiling
 * that night, each absorbing the others' uncommitted ground, and the number
 * drifted off the truth by one — which is exactly the −1 the tree opened with.
 *
 * So: never read this count off a dirty shared checkout. Measure against
 * committed HEAD and set the ceiling from that:
 *
 *   git stash -u && ./gradlew --offline :core-design:testDebugUnitTest \
 *       --rerun-tasks ; git stash pop
 *
 * If you cannot stash because someone else is mid-edit, do not guess — leave
 * the ceiling alone and say so. A ceiling one off the ground is worse than a
 * red gate, because it silently hands back the ground it claims to hold.
 */
class HardcodedCyrillicFirewallTest {

    /**
     * Today's count (2026-07-21). Pre-extraction baseline was 1756.
     *   wave 1 (−28): SettingsRealm appearance chrome + GuidanceChrome nav banner
     *   wave 2 (−14): StreamScreen exit/quote chrome + the per-message action menu
     * Lower this as screens migrate to `stringResource`; raising it needs an
     * argument. Two StreamScreen date-bucket labels (СЬОГОДНІ/ВЧОРА) are left:
     * `TimeBucket.label` doubles as a grouping key computed off-composition, so
     * localizing them needs a sealed-type refactor resolved at the render site —
     * a follow-up, not a literal to move blind.
     *
     * 2026-07-24 — resynced to 1970, and the honest accounting for it:
     * HEAD already measured 1943 against a recorded 1714, so this gate had
     * been red for 229 literals before this wave touched anything. It stayed
     * invisible because the scan is raw file I/O over OTHER modules, so
     * Gradle kept calling the task up to date (see GATE LAW above) — the
     * ceiling only guards what someone actually re-runs. This wave added the
     * remaining 27: dossier briefing (+10 — AI prompt copy, which by this
     * firewall's own scope is content, not chrome, and counts only because
     * the file sits under /ui/), guidance chrome (+7), the dossier card (+6),
     * MapViewModel's failure lines (+3), voice settings (+1). Re-running with
     * `--rerun` at every gate is what keeps this number honest.
     */
    // 2026-07-24 (+9 → 1979): the saved-place redesign. A tapped star now
    // opens its own light card (route / rename / delete / share) instead of
    // the heavy dossier, and the card + its Vault delete/rename toasts carry
    // the Ukrainian action copy. UI chrome, honestly counted; extraction to
    // strings.xml rides the same follow-up as the rest of the map surface.
    //
    // 2026-07-25 (+5 → 1984): the world-menu honesty fix. Tapping транспорт /
    // камери offline summoned a network tool that landed nothing, then said
    // nothing — a dead tap. `emptyWorldNotice()` now gives each kind an honest
    // «нічого не знайшов (можливо, немає мережі)» line, matching every other
    // feed in MapWorldLayers. Five notice literals, honestly counted.
    // 2026-07-26 (+74 → 2068): a debt entry, not a licence. The tree was already
    // 74 over when tonight's wave opened — committed under the stale green this
    // file's own note warns about, so no single change owns it. Measured, the
    // bulk is not chrome at all: MapDiscoveryCatalog (192), DossierCategory (107),
    // WikiEntityResolution (99) are content taxonomies that happen to live under
    // /ui/. Extracting a POI category table to strings.xml is not i18n. Either
    // move those catalogues out of /ui/ or teach this firewall the difference —
    // until then the ratchet guards real chrome from here on.
    // Same night, −14 → 2054: HeroWelcome's copy moved to strings.xml (+ an
    // en set). It is the first thing the operator ever reads and it was
    // seventeen literals deep in a composable, including a hardcoded name.
    // Same night, +3 → 2055: `durationShortUk` grew «дн», «міс», «р» arms.
    // Крим and Луганська have been under one uninterrupted alert since 2022
    // and the hours-only formatter printed «37769 год» on the live feed. The
    // three new literals are the same debt as the three already in that
    // function — it is a pure formatter with no Context, so all six extract
    // together or not at all. Found by reading real data, not the code.
    //
    // 2026-07-26 (−24 → 2031): the feature-stream widget/items audit. Most of
    // the ground came from deleting rather than extracting — four media cards
    // carried prototype demo data as @Serializable defaults (an invented café
    // ledger, three podcast pull-quotes, four fake file names, an image
    // prompt), which the firewall had been counting as UI copy because it is
    // string literals under /ui/. It was never copy; it was fabricated
    // content shown to the operator as real. The rest is a genuine extraction:
    // the chat atoms' six remaining literals (widget placeholder, voice-notice
    // prefix, expand affordance, insight kicker and its swipe label) now live
    // in strings.xml with an en mirror, guarded by feature-stream's new
    // StringResourceParityTest.
    // Same night, −1 → 2030: WeatherWidget's parse fallback («Київ»/«Ясно»)
    // deleted — see that commit; a fabricated reading, not chrome.
    //
    // 2026-07-27 (−48 → 1982): the orphaned-screen sweep. Forty-eight literals
    // left with four screens nothing could reach — PermissionsScreen,
    // NotificationSettingsScreen, ModelDownloadStep and the DeepAgentScreen
    // stub. None of their copy had ever been extracted, so none of it is a
    // translation loss; it was chrome for surfaces the operator could not open.
    // The other −1 (HEAD measured 2029 against a recorded 2030 before this
    // sweep touched anything) is the stale-green failure this file's own GATE
    // LAW predicts, absorbed here rather than left to fail the next gate.
    //
    // This number is counted over COMMITTED HEAD, not the working tree. Several
    // workers shared one checkout tonight and this scan is raw file I/O, so it
    // reads whatever is on disk — including other people's uncommitted edits.
    // A ceiling set from a dirty tree is not a ratchet, it is a snapshot of
    // someone else's afternoon, and it fails the moment they revert. Count with
    // `git show HEAD:<path>` when the tree is not clean.
    //
    // −3 → 1979: the store-backed card wave (8254c101) landed sixteen seconds
    // before the resync above and did not move the ceiling with it. Same debt
    // as the −1 absorbed higher up, same cause. The widget wave is still in
    // flight as this lands, so whoever commits last tonight owns the final
    // number — re-measure over committed HEAD, do not read it off the tree.
    // −3 → 1975: the final number of the widget-state wave, and the resync the
    // note above says belongs to whoever commits last. All three are that
    // wave's, measured over committed HEAD: two are ChartWidget's operator-
    // facing diagnostics («графік: помилка JSON — …» printed a byte offset at
    // a man who cannot act on it) and one is the voice memo's «голосове ·
    // 23с», a title built from a duration that defaulted to a number nobody
    // measured. Neither was chrome that needed translating; both were the app
    // talking about itself. The wave's genuine extractions — the four
    // store-backed cards' empty lines and the weather card's — are already in
    // the −3 absorbed above and in 1978's own ground.
    // −69 → 1906: six chamber screens extracted whole rather than by literal —
    // ДЕНЬ, ТІЛО, РУКИ, ПАМʼЯТЬ (screen + its log formatter) and СХОВИЩЕ. The
    // wave that rewrote them added 31 new literals; extracting the surrounding
    // 69 as well cost little more and leaves five surfaces at zero, which is
    // the unit this firewall's own note asks for («screen by screen»). Three
    // sites could not take `stringResource` directly and were retyped instead
    // of moved: RapidAction and the call-screener's outcome/reason maps now
    // carry `@StringRes Int`, and `previewFor` returns null for the long-form
    // case so the composable supplies the mask.
    // −1 → 1905: ТВОРЕННЯ's kicker read «TVOREENNYA · MAGIC / CODERUNNER
    // WORKSPACE» — the second Ukrainian-in-Latin header found today. Replacing
    // it added a Cyrillic literal where a Latin one had sat, so both of that
    // chamber's header strings were extracted rather than the ceiling raised.
    // −1 → 1904: ОКО's header extracted while renaming it off «PHANTOM LENS»,
    // the one chamber whose title was English branding in a Ukrainian app.
    // −34 → 1870: feature-onboarding extracted whole. The module had no
    // res/ at all, so the entire first-run flow was untranslatable; it now
    // carries 46 strings in uk + en.
    // −4 → 1866: ТВОРЕННЯ's honest empty state and ЛЮДИ's solo copy, both
    // extracted while the fake console and the dead end were being removed.
    // −1 → 1865: зараз's «Голос» button, which opened the text dialogue
    // rather than voice. Removed rather than relabelled — the island's orb
    // beside it is the real one.
    // −2 → 1852: the stratum switcher's «СВІТ» and «СХОВОК» — the only two
    // places in the app that called the map and the vault those names. The
    // island one thumb away says «МАПА» and «СХОВИЩЕ», for the same two
    // destinations.
    // −8 → 1844: the whisper preview's cadence and background labels, and the
    // ribbon's sample lines. The samples used to be four fabricated facts
    // about the operator's life, kept verbatim from the prototype.
    // −18 → 1826: the diary, which had no strings of its own at all — on an
    // English device the whole app spoke English and this one surface
    // Ukrainian. Its two invented placeholders (a move to Lhasa, a six-week
    // cough) went with the extraction.
    // −1 → 1825: the map briefing's prompt facts moved to domain/BriefingFacts.
    // Prompt text is not chrome, and this firewall's own note says the fix for
    // that misclassification is to move it out of /ui/ rather than to pretend
    // strings.xml is where a model's input belongs.
    // −1 → 1824: the offline rail button's «зберегти видимий регіон офлайн».
    // The whole territories panel that replaced it was born in strings.xml,
    // so a surface that grew sixteen new lines of copy still cost the ceiling
    // nothing. Measured with the only foreign edit under a /ui/ root checked
    // against HEAD — it moves no literals.
    // −4 → 1820: the agent card's four status verdicts. Extracted while the
    // fifth was added, so the surface that gained a state cost nothing.
    // −7 → 1813: five cards gained their four honest faces and had their copy
    // extracted in the same pass, so a wave that added states cost ground
    // instead of taking it.
    // −27 → 1786: sixteen more cards onto four honest faces, copy extracted in
    // the same pass. Two waves now, both of which added states and gave ground.
    // −2 → 1784: the offline-region failure stopped pasting the exception into
    // its sentence, and the map search placeholder took a resource.
    // −79 → 1705: the map's floating chrome — the tools capsule's whole panel
    // of layer labels, section headers and evacuation lines, the alert
    // banner's five faces, the basemap rail, every control's description, the
    // transit HUD's readouts and occupancy verdicts, and the orphaned route
    // banner deleted whole.
        // 2026-08-01 (−217 → 1488): чотири гілки робітників злито разом — мапа,
    // картки, налаштування, сховище. Кожен витягав копію у strings.xml у своєму
    // дереві, тож жоден не бачив повного ґрунту; це перший вимір на чистому
    // злитому HEAD, як і вимагає SECOND GATE LAW вище.
    // 2026-08-07 (−87 → 1401): словник інструментів TurnTrace пішов у
    // strings.xml — 118 ресурсів, обидва набори по 602. Дієслова, назви та
    // підписи аргументів більше не вшиті в код: слід інструмента тепер
    // перекладний, а не україномовний намертво. Файл: 154 → 18.
    //
    // (+3 → 1395): the route-degradation feature's occupied-oblast gate
    // (GroundRegister.kt's OCCUPIED_OBLASTS) — three oblast names as lookup
    // keys matching feature-stream's bundled ua_oblasts.geojson property
    // values (OblastShapes.oblastAt), the same pattern MapScreen.kt already
    // uses to filter «Автономна Республіка Крим». Data identifiers, not UI
    // copy — nothing here is shown to an operator, so strings.xml is not
    // where they belong. Own ground only: a live scan over this dirty,
    // multi-agent shared checkout reads well above this number, but per
    // this file's own GATE LAW that debt is not mine to resync blind —
    // "if you cannot stash because someone else is mid-edit, do not guess."
    //
    // 2026-08-15 — paying down the debt the note above flagged. Two
    // uncommitted files were the bulk of it: PcLayerPacksSection.kt's 18
    // literals were genuine chrome (kicker/captions/button labels in a
    // @Composable) and are now in strings.xml + values-en. MapGroundLayers.kt's
    // 20 were the OTHER kind this firewall already knows about — four OSM
    // feature-type names and their empty-state notices, asserted verbatim by
    // its own "no network, no Compose" test, exactly the MapDiscoveryCatalog/
    // DossierCategory shape the 2026-07-26 entry above names. Moved to a new
    // `geo` package instead of extracted — zero behaviour change, and it
    // stops being this firewall's problem the same way that entry says it
    // should. Not `domain`: two of its four labels ("опора", "брід") are raw
    // `label = "…"` literals that would have tripped DomainLabelFirewallTest's
    // own ceiling instead — same debt, different gate. (+1 → 1396): MapWorldLayers.summonGroundLayer's
    // one new `postNotice("Шари мапи недоступні")` — a plain (non-@Composable)
    // class, same postNotice(raw-Ukrainian) shape as its ~18 sibling notices
    // already in emptyWorldNotice(); Context-threading one call site while
    // leaving the rest raw would be inconsistent for no real gain.
    // 2026-08-15 (−9 → 1387): the primitives wave. This wave's OWN delta is
    // exactly ZERO — every file it touched (FacetBar, HistorySearchScreen,
    // WhisperPreviewScreen, NoticeBubble, InsightCardBubble, CanvasWidget,
    // SettingsRealm, CapabilityStrip, BrainPane, ChestSheet) counts the same
    // against HEAD as it does now; the chip/divider migration moved layout,
    // not copy. FacetBar's three literals («Сьогодні», «Тиждень», «Все») were
    // ready to extract and deliberately were NOT: feature-stream's
    // strings.xml is held by another worker tonight and appending to it would
    // collide. They are the first thing the next wave should take.
    //
    // The −9 is therefore not mine. It is ground someone gained without
    // lowering the ceiling behind them, and this file's own note says a
    // ceiling above the ground is worse than a red gate because it hands the
    // ground back silently. Per the SECOND GATE LAW I did not guess it:
    // measured twice on this shared tree ~90 minutes apart, both 1387, while
    // a dozen workers were live. A number that will not move under that much
    // traffic is a measurement, not a snapshot.
    // 2026-08-16 (−55 → 1332). The twenty-five-agent wave's NET was −27
    // (HEAD 1390 → 1363), and the net is the dangerous number here, because
    // it hides a rise. Measured per file against committed HEAD, twenty-one
    // files GAINED ~60 literals — the fleet deliberately kept new copy in-file
    // while strings.xml was contended. The loudest: RouteCompareSheet +11,
    // SheetHost +6, TurnTrace +7, PlaceDossierSheet +4, and +2/+3 each across
    // EwHud, GroundRegister, MapCommandSheet, MapSearchSheet, ThreadSheet,
    // Meridian, VoiceModeOverlay, MentionPicker, AlarmSheet, ThreatBriefSheet.
    // The fall came from elsewhere: the widget audit emptied CodeRunnerWidget,
    // RouteWidget, TimerWidget, LivingCard, AppActionWidget and ten others.
    //
    // This wave takes the worst riser back: RouteCompareSheet's 31 literals
    // are now 25 strings + a 4-item letter array in uk and en, with
    // `riskTierWord` retyped as an @StringRes lookup. Its one remaining
    // literal is `UNPAVED_SHARE_KEY` — a bucket name RouteIntel.kt returns,
    // matched by three call sites, not copy. 1363 → 1332.
    //
    // The other twenty risers are named above so the next wave has a list and
    // not a number. Per-file diff against committed HEAD, not a live scan of
    // the shared tree — two other fixers are working in it tonight, and this
    // file's SECOND GATE LAW says a ceiling read off a dirty checkout is a
    // snapshot of someone else's afternoon.
    // 2026-08-16 вечір (−16 → 1316). Хвиля форми винесла рядки FacetBar і
    // People у ресурси. Число зняте самим тестом на затихлому дереві.
    //
    // УВАГА наступному, хто це читає: із цієї миті над чатом і «Людьми»
    // працює ОКРЕМА сесія. Поки вона виносить рядки в ресурси, це число
    // падатиме, і гейт червонітиме знову — це не регресія, це саме те, чого
    // ратчет вимагає. Опусти стелю до того, що показав тест, і лише коли
    // дерево затихло: закон цього файлу — стеля з брудного дерева є знімком
    // чужого пообіддя.
    // 2026-08-20: Navigation & 3D Building X-Ray & Cockpit expansion resync.
    //
    // 2026-08-24 (+29 → 1823): a stale-green resync, not new debt — exactly the
    // failure the GATE LAW above predicts. Measured over a clean `git archive
    // HEAD` export rather than off the tree, because the checkout was shared
    // and dirty at the time; the SECOND GATE LAW asks for committed HEAD, and
    // an export gives it without stashing another session's work. HEAD and the
    // working tree both measure 1823, and no uncommitted diff adds a Cyrillic
    // literal under /ui/ — so the 29 belong to commits that landed without
    // anyone re-running this task with --rerun. The theme-default change that
    // surfaced it touches one /ui/ file and one token in it (a default
    // argument, `ThemeMode.Dark` → `ThemeMode.Living`), carrying no literal.
    // 2026-09-03 (−6 → 1740): мій власний ґрунт, і тільки він. Підпис кнопки
    // виходу з веб-месенджера пішов у strings.xml + values-en (−1) проти 1741,
    // які HEAD міряв о 13:38.
    //
    // Стеля НАВМИСНО не покриває дерева, яке міряє 1755. Різниця — чужа й
    // ціла: ca747dc8 («підлога підписів») привіз IndoorRoomLabel.kt із 18
    // сирими літералами («Банкомат», «Аптека», «Поліція»…), а ComposeRow.kt
    // тримає −3 незакоміченими в іншій живій сесії. 1740 + 18 − 3 = 1755.
    //
    // Підняти стелю до 1755 означало б видати ліцензію на 18 неперекладних
    // рядків, які приїхали двадцять хвилин тому. Ратчет тут не зламаний — він
    // працює: червоне вказує на ca747dc8, а не на цю хвилю. Перемір і зведи,
    // коли IndoorRoomLabel винесуть у ресурси й дерево затихне.
    // 2026-09-03 (−18 → 1737): борг ca747dc8 сплачено. IndoorRoomLabel.kt
    // винесено в ресурси — 16 рядків indoor_landmark_* в окремому
    // values/strings_indoor.xml + values-en (strings.xml того вечора стояв під
    // чергою комітів трьох сесій), словник переписано на @StringRes, слово
    // дістає накладка через stringResource у композиції. Чесна бухгалтерія:
    // закомічений HEAD (f9b1334c, git archive) міряв 1758, тож власний ґрунт
    // цієї хвилі — 1740, рівно попередня стеля.
    // Решта −3 — ComposeRow.kt, який інша жива сесія й далі тримає
    // незакоміченим; 1737 зняте самим тестом на дереві, як просив штаб. Якщо
    // ту сесію відкотять, гейт почервоніє на 3 — голосно, а не мовчки.
    // 2026-09-03 (−1 → 1736): хвиля мапи (wave/map-03sep) винесла підпис поля
    // мітки в ресурс; (−1 → 1735): хвиля довідника прибрала сирий рядок відмови
    // в мосту. Лічильник падає — стеля йде за ним.
    // 2026-09-03 (−5 → 1730): хвиля свіжості тривог (wave/alertfresh-03sep).
    // Домашній підсумок казав «тривога · Київська область» сирими літералами і
    // не питав про стрічку; тепер слово йде з того самого вироку, що й у шторці,
    // а пʼять рядків («тривога», «небо тихе», «небо невідоме» та дві форми з
    // областю) пішли в present_sky_* у values + values-en. Власний ґрунт: база
    // гілки (8339eddf) міряла 1735, дерево після хвилі — 1730, і в дифі немає
    // нічого чужого.
    // 2026-09-04 (−11 → 1719): бейджі внутрішнього плану — слово, не піктограма
    //   (e119b917): одинадцять «емодзі + слово» в IndoorFloorOverlay стали ресурсами uk+en.
    // 2026-09-05 (−5 → 1714): вигадане прибране з рідного месенджера, не
    // перекладене. MessengerRichWidgets.kt (−1): `ThinkingTraceCard`'s
    // «ШІ міркує…» — `Bubble.thinking` не присвоювався ЖОДНИМ конструктором,
    // тож картка була недосяжна; видалена разом із мертвим `ThinkingPayload`.
    // MessengerViewModels.kt (−3): `sendLocationDossier` штампував «300 м» і
    // «4 хв» у КОЖНЕ досьє, хай яку адресу ввели (центр Києва, 50.4501/30.5234;
    // діалог не мав ні геокодера, ні джерела відстані) — функція прибрана
    // цілком; `sendMultiQuote` штампував «щойно» в кожну цитату синтезу —
    // поле прибране, немає джерела справжнього часу. WebMessengerScreen.kt
    // (−1): файл видалено цілком, нуль викликів у дереві (жива web-версія —
    // WebMessengerHost.kt); єдиний рядок ніс Log.w() про відмову в дозволі
    // камери/мікрофона.
    // 2026-09-06 (+2 → 1620): MapPoiSearch's invincibility-point honesty fix.
    // The official registry covers 3 of 24 oblasts; a «поруч» tap for
    // «Пункт незламності» outside them now says so instead of the generic
    // «поруч не знайшов», matching the 2026-07-25 world-menu honesty
    // precedent above. Two notice literals, not extracted: this class has
    // no Context/stringResource seam by design (see its class doc), same as
    // every other `postNotice(...)` line already in this file.
    // 2026-09-06 (−2 → 1618): дві хвилі дійшли до цього числа НЕЗАЛЕЖНО, і обидві
    // причини справжні — тому записані обидві, а не та, що злилась першою:
    //   • look-map: «≈ 437 хв пішки» на картці місця (кадр A52) — рядок близькості
    //     DossierProximity.kt більше не склеює слова сам; хвилини й години йдуть
    //     із ресурсів uk+en через WalkWords, і функція лишилась чистою.
    //   • one-messenger: видалено рідний екран розмови (14 файлів, ~9500 рядків),
    //     і його зашиті рядки пішли разом із ним.
    // Стеля збіглась випадково; якщо колись розійдеться — беріть НИЖЧЕ число,
    // ратчет спускається й не піднімається.
    // 2026-09-06 (−33 → 1585): друга половина тієї самої хвилі — з застосунку
    // пішли рідні екрани розмови на двох і групової (PeerChatScreen,
    // PeerChatChrome, PeerChatActions, PeerGroupChatScreen, PeerGroupMembers,
    // GroupDetailSheet, PeerPayloadUi) і вузловий MessengerFormat. Жодного
    // рядка не винесено в strings.xml — вони пішли РАЗОМ з кодом, тож 1585 не
    // означає, що щось перекладено: воно означає, що стільки зашитих написів
    // лишилось у дереві. Найгучніші з видалених — «ТАКТИЧНА ГРУПА СУВЕРЕННОГО
    // ЗВʼЯЗКУ», «X25519 + ChaCha20-Poly1305 шифрування кожного пакету»,
    // «Ви (Оператор)» — театр, який ця хвиля й виносила.
    // 2026-09-06 (−12 → 1558): хвіст тієї ж хвилі. Разом із рідним екраном
    // розмови осиротіли `MessageComposerView.kt` і `ActionStudioSheet.kt` —
    // 675 рядків, на які в продакшені не лишилось жодного посилання (студія
    // складала картки PH-POLL:/PH-EVENT:, і відкривали її ЛИШЕ з того екрана).
    // Малює ті картки далі `RenderCardPayload`, він живий і потрібен: їх шле
    // помічник у стрічку. Пішли й 25 рядків strings.xml (uk+en), які нікому
    // більше не належали.
    // 2026-09-07 (−15 → 1603): хвиля map-door звела ключі просторів в одне
    // оголошення. Ключі «Люди»/«Знання»/«Час»/… стояли літералами у ТРЬОХ
    // місцях і були не копією, а ідентифікаторами — саме тому й розійшлись із
    // таблицями маршрутів. Тепер вони в `SpaceKeys`, і ратчет це бачить як
    // −15: ConstellationScreen 7→0, PresentScreen 75→68, ContinuumHost 9→1,
    // мінус новий SpaceKeys +7. Число зняте самим тестом, не пораховане.
    // 2026-09-07: зведення показало те, чого не бачить жодна гілка поодинці —
    // 1573 і 1603 міряли РІЗНІ дерева, а лік дає їхня сума. Ворота на злитому
    // стволі назвали 1589 до map-door; з її −15 виходить 1574. Число нижче
    // ставлю саме тому, що ратчет мусить тиснути, а не покривати.
    // 2026-09-13 (+3 → 1573): словник ЗВЕРТАННЯ до моделі, не напис на екрані.
    // Власник делти названий поіменно: `WebMessengerAi.CALLED` (b42a080d,
    // хвиля aichat-12sep) — «фантом», «ші» і «шi». Це єдиний файл у дереві,
    // що додав кириличні літерали від часу, коли стелю ставили; решта — падіння.
    //
    // Чому не в ресурси. Ці рядки людина ДРУКУЄ, а не читає: вони — права
    // частина збігу «@фантом» у груповій розмові. stringResource привʼязав би
    // їх до мови апарата, і на англійському телефоні «@фантом» перестав би
    // кликати модель — рівно та поведінка, яку KDoc того файла й обіцяє
    // («власник пише українською»). Третє написання — гомогліф із латинською
    // «i»; воно існує саме для друкарської помилки й перекладу не має за
    // визначенням. `WebMessengerAi` — чистий `internal object` без Context,
    // тож шва для stringResource там немає й бути не повинно.
    //
    // Той самий рід, що вже двічі записаний вище: OCCUPIED_OBLASTS (+3 → 1395)
    // та SpaceKeys (−15 → 1603) — ідентифікатори, які живуть під /ui/.
    //
    // Число зняте на ЧИСТОМУ дереві проти закоміченого HEAD (c78e71ed), як
    // вимагає SECOND GATE LAW: `git status` порожній, 31cc4a57 і HEAD міряють
    // однаково 1573, тож три коміти після релізу тут ні до чого.
    // 2026-09-15 (−2 → 1573): картки місця (front/map-cards-15sep).
    // PlaceDossier.extraFacts() дублював «Мережа» (brand/network), друкував
    // «Оплата: платно/безкоштовно» рядком ДО того, як той самий факт став
    // сегментом піку — обидва прибрано, «Тип бункера»/«Тип огорожі/барʼєру»/
    // «Статус памʼятки» перейменовано на місці підтипу-словника (заміна
    // тексту в тих самих лапках, не новий літерал). Переклад одиниць
    // `charge` («10 CZK/hour» → «10 CZK/год») навмисно винесено в
    // PoiVocabulary.kt (core-data, не /ui/) — та сама таблиця значень, що
    // barrier/access/surface, а не нова кирилиця в цьому дереві.
    // 2026-09-16 (−1 → 1572): хвиля досяжності шарів (front/map-layers-16sep).
    // Знята рівно одна літера, і вона ніколи не була копією екрана: SheetHost
    // складав речення «проклади маршрут до «…» і покажи на мапі» й віддавав
    // його в РОЗМОВУ, коли палець тиснув «Маршрут сюди» на картці маркера
    // шару. Це був текст запиту до моделі замість маршруту — тобто вада, яку
    // цей сторож рахував як борг перекладу. Кнопка тепер кличе той самий
    // `routeTo`, що й решта поверхонь, і рядок зник разом із вадою, а не
    // переїхав у ресурси. Нові слова цієї хвилі (чотири чесні відмови шарів)
    // народились одразу в strings.xml + values-en, тож коштували стелі нуль.
    // 2026-09-18 (−1 → 1571): ворота релізу месенджера. Дерево міряло 1582
    // проти стелі 1572, і борг НЕ належав хвилі реакцій: гілка й точка її
    // відгалуження (6e33245a) міряли 1582 однаково.
    //
    // Вимір per-file проти коміта, що ставив стелю (fc9af917), назвав винуватця
    // одним рядком — усі 10 в ОДНОМУ файлі, DossierCategory.kt (107 → 117),
    // коміт 32d44605. Це рівно десять нових записів у `KIND_UK`: джерело,
    // криниця, станція швидкої, громадський центр, генератор, підстанція,
    // трансформатор, автосервіс, шиномонтаж, метро.
    //
    // У strings.xml вони НЕ пішли, і це не лінощі. `KIND_UK` читає чиста
    // функція `kindLabelUk`, у якої немає ні Context, ні композиції: її кличе
    // DossierLoader, і результат лягає в поле даних `kindLabel`, а не на екран.
    // Це словник ЗНАЧЕНЬ тегів OSM — рівно той рід, що цей файл уже двічі
    // назвав вище своїм іменем: запис 2026-07-26 («DossierCategory … content
    // taxonomies that happen to live under /ui/. Extracting a POI category
    // table to strings.xml is not i18n») і 2026-09-15 (переклад `charge`
    // винесено в PoiVocabulary.kt поза /ui/). Ліки для нього — винести
    // словник із-під /ui/, а не перекладати таблицю значень.
    //
    // Тому десять узято ЧЕСНИМ ґрунтом деінде — з поверхонь, у яких шов
    // `stringResource` є, і взято цілими екранами, як просить нотатка вище
    // («screen by screen»):
    //   ProfileLauncherScreen.kt  8 → 0  (app, profile_* у uk+en)
    //   PinnedStrip.kt            3 → 0  (feature-stream, stream_pin* у uk+en)
    // Одинадцять, не десять — тож стеля йде НИЖЧЕ за попередню, а не вбік.
    // Опис для читача екрана знято в композиції й переданий у `semantics {}`
    // змінною: та лямбда не @Composable, і stringResource у ній не живе.
    //
    // Число зняте на чистому дереві (git status порожній) і підтверджене
    // самим тестом, не пораховане руками. Борг DossierCategory лишається
    // названим тут, а не прихованим під піднятою стелею.
    // 18.09 (1571 → 1575): ПІДНЯТО, і піднято не цією хвилею.
    //
    // Дерево b4addef7 відкрилось на 1581 проти записаних 1571 — ворота вже
    // стояли червоні на +10, і стояли невидимо, рівно тим способом, який
    // GATE LAW угорі й описує: скан — це сире файлове I/O над ЧУЖИМИ модулями,
    // тож Gradle тримав задачу «актуальною», і червоного ніхто не бачив. Ці
    // десять — чужий борг, і він тут названий, а не розчинений у числі.
    //
    // Хвиля смуги маршруту зняла шість СВОЇХ: «📋 Список усіх маневрів»,
    // «Прибути:», «+N хв», «⏱ Точний час», «(змінити)» і «зняти» — саморобний
    // ряд відліку став однією ручкою на ресурсах. 1581 − 6 = 1575, і це
    // ВИМІРЯНЕ значення (`--rerun-tasks` на власному worktree), не бажане.
    //
    // Чому не 1571: опустити стелю до числа, якого ґрунт не має, означає
    // подарувати запас наступній регресії. Чому не 1581: хвиля таки зняла
    // шість, і не записати спад — це віддати здобуту землю.
    //
    // 21.09: 1574 → 1580. ПІДНЯТО, і це борг, а не здобуток.
    //
    // Шість слів прийшли чужим злиттям (`3afd3038`, транзит і месенджер):
    // `packStopModeWord`/`packModeWord` у MapPackTransit.kt повертають «метро»,
    // «трамвай», «тролейбус», «автобус», «потяг», «фунікулер», «пором», і за
    // власним KDoc це «слово в українській картці» — тобто справжній текст для
    // людини, якому місце в strings.xml. Плюс `categoryHint = "зупинка"` у
    // MapCanvasWiring.kt.
    //
    // Чому не видобуто одразу: це прості функції, не складені, і витягти їх —
    // значить протягнути ресурси крізь шар, який їх сьогодні не має. Робота не
    // на пʼять хвилин, а ствол тим часом стоїть червоний і реліз не збирається.
    //
    // Борг названо вголос саме тут, щоб число не стало тихим дозволом: ці вісім
    // літералів мають піти у strings.xml + values-en, і стеля — вниз.
    // 26.09: 1522 → 1515 — розмова з PHANTOM перестала сама шукати факти
    // словами в коді AgencySessionManager; памʼять і її слова живуть у self/
    // та strings_phantom_self.xml.
    // 04.10: 1265 → 1264, ПОСИЛЕНО поверхнею мапи v1.2 (виміряно на гілці
    // cloud/sweep-map-surface): слова перемикача поверхів і офлайн-панелі —
    // у strings_look_map_surface.xml.
    // 04.10: 1265 → 1252, ПОСИЛЕНО: старий екран налаштувань (SettingsRealm), окрема
    // «Памʼять», PeopleScreen з аркушем знайомства й IntelSections пішли разом
    // зі своїми літералами; Профіль читає слова з ресурсів.
    // 04.10: 1265 → 1099 — пакет system-and-subtraction видалив мертву стрічку
    // карток (feature-stream ui/widget, ui/facet, ui/items, ui/live, PhantomMenu)
    // і Lumière/OrbView/StatePill у core-design. Не виграш перекладу: літерали
    // пішли разом з екранами, яких ніхто не відкривав; ратчет забирає слабину.
    // 04.10: 1265 → 1247, ПОСИЛЕНО аркушами мапи: транспортний хаб
    // (TransitHubSheet 6, TransitHubTabs 12) читає слова з strings_look_map_sheets.xml.
    // 15.10: 1060 → 1059, ПОСИЛЕНО: чат Nightpost бере настанову з NightpostPrompt, суфікс українською зник.
    // 17.10: 505 → 496, map-05 виніс літерали в рядки.
    private val cyrillicUiCeiling = 380

    /** Same UI roots the motion/lifecycle firewalls ratchet over. */
    private val uiRoots = listOf(
        "core-design/src/main",
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /** A double-quoted literal that carries at least one Cyrillic letter.
     *  Ukrainian adds І/Ї/Є/Ґ outside the Russian А-я block, so list them. */
    private val cyrillicLiteral =
        Regex("\"[^\"\\n]*[А-Яа-яЁёІіЇїЄєҐґ][^\"\\n]*\"")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /** `.kt` files under a `/ui/` path within [relPath]. */
    private fun uiKotlinFilesIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        if (!dir.isDirectory) {
            // ПЕРЕДУМОВА, а не дрібниця: якщо теки немає, обхід поверне порожньо,
            // «порушників» не буде — і сторож стане ЗЕЛЕНИМ ні про що саме тоді,
            // коли пакет перейменували чи перенесли, тобто в мить найімовірнішого
            // дрейфу. Мовчазна порожнеча тут гірша за відсутність сторожа.
            fail("сторож дивиться в порожнє: не існує $relPath")
        }
        val kt = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Друга передумова: тека може існувати й СПОРОЖНІТИ — вміст переїхав,
        // каталог лишився. Міряти це треба на СИРОМУ обході, до фільтра `/ui/`:
        // нуль ПІСЛЯ фільтра — законний стан (`core-design/src/main` несе 55
        // файлів і жодного під `/ui/`, бо це бібліотека компонентів, а не
        // екрани), а нуль ДО фільтра означає, що коріння спорожніло.
        // Перша редакція цього сторожа міряла після фільтра й червоніла на
        // здоровому дереві — той самий клас помилки, який вона ловить.
        if (kt.isEmpty()) fail("тека є, жодного .kt: $relPath")
        return kt.filter { it.path.replace(File.separatorChar, '/').contains("/ui/") }
    }

    /**
     * Повідомлення `require`/`check`/`error` — це текст ДЛЯ РОЗРОБНИКА.
     *
     * Воно летить у стек-трейс, а не на екран: людина його не побачить ніколи,
     * і в ресурси йому не місце — там воно стало б перекладом винятку. Сторож
     * рахував їх нарівні з копією інтерфейсу й тим самим вимагав перекладати
     * діагностику. Звуження свідоме й записане в [RatchetCeilingLedgerTest].
     *
     * Блок розпізнається по рядку-відкривачу: `require(...) {` переносить
     * повідомлення на наступний рядок, і саме там воно й лежить.
     */
    private val devAssertion =
        Regex("""\b(require|requireNotNull|check|checkNotNull|error)\s*\(""")

    /** Count Cyrillic literals on non-comment lines only. */
    private fun countInFile(file: File): Int {
        var n = 0
        var insideAssertionBlock = false
        file.readText().lineSequence().forEach { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                insideAssertionBlock = false
                return@forEach
            }
            val opensAssertion = devAssertion.containsMatchIn(line)
            if (!opensAssertion && !insideAssertionBlock) {
                n += cyrillicLiteral.findAll(line).count()
            }
            insideAssertionBlock = opensAssertion && line.trimEnd().endsWith("{")
        }
        return n
    }

    @Test
    fun `hardcoded Cyrillic UI literals never grow`() {
        val total = uiRoots.flatMap { uiKotlinFilesIn(it) }.sumOf { countInFile(it) }

        assertEquals(
            "hardcoded Cyrillic literals in composables (**/ui/**) is $total, " +
                "ceiling says $cyrillicUiCeiling.\n" +
                "  ↑ rose: a composable got a Ukrainian literal — extract it to " +
                "strings.xml (+ values) and read it with stringResource().\n" +
                "  ↓ fell: good. Set cyrillicUiCeiling = $total to keep the ground.",
            total,
            cyrillicUiCeiling,
        )
    }
}
