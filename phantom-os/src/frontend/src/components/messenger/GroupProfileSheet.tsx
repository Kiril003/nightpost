/**
 * Профіль групи на каркасі InfoView: обличчя, «Запросити» + швидкі дії,
 * рядок налаштувань, вкладки Учасники · Медіа · Файли · Посилання · Голосові.
 * Рідше потрібне (закріпити, керування, журнал, скарга, вихід) — у ⋮.
 */
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import React, { useEffect, useMemo, useState } from 'react';
import { BarChart3, Bell, BellOff, Loader2, Lock, LogOut, MessageSquare, MoreVertical, Pencil, Phone, PinOff, ScrollText, Search, ShieldCheck, SlidersHorizontal, Trash2, UserPlus, Users } from 'lucide-react';
import { messengerApi } from '../../services/messengerApi';
import { currentDateTag } from '../chat/tNow';
import { parseNodeTime } from './chatListFormat';
import { peerProfileEdits } from './peerProfileEdits';
import type { Chat, ChatMember } from '../../types/messenger';
import { indexShared, SHARED_TABS, type SharedTab } from '../../services/peerShared';
import { useMessengerStore } from '../../stores/messengerStore';
import { displayTitle } from './chatListFormat';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { AVATAR_SIZE, Avatar } from './Avatar';
import { AvatarViewerModal } from './AvatarViewerModal';
import { FormSheet } from './FormSheet';
import { GroupManageTab, MemberActions, roleLine } from './GroupRoles';
import { SharedPanel } from './SharedSection';
import { ChannelPostsPanel, ChannelStatsView, ChannelSummary, channelPosts, channelStats } from './ChannelInfo';
import { InfoRows, InfoView, type InfoAction, type InfoMenuItem, type InfoRowSpec } from './InfoView';
import { canHaveCircle, useCircleLabels } from './circles';
import { CircleSheet } from './CircleSheet';
import { CIRCLE_KEY, isRealCircle } from './todayFormat';
import { PlanSummary } from './meetup/PlanSummary';
import { GroupSettingsPanel, isChannel } from './GroupSettingsPanel';
import { isRealName, mutualGroups, peerFace } from './groupPeople';
import { useSupportStore } from '../../stores/supportStore';
import { ReportSheetHost } from '../support/ReportSheet';

export interface GroupProfileSheetProps {
  chat: Chat;
  onClose: () => void;
  onSelectMember: (member: ChatMember) => void;
  onTogglePinChat: (chatId: string) => void;
  /** Пошук у розмові. Немає — кнопки немає. */
  onSearch?: () => void;
  /** Колонка ПК: лист лежить у потоці, а не поверх вікна. */
  inline?: boolean;
}

type View = 'main' | 'manage' | 'add' | 'log' | 'settings' | 'stats' | 'perms' | 'requests';

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export const people = (n: number, t: Translation['t'], locale: Translation['locale'], channel = false): string => {
  const [one, few, many] = channel
    ? (['groupsTg.sub1', 'groupsTg.sub2', 'groupsTg.sub5'] as const)
    : (['groupProfileSheet.member1', 'groupProfileSheet.member2', 'groupProfileSheet.member5'] as const);
  if (locale !== 'uk') return t(n === 1 ? one : many);
  const d = n % 10;
  const h = n % 100;
  if (d === 1 && h !== 11) return t(one);
  if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return t(few);
  return t(many);
};

