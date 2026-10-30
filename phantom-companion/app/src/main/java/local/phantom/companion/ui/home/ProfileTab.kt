package local.phantom.companion.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.border
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import java.io.File
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.kit.SheetFrame
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.withAccent
import local.phantom.companion.data.ProfileHerald
import local.phantom.companion.data.ProfilesMirror
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.data.SelfFace
import local.phantom.companion.navigation.Routes
import local.phantom.companion.ui.messenger.WebNav
import local.phantom.companion.core.design.R as DesignR

internal data class Me(
    val name: String,
    val photo: Bitmap?,
    val banner: List<BannerShot> = emptyList(),
    val video: File? = null,
    val videoMs: Long = 0L,
    val videoShared: Boolean = false,
    val statusText: String = "",
    val bio: String = "",
    val toneKey: String = "",
)

private val NoopUnit: () -> Unit = {}

/** Свій банер з диска: фото в порядку власника з їхнім фокусом. */
internal fun loadOwnBanner(herald: ProfileHerald): List<BannerShot> {
    val banner = runCatching { herald.ownBanner() }.getOrNull() ?: return emptyList()
    return banner.items.mapNotNull { item ->
        val bytes = herald.ownBannerFile(item.hash)?.let { f -> runCatching { f.readBytes() }.getOrNull() } ?: return@mapNotNull null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
        BannerShot(bytes, bmp, item.fx, item.fy)
    }
}

