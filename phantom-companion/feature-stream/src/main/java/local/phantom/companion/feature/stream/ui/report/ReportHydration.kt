package local.phantom.companion.feature.stream.ui.report

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomRadii

/**
 * Прогресивна гідратація важких блоків.
 *
 * ## Проблема
 *
 * Розділ звіту може містити слід маршруту на 900 точок, теплову матрицю
 * 7×24 і таблицю на 40 рядків. Скласти це в тому ж кадрі, у якому рядок
 * зʼявився на екрані, — гарантований пропущений кадр і ривок скролу.
 * Веб-версія розвʼязує це `IntersectionObserver`; у Compose немає
 * спостерігача перетину, зате є краще: `LazyColumn` уже композує лише
 * видимі рядки. Бракує другого кроку — **не малювати важке в тому ж
 * кадрі, у якому рядок зʼявився**.
 *
 * ## Рішення
 *
 * [HydrateOnVisible] на першій композиції показує скелет **точної
 * зарезервованої висоти**, чекає один кадр (`withFrameNanos`) і лише тоді
 * підставляє справжній вміст. Скрол устигає віддати свій кадр, важкий
 * рендер лягає на наступний, висота не змінюється — ривка немає, білого
 * блимання немає (скелет — це поверхня теми, а не порожнеча).
 *
 * ## Чому стан живе вище за `LazyColumn`
 *
 * [ReportHydrationState] тримається на рівні поверхні, а не всередині
 * рядка. Рядок, який виїхав за екран, `LazyColumn` знищує; якби прапорець
 * «уже гідратовано» жив у ньому, кожне повернення нагору повторювало б
 * скелет — документ мерехтів би при кожному прокручуванні вгору-вниз.
 * Гідратація в межах сесії відбувається **один раз на блок**.
 */
@Stable
class ReportHydrationState {
    private val hydrated = mutableStateMapOf<String, Boolean>()

    fun isHydrated(key: String): Boolean = hydrated[key] == true

    fun markHydrated(key: String) {
        hydrated[key] = true
    }
}

@Composable
fun rememberReportHydration(docId: String): ReportHydrationState =
    remember(docId) { ReportHydrationState() }

/**
 * Чи просить оператор прибрати рух.
 *
 * Android не має вебового `prefers-reduced-motion`; найближчий чесний
 * еквівалент — системний масштаб тривалості анімацій. Коли він 0
 * (Налаштування → Для розробників, або режим економії у багатьох
 * прошивках, або служба доступності), система вже вимкнула анімації
 * скрізь, і поверхня звіту не має бути винятком.
 *
 * Читається один раз на композицію поверхні: перемикання цієї опції
 * посеред читання звіту — не той сценарій, заради якого варто тримати
 * підписку на `ContentObserver`.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Обгортка важкого блока: скелет → (кадр) → вміст.
 *
 * @param key ключ блока; має бути стабільним у межах документа
 * @param reservedHeightDp висота, яку тримає скелет — має відповідати
 *   реальній висоті вмісту, інакше сенс усієї конструкції втрачається
 * @param reducedMotion при `true` поява миттєва, без згасання
 */
@Composable
fun HydrateOnVisible(
    key: String,
    reservedHeightDp: Int,
    state: ReportHydrationState,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alreadyHydrated = state.isHydrated(key)

    // Уже матеріалізований блок не проходить через скелет удруге: при
    // поверненні скролом угору документ має бути на місці, а не збиратися
    // знову на очах.
    if (alreadyHydrated) {
        Box(modifier = modifier) { content() }
        return
    }

    var ready by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        // Один кадр форе: скрол віддає свій кадр, важкий рендер іде наступним.
        withFrameNanos { }
        ready = true
        state.markHydrated(key)
    }

    if (!ready) {
        ReportSkeleton(heightDp = reservedHeightDp, modifier = modifier)
        return
    }

    if (reducedMotion) {
        Box(modifier = modifier) { content() }
        return
    }

    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = MotionTokens.fadeQuart,
        label = "report-hydrate",
    )
    Box(modifier = modifier.alpha(appear)) { content() }
}

/**
 * Скелет блока — поверхня теми з мʼяким градієнтом, а не порожнє місце.
 * Тримає точну висоту, тому підміна вмісту не рухає жодного пікселя нижче.
 */
@Composable
fun ReportSkeleton(heightDp: Int, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.coerceAtLeast(24).dp)
            .clip(PhantomRadii.LargeShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        theme.glassPanel.copy(alpha = PhantomAlpha.Secondary),
                        theme.glassPanel.copy(alpha = 0.25f),
                    ),
                ),
            ),
    )
}
