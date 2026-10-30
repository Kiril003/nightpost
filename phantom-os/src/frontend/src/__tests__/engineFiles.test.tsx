/**
 * Рушій › Файли (17.10): тека вузла лише з `routes_files`, межі шляху —
 * на сервері, видалення — тільки ROOT і тільки після підтвердження з назвою.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import * as api from '../services/api';
import { ApiError } from '../services/api';
import { engineApi } from '../services/engineApi';
import { EngineFiles } from '../components/engine/files/EngineFiles';
import { DeskEngine } from '../components/engine/DeskEngine';
import { useAuthStore } from '../stores/authStore';

const HOME = '/home/k';
const NOTES = { name: 'notes.md', path: `${HOME}/notes.md`, is_dir: false, size: 2048, size_display: '2.0 KB', mtime_ms: Date.parse('2026-10-16T09:30:00Z') };
const DOCS = { name: 'Docs', path: `${HOME}/Docs`, is_dir: true, size: 0, size_display: '', mtime_ms: null };
const listing = (entries: unknown[], path = HOME) => ({ path, display: '~', parent: null, entries, count: entries.length });

type Handler = (method: string, path: string) => unknown;
let calls: Array<[string, string]> = [];
function serve(handler: Handler) {
  calls = [];
  vi.spyOn(api, 'request').mockImplementation(async (method: string, path: string) => {
    calls.push([method, path]);
    return handler(method, path) as never;
  });
}
const asRole = (role: 'ROOT' | 'OPERATOR' | 'GUEST') =>
  useAuthStore.setState({ user: { id: 'u1', username: 'k', role, preferences: {} } as never });

const rows = () => document.querySelectorAll('[data-engine-row]');

beforeEach(() => asRole('ROOT'));
afterEach(() => vi.restoreAllMocks());

describe('engineApi', () => {
  it('a ../ path goes to the node verbatim and its 403 comes back as refused', async () => {
    serve((_m, path) => {
      if (path.includes('..')) throw new ApiError(403, 'UNKNOWN', 'outside the allowed tree');
      return listing([]);
    });
    const res = await engineApi.list('~/../../etc');
    expect(calls).toEqual([['GET', `/files/list?path=${encodeURIComponent('~/../../etc')}`]]);
    expect(res).toEqual({ ok: false, reason: 'refused', status: 403 });
  });

  it('maps 404 to missing and a dead node to offline', async () => {
    serve(() => {
      throw new ApiError(404, 'UNKNOWN', 'gone');
    });
    expect(await engineApi.list('~/gone')).toEqual({ ok: false, reason: 'missing', status: 404 });
    vi.restoreAllMocks();
    serve(() => {
      throw new TypeError('Failed to fetch');
    });
    expect(await engineApi.list()).toEqual({ ok: false, reason: 'offline', status: 0 });
    expect(calls[0]).toEqual(['GET', '/files/list']);
  });

  it('delete is a DELETE on /files/delete with the encoded path', async () => {
    serve(() => ({ ok: true, path: NOTES.path }));
    expect(await engineApi.remove(NOTES.path)).toEqual({ ok: true, data: { path: NOTES.path } });
    expect(calls).toEqual([['DELETE', `/files/delete?path=${encodeURIComponent(NOTES.path)}`]]);
  });
});

describe('Рушій › Файли', () => {
  it('an empty folder shows the honest empty state, no rows', async () => {
    serve(() => listing([]));
    render(<EngineFiles />);
    expect(await screen.findByTestId('engine-files-empty')).toBeTruthy();
    expect(rows()).toHaveLength(0);
  });

  it('a refused folder shows the refused state, no rows', async () => {
    serve(() => {
      throw new ApiError(403, 'UNKNOWN', 'outside');
    });
    render(<EngineFiles />);
    expect(await screen.findByTestId('engine-files-refused')).toBeTruthy();
    expect(rows()).toHaveLength(0);
  });

  it('rows come from the API only: server size text, no size for folders, no date without mtime', async () => {
    serve(() => listing([DOCS, NOTES]));
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(2));
    const [docs, notes] = Array.from(rows()) as HTMLElement[];
    expect(within(docs).getByText('Docs')).toBeTruthy();
    expect(docs.querySelector('[data-engine-size]')).toBeNull();
    expect(docs.querySelector('[data-engine-date]')).toBeNull();
    expect(within(notes).getByText('notes.md')).toBeTruthy();
    expect(notes.querySelector('[data-engine-size]')?.textContent).toBe('2.0 KB');
    expect(notes.querySelector('[data-engine-date]')).not.toBeNull();
  });

  it('opening a folder lists it by the path the server gave', async () => {
    serve((_m, path) => (path.includes(encodeURIComponent(DOCS.path)) ? listing([], DOCS.path) : listing([DOCS])));
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(1));
    fireEvent.click(screen.getByText('Docs'));
    await screen.findByTestId('engine-files-empty');
    expect(calls.at(-1)).toEqual(['GET', `/files/list?path=${encodeURIComponent(DOCS.path)}`]);
  });

  it('a text file previews as text; a binary file never prints its bytes', async () => {
    serve((_m, path) => {
      if (path.startsWith('/files/read')) {
        return path.includes('notes')
          ? { kind: 'text', path: NOTES.path, name: 'notes.md', size: 12, size_display: '12 B', truncated: false, mtime_ms: null, content: 'hello engine' }
          : { kind: 'binary', path: `${HOME}/a.bin`, name: 'a.bin', size: 4, size_display: '4 B', truncated: false, mtime_ms: null, content_base64: 'AAECAw==' };
      }
      return listing([NOTES, { ...NOTES, name: 'a.bin', path: `${HOME}/a.bin` }]);
    });
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(2));
    fireEvent.click(screen.getByText('notes.md'));
    expect((await screen.findByTestId('engine-preview-text')).textContent).toContain('hello engine');
    fireEvent.click(screen.getByText('a.bin'));
    await screen.findByTestId('engine-preview-none');
    expect(document.body.textContent).not.toContain('AAECAw==');
  });

  it('a slow answer for an earlier pick never replaces the later pick', async () => {
    let releaseSlow: () => void = () => undefined;
    const slow = new Promise<void>((r) => (releaseSlow = r));
    const text = (name: string) => ({ kind: 'text', path: `${HOME}/${name}`, name, size: 1, size_display: '1 B', truncated: false, mtime_ms: null, content: `body of ${name}` });
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path.startsWith('/files/read') && path.includes('slow')) {
        await slow;
        return text('slow.md') as never;
      }
      if (path.startsWith('/files/read')) return text('fast.md') as never;
      return listing([{ ...NOTES, name: 'slow.md', path: `${HOME}/slow.md` }, { ...NOTES, name: 'fast.md', path: `${HOME}/fast.md` }]) as never;
    });
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(2));
    fireEvent.click(screen.getByText('slow.md'));
    fireEvent.click(screen.getByText('fast.md'));
    expect((await screen.findByTestId('engine-preview-text')).textContent).toBe('body of fast.md');
    releaseSlow();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.getByTestId('engine-preview-text').textContent).toBe('body of fast.md');
  });

  it('search asks the node and lists its matches', async () => {
    serve((_m, path) =>
      path.startsWith('/files/search')
        ? { root_display: '~', total_matches: 1, matches: [{ match_id: 'm', name: 'notes.md', abs_path: NOTES.path, size_display: '2.0 KB', size_bytes: 2048, mtime_ms: NOTES.mtime_ms, date_display: '', icon: 'doc', tone: 'neutral', highlight: true }] }
        : listing([DOCS]),
    );
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(1));
    const box = screen.getByRole('searchbox');
    fireEvent.change(box, { target: { value: 'notes' } });
    fireEvent.submit(box.closest('form')!);
    await waitFor(() => expect(screen.getByText('notes.md')).toBeTruthy());
    expect(calls.some(([m, p]) => m === 'GET' && p.startsWith('/files/search?query=notes'))).toBe(true);
    expect(rows()).toHaveLength(1);
  });

  it('no delete for a non-ROOT user', async () => {
    asRole('OPERATOR');
    serve((_m, path) => (path.startsWith('/files/read') ? { kind: 'text', path: NOTES.path, name: 'notes.md', size: 1, size_display: '1 B', truncated: false, mtime_ms: null, content: 'x' } : listing([NOTES])));
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(1));
    fireEvent.click(screen.getByText('notes.md'));
    await screen.findByTestId('engine-preview-text');
    expect(screen.queryByTestId('engine-delete')).toBeNull();
  });

  it('ROOT delete asks once with the file name, sends nothing before yes, and promises no undo', async () => {
    let gone = false;
    serve((method, path) => {
      if (method === 'DELETE') {
        gone = true;
        return { ok: true, path: NOTES.path };
      }
      if (path.startsWith('/files/read')) return { kind: 'text', path: NOTES.path, name: 'notes.md', size: 1, size_display: '1 B', truncated: false, mtime_ms: null, content: 'x' };
      return listing(gone ? [] : [NOTES]);
    });
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(1));
    fireEvent.click(screen.getByText('notes.md'));
    fireEvent.click(await screen.findByTestId('engine-delete'));
    const ask = await screen.findByRole('alertdialog');
    expect(ask.textContent).toContain('notes.md');
    expect(calls.some(([m]) => m === 'DELETE')).toBe(false);
    fireEvent.click(within(ask).getByTestId('engine-delete-confirm'));
    await waitFor(() => expect(calls).toContainEqual(['DELETE', `/files/delete?path=${encodeURIComponent(NOTES.path)}`]));
    await waitFor(() => expect(rows()).toHaveLength(0));
    expect(document.body.textContent).not.toMatch(/undo|відновити|повернути|скасувати видалення/i);
  });

  it('cancel in the confirm sends nothing', async () => {
    serve((_m, path) => (path.startsWith('/files/read') ? { kind: 'text', path: NOTES.path, name: 'notes.md', size: 1, size_display: '1 B', truncated: false, mtime_ms: null, content: 'x' } : listing([NOTES])));
    render(<EngineFiles />);
    await waitFor(() => expect(rows()).toHaveLength(1));
    fireEvent.click(screen.getByText('notes.md'));
    fireEvent.click(await screen.findByTestId('engine-delete'));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByTestId('engine-delete-cancel'));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(calls.some(([m]) => m === 'DELETE')).toBe(false);
  });
});

describe('Рушій', () => {
  it('/hq keeps the HQ as tab one and adds Files as tab two', async () => {
    serve((_m, path) => (path.startsWith('/files/list') ? listing([NOTES]) : path === '/agent/waiting' ? { questions: [] } : path.startsWith('/agent/forge') ? { runs: [] } : {}));
    render(
      <MemoryRouter initialEntries={['/hq']}>
        <Routes>
          <Route path="/hq" element={<DeskEngine />} />
        </Routes>
      </MemoryRouter>,
    );
    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['Штаб', 'Файли']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(tabs[1]);
    await waitFor(() => expect(rows()).toHaveLength(1));
    expect(screen.getAllByRole('tab')[1]).toHaveAttribute('aria-selected', 'true');
  });
});
