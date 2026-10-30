/**
 * Будівельник картки — один екран: назва, пункти, «Надіслати».
 *
 * Картка йде живим родом (`widget:*`), який дріт справді везе. Таблиця —
 * лише коли дріт знає рід `table`; до того її двері не показуються зовсім.
 */
import React, { useState } from 'react';
import { X } from 'lucide-react';
import type { TableColumn, TableData } from '../../../types/messenger';
import { useMessengerStore } from '../../../stores/messengerStore';
import { isCardKind } from '../../../services/composerCards';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { ChecklistEditor, ColumnsEditor, ExpenseEditor, TableGrid } from './CardEditors';
import { CheckDot } from './CardShell';
import { localCurrency, newId, type CheckItem, type Expense } from './cardModel';
import { MeetupDraftSheet } from '../meetup/MeetupDraftSheet';
import { tNow } from '../../chat/tNow';
/**
 * `meetup` — не будівельник картки, а чернетка зустрічі (збірка 18): вона
 * відкривається завжди, а «Запросити» сама каже, чи дріт уже везе картку.
 * Тому її немає в `sendableBuilders()` — це двері, а не рід.
 */
export type BuilderKind = 'checklist' | 'poll' | 'expense' | 'table' | 'meetup';

/** Які будівельники має сенс показувати: лише ті, чий рід їде дротом. */
export function sendableBuilders(): BuilderKind[] {
  const out: BuilderKind[] = ['checklist', 'poll', 'expense'];
  if (isCardKind('table')) out.push('table');
  return out;
}

const TITLE_FIELD =
  'h-12 min-h-0 w-full rounded-control bg-raised px-3 text-headline text-ink placeholder:text-ink-3 placeholder:font-normal outline-none focus:outline focus:outline-2 focus:outline-accent-line';

const SHEET_TITLE: Record<Exclude<BuilderKind, 'meetup'>, string> = {
  get checklist() { return tNow('cardBuilder.checklist'); },
  get poll() { return tNow('cardBuilder.poll'); },
  get expense() { return tNow('cardBuilder.expenses'); },
  get table() { return tNow('cardBuilder.table'); },
};

const Toggle: React.FC<{ on: boolean; onToggle: () => void; label: string; testId: string }> = ({ on, onToggle, label, testId }) => (
  <button
    type="button"
    role="switch"
    aria-checked={on}
    onClick={onToggle}
    data-testid={testId}
    className="w-full min-h-12 px-4 flex items-center gap-3 text-left active:bg-raised"
  >
    <span className="flex-1 min-w-0 text-callout text-ink">{label}</span>
    <span aria-hidden className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${on ? 'bg-accent' : 'bg-raised'}`}>
      <span
        style={{ backgroundColor: 'var(--ph-lit-white)' }}
        className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${on ? 'translate-x-5' : ''}`}
      />
    </span>
  </button>
);

const Label: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="px-4 pt-4 pb-1 text-caption text-ink-3">{children}</div>
);

export const CardBuilder: React.FC<{ kind: BuilderKind | null; onClose: () => void }> = ({ kind, onClose }) => {
  const chatId = useMessengerStore((s) => s.activeChatId);
  if (kind === 'meetup') return <MeetupDraftSheet open onClose={onClose} seed={{ chatId: chatId || null }} />;
  return <CardBuilderForm kind={kind} onClose={onClose} />;
};

