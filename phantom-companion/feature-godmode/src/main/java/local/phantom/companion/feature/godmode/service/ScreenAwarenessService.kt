package local.phantom.companion.feature.godmode.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import local.phantom.companion.feature.godmode.engine.AccessibilityActionController
import local.phantom.companion.feature.godmode.engine.AccessibilityActionDispatcher
import local.phantom.companion.feature.godmode.engine.AppAction
import local.phantom.companion.feature.godmode.engine.AppActionAuthorizer
import local.phantom.companion.feature.godmode.engine.AppActionTraceBus
import local.phantom.companion.feature.godmode.engine.AuthorizationResult
import local.phantom.companion.feature.godmode.engine.GodModeCommand
import local.phantom.companion.feature.godmode.engine.GodModeEvent
import local.phantom.companion.feature.godmode.engine.GodModeEventBus
import local.phantom.companion.feature.godmode.engine.ProtectedWindows
import local.phantom.companion.feature.godmode.engine.ScrollAmount
import local.phantom.companion.feature.godmode.engine.ScrollDirection
import local.phantom.companion.feature.godmode.engine.ScreenContextExtractor
import local.phantom.companion.feature.godmode.engine.ScreenContextStore
import local.phantom.companion.feature.godmode.engine.ScreenNode
import local.phantom.companion.feature.godmode.engine.SelectedText
import local.phantom.companion.feature.godmode.engine.SensitiveScreens
import local.phantom.companion.feature.godmode.engine.SelectedTextRouter

/**
 * Phase 3 W4-2 — ScreenAwareness with structured context publish.
 *
 * On every debounced TYPE_WINDOW_STATE_CHANGED / _CONTENT_CHANGED:
 *  1. walk the active root, collecting (text, areaPx) [ScreenNode]s
 *  2. resolve appLabel via PackageManager (LRU cached)
 *  3. [ScreenContextExtractor.build] → [ScreenContext]
 *  4. publish to [ScreenContextStore] (AIRouter enrichedContext consumer)
 *  5. ALSO emit the legacy ScreenSnapshot / ScreenChanged events on
 *     [GodModeEventBus] so Phase 1.5-C ForegroundAppMonitor +
 *     TaskerEngine keep working unchanged.
 *
 * Debounce window is 500 ms — busy screens (Telegram chat scrolling)
 * fire dozens of CONTENT_CHANGED events per second, but the AI prompt
 * cadence is at most once per turn. The first event of a burst still
 * publishes immediately so a window switch is reflected without lag;
 * subsequent ones inside the window are coalesced into one final
 * pass when the window expires.
 *
 * Command dispatch (ClickText / TypeText / GlobalAction) is unchanged
 * from v1; W4-4 adds the AccessibilityActionDispatcher path on top.
 */
