# PHANTOM Companion — ProGuard / R8 rules
# ─────────────────────────────────────────────────────────────────────────────
# Generated for: AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.11.00
# Target SDK 34 · minSdk 29
#
# Philosophy: keep reflection-heavy libraries whole; let R8 shrink everything
# else aggressively.  Each block is annotated with the dependency it covers.
# ─────────────────────────────────────────────────────────────────────────────

# ── 1. Kotlin ────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
# Доки нічого не перейменовувалось, стектрейс із поля читався сам собою. Тепер
# перейменовується майже все, і без цих двох атрибутів звіт про падіння — сміття.
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic <methods>;
    @kotlin.jvm.JvmField <fields>;
}
# Kotlin serialization — all @Serializable classes need their companion object
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# ── 2. Jetpack Compose ───────────────────────────────────────────────────────
# Тут стояло `-keep class androidx.compose.** { *; }`. Воно тримало живими всі
# 12 144 класи material-icons-extended — 10,2 МБ dex — заради 175 іконок, які
# застосунок справді малює. Поруч стояв keepclassmembers на @Composable, який
# лишав кожну невикористану композицію в кожному живому класі.
# Кожна бібліотека Compose везе власний consumer proguard.txt у своєму .aar;
# іконки — це звичайні властивості ImageVector, до них ніхто не ходить за іменем.
# Додавати сюди нічого не треба.

# ── 3. AndroidX / Lifecycle / ViewModel ─────────────────────────────────────
# lifecycle і work теж мають власні consumer-правила в .aar. Лишається те, що
# стосується НАШИХ класів: воркер створюється за іменем, узятим із бази WorkManager.
# WorkManager workers are instantiated by class name via WorkerFactory
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── 4. Room ──────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── 5. SQLCipher ─────────────────────────────────────────────────────────────
# Нативний бік шукає класи за іменем `net/zetetic/database/sqlcipher/*`.
# ДО цього тут стояло `-keep class net.sqlcipher.**` — пакет, якого в збірці
# немає з переїзду на net.zetetic:sqlcipher-android. Правило не тримало ЖОДНОГО
# класу і ніхто цього не помітив, бо Room посилається на класи прямо. Саме такий
# провал ловить verifyFieldReleaseSurvivors у app/build.gradle.kts.
-keep class net.zetetic.database.** { *; }

# ── 6. Ktor ──────────────────────────────────────────────────────────────────
# ktor і okhttp самі везуть consumer-правила; блокет-keep лише лишав мертве.
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── 7. BouncyCastle ──────────────────────────────────────────────────────────
# Рефлексії немає: JCE-провайдер не реєструється ніде (жодного addProvider,
# жодного getInstance(..,"BC")), а крипто береться прямими посиланнями на легкий
# API — Ed25519Signer, X25519Agreement, HKDFBytesGenerator, Argon2BytesGenerator,
# SHA256Digest (core-net/crypto/*, core-license/SignedDocument.kt). Компілятор
# уже гарантує, що вони на місці, тож R8 дійде до них сам; блокет-keep натомість
# тягнув і весь постквантовий розділ.
-dontwarn org.bouncycastle.**

# ── 8. ONNX Runtime (Whisper / StyleTTS2) ───────────────────────────────────
# OrtEnvironment and related classes load native via reflection
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── 9. Vosk ──────────────────────────────────────────────────────────────────
# Vosk не викликає libvosk сам — це робить JNA, і робить рефлексією: підкласи
# PointerType вона будує за типом, а власні нутрощі шукає за іменем. Без правила
# на com.sun.jna слух ламається НЕ на старті, а на першому натисканні мікрофона,
# коли модель уже завантажена, — тобто там, куди димова перевірка не дивиться.
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**


# ── 11. ML Kit ───────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ── 12. ZXing (QR / Barcode) ─────────────────────────────────────────────────
# MultiFormatReader і BarcodeFormat названі в коді прямо (QrDecoder, InviteQr),
# тож R8 дійде сам. Рефлексії в zxing-core немає.
-dontwarn com.google.zxing.**

# ── 13. MapLibre ─────────────────────────────────────────────────────────────
-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**

# ── 14. (звільнено) ──────────────────────────────────────────────────────────
# Тут жив -keep на com.google.ar.**. ARCore не імпортує жоден файл і не оголошений
# у жодному маніфесті — правило тримало в APK 233 мертві типи. Залежність знято
# з feature-stream/build.gradle.kts разом із записом у каталозі.

# ── 15. Filament + Lottie ────────────────────────────────────────────────────
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# ── 16. (звільнено) ──────────────────────────────────────────────────────────
# Правила на com.wireguard.** описували залежність, якої не оголошує жоден модуль.
# Та сама хвороба, що й net.sqlcipher: текст, який ніби щось захищає.

# ── 17. CameraX ──────────────────────────────────────────────────────────────
-dontwarn androidx.camera.**

# ── 18. Guava ────────────────────────────────────────────────────────────────
-dontwarn com.google.common.**

# ── 19. PHANTOM domain models ────────────────────────────────────────────────
# All data classes that cross the WS boundary via kotlinx.serialization
# need their field names preserved.
-keepclassmembers @kotlinx.serialization.Serializable class local.phantom.** {
    *;
}
# Room entities and DAOs
-keep @androidx.room.Entity class local.phantom.** { *; }
-keep @androidx.room.Dao interface local.phantom.** { *; }
# Accessibility + Notification + Telecom Services
-keep class local.phantom.companion.feature.godmode.** extends android.accessibilityservice.AccessibilityService { *; }
-keep class local.phantom.companion.feature.godmode.** extends android.service.notification.NotificationListenerService { *; }
-keep class local.phantom.companion.feature.ambient.** extends android.service.dreams.DreamService { *; }
# BootReceiver & background services that are referenced by class name in Manifest
-keep class local.phantom.companion.** extends android.content.BroadcastReceiver { *; }
-keep class local.phantom.companion.** extends android.app.Service { *; }

# ── 20. Miscellaneous / suppress known-safe warnings ─────────────────────────
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.instrument.**
-dontwarn kotlinx.coroutines.debug.**
# R8 complains about missing JDK internal refs in Kotlin stdlib on old targets
-dontwarn kotlin.Unit

# Supress warnings for code-generation and logging classes missing in Android Runtime.
# Перелічувати їх поштучно — біг по колу: кожна нова залежність приносить ще
# один клас із того самого пакета, і релізна збірка падає знову. Це все живе
# лише в процесорах анотацій і в APK не потрапляє за визначенням.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn org.slf4j.impl.**

# ── 21. Strip debug/verbose logging from release ─────────────────────────────
# The product's own promise is that the operator's data does not leave the
# device; a system-wide log buffer is off the device by any reasonable reading
# of that. R8 keeps android.util.Log calls unless told otherwise, so the debug
# and verbose levels go here. warn/error stay — they carry no content and are
# what makes a field failure triageable.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
