/**
 * Жива картка листа: заголовок, рядок стану, живий вміст одразу й одна дія.
 * Повний перегляд і правка — аркуш на весь екран.
 */
import React, { useEffect, useState } from 'react';
import {
  ChartBarBig,
  ChartGantt,
  Check,
  FileText,
  GitCompare,
  KeyRound,
  Layers,
  ListChecks,
  Receipt,
  Shapes,
  Sheet,
  SquareKanban,
  SquareTerminal,
  Users,
  Video,
  Webhook,
  Workflow,
  X,
  type LucideIcon,
} from 'lucide-react';
import type { Message, TableData } from '../../../types/messenger';
import { useMessengerStore } from '../../../stores/messengerStore';
import { soundFx } from '../../../utils/messengerSound';
import { cardFieldOf } from '../../../services/composerCards';
import { doorCodeOfMessage, isDoorCode } from '../../../services/codeWindow';
import { confirm, preTick, type DayItem } from '../../../services/timePoll';
import { policy, readEnv } from '../../../feel/motionTokens';
import { FormSheet } from '../FormSheet';
import { CardShell, CheckDot } from './CardShell';
import { ChecklistEditor, ExpenseEditor, TableGrid } from './CardEditors';
import { PERSON_KIND, PersonCard } from './PersonCard';
import { GiftCard } from './GiftCard';
import { GIFT_KIND } from '../gifts/giftCatalogue';
import { DAY_KIND, DayCard } from './DayCard';
import { GLANCE_KIND, GlanceCard } from './GlanceCard';
import { MONEY_KIND, MoneyCard } from './MoneyCard';
import { ASSISTANT_PEER_ID } from '../../../services/assistantChat';
import {
  canVote,
  cardKey,
  cellText,
  checklistOf,
  checklistPatch,
  checklistStatus,
  expensesOf,
  expensesPatch,
  expensesStatus,
  money,
  numericColumn,
  pollOf,
  pollPatch,
  pollStatus,
  tableOf,
  tableStatus,
  THEATRE_CARD_KINDS,
  tick,
  timeLabel,
  vote,
  votersCount,
  type Poll,
} from './cardModel';
import { currentDateTag, tNow } from '../../chat/tNow';
import { userMarks } from '../../../services/userMarks';
const PEEK = 3;

/**
 * Правка картки їде людині новою версією того самого ключа — стрічка обох
 * складе її з першою (`foldCards`). Картка без ключа (старі роди, що дротом
 * не їздять) правиться лише в себе: везти її нікуди.
 */
export function reviseCard(msg: Message, fields: Partial<Message>) {
  const store = useMessengerStore.getState();
  const next = { ...msg, ...fields } as Message;
  const field = cardFieldOf(msg.type);
  if (field && cardKey(next)) {
    void store.sendCard(msg.type, next[field]);
    return;
  }
  store.updateMessage(msg.id, fields);
}


const More: React.FC<{ hidden: number }> = ({ hidden }) =>
  hidden > 0 ? <div className="px-4 pt-1 text-caption text-ink-3">{tNow('messageCard.more', { n: hidden })}</div> : null;

const ChecklistCard: React.FC<{ msg: Message; me: string; meId: string }> = ({ msg, me, meId }) => {
  const [open, setOpen] = useState(false);
  const list = checklistOf(msg)!;
  const mine = isAuthor(msg, meId);
  const write = (items: typeof list.items) => reviseCard(msg, checklistPatch(msg, items, me));
  const title = list.title.trim() || tNow('messageCard.checklist');
  return (
    <>
      <CardShell
        testId="card-checklist"
        kind={{ label: tNow('messageCard.checklist'), icon: <ListChecks strokeWidth={1.75} /> }}
        title={title}
        status={checklistStatus(list)}
        action={{ label: mine ? tNow('msgTypes.edit') : tNow('messageCard.open'), onClick: () => setOpen(true) }}
      >
        {list.items.slice(0, PEEK).map((it) => (
          <button
            key={it.id}
            type="button"
            onClick={() => {
              soundFx.playTap();
              write(tick(list.items, it.id, me));
            }}
            aria-pressed={it.done}
            className="w-full min-h-11 px-4 flex items-center gap-3 text-left active:bg-raised"
          >
            <CheckDot done={it.done} />
            <span className={`flex-1 min-w-0 text-callout truncate ${it.done ? 'text-ink-3 line-through' : 'text-ink'}`}>
              {it.text}
            </span>
            {it.done && it.doneBy && (
              <span data-testid="checklist-done-by" className="shrink-0 max-w-[40%] text-caption text-ink-3 truncate">
                {it.doneBy}
              </span>
            )}
          </button>
        ))}
        <More hidden={list.items.length - PEEK} />
      </CardShell>
      <FormSheet open={open} onClose={() => setOpen(false)} title={title} tall testId="sheet-checklist">
        <p className="px-4 pb-2 text-caption text-ink-3">{checklistStatus(list)}</p>
        <ChecklistEditor items={list.items} onChange={write} by={me} fixed={!mine} />
      </FormSheet>
    </>
  );
};

