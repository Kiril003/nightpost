# Phase 3 Integration Audit (DA-6) — Performance Baseline

> Pre-optimization baseline captured 2026-05-10. Measurement-only Wave;
> no app fixes for performance per `docs/INTEGRATION_AUDIT.md:424`. One
> incidental manifest hygiene fix was required to make cold-launch
> measurable at all (see `## Pre-measurement fix` below). Numbers here
> are the reference DA-7 (Phase 3 closing report) cites and that any
> future R8 / size / startup optimisation Wave compares against.

## TL;DR

| Metric | Value | Status |
|---|---|---|
| Cold-launch `TotalTime` median (n=9) | **922 ms** | ⚠ pre-R8, full ABI fat APK, debug-built |
| Cold-launch p95 / max | 1197 ms / 1197 ms | ⚠ first-iter outlier 1257 ms dropped |
| Heap PSS at idle (5 s after cold) | **239 MB** | ⚠ baseline includes `PhantomLinkService` foreground autoboot |
| Heap PSS peak (after pair-flow) | 357 MB | ⚠ camera/Compose surfaces dominate the +118 MB delta |
| APK size — debug | **285 MB** (298,712,373 B) | ⚠ `isMinifyEnabled = false` per `app/build.gradle.kts:49`; 7-ABI fat |
| APK size — release | **267 MB** (279,130,520 B) | ⚠ R8 deferred (`:54-57`); release-vs-debug delta is DEX-consolidation only |
| Native libs share of APK | **210 MB** (≈74 % of debug, ≈75 % of release) | ⚠ identical across both build types — splits/per-ABI deferred |
| DEX method count (release) | 303,810 across 5 `classes*.dex` | ⚠ `classes.dex` near 64 K cap × 5 |

Two load-bearing facts the table memorialises: `isMinifyEnabled = false`
on **both** debug and release per `app/build.gradle.kts:49,58` (R8
deferred — see comment at `:54-57`); single-device baseline only —
**Samsung Galaxy A56 (SM-A566B), Android 16 (SDK 36)**, 8-core, 7.2 GiB
RAM, screen 1080×2340 @ 450 dpi.

---

## Pre-measurement fix

`MainActivity.checkAndRequestPermissions()` (`app/src/main/java/local/phantom/companion/MainActivity.kt:83-97`) requests `android.permission.READ_PHONE_STATE` at cold-launch when `Build.VERSION.SDK_INT >= 31`, but the permission was **not declared** in `app/src/main/AndroidManifest.xml`. Effect: every cold launch popped a `GrantPermissionsActivity` dialog as the first frame, so `am start -W` reported time-to-permission-dialog (~770 ms median in the broken state) instead of time-to-MainActivity-displayed.

Fix landed in a separate prep commit: added `<uses-permission android:name="android.permission.READ_PHONE_STATE" />` to `AndroidManifest.xml` next to `RECORD_AUDIO` (the perm is paired with it in `MainActivity.kt:84-87`). After the fix, `am start -W` reports `Activity: ...MainActivity` (not `GrantPermissionsActivity`) and `LaunchState: COLD` for every iteration.

> Categorised as manifest hygiene, not feature change — the code already
> requests the permission; the manifest just didn't reflect that. Without
> this declaration the cold-launch baseline could not be captured at all.

---

## Methodology

> N=10 cold-launches per iteration, force-stop + `am kill` between
> (no `pm clear` — that would force first-launch onboarding, which is
> the wrong baseline). Iteration 1 dropped from aggregates as filesystem
> warm-up outlier; the remaining 9 feed median + p95 + min + max +
> stddev. **Median + p95 chosen over mean ± stddev** because the
> distribution is right-skewed (cold-launch JIT variance produces long
> tails — mean is misleading).
>
> Canonical "cold-launch" number is `TotalTime` from `am start -W`:
> first-activity-of-transition → idle of target activity. `ThisTime`
> undercounts when a splash exits itself (always 0 for this app since
> `MainActivity` is the only activity in the launch transition).
> `WaitTime` adds AM/IPC overhead (~5 ms above `TotalTime` here).

