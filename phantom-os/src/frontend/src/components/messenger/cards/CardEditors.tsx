/**
 * Редактори карток — одні й ті самі для повного перегляду листа і для
 * будівельника перед надсиланням. Жодного стану поза `value`: хто тримає
 * картку (лист у сторі чи чернетка), той і пише.
 */
import React, { useEffect, useRef, useState } from 'react';
import { Plus, X } from 'lucide-react';
import type { TableColumn, TableData } from '../../../types/messenger';
import { CheckDot } from './CardShell';
import { cellText, columnUnit, money, newId, tick, timeLabel, type CheckItem, type Expense } from './cardModel';
import { tNow } from '../../chat/tNow';
const FIELD =
  'h-11 min-h-0 w-full min-w-0 rounded-control bg-raised px-3 text-callout text-ink placeholder:text-ink-3 outline-none focus:outline focus:outline-2 focus:outline-accent-line';
const ICON_BTN =
  'w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-control text-ink-3 active:bg-raised';

/** Поле додавання: Enter або «+» додає й лишає фокус для наступного. */
const AddLine: React.FC<{ placeholder: string; onAdd: (text: string) => void; testId?: string }> = ({
  placeholder,
  onAdd,
  testId,
}) => {
  const [text, setText] = useState('');
  const add = () => {
    const t = text.trim();
    if (!t) return;
    onAdd(t);
    setText('');
  };
  return (
    <div className="flex items-center gap-2 px-4 py-2">
      <input
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            add();
          }
        }}
        placeholder={placeholder}
        className={FIELD}
        data-testid={testId}
        enterKeyHint="done"
      />
      <button type="button" onClick={add} aria-label={tNow('cardEditors.add')} className={`${ICON_BTN} bg-raised text-ink`}>
        <Plus className="w-5 h-5" strokeWidth={2} />
      </button>
    </div>
  );
};

export const ChecklistEditor: React.FC<{
  items: CheckItem[];
  onChange: (items: CheckItem[]) => void;
  /** У будівельнику галочок ще нема — лише пункти. */
  draft?: boolean;
  /** Хто ставить галочку. */
  by?: string;
  /** Склад списку міняє лише автор; решта лише ставить галочки. */
  fixed?: boolean;
}> = ({ items, onChange, draft = false, by = '', fixed = false }) => (
  <div>
    {items.map((it) => (
      <div key={it.id} className="flex items-center min-h-12 pl-4 pr-1">
        {draft ? (
          <span className="flex-1 min-w-0 text-callout text-ink break-words py-2">{it.text}</span>
        ) : (
          <button
            type="button"
            onClick={() => onChange(tick(items, it.id, by))}
            className="flex-1 min-w-0 min-h-12 flex items-center gap-3 text-left"
            aria-pressed={it.done}
          >
            <CheckDot done={it.done} />
            <span className="flex-1 min-w-0">
              <span className={`block text-callout break-words ${it.done ? 'text-ink-3 line-through' : 'text-ink'}`}>
                {it.text}
              </span>
              {it.done && it.doneBy && (
                <span data-testid="checklist-done-by" className="block text-caption text-ink-3 truncate">
                  {it.doneBy}
                </span>
              )}
            </span>
          </button>
        )}
        {!fixed && (
          <button
            type="button"
            onClick={() => onChange(items.filter((x) => x.id !== it.id))}
            aria-label={tNow('cardEditors.removeItem', { name: it.text })}
            className={ICON_BTN}
          >
            <X className="w-4 h-4" strokeWidth={1.75} />
          </button>
        )}
      </div>
    ))}
    {!fixed && (
      <AddLine
        placeholder={tNow('cardEditors.addItem')}
        testId="checklist-add"
        onAdd={(text) => onChange([...items, { id: newId('i'), text, done: false }])}
      />
    )}
  </div>
);