/** Автор картки — той, хто надіслав її першу версію. */
function isAuthor(msg: Message, meId: string): boolean {
  return !!msg.isSelf || (!!meId && msg.senderId === meId);
}

/** Імена людей розмови: учасники й ті, хто в ній писав. */
function useNames(meId: string, meName: string): (id: string) => string {
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === s.activeChatId));
  return (id) => {
    if (id === meId) return meName;
    const m = chat?.members?.find((x) => x.id === id);
    if (m?.name) return m.name;
    return chat?.messages.find((x) => x.senderId === id && x.senderName)?.senderName ?? '';
  };
}

const VoterNames: React.FC<{ ids: string[]; name: (id: string) => string }> = ({ ids, name }) => {
  const named = ids.map(name).filter(Boolean);
  if (named.length === 0) return null;
  const shown = named.slice(0, 3).join(', ');
  const rest = named.length - 3;
  return (
    <span data-testid="poll-voters" className="relative block text-caption text-ink-3 truncate">
      {rest > 0 ? tNow('msgTypes.votersMore', { names: shown, n: rest }) : shown}
    </span>
  );
};

const PollCard: React.FC<{ msg: Message; meId: string; meName: string; day?: readonly DayItem[] | null }> = ({ msg, meId, meName, day }) => {
  const poll = pollOf(msg)!;
  const total = votersCount(poll);
  const top = Math.max(0, ...poll.options.map((o) => o.voters.length));
  const stateKey = `${msg.id}:${poll.options.map((o) => o.id).join(',')}`;
  const hasMyVote = poll.options.some((o) => o.voters.includes(meId));
  const pre = poll.kind === 'time' ? preTick(poll.options.map((o) => o.text), day ?? null) : null;
  const computeInitialPicks = () => {
    if (hasMyVote) return poll.options.map((o) => o.voters.includes(meId));
    if (pre) return pre;
    return poll.options.map(() => false);
  };
  const [pickState, setPickState] = useState<{ key: string; picks: boolean[] }>(() => ({
    key: stateKey,
    picks: computeInitialPicks(),
  }));
  const picks = pickState.key === stateKey ? pickState.picks : computeInitialPicks();

  if (poll.kind === 'time') {
    const status = pre === null && !hasMyVote ? tNow('chats5.noDay') : pollStatus(poll);
    return (
      <CardShell
        testId="card-poll"
        kind={{ label: tNow('chats5.times'), icon: <ChartBarBig strokeWidth={1.75} /> }}
        title={poll.question.trim() || tNow('chats5.times')}
        status={status}
        action={{ label: tNow('chats5.confirm'), onClick: () => confirm(msg, picks, meId), primary: true }}
      >
        <div className="px-2 space-y-1">
          {poll.options.map((o, i) => {
            const n = o.voters.length;
            const mine = o.voters.includes(meId);
            const picked = !!picks[i];
            const ring = picked && !mine;
            const pct = total > 0 ? Math.round((n / total) * 100) : 0;
            return (
              <button
                key={o.id}
                type="button"
                onClick={() => {
                  soundFx.playTap();
                  setPickState({
                    key: stateKey,
                    picks: picks.map((p, idx) => (idx === i ? !p : p)),
                  });
                }}
                aria-pressed={picked}
                data-preticked={ring ? 'true' : undefined}
                className="relative w-full min-h-11 rounded-control overflow-hidden flex items-center gap-3 px-2.5 text-left active:opacity-80"
              >
                <span
                  aria-hidden
                  className={`absolute inset-y-0 left-0 transition-[width] duration-standard ease-standard ${
                    mine ? 'bg-accent/25' : n > 0 && n === top ? 'bg-raised' : 'bg-raised/60'
                  }`}
                  style={{ width: `${pct}%` }}
                />
                <span className="relative">
                  {ring ? (
                    <span aria-hidden className="w-5 h-5 shrink-0 rounded-full border border-accent-line block" />
                  ) : (
                    <CheckDot done={mine && picked} />
                  )}
                </span>
                <span className="relative flex-1 min-w-0 text-callout text-ink truncate tnum">
                  {timeLabel(o.text)}
                </span>
                {total > 0 && <span className="relative text-caption text-ink-2 tnum">{n}</span>}
              </button>
            );
          })}
        </div>
      </CardShell>
    );
  }

  return <ChoicePoll msg={msg} poll={poll} meId={meId} meName={meName} />;
};

