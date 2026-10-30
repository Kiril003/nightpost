/**
 * Прохід строку тимчасових груп зустрічі — правило `expirySweep`
 * (`services/meetup/expiry.ts`), прикладене до стору.
 *
 * Група, найновіша зустріч якої прострочена і яку ніхто не лишив, іде в
 * місцевий архів (`chatArchive`) — оборотно, без стирання і без кадру в
 * дріт. Раз на зустріч (`sweptMeetups`): дістав сам — назад не ховається.
 * Сховану проходом групу, яку потім хтось лишив, прохід повертає сам.
 * Читає лише ті листи, що вже є в сторі; група, чиїх листів телефон ще не
 * віддав, пройде наступним проходом.
 */
import { useMessengerStore } from '../stores/messengerStore';
import { chatArchive } from './chatArchive';
import { expirySweep, isPlanGroup, keptBack, planSweep, sweptMeetups, type GroupMeetups } from './meetup/expiry';
import { MEETUP_KIND, parseMeetupData } from '../types/meetup';
import type { ChatPlan } from '../types/messenger';

/**
 * Сховати прострочені групи і повернути ті, які прохід сховав, а хтось
 * потім лишив (`keep:1` дійшов пізніше або натиснули «Лишити» на вже
 * простроченій картці). Повертає id схованих розмов.
 */
export function sweepMeetupGroups(now = Date.now()): string[] {
  const st = useMessengerStore.getState();
  const planRows: Array<{ chatId: string; plan: ChatPlan; archived: boolean }> = [];
  const groups: GroupMeetups[] = [];
  const shelved: GroupMeetups[] = [];
  for (const c of st.chats) {
    if (c.type !== 'group') continue;
    if (c.plan) {
      planRows.push({ chatId: c.id, plan: c.plan, archived: Boolean(c.archived) });
      continue;
    }
    if (!isPlanGroup(c.messages ?? [])) continue;
    const cards: GroupMeetups['cards'] = [];
    for (const m of c.messages ?? []) {
      if (m.type !== MEETUP_KIND || m.isDeleted) continue;
      const meetup = parseMeetupData(m.meetupData);
      if (!meetup) continue;
      const sentAt = m.sentAt ? Date.parse(m.sentAt) : NaN;
      cards.push({ meetup, marks: m.cardMarks, sentAt: Number.isFinite(sentAt) ? sentAt : 0 });
    }
    if (cards.length) (c.archived ? shelved : groups).push({ chatId: c.id, cards });
  }
  const done = sweptMeetups.all();
  const fromPlans = planSweep(planRows, now, done);
  const hide = [...fromPlans.hide, ...expirySweep(groups, now, done)];
  const back = [...fromPlans.back, ...keptBack(shelved, now, done)];
  if (!hide.length && !back.length) return [];
  for (const h of hide) {
    chatArchive.set(h.chatId, true);
    sweptMeetups.add(h.meetupId);
  }
  for (const b of back) {
    chatArchive.set(b.chatId, false);
    sweptMeetups.remove(b.meetupId);
  }
  const ids = new Set(hide.map((h) => h.chatId));
  const backIds = new Set(back.map((b) => b.chatId));
  useMessengerStore.setState((s) => ({
    chats: s.chats.map((c) =>
      ids.has(c.id) ? { ...c, archived: true } : backIds.has(c.id) ? { ...c, archived: false } : c,
    ),
  }));
  return [...ids];
}

/** Прохід при кожній зміні списку розмов і раз на хвилину. Повертає відписку. */
export function installMeetupExpirySweep(): () => void {
  const run = () => {
    try {
      sweepMeetupGroups(Date.now());
    } catch {
      /* прохід не має права зупинити месенджер */
    }
  };
  run();
  const off = useMessengerStore.subscribe((s, p) => {
    if (s.chats !== p.chats) run();
  });
  const t = setInterval(run, 60_000);
  return () => {
    off();
    clearInterval(t);
  };
}
