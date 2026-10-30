package local.phantom.companion.core.predict

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import local.phantom.companion.core.bus.PhantomEvent
import local.phantom.companion.core.bus.PhantomNervousBus
import local.phantom.companion.core.context.*
import local.phantom.companion.core.context.SystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForesightEngineTest {

    @Test
    fun testForesightEvaluate() = runTest {
        val bus = PhantomNervousBus()
        val published = mutableListOf<PhantomEvent>()
        val collectJob = launch {
            bus.events.collect { published.add(it) }
        }

        // Mock a fixed timestamp for predictable scheduled review checks:
        // 90 days is 90 * 24 * 3600 * 1000 = 7,776,000,000 ms.
        val baseTimeMs = 1000000000L
        val evaluateTimeMs = baseTimeMs + 95 * 24 * 3600 * 1000L // 95 days later

        val engine = ForesightEngine(
            bus = bus,
            deviceId = "test_device",
            clock = { evaluateTimeMs }
        )

        // 1. Setup Principle
        val principle = Principle(
            id = "p1",
            declaredAtMs = baseTimeMs,
            statement = "Don't open instagram at night",
            forbiddenVerbs = setOf("open_app"),
            contextTags = setOf("com.instagram.android")
        )
        engine.registerPrinciple(principle)

        // 2. Setup Conviction
        val conviction = ConvictionScore(
            decisionId = "dec_123",
            score = 9,
            recordedAtMs = baseTimeMs
        )
        engine.registerConviction(conviction)

        // 3. Setup Shimmer Engine route
        val bucket = ShimmerEngine.Bucket(
            state = SystemState.FOCUS.name,
            hour = 13,
            location = "office",
            lastAction = "com.instagram.android" // Match active app to trigger suggest
        )
        engine.registerTransition(bucket, "com.jetbrains.toolbox")

        // 4. Create ContextFrame triggering all checks
        val frame = ContextFrame(
            timestampMs = evaluateTimeMs,
            state = SystemState.FOCUS.name,
            who = WhoContext(profileId = "operator"),
            where = WhereContext(placeLabel = "office"),
            what = WhatContext(
                activeAppPackage = "com.instagram.android",
                detectedSounds = emptyList()
            ),
            feel = FeelContext(),
            owe = OweContext(),
            device = DeviceContext(),
            mesh = MeshContext()
        )

        engine.evaluate(frame)
        yield()

        // Cancel background collector
        collectJob.cancel()

        // Check published events on bus
        val publishedTypes = published.map { it.type }
        assertTrue(publishedTypes.contains(ForesightEngine.TYPE_PROACTIVE_CARD))

        val doubtCards = published.filter { it.payloadJson.contains("doubt_warning") }
        assertEquals(1, doubtCards.size)
        assertTrue(doubtCards[0].payloadJson.contains("instagram"))

        val reviewCards = published.filter { it.payloadJson.contains("conviction_review") }
        assertEquals(1, reviewCards.size)
        assertTrue(reviewCards[0].payloadJson.contains("dec_123"))

        val suggestionCards = published.filter { it.payloadJson.contains("shimmer_suggestion") }
        assertEquals(1, suggestionCards.size)
        assertTrue(suggestionCards[0].payloadJson.contains("com.jetbrains.toolbox"))
    }
}
