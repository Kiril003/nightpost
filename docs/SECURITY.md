# Nightpost — Security & Privacy Model

Nightpost is designed around **cryptographic device identity**, **blind message relay**, **hardware-isolated keys**, and **verifiable build integrity**.

---

## 1. Identity & Multi-Profile Isolation

- **Person = Key, Not Phone Number:** A Nightpost identity is an Ed25519 / X25519 keypair generated on the device (`node_id` fingerprint). No phone number, email, or central account is required to talk or transact.
- **Signed `@handle` Directory:** Handles (`8–32` chars, `[a-z0-9_]`) are registered in `directory.py` as `sha256(handle)` records signed by the owner's private key with a 30-day renewable lease. The client (`PeerHandleBook`) verifies the cryptographic signature before displaying any `@handle` on screen — spoofing another person's handle is mathematically impossible.
- **Multiple Life Profiles:** Profiles (`Personal`, `Work`, `Family`, `Studio`, `Temporary`) partition circles, inboxes, notifications, and privacy rules on the same device. Temporary profiles automatically expire after `1h`, `24h`, or `7d`.
- **Desktop Pairing (`Kind.DEVICE`):** Pairing a phone with the computer node (`phantom-os`) uses QR-bootstrapped X25519 ECDH + HKDF-SHA256 + HMAC proof and long-term Ed25519 device keys. The relay server is never in the plaintext path between your phone and your PC.

---

## 2. E2E Messaging & Blind Relay

- **Sealed Envelopes:** Every message, voice note, and attachment is encrypted end-to-end before leaving the phone. The store-and-forward relay (`api.phantom-os.dev`) sees only an opaque routing address and ciphertext blob. It never learns who is talking to whom, and envelopes are purged upon delivery (or after a 7-day TTL).
- **View-Once Media (`FLAG_SECURE`):** Single-view photos are transmitted as sealed payloads without feed thumbnails. While open, the native Android window enforces `WindowManager.LayoutParams.FLAG_SECURE` (blocking screenshots, screen recording, and task-switcher previews) and physically shreds the decrypted bytes upon close.
- **Local Database Encryption:** On-device conversation history, profiles, and vaults are stored in SQLCipher-encrypted databases (`:core-data`) with Android cloud auto-backup disabled.

---

## 3. Hardware Key Isolation & Secrets Policy

- **Android Keystore Separation:** On-device keys are isolated across dedicated Keystore-backed stores:
  - **Wallet Signing Key (`core-solana`):** Protected by biometric / fingerprint prompt (`BiometricPrompt`) or device PIN on every signature.
  - **Activity Claims Key (`ClaimKeyStore`):** Signs point and gift claims so the user's financial wallet key is never exposed to activity endpoints.
  - **Backup Encryption Key (`BackupKeyStore`):** Encrypts account snapshots locally before upload.
- **Zero Client Secrets:** No LLM provider key, model identifier, or cloud secret is ever compiled into the APK or web bundle. The client sends logical profile intents (`chat`, `act`, `voice`, `summary`) to the server, which routes them under strict per-device rate limits.
- **Repository Hygiene:** `.env`, `local.properties`, `keystore.properties`, `*.jks`, and `.mcp.json` are strictly excluded from version control and verified by automated secret scanners (`export.sh`). Only empty `*.env.example` templates ship in the repository.

---

## 4. Solana Wallet & On-Chain Escrow Security

- **Devnet Default & Explicit Mainnet Gate:** All financial flows run on **Solana devnet** by default. Mainnet execution requires both an explicit user confirmation gate and a server-side operator flag.
- **External Hardware Wallets:** On Solana Seeker and compatible devices, Nightpost connects via **Mobile Wallet Adapter (MWA)** and **Seed Vault** — the app never touches the external seed phrase.
- **Server-Side Chain Verification:** The backend never trusts client-reported payment claims. When verifying a bill share, booking deposit, or review eligibility, the server queries the Solana RPC directly to inspect the finalized transaction, token mint, `TransferChecked` amount, and SPL Memo (`nightpost-bill:v1:<id>`).
- **Deterministic PDA Escrow (`nightpost_bill`):** Group bill shares (`Split`) and booking deposits (`Hold`) are held in Program Derived Address (PDA) token vaults governed by strict timestamp invariants (`cutoff`, `deadline`, and permissionless `refund_share` / `refund_hold` recovery).

---

## 5. Build Integrity: The Web Bundle Seal

Because the conversation UI is compiled from `phantom-os/src/frontend` into `app/src/main/assets/webmsgr`, Nightpost enforces a deterministic build seal:
1. `scripts/sync_web_messenger.sh` checks out a **clean git tree** of the commit, runs `npm run build:phone`, and computes the SHA-256 over all generated assets.
2. It writes `BUNDLE_SOURCE` with `web_sha` and `content_sha256`.
3. During every Android build, the Gradle task `verify<Variant>WebBundleSeal` recomputes the asset hash and aborts the build if a single byte differs from `BUNDLE_SOURCE`.
