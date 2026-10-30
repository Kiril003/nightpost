# Architecture

## Phone
The app is a Kotlin shell with a React messenger inside a WebView. The shell owns everything that needs the device: network, keys, wallet, map engine, location, voice, notifications and storage. The messenger owns the conversation UI and talks to the shell through a typed bridge.

| Layer | Modules |
|---|---|
| Shell and screens | `app` (flavours `field` and `store`), `core-design` (style tokens: Day, Night, By sun) |
| Network and identity | `core-net` (relay envelopes, directory, calls/ICE, invites), `core-license`, `core-truth` |
| Money | `core-solana` (in-app wallet, Mobile Wallet Adapter / Seed Vault, Solana Pay, split, fee sponsor, Seeker claim) |
| Map and place | `core-geo` (basemap, offline packs, place index), `core-nav`, `core-gnss`, `core-indoor`, `core-mobility` (transit), `core-sensor`, `feature-stream` (map UI) |
| Assistant | `core-ai` (server profiles: chat, act, voice, summary), `core-voice`, `core-vision`, `core-context`, `core-memory`, `core-symbiote` |
| Data | `core-data` (country data baked by scripts: places, streets, settlements, facilities) |
| Features | `feature-onboarding`, `feature-car`, `feature-precision`, `feature-ambient`, `feature-godmode` (operator tools) |

The messenger bundle (`phantom-os/src/frontend` → `app/src/main/assets/webmsgr`) is built from a clean commit and sealed with a content hash that Gradle verifies on every package.

## Server (`platform-site/server`)
FastAPI in one container on our own VM behind nginx and TLS at `api.phantom-os.dev`, with Postgres.

| Area | What it serves |
|---|---|
| Directory | handles, public profiles `/p/:handle`, public groups, channels and places in one search |
| Relay | sealed envelopes, store-and-forward, no sender or recipient |
| Builder | page drafts, a block registry, publishing, bookings and requests, stock, geocoding |
| Points and gifts | claims signed by a separate key, redemption |
| Assistant | model chosen per profile on the server; daily limits per device |
| Backup | encrypted snapshots (ciphertext only), recovery-code vault, email re-link, device revoke |
| Money | RPC proxy, fee payer (devnet), transaction reading for verification |
| Support | notices, tickets |

## Site (`platform-site`)
React + Vite on Netlify at phantom-os.dev: the product pages, download, account, and the Builder editor (the same editor the app uses). It talks only to the API above.

## Computer node (`phantom-os/src/backend`)
The same account on a desktop: FastAPI plus a desktop shell, paired with the phone end to end.

## Data flow, one message
1. The messenger hands the text to the shell.
2. The shell seals it for the recipient and posts the envelope to the relay.
3. The recipient's phone pulls envelopes for its address, opens them and shows the message.
4. Delivery and read state travel back the same way. The relay never learns who talks to whom.
