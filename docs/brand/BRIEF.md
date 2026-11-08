# Nightpost: brand brief v1

*04.10.2026. Facts come from `cloud/beta` @ b629fd08, `cloud/sol-wedge` @ 3c90e4ce, `cloud/sol-dappstore` @ c0910925 and `cloud/familiar` @ 01b45000. Store and launcher specs were checked on 04.10.2026 against three sources: docs.solanamobile.com (raw `.md` pages and the portal screenshots on those pages), the live publisher portal's form code (`publish.solanamobile.com/assets/index-CZkFN4zp.js`), and developer.android.com.*

*This is the brief the brand system in `BRAND.md` answers. Where the two differ, `BRAND.md` records the decision.*

---

## 1. Name

**Nightpost.** It has two readings, and both must hold up:
- **Night post** (UA «нічний пост»): the sentry who stays awake so others can sleep. This is vigilance.
- **Night mail**: a message that still arrives when the network is dark. This is delivery.

Rules:
- The wordmark stays in Latin script in every market. The Ukrainian descriptor is «нічний пост». Do not transliterate it to «Найтпост».
- "Phantom" must not appear anywhere in the brand, the listing, the deck or the video. The Phantom wallet sponsors the hackathons and is listed in the same store. The Solana Mobile Publisher Policy (updated 21 Jul 2026) bans content likely to be confused with a third party's brand. The portal's review form also has a rejection category called "Branding & Impersonation".
- **Phantom is still in the code**, and that matters for the listing:
  - Package name: `local.phantom.companion(.seeker)`.
  - It is public in the listing link `solanadappstore://details?id=<package>`.
  - The portal matches every later release to the app by package name. Renaming after the first submission means a new listing.
  - **The package name has to be decided before the first dApp Store submission.**
  - Other places: `app_name` = "PHANTOM", the in-app assistant "Спитати PHANTOM", and `aria-label="PHANTOM"`.
- **Name collision scan** (one web search, not legal advice):
  - "The Night Post" is a podcast.
  - "Nightpost" is a Bandcamp artist.
  - "NightPost" is an upload feature inside the Juggluco CGM app.
  - No messenger or wallet uses the name. Before spending on the mark, the owner should run EUIPO and USPTO searches in classes 9, 38 and 36.

## 2. Product truth: what it does today and what is coming

Copy may claim only what the submitted build does. Each claim needs a source.

| Area | **Today (in code)** | **Not yet / coming** |
|---|---|---|
| Messenger | E2E messages, photos and voice notes. No phone number, email or account; people join by invitation; @nick directory. Phones on the same Wi-Fi or hotspot talk directly, with no internet. If the person is away, a sealed envelope waits on the relay ("сховок") for up to 7 days; the server cannot read it. Voice and video calls between phones. | Bluetooth mesh *messaging*: `cloud/radio-mesh` is raw and test-only. Do not claim a "Bluetooth mesh messenger". |
| Phone-to-phone without router | Cash notes travel over Google Nearby Connections (BLE/Bluetooth). This is in the `field` flavor only (`GmsNearbyCourier`, `fieldImplementation(play-services-nearby)`). | The dApp Store `seeker` flavor uses the store manifest. Confirm Bluetooth handoff is in the **submitted** APK before claiming it. |
| Map | OSM vector map with turn-by-turn navigation on foot and by car. Routes are computed on the phone from downloaded road packs, so routing works offline. Air-raid alerts and shelters, with crowd-confirmed status. | Map look and people on the map: final tuning on a real device. |
| Money (Solana) | Non-custodial wallet: 12 words, key in Keystore, every signature behind fingerprint or PIN. MWA and Seed Vault supported. Send SOL, USDC and EURC from a chat; addresses are exchanged inside E2E. **Offline cash notes were proven on devnet** with production `core-solana` code: SOL on 02.10; USDC and EURC on 03.10; double-spend "twins" were rejected (`docs/solana/DEVNET_PROOF.md`). File seal: only the SHA-256 goes on-chain, in a Memo. Seeker Genesis Token recognition and an SKR stake mark. Devnet by default; mainnet only through the user's own switch. | No two-phone on-device run of offline cash is recorded in the repo as of 04.10. No mainnet without the owner's yes. The dApp Store listing is not submitted yet (KYC, publisher wallet and signing are owner steps). |
| Assistant | Speech recognition on the device; a cloud model only with the user's own key. The money assistant drafts a payment from plain words; the user checks and signs. | It is not "AI". Never market it that way. |
| Light | The theme follows the **sun's altitude at the user's location** (`LivingSun`): night below −6°, dawn/dusk from −6° to +6°, day above +6°. The map turns dark at −5°. | — |
| PC | The same web messenger runs in Tauri. | PC glass on the shared web base is in progress. |
| Companion | 3D work on `cloud/familiar`. | Direction is unsettled; see §5.4. |