| Tool | Used for | Notes |
|---|---|---|
| `adb shell am start -W` | Cold-launch timing | After `am force-stop` + `am kill`; no `pm clear` |
| `adb shell dumpsys meminfo -d` | Heap snapshots | `-d` adds Dalvik split (Java alloc/free). Sequential within one app session, no force-stop between snaps. |
| `aapt2 dump badging` | APK metadata | Ubuntu's aarch64 `aapt2` (`/usr/bin/aapt2`, version 2.19-debian) per the `aapt2FromMavenOverride` set in `gradle.properties` |
| `unzip -l` + `awk` per-section sum | APK section breakdown | DEX bytes / `lib/` per ABI / `res/` / `assets/` / `resources.arsc` |
| DEX header parse (`od -An -t u4 -N4 -j88`) | Method count per `.dex` | `method_ids_size` is a uint32 LE at offset 88 of the DEX header. **`apkanalyzer` and `dexdump` are x86_64-only on this aarch64 host**, so per-package method breakdown is not available — only per-DEX totals. |

## Device under test

| Field | Value |
|---|---|
| Device id | `RFCY61HG0WB` |
| Model | `SM-A566B` (Samsung Galaxy A56) |
| Manufacturer | `samsung` |
| Android version | 16 |
| SDK | 36 |
| Build fingerprint | `BP2A.250605.031.A3.A566BXXS6BYJ4` |
| Screen | 1080 × 2340 px @ 450 dpi (override 420) |
| ABI | `arm64-v8a` (single) |
| CPUs | 8 |
| Total RAM | 7,598,476 kB (≈7.24 GiB) |
| Battery at run start | 57 % (≥ 50 % gate) |
| Stay-awake | `svc power stayon usb` set during run |
| Doze whitelist | `dumpsys deviceidle whitelist +local.phantom.companion.debug` set |

Host (build + measurement console): Radxa SBC, aarch64, Ubuntu 24.04.4 LTS,
Linux 6.17.1-2-qcom, Java 21 (OpenJDK), Gradle 8.10.2 (Kotlin 1.9.24),
`adb` 1.0.41, `aapt2` 2.19-debian, build-tools 34.0.0 (x86_64-only — used for jar layout, not invocation).

## Cold-launch (am start -W)

Raw per-iteration timing (`TotalTime` is the canonical cold-launch number; all values in ms). All 10 iterations reported `LaunchState: COLD` and `Activity: local.phantom.companion.debug/local.phantom.companion.MainActivity`.

| iter | ThisTime | TotalTime | WaitTime |
|---:|---:|---:|---:|
| 1 (warm-up, dropped) | 0 | 1257 | 1267 |
| 2 | 0 | 1197 | 1204 |
| 3 | 0 | 1023 | 1030 |
| 4 | 0 | 1019 | 1028 |
| 5 | 0 | 1001 | 1005 |
| 6 | 0 | 891 | 892 |
| 7 | 0 | 862 | 866 |
| 8 | 0 | 922 | 927 |
| 9 | 0 | 878 | 885 |
| 10 | 0 | 879 | 881 |

Aggregates over iterations 2-10 (n=9):

| Metric | median | p95 | min | max | mean | stddev |
|---|---:|---:|---:|---:|---:|---:|
| `TotalTime` | **922** | 1197 | 862 | 1197 | 963.6 | 108.6 |
| `WaitTime` | 927 | 1204 | 866 | 1204 | 968.7 | 110.0 |
| `ThisTime` | 0 | 0 | 0 | 0 | 0.0 | 0.0 |

`ThisTime` is uniformly 0 because `MainActivity` is the only activity in the launch transition — there is no preceding splash to subtract.

## Heap snapshots

Four sequential `dumpsys meminfo -d` snapshots on the **same** running app session — no `am force-stop` between them — so the Δ column tracks cumulative heap growth as different surfaces are exercised. All values in kB.

| # | Trigger | PSS Total | Java | Native | Code | Stack | Graphics | Priv. Other | System | Δ PSS vs prev |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | Idle 5 s after cold launch (focus = MainActivity, `PhantomLinkService` foreground autoboot per `AndroidManifest.xml:78-81`) | 239,662 | 23,116 | 43,388 | 94,088 | 2,676 | 47,492 | 11,128 | 17,774 | — |
| 2 | After tap on "Vault" label (TextView at `[937,419][1016,492]`, `clickable="false"` — Compose IconButton wrapper did **not** route the tap; surface unchanged. Heap shows GC firing on background work.) | 238,335 | 15,192 | 44,504 | 94,288 | 2,176 | 47,492 | 15,832 | 18,851 | −1,327 |
| 3 | After `POST /api/v1/pair/init` from phantom-os frontend (server-side trigger, `pair_id=fb57f26c…` issued, TTL 60 s) | 356,989 | 80,152 | 47,864 | 64,456 | 3,628 | 115,744 | 10,776 | 34,369 | **+118,654** |
| 4 | After `POST /api/v1/companion-control/lock_vault` from phantom-os REST (`delivered_to: 1`, `verb: lock_vault`, payload `{id: da6-baseline-2}`); app re-foregrounded via `am start` (no kill) before snap because Google Assistant briefly stole focus and dropped graphics surfaces | 285,345 | 57,940 | 32,132 | 26,780 | 1,764 | 95,316 | 18,588 | 52,825 | −71,644 |