export const ExpenseEditor: React.FC<{
  items: Expense[];
  currency: string;
  me: string;
  onChange: (items: Expense[]) => void;
}> = ({ items, currency, me, onChange }) => {
  const [title, setTitle] = useState('');
  const [amount, setAmount] = useState('');
  const sum = Number(amount.replace(',', '.'));
  const ready = title.trim() !== '' && Number.isFinite(sum) && sum > 0;
  const add = () => {
    if (!ready) return;
    onChange([...items, { id: newId('x'), title: title.trim(), amount: sum, paidBy: me }]);
    setTitle('');
    setAmount('');
  };
  return (
    <div>
      {items.map((it) => (
        <div key={it.id} className="flex items-center min-h-12 pl-4 pr-1 gap-3">
          <div className="flex-1 min-w-0">
            <div className="text-callout text-ink truncate">{it.title}</div>
            {it.paidBy && <div className="text-caption text-ink-3 truncate">{it.paidBy === me ? tNow('billCard.you') : it.paidBy}</div>}
          </div>
          <span className="text-callout text-ink tnum shrink-0">
            {money(it.amount)} {currency}
          </span>
          <button
            type="button"
            onClick={() => onChange(items.filter((x) => x.id !== it.id))}
            aria-label={tNow('cardEditors.removeItem', { name: it.title })}
            className={ICON_BTN}
          >
            <X className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      ))}
      <div className="flex items-center gap-2 px-4 py-2">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={tNow('cardEditors.forWhat')}
          className={FIELD}
          data-testid="expense-title"
        />
        <input
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d.,]/g, ''))}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          placeholder={currency || '0'}
          inputMode="decimal"
          className={`${FIELD} !w-24 shrink-0 text-right tnum`}
          data-testid="expense-amount"
        />
        <button
          type="button"
          onClick={add}
          disabled={!ready}
          aria-label={tNow('cardEditors.addExpense')}
          className={`${ICON_BTN} ${ready ? 'bg-accent text-on-accent' : 'bg-raised text-ink-3'}`}
        >
          <Plus className="w-5 h-5" strokeWidth={2} />
        </button>
      </div>
    </div>
  );
};

/** Клітинка, що правиться дотиком: інпут на її місці, Enter чи втрата фокусу — зберегти. */
const Cell: React.FC<{
  col: TableColumn;
  value: unknown;
  editing: boolean;
  onEdit: () => void;
  onSave: (v: unknown) => void;
  testId?: string;
}> = ({ col, value, editing, onEdit, onSave, testId }) => {
  const [draft, setDraft] = useState('');
  const ref = useRef<HTMLInputElement>(null);
  const numeric = col.type === 'number';
  useEffect(() => {
    if (editing) {
      setDraft(value === undefined || value === null ? '' : String(value));
      ref.current?.focus();
    }
  }, [editing, value]);
  const commit = () => {
    const t = draft.trim();
    if (numeric) {
      const n = Number(t.replace(/\s/g, '').replace(',', '.'));
      onSave(t === '' || !Number.isFinite(n) ? '' : n);
    } else onSave(t);
  };
  const width = numeric ? 'w-[112px]' : 'w-[160px]';
  if (editing) {
    return (
      <td className={`${width} p-1 align-middle`}>
        <input
          ref={ref}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              commit();
            }
          }}
          inputMode={numeric ? 'decimal' : 'text'}
          className={`${FIELD} ${numeric ? 'text-right tnum' : ''}`}
          data-testid={testId ? `${testId}-input` : undefined}
        />
      </td>
    );
  }
  const shown = cellText(col, value);
  return (
    <td className={`${width} p-0 align-middle`}>
      <button
        type="button"
        onClick={onEdit}
        className={`w-full min-h-12 px-3 text-callout break-words ${numeric ? 'text-right tnum' : 'text-left'} ${
          shown ? 'text-ink' : 'text-ink-3'
        }`}
        data-testid={testId}
      >
        {shown || '—'}
      </button>
    </td>
  );
};

/**
 * Таблиця на весь екран: гортається вбік, клітинка правиться дотиком,
 * рядок додається одним дотиком і одразу відкриває першу клітинку.
 */
