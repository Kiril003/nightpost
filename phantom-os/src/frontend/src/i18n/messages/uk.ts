/**
 * Ukrainian message catalogue — the source of truth.
 *
 * `MessageKey` is derived from this object's keys, so every other locale is
 * type-checked against it (a typo'd or stale key in en.ts fails the build) and
 * this catalogue is, by construction, always complete. Keys are dotted by
 * surface (`chat.*`, `settings.*`); values may carry `{var}` placeholders that
 * the runtime interpolates (see ../index).
 */
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

export const uk = {
  // ── Chat: sessions sidebar ────────────────────────────────────────────────
  'chat.sessions.title': 'Сесії',
  'chat.sessions.empty': 'Ще немає сесій',
  'chat.sessions.listening': 'Nightpost слухає.',
  'chat.sessions.new': 'Нова сесія',
  'chat.sessions.open': 'Відкрити сесії',
  'chat.sessions.close': 'Закрити сесії',
  'chat.sessions.toggle': 'Перемкнути меню сесій',
  'chat.sessions.rename': 'Перейменувати сесію',
  'chat.sessions.renameHint': 'Подвійний клік — перейменувати',
  'chat.sessions.delete': 'Видалити сесію',
  'chat.sessions.count': '{count} повідом.',
  'chat.sessions.preview': 'Сесія · {id}',
  'chat.sessions.newShort': 'Нова',

  // ── Chat: header ──────────────────────────────────────────────────────────
  'chat.header.untitled': 'Нова розмова',

  // ── Chat: empty state + quick prompts ─────────────────────────────────────
  'chat.empty.new': 'Нова розмова',
  'chat.empty.loaded': 'Сесію завантажено',
  'chat.quick.aria': 'Швидкі дії чату',
  'chat.empty.body':
    'Оберіть дію або напишіть напряму. Nightpost краще відповідає, коли бачить контекст, ціль і бажаний формат.',
  'chat.quick.plan.label': 'План дня',
  'chat.quick.plan.prompt':
    'Допоможи зібрати короткий план дня: пріоритети, ризики, наступні 3 дії.',
  'chat.quick.risks.label': 'Перевір ризики',
  'chat.quick.risks.prompt':
    'Подивись на поточний стан системи й скажи, що потребує уваги першим.',
  'chat.quick.settings.label': 'Поясни налаштування',
  'chat.quick.settings.prompt':
    'Поясни ключові налаштування Nightpost простими словами і що варто змінити спочатку.',

  // ── Chat: input ───────────────────────────────────────────────────────────
  'chat.input.placeholder': 'Напишіть Nightpost…',
  'chat.input.sending': 'Nightpost відповідає…',
  'chat.voice.stopSpeaking': 'Спинити Nightpost',

  // ── Chat: live indicators ─────────────────────────────────────────────────
  'chat.thinking': 'Мислю',
  'chat.thinking.aria': 'Nightpost мислить',
  'chat.transcribing': 'Розпізнавання у процесі',
  'chat.thoughtStream': 'Потік Свідомості',

  // ── Messenger: add a person (WC, 08.10) ──────────────────────────────────
  'msgr.add.door': 'Додати людину',
  'msgr.add.doorHint': 'QR, посилання або @нік',
  'msgr.add.title': 'Додати людину',
  'msgr.add.oneScan': 'Досить одного сканування — з будь-якого боку',
  'msgr.add.scan': 'Сканувати',
  'msgr.add.myQr': 'Мій QR',
  'msgr.add.share': 'Надіслати посилання',
  'msgr.add.copy': 'Копіювати посилання',
  'msgr.add.copied': 'Скопійовано',
  'msgr.add.shared': 'Надіслано',
  'msgr.add.copyFailed': 'Буфер обміну недоступний — виділіть текст нижче',
  'msgr.add.qrAlt': 'Мій QR для сканування',
  'msgr.add.preparing': 'Готую QR…',
  'msgr.add.notReady': 'Телефон не віддав запрошення — спробуйте ще раз',
  'msgr.add.retry': 'Спробувати ще раз',
  'msgr.add.nick': '@нік людини',
  'msgr.add.nickNone': 'Нікого з таким ніком',
  'msgr.add.nickPlaceholder': 'нік людини',
  'msgr.add.newGroup': 'Нова група',
  'msgr.add.nickDown': 'Немає звʼязку — нік зараз не перевірити',
  'msgr.add.getNick': 'Отримати свій @нік',
  'msgr.add.nickTitle': 'Ваш @нік',
  'msgr.add.paste': 'Вставити запрошення',
  'msgr.add.pasteLabel': 'Запрошення',
  'msgr.add.pastePlaceholder': 'Вставте посилання або запрошення',
  'msgr.add.clipboard': 'У буфері є запрошення',
  'msgr.add.open': 'Відкрити',
  'msgr.add.notInvite': 'Це не схоже на запрошення',
  'msgr.add.tooNew': 'Це запрошення з новішої версії Nightpost — оновіть застосунок.',
  'msgr.add.other': 'Інше запрошення',
  'msgr.add.nameLabel': 'Імʼя людини',
  'msgr.add.confirm': 'Додати',
  'msgr.add.adding': 'Додаю…',
  'msgr.add.failed': 'Не вдалося додати — спробуйте ще раз',
  'msgr.add.waiting': 'Чекаємо, поки {name} отримає ваш контакт',
  'msgr.add.ready': '{name} на звʼязку',
  'msgr.add.write': 'Написати',
  'msgr.add.yourName': 'Ваше імʼя',
  'msgr.add.rename': 'Змінити імʼя',
  'msgr.add.done': 'Готово',
  'msgr.add.unnamed': 'Без імені',
  'msgr.add.shareLead': 'Привіт! Додай мене в Nightpost: {link}',
  'msgr.add.shareFallback': 'Посилання не відкривається? Вставте в застосунок цей рядок:',
  'msgr.add.shareTitle': 'Запрошення в Nightpost',
  'msgr.add.searching': 'Шукаю…',
  'msgr.add.or': 'або',
  'msgr.scan.unavailable': 'Камера недоступна.',
  'msgr.scan.denied': 'Немає дозволу на камеру — дозвольте його в налаштуваннях',
  'msgr.scan.decoderFailed': 'Не вдалося ввімкнути розпізнавання QR.',
  'msgr.scan.stop': 'Зупинити камеру',
  'msgr.scan.allowCamera': 'Дозволити камеру',
  'msgr.scan.retry': 'Спробувати ще раз',

  // ── Settings: language picker ─────────────────────────────────────────────
  'settings.language.label': 'Мова інтерфейсу',
  'settings.language.hint': 'Українська — основна; English — запасна.',

  // ── Call screen (P0-5, 08.10) ─────────────────────────────────────────────
  'call.route.menu': 'Куди йде звук',
  'call.route.button': 'Звук іде: {route}. Змінити',
  'call.route.earpiece': 'Телефон',
  'call.route.speaker': 'Динамік',
  'call.route.bluetooth': 'Бездротові навушники',
  'call.route.wired': 'Навушники',
  'call.quality.label': 'Якість звʼязку: {bars} з 4',
  'call.quality.relay': 'через посередника',
  'call.flip': 'Перевернути камеру',
  'call.flip.short': 'Обернути',
  'call.self': 'Ваше відео. Перетягніть у будь-який кут',
  'deskPane.day': 'День',
  'deskPane.map': 'Мапа',
  'deskPane.wallet': 'Гаманець',
  'deskPane.info': 'Інфо',
  'deskPane.close': 'Закрити панель',
  'deskPane.tabsAria': 'Бічна панель',
  'deskPane.emptyDay': 'На сьогодні нічого немає',
  'deskPane.emptyWallet': 'Оберіть розмову',
  'deskPane.emptyInfo': 'Оберіть розмову',
  'deskPane.devnet': 'Solana Devnet',
  'deskPane.mainnet': 'Solana Mainnet',
  'deskPane.noPay': 'Переказ недоступний',
  ...l1.uk,
  ...l2.uk,
  ...l3.uk,
  ...l4.uk,
  ...l5.uk,
  ...look.uk,
  ...day.uk,
  ...groups.uk,
  ...circles.uk,
  ...people.uk,
  ...settingsRoot.uk,
  ...money.uk,
  ...share.uk,
  ...voiceNote.uk,
  ...media5.uk,
  ...callSettings.uk,
  ...media18.uk,
  ...media19.uk,
  ...money39.uk,
  ...money17.uk,
  ...day11.uk,
  ...chats3.uk,
  ...chats58.uk,
  ...msgr.uk,
  ...coreChat.uk,
  ...profile26.uk,
  ...media35.uk,
  ...openItems.uk,
  ...audienceCount.uk,
  ...billMode.uk,
  ...map55.uk,
  ...mapGeo.uk,
  ...billEscrow.uk,
  ...carrier.uk,
  ...ringMove.uk,
  ...ringPreset.uk,
  ...whereAsk.uk,
  ...momentsR.uk,
  ...seenTogether.uk,
  ...ringGuard.uk,
  ...signalFold.uk,
  ...silenceGap.uk,
  ...payGuard.uk,
  ...pcAlpha.uk,
  ...polish1.uk,
  ...pcBar.uk,
  ...pcToday.uk,
  ...dayMoney.uk,
  ...builder.uk,
  ...lookalike.uk,
  ...engine.uk,
  ...media7.uk,
  ...media27.uk,
  ...chats7.uk,
  ...money19.uk,
  ...chats21.uk,
  ...chats46.uk,
  ...media48.uk,
  ...chats18.uk,
  ...media25.uk,
  ...carrierOpen.uk,
  ...autoFetch.uk,
  ...infoView.uk,
  ...infoGroup.uk,
  ...infoChannel.uk,
  ...infoStates.uk,
  ...infoBiz.uk,
  ...freeSlots.uk,
  ...pageReq.uk,
  ...amountTap.uk,
  ...money48.uk,
  ...chats24.uk,
  ...chats40.uk,
  ...chats14.uk,
  ...chats47.uk,
  ...profile25.uk,
  ...media20.uk,
  ...profile33.uk,
  ...chats5.uk,
  ...money33.uk,
  ...media6.uk,
  ...payNotch.uk,
  ...doorCode.uk,
  ...sendAck.uk,
  ...motionr2b29.uk,
  ...motionr2b12.uk,
  ...auditL3.uk,
  ...l2c.uk,
  ...profilesW1.uk,
  ...profilesW5.uk,
  ...profilesR.uk,
  ...bizDirection.uk,
  ...auditL2a.uk,
  ...msgX.uk,
  ...momentsX.uk,
  ...momentsF.uk,
  ...tgLook.uk,
  ...points.uk,
  ...gifts.uk,
  ...tempEnd.uk,
  ...auditL3b.uk,
  ...auditL5.uk,
  ...mediaC6.uk,
  ...auditL2b.uk,
  ...auditL5b.uk,
  ...fmt0.uk,
  ...groupsTg.uk,
  ...msgV2.uk,
  ...pickMany.uk,
  ...msgTypes.uk,
  ...support.uk,
  ...work.uk,
  ...edu.uk,
  ...spaces.uk,
  ...profilesX.uk,
  ...ask.uk,
  ...lxSvc.uk,
  ...lxMap.uk,
  ...lxSet.uk,
  ...lxMsg.uk,
  ...groupLink.uk,
  ...callLog.uk,
} as const;

export type MessageKey = keyof typeof uk;