/** Єдиний свій профіль: сторінка людини, правиться на місці. Решта — за шестернею. */
@Composable
internal fun ProfileTab(navController: NavHostController) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val face = container.selfFace
    val herald = container.profileHerald
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val handle by container.directory.handle.collectAsStateWithLifecycle()
    val until by container.directory.untilSeconds.collectAsStateWithLifecycle()
    val liveHandle = remember(handle, until) {
        handle?.takeUnless { local.phantom.companion.data.PeerDirectoryStore.expired(until, System.currentTimeMillis()) }
    }
    val circlesMap by container.peers.memberships.collectAsStateWithLifecycle()
    val outbox by container.mesh.outbox.collectAsStateWithLifecycle()
    val meId by container.mesh.selfIdFlow.collectAsStateWithLifecycle()
    val profiles by container.profilesMirror.state.collectAsStateWithLifecycle()

    var version by remember { mutableIntStateOf(0) }
    val activeId = profiles.active?.id
    var shownFor by remember { mutableStateOf(activeId) }
    val loaded = produceState<Me?>(null, version, activeId) {
        // Банер іншого простору не лишається на екрані, поки вантажиться цей.
        if (shownFor != activeId) {
            shownFor = activeId
            value = null
        }
        value = withContext(Dispatchers.IO) {
            val active = runCatching { container.profileStore.getActive() }.getOrNull()
            face.adoptLegacyPhoto(active?.avatarUri) { encodePhoto(context, Uri.parse(it)) }
            val video = runCatching { herald.ownBannerVideo() }.getOrNull()
            Me(
                name = active?.displayName.orEmpty(),
                photo = runCatching { herald.ownPhotoFile()?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull(),
                banner = loadOwnBanner(herald),
                video = video,
                videoMs = video?.let(::videoMs) ?: 0L,
                videoShared = runCatching { herald.sharedBannerVideo() != null }.getOrDefault(false),
                statusText = herald.statusText,
                bio = herald.bio,
                toneKey = active?.id.orEmpty(),
            )
        }
    }
    val me = loaded.value ?: return
    var failed by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var nameSaveFailed by remember { mutableStateOf(false) }
    var aboutSaveFailed by remember { mutableStateOf(false) }
    var aboutEditing by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var bannerOpen by remember { mutableStateOf(false) }
    var bannerSaving by remember { mutableStateOf(false) }
    var bannerFailed by remember { mutableStateOf(false) }
    var giftsOpen by remember { mutableStateOf(false) }
    val gifts = remember(outbox, meId) { ProfileGifts.received(outbox, meId) }
    LaunchedEffect(naming) { if (naming) nameSaveFailed = false }

    fun go(route: String) = navController.navigate(route) { launchSingleTop = true }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { savePhoto(context, face, uri) }
            failed = !ok
            if (ok) version++
        }
    }
    val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val openBanner = { bannerFailed = false; bannerOpen = true }

    ProfileFaceBody(
        me = me,
        handle = liveHandle,
        activeProfile = profiles.active?.name,
        activeAccent = profiles.active?.accent,
        circles = ProfileFace.circles(circlesMap),
        highlights = remember(outbox, meId, version) {
            ProfileFace.highlights(
                outbox,
                meId,
                System.currentTimeMillis(),
                runCatching { container.moments.highlights(meId) }.getOrDefault(emptyList()),
                runCatching { container.moments.liveMoments(meId) }.getOrDefault(emptyList()),
            )
        },
        failed = failed,
        naming = naming,
        nameSaveFailed = nameSaveFailed,
        onPhoto = { photoPicker.launch(images) },
        onName = { naming = true },
        onNameDone = { draft ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { face.setName(draft) }.getOrNull() }
                if (saved != null) {
                    nameSaveFailed = false
                    naming = false
                    version++
                } else {
                    nameSaveFailed = true
                }
            }
        },
        onNameCancel = { nameSaveFailed = false; naming = false },
        openTab = { key -> WebNav.request(WebNav.Door.OpenTab(key)); container.homeTabRequest.value = HomeTab.CHATS },
        openMoment = { id -> WebNav.request(WebNav.Door.OpenMoment(id)); container.homeTabRequest.value = HomeTab.CHATS },
        composeMoment = { WebNav.request(WebNav.Door.ComposeMoment); container.homeTabRequest.value = HomeTab.CHATS },
        onAction = { action ->
            when (action) {
                ProfileAction.CODE -> showCode = true
                else -> action.route?.let { go(it) }
            }
        },
        onEdit = { editOpen = true },
        onBanner = openBanner,
        aboutEditing = aboutEditing,
        onAbout = { aboutEditing = true },
        onAboutDone = { st, b ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { face.setAbout(st, b) }.getOrDefault(false) }
                if (ok) {
                    aboutSaveFailed = false
                    aboutEditing = false
                    version++
                } else {
                    aboutSaveFailed = true
                }
            }
        },
        onAboutCancel = { aboutSaveFailed = false; aboutEditing = false },
        aboutSaveFailed = aboutSaveFailed,
        onHandle = { go(ProfileSection.PRIVACY.route) },
        gifts = gifts,
        onGifts = { giftsOpen = true },
        profiles = profiles.profiles,
        activeId = activeId,
        onProfiles = { WebNav.request(WebNav.Door.OpenProfiles); container.homeTabRequest.value = HomeTab.CHATS },
    )

    if (giftsOpen && gifts.isNotEmpty()) {
        val people = container.peers.peers.value
        GiftsSheet(gifts = gifts, nameOf = { id -> people.firstOrNull { it.id == id }?.name }, onClose = { giftsOpen = false })
    }

    if (editOpen) {
        ProfileEditSheet(
            hasPhoto = me.photo != null,
            hasBanner = me.banner.isNotEmpty(),
            onPick = { row ->
                editOpen = false
                when (row) {
                    EditRow.PHOTO -> photoPicker.launch(images)
                    EditRow.BANNER -> openBanner()
                    EditRow.NAME -> naming = true
                    EditRow.ABOUT -> aboutEditing = true
                    EditRow.CLEAR_PHOTO -> scope.launch {
                        withContext(Dispatchers.IO) { runCatching { face.clearPhoto() } }
                        version++
                    }
                    EditRow.CLEAR_BANNER -> scope.launch {
                        withContext(Dispatchers.IO) { runCatching { herald.publishBanner(null) } }
                        version++
                    }
                }
            },
            onClose = { editOpen = false },
        )
    }
    if (bannerOpen) {
        BannerEditor(
            initial = me.banner,
            initialVideo = me.video?.let { v -> me.banner.firstOrNull()?.let { PickedVideo(v, it, me.videoMs) } },
            saving = bannerSaving,
            failed = bannerFailed,
            onDismiss = { bannerOpen = false },
            onSave = { shots, clip ->
                bannerSaving = true
                scope.launch {
                    val photos = (clip?.let { listOf(it.still) } ?: shots).map { ProfileHerald.BannerPhoto(it.bytes, it.fx, it.fy) }
                    val ok = withContext(Dispatchers.IO) {
                        val done = runCatching { herald.publishBanner(photos, clip?.file) != null }.getOrDefault(false)
                        if (clip != null && clip.file.parentFile == context.cacheDir) clip.file.delete()
                        done
                    }
                    bannerSaving = false
                    bannerFailed = !ok
                    if (ok) {
                        bannerOpen = false
                        version++
                    }
                }
            },
        )
    }
    if (showCode) {
        MyCodeDialog(
            onDismiss = { showCode = false },
            onScan = {
                showCode = false
                go(Routes.Scan.path)
            },
        )
    }
}