export const TableGrid: React.FC<{ table: TableData; onChange: (t: TableData) => void }> = ({ table, onChange }) => {
  const [editing, setEditing] = useState<{ row: number; col: string } | null>(null);
  const rows = table.rows ?? [];
  const setCell = (ri: number, key: string, v: unknown) => {
    const next = rows.map((r, i) => (i === ri ? { ...r, [key]: v } : r));
    onChange({ ...table, rows: next });
    setEditing(null);
  };
  const addRow = () => {
    const row: Record<string, unknown> = { id: newId('r') };
    onChange({ ...table, rows: [...rows, row] });
    const first = table.columns[0];
    if (first) setEditing({ row: rows.length, col: first.key });
  };
  return (
    <div>
      <div className="overflow-x-auto no-scrollbar" data-testid="table-scroll">
        <table className="border-collapse min-w-full">
          <thead>
            <tr>
              {table.columns.map((c) => {
                const { name, unit } = columnUnit(c);
                return (
                  <th
                    key={c.key}
                    className={`px-3 py-2 text-caption font-normal text-ink-3 whitespace-nowrap ${
                      c.type === 'number' ? 'text-right' : 'text-left'
                    }`}
                  >
                    {unit ? `${name}, ${unit}` : name}
                  </th>
                );
              })}
              <th className="w-11" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, ri) => (
              <tr key={String(r.id ?? ri)} className="border-t border-line">
                {table.columns.map((c) => (
                  <Cell
                    key={c.key}
                    col={c}
                    value={r[c.key]}
                    editing={editing?.row === ri && editing.col === c.key}
                    onEdit={() => setEditing({ row: ri, col: c.key })}
                    onSave={(v) => setCell(ri, c.key, v)}
                    testId={`cell-${ri}-${c.key}`}
                  />
                ))}
                <td className="p-0">
                  <button
                    type="button"
                    onClick={() => onChange({ ...table, rows: rows.filter((_, i) => i !== ri) })}
                    aria-label={tNow('cardEditors.removeRow')}
                    className={ICON_BTN}
                  >
                    <X className="w-4 h-4" strokeWidth={1.75} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="px-4 pt-2">
        <button
          type="button"
          onClick={addRow}
          data-testid="table-add-row"
          className="w-full h-12 min-h-0 rounded-control bg-raised text-footnote font-semibold text-ink flex items-center justify-center gap-2 active:opacity-80"
        >
          <Plus className="w-4 h-4" strokeWidth={2} />
          {tNow('cardEditors.row')}
        </button>
      </div>
    </div>
  );
};

/** Стовпці будівельника: назва й чи це число. */
export const ColumnsEditor: React.FC<{ columns: TableColumn[]; onChange: (c: TableColumn[]) => void }> = ({
  columns,
  onChange,
}) => (
  <div>
    {columns.map((c) => (
      <div key={c.key} className="flex items-center gap-2 pl-4 pr-1 min-h-12">
        <span className="flex-1 min-w-0 text-callout text-ink truncate">{c.label}</span>
        <button
          type="button"
          onClick={() =>
            onChange(columns.map((x) => (x.key === c.key ? { ...x, type: x.type === 'number' ? 'text' : 'number' } : x)))
          }
          aria-pressed={c.type === 'number'}
          className={`h-11 min-h-0 px-3 rounded-full text-caption ${
            c.type === 'number' ? 'bg-accent text-on-accent' : 'bg-raised text-ink-2'
          }`}
        >
          {tNow('cardEditors.numbers')}
        </button>
        <button
          type="button"
          onClick={() => onChange(columns.filter((x) => x.key !== c.key))}
          aria-label={tNow('cardEditors.removeColumn', { name: c.label })}
          className={ICON_BTN}
        >
          <X className="w-4 h-4" strokeWidth={1.75} />
        </button>
      </div>
    ))}
    <AddLine
      placeholder={tNow('cardEditors.newColumn')}
      testId="table-add-column"
      onAdd={(label) => onChange([...columns, { key: newId('c'), label, type: 'text' }])}
    />
  </div>
);

export const TimeOptionsEditor: React.FC<{
  day: string;
  options: string[];
  onChange: (o: string[]) => void;
}> = ({ day, options, onChange }) => {
  const [time, setTime] = useState('');
  const sorted = [...options].sort((a, b) => Date.parse(a) - Date.parse(b) || a.localeCompare(b));
  const add = () => {
    const hhmm = time.trim();
    if (!hhmm) return;
    const d = new Date(`${day}T${hhmm}`);
    if (!Number.isFinite(d.getTime())) return;
    const iso = d.toISOString();
    if (!options.includes(iso)) {
      onChange([...options, iso].sort((a, b) => Date.parse(a) - Date.parse(b) || a.localeCompare(b)));
    }
    setTime('');
  };
  return (
    <div>
      {sorted.map((iso) => {
        const label = timeLabel(iso);
        return (
          <div key={iso} className="flex items-center min-h-12 pl-4 pr-1">
            <span className="flex-1 min-w-0 text-callout text-ink tnum py-2">{label}</span>
            <button
              type="button"
              onClick={() => onChange(options.filter((x) => x !== iso))}
              aria-label={tNow('chats5.removeTime', { time: label })}
              className={ICON_BTN}
            >
              <X className="w-4 h-4" strokeWidth={1.75} />
            </button>
          </div>
        );
      })}
      <div className="flex items-center gap-2 px-4 py-2">
        <input
          type="time"
          value={time}
          onChange={(e) => setTime(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          className={FIELD}
          data-testid="time-add"
        />
        <button
          type="button"
          onClick={add}
          aria-label={tNow('chats5.addTime')}
          className={`${ICON_BTN} bg-raised text-ink`}
        >
          <Plus className="w-5 h-5" strokeWidth={2} />
        </button>
      </div>
    </div>
  );
};
