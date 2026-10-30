import React, { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { messengerApi } from '../../services/messengerApi';
import {
  checkNewHandle,
  freeWord,
  handleRule,
  suggestHandles,
  takenWord,
} from '../../services/peopleIdentity';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';

export const HANDLE_CHANGED = 'phantom:handle-changed';

type Mine = { kind: 'loading' } | { kind: 'none' } | { kind: 'set'; handle: string } | { kind: 'away' };
type LiveState = { kind: 'idle' } | { kind: 'free'; handle: string } | { kind: 'taken'; handle: string };

export const useMyHandle = (): Mine => {
  const [mine, setMine] = useState<Mine>({ kind: 'loading' });
  useEffect(() => {
    let alive = true;
    const read = () =>
      messengerApi
        .directoryMe()
        .then((me) => alive && setMine(me.published && me.handle ? { kind: 'set', handle: me.handle } : { kind: 'none' }))
        .catch(() => alive && setMine({ kind: 'away' }));
    void read();
    window.addEventListener(HANDLE_CHANGED, read);
    return () => {
      alive = false;
      window.removeEventListener(HANDLE_CHANGED, read);
    };
  }, []);
  return mine;
};

const codeOf = (err: unknown): string => {
  const c = (err as { code?: unknown } | null)?.code;
  return typeof c === 'string' ? c : '';
};

const isHttp404 = (err: unknown): boolean =>
  Boolean(err && typeof err === 'object' && (err as { status?: unknown }).status === 404);

const isFreeAnswer = (
  res: { available?: boolean; taken?: boolean; mine?: boolean } | null | undefined,
): boolean => {
  if (!res || typeof res !== 'object') return false;
  if (typeof res.available === 'boolean') return res.available;
  if (typeof res.taken === 'boolean') return !res.taken;
  return false;
};

/** Задати, змінити чи прибрати свій @нікнейм — глобальну адресу людини. */
export const HandleSheet: React.FC<{ onDone: () => void }> = ({ onDone }) => {
  const { t } = useTranslation();
  const inputId = useId();
  const mine = useMyHandle();
  const myHandle = mine.kind === 'set' ? mine.handle : null;
  const myName = useMessengerStore((s) => s.currentUser.name);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [live, setLive] = useState<LiveState>({ kind: 'idle' });
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const lookup404Ref = useRef(false);
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const reqTokenRef = useRef(0);

  const isStillCurrent = (handle: string, token: number): boolean => {
    if (reqTokenRef.current !== token) return false;
    const cur = checkNewHandle(draftRef.current);
    return cur.ok && cur.handle === handle;
  };

  useEffect(() => {
    if (myHandle) {
      draftRef.current = myHandle;
      setDraft(myHandle);
    }
  }, [myHandle]);

  const resolveFreeSuggestions = async (
    takenHandle: string,
    personName: string,
    allow404Fallback: boolean,
  ): Promise<string[]> => {
    const candidates = suggestHandles(takenHandle, personName).slice(0, 6);
    const results = await Promise.all(
      candidates.map(async (c) => {
        try {
          const res = await messengerApi.directoryLookup(c);
          lookup404Ref.current = false;
          return isFreeAnswer(res) ? c : null;
        } catch (err) {
          if (isHttp404(err)) {
            lookup404Ref.current = true;
            return allow404Fallback ? c : null;
          }
          lookup404Ref.current = false;
          return null;
        }
      }),
    );
    return results.filter((c): c is string => c !== null).slice(0, 3);
  };

  useEffect(() => {
    const check = checkNewHandle(draft);
    if (!check.ok || (myHandle !== null && myHandle === check.handle)) {
      return undefined;
    }
    const token = ++reqTokenRef.current;
    const timer = setTimeout(() => {
      void (async () => {
        try {
          const res = await messengerApi.directoryLookup(check.handle);
          if (!isStillCurrent(check.handle, token)) return;
          lookup404Ref.current = false;
          if (res?.mine) {
            setLive({ kind: 'idle' });
            setSuggestions([]);
          } else if (isFreeAnswer(res)) {
            setLive({ kind: 'free', handle: check.handle });
            setSuggestions([]);
          } else {
            setLive({ kind: 'taken', handle: check.handle });
            const free = await resolveFreeSuggestions(check.handle, myName, false);
            if (isStillCurrent(check.handle, token)) {
              setSuggestions(free);
            }
          }
        } catch (err) {
          if (!isStillCurrent(check.handle, token)) return;
          lookup404Ref.current = isHttp404(err);
          setLive({ kind: 'idle' });
          setSuggestions([]);
        }
      })();
    }, 400);
    return () => {
      clearTimeout(timer);
    };
  }, [draft, myHandle, myName]);

  const changed = () => window.dispatchEvent(new Event(HANDLE_CHANGED));

  const save = async () => {
    const check = checkNewHandle(draft);
    if (!check.ok) {
      setNote(check.why);
      setSuggestions([]);
      return;
    }
    if (mine.kind === 'set' && mine.handle === check.handle) {
      onDone();
      return;
    }
    const token = ++reqTokenRef.current;
    setBusy(true);
    setNote(null);
    try {
      await messengerApi.directoryPublish(check.handle);
      setBusy(false);
      soundFx.playSend();
      changed();
      onDone();
    } catch (err) {
      setBusy(false);
      if (!isStillCurrent(check.handle, token)) return;
      const code = codeOf(err);
      if (code === 'taken') {
        setLive({ kind: 'taken', handle: check.handle });
        setNote(takenWord(check.handle));
        void resolveFreeSuggestions(check.handle, myName, true).then((free) => {
          if (isStillCurrent(check.handle, token)) {
            setSuggestions(free);
          }
        });
      } else {
        setNote(code === 'bad_handle' ? handleRule() : t('handleSheet.saveOffline'));
      }
    }
  };

  const remove = async () => {
    setBusy(true);
    setNote(null);
    try {
      await messengerApi.directoryWithdraw();
      changed();
      onDone();
    } catch {
      setNote(t('handleSheet.removeOffline'));
    } finally {
      setBusy(false);
    }
  };

  const pickSuggestion = (s: string) => {
    draftRef.current = s;
    reqTokenRef.current += 1;
    setDraft(s);
    setNote(null);
    setSuggestions([]);
    setLive(lookup404Ref.current ? { kind: 'idle' } : { kind: 'free', handle: s });
  };

  if (mine.kind === 'away') {
    return <p className="px-4 pb-6 text-body text-ink-2">{t('handleSheet.phoneOnly')}</p>;
  }

  const statusLine =
    note ??
    (live.kind === 'free'
      ? freeWord(live.handle)
      : live.kind === 'taken'
        ? takenWord(live.handle)
        : checkNewHandle(draft).ok
          ? t('handleSheet.findable')
          : handleRule());

  const tooShort = draft.trim() !== '' && !checkNewHandle(draft).ok;

  const statusTone =
    !note && live.kind === 'free'
      ? 'text-success'
      : note || live.kind === 'taken' || tooShort
        ? 'text-danger'
        : 'text-ink-3';

  return (
    <div className="px-4 pb-6" data-testid="handle-sheet">
      <p className="text-body text-ink-2">{t('handleSheet.intro')}</p>
      <label htmlFor={inputId} className="mt-4 block text-body font-medium text-ink">
        {t('handleSheet.inputAria')}
      </label>
      <div className="mt-2 h-12 px-4 rounded-full bg-raised flex items-center gap-1 focus-within:outline focus-within:outline-2 focus-within:outline-accent-line">
        <span className="text-body text-ink-3" aria-hidden>
          @
        </span>
        <input
          id={inputId}
          type="text"
          value={draft}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          onChange={(e) => {
            const next = e.target.value.replace(/^@/, '');
            draftRef.current = next;
            reqTokenRef.current += 1;
            setDraft(next);
            if (note) setNote(null);
            setLive({ kind: 'idle' });
            setSuggestions([]);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !busy && mine.kind !== 'loading') void save();
          }}
          aria-label={t('handleSheet.inputAria')}
          placeholder={t('handleSheet.placeholder')}
          className="flex-1 min-w-0 h-12 bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline-none"
        />
      </div>
      <p className={`mt-2 min-h-[22px] text-body ${statusTone}`} role="status" data-testid="handle-note">
        {statusLine}
      </p>
      {suggestions.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-2" data-testid="handle-suggestions">
          {suggestions.map((s) => (
            <button
              key={s}
              type="button"
              data-testid="handle-suggestion"
              onClick={() => pickSuggestion(s)}
              className="h-12 px-4 rounded-full border border-line-strong bg-raised text-body text-ink active:bg-overlay"
            >
              @{s}
            </button>
          ))}
        </div>
      )}
      <button
        type="button"
        onClick={() => void save()}
        disabled={busy || !draft.trim() || mine.kind === 'loading'}
        className="mt-3 w-full h-12 rounded-full bg-accent text-on-accent text-headline font-bold disabled:opacity-50"
      >
        {busy ? t('handleSheet.saving') : t('handleSheet.save')}
      </button>
      {mine.kind === 'set' && (
        <button
          type="button"
          onClick={() => void remove()}
          disabled={busy}
          className="mt-2 w-full h-12 rounded-full text-headline text-ink-2 active:bg-raised disabled:opacity-50"
        >
          {t('handleSheet.remove')}
        </button>
      )}
    </div>
  );
};
