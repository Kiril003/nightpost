# PHANTOM Companion v2 (Android, composite build)

Кишеневе тіло PHANTOM. Сканує QR з desktop-панелі, парується через
ECDH+HMAC, тримає WebSocket-канал до `phantom-os` і дзеркалить пульс /
голосовий PTT / стан системи.

> **Vision (єдине джерело-істина):** `../phantom-os/docs/MOBILE_ECOSYSTEM_VISION.md`.
> **Дорожня карта по фазах:** `companion-android/docs/ROADMAP.md`.
> **Візуальні референси (read-only архів PWA):** `companion-android/docs/design-refs/legacy-pwa-snapshot/`.
>
> IA скасовано: жодних 5 табів, жодних "мертвих апок". Stream + Orb +
> 6 станів + Glass-Glow візуал керують усім.

## Phase 0 (companion-v2-phase-0) — composite build + IA refactor

16-модульна composite-збірка (9 `core-*` + 4 `feature-*` + `wear` + `app`).
Колишня IA з 7 dead-app feature-модулями (`pulse/voice/map/comms/vault/
driver/launcher`) знесена відповідно до vision §1 — її замінили три
vision-aligned модулі: `:feature-stream` (Generative UI host),
`:feature-godmode` (Accessibility / Notification / TelecomManager),
`:feature-ambient` (floating Orb / AOD / hearables).

Візуальний рушій (`PhantomTheme`, `StateAccent`, `GlassCard`, `OrbView`,
`StatePill`, `VitalsRow`, `PttButton`) живе у `:core-design/` — спільна
design-system для всіх feature-модулів і `:wear`.

## Що готове

* Pair flow з QR (ZXing camera + manual JSON paste).
* X25519 ECDH + HKDF-SHA256 + HMAC-proof — байт-у-байт сумісно з
  `src/backend/security/pair_crypto.py`.
* Long-term Ed25519 device key для `/pair/refresh`.
* Device-JWT в `EncryptedSharedPreferences` (Keystore-wrapped).
* Persistent `PhantomLinkService` (foreground, type=dataSync) з
  exponential-backoff reconnect і channel `subscribe` фільтром.
* Foreground/background sensor batch endpoint (`/api/v1/sensors/mobile_batch`).
* `:core-design` design-system — GlassCard (RenderEffect blur API 31+,
  flat fallback), OrbView audio-reactive 220dp, StatePill, VitalsRow,
  PttButton; 4-рівневий glass-stack (Subtle/Panel/Card/Elevated).
* `StreamNav` post-pair surface — radial breathing bloom (8s),
  centred Orb з accent-halo, AwakeningCard з per-state UA-копірайтом,
  IncomingChannel placeholder для першого generative widget.
* Sunrise-warm + amber-night теми + per-state accent (FOCUS / SENTINEL /
  GHOST / DREAM / SHADOW / DIALOGUE) — керують color + motionScale +
  uiOpacity глобально через `LocalStateAccent`.

## Що зібрано

* `app/build/outputs/apk/debug/app-debug.apk` — 70 MB, signed v2 scheme,
  applicationId `local.phantom.companion.debug`, min API 29, target 34.

## Як перебудувати

```bash
cd companion-android/
./scripts/build_apk.sh          # debug APK
./scripts/build_apk.sh release  # release (unsigned, sign at deploy)
```

Скрипт виставляє `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` і кличе
`gradle assembleDebug`. На цьому Radxa-хості:

* `ANDROID_HOME=/home/radxa/android-sdk` (cmdline-tools + platform-34 +
  build-tools-34 інстальовані через sdkmanager).
* `gradle.properties` форсить
  `android.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2`,
  бо Google поставляє лише x86_64 ELF, тоді як Ubuntu Noble має aarch64
  через пакет `android-sdk-build-tools`.

## Як встановити

З десктопа із підключеним телефоном (USB debug ON):

```bash
adb install -r companion-android/app/build/outputs/apk/debug/app-debug.apk
```

Або просто перенести `app-debug.apk` на пристрій і відкрити (потрібен
дозвіл "встановлення з невідомих джерел").

## Як спарити

1. `phantom-os` запущений (`./start-phantom.sh`), ROOT-адмін відкриває
   `Settings → Pair`. Frontend кличе `POST /api/v1/pair/init` і малює QR.
2. Запускаєш Companion → дозволяєш камеру → наводиш на QR.
3. Companion обчислює ECDH/HMAC, кидає `POST /api/v1/pair/claim`,
   зберігає device-JWT і вмикає WS-канал.
