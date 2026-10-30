# Development

## Layout
| Part | Path | Check |
|---|---|---|
| Android app | `phantom-companion/` (flavours `field`, `store`) | `bash scripts/gate.sh <tasks>` |
| Messenger in the APK | `phantom-os/src/frontend` | `npx vitest run`, `npm run typecheck`, `npm run build:phone` |
| Computer node | `phantom-os/src/backend` | `python -m pytest -q` (any test `JWT_SECRET_KEY`) |
| Site + API server | `platform-site/`, `platform-site/server/` | `npm run build`, `python -m pytest tests -q` |
| Solana programs | `solana-programs/` | `anchor build` (devnet only) |

## Android
```bash
cd phantom-companion
bash scripts/gate.sh :feature-stream:testDebugUnitTest                 # one module
bash scripts/gate.sh :app:testFieldDebugUnitTest :app:testStoreDebugUnitTest
bash scripts/gate.sh $(scripts/gate-modules.sh tasks) --continue        # every module with tests
bash scripts/gate.sh :app:assembleFieldDebug                            # APK
```
- `gate.sh` is a gate, not a wrapper. It takes a machine lock, clears the configuration cache and prints a per-module table from **fresh** JUnit XML. A module with zero tests or no fresh report is red even on `BUILD SUCCESSFUL`.
- `:app` has flavours, so a bare `testDebugUnitTest` does not run the `:app` tests.
- Read the verdict from the gate output and its `log=` file, never from old XML in `build/`.
- Release signing (`keystore.properties`) never lives in the repository. CI and clean clones build debug, or an unsigned release with `-Pphantom.unsignedRelease=true`.

## The messenger bundle seal
The messenger that runs inside the APK is a committed artifact in `app/src/main/assets/webmsgr`. Any change in `phantom-os/src/frontend` or `src/shared` that must reach the phone ends with:
```bash
bash phantom-companion/scripts/sync_web_messenger.sh [<ref>]
```
The script builds `build:phone` from a **clean checkout of the commit**, never from the working tree. It writes `BUNDLE_SOURCE` with the last web commit and a `content_sha256` of the bundle. The Gradle task `verify<Variant>WebBundleSeal` checks that hash against what goes into the APK. The release script refuses a missing or stale seal.

Order: commit the web change, then sync, then commit the bundle separately. Never edit `webmsgr` by hand. Do not update the frontend `package-lock.json` in passing, because it is what makes the bundle reproduce byte for byte.

## Country data
Files under `core-data/src/main/assets/country/**` are produced by `phantom-companion/scripts/bake_*.py`. Change the script and re-bake; never edit the output.

## Rules for a change
1. A test that is red without the change.
2. Reachable from a real entry point: a screen, a notification, a deep link.
3. No invented UI. When there is no data, show an honest empty state. A control works end to end or is not shown.
4. Secrets never in git: `.env`, `local.properties`, `keystore.properties`, `*.jks`, `.mcp.json`. Only `*.env.example` with empty values.

## Release path
Branch per theme → review → integration branch → one bundle sync → gate (`:app:testFieldDebugUnitTest :app:testStoreDebugUnitTest :app:assembleFieldDebug`) → device walk → `main` → `scripts/play-release.sh NN` (AAB) and `:app:assembleStoreRelease` (APK).
