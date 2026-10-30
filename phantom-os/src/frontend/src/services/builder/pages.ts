import { sortBlocks, type Block, type VenuePage, PAGE_VERSION } from './venueBlocks';

export interface PageVersion {
  at: number;
  name: string;
  blocks: Block[];
}

export interface LocalPage {
  id: string;
  name: string;
  template: TemplateId;
  blocks: Block[];
  createdMs: number;
  updatedMs: number;
  versions: PageVersion[];
}

export type TemplateId = 'blank' | 'cafe' | 'club' | 'studio';

/** Шаблон — лише набір видів блоків; змісту в ньому немає, його вписує власник. */
export const TEMPLATES: Record<TemplateId, Block['type'][]> = {
  blank: ['title'],
  cafe: ['title', 'about', 'address', 'hours', 'menu'],
  club: ['title', 'about', 'address', 'event', 'wallet'],
  studio: ['title', 'about', 'address', 'hours'],
};

const KEY = 'nightpost.builder.pages.v1';
export const MAX_PAGES = 12;
export const MAX_VERSIONS = 10;

export function blankBlock(type: Block['type'], name = ''): Block {
  switch (type) {
    case 'title':
      return { type, text: name };
    case 'about':
      return { type, text: '' };
    case 'address':
      return { type, text: '', lat: 0, lon: 0 };
    case 'hours':
      return { type, rows: [] };
    case 'menu':
      return { type, token: 'USDC', items: [] };
    case 'event':
      return { type, id: `ev-${Math.random().toString(36).slice(2, 8)}`, title: '', starts: '' };
    case 'wallet':
      return { type, address: '' };
  }
}

export function pageOf(p: Pick<LocalPage, 'blocks'>): VenuePage {
  return { v: PAGE_VERSION, blocks: p.blocks };
}

function read(): LocalPage[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(KEY) || 'null');
    const list = (raw as { pages?: unknown } | null)?.pages;
    return Array.isArray(list) ? (list as LocalPage[]).filter((p) => p && typeof p.id === 'string' && Array.isArray(p.blocks)) : [];
  } catch {
    return [];
  }
}

function write(pages: LocalPage[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, pages }));
  } catch {
    // Сховище недоступне: сторінка живе до закриття екрана.
  }
  listeners.forEach((f) => f());
}

const listeners = new Set<() => void>();
export const subscribePages = (f: () => void): (() => void) => {
  listeners.add(f);
  return () => listeners.delete(f);
};

export const listPages = (): LocalPage[] => read().sort((a, b) => b.updatedMs - a.updatedMs);
export const getPage = (id: string): LocalPage | null => read().find((p) => p.id === id) ?? null;

export function createPage(name: string, template: TemplateId, now = Date.now()): LocalPage | null {
  const pages = read();
  if (pages.length >= MAX_PAGES) return null;
  const blocks = TEMPLATES[template].map((t) => blankBlock(t, t === 'title' ? name : ''));
  const page: LocalPage = {
    id: `pg-${now.toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
    name,
    template,
    blocks,
    createdMs: now,
    updatedMs: now,
    versions: [],
  };
  write([...pages, page]);
  return page;
}

/** Зберегти правки. Знімок версії — тільки коли зміст справді змінився. */
export function savePage(id: string, blocks: Block[], opts: { snapshot?: boolean; now?: number } = {}): LocalPage | null {
  const pages = read();
  const cur = pages.find((p) => p.id === id);
  if (!cur) return null;
  const now = opts.now ?? Date.now();
  const changed = JSON.stringify(cur.blocks) !== JSON.stringify(blocks);
  const title = blocks.find((b) => b.type === 'title');
  const next: LocalPage = {
    ...cur,
    blocks,
    name: title && title.type === 'title' && title.text.trim() ? title.text.trim() : cur.name,
    updatedMs: changed ? now : cur.updatedMs,
    versions:
      opts.snapshot && changed
        ? [{ at: now, name: cur.name, blocks: cur.blocks }, ...cur.versions].slice(0, MAX_VERSIONS)
        : cur.versions,
  };
  write(pages.map((p) => (p.id === id ? next : p)));
  return next;
}

export function restoreVersion(id: string, at: number, now = Date.now()): LocalPage | null {
  const cur = getPage(id);
  const v = cur?.versions.find((x) => x.at === at);
  if (!cur || !v) return null;
  // Нинішній стан теж стає версією: відновлення можна скасувати.
  return savePage(id, v.blocks, { snapshot: true, now });
}

export function deletePage(id: string): void {
  write(read().filter((p) => p.id !== id));
}

export const ordered = (blocks: Block[]): Block[] => sortBlocks(blocks);