4. На десктопі через WS виходить `pair/claimed` toast.

Якщо камера тимчасово не працює — кнопка "Вставити JSON QR" приймає
сирий JSON QR-payload (з `qr_svg_data_url` десктоп копіює сирий JSON
натиснувши на нього довгим тапом).

## Структура (post-IA-refactor)

```
companion-android/
├── settings.gradle.kts          # composite include: app + 8 core + 4 feature + wear
├── build.gradle.kts             # plugin-management glue (apply false)
├── gradle.properties            # aapt2 aarch64 override + heap/parallel/cache
├── gradle/libs.versions.toml    # AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.11
│
├── core-design/                 # ✅ PhantomTheme + GlassCard + OrbView + StatePill
│                                #   + VitalsRow + PttButton + StateAccent
├── core-net/                    # ✅ Ktor + pair crypto + Ed25519 + PairDiscoveryClient
│                                #   (LAN-first mDNS → relay fallback)
├── core-data/                   # ✅ Profile + ProfileStore + Vault (SQLCipher)
├── core-ai/                     # ✅ Gemma local + Gemini cloud AIRouter
├── core-voice/                  # ✅ whisper + Vosk STT + System/StyleTTS2 TTS
├── core-vision/                 # ✅ MediaPipe + ML Kit + ARCore (Phase 4)
├── core-context/                # ✅ ContextEngine + 6-state FSM + ContextFormatter
├── core-sensor/                 # ✅ MobileSensorAdapter (GPS/IMU/MicRMS)
│
├── feature-onboarding/          # ✅ Name + PIN + Profile + camera-pair (Phase 1-C)
├── feature-stream/              #    Generative UI host — StreamScreen + WidgetRenderer
│                                #    (Phase 1-E,F,G); замінює всі колишні таби
├── feature-godmode/             #    Accessibility + NotificationListener + Telecom
│                                #    AI-secretary + TaskerEngine (Phase 3)
├── feature-ambient/             #    Floating Orb + AOD + hearables (Phase 3-E,F,G)
│
├── wear/                        #    Wear OS (Phase 5-E)
│
├── app/                         # Application + MainActivity + StreamNav + ProfileShell
│   ├── build.gradle.kts
│   ├── debug.keystore           # generated by build_apk.sh
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/res/            # icons, strings, themes, NSC
│   └── src/main/java/local/phantom/companion/
│       ├── PhantomApp.kt
│       ├── MainActivity.kt
│       ├── auth/                # AndroidBiometricGate
│       ├── data/                # AppContainer, PairedDeviceStore (EncryptedSP),
│       │                       #   SystemPulseRepository (vitals aggregator)
│       ├── net/                 # PhantomApi (Ktor), PhantomLink (WS)
│       ├── service/             # PhantomLinkService (foreground), Watchdog (alarm)
│       ├── vm/                  # PhantomViewModel + PhantomLinkEvents (typed flows)
│       └── ui/
│           ├── StreamNav.kt     # primary post-pair surface — Stream + Orb
│           ├── widget/          # VitalsWidgetProvider (App Widget)
│           ├── ProfileShell.kt  # auth gating shell (Onboarding ↔ PhantomRoot)
│           └── screens/PairScreen.kt   # legacy fallback pair entry
├── docs/
│   ├── COMPANION_CONTRACT.md    # ground-truth wire contract vs phantom-os/
│   ├── E2E_MANUAL_CHECKLIST.md  # device-required test plan
│   ├── ROADMAP.md               # Tier 1..4 phases (vision §13)
│   └── design-refs/
│       └── legacy-pwa-snapshot/ # 65 .tsx файлів read-only візуального референсу
└── scripts/build_apk.sh
```

✅ = реалізація вже на місці. Решта помічена коментарями про active phase.
Pair crypto consolidation: starting with companion-v2-phase-0 (2026-05-09)
the legacy `app/.../pair/` directory was removed and all pair primitives
(QR, ECDH, HMAC proofs, PIN-based discovery) live under `:core-net/pair/`
and `:core-net/discovery/`. The `:core-bridge` module's Phase-5 scaffolds
were also retired; that work landed inline where it actually shipped.

## Що далі

Дивись `docs/ROADMAP.md` — Tier 1..4 з phase-IDs, scope, acceptance.
Найближча черга: **Phase 1-D** (`:core-ai` — Gemma 3-2B-it локально + Gemini
cloud router) → **Phase 1-E** (`:feature-stream` StreamScreen +
StreamItem + ViewModel) → **Phase 1-F** (Generative Widget Renderer).
