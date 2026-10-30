import React, { useEffect, useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, ChevronRight, Plus, Trash2 } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import {
  MAX_PAGES,
  TEMPLATES,
  blankBlock,
  createPage,
  deletePage,
  getPage,
  listPages,
  restoreVersion,
  savePage,
  subscribePages,
  type LocalPage,
  type TemplateId,
} from '../../services/builder/pages';
import { hasPayments, publishFailures, type CheckProblem } from '../../services/builder/checks';
import { DAYS, LIMITS, canonicalPrice, dishId, parsePoint, sortBlocks, type Block, type BlockType, type Day, type MenuItem } from '../../services/builder/venueBlocks';

export const BUILDER_CABINET_URL = 'https://phantom-os.dev/build';

const usePages = (): LocalPage[] => {
  const [pages, setPages] = useState(listPages);
  useEffect(() => subscribePages(() => setPages(listPages())), []);
  return pages;
};

const inputCls = 'w-full h-11 px-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 outline-none focus-visible:outline-2 focus-visible:outline-accent-line';
const smallBtn = 'h-9 min-h-0 min-w-9 px-3 rounded-control bg-raised text-subhead text-ink-2 active:bg-overlay disabled:opacity-40 flex items-center justify-center';

const Label: React.FC<{ children: React.ReactNode }> = ({ children }) => <span className="block px-1 pb-1 text-caption text-ink-3">{children}</span>;

export const TestPayChip: React.FC = () => {
  const { t } = useTranslation();
  return (
    <span data-testid="builder-testpay" className="inline-flex h-6 items-center rounded-full bg-raised px-2.5 text-caption text-ink-2">
      {t('builder.testPay')}
    </span>
  );
};

// ── список ───────────────────────────────────────────────────────────────────

export const BuilderHub: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useTranslation();
  const pages = usePages();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const full = pages.length >= MAX_PAGES;
  return (
    <>
      <FormSheet
        open
        tall
        onClose={onClose}
        title={t('builder.title')}
        testId="builder-hub"
        footer={
          <PrimaryButton disabled={full} onClick={() => setCreating(true)}>
            {full ? t('builder.limit') : t('builder.create')}
          </PrimaryButton>
        }
      >
        {pages.length === 0 ? (
          <p data-testid="builder-empty" className="px-4 py-10 text-center text-body text-ink-3">
            {t('builder.empty')}
          </p>
        ) : (
          <div className="mx-4 rounded-card bg-surface overflow-hidden">
            {pages.map((p) => (
              <button
                key={p.id}
                type="button"
                data-testid="builder-page-row"
                onClick={() => setEditing(p.id)}
                className="relative w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left active:bg-overlay before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden"
              >
                <span className="flex-1 min-w-0">
                  <span className="block truncate text-body text-ink">{p.name || t('builder.draft')}</span>
                  <span className="block text-caption text-ink-3">
                    {t('builder.draft')} · {t(`builder.tpl.${p.template}` as MessageKey)}
                  </span>
                </span>
                <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
              </button>
            ))}
          </div>
        )}
      </FormSheet>
      {creating && (
        <CreateSheet
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            setEditing(id);
          }}
        />
      )}
      {editing && <BuilderEditor id={editing} onClose={() => setEditing(null)} />}
    </>
  );
};

