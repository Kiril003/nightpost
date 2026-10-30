import type { CtaBand, Item, Lang, PageMeta, Step } from './types';

export interface EgressRow {
  call: string;
  when: string;
  carries: string;
  never: string;
}

interface Head {
  eyebrow: string;
  title: string;
  lead: string;
}

export interface SecurityCopy {
  meta: PageMeta;
  hero: { eyebrow: string; title: string; sub: string; chips: string[] };
  threats: Head & { safeTitle: string; safe: Item[]; unsafeTitle: string; unsafe: Item[] };
  residency: Head & { points: Item[]; caveat: string };
  egress: Head & {
    columns: { call: string; when: string; carries: string; never: string };
    rows: EgressRow[];
    footnote: string;
  };
  zeroConfig: Head & { points: Item[] };
  licence: Head & {
    steps: Step[];
    facts: { trialLabel: string; graceLabel: string; freeLabel: string };
    gateTitle: string;
    gateLead: string;
    freeLabels: Record<string, string>;
    gateNote: string;
  };
  pairing: Head & { steps: Step[]; note: string };
  peer: Head & { points: Item[]; caveat: string; caveatTitle: string };
  servers: Head & { points: Item[] };
  posture: Head & {
    haveTitle: string;
    have: string[];
    notYetTitle: string;
    notYet: string[];
  };
  report: {
    eyebrow: string;
    title: string;
    lead: string;
    cta: string;
    mailSubject: string;
    addressLabel: string;
    note: string;
  };
  ui: { limitLabel: string };
  cta: CtaBand;
}