class ScreenAwarenessService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val labelCache = LruCache<String, String>(LABEL_CACHE_SIZE)

    @Volatile private var lastEmittedAtMs: Long = 0L
    @Volatile private var pending: Pair<String, String?>? = null
    private var debounceJob: Job? = null

    /**
     * R5.1 — timestamp of the last interaction attributable to the OPERATOR
     * (a TYPE_VIEW_CLICKED / TYPE_VIEW_TEXT_CHANGED that did NOT fall inside
     * the short echo window after one of our own performAction calls). The
     * action dispatcher treats any value newer than a sequence's start as
     * «the human took over» and suspends so PHANTOM never fights the
     * operator's hands.
     */
    @Volatile var lastOperatorTouchAtMs: Long = 0L
        private set

    /** Until this instant, view-click/text-change events are echoes of our
     *  own performAction and must NOT be counted as operator interaction. */
    @Volatile private var selfActionUntilMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        scope.launch {
            GodModeEventBus.commands.collect { command -> executeCommand(command) }
        }
        // W4-4 — register the live service as the action controller so
        // AccessibilityServiceActionRegistry.requestDispatch can find a
        // ready receiver. The static singleton wiring deliberately
        // mirrors GodModeEventBus / ScreenContextStore — the
        // AccessibilityService is instantiated by Android, not under
        // our DI, so a process-global is the only way the StreamView-
        // Model can dispatch into it.
        AccessibilityServiceActionRegistry.bind(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityServiceActionRegistry.unbind(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Active Window: $packageName / $className")
            GodModeEventBus.push(GodModeEvent.ScreenChanged(packageName, className))
        }

        if (ProtectedWindows.isOwn(packageName, applicationContext.packageName)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ScreenContextStore.clear()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            scheduleCapture(packageName, className.takeIf { it.isNotBlank() })
        }

        // W4-3 — selected-text capture. Picks up TYPE_VIEW_TEXT_SELECTION_CHANGED
        // (the most reliable signal — fires when the operator's
        // text-selection handles settle on a range) and a long-click
        // on a node carrying text (Chrome / WebView often dispatches
        // these on a long-press). Phantom's own package is filtered
        // out so the operator doesn't shadow their Stream typing
        // with the same text round-trip.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            captureSelection(event, packageName)
        }

        // R5.1 — operator-intervention detection. A view-click / text-change
        // OUTSIDE the echo window that follows our own performAction is the
        // human interacting. Phantom's own window is filtered so our Stream
        // typing doesn't self-trigger. Best-effort: the dispatcher only uses
        // this to pause-and-ask, never to act, so a rare false positive costs
        // one confirmation, not a wrong action.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            val now = System.currentTimeMillis()
            val isSelfEcho = now < selfActionUntilMs
            val isOwnApp = packageName == applicationContext.packageName
            if (!isSelfEcho && !isOwnApp) {
                lastOperatorTouchAtMs = now
            }
        }
    }

    private fun captureSelection(event: AccessibilityEvent, packageName: String) {
        if (packageName.isBlank()) return
        if (packageName == applicationContext.packageName) return
        val raw = event.text?.joinToString(separator = " ").orEmpty()
        if (raw.isBlank()) return
        val from = event.fromIndex.coerceAtLeast(0)
        val to = event.toIndex.coerceAtMost(raw.length)
        val selected = if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            to > from
        ) {
            raw.substring(from, to)
        } else {
            // Long-click without an explicit selection range: take the
            // full text (clipped further by the router's max-chars).
            raw
        }
        val trimmed = selected.trim()
        if (trimmed.isBlank()) return
        SelectedTextRouter.update(
            SelectedText(
                text = trimmed,
                sourcePackage = packageName,
                capturedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun scheduleCapture(packageName: String, rootClass: String?) {
        if (packageName.isBlank()) return
        pending = packageName to rootClass
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch {
            // First event of a burst — capture immediately so a window
            // change shows up in the next AI prompt without a 500 ms lag.
            captureNow()
            // Cool-down window. Subsequent events within update [pending]
            // (target package may change to a transient overlay) but do
            // not start another job.
            delay(DEBOUNCE_MS)
            // Final pass with whatever pending now points at — content
            // bursts (chat scrolling, lazy lists) end up captured once.
            captureNow()
        }
    }

    private fun captureNow() {
        val target = pending ?: return
        val (pkg, rootClassHint) = target
        val root = rootInActiveWindow ?: return
        // The debounced pass can land after the switch into our app: the root is the truth, not the pending name.
        if (ProtectedWindows.isOwn(root.packageName?.toString(), applicationContext.packageName)) return
        val now = System.currentTimeMillis()
        if (now - lastEmittedAtMs < MIN_INTERVAL_MS) return
        lastEmittedAtMs = now

        val nodes = mutableListOf<ScreenNode>()
        val textBuilder = StringBuilder()
        walk(root, nodes, textBuilder)

        val resolvedRootClass = rootClassHint ?: root.className?.toString()
        val appLabel = resolveAppLabel(pkg)
        val ctx = ScreenContextExtractor.build(
            packageName = pkg,
            appLabel = appLabel,
            rootClass = resolvedRootClass,
            nodes = nodes,
            nowMs = now,
        )
        ScreenContextStore.update(ctx)

        // Phase 3-A v1 legacy path — TaskerEngine + LifeLog still
        // consume the formatted text dump. Cap at 5000 chars per v1
        // contract.
        val snapshot = textBuilder.toString().trim()
        if (snapshot.isNotEmpty()) {
            GodModeEventBus.push(GodModeEvent.ScreenSnapshot(pkg, snapshot.take(LEGACY_DUMP_CAP)))
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        nodes: MutableList<ScreenNode>,
        builder: StringBuilder,
    ) {
        if (node == null) return
        val text = (node.text ?: node.contentDescription)?.toString().orEmpty()
        if (text.isNotBlank()) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val w = rect.width().toLong()
            val h = rect.height().toLong()
            val area = (w * h).coerceAtLeast(1L)
            nodes += ScreenNode(text = text, areaPx = area)
            builder.append("[${rect.left},${rect.top},${rect.width()},${rect.height()}] ")
                .append(text)
                .append(" | ")
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), nodes, builder)
        }
    }

    private fun resolveAppLabel(pkg: String): String? {
        if (pkg.isBlank()) return null
        labelCache.get(pkg)?.let { return it }
        val resolved = runCatching {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull()
        if (resolved != null) labelCache.put(pkg, resolved)
        return resolved
    }

    /**
     * W4-4 — execute a single [AppAction] via the live service. Returns
     * `true` when the action landed (node found + performAction
     * accepted, or a global action was dispatched). The dispatcher
     * orchestrates the sequence; this method is the per-step adapter.
     */
    internal fun executeAppAction(action: AppAction): Boolean {
        // R5.1 — open the echo window so the TYPE_VIEW_CLICKED /
        // TYPE_VIEW_TEXT_CHANGED this action is about to raise is
        // attributed to PHANTOM, not misread as operator intervention.
        // AP-1 — for gestures, extend the window past the gesture's own
        // duration so its synthesized touch events aren't misread either.
        val gestureMs = when (action) {
            is AppAction.Swipe -> action.durationMs
            is AppAction.LongPress -> action.durationMs
            is AppAction.Tap -> TAP_DURATION_MS
            else -> 0L
        }
        val root = rootInActiveWindow
        if (!SensitiveScreens.mayTouch(root?.packageName?.toString(), applicationContext.packageName, action)) return false
        if (!SensitiveScreens.mayTouch(packageUnder(action, root?.packageName?.toString()), applicationContext.packageName, action)) return false
        // PC orders (performOne) and the mission pilot arrive here without the dispatcher, so the authorizer is asked again.
        if (AccessibilityServiceActionRegistry.authorizer?.authorize(action, root?.packageName?.toString().orEmpty()) != AuthorizationResult.Allowed) return false
        selfActionUntilMs = System.currentTimeMillis() + SELF_ACTION_ECHO_MS + gestureMs
        return when (action) {
            is AppAction.Swipe -> dispatchStroke(
                path = android.graphics.Path().apply {
                    moveTo(action.startX.toFloat(), action.startY.toFloat())
                    lineTo(action.endX.toFloat(), action.endY.toFloat())
                },
                durationMs = action.durationMs,
            )
            is AppAction.Tap -> dispatchStroke(
                path = android.graphics.Path().apply {
                    moveTo(action.x.toFloat(), action.y.toFloat())
                    lineTo(action.x.toFloat(), action.y.toFloat())
                },
                durationMs = TAP_DURATION_MS,
            )
            is AppAction.LongPress -> dispatchStroke(
                path = android.graphics.Path().apply {
                    moveTo(action.x.toFloat(), action.y.toFloat())
                    lineTo(action.x.toFloat(), action.y.toFloat())
                },
                durationMs = action.durationMs,
            )
            is AppAction.Click -> {
                val node = findTargetNode(root, action.target) ?: return false
                val clickable = node.takeIf { it.isClickable }
                    ?: ascendToClickable(node)
                    ?: return false
                if (ProtectedWindows.isOwn(node.packageName?.toString(), applicationContext.packageName) ||
                    ProtectedWindows.isOwn(clickable.packageName?.toString(), applicationContext.packageName)
                ) return false
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            is AppAction.Scroll -> {
                if (root == null) return false
                val scrollable = findScrollable(root) ?: return false
                val verb = when (action.direction) {
                    ScrollDirection.UP, ScrollDirection.LEFT ->
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                scrollable.performAction(verb)
            }
            is AppAction.Type -> {
                val target = action.target
                val node = if (target != null) {
                    findTargetNode(root, target)
                } else {
                    root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } ?: return false
                if (!node.isEditable) return false
                if (ProtectedWindows.isOwn(node.packageName?.toString(), applicationContext.packageName)) return false
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    action.value,
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            AppAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            AppAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            AppAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            AppAction.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            AppAction.Screenshot -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            is AppAction.OpenApp -> launchByQuery(action.query)
            is AppAction.NavigateTo,
            is AppAction.SearchPlace,
            is AppAction.ShowOnMap,
            is AppAction.SaveOfflineRegion -> {
                // MAP-AI-3 verbs route through MapActionRouter inside the
                // dispatcher; they never reach the accessibility leg. The
                // explicit no-op keeps the sealed `when` exhaustive.
                false
            }
        }
    }

    /**
     * R5.1 — the model's eyes. Snapshots the foreground window as a
     * compact, interactive-node list so the model can observe → act →
     * observe. Unlike the ambient `[SCREEN:]` context (visible text only),
     * this exposes what is CLICKABLE / EDITABLE with resource-ids and
     * bounds, which is what the `accessibility` tool needs to target a
     * next tap. Capped at [SNAPSHOT_NODE_CAP] nodes to keep the tool
     * result inside the model's budget.
     */
    internal fun snapshotInteractiveTree(): UiSnapshot {
        val root = rootInActiveWindow
            ?: return UiSnapshot(packageName = null, appLabel = null, nodes = emptyList())
        val pkg = root.packageName?.toString()
        if (ProtectedWindows.isOwn(pkg, applicationContext.packageName)) {
            return UiSnapshot(packageName = pkg, appLabel = null, nodes = emptyList())
        }
        // Статут: банк — закритий для SEE за замовчуванням. Дерево навіть не
        // збираємо: нічого не видно — нічого не витече.
        if (SensitiveScreens.isBankingPackage(pkg)) {
            return UiSnapshot(
                packageName = pkg,
                appLabel = pkg?.let { resolveAppLabel(it) },
                nodes = emptyList(),
                banking = true,
            )
        }
        val collected = mutableListOf<UiSnapshotNode>()
        collectInteractive(root, collected)
        return UiSnapshot(
            packageName = pkg,
            appLabel = pkg?.let { resolveAppLabel(it) },
            nodes = collected.take(SNAPSHOT_NODE_CAP),
        )
    }

    private fun collectInteractive(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiSnapshotNode>,
    ) {
        if (node == null || out.size >= SNAPSHOT_NODE_CAP) return
        // Поле пароля: текст не читаємо НІКОЛИ, навіть якщо ОС його віддає.
        val password = node.isPassword
        val rawText = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        val text = if (password) "" else rawText
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        // Keep a node only if it carries something the model can act on or read.
        if ((clickable || editable || scrollable) || text.isNotBlank()) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            out += UiSnapshotNode(
                text = text.take(SNAPSHOT_TEXT_CAP),
                resourceId = node.viewIdResourceName,
                clickable = clickable,
                editable = editable,
                scrollable = scrollable,
                focused = node.isFocused,
                isPassword = password,
                centerX = (rect.left + rect.right) / 2,
                centerY = (rect.top + rect.bottom) / 2,
            )
        }
        for (i in 0 until node.childCount) collectInteractive(node.getChild(i), out)
    }

    /**
     * AP-1 — fire a single-stroke gesture and return whether the OS
     * ACCEPTED it into the dispatch queue (true) or refused (false —
     * e.g. another gesture in flight, or gestures not permitted). This
     * is the synchronous truth the accessibility-tool chain needs; the
     * tight game loop uses [performGestureAwait] to also know when the
     * stroke actually finished.
     */
    private fun dispatchStroke(path: android.graphics.Path, durationMs: Long): Boolean {
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0L, durationMs.coerceAtLeast(1L),
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    internal fun activeWindowPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    // A coordinate gesture goes to the topmost window under its first point: the shade, a heads-up or a dialog, not always the active window.
    private fun packageUnder(action: AppAction, activePackage: String?): String? {
        val (x, y) = when (action) {
            is AppAction.Tap -> action.x to action.y
            is AppAction.LongPress -> action.x to action.y
            is AppAction.Swipe -> action.startX to action.startY
            else -> return activePackage
        }
        val bounds = android.graphics.Rect()
        val hit = runCatching { windows }.getOrNull().orEmpty()
            .sortedByDescending { it.layer }
            .firstOrNull { w -> w.getBoundsInScreen(bounds); bounds.contains(x, y) }
        return hit?.root?.packageName?.toString()
    }

    /**
     * AP-1 — awaited gesture for the mission pilot loop. Suspends until
     * the OS reports the stroke completed or cancelled, so the caller
     * can pace the next move against real animation timing rather than
     * a guessed sleep. Returns false if the gesture was refused or
     * cancelled. The [action] must be a gesture kind (Swipe / Tap /
     * LongPress); anything else returns false.
     */
    internal suspend fun performGestureAwait(action: AppAction): Boolean {
        val root = rootInActiveWindow
        if (!SensitiveScreens.mayTouch(root?.packageName?.toString(), applicationContext.packageName, action)) return false
        if (!SensitiveScreens.mayTouch(packageUnder(action, root?.packageName?.toString()), applicationContext.packageName, action)) return false
        if (AccessibilityServiceActionRegistry.authorizer?.authorize(action, root?.packageName?.toString().orEmpty()) != AuthorizationResult.Allowed) return false
        val (path, durationMs) = when (action) {
            is AppAction.Swipe -> android.graphics.Path().apply {
                moveTo(action.startX.toFloat(), action.startY.toFloat())
                lineTo(action.endX.toFloat(), action.endY.toFloat())
            } to action.durationMs
            is AppAction.Tap -> android.graphics.Path().apply {
                moveTo(action.x.toFloat(), action.y.toFloat())
                lineTo(action.x.toFloat(), action.y.toFloat())
            } to TAP_DURATION_MS
            is AppAction.LongPress -> android.graphics.Path().apply {
                moveTo(action.x.toFloat(), action.y.toFloat())
                lineTo(action.x.toFloat(), action.y.toFloat())
            } to action.durationMs
            else -> return false
        }
        selfActionUntilMs = System.currentTimeMillis() + SELF_ACTION_ECHO_MS + durationMs
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0L, durationMs.coerceAtLeast(1L),
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = runCatching {
                dispatchGesture(gesture, callback, android.os.Handler(android.os.Looper.getMainLooper()))
            }.getOrDefault(false)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    /**
     * AP-1 — grab the current screen as an in-memory [android.graphics.Bitmap].
     * Uses [AccessibilityService.takeScreenshot] (API 30+); the bitmap
     * never touches disk / MediaStore. Null on any failure (unsupported
     * API, throttled — the OS rate-limits screenshots, or a hardware
     * buffer we couldn't wrap). The pixel BoardReader path consumes this
     * only when the a11y-tree path can't read the board.
     */
    internal suspend fun captureScreen(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        if (ProtectedWindows.isOwn(activeWindowPackage(), applicationContext.packageName)) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            runCatching {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = runCatching {
                                val hb = result.hardwareBuffer
                                val cs = result.colorSpace
                                val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, cs)
                                hb.close()
                                // Copy off the hardware buffer so the bitmap is
                                // CPU-readable (getPixel) and outlives the buffer.
                                bmp?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            executor.shutdown()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            executor.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure {
                executor.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun launchByQuery(query: String): Boolean {
        val pm = applicationContext.packageManager
        val target = resolvePackage(pm, query)?.takeUnless { ProtectedWindows.isOwn(it, applicationContext.packageName) }
        if (target == null) {
            toastDebug("не знайшов додаток: $query")
            return false
        }
        val intent = pm.getLaunchIntentForPackage(target)
            ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(target)
            }
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        // Try the service's own context first — it carries the
        // AccessibilityService token which Samsung tends to honour for
        // background-activity-launch. Fall back to applicationContext
        // if the service context refuses (e.g., during a tear-down).
        val ok = runCatching { this.startActivity(intent) }.isSuccess ||
            runCatching { applicationContext.startActivity(intent) }.isSuccess
        if (!ok) {
            toastDebug("не вдалось запустити: $target")
            android.util.Log.e("PhantomA11y", "launchByQuery failed for $target")
        }
        return ok
    }

    private fun toastDebug(text: String) {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                android.widget.Toast
                    .makeText(applicationContext, "Nightpost: $text", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun resolvePackage(
        pm: android.content.pm.PackageManager,
        query: String,
    ): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        if (q.contains('.')) return q
        local.phantom.companion.feature.godmode.engine.AppActionAliases.KNOWN[q]?.let { return it }
        val installed = runCatching { pm.getInstalledApplications(0) }
            .getOrDefault(emptyList())
        for (info in installed) {
            val label = runCatching { pm.getApplicationLabel(info).toString().lowercase() }
                .getOrDefault("")
            if (label == q || label.startsWith(q)) return info.packageName
        }
        return null
    }

    private fun findTargetNode(
        root: AccessibilityNodeInfo?,
        target: local.phantom.companion.feature.godmode.engine.ActionTarget,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        // Priority 1: text (substring on findAccessibilityNodeInfosByText
        // which is itself substring + case-insensitive on most OEM impls).
        target.text?.let { txt ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(txt) }
                .getOrDefault(emptyList())
            nodes.firstOrNull()?.let { return it }
        }
        // Priority 2: resource id.
        target.resourceId?.let { id ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrDefault(emptyList())
            nodes.firstOrNull()?.let { return it }
        }
        // Priority 3: bounds (closest centre).
        target.bounds?.let { b ->
            return findByBounds(root, b)
        }
        return null
    }

    private fun ascendToClickable(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = start
        var hops = 0
        while (cur != null && hops < ASCEND_HOP_LIMIT) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    private fun findByBounds(
        root: AccessibilityNodeInfo,
        wanted: local.phantom.companion.feature.godmode.engine.ActionBounds,
    ): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestDist = Long.MAX_VALUE
        val targetCx = (wanted.x + wanted.width / 2).toLong()
        val targetCy = (wanted.y + wanted.height / 2).toLong()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.getBoundsInScreen(rect)
            val cx = (rect.left + rect.right) / 2L
            val cy = (rect.top + rect.bottom) / 2L
            val d = Math.abs(cx - targetCx) + Math.abs(cy - targetCy)
            if (d < bestDist) {
                bestDist = d
                best = node
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }

        visit(root)
        return best
    }

    private fun executeCommand(command: GodModeCommand) {
        val root = rootInActiveWindow
        if (ProtectedWindows.isOwn(root?.packageName?.toString(), applicationContext.packageName)) return
        if (!SensitiveScreens.mayTouch(root?.packageName?.toString(), applicationContext.packageName, command)) return
        when (command) {
            is GodModeCommand.GlobalAction -> {
                performGlobalAction(command.action)
            }
            is GodModeCommand.ClickText -> {
                if (root == null) return
                val nodes = root.findAccessibilityNodeInfosByText(command.text)
                nodes.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            is GodModeCommand.TypeText -> {
                if (root == null) return
                val nodes = root.findAccessibilityNodeInfosByText(command.targetNodeText)
                val node = nodes.firstOrNull { it.isEditable }
                if (node != null) {
                    val arguments = Bundle()
                    arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        command.input,
                    )
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        ScreenContextStore.clear()
    }

    private companion object {
        const val TAG = "GodModeScreen"
        const val DEBOUNCE_MS: Long = 500L
        const val MIN_INTERVAL_MS: Long = 200L
        const val LEGACY_DUMP_CAP: Int = 5_000
        const val LABEL_CACHE_SIZE: Int = 64
        const val ASCEND_HOP_LIMIT: Int = 6
        /** How long a view-click/text-change after our own performAction is
         *  treated as an echo of PHANTOM's action, not operator input. */
        const val SELF_ACTION_ECHO_MS: Long = 1_200L
        const val SNAPSHOT_NODE_CAP: Int = 60
        const val SNAPSHOT_TEXT_CAP: Int = 80
        /** AP-1 — synthetic duration for a [AppAction.Tap] stroke. Short
         *  enough to read as a tap, non-zero so the OS accepts it. */
        const val TAP_DURATION_MS: Long = 40L
    }
}

/** R5.1 — one interactive node in a [UiSnapshot]. */
data class UiSnapshotNode(
    val text: String,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean = false,
    val isPassword: Boolean = false,
    val centerX: Int,
    val centerY: Int,
)

/** R5.1 — compact snapshot of the foreground window for the `screen.read` tool. */
data class UiSnapshot(
    val packageName: String?,
    val appLabel: String?,
    val nodes: List<UiSnapshotNode>,
    /** Застосунок — банк/гаманець: SEE закрито за замовчуванням, дерева немає. */
    val banking: Boolean = false,
) {
    /** Model-friendly rendering: one line per node, targeting hints inline. */
    fun render(): String {
        val who = appLabel ?: packageName ?: "невідомо"
        if (banking) {
            return "екран: $who — застосунок банку, читання екрана тут закрите за " +
                "замовчуванням. Якщо треба, власник відкриває SEE для цього застосунку сам."
        }
        if (nodes.isEmpty()) {
            return "екран: $who — інтерактивних елементів не видно"
        }
        val header = "екран: ${appLabel ?: "?"} (${packageName ?: "?"}), ${nodes.size} елемент(ів):"
        // Кожен рядок починається з [nN] — це nodeRef для phone.tap/phone.type.
        val body = nodes.mapIndexed { i, n ->
            val kinds = buildList {
                if (n.clickable) add("click")
                if (n.editable) add("edit")
                if (n.scrollable) add("scroll")
            }.joinToString(",").ifEmpty { "text" }
            val id = n.resourceId?.substringAfterLast('/')?.let { " #$it" } ?: ""
            val focus = if (n.focused) " ←фокус" else ""
            val label = when {
                n.isPassword -> SensitiveScreens.PASSWORD_MASK
                n.text.isBlank() -> "—"
                else -> n.text
            }
            "[n$i] «$label» [$kinds]$id @${n.centerX},${n.centerY}$focus"
        }.joinToString("\n")
        return "$header\n$body"
    }
}

/**
 * Phase 3 W4-4 — process-global registry that hands a live
 * [ScreenAwarenessService] reference to the StreamViewModel so it
 * can dispatch [AppAction] sequences without being constructor-
 * coupled to the AccessibilityService instance.
 *
 * The Android framework constructs the service when the operator
 * grants Accessibility access; the registry tracks the most recent
 * bound instance and serves it as an [AccessibilityActionController].
 *
 * `requestDispatch` is a fire-and-forget helper that does the right
 * thing when the service is unbound (operator hasn't granted
 * Accessibility yet, OR the OS revoked the binding) — it surfaces a
 * Suspended trace event with a clear reason instead of silently
 * dropping. The widget UI renders that as «дія заблокована · немає
 * Accessibility».
 */
object AccessibilityServiceActionRegistry {
    @Volatile
    private var current: ScreenAwarenessService? = null

    @Volatile
    var authorizer: AppActionAuthorizer? = null

    internal fun bind(service: ScreenAwarenessService) {
        current = service
    }

    internal fun unbind(service: ScreenAwarenessService) {
        if (current === service) current = null
    }

    private fun buildDispatcher(
        svc: ScreenAwarenessService,
        auth: AppActionAuthorizer,
        widgetId: String,
    ): AccessibilityActionDispatcher {
        val controller = object : AccessibilityActionController {
            override fun execute(action: AppAction): Boolean = svc.executeAppAction(action)
        }
        return AccessibilityActionDispatcher(
            authorizer = auth,
            controller = controller,
            currentPackage = { svc.activeWindowPackage() },
            emitProgress = { progress -> AppActionTraceBus.emit(widgetId, progress) },
            lastOperatorTouchAtMs = { svc.lastOperatorTouchAtMs },
        )
    }

    /**
     * Emits a [AppActionProgress.Suspended] for [widgetId] when no
     * service is bound or no authorizer has been wired by AppContainer.
     */
    fun requestDispatch(
        widgetId: String,
        actions: List<AppAction>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): kotlinx.coroutines.Job? {
        val svc = current
        val auth = authorizer
        if (svc == null || auth == null) {
            AppActionTraceBus.emit(
                widgetId = widgetId,
                progress = local.phantom.companion.feature.godmode.engine.AppActionProgress.Suspended(
                    reasonKey = "service-unbound",
                    displayText = "дія заблокована · надай Accessibility-доступ",
                    atStep = 0,
                    totalSteps = actions.size,
                ),
            )
            return null
        }
        val dispatcher = buildDispatcher(svc, auth, widgetId)
        return scope.launch { dispatcher.dispatch(actions) }
    }

    /**
     * R5.1 — awaited dispatch for the `accessibility` tool. Runs the whole
     * sequence once, streams [onStep], and RETURNS the honest outcome —
     * including the «service unbound» case that the fire-and-forget path
     * could only signal through a trace event nobody in the tool layer
     * awaited. [widgetId] still drives the AppActionTraceBus so the live
     * widget animates in lock-step.
     */
    suspend fun dispatchAwait(
        widgetId: String,
        actions: List<AppAction>,
        onStep: suspend (index: Int, total: Int, description: String) -> Unit = { _, _, _ -> },
    ): AccessibilityActionDispatcher.SequenceOutcome {
        val svc = current
        val auth = authorizer
        if (svc == null || auth == null) {
            AppActionTraceBus.emit(
                widgetId = widgetId,
                progress = local.phantom.companion.feature.godmode.engine.AppActionProgress.Suspended(
                    reasonKey = "service-unbound",
                    displayText = "дія заблокована · надай Accessibility-доступ",
                    atStep = 0,
                    totalSteps = actions.size,
                ),
            )
            return AccessibilityActionDispatcher.SequenceOutcome(
                completedSteps = 0,
                totalSteps = actions.size,
                stop = AccessibilityActionDispatcher.Stop("service-unbound", "Accessibility off"),
            )
        }
        return buildDispatcher(svc, auth, widgetId).dispatchDetailed(actions, onStep)
    }

    /**
     * R5.1 — foreground UI snapshot for the `screen.read` tool. Null when
     * the service is unbound (operator hasn't granted Accessibility).
     */
    fun snapshotUi(): UiSnapshot? = current?.snapshotInteractiveTree()

    /**
     * Симбіот: одна дія від тіла, вже пропущена крізь драбину [SymbioteOrders].
     * Гейт тут не наш (його тримає драбина), тож це прямий виклик службі.
     * Null-безпечно: `false`, коли служби немає (власник не дав Accessibility).
     */
    fun performOne(action: AppAction): Boolean = current?.executeAppAction(action) ?: false

    /**
     * AP-1 — awaited gesture for the mission pilot. Null-safe: returns
     * false when no service is bound. Callers MUST authorize the action
     * (via MissionAuthorizer) before invoking — the registry performs no
     * gate of its own here.
     */
    suspend fun performGestureAwait(action: AppAction): Boolean =
        current?.performGestureAwait(action) ?: false

    /** AP-1 — in-memory screenshot for the pixel BoardReader path. Null
     *  when unbound, unsupported, or throttled. */
    suspend fun captureScreen(): android.graphics.Bitmap? = current?.captureScreen()

    /** AP-1 — the current package as seen by the live a11y context. */
    fun currentPackage(): String =
        ScreenContextStore.current()?.packageName.orEmpty()

    /** AP-1 — timestamp of the last physical operator touch (for the
     *  mission engine's instant-pause guard). 0 when unbound. */
    fun lastOperatorTouchAtMs(): Long = current?.lastOperatorTouchAtMs ?: 0L
}