const CreateSheet: React.FC<{ onClose: () => void; onCreated: (id: string) => void }> = ({ onClose, onCreated }) => {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [tpl, setTpl] = useState<TemplateId>('blank');
  const ok = name.trim().length > 0 && name.trim().length <= LIMITS.title;
  const go = () => {
    const page = createPage(name.trim(), tpl);
    if (page) onCreated(page.id);
  };
  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('builder.create')}
      testId="builder-create"
      layerKey="builder-create"
      layerClassName="z-[1250]"
      footer={
        <PrimaryButton disabled={!ok} onClick={go}>
          {t('builder.create')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-4 space-y-4">
        <label className="block">
          <Label>{t('builder.name')}</Label>
          <input data-testid="builder-name" className={inputCls} value={name} maxLength={LIMITS.title} onChange={(e) => setName(e.target.value)} />
        </label>
        <div role="radiogroup" aria-label={t('builder.template')} className="space-y-2">
          <Label>{t('builder.template')}</Label>
          {(Object.keys(TEMPLATES) as TemplateId[]).map((id) => (
            <button
              key={id}
              type="button"
              role="radio"
              aria-checked={tpl === id}
              data-testid={`builder-tpl-${id}`}
              onClick={() => setTpl(id)}
              className={`w-full min-h-12 px-4 rounded-control flex items-center justify-between text-left text-body text-ink ${tpl === id ? 'bg-overlay border border-accent-line' : 'bg-raised border border-transparent'}`}
            >
              <span>{t(`builder.tpl.${id}` as MessageKey)}</span>
              <span className="text-caption text-ink-3">{TEMPLATES[id].length}</span>
            </button>
          ))}
        </div>
      </div>
    </FormSheet>
  );
};

// ── редактор ─────────────────────────────────────────────────────────────────

type Tab = 'edit' | 'preview' | 'publish';
const MAX_ONE: BlockType[] = ['title', 'about', 'address', 'hours', 'wallet'];

