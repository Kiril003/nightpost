import React, { useEffect, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import type { Chat } from '../../types/messenger';
import { groupLinkErrorCode, groupLinksApi, type GroupLinkState } from '../../services/groupLinks';
import { copyText } from '../../utils/messengerInvite';
import { shareChatText } from '../../services/chatExport';
import { Button } from './kit/Button';
import { Link2 } from 'lucide-react';

export const groupLinkError = (code: string): MessageKey => {
  if (code.startsWith('no_right')) return 'groupLink.noRight';
  if (code === 'not_ready') return 'groupLink.notReady';
  if (code === 'unsent') return 'groupLink.unsent';
  if (code === 'bad_link') return 'groupLink.badLink';
  if (code === 'own_link') return 'groupLink.ownLink';
  return 'groupLink.failed';
};

/** Посилання-запрошення: лише для того, хто має право запрошувати. */
export const GroupInviteLinkRow: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const may = chat.powers?.invite === true;
  const [state, setState] = useState<GroupLinkState | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<MessageKey | null>(null);

  useEffect(() => {
    if (!may) return undefined;
    let live = true;
    groupLinksApi
      .get(chat.id)
      .then((s) => live && setState(s))
      .catch((e) => live && setNote(groupLinkError(groupLinkErrorCode(e))));
    return () => {
      live = false;
    };
  }, [may, chat.id]);

  if (!may) return null;

  const run = async (call: () => Promise<GroupLinkState>) => {
    setBusy(true);
    setNote(null);
    try {
      setState(await call());
    } catch (e) {
      setNote(groupLinkError(groupLinkErrorCode(e)));
    } finally {
      setBusy(false);
    }
  };

  const link = state?.link ?? '';
  const off = busy || state === null || !state.may;

  return (
    <div data-testid="group-invite-link">
      <p className="pb-2 text-footnote font-semibold text-ink-2">{t('groupLink.title')}</p>
      {link ? (
        <>
          <div className="h-12 px-4 rounded-control bg-raised flex items-center gap-3">
            <Link2 className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
            <span className="flex-1 min-w-0 truncate text-callout text-ink select-all" data-testid="group-invite-link-url">
              {link}
            </span>
          </div>
          <div className="pt-3 grid grid-cols-2 gap-2" data-testid="group-invite-link-main">
            <Button
              variant="primary"
              testId="group-invite-link-copy"
              className="h-11"
              disabled={busy}
              onClick={() => void copyText(link).then((ok) => setNote(ok ? 'groupLink.copied' : 'groupLink.failed'))}
            >
              {t('groupLink.copy')}
            </Button>
            <Button testId="group-invite-link-share" className="h-11" disabled={busy} onClick={() => void shareChatText(link, chat.title)}>
              {t('groupLink.share')}
            </Button>
          </div>
          <div className="pt-1 flex items-center gap-5">
            <button type="button" data-testid="group-invite-link-renew" disabled={off} onClick={() => void run(() => groupLinksApi.renew(chat.id))} className="min-h-[44px] text-callout font-medium text-ink-2 disabled:opacity-50">
              {t('groupLink.renew')}
            </button>
            <button type="button" data-testid="group-invite-link-revoke" disabled={off} onClick={() => void run(() => groupLinksApi.revoke(chat.id))} className="min-h-[44px] text-callout font-medium text-danger disabled:opacity-50">
              {t('groupLink.revoke')}
            </button>
          </div>
        </>
      ) : (
        <Button
          variant="primary"
          testId="group-invite-link-create"
          disabled={off || state?.ready === false}
          onClick={() => void run(() => groupLinksApi.renew(chat.id))}
        >
          {t('groupLink.create')}
        </Button>
      )}
      {state?.ready === false && !note && <p className="pt-2 text-footnote text-ink-3">{t('groupLink.notReady')}</p>}
      {note && <p className={`pt-2 text-footnote ${note === 'groupLink.copied' ? 'text-ink-3' : 'text-danger'}`}>{t(note)}</p>}
    </div>
  );
};
