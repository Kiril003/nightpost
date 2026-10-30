package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.peers.PeerSeeRow
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetTitle
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.around.AroundRating
import local.phantom.companion.feature.stream.ui.map.peers.PeerAvatar
import java.util.Locale

/** Фото веде сторінку лише тоді, коли стаття саме про це місце. */
internal fun placeHeroUrl(dossier: PlaceDossier): String? =
    dossier.reference?.takeIf { it.isAboutEntity }?.article?.thumbnailUrl?.takeIf { it.isNotBlank() }

internal fun AroundPlace.dossierTarget(): DossierTarget =
    DossierTarget(lat, lon, nameHint = name, categoryHint = category, subcategoryHint = subcategory, rating = rating, venueId = venueId)

internal fun placeRatingLine(r: AroundRating?, fmt: (String, Int) -> String, locale: Locale = Locale.getDefault()): String? =
    r?.takeIf { it.count > 0 }?.let { fmt(String.format(locale, "%.1f", it.mean), it.count) }

internal fun placeSendPeople(rows: List<PeerSeeRow>): List<Pair<String, String>> =
    rows.filter { it.name.isNotBlank() }
        .distinctBy { it.peerId }
        .sortedBy { it.name.lowercase() }
        .map { it.peerId to it.name }

@Composable
internal fun PlaceHero(url: String) {
    val photo = rememberPlacePhoto(url) ?: return
    Image(
        bitmap = photo,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .testTag("place-hero"),
    )
    Spacer(Modifier.height(PhantomSpace.s3))
}

@Composable
private fun rememberPlacePhoto(url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "NightpostCompanion/0.1")
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?.asImageBitmap()
                    .also { conn.disconnect() }
            }.getOrNull()
        }
    }
    return bitmap
}

/** «У чат»: дотик по людині — і місце йде в розмову з нею. */
@Composable
internal fun PlaceSendSheet(place: String, people: List<Pair<String, String>>, onSend: (String, String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = readingGutter(PhantomSpace.gutterPhone))
            .padding(bottom = PhantomSpace.s6)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        SheetTitle(stringResource(R.string.place_send_title, place))
        if (people.isEmpty()) {
            SheetNote(stringResource(R.string.place_send_none), modifier = Modifier.testTag("place-send-none"))
            return@Column
        }
        people.forEach { (id, name) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomTouchFloor)
                    .clickable(role = Role.Button) { onSend(id, name) }
                    .semantics(mergeDescendants = true) { contentDescription = name },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(name, 36.dp, toneKey = id)
                Spacer(Modifier.width(PhantomSpace.s3))
                SheetBody(name, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val HERO_HEIGHT = 160.dp
