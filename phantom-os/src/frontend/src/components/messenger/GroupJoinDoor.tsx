import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { FormSheet } from './FormSheet';
import { Button } from './kit/Button';
import { useMessengerStore } from '../../stores/messengerStore';
import { GROUP_LINK_EVENT, groupLinkErrorCode, groupLinksApi, type GroupJoinView } from '../../services/groupLinks';
import { groupLinkError } from './GroupInviteLinkRow';

type DoorWindow = Window & {
  __phantomOpenGroupLink?: (link: string) => boolean;
  __phantomOpenChat?: (id: string) => boolean;
};

const WHY: Record<string, MessageKey> = {
  gone: 'groupLink.why.gone',
  noright: 'groupLink.why.noright',
  full: 'groupLink.why.full',
  no: 'groupLink.why.no',
};

export const JOIN_POLL_MS = 3000;

/** Аркуш вступу за посиланням-запрошенням: превʼю → «Приєднатися» → відповідь телефона, що ділився. */
export const GroupJoinDoor: React.FC = () => {
  const { t } = useTranslation();
  const [link, setLink] = useState<string | null>(null);
  const [view, setView] = useState<GroupJoinView | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<MessageKey | null>(null);
  const linkRef = useRef<string | null>(null);

  const show = useCallback((next: string) => {
    linkRef.current = next;
    setLink(next);
    setView(null);
    setErr(null);
    groupLinksApi
      .open(next)
      .then((v) => linkRef.current === next && setView(v))
      .catch((e) => linkRef.current === next && setErr(groupLinkError(groupLinkErrorCode(e))));
    return true;
  }, []);

  useEffect(() => {
    const w = window as DoorWindow;
    const onEvent = (e: Event) => {
      const detail = (e as CustomEvent<string>).detail;
      if (typeof detail === 'string') show(detail);
    };
    w.__phantomOpenGroupLink = show;
    window.addEventListener(GROUP_LINK_EVENT, onEvent);
    return () => {
      if (w.__phantomOpenGroupLink === show) delete w.__phantomOpenGroupLink;
      window.removeEventListener(GROUP_LINK_EVENT, onEvent);
    };
  }, [show]);

  const stage = view?.stage;
  useEffect(() => {
    if (!link || (stage !== 'asked' && stage !== 'pending')) return undefined;
    const id = window.setInterval(() => {
      groupLinksApi
        .open(link)
        .then((v) => linkRef.current === link && setView(v))
        .catch(() => undefined);
    }, JOIN_POLL_MS);
    return () => window.clearInterval(id);
  }, [link, stage]);

  const close = () => {
    linkRef.current = null;
    setLink(null);
    setView(null);
    setErr(null);
  };

  const join = async () => {
    if (!link) return;
    setBusy(true);
    setErr(null);
    try {
      setView(await groupLinksApi.join(link));
    } catch (e) {
      setErr(groupLinkError(groupLinkErrorCode(e)));
    } finally {
      setBusy(false);
    }
  };

  const openChat = () => {
    const id = view?.group_id;
    if (!id) return;
    close();
    const w = window as DoorWindow;
    if (!w.__phantomOpenChat?.(id)) useMessengerStore.getState().setActiveChat(id);
  };

  const footer =
    stage === 'none' ? (
      <Button variant="primary" className="w-full" testId="group-join-go" disabled={busy} onClick={() => void join()}>
        {t('groupLink.join')}
      </Button>
    ) : stage === 'joined' ? (
      <Button variant="primary" className="w-full" testId="group-join-open" onClick={openChat}>
        {t('groupLink.open')}
      </Button>
    ) : undefined;

  return (
    <FormSheet open={link !== null} onClose={close} title={t('groupLink.joinTitle')} footer={footer} testId="group-join-sheet">
      <div className="px-4 pb-4 space-y-1">
        {view && (
          <>
            <p className="text-headline text-ink" data-testid="group-join-name">
              {view.name}
            </p>
            <p className="text-callout text-ink-2">{t('groupLink.members', { n: view.count })}</p>
            {view.sharer && <p className="text-callout text-ink-2">{t('groupLink.from', { name: view.sharer })}</p>}
            {stage === 'asked' && (
              <p className="pt-3 text-callout text-ink-2" data-testid="group-join-asked">
                {t('groupLink.asked')}
              </p>
            )}
            {stage === 'pending' && (
              <p className="pt-3 text-callout text-ink-2" data-testid="group-join-pending">
                {t('groupLink.pending')}
              </p>
            )}
            {stage === 'joined' && <p className="pt-3 text-callout text-ink">{t('groupLink.joined')}</p>}
            {stage === 'refused' && (
              <p className="pt-3 text-callout text-danger" data-testid="group-join-refused">
                {t(WHY[view.why ?? ''] ?? 'groupLink.failed')}
              </p>
            )}
          </>
        )}
        {err && <p className="pt-3 text-callout text-danger">{t(err)}</p>}
      </div>
    </FormSheet>
  );
};
