import React, { useCallback, useEffect, useState } from 'react';
import { ChevronDown, UserRound } from 'lucide-react';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import type { Chat, ChatMember } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { groupLinkErrorCode, groupRequestsApi, type GroupJoinRequest, type GroupRequestsState } from '../../services/groupLinks';
import { Button } from './kit/Button';
import { Avatar } from './Avatar';
import { groupLinkError } from './GroupInviteLinkRow';
import { mutualGroups, peerFace } from './groupPeople';
import { soundFx } from '../../utils/messengerSound';

export const REQUESTS_POLL_MS = 5000;

const decideError = (code: string): MessageKey => {
  if (code === 'full') return 'groupLink.full';
  if (code === 'no_request') return 'groupLink.noRequest';
  return groupLinkError(code);
};

export const askedWord = (at: number, t: Translation['t'], now = Date.now()): string => {
  const ms = at > 1e12 ? at : at * 1000;
  if (!(ms > 1.5e12)) return '';
  const min = Math.max(0, Math.floor((now - ms) / 60000));
  if (min < 1) return t('groupLink.askedNow');
  if (min < 60) return t('groupLink.askedMin', { n: min });
  if (min < 1440) return t('groupLink.askedHour', { n: Math.floor(min / 60) });
  return t('groupLink.askedDay', { n: Math.floor(min / 1440) });
};

/** Заявки до групи: опитування, поки на них дивиться той, хто має право запрошувати. */
export const useGroupRequests = (chat: Chat) => {
  const may = chat.powers?.invite === true;
  const [state, setState] = useState<GroupRequestsState | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<MessageKey | null>(null);

  const load = useCallback(
    () =>
      groupRequestsApi
        .list(chat.id)
        .then((s) => setState(s))
        .catch(() => undefined),
    [chat.id],
  );

  useEffect(() => {
    if (!may) return undefined;
    void load();
    const id = window.setInterval(() => void load(), REQUESTS_POLL_MS);
    return () => window.clearInterval(id);
  }, [may, load]);

  const decide = async (peers: string[], accept: boolean) => {
    setBusy(true);
    setNote(null);
    try {
      for (const peer of peers) setState(await groupRequestsApi.decide(chat.id, peer, accept));
    } catch (e) {
      setNote(decideError(groupLinkErrorCode(e)));
      void load();
    } finally {
      setBusy(false);
    }
  };

  const toggleAsk = async () => {
    setBusy(true);
    setNote(null);
    try {
      await useMessengerStore.getState().setGroupSettings(chat.id, { ask: chat.groupSettings?.ask ? 'off' : 'on' });
    } catch {
      setNote('groupLink.failed');
    } finally {
      setBusy(false);
    }
  };

  return { may, list: state?.requests ?? [], busy, note, decide, toggleAsk };
};

type Requests = ReturnType<typeof useGroupRequests>;

const RequestActions: React.FC<{ r: GroupJoinRequest; q: Requests }> = ({ r, q }) => {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-2 pt-1">
      <Button testId={`group-request-accept-${r.peer}`} disabled={q.busy} onClick={() => void q.decide([r.peer], true)}>
        {t('groupLink.accept')}
      </Button>
      <Button variant="text" className="!text-ink-2 px-2" testId={`group-request-decline-${r.peer}`} disabled={q.busy} onClick={() => void q.decide([r.peer], false)}>
        {t('groupLink.decline')}
      </Button>
    </div>
  );
};

/**
 * Заявки людьми: фото, імʼя, @нік або «У ваших людях», спільні групи, коли просився.
 * Заявки без жодного імені — не рядки з адресою, а одна згорнута група в кінці.
 */
