/**
 * Особливі стани профілю людини на каркасі InfoView (L4 зріз 4):
 * заблоковано (`blocked`), незнайомець зі слотом смуги C1 (`stranger`),
 * видалений акаунт (`deleted`). Монтує `PeerProfileSheet` (L3).
 */
import React, { useState } from 'react';
import { Ban, Flag, MessageCircle, Phone, Search, Trash2, User, Users } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { SHARED_TABS, type SharedIndex, type SharedTab } from '../../services/peerShared';
import type { Message } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { Avatar } from './Avatar';
import { InfoView, type InfoAction, type InfoPrimary, type InfoRowSpec, type InfoTab } from './InfoView';
import { SharedPanel } from './SharedSection';

export type PersonStateKind = 'blocked' | 'stranger' | 'deleted';

export interface PersonSharedGroup {
  id: string;
  title: string;
  subtitle?: string;
  avatar?: string;
}

export interface PersonInfoStateProps {
  state: PersonStateKind;
  name: string;
  handle?: string | null;
  avatar?: string | null;
  peerId?: string;
  tone?: 'canvas' | 'surface';
  shared: SharedIndex;
  sharedGroups?: PersonSharedGroup[];
  whoSent: (msg: Message) => string;
  onBack: () => void;
  onWrite?: () => void;
  onUnblock?: () => void;
  onReport?: () => void;
  onDeleteChat?: () => Promise<void> | void;
  onSearch?: () => void;
  onSelectGroup?: (groupId: string) => void;
  onJump: (msgId: string) => void;
  onOpenImage: (url: string) => void;
  /** Смуга C1 («Не з ваших кіл») від L2, ставиться між шапкою та вкладками. */
  strangerSlot?: React.ReactNode;
}

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export const PersonInfoState: React.FC<PersonInfoStateProps> = ({
  state,
  name,
  handle,
  avatar,
  peerId,
  tone = 'canvas',
  shared,
  sharedGroups = [],
  whoSent,
  onBack,
  onWrite,
  onUnblock,
  onReport,
  onDeleteChat,
  onSearch,
  onSelectGroup,
  onJump,
  onOpenImage,
  strangerSlot,
}) => {
  const { t } = useTranslation();
  const [infoTab, setInfoTab] = useState<string>();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteWhy, setDeleteWhy] = useState<string | null>(null);

  const isDeleted = state === 'deleted';
  const title = isDeleted ? t('infoStates.deletedAccount') : name;
  const cleanHandle = handle?.trim() ? (handle.trim().startsWith('@') ? handle.trim() : `@${handle.trim()}`) : null;
  const kind = isDeleted
    ? t('infoStates.wasInContacts')
    : state === 'blocked'
      ? cleanHandle
        ? t('infoStates.blockedKind', { handle: cleanHandle })
        : t('infoStates.blocked')
      : cleanHandle || t('infoStates.notInCircles');

  const primary: InfoPrimary | undefined =
    state === 'blocked' && onUnblock
      ? {
          key: 'unblock',
          label: t('infoStates.unblock'),
          icon: <Ban {...ICO} />,
          onClick: onUnblock,
          testId: 'person-state-unblock',
        }
      : state === 'stranger' && onWrite
        ? {
            key: 'write',
            label: t('peerProfileSheet.message'),
            icon: <MessageCircle {...ICO} />,
            tonal: true,
            onClick: onWrite,
            testId: 'person-state-write',
          }
        : undefined;

  const quick: InfoAction[] = [
    ...(state !== 'deleted'
      ? [
          {
            key: 'call',
            label: t('peerProfileSheet.call'),
            icon: <Phone {...ICO} />,
            disabled: true,
            onClick: () => {},
            testId: 'person-state-call',
          },
        ]
      : []),
    ...(onSearch
      ? [
          {
            key: 'search',
            label: t('groupProfileSheet.search'),
            icon: <Search {...ICO} />,
            onClick: onSearch,
            testId: 'person-state-search',
          },
        ]
      : []),
  ];

  const rows: InfoRowSpec[] = [
    ...(state === 'blocked' && onReport
      ? [
          {
            key: 'report',
            icon: <Flag {...ICO} />,
            label: t('peerProfileSheet.report'),
            onClick: onReport,
            testId: 'person-state-report',
          },
        ]
      : []),
    ...(state !== 'stranger' && onDeleteChat
      ? [
          {
            key: 'delete',
            icon: <Trash2 {...ICO} />,
            label: t('infoStates.deleteChat'),
            danger: true,
            onClick: () => setConfirmDelete(true),
            testId: 'person-state-delete',
          },
        ]
      : []),
  ];

  const shareTabs: InfoTab[] = SHARED_TABS.map((x) => ({
    key: x.key,
    label: x.label,
    count: shared[x.key].length,
  }));
  const groupsTab: InfoTab = {
    key: 'groups',
    label: t('infoStates.sharedGroupsTab'),
    count: sharedGroups.length,
  };
  const tabs: InfoTab[] = isDeleted ? shareTabs : [...shareTabs, groupsTab];
  const firstNonEmpty = tabs.find((x) => (x.count ?? 0) > 0)?.key ?? (state === 'stranger' ? 'groups' : 'media');
  const tab = infoTab ?? firstNonEmpty;

  const runDelete = async () => {
    if (!onDeleteChat) return;
    soundFx.playTap();
    setDeleteBusy(true);
    setDeleteWhy(null);
    try {
      await onDeleteChat();
    } catch {
      setDeleteWhy(t('infoStates.deleteFailed'));
      setDeleteBusy(false);
    }
  };

  const between = (
    <>
      {state === 'stranger' && strangerSlot}
      {confirmDelete && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-raised" data-testid="person-delete-confirm">
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => void runDelete()}
              disabled={deleteBusy}
              className="h-12 min-h-0 rounded-full bg-surface text-headline text-danger disabled:opacity-60"
            >
              {deleteBusy ? t('infoStates.deleting') : t('infoStates.delete')}
            </button>
            <button type="button" onClick={() => setConfirmDelete(false)} className="h-12 min-h-0 rounded-full bg-surface text-headline text-ink">
              {t('groupProfileSheet.cancel')}
            </button>
          </div>
          {deleteWhy && <p className="pt-2 text-callout text-danger">{deleteWhy}</p>}
        </div>
      )}
    </>
  );

  const avatarNode = isDeleted ? (
    <span className="w-20 h-20 rounded-full bg-raised text-ink-3 flex items-center justify-center" data-testid="person-deleted-avatar">
      <User className="w-8 h-8" strokeWidth={1.75} aria-hidden="true" />
    </span>
  ) : (
    <Avatar src={avatar} name={title} tone={peerId || title} radius="rounded-full" className="w-20 h-20 text-[30px]" />
  );

  const compactAvatarNode = isDeleted ? (
    <span className="w-8 h-8 rounded-full bg-raised text-ink-3 flex items-center justify-center">
      <User className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
    </span>
  ) : (
    <Avatar src={avatar} name={title} tone={peerId || title} radius="rounded-full" className="w-8 h-8 text-footnote" />
  );

  return (
    <InfoView
      testId={`person-info-${state}`}
      tone={tone}
      onBack={onBack}
      avatar={avatarNode}
      compactAvatar={compactAvatarNode}
      title={title}
      kind={kind}
      primary={primary}
      quick={quick}
      rows={rows}
      between={between}
      tabs={tabs}
      tab={tab}
      onTab={setInfoTab}
    >
      {tab === 'groups' ? (
        sharedGroups.length === 0 ? (
          <p className="px-4 py-12 text-center text-subhead text-ink-2" data-testid="person-groups-empty">
            {t('infoStates.noSharedGroups')}
          </p>
        ) : (
          <ul data-testid="person-shared-groups">
            {sharedGroups.map((g, i) => (
              <li key={g.id} className={i > 0 ? 'border-t border-line' : ''}>
                <button
                  type="button"
                  onClick={() => {
                    soundFx.playTap();
                    onSelectGroup?.(g.id);
                  }}
                  className="w-full min-h-14 px-4 py-2 flex items-center gap-3.5 text-left active:bg-raised"
                >
                  {g.avatar ? (
                    <Avatar src={g.avatar} name={g.title} tone={g.id} radius="rounded-full" className="w-10 h-10 text-subhead" />
                  ) : (
                    <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
                      <Users {...ICO} aria-hidden="true" />
                    </span>
                  )}
                  <span className="flex-1 min-w-0">
                    <span className="block text-headline text-ink truncate">{g.title}</span>
                    {g.subtitle && <span className="block text-footnote text-ink-2 truncate">{g.subtitle}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )
      ) : (
        <SharedPanel shared={shared} tab={tab as SharedTab} whoSent={whoSent} onJump={onJump} onOpenImage={onOpenImage} />
      )}
    </InfoView>
  );
};

export default PersonInfoState;
