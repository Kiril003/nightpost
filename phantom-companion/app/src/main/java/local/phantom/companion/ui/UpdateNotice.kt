package local.phantom.companion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import local.phantom.companion.BuildConfig
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.license.AvailableBuild
import local.phantom.companion.license.UpdateInstaller

/**
 * «Вийшла нова версія» — плаваюча картка над стрічкою.
 *
 * Не заслінка: під нею видно розмову, і застосунок працює далі, навіть якщо
 * картку ніколи не чіпати. «Пізніше» тримається до НАСТУПНОЇ збірки, а не до
 * наступного запуску, — інакше це було б не повідомлення, а нагадування.
 *
 * У польовій збірці файл забирає сам застосунок і віддає системному
 * встановлювачу — але лише після того, як sha256 завантаженого зійшлася з тією,
 * що приїхала в ПІДПИСАНІЙ політиці. Поки не зійшлася, файл не існує для решти
 * системи. У store-збірці дозволу на встановлення немає взагалі, тож там кнопка
 * веде в кабінет, як і раніше.
 *
 * 04.10: вигляд переведено на мову форми 1.2 (піднята картка, ролі PhantomText,
 * бурштинова головна дія), кожен символ файла лишився. Хост цієї картки
 * (`topOverlay = { UpdateNotice() }`) пішов зі старою стрічкою 02.10 — це
 * єдиний інтерфейс польового самооновлення зі звіркою sha256, і де його
 * поселити знову, вирішує штаб, а не зміна вигляду.
 */
@Composable
fun UpdateNotice(modifier: Modifier = Modifier) {
    val service = remember { PhantomApp.container().license }
    val offer by service.update.collectAsStateWithLifecycle()

    offer?.let { build ->
        UpdateCard(build = build, onLater = service::dismissUpdate, modifier = modifier)
    }
}

@Composable
private fun UpdateCard(
    build: AvailableBuild,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val service = remember { PhantomApp.container().license }
    val installer = remember { service.installer }
    val stage by installer.stage.collectAsStateWithLifecycle()
    val broken = stage is UpdateInstaller.Stage.Mismatch || stage is UpdateInstaller.Stage.WrongApp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.raised)
            .padding(PhantomSpace.s4),
    ) {
        Text(
            text = if (broken) {
                stringResource(R.string.update_mismatch_headline)
            } else {
                stringResource(R.string.update_headline, build.version)
            },
            style = PhantomText.headline,
            color = if (broken) c.danger else c.text,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(
            text = stageLine(build, stage, service.canInstallUpdates),
            style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
            color = c.text2,
        )
        if (stage is UpdateInstaller.Stage.Fetching) {
            Spacer(modifier = Modifier.height(PhantomSpace.s3))
            ProgressRule(
                fraction = (stage as UpdateInstaller.Stage.Fetching).let {
                    if (it.total > 0) it.received.toFloat() / it.total else 0f
                },
            )
        }
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        Actions(
            build = build,
            stage = stage,
            installer = installer,
            canInstall = service.canInstallUpdates,
            onLater = onLater,
            context = context,
        )
    }
}

@Composable
private fun Actions(
    build: AvailableBuild,
    stage: UpdateInstaller.Stage,
    installer: UpdateInstaller,
    canInstall: Boolean,
    onLater: () -> Unit,
    context: Context,
) {
    val c = LocalPhantomColors.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            // Магазинна збірка не має дозволу встановлювати — обіцяти тут
            // «Завантажити» означало б вести в глухий кут.
            !canInstall -> Primary(stringResource(R.string.update_open)) {
                openBuildPage(context, build.url)
            }

            stage is UpdateInstaller.Stage.Ready -> Primary(stringResource(R.string.update_install)) {
                openInstaller(context, installer, stage.file)
            }

            stage is UpdateInstaller.Stage.Fetching ->
                Primary(stringResource(R.string.update_pause), installer::pause)

            // Кнопки «спробувати ще» тут немає навмисно: її наявність і була б
            // порадою повторити, а повтор принесе ті самі байти.
            stage is UpdateInstaller.Stage.Mismatch ||
                stage is UpdateInstaller.Stage.WrongApp ->
                Primary(stringResource(R.string.update_open)) {
                    openBuildPage(context, build.url)
                }

            else -> Primary(
                stringResource(
                    R.string.update_fetch,
                    Formatter.formatShortFileSize(context, build.sizeBytes),
                ),
            ) { installer.start(build) }
        }
        Spacer(modifier = Modifier.width(PhantomSpace.s2))
        if (stage !is UpdateInstaller.Stage.Fetching) {
            Box(
                modifier = Modifier
                    .heightIn(min = PhantomSize.buttonM)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onLater)
                    .padding(horizontal = PhantomSpace.s4),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(R.string.update_later), style = PhantomText.subhead, color = c.text2)
            }
        }
    }
    if (stage is UpdateInstaller.Stage.Ready || stage is UpdateInstaller.Stage.Mismatch) {
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(
            text = stringResource(R.string.update_unknown_sources),
            style = PhantomText.footnote,
            color = c.text3,
        )
    }
}