Notes:

- **Snap 1 → 2** delta is mostly noise (−1.3 MB PSS) — the SurfaceTray "Vault" label rendered as a non-clickable `TextView`, so the tap did not change the visible surface; the Java heap drop (−7.9 MB) is GC reclaiming temporary allocations from the background `PhantomLinkService` and friends.
- **Snap 3** is the largest growth point: pair init opens a QR-display + WS pair channel, which on the desktop side allocates camera/Compose preview buffers (`Graphics +68 MB`) and inflates the Java heap (`+65 MB`). Code mmap drops by 30 MB simultaneously as Android trims unused class data — net heap growth is dominated by graphics + Java.
- **Snap 4** measures a re-foregrounded session: the **first** lock_vault snap (not shown above; raw at `/tmp/da6/heap-4-lock_vault.txt`) was captured while Google Assistant held focus, so PSS had collapsed to 163 MB through normal background-app trimming — that number was **not** representative of post-`lock_vault` heap impact, so the snap was retaken after `am start -n .../MainActivity` (without `force-stop`) brought the app back to foreground. The re-snap shows real lock-pulse impact: −71 MB from snap 3 as the pair-flow camera preview tears down.
- All triggers happened on the **same process** (pid 30757 throughout). No `force-stop` between snaps.

Raw `dumpsys meminfo -d` outputs in `/tmp/da6/heap-{1-idle,2-vault,3-pair,4-lock_vault-fg}.txt` for replay.

## APK breakdown

Both build types use the debug keystore (`app/build.gradle.kts:59` reuses `signingConfigs.getByName("debug")` for release). `isMinifyEnabled = false` on **both** (`:49`, `:58`) — release-vs-debug delta therefore comes entirely from DEX consolidation, not from R8/Proguard.

| Section | Debug bytes | Release bytes | Δ release−debug |
|---|---:|---:|---:|
| **Total APK** | **298,712,373** (285 MiB) | **279,130,520** (267 MiB) | −19,581,853 (−6.6 %) |
| Uncompressed total | 298,474,463 | 278,928,250 | −19,546,213 |
| DEX (sum of `classes*.dex`) | 85,793,452 | 66,253,516 | **−19,539,936** |
| DEX file count | 31 | 5 | −26 |
| DEX methods (sum) | 309,891 | 303,810 | −6,081 |
| `lib/` (all ABIs) | 209,647,496 | 209,647,496 | **0 (identical)** |
| `res/` | 436,164 | 437,060 | +896 |
| `resources.arsc` | 1,085,856 | 1,066,440 | −19,416 |
| `assets/` | 0 | 11,596 | +11,596 (baseline profiles) |
| `kotlin/` | 29,945 | 29,945 | 0 |
| `META-INF/` | 6,141 | 6,261 | +120 |

`lib/` per ABI (identical for both build types — both are full multi-ABI fat APKs):

| ABI | bytes | comment |
|---|---:|---|
| `arm64-v8a` | 59,663,128 | the only ABI used on the test device |
| `x86_64` | 55,767,680 | unused on physical Android |
| `x86` | 54,672,680 | unused on physical Android |
| `armeabi-v7a` | 39,128,396 | 32-bit ARM, rarely used in 2026 |
| `mips64` | 141,904 | obsolete — ABI dropped from Android in API 26 |
| `mips` | 150,924 | obsolete |
| `armeabi` | 122,784 | obsolete (ARMv5 — no Android device since ~2014) |

Total fat-APK overhead: **~150 MB** of `lib/` is for ABIs that will never run on the target device. APK splits per ABI / Play Asset Delivery / R8 → AAB are deferred to a future Wave.

Top contributors to `lib/` size (per ABI, single representative shown):