const ChoicePoll: React.FC<{ msg: Message; poll: Poll; meId: string; meName: string }> = ({ msg, poll, meId, meName }) => {
  const name = useNames(meId, meName);
  const total = votersCount(poll);
  const top = Math.max(0, ...poll.options.map((o) => o.voters.length));
  const voted = poll.options.some((o) => o.voters.includes(meId));
  const open = canVote(poll, meId);
  const results = voted || !!poll.closed;
  const quiz = !!poll.correct;
  const label = quiz ? tNow('msgTypes.quiz') : poll.anonymous ? tNow('msgTypes.anonPoll') : tNow('messageCard.poll');
  const status = poll.closed ? `${tNow('msgTypes.closed')} · ${pollStatus(poll)}` : pollStatus(poll);
  const close =
    isAuthor(msg, meId) && !poll.closed
      ? {
          label: tNow('msgTypes.closePoll'),
          onClick: () => {
            soundFx.playTap();
            reviseCard(msg, pollPatch(msg, { ...poll, closed: true }, meId));
          },
        }
      : undefined;
  return (
    <CardShell
      testId="card-poll"
      kind={{ label, icon: <ChartBarBig strokeWidth={1.75} /> }}
      title={poll.question.trim() || label}
      status={status}
      action={close}
    >
      <div className="px-2 space-y-1" data-closed={poll.closed ? 'true' : undefined}>
        {poll.options.map((o) => {
          const n = o.voters.length;
          const mine = o.voters.includes(meId);
          const pct = results && total > 0 ? Math.round((n / total) * 100) : 0;
          const right = quiz && results && o.id === poll.correct;
          const wrong = quiz && mine && o.id !== poll.correct;
          return (
            <button
              key={o.id}
              type="button"
              disabled={!open}
              onClick={() => {
                soundFx.playTap();
                const next = vote(poll, o.id, meId);
                userMarks.vote(msg.id, next.options.filter((x) => x.voters.includes(meId)).map((x) => x.id));
                reviseCard(msg, pollPatch(msg, next, meId));
              }}
              aria-pressed={mine}
              data-correct={right ? 'true' : undefined}
              data-wrong={wrong ? 'true' : undefined}
              className="relative w-full min-h-11 rounded-control overflow-hidden flex items-center gap-3 px-2.5 py-1.5 text-left active:opacity-80 disabled:active:opacity-100"
            >
              <span
                aria-hidden
                className={`absolute inset-y-0 left-0 transition-[width] duration-standard ease-standard ${
                  mine ? 'bg-accent/25' : n > 0 && n === top ? 'bg-raised' : 'bg-raised/60'
                }`}
                style={{ width: `${pct}%` }}
              />
              <span className="relative">
                {right ? (
                  <Check aria-hidden className="w-[22px] h-[22px] text-f-live" strokeWidth={2} />
                ) : wrong ? (
                  <X aria-hidden className="w-[22px] h-[22px] text-f-danger" strokeWidth={2} />
                ) : (
                  <CheckDot done={mine} />
                )}
              </span>
              <span className="relative flex-1 min-w-0">
                <span className="block text-callout text-ink truncate">{o.text}</span>
                {results && !poll.anonymous && <VoterNames ids={o.voters} name={name} />}
              </span>
              {results && total > 0 && <span className="relative text-caption text-ink-2 tnum">{pct}%</span>}
            </button>
          );
        })}
      </div>
    </CardShell>
  );
};