Copy rules:
- Always say "devnet" or "test money" next to any money claim.
- Never "secure", "unhackable", "AI-powered" or "military-grade".
- No invented users, metrics or partners. Hackathon rules disqualify for it, and CLOCK IN §15 allows the prize to be clawed back.

## 3. Audience

1. **Europe (primary market).** The owner is based in Czechia. EU households are being asked to prepare for disruption (EU Preparedness Union Strategy, 2025). They want calm competence, not fear. Copy should be EN first in the store and UA first in the product. Readers will judge us by taste: premium, quiet, no prepper or military aesthetic.
2. **Ukraine (proof).** Blackouts, alerts and mobile-internet cuts are daily life there. This is where the product was born and tested; it is Ukrainian-first, and the terrain seed is Kyiv. Use Ukraine as evidence ("built where networks go dark"), never as pity or marketing.
3. **Seeker owners.** Crypto-native, with Seed Vault, Genesis Token and SKR. They are tired of trading apps. Show them a phone that uses crypto for real life without internet. They will look for: MWA and Seed Vault support, SGT recognised, honest devnet labelling.
4. **Hackathon judges.** Deadlines: CLOCK IN 09.10 08:59 CEST; Colosseum and SolanaCZE 13.10 08:59 CEST. Judges spend about 3 minutes per entry. They need one demo path, verifiable proof (devnet signatures and explorer links) and a clear difference from roughly 15 earlier offline/mesh payment entries. The brand must read as finished, not as a hackathon project.

## 4. Emotional core

**Calm vigilance: a light kept on at night, a presence that stays.**

- **Promise:** when the network leaves, Nightpost does not.
- **Feeling:** steady, warm, unhurried, competent and honest. The relief of seeing one lit window on a dark street: someone is awake, and the message will get through.
- **Personality:** a night watchman rather than a hero. Speaks little and plainly; numbers are exact. Never alarms, never boasts.
- **Lines:** EN "The light stays on." (18) · UA «Світло не гасне.» · Descriptor: "Messenger and map that keep working when the network doesn't."
- **Not:** fear-selling, militarism, hacker-noir, crypto hype; cute mascots; "AI magic".

## 5. Identity today: exact values (tokens v1.2.0)

`docs/design/system/tokens.json` is the single source of truth (`meta.brand` = «тепла ніч · глибоке чорнило»). It generates `build/tokens.css`, `tailwind-preset.ts`, `PhantomTokens.kt` and `contrast-report.md`.

### 5.1 Colour

| Token | **Ніч** (default) | **День** | Role |
|---|---|---|---|
| canvas | **#0A0C10** | #F8F4EE | background |
| surface | #12151B | #FFFFFF | top bar, composer, incoming bubble, nav |
| raised | #1A1E26 | #F0EAE2 | inputs, sheets, cards |
| overlay | #242934 | #E6DED3 | pressed or selected |
| line / line-strong | #232831 / #343B47 | #E6DED3 / #D2C6B7 | hairline / input outline |
| text / text-2 / text-3 | #F4F1EC / #B6BCC6 / #939AA6 | #17110C / #4E4339 / #665A4D | ink ramp |
| **accent** | **#F2A93B** | **#F2A93B** | the one lit thing: primary fill, unread, focus, active nav |
| accent-hover / press | #F6B85A / #DD9629 | #EE9F2A / #DD9629 | fill states |
| accent-text | #F5B24A | #8A5200 | amber as text |
| accent-line | #F2A93B | #A9680A | amber as a mark (focus ring, indicator) |
| accent-soft | #2D2416 | #FCE6C0 | tinted selection |
| on-accent | #1A1206 | #1A1206 | label on amber (9.28:1) |
| self / on-self / on-self-2 | #D48B2C / #1A1206 / #2E1D04 | #FFE0A8 / #1C1309 / #5E4216 | own bubble |
| success / danger / warning | #5FD08A / #FF7D71 / #F0C068 | #1B7843 / #B42318 / #8A5A00 | status, always with a word |
| act-pin / mute / archive | #2E5A8C / #5D4A8A / #3B4352 | #2F6AA8 / #6E4FA0 / #5E6573 | swipe fills |
| scrim | #05060A @65% | #1E1712 @40% | behind sheets |

