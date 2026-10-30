/**
 * Дії з розмовою на телефоні (довгий дотик по рядку списку).
 * Той самий набір, що в меню трьох крапок на ПК, у мові форми: рядки-двері,
 * руйнівне — словом `f-danger`, без заливки, з підтвердженням у тому ж аркуші.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useEffect, useState } from 'react';
import { Archive, ArchiveRestore, Bell, BellOff, CheckCheck, CircleDot, Eraser, Pencil, Pin, PinOff, Trash2, UserRoundCog, Users } from 'lucide-react';
import { chatMarkUnread, useUnreadMarks } from '../../services/chatMarkUnread';
import type { Chat } from '../../types/messenger';
import { DoorGroup, DoorRow, FormSheet, PrimaryButton } from './FormSheet';
import { displayTitle, isSavedNotes } from './chatListFormat';
import { CircleChoices, canHaveCircle } from './circles';
import { CIRCLE_KEY, isRealCircle } from './todayFormat';
import { useMessengerStore } from '../../stores/messengerStore';
import { useProfiles } from '../../stores/profilesStore';
import { MoveToProfileSheet, canMoveToProfile } from './MoveToProfileSheet';

interface ChatActionsSheetProps {
  chat: Chat | null;
  onClose: () => void;
  onMarkRead: (chat: Chat) => Promise<void> | void;
  /** Закріпити / відкріпити: закріплене завжди нагорі, над правилом потоку. */
  onTogglePin?: (chat: Chat) => void;
  onToggleMute?: (chat: Chat) => void;
  onToggleArchive?: (chat: Chat) => void;
  onRename: (chat: Chat, title: string) => Promise<void> | void;
  onClear: (chat: Chat) => Promise<void> | void;
  onDelete: (chat: Chat) => Promise<void> | void;
}