/** Сторінка без стану: її ж малює вкладка і знімає тест кадрів. */
@Composable
internal fun ProfileFaceBody(
    me: Me,
    handle: String?,
    activeProfile: String? = null,
    activeAccent: String? = null,
    circles: List<ProfileFace.Circle>,
    highlights: List<ProfileFace.Highlight>,
    failed: Boolean,
    naming: Boolean,
    nameSaveFailed: Boolean = false,
    onPhoto: () -> Unit,
    onName: () -> Unit,
    onNameDone: (String) -> Unit,
    onNameCancel: () -> Unit,
    onAction: (ProfileAction) -> Unit,
    openTab: (String) -> Unit,
    openMoment: (String) -> Unit = {},
    composeMoment: () -> Unit,
    onEdit: () -> Unit = NoopUnit,
    onBanner: () -> Unit = NoopUnit,
    aboutEditing: Boolean = false,
    aboutSaveFailed: Boolean = false,
    onAbout: () -> Unit = NoopUnit,
    onAboutDone: (String, String) -> Unit = { _, _ -> },
    onAboutCancel: () -> Unit = NoopUnit,
    onHandle: (() -> Unit)? = null,
    gifts: List<ProfileGifts.Got> = emptyList(),
    onGifts: () -> Unit = NoopUnit,
    profiles: List<ProfilesMirror.Profile> = emptyList(),
    activeId: String? = null,
    onProfiles: () -> Unit = NoopUnit,
) {
    val c = LocalPhantomColors.current
    val hasBanner = me.banner.isNotEmpty()
    val bannerState = remember { MutableTransitionState(hasBanner) }
    bannerState.targetState = hasBanner
    val bannerShown = hasBanner || !bannerState.isIdle
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AnimatedVisibility(visibleState = bannerState, enter = PhantomMotion.expand, exit = PhantomMotion.dismiss) {
            OwnBanner(shots = me.banner, video = me.video, videoMs = me.videoMs, onOpen = onBanner, shared = me.videoShared) {
                BannerTopActions(onAction, Modifier.align(Alignment.TopEnd))
            }
        }
        if (!bannerShown) YouTopBar(onAction)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PhantomSpace.s3, end = PhantomSpace.gutterPhone)
                .then(if (bannerShown) Modifier.liftedBy(BannerAvatarLift) else Modifier.padding(top = PhantomSpace.s2)),
            verticalAlignment = if (bannerShown) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            YouAvatar(me = me, onPhoto = onPhoto, modifier = Modifier)
            Spacer(Modifier.weight(1f))
            ProfileLabel(active = activeProfile, accent = activeAccent)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.gutterPhone).padding(top = PhantomSpace.s2),
        ) {
            if (naming) {
                NameEditor(me.name, saveFailed = nameSaveFailed, onDone = { if (it.isBlank()) onNameCancel() else onNameDone(it) }, onCancel = onNameCancel)
            } else {
                Text(
                    text = me.name.ifBlank { stringResource(R.string.profile_name_missing) },
                    style = PhantomText.display,
                    color = if (me.name.isBlank()) c.text2 else c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .testTag("you-name")
                        .clip(PhantomRadii.SmallShape)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.profile_name_edit), onClick = onName),
                )
            }
            val sub = listOfNotNull(handle?.let { h -> "@$h" }, me.statusText.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = PhantomText.subhead,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .testTag("you-sub")
                        .padding(top = PhantomSpace.s0_5)
                        .then(if (onHandle != null) Modifier.clip(PhantomRadii.SmallShape).clickable(role = Role.Button, onClick = onHandle) else Modifier),
                )
            }
            if (me.bio.isNotBlank() && !aboutEditing) {
                Text(
                    text = me.bio,
                    style = PhantomText.callout,
                    color = c.text2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("you-about").padding(top = PhantomSpace.s1),
                )
            }
            if (aboutEditing) AboutEditor(me = me, saveFailed = aboutSaveFailed, onDone = onAboutDone, onCancel = onAboutCancel)
            if (failed) {
                Text(stringResource(R.string.profile_photo_failed), style = PhantomText.footnote, color = c.warning)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = PhantomSpace.s4),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                PageAction(R.string.profile_edit, DesignR.drawable.lc_pencil, primary = true, tag = "me-edit", onClick = onEdit, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(PhantomSpace.s6))
            MomentsCard(highlights = highlights, onAdd = composeMoment, onOpen = { openMoment(it.id) })
            Spacer(Modifier.height(PhantomSpace.s6))
            if (gifts.isNotEmpty()) {
                GiftsCard(gifts = gifts, onOpen = onGifts)
                Spacer(Modifier.height(PhantomSpace.s6))
            }
            CirclesCard(circles = circles, openTab = openTab)
            Spacer(Modifier.height(PhantomSpace.s6))
            ProfilesCard(profiles = profiles, activeId = activeId, onOpen = onProfiles)
            Spacer(Modifier.height(PhantomSpace.s6))
        }
    }
}