const en: SecurityCopy = {
  meta: {
    title: 'Security and data — Nightpost',
    description:
      'Where data is stored, every request the app makes to our servers, and what is not done: no external audit, no certification, no bug bounty.',
  },
  hero: {
    eyebrow: 'Security and data',
    title: 'What leaves your phone, and what stays.',
    sub: 'Where your data lives, every request the app makes to our servers, and what is not built yet.',
    chips: [
      'No sign-up, no phone number',
      'End-to-end encrypted messages',
      'Full server request table below',
      'Local Ed25519 licence verification',
      'No external security audit yet',
    ],
  },

  threats: {
    eyebrow: 'Summary',
    title: 'What is protected and what is not.',
    lead: 'A summary of the boundaries detailed below.',
    safeTitle: 'Protected',
    safe: [
      {
        title: 'Message content from our server',
        desc: 'Messages are encrypted on your phone. The server holds ciphertext and has no decryption key.',
      },
      {
        title: 'Message content on the network',
        desc: 'Traffic on shared Wi-Fi or in transit carries ciphertext, verified by tests on captured socket bytes.',
      },
      {
        title: 'Contact identity after QR verification',
        desc: 'Scanning a key from a live screen and matching 48 digits confirms the key. Key changes clear the verified mark.',
      },
      {
        title: 'A list of people that could leak',
        desc: 'No phone number. Signing in on the website makes an account from your device key. The server keeps no list of people: only hashes of names that people published themselves.',
      },
      {
        title: 'Usage tracking',
        desc: 'No analytics endpoint, no crash reporter, no usage counter.',
      },
    ],
    unsafeTitle: 'Not protected',
    unsafe: [
      {
        title: 'An unlocked phone',
        desc: 'Anyone holding your unlocked phone can read the conversations stored on it.',
      },
      {
        title: 'IP address and timestamps',
        desc: 'Our server sees which IP address dropped or collected a message and when.',
      },
      {
        title: 'Queued messages on our server',
        desc: 'Queued messages use the standing pair key without forward secrecy; seizing that key later opens previously captured messages.',
      },
      {
        title: 'Call metadata on relayed calls',
        desc: 'When a call uses our TURN relay, audio stays encrypted, but the relay sees who connected and for how long.',
      },
      {
        title: 'Keys exchanged remotely',
        desc: 'If a contact key arrived via link or another messenger, matching digits do not rule out an active middleman.',
      },
      {
        title: 'External code review',
        desc: 'No independent security audit has been performed on this codebase.',
      },
    ],
  },

  residency: {
    eyebrow: 'Data storage',
    title: 'Stored on your device, with two exceptions.',
    lead: 'Conversations, contacts, saved places and history stay on the device. Two exceptions exist: queued messages and the phone assistant.',
    points: [
      {
        title: 'Messages are encrypted on your phone',
        desc: 'A message is encrypted before sending, and only the recipient can open it. Undelivered messages wait on our server for up to seven days as ciphertext.',
      },
      {
        title: 'The phone assistant uses Gemini',
        desc: 'With your API key, requests go directly to Google Gemini; without one, our server forwards them without logging text. Speech recognition runs locally after a 137 MB model download.',
      },
      {
        title: 'The computer vault is encrypted',
        desc: 'Each computer vault field uses AES-256-GCM under a per-user key, bound to the card and field name. Personal fields are encrypted before database storage.',
      },
      {
        title: 'No usage metering',
        desc: 'There is no analytics endpoint, crash reporter or usage counter, and nothing is billed per request.',
      },
    ],
    caveat:
      'On the computer, if the database and operator secret are taken together, encrypted fields can be decrypted. Rotating that secret currently requires a manual migration.',
  },

  egress: {
    eyebrow: 'Network egress',
    title: 'Every request to our servers.',
    lead: 'Every request the app makes to our servers is listed below by path. Third-party services are listed beneath the table.',
    columns: {
      call: 'Request',
      when: 'When',
      carries: 'Carries',
      never: 'Never carries',
    },
    rows: [
      {
        call: 'Activation · POST /api/activate',
        when: 'Once, when a licence key is entered.',
        carries: 'The key, a SHA-256 device fingerprint, and the phone make and model.',
        never: 'No content, questions, location or counters.',
      },
      {
        call: 'Freeing a device · POST /api/deactivate',
        when: 'When you release a seat on your licence.',
        carries: 'The key and the same SHA-256 fingerprint.',
        never: 'Nothing else.',
      },
      {
        call: 'Delete everything · POST /api/erase/claim, POST /api/erase, POST /api/erase/cancel',
        when: 'Only when you choose Delete everything.',
        carries: 'The two public keys in two separate requests, each with its own signature.',
        never: 'Nothing else.',
      },
      {
        call: 'Policy check · GET /api/policy',
        when: 'On app start and every 12 hours on the free tier, or every 20 hours with a licence installed.',
        carries: 'Nothing: a bare GET with no auth header, key or fingerprint.',
        never: 'Nothing beyond the source IP address.',
      },
      {
        call: 'Licence check · POST /api/validate',
        when: 'With an installed certificate, at most once every 20 hours.',
        carries: 'Licence ID, certificate serial, and device fingerprint.',
        never: 'No telemetry. Unsigned responses are ignored.',
      },
      {
        call: 'Update · POST /api/download/link, then the file',
        when: 'When you tap install on a newer build.',
        carries: 'The platform name, then the build file over a signed link.',
        never: 'No key or fingerprint.',
      },
      {
        call: 'Computer update check · GET /api/desktop/update',
        when: 'In the computer app only, 45 seconds after launch.',
        carries: 'OS target, processor architecture and installed version in the URL. Updates require a valid built-in signature.',
        never: 'No licence key, fingerprint or account.',
      },
      {
        call: 'Google Play purchase · POST /api/play/verify',
        when: 'In the Google Play build, after payment or when tapping Restore purchase.',
        carries: 'Product ID and Google Play purchase token to issue a licence key.',
        never: 'No fingerprint, name or email.',
      },
      {
        call: 'Finding a person · GET /directory/lookup, POST and DELETE /directory/publish',
        when: 'When publishing your name or looking up an exact name.',
        carries: 'Name hash, invitation payload, and signature.',
        never: 'No plain-text name and no downloadable directory list.',
      },
      {
        call: 'Waiting letters · POST /relay/v2/batch',
        when: 'To drop or collect queued letters: every 10 minutes while the live socket is up, every 45 seconds otherwise, and every 15 minutes in the background.',
        carries: 'Sealed letters and 64 mailbox tags (real tags padded with decoys).',
        never: 'No licence key, cookie, auth header, content or names. The server sees source IP addresses and daily per-pair mailbox tags.',
      },
      {
        call: 'Waiting letters, older route · POST /relay/drop, /relay/fetch, /relay/burn',
        when: 'Fallback when the server does not support the batch route above.',
        carries: 'Same payload as batch, one letter per request.',
        never: 'Same as the batch route above.',
      },
      {
        call: 'Checking the drop · GET /relay/v2/health',
        when: 'On relay start, network change, or app resume.',
        carries: 'Nothing: a bare GET with no body, key or fingerprint.',
        never: 'Nothing beyond the source IP address.',
      },
      {
        call: 'Live connection · /relay/v2/live (WebSocket)',
        when: 'Held open to /relay/v2/live while you have contacts, reconnecting with up to a one-minute backoff.',
        carries: 'The 64 mailbox tags, arriving ciphertext letters, and ephemeral call-setup signals that are never stored.',
        never: 'No licence key or auth header. The server sees connection IP and duration.',
      },
      {
        call: 'What is happening here · GET /truth/area, POST /truth/observe',
        when: 'When viewing a place on the map or submitting a field report.',
        carries: 'Area query: a ~3 km cell code. Report: a sub-kilometre cell code, fact, and device key signature.',
        never: 'No exact coordinates or name. Key-to-report links expire after two fact half-lives (4 hours to 14 days).',
      },
      {
        call: 'Air-alert snapshot · GET /api/alerts/snapshot',
        when: 'When an air alert starts in your region, and nightly on unmetered Wi-Fi while charging.',
        carries: 'Nothing: a bare GET returning a signed 92-byte snapshot verified on the phone.',
        never: 'No location or selected region.',
      },
      {
        call: 'A call · STUN and TURN on our machine',
        when: 'When starting or answering a voice or video call.',
        carries: 'ICE connection candidates (external IP and port). Media is encrypted end-to-end with DTLS-SRTP.',
        never: 'No media keys. When relayed through TURN, the server sees participant IPs and call duration, not content.',
      },
      {
        call: 'Licence by wallet transfer · GET /api/solana/offer, POST /api/solana/claim',
        when: 'In the sideload build only, when checking the price and claiming a licence by wallet transfer.',
        carries: 'One-time reference key, Solana transfer signature, and signed proof.',
        never: 'No device fingerprint, account or messenger identity.',
      },
      {
        call: 'Seeker month and badge · GET /api/solana/seeker/nonce, POST /api/solana/seeker/claim, POST /api/solana/seeker/badge',
        when: 'Only when tapping Check for Seeker or Refresh Seeker badge in the wallet.',
        carries: 'Wallet address, signed Sign-In-With-Solana message and messenger ID. The server checks mainnet for the Seeker token and SKR stake.',
        never: 'No device fingerprint, messages, private keys or transactions. The badge stores stake tier, not amount.',
      },
      {
        call: 'Wallet on the Solana network · GET /api/solana/config, POST /api/solana/rpc',
        when: 'When the wallet reads or writes the network: balances, history, transfers, sending, test SOL. The address names the network; mainnet is read-only through us. If we refuse, the app asks a public Solana node directly.',
        carries: 'One standard Solana request: method and parameters, wallet and token addresses, and the signed transaction when you send. Our server passes it to a Solana node under our key; the node sees our server, not your address.',
        never: 'No licence key, fingerprint or messenger identity. The log keeps only network, method and response code. Your IP stays in memory for minutes for the rate limit, and until day end for test SOL.',
      },
      {
        call: 'Network fee paid by us · GET /api/solana/sponsor, POST /api/solana/sponsor/sign',
        when: 'On balance refresh, and when sending tokens from the built-in wallet on the test network.',
        carries: 'Your signed token transfer transaction and wallet address so our server can co-sign as fee payer.',
        never: 'No licence key, fingerprint or messenger ID. The server logs wallet address, IP and timestamp to enforce daily limits.',
      },
      {
        call: 'Test coins on the test network · POST /api/solana/faucet',
        when: 'Only when you press "Get test USDC" in the wallet on a test network. Opening the wallet sends nothing.',
        carries: 'Your wallet address and a code made from your messenger ID by a one-way hash, so the server never sees the ID itself. Our server sends 20 test USDC to that address.',
        never: 'No licence key, fingerprint or messenger identity. For the weekly limit the server keeps the wallet address, hash code, IP and payout time, and does not delete them. The coins have no money value.',
      },
      {
        call: 'Paid right away · POST /api/solana/watch, GET /api/solana/seen',
        when: 'Only while a transfer that pays a bill share or a request waits for the network.',
        carries: 'A one-time code made from the bill ID by a one-way hash — the same code the transfer itself carries on the network. The answer only says the network saw a transfer with that code, and its signature.',
        never: 'No wallet address, no amount, no messenger identity. Codes stay in memory for at most 24 hours and are not written down. To be told about them, our server hands the codes to Helius, the Solana node provider we use.',
      },
      {
        call: 'Assistant without your own key · POST /api/brain/attest, GET /api/brain/status, POST /api/brain/v1beta/models/{model}:streamGenerateContent',
        when: 'When you have not entered a Gemini key and no paired computer answers. If the status cannot be read, the app sends the question anyway. A key you entered is never replaced by ours.',
        carries: 'Prompt, attachments, context (location, today’s calendar), install ID, and device public key registered once via POST /api/brain/attest.',
        never: 'No prompt or answer text is logged by our server. Requests go to Google under our key; device key and daily count are stored.',
      },
      {
        call: 'Voice call · POST /api/brain/live-token',
        when: 'When you start a voice conversation with the assistant and have not entered your own Gemini key.',
        carries: 'An empty request signed by the device key. Our server answers with a one-time Google token that works for 30 minutes and must be used within one minute.',
        never: 'Audio and text never pass through us; they go from the phone to Google. We log a short device ID, status and time, and count calls per day: 20 per device, 400 in total.',
      },
      {
        call: 'Everything else',
        when: 'Never.',
        carries: '—',
        never: 'No analytics endpoint, crash reporter or usage counter.',
      },
    ],
    footnote:
      'External services called directly by the app: OpenFreeMap (tiles), Photon and Nominatim (search), Overpass and Wikipedia (places), Open-Meteo (weather), public alert feeds, Hugging Face and Alpha Cephei (voice models), and Google Gemini under your key or our token.',
  },

  zeroConfig: {
    eyebrow: 'Setup',
    title: 'No sign-up, phone number or server address.',
    lead: 'No sign-up or server setup is required. Only a personal Gemini key is optional.',
    points: [
      {
        title: 'No sign-up or phone number',
        desc: 'Contacts are added via QR code or invitation link, without tying your identity to an email or phone number.',
      },
      {
        title: 'Optional assistant key',
        desc: 'You can enter your own Gemini key, stored in Android Keystore, or use our server proxy while available.',
      },
      {
        title: 'Automatic local computer discovery',
        desc: 'Pairing discovers your computer on the local network and verifies via a code on its screen, without typing IP addresses.',
      },
      {
        title: 'Map tiles and local packs',
        desc: 'The map loads tiles over the network by default, or reads country map files added to the phone manually.',
      },
    ],
  },

  licence: {
    eyebrow: 'The licence',
    title: 'Licence verification happens on the device.',
    lead: 'Entitlement is proven by an Ed25519 signature checked on the device in four steps:',
    steps: [
      {
        k: '01',
        title: 'Signed once',
        desc: 'The licence server signs an Ed25519 certificate containing tier, serial number, update window, and device fingerprint.',
      },
      {
        k: '02',
        title: 'Checked on the device',
        desc: 'The app verifies the signature against the public key embedded in the build, ignoring any key sent in the response.',
      },
      {
        k: '03',
        title: 'Tied to one device',
        desc: 'The fingerprint is a SHA-256 hash of ANDROID_ID on the phone, or machine ID and SoC serial on the computer.',
      },
      {
        k: '04',
        title: 'Grace window between checks',
        desc: 'Revalidation is allowed to fail. The paid tier remains active throughout the grace window from the last check.',
      },
    ],
    facts: {
      trialLabel: 'days of trial before entering a key',
      graceLabel: 'days between checks before the paid tier steps down',
      freeLabel: 'core features that never need a licence',
    },
    gateTitle: 'Core features are never behind payment',
    gateLead:
      'These stay open with no key, after the update window ends, and when the licence server is unreachable:',
    freeLabels: {
      'core.chat': 'Assistant and chat',
      'core.map': 'Map',
      'core.nav': 'Navigation',
      'core.alerts': 'Alerts and warnings',
      'core.vault': 'Vault',
      'core.voice': 'Voice',
    },
    gateNote:
      'Gated features return HTTP 402 with the feature name instead of blocking the app. On the computer, only bridge pairing and mission export are gated; development builds do not enforce limits.',
  },

  pairing: {
    eyebrow: 'Phone and computer',
    title: 'Paired in person, not through an account.',
    lead: 'No sign-up or email. Physical proximity to the computer screen establishes the pair.',
    steps: [
      {
        k: '01',
        title: 'Three-minute window',
        desc: 'The computer displays a QR code with a one-time X25519 public key and random nonce. The session lives in memory for three minutes, and the private key never touches disk.',
      },
      {
        k: '02',
        title: 'Shared derived key',
        desc: 'ECDH and HKDF-SHA256, salted with the nonce from the code, derive the same 32-byte key on both sides.',
      },
      {
        k: '03',
        title: 'Single-use HMAC proof',
        desc: 'The phone returns an HMAC over the pairing ID and its long-term public key, verified in constant time. Each session is single-use.',
      },
      {
        k: '04',
        title: 'Revocable token',
        desc: 'The phone stores a Keystore-wrapped device token and an Ed25519 renewal key. Renewals cap at 30 days, and device revocation invalidates the token immediately.',
      },
    ],
    note: 'The Android app and the computer implement the exact same byte-level protocol.',
  },

  peer: {
    eyebrow: 'Phone to phone',
    title: 'Direct and queued messages between phones.',
    lead: 'Phones on the same Wi-Fi network connect directly. Otherwise, encrypted messages wait in the server queue.',
    points: [
      {
        title: 'Derived pair encryption key',
        desc: 'Each letter is signed with Ed25519 and sealed with AES-256-GCM using a 256-bit key derived via X25519 ECDH and HKDF-SHA256. Letter ID, sender and recipient are bound as AAD.',
      },
      {
        title: '48-digit verification code',
        desc: 'The verification code joins two 24-digit key fingerprints. Any key change clears the verified mark and warns the user. It rules out an active middleman only when keys are scanned via camera in person.',
      },
      {
        title: 'Plaintext sending is blocked',
        desc: 'Invitations without an encryption key are rejected. Contacts missing a key cannot be messaged, and unsealed frames from older builds are dropped on arrival.',
      },
      {
        title: 'Tested on captured socket bytes',
        desc: 'End-to-end tests capture raw socket bytes and verify that message plaintext never appears on the wire.',
      },
    ],
    caveatTitle: 'What this does not protect',
    caveat:
      'Envelope metadata (IDs, lengths, timing) is visible on the network. Remote key exchange does not stop an active middleman. Ephemeral X25519 forward secrecy applies only after two phones meet live on one Wi-Fi network, not to server-queued messages.',
  },

  servers: {
    eyebrow: 'Our servers',
    title: 'Two small servers and their stored state.',
    lead: 'What runs and what is stored on our side.',
    points: [
      {
        title: 'Licence keys stored as hashes',
        desc: 'The licence server stores only the SHA-256 hash of each key, never the raw key itself.',
      },
      {
        title: 'Stored licence and relay records',
        desc: 'The server stores licence tier, purchase email, activated device fingerprints, and activation logs with IP addresses. Queued encrypted letters expire after seven days.',
      },
      {
        title: 'Rate limits and webhook signatures',
        desc: 'Per-IP rate limits cap licence routes at 20 requests/min, legacy relay and truth routes at 120/min, and batch relay at 300/min. Admin tokens use constant-time comparison; webhooks require HMAC verification.',
      },
      {
        title: 'Static site headers',
        desc: 'This site is a static build with framing, MIME sniffing, geolocation, microphone and camera blocked via HTTP headers, and no analytics scripts.',
      },
    ],
  },

  posture: {
    eyebrow: 'Current status',
    title: 'What is implemented and what is not.',
    lead: 'Implemented controls alongside known gaps.',
    haveTitle: 'Implemented today',
    have: [
      'Local Ed25519 licence check against a public key built into the app',
      'Phone-to-computer pairing with X25519, HKDF-SHA256 and HMAC, byte-compatible on both sides',
      'Messages between phones sealed with AES-256-GCM under a per-pair X25519 + HKDF-SHA256 key, verified on socket bytes',
      '48-digit verification code from both keys that clears verification whenever either key changes',
      'Device tokens with a 30-day cap and instant revocation',
      'Computer AES-256-GCM vault with per-user keys and field-level AAD binding',
      'Computer roles (ROOT, OPERATOR, GUEST) with self-or-root access checks',
      'Computer sign-in lockout after five failed attempts in a minute',
      'Core features never behind payment: a signed policy only adds to the free set, never subtracts',
    ],
    notYetTitle: 'Not done',
    notYet: [
      'No external security audit has been performed on this codebase.',
      'No certification or compliance attestation (no ISO 27001, no SOC 2).',
      'No published penetration test report.',
      'No bug bounty programme.',
      'Matching digits on a pasted key only confirm both sides hold the same key, not identity.',
      'The 48-digit fingerprint check proves identity only when the key was scanned via camera from a live screen.',
      'Server-queued messages use the standing pair key without forward secrecy; live Wi-Fi forward secrecy starts after the first meeting.',
      'Sender, recipient, timing and message size are visible to an observer on the same network.',
      'Direct phone-to-phone delivery works only on the same Wi-Fi network, without Wi-Fi Direct or multi-hop relay through a third phone.',
      'Remote delivery depends on our relay server, which sees message size and client IP addresses.',
      'On the computer, rotating the operator secret is a manual migration, and losing both database and secret exposes vault data.',
    ],
  },

  report: {
    eyebrow: 'Vulnerability disclosure',
    title: 'Report a security issue.',
    lead: 'Send steps to reproduce, what happened, and what you expected. We reply and document fixes.',
    cta: 'Report a vulnerability',
    mailSubject: 'Nightpost security report',
    addressLabel: 'Or write directly to',
    note: 'There is no paid bounty programme. We provide a direct reply and changelog credit if requested.',
  },

  ui: {
    limitLabel: 'Design boundary',
  },

  cta: {
    title: 'Verify the release build yourself.',
    sub: 'The Android APK is signed with the release key, and its SHA-256 hash is published from the signed server policy on the download page.',
    primary: 'Download for Android',
    secondary: 'Request a demo',
  },
};