type Step = 'menu' | 'rename' | 'clear' | 'delete' | 'circle' | 'profile';
const ICON = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export const ChatActionsSheet: React.FC<ChatActionsSheetProps> = ({
  chat,
  onClose,
  onMarkRead,
  onTogglePin,
  onToggleMute,
  onToggleArchive,
  onRename,
  onClear,
  onDelete,
}) => {
  const { t } = useTranslation();
  const [step, setStep] = useState<Step>('menu');
  const [draft, setDraft] = useState('');
  const [running, setRunning] = useState(false);
  const marks = useUnreadMarks();
  const [circleBusy, setCircleBusy] = useState(false);
  const [circleFailed, setCircleFailed] = useState(false);
  const profileCount = useProfiles((s) => s.profiles.length);

  useEffect(() => {
    setStep('menu');
    setCircleFailed(false);
    // Поле починається з того, що людина бачить у списку, а не з сирої назви мосту.
    setDraft(chat ? displayTitle(chat.title) : '');
  }, [chat?.id, chat?.title]);

  if (!chat) return null;
  const saved = isSavedNotes(chat);
  const isGroup = chat.type === 'group' || chat.type === 'channel';
  const leaves = isGroup && chat.standing !== 'left' && chat.standing !== 'removed';
  const title = displayTitle(chat.title);

  const confirm =
    step === 'clear'
      ? {
          sentence: t(isGroup ? 'chatActionsSheet.clearSentenceGroup' : 'chatActionsSheet.clearSentence'),
          verb: t('chatActionsSheet.clear'),
          run: onClear,
        }
      : step === 'delete'
      ? {
          sentence: t(leaves ? 'chatActionsSheet.deleteSentenceGroup' : isGroup ? 'chatActionsSheet.deleteSentenceLeft' : 'chatActionsSheet.deleteSentence'),
          verb: t(leaves ? 'chatActionsSheet.leaveAndDelete' : 'chatActionsSheet.delete'),
          run: onDelete,
        }
      : null;

  return (
    <FormSheet
      open
      onClose={onClose}
      title={step === 'circle' ? t('circles.title') : step === 'profile' ? t('profileMove.door') : title}
      testId="chat-actions-sheet"
      onBack={step === 'menu' ? undefined : () => setStep('menu')}
    >
      {step === 'menu' && (
        <div className="pb-2 flex flex-col gap-3">
          <DoorGroup>
          {chat.unreadCount > 0 || chat.id in marks ? (
            <DoorRow
              icon={<CheckCheck {...ICON} />}
              label={t('chatActionsSheet.markRead')}
              onClick={() => {
                chatMarkUnread.clear(chat.id);
                if (chat.unreadCount > 0) void onMarkRead(chat);
                else onClose();
              }}
            />
          ) : (
            <DoorRow
              icon={<CircleDot {...ICON} />}
              label={t('chatActionsSheet.markUnread')}
              onClick={() => {
                chatMarkUnread.mark(chat.id);
                onClose();
              }}
            />
          )}
          {!saved && onTogglePin && (
            <DoorRow
              icon={chat.pinned ? <PinOff {...ICON} /> : <Pin {...ICON} />}
              label={chat.pinned ? t('chatActionsSheet.unpin') : t('chatActionsSheet.pin')}
              onClick={() => onTogglePin(chat)}
            />
          )}
          {onToggleMute && (
            <DoorRow
              icon={chat.muted ? <Bell {...ICON} /> : <BellOff {...ICON} />}
              label={chat.muted ? t('chatActionsSheet.unmute') : t('chatActionsSheet.mute')}
              onClick={() => onToggleMute(chat)}
            />
          )}
          </DoorGroup>
          {!saved && (
            <DoorGroup>
              {onToggleArchive && (
                <DoorRow
                  icon={chat.archived ? <ArchiveRestore {...ICON} /> : <Archive {...ICON} />}
                  label={chat.archived ? t('chatActionsSheet.unarchive') : t('chatActionsSheet.archive')}
                  onClick={() => onToggleArchive(chat)}
                />
              )}
              {canHaveCircle(chat) && (
                <DoorRow
                  icon={<Users {...ICON} />}
                  label={t('circles.move')}
                  hint={isRealCircle(chat.circle) ? t(CIRCLE_KEY[chat.circle]) : undefined}
                  onClick={() => setStep('circle')}
                />
              )}
              {canMoveToProfile(chat, profileCount) && (
                <DoorRow icon={<UserRoundCog {...ICON} />} label={t('profileMove.door')} onClick={() => setStep('profile')} />
              )}
              <DoorRow icon={<Pencil {...ICON} />} label={t('chatActionsSheet.rename')} onClick={() => setStep('rename')} />
            </DoorGroup>
          )}
          <DoorGroup>
          <DoorRow icon={<Eraser {...ICON} />} label={t('chatActionsSheet.clearHistory')} onClick={() => setStep('clear')} />
          {!saved && (
            <DoorRow
              icon={<Trash2 {...ICON} className="w-5 h-5 text-danger" />}
              label={t(leaves ? 'chatActionsSheet.leaveAndDelete' : 'chatActionsSheet.deleteChat')}
              tone="danger"
              onClick={() => setStep('delete')}
            />
          )}
          </DoorGroup>
        </div>
      )}

      {step === 'circle' && (
        <>
          <CircleChoices
            current={chat.circle}
            busy={circleBusy}
            onPick={async (c) => {
              setCircleBusy(true);
              setCircleFailed(false);
              const done = await useMessengerStore.getState().setChatCircle(chat.id, c);
              setCircleBusy(false);
              if (done) onClose();
              else setCircleFailed(true);
            }}
          />
          {circleFailed && <p className="px-4 pb-4 text-footnote text-danger">{t('circles.failed')}</p>}
        </>
      )}

      {step === 'profile' && <MoveToProfileSheet chat={chat} onDone={onClose} />}

      {step === 'rename' && (
        <form
          className="px-4 pb-4"
          onSubmit={(e) => {
            e.preventDefault();
            void onRename(chat, draft.trim());
          }}
        >
          <label className="block text-footnote font-semibold text-ink-2 mb-2" htmlFor="chat-rename">
            {t('chatActionsSheet.chatName')}
          </label>
          <input
            id="chat-rename"
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="w-full h-12 px-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
          />
          <PrimaryButton type="submit" disabled={!draft.trim() || draft.trim() === title} className="mt-4">
            {t('chatActionsSheet.save')}
          </PrimaryButton>
        </form>
      )}

      {confirm && (
        <div className="px-4 pb-4">
          <p className="text-callout text-ink-2">{confirm.sentence}</p>
          <button
            type="button"
            disabled={running}
            onClick={async () => {
              if (running) return;
              setRunning(true);
              try {
                await confirm.run(chat);
              } finally {
                setRunning(false);
              }
            }}
            className="mt-4 w-full h-12 min-h-0 rounded-control bg-raised text-headline text-danger active:opacity-90"
          >
            {confirm.verb}
          </button>
          <button
            type="button"
            onClick={() => setStep('menu')}
            className="mt-2 w-full h-12 min-h-0 rounded-control text-headline text-ink-2 active:bg-raised"
          >
            {t('chatActionsSheet.cancel')}
          </button>
        </div>
      )}
    </FormSheet>
  );
};

export default ChatActionsSheet;