/** Рядок під банером: тягнеться вгору на [lift], а місця займає менше на стільки ж. */
private fun Modifier.liftedBy(lift: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val l = lift.roundToPx().coerceAtMost(p.height)
    layout(p.width, p.height - l) { p.place(0, -l) }
}

/** Дія сторінки: бурштинова лише одна, друга — тонова. */
@Composable
private fun PageAction(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    primary: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val ink = if (primary) c.onAccent else c.text
    Row(
        modifier = modifier
            .testTag(tag)
            .height(PhantomSize.buttonL)
            .clip(CircleShape)
            .background(if (primary) c.accent else c.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = ink, modifier = Modifier.size(PhantomSize.iconM))
        Text(
            text = stringResource(label),
            style = PhantomText.subhead,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = PhantomSpace.s2),
        )
    }
}

/** QR і шестерня поверх банера: 40 dp на scrim, дотик — 48. */
@Composable
private fun BannerTopActions(onAction: (ProfileAction) -> Unit, modifier: Modifier) {
    val c = LocalPhantomColors.current
    Row(modifier = modifier.testTag("you-banner-actions").padding(PhantomSpace.s1)) {
        ProfileAction.entries.forEach { action ->
            Box(
                modifier = Modifier
                    .size(PhantomSpace.touchMinAndroid)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(PhantomSize.buttonM).clip(CircleShape).background(c.scrim), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(action.icon),
                        contentDescription = stringResource(action.label),
                        tint = c.onAct,
                        modifier = Modifier.size(PhantomSize.iconM),
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTopBar(onAction: (ProfileAction) -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .testTag("you-topbar")
            .fillMaxWidth()
            .height(PhantomSize.topbarPhone)
            .padding(start = PhantomSpace.gutterPhone, end = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_tab_profile),
            style = PhantomText.title1,
            color = c.text,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        ProfileAction.entries.forEach { action ->
            Box(
                modifier = Modifier
                    .size(PhantomSpace.touchMinAndroid)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(action.icon),
                    contentDescription = stringResource(action.label),
                    tint = c.text,
                    modifier = Modifier.size(PhantomSize.iconL),
                )
            }
        }
    }
}

@Composable
private fun YouAvatar(me: Me, onPhoto: () -> Unit, modifier: Modifier) {
    val c = LocalPhantomColors.current
    val changePhoto = stringResource(R.string.profile_photo_change)
    Box(
        modifier = modifier
            .size(AvatarOuter)
            .testTag("you-avatar")
            .clip(CircleShape)
            .background(c.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PhotoSize)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onPhoto)
                .semantics { contentDescription = changePhoto },
        ) {
            Avatar(name = me.name, photo = me.photo, size = PhotoSize, toneKey = me.toneKey)
        }
    }
}

