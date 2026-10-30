package local.phantom.companion.feature.stream.ui.map.indoor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapTouch

/**
 * INDOOR-2 — вертикальний перемикач поверхів для багатоповерхових будівель.
 *
 * Сегментований контрол v1.2: піднята доріжка з волосиною й тінню (та сама
 * поверхня, що в пігулок і чипів мапи), сегмент 48dp — палець, а не олівець,
 * обраний поверх — бурштин дії з чорнилом onAccent. Підпис «Поверх» — із
 * ресурсів, мовою людини. Раніше тут було скло GlassCard, кнопки 36dp і білі
 * обводки, що на денній мапі зникали.
 */
@Composable
fun IndoorLevelSwitcher(
    visible: Boolean,
    availableLevels: List<Int>,
    currentLevel: Int,
    onLevelSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val levels = if (availableLevels.isEmpty()) listOf(0) else availableLevels.sortedDescending()

    AnimatedVisibility(
        visible = visible && levels.size > 1,
        enter = fadeIn() + slideInHorizontally { -it },
        exit = fadeOut() + slideOutHorizontally { -it },
        modifier = modifier,
    ) {
        val c = LocalPhantomColors.current
        val track = RoundedCornerShape(PhantomRadius.control + PhantomSpace.s1)
        val segment = RoundedCornerShape(PhantomRadius.control)
        Column(
            modifier = Modifier
                .clip(track)
                .background(c.raised)
                .border(1.dp, c.line, track)
                .padding(PhantomSpace.s1)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s0_5),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.indoor_level_title),
                style = PhantomText.caption,
                color = c.text3,
                modifier = Modifier.padding(top = PhantomSpace.s1, bottom = PhantomSpace.s0_5),
            )
            // Згори вниз, як у ліфті: верхній поверх — нагорі.
            levels.forEach { lvl ->
                val selected = lvl == currentLevel
                val label = indoorLevelLabel(lvl)
                val spoken = stringResource(R.string.indoor_level_desc, label)
                Box(
                    modifier = Modifier
                        .size(MapTouch)
                        .clip(segment)
                        .background(if (selected) c.accent else c.raised)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onLevelSelected(lvl) },
                        )
                        .semantics { contentDescription = spoken },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = PhantomText.subhead.copy(fontFeatureSettings = "tnum"),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) c.onAccent else c.text,
                    )
                }
            }
        }
    }
}

/**
 * Підпис рівня OSM так, як його читає людина в ліфті: `level=0` — перший
 * поверх, вище — «+1», «+2», нижче — «-1». Чиста функція, бо це правило, а не
 * вигляд.
 */
internal fun indoorLevelLabel(level: Int): String = when {
    level > 0 -> "+$level"
    level == 0 -> "1"
    else -> "$level"
}
