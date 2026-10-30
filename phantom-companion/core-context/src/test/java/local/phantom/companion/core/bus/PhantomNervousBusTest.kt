package local.phantom.companion.core.bus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhantomNervousBusTest {

    @Test
    fun testPublishAndCollect() = runTest {
        val bus = PhantomNervousBus()
        val eventsList = mutableListOf<PhantomEvent>()

        val job = launch {
            bus.events.collect { eventsList.add(it) }
        }

        val event1 = PhantomEvent(
            id = "1",
            timestampMs = 12345L,
            sourceOrgan = "Test",
            type = "TypeA",
            payloadJson = "{}",
            deviceOrigin = "test-device",
            privacy = "public"
        )
        val event2 = PhantomEvent(
            id = "2",
            timestampMs = 12346L,
            sourceOrgan = "Test",
            type = "TypeB",
            payloadJson = "{}",
            deviceOrigin = "test-device",
            privacy = "trusted"
        )

        bus.publish(event1)
        bus.publish(event2)

        // Give the collector coroutine a chance to run
        testScheduler.advanceUntilIdle()

        assertEquals(2, eventsList.size)
        assertEquals("1", eventsList[0].id)
        assertEquals("TypeA", eventsList[0].type)
        assertEquals("2", eventsList[1].id)
        assertEquals("TypeB", eventsList[1].type)

        job.cancel()
    }
}
