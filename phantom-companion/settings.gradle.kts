// PHANTOM Companion v2 — Gradle settings.
//
// Consolidated structure to reduce build overhead and project "junk".
// Merged 19 speculative core modules into :core-symbiote.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "phantom-companion-v2"

// — Application module ————————————————————————————————————————————————
include(":app")

// — Core (shared infrastructure) ————————————————————————————————————————
include(":core-design")
include(":core-net")
include(":core-data")
include(":core-ai")
include(":core-voice")
include(":core-vision")
include(":core-context")
include(":core-sensor")
// HIGHWAY DOMINANCE — the headless navigator. Owns the route, the guidance
// session and the cues, so both heads (Continuum UI, Android Auto) can observe
// one engine that outlives either of them. No Compose, no ViewModel, no UI.
include(":core-nav")

// — Геопросторова платформа та позиціонування —————————————————————————
// Чотири незалежні домени, свідомо розведені по окремих модулях: кожен має
// власну математику, власні тести і жодного Android UI, тож перевіряється на
// JVM без пристрою. `:core-sensor` лишається власником поточного фьюжену —
// ці модулі постачають йому кращі входи, а не конкурують за ту саму роль.
//
//   :core-gnss     — сирі супутникові вимірювання, TDCP, FDE, анти-спуфінг
//   :core-indoor   — позиціонування без супутника: PDR, Wi-Fi RTT, барометр
//   :core-mobility — визначення режиму пересування (TMD)
//   :core-geo      — шари карти, PMTiles, ліцензійний контур
include(":core-gnss")
include(":core-indoor")
include(":core-mobility")
include(":core-geo")

// — Комерційні ворота ——————————————————————————————————————————————————
// Ані мережі, ані UI: розбір підписаного сертифіката й політики плюс одна
// чиста функція рішення. Тому «безпека не вимикається» — не обіцянка в
// документі, а тест, що падає на JVM без пристрою й без сервера.
include(":core-license")

// — Краудправда —————————————————————————————————————————————————————————
// Факти від людей («укриття відчинене», «світла немає») з довірою, що згасає,
// і вагою голосу, яку не купиш новим акаунтом. Чиста JVM: той самий код
// житиме на сервері.
include(":core-truth")

include(":core-memory")

// — Solana: гаманець, перекази в розмові, Seeker. Лише адреса, жодних ключів чи коштів.
include(":core-solana")

// — Symbiote (consolidated library for advanced AI features) —————————
include(":core-symbiote")

// HIGHWAY — the car head. A CarAppService is a second, independent UI process
// driven by the Android Auto host; it has no Activity and no Compose, so it
// lives in its own module and cannot accidentally reach for either.
include(":feature-car")

// — Features (product surfaces) ————————————————————————————————————————
include(":feature-onboarding")
include(":feature-stream")
// Єдина поверхня, де стан позиціонування показано чесно: констеляція, C/N0,
// виключені супутники, джерело поточного розв'язку, індикатор глушіння/підміни.
include(":feature-precision")
include(":feature-godmode")
include(":feature-ambient")

// — Інструменти збірки ————————————————————————————————————————————————
// ATLAS-1: компілятор стилю «Обсидіан». Єдиний модуль без Android — чиста JVM,
// щоб уся картографія перевірялась тестами без пристрою й без емулятора.
// Продукт його не залежить: у застосунок їдуть випечені асети, не цей код.
include(":tools:obsidian")