/** Головна дія картки — бурштинова заливка, як головні дії решти застосунку. */
@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonM)
            .clip(CircleShape)
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.subhead, fontWeight = FontWeight.SemiBold, color = c.onAccent)
    }
}

/**
 * Смуга — це і є відсоток, тому цифри відсотка ніде немає: поруч стоять МБ,
 * тобто витрачений трафік, який числом сказати чесніше.
 */
@Composable
private fun ProgressRule(fraction: Float) {
    val c = LocalPhantomColors.current
    val width by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = PhantomMotion.meter,
        label = "update-progress",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(CircleShape)
            .background(c.line),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(width)
                .height(2.dp)
                .clip(CircleShape)
                .background(c.accentLine),
        )
    }
}

@Composable
private fun stageLine(
    build: AvailableBuild,
    stage: UpdateInstaller.Stage,
    canInstall: Boolean,
): String {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    return when (stage) {
        is UpdateInstaller.Stage.Fetching ->
            // Звірка 156 МБ триває секунди: окремий блимаючий стан був би шумом,
            // тож це хвіст того самого завантаження.
            if (stage.received >= stage.total) {
                stringResource(R.string.update_verifying)
            } else {
                stringResource(R.string.update_progress, size(stage.received), size(stage.total))
            }

        is UpdateInstaller.Stage.Ready -> stringResource(R.string.update_ready)
        is UpdateInstaller.Stage.Stalled -> if (stage.noSpace) {
            stringResource(R.string.update_no_space, size(build.sizeBytes))
        } else {
            stringResource(R.string.update_stalled)
        }

        UpdateInstaller.Stage.Mismatch -> stringResource(R.string.update_mismatch)
        is UpdateInstaller.Stage.WrongApp -> if (stage.samePackage) {
            stringResource(R.string.update_wrong_signer)
        } else {
            stringResource(R.string.update_wrong_package)
        }
        UpdateInstaller.Stage.Idle -> if (canInstall) {
            stringResource(R.string.update_detail, BuildConfig.VERSION_NAME)
        } else {
            stringResource(
                R.string.update_detail_cabinet,
                BuildConfig.VERSION_NAME,
                build.filename,
                size(build.sizeBytes),
            )
        }
    }
}

/**
 * Той самий зміст на екрані ліцензії — там, де його шукають свідомо, і де є
 * місце для ПОВНОЇ суми. Обрізок ловив би лише випадкову підміну, а звірятися
 * людина буде з тим, що покаже sha256sum на завантаженому файлі.
 */
@Composable
fun UpdateDetails(build: AvailableBuild, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.update_headline, build.version),
            style = PhantomText.headline,
            color = c.text,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(
            text = stringResource(
                R.string.update_file,
                build.filename,
                Formatter.formatShortFileSize(context, build.sizeBytes),
            ),
            style = PhantomText.footnote,
            color = c.text2,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        Text(
            text = stringResource(R.string.update_checksum),
            style = PhantomText.footnote,
            color = c.text3,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        // Повна сума — дані, не оздоба: рівні цифри (tnum), переноситься цілою.
        Text(
            text = build.sha256,
            style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
            color = c.text,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        Primary(stringResource(R.string.update_open)) { openBuildPage(context, build.url) }
    }
}

private fun openBuildPage(context: Context, url: String) {
    // Браузера може не бути взагалі (урізана прошивка) — тиша тут краща за
    // падіння застосунку від натискання на пропозицію оновитись.
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openInstaller(context: Context, installer: UpdateInstaller, file: File) {
    // null означає «архів не наш» — установник не отримує нічого, а стан уже
    // несе причину, і банер каже її словом.
    val intent = installer.installIntent(file) ?: return
    runCatching { context.startActivity(intent) }
}