internal enum class ProfileAction(@StringRes val label: Int, @DrawableRes val icon: Int) {
    CODE(R.string.profile_action_code, DesignR.drawable.lc_qr),
    SETTINGS(R.string.profile_settings, DesignR.drawable.lc_settings),
    ;

    val route: String?
        get() = when (this) {
            CODE -> null
            SETTINGS -> ProfileSection.SETTINGS.route
        }
}

private val PhotoSize = PhantomSize.avatarXl
private val CutOut = 2.dp
private val AvatarOuter = PhotoSize + CutOut * 2
private val HighlightSize = 64.dp
private val CircleTileWidth = 104.dp

/**
 * Бюджет імені в коді запрошення (CAP_QR_NAME = 32, див. [PeerName.CAP_QR_NAME]).
 * [PeerName.MAX] (64) каже, скільки ми ПРИЙМАЄМО від чужого пристрою; це — скільки
 * друкуємо самі, і питання фізичне. Заміряно на зліпку PeerInviteQrTest: звичайне
 * імʼя дає версію 10, 32 кириличні літери — версію 12, 64 — версію 14 (73×73),
 * а по той бік екрана стоїть камера сусіднього телефона. Обмежуємо не мовчки:
 * лічильник видно до того, як людина впреться.
 */
@Composable
private fun NameEditor(initial: String, saveFailed: Boolean = false, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val c = LocalPhantomColors.current
    var draft by remember { mutableStateOf(initial) }
    var refused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onCancel)
    val label = stringResource(R.string.profile_name_edit)
    val ok = draft.isNotBlank()
    BasicTextField(
        value = draft,
        onValueChange = {
            val fit = PeerName.fit(it, CAP_QR_NAME)
            refused = fit.refused
            draft = fit.text
        },
        singleLine = true,
        textStyle = PhantomText.display.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (ok) onDone(draft) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = label }
            .padding(vertical = PhantomSpace.s1),
    )
    Box(Modifier.width(160.dp).height(1.dp).background(if (refused) c.danger else c.accentLine))
    Text(
        text = stringResource(R.string.profile_name_budget, PeerName.length(draft), CAP_QR_NAME),
        style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
        color = if (refused) c.danger else c.text2,
        modifier = Modifier.padding(top = PhantomSpace.s1),
    )
    if (refused) {
        Text(stringResource(R.string.profile_name_too_long), style = PhantomText.footnote, color = c.danger)
    }
    if (saveFailed) {
        Text(stringResource(R.string.profile_name_save_failed), style = PhantomText.footnote, color = c.danger)
    }
    Text(
        text = stringResource(R.string.profile_name_save),
        style = PhantomText.headline,
        color = if (ok) c.accentText else c.text3,
        modifier = Modifier
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clip(PhantomRadii.SmallShape)
            .clickable(enabled = ok, role = Role.Button) { onDone(draft) }
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
    )
}