export const GroupJoinRequestsList: React.FC<{ chat: Chat; q: Requests; onOpenPerson?: (m: ChatMember) => void }> = ({ chat, q, onOpenPerson }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const [openBare, setOpenBare] = useState(false);
  const rows = q.list.map((r) => ({ r, face: peerFace(chats, r.peer, r.name) }));
  const named = rows.filter((x) => x.face.name);
  const bare = rows.filter((x) => !x.face.name);

  return (
    <div className="px-4 pb-4" data-testid="group-join-requests">
      <p className="sr-only" data-testid="group-requests-title">
        {t('groupLink.requests', { n: q.list.length })}
      </p>
      {q.list.length === 0 && <p className="py-6 text-center text-callout text-ink-3">{t('groupLink.requestsNone')}</p>}
      <ul>
        {named.map(({ r, face }) => {
          const mutual = mutualGroups(chats, r.peer, chat.id);
          const who = face.handle ? `@${face.handle}` : face.known ? t('groupLink.inPeople') : '';
          const line = [who, askedWord(r.at, t)].filter(Boolean).join(' · ');
          return (
            <li key={r.peer} className="flex items-start gap-3 py-2.5 border-b border-line last:border-b-0" data-testid={`group-request-${r.peer}`}>
              <button
                type="button"
                data-testid={`group-request-open-${r.peer}`}
                onClick={() => {
                  soundFx.playTap();
                  onOpenPerson?.({ id: r.peer, name: face.name, handle: face.handle, avatar: face.avatar, role: 'member' });
                }}
                className="shrink-0 rounded-full"
              >
                <Avatar src={face.avatar} name={face.name} tone={r.peer} radius="rounded-full" className="w-12 h-12 text-headline" />
              </button>
              <div className="flex-1 min-w-0">
                <p className="truncate text-headline font-semibold text-ink">{face.name}</p>
                <p className="truncate text-footnote text-ink-2">{line}</p>
                {mutual.length > 0 && (
                  <p className="truncate text-footnote text-ink-3" data-testid={`group-request-mutual-${r.peer}`}>
                    {t('groupLink.mutual', { names: mutual.slice(0, 2).join(', ') + (mutual.length > 2 ? ` +${mutual.length - 2}` : '') })}
                  </p>
                )}
                <RequestActions r={r} q={q} />
              </div>
            </li>
          );
        })}
      </ul>
      {bare.length > 0 && (
        <div className="mt-3 rounded-card bg-raised" data-testid="group-requests-bare">
          <div className="flex items-center gap-2 pl-4 pr-2 min-h-14">
            <button
              type="button"
              aria-expanded={openBare}
              data-testid="group-requests-bare-toggle"
              onClick={() => setOpenBare((v) => !v)}
              className="flex-1 min-w-0 min-h-[44px] flex items-center gap-2 text-left"
            >
              <span className="truncate text-headline text-ink">{t('groupLink.noProfile', { n: bare.length })}</span>
              <ChevronDown className={`w-4 h-4 shrink-0 text-ink-3 transition-transform ${openBare ? 'rotate-180' : ''}`} strokeWidth={1.75} aria-hidden />
            </button>
            <Button variant="text" className="!text-danger px-2" testId="group-requests-decline-all" disabled={q.busy} onClick={() => void q.decide(bare.map((x) => x.r.peer), false)}>
              {t('groupLink.declineAll')}
            </Button>
          </div>
          {openBare && (
            <ul className="px-4 pb-2">
              {bare.map(({ r }) => (
                <li key={r.peer} className="flex items-start gap-3 py-2 border-t border-line" data-testid={`group-request-${r.peer}`}>
                  <span className="w-10 h-10 shrink-0 rounded-full bg-overlay text-ink-3 flex items-center justify-center" aria-hidden>
                    <UserRound className="w-5 h-5" strokeWidth={1.75} />
                  </span>
                  <div className="flex-1 min-w-0">
                    {askedWord(r.at, t) && <p className="truncate text-footnote text-ink-2">{askedWord(r.at, t)}</p>}
                    <RequestActions r={r} q={q} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {q.note && <p className="pt-2 text-footnote text-danger">{t(q.note)}</p>}
    </div>
  );
};