const ExpenseCard: React.FC<{ msg: Message; me: string }> = ({ msg, me }) => {
  const [open, setOpen] = useState(false);
  const e = expensesOf(msg)!;
  const title = e.title.trim() || tNow('messageCard.expenses');
  const write = (items: typeof e.items) => reviseCard(msg, expensesPatch(msg, items));
  return (
    <>
      <CardShell
        testId="card-expense"
        kind={{ label: tNow('messageCard.expenses'), icon: <Receipt strokeWidth={1.75} /> }}
        title={title}
        status={expensesStatus(e)}
        action={{ label: tNow('messageCard.open'), onClick: () => setOpen(true) }}
      >
        {e.items.slice(0, PEEK).map((it) => (
          <div key={it.id} className="min-h-11 px-4 flex items-center gap-3">
            <span className="flex-1 min-w-0 text-callout text-ink truncate">{it.title}</span>
            {it.paidBy && <span className="text-caption text-ink-3 truncate max-w-[80px]">{it.paidBy === me ? tNow('billCard.you') : it.paidBy}</span>}
            <span className="text-callout text-ink tnum shrink-0">{money(it.amount)}</span>
          </div>
        ))}
        <More hidden={e.items.length - PEEK} />
      </CardShell>
      <FormSheet open={open} onClose={() => setOpen(false)} title={title} tall testId="sheet-expense">
        <p className="px-4 pb-2 text-caption text-ink-3">{expensesStatus(e)}</p>
        <ExpenseEditor items={e.items} currency={e.currency} me={me} onChange={write} />
      </FormSheet>
    </>
  );
};

/** Перші рядки: перший стовпець і стовпець чисел — те, що влазить у 300. */
const TableCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const [open, setOpen] = useState(false);
  const t = tableOf(msg)!;
  const title = t.title?.trim() || tNow('messageCard.table');
  const first = t.columns[0];
  const num = numericColumn(t);
  const second = num && num !== first ? num : t.columns[1];
  return (
    <>
      <CardShell
        testId="card-table"
        kind={{ label: tNow('messageCard.table'), icon: <Sheet strokeWidth={1.75} /> }}
        title={title}
        status={tableStatus(t)}
        action={{ label: tNow('messageCard.open'), onClick: () => setOpen(true) }}
      >
        {first &&
          t.rows.slice(0, PEEK).map((r, i) => (
            <div key={String(r.id ?? i)} className="min-h-10 px-4 flex items-center gap-3 border-t border-line first:border-t-0">
              <span className="flex-1 min-w-0 text-callout text-ink truncate">{cellText(first, r[first.key])}</span>
              {second && (
                <span
                  className={`text-callout truncate max-w-[45%] ${
                    second.type === 'number' ? 'text-ink tnum' : 'text-ink-2'
                  }`}
                >
                  {cellText(second, r[second.key])}
                </span>
              )}
            </div>
          ))}
        <More hidden={t.rows.length - PEEK} />
      </CardShell>
      <FormSheet open={open} onClose={() => setOpen(false)} title={title} tall testId="sheet-table">
        <p className="px-4 pb-2 text-caption text-ink-3">{tableStatus(t)}</p>
        <TableGrid table={t} onChange={(next: TableData) => reviseCard(msg, { tableData: next })} />
      </FormSheet>
    </>
  );
};

const UNREADABLE: Record<string, string> = {
  get table() { return tNow('messageCard.unreadable.table'); },
  get 'task-list'() { return tNow('messageCard.unreadable.task_list'); },
  get 'widget:family-checklist'() { return tNow('messageCard.unreadable.widget_family_checklist'); },
  get poll() { return tNow('messageCard.unreadable.poll'); },
  get 'widget:voting'() { return tNow('messageCard.unreadable.widget_voting'); },
  get 'widget:family-expense'() { return tNow('messageCard.unreadable.widget_family_expense'); },
};