@Composable
private fun SectionTitle(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = PhantomText.title2,
        color = LocalPhantomColors.current.text,
        modifier = Modifier.padding(bottom = PhantomSpace.s3),
    )
}

@Composable
private fun MomentsCard(
    highlights: List<ProfileFace.Highlight>,
    onAdd: () -> Unit,
    onOpen: (ProfileFace.Highlight) -> Unit = {},
) {
    Column(modifier = Modifier.testTag("you-moments").fillMaxWidth()) {
        SectionTitle(R.string.profile_moments)
        Highlights(highlights, onAdd, onOpen)
    }
}

@Composable
private fun CirclesCard(
    circles: List<ProfileFace.Circle>,
    openTab: (String) -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(modifier = Modifier.testTag("you-circles").fillMaxWidth()) {
        SectionTitle(R.string.profile_circles)
        if (circles.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_circles_empty),
                style = PhantomText.callout,
                color = c.text2,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                circles.forEach { CircleTile(circle = it, openTab = openTab) }
            }
        }
    }
}

@Composable
private fun ProfilesCard(
    profiles: List<ProfilesMirror.Profile>,
    activeId: String?,
    onOpen: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val ordered = profiles.sortedByDescending { it.id == activeId }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(R.string.profile_profiles)
        Row(
            modifier = Modifier
                .testTag("you-profiles")
                .fillMaxWidth()
                .heightIn(min = PhantomSize.listrowCompact)
                .clip(PhantomRadii.MediumShape)
                .background(c.raised)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1)) {
                if (ordered.isEmpty()) {
                    Text(stringResource(R.string.profile_new), style = PhantomText.body, color = c.text)
                }
                ordered.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.withAccent(p.accent).accent))
                        Text(
                            text = p.name.ifBlank { stringResource(R.string.profile_profiles) },
                            style = PhantomText.body,
                            color = if (p.id == activeId) c.text else c.text2,
                            fontWeight = if (p.id == activeId) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(painterResource(DesignR.drawable.lc_chevron_right), null, tint = c.text3, modifier = Modifier.size(PhantomSize.iconM))
        }
    }
}

