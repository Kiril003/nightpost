/**
 * Профіль людини — один екран і для телефона, і для ПК.
 *
 * Каркас InfoView (рендер p-info): обличчя, одна бурштинова «Написати» і
 * круглі швидкі дії, рядки (коло, місце, перевірка), між вами, памʼятати,
 * руйнівне одним блоком рядків, далі вкладки спільного. Заблокована людина —
 * стан PersonInfoState.
 */
import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Ban, Bell, BellOff, Eraser, Eye, Flag, Lock, MapPin, MessageCircle, Navigation, Phone, ShieldCheck, Trash2, User, Users } from 'lucide-react';
import { messengerApi } from '../../services/messengerApi';
import type { NodeContact } from '../../services/messengerApi';
import { verifiedTwin } from '../../services/nameTwin';
import { readPeerProfile, avatarSrc, valueOf } from '../../services/peerProfile';
import { indexShared, heardLine, SHARED_TABS, type SharedTab } from '../../services/peerShared';
import {
  balanceWith,
  factsAbout,
  loadMoments,
  nextPlanIn,
  openTasksIn,
  sharedMomentsWith,
  taskMeta,
  unitsText,
  type Moment,
} from '../../services/peerBetween';
import { chatLedger } from '../../services/money/ledger';
import { phantomSelfApi, type KnownFact } from '../../services/phantomSelfApi';
import { nearWord, wherePeerOf, whereApi, onWhereChanged, type WhereState } from '../../services/peerWhere';
import { callGate } from '../../services/callGate';
import { useIceRoads } from '../../hooks/useIceRoads';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { soundFx } from '../../utils/messengerSound';
import { avatarLetters, personTone } from './Avatar';
import { AvatarViewerModal } from './AvatarViewerModal';
import { BannerCarousel, bannerShown } from './BannerCarousel';
import { PrimaryButton, useFocusTrap } from './FormSheet';
import { SafetyVerifyBlock, fingerprintGrid } from './VerifyContact';
import { isPhoneShell } from './phoneShell';
import { SharedPanel } from './SharedSection';
import { InfoRows, InfoView, type InfoAction, type InfoMenuItem, type InfoPrimary, type InfoRowSpec, type InfoTab } from './InfoView';
import { PersonInfoState } from './PersonInfoState';
import { CircleSheet } from './CircleSheet';
import { addedLine, handleLabel } from '../../services/peopleIdentity';
import { isUnnamed } from './chatListFormat';
import { canHaveCircle, useCircleLabels } from './circles';
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { pluralKey } from '../chat/tNow';
import { seenTogether } from '../../services/seenTogether';
import { GiftShelf, GiftViewSheet } from './gifts/GiftShelf';
import { receivedGifts, type ShelfGift } from './gifts/giftCatalogue';
import { useSupportStore } from '../../stores/supportStore';
import { ReportSheetHost } from '../support/ReportSheet';
import type { Message } from '../../types/messenger';
import { PhantomGlyph } from './PhantomMark';
import { personAsk } from '../../services/askContext';
import { openAsk } from '../../stores/askSheetStore';

export interface PeerProfileSheetProps {
  peerNodeId?: string;
  name: string;
  avatar?: string;
  /** Розмова вдвох із людиною, якщо вона вже є. */
  chatId?: string;
  onClose: () => void;
  /** Пошук у розмові. Немає — рядка немає. */
  onSearch?: () => void;
  /** Поділитися своїм місцем у цю розмову. */
  onWhere?: () => void;
  /** Відкрити одразу на перевірці «це справді ця людина». */
  startOnCheck?: boolean;
  /** Відкрити/почати розмову, коли профіль відкрито не з неї. */
  onWrite?: () => void;
  onVerified?: (verified: boolean) => void;
  onRenamed?: (title: string) => void;
  onCleared?: () => void;
  onDeleted?: () => void;
  /** Колонка ПК: профіль лежить у потоці, без порталу, без «назад» (колонка має свій «×»). */
  inline?: boolean;
}

const SectionTitle: React.FC<{ children: React.ReactNode; aside?: React.ReactNode }> = ({ children, aside }) => (
  <div className="px-1 pb-2 flex items-baseline justify-between gap-3">
    <h3 className="text-title-2 text-ink">{children}</h3>
    {aside}
  </div>
);

export const factDay = (iso: string, locale: string): string => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(dateTag(locale), { day: 'numeric', month: 'long' });
};

