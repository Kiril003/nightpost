package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.prefetch.CorridorPrefetcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangFTest {

    @Test
    @Config(qualifiers = "en")
    fun labelsReturnEnglishUnderEn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        MapText.bind(ctx)
        assertEquals(
            "No place pack installed and these places have not been seen yet",
            NOWHERE_TO_LOOK,
        )
        assertEquals(
            "Nightpost did not respond",
            MapText.s(R.string.mapl_f_ai_failed),
        )
        val prefetcher = CorridorPrefetcher(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            fetchRoadWays = { _, _, _, _ -> null },
            heldCells = { LongArray(0) },
            offlineRegions = { error("unused") },
            styleUrl = { "" },
        )
        assertEquals("Nothing is being fetched.", prefetcher.stateLine())
    }

    @Test
    @Config(qualifiers = "uk")
    fun labelsReturnUkrainianUnderUk() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        MapText.bind(ctx)
        assertEquals(
            "Шукати нема де: пакет місць не встановлено, і я їх ще не бачив",
            NOWHERE_TO_LOOK,
        )
        assertEquals(
            "Nightpost не відповів",
            MapText.s(R.string.mapl_f_ai_failed),
        )
        val prefetcher = CorridorPrefetcher(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            fetchRoadWays = { _, _, _, _ -> null },
            heldCells = { LongArray(0) },
            offlineRegions = { error("unused") },
            styleUrl = { "" },
        )
        assertEquals("Нічого не стягується.", prefetcher.stateLine())
    }
}
