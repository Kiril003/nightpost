/**
 * Call-engine end reasons and diagnostics. These strings are codes kept in the
 * call snapshot and parsed by callWords.endedLine, which turns every one into a
 * catalogue sentence; none is rendered as is. They stay Ukrainian because the
 * engine tests and the parser pin this exact vocabulary.
 */

export const REASON = {
  offerFailed: 'не вдалося скласти пропозицію',
  acceptFailed: 'не вдалося прийняти дзвінок',
  declined: 'відхилено',
  finished: 'завершено',
  allLeft: 'усі вийшли з розмови',
  peerRadio: 'співрозмовник перейшов на рацію',
  answerRejected: 'відповідь співрозмовника не прийнялась',
  peerDeclined: 'співрозмовник відхилив',
  callsOff: 'не приймає дзвінків',
  captureUnavailable: 'пристрої захоплення недоступні',
  peerHungUp: 'співрозмовник поклав слухавку',
  trackUnstable: 'доріжка не тримається',
  trackGone: 'доріжка пропала',
  noCandidatePairs: 'прохідних пар кандидатів не лишилось',
  linkDropped: 'звʼязок обірвався',
  directNeverUp: 'пряма доріжка так і не встала',
  accessDenied: 'доступ до мікрофона й камери не дано',
  deviceNotFound: 'мікрофона чи камери не знайдено',
  deviceBusy: 'мікрофон або камеру зайнято іншим застосунком',
  noTrack: 'доріжки й так немає',
  trackCutManual: 'доріжку обірвано вручну',
  trackClosed: 'доріжку закрито',
  nodeRefusedSignal: 'вузол не прийняв сигнал',
  directRetryFailed: 'Пряму доріжку підняти не вдалось — лишаємось на рації.',
  channelRestored: 'Канал відновлено',
  wOffer: 'пропозицію',
  wAnswer: 'відповідь',
  wSignals: 'сигнали дзвінка',
  micNoSound: 'мікрофон не віддає звук',
  noAccessMark: 'доступ до мікрофона',
  notFoundMark: 'не знайдено',
  busyMark: 'зайнято',
  noAnswerHead: 'співрозмовник не бере слухавку',
  detailSep: ' — але ',
  radioNoWebm: 'Цей браузер не вміє записувати webm/opus — рація тут неможлива.',
  radioMicSilent: 'Мікрофон не віддає звук для рації — записувач не запустився.',
} as const;

export const HANGUP_LOST_MARK = 'не доїхав';
export const HANGUP_LOST_TAIL = ' — але сигнал «поклав слухавку» не доїхав, у співрозмовника може ще дзвонити';

export const REASON_NO_ANSWER_NOTE =
  'Співрозмовник не бере слухавку. Дзвінок доїхав до його вузла, але відповіді ще немає.';

export interface RelayTally {
  candidates: number;
  errors: number;
  lastCode: number | null;
  lastText: string;
}

export const stallNoteText = (turn: boolean, relay?: RelayTally): string => {
  if (!turn) {
    return 'Не вдається зʼєднатися напряму. Без TURN-сервера дзвінок за суворим NAT неможливий — це відома межа поточної версії.';
  }
  if (relay && relay.errors > 0 && relay.candidates === 0) {
    const code = relay.lastCode ? ` (код ${relay.lastCode}${relay.lastText ? `: ${relay.lastText}` : ''})` : '';
    return (
      'Не вдається зʼєднатися напряму, і ретранслятор не дав жодної адреси: ' +
      `${relay.errors} відмов${relay.errors === 1 ? 'а' : ''} від TURN${code}. ` +
      'Сервер відповідає, але не може виділити порт для медіа — доки це не ' +
      'полагоджено на його боці, дзвінок за суворим NAT не встане.'
    );
  }
  if (relay && relay.candidates === 0) {
    return (
      'Не вдається зʼєднатися напряму. Ретранслятор оголошено, але жодної ' +
      'relay-адреси браузер поки не отримав — пробуємо далі.'
    );
  }
  return 'Не вдається зʼєднатися напряму. Пробуємо через ретранслятор — це може зайняти ще кілька секунд.';
};

export const signalRefusedText = (what: string, detail: string): string =>
  detail
    ? `Вузол не прийняв ${what}: ${detail}`
    : `Вузол не прийняв ${what} — дзвінок нікуди не поїхав`;

export const iceLostText = (lost: number): string =>
  ` Вузол не прийняв ${lost} ICE-кандидат${lost === 1 ? '' : lost < 5 ? 'и' : 'ів'} — найімовірніше саме тому дороги й не знайшлось.`;

export const iceRejectedText = (n: number): string => ` Браузер відхилив ${n} кандидат(ів) співрозмовника.`;
