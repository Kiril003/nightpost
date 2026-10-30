import { useId, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Btn, Field, controlSkin, labelSkin } from '../account/Panels';
import { LIMITS, TOKENS, type Day, type HoursRow, type Token } from '../../lib/venueBlocks';
import type { BuildCopy } from '../../i18n/build';
import { builderA, type BuilderACopy } from '../../i18n/builderA';
import { useLanguage } from '../../i18n';
import { morph } from '../../lib/morph';
import {
  blankDish,
  blankEvent,
  blankMenu,
  type DishDraft,
  type Draft,
  type EventDraft,
  type FieldErrors,
  type MenuDraft,
  withToken,
} from './draft';
import { compactHours, setDay, weekStrip } from './builderModel';

const ICON = { strokeWidth: 1.75 } as const;

export default function VenueEditor({
  draft,
  onChange,
  fields,
  copy,
  disabled = false,
}: {
  draft: Draft;
  onChange: (next: Draft) => void;
  fields: FieldErrors;
  copy: BuildCopy;
  disabled?: boolean;
}) {
  const { lang } = useLanguage();
  const a = builderA[lang];
  const e = copy.editor;
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => onChange({ ...draft, [key]: value });

  return (
    <fieldset disabled={disabled} data-flow className="min-w-0 space-y-8">
      <section id="sec-basics" className="rounded-card border border-line bg-surface p-6 space-y-5">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.basics}</h2>
        <Field
          label={e.title}
          value={draft.title}
          onChange={(v) => set('title', v)}
          maxLength={LIMITS.title}
          required
          error={fields.title ? copy.problems.missing_title : null}
        />
        <TextArea label={e.about} value={draft.about} max={LIMITS.about} onChange={(v) => set('about', v)} />
      </section>

      <Hours rows={draft.hours} onChange={(rows) => set('hours', rows)} copy={copy} a={a} />

      <section id="sec-location" className="rounded-card border border-line bg-surface p-6 space-y-5">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.location}</h2>
        <div data-map-slot />
        <div className="grid gap-5 sm:grid-cols-2">
          <Field
            label={e.address}
            value={draft.address}
            onChange={(v) => set('address', v)}
            placeholder={e.addressPlaceholder}
            maxLength={LIMITS.address}
            autoComplete="street-address"
            error={fields.address ? e.addressMissing : null}
          />
          <Field
            label={e.point}
            value={draft.point}
            onChange={(v) => set('point', v)}
            placeholder={e.pointPlaceholder}
            inputMode="decimal"
            error={fields.point === 'missing' ? e.pointMissing : fields.point ? e.pointBad : null}
          />
        </div>
      </section>

      <Events draft={draft} onChange={onChange} fields={fields} copy={copy} a={a} />

      <Menus draft={draft} onChange={onChange} fields={fields} copy={copy} a={a} />

      <section id="sec-wallet" className="rounded-card border border-line bg-surface p-6 space-y-5">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.payoutWallet}</h2>
        <Field
          label={e.wallet}
          value={draft.wallet}
          onChange={(v) => set('wallet', v.trim())}
          placeholder={e.walletPlaceholder}
          mono
          spellCheck={false}
          autoCapitalize="off"
          autoComplete="off"
        />
      </section>
    </fieldset>
  );
}

function TextArea({
  label,
  value,
  max,
  onChange,
}: {
  label: string;
  value: string;
  max: number;
  onChange: (v: string) => void;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className={labelSkin}>
        {label}
      </label>
      <textarea
        id={id}
        value={value}
        maxLength={max}
        rows={3}
        onChange={(ev) => onChange(ev.target.value)}
        className={`${controlSkin} px-4 py-3`}
      />
    </div>
  );
}

