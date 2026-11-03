# Nightpost — System Architecture

Nightpost combines a native Android application (`phantom-companion`), an embedded React messenger (`phantom-os/src/frontend`), a paired desktop runtime (`phantom-os/src/backend`), a web platform with a structured page Builder and blind relay server (`platform-site`), and an on-chain Solana escrow program (`solana-programs`).

---

## 1. Android Application (`phantom-companion`)

The Android app is structured as a **26-module Gradle composite project** (`minSdk 29`, `targetSdk 36`, Kotlin 2.0, Jetpack Compose). It builds two product flavors:
- **`store`**: Google Play and Solana dApp Store compliant build (no background location, SMS, or Accessibility services).
- **`field`**: Operator and field build with extended device-level capabilities (`feature-godmode`, BLE/Nearby offline courier).

### Module Breakdown

| Domain | Modules | Responsibilities |
|---|---|---|
| **App Shell & Design** | `:app`, `:core-design` | Main activity, tab orchestration, biometric lock gate, WebView host (`WebMessengerHost`), design system tokens (*Day*, *Night*, *LivingSun* solar altitude theme), glass & surface hierarchy. |
| **Network & Identity** | `:core-net`, `:core-license`, `:core-truth` | X25519 + Ed25519 cryptography, blind relay store-and-forward envelopes, `@handle` directory resolution (`PeerDirectory`), WebRTC audio/video call signaling & TURN/ICE guard, crowd-verified status (`core-truth`). |
| **Solana & Payments** | `:core-solana` | Hardware-backed wallet (`AndroidKeyStore`), BIP-39 12-word seed vault, SPL Token transfers (`USDC`, `EURC`, `SOL`), `TransferChecked` + `SPL Memo`, gasless fee sponsor client (`Sponsor.kt`), Mobile Wallet Adapter (MWA), Seed Vault, Seeker Genesis Token (`SeekerGenesis.kt`) & SKR tier verification. |
| **Map, Routing & Mobility** | `:core-geo`, `:core-nav`, `:core-gnss`, `:core-indoor`, `:core-mobility`, `:core-sensor`, `:feature-stream`, `:feature-car` | MapLibre vector map rendering, offline city road packs, turn-by-turn pedestrian/car/bike navigation, road closure time-window evaluation, parking pricing, GTFS public transit schedules, GNSS anti-spoofing black box, Android Auto surface (`:feature-car`). |
| **Assistant, Voice & Context** | `:core-ai`, `:core-voice`, `:core-vision`, `:core-context`, `:core-memory`, `:core-symbiote` | On-device speech-to-text (Whisper / Vosk downloaded over Wi-Fi), TTS synthesis, 6-state context FSM, server-routed assistant profiles (`chat`, `act`, `voice`, `summary`), action drafting across chats, map, calendar, and wallet. |
| **Data & Country Packs** | `:core-data` | Encrypted local databases (SQLCipher), multi-profile state isolation, pre-baked offline country datasets (`assets/country/**`). |
| **Feature Surfaces** | `:feature-onboarding`, `:feature-precision`, `:feature-ambient`, `:feature-godmode`, `:wear` | Zero-account cryptographic onboarding, recovery code flow, precision instruments, ambient widget/Orb surfaces, Wear OS companion. |

### The Typed JS ↔ Native Bridge & Bundle Seal
The conversation UI, Moments, Circles, Profile Switcher, and in-app Builder run inside `WebMessengerHost` from a pre-compiled React bundle (`app/src/main/assets/webmsgr`).
- All sensitive operations (key signing, envelope encryption, SQLite/SQLCipher storage, camera/mic with `FLAG_SECURE`, Solana transactions) cross a strictly typed bridge to the Kotlin shell.
- `scripts/sync_web_messenger.sh` builds `phantom-os/src/frontend` from a clean commit and writes `BUNDLE_SOURCE` (`web_sha` + `content_sha256`). The Gradle task `verify<Variant>WebBundleSeal` verifies this SHA-256 before packaging any APK or AAB.

---

## 2. Cloud API & Blind Relay (`platform-site/server`)

A FastAPI service backed by PostgreSQL (and dedicated SQLite stores for high-read venue/points catalogs), deployed behind Nginx and TLS at `api.phantom-os.dev`.