export const BuilderEditor: React.FC<{ id: string; onClose: () => void }> = ({ id, onClose }) => {
  const { t } = useTranslation();
  const initial = useMemo(() => getPage(id), [id]);
  const [blocks, setBlocks] = useState<Block[]>(initial?.blocks ?? []);
  const [tab, setTab] = useState<Tab>('edit');
  const [adding, setAdding] = useState(false);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const pages = usePages();
  const page = pages.find((p) => p.id === id) ?? initial;

  const commit = (next: Block[]) => {
    setBlocks(next);
    savePage(id, next);
  };
  const close = () => {
    savePage(id, blocks, { snapshot: true });
    onClose();
  };
  if (!page) return null;

  const failures = publishFailures(blocks);
  const set = (i: number, b: Block) => commit(blocks.map((x, j) => (j === i ? b : x)));
  const move = (i: number, d: -1 | 1) => {
    const j = i + d;
    if (j < 0 || j >= blocks.length) return;
    const next = [...blocks];
    [next[i], next[j]] = [next[j], next[i]];
    commit(next);
  };
  const free = (Object.keys(blankKinds) as BlockType[]).filter(
    (k) => !(MAX_ONE.includes(k) && blocks.some((b) => b.type === k)) && !(k === 'menu' && blocks.filter((b) => b.type === 'menu').length >= LIMITS.menus) && !(k === 'event' && blocks.filter((b) => b.type === 'event').length >= LIMITS.events),
  );

  return (
    <>
      <FormSheet
        open
        tall
        onClose={close}
        title={page.name || t('builder.draft')}
        testId="builder-editor"
        layerKey="builder-editor"
        layerClassName="z-[1240]"
        footer={
          tab === 'edit' ? (
            <PrimaryButton disabled={free.length === 0} onClick={() => setAdding(true)}>
              {t('builder.addBlock')}
            </PrimaryButton>
          ) : tab === 'publish' ? (
            <a
              data-testid="builder-handoff"
              href={BUILDER_CABINET_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="w-full h-12 rounded-control bg-accent text-on-accent text-headline flex items-center justify-center"
            >
              {t('builder.handoff')}
            </a>
          ) : undefined
        }
      >
        <div role="tablist" className="mx-4 mb-3 h-10 rounded-control bg-raised p-1 flex gap-1">
          {(['edit', 'preview', 'publish'] as Tab[]).map((k) => (
            <button
              key={k}
              type="button"
              role="tab"
              aria-selected={tab === k}
              data-testid={`builder-tab-${k}`}
              onClick={() => setTab(k)}
              className={`flex-1 min-h-0 rounded-[8px] text-subhead ${tab === k ? 'bg-surface text-ink' : 'text-ink-3'}`}
            >
              {k === 'edit' ? t('builder.edit') : k === 'preview' ? t('builder.preview') : t('builder.publish')}
            </button>
          ))}
        </div>

        {tab === 'edit' && (
          <div className="px-4 pb-4 space-y-3">
            {hasPayments(blocks) && <TestPayChip />}
            {blocks.map((b, i) => (
              <section key={`${b.type}-${i}`} data-testid="builder-block" data-kind={b.type} className="rounded-card bg-surface p-3 space-y-2">
                <div className="flex items-center gap-2">
                  <h3 className="flex-1 text-subhead text-ink">{t(`builder.kind.${b.type}` as MessageKey)}</h3>
                  <button type="button" aria-label={t('builder.up')} disabled={i === 0} onClick={() => move(i, -1)} className={smallBtn}>
                    <ArrowUp className="w-4 h-4" strokeWidth={1.75} />
                  </button>
                  <button type="button" aria-label={t('builder.down')} disabled={i === blocks.length - 1} onClick={() => move(i, 1)} className={smallBtn}>
                    <ArrowDown className="w-4 h-4" strokeWidth={1.75} />
                  </button>
                  <button type="button" aria-label={t('builder.remove')} data-testid="builder-remove" onClick={() => commit(blocks.filter((_, j) => j !== i))} className={smallBtn}>
                    <Trash2 className="w-4 h-4" strokeWidth={1.75} />
                  </button>
                </div>
                <BlockFields block={b} onChange={(nb) => set(i, nb)} />
              </section>
            ))}
            <div className="flex gap-2 pt-1">
              <button type="button" data-testid="builder-versions-open" onClick={() => setVersionsOpen(true)} className={`${smallBtn} flex-1`}>
                {t('builder.versions')} · {page.versions.length}
              </button>
              <button type="button" data-testid="builder-delete" onClick={() => setConfirmDelete(true)} className={`${smallBtn} flex-1 text-danger`}>
                {t('builder.delete')}
              </button>
            </div>
          </div>
        )}

        {tab === 'preview' && <VisitorPreview blocks={blocks} />}

        {tab === 'publish' && (
          <div className="px-4 pb-4 space-y-3" data-testid="builder-publish">
            <p data-testid="builder-state" className={`text-headline ${failures.length ? 'text-danger' : 'text-ink'}`}>
              {failures.length ? t('builder.notReady', { n: failures.length }) : t('builder.ready')}
            </p>
            {failures.length > 0 && (
              <ul className="rounded-card bg-surface divide-y divide-line" aria-label={t('builder.checks')}>
                {failures.map((f, i) => (
                  <li key={`${f.problem}-${f.block}-${i}`} data-testid="builder-failure" className="px-4 py-3">
                    <span className="block text-body text-ink">{problemLabel(t, f.problem)}</span>
                    {f.block != null && <span className="block text-caption text-ink-3">{t(`builder.kind.${blocks[f.block].type}` as MessageKey)}</span>}
                  </li>
                ))}
              </ul>
            )}
            {hasPayments(blocks) && <TestPayChip />}
            <p className="text-caption text-ink-3">{t('builder.onlyCabinet')}</p>
          </div>
        )}
      </FormSheet>

      {adding && (
        <FormSheet open onClose={() => setAdding(false)} title={t('builder.addBlock')} testId="builder-add" layerKey="builder-add" layerClassName="z-[1260]">
          <div className="mx-4 mb-4 rounded-card bg-surface overflow-hidden">
            {free.map((k) => (
              <button
                key={k}
                type="button"
                data-testid={`builder-add-${k}`}
                onClick={() => {
                  commit([...blocks, blankBlock(k)]);
                  setAdding(false);
                }}
                className="relative w-full min-h-12 px-4 flex items-center gap-3 text-left text-body text-ink active:bg-overlay before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden"
              >
                <Plus className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
                {t(`builder.kind.${k}` as MessageKey)}
              </button>
            ))}
          </div>
        </FormSheet>
      )}

      {versionsOpen && (
        <FormSheet open onClose={() => setVersionsOpen(false)} title={t('builder.versions')} testId="builder-versions" layerKey="builder-versions" layerClassName="z-[1260]">
          {page.versions.length === 0 ? (
            <p className="px-4 py-8 text-center text-body text-ink-3">{t('builder.noVersions')}</p>
          ) : (
            <div className="mx-4 mb-4 rounded-card bg-surface overflow-hidden">
              {page.versions.map((v) => (
                <div key={v.at} className="relative px-4 min-h-14 flex items-center gap-3 before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden">
                  <span className="flex-1 min-w-0">
                    <span className="block text-body text-ink tabular-nums">{new Date(v.at).toLocaleString()}</span>
                    <span className="block text-caption text-ink-3">{v.blocks.length}</span>
                  </span>
                  <button
                    type="button"
                    data-testid="builder-restore"
                    className={smallBtn}
                    onClick={() => {
                      const next = restoreVersion(id, v.at);
                      if (next) setBlocks(next.blocks);
                      setVersionsOpen(false);
                    }}
                  >
                    {t('builder.restore')}
                  </button>
                </div>
              ))}
            </div>
          )}
        </FormSheet>
      )}

      {confirmDelete && (
        <FormSheet
          open
          onClose={() => setConfirmDelete(false)}
          title={t('builder.deleteAsk', { name: page.name })}
          testId="builder-delete-confirm"
          layerKey="builder-delete"
          layerClassName="z-[1270]"
          footer={
            <button
              type="button"
              data-testid="builder-delete-yes"
              onClick={() => {
                deletePage(id);
                onClose();
              }}
              className="w-full h-12 rounded-control bg-danger text-on-accent text-headline"
            >
              {t('builder.delete')}
            </button>
          }
        >
          <div className="px-4 pb-2">
            <button type="button" onClick={() => setConfirmDelete(false)} className={`${smallBtn} w-full`}>
              {t('builder.cancel')}
            </button>
          </div>
        </FormSheet>
      )}
    </>
  );
};

const blankKinds: Record<BlockType, true> = { title: true, about: true, address: true, hours: true, menu: true, event: true, wallet: true };

function problemLabel(t: (k: MessageKey, v?: Record<string, string | number>) => string, p: CheckProblem): string {
  const known: string[] = ['missing_title', 'needs_address', 'no_point', 'bad_text', 'bad_hours', 'no_items', 'bad_price', 'bad_item', 'bad_event_time', 'bad_wallet', 'duplicate_block'];
  return t((known.includes(p) ? `builder.p.${p}` : 'builder.p.other') as MessageKey);
}

// ── поля блоків ──────────────────────────────────────────────────────────────

const BlockFields: React.FC<{ block: Block; onChange: (b: Block) => void }> = ({ block, onChange }) => {
  const { t } = useTranslation();
  const [point, setPoint] = useState(() => (block.type === 'address' && (block.lat || block.lon) ? `${block.lat}, ${block.lon}` : ''));
  switch (block.type) {
    case 'title':
      return <input aria-label={t('builder.kind.title')} className={inputCls} maxLength={LIMITS.title} value={block.text} onChange={(e) => onChange({ ...block, text: e.target.value })} />;
    case 'about':
      return (
        <textarea
          aria-label={t('builder.kind.about')}
          className={`${inputCls} h-28 py-2`}
          maxLength={LIMITS.about}
          value={block.text}
          onChange={(e) => onChange({ ...block, text: e.target.value })}
        />
      );
    case 'address':
      return (
        <div className="space-y-2">
          <input aria-label={t('builder.f.address')} placeholder={t('builder.f.address')} className={inputCls} maxLength={LIMITS.address} value={block.text} onChange={(e) => onChange({ ...block, text: e.target.value })} />
          <input
            aria-label={t('builder.f.point')}
            placeholder={t('builder.f.point')}
            inputMode="decimal"
            className={inputCls}
            value={point}
            onChange={(e) => {
              setPoint(e.target.value);
              const p = parsePoint(e.target.value);
              onChange({ ...block, lat: p?.lat ?? 0, lon: p?.lon ?? 0 });
            }}
          />
        </div>
      );
    case 'hours':
      return <HoursFields rows={block.rows} onChange={(rows) => onChange({ ...block, rows })} />;
    case 'menu':
      return <MenuFields block={block} onChange={onChange} />;
    case 'event':
      return (
        <div className="space-y-2">
          <input aria-label={t('builder.f.evTitle')} placeholder={t('builder.f.evTitle')} className={inputCls} maxLength={LIMITS.title} value={block.title} onChange={(e) => onChange({ ...block, title: e.target.value })} />
          <label className="block">
            <Label>{t('builder.f.starts')}</Label>
            <input type="datetime-local" className={inputCls} value={block.starts} onChange={(e) => onChange({ ...block, starts: e.target.value })} />
          </label>
          <label className="block">
            <Label>{t('builder.f.ends')}</Label>
            <input
              type="datetime-local"
              className={inputCls}
              value={block.ends ?? ''}
              onChange={(e) => {
                const { ends: _drop, ...rest } = block;
                onChange(e.target.value ? { ...rest, ends: e.target.value } : rest);
              }}
            />
          </label>
        </div>
      );
    case 'wallet':
      return <input aria-label={t('builder.f.wallet')} placeholder={t('builder.f.wallet')} className={inputCls} autoCapitalize="off" autoCorrect="off" spellCheck={false} value={block.address} onChange={(e) => onChange({ ...block, address: e.target.value.trim() })} />;
  }
};

const HoursFields: React.FC<{ rows: { day: Day; open: string; close: string }[]; onChange: (r: { day: Day; open: string; close: string }[]) => void }> = ({ rows, onChange }) => {
  const { t } = useTranslation();
  const upd = (day: Day, patch: Partial<{ open: string; close: string }>) =>
    onChange(rows.map((r) => (r.day === day ? { ...r, ...patch } : r)));
  const missing = DAYS.filter((d) => !rows.some((r) => r.day === d));
  const add = (day: Day) => onChange(DAYS.flatMap((d) => (d === day ? [{ day, open: '09:00', close: '18:00' }] : rows.filter((r) => r.day === d))));
  return (
    <div className="space-y-2">
      {rows.map((r) => (
        <div key={r.day} data-testid="builder-hours-row" className="flex items-center gap-2">
          <span className="w-9 text-subhead text-ink-2">{t(`builder.day.${r.day}` as MessageKey)}</span>
          <input type="time" aria-label={t('builder.f.open')} className={inputCls} value={r.open} onChange={(e) => upd(r.day, { open: e.target.value })} />
          <input type="time" aria-label={t('builder.f.close')} className={inputCls} value={r.close} onChange={(e) => upd(r.day, { close: e.target.value })} />
          <button type="button" aria-label={t('builder.remove')} className={smallBtn} onClick={() => onChange(rows.filter((x) => x.day !== r.day))}>
            <Trash2 className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      ))}
      {missing.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {missing.map((d) => (
            <button key={d} type="button" data-testid={`builder-add-day-${d}`} aria-label={`${t('builder.f.addDay')} ${t(`builder.day.${d}` as MessageKey)}`} className={smallBtn} onClick={() => add(d)}>
              {t(`builder.day.${d}` as MessageKey)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

const MenuFields: React.FC<{ block: Extract<Block, { type: 'menu' }>; onChange: (b: Block) => void }> = ({ block, onChange }) => {
  const { t } = useTranslation();
  const [prices, setPrices] = useState<Record<string, string>>(() => Object.fromEntries(block.items.map((i) => [i.id, i.price])));
  const setItem = (id: string, patch: Partial<MenuItem>) => onChange({ ...block, items: block.items.map((i) => (i.id === id ? { ...i, ...patch } : i)) });
  return (
    <div className="space-y-2">
      <input aria-label={t('builder.f.menuTitle')} placeholder={t('builder.f.menuTitle')} className={inputCls} maxLength={LIMITS.menuTitle} value={block.title ?? ''} onChange={(e) => {
        const { title: _d, ...rest } = block;
        onChange(e.target.value ? { ...rest, title: e.target.value } : rest);
      }} />
      {block.items.map((it) => (
        <div key={it.id} data-testid="builder-dish" className="flex items-center gap-2">
          <input aria-label={t('builder.f.dish')} placeholder={t('builder.f.dish')} className={inputCls} maxLength={LIMITS.dishName} value={it.name} onChange={(e) => setItem(it.id, { name: e.target.value })} />
          <input
            aria-label={t('builder.f.price')}
            placeholder={`${t('builder.f.price')} ${block.token}`}
            inputMode="decimal"
            className={`${inputCls} w-28`}
            value={prices[it.id] ?? it.price}
            onChange={(e) => {
              setPrices({ ...prices, [it.id]: e.target.value });
              setItem(it.id, { price: canonicalPrice(e.target.value, block.token) ?? '' });
            }}
          />
          <button type="button" aria-label={t('builder.remove')} className={smallBtn} onClick={() => onChange({ ...block, items: block.items.filter((x) => x.id !== it.id) })}>
            <Trash2 className="w-4 h-4" strokeWidth={1.75} />
          </button>
        </div>
      ))}
      <button
        type="button"
        data-testid="builder-add-dish"
        className={`${smallBtn} w-full`}
        disabled={block.items.length >= LIMITS.dishes}
        onClick={() => onChange({ ...block, items: [...block.items, { id: dishId(`item ${block.items.length + 1}`, new Set(block.items.map((i) => i.id))), name: '', price: '' }] })}
      >
        {t('builder.f.addDish')}
      </button>
    </div>
  );
};

// ── перегляд очима гостя ─────────────────────────────────────────────────────

export const VisitorPreview: React.FC<{ blocks: Block[] }> = ({ blocks }) => {
  const { t } = useTranslation();
  const shown = sortBlocks(blocks).filter(hasContent);
  return (
    <div data-testid="builder-preview" className="mx-4 mb-4 rounded-card bg-surface p-4 space-y-4">
      {shown.length === 0 && <p className="py-6 text-center text-body text-ink-3">{t('builder.previewEmpty')}</p>}
      {shown.map((b, i) => (
        <div key={i}>
          {b.type === 'title' && <h2 className="text-title-1 text-ink">{b.text}</h2>}
          {b.type === 'about' && <p className="text-body text-ink-2 whitespace-pre-wrap">{b.text}</p>}
          {b.type === 'address' && <p className="text-body text-ink">{b.text}</p>}
          {b.type === 'hours' && (
            <ul className="space-y-1">
              {b.rows.map((r) => (
                <li key={r.day} className="flex justify-between text-body text-ink-2">
                  <span>{t(`builder.day.${r.day}` as MessageKey)}</span>
                  <span className="tabular-nums">{r.open}–{r.close}</span>
                </li>
              ))}
            </ul>
          )}
          {b.type === 'menu' && (
            <div className="space-y-1">
              {b.title && <h3 className="text-subhead text-ink-3">{b.title}</h3>}
              {b.items.filter((it) => it.name.trim()).map((it) => (
                <p key={it.id} className="flex justify-between text-body text-ink">
                  <span>{it.name}</span>
                  <span className="tabular-nums text-ink-2">{it.price} {b.token}</span>
                </p>
              ))}
            </div>
          )}
          {b.type === 'event' && (
            <p className="text-body text-ink">
              {b.title} <span className="text-ink-3 tabular-nums">{b.starts.replace('T', ' ')}</span>
            </p>
          )}
          {b.type === 'wallet' && <p className="text-caption text-ink-3 break-all">{b.address}</p>}
        </div>
      ))}
      {hasPayments(blocks) && <TestPayChip />}
    </div>
  );
};

function hasContent(b: Block): boolean {
  switch (b.type) {
    case 'title':
    case 'about':
    case 'address':
      return b.text.trim().length > 0;
    case 'hours':
      return b.rows.length > 0;
    case 'menu':
      return b.items.some((i) => i.name.trim());
    case 'event':
      return b.title.trim().length > 0;
    case 'wallet':
      return b.address.length > 0;
  }
}
