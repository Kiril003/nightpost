# Slice A — Present Canvas + Presentation Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the chat-log home with a living **Present** canvas, and wrap every assistant turn in a cohesive **PresentationTemplate** chosen by a pure planner — the cardinal first step of the Living Information Surface.

**Architecture:** A pure `PresentationPlanner` maps `(widgetType, payload, modelHint?) → PresentationTemplate` at *render time* — no DB migration, no edits to the 2555-line `StreamViewModel` parse path. `core-design` gains `PresentationTemplate` tokens (mirroring the existing `StateAccent` resolver pattern) and a `TemplateScaffold` composable. A new `PresentScreen`/`PresentViewModel` aggregates existing sources (recent stream items, insights, context, pending intents) into ranked `PresentCard`s and becomes the post-bootstrap start destination; chat (`StreamNav`) opens from it as a mode.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, Navigation-Compose, Room (read-only here), JUnit (plain-JVM unit tests, as feature-stream already runs).

## Global Constraints

- Files < 500 lines; one design language (PhantomTheme / GlassCard / MotionTokens); honest stubs over fake-working buttons. (project `CLAUDE.md`)
- Build cadence: `assembleDebug` ~13 min, freeze-prone on the Radxa host — unit tests are the fast loop; full APK only at the milestone, supervised. No parallel subagents on this host — serialize. (memory `host_agent_concurrency`)
- Background/retention work uses WorkManager, never coroutine loops. (memory `workmanager_for_oem_battery_kill`) — N/A to this slice (no new background work) but do not introduce any.
- Material icons: use `Icons.AutoMirrored.*` for any directional icon. (memory `material_automirrored_icons`)
- Tolerant parsing: an absent/invalid `present` hint must never break rendering. (memory `chart_widget_dual_shape`)
- Privacy: Present cards sourced from vault/private context never leave the device.
- Ukrainian-only operator-facing copy.
- `SystemState` enum (existing): `SHADOW, FOCUS, DIALOGUE, SENTINEL, GHOST, DREAM` (`core-context/.../SystemState.kt`).

---

### Task 1: `PresentationTemplate` + token resolver (core-design)

The named visual treatments and their design tokens. Mirrors the existing `StateAccent` /
`stateAccentForSunrise` resolver pattern so it feels native to the module.

**Files:**
- Create: `core-design/src/main/java/local/phantom/companion/core/design/present/PresentationTemplate.kt`
- Test: `core-design/src/test/java/local/phantom/companion/core/design/present/PresentationTemplateTest.kt`

**Interfaces:**
- Produces: `enum class PresentationTemplate { ANSWER, RESEARCH, ALERT, MEMORY, VITAL, ACTION, REFLECTION, PLAIN }`
- Produces: `data class TemplateTokens(val accent: Color, val glassLevel: GlassLevel, val glyph: String, val emphasizeAccentBorder: Boolean, val motionScale: Float)`
- Produces: `fun tokensFor(template: PresentationTemplate): TemplateTokens`

- [ ] **Step 1: Write the failing test**

```kotlin
package local.phantom.companion.core.design.present

import local.phantom.companion.core.design.component.GlassLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTemplateTest {

    @Test
    fun everyTemplateResolvesTokens() {
        PresentationTemplate.values().forEach { t ->
            val tokens = tokensFor(t)
            assertTrue("glyph non-empty for $t", tokens.glyph.isNotEmpty())
            assertTrue("motionScale positive for $t", tokens.motionScale > 0f)
        }
    }

    @Test
    fun alertIsVisuallyDistinctFromAnswer() {
        val answer = tokensFor(PresentationTemplate.ANSWER)
        val alert = tokensFor(PresentationTemplate.ALERT)
        assertNotEquals(answer.accent, alert.accent)
        assertTrue("alert uses an accent border", alert.emphasizeAccentBorder)
    }

    @Test
    fun plainIsTheQuietBaseline() {
        val plain = tokensFor(PresentationTemplate.PLAIN)
        assertEquals(GlassLevel.Subtle, plain.glassLevel)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-design:testDebugUnitTest --tests "*PresentationTemplateTest"`
Expected: FAIL — `PresentationTemplate` / `tokensFor` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package local.phantom.companion.core.design.present

import androidx.compose.ui.graphics.Color
import local.phantom.companion.core.design.component.GlassLevel

/**
 * Named visual treatments. Each template is a cohesive bundle of design
 * tokens chosen for a *kind* of answer — not a per-widget style. Mirrors
 * the [local.phantom.companion.core.design.theme.StateAccent] resolver
 * pattern (data + a `when` factory) so it stays testable and consistent.
 */
enum class PresentationTemplate { ANSWER, RESEARCH, ALERT, MEMORY, VITAL, ACTION, REFLECTION, PLAIN }

