/**
 * Питання до Nightpost про те, що на екрані: разовий `/messenger/self/ask`
 * з фактами поверхні в `system`. Відповіді не вигадуємо ніколи — помилка
 * стає чесною причиною (без розуму, без звʼязку, ліміт).
 */
import { ApiError } from './api';
import { phantomSelfApi } from './phantomSelfApi';
import type { AskContext, AskSurface } from './askContext';

export type AskNone = 'no_mind' | 'offline' | 'limit' | 'failed';
export type AskOutcome = { kind: 'answer'; text: string } | { kind: 'none'; reason: AskNone; detail?: string };

/** Телефон ріже `system` на 2000 — ріжемо самі, щоб правило мови не загубилось. */
const SYSTEM_MAX = 2000;

const ROLE: Record<AskSurface, string> = {
  day: "You are Nightpost, the user's assistant. Answer about the user's day from the agenda below.",
  wallet: "You are Nightpost, the user's assistant. Answer about the user's money from the wallet data below. Never move money.",
  person: "You are Nightpost, the user's assistant. Answer about this person and the user's conversation with them.",
  chat: "You are Nightpost, the user's assistant. Answer about this conversation.",
  group: "You are Nightpost, the user's assistant. Answer about this group conversation.",
  compose:
    "You are Nightpost, helping the user write a message. Return only the rewritten message text, nothing else: no quotes, no comments.",
};

const RULES = 'Reply in the language of the question, briefly. Use only the context; if it lacks the answer, say so.';

/** Чернетку пише сам користувач комусь іншому — її звертання не чіпаємо. */
const ADDRESS = 'In Ukrainian address the user formally as «ви» (вам, ваш, у вас), never «ти», «тобі» or «твій».';

export function systemFor(ctx: AskContext): string {
  const rules = ctx.surface === 'compose' ? RULES : `${RULES} ${ADDRESS}`;
  const head = `${ROLE[ctx.surface]} ${rules}\nContext:\n`;
  return head + ctx.facts.slice(0, Math.max(0, SYSTEM_MAX - head.length));
}

const humanDetail = (msg: string | undefined): string | undefined => {
  const s = (msg ?? '').trim();
  return s && s !== 'Unknown error' ? s : undefined;
};

export function noneOf(err: unknown): Extract<AskOutcome, { kind: 'none' }> {
  if (err instanceof ApiError) {
    if (err.status === 503 && err.code === 'no_mind') {
      const detail = humanDetail(err.message);
      return detail ? { kind: 'none', reason: 'no_mind', detail } : { kind: 'none', reason: 'no_mind' };
    }
    // ПК-вузол цієї дороги не має: розуму тут немає, пояснення не вигадуємо.
    if (err.status === 404) return { kind: 'none', reason: 'no_mind' };
    if (err.status === 429) return { kind: 'none', reason: 'limit' };
    if (err.status === 504 || err.code === 'timeout') return { kind: 'none', reason: 'offline' };
    return { kind: 'none', reason: 'failed' };
  }
  if (err instanceof TypeError) return { kind: 'none', reason: 'offline' };
  return { kind: 'none', reason: 'failed' };
}

export async function askAbout(ctx: AskContext, question: string): Promise<AskOutcome> {
  const q = question.trim();
  if (!q) return { kind: 'none', reason: 'failed' };
  try {
    const res = await phantomSelfApi.ask(q, systemFor(ctx));
    const text = (res?.answer ?? '').trim();
    return text ? { kind: 'answer', text } : { kind: 'none', reason: 'failed' };
  } catch (err) {
    return noneOf(err);
  }
}

export type ComposeOp = 'shorter' | 'warmer' | 'english';

/** Питання для операції над чернеткою: сам текст іде всередині, щоб розум не гадав. */
export function composeQuestion(op: ComposeOp | 'again' | 'write', draft: string, request = ''): string {
  switch (op) {
    case 'shorter':
      return `Make this message shorter, keep its meaning and language:\n${draft}`;
    case 'warmer':
      return `Make this message warmer and friendlier, keep its language:\n${draft}`;
    case 'english':
      return `Translate this message into natural English:\n${draft}`;
    case 'again':
      return `Rewrite this message another way, keep its meaning and language:\n${draft}`;
    case 'write':
      return `Write the message: ${request}`;
  }
}