export const PeerProfileSheet: React.FC<PeerProfileSheetProps> = ({
  peerNodeId,
  name: nameProp,
  avatar,
  chatId,
  onClose,
  onSearch,
  onWhere,
  startOnCheck = false,
  onWrite,
  onVerified,
  onRenamed,
  onCleared,
  onDeleted,
  inline = false,
}) => {
  const { t, locale } = useTranslation();
  const layerId = useId();
  // Без фото постер — велика літера на кулі тону людини, як її аватар у списку.
  const tone = personTone(peerNodeId || chatId || nameProp);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`peer${layerId}`, !inline, () => closeRef.current()), [layerId, inline]);

  const [contact, setContact] = useState<NodeContact | null>(null);
  const [twin, setTwin] = useState<string | null>(null);
  const [securityOpen, setSecurityOpen] = useState(startOnCheck);
  const [verifying, setVerifying] = useState(false);
  const [viewerOpen, setViewerOpen] = useState(false);
  const [renaming, setRenaming] = useState(false);
  // Нове імʼя видно одразу, навіть коли викликач не оновлює свій стор (картка людини в листі).
  const [renamedTo, setRenamedTo] = useState<string | null>(null);
  useEffect(() => setRenamedTo(null), [nameProp]);
  const name = renamedTo ?? nameProp;
  // «Без імені» — не імʼя: поле перейменування відкривається порожнім.
  const ownName = isUnnamed(name) ? '' : name;
  const [nameDraft, setNameDraft] = useState(ownName);
  const [confirmKind, setConfirmKind] = useState<'clear' | 'delete' | 'block' | 'report' | 'call' | 'video' | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<boolean | null>(null);
  const chat = useMessengerStore((s) =>
    chatId ? s.chats.find((c) => c.id === chatId) : peerNodeId ? s.chats.find((c) => c.peerNodeId === peerNodeId) : undefined,
  );
  const handle = handleLabel(chat?.handle);
  const mine = useMessengerStore((s) => Boolean(chatId && s.chats.find((c) => c.id === chatId)?.mine));
  const circled = useMessengerStore((s) => {
    const c = chatId ? s.chats.find((x) => x.id === chatId) : undefined;
    return Boolean(c && canHaveCircle(c));
  });
  const toggleMine = async () => {
    if (!peerNodeId || busy) return;
    setBusy(true);
    try {
      await messengerApi.markMine(peerNodeId, !mine);
      await useMessengerStore.getState().refreshConversations();
    } catch {
      setNote(t('peerProfileSheet.failedRetry'));
    } finally {
      setBusy(false);
    }
  };
  const [sharedGroups, setSharedGroups] = useState<Array<{ id: string; title: string }> | null>(null);
  const [infoTab, setInfoTab] = useState<string>();
  const [facts, setFacts] = useState<KnownFact[]>([]);
  const [moments, setMoments] = useState<Moment[]>([]);
  const [where, setWhere] = useState<WhereState | null>(null);
  const [photoBroken, setPhotoBroken] = useState(false);

  const ice = useIceRoads();
  const phoneShell = __PHONE_BUILD__ || isPhoneShell();
  const selfId = useMessengerStore((s) => s.currentUser.id);
  const selfName = useMessengerStore((s) => s.currentUser.name);
  const chats = useMessengerStore((s) => s.chats);
  const muted = chat?.muted ?? false;
  // Дата додавання — тут, а не біля імені в шапці чи списку.
  const added = chat && chat.type !== 'group' ? addedLine(chat.addedAt) : null;
  const wherePeer = wherePeerOf(chat);

  useEffect(() => {
    let alive = true;
    setContact(null);
    setTwin(null);
    if (!peerNodeId) return;
    void (async () => {
      try {
        const all = await messengerApi.listContacts();
        const rows = all.filter((c) => c.peer_node_id === peerNodeId);
        if (!alive) return;
        setTwin(verifiedTwin(peerNodeId, rows[0]?.display_name || name, all));
        // Тривога виграє: з двох рядків на один вузол — той, де ключ змінився.
        const found = rows.find((c) => c.key_changed) ?? rows[0] ?? null;
        setContact(found);
        if (found && typeof found.blocked === 'boolean') setBlocked(found.blocked);
        else {
          const list = await messengerApi.listBlocked().catch(() => null);
          if (alive && list) setBlocked(list.blocked.includes(peerNodeId));
        }
      } catch {
        /* вузол мовчить — людина лишається з іменем і діями */
      }
    })();
    return () => {
      alive = false;
    };
  }, [peerNodeId]);

  useEffect(() => {
    if (!peerNodeId) return;
    let alive = true;
    void (async () => {
      try {
        const groups = (await messengerApi.listConversations()).filter((c) => c.kind === 'group');
        const rows = await Promise.allSettled(groups.map((g) => messengerApi.groupMembers(g.id)));
        if (!alive) return;
        const shared: Array<{ id: string; title: string }> = [];
        rows.forEach((r, i) => {
          if (r.status === 'fulfilled' && r.value.members.some((m) => m.id === peerNodeId)) {
            shared.push({ id: groups[i].id, title: r.value.title || groups[i].title });
          }
        });
        setSharedGroups(shared);
      } catch {
        if (alive) setSharedGroups(null);
      }
    })();
    return () => {
      alive = false;
    };
  }, [peerNodeId]);

  // Памʼять PHANTOM — лише те, де людину згадано; немає — розділу немає.
  useEffect(() => {
    let alive = true;
    const t = window.setTimeout(() => {
      phantomSelfApi
        .facts()
        .then((all) => alive && setFacts(factsAbout(Array.isArray(all) ? all : [], name)))
        .catch(() => alive && setFacts([]));
    }, 0);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
  }, [name]);

  useEffect(() => {
    if (!peerNodeId) {
      setMoments([]);
      return;
    }
    let alive = true;
    const timer = window.setTimeout(() => {
      loadMoments(peerNodeId, selfId)
        .then((all) => {
          if (!alive) return;
          const next = sharedMomentsWith(Array.isArray(all) ? all : [], selfId, peerNodeId);
          setMoments((prev) => (prev.length === 0 && next.length === 0 ? prev : next));
        })
        .catch(() => {
          if (!alive) return;
          setMoments((prev) => (prev.length === 0 ? prev : []));
        });
    }, 0);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [selfId, peerNodeId]);

  const wherePeerId = wherePeer?.id ?? null;
  useEffect(() => {
    if (!wherePeerId) return;
    let alive = true;
    const read = () => {
      whereApi.get(wherePeerId).then(
        (st) => alive && setWhere(st),
        () => alive && setWhere(null),
      );
    };
    read();
    const off = onWhereChanged(read);
    return () => {
      alive = false;
      off();
    };
  }, [wherePeerId]);

  const view = contact ? readPeerProfile(contact) : null;
  const photo = (view && avatarSrc(view)) || avatar || null;
  useEffect(() => {
    setPhotoBroken(false);
  }, [photo, peerNodeId]);
  const photoUrl = photo && !photoBroken && /^(https?:\/\/|data:|blob:|\/|\.\/)/i.test(photo.trim()) ? photo.trim() : null;
  const status = view ? [valueOf(view.statusEmoji), valueOf(view.statusText)].filter(Boolean).join(' ') : '';
  const bio = view ? valueOf(view.bio) : null;
  const heard = heardLine(contact?.heard_at, contact?.lan_seen_at);
  const theirs = where?.theirs ?? null;
  const nearNow = theirs && (theirs.near === 'same' || theirs.near === 'near') ? nearWord(theirs.near, theirs.precision) : heard === t('shared.heardNear') ? t('peerProfileSheet.nearby') : null;
  const shared = useMemo(() => indexShared(chat?.messages ?? [], selfId), [chat?.messages, selfId]);
  const tasks = useMemo(() => openTasksIn(chat?.messages ?? [], selfName, name), [chat?.messages, selfName, name]);
  const ledger = useMemo(() => chatLedger(chat?.messages ?? [], selfId), [chat?.messages, selfId]);
  const balance = useMemo(() => (peerNodeId ? balanceWith(ledger, selfId, peerNodeId) : []), [ledger, selfId, peerNodeId]);
  const next = useMemo(() => nextPlanIn(tasks), [tasks]);
  const restTasks = useMemo(() => (next ? tasks.filter((item) => item.key !== next.key) : tasks), [tasks, next]);
  const lastBillMsgId = useMemo(() => {
    const msgs = chat?.messages ?? [];
    for (let i = msgs.length - 1; i >= 0; i -= 1) {
      if (msgs[i] && !msgs[i].isDeleted && msgs[i].billData) return msgs[i].id;
    }
    return null;
  }, [chat?.messages]);
  const seen = useMemo(() => (peerNodeId ? seenTogether(chats, selfId, peerNodeId) : null), [chats, selfId, peerNodeId]);
  const gifts = useMemo(() => (chat ? receivedGifts([chat], selfId) : []), [chat, selfId]);
  const [giftOpen, setGiftOpen] = useState<ShelfGift | null>(null);
  const verifiedNow = Boolean(contact?.verified && !contact?.key_changed);
  const grid = fingerprintGrid(contact?.fingerprint || contact?.safety_number);

  const calling = confirmKind === 'call' || confirmKind === 'video';
  const gate = callGate({ roads: ice, phoneShell, hasPeer: Boolean(peerNodeId || contact?.id), isGroup: false });

  const jumpTo = (msgId: string) => {
    if (!chat) return;
    soundFx.playTap();
    useMessengerStore.getState().openChatAtMessage(chat.id, msgId);
    onClose();
  };

  const write = () => {
    if (onWrite) {
      onWrite();
      return;
    }
    onClose();
    window.setTimeout(() => document.querySelector<HTMLTextAreaElement>('[data-composer-input]')?.focus(), 0);
  };

  const call = (video: boolean) => {
    if (!gate.can) {
      useUIStore.getState().toast({ kind: 'warn', message: gate.why });
      return;
    }
    window.dispatchEvent(
      new CustomEvent('phantom:start-call', {
        detail: { peerNodeId, contactId: contact?.id, displayName: name, verified: contact?.verified ?? false, video },
      }),
    );
    onClose();
  };

  const showTheirs = () => {
    if (!wherePeerId) return;
    whereApi.openMap(wherePeerId).catch(() => useUIStore.getState().toast({ kind: 'warn', message: t('peerProfileSheet.placeStopped', { name }) }));
  };

  const saveRename = async () => {
    const trimmed = nameDraft.trim();
    if (!trimmed || trimmed === name) {
      setRenaming(false);
      return;
    }
    if (!chatId) {
      setNote(t('peerProfileSheet.renameFailed'));
      return;
    }
    setBusy(true);
    setNote(null);
    try {
      await messengerApi.renameConversation(chatId, trimmed);
      setRenamedTo(trimmed);
      onRenamed?.(trimmed);
      setRenaming(false);
    } catch {
      setNote(t('peerProfileSheet.renameFailed'));
    } finally {
      setBusy(false);
    }
  };

  const runConfirmed = async () => {
    if (!confirmKind) return;
    setBusy(true);
    setNote(null);
    try {
      if (confirmKind === 'report') {
        if (!peerNodeId) return;
        const done = await messengerApi.reportPeer(peerNodeId);
        setBlocked(done.blocked);
        setConfirmKind(null);
        if (!done.mailed) setNote(t('peerProfileSheet.noMailApp', { address: done.address }));
        return;
      }
      if (confirmKind === 'block') {
        if (!peerNodeId) return;
        const next = blocked ? await messengerApi.unblockPeer(peerNodeId) : await messengerApi.blockPeer(peerNodeId);
        setBlocked(next.blocked);
        setConfirmKind(null);
        return;
      }
      if (!chatId) return;
      if (confirmKind === 'clear') {
        await messengerApi.clearConversation(chatId);
        onCleared?.();
      } else {
        await messengerApi.deleteConversation(chatId);
        onDeleted?.();
      }
      onClose();
    } catch {
      setNote(
        confirmKind === 'report'
          ? t('peerProfileSheet.reportUnsupported')
          : confirmKind === 'block'
          ? t('peerProfileSheet.blockUnsupported')
          : confirmKind === 'clear'
          ? t('peerProfileSheet.clearFailed')
          : t('peerProfileSheet.deleteFailed'),
      );
    } finally {
      setBusy(false);
    }
  };

  const ICON = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;
  const primary: InfoPrimary | undefined =
    chatId || onWrite
      ? { key: 'write', label: t('peerProfileSheet.message'), icon: <MessageCircle {...ICON} />, onClick: write, testId: 'peer-action-write' }
      : undefined;
  const quick: InfoAction[] = [
    ...(gate.can
      ? [{ key: 'call', label: t('peerProfileSheet.call'), icon: <Phone {...ICON} />, onClick: () => setConfirmKind('call'), testId: 'peer-action-call' }]
      : []),
    {
      key: 'ask',
      label: t('ask.personAction'),
      icon: <PhantomGlyph className="w-5 h-5" />,
      onClick: () =>
        openAsk(
          personAsk(
            { name, messages: chat?.messages ?? [], meId: selfId, promises: tasks.filter((x) => x.who === 'self').map((x) => x.title) },
            { t, locale },
          ),
        ),
      testId: 'peer-action-ask',
    },
    ...(chatId
      ? [
          {
            key: 'mute',
            label: muted ? t('peerProfileSheet.sound') : t('peerProfileSheet.muted'),
            icon: muted ? <Bell {...ICON} /> : <BellOff {...ICON} />,
            onClick: () => useMessengerStore.getState().toggleMuteChat(chatId),
            testId: 'peer-action-mute',
          },
        ]
      : []),
  ];
  const startRename = () => {
    setNameDraft(ownName);
    setRenaming(true);
  };

  // Колонка ПК: Escape скасовує перейменування чи підтвердження, а не закриває колонку.
  useEscapeClose(renaming || confirmKind !== null, () => {
    if (renaming) setRenaming(false);
    else setConfirmKind(null);
  });
  useEscapeClose(!inline && !renaming && confirmKind === null, () => closeRef.current());
  const layerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(layerRef, !inline);

  const betweenNodes: Array<{ key: string; node: React.ReactNode }> = [];
  if (next) {
    betweenNodes.push({
      key: `next:${next.key}`,
      node: (
        <button
          type="button"
          data-testid="peer-between-next"
          onClick={() => jumpTo(next.msgId)}
          className="w-full min-h-12 py-2.5 px-3.5 flex items-center gap-3 text-left"
        >
          <span
            aria-hidden="true"
            className={`w-[22px] h-[22px] shrink-0 rounded-full border-2 ${next.who === 'self' ? 'border-accent-line' : 'border-f-ink-3'}`}
          />
          <span className="flex-1 min-w-0">
            <span className="block text-caption text-ink-3">{t('profile33.next')}</span>
            <span className="block text-callout text-ink break-words">{next.title}</span>
            {taskMeta(next) && <span className="block text-caption text-ink-3 truncate">{taskMeta(next)}</span>}
          </span>
        </button>
      ),
    });
  }
  balance.forEach((b) => {
    const amount = unitsText(b.units, b.token);
    const label = b.pending
      ? t('profile33.pending', { amount })
      : b.dir === 'owe'
      ? t('profile33.youOwe', { amount })
      : t('profile33.owedToYou', { amount });
    const toneClass = b.pending ? 'text-ink-3' : 'text-ink';
    betweenNodes.push({
      key: `bal:${b.token}:${b.pending ? 'p' : 'o'}`,
      node: lastBillMsgId ? (
        <button
          type="button"
          data-testid="peer-between-balance"
          onClick={() => jumpTo(lastBillMsgId)}
          className={`w-full min-h-12 py-2.5 px-3.5 flex items-center text-left text-callout ${toneClass}`}
        >
          {label}
        </button>
      ) : (
        <div
          data-testid="peer-between-balance"
          className={`w-full min-h-12 py-2.5 px-3.5 flex items-center text-callout ${toneClass}`}
        >
          {label}
        </div>
      ),
    });
  });
  if (moments.length > 0) {
    betweenNodes.push({
      key: 'moments',
      node: (
        <div
          data-testid="peer-between-moments"
          className="w-full min-h-12 py-2.5 px-3.5 flex items-center justify-between gap-3 text-callout text-ink"
        >
          <span className="truncate">{t('profile33.moments')}</span>
          <span className="shrink-0 text-ink-3 tnum">{moments.length}</span>
        </div>
      ),
    });
  }
  restTasks.slice(0, 5).forEach((task) => {
    betweenNodes.push({
      key: task.key,
      node: (
        <button
          type="button"
          onClick={() => jumpTo(task.msgId)}
          className="w-full min-h-12 py-2.5 px-3.5 flex items-center gap-3 text-left"
        >
          <span
            aria-hidden="true"
            className={`w-[22px] h-[22px] shrink-0 rounded-full border-2 ${task.who === 'self' ? 'border-accent-line' : 'border-f-ink-3'}`}
          />
          <span className="flex-1 min-w-0">
            <span className="block text-callout text-ink break-words">{task.title}</span>
            {taskMeta(task) && <span className="block text-caption text-ink-3 truncate">{taskMeta(task)}</span>}
          </span>
        </button>
      ),
    });
  });

  const circleLabels = useCircleLabels(chatId ?? '');
  const [circleOpen, setCircleOpen] = useState(false);
  const rows: InfoRowSpec[] = [];
  if (chatId && circled) {
    rows.push({ key: 'circle', icon: <Users {...ICON} />, label: t('circles.who'), aside: circleLabels.join(' · ') || undefined, onClick: () => setCircleOpen(true), testId: 'circle-row' });
  }
  if (theirs) {
    rows.push({ key: 'theirs', icon: <MapPin {...ICON} />, label: t('peerProfileSheet.onMap'), onClick: showTheirs, testId: 'peer-action-theirs' });
  } else if (onWhere && contact) {
    rows.push({
      key: 'where',
      icon: <Navigation {...ICON} />,
      label: t('peerProfileSheet.theirPlace'),
      onClick: () => {
        onClose();
        onWhere();
      },
      testId: 'peer-action-where',
    });
  }
  if (seen) {
    rows.push({
      key: 'seen',
      icon: <Eye {...ICON} />,
      label: t('seen.title'),
      aside: `${t(pluralKey(locale, seen.count, 'seen.times'), { n: seen.count })} · ${t('seen.last', { date: new Intl.DateTimeFormat(dateTag(locale), { day: 'numeric', month: 'long' }).format(seen.lastAt) })}`,
      testId: 'peer-profile-seen',
    });
  }
  if (contact && !contact.key_changed) {
    rows.push({
      key: 'verify',
      icon: <ShieldCheck {...ICON} />,
      label: verifiedNow ? t('peerProfileSheet.verified') : t('peerProfileSheet.verifyIdentity'),
      onClick: () => setSecurityOpen((v) => !v),
      testId: 'peer-row-verify',
    });
  }

  const report = () =>
    useSupportStore.getState().openReport({
      targetKind: 'person',
      targetId: peerNodeId || chatId || name,
      targetName: name,
      chatId,
      senderKey: peerNodeId || contact?.id,
      peerId: peerNodeId,
    });
  const dangerRows: InfoRowSpec[] = [
    ...(chatId ? [{ key: 'clear', icon: <Eraser {...ICON} />, label: t('peerProfileSheet.clearHistory'), inPlace: true, onClick: () => setConfirmKind('clear') }] : []),
    ...(peerNodeId
      ? [{ key: 'block', icon: <Ban {...ICON} />, label: blocked ? t('peerProfileSheet.unblock') : t('peerProfileSheet.block'), danger: true, inPlace: true, onClick: () => setConfirmKind('block') }]
      : []),
    ...(peerNodeId || chatId ? [{ key: 'report', icon: <Flag {...ICON} />, label: t('peerProfileSheet.report'), onClick: report }] : []),
    ...(chatId ? [{ key: 'delete', icon: <Trash2 {...ICON} />, label: t('peerProfileSheet.deleteChat'), danger: true, inPlace: true, onClick: () => setConfirmKind('delete') }] : []),
  ];

  const menu: InfoMenuItem[] = [
    ...(onSearch
      ? [
          {
            key: 'search',
            label: t('groupProfileSheet.search'),
            onClick: () => {
              onClose();
              onSearch();
            },
          },
        ]
      : []),
    ...(chatId ? [{ key: 'rename', label: t('peerProfileSheet.edit'), onClick: startRename }] : []),
    ...(gate.can ? [{ key: 'video', label: t('peerProfileSheet.video'), onClick: () => setConfirmKind('video') }] : []),
    ...(chatId && peerNodeId && mine ? [{ key: 'mine', label: t('peerProfileSheet.notMine'), onClick: () => void toggleMine() }] : []),
  ];

  const tabs: InfoTab[] = [
    ...SHARED_TABS.map((x) => ({ key: x.key, label: x.label, count: shared[x.key].length })),
    { key: 'groups', label: t('infoStates.sharedGroupsTab'), count: sharedGroups?.length ?? 0 },
  ];
  // Спільного немає зовсім — вкладок немає, а не «Медіа» з нулем.
  const anyShared = tabs.some((x) => (x.count ?? 0) > 0);
  const tab = infoTab ?? tabs.find((x) => (x.count ?? 0) > 0)?.key ?? 'media';
  const whoSent = (msg: Message) => (msg.isSelf || msg.senderId === selfId ? t('peerProfileSheet.you') : name);
  const openImage = (url: string) => useMessengerStore.getState().openLightbox(url, name);
  const openGroup = (id: string) => {
    useMessengerStore.getState().setActiveChat(id);
    onClose();
  };

  const nameless = isUnnamed((name || '').trim()) || avatarLetters(name) === '?';
  const face = (big: boolean) =>
    photoUrl ? (
      <img src={photoUrl} alt={big ? name : ''} onError={() => setPhotoBroken(true)} className={`${big ? 'w-20 h-20' : 'w-8 h-8'} rounded-full object-cover object-[50%_28%]`} />
    ) : (
      <span
        aria-hidden="true"
        data-testid={big ? 'peer-profile-initial' : undefined}
        data-nameless={big && nameless ? '' : undefined}
        className={`${big ? 'w-20 h-20 text-[30px]' : 'w-8 h-8 text-footnote'} rounded-full flex items-center justify-center font-semibold`}
        style={{ color: tone.ink, backgroundColor: tone.fill }}
      >
        {nameless ? <User className="w-[46%] h-[46%]" strokeWidth={1.75} aria-hidden /> : avatarLetters(name)}
      </span>
    );
  const banner = bannerShown(contact?.banner);
  const presence = blocked ? t('peerProfileSheet.blocked') : nearNow ?? heard ?? '';
  const kind = [handle, presence].filter(Boolean).join(' · ');

  const notes = (
    <>
      {renaming && (
        <form
          className="px-4 pt-3 flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void saveRename();
          }}
        >
          <input
            autoFocus
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value)}
            aria-label={t('peerProfileSheet.nicknameLabel')}
            placeholder={t('peerProfileSheet.nicknamePlaceholder')}
            className="flex-1 min-w-0 h-12 px-3 rounded-control bg-raised border border-line-strong text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
          />
          <button type="submit" disabled={busy} className="h-12 px-4 rounded-control bg-raised text-headline text-ink">
            {t('peerProfileSheet.done')}
          </button>
        </form>
      )}
      {renaming && note && (
        <p className="px-4 pt-2 text-callout text-danger" data-testid="peer-profile-rename-note">{note}</p>
      )}
      {status && (
        <p className="px-4 pt-2 text-callout text-ink-2 break-words" data-testid="peer-profile-status">
          {status}
        </p>
      )}
      {bio && (
        <p className="px-4 pt-2 text-body text-ink whitespace-pre-wrap break-words" data-testid="peer-profile-bio">
          {bio}
        </p>
      )}
      {added && (
        <p className="px-4 pt-1 text-footnote text-ink-3 truncate" data-testid="peer-profile-added">
          {added}
        </p>
      )}
      {twin && !renaming && (
        <p className="px-4 pt-1 text-callout text-ink-2 flex items-center gap-2" data-testid="peer-profile-twin">
          <span className="truncate">{t('peerProfileSheet.twin', { name: twin })}</span>
          <button type="button" data-testid="peer-profile-twin-verify" onClick={() => setSecurityOpen(true)} className="shrink-0 min-h-12 min-w-12 px-2 text-ink font-semibold underline">
            {t('peerProfileSheet.verify')}
          </button>
        </p>
      )}
    </>
  );

  const between = (
    <>
      {calling && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-surface" data-testid="peer-call-confirm">
          <p className="text-callout text-ink-2">{t(confirmKind === 'video' ? 'peerProfileSheet.confirmVideo' : 'peerProfileSheet.confirmCall')}</p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button type="button" onClick={() => setConfirmKind(null)} className="min-h-12 rounded-full bg-raised text-headline text-ink-2">
              {t('peerProfileSheet.cancel')}
            </button>
            <button type="button" data-testid="peer-call-go" onClick={() => call(confirmKind === 'video')} className="min-h-12 rounded-full bg-accent text-headline text-on-accent">
              {t('peerProfileSheet.callAction')}
            </button>
          </div>
        </div>
      )}

      {/* Голос безпеки — лише коли є що сказати: людина змінила телефон. */}
      {contact?.key_changed && !securityOpen && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-surface" data-testid="peer-profile-key-changed">
          <p className="text-callout text-ink">{t('peerProfileSheet.keyChanged', { name })}</p>
          <button type="button" onClick={() => setSecurityOpen(true)} className="mt-3 h-11 px-4 rounded-full bg-raised text-footnote font-semibold text-ink">
            {t('peerProfileSheet.verify')}
          </button>
        </div>
      )}

      {securityOpen && contact && (
        <div className="mx-4 mt-3 p-4 rounded-card bg-surface" data-testid="peer-profile-verify">
          <p className="text-callout text-ink-2">{t('peerProfileSheet.compareNumbers', { name })}</p>
          {grid.length > 0 && (
            <div className="mt-3 grid grid-cols-4 gap-x-5 gap-y-0.5 text-callout text-ink w-fit" data-testid="contact-fingerprint">
              {grid.flat().map((g, i) => (
                <span key={i} className="tnum">
                  {g}
                </span>
              ))}
            </div>
          )}
          {!verifiedNow && !verifying && !contact.key_changed && (
            <div className="mt-4">
              <PrimaryButton onClick={() => setVerifying(true)}>{t('peerProfileSheet.numbersMatch')}</PrimaryButton>
            </div>
          )}
          {(verifying || contact.key_changed) && (
            <div className="mt-4">
              <SafetyVerifyBlock
                contact={contact}
                startOpen
                onVerified={(updated) => {
                  setContact(updated);
                  setVerifying(false);
                  onVerified?.(updated.verified);
                }}
                onDismiss={() => setVerifying(false)}
              />
            </div>
          )}
        </div>
      )}

      {/* ── між вами: відкриті справи з чек-листів розмови ───────── */}
      {betweenNodes.length > 0 && (
        <section className="px-4 pt-5" data-testid="peer-profile-between">
          <SectionTitle>{t('peerProfileSheet.betweenYou')}</SectionTitle>
          <ul className="rounded-card bg-surface">
            {betweenNodes.map((row, i) => (
              <li key={row.key}>
                {i > 0 && <div aria-hidden="true" className="h-px bg-line ml-12" />}
                {row.node}
              </li>
            ))}
          </ul>
        </section>
      )}

      {gifts.length > 0 && (
        <section className="px-4 pt-5" data-testid="peer-profile-gifts">
          <SectionTitle>{`${t('gifts.shelf')} · ${gifts.length}`}</SectionTitle>
          <GiftShelf
            items={gifts}
            showFrom={false}
            onOpen={(g) => {
              soundFx.playTap();
              setGiftOpen(g);
            }}
          />
          <GiftViewSheet
            gift={giftOpen}
            onClose={() => setGiftOpen(null)}
            onChat={(g) => {
              setGiftOpen(null);
              jumpTo(g.msgId);
            }}
          />
        </section>
      )}

      {/* ── памʼятати: згадки в памʼяті PHANTOM ─────────────────── */}
      {facts.length > 0 && (
        <section className="px-4 pt-5" data-testid="peer-profile-remember">
          <SectionTitle
            aside={
              <span className="text-caption text-ink-3 flex items-center gap-1.5">
                <Lock className="w-3 h-3" strokeWidth={2.4} aria-hidden="true" />
                {t('peerProfileSheet.onlyYou')}
              </span>
            }
          >
            {t('peerProfileSheet.remember')}
          </SectionTitle>
          <div className="grid grid-cols-2 gap-2">
            {facts.slice(0, 6).map((f) => (
              <div key={f.id} className={`px-3.5 py-3 rounded-control bg-surface ${f.text.length > 16 ? 'col-span-2' : ''}`}>
                {factDay(f.at, locale) && <p className="text-caption text-ink-3">{factDay(f.at, locale)}</p>}
                <p className="mt-0.5 text-headline text-ink break-words">{f.text}</p>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* ── руйнівне: один блок рядків зі значками ──────────────── */}
      {confirmKind === null || calling ? (
        dangerRows.length > 0 && (
          <div className="mt-3 border-t border-line" data-testid="peer-profile-danger">
            <InfoRows rows={dangerRows} />
          </div>
        )
      ) : (
        <div className="mx-4 mt-3 p-4 rounded-card bg-surface" data-testid="peer-profile-confirm">
          <p className="text-callout text-ink-2">
            {confirmKind === 'report'
              ? t('peerProfileSheet.confirmReport', { name })
              : confirmKind === 'block'
              ? blocked
                ? t('peerProfileSheet.confirmUnblock', { name })
                : t('peerProfileSheet.confirmBlock', { name })
              : confirmKind === 'clear'
              ? t('peerProfileSheet.confirmClear')
              : t('peerProfileSheet.confirmDelete')}
          </p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button type="button" onClick={() => void runConfirmed()} disabled={busy} className="min-h-12 px-3 rounded-full bg-raised text-headline text-danger">
              {busy
                ? t('peerProfileSheet.working')
                : confirmKind === 'report'
                ? t('peerProfileSheet.reportAndBlock')
                : confirmKind === 'block'
                ? blocked
                  ? t('peerProfileSheet.unblockAction')
                  : t('peerProfileSheet.blockAction')
                : confirmKind === 'clear'
                ? t('peerProfileSheet.clear')
                : t('peerProfileSheet.delete')}
            </button>
            <button type="button" onClick={() => setConfirmKind(null)} className="min-h-12 px-3 rounded-full bg-raised text-headline text-ink">
              {t('peerProfileSheet.cancel')}
            </button>
          </div>
        </div>
      )}

      {note && !renaming && <p className="px-4 pt-3 text-callout text-danger">{note}</p>}
    </>
  );

  const body =
    blocked === true && confirmKind === null ? (
      <PersonInfoState
        state="blocked"
        name={name}
        handle={chat?.handle ?? null}
        avatar={photoUrl}
        peerId={peerNodeId}
        tone={inline ? 'surface' : 'canvas'}
        shared={shared}
        sharedGroups={sharedGroups ?? []}
        whoSent={whoSent}
        onBack={onClose}
        onUnblock={
          peerNodeId
            ? () => {
                messengerApi.unblockPeer(peerNodeId).then(
                  (next) => setBlocked(next.blocked),
                  () => setNote(t('peerProfileSheet.blockUnsupported')),
                );
              }
            : undefined
        }
        onReport={report}
        onDeleteChat={
          chatId
            ? async () => {
                await messengerApi.deleteConversation(chatId);
                onDeleted?.();
                onClose();
              }
            : undefined
        }
        onSearch={onSearch ? () => (onClose(), onSearch()) : undefined}
        onSelectGroup={openGroup}
        onJump={jumpTo}
        onOpenImage={openImage}
      />
    ) : (
      <InfoView
        testId="peer-info"
        tone={inline ? 'surface' : 'canvas'}
        onBack={onClose}
        cover={
          banner ? (
            <div data-testid="peer-profile-poster" data-banner="">
              <BannerCarousel banner={contact!.banner!} peerId={peerNodeId} />
            </div>
          ) : undefined
        }
        avatar={
          <span className="block" data-testid={banner ? undefined : 'peer-profile-poster'}>
            <button
              type="button"
              data-testid="peer-profile-photo"
              disabled={!photoUrl}
              onClick={() => setViewerOpen(true)}
              aria-label={t('peerProfileSheet.photoFull')}
              className="block rounded-full focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line disabled:cursor-default"
            >
              {face(true)}
            </button>
          </span>
        }
        compactAvatar={face(false)}
        title={name}
        titleTestId="peer-profile-name"
        kind={kind}
        notes={notes}
        primary={primary}
        quick={quick}
        rows={rows}
        between={between}
        menu={menu}
        tabs={anyShared ? tabs : []}
        tab={tab}
        onTab={setInfoTab}
      >
        {!anyShared ? null : tab === 'groups' ? (
          !sharedGroups || sharedGroups.length === 0 ? (
            <p className="px-4 py-12 text-center text-subhead text-ink-2" data-testid="peer-groups-empty">
              {t('infoStates.noSharedGroups')}
            </p>
          ) : (
            <ul data-testid="peer-profile-groups">
              {sharedGroups.map((g, i) => (
                <li key={g.id} className={i > 0 ? 'border-t border-line' : ''}>
                  <button
                    type="button"
                    onClick={() => {
                      soundFx.playTap();
                      openGroup(g.id);
                    }}
                    className="w-full min-h-14 px-4 py-2 flex items-center gap-3.5 text-left active:bg-raised"
                  >
                    <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
                      <Users {...ICON} aria-hidden="true" />
                    </span>
                    <span className="flex-1 min-w-0 truncate text-headline text-ink">{g.title}</span>
                  </button>
                </li>
              ))}
            </ul>
          )
        ) : (
          <SharedPanel shared={shared} tab={tab as SharedTab} whoSent={whoSent} onJump={jumpTo} onOpenImage={openImage} />
        )}
      </InfoView>
    );

  const sheet = (
    <>
      <div
        ref={layerRef}
        tabIndex={inline ? undefined : -1}
        role={inline ? undefined : 'dialog'}
        aria-modal={inline ? undefined : true}
        aria-label={t('peerProfileSheet.profileOf', { name })}
        data-testid="peer-profile"
        className={inline ? 'relative flex-1 min-h-0 font-sans' : 'f-dialog fixed inset-0 z-[80] outline-none font-sans f-fade-in'}
      >
        {/* Старе правило фарбує кожен [role=dialog]; полотно — на внутрішньому шарі. */}
        <div
          className={`absolute inset-0 ${inline ? 'bg-surface' : 'bg-canvas pt-[var(--sat,0px)]'} text-ink overflow-y-auto no-scrollbar pb-[calc(var(--sab,0px)+16px)]`}
          data-peer-scroll
        >
          {body}
        </div>
      </div>

      {circleOpen && chatId && <CircleSheet chatId={chatId} open onClose={() => setCircleOpen(false)} />}
      <AvatarViewerModal isOpen={viewerOpen} onClose={() => setViewerOpen(false)} src={photo} name={name} bytes={view?.avatarBytes ?? 0} />
      <ReportSheetHost onBlocked={(b) => setBlocked(b)} />
    </>
  );

  return inline ? sheet : createPortal(sheet, document.body);
};

export default PeerProfileSheet;
