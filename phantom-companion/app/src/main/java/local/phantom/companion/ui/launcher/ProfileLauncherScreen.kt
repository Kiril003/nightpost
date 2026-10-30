package local.phantom.companion.ui.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.data.profile.Profile
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Phase A2 — multi-profile picker.
 *
 * Replaces the "Bypass Launcher for now" hack in the previous ProfileShell.
 * Shown by the nav graph when profiles exist but none is active. Tapping a
 * profile activates it (real `profileStore.setActive()` call) and signals
 * the nav graph to advance to Stream.
 *
 * Сітка безпеки, а не щоденний екран: сюди потрапляють лише тоді, коли процес
 * помер між `create()` і `setActive()` першого запуску. Вигляд — мова форми
 * 1.2 (полотно, підняті плитки, тон людини на колі), щоб і цей рідкісний вхід
 * не повертав людину в старе скло.
 */
@Composable
fun ProfileLauncherScreen(
    onProfileActivated: () -> Unit,
    onAddProfile: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val coroutineScope = rememberCoroutineScope()

    val profiles by container.profileStore.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .systemBarsPadding()
            .padding(horizontal = PhantomSpace.gutterPhone),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(PhantomSpace.s6))
            Text(text = "Nightpost", style = PhantomText.title1, color = c.text)
            Spacer(Modifier.height(PhantomSpace.s1))
            Text(
                text = stringResource(R.string.profile_picker_kicker).sentence(),
                style = PhantomText.callout,
                color = c.text2,
            )
            Spacer(Modifier.height(PhantomSpace.s6))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTile(
                        profile = profile,
                        onActivate = {
                            coroutineScope.launch {
                                container.profileStore.setActive(profile.id)
                                onProfileActivated()
                            }
                        },
                        onRequestDelete = { pendingDelete = profile },
                    )
                }
                item {
                    AddProfileTile(onClick = onAddProfile)
                }
            }
        }

        AnimatedVisibility(
            visible = pendingDelete != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.scrim)
                    .clickable { pendingDelete = null },
                contentAlignment = Alignment.Center,
            ) {
                pendingDelete?.let { target ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PhantomRadius.sheet))
                            .background(c.raised)
                            .clickable(enabled = false) {} // intercept clicks
                            .padding(PhantomSpace.s6),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_delete_title),
                            style = PhantomText.title2,
                            color = c.text,
                        )
                        Spacer(Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.profile_delete_body, target.displayName),
                            style = PhantomText.body,
                            color = c.text2,
                        )
                        Spacer(Modifier.height(PhantomSpace.s6))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            DialogAction(
                                label = stringResource(R.string.profile_delete_cancel).sentence(),
                                ink = c.text2,
                                filled = false,
                            ) { pendingDelete = null }
                            Spacer(Modifier.width(PhantomSpace.s2))
                            // Стирання — небезпека, не бурштин: бурштин тут означав
                            // би «головна дія», а головна дія — не втратити дані.
                            DialogAction(
                                label = stringResource(R.string.profile_delete_confirm).sentence(),
                                ink = c.danger,
                                filled = true,
                            ) {
                                coroutineScope.launch {
                                    val remaining = profiles.filter { it.id != target.id }
                                    container.profileStore.delete(target.id)
                                    container.selfFace.clearForProfile(target.id)
                                    if (remaining.isEmpty()) {
                                        onAddProfile()
                                    } else if (target.isActive && remaining.none { it.isActive }) {
                                        container.profileStore.setActive(remaining.first().id)
                                    }
                                    pendingDelete = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, ink: androidx.compose.ui.graphics.Color, filled: Boolean, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(CircleShape)
            .background(if (filled) c.overlay else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.headline, color = ink)
    }
}

@Composable
private fun ProfileTile(
    profile: Profile,
    onActivate: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val c = LocalPhantomColors.current
    // Той самий тон людини, що й у «Профілі»: коло однієї людини не міняє колір між екранами.
    val tone = c.person[Math.floorMod(profile.id.ifBlank { profile.displayName }.hashCode(), c.person.size)]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.raised)
            .combinedClickable(
                onClick = onActivate,
                onLongClick = onRequestDelete,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(PhantomSpace.s3),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(tone.first),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = PhantomText.display,
                    color = tone.second,
                )
            }
            Spacer(Modifier.height(PhantomSpace.s3))
            Text(
                text = profile.displayName,
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.isActive) {
                Spacer(Modifier.height(PhantomSpace.s1))
                Text(
                    text = stringResource(R.string.profile_tile_active).sentence(),
                    style = PhantomText.footnote,
                    color = c.success,
                )
            }
        }
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.sheet)
    val label = stringResource(R.string.profile_add_cd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .border(1.dp, c.lineStrong, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Плюс малюється тут: у наборі lc_* його немає, а стоковий Icons.Add
            // приніс би в цей екран чужу лінію.
            val ink = c.text2
            Canvas(modifier = Modifier.size(PhantomSize.iconL)) {
                val stroke = 2.dp.toPx()
                val mid = size.minDimension / 2f
                drawLine(ink, Offset(mid, stroke), Offset(mid, size.height - stroke), stroke, StrokeCap.Round)
                drawLine(ink, Offset(stroke, mid), Offset(size.width - stroke, mid), stroke, StrokeCap.Round)
            }
            Spacer(Modifier.height(PhantomSpace.s3))
            Text(
                text = stringResource(R.string.profile_add_label),
                style = PhantomText.headline,
                color = c.text2,
            )
        }
    }
}

/** Рядки цього екрана лежать у ресурсах капсом («ОБЕРИ ПРОФІЛЬ»); мова форми — речення. */
private fun String.sentence(): String =
    lowercase().replaceFirstChar { it.titlecase() }