const THEATRE_NOUN: Record<string, string> = {
  get 'widget:canvas'() { return tNow('messageCard.theatre.widget_canvas'); },
  get 'widget:kanban'() { return tNow('messageCard.theatre.widget_kanban'); },
  get 'widget:raci'() { return tNow('messageCard.theatre.widget_raci'); },
  get 'widget:timeline'() { return tNow('messageCard.theatre.widget_timeline'); },
  get 'widget:code-runner'() { return tNow('messageCard.theatre.widget_code_runner'); },
  get 'widget:study-flashcards'() { return tNow('messageCard.theatre.widget_study_flashcards'); },
  get 'snippet:async'() { return tNow('messageCard.theatre.snippet_async'); },
  get 'embed:mermaid'() { return tNow('messageCard.theatre.embed_mermaid'); },
  get 'embed:diff'() { return tNow('messageCard.theatre.embed_diff'); },
  get 'embed:svg'() { return tNow('messageCard.theatre.embed_svg'); },
  get 'webhook:event'() { return tNow('messageCard.theatre.webhook_event'); },
};

const THEATRE_ICON: Record<string, LucideIcon> = {
  'widget:canvas': FileText,
  'widget:kanban': SquareKanban,
  'widget:raci': Users,
  'widget:timeline': ChartGantt,
  'widget:code-runner': SquareTerminal,
  'widget:study-flashcards': Layers,
  'snippet:async': Video,
  'embed:mermaid': Workflow,
  'embed:diff': GitCompare,
  'embed:svg': Shapes,
  'webhook:event': Webhook,
};

/**
 * Хвіст рядка. «Відкрити на ПК» — лише документ на телефоні: на ПК у нього є
 * справжній вид (CanvasCardEmbed). Решта родів не відкривається ніде.
 */
export const pcRowTail = (kind: string, phone: boolean): string =>
  phone && kind === 'widget:canvas' ? tNow('messageCard.openOnPc') : tNow('messageCard.notShown');