@Composable
private fun CircleTile(
    circle: ProfileFace.Circle,
    openTab: (String) -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .testTag("you-circle-tile")
            .widthIn(min = CircleTileWidth)
            .clip(PhantomRadii.MediumShape)
            .background(c.raised)
            .then(if (circle.count > 0) Modifier.clickable(role = Role.Button) { openTab("c:" + circle.key) } else Modifier)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
    ) {
        Text(
            text = circle.count.toString(),
            style = PhantomText.title1.copy(fontFeatureSettings = "tnum"),
            color = if (circle.count > 0) c.text else c.text3,
        )
        Text(
            text = stringResource(circle.label),
            style = PhantomText.subhead,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Highlights(
    items: List<ProfileFace.Highlight>,
    onAdd: () -> Unit,
    onOpen: (ProfileFace.Highlight) -> Unit = {},
) {
    val c = LocalPhantomColors.current
    val label = stringResource(R.string.profile_moment)
    val addLabel = stringResource(R.string.profile_moment_add)
    val edgePx = with(LocalDensity.current) { HighlightSize.roundToPx() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .testTag("you-moment-add")
                .size(HighlightSize)
                .clip(PhantomRadii.MediumShape)
                .background(c.raised)
                .clickable(role = Role.Button, onClickLabel = addLabel, onClick = onAdd)
                .semantics { contentDescription = addLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ph_plus), null, tint = c.text, modifier = Modifier.size(PhantomSize.iconL))
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_moments_empty),
                style = PhantomText.callout,
                color = c.text2,
            )
        }
        // Момент без картинки не малюється порожньою плиткою з камерою: поки вантажиться — тиха плитка, не вдалося — плитки нема.
        val failed = remember(items) { mutableStateListOf<String>() }
        items.filter { it.id !in failed }.forEach { h ->
            val bmp = produceState<Bitmap?>(null, h.id) {
                val got = withContext(Dispatchers.IO) {
                    h.media?.let { m -> runCatching { PhantomApp.container().moments.image(m) }.getOrNull() }?.let { ProfileFace.decodeAt(it, edgePx) }
                        ?: h.thumb?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                }
                if (got == null) failed.add(h.id)
                value = got
            }.value
            Box(
                modifier = Modifier
                    .testTag("you-moment-tile")
                    .size(HighlightSize)
                    .clip(PhantomRadii.MediumShape)
                    .background(c.overlay)
                    .clickable(role = Role.Button, onClickLabel = label) { onOpen(h) },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun MyCodeDialog(onDismiss: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()
    val invite by container.mesh.myInvite.collectAsStateWithLifecycle()
    var codeFailed by remember { mutableStateOf(false) }
    suspend fun loadCode() = coroutineScope {
        codeFailed = false
        // refreshInvite блокує IO і не скасовується — чекаємо 8 с окремо від нього.
        val job = launch(Dispatchers.IO) { runCatching { container.mesh.refreshInvite() } }
        val done = withTimeoutOrNull(8_000L) { job.join() } != null
        if (!done || container.mesh.myInvite.value == null) codeFailed = true
    }
    LaunchedEffect(Unit) { if (container.mesh.myInvite.value == null) loadCode() }
    val inv = invite
    val qr = remember(inv) { inv?.let { runCatching { qrBitmap(PeerInvite.encode(it)) }.getOrNull() } }
    ProfileDialog(
        title = stringResource(R.string.profile_action_code),
        confirm = stringResource(R.string.profile_code_send),
        confirmEnabled = qr != null,
        onConfirm = {
            if (inv != null) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, PeerInvite.webLink(inv))
                context.startActivity(Intent.createChooser(send, null))
            }
        },
        onDismiss = onDismiss,
        secondary = stringResource(R.string.scan_action) to onScan,
    ) {
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.profile_action_code),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(PhantomRadii.MediumShape)
                    .background(Color.White)
                    .padding(PhantomSpace.s3),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(PhantomRadii.MediumShape)
                    .background(c.overlay),
                contentAlignment = Alignment.Center,
            ) {
                if (codeFailed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(PhantomSpace.s4),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_code_failed),
                            style = PhantomText.callout,
                            color = c.text2,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.delete_everything_retry),
                            style = PhantomText.headline,
                            color = c.accentText,
                            modifier = Modifier
                                .heightIn(min = PhantomSpace.touchMinAndroid)
                                .clip(PhantomRadii.SmallShape)
                                .clickable(role = Role.Button) { scope.launch { loadCode() } }
                                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                        )
                    }
                }
            }
        }
    }
}

private fun qrBitmap(text: String, side: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        side,
        side,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0),
    )
    val px = IntArray(side * side) { if (m[it % side, it / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
}

private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxSide) {
            val k = maxSide.toFloat() / big
            decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

private fun encodePhoto(context: Context, uri: Uri): Pair<ByteArray, ByteArray>? {
    val square = decode(context, uri, 1024)?.let(SelfFace::centerSquare) ?: return null
    val thumb = SelfFace.fit(PeerProfile.MAX_AVATAR_BYTES, SelfFace.THUMB_SIDES) { s, q -> SelfFace.jpeg(square, s, q) } ?: return null
    val photo = SelfFace.fit(PeerProfile.MAX_PHOTO_BYTES, SelfFace.PHOTO_SIDES) { s, q -> SelfFace.jpeg(square, s, q) } ?: return null
    return thumb to photo
}

internal fun savePhoto(context: Context, face: SelfFace, uri: Uri): Boolean =
    encodePhoto(context, uri)?.let { (thumb, photo) -> face.setPhoto(thumb, photo) } ?: false

@Composable
internal fun Avatar(name: String, photo: Bitmap?, size: Dp = PhantomSize.avatarXl, toneKey: String = "") {
    val c = LocalPhantomColors.current
    if (photo != null) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = stringResource(R.string.profile_photo),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    val tone = c.person[Math.floorMod(toneKey.ifBlank { name }.hashCode(), c.person.size)]
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tone.first),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "N" },
            style = PhantomText.display,
            color = tone.second,
        )
    }
}