const uk: SecurityCopy = {
  meta: {
    title: 'Безпека та дані — Nightpost',
    description:
      'Де зберігаються дані, кожен запит застосунку до наших серверів і чого ще не зроблено: без зовнішнього аудиту, сертифікації та програми винагород.',
  },
  hero: {
    eyebrow: 'Безпека та дані',
    title: 'Що виходить із телефона, а що лишається.',
    sub: 'Де лежать дані, кожен запит застосунку до наших серверів і чого ще не зроблено.',
    chips: [
      'Без реєстрації й номера телефона',
      'Наскрізне шифрування листів',
      'Усі запити до серверів у таблиці',
      'Локальна перевірка ліцензії Ed25519',
      'Без зовнішнього аудиту безпеки',
    ],
  },

  threats: {
    eyebrow: 'Коротко',
    title: 'Що захищено, а що ні.',
    lead: 'Короткий зміст меж, описаних нижче.',
    safeTitle: 'Захищено',
    safe: [
      {
        title: 'Зміст листів від нашого сервера',
        desc: 'Лист шифрується на телефоні. Сервер зберігає шифротекст і не має ключа розшифрування.',
      },
      {
        title: 'Зміст листів у мережі',
        desc: 'У спільній Wi-Fi-мережі чи дорогою до сервера передається шифротекст, що перевірено тестами на знятих байтах зʼєднання.',
      },
      {
        title: 'Тотожність контакту при звірці QR',
        desc: 'Зчитування ключа камерою з екрана і збіг 48 цифр підтверджують ключ. Зміна ключа знімає позначку звірки.',
      },
      {
        title: 'Злив переліку людей',
        desc: 'Номера телефона немає. Вхід на сайті створює обліковий запис із ключа пристрою. Переліку людей сервер не тримає: лише хеші імен, які люди опублікували самі.',
      },
      {
        title: 'Підрахунок дій',
        desc: 'Немає аналітики, збирача збоїв і лічильника використання.',
      },
    ],
    unsafeTitle: 'Не захищено',
    unsafe: [
      {
        title: 'Розблокований телефон',
        desc: 'Хто тримає розблокований телефон, може прочитати збережені на ньому розмови.',
      },
      {
        title: 'IP-адреса і час',
        desc: 'Сервер бачить, з якої IP-адреси поклали чи забрали лист, і коли саме.',
      },
      {
        title: 'Листи в черзі на сервері',
        desc: 'Листи в черзі запечатані сталим ключем пари без forward secrecy; вилучення ключа дозволить відкрити перехоплені раніше листи.',
      },
      {
        title: 'Метадані дзвінка через ретранслятор',
        desc: 'Коли дзвінок іде через наш TURN-сервер, звук зашифрований, але сервер бачить учасників і тривалість.',
      },
      {
        title: 'Ключ, переданий дистанційно',
        desc: 'Якщо ключ прийшов посиланням або через інший месенджер, збіг цифр не виключає активного посередника.',
      },
      {
        title: 'Зовнішній аудит коду',
        desc: 'Незалежного аудиту безпеки цього коду ще не проводили.',
      },
    ],
  },

  residency: {
    eyebrow: 'Зберігання даних',
    title: 'На пристрої, крім двох винятків.',
    lead: 'Розмови, контакти, збережені місця й історія лишаються на пристрої. Два винятки: листи в черзі та помічник на телефоні.',
    points: [
      {
        title: 'Листи шифруються на телефоні',
        desc: 'Лист шифрується до відправлення, і відкрити його може лише отримувач. Недоставлені листи чекають на нашому сервері до семи днів у зашифрованому вигляді.',
      },
      {
        title: 'Помічник на телефоні працює через Gemini',
        desc: 'З вашим ключем запити йдуть напряму до Google Gemini; без нього — через наш сервер без запису тексту. Розпізнавання мовлення локальне після завантаження моделі на 137 МБ.',
      },
      {
        title: 'Сейф на компʼютері зашифровано',
        desc: 'Кожне поле сейфа на компʼютері шифрується AES-256-GCM ключем користувача з привʼязкою до картки й поля. Особисті поля шифруються до запису в базу.',
      },
      {
        title: 'Без обліку використання',
        desc: 'Немає аналітики, збирача збоїв чи лічильника запитів, і плата за окремі запити не стягується.',
      },
    ],
    caveat:
      'На компʼютері викрадення бази даних разом з операторським секретом дозволяє розшифрувати поля. Зміна цього секрету зараз потребує ручної міграції.',
  },

  egress: {
    eyebrow: 'Мережеві запити',
    title: 'Кожен запит до наших серверів.',
    lead: 'Усі запити застосунку до наших серверів перелічено нижче з точними шляхами. Сторонні сервіси вказано під таблицею.',
    columns: {
      call: 'Запит',
      when: 'Коли',
      carries: 'Що несе',
      never: 'Чого не несе',
    },
    rows: [
      {
        call: 'Активація · POST /api/activate',
        when: 'Один раз під час введення ключа ліцензії.',
        carries: 'Ключ, відбиток пристрою SHA-256 та марку й модель телефона.',
        never: 'Ні змісту, ні запитань, ні геоданих, ні лічильників.',
      },
      {
        call: 'Звільнити пристрій · POST /api/deactivate',
        when: 'Коли ви самі звільняєте місце в ліцензії.',
        carries: 'Ключ і той самий відбиток SHA-256.',
        never: 'Нічого іншого.',
      },
      {
        call: 'Видалити все · POST /api/erase/claim, POST /api/erase, POST /api/erase/cancel',
        when: 'Лише коли ви самі обираєте «Видалити все».',
        carries: 'Два відкриті ключі у двох окремих запитах, кожен зі своїм підписом.',
        never: 'Нічого іншого.',
      },
      {
        call: 'Політика · GET /api/policy',
        when: 'При запуску і кожні 12 годин на вільному рівні, або раз на 20 годин зі встановленою ліцензією.',
        carries: 'Нічого: голий GET без заголовка авторизації, ключа чи відбитка.',
        never: 'Нічого, крім IP-адреси відправника.',
      },
      {
        call: 'Перевірка ліцензії · POST /api/validate',
        when: 'Зі встановленим сертифікатом, не частіше ніж раз на 20 годин.',
        carries: 'Ідентифікатор ліцензії, серійний номер сертифіката й відбиток пристрою.',
        never: 'Жодної телеметрії. Непідписані відповіді ігноруються.',
      },
      {
        call: 'Оновлення · POST /api/download/link, далі сам файл',
        when: 'Коли ви натискаєте встановлення новішої збірки.',
        carries: 'Назву платформи, а далі сам файл за підписаним посиланням.',
        never: 'Ні ключа, ні відбитка.',
      },
      {
        call: 'Оновлення компʼютера · GET /api/desktop/update',
        when: 'Лише в застосунку для компʼютера, через 45 секунд після запуску.',
        carries: 'Операційну систему, архітектуру процесора й поточну версію в адресі. Оновлення перевіряється вшитим ключем підпису.',
        never: 'Ні ключа ліцензії, ні відбитка, ні акаунта.',
      },
      {
        call: 'Покупка в Google Play · POST /api/play/verify',
        when: 'У збірці з Google Play після оплати або натискання «Відновити покупку».',
        carries: 'Ідентифікатор товару й токен покупки Google Play для видачі ключа ліцензії.',
        never: 'Ні відбитка, ні імені, ні пошти.',
      },
      {
        call: 'Знайти людину · GET /directory/lookup, POST і DELETE /directory/publish',
        when: 'Під час публікації свого імені або пошуку за точним іменем.',
        carries: 'Хеш імені, запрошення та підпис.',
        never: 'Ні відкритого імені, ні загального списку людей.',
      },
      {
        call: 'Листи, що чекають · POST /relay/v2/batch',
        when: 'Для відправлення й забору листів у черзі: кожні 10 хвилин за активного живого зʼєднання, інакше кожні 45 секунд, у фоні — кожні 15 хвилин.',
        carries: 'Запечатані листи та рівно 64 теги скриньок (справжні разом із випадковими).',
        never: 'Ні ключа ліцензії, ні куки, ні авторизації, ні змісту. Сервер бачить лише IP-адреси та добові теги скриньок.',
      },
      {
        call: 'Листи, старий шлях · POST /relay/drop, /relay/fetch, /relay/burn',
        when: 'Запасний шлях, якщо сервер не підтримує пакетний маршрут вище.',
        carries: 'Те саме, по одному листу за запит.',
        never: 'Те саме, що в пакетному маршруті вище.',
      },
      {
        call: 'Перевірка сховку · GET /relay/v2/health',
        when: 'Під час старту ретранслятора, зміни мережі або повернення в застосунок.',
        carries: 'Нічого: голий GET без тіла, ключа чи відбитка.',
        never: 'Нічого, крім IP-адреси відправника.',
      },
      {
        call: 'Живе зʼєднання · /relay/v2/live (WebSocket)',
        when: 'Тримається відкритим до /relay/v2/live за наявності контактів, перепідключаючись із паузою до хвилини.',
        carries: '64 теги скриньок, зашифровані листи та короткі сигнали встановлення дзвінка без збереження.',
        never: 'Ні ключа ліцензії, ні авторизації. Сервер бачить IP-адресу й тривалість зʼєднання.',
      },
      {
        call: 'Що тут зараз · GET /truth/area, POST /truth/observe',
        when: 'Під час перегляду місця на мапі або надсилання власного підтвердження факту.',
        carries: 'Запит: код ділянки ~3 км. Звіт: код клітинки менше кілометра, факт і підпис ключа телефона.',
        never: 'Ні точних координат, ні імені. Звʼязок ключа зі звітом стирається за два напівперіоди (4 години — 14 днів).',
      },
      {
        call: 'Знімок тривог · GET /api/alerts/snapshot',
        when: 'На початку тривоги у вашій області та щоночі на безлімітному Wi-Fi під час зарядки.',
        carries: 'Нічого: голий GET, що повертає підписаний 92-байтний знімок, який телефон перевіряє сам.',
        never: 'Ні координат, ні вибраної області.',
      },
      {
        call: 'Дзвінок · STUN і TURN на нашій машині',
        when: 'Під час початку або прийому голосового чи відеодзвінка.',
        carries: 'Кандидати зʼєднання (зовнішня IP-адреса й порт). Потік звуку та відео зашифрований DTLS-SRTP.',
        never: 'Жодних ключів потоку. Через TURN сервер бачить IP-адреси учасників і тривалість, але не зміст.',
      },
      {
        call: 'Ліцензія за переказ із гаманця · GET /api/solana/offer, POST /api/solana/claim',
        when: 'Лише в окремій збірці під час запиту ціни та отримання ліцензії за переказ із гаманця.',
        carries: 'Одноразовий ключ-позначку, підпис переказу в Solana та підписаний доказ.',
        never: 'Ні відбитка пристрою, ні акаунта, ні особи в месенджері.',
      },
      {
        call: 'Місяць і знак за Seeker · GET /api/solana/seeker/nonce, POST /api/solana/seeker/claim, POST /api/solana/seeker/badge',
        when: 'Лише під час натискання перевірки або оновлення знака Seeker у гаманці.',
        carries: 'Адресу гаманця, підписане повідомлення Sign-In-With-Solana та ID у месенджері. Сервер перевіряє Seeker-токен і стейк SKR у mainnet.',
        never: 'Ні відбитка пристрою, ні листів, ні приватних ключів. У знаку вказано лише рівень стейку, не суму.',
      },
      {
        call: 'Гаманець у мережі Solana · GET /api/solana/config, POST /api/solana/rpc',
        when: 'Щоразу, коли гаманець читає чи пише в мережу: баланси, історія, перекази, надсилання, тестові SOL. Адреса називає мережу; у mainnet ми лише читаємо. Якщо ми відмовили, застосунок питає публічний вузол Solana напряму.',
        carries: 'Один звичайний запит Solana: метод і параметри, адреси гаманців і токенів, а під час надсилання — підписана транзакція. Наш сервер передає його вузлу Solana під нашим ключем; вузол бачить наш сервер, а не вашу адресу.',
        never: 'Ні ліцензійного ключа, ні відбитка, ні особи в месенджері. У журналі лише мережа, метод і код відповіді. IP лишається в памʼяті кілька хвилин для ліміту, а для тестових SOL — до кінця доби.',
      },
      {
        call: 'Комісію мережі платимо ми · GET /api/solana/sponsor, POST /api/solana/sponsor/sign',
        when: 'Під час оновлення балансу та надсилання токенів із вбудованого гаманця в тестовій мережі.',
        carries: 'Підписану вами транзакцію переказу й адресу гаманця для додавання підпису платника комісії.',
        never: 'Ні ключа ліцензії, ні відбитка, ні ID месенджера. Сервер записує гаманець, IP і час для добового ліміту.',
      },
      {
        call: 'Тестові монети в тестовій мережі · POST /api/solana/faucet',
        when: 'Лише коли ви натискаєте «Отримати тестові USDC» у гаманці на тестовій мережі. Саме відкриття гаманця нічого не надсилає.',
        carries: 'Адресу вашого гаманця і код, зроблений із вашого ідентифікатора в месенджері односторонньою згорткою, — самого ідентифікатора сервер не бачить. Наш сервер надсилає 20 тестових USDC на цю адресу.',
        never: 'Ні ліцензійного ключа, ні відбитка, ні особи в месенджері. Для тижневого ліміту сервер тримає адресу гаманця, код згортки, IP і час виплати й не стирає їх. Ці монети грошової вартості не мають.',
      },
      {
        call: 'Сплачено одразу · POST /api/solana/watch, GET /api/solana/seen',
        when: 'Лише поки переказ, що платить частку рахунку чи прохання, чекає на мережу.',
        carries: 'Одноразовий код, зроблений з id рахунку односторонньою функцією, — той самий, що несе в мережі сам переказ. Відповідь каже лише, що мережа побачила переказ із цим кодом, і його підпис.',
        never: 'Ні адреси гаманця, ні суми, ні особи в месенджері. Коди живуть у памʼяті щонайбільше 24 години й ніде не записуються. Щоб дізнатися про них, наш сервер передає коди Helius — постачальнику вузлів Solana, яким ми користуємося.',
      },
      {
        call: 'Помічник без власного ключа · POST /api/brain/attest, GET /api/brain/status, POST /api/brain/v1beta/models/{model}:streamGenerateContent',
        when: 'Коли ви не вписали ключ Gemini і парний компʼютер не відповідає. Якщо статус прочитати не вдалося, застосунок усе одно надсилає запит. Ваш ключ наш не підміняє.',
        carries: 'Текст запиту, вкладення, контекст (місце, події на сьогодні), номер установки й публічний ключ пристрою (реєструється через POST /api/brain/attest).',
        never: 'Наш сервер не логує текст запитів і відповідей. Запит іде до Google під нашим ключем; зберігаються ключ пристрою й добовий лічильник.',
      },
      {
        call: 'Голосова розмова · POST /api/brain/live-token',
        when: 'Коли ви починаєте голосову розмову з помічником і не вписали власний ключ Gemini.',
        carries: 'Порожній запит, підписаний ключем пристрою. Наш сервер відповідає одноразовим токеном Google: він діє 30 хвилин і має бути використаний протягом хвилини.',
        never: 'Звук і текст не проходять через нас: вони йдуть з телефона до Google. Ми пишемо короткий ID пристрою, код і час та рахуємо виклики за добу: 20 на пристрій, 400 разом.',
      },
      {
        call: 'Усе інше',
        when: 'Ніколи.',
        carries: '—',
        never: 'Ні адреси аналітики, ні збирача збоїв, ні лічильника використання.',
      },
    ],
    footnote:
      'Зовнішні сервіси, до яких застосунок звертається напряму: OpenFreeMap (тайли), Photon і Nominatim (пошук), Overpass і Вікіпедія (місця), Open-Meteo (погода), публічні стрічки тривог, Hugging Face і Alpha Cephei (моделі мовлення) та Google Gemini з вашим ключем або нашим токеном.',
  },

  zeroConfig: {
    eyebrow: 'Початок роботи',
    title: 'Без реєстрації, номера й адреси сервера.',
    lead: 'Реєстрація чи налаштування сервера не потрібні. За бажанням можна вказати лише власний ключ Gemini.',
    points: [
      {
        title: 'Без реєстрації й номера',
        desc: 'Контакти додаються через QR-код або посилання-запрошення без привʼязки до пошти чи номера телефона.',
      },
      {
        title: 'Необовʼязковий ключ помічника',
        desc: 'Власний ключ Gemini зберігається під захистом Android Keystore; без нього помічник працює через наш сервер.',
      },
      {
        title: 'Автоматичний пошук компʼютера',
        desc: 'Телефон сам знаходить компʼютер у локальній мережі й підтверджує пару за кодом з екрана без введення IP-адреси.',
      },
      {
        title: 'Тайли мапи та локальні файли',
        desc: 'Мапа за замовчуванням завантажує тайли з мережі або читає файли мап, додані на телефон вручну.',
      },
    ],
  },

  licence: {
    eyebrow: 'Ліцензія',
    title: 'Перевірка ліцензії виконується на пристрої.',
    lead: 'Право на рівень підтверджується цифровим підписом на пристрої за чотири кроки:',
    steps: [
      {
        k: '01',
        title: 'Підпис один раз',
        desc: 'Сервер ліцензій підписує сертифікат Ed25519: рівень, серійний номер, вікно оновлень і відбиток конкретного пристрою.',
      },
      {
        k: '02',
        title: 'Перевірка на пристрої',
        desc: 'Застосунок звіряє підпис із публічним ключем, вшитим у збірку, ігноруючи будь-який ключ із відповіді.',
      },
      {
        k: '03',
        title: 'Привʼязка до пристрою',
        desc: 'Відбиток — це SHA-256 від ANDROID_ID на телефоні або від machine-id та серійного номера процесора на компʼютері.',
      },
      {
        k: '04',
        title: 'Пільгове вікно між перевірками',
        desc: 'Повторній перевірці дозволено не вдатися. Платний рівень діє протягом пільгового строку від останньої вдалої перевірки.',
      },
    ],
    facts: {
      trialLabel: 'днів пробного періоду до введення ключа',
      graceLabel: 'днів між перевірками до переходу на вільний рівень',
      freeLabel: 'основних функцій, яким ніколи не потрібна ліцензія',
    },
    gateTitle: 'Основні функції ніколи не за оплатою',
    gateLead:
      'Ці можливості відкриті без ключа, після закінчення вікна оновлень і за недоступного сервера ліцензій:',
    freeLabels: {
      'core.chat': 'Помічник і чат',
      'core.map': 'Мапа',
      'core.nav': 'Навігація',
      'core.alerts': 'Тривоги й попередження',
      'core.vault': 'Сейф',
      'core.voice': 'Голос',
    },
    gateNote:
      'Закрита функція повертає код 402 і свою назву, не блокуючи роботу. На компʼютері так обмежено лише парування мосту й експорт місії; у розробницьких збірках обмеження вимкнено.',
  },

  pairing: {
    eyebrow: 'Телефон і компʼютер',
    title: 'Парування наживо, а не через акаунт.',
    lead: 'Без реєстрації та пошти. Пару встановлює фізичний доступ до екрана компʼютера.',
    steps: [
      {
        k: '01',
        title: 'Трихвилинне вікно',
        desc: 'Компʼютер показує QR-код з одноразовим публічним ключем X25519 і випадковим числом. Сесія живе в памʼяті три хвилини, а приватний ключ не записується на диск.',
      },
      {
        k: '02',
        title: 'Спільний виведений ключ',
        desc: 'ECDH та HKDF-SHA256 із сіллю з коду виводять однаковий 32-байтний ключ на обох пристроях.',
      },
      {
        k: '03',
        title: 'Одноразовий доказ HMAC',
        desc: 'Телефон повертає HMAC над ідентифікатором парування й власним довгостроковим публічним ключем; звірка виконується за сталий час, сесія одноразова.',
      },
      {
        k: '04',
        title: 'Токен із відкликанням',
        desc: 'Телефон зберігає токен пристрою під захистом Keystore та ключ Ed25519 для поновлення. Поновлення діють щонайбільше 30 днів, а відкликання миттєво скасовує токен.',
      },
    ],
    note: 'Застосунок Android і компʼютер реалізують цей обмін байт у байт за одним протоколом.',
  },

  peer: {
    eyebrow: 'Між телефонами',
    title: 'Прямі та чергові листи між телефонами.',
    lead: 'Телефони в одній мережі Wi-Fi зʼєднуються напряму. Інакше зашифрований лист чекає в черзі на сервері.',
    points: [
      {
        title: 'Виведений ключ шифрування пари',
        desc: 'Лист підписується Ed25519 і запечатується AES-256-GCM під 256-бітним ключем, виведеним через X25519 ECDH та HKDF-SHA256. ID листа, відправник і отримувач привʼязані в AAD.',
      },
      {
        title: '48-значний код звірки',
        desc: 'Код звірки складається з двох 24-значних відбитків ключів. Зміна ключа знімає позначку звірки й показує попередження. Захист від активного посередника діє лише при зчитуванні ключа камерою наживо.',
      },
      {
        title: 'Відкритий текст заблоковано',
        desc: 'Запрошення без ключа шифрування відхиляються. Контакту без ключа неможливо написати, а незапечатані кадри старих збірок відкидаються на вході.',
      },
      {
        title: 'Перевірено на байтах сокета',
        desc: 'Наскрізний тест перехоплює сирі байти сокета й перевіряє відсутність відкритого тексту листа в мережі.',
      },
    ],
    caveatTitle: 'Чого це не захищає',
    caveat:
      'Метадані конверта (ID, розмір, час) видно в мережі. Дистанційний обмін ключами не захищає від активного посередника. Тимчасові ключі X25519 (forward secrecy) діють лише під час прямої зустрічі телефонів у спільній Wi-Fi-мережі, а не для черги сервера.',
  },

  servers: {
    eyebrow: 'Наші сервери',
    title: 'Два невеликі сервери та збережені дані.',
    lead: 'Що працює і що саме зберігається на нашому боці.',
    points: [
      {
        title: 'Ключі ліцензій зберігаються хешами',
        desc: 'Сервер ліцензій зберігає лише SHA-256 хеш ключа, а не сам ключ.',
      },
      {
        title: 'Записи ліцензій і ретранслятора',
        desc: 'Зберігаються рівень ліцензії, пошта покупки, відбитки активованих пристроїв і журнал подій з IP-адресами. Зашифровані листи в черзі зберігаються до семи днів.',
      },
      {
        title: 'Ліміти запитів і підписи вебхуків',
        desc: 'Ліміти на IP: 20 запитів/хв для ліцензій, 120/хв для старих маршрутів листів і мапи, 300/хв для пакетного маршруту. Адмін-токени звіряються за сталий час, вебхуки вимагають HMAC.',
      },
      {
        title: 'Заголовки цього сайту',
        desc: 'Сайт є статичною збіркою із забороною фреймів, MIME-сніфінгу, геолокації, мікрофона й камери в HTTP-заголовках і без скриптів аналітики.',
      },
    ],
  },

  posture: {
    eyebrow: 'Поточний стан',
    title: 'Що реалізовано, а чого ще немає.',
    lead: 'Наявні механізми захисту та відомі обмеження.',
    haveTitle: 'У коді сьогодні',
    have: [
      'Локальна перевірка ліцензії Ed25519 публічним ключем, вшитим у застосунок',
      'Парування телефона з компʼютером через X25519 + HKDF-SHA256 + HMAC, сумісне побайтово',
      'Листи між телефонами запечатані AES-256-GCM під ключем пари з X25519 + HKDF-SHA256, перевіреним на байтах сокета',
      '48-значний код звірки з обох ключів, що скидає позначку при зміні будь-якого ключа',
      'Токени пристрою з лімітом у 30 днів і миттєвим відкликанням',
      'Сейф AES-256-GCM на компʼютері з ключами користувачів і привʼязкою полів в AAD',
      'Ролі на компʼютері (ROOT, OPERATOR, GUEST) із перевіркою доступу до особистих даних',
      'Блокування входу на компʼютері після пʼяти невдалих спроб за хвилину',
      'Основні функції ніколи не за оплатою: підписана політика лише доповнює вільний набір',
    ],
    notYetTitle: 'Не зроблено',
    notYet: [
      'Незалежного зовнішнього аудиту безпеки цього коду ще не проводили.',
      'Немає сертифікації чи атестації відповідності (ні ISO 27001, ні SOC 2).',
      'Немає опублікованого звіту тестування на проникнення.',
      'Немає програми винагород за вразливості.',
      'Збіг цифр на вставленому ключі підтверджує лише однаковість ключа, а не особу співрозмовника.',
      'Звірка 48 цифр доводить належність ключа лише за умови сканування камерою з живого екрана.',
      'Листи в черзі на сервері не мають forward secrecy; у Wi-Fi вона діє лише після першої прямої зустрічі.',
      'Відправник, отримувач, час і розмір листа видимі спостерігачу в тій самій мережі.',
      'Пряма доставка між телефонами працює лише в спільній мережі Wi-Fi, без Wi-Fi Direct і передачі через третій телефон.',
      'Доставка поза локальною мережею залежить від нашого сервера, який бачить розмір листа та IP-адреси.',
      'На компʼютері ротація операторського секрету ручна, а компрометація бази разом із секретом розкриває сейф.',
    ],
  },

  report: {
    eyebrow: 'Повідомлення про вразливість',
    title: 'Повідомте про проблему безпеки.',
    lead: 'Напишіть кроки відтворення, отриманий і очікуваний результат. Ми відповімо й опишемо виправлення.',
    cta: 'Повідомити про вразливість',
    mailSubject: 'Nightpost — повідомлення про вразливість',
    addressLabel: 'Або напишіть прямо на',
    note: 'Платної програми винагород немає. Ми відповідаємо особисто й за бажанням зазначаємо автора в журналі змін.',
  },

  ui: {
    limitLabel: 'Межа конструкції',
  },

  cta: {
    title: 'Перевірте релізну збірку самостійно.',
    sub: 'APK для Android підписано релізним ключем, а його SHA-256 публікується на сторінці завантажень напряму з підписаної політики сервера.',
    primary: 'Завантажити для Android',
    secondary: 'Запитати демо',
  },
};

export const securityCopy: Record<Lang, SecurityCopy> = { en, uk };
