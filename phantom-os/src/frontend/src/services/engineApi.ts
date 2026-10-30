import { request, ApiError } from './api';

export interface EngineEntry {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
  sizeDisplay: string;
  mtimeMs: number | null;
}

export interface EngineListing {
  path: string;
  display: string;
  parent: string | null;
  entries: EngineEntry[];
}

export interface EngineMatch {
  name: string;
  path: string;
  sizeDisplay: string;
  mtimeMs: number | null;
}

export type EnginePreview =
  | {
      kind: 'text';
      name: string;
      path: string;
      sizeDisplay: string;
      truncated: boolean;
      content: string;
    }
  | {
      kind: 'binary';
      name: string;
      path: string;
      sizeDisplay: string;
      truncated: boolean;
    };

export type EngineFailReason = 'refused' | 'missing' | 'offline';

export type EngineResult<T> =
  | { ok: true; data: T }
  | { ok: false; reason: EngineFailReason; status: number };

interface RawEntry {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  size_display: string;
  mtime_ms?: number | null;
}

interface RawListing {
  path: string;
  display: string;
  parent: string | null;
  entries: RawEntry[];
}

interface RawMatch {
  name: string;
  abs_path: string;
  size_display: string;
  mtime_ms?: number | null;
}

interface RawSearch {
  matches: RawMatch[];
}

interface RawRead {
  kind: 'text' | 'binary';
  name: string;
  path: string;
  size_display: string;
  truncated: boolean;
  content?: string;
}

function fail<T>(err: unknown): EngineResult<T> {
  if (err instanceof ApiError) {
    if (err.status === 403 || err.status === 400) {
      return { ok: false, reason: 'refused', status: err.status };
    }
    if (err.status === 404) {
      return { ok: false, reason: 'missing', status: 404 };
    }
    return { ok: false, reason: 'offline', status: err.status };
  }
  return { ok: false, reason: 'offline', status: 0 };
}

async function list(path?: string): Promise<EngineResult<EngineListing>> {
  const url = path === undefined ? '/files/list' : `/files/list?path=${encodeURIComponent(path)}`;
  try {
    const raw = await request<RawListing>('GET', url);
    return {
      ok: true,
      data: {
        path: raw.path,
        display: raw.display,
        parent: raw.parent ?? null,
        entries: (raw.entries ?? []).map((e) => ({
          name: e.name,
          path: e.path,
          isDir: Boolean(e.is_dir),
          size: e.size ?? 0,
          sizeDisplay: e.size_display ?? '',
          mtimeMs: e.mtime_ms ?? null,
        })),
      },
    };
  } catch (err) {
    return fail(err);
  }
}

async function search(query: string, root?: string): Promise<EngineResult<EngineMatch[]>> {
  const qs =
    root === undefined
      ? `query=${encodeURIComponent(query)}`
      : `query=${encodeURIComponent(query)}&root=${encodeURIComponent(root)}`;
  try {
    const raw = await request<RawSearch>('GET', `/files/search?${qs}`);
    return {
      ok: true,
      data: (raw.matches ?? []).map((m) => ({
        name: m.name,
        path: m.abs_path,
        sizeDisplay: m.size_display ?? '',
        mtimeMs: m.mtime_ms ?? null,
      })),
    };
  } catch (err) {
    return fail(err);
  }
}

async function read(path: string): Promise<EngineResult<EnginePreview>> {
  try {
    const raw = await request<RawRead>('GET', `/files/read?path=${encodeURIComponent(path)}`);
    const data: EnginePreview =
      raw.kind === 'text'
        ? {
            kind: 'text',
            name: raw.name,
            path: raw.path,
            sizeDisplay: raw.size_display ?? '',
            truncated: Boolean(raw.truncated),
            content: raw.content ?? '',
          }
        : {
            kind: 'binary',
            name: raw.name,
            path: raw.path,
            sizeDisplay: raw.size_display ?? '',
            truncated: Boolean(raw.truncated),
          };
    return { ok: true, data };
  } catch (err) {
    return fail(err);
  }
}

async function remove(path: string): Promise<EngineResult<{ path: string }>> {
  try {
    const raw = await request<{ ok?: boolean; path?: string }>(
      'DELETE',
      `/files/delete?path=${encodeURIComponent(path)}`,
    );
    return { ok: true, data: { path: raw?.path ?? path } };
  } catch (err) {
    return fail(err);
  }
}

export const engineApi = { list, search, read, remove };
