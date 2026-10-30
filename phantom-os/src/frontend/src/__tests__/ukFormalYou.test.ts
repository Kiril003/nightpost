/**
 * Український застосунок звертається до людини на «ви» (profiles-v4 SPEC:345).
 * Сторож червоніє на будь-яку «ти»-форму в uk-рядках: займенники, дієслова 2-ї особи
 * однини й наказові форми. Цитати в «…» — це слова самої людини, їх не чіпаємо.
 * Виняток — рядки, які людина сама каже асистентові чи другові.
 */
import { describe, it, expect } from 'vitest';
import { uk } from '../i18n/messages/uk';

const L = "\\p{L}'’ʼ";
const PRONOUNS = ['ти', 'тебе', 'тобі', 'тобою', 'твій', 'твоя', 'твоє', 'твої', 'твого', 'твоєї', 'твоїм', 'твоїх', 'твоєму', 'твоїми', 'твою', 'твоїй', 'твоєю'];
const IMPERATIVES = [
  'спробуй', 'перевір', 'придумай', 'скажи', 'увімкни', 'вимкни', 'обери', 'вибери', 'додай', 'встав', 'перезапусти',
  'зачекай', 'розкажи', 'подивись', 'глянь', 'набери', 'перезавантаж', 'тримай', 'торкнись', 'напиши', 'дозволь', 'візьми',
  'заповни', 'зніми', 'перемкни', 'заверши', 'підійди', 'перейди', 'дочекайся', 'попроси', 'опиши', 'вийди', 'увійди',
  'під’єднайся', "під'єднайся", 'підʼєднайся', 'введи', 'натисни', 'наведи', 'згенеруй', 'відкрий', 'закрий', 'знайди',
  'онови', 'познач', 'надішли', 'покажи', 'створи', 'запроси', 'почни', 'зроби',
];
const NOT_VERBS = new Set(['кеш', 'миш']);

const PRONOUN_RE = new RegExp(`(?<![${L}])(${PRONOUNS.join('|')})(?![${L}])`, 'iu');
const IMPERATIVE_RE = new RegExp(`(?<![${L}])(${IMPERATIVES.join('|')})(?![${L}])`, 'iu');
const VERB_2SG_RE = new RegExp(`(?<![${L}])([${L}]+(?:еш|єш|иш|їш)(?:ся)?)(?![${L}])`, 'giu');

/** Рядки, де говорить сама людина — асистентові або другові. */
const SPEAKER_IS_USER: Record<string, string> = {
  'chatArea.starterAbilities': 'prompt the user sends to the assistant',
  'chatArea.starterWrite': 'prompt the user sends to the assistant',
  'chat.quick.plan.prompt': 'prompt the user sends to the assistant',
  'chat.quick.risks.label': 'button = the prompt the user sends',
  'chat.quick.risks.prompt': 'prompt the user sends to the assistant',
  'chat.quick.settings.label': 'button = the prompt the user sends',
  'chat.quick.settings.prompt': 'prompt the user sends to the assistant',
  'assist.prompt.translate': 'instruction sent to the model',
  'assist.prompt.summary': 'instruction sent to the model',
  'assist.prompt.tasks': 'instruction sent to the model',
  'phantomKnows.forget': 'the command word the user says to the assistant',
  'msgr.add.shareLead': "the user's own invite text to a friend",
  'planSheet.pingText': 'message the user sends to a friend',
};

function tyForms(value: string): string[] {
  const text = value.replace(/«[^»]*»/g, ' ');
  const hits: string[] = [];
  const p = text.match(PRONOUN_RE);
  if (p) hits.push(p[1]);
  const i = text.match(IMPERATIVE_RE);
  if (i) hits.push(i[1]);
  for (const m of text.matchAll(VERB_2SG_RE)) if (!NOT_VERBS.has(m[1].toLowerCase())) hits.push(m[1]);
  return hits;
}

describe('uk звертається на «ви»', () => {
  it('ловить «ти»-форми й не ловить схожі слова', () => {
    expect(tyForms('Що я про тебе знаю')).toEqual(['тебе']);
    expect(tyForms('Спробуй ще раз')).toEqual(['Спробуй']);
    expect(tyForms('Ним заходиш з інших пристроїв')).toEqual(['заходиш']);
    expect(tyForms('Твоя частка')).toEqual(['Твоя']);
    expect(tyForms('Тихо цього тижня, тим краще; тиждень; Тиша')).toEqual([]);
    expect(tyForms('Очистити кеш медіа')).toEqual([]);
    expect(tyForms('Будь ласка, увійдіть знову. Спробуйте ще раз.')).toEqual([]);
    expect(tyForms('Або скажіть «запамʼятай …».')).toEqual([]);
  });

  it('жоден uk-рядок не звертається на «ти»', () => {
    const offenders = Object.entries(uk)
      .filter(([key]) => !(key in SPEAKER_IS_USER))
      .map(([key, value]) => [key, value, tyForms(String(value))] as const)
      .filter(([, , hits]) => hits.length > 0)
      .map(([key, value, hits]) => `${key}: ${hits.join(', ')} — «${value}»`);
    expect(offenders).toEqual([]);
  });

  it('кожен виняток ще існує', () => {
    const keys = new Set(Object.keys(uk));
    expect(Object.keys(SPEAKER_IS_USER).filter((k) => !keys.has(k))).toEqual([]);
  });
});