function Hours({
  rows,
  onChange,
  copy,
  a,
}: {
  rows: HoursRow[];
  onChange: (rows: HoursRow[]) => void;
  copy: BuildCopy;
  a: BuilderACopy;
}) {
  const e = copy.editor;
  const [selected, setSelected] = useState<Day>(() => rows[0]?.day ?? 'mon');
  const strip = weekStrip(rows);
  const current = strip.find((slot) => slot.day === selected) ?? { day: selected, open: null, close: null };
  const isOpen = current.open !== null && current.close !== null;

  const toggleDay = () => {
    if (isOpen) {
      morph(() => onChange(setDay(rows, selected, null)));
    } else if (rows.length < LIMITS.hoursRows) {
      morph(() => onChange(setDay(rows, selected, { open: '08:00', close: '22:00' })));
    }
  };

  return (
    <section id="sec-hours" data-flow className="rounded-card border border-line bg-surface p-6 space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.hours}</h2>
        <span className="text-subhead text-ink-3">{a.editor.daySelected(copy.days[selected])}</span>
      </div>

      <div className="flex gap-1.5">
        {strip.map((slot) => {
          const on = slot.day === selected;
          const closed = slot.open === null || slot.close === null;
          const status = closed ? a.editor.closed : compactHours(slot.open!, slot.close!);
          return (
            <button
              key={slot.day}
              type="button"
              onClick={() => setSelected(slot.day)}
              aria-pressed={on}
              aria-label={`${copy.days[slot.day]}, ${status}`}
              className={`flex min-h-[44px] min-w-0 flex-1 flex-col items-center justify-center gap-1 rounded-tile bg-raised px-1 py-2 text-center transition-colors ${
                on ? 'ring-1 ring-ink' : ''
              } ${closed ? 'opacity-70' : ''}`}
            >
              <span className="block truncate text-footnote font-semibold text-ink">{copy.days[slot.day]}</span>
              <span
                aria-hidden
                className={`h-1.5 w-1.5 rounded-full ${closed ? 'bg-line-strong' : 'bg-ink'}`}
              />
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded-control bg-raised px-4 py-3">
        <span className="min-w-0 flex-1 text-callout font-semibold text-ink">{copy.days[selected]}</span>
        {isOpen && (
          <>
            <input
              type="time"
              aria-label={e.open}
              value={current.open!}
              onChange={(ev) =>
                onChange(setDay(rows, selected, { open: ev.target.value, close: current.close! }))
              }
              className={`${controlSkin} tnum h-10 !w-[7.5rem] px-3`}
            />
            <span className="text-ink-3" aria-hidden>
              –
            </span>
            <input
              type="time"
              aria-label={e.close}
              value={current.close!}
              onChange={(ev) =>
                onChange(setDay(rows, selected, { open: current.open!, close: ev.target.value }))
              }
              className={`${controlSkin} tnum h-10 !w-[7.5rem] px-3`}
            />
          </>
        )}
        <button
          type="button"
          onClick={toggleDay}
          aria-pressed={!isOpen}
          className={`inline-flex h-10 items-center rounded-full px-3.5 text-footnote font-semibold transition-colors ${
            !isOpen ? 'bg-ink text-canvas' : 'bg-overlay text-ink-2'
          }`}
        >
          {a.editor.closed}
        </button>
      </div>
    </section>
  );
}

function Events({
  draft,
  onChange,
  fields,
  copy,
  a,
}: {
  draft: Draft;
  onChange: (next: Draft) => void;
  fields: FieldErrors;
  copy: BuildCopy;
  a: BuilderACopy;
}) {
  const e = copy.editor;
  const rows = draft.events ?? [];
  const setEvents = (events: EventDraft[]) => onChange({ ...draft, events });
  const patchEvent = (key: string, patch: Partial<EventDraft>) =>
    setEvents(rows.map((ev) => (ev.key === key ? { ...ev, ...patch } : ev)));

  return (
    <section id="sec-events" data-flow className="rounded-card border border-line bg-surface p-6 space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.events}</h2>
        {rows.length < LIMITS.events && (
          <Btn tone="secondary" onClick={() => morph(() => setEvents([...rows, blankEvent()]))}>
            <Plus className="h-4 w-4" {...ICON} aria-hidden />
            {e.addEvent}
          </Btn>
        )}
      </div>

      {rows.length > 0 && (
        <ul data-flow className="space-y-4">
          {rows.map((ev, n) => (
            <li key={ev.key} className="space-y-3 rounded-card border border-line p-4">
              <div className="grid grid-cols-[minmax(0,1fr)_7rem_auto] items-start gap-2">
                <input
                  aria-label={`${e.eventTitle} ${n + 1}`}
                  value={ev.title}
                  placeholder={e.eventTitle}
                  maxLength={LIMITS.title}
                  onChange={(evChange) => patchEvent(ev.key, { title: evChange.target.value })}
                  aria-invalid={fields[`eventTitle:${ev.key}`] ? true : undefined}
                  className={`${controlSkin} h-12 px-3`}
                />
                <div>
                  <input
                    aria-label={`${e.price} ${n + 1}, ${ev.token ?? draft.token}`}
                    value={ev.price}
                    placeholder="0.00"
                    inputMode="decimal"
                    onChange={(evChange) => patchEvent(ev.key, { price: evChange.target.value })}
                    aria-invalid={fields[`eventPrice:${ev.key}`] ? true : undefined}
                    className={`${controlSkin} tnum h-12 px-3 text-right`}
                  />
                  <span data-unit className="mt-1 block text-right text-caption text-ink-3">{ev.token ?? draft.token}</span>
                </div>
                <button
                  type="button"
                  onClick={() => morph(() => setEvents(rows.filter((x) => x.key !== ev.key)))}
                  aria-label={`${e.remove}: ${ev.title || `${e.eventTitle} ${n + 1}`}`}
                  className="btn-ghost !min-h-[48px] !px-3"
                >
                  <Trash2 className="h-4 w-4" {...ICON} aria-hidden />
                </button>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block">
                  <span className={labelSkin}>{e.eventStarts}</span>
                  <input
                    type="datetime-local"
                    aria-label={`${e.eventStarts} ${n + 1}`}
                    value={ev.starts}
                    onChange={(evChange) => patchEvent(ev.key, { starts: evChange.target.value })}
                    aria-invalid={fields[`eventStarts:${ev.key}`] ? true : undefined}
                    className={`${controlSkin} tnum h-12 px-3`}
                  />
                </label>
                <label className="block">
                  <span className={labelSkin}>{e.eventEnds}</span>
                  <input
                    type="datetime-local"
                    aria-label={`${e.eventEnds} ${n + 1}`}
                    value={ev.ends}
                    onChange={(evChange) => patchEvent(ev.key, { ends: evChange.target.value })}
                    aria-invalid={fields[`eventEnds:${ev.key}`] ? true : undefined}
                    className={`${controlSkin} tnum h-12 px-3`}
                  />
                </label>
              </div>
              <input
                aria-label={`${e.note} ${n + 1}`}
                value={ev.note}
                placeholder={e.note}
                maxLength={LIMITS.dishNote}
                onChange={(evChange) => patchEvent(ev.key, { note: evChange.target.value })}
                className={`${controlSkin} h-12 px-3 !text-footnote`}
              />
              {eventError(ev, draft, fields, copy) && (
                <p role="alert" className="text-footnote text-danger">
                  {eventError(ev, draft, fields, copy)}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function eventError(ev: EventDraft, draft: Draft, fields: FieldErrors, copy: BuildCopy): string | null {
  const p = copy.problems;
  if (fields[`eventTitle:${ev.key}`]) return p.bad_text;
  if (fields[`eventStarts:${ev.key}`] === 'missing') return p.missing_event_start;
  if (fields[`eventStarts:${ev.key}`] || fields[`eventEnds:${ev.key}`]) return p.bad_event_time;
  if (fields[`eventPrice:${ev.key}`]) return copy.editor.priceBad(ev.token ?? draft.token);
  return null;
}

function Menus({
  draft,
  onChange,
  fields,
  copy,
  a,
}: {
  draft: Draft;
  onChange: (next: Draft) => void;
  fields: FieldErrors;
  copy: BuildCopy;
  a: BuilderACopy;
}) {
  const e = copy.editor;
  const setMenus = (menus: MenuDraft[]) => onChange({ ...draft, menus });
  const patchMenu = (key: string, patch: Partial<MenuDraft>) =>
    setMenus(draft.menus.map((m) => (m.key === key ? { ...m, ...patch } : m)));
  const patchDish = (menu: MenuDraft, key: string, patch: Partial<DishDraft>) =>
    patchMenu(menu.key, { items: menu.items.map((i) => (i.key === key ? { ...i, ...patch } : i)) });

  const totalDishes = draft.menus.reduce(
    (sum, m) => sum + m.items.filter((i) => i.name.trim().length > 0).length,
    0,
  );

  return (
    <section id="sec-menu" data-flow className="rounded-card border border-line bg-surface p-6 space-y-5">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-[20px] font-semibold text-ink">{a.editor.menu}</h2>
        <span className="tnum text-subhead text-ink-3">{a.editor.dishesCount(totalDishes)}</span>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <div className="flex gap-1.5" role="group" aria-label={e.token}>
            {TOKENS.map((t) => {
              const on = draft.token === t;
              return (
                <button
                  key={t}
                  type="button"
                  aria-pressed={on}
                  onClick={() => onChange(withToken(draft, t as Token))}
                  className={`h-9 rounded-full px-3.5 text-subhead font-semibold transition-colors ${
                    on ? 'bg-ink text-canvas' : 'bg-raised text-ink-2'
                  }`}
                >
                  {t}
                </button>
              );
            })}
          </div>
          {draft.menus.length < LIMITS.menus && (
            <Btn tone="secondary" onClick={() => morph(() => setMenus([...draft.menus, blankMenu()]))}>
              <Plus className="h-4 w-4" {...ICON} aria-hidden />
              {a.editor.section}
            </Btn>
          )}
        </div>
      </div>

      <div data-flow className="grid gap-8 lg:grid-cols-2">
        {draft.menus.map((menu) => {
          const namedCount = menu.items.filter((i) => i.name.trim().length > 0).length;
          return (
            <div key={menu.key} data-flow className="min-w-0 space-y-3">
              <div className="flex items-end gap-2">
                <div className="min-w-0 flex-1">
                  <Field
                    label={`${e.menuTitle} (${namedCount})`}
                    value={menu.title}
                    onChange={(v) => patchMenu(menu.key, { title: v })}
                    placeholder={e.menuTitlePlaceholder}
                    maxLength={LIMITS.menuTitle}
                  />
                </div>
                {draft.menus.length > 1 && (
                  <button
                    type="button"
                    onClick={() => morph(() => setMenus(draft.menus.filter((m) => m.key !== menu.key)))}
                    aria-label={e.removeMenu}
                    className="btn-ghost !min-h-[48px] !px-3"
                  >
                    <Trash2 className="h-4 w-4" {...ICON} aria-hidden />
                  </button>
                )}
              </div>

              <ul data-flow className="divide-y divide-line">
                {menu.items.map((dish, n) => (
                  <li key={dish.key} className="grid grid-cols-[minmax(0,1fr)_7.5rem_auto] items-start gap-2 py-3">
                    <div className="min-w-0">
                      <input
                        aria-label={`${e.dish} ${n + 1}`}
                        value={dish.name}
                        placeholder={e.dishPlaceholder}
                        maxLength={LIMITS.dishName}
                        onChange={(ev) => patchDish(menu, dish.key, { name: ev.target.value })}
                        aria-invalid={fields[`name:${dish.key}`] ? true : undefined}
                        className={`${controlSkin} h-11 px-3`}
                      />
                      <input
                        aria-label={`${e.note} ${n + 1}`}
                        value={dish.note}
                        placeholder={e.note}
                        maxLength={LIMITS.dishNote}
                        onChange={(ev) => patchDish(menu, dish.key, { note: ev.target.value })}
                        className={`${controlSkin} mt-1 h-9 px-3 !text-footnote`}
                      />
                    </div>
                    <div className="relative">
                      <input
                        aria-label={`${e.price} ${n + 1}, ${draft.token}`}
                        value={dish.price}
                        placeholder="0.00"
                        inputMode="decimal"
                        onChange={(ev) => patchDish(menu, dish.key, { price: ev.target.value })}
                        aria-invalid={fields[`price:${dish.key}`] ? true : undefined}
                        title={fields[`price:${dish.key}`] ? e.priceBad(draft.token) : undefined}
                        className={`${controlSkin} tnum h-11 px-3 text-right`}
                      />
                      <span className="mt-1 block text-right text-caption text-ink-3">{draft.token}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() =>
                        morph(() =>
                          patchMenu(menu.key, {
                            items:
                              menu.items.length > 1
                                ? menu.items.filter((i) => i.key !== dish.key)
                                : [blankDish()],
                          }),
                        )
                      }
                      aria-label={`${e.remove}: ${dish.name || `${e.dish} ${n + 1}`}`}
                      className="btn-ghost !min-h-[44px] !px-3"
                    >
                      <Trash2 className="h-4 w-4" {...ICON} aria-hidden />
                    </button>
                  </li>
                ))}
              </ul>
              {menu.items.some((i) => fields[`name:${i.key}`]) && (
                <p role="alert" className="text-footnote text-danger">
                  {copy.problems.bad_item}
                </p>
              )}
              {menu.items.some((i) => fields[`price:${i.key}`]) && (
                <p role="alert" className="text-footnote text-danger">
                  {e.priceBad(draft.token)}
                </p>
              )}
              {menu.items.length < LIMITS.dishes && (
                <Btn
                  tone="ghost"
                  onClick={() => morph(() => patchMenu(menu.key, { items: [...menu.items, blankDish()] }))}
                >
                  <Plus className="h-4 w-4" {...ICON} aria-hidden />
                  {a.editor.dish}
                </Btn>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