**Person tones** (solid fill, white initials, no gradient; the amber sector is skipped on purpose because amber means "act"): red #C8463E, green #24855A, teal #177C8B, blue #3570C4, violet #7D55C7, rose #C2457E.

**Hard rules:** #F2A93B is never text and never a mark on light ground (1.9:1 on cream). At most one amber fill per screen region. Amber never decorates.

### 5.2 Type
- **Manrope** 400/500/600/700 for everything. `tnum` for times, counters and distances. No italic; weight 600 for emphasis.
- **JetBrains Mono** 400 for code blocks only.
- Scale: display 28/34/700 (−0.02); title-1 22/28/700; title-2 18/24/600; headline 16/22/600; body 16/23/400; callout 15/20; subhead 14/20/500; footnote 13/18; caption 12/16/500. Sentence case, never ALL CAPS.
- **Inter:** `cloud/beta` has retired it. If Inter is meant to be the brand's text face, that is a new decision, not the current state.
- Drift: `core-design` still ships `playfair_display.ttf` and `manrope_extrabold.ttf`, which are not in the spec.

### 5.3 Form, motion and light
- **Radius:** chip 6, control 10, card 14, sheet 20, composer 24, bubble 18 (tail 6), full 9999.
- **Elevation:** at night, elevate by surface step, not by shadow.
- **Motion:** durations 90 / 150 / 240 / 360 / exit 180 ms; easing standard (0.2, 0, 0, 1); springs (stiffness / ζ) press 700/0.9, layer 400/0.86, flight 260/0.78, snap 180/0.75, breath 120/0.85. Only live states loop.
- **Motif: contour relief.** A district's terrain drawn as contour lines; the amber in the lines scales with sun power. Same seed generator as the relief under conversations in the app. The site's night scene presses the **P mark into the relief** (`world.ts markMask`); that must change with the mark.
- **Light:** driven by the sun. Low, warm and directional. Never glow, blobs or gradients.

### 5.4 Things that conflict with this brief (owner decisions)
1. **The old mark is a "P"** (`M4.5 21V3H12.5A7 7 0 1 1 10.9 16.81V13.45A3.8 3.8 0 1 0 12.5 6.2H7.7V21Z`) in 12 files on `cloud/beta`: `docs/design/system/mark/phantom-mark.svg`; `app/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_splash_mark.xml`, `lc_phantom_mark.xml`; `core-design/.../PhantomSplash.kt`; `HomeOpensOnPhantomTest.kt`; `phantom-os/src/frontend/index.html`, `public/phantom-icon.svg`, `PhantomMark.tsx`; `platform-site/public/phantom.svg`, `src/components/Logo.tsx`. Also: the site relief mask (`world.ts`), the Tauri icons, the dApp Store drafts on `cloud/sol-dappstore`.
2. **The companion has drifted.** The brief says a hooded figure with eyes as two slits of warm light. Familiar contract v4 has a galaxy in the hood with no eyes, steel limbs, gold-to-turquoise gradient ribbons, glyph rings and crystals: the AI-cliché set the owner banned. Either v4 comes back toward the brief, or the brief changes.
3. **Phantom-ghost risk.** A hooded silhouette with two lit eyes as an **app icon** is too close to the Phantom wallet's ghost. Keep the slit-eyes for the in-app character. The symbol must not be a figure, hood, ghost or face.
4. **Leftover systems.** Legacy `tokens.css` `sunrise-warm` and `amber-night`; Android `PhantomPalette` #F4AF25 and `values/colors.xml` sunrise_*; the §2.1 table in `PHANTOM_DESIGN_SYSTEM.md` shows v1.0 values. The source of truth is tokens.json v1.2.

## 6. The new symbol: requirements