export const GroupProfileSheet: React.FC<GroupProfileSheetProps> = ({
  chat,
  onClose,
  onSelectMember,
  onTogglePinChat,
  onSearch,
  inline = false,
}) => {
  const { t, locale } = useTranslation();
  const [view, setView] = useState<View>('main');
  const circleLabels = useCircleLabels(chat.id);
  const [circleOpen, setCircleOpen] = useState(false);
  const [faceOpen, setFaceOpen] = useState(false);
  const [memberSearchQuery, setMemberSearchQuery] = useState('');
  const [openMemberId, setOpenMemberId] = useState<string | null>(null);
  const [unpinBusy, setUnpinBusy] = useState(false);
  const [unpinWhy, setUnpinWhy] = useState<string | null>(null);
  /**
   * Три стани складу, а не два. До 16.09 «ще питаємо» і «міст відмовив»
   * малювались одним написом, і людина чекала того, що вже не прийде.
   */
  const [rosterState, setRosterState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [rosterAttempt, setRosterAttempt] = useState(0);
  const [confirmLeave, setConfirmLeave] = useState(false);
  // null — ще не виходили; число — скільком це вже сказано.
  const [leaveSaid, setLeaveSaid] = useState<number | null>(null);
  const [leaveBusy, setLeaveBusy] = useState(false);
  const [leaveRefused, setLeaveRefused] = useState<string | null>(null);
  const [addingId, setAddingId] = useState<string | null>(null);
  const [addRefused, setAddRefused] = useState<string | null>(null);
  const [heirId, setHeirId] = useState<string | null>(null);
  const [infoTab, setInfoTab] = useState<string>();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteWhy, setDeleteWhy] = useState<string | null>(null);

  const isGroup = chat.type === 'group';
  const selfId = useMessengerStore((s) => s.currentUser.id);
  const selfName = useMessengerStore((s) => s.currentUser.name);
  const chats = useMessengerStore((s) => s.chats);
  // Імʼя учасника — людське: себе — своїм імʼям, інших — як у списку розмов.
  // На A52 тут стояли «747a·a842 · власник · ви» і «Вузол 4d839df7» (02.10).
  const memberName = (m: { name: string; isMe?: boolean }): string =>
    m.isMe ? (selfName || '').trim() || t('groupProfileSheet.you') : displayTitle(m.name);

  // Склад питаємо тоді, коли на нього дивляться: возити імена на кожен рядок
  // переліку дорожче за саму розмову.
  useEffect(() => {
    if (!isGroup) return;
    let alive = true;
    setRosterState('loading');
    void useMessengerStore
      .getState()
      .loadGroupMembers(chat.id)
      .then((told) => {
        if (alive) setRosterState(told ? 'ready' : 'failed');
      });
    return () => {
      alive = false;
    };
  }, [isGroup, chat.id, rosterAttempt]);

  const shared = useMemo(() => indexShared(chat.messages || [], selfId), [chat.messages, selfId]);
  /**
   * Журнал — з власних рядків гілки (`system_act`), а не з `chat.auditLogs`:
   * те поле не писав ніхто, а до 05.09 там стояли двоє вигаданих записів.
   */
  const actLog = useMemo(() => (chat.messages || []).filter((m) => m.systemAct).slice().reverse(), [chat.messages]);
  const channel = isGroup && isChannel(chat);
  const staff = chat.myRole === 'owner' || chat.myRole === 'admin';
  const posts = useMemo(() => (channel ? channelPosts(chat.messages || []) : []), [channel, chat.messages]);

  const title = chat.title || t('groupProfileSheet.conversation');
  const members = chat.members || [];
  const powers = chat.powers;
  const needle = memberSearchQuery.trim().toLowerCase();
  // Пошук — лише серед цього складу: каталогу нікнеймів у мережі не існує.
  const filteredMembers = !needle
    ? members
    : members.filter(
        (m) =>
          memberName(m).toLowerCase().includes(needle) ||
          m.handle.toLowerCase().includes(needle) ||
          (m.customTitle && m.customTitle.toLowerCase().includes(needle)),
      );
  const inGroup = chat.standing !== 'left' && chat.standing !== 'removed';
  const muted = chat.muted ?? false;

  const countLine = !isGroup
    ? t('groupProfileSheet.personal')
    : chat.members
      ? `${members.length} ${people(members.length, t, locale, isChannel(chat))}`
      : rosterState === 'failed'
        ? ''
        : '';

  const jumpTo = (msgId: string) => {
    soundFx.playTap();
    useMessengerStore.getState().openChatAtMessage(chat.id, msgId);
    onClose();
  };

  const unpin = async () => {
    soundFx.playTap();
    setUnpinBusy(true);
    setUnpinWhy(null);
    try {
      await useMessengerStore.getState().pinGroupMessage(chat.id, '');
    } catch (e) {
      setUnpinWhy(e instanceof Error ? e.message : t('groupProfileSheet.unpinFailed'));
    } finally {
      setUnpinBusy(false);
    }
  };

  // Пряме додавання — єдиний спосіб, який дріт вміє (`Act.Add`).
  // Дивись docs/GROUP_ROLES_CONTRACT.md.
  const addMember = async (peerId: string) => {
    soundFx.playTap();
    setAddingId(peerId);
    setAddRefused(null);
    try {
      await useMessengerStore.getState().addGroupMember(chat.id, peerId);
    } catch (e) {
      setAddRefused(e instanceof Error ? e.message : t('groupProfileSheet.addFailed'));
    } finally {
      setAddingId(null);
    }
  };

  const leave = async () => {
    soundFx.playTap();
    setLeaveBusy(true);
    setLeaveRefused(null);
    try {
      if (heirId) await useMessengerStore.getState().setGroupRole(chat.id, heirId, 'owner');
      const told = await useMessengerStore.getState().leaveGroup(chat.id);
      setLeaveSaid(told);
      setConfirmLeave(false);
    } catch (e) {
      setLeaveRefused(e instanceof Error ? e.message : t('groupProfileSheet.leaveFailed'));
    } finally {
      setLeaveBusy(false);
    }
  };

  // Видалити — стерти розмову на цьому телефоні; вихід із групи це не замінює.
  const deleteChat = async () => {
    soundFx.playTap();
    setDeleteBusy(true);
    setDeleteWhy(null);
    try {
      await messengerApi.deleteConversation(chat.id);
      peerProfileEdits(chat.id).onDeleted();
      onClose();
    } catch {
      setDeleteWhy(t('infoStates.deleteFailed'));
      setDeleteBusy(false);
    }
  };

  const back = () => setView('main');
  // Колонка ПК: Escape скасовує підтвердження виходу, а не закриває колонку.
  useEscapeClose(inline && view === 'main' && confirmLeave, () => setConfirmLeave(false));

  if (view === 'manage') {
    return (
      <FormSheet open tall inline={inline} onClose={onClose} onBack={back} title={t('groupProfileSheet.manage')} testId="group-profile-manage">
        <GroupManageTab chat={chat} />
      </FormSheet>
    );
  }

  if (view === 'add') {
    const max = chat.membersMax || 32;
    return (
      <FormSheet open tall inline={inline} onClose={onClose} onBack={back} title={t('groupProfileSheet.addPeople')} testId="group-profile-add">
        <div className="px-4 pb-4 space-y-4">
          {members.length >= max && (
            <p className="text-callout text-ink-2">
              {t('groupProfileSheet.full', { n: members.length, max })}
            </p>
          )}
          {addRefused && <p className="text-callout text-danger">{addRefused}</p>}

          <div>
            {!chat.membersAddable ? (
              <p className="text-callout text-ink-3">{t('groupProfileSheet.loading')}</p>
            ) : chat.membersAddable.length === 0 ? (
              <p className="text-callout text-ink-3">{t('groupProfileSheet.allIn')}</p>
            ) : (
              <ul>
                {chat.membersAddable.map((c) => (
                  <li key={c.id} className="min-h-[64px] flex items-center gap-3.5">
                    <Avatar name={c.name} tone={c.id} radius="rounded-full" className={AVATAR_SIZE.row} />
                    <span className="flex-1 min-w-0 text-headline text-ink truncate">{c.name}</span>
                    <button
                      type="button"
                      onClick={() => void addMember(c.id)}
                      disabled={addingId === c.id}
                      className="h-11 min-h-0 px-5 rounded-full bg-accent text-on-accent text-callout font-semibold disabled:opacity-60"
                    >
                      {addingId === c.id ? t('groupProfileSheet.adding') : t('groupProfileSheet.add')}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </FormSheet>
    );
  }

  if (view === 'settings') {
    return (
      <FormSheet
        open
        tall
        inline={inline}
        onClose={onClose}
        onBack={back}
        title={isChannel(chat) ? t('groupsTg.channelSettings') : t('groupsTg.settings')}
        testId="group-profile-settings"
      >
        <GroupSettingsPanel chat={chat} onPage={(p) => p !== 'root' && setView(p)} onOpenPerson={onSelectMember} />
        <p className="px-4 pt-5 pb-1.5 text-subhead text-ink-3">{t('infoGroup.management')}</p>
        <InfoRows
          rows={[
            {
              key: 'admins',
              icon: <ShieldCheck {...ICO} />,
              label: t('infoGroup.admins'),
              aside: String(members.filter((m) => m.role === 'owner' || m.role === 'admin').length || ''),
              onClick: () => setView('manage'),
              testId: 'group-settings-admins',
            },
            { key: 'log', icon: <ScrollText {...ICO} />, label: t('groupProfileSheet.log'), count: actLog.length, onClick: () => setView('log'), testId: 'group-settings-log' },
          ]}
        />
        {leaveSaid === null && (
          <InfoRows
            rows={[
              {
                key: 'leave',
                icon: <LogOut {...ICO} />,
                label: isChannel(chat) ? t('infoChannel.unsubscribe') : t('groupProfileSheet.leaveGroup'),
                danger: true,
                onClick: () => {
                  setView('main');
                  setConfirmLeave(true);
                },
                testId: 'group-settings-leave',
              },
            ]}
          />
        )}
      </FormSheet>
    );
  }

  if (view === 'perms' || view === 'requests') {
    return (
      <FormSheet
        open
        tall
        inline={inline}
        onClose={onClose}
        onBack={() => setView('settings')}
        title={view === 'perms' ? t('groupsTg.perms') : t('groupsTg.requestsRow')}
        testId={`group-profile-${view}`}
      >
        <GroupSettingsPanel chat={chat} page={view} onOpenPerson={onSelectMember} />
      </FormSheet>
    );
  }

  if (view === 'stats') {
    return (
      <FormSheet open tall inline={inline} onClose={onClose} onBack={back} title={t('infoChannel.stats')} testId="group-profile-stats">
        <ChannelStatsView messages={chat.messages || []} total={members.length} />
      </FormSheet>
    );
  }

  if (view === 'log') {
    return (
      <FormSheet open tall inline={inline} onClose={onClose} onBack={back} title={t('groupProfileSheet.logTitle')} testId="group-profile-log">
        <div className="px-4 pb-4">
          {actLog.length === 0 ? (
            <p className="py-6 text-callout text-ink-3">{t('groupProfileSheet.logEmpty')}</p>
          ) : (
            <>
              <ul>
                {actLog.map((m) => (
                  <li key={m.id} className="py-2 flex items-start gap-3.5">
                    <Avatar name={m.senderName || t('groupProfileSheet.memberFallback')} tone={m.senderId} radius="rounded-full" className="w-10 h-10 shrink-0" />
                    <span className="flex-1 min-w-0">
                      <span className="block text-callout text-ink">{m.text}</span>
                      <span className="block text-caption text-ink-3">{m.timestamp}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </FormSheet>
    );
  }

  // Без історії для нових рядки до вступу не доїжджають — вкладка медіа лишається і каже чому.
  const histHidden = isGroup && inGroup && chat.groupSettings?.hist === false && !staff;
  const shareTabs = SHARED_TABS.map((x) => ({
    key: x.key,
    label: x.label,
    count: histHidden && x.key === 'media' && shared.media.length === 0 ? undefined : shared[x.key].length,
  }));
  const membersTab = { key: 'members', label: t(channel ? 'infoChannel.subscribersTab' : 'groupProfileSheet.members'), count: chat.members ? members.length : undefined };
  // Канал читають дописами; склад підписників бачить лише той, хто ним керує.
  const tabs = channel
    ? [{ key: 'posts', label: t('infoChannel.posts'), count: posts.length }, ...(staff && inGroup ? [membersTab] : []), ...shareTabs]
    : isGroup && inGroup
      ? [membersTab, ...shareTabs]
      : shareTabs;
  const tab = infoTab ?? (channel ? 'posts' : isGroup && inGroup ? 'members' : (shareTabs.find((x) => (x.count ?? 0) > 0)?.key ?? 'media'));
  const mine = (m: { isSelf?: boolean; senderId: string }) => m.isSelf || m.senderId === selfId;
  const leftIso = actLog.find((m) => m.systemAct === 'leave' && mine(m))?.sentAt;
  const leftAt = leftIso ? parseNodeTime(leftIso)?.toLocaleDateString(currentDateTag(), { day: '2-digit', month: '2-digit' }) : undefined;
  const kind = !isGroup
    ? t('groupProfileSheet.personal')
    : chat.standing === 'removed'
      ? t(channel ? 'infoStates.removedChannel' : 'infoStates.removed')
      : chat.standing === 'left'
        ? leftAt
          ? t(channel ? 'infoStates.leftChannelOn' : 'infoStates.leftOn', { date: leftAt })
          : t(channel ? 'infoStates.leftChannel' : 'infoStates.left')
        : channel && chat.myRole === 'owner'
      ? t('infoChannel.kindOwner')
      : countLine
      ? t(isChannel(chat) ? 'infoGroup.kindChannel' : 'infoGroup.kindGroup', { count: countLine })
      : '';
  const mayAdd = isGroup && inGroup && Boolean(powers?.add);
  const live = isGroup && chat.liveRoom && !chat.liveRoom.mine ? chat.liveRoom : null;

  const quick = (!inGroup && isGroup
    ? [onSearch ? { key: 'search', label: t('groupProfileSheet.search'), icon: <Search {...ICO} />, testId: 'group-action-search', onClick: () => { onClose(); onSearch(); } } : null]
    : [
    live
      ? {
          key: 'join',
          label: t('liveRoomStrip.join'),
          icon: <Phone {...ICO} />,
          testId: 'group-action-join',
          onClick: () =>
            window.dispatchEvent(
              new CustomEvent('phantom:start-call', {
                detail: { contactId: chat.id, peerNodeId: chat.peerNodeId, displayName: displayTitle(title), callId: live.callId, video: false },
              }),
            ),
        }
      : null,
    channel && staff
      ? { key: 'edit', label: t('infoChannel.edit'), icon: <Pencil {...ICO} />, testId: 'group-action-edit', onClick: () => setView('manage') }
      : {
          key: 'mute',
          label: muted ? t('groupProfileSheet.unmute') : t('groupProfileSheet.mute'),
          icon: muted ? <Bell {...ICO} /> : <BellOff {...ICO} />,
          active: muted,
          testId: 'group-action-mute',
          onClick: () => useMessengerStore.getState().toggleMuteChat(chat.id),
        },
    onSearch
      ? {
          key: 'search',
          label: t('groupProfileSheet.search'),
          icon: <Search {...ICO} />,
          testId: 'group-action-search',
          onClick: () => {
            onClose();
            onSearch();
          },
        }
      : null,
  ] as (InfoAction | null)[]).filter((x): x is InfoAction => x !== null);

  const settingsRow: InfoRowSpec[] =
    inGroup && chat.groupSettings && powers?.settings
      ? [
          {
            key: 'settings',
            icon: <SlidersHorizontal {...ICO} />,
            label: isChannel(chat) ? t('groupsTg.channelSettings') : t('groupsTg.settings'),
            onClick: () => setView('settings'),
            testId: 'group-row-settings',
          },
        ]
      : [];
  const rows: InfoRowSpec[] = isGroup && !inGroup
    ? [{ key: 'delete', icon: <Trash2 {...ICO} />, label: t('infoStates.deleteChat'), danger: true, onClick: () => setConfirmDelete(true), testId: 'group-row-delete' }]
    : !channel
    ? settingsRow
    : staff
      ? [
          { key: 'stats', icon: <BarChart3 {...ICO} />, label: t('infoChannel.stats'), onClick: () => setView('stats'), testId: 'channel-row-stats' },
          {
            key: 'admins',
            icon: <ShieldCheck {...ICO} />,
            label: t('infoGroup.admins'),
            aside: String(members.filter((m) => m.role === 'owner' || m.role === 'admin').length || ''),
            onClick: () => setView('manage'),
            testId: 'channel-row-admins',
          },
          ...settingsRow,
        ]
      : chat.groupSettings
        ? [
            {
              key: 'talk',
              icon: <MessageSquare {...ICO} />,
              label: t('infoChannel.discussion'),
              aside: chat.groupSettings.talk ? t('infoChannel.comments') : t('infoChannel.commentsOff'),
              testId: 'channel-row-talk',
            },
          ]
        : [];
  // Коло — рядок списку, як у профілі людини, а не картка-поле посеред інфо.
  const circleRow: InfoRowSpec[] = canHaveCircle(chat)
    ? [{ key: 'circle', icon: <Users {...ICO} />, label: t('circles.row'), aside: circleLabels.join(' · ') || undefined, onClick: () => setCircleOpen(true), testId: 'circle-row' }]
    : [];
  const infoRows = [...rows.filter((r) => !r.danger), ...circleRow, ...rows.filter((r) => r.danger)];
  const summary =
    channel && staff ? (
      <ChannelSummary
        items={[
          { key: 'total', label: t('infoChannel.subscribers'), value: String(members.length) },
          { key: 'week', label: t('infoChannel.week'), value: `+${channelStats(chat.messages || [], members.length, '7').joined}` },
          { key: 'posts', label: t('infoChannel.posts'), value: String(posts.length) },
        ]}
      />
    ) : undefined;

  const menu: InfoMenuItem[] = [
    { key: 'pin', label: chat.pinned ? t('infoGroup.unpinChat') : t('infoGroup.pinChat'), onClick: () => onTogglePinChat(chat.id) },
    ...(isGroup
      ? [
          ...(channel || !inGroup ? [] : [{ key: 'manage', label: t('groupProfileSheet.manage'), onClick: () => setView('manage') }]),
          { key: 'log', label: t('groupProfileSheet.log'), onClick: () => setView('log') },
          {
            key: 'report',
            label: channel ? t('infoChannel.report') : t('support.group.report'),
            danger: true,
            onClick: () =>
              useSupportStore.getState().openReport({
                targetKind: 'group',
                targetId: chat.id,
                targetName: title,
                chatId: chat.id,
                senderKey: chat.peerNodeId || chat.id,
                surface: chat.isPublic ? 'public' : 'private',
              }),
          },
          ...(inGroup && leaveSaid === null ? [{ key: 'leave', label: channel ? t('infoChannel.unsubscribe') : t('groupProfileSheet.leaveGroup'), danger: true, onClick: () => setConfirmLeave(true) }] : []),
        ]
      : []),
  ];

  const membersPanel = (
    <div data-testid="group-profile-members">
      {members.length > 8 && (
        <div className="relative mx-4 my-2">
          <Search className="w-5 h-5 absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          <input
            type="text"
            placeholder={t('groupProfileSheet.searchMembers')}
            aria-label={t('groupProfileSheet.searchMembers')}
            value={memberSearchQuery}
            onChange={(e) => setMemberSearchQuery(e.target.value)}
            className="w-full h-12 pl-11 pr-4 rounded-full bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
          />
        </div>
      )}
      {mayAdd && <InfoRows rows={[{ key: 'add', icon: <UserPlus {...ICO} />, label: t('infoGroup.addMembers'), onClick: () => setView('add'), testId: 'group-row-add' }]} />}

      {members.length === 0 &&
        !needle &&
        (rosterState === 'loading' ? (
          <p className="px-4 py-4 flex items-center gap-2 text-callout text-ink-3">
            <Loader2 className="w-4 h-4 animate-spin" strokeWidth={2} />
            {t('groupProfileSheet.loadingMembers')}
          </p>
        ) : rosterState === 'failed' ? (
          <div className="px-4 py-2 flex items-center gap-3">
            <p className="flex-1 text-callout text-ink-2">{t('groupProfileSheet.membersFailed')}</p>
            <button type="button" data-testid="group-members-retry" onClick={() => setRosterAttempt((n) => n + 1)} className="h-11 px-4 rounded-full bg-raised text-subhead text-ink active:bg-overlay">
              {t('coreChat.retry')}
            </button>
          </div>
        ) : (
          <p className="px-4 py-2 text-callout text-ink-2">{t('groupProfileSheet.noMembers')}</p>
        ))}
      {needle && filteredMembers.length === 0 && <p className="px-4 py-2 text-callout text-ink-2">{t('groupProfileSheet.noSuchMember')}</p>}

      <ul>
        {filteredMembers.map((member, i, all) => {
          const hasActions = member.mayRemove || member.mayGrant || member.mayOwn || member.mayRights;
          // Ролі стоять одним стовпцем: де дій немає, місце під «⋮» лишається порожнім.
          const column = all.some((m) => m.mayRemove || m.mayGrant || m.mayOwn || m.mayRights);
          const open = openMemberId === member.id;
          const theirs = member.isMe ? undefined : chats.find((c) => (c.type === 'dm' || c.type === 'direct') && (c.peerNodeId === member.id || c.id === member.id))?.circle;
          const roleLow = roleLine(member.role, t);
          const role = roleLow && roleLow.charAt(0).toLocaleUpperCase() + roleLow.slice(1);
          const mutual = member.isMe ? [] : mutualGroups(chats, member.id, chat.id);
          const face = member.isMe ? null : peerFace(chats, member.id, member.name);
          const shownName = member.isMe || isRealName(member.name, member.id) ? memberName(member) : face?.name || memberName(member);
          const handle = (member.handle || face?.handle || '').replace(/^@/, '');
          const sub = [
            member.isMe && (selfName || '').trim() ? t('groupProfileSheet.you') : null,
            !member.isMe && handle ? `@${handle}` : null,
            isRealCircle(theirs) ? t('groupProfileSheet.alsoIn', { circle: t(CIRCLE_KEY[theirs]) }) : null,
            !isRealCircle(theirs) && mutual.length > 0 ? t('groupLink.mutual', { names: mutual.slice(0, 2).join(', ') }) : null,
            !member.isMe && member.sessionReady === false ? t('groupProfileSheet.notConnected') : null,
          ]
            .filter(Boolean)
            .join(' · ');
          return (
            <li key={member.id} className={open ? 'mx-2 mb-2 pb-3 rounded-card bg-raised' : i > 0 || mayAdd ? 'border-t border-line' : ''}>
              <div className={`flex items-center gap-1 ${open ? 'pl-2 pr-1' : 'pl-4 pr-2'}`}>
                {/* Картка людини — за іменем; дії над нею в групі — за «⋮».
                    Висота рядка — з відступу: min-h-[…] на телефоні
                    перебиває підлога дотику для min-w-0 (messenger.css), і аватари злипались (T44). */}
                <button
                  type="button"
                  onClick={() => {
                    soundFx.playTap();
                    onSelectMember(member);
                  }}
                  className={`flex-1 min-w-0 py-1.5 flex items-center gap-3.5 text-left ${column ? '' : 'pr-2'}`}
                >
                  <Avatar src={member.avatar || face?.avatar} name={shownName} tone={member.id} radius="rounded-full" className="w-10 h-10 text-headline" />
                  <span className="flex-1 min-w-0">
                    <span className="block text-headline font-semibold text-ink truncate">{shownName}</span>
                    {sub && <span className="block text-footnote text-ink-3 truncate">{sub}</span>}
                  </span>
                  {role && (
                    <span className={`shrink-0 h-6 px-2 rounded-[6px] ${open ? 'bg-surface' : 'bg-raised'} text-caption text-ink-2 flex items-center`} data-testid="group-member-role">
                      {role}
                    </span>
                  )}
                </button>
                {hasActions && (
                  <button
                    type="button"
                    onClick={() => {
                      soundFx.playTap();
                      setOpenMemberId(open ? null : member.id);
                    }}
                    aria-label={open ? t('groupProfileSheet.collapse') : t('groupProfileSheet.memberActions')}
                    aria-expanded={open}
                    className="w-[var(--touch-min)] h-[var(--touch-min)] shrink-0 rounded-full text-ink-3 flex items-center justify-center active:bg-raised"
                  >
                    <MoreVertical className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                  </button>
                )}
                {!hasActions && column && <span className="w-[var(--touch-min)] shrink-0" data-testid="member-trail-spacer" aria-hidden />}
              </div>
              {open && <MemberActions chat={chat} member={member} onDone={() => setOpenMemberId(null)} />}
            </li>
          );
        })}
      </ul>
    </div>
  );

  const between = (
    <>
      {isGroup && <PlanSummary chat={chat} meId={selfId} onClose={onClose} />}

      {/* Закріплений рядок. `id: ''` при наявному авторі — рядок закріпили
          до того, як він сюди приїхав, або стерли. */}
      {/* У каналі закріплене стоїть першим у «Дописах». */}
      {isGroup && !channel && chat.pinnedMessage && (
        <div className="mx-4 mt-3 py-3 pl-4 pr-2 rounded-card bg-raised flex items-start gap-3" data-testid="group-profile-pinned">
          <div className="min-w-0 flex-1">
            <p className="text-footnote font-semibold text-accent-text">{t('groupProfileSheet.pinnedBy', { author: chat.pinnedMessage.author })}</p>
            <p className="mt-0.5 text-callout text-ink line-clamp-3">
              {chat.pinnedMessage.text ?? <span className="text-ink-3">{t('groupProfileSheet.msgMissing')}</span>}
            </p>
            {unpinWhy && <p className="mt-1 text-callout text-danger">{unpinWhy}</p>}
          </div>
          {chat.powers?.pin && (
            <button
              type="button"
              disabled={unpinBusy}
              onClick={() => void unpin()}
              title={t('groupProfileSheet.unpin')}
              aria-label={t('groupProfileSheet.unpin')}
              className="w-11 h-11 shrink-0 rounded-full text-ink-2 active:bg-surface flex items-center justify-center disabled:opacity-50"
            >
              {unpinBusy ? <Loader2 className="w-5 h-5 animate-spin" strokeWidth={2} /> : <PinOff {...ICO} />}
            </button>
          )}
        </div>
      )}

      {circleOpen && <CircleSheet chatId={chat.id} open onClose={() => setCircleOpen(false)} />}

      {confirmLeave && inGroup && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-raised" data-testid="group-leave-confirm">
          <p className="text-callout text-ink-2">{t('groupProfileSheet.leaveWarn')}</p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button type="button" onClick={() => void leave()} disabled={leaveBusy} className="h-12 min-h-0 rounded-full bg-surface text-headline text-danger disabled:opacity-60">
              {leaveBusy ? t('groupProfileSheet.leaving') : heirId ? t('groupsTg.transferLeave') : t('groupProfileSheet.leave')}
            </button>
            <button type="button" onClick={() => setConfirmLeave(false)} className="h-12 min-h-0 rounded-full bg-surface text-headline text-ink">
              {t('groupProfileSheet.cancel')}
            </button>
          </div>
          {chat.myRole === 'owner' && members.some((m) => !m.isMe) && (
            <div className="pt-3" data-testid="group-leave-heir">
              <p className="pb-2 text-footnote font-semibold text-ink-2">{t('groupsTg.newOwner')}</p>
              <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t('groupsTg.newOwner')}>
                {members
                  .filter((m) => !m.isMe)
                  .map((m) => (
                    <button
                      key={m.id}
                      type="button"
                      role="radio"
                      aria-checked={heirId === m.id}
                      onClick={() => setHeirId(heirId === m.id ? null : m.id)}
                      className={`h-11 min-h-0 px-4 rounded-full text-callout font-semibold ${heirId === m.id ? 'bg-ink text-surface' : 'bg-surface text-ink-2'}`}
                    >
                      {memberName(m)}
                    </button>
                  ))}
              </div>
            </div>
          )}
        </div>
      )}
      {leaveRefused && <p className="px-4 pt-2 text-callout text-danger">{leaveRefused}</p>}
      {confirmDelete && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-raised" data-testid="group-delete-confirm">
          <div className="grid grid-cols-2 gap-2">
            <button type="button" onClick={() => void deleteChat()} disabled={deleteBusy} className="h-12 min-h-0 rounded-full bg-surface text-headline text-danger disabled:opacity-60">
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

  const notes = (
    <>
      {chat.publicHandle && <p className="px-4 pt-1 text-callout text-ink-3">{chat.publicHandle}</p>}
      {/* Після виходу число лишається: «сказано двом» інакше зникало б у ту
          мить, коли стан ставав `left`. */}
      {leaveSaid !== null && (
        <p className="px-4 pt-2 text-callout text-ink-2" data-testid="group-leave-said">
          {t(leaveSaid === 1 ? 'groupProfileSheet.leftSaid1' : 'groupProfileSheet.leftSaidN', { n: leaveSaid })}
        </p>
      )}
    </>
  );

  return (
    <FormSheet open tall full={!inline} inline={inline} hideTitle onClose={onClose} title={t('groupProfileSheet.groupTitle', { title })} testId="group-profile">
      <InfoView
        testId="group-info"
        tone={inline ? 'surface' : 'canvas'}
        onBack={onClose}
        avatar={
          // Дотик до фото — переглядач обличчя з рядком правди про мініатюру,
          // а не лайтбокс вкладень з «оригіналом». Без сяйва тону (DS §7).
          <button
            type="button"
            data-testid="group-profile-photo"
            disabled={!chat.avatar}
            onClick={() => chat.avatar && setFaceOpen(true)}
            aria-label={chat.avatar ? t('groupProfileSheet.photoFull') : t('groupProfileSheet.photoNone')}
            className="block rounded-card focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line disabled:cursor-default"
          >
            <Avatar src={chat.avatar} name={title} tone={chat.id} radius="rounded-card" className="w-20 h-20 text-[30px]" />
          </button>
        }
        compactAvatar={<Avatar src={chat.avatar} name={title} tone={chat.id} radius="rounded-control" className="w-8 h-8 text-footnote" />}
        title={title}
        kind={kind}
        description={chat.description}
        notes={notes}
        primary={mayAdd ? { key: 'invite', label: t('infoGroup.invite'), icon: <UserPlus {...ICO} />, onClick: () => setView('add'), testId: 'group-action-add' } : undefined}
        quick={quick}
        summary={summary}
        rows={infoRows}
        between={between}
        menu={menu}
        tabs={tabs}
        tab={tab}
        onTab={setInfoTab}
      >
        {tab === 'posts' ? (
          <ChannelPostsPanel posts={posts} pinnedId={chat.pinnedMessage?.id || undefined} onJump={jumpTo} />
        ) : tab === 'members' ? (
          membersPanel
        ) : histHidden && tab === 'media' && shared.media.length === 0 ? (
          <div className="px-4 py-12 flex flex-col items-center gap-2 text-center" data-testid="group-hist-hidden">
            <span className="w-11 h-11 rounded-full bg-raised text-ink-2 flex items-center justify-center">
              <Lock {...ICO} aria-hidden="true" />
            </span>
            <p className="text-subhead font-semibold text-ink">{t('infoStates.histHidden')}</p>
          </div>
        ) : (
          <SharedPanel
            shared={shared}
            tab={tab as SharedTab}
            whoSent={(msg) => (msg.isSelf || msg.senderId === selfId ? t('groupProfileSheet.you2') : msg.senderName || t('groupProfileSheet.memberLower'))}
            onJump={jumpTo}
            onOpenImage={(url) => useMessengerStore.getState().openLightbox(url, title)}
          />
        )}
      </InfoView>
      <AvatarViewerModal isOpen={faceOpen} onClose={() => setFaceOpen(false)} src={chat.avatar || null} name={title} kind="group" />
      <ReportSheetHost />
    </FormSheet>
  );
};

export default GroupProfileSheet;