| Subsystem | Key Modules | Responsibilities |
|---|---|---|
| **Blind Envelope Relay** | `main.py` (`/relay/*`) | Store-and-forward delivery of E2E encrypted envelopes. Stores opaque blobs addressed by recipient key hash for up to 7 days; deletes immediately upon delivery receipt. |
| **Identity & Directory** | `directory.py` | Maps `@handle` (8–32 chars) and `sha256(handle)` to Ed25519-signed invite records (30-day renewable TTL). Serves unified search across people, public groups, channels, and Builder places. |
| **Builder & Structured Commerce** | `main.py` (`/api/builder/*`, `/p/:handle`) | 55-block schema registry, AI page generation from business descriptions/photos/price tables, real-time slot booking engine, stock tracking, and owner phone inbox routing. |
| **Solana Fee Sponsor & RPC Verifier** | `sponsor.py` | Co-signs user SPL transfers as the fee payer on devnet so users never need SOL for gas. Reads finalized transactions directly from Solana RPC to verify payments and unlock review eligibility. |
| **Points & 3D Gift Store** | `gift_store.py` | Verifies activity claims signed by the device's isolated `ClaimKeyStore` key; manages point balances and 3D gift/sticker redemptions. |
| **Assistant Router** | `main.py` (`/api/ai/*`) | Maps logical assistant profiles (`chat`, `act`, `voice`, `summary`) to LLM providers on the server, enforcing per-device daily quotas (40/day) so zero API keys or model names ever ship in the client. |
| **Zero-Knowledge Backup & Recovery** | `main.py` (`/api/backup/*`, `/api/me/*`) | Stores client-encrypted profile/circle/chat snapshots (ciphertext only), recovery-code vaults, device revocation, and GDPR/Play account erasure with grace-period cancellation. |
| **Support & Moderation Desk** | `support_desk.py` | User tickets, group/page abuse reports, appeals, crash report ingestion (`/api/support/crash`), and staff moderation console. |

---

## 3. Web Platform & Builder Editor (`platform-site/src`)

Built with React 18, TypeScript, and Vite 5, hosted at `phantom-os.dev`:
- **Product & Trust Surfaces:** Interactive product tour, `/download` (APK + checksums), `/pricing`, `/security`, `/privacy`, `/delete-account`.
- **Shared Builder Editor (`/build`):** The exact same 55-block editor (`Canvas` and `Pro` modes, desktop 1440px and phone 390px viewports, Day and Night themes) that runs inside the mobile app.
- **Public Live Pages (`/p/:handle`):** Fast, SEO-clean rendering of published venues, studios, creators, and schools with interactive booking calendars, direct chat triggers, and USDC payment sheets.

---

## 4. On-Chain Solana Programs (`solana-programs`)

Written in Rust with Anchor (`programs/nightpost_bill`) and verified with LiteSVM integration tests (`tests/escrow.rs`):
- **`Split` Mode (Group Bill Escrow):** A payee creates a bill with `1..=16` participant shares and a deadline (up to 30 days). Each participant calls `pay_share` to deposit their SPL Token share into a PDA vault. When the final share is paid, the vault automatically settles the full amount to the payee in the same transaction. If cancelled or expired, participants reclaim funds via permissionless `refund_share`.
- **`Hold` Mode (Booking Deposit Escrow):** A venue or studio holds a single buyer's deposit in a PDA vault with a cancellation `cutoff` timestamp and `deadline` (up to 90 days). Before `cutoff`, the buyer can self-refund; after `cutoff`, if `forfeit_allowed` is set, the venue can claim a no-show forfeit, or release the deposit upon service completion.

---

## 5. End-to-End Flows

### Flow A: Sending an E2E Message
1. User types a message (or attaches a view-once photo) in the React messenger UI.
2. The payload crosses `WebMessengerHost` to `:core-net`.
3. `:core-net` encrypts and signs the payload for the recipient's public key, producing an opaque sealed envelope.
4. If both phones are on the same LAN/Wi-Fi, the envelope delivers peer-to-peer; otherwise, it posts to the blind relay at `api.phantom-os.dev`.
5. The recipient's phone pulls envelopes for its address, verifies the Ed25519 signature, decrypts locally into SQLCipher, and posts a sealed delivery receipt.

### Flow B: Customer Booking on a Builder Page → Owner Chat
1. A studio owner publishes `/p/studio-lina` from the Builder editor and links their phone via QR code.
2. A customer opens `phantom-os.dev/p/studio-lina`, picks *"Haircut · 45 min · Fri 13:00"*, and submits the booking (optionally locking a USDC deposit in `nightpost_bill` `Hold` mode).
3. The server validates slot availability, records the booking, and pushes a structured `"Page Request"` into the owner's Nightpost chat list junto with an AI-drafted confirmation reply.
