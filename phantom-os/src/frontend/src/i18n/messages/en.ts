import type { MessageKey } from './uk';
import * as work from './parts/work';
import * as l1 from './parts/l1';
import * as l2 from './parts/l2';
import * as l3 from './parts/l3';
import * as l4 from './parts/l4';
import * as l5 from './parts/l5';
import * as look from './parts/look';
import * as day from './parts/day';
import * as groups from './parts/groups';
import * as circles from './parts/circles';
import * as people from './parts/people';
import * as settingsRoot from './parts/settingsRoot';
import * as money from './parts/money';
import * as share from './parts/share';
import * as media5 from './parts/media5';
import * as voiceNote from './parts/voiceNote';
import * as callSettings from './parts/callSettings';
import * as media18 from './parts/media18';
import * as media19 from './parts/media19';
import * as money39 from './parts/money39';
import * as money17 from './parts/money17';
import * as day11 from './parts/day11';
import * as chats3 from './parts/chats3';
import * as chats58 from './parts/chats58';
import * as msgr from './parts/msgr';
import * as coreChat from './parts/coreChat';
import * as profile26 from './parts/profile26';
import * as media35 from './parts/media35';
import * as openItems from './parts/openItems';
import * as audienceCount from './parts/audienceCount';
import * as billMode from './parts/billMode';
import * as map55 from './parts/map55';
import * as mapGeo from './parts/mapGeo';
import * as billEscrow from './parts/billEscrow';
import * as carrier from './parts/carrier';
import * as ringMove from './parts/ringMove';
import * as ringPreset from './parts/ringPreset';
import * as whereAsk from './parts/whereAsk';
import * as momentsR from './parts/momentsR';
import * as seenTogether from './parts/seenTogether';
import * as ringGuard from './parts/ringGuard';
import * as signalFold from './parts/signalFold';
import * as silenceGap from './parts/silenceGap';
import * as payGuard from './parts/payGuard';
import * as pcAlpha from './parts/pcAlpha';
import * as polish1 from './parts/polish1';
import * as pcBar from './parts/pcBar';
import * as pcToday from './parts/pcToday';
import * as builder from './parts/builder';
import * as dayMoney from './parts/dayMoney';
import * as lookalike from './parts/lookalike';
import * as engine from './parts/engine';
import * as media7 from './parts/media7';
import * as media27 from './parts/media27';
import * as chats7 from './parts/chats7';
import * as money19 from './parts/money19';
import * as chats21 from './parts/chats21';
import * as chats46 from './parts/chats46';
import * as media48 from './parts/media48';
import * as chats18 from './parts/chats18';
import * as media25 from './parts/media25';
import * as carrierOpen from './parts/carrierOpen';
import * as autoFetch from './parts/autoFetch';
import * as infoView from './parts/infoView';
import * as infoGroup from './parts/infoGroup';
import * as infoChannel from './parts/infoChannel';
import * as infoStates from './parts/infoStates';
import * as infoBiz from './parts/infoBiz';
import * as freeSlots from './parts/freeSlots';
import * as pageReq from './parts/pageReq';
import * as amountTap from './parts/amountTap';
import * as money48 from './parts/money48';
import * as chats24 from './parts/chats24';
import * as chats40 from './parts/chats40';
import * as chats14 from './parts/chats14';
import * as chats47 from './parts/chats47';
import * as profile25 from './parts/profile25';
import * as media20 from './parts/media20';
import * as profile33 from './parts/profile33';
import * as chats5 from './parts/chats5';
import * as money33 from './parts/money33';
import * as media6 from './parts/media6';
import * as payNotch from './parts/payNotch';
import * as doorCode from './parts/doorCode';
import * as sendAck from './parts/sendAck';
import * as motionr2b29 from './parts/motionr2b29';
import * as motionr2b12 from './parts/motionr2b12';
import * as auditL3 from './parts/auditL3';
import * as l2c from './parts/l2c';
import * as profilesW1 from './parts/profilesW1';
import * as profilesR from './parts/profilesR';
import * as profilesW5 from './parts/profilesW5';
import * as bizDirection from './parts/bizDirection';
import * as auditL2a from './parts/auditL2a';
import * as msgX from './parts/msgX';
import * as momentsX from './parts/momentsX';
import * as momentsF from './parts/momentsF';
import * as tgLook from './parts/tgLook';
import * as points from './parts/points';
import * as gifts from './parts/gifts';
import * as tempEnd from './parts/tempEnd';
import * as auditL3b from './parts/auditL3b';
import * as auditL5 from './parts/auditL5';
import * as mediaC6 from './parts/mediaC6';
import * as auditL2b from './parts/auditL2b';
import * as auditL5b from './parts/auditL5b';
import * as groupsTg from './parts/groupsTg';
import * as msgV2 from './parts/msgV2';
import * as pickMany from './parts/pickMany';
import * as fmt0 from './parts/fmt0';
import * as msgTypes from './parts/msgTypes';
import * as support from './parts/support';
import * as edu from './parts/edu';
import * as spaces from './parts/spaces';
import * as profilesX from './parts/profilesX';
import * as ask from './parts/ask';
import * as lxSvc from './parts/lxSvc';
import * as lxMap from './parts/lxMap';
import * as lxSet from './parts/lxSet';
import * as lxMsg from './parts/lxMsg';
import * as groupLink from './parts/groupLink';
import * as callLog from './parts/callLog';