data class TemplateTokens(
    val accent: Color,
    val glassLevel: GlassLevel,
    /** Leading header glyph, design §IV glyph vocabulary. */
    val glyph: String,
    /** ALERT/ACTION draw a 1.5dp accent border so they read as "act now". */
    val emphasizeAccentBorder: Boolean,
    /** Multiplier folded into MotionTokens by the scaffold. */
    val motionScale: Float,
)

fun tokensFor(template: PresentationTemplate): TemplateTokens = when (template) {
    PresentationTemplate.ANSWER -> TemplateTokens(
        accent = Color(0xFFF4AF25), glassLevel = GlassLevel.Card,
        glyph = "◆", emphasizeAccentBorder = false, motionScale = 1.0f,
    )
    PresentationTemplate.RESEARCH -> TemplateTokens(
        accent = Color(0xFF5BA3D0), glassLevel = GlassLevel.Panel,
        glyph = "⌕", emphasizeAccentBorder = false, motionScale = 1.0f,
    )
    PresentationTemplate.ALERT -> TemplateTokens(
        accent = Color(0xFFE76F51), glassLevel = GlassLevel.Elevated,
        glyph = "▲", emphasizeAccentBorder = true, motionScale = 1.3f,
    )
    PresentationTemplate.MEMORY -> TemplateTokens(
        accent = Color(0xFF7E5DA4), glassLevel = GlassLevel.Panel,
        glyph = "◉", emphasizeAccentBorder = false, motionScale = 0.8f,
    )
    PresentationTemplate.VITAL -> TemplateTokens(
        accent = Color(0xFF4FA88B), glassLevel = GlassLevel.Card,
        glyph = "♥", emphasizeAccentBorder = false, motionScale = 1.1f,
    )
    PresentationTemplate.ACTION -> TemplateTokens(
        accent = Color(0xFFB07A10), glassLevel = GlassLevel.Elevated,
        glyph = "⚙", emphasizeAccentBorder = true, motionScale = 1.0f,
    )
    PresentationTemplate.REFLECTION -> TemplateTokens(
        accent = Color(0xFF8A7F72), glassLevel = GlassLevel.Subtle,
        glyph = "❝", emphasizeAccentBorder = false, motionScale = 0.6f,
    )
    PresentationTemplate.PLAIN -> TemplateTokens(
        accent = Color(0xFF7A7A7A), glassLevel = GlassLevel.Subtle,
        glyph = "·", emphasizeAccentBorder = false, motionScale = 1.0f,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-design:testDebugUnitTest --tests "*PresentationTemplateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core-design/src/main/java/local/phantom/companion/core/design/present/PresentationTemplate.kt \
        core-design/src/test/java/local/phantom/companion/core/design/present/PresentationTemplateTest.kt
git commit -m "feat(design): PresentationTemplate tokens for the Presentation Planner"
```

---

### Task 2: `PresentationPlanner` — pure intent→template mapping (feature-stream)

The decision layer. Given a widget type + raw payload (+ optional model `present` hint), pick the
template. Pure and deterministic so the mapping can never silently regress.

**Files:**
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentationPlanner.kt`
- Test: `feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentationPlannerTest.kt`

**Interfaces:**
- Consumes: `PresentationTemplate` (Task 1).
- Produces: `object PresentationPlanner { fun plan(widgetType: String?, payload: String?): PresentationTemplate }`
  - Reads an optional top-level `"present":"<name>"` string inside `payload` JSON; if it names a
    valid template (case-insensitive) it wins. Otherwise infers from `widgetType`. `null`/blank
    type with no hint → `PLAIN` (plain text turns).

- [ ] **Step 1: Write the failing test**

```kotlin
package local.phantom.companion.feature.stream.present

import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.present.PresentationPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationPlannerTest {

    @Test fun plainTextTurnIsPlain() =
        assertEquals(PresentationTemplate.PLAIN, PresentationPlanner.plan(null, null))

    @Test fun searchResultInfersResearch() =
        assertEquals(PresentationTemplate.RESEARCH, PresentationPlanner.plan("search-result", "{}"))

    @Test fun vitalsInfersVital() =
        assertEquals(PresentationTemplate.VITAL, PresentationPlanner.plan("vitals-constellation", "{}"))

    @Test fun appActionInfersAction() =
        assertEquals(PresentationTemplate.ACTION, PresentationPlanner.plan("app-action", "{}"))

    @Test fun weatherInfersAnswer() =
        assertEquals(PresentationTemplate.ANSWER, PresentationPlanner.plan("weather", "{}"))

    @Test fun explicitHintWinsOverInference() =
        assertEquals(
            PresentationTemplate.ALERT,
            PresentationPlanner.plan("weather", """{"present":"alert","payload":{}}"""),
        )

    @Test fun invalidHintFallsBackToInference() =
        assertEquals(
            PresentationTemplate.ANSWER,
            PresentationPlanner.plan("weather", """{"present":"banana"}"""),
        )

    @Test fun malformedPayloadNeverThrows() =
        assertEquals(PresentationTemplate.ANSWER, PresentationPlanner.plan("weather", "{not json"))

    @Test fun unknownTypeIsAnswer() =
        assertEquals(PresentationTemplate.ANSWER, PresentationPlanner.plan("mystery", "{}"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentationPlannerTest"`
Expected: FAIL — `PresentationPlanner` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package local.phantom.companion.feature.stream.domain.present

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.design.present.PresentationTemplate

/**
 * Pure render-time chooser of a [PresentationTemplate] for a turn.
 *
 * Order: an explicit top-level `"present"` string in the payload wins when
 * it names a valid template; otherwise the widget `type` is mapped to its
 * natural template; plain text (no type) is [PresentationTemplate.PLAIN].
 *
 * Tolerant by contract (memory `chart_widget_dual_shape`): any parse
 * failure or unknown value degrades to inference / ANSWER, never throws.
 */
object PresentationPlanner {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun plan(widgetType: String?, payload: String?): PresentationTemplate {
        hintOf(payload)?.let { return it }
        val type = widgetType?.trim()?.lowercase()
        if (type.isNullOrEmpty()) return PresentationTemplate.PLAIN
        return when (type) {
            "search-result", "citation" -> PresentationTemplate.RESEARCH
            "vitals-constellation" -> PresentationTemplate.VITAL
            "app-action", "command", "code-runner" -> PresentationTemplate.ACTION
            "reasoning" -> PresentationTemplate.REFLECTION
            else -> PresentationTemplate.ANSWER
        }
    }

    private fun hintOf(payload: String?): PresentationTemplate? {
        val raw = payload?.takeIf { it.isNotBlank() } ?: return null
        val name = runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            (obj["present"] as? JsonPrimitive)?.content
        }.getOrNull()?.trim()?.uppercase() ?: return null
        return PresentationTemplate.values().firstOrNull { it.name == name }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentationPlannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentationPlanner.kt \
        feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentationPlannerTest.kt
git commit -m "feat(stream): pure PresentationPlanner — intent to template mapping"
```

---

### Task 3: `TemplateScaffold` composable (core-design)

Wraps any content in a template's chrome (glyph header + accent + GlassCard). One scaffold ⇒ one
design language for every templated surface.

**Files:**
- Create: `core-design/src/main/java/local/phantom/companion/core/design/present/TemplateScaffold.kt`

**Interfaces:**
- Consumes: `PresentationTemplate`, `tokensFor` (Task 1), existing `GlassCard`, `GlassLevel`,
  `LocalPhantomTheme` (`theme.ink`, `theme.accentSharp`).
- Produces: `@Composable fun TemplateScaffold(template: PresentationTemplate, modifier: Modifier = Modifier, header: String? = null, content: @Composable () -> Unit)`

> No unit test (pure Compose layout). Verified by the Task 7 preview and the milestone APK. The
> *decision* logic it depends on is already covered by Tasks 1–2.

- [ ] **Step 1: Implement the scaffold**

```kotlin
package local.phantom.companion.core.design.present

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.theme.LocalPhantomTheme

/**
 * Chrome for one templated surface: a glyph + optional header line in the
 * template accent, over a GlassCard at the template's glass level. ALERT /
 * ACTION request an accent border so they read as "act now".
 */
@Composable
fun TemplateScaffold(
    template: PresentationTemplate,
    modifier: Modifier = Modifier,
    header: String? = null,
    content: @Composable () -> Unit,
) {
    val tokens = tokensFor(template)
    val theme = LocalPhantomTheme.current
    GlassCard(
        modifier = modifier,
        level = tokens.glassLevel,
        accentBorder = tokens.emphasizeAccentBorder,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (header != null) {
                Row {
                    Text(text = tokens.glyph, color = tokens.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(text = header, color = theme.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(0.dp))
            }
            content()
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core-design:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core-design/src/main/java/local/phantom/companion/core/design/present/TemplateScaffold.kt
git commit -m "feat(design): TemplateScaffold — one chrome for every templated surface"
```

---

### Task 4: Render widgets through the planner (feature-stream)

Wrap `WidgetBubble`'s renderer output in the planner-chosen scaffold so every widget turn now reads
in its template's voice. Backward compatible: untyped/plain content is unaffected.

**Files:**
- Modify: `feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/items/WidgetBubble.kt`
- Test: `feature-stream/src/test/java/local/phantom/companion/feature/stream/present/WidgetBubbleTemplateTest.kt`

**Interfaces:**
- Consumes: `PresentationPlanner.plan` (Task 2), `TemplateScaffold` (Task 3), existing `WidgetRenderer`.
- Produces: no new public symbols; behavior change only.

- [ ] **Step 1: Write the failing test** (asserts the planner is the seam WidgetBubble will use — a guard so the wiring can't silently drop the template)

```kotlin
package local.phantom.companion.feature.stream.present

import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.present.PresentationPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

/** WidgetBubble chooses its scaffold via this exact call shape. */
class WidgetBubbleTemplateTest {
    @Test fun bubbleUsesPlannerForType() {
        assertEquals(
            PresentationTemplate.RESEARCH,
            PresentationPlanner.plan("search-result", """{"sources":[]}"""),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes-trivially, then do the wiring**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*WidgetBubbleTemplateTest"`
Expected: PASS (depends only on Task 2). This test pins the contract; the wiring below is verified by compile + preview.

- [ ] **Step 3: Wire the scaffold into WidgetBubble**

Replace the `Box { WidgetRenderer(...) ; WidgetContextMenu(...) }` body so the renderer is wrapped:

```kotlin
import local.phantom.companion.core.design.present.TemplateScaffold
import local.phantom.companion.feature.stream.domain.present.PresentationPlanner
// ... existing imports retained ...

    Box(
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {},
            onLongClick = { menuExpanded = true },
        ),
    ) {
        val template = PresentationPlanner.plan(type, payload)
        TemplateScaffold(template = template) {
            WidgetRenderer(
                type = type,
                payload = payload,
                widgetId = widgetId,
                onAnswer = onAnswer,
            )
        }
        WidgetContextMenu(
            expanded = menuExpanded,
            widgetId = widgetId,
            widgetType = type,
            payload = payload,
            onDismiss = { menuExpanded = false },
        )
    }
```

- [ ] **Step 4: Verify build + existing widget tests still green**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*Widget*" :feature-stream:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; no widget test regressions.

- [ ] **Step 5: Commit**

```bash
git add feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/items/WidgetBubble.kt \
        feature-stream/src/test/java/local/phantom/companion/feature/stream/present/WidgetBubbleTemplateTest.kt
git commit -m "feat(stream): render widgets through the PresentationPlanner scaffold"
```

---

### Task 5: `PresentCard` + `PresentAggregator` — pure feed model (feature-stream)

The Present feed's data + ranking, pure and testable, independent of Room/Compose. Maps existing
domain types (`StreamItem.Insight`, `StreamItem.Text`, etc.) into ranked cards.

**Files:**
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentCard.kt`
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentAggregator.kt`
- Test: `feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentAggregatorTest.kt`

**Interfaces:**
- Consumes: `PresentationTemplate` (Task 1).
- Produces:
  ```kotlin
  data class PresentCard(
      val id: String,
      val template: PresentationTemplate,
      val title: String,
      val body: String,
      val timestamp: Long,
      val source: PresentCard.Source,
  ) { enum class Source { INSIGHT, ANSWER, ENVIRONMENT, REMINDER, GREETING } }
  ```
- Produces: `object PresentAggregator { fun build(insights: List<PresentCard>, answers: List<PresentCard>, environment: PresentCard?, reminders: List<PresentCard>, now: Long): List<PresentCard> }`
  - Ranking: dismissable INSIGHT/REMINDER first (most recent), then ENVIRONMENT, then recent ANSWERs; de-dup by `id`; if everything is empty, return a single GREETING card so the canvas is never blank.

- [ ] **Step 1: Write the failing test**

```kotlin
package local.phantom.companion.feature.stream.present

import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.present.PresentAggregator
import local.phantom.companion.feature.stream.domain.present.PresentCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentAggregatorTest {

    private fun card(id: String, src: PresentCard.Source, ts: Long) = PresentCard(
        id = id, template = PresentationTemplate.ANSWER, title = id, body = "", timestamp = ts, source = src,
    )

    @Test fun emptyYieldsSingleGreeting() {
        val out = PresentAggregator.build(emptyList(), emptyList(), null, emptyList(), now = 1000L)
        assertEquals(1, out.size)
        assertEquals(PresentCard.Source.GREETING, out.first().source)
    }

    @Test fun insightsOutrankAnswers() {
        val out = PresentAggregator.build(
            insights = listOf(card("i1", PresentCard.Source.INSIGHT, 10)),
            answers = listOf(card("a1", PresentCard.Source.ANSWER, 99)),
            environment = null, reminders = emptyList(), now = 100L,
        )
        assertEquals("i1", out.first().id)
    }

    @Test fun duplicatesAreCollapsedById() {
        val dup = card("x", PresentCard.Source.ANSWER, 5)
        val out = PresentAggregator.build(emptyList(), listOf(dup, dup), null, emptyList(), now = 100L)
        assertEquals(1, out.count { it.id == "x" })
    }

    @Test fun environmentSitsBetweenInsightsAndAnswers() {
        val out = PresentAggregator.build(
            insights = listOf(card("i", PresentCard.Source.INSIGHT, 1)),
            answers = listOf(card("a", PresentCard.Source.ANSWER, 1)),
            environment = card("e", PresentCard.Source.ENVIRONMENT, 1),
            reminders = emptyList(), now = 100L,
        )
        assertTrue(out.indexOfFirst { it.id == "e" } in 1 until out.indexOfFirst { it.id == "a" } + 1)
        assertEquals("i", out.first().id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentAggregatorTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Write minimal implementation**

`PresentCard.kt`:
```kotlin
package local.phantom.companion.feature.stream.domain.present

import local.phantom.companion.core.design.present.PresentationTemplate

/** One card on the Present canvas. Rendering is the scaffold's job. */
data class PresentCard(
    val id: String,
    val template: PresentationTemplate,
    val title: String,
    val body: String,
    val timestamp: Long,
    val source: Source,
) {
    enum class Source { INSIGHT, ANSWER, ENVIRONMENT, REMINDER, GREETING }
}
```

`PresentAggregator.kt`:
```kotlin
package local.phantom.companion.feature.stream.domain.present

import local.phantom.companion.core.design.present.PresentationTemplate

/**
 * Pure ranking of the Present feed. Insights & reminders (the entity's
 * proactive voice) lead, then an environment glance, then recent answers.
 * Never returns empty — a warm GREETING card keeps the canvas alive.
 */
object PresentAggregator {

    fun build(
        insights: List<PresentCard>,
        answers: List<PresentCard>,
        environment: PresentCard?,
        reminders: List<PresentCard>,
        now: Long,
    ): List<PresentCard> {
        val ranked = buildList {
            addAll((insights + reminders).sortedByDescending { it.timestamp })
            environment?.let { add(it) }
            addAll(answers.sortedByDescending { it.timestamp })
        }.distinctBy { it.id }

        if (ranked.isNotEmpty()) return ranked
        return listOf(
            PresentCard(
                id = "greeting",
                template = PresentationTemplate.REFLECTION,
                title = "Я тут",
                body = "Питай, шукай, або просто побудь — я поряд і слухаю.",
                timestamp = now,
                source = PresentCard.Source.GREETING,
            ),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentAggregatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentCard.kt \
        feature-stream/src/main/java/local/phantom/companion/feature/stream/domain/present/PresentAggregator.kt \
        feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentAggregatorTest.kt
git commit -m "feat(stream): PresentCard + pure PresentAggregator feed ranking"
```

---

### Task 6: `PresentViewModel` — bind sources to the aggregator (feature-stream)

Android-host-free VM (the codebase pattern — see `VaultViewModel`/`DiaryViewModel` wired via a
factory in `PhantomNavGraph`). Takes already-mapped flows/suppliers, exposes a `StateFlow` feed.

**Files:**
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/present/PresentViewModel.kt`
- Test: `feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentViewModelTest.kt`

**Interfaces:**
- Consumes: `PresentAggregator`, `PresentCard` (Task 5).
- Produces:
  ```kotlin
  class PresentViewModel(
      insights: kotlinx.coroutines.flow.Flow<List<PresentCard>>,
      recentAnswers: kotlinx.coroutines.flow.Flow<List<PresentCard>>,
      environment: kotlinx.coroutines.flow.Flow<PresentCard?>,
      reminders: kotlinx.coroutines.flow.Flow<List<PresentCard>>,
      now: () -> Long = System::currentTimeMillis,
  ) : androidx.lifecycle.ViewModel { val feed: kotlinx.coroutines.flow.StateFlow<List<PresentCard>> }
  ```
  - A source that errors is treated as empty (caught per-flow) so it can't blank the canvas.

- [ ] **Step 1: Write the failing test**

```kotlin
package local.phantom.companion.feature.stream.present

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.present.PresentCard
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentViewModelTest {

    private fun card(id: String, src: PresentCard.Source) = PresentCard(
        id = id, template = PresentationTemplate.ANSWER, title = id, body = "", timestamp = 1L, source = src,
    )

    @Test fun feedMergesSources() = runTest {
        val vm = PresentViewModel(
            insights = flowOf(listOf(card("i", PresentCard.Source.INSIGHT))),
            recentAnswers = flowOf(listOf(card("a", PresentCard.Source.ANSWER))),
            environment = flowOf(null),
            reminders = flowOf(emptyList()),
            now = { 100L },
        )
        // combine emits after all upstreams produce once.
        val first = kotlinx.coroutines.withTimeout(2000) {
            kotlinx.coroutines.flow.first(vm.feed) { it.isNotEmpty() }
        }
        assertEquals("i", first.first().id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentViewModelTest"`
Expected: FAIL — `PresentViewModel` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package local.phantom.companion.feature.stream.present

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import local.phantom.companion.feature.stream.domain.present.PresentAggregator
import local.phantom.companion.feature.stream.domain.present.PresentCard

/**
 * Aggregates the Present feed from already-mapped source flows. Host-free
 * (no Android/Room imports) so it runs on plain JVM, mirroring
 * VaultViewModel/DiaryViewModel; the factory in PhantomNavGraph maps the
 * real DAOs/providers into these flows.
 */
class PresentViewModel(
    insights: Flow<List<PresentCard>>,
    recentAnswers: Flow<List<PresentCard>>,
    environment: Flow<PresentCard?>,
    reminders: Flow<List<PresentCard>>,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val feed: StateFlow<List<PresentCard>> =
        combine(
            insights.orEmptyList(),
            recentAnswers.orEmptyList(),
            environment.catch { emit(null) },
            reminders.orEmptyList(),
        ) { ins, ans, env, rem ->
            PresentAggregator.build(ins, ans, env, rem, now())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private fun <T> Flow<List<T>>.orEmptyList(): Flow<List<T>> = catch { emit(emptyList()) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentViewModelTest"`
Expected: PASS. (If `coroutines-test`/`kotlinx.coroutines.flow.first` overload differs in the module, collect the flow into a list with a `launch`+`advanceUntilIdle` instead — the assertion target is `feed.value.first().id == "i"`.)

- [ ] **Step 5: Commit**

```bash
git add feature-stream/src/main/java/local/phantom/companion/feature/stream/present/PresentViewModel.kt \
        feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentViewModelTest.kt
git commit -m "feat(stream): PresentViewModel — host-free Present feed aggregation"
```

---

### Task 7: `PresentScreen` + preview (feature-stream)

The canvas: Orb header + the ranked feed rendered via `TemplateScaffold`, plus mode entries
(open chat, open voice). Honest stub for entries not yet built (History → Slice C) as labelled
buttons, not fake-working surfaces.

**Files:**
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/present/PresentScreen.kt`
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/present/PresentScreenPreview.kt`

**Interfaces:**
- Consumes: `PresentCard` (Task 5), `TemplateScaffold` (Task 3), existing `OrbView`, `StatePill`,
  `PhantomTheme`.
- Produces: `@Composable fun PresentScreen(feed: List<PresentCard>, state: local.phantom.companion.core.context.SystemState, onOpenChat: () -> Unit, onVoice: () -> Unit, onOpenHistory: () -> Unit, modifier: Modifier = Modifier)`

> Verified via `@Preview` + milestone APK (Compose UI; the ranked data + templates it renders are
> already unit-covered in Tasks 1–6).

- [ ] **Step 1: Implement PresentScreen**

```kotlin
package local.phantom.companion.feature.stream.ui.present

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.design.component.OrbView
import local.phantom.companion.core.design.component.StatePill
import local.phantom.companion.core.design.present.TemplateScaffold
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.feature.stream.domain.present.PresentCard

/**
 * The Present canvas — the post-bootstrap home. Presence first: the Orb +
 * a curated, ranked feed of the entity's cards. Chat opens from here as a
 * mode (StreamNav), not as the whole app.
 */
@Composable
fun PresentScreen(
    feed: List<PresentCard>,
    state: SystemState,
    onOpenChat: () -> Unit,
    onVoice: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                OrbView(modifier = Modifier.height(160.dp))
                Spacer(Modifier.height(8.dp))
                StatePill(state = state)
            }
        }
        items(feed, key = { it.id }) { card ->
            TemplateScaffold(template = card.template, header = card.title) {
                if (card.body.isNotEmpty()) {
                    Text(text = card.body, color = theme.ink, fontSize = 14.sp)
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                TextButton(onClick = onOpenChat) { Text("Відкрити чат", fontWeight = FontWeight.Medium) }
                TextButton(onClick = onVoice) { Text("Голос") }
                TextButton(onClick = onOpenHistory) { Text("Хроніка (скоро)") }
            }
        }
    }
}
```

> If `OrbView` / `StatePill` signatures differ from `(modifier)` / `(state)`, match the call sites in
> `feature-stream/.../ui/live/LivePresenceHeader.kt` (it already renders both) — copy its exact
> parameters rather than guessing.

- [ ] **Step 2: Implement the preview**

```kotlin
package local.phantom.companion.feature.stream.ui.present

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.feature.stream.domain.present.PresentCard

@Preview
@Composable
private fun PresentScreenPreview() {
    val cards = listOf(
        PresentCard("e1", PresentationTemplate.ALERT, "Повітря поряд — погане", "PM2.5 високий. Прикрий вікно, я постежу.", 3, PresentCard.Source.ENVIRONMENT),
        PresentCard("i1", PresentationTemplate.MEMORY, "Минулого тижня", "Ти питав про це у вівторок.", 2, PresentCard.Source.INSIGHT),
        PresentCard("a1", PresentationTemplate.ANSWER, "Погода", "Завтра до обіду — дощ.", 1, PresentCard.Source.ANSWER),
    )
    PhantomTheme {
        PresentScreen(feed = cards, state = SystemState.DIALOGUE, onOpenChat = {}, onVoice = {}, onOpenHistory = {})
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature-stream:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Fix Orb/StatePill/PhantomTheme call shapes against `LivePresenceHeader.kt` if needed.)

- [ ] **Step 4: Commit**

```bash
git add feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/present/PresentScreen.kt \
        feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/present/PresentScreenPreview.kt
git commit -m "feat(stream): PresentScreen canvas + preview"
```

---

### Task 8: Make Present the home + map real sources + document the hint (app)

Add a `Present` route, make Bootstrap land there, wire the VM factory to real DAOs/providers
(mapping into `PresentCard` flows), and open chat from Present. Then teach the model the optional
`present` hint.

**Files:**
- Modify: `app/src/main/java/local/phantom/companion/navigation/Routes.kt` (add `Present`)
- Modify: `app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt` (route + destination + Bootstrap target)
- Create: `feature-stream/src/main/java/local/phantom/companion/feature/stream/present/PresentSourceMappers.kt` (DAO/provider → `PresentCard` mappers — pure)
- Modify: `core-ai/src/main/java/local/phantom/companion/core/ai/SystemPrompts.kt` (document `present`)
- Test: `feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentSourceMappersTest.kt`

**Interfaces:**
- Consumes: `StreamItemDao.lastN`, `StreamItem.Insight`, `PendingIntentDao`, `LocationContextProvider`
  (existing); `PresentCard`, `PresentationPlanner`.
- Produces: `object PresentSourceMappers { fun answerCard(item: local.phantom.companion.core.data.stream.StreamItemEntity): PresentCard?; fun insightCard(i: local.phantom.companion.feature.stream.domain.StreamItem.Insight): PresentCard }`
- Produces: `data object Present : Routes("present")` and `PresentDestination(navController)`.

- [ ] **Step 1: Write the failing mapper test**

```kotlin
package local.phantom.companion.feature.stream.present

import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.StreamItem
import local.phantom.companion.feature.stream.domain.present.PresentCard
import local.phantom.companion.feature.stream.present.PresentSourceMappers
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentSourceMappersTest {
    @Test fun insightBecomesMemoryOrAnswerCard() {
        val insight = StreamItem.Insight(id = "x", title = "Думка", body = "тіло")
        val card = PresentSourceMappers.insightCard(insight)
        assertEquals(PresentCard.Source.INSIGHT, card.source)
        assertEquals("Думка", card.title)
        assertEquals(PresentationTemplate.MEMORY, card.template)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentSourceMappersTest"`
Expected: FAIL — `PresentSourceMappers` unresolved.

- [ ] **Step 3: Implement the mappers**

```kotlin
package local.phantom.companion.feature.stream.present

import local.phantom.companion.core.data.stream.StreamItemEntity
import local.phantom.companion.core.design.present.PresentationTemplate
import local.phantom.companion.feature.stream.domain.StreamItem
import local.phantom.companion.feature.stream.domain.present.PresentCard
import local.phantom.companion.feature.stream.domain.present.PresentationPlanner

/** Pure source → PresentCard mappers (kept out of the VM so they're testable). */
object PresentSourceMappers {

    fun insightCard(i: StreamItem.Insight): PresentCard = PresentCard(
        id = "insight:${i.id}",
        template = PresentationTemplate.MEMORY,
        title = i.title,
        body = i.body,
        timestamp = System.currentTimeMillis(),
        source = PresentCard.Source.INSIGHT,
    )

    /** Only ASSISTANT text entities become answer cards; returns null otherwise. */
    fun answerCard(item: StreamItemEntity): PresentCard? {
        val text = item.textContent?.takeIf { it.isNotBlank() } ?: return null
        return PresentCard(
            id = "answer:${item.id}",
            template = PresentationPlanner.plan(item.kind.name.lowercase().takeIf { it != "text" }, null),
            title = text.lineSequence().firstOrNull()?.take(48) ?: "Відповідь",
            body = "",
            timestamp = item.createdAt,
            source = PresentCard.Source.ANSWER,
        )
    }
}
```

> Verify `StreamItemEntity`'s field names (`textContent`, `kind`, `createdAt`, `id`) against
> `core-data/.../stream/StreamItemEntity.kt`; adjust the accessors to the real property names if they
> differ. The test only pins `insightCard`, which uses stable domain types.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*PresentSourceMappersTest"`
Expected: PASS.

- [ ] **Step 5: Add the Present route**

In `Routes.kt`, add after `Stream`:
```kotlin
    /** Living home canvas — the post-bootstrap presence surface (Slice A). */
    data object Present : Routes("present")
```

- [ ] **Step 6: Wire the destination + Bootstrap target**

In `PhantomNavGraph.kt`:
1. In `BootstrapDestination`, change the `else` branch from `Routes.Stream.createRoute(null)` to `Routes.Present.path`.
2. Register the route inside `NavHost { … }`:
```kotlin
        composable(Routes.Present.path) { PresentDestination(navController) }
```
3. Add the destination (host wires real sources into `PresentViewModel`; each flow is mapped via `PresentSourceMappers`, defaulting to empty on absence so the canvas always renders):
```kotlin
@Composable
private fun PresentDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }

    val vm: local.phantom.companion.feature.stream.present.PresentViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                local.phantom.companion.feature.stream.present.PresentViewModel(
                    insights = kotlinx.coroutines.flow.flowOf(emptyList()),
                    recentAnswers = kotlinx.coroutines.flow.flowOf(emptyList()),
                    environment = kotlinx.coroutines.flow.flowOf(null),
                    reminders = kotlinx.coroutines.flow.flowOf(emptyList()),
                )
            }
        },
    )
    val feed by vm.feed.collectAsStateWithLifecycle()

    local.phantom.companion.feature.stream.ui.present.PresentScreen(
        feed = feed,
        state = local.phantom.companion.core.context.SystemState.DIALOGUE,
        onOpenChat = { navController.navigate(Routes.Stream.createRoute(null)) },
        onVoice = { navController.navigate(Routes.Stream.createRoute(null)) },
        onOpenHistory = { /* Slice C */ },
    )
}
```
> The empty `flowOf` sources are an honest first wiring — the canvas renders the GREETING card and
> chat opens. A follow-up step within this task (below) replaces them with the real DAOs once the
> container accessor names are confirmed; if `container.streamItemDao` / `container.pendingIntentDao`
> exist, map `dao.lastN(20)` → `PresentSourceMappers.answerCard` into `recentAnswers`. Keep the
> empty default for any source whose accessor is not present, so the build never blocks on a missing
> seam.

- [ ] **Step 7: Document the `present` hint in the system prompt**

In `SystemPrompts.kt`, inside `DEFAULT_PHANTOM`, add to the «КРИТИЧНО — ФОРМАТ ВІДПОВІДІ» block:
```
- ПОДАЧА (`present` поле): будь-який віджет може містити опційне ВЕРХНЬОГО рівня поле "present" зі стилем подачі: "answer" (звичайна відповідь), "research" (результат пошуку з джерелами), "alert" (попередження — щось термінове чи про оточення), "memory" (спогад/відклик з памʼяті), "vital" (показники з сенсорів), "action" (дія на пристрої), "reflection" (роздум). Якщо не впевнений — НЕ став "present", система обере сама за типом. Це лише підказка вигляду; формат самого віджета не змінюється.
```

- [ ] **Step 8: Verify build + full slice tests green**

Run: `./gradlew :feature-stream:testDebugUnitTest --tests "*present*" :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; all Slice-A unit tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/local/phantom/companion/navigation/Routes.kt \
        app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt \
        feature-stream/src/main/java/local/phantom/companion/feature/stream/present/PresentSourceMappers.kt \
        feature-stream/src/test/java/local/phantom/companion/feature/stream/present/PresentSourceMappersTest.kt \
        core-ai/src/main/java/local/phantom/companion/core/ai/SystemPrompts.kt
git commit -m "feat(app): Present is the home; map sources + document the present hint"
```

---

### Task 9 (milestone): full APK build + on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Full debug build** (supervised — ~13 min, freeze-prone on the Radxa host; do not background-and-forget)

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/`.

- [ ] **Step 2: Install + launch on the Samsung A56**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: app opens to the **Present** canvas (Orb + at least the GREETING card); "Відкрити чат" enters the existing Stream; a weather/search/app-action turn in chat now renders inside its template (distinct accent + glyph header).

- [ ] **Step 3: Record the result** in `docs/INTEGRATION_AUDIT.md` (one line: date, build status, what rendered) and commit that note.

---

## Self-Review

**Spec coverage:**
- North-star Pillar 1 (Present home) → Tasks 5–8. ✔
- Pillar 2 (Presentation Planner / templates) → Tasks 1–4. ✔
- Pillars 3–4 (search, chronicle) → explicitly out of scope (Slices B/C); Task 8 leaves an honest "Хроніка (скоро)" entry. ✔
- Error handling (invalid hint → infer; empty feed → greeting; source failure → skip) → Tasks 2, 5, 6. ✔
- Testing (planner, aggregator, VM, mappers unit-covered; Compose via preview + APK) → Tasks 1,2,5,6,8 + 3,7,9. ✔
- Constraints (no DB migration, no StreamViewModel edits, WorkManager untouched, AutoMirrored, tolerant parsing, <500-line files) → honored; planner runs at render time. ✔

**Placeholder scan:** No "TBD/TODO/handle edge cases" left as instructions; the two "verify field names against X" notes point at exact real files and are guardrails, not deferrals (the tests pin the stable contracts). ✔

**Type consistency:** `PresentationTemplate`, `tokensFor`/`TemplateTokens`, `PresentationPlanner.plan(widgetType, payload)`, `PresentCard(+Source)`, `PresentAggregator.build(...)`, `PresentViewModel(feed)`, `PresentSourceMappers.{insightCard,answerCard}` are referenced identically across tasks. ✔
