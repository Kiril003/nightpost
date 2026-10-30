import React, { useMemo, useState } from 'react';
import type { Chat, ChatCircle } from '../../types/messenger';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { Avatar } from './Avatar';
import { chatKind, chatToneKey, displayTitle } from './chatListFormat';
import { canHaveCircle } from './circles';
import { FormSheet } from './FormSheet';

/** Люди з розмов, яких можна покласти в це коло: «Додати людину» у порожньому колі веде сюди. */
export const CirclePickSheet: React.FC<{ circle: Extract<ChatCircle, 'close' | 'friends'>; onClose: () => void }> = ({ circle, onClose }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const setChatCircle = useMessengerStore((s) => s.setChatCircle);
  const [failed, setFailed] = useState(false);
  const rows = useMemo(
    () =>
      chats.filter(
        (c: Chat) => !c.archived && chatKind(c) === 'person' && canHaveCircle(c) && c.circle !== circle && !(c.circles ?? []).includes(circle),
      ),
    [chats, circle],
  );

  const pick = async (id: string) => {
    setFailed(false);
    if (await setChatCircle(id, circle)) onClose();
    else setFailed(true);
  };

  return (
    <FormSheet open onClose={onClose} title={t(circle === 'close' ? 'circlePick.closeTitle' : 'circlePick.friendsTitle')} testId="circle-pick-sheet">
      <ul className="px-2 pb-2">
        {rows.map((c) => (
          <li key={c.id}>
            <button
              type="button"
              data-circle-pick={c.id}
              onClick={() => void pick(c.id)}
              className="w-full h-16 min-h-0 px-3 flex items-center gap-3 text-left rounded-control active:bg-overlay"
            >
              <Avatar src={c.avatar} name={displayTitle(c.title)} tone={chatToneKey(c)} className="w-10 h-10 text-subhead" />
              <span className="flex-1 min-w-0 truncate text-body text-ink">{displayTitle(c.title)}</span>
            </button>
          </li>
        ))}
      </ul>
      {failed && <p className="px-4 pb-3 text-callout text-danger">{t('circlePick.failed')}</p>}
      <div className="px-4 pb-4">
        <button
          type="button"
          data-circle-pick-new
          onClick={() => {
            onClose();
            useMessengerStore.getState().setCreateChatModalOpen(true, 'add');
          }}
          className="w-full h-12 min-h-0 rounded-control bg-raised text-body text-ink"
        >
          {t('msgV2.addPerson')}
        </button>
      </div>
    </FormSheet>
  );
};