/**
 * English catalogue — the wired seam.
 *
 * `Partial` is deliberate: English is a real, selectable locale but it is not
 * the source language, so it is allowed to lag behind. Anything missing here
 * resolves to the Ukrainian source string (see ../index), which means a
 * half-translated UI degrades to readable Ukrainian rather than to raw keys.
 * The `MessageKey` constraint still catches stale/typo'd keys at build time.
 */
export const en: Partial<Record<MessageKey, string>> = {
  // ── Chat: sessions sidebar ────────────────────────────────────────────────
  'chat.sessions.title': 'Sessions',
  'chat.sessions.empty': 'No sessions yet',
  'chat.sessions.listening': 'Nightpost is listening.',
  'chat.sessions.new': 'New session',
  'chat.sessions.open': 'Open sessions',
  'chat.sessions.close': 'Close sessions',
  'chat.sessions.toggle': 'Toggle session menu',
  'chat.sessions.rename': 'Rename session',
  'chat.sessions.renameHint': 'Double-click to rename',
  'chat.sessions.delete': 'Delete session',
  'chat.sessions.count': '{count} msg',
  'chat.sessions.preview': 'Session · {id}',
  'chat.sessions.newShort': 'New',

  // ── Chat: header ──────────────────────────────────────────────────────────
  'chat.header.untitled': 'New Conversation',

  // ── Chat: empty state + quick prompts ─────────────────────────────────────
  'chat.empty.new': 'New conversation',
  'chat.empty.loaded': 'Session loaded',
  'chat.quick.aria': 'Quick chat actions',
  'chat.empty.body':
    'Pick an action or just type. Nightpost answers better when it can see the context, the goal and the format you want.',
  'chat.quick.plan.label': 'Plan the day',
  'chat.quick.plan.prompt':
    'Help me put together a short plan for the day: priorities, risks, the next 3 actions.',
  'chat.quick.risks.label': 'Check the risks',
  'chat.quick.risks.prompt':
    'Look at the current system state and tell me what needs attention first.',
  'chat.quick.settings.label': 'Explain the settings',
  'chat.quick.settings.prompt':
    'Explain the key Nightpost settings in plain words, and what is worth changing first.',

  // ── Chat: input ───────────────────────────────────────────────────────────
  'chat.input.placeholder': 'Message Nightpost…',
  'chat.input.sending': 'Nightpost is typing…',
  'chat.voice.stopSpeaking': 'Stop Nightpost speaking',

  // ── Chat: live indicators ─────────────────────────────────────────────────
  'chat.thinking': 'Thinking',
  'chat.thinking.aria': 'Nightpost is thinking',
  'chat.transcribing': 'Transcription in progress',
  'chat.thoughtStream': 'Thought Stream',

  // ── Messenger: add a person (WC, 08.10) ──────────────────────────────────
  'msgr.add.door': 'Add a person',
  'msgr.add.doorHint': 'QR, link or @nickname',
  'msgr.add.title': 'Add a person',
  'msgr.add.oneScan': 'One scan is enough — from either side',
  'msgr.add.scan': 'Scan',
  'msgr.add.myQr': 'My QR',
  'msgr.add.share': 'Share link',
  'msgr.add.copy': 'Copy link',
  'msgr.add.copied': 'Copied',
  'msgr.add.shared': 'Sent',
  'msgr.add.copyFailed': 'Clipboard is unavailable — select the text below',
  'msgr.add.qrAlt': 'My QR to scan',
  'msgr.add.preparing': 'Preparing QR…',
  'msgr.add.notReady': "The phone didn't hand over an invite — try again",
  'msgr.add.retry': 'Try again',
  'msgr.add.nick': 'Their @nickname',
  'msgr.add.nickNone': 'No one with that nickname',
  'msgr.add.nickPlaceholder': 'their nickname',
  'msgr.add.newGroup': 'New group',
  'msgr.add.nickDown': "No connection — can't check nicknames now",
  'msgr.add.getNick': 'Get your @nickname',
  'msgr.add.nickTitle': 'Your @nickname',
  'msgr.add.paste': 'Paste an invite',
  'msgr.add.pasteLabel': 'Invite',
  'msgr.add.pastePlaceholder': 'Paste the link or invite',
  'msgr.add.clipboard': "There's an invite in your clipboard",
  'msgr.add.open': 'Open',
  'msgr.add.notInvite': "That doesn't look like an invite",
  'msgr.add.tooNew': 'This invite is from a newer Nightpost — update the app.',
  'msgr.add.other': 'Another invite',
  'msgr.add.nameLabel': "Person's name",
  'msgr.add.confirm': 'Add',
  'msgr.add.adding': 'Adding…',
  'msgr.add.failed': "Couldn't add — try again",
  'msgr.add.waiting': 'Waiting for {name} to receive your contact',
  'msgr.add.ready': '{name} is connected',
  'msgr.add.write': 'Message',
  'msgr.add.yourName': 'Your name',
  'msgr.add.rename': 'Edit name',
  'msgr.add.done': 'Done',
  'msgr.add.unnamed': 'Name not set',
  'msgr.add.shareLead': 'Hi! Add me on Nightpost: {link}',
  'msgr.add.shareFallback': "Link won't open? Paste this line into the app:",
  'msgr.add.shareTitle': 'Invite to Nightpost',
  'msgr.add.searching': 'Searching…',
  'msgr.add.or': 'or',
  'msgr.scan.unavailable': 'Camera unavailable.',
  'msgr.scan.denied': 'No camera permission — allow it in settings',
  'msgr.scan.decoderFailed': "Couldn't start QR recognition.",
  'msgr.scan.stop': 'Stop camera',
  'msgr.scan.allowCamera': 'Allow camera',
  'msgr.scan.retry': 'Try again',

  // ── Settings: language picker ─────────────────────────────────────────────
  'settings.language.label': 'Interface language',
  'settings.language.hint': 'Ukrainian is primary; English is the fallback.',

  // ── Call screen (P0-5, 08.10) ─────────────────────────────────────────────
  'call.route.menu': 'Audio output',
  'call.route.button': 'Audio: {route}. Change',
  'call.route.earpiece': 'Phone',
  'call.route.speaker': 'Speaker',
  'call.route.bluetooth': 'Wireless headphones',
  'call.route.wired': 'Headphones',
  'call.quality.label': 'Connection quality: {bars} of 4',
  'call.quality.relay': 'via relay',
  'call.flip': 'Flip camera',
  'call.flip.short': 'Flip',
  'call.self': 'Your video. Drag it to any corner',
  'deskPane.day': 'Day',
  'deskPane.map': 'Map',
  'deskPane.wallet': 'Wallet',
  'deskPane.info': 'Info',
  'deskPane.close': 'Close panel',
  'deskPane.tabsAria': 'Side panel',
  'deskPane.emptyDay': 'Nothing for today',
  'deskPane.emptyWallet': 'Select a chat',
  'deskPane.emptyInfo': 'Select a chat',
  'deskPane.devnet': 'Solana Devnet',
  'deskPane.mainnet': 'Solana Mainnet',
  'deskPane.noPay': 'Payments unavailable',
  ...l1.en,
  ...l2.en,
  ...l3.en,
  ...l4.en,
  ...l5.en,
  ...look.en,
  ...day.en,
  ...groups.en,
  ...circles.en,
  ...people.en,
  ...settingsRoot.en,
  ...money.en,
  ...share.en,
  ...voiceNote.en,
  ...media5.en,
  ...callSettings.en,
  ...media18.en,
  ...media19.en,
  ...money39.en,
  ...money17.en,
  ...day11.en,
  ...chats3.en,
  ...chats58.en,
  ...msgr.en,
  ...coreChat.en,
  ...profile26.en,
  ...media35.en,
  ...openItems.en,
  ...audienceCount.en,
  ...billMode.en,
  ...map55.en,
  ...mapGeo.en,
  ...billEscrow.en,
  ...carrier.en,
  ...ringMove.en,
  ...ringPreset.en,
  ...whereAsk.en,
  ...momentsR.en,
  ...seenTogether.en,
  ...ringGuard.en,
  ...signalFold.en,
  ...silenceGap.en,
  ...payGuard.en,
  ...pcAlpha.en,
  ...polish1.en,
  ...pcBar.en,
  ...pcToday.en,
  ...dayMoney.en,
  ...builder.en,
  ...lookalike.en,
  ...engine.en,
  ...media7.en,
  ...media27.en,
  ...chats7.en,
  ...money19.en,
  ...chats21.en,
  ...chats46.en,
  ...media48.en,
  ...chats18.en,
  ...media25.en,
  ...carrierOpen.en,
  ...autoFetch.en,
  ...infoView.en,
  ...infoGroup.en,
  ...infoChannel.en,
  ...infoStates.en,
  ...infoBiz.en,
  ...freeSlots.en,
  ...pageReq.en,
  ...amountTap.en,
  ...money48.en,
  ...chats24.en,
  ...chats40.en,
  ...chats14.en,
  ...chats47.en,
  ...profile25.en,
  ...media20.en,
  ...profile33.en,
  ...chats5.en,
  ...money33.en,
  ...media6.en,
  ...payNotch.en,
  ...doorCode.en,
  ...sendAck.en,
  ...motionr2b29.en,
  ...motionr2b12.en,
  ...auditL3.en,
  ...l2c.en,
  ...profilesW1.en,
  ...profilesW5.en,
  ...profilesR.en,
  ...bizDirection.en,
  ...auditL2a.en,
  ...msgX.en,
  ...momentsX.en,
  ...momentsF.en,
  ...tgLook.en,
  ...points.en,
  ...gifts.en,
  ...tempEnd.en,
  ...auditL3b.en,
  ...auditL5.en,
  ...mediaC6.en,
  ...auditL2b.en,
  ...auditL5b.en,
  ...fmt0.en,
  ...groupsTg.en,
  ...msgV2.en,
  ...pickMany.en,
  ...msgTypes.en,
  ...support.en,
  ...work.en,
  ...edu.en,
  ...spaces.en,
  ...profilesX.en,
  ...ask.en,
  ...lxSvc.en,
  ...lxMap.en,
  ...lxSet.en,
  ...lxMsg.en,
  ...groupLink.en,
  ...callLog.en,
};