@Composable
private fun AboutEditor(
    me: Me,
    saveFailed: Boolean = false,
    onDone: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalPhantomColors.current
    var statusDraft by remember(me.statusText) { mutableStateOf(me.statusText) }
    var bioDraft by remember(me.bio) { mutableStateOf(me.bio) }
    BackHandler(onBack = onCancel)
    Column(
        modifier = Modifier.testTag("you-about-editor").fillMaxWidth().padding(top = PhantomSpace.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileField(
            value = statusDraft,
            onValueChange = { statusDraft = it.take(PeerProfile.MAX_STATUS_TEXT_BYTES) },
            hint = stringResource(R.string.profile_status_hint),
        )
        Spacer(Modifier.height(PhantomSpace.s2))
        ProfileField(
            value = bioDraft,
            onValueChange = { bioDraft = it.take(PeerProfile.MAX_BIO_BYTES) },
            hint = stringResource(R.string.profile_about_edit),
        )
        if (saveFailed) {
            Text(stringResource(R.string.profile_name_save_failed), style = PhantomText.footnote, color = c.danger, modifier = Modifier.padding(top = PhantomSpace.s2))
        }
        // Дія читається на будь-якому банері: заповнена кнопка, а не акцентний текст на фото.
        Text(
            text = stringResource(R.string.profile_name_save),
            style = PhantomText.headline,
            color = c.onAccent,
            modifier = Modifier
                .testTag("you-about-save")
                .padding(top = PhantomSpace.s2)
                .heightIn(min = PhantomSpace.touchMinAndroid)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(role = Role.Button) { onDone(statusDraft, bioDraft) }
                .padding(horizontal = PhantomSpace.s6, vertical = PhantomSpace.s3),
        )
    }
}

@Composable
private fun ProfileLabel(active: String?, accent: String?) {
    val name = active?.takeIf { it.isNotBlank() } ?: return
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier.testTag("me-profile-label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.withAccent(accent).accent))
        Text(
            text = name,
            style = PhantomText.subhead,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

internal enum class EditRow(@StringRes val label: Int, @DrawableRes val icon: Int) {
    PHOTO(R.string.profile_photo_row, DesignR.drawable.lc_camera),
    BANNER(R.string.profile_banner, R.drawable.ph_image),
    NAME(R.string.profile_name_edit, DesignR.drawable.lc_pencil),
    ABOUT(R.string.profile_about_row, DesignR.drawable.lc_pencil),
    CLEAR_PHOTO(R.string.profile_photo_remove, DesignR.drawable.lc_trash),
    CLEAR_BANNER(R.string.profile_banner_remove, DesignR.drawable.lc_trash),
}

/** «Редагувати»: усе, що правиться на сторінці, одним списком. */
@Composable
internal fun ProfileEditSheet(hasPhoto: Boolean, hasBanner: Boolean, onPick: (EditRow) -> Unit, onClose: () -> Unit) {
    val rows = EditRow.entries.filter {
        when (it) {
            EditRow.CLEAR_PHOTO -> hasPhoto
            EditRow.CLEAR_BANNER -> hasBanner
            else -> true
        }
    }
    SheetFrame(title = stringResource(R.string.profile_edit), closeLabel = stringResource(R.string.profile_banner_close), onClose = onClose) {
        rows.forEach { row -> SheetRow(stringResource(row.label), row.icon, tag = "edit-${row.name.lowercase()}") { onPick(row) } }
        Spacer(Modifier.height(PhantomSpace.s2))
    }
}

@Composable
private fun SheetRow(label: String, @DrawableRes icon: Int, tag: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid + PhantomSpace.s2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.gutterPhone),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Icon(painterResource(icon), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconL))
        Text(label, style = PhantomText.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
