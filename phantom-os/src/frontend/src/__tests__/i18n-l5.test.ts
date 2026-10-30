/**
 * Лінія 5 каталогу: слова сервісів і форматерів поза компонентами
 * (час «Вчора», слова дзвінка, місце людини, зникнення, межі файлів, дії над листом).
 *
 * Без англійського значення в parts/l5.ts кожен із цих рядків лишається
 * українським, тож англійські перевірки нижче червоні.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { uk, en } from '../i18n/messages/parts/l5';
import { chatTimeLabel, displayTitle, isUnnamed, kindLine, selfWord, UNNAMED } from '../components/messenger/chatListFormat';
import { formatRelativeClock } from '../utils/format';
import { callStateLine, endedLine, linkLine, roomFullLine } from '../services/callWords';
import { agoWord, mineLine, nearWord, minutesLeft, WHERE_PRECISIONS } from '../services/peerWhere';
import { durationWord as pulseDuration } from '../services/livingPulse';
import { timerLine, DISAPPEAR_CHOICES } from '../services/disappearWords';
import { humanSize, mediaLimitLabel, extensionOf } from '../services/messengerMedia';
import { hintWords, whenWords } from '../services/letterActions';
import { intentLine, oblastWord } from '../services/intentWords';
import { distanceLabel, durationLabel, profileWord } from '../services/peerWire';
import { sayRefusal as sayAvatarRefusal } from '../services/avatarCompress';
import { dueWords, sharedSummary } from '../services/peerBetween';
import { iceRoadLabel } from '../services/iceRoads';
import { heardLine, SHARED_TABS } from '../services/peerShared';
import { linkState } from '../services/phantomHome';
import { seenWord, levelTitle } from '../services/deviceApi';
import { footLabel, whyLabel } from '../services/callRoom';
import { uploadFailureWord } from '../stores/messengerStore';

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));
const CYR = /[А-Яа-яІіЇїЄєҐґ]/;

afterEach(() => setLocale('uk'));

describe('lane l5 catalogue', () => {
  it('кожен український ключ має англійське значення без кирилиці', () => {
    expect(Object.keys(uk).filter((k) => !(k in en))).toEqual([]);
    const leaked = Object.entries(en).filter(([, v]) => CYR.test(v));
    expect(leaked).toEqual([]);
  });

  it('плейсхолдери збігаються між мовами', () => {
    const ph = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join(',');
    const bad = Object.keys(uk).filter((k) => ph((uk as Record<string, string>)[k]) !== ph((en as Record<string, string>)[k]));
    expect(bad).toEqual([]);
  });

  it('українська лишається типовою і слово в слово', () => {
    const yesterday = new Date(Date.now() - 86_400_000).toISOString();
    expect(chatTimeLabel(yesterday)).toBe('Вчора');
    expect(formatRelativeClock(30)).toBe('щойно');
    expect(formatRelativeClock(300)).toBe('5 хв');
    expect(endedLine('відхилено')).toBe('Дзвінок відхилено');
    expect(roomFullLine(8)).toBe('У розмові може бути щонайбільше 8 людей');
    expect(kindLine('voice')).toBe('Голосове');
    expect(selfWord('me')).toBe('Ви');
    expect(timerLine(86_400, true, '')).toBe('Ви: повідомлення зникають через 1 день');
    expect(humanSize(2048)).toBe('2 КБ');
    expect(whenWords(new Date(2026, 9, 10, 19, 40), true, new Date(2026, 9, 9, 12, 0))).toBe('завтра о 19:40');
    expect(distanceLabel(3400)).toBe('3,4 км');
  });

  it('англійська: час і слова списку розмов', () => {
    setLocale('en');
    expect(chatTimeLabel(new Date(Date.now() - 86_400_000).toISOString())).toBe('Yesterday');
    expect(formatRelativeClock(300)).toBe('5 min');
    expect(kindLine('voice', { voiceData: { duration: 12 } } as never)).toBe('Voice message · 0:12');
    expect(kindLine('image')).toBe('Photo');
    expect(selfWord('me')).toBe('You');
    expect(displayTitle('Вузол 4d839df7')).toBe('Name not shared');
    expect(isUnnamed(displayTitle('Вузол 4d839df7'))).toBe(true);
    expect(isUnnamed(UNNAMED)).toBe(true);
    expect(displayTitle(UNNAMED)).toBe('Name not shared');
  });

  it('англійська: дзвінок', () => {
    setLocale('en');
    expect(callStateLine({ state: 'calling', media: 'audio', stall: null, endedReason: null })).toBe('Calling…');
    expect(endedLine('відхилено')).toBe('Call declined');
    expect(endedLine('щось невідоме')).toBe('Couldn’t connect. Try again in a little while');
    expect(endedLine('доступ до мікрофона й камери не дано', 'video')).toMatch(/camera/);
    expect(roomFullLine(8)).toBe('A call can have at most 8 people');
    expect(linkLine({ radio: null, radioReason: null, linkNote: 'x', videoDropped: false })).toMatch(/Weak connection/);
    expect(footLabel({ foot: 'talking' } as never)).toBe('in the call');
    expect(whyLabel('busy')).toBe('busy');
  });

  it('англійська: місце, присутність, зникнення', () => {
    setLocale('en');
    const NOW = Date.UTC(2026, 9, 9, 12, 0);
    expect(nearWord('same', 'district')).toBe('in the same district');
    expect(agoWord(NOW - 5 * 60_000, NOW)).toBe('5 min ago');
    expect(minutesLeft(NOW + 90 * 60_000, NOW)).toBe('1 h 30 min left');
    expect(mineLine({ precision: 'quarter', until_ms: null } as never, NOW).title).toBe('You are sharing where you are');
    expect(WHERE_PRECISIONS.map((p) => p.label)).toEqual(['District', 'Block', 'Exact']);
    expect(pulseDuration(NOW - 65 * 60_000, NOW)).toBe('1 h 5 min');
    expect(timerLine(604_800, false, '')).toBe('Your contact: messages disappear after 1 week');
    expect(DISAPPEAR_CHOICES.map((c) => c.label)).toEqual(['Off', '1 day', '1 week', '1 month']);
    expect(heardLine(new Date(NOW - 10 * 60_000).toISOString(), null, NOW)).toBe('reachable 10 min ago');
    expect(SHARED_TABS.map((t) => t.label)).toEqual(['Media', 'Files', 'Voice', 'Links', 'Places', 'Payments', 'Meetups']);
    expect(seenWord(new Date(NOW - 5 * 60_000).toISOString(), NOW)).toBe('seen 5 min ago');
    expect(levelTitle('ask')).toBe('Do it after my “yes”');
  });

  it('англійська: розміри, маршрут, дії над листом, прохання до ПК', () => {
    setLocale('en');
    expect(humanSize(2048)).toBe('2 KB');
    expect(humanSize(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(extensionOf('noext')).toBe('FILE');
    expect(mediaLimitLabel()).toMatch(/^up to \d/);
    expect(distanceLabel(3400)).toBe('3.4 km');
    expect(distanceLabel(337)).toBe('337 m');
    expect(durationLabel(4300)).toBe('1 h 11 min');
    expect(profileWord('FOOT')).toBe('on foot');
    const now = new Date(2026, 9, 9, 12, 0);
    expect(whenWords(new Date(2026, 9, 10, 19, 40), true, now)).toBe('tomorrow at 19:40');
    expect(whenWords(new Date(2026, 9, 9, 18, 0), true, now)).toBe('at 18:00');
    expect(hintWords({ kind: 'remind', at: now, when: 'at 18:00' }).say).toBe('Remind you at 18:00?');
    expect(hintWords({ kind: 'copy-parcel', value: '20451188' }).button).toBe('Copy');
    expect(oblastWord('lviv')).toBe('Lviv');
    expect(oblastWord('unknown-id')).toBe('unknown-id');
    expect(intentLine({ phase: 'AT_PC', tries: [] } as never)).toBe('your home PC got it · preparing a reply');
    expect(uploadFailureWord({ name: 'NotReadableError' })).toBe('the phone would not let the file be read — pick it again');
  });

  it('англійська: фото, спільне, ICE', () => {
    setLocale('en');
    expect(sayAvatarRefusal({ kind: 'no_canvas' } as never)).toMatch(/^This app can’t compress/);
    const shared = { media: [1, 2], places: [1], files: [1, 2], voice: [], links: [] } as never;
    expect(sharedSummary(shared)).toBe('2 photos · 1 place · 2 files');
    expect(dueWords('2026-10-09', new Date(2026, 9, 9, 12))).toBe('today');
    expect(iceRoadLabel(null)).toBe('the node hasn’t been asked yet');
    setLocale('uk');
    expect(sharedSummary(shared)).toBe('2 фото · 1 місце · 2 файли');
  });

  it('англійська: рядок «без інтернету» не українською', () => {
    setLocale('en');
    const s = linkState({ link: { online: false, direct: true } } as never, false, null, 2);
    expect(s.word).toBe('No internet');
    expect(s.works.join(' ')).not.toMatch(CYR);
    expect(s.works[0]).toMatch(/^2 messages are queued/);
  });
});