/** Що всередині показового роду одним словом; null — сказати нічого. Дошка, ролі, план і картки — WorkCard. */
export function theatreFact(msg: Message): string | null {
  switch (msg.type) {
    case 'snippet:async': {
      const s = Math.round(Number(msg.asyncSnippetData?.durationSeconds));
      return Number.isFinite(s) && s > 0 ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` : null;
    }
    case 'embed:diff':
      return msg.codeDiffData?.filename?.split('/').pop()?.trim() || null;
    case 'widget:code-runner':
      return msg.codeRunnerData?.language || null;
    default:
      return null;
  }
}

/** Показовий рід: один рядок — значок, слово (або назва), хвіст. Без картки. */
export const TheatreCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const field = Object.values(msg).find(
    (v) => v && typeof v === 'object' && ('title' in v || 'topic' in v || 'question' in v),
  ) as { title?: string; topic?: string; question?: string } | undefined;
  const named = msg.type === 'webhook:event' ? '' : (field?.title || field?.topic || field?.question || '').trim();
  const noun = THEATRE_NOUN[msg.type] ?? tNow('messageCard.card');
  const Icon = THEATRE_ICON[msg.type] ?? FileText;
  return (
    <div
      data-testid="card-pc-row"
      data-kind={msg.type}
      title={named ? noun : undefined}
      className="inline-flex max-w-[300px] items-center gap-2 min-h-10 px-3 py-2 rounded-bubble bg-surface border border-line"
    >
      <Icon className="h-4 w-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-label={noun} />
      <span data-row-word className={`${msg.type === 'widget:canvas' ? 'line-clamp-2 break-words' : 'truncate'} text-subhead text-ink`}>{named || noun}</span>
      <span className="shrink-0 text-caption text-ink-3">{theatreFact(msg) ?? pcRowTail(msg.type, __PHONE_BUILD__)}</span>
    </div>
  );
};

export function isTheatreKind(kind: string): boolean {
  return THEATRE_CARD_KINDS.has(kind);
}

export const isDoorCodeMessage = isDoorCode;

const MAX_TIMER_MS = 2_147_483_647;
type DoorState = 'open' | 'folding' | 'shut';

/** Дверний код: відкритий до кінця вікна, тоді згортається й зникає з DOM. */
const DoorCodeCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const t = tNow;
  const found = doorCodeOfMessage(msg);
  const [held, setHeld] = useState(found);
  const [until, setUntil] = useState(found ? found.until.getTime() : null);
  if (found && found.until.getTime() !== until) setUntil(found.until.getTime());
  const [now, setNow] = useState(() => Date.now());
  const expired = Boolean(msg.isDeleted) || (until !== null && now >= until);
  const [state, setState] = useState<DoorState>(expired ? 'shut' : 'open');

  if (expired && state === 'open') setState(policy(readEnv()).fold.end ? 'shut' : 'folding');
  if (!expired && state !== 'open') setState('open');
  const keep = state === 'shut' ? null : (found ?? held);
  if (keep?.code !== held?.code || keep?.until.getTime() !== held?.until.getTime()) setHeld(keep);

  useEffect(() => {
    if (until === null || now >= until) return;
    const id = setTimeout(() => setNow(Date.now()), Math.min(Math.max(0, until - Date.now()), MAX_TIMER_MS));
    return () => clearTimeout(id);
  }, [until, now]);

  const fold = policy(readEnv()).fold;
  useEffect(() => {
    if (state !== 'folding') return;
    const id = setTimeout(() => setState('shut'), fold.ms);
    return () => clearTimeout(id);
  }, [state, fold.ms]);

  const shown = state === 'shut' ? null : keep;
  const when = shown
    ? new Intl.DateTimeFormat(currentDateTag(), { weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(shown.until)
    : '';

  return (
    <div data-testid="card-door-code" data-state={state}>
      <CardShell
        kind={{ label: t('doorCode.kind'), icon: <KeyRound strokeWidth={1.75} /> }}
        title={t('doorCode.kind')}
        status={state === 'open' ? t('doorCode.until', { when }) : t('doorCode.shut')}
        action={
          state === 'open' && shown
            ? {
                label: t('doorCode.copy'),
                onClick: () => {
                  soundFx.playTap();
                  void navigator.clipboard?.writeText(shown.code);
                },
              }
            : undefined
        }
      >
        {shown && (
          <div
            data-testid="door-code-body"
            aria-hidden={state === 'folding' || undefined}
            onTransitionEnd={(e) => {
              if (e.target === e.currentTarget && state === 'folding') setState('shut');
            }}
            style={{
              transformOrigin: 'top',
              transform: state === 'open' ? 'scaleY(1)' : 'scaleY(0)',
              transition: `transform ${fold.ms}ms ${fold.easing}`,
            }}
            className="px-4 py-2 text-title-2 tnum text-ink"
          >
            {shown.code}
          </div>
        )}
      </CardShell>
    </div>
  );
};

/** День чи погляд на людину не від помічника — підробка: лише рядок, нічого схожого на картку. */
const NotFromAssistant: React.FC = () => (
  <p data-testid="card-refused" className="text-body text-ink-2">
    {tNow('messageCard.unreadable')}
  </p>
);

/** Картка листа, або null — цей лист не картка нової мови. */
export const MessageCard: React.FC<{
  msg: Message;
  meId: string;
  meName: string;
  day?: readonly DayItem[] | null;
}> = ({ msg, meId, meName, day }) => {
  if (isDoorCode(msg)) return <DoorCodeCard msg={msg} />;
  if (checklistOf(msg)) return <ChecklistCard msg={msg} me={meName} meId={meId} />;
  if (pollOf(msg)) return <PollCard msg={msg} meId={meId} meName={meName} day={day} />;
  if (expensesOf(msg)) return <ExpenseCard msg={msg} me={meName} />;
  if (msg.type === 'table' && tableOf(msg)) return <TableCard msg={msg} />;
  if (msg.type === PERSON_KIND) return <PersonCard msg={msg} meId={meId} />;
  if (msg.type === GIFT_KIND) return <GiftCard msg={msg} meId={meId} />;
  if (msg.type === DAY_KIND || msg.type === GLANCE_KIND || msg.type === MONEY_KIND) {
    if (msg.senderId !== ASSISTANT_PEER_ID) return <NotFromAssistant />;
    if (msg.type === DAY_KIND) return <DayCard msg={msg} />;
    if (msg.type === GLANCE_KIND) return <GlanceCard msg={msg} />;
    return <MoneyCard msg={msg} meId={meId} />;
  }
  // Рід картки, а вміст не прочитався: чесне слово замість порожнього рядка.
  return <CardShell testId="card-unreadable" title={UNREADABLE[msg.type] ?? tNow('messageCard.card')} status={tNow('messageCard.unreadable')} />;
};
