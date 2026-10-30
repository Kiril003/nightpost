/**
 * Дошка, ролі, план-графік і навчальні картки — справжній вид у розмові.
 * Лише читання: правки цих родів немає ніде. Без пунктів — не картка
 * (лишається рядок TheatreCard з «не показується»).
 */
import React, { useState } from 'react';
import { ChartGantt, Layers, SquareKanban, Users } from 'lucide-react';
import type { Message } from '../../../types/messenger';
import { FormSheet } from '../FormSheet';
import { CardShell, CheckDot } from './CardShell';
import { reviseCard } from './MessageCard';
import { useMessengerStore } from '../../../stores/messengerStore';
import { soundFx } from '../../../utils/messengerSound';
import { tNow } from '../../chat/tNow';
import { arr, str, toggleWork, workOf, type Row, type Work } from './workModel';

export { isWorkCard } from './workModel';

const PEEK = 3;
const ICON = { board: SquareKanban, roles: Users, plan: ChartGantt, study: Layers } as const;
const NOUN = {
  board: 'messageCard.theatre.widget_kanban',
  roles: 'messageCard.theatre.widget_raci',
  plan: 'messageCard.theatre.widget_timeline',
  study: 'messageCard.theatre.widget_study_flashcards',
} as const;

const StateMark: React.FC<{ done: boolean }> = ({ done }) => (
  <svg data-testid="work-mark" data-state={done ? 'done' : 'open'} aria-hidden viewBox="0 0 16 16" className="w-4 h-4 shrink-0 text-ink-3">
    {done ? (
      <path d="M3.5 8.5l3 3 6-7" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    ) : (
      <circle cx="8" cy="8" r="2.5" fill="currentColor" />
    )}
  </svg>
);

const RowLine: React.FC<{ row: Row; check: boolean; onToggle?: (id: string) => void }> = ({ row, check, onToggle }) => {
  const body = (
    <>
      <span className={`flex-1 min-w-0 text-callout truncate ${row.done ? 'text-ink-3 line-through' : 'text-ink'}`}>{row.title}</span>
      {row.side && <span className="shrink-0 max-w-[40%] text-caption text-ink-3 truncate tnum">{row.side}</span>}
    </>
  );
  if (check && row.live && onToggle) {
    return (
      <button
        type="button"
        data-testid="work-row"
        data-done={row.done ? '' : undefined}
        aria-pressed={Boolean(row.done)}
        onClick={() => onToggle(row.id)}
        className="w-full min-h-11 px-4 flex items-center gap-3 text-left active:bg-raised"
      >
        <CheckDot done={Boolean(row.done)} />
        {body}
      </button>
    );
  }
  return (
    <div data-testid="work-row" data-done={row.done ? '' : undefined} className="min-h-11 px-4 flex items-center gap-3">
      {check && <StateMark done={Boolean(row.done)} />}
      {body}
    </div>
  );
};

const RolesSheet: React.FC<{ msg: Message }> = ({ msg }) => (
  <div className="px-4 pb-4 flex flex-col gap-3">
    {arr(msg.raciData?.rows)
      .filter((r) => str(r.task))
      .map((r, i) => (
        <div key={str(r.id) || i} data-testid="work-role" className="rounded-card border border-line px-3 py-2.5">
          <p className="text-callout font-semibold text-ink">{str(r.task)}</p>
          {(['r', 'a', 'c', 'i'] as const)
            .filter((k) => str(r[k]))
            .map((k) => (
              <p key={k} className="mt-0.5 text-subhead text-ink-2">
                <span className="text-ink-3">{tNow(`work.raci.${k}`)} · </span>
                {str(r[k])}
              </p>
            ))}
        </div>
      ))}
  </div>
);

const StudyCard: React.FC<{ work: Work }> = ({ work }) => {
  const [at, setAt] = useState(0);
  const [back, setBack] = useState(false);
  const card = work.rows[at % work.rows.length];
  const many = work.rows.length > 1;
  return (
    <CardShell
      testId="card-study"
      kind={{ label: tNow(NOUN.study), icon: <Layers strokeWidth={1.75} /> }}
      title={work.title}
      status={tNow('work.cardOf', { n: at + 1, total: work.rows.length })}
      action={
        many
          ? {
              label: tNow('work.next'),
              onClick: () => {
                setAt((a) => (a + 1) % work.rows.length);
                setBack(false);
              },
            }
          : undefined
      }
    >
      <button
        type="button"
        data-testid="study-face"
        data-side={back ? 'answer' : 'question'}
        aria-pressed={back}
        disabled={!card.side}
        onClick={() => setBack((b) => !b)}
        className="mx-4 w-[calc(100%-2rem)] min-h-[96px] px-4 py-3 rounded-card bg-raised text-left flex flex-col justify-center gap-1 active:opacity-80"
      >
        <span className="text-caption font-semibold text-ink-3">{tNow(back ? 'work.answer' : 'work.question')}</span>
        <span className="text-callout text-ink break-words line-clamp-4">{back ? card.side : card.title}</span>
      </button>
    </CardShell>
  );
};

export const WorkCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const [open, setOpen] = useState(false);
  const me = useMessengerStore((s) => s.currentUser?.name ?? '');
  const work = workOf(msg);
  if (!work) return null;
  const toggle = (id: string) => {
    const patch = toggleWork(msg, id, me);
    if (!patch) return;
    soundFx.playTap();
    reviseCard(msg, patch);
  };
  if (work.kind === 'study') return <StudyCard work={work} />;
  const Icon = ICON[work.kind];
  const check = work.kind !== 'roles';
  return (
    <>
      <CardShell
        testId={`card-${work.kind}`}
        kind={{ label: tNow(NOUN[work.kind]), icon: <Icon strokeWidth={1.75} /> }}
        title={work.title}
        status={work.status}
        action={{ label: tNow('messageCard.open'), onClick: () => setOpen(true) }}
      >
        {work.rows.slice(0, PEEK).map((r) => (
          <RowLine key={r.id} row={r} check={check} onToggle={toggle} />
        ))}
        {work.rows.length > PEEK && (
          <div className="px-4 pt-1 text-caption text-ink-3">{tNow('messageCard.more', { n: work.rows.length - PEEK })}</div>
        )}
      </CardShell>
      <FormSheet open={open} onClose={() => setOpen(false)} title={work.title} tall testId={`sheet-${work.kind}`}>
        <p className="px-4 pb-2 text-caption text-ink-3">{work.status}</p>
        {work.kind === 'roles' ? <RolesSheet msg={msg} /> : work.rows.map((r) => <RowLine key={r.id} row={r} check={check} onToggle={toggle} />)}
      </FormSheet>
    </>
  );
};
