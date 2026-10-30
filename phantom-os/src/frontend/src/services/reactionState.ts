/**
 * Згортання смайлів у полях рядка. Чистий перетворювач — без мережі й часу.
 *
 * ВАЖЛИВО про межі цього файла. До 18.09 він був УСІМ, що месенджер мав із
 * реакцій: `toggleReaction` міняв цей масив, і на цьому дія закінчувалась. Тут
 * немає й не буде мережі — правду про позначки складає журнал вузла
 * (`PeerMark.foldAll` у companion), а це лише оптимістичний домальовок на ті
 * пів секунди, доки вузол не відповів.
 *
 * Саме тому тут зʼявилось `mine`: домальовок мусить ВІДРІЗНЯТИСЬ від правди.
 * Смайл, який виглядає однаково «до» і «після» відповіді вузла, і є те, що
 * власник називає «начебто поставилось».
 */

import type { Reaction, ReactionRoad } from '../types/messenger';
import { tNow } from '../components/chat/tNow';

export type ReactionAction = 'add' | 'remove';

/** Хто ставить: ідентифікатор вузла і, якщо відоме, імʼя для підказки. */
export interface ReactionAuthor {
  id: string;
  name?: string;
}

/**
 * Імена й автори мусять лишатись однакової довжини.
 *
 * Інакше підказка зʼїхала б на один: під смайлом стояло б імʼя сусіда. Коли
 * зійтись не виходить (додаємо автора, чийого імені не знаємо), імен не
 * лишається зовсім — «вузол не назвав» чесніше за зсунутий список.
 */
function withAuthor(reaction: Reaction, author: ReactionAuthor): Reaction {
  const users = [...reaction.users, author.id];
  const hadNames = Array.isArray(reaction.names) && reaction.names.length === reaction.users.length;
  const names = hadNames && author.name ? [...reaction.names!, author.name] : undefined;
  // `names` знімається ЯВНО, а не лишається зі спреду: список на один
  // коротший підписав би кожного автора іменем сусіда.
  const { names: _old, ...rest } = reaction;
  return {
    ...rest,
    users,
    count: users.length,
    ...(names ? { names } : {}),
  };
}

function withoutAuthor(reaction: Reaction, authorId: string): Reaction {
  const keep = reaction.users.map((id) => id !== authorId);
  const users = reaction.users.filter((_, i) => keep[i]);
  const names = Array.isArray(reaction.names) && reaction.names.length === reaction.users.length
    ? reaction.names.filter((_, i) => keep[i])
    : undefined;
  // Своя позначка знята — дороги в неї більше немає. Лишений `mine` світив би
  // годинником під смайлом, якого вже не тримаємо.
  const { mine: _road, ...rest } = reaction;
  return {
    ...rest,
    users,
    count: users.length,
    ...(names ? { names } : {}),
  };
}

/**
 * [road] — дорога ВЛАСНОЇ позначки, яку домальовуємо. Для чужої позначки (тієї,
 * що приїхала з дроту) його не передають: чужа вже доїхала за самим фактом
 * того, що ми її бачимо.
 */
export function applyReaction(
  reactions: Reaction[] | undefined,
  emoji: string,
  user: string | ReactionAuthor,
  action: ReactionAction,
  road?: ReactionRoad,
): Reaction[] {
  const author: ReactionAuthor = typeof user === 'string' ? { id: user } : user;
  const next = (reactions || []).map((r) => ({
    ...r,
    users: [...r.users],
    ...(r.names ? { names: [...r.names] } : {}),
  }));
  const index = next.findIndex((r) => r.emoji === emoji);

  if (action === 'add') {
    if (index === -1) {
      return [
        ...next,
        {
          emoji,
          count: 1,
          users: [author.id],
          ...(author.name ? { names: [author.name] } : {}),
          ...(road ? { mine: road } : {}),
        },
      ];
    }
    const grown = next[index].users.includes(author.id)
      ? next[index]
      : withAuthor(next[index], author);
    next[index] = road ? { ...grown, mine: road } : grown;
    return next;
  }

  if (index === -1) return next;
  next[index] = withoutAuthor(next[index], author.id);
  return next.filter((r) => r.users.length > 0);
}

export function reactionActionFor(
  reactions: Reaction[] | undefined,
  emoji: string,
  userId: string,
): ReactionAction {
  return reactions?.find((r) => r.emoji === emoji)?.users.includes(userId) ? 'remove' : 'add';
}

/**
 * Хто поставив — СЛОВАМИ, а не ключами вузлів.
 *
 * До 18.09 підказка під смайлом складалась як `r.users.join(', ')`, тобто
 * показувала людині `operator_me, 8f3ac1d0`. Імена знає лише вузол; коли він
 * їх не назвав, коротка форма ключа чесніша за вигадане імʼя.
 */
export function reactionAuthors(reaction: Reaction, selfId: string, selfLabel = tNow('reactions.me')): string[] {
  return reaction.users.map((id, i) => {
    if (id === selfId) return selfLabel;
    const name = reaction.names?.[i];
    return name && name.trim() ? name : id.slice(0, 8);
  });
}
