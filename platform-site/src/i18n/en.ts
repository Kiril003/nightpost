import type { Copy } from './types';

export const en: Copy = {
  meta: {
    title: 'Nightpost: everything your phone is for, joined',
    description:
      'Talk, people, the map, your day and money in one app, with an assistant across it. For Android.',
  },
  nav: {
    home: 'Overview',
    os: 'Nightpost',
    companion: 'Companion',
  },
  footer: {
    tagline:
      'Talk, people, the map, your day and money in one app. No phone number, no sign-up.',
    productHeading: 'Product',
    made: '',
    rights: '© 2026 Nightpost. All rights reserved.',
  },

  home: {
    spectrum: {
      eyebrow: 'HOW IT BEHAVES',
      title: 'It behaves differently depending on what is going on.',
      lead: 'No settings to dig through. Driving — it keeps it short. Alarm — it speaks first. Evening — it quiets down by itself.',
      states: [
        { name: 'SHADOW', epithet: 'Stays quiet', desc: 'Present but not in the way. Asks nothing, pops up nothing.' },
        { name: 'FOCUS', epithet: 'Guards attention', desc: 'Holds notifications back while you are busy with something that matters.' },
        { name: 'DIALOGUE', epithet: 'Talks', desc: 'An ordinary spoken conversation. You can cut in mid-word.' },
        { name: 'SENTINEL', epithet: 'Warns', desc: 'Air raids, GPS spoofing, readings that make no sense — it tells you first.' },
        { name: 'GHOST', epithet: 'Leaves no trace', desc: 'Records nothing. What already exists stays encrypted.' },
        { name: 'DREAM', epithet: 'Sorts the day', desc: 'Overnight it tidies what was written down, so mornings find things faster.' },
      ],
    },
    cta: {
      title: 'See it live — starting from your own pocket.',
      sub: 'The early Android build downloads today: no account, no store. The machine on the desk in Kyiv we still show on a call — no slides.',
      primary: 'Get the early Android build',
      secondary: 'Request a live demo',
    },
  },

  os: {
    meta: {
      title: 'Nightpost — a computer that works with no internet',
      description:
        'A small machine with its own screen: a voice assistant, maps with routes, memory and a vault. Everything is computed inside it — no cloud, no account.',
    },
    hero: {
      eyebrow: 'THE DEVICE ON YOUR DESK',
      title: 'A computer that does not need the internet.',
      sub: 'A small machine with its own screen, microphone and speaker. It listens, answers, shows the map and remembers what you agreed. Everything is computed inside it — nothing goes to someone else’s cloud. And before anything serious, it asks.',
      chips: ['Its own 7″ screen', 'Works with no network', 'No sign-up', 'Talks out loud'],
    },
    intelligence: {
      eyebrow: 'THE MIND',
      title: 'The network is a bonus, not a condition.',
      lead: 'If the internet disappears mid-sentence, the answer still arrives — the device simply finishes it itself.',
      features: [
        {
          title: 'Two roads to an answer',
          desc: 'With a network, a hard question can go to a large model. Without one, the model inside the device answers. It switches by itself and does not ask permission.',
        },
        {
          title: 'It remembers, and sorts at night',
          desc: 'What was said and done goes into memory and is searchable in plain words. Overnight the system tidies it up so mornings find things faster.',
        },
        {
          title: 'Answers rest on facts',
          desc: 'Before answering, Nightpost checks its own records: where you were, what time it is, what the sensors say. So an answer about your day really is about your day.',
        },
        {
          title: 'Nothing important without your yes',
          desc: 'The assistant can look and search as much as it likes. But the moment an action changes something, it asks first.',
        },
      ],
      ladderTitle: 'Four levels of hearing — from instant to most accurate',
      ladder: [
        { k: '01', title: 'Instant', desc: 'Recognises straight away, on any hardware. The floor that is always there.' },
        { k: '02', title: 'Refinement', desc: 'A second pass fixes what the first one only heard roughly.' },
        { k: '03', title: 'Accelerated', desc: 'A dedicated chip takes recognition over — faster and without the heat.' },
        { k: '04', title: 'The hardest audio', desc: 'Noise, wind, several voices — a large model for the cases where the rest give up.' },
      ],
    },
    surface: {
      eyebrow: 'THE SCREEN',
      title: 'Built like an instrument, not like a website.',
      lead: 'One screen at arm’s length. Nothing slides off, nothing has to be hunted for, everything can be hit with a finger.',
      features: [
        {
          title: 'A map that does not depend on the network',
          desc: 'The city is on disk in advance: pan, search and plan routes with the cable pulled out.',
        },
        {
          title: 'Alerts — out loud, and first',
          desc: 'Air raid alerts by region and zones on the map: Nightpost says it out loud rather than waiting for you to look.',
        },
        {
          title: 'It learns your routes',
          desc: 'The roads you drive and the places you visit stay on your disk and make its suggestions sharper.',
        },
        {
          title: 'The screen size is the law',
          desc: 'Everything is checked at a real 1024×600. If something does not fit, that is a defect, not a detail.',
        },
        {
          title: 'A machine checks every change',
          desc: 'Before anything reaches a build, an automatic pass walks the screens and catches what broke.',
        },
      ],
    },
    desktop: {
      eyebrow: 'ON AN ORDINARY COMPUTER',
      title: 'The same thing — on the computer you already own.',
      lead: 'If you do not want a separate box, install the program on your Linux machine. It is the same Nightpost, just in a window.',
      points: [
        {
          title: 'All of it, with no new hardware',
          desc: 'Conversation, memory, voice and maps — the same core in an ordinary window.',
        },
        {
          title: 'The key is checked locally',
          desc: 'The licence opens on your device. No account, no phoning home, no meter.',
        },
        {
          title: 'The key moves with you',
          desc: 'Get a dedicated box later and the licence moves onto it.',
        },
      ],
    },
    reliability: {
      eyebrow: 'RELIABILITY',
      title: 'It holds like infrastructure, because it is.',
      lead: 'This is not a weekend demo. The device has to run for months and ask nothing of you.',
      points: [
        { title: '1,230+ checks on every change', desc: 'No edit reaches a build until the whole automated set has passed.' },
        { title: 'One file, no administrator rights', desc: 'The Linux app for 64-bit processors arrives as a single file and asks for no system install.' },
        { title: 'You can see inside', desc: 'The device answers whether it is alive, and keeps a log you can reconstruct events from.' },
        { title: 'A hardened way in', desc: 'Password guessing is blocked even when the attacker hides behind someone else’s address. A default PIN from the network is never accepted.' },
        { title: 'Every build is checked', desc: 'Every change runs the server side and the interface — both must come out clean before an edit reaches a build.' },
        { title: 'One owner by default', desc: 'The device belongs to one person. A mode for several is a separate flag you must turn on deliberately.' },
      ],
    },
    cta: {
      title: 'The core you cannot download yet. The pocket you can.',
      sub: 'There is no core build yet — and no button pretending there is. But part of the system is already real: the early Android Companion. How the core thinks, remembers and routes — we show on a call.',
      primary: 'Get the early Android build',
      secondary: 'Request a live demo',
    },
  },

  companion: {
    meta: {
      title: 'Nightpost — an app that works with no network',
      description:
        'Messages straight to your people, phone to phone, with no server and no internet, on one Wi-Fi. Plus maps with routes, voice and a vault — all on the phone itself.',
    },
    hero: {
      eyebrow: 'THE APP ON YOUR PHONE',
      title: 'The same assistant — in your pocket.',
      sub: 'An Android app that works on its own: messages straight to your people, conversation with the assistant, maps with routes and voice — all on the phone, with no network. And when the desk device is nearby, the two start remembering the same things.',
      chips: [
        'Messages phone to phone — on one Wi-Fi',
        'Works with no network',
        'Android 10 and newer',
        'Watch and car',
        'Sealed end to end',
      ],
    },
    foundation: {
      eyebrow: 'HOW IT IS BUILT',
      title: 'An ordinary app, built properly.',
      lead: 'Not a website in a wrapper and not a builder kit. Written for Android directly — so it is fast and can reach what a browser cannot.',
      points: [
        {
          title: 'Written for Android, not for everything',
          desc: 'That is why it opens instantly and does things a wrapped website simply cannot.',
        },
        {
          title: 'It looks like one thing',
          desc: 'Colour and motion change with the state: calm is warm and quiet, an alarm is visible out of the corner of your eye. That is a cue, not decoration.',
        },
        {
          title: 'It runs on the phone you have',
          desc: 'Android 10 and newer. Plus separate screens for the watch and for the car.',
        },
        {
          title: 'Complete on its own',
          desc: 'Everything below works with no desk device, no account and no signal. Pairing adds power but is never a condition.',
        },
      ],
    },
    stream: {
      eyebrow: 'THE CONVERSATION',
      title: 'Instead of a pile of screens — one conversation.',
      lead: 'There is no icon grid where you have to guess the right one. You ask, and the answer arrives as a finished card: a route, a calculation, a reminder. Right in the conversation.',
      points: [
        {
          title: 'The answer arrives as a card',
          desc: 'Routes, comparisons, calculations — all shown in the conversation itself, not in some separate section.',
        },
        {
          title: 'Nearby, even when the screen is not in your hand',
          desc: 'A bubble over other apps, the state on a dimmed screen and headphone control — you need not take the phone out.',
        },
        {
          title: 'It can act inside the phone',
          desc: 'With your permission the assistant sees notifications and calls, and can tap something for you. Permissions can be taken back at any time.',
        },
      ],
    },
    nav: {
      eyebrow: 'NAVIGATION',
      title: 'Navigation for when everything is against you.',
      lead: 'Not just a map: the route, spoken prompts, public transport and knowledge of what is happening along the way.',
      points: [
        {
          title: 'Several routes, not one',
          desc: 'It shows options and waypoints. If a new calculation fails, the old route stays — you will not be left with nothing mid-journey.',
        },
        {
          title: 'What lies along the way',
          desc: 'For each route the app looks ahead at what lies along it: fuel and shops, climbs, risky stretches.',
        },
        {
          title: 'On foot and by transit',
          desc: 'It assembles a journey with transfers — where to walk, where to board, where to get off. From timetables stored on the phone.',
        },
        {
          title: 'It knows about shelters and time',
          desc: 'The nearest shelters, the alert and whether you are still on time — all next to the route, not in another app.',
        },
        {
          title: 'It tells you honestly when you turned off',
          desc: 'Going off-route is computed openly and covered by tests. No wondering why it decided that — the logic is written down and visible.',
        },
      ],
    },
    mesh: {
      eyebrow: 'PEOPLE',
      title: 'Write to each other with no tower, no internet and no account.',
      lead: 'Two phones on one Wi-Fi are already a network. A message is signed with your key, sealed, and goes straight to the device next to you: no server in between, no account, no phone number. Off that network it goes as a sealed envelope into a blind drop on our server, which sees the address and the time of the drop — never the content. We say this before the first time rather than after.',
      points: [
        {
          title: 'A key instead of an account',
          desc: 'A person on your list is a proved key, not a row in somebody\u2019s database. The 48 digits are computed from both keys together: change either one and the digits diverge, and the verified tick drops on its own. They prove the key belongs to that person when the two of you exchanged keys by camera, screen to screen, and both of you did it — one reading confirms only the key you hold. A code that arrived through a chat is not proven by matching digits; why is below.',
        },
        {
          title: 'Encrypted — and precisely this much',
          desc: 'The text and any place you send are sealed before they reach the network. Somebody listening on that same network can see that you sent something, to whom and when — but not what. We do not claim to hide the fact of the message.',
        },
        {
          title: 'An old invite does not pass quietly',
          desc: 'An invite carrying no encryption key is refused, with the reason on screen. And a message to a contact added by such an invite will not go at all: the input is disabled, and even if something did reach the queue it is marked failed before the socket rather than going out in the clear.',
        },
        {
          title: 'A group with no owner',
          desc: 'A group is not a place on a server but a label on ordinary messages: every member gets their own signed and sealed copy. Nobody can be added from above, you see «2 of 4» rather than a cheerful tick, and anyone not on your own contact list is marked as such. Changing who is in a group is not possible yet — no adding, no removing, no leaving.',
        },
        {
          title: 'What you wrote does not vanish',
          desc: 'No link — the message waits and keeps trying: through a flat battery, through a night. You can see that it waits and why — «waiting» and «not heard yet» are different lines. If it finally gives up it stays in view rather than disappearing.',
        },
        {
          title: 'A place as a message',
          desc: 'Sending where you are is one tap, and it lands on their map as a pin — not as coordinates to paste somewhere. It travels inside the same sealed message.',
        },
      ],
      proof: [
        { label: 'Checked live', value: '7 of 7 deliveries (6 Sep 2026); 5 of 5 replies (13 Aug 2026)' },
        { label: 'Refused', value: 'cleartext, a forged body, an invite with no key' },
        { label: 'Server', value: 'none on one network; off it, a blind drop that cannot read the envelope' },
      ],
      limitTitle: 'What this does not do',
      limit: 'Directly, it is the local network and only the local network: both phones on the same Wi-Fi, with the app open on both. Another phone will not pick a message up and carry it onward — there is no relaying through a third phone; off the shared network the envelope waits in the drop on our server, which sees who wrote to whom and when, just not what. Digits compared over the same channel the code arrived on are no defence against somebody who controls that channel: he can alter both. That is why a key is worth taking with the camera, off the screen. Forward secrecy is here now, but it starts at the first successful meeting in the flesh: until there has been one, the letter goes under the standing pair key, and a phone taken today opens it from yesterday’s capture. Everything sent before this build stays openable the same way.',
    },
    voice: {
      eyebrow: 'VOICE',
      title: 'A voice that goes nowhere.',
      lead: 'What you say is recognised on the phone itself and sent nowhere. Ukrainian is not one of the supported languages — it is the main one.',
      points: [
        {
          title: 'It hears with no network',
          desc: 'Recognition runs on the phone itself. The app tells speech from noise, so it does not fire on every sound.',
        },
        {
          title: 'It speaks Ukrainian like a person',
          desc: 'Numbers, dates and abbreviations are spoken correctly — “14:30” becomes “half past two”, not four separate digits.',
        },
        {
          title: 'Hold — speak',
          desc: 'Hold the button, speak, let go. When the desk device is nearby, your voice goes to it over a closed link.',
        },
      ],
    },
    ai: {
      eyebrow: 'THE MIND IN YOUR PHONE',
      title: 'The same approach as on the desk.',
      lead: 'The network helps but is not needed. With no connection, the phone thinks for itself.',
      points: [
        {
          title: 'It picks who to ask',
          desc: 'With a network, a hard question goes to a large model. Without one, the model in the phone answers. The switch is invisible.',
        },
        {
          title: 'Search works with no server',
          desc: 'It searches by meaning rather than exact words — and computes that on the phone itself.',
        },
        {
          title: 'Models under control',
          desc: 'You can see which models are downloaded and how much space they take. Anything spare can be removed.',
        },
      ],
    },
    data: {
      eyebrow: 'YOUR DATA',
      title: 'What is written down stays with you.',
      lead: 'No network is an ordinary state, not an error. The app will not even notice.',
      points: [
        {
          title: 'A diary you can search',
          desc: 'Where you were, what you did, what you agreed — all on your phone, and all findable in plain words.',
        },
        {
          title: 'You can see why it decided that',
          desc: 'Every decision the assistant makes is recorded together with its reason.',
        },
        {
          title: 'Nothing is lost in a dead zone',
          desc: 'What you did without a connection waits, and runs exactly once when the connection comes back.',
        },
      ],
    },
    everywhere: {
      eyebrow: 'NOT JUST THE PHONE',
      title: 'On your wrist, in the car, in your ears.',
      lead: 'Where reaching for the phone is awkward, or not allowed.',
      points: [
        { title: 'The car screen', desc: 'Big buttons, voice first, a glance of a second. The way it is allowed at the wheel.' },
        { title: 'The watch', desc: 'State and the one thing that matters — at a glance on your wrist.' },
        { title: 'Headphones', desc: 'You can work by voice without looking at the screen at all.' },
      ],
    },
    cta: {
      title: 'One file, and the assistant is in your pocket. Literally, now.',
      sub: 'The early Android build, signed with our release key, downloads with no account. Meeting the core — one QR, a few seconds — we show on a call.',
      primary: 'Get the early Android build',
      secondary: 'Request a live demo',
    },
  },
};