- Independent of the name: no letters; it must survive another rename.
- Built from the meaning: one kept light / a post / a lit aperture in the night.
- Single path on a 24-unit grid, the same contract as today.
- One colour: alpha-only (themed icon, status bar), amber on ink, ink on paper, pressed into the contour relief.
- Readable at 16 px (favicon), 24 dp (status bar), 48 dp (launcher) and 192 dp (splash).
- Outside the neighbours' colours: Phantom purple, Solana purple-to-green, Seeker teal.
- Banned: ghost, hood, face or eyes, robot; shield or padlock, globe-and-nodes, wifi arcs; sparkles or stars, gradients, neon; glitch, matrix, generic "P".

## 7. Required assets: exact specs (checked 04.10.2026)

### 7.1 Solana dApp Store (publish.solanamobile.com)

| Field / asset | Requirement |
|---|---|
| dApp name | ≤ 25 characters, unique, not similar to existing apps |
| Subtitle | docs ≤ 30 (portal code accepts 50); write to 30 |
| Description | required, ≤ 10,000 characters |
| What's new | required on every release after the first |
| dApp icon | exactly 512×512, background colour, no transparency; JPG/PNG/WebP/GIF ≤ 3 MB; deliver full-bleed |
| Banner | exactly 1200×600, required; the icon overlaps the bottom-left (x 5–38 %, y 84–100 %) — keep it empty |
| Editor's Choice graphic | exactly 1200×1200, optional; headline ≤ 50, tag ≤ 12 |
| dApp Preview | 4–8 items, same dimensions and orientation; images ≥ 1080 px both sides, ≤ 3 MB; plan 1080×2400 portrait |
| Languages | ≥ 1 Android locale → `en-US`, `uk-UA` |
| App website · Support email · Terms · Privacy | required, https |
| Package name | permanent for the listing |
| Binary | signed release APK; separate signing key from Play |
| Category | assigned by Solana Mobile |
| Review | 3–5 business days; publisher wallet about 0.2 SOL |

### 7.2 Android launcher
- Adaptive icon: 108×108 dp layers; inner 66 dp safe zone; logo 48–66 dp; no baked shadows or masks; minSdk 29, so `mipmap-anydpi-v26` XML covers every device.
- Background: solid `#0A0C10`.
- Monochrome: one colour, alpha is the shape; ship our own (Android 16 QPR2 auto-themes otherwise).
- Splash (12+): 288 dp canvas, mark inside a 192 dp circle.
- Notification small icon: dedicated `ic_stat_*`, 24 dp, white on transparent.

### 7.3 Other surfaces
- Google Play: icon 512 (32-bit PNG, full square, ≤ 1024 KB); feature graphic 1024×500 (no alpha); short description ≤ 80.
- PC (Tauri): `src-tauri/icons/` from one 1024² master.
- Web: SVG favicons; `Logo.tsx`.
- Judges: demo video 1920×1080 and a 16:9 deck, Nightpost mark only.

### 7.4 Deliverables checklist
1. Symbol master: SVG, single path, 24-unit grid, plus a 1024² PNG.
2. Wordmark and lockups, with clearspace.
3. Android: `ic_launcher_foreground` / `_monochrome` (108 dp), `ic_splash_mark` (288 dp), `ic_stat_*` (24 dp), in-app mark.
4. dApp Store: `icon-512.png`, `banner-1200x600.png`, optional `editors-choice-1200x1200.png`, 4–8 previews at 1080×2400.
5. Play: icon 512 and feature graphic 1024×500.
6. Tauri icon set.
7. Web favicons.
8. Replace the P in the relief mask (`world.ts`) and update the guard tests (`identity.test.tsx`, `HomeOpensOnPhantomTest`).

---
Sources: [Listing Page Guidelines](https://docs.solanamobile.com/dapp-store/listing-page-guidelines) · [Submit a New App](https://docs.solanamobile.com/dapp-store/submit-new-app) · [Submit an Update](https://docs.solanamobile.com/dapp-store/submit-an-update) · [Publishing Checklist](https://docs.solanamobile.com/dapp-store/checklist) · [Publisher Policy](https://solanamobile.com/publisher-policy-web) · [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) · [Android splash screen](https://developer.android.com/develop/ui/views/launch/splash-screen) · [Google Play icon spec](https://developer.android.com/distribute/google-play/resources/icon-design-specifications) · [Google Play preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
