import { useEffect, useRef, useState } from 'react';
import { ArrowUp, FileText, Folder, Search, Trash2 } from 'lucide-react';
import {
  engineApi,
  type EngineEntry,
  type EngineFailReason,
  type EngineListing,
  type EnginePreview,
} from '../../../services/engineApi';
import { useAuthStore } from '../../../stores/authStore';
import { currentLocale, tNow } from '../../chat/tNow';

export function EngineFiles() {
  const isRoot = useAuthStore((s) => s.user?.role === 'ROOT');
  const [currentPath, setCurrentPath] = useState<string | undefined>(undefined);
  const [listing, setListing] = useState<EngineListing | null>(null);
  const [listError, setListError] = useState<EngineFailReason | null>(null);
  const [listLoading, setListLoading] = useState(true);

  const [query, setQuery] = useState('');
  const [mode, setMode] = useState<'list' | 'search'>('list');
  const [searchEntries, setSearchEntries] = useState<EngineEntry[]>([]);
  const [searchError, setSearchError] = useState<EngineFailReason | null>(null);
  const [searchLoading, setSearchLoading] = useState(false);

  const [preview, setPreview] = useState<EnginePreview | null>(null);
  const [previewError, setPreviewError] = useState<EngineFailReason | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteFailed, setDeleteFailed] = useState(false);
  const cancelBtnRef = useRef<HTMLButtonElement | null>(null);
  const listSeq = useRef(0);
  const previewSeq = useRef(0);

  const loadFolder = async (path?: string) => {
    setCurrentPath(path);
    setMode('list');
    setListLoading(true);
    setListError(null);
    const seq = ++listSeq.current;
    const res = await engineApi.list(path);
    if (seq !== listSeq.current) return;
    setListLoading(false);
    if (res.ok) {
      setListing(res.data);
    } else {
      setListing(null);
      setListError(res.reason);
    }
  };

  useEffect(() => {
    void loadFolder(undefined);
  }, []);

  useEffect(() => {
    if (confirmingDelete) {
      cancelBtnRef.current?.focus();
    }
  }, [confirmingDelete]);

  const runSearch = async (rawQuery: string) => {
    const q = rawQuery.trim();
    if (!q) {
      setMode('list');
      setSearchEntries([]);
      setSearchError(null);
      return;
    }
    setMode('search');
    setSearchLoading(true);
    setSearchError(null);
    const seq = ++listSeq.current;
    const res = await engineApi.search(q, listing?.path);
    if (seq !== listSeq.current) return;
    setSearchLoading(false);
    if (res.ok) {
      setSearchEntries(
        res.data.map((m) => ({
          name: m.name,
          path: m.path,
          isDir: false,
          size: 0,
          sizeDisplay: m.sizeDisplay,
          mtimeMs: m.mtimeMs,
        })),
      );
    } else {
      setSearchEntries([]);
      setSearchError(res.reason);
    }
  };

  const openFile = async (path: string) => {
    setConfirmingDelete(false);
    setDeleteFailed(false);
    setPreviewError(null);
    const seq = ++previewSeq.current;
    const res = await engineApi.read(path);
    if (seq !== previewSeq.current) return;
    if (res.ok) {
      setPreview(res.data);
    } else {
      setPreview(null);
      setPreviewError(res.reason);
    }
  };

  const confirmDelete = async () => {
    if (!preview) return;
    setDeleting(true);
    setDeleteFailed(false);
    const res = await engineApi.remove(preview.path);
    setDeleting(false);
    if (res.ok) {
      setConfirmingDelete(false);
      setPreview(null);
      setQuery('');
      await loadFolder(currentPath);
    } else {
      setDeleteFailed(true);
    }
  };

  const activeError = mode === 'search' ? searchError : listError;
  const activeLoading = mode === 'search' ? searchLoading : listLoading;
  const activeEntries = mode === 'search' ? searchEntries : (listing?.entries ?? []);

  return (
    <div className="h-full min-h-0 px-4 pb-2 flex gap-4 overflow-hidden">
      <div className="w-full max-w-[360px] min-w-0 h-full rounded-f-sheet bg-f-surface flex flex-col overflow-hidden">
        <div className="shrink-0 min-h-12 px-3 py-2 flex items-center gap-2">
          <span className="flex-1 min-w-0 truncate text-f-meta text-f-ink-2">
            {listing?.display ?? '~'}
          </span>
          {listing?.parent !== null && listing?.parent !== undefined && (
            <button
              type="button"
              onClick={() => void loadFolder(listing.parent!)}
              className="shrink-0 min-h-11 px-3 rounded-f-m bg-f-raise text-f-body text-f-ink flex items-center gap-1.5"
            >
              <ArrowUp className="w-4 h-4 text-f-ink-2" strokeWidth={1.75} aria-hidden />
              <span>{tNow('engineFiles.up')}</span>
            </button>
          )}
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            void runSearch(query);
          }}
          className="shrink-0 px-3 pb-2"
        >
          <div className="h-11 px-3 rounded-f-m bg-f-raise flex items-center gap-2">
            <Search className="w-4 h-4 shrink-0 text-f-ink-3" strokeWidth={1.75} aria-hidden />
            <input
              type="search"
              value={query}
              onChange={(e) => {
                const v = e.target.value;
                setQuery(v);
                if (!v.trim() && mode === 'search') {
                  setMode('list');
                  setSearchEntries([]);
                  setSearchError(null);
                }
              }}
              aria-label={tNow('engineFiles.search')}
              placeholder={tNow('engineFiles.search')}
              className="flex-1 min-w-0 bg-transparent text-f-body text-f-ink placeholder:text-f-ink-3 focus:outline-none"
            />
          </div>
        </form>

        <div className="flex-1 min-h-0 overflow-y-auto px-2 pb-2">
          {activeError ? (
            <p
              data-testid={`engine-files-${activeError}`}
              className="px-2 py-6 text-center text-f-body text-f-ink-3"
            >
              {tNow(`engineFiles.${activeError}`)}
            </p>
          ) : activeLoading ? null : activeEntries.length === 0 ? (
            <p
              data-testid="engine-files-empty"
              className="px-2 py-6 text-center text-f-body text-f-ink-3"
            >
              {tNow('engineFiles.empty')}
            </p>
          ) : (
            <div className="flex flex-col">
              {activeEntries.map((entry) => {
                const selected = preview?.path === entry.path;
                return (
                  <button
                    key={entry.path}
                    type="button"
                    data-engine-row
                    onClick={() => {
                      if (entry.isDir) {
                        void loadFolder(entry.path);
                      } else {
                        void openFile(entry.path);
                      }
                    }}
                    className={`w-full min-h-11 px-3 py-2 rounded-f-m flex items-center gap-2.5 text-left ${
                      selected ? 'bg-f-raise text-f-ink' : 'text-f-ink hover:bg-f-raise'
                    }`}
                  >
                    {entry.isDir ? (
                      <Folder className="w-4 h-4 shrink-0 text-f-ink-2" strokeWidth={1.75} aria-hidden />
                    ) : (
                      <FileText className="w-4 h-4 shrink-0 text-f-ink-3" strokeWidth={1.75} aria-hidden />
                    )}
                    <span className="flex-1 min-w-0 truncate text-f-body text-f-ink">
                      {entry.name}
                    </span>
                    {!entry.isDir && entry.sizeDisplay && (
                      <span data-engine-size className="shrink-0 text-f-meta text-f-ink-3 tabular-nums">
                        {entry.sizeDisplay}
                      </span>
                    )}
                    {entry.mtimeMs !== null && (
                      <span data-engine-date className="shrink-0 text-f-meta text-f-ink-3 tabular-nums">
                        {new Date(entry.mtimeMs).toLocaleDateString(currentLocale(), {
                          day: 'numeric',
                          month: 'short',
                        })}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <div className="flex-1 min-w-0 h-full rounded-f-sheet bg-f-surface flex flex-col overflow-hidden">
        {previewError ? (
          <div className="flex-1 min-h-0 flex items-center justify-center p-6">
            <p className="text-f-body text-f-ink-3">{tNow(`engineFiles.${previewError}`)}</p>
          </div>
        ) : !preview ? (
          <div className="flex-1 min-h-0 flex items-center justify-center p-6">
            <p className="text-f-body text-f-ink-3">{tNow('engineFiles.pick')}</p>
          </div>
        ) : (
          <>
            <div className="shrink-0 min-h-14 px-4 py-2 border-b border-f-line flex items-center gap-3">
              <span className="flex-1 min-w-0 truncate text-f-title text-f-ink">{preview.name}</span>
              {preview.sizeDisplay && (
                <span className="shrink-0 text-f-meta text-f-ink-3 tabular-nums">
                  {preview.sizeDisplay}
                </span>
              )}
              {preview.truncated && (
                <span className="shrink-0 text-f-meta text-f-ink-2">
                  {tNow('engineFiles.truncated')}
                </span>
              )}
              {isRoot && (
                <button
                  type="button"
                  data-testid="engine-delete"
                  onClick={() => {
                    setDeleteFailed(false);
                    setConfirmingDelete(true);
                  }}
                  className="shrink-0 min-h-11 px-3 rounded-f-m bg-f-raise text-f-body text-f-danger flex items-center gap-1.5"
                >
                  <Trash2 className="w-4 h-4" strokeWidth={1.75} aria-hidden />
                  <span>{tNow('engineFiles.delete')}</span>
                </button>
              )}
            </div>

            {isRoot && confirmingDelete && (
              <div
                role="alertdialog"
                aria-modal="true"
                aria-label={tNow('engineFiles.deleteTitle', { name: preview.name })}
                onKeyDown={(e) => {
                  if (e.key === 'Escape') setConfirmingDelete(false);
                }}
                className="shrink-0 px-4 py-3 border-b border-f-line bg-f-raise flex items-center justify-between gap-3"
              >
                <p className="min-w-0 truncate text-f-body text-f-ink">
                  {tNow('engineFiles.deleteTitle', { name: preview.name })}
                </p>
                <div className="shrink-0 flex items-center gap-2">
                  <button
                    ref={cancelBtnRef}
                    type="button"
                    data-testid="engine-delete-cancel"
                    onClick={() => setConfirmingDelete(false)}
                    className="min-h-11 px-3 rounded-f-m bg-f-surface text-f-body text-f-ink"
                  >
                    {tNow('engineFiles.cancel')}
                  </button>
                  <button
                    type="button"
                    data-testid="engine-delete-confirm"
                    disabled={deleting}
                    onClick={() => void confirmDelete()}
                    className="min-h-11 px-3 rounded-f-m bg-f-danger text-f-on-accent text-f-body disabled:opacity-50"
                  >
                    {tNow('engineFiles.delete')}
                  </button>
                </div>
              </div>
            )}

            {deleteFailed && (
              <p role="status" className="shrink-0 px-4 py-2 text-f-body text-f-danger">
                {tNow('engineFiles.deleteFailed')}
              </p>
            )}

            {preview.kind === 'text' ? (
              <pre
                data-testid="engine-preview-text"
                className="flex-1 min-h-0 overflow-y-auto p-4 font-mono text-f-read text-f-ink whitespace-pre-wrap break-words"
              >
                {preview.content}
              </pre>
            ) : (
              <div className="flex-1 min-h-0 flex items-center justify-center p-6">
                <p data-testid="engine-preview-none" className="text-f-body text-f-ink-3">
                  {tNow('engineFiles.noPreview')}
                </p>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