const CardBuilderForm: React.FC<{ kind: Exclude<BuilderKind, 'meetup'> | null; onClose: () => void }> = ({ kind, onClose }) => {
  const me = useMessengerStore((s) => s.currentUser);
  const sendCard = useMessengerStore((s) => s.sendCard);
  const [title, setTitle] = useState('');
  const [items, setItems] = useState<CheckItem[]>([]);
  const [options, setOptions] = useState<string[]>(['', '']);
  const [anonymous, setAnonymous] = useState(true);
  const [multiple, setMultiple] = useState(false);
  const [quiz, setQuiz] = useState(false);
  const [correct, setCorrect] = useState<number | null>(null);
  const [expenses, setExpenses] = useState<Expense[]>([]);
  const [columns, setColumns] = useState<TableColumn[]>([
    { key: 'c1', label: tNow('cardBuilder.name'), type: 'text' },
    { key: 'c2', label: tNow('cardBuilder.sum'), type: 'number' },
  ]);
  const [rows, setRows] = useState<TableData['rows']>([]);
  const [sending, setSending] = useState(false);

  const reset = () => {
    setTitle('');
    setItems([]);
    setOptions(['', '']);
    setAnonymous(true);
    setMultiple(false);
    setQuiz(false);
    setCorrect(null);
    setExpenses([]);
    setRows([]);
    setSending(false);
  };
  const close = () => {
    reset();
    onClose();
  };

  const filledOptions = options.map((o) => o.trim()).filter(Boolean);
  const ready =
    kind === 'checklist'
      ? items.length > 0
      : kind === 'poll'
        ? title.trim() !== '' && filledOptions.length >= 2 && (!quiz || (correct !== null && options[correct]?.trim() !== ''))
        : kind === 'expense'
          ? expenses.length > 0
          : kind === 'table'
            ? columns.length > 0
            : false;

  const send = async () => {
    if (!kind || !ready || sending) return;
    setSending(true);
    const t = title.trim();
    let refused: string | null = null;
    if (kind === 'checklist') {
      refused = await sendCard('widget:family-checklist', {
        id: newId('fc'),
        title: t || tNow('cardBuilder.checklist'),
        items: items.map((i) => ({ id: i.id, text: i.text, category: '', done: false, addedBy: me.name })),
      });
    } else if (kind === 'poll') {
      const picked = options.map((text, i) => ({ text: text.trim(), i })).filter((o) => o.text);
      const right = quiz ? picked.findIndex((o) => o.i === correct) : -1;
      refused = await sendCard('widget:voting', {
        id: newId('v'),
        question: t,
        options: picked.map((o, i) => ({ id: `o${i + 1}`, text: o.text, votes: 0, ...(anonymous ? {} : { voters: [] }) })),
        totalVotes: 0,
        ...(anonymous ? { anonymous: true } : {}),
        ...(quiz ? { correctOptionId: `o${right + 1}` } : multiple ? { allowMultiple: true } : {}),
      });
    } else if (kind === 'expense') {
      refused = await sendCard('widget:family-expense', {
        id: newId('fe'),
        title: t || tNow('cardBuilder.expenses'),
        currency: localCurrency(),
        items: expenses.map((x) => ({ id: x.id, title: x.title, amountUAH: x.amount, category: '', paidBy: x.paidBy })),
      });
    } else {
      refused = await sendCard('table', { id: newId('tb'), title: t || tNow('cardBuilder.table'), columns, rows } satisfies TableData);
    }
    // Відмову вже сказав тост стору; аркуш лишається з тим, що людина набрала.
    if (refused) setSending(false);
    else close();
  };

  return (
    <FormSheet
      open={kind !== null}
      onClose={close}
      title={kind ? SHEET_TITLE[kind] : ''}
      tall
      testId={kind ? `builder-${kind}` : undefined}
      footer={
        <PrimaryButton onClick={send} disabled={!ready || sending}>
          {tNow('cardBuilder.send')}
        </PrimaryButton>
      }
    >
      <div className="px-4">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={kind === 'poll' ? tNow('cardBuilder.question') : tNow('cardBuilder.name')}
          className={TITLE_FIELD}
          data-testid="builder-title"
          autoFocus
        />
      </div>

      {kind === 'checklist' && (
        <>
          <Label>{tNow('cardBuilder.items')}</Label>
          <ChecklistEditor items={items} onChange={setItems} draft />
        </>
      )}

      {kind === 'poll' && (
        <>
          <Label>{tNow('cardBuilder.options')}</Label>
          {options.map((o, i) => (
            <div key={i} className="flex items-center gap-2 pl-4 pr-1 py-1">
              {quiz && (
                <button
                  type="button"
                  role="radio"
                  aria-checked={correct === i}
                  aria-label={tNow('msgTypes.correctOption', { n: i + 1 })}
                  data-testid={`poll-correct-${i}`}
                  onClick={() => setCorrect(i)}
                  className="w-11 h-11 min-h-0 min-w-0 shrink-0 -ml-3 flex items-center justify-center"
                >
                  <CheckDot done={correct === i} />
                </button>
              )}
              <input
                value={o}
                onChange={(e) => {
                  const next = options.map((x, j) => (j === i ? e.target.value : x));
                  // Останнє поле заповнили — одразу є наступне, без кнопки «Додати».
                  if (i === options.length - 1 && e.target.value.trim() && options.length < 10) next.push('');
                  setOptions(next);
                }}
                placeholder={tNow('cardBuilder.option', { n: i + 1 })}
                className="h-11 min-h-0 flex-1 min-w-0 rounded-control bg-raised px-3 text-callout text-ink placeholder:text-ink-3 outline-none focus:outline focus:outline-2 focus:outline-accent-line"
                data-testid={`poll-option-${i}`}
              />
              {options.length > 2 ? (
                <button
                  type="button"
                  onClick={() => {
                    setOptions(options.filter((_, j) => j !== i));
                    if (correct !== null) setCorrect(correct === i ? null : correct > i ? correct - 1 : correct);
                  }}
                  aria-label={tNow('cardBuilder.removeOption', { n: i + 1 })}
                  className="w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-control text-ink-3 active:bg-raised"
                >
                  <X className="w-4 h-4" strokeWidth={1.75} />
                </button>
              ) : (
                <span className="w-11 shrink-0" />
              )}
            </div>
          ))}
          <Label>{tNow('msgTypes.settings')}</Label>
          <Toggle on={anonymous} onToggle={() => setAnonymous(!anonymous)} label={tNow('msgTypes.anonymous')} testId="poll-anonymous" />
          {!quiz && (
            <Toggle on={multiple} onToggle={() => setMultiple(!multiple)} label={tNow('msgTypes.multiple')} testId="poll-multiple" />
          )}
          <Toggle
            on={quiz}
            onToggle={() => {
              setQuiz(!quiz);
              setMultiple(false);
            }}
            label={tNow('msgTypes.quiz')}
            testId="poll-quiz"
          />
          {quiz && correct === null && (
            <p className="px-4 pt-1 text-caption text-ink-3">{tNow('msgTypes.pickCorrect')}</p>
          )}
        </>
      )}

      {kind === 'expense' && (
        <>
          <Label>{tNow('cardBuilder.expenses')}</Label>
          <ExpenseEditor items={expenses} currency={localCurrency()} me={me.name} onChange={setExpenses} />
        </>
      )}

      {kind === 'table' && (
        <>
          <Label>{tNow('cardBuilder.columns')}</Label>
          <ColumnsEditor columns={columns} onChange={setColumns} />
          {columns.length > 0 && (
            <>
              <Label>{tNow('cardBuilder.rows')}</Label>
              <TableGrid
                table={{ title, columns, rows }}
                onChange={(next) => setRows(next.rows)}
              />
            </>
          )}
        </>
      )}
    </FormSheet>
  );
};
