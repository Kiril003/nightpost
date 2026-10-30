package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.R as DesignR

/**
 * Двері до вибору території за назвою — «місто», «область», «країна».
 *
 * Самі збережені місця (стан, пауза, прибрати цілим) живуть у Профілі ›
 * Офлайн-мапи разом із рештою власності ([OfflineMapsCatalog]); тут лишилось
 * те, що починає НОВЕ завантаження, і чесне застереження, якщо частину місць
 * тягнули під інший стиль карти.
 *
 * Свого менеджера не заводить: панель тримає один на всіх.
 */
@Composable
internal fun OfflinePlacesSection(
    manager: OfflineRegionsManager,
    styleUrl: String,
    regions: List<RegionInfo>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var pickerOpen by remember { mutableStateOf(false) }
    val deviceAverage = remember(regions) { manager.deviceAverage() }
    val usableBytes = remember(regions) { manager.usableDiskBytes() }
    val otherStyle = remember(regions, styleUrl) {
        OfflinePlaces.group(regions).any { it.styleUrl != null && it.styleUrl != styleUrl }
    }

    OfflinePlacesDoor(otherStyle = otherStyle, onOpen = { pickerOpen = true }, modifier = modifier)

    if (pickerOpen) {
        OfflinePlacePickerDialog(
            deviceAverage = deviceAverage,
            usableBytes = usableBytes,
            onDismiss = { pickerOpen = false },
            onDownload = { plan ->
                pickerOpen = false
                scope.launch { runCatching { manager.createPlacePacks(plan, styleUrl) } }
            },
        )
    }
}

/** Двері до вибору місця — рядок групи; під ним, якщо треба, застереження про стиль. */
@Composable
internal fun OfflinePlacesDoor(otherStyle: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        val label = stringResource(R.string.map_places_open)
        MapRowGroup {
            MapListRow(
                title = label,
                leading = { MapIcon(DesignR.drawable.lc_search) },
                onClick = onOpen,
                spoken = label,
                trailing = {
                    Box(Modifier.padding(end = PhantomSpace.s3)) {
                        MapIcon(DesignR.drawable.lc_chevron_right, tint = c.text3, size = PhantomSize.iconS)
                    }
                },
            )
        }

        if (otherStyle) {
            Text(
                text = stringResource(R.string.map_places_other_style),
                style = PhantomText.footnote,
                color = c.warning,
            )
        }
    }
}