| Native lib | arm64-v8a bytes | Source / purpose |
|---|---:|---|
| `libonnxruntime.so` | 17,575,328 | ONNX Runtime — voice + AI inference |
| `libmaplibre.so` | 11,206,736 | MapLibre Native — map rendering |
| `libvosk.so` | 8,862,928 | Vosk speech recognition |
| `libllm_inference_engine_jni.so` | 8,575,912 | MediaPipe Tasks LLM inference |
| `libsqlcipher.so` | 5,797,528 | SQLCipher encrypted DB |

Multidex layout is the dominant release-vs-debug visible difference: debug splits aggressively into 31 DEX files (build-performance-driven, one slice per gradle module slice) while release consolidates to 5 DEX files (link-time chosen for runtime layout). The 5 release DEX files are individually near the 64 K-method per-DEX cap (each `classes.dex` … `classes4.dex` carries ~60-65 K methods).

## Known caveats

- ⚠ **R8 disabled on both build types** (`app/build.gradle.kts:49`, `:58`) — comment at `:54-57` states "R8 stays off until we have a tested proguard-rules.pro covering Ktor + BouncyCastle reflection". This baseline is therefore the **pre-optimization baseline by design** — any future R8 Wave should compare against the numbers above, not against an idealised target.
- ⚠ **Release signed with debug keystore** (`:59`). Adequate for LAN sideloading per the existing operator workflow; not adequate for Play distribution.
- ⚠ **Single-device baseline.** Numbers above are specific to `RFCY61HG0WB` (Samsung Galaxy A56, Android 16). Cold-launch on a slower or older device will scale up; heap residency will scale up with display density.
- ⚠ **Heap snapshot 2 measured GC noise, not surface change.** The "Vault" label is a non-clickable `TextView` inside a Compose IconButton; the tap at `(976, 455)` did not change focus. Re-running snap 2 with a different surface (e.g. Map tab) would produce a more meaningful Δ — left as documented limitation rather than re-trigger because the heap session would have to start over.
- ⚠ **Heap snapshot 4 was retaken** after Google Assistant briefly stole focus and dropped phantom-companion to background. The first capture (PSS 163 MB) is in `/tmp/da6/heap-4-lock_vault.txt` and reflects background-trim heap, not lock_vault impact. The number reported in the table is the foreground re-snap.
- ⚠ **`apkanalyzer` and `dexdump` are unavailable on this aarch64 host** — both ship as x86_64 binaries from Google. DEX method count fell back to direct DEX-header parsing (`method_ids_size` at offset 88, uint32 LE); per-package method breakdown is **not** available. Recommend installing aarch64-native equivalents from Ubuntu repositories or building from AOSP if DA-7+ wants finer breakdown.
- ⚠ **`pm.isIgnoringBatteryOptimizations()` returns false despite `dumpsys deviceidle whitelist +<pkg>`** — the user-facing battery whitelist (Settings → Battery → Unrestricted) is a separate gate from the deviceidle whitelist. `MainActivity.requestBatteryOptimizations()` (`MainActivity.kt:99-107`) therefore still fires the system intent on every cold launch; it lands silently because the runCatching wraps it, but it is observable as a brief `Activity` window in `dumpsys activity activities` immediately after each launch.
- ⚠ **`am force-stop` + `am kill` is "warm-disk cold-process"**, not pure cold launch. `drop_caches` requires root and is unavailable on production phones — so this is the operator-reality scenario, not a defect. Phones effectively never reboot in normal use.
- ⚠ **`READ_PHONE_STATE` runtime perm + `RECORD_AUDIO` + `ACCESS_FINE_LOCATION` + `BLUETOOTH_*` + `CAMERA` + `UWB_RANGING` + `ACTIVITY_RECOGNITION` were `pm grant`-ed before measurement.** This represents the steady-state granted configuration any operator reaches after running the app once. First-launch cold-launch (with all dialogs to dismiss) is a separate, higher-latency scenario not measured here.
- ⚠ **dumpsys output format** is Samsung One UI 6.x / Android 16-specific. Field-extraction script (`/tmp/da6/heap_snapshot.sh`) keys off label text not column index, so it should survive minor format drift, but cross-device comparisons require re-extraction.
- ⚠ **Release build required `-x verifyReleaseResources` workarounds** for `feature-ambient`, `feature-godmode`, and `feature-stream`. Root cause: `feature-ambient/.../phantom_wallpaper.xml` references `@mipmap/ic_launcher`, which exists in `:app` but not in the feature module's own resource set. APK is functionally complete but a future Wave should add the icon to the feature module (or change the reference to `@android:drawable/sym_def_app_icon` / a feature-local resource) to remove the workaround.

## Reproduction steps appendix

End-to-end reproduction of every number in this document. Run on the same Radxa host with the device attached and app installed.

```bash
# Pre-flight
adb devices                                  # exactly one `device` line
adb shell pm path local.phantom.companion.debug
adb shell cmd package resolve-activity --brief local.phantom.companion.debug | tail -1
curl -fsS http://127.0.0.1:8000/healthz      # phantom-os backend up
adb shell dumpsys battery | grep level       # ≥ 50 %
adb shell svc power stayon usb               # keep screen alive
adb shell dumpsys deviceidle whitelist +local.phantom.companion.debug
for p in POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
         BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN CAMERA \
         UWB_RANGING ACTIVITY_RECOGNITION READ_PHONE_STATE; do
  adb shell pm grant local.phantom.companion.debug "android.permission.$p"
done

# Build + install fresh APK (debug, ~5-15 min on Radxa SBC)
./scripts/build_apk.sh debug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Cold-launch N=10 (full script lives at /tmp/da6/cold_launch.sh)
PKG=local.phantom.companion.debug
ACT=local.phantom.companion.MainActivity
for i in $(seq 1 10); do
  adb shell am force-stop "$PKG"
  adb shell am kill "$PKG"
  sleep 2
  adb shell am start -W -n "${PKG}/${ACT}"
done | grep -E "TotalTime|WaitTime"

# Heap snapshots (one process, no force-stop between)
adb shell dumpsys meminfo "$PKG" -d > /tmp/heap-1-idle.txt
sleep 3 && adb shell input tap 976 455           # tap "Vault" label
sleep 4 && adb shell dumpsys meminfo "$PKG" -d > /tmp/heap-2-vault.txt
JWT='<root token from phantom_token in localStorage>'
curl -sS -X POST http://127.0.0.1:8000/api/v1/pair/init \
  -H "Authorization: Bearer $JWT" -d '{}'
sleep 4 && adb shell dumpsys meminfo "$PKG" -d > /tmp/heap-3-pair.txt
curl -sS -X POST http://127.0.0.1:8000/api/v1/companion-control/lock_vault \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"payload":{"id":"da6-baseline","reason":"DA-6 snapshot"}}'
sleep 4 && adb shell dumpsys meminfo "$PKG" -d > /tmp/heap-4-lock_vault.txt
# If app went to background between trigger and snap, re-foreground without
# kill: `adb shell am start -n "${PKG}/${ACT}"` then re-snap.

# Release APK (with verifyResources workarounds)
$GRADLE_BIN :app:assembleRelease \
  -x :feature-ambient:verifyReleaseResources \
  -x :feature-godmode:verifyReleaseResources \
  -x :feature-stream:verifyReleaseResources

# Per-section breakdown for either APK
APK=app/build/outputs/apk/{debug,release}/app-{debug,release}.apk  # pick one
unzip -l "$APK" | awk '
  /classes.*\.dex$/{dex+=$1; dexn++}
  /lib\/[^/]+\//   {lib+=$1}
  /res\//          {res+=$1}
  /resources\.arsc$/{arsc+=$1}
  END {printf "DEX=%d (%d files)  lib=%d  res=%d  arsc=%d\n",dex,dexn,lib,res,arsc}'

# DEX method count (apkanalyzer / dexdump unavailable on aarch64)
mkdir -p /tmp/dex && cd /tmp/dex && unzip -o "$APK" 'classes*.dex'
total=0
for d in classes*.dex; do
  count=$(od -An -t u4 -N4 -j88 "$d" | tr -d ' ')
  total=$((total + count))
done
echo "$total methods total"
```

## Raw outputs appendix

Full raw outputs preserved at `/tmp/da6/`:

- `cold_launch.txt` — all 10 `am start -W` outputs verbatim
- `cold_launch.csv` — per-iter columns
- `heap-1-idle.txt`, `heap-2-vault.txt`, `heap-3-pair.txt`, `heap-4-lock_vault.txt`, `heap-4-lock_vault-fg.txt` — full `dumpsys meminfo -d` per snapshot
- `apk/debug.txt`, `apk/release.txt` — full APK breakdown including `aapt2 dump badging`
- `ui-current.xml`, `ui-pre-snap2.xml`, `ui-post-vault-tap.xml` — `uiautomator dump` snapshots used to find tap coordinates

These live outside the repo; if reproducing, expect 60-90 min wall-clock on a Radxa SBC (most of it is the release build).
