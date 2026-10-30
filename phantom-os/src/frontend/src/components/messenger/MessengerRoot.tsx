import React, { Suspense, lazy, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { MessageCircle } from 'lucide-react';
import { useProfiles } from '../../stores/profilesStore';
import { useShallow } from 'zustand/react/shallow';
import { useMessengerStore, type MessengerState } from '../../stores/messengerStore';
import { phantomRelayService } from '../../services/phantomRelayService';
import { wsClient } from '../../services/websocket';
import { messengerNetworkEngine } from '../../services/messengerNetworkEngine';
import { OUTBOX_SCAN_MS, outboxDue } from '../../services/outboxRetryPolicy';
import type { NetworkDiagnostics } from '../../types/messenger';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { ChatArea, lastPeerMessageOf, type ChatAreaHandle } from './ChatArea';
import { MessageComposer } from './MessageComposer';
import { KeyHoldBar } from './KeyHoldBar';
import { ReportPickBar } from './ReportPickBar';
import { StrangerBar } from './StrangerBar';
import { isRequest } from '../../services/chatFilters';
import { SelectionBar } from './SelectionBar';
import { ThreadView } from './ThreadView';
import { EduHost } from './directions/education/EduHost';
import { SpaceHost } from './spaces/SpaceHost';
import { isSpaceChat, openSpace } from '../../services/spaces/spaceNav';
import { WhereSheet } from './WhereCard';
import { WhereAskChips } from './WhereAskChips';
import { ChannelReadBar, GroupRoleProbe, readOnlyChannel, SlowModeNote } from './GroupComposerGate';
import { SolanaSheets } from './solana/SolanaSheets';
import { SettleUpHost } from './SettleUpSheet';
import { wherePeerOf } from '../../services/peerWhere';
import { forwardPayload } from '../../services/ringGuard';
import { typingReaches } from '../../services/typingSignal';
import { messengerApi } from '../../services/messengerApi';
import { useUIStore, type DeskPane } from '../../stores/uiStore';
import { LIST_SEARCH_CLOSE_EVENT, LIST_SEARCH_OPEN_EVENT, shownChatsForNav } from './PhoneChatList';

// Modals & Drawers
//
// «Місце» лишається статичним НЕ з недогляду: його поруч тягне стрічка, а
// вона в першому кадрі. Лінива межа тут не забрала б із першого шматка ані
// байта — статичне ребро в графі вже є. Подробиці листа, видалення й вибір
// реакції монтує сама `ChatArea` біля стрічки; другий монтаж тих самих вікон
// тут, у корені, відкривався лише дверима стору, до яких не вів жоден дотик.
import { LocationSheetModal } from './LocationSheetModal';

/**
 * Решта модалок кореня — за однією лінивою межею, в ОДНОМУ шматку.
 *
 * Усі сім `lazy()` вказують на той самий модуль (`modals/rootModals`),
 * тож rollup робить один файл, а не сім: сім окремих `import()` дали б сім
 * шматків — ту саму ваду, що вже була в `ModalHost` із його 57 показовими
 * модалками.
 *
 * Жодна з них не малюється, доки оператор її не відкриє, тож у першому
 * кадрі розмови їх бути не мусить.
 */
const rootModals = () => import('./modals/rootModals');
const CreateChatModal = lazy(() => rootModals().then((m) => ({ default: m.CreateChatModal })));
const ForwardMessageModal = lazy(() => rootModals().then((m) => ({ default: m.ForwardMessageModal })));
const GroupProfileSheet = lazy(() => rootModals().then((m) => ({ default: m.GroupProfileSheet })));
const MediaLightboxModal = lazy(() => rootModals().then((m) => ({ default: m.MediaLightboxModal })));
const ScheduledMessagesDrawer = lazy(() =>
  rootModals().then((m) => ({ default: m.ScheduledMessagesDrawer })),
);
const SettingsModal = lazy(() => rootModals().then((m) => ({ default: m.SettingsModal })));
const UserProfileModal = lazy(() => rootModals().then((m) => ({ default: m.UserProfileModal })));
const DeskSidePane = __PHONE_BUILD__ ? () => null : lazy(() => import('./DeskSidePane'));
if (!__PHONE_BUILD__) {
  try {
    const l = DeskSidePane as unknown as { _init?: (p: unknown) => unknown; _payload?: unknown };
    l._init?.(l._payload);
  } catch {
    /* preload DeskSidePane chunk */
  }
}
import { CallOverlay } from './CallOverlay';
import { isPhoneShell } from './phoneShell';

import { callEngine } from '../../services/callEngine';
import { useCallAlerts } from '../../hooks/useCallAlerts';
import { usePhoneBackStack } from '../../hooks/usePhoneBackStack';
import { useModalChrome } from '../../hooks/useModalChrome';
import { useCallChromeStore } from '../../stores/callChromeStore';
import { usePhoneLayerStore } from '../../stores/phoneLayerStore';
import { startThemeWatch } from '../../services/theme';
import { startLookWatch } from '../../services/appearance';
import { ChatWallpaper, useHasWallpaper } from './ChatWallpaper';
import { useSpaceStore } from '../../stores/spaceStore';
import { soundFx } from '../../utils/messengerSound';
import { installNativeOpenChat } from '../../services/nativeOpenChat';
import { installCallLog } from '../../services/callLog';
import { installNativeMeetupDraft } from '../../services/nativeMeetupDraft';
import { installMeetupExpirySweep } from '../../services/meetupExpirySweep';
import { installTempProfileEnd } from '../../services/tempProfileEnd';
import { MeetupDraftHost } from './meetup/MeetupDraftSheet';
import { HelpDoor } from '../support/HelpDoor';
import { GroupJoinDoor } from './GroupJoinDoor';
import { ShareInHost } from './ShareInSheet';
import { ProfilesHost } from './ProfilesSheet';
import { AskSheetHost } from './ask/AskSheet';
import { DirectionTabHost } from './directions/DirectionTabHost';
import { installIntentsHook } from '../../stores/intentsStore';
import { armReminders } from '../../services/letterReminders';
import type { UserProfile, ChatMember } from '../../types/messenger';
import { useTranslation, resolveLocale } from '../../i18n/useTranslation';
import { translate } from '../../i18n';
import { useSettingsStore, LANGUAGE_SETTING_KEY } from '../../stores/settingsStore';
import { startHushRulesSync } from '../../services/hushRulesSync';

interface MessengerRootProps {
  className?: string;
}

/**
 * Що з месенджер-стору корінь справді читає. Підписка — лише на ці поля
 * (з поверхневим порівнянням), а не на весь стор.
 *
 * Було `useMessengerStore()` без селектора: БУДЬ-ЯКИЙ тік стору — чужий
 * «друкує…», чернетка на кожну літеру, стан мережі — перемальовував корінь, а
 * з ним шапку, список розмов, стрічку й композер. Чернетки тут немає взагалі:
 * корінь знає лише, чи вона порожня (`hasDraft`), а сам текст композер тримає
 * в собі.
 */
const ROOT_FIELDS = [
  'chats',
  'activeChatId',
  'currentUser',
  'searchQuery',
  'scheduledMessages',
  'selectedMessageIds',
  'multiSelectMode',
  'selectionAnchorId',
  'pendingJump',
  'replyingTo',
  'editingMessage',
  'isCreateChatModalOpen',
  'isSettingsModalOpen',
  'isProfileModalOpen',
  'isMediaLightboxOpen',
  'activeLightboxUrl',
  'activeLightboxTitle',
  'isGroupDetailsOpen',
  'isScheduledDrawerOpen',
  'isForwardModalOpen',
  'activeForwardMessage',
  'activeForwardMessages',
  'activeLocationData',
] as const;
type RootField = (typeof ROOT_FIELDS)[number];
type RootSlice = Pick<MessengerState, RootField> & { hasDraft: boolean };

const selectRoot = (s: MessengerState): RootSlice => {
  const out = { hasDraft: Boolean(s.drafts[s.activeChatId]?.trim()) } as RootSlice;
  for (const k of ROOT_FIELDS) (out as Record<string, unknown>)[k] = s[k];
  return out;
};

/**
 * Літера чи цифра скорочення, не залежна від розкладки: латинська клавіша — як є,
 * інакше (кирилиця, AZERTY) — фізична клавіша з `e.code`.
 */
const shortcutOf = (e: KeyboardEvent): string => {
  const k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
  if (/^[a-z0-9]$/.test(k)) return k;
  const m = /^(?:Key([A-Z])|Digit([0-9]))$/.exec(e.code);
  if (m) return (m[1] ?? m[2]).toLowerCase();
  return e.code === 'Backslash' || k === '|' ? '\\' : k;
};

const isTypingTarget = (target: EventTarget | null): boolean =>
  target instanceof Element &&
  target.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]') !== null;

/** Option+↑/↓ у полі вводу лише на macOS рухає каретку по абзацах; на Windows і Linux Alt+стрілка там нічого не значить. */
const altArrowMovesCaret = (target: EventTarget | null): boolean =>
  typeof navigator !== 'undefined' && /^(Mac|iP)/i.test(navigator.platform) && isTypingTarget(target);

export const MessengerRoot: React.FC<MessengerRootProps> = ({ className = '' }) => {
  const { t } = useTranslation();
  const slice = useMessengerStore(useShallow(selectRoot));
  // Дії стору — стабільні функції; дані — лише з підписаного зрізу.
  const store = { ...useMessengerStore.getState(), ...slice };
  const retryOutbox = useMessengerStore((s) => s.retrySend);
  const activeChat = store.getActiveChat();
  const wherePeer = wherePeerOf(activeChat);
  const deskPane = useUIStore((s) => s.deskPane);
  const [whereOpen, setWhereOpen] = useState(false);
  const [scheduleSeed, setScheduleSeed] = useState('');
  const [, setStrangerTick] = useState(0);
  const scheduleDoneRef = useRef<(() => void) | null>(null);
  const [infoSubView, setInfoSubView] = useState<'profile' | 'where'>('profile');
  useEffect(() => {
    setWhereOpen(false);
    setInfoSubView('profile');
    const st = useMessengerStore.getState();
    if (!__PHONE_BUILD__ && !isPhoneShell() && st.activeLocationData) st.closeLocationSheet();
  }, [activeChat?.id]);
  // ПК: місце з будь-якого входу відкриває колонку Мапа; колонка закрилась чи змінилась — місце скинуте.
  useEffect(() => {
    if (!__PHONE_BUILD__ && !isPhoneShell() && store.activeLocationData) useUIStore.getState().setDeskPane('map');
  }, [store.activeLocationData]);
  const prevDeskPane = useRef(deskPane);
  useEffect(() => {
    const was = prevDeskPane.current;
    prevDeskPane.current = deskPane;
    const st = useMessengerStore.getState();
    if (was === 'map' && deskPane !== 'map' && st.activeLocationData) st.closeLocationSheet();
  }, [deskPane]);
  // Глушник шторки: відкрита розмова не шле сповіщень про власні листи.
  // Згорнута сторінка — жодна не видима.
  useEffect(() => installNativeOpenChat(), []);
  useEffect(() => installCallLog(callEngine), []);
  // Помічник телефона відкриває чернетку зустрічі (`__phantomOpenMeetupDraft`) — надсилає людина.
  useEffect(() => installNativeMeetupDraft(), []);
  // Тимчасові групи зустрічей: строк минув і ніхто не лишив — у місцевий архів.
  useEffect(() => installMeetupExpirySweep(), []);
  useEffect(() => installTempProfileEnd(), []);
  useEffect(() => installIntentsHook(), []);
  // Нагадування з листів: ті, що чекають, і ті, що пропустили, поки месенджер спав.
  useEffect(() => armReminders(), []);
  useEffect(() => startHushRulesSync(useMessengerStore), []);

  useEffect(() => {
    const tell = () =>
      messengerApi
        .markVisible(document.visibilityState === 'visible' ? activeChat?.id ?? null : null)
        .catch(() => undefined);
    void tell();
    document.addEventListener('visibilitychange', tell);
    return () => document.removeEventListener('visibilitychange', tell);
  }, [activeChat?.id]);
  const localLayers = usePhoneLayerStore((s) => s.layers);
  const callEnded = useSyncExternalStore(callEngine.subscribe, () => callEngine.getSnapshot().state === 'ended');
  // Тема — від моста, і вона одна на застосунок; ПК лишається ніччю.
  useEffect(() => startThemeWatch(), []);
  useEffect(() => startLookWatch(), []);
  const threadView = useSpaceStore((s) => s.threadView);
  const hasWallpaper = useHasWallpaper(activeChat?.id ?? '');
  // Порядок — z-порядок: вікна лежать поверх розмови, тож BACK знімає їх першими.
  // Розмову закриваємо станом, а не setActiveChat(''): той ще й тягне стрічку по порожньому id.
  // Список розмов — корінь (збірка 17): BACK із нього бере рідна оболонка
  // (застосунок іде у фон, як чекає Android); з розмови — назад у список.
  usePhoneBackStack(
    [
      { open: !!activeChat, close: () => useMessengerStore.setState({ activeChatId: '' }) },
      { open: store.isCreateChatModalOpen, close: () => store.setCreateChatModalOpen(false) },
      { open: store.isSettingsModalOpen, close: () => store.setSettingsModalOpen(false) },
      { open: store.isProfileModalOpen, close: () => store.setProfileModalOpen(false) },
      { open: store.isGroupDetailsOpen, close: () => store.setGroupDetailsOpen(false) },
      { open: store.isScheduledDrawerOpen, close: () => store.setScheduledDrawerOpen(false) },
      { open: store.isForwardModalOpen, close: () => store.closeForwardModal() },
      { open: !!store.activeLocationData, close: () => store.closeLocationSheet() },
      { open: !!threadView, close: () => useSpaceStore.getState().openThreadView(null) },
      ...Object.values(localLayers).map((close) => ({ open: true, close })),
      // Екран дзвінка, що не встав, лежить над усім: BACK закриває його першим.
      { open: callEnded, close: () => callEngine.dismiss() },
    ],
    isPhoneShell(),
  );
  const callCovers = useCallChromeStore((s) => s.covers);
  useModalChrome(
    callCovers ||
    store.isCreateChatModalOpen ||
      store.isSettingsModalOpen ||
      store.isProfileModalOpen ||
      store.isMediaLightboxOpen ||
      store.isGroupDetailsOpen ||
      store.isScheduledDrawerOpen ||
      store.isForwardModalOpen ||
      !!store.activeLocationData ||
      !!threadView,
  );
  const [isSearchingInChat, setIsSearchingInChat] = useState(false);
  const searchHomeId = store.activeChatId;
  useEffect(() => setIsSearchingInChat(false), [searchHomeId]);
  // Закладка закріпленого живе в шапці, а стрічка — в ChatArea: тримаємо ручку.
  const chatAreaRef = useRef<ChatAreaHandle>(null);

  // Sound settings
  const [isSoundEnabled, setIsSoundEnabled] = useState(true);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  // Вхідний дзвінок чутно й видно навіть тоді, коли вкладка не активна.
  useCallAlerts();

  // Перемикач звуку глушить синтезатор, а не лише власну іконку.
  useEffect(() => {
    soundFx.enabled = isSoundEnabled;
  }, [isSoundEnabled]);

  // Selected member/peer profile to inspect in UserProfileModal
  const [selectedProfileUser, setSelectedProfileUser] = useState<UserProfile | ChatMember | null>(null);

  const handleSelectMemberByName = (name: string) => {
    const clean = name.replace(/^@/, '').trim().toLowerCase();
    const fromGroup = activeChat?.members?.find(
      (m) => m.name.toLowerCase() === clean || m.handle?.replace(/^@/, '').toLowerCase() === clean
    );
    if (fromGroup) {
      setSelectedProfileUser(fromGroup);
      store.setProfileModalOpen(true);
      return;
    }
    const peerChat = store.chats.find(
      (c) => c.title.toLowerCase() === clean || c.peerNodeId?.toLowerCase() === clean
    );
    if (peerChat) {
      setSelectedProfileUser({
        id: peerChat.peerNodeId || peerChat.id,
        name: peerChat.title,
        handle: peerChat.peerNodeId ? `@${peerChat.peerNodeId.slice(0, 10)}` : `@${clean}`,
        avatar: peerChat.avatar,
        role: 'member',
        isOnline: peerChat.isOnline,
      });
      store.setProfileModalOpen(true);
      return;
    }
    setSelectedProfileUser({
      id: name,
      name: name.replace(/^@/, ''),
      handle: `@${clean}`,
      avatar: '',
      role: 'member',
      isOnline: false,
    });
    store.setProfileModalOpen(true);
  };

  // Інший профіль — інше імʼя й фото в «Я»: тягнемо їх із вузла знову.
  const activeProfileId = useProfiles((s) => s.activeId);
  const firstProfile = useRef(true);
  useEffect(() => {
    if (firstProfile.current) {
      firstProfile.current = false;
      return;
    }
    void store.hydrateFromNode({ openFirst: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeProfileId]);

  // Стрічку забираємо з вузла на вході — до цього показувати нічого.
  useEffect(() => {
    void store.hydrateFromNode();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Recover transient network failures without duplicating frames: retrySend
  // reuses the original client_id and the node's idempotency check.
  //
  // Звʼязок повернувся (сокет вузла, `online` вікна) — невдалі листи йдуть
  // ОДРАЗУ, а не чекають своєї паузи (`outboxRetryPolicy.ts`). Сканер між
  // тим дивиться часто, але дешево: лише на листи зі станом `failed`.
  useEffect(() => {
    const tick = (reconnected = false) => {
      for (const id of outboxDue(useMessengerStore.getState().chats, Date.now(), reconnected)) void retryOutbox(id);
    };
    tick();
    const timer = window.setInterval(() => tick(), OUTBOX_SCAN_MS);
    const onBack = () => tick(true);
    const offConnect = wsClient.onConnect(onBack);
    window.addEventListener('online', onBack);
    return () => {
      window.clearInterval(timer);
      offConnect();
      window.removeEventListener('online', onBack);
    };
  }, [retryOutbox]);

  // Диспетчер відкладених листів: що десять секунд — ті, чий час настав.
  //
  // Тут стояло порівняння `scheduledTime === "ГГ:ХХ"`, а шухляда писала туди
  // «Сьогодні о 18:00» — тож не спрацьовувало воно ніколи. Тепер час — день і
  // година листа (`scheduledDueMs`), а лист іде в свою розмову, не у відкриту.
  // Живе, лише поки відкрита сторінка, — і шухляда це каже словами.
  useEffect(() => {
    const timer = setInterval(() => useMessengerStore.getState().sendDueScheduled(Date.now()), 10_000);
    void useMessengerStore.getState().refreshScheduled();
    return () => clearInterval(timer);
  }, []);

  // Дзвінки: рушій слухає сигнали вузла/мосту, поки месенджер відкритий. Кнопки
  // слухавки в шапці кидають сюди 'phantom:start-call'.
  useEffect(() => {
    const detach = callEngine.attach();
    if (typeof window !== 'undefined') {
      (window as unknown as Record<string, unknown>).__phantomCallSignal = (data: unknown) => {
        void callEngine.onSignal(data);
      };
    }
    const onStart = (event: Event) => {
      const detail = (event as CustomEvent).detail ?? {};
      const chat = store.getActiveChat();
      const peerNodeId = detail.peerNodeId ?? chat?.peerNodeId;
      if (!peerNodeId && !detail.contactId) return;
      void callEngine.startCall(
        {
          contactId: detail.contactId,
          peerNodeId,
          displayName: detail.displayName ?? chat?.title ?? translate('messengerRoot.peerFallback', resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY])),
          verified: detail.verified ?? chat?.contactVerified ?? false,
          callId: detail.callId ?? chat?.liveRoom?.callId,
        },
        detail.video ? 'video' : 'audio',
      );
    };
    window.addEventListener('phantom:start-call', onStart);
    return () => {
      window.removeEventListener('phantom:start-call', onStart);
      detach();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Стан ретранслятора опитуємо, лише поки месенджер відкритий.
  useEffect(() => {
    phantomRelayService.start();
    return () => phantomRelayService.stop();
  }, []);

  // Заголовок малює канал із живої діагностики рушія та WebSocket вузла.
  const [diagnostics, setDiagnostics] = useState<NetworkDiagnostics | null>(null);
  const [isWsConnected, setIsWsConnected] = useState<boolean>(true);

  useEffect(() => {
    const off = messengerNetworkEngine.onDiagnostics(setDiagnostics);
    const offConnect = wsClient.onConnect(() => setIsWsConnected(true));
    const offDisconnect = wsClient.onDisconnect(() => setIsWsConnected(false));
    return () => {
      off();
      offConnect();
      offDisconnect();
    };
  }, []);

  useEffect(() => {
    if (__PHONE_BUILD__) return;

    const PANE_BY_DIGIT: Record<string, Exclude<DeskPane, null>> = {
      '1': 'day',
      '2': 'map',
      '3': 'wallet',
      '4': 'info',
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      const sc = shortcutOf(e);

      if (mod && !e.altKey && sc === '\\') {
        e.preventDefault();
        setIsSidebarCollapsed((prev) => !prev);
        return;
      }

      if (mod && !e.altKey && sc === 'k') {
        e.preventDefault();
        setIsSidebarCollapsed(false);
        window.dispatchEvent(new CustomEvent(LIST_SEARCH_OPEN_EVENT));
        return;
      }

      if (mod && !e.altKey && sc === 'f') {
        const st = useMessengerStore.getState();
        if (st.getActiveChat()) {
          e.preventDefault();
          setIsSearchingInChat((prev) => !prev);
        }
        return;
      }

      if (mod && !e.altKey && !e.shiftKey && PANE_BY_DIGIT[sc]) {
        e.preventDefault();
        const target = PANE_BY_DIGIT[sc];
        const ui = useUIStore.getState();
        ui.setDeskPane(ui.deskPane === target ? null : target);
        return;
      }

      if (e.altKey && !mod && !altArrowMovesCaret(e.target) && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
        const st = useMessengerStore.getState();
        const ordered = shownChatsForNav(st.chats);
        if (ordered.length === 0) return;
        e.preventDefault();
        const idx = ordered.findIndex((c) => c.id === st.activeChatId);
        const nextIdx =
          e.key === 'ArrowDown'
            ? idx < 0
              ? 0
              : Math.min(ordered.length - 1, idx + 1)
            : idx <= 0
              ? 0
              : idx - 1;
        const target = ordered[nextIdx];
        if (target && target.id !== st.activeChatId) {
          st.setActiveChat(target.id);
        }
        return;
      }

      if (e.key === 'Escape') {
        if (e.defaultPrevented) return;
        const st = useMessengerStore.getState();
        if (st.isCreateChatModalOpen) {
          e.preventDefault();
          st.setCreateChatModalOpen(false);
          return;
        }
        if (st.isSettingsModalOpen) {
          e.preventDefault();
          st.setSettingsModalOpen(false);
          return;
        }
        if (st.isProfileModalOpen) {
          e.preventDefault();
          st.setProfileModalOpen(false);
          setSelectedProfileUser(null);
          return;
        }
        if (st.isMediaLightboxOpen) {
          e.preventDefault();
          st.closeLightbox();
          return;
        }
        if (st.isGroupDetailsOpen) {
          e.preventDefault();
          st.setGroupDetailsOpen(false);
          return;
        }
        if (st.isScheduledDrawerOpen) {
          e.preventDefault();
          st.setScheduledDrawerOpen(false);
          return;
        }
        if (st.isForwardModalOpen) {
          e.preventDefault();
          st.closeForwardModal();
          return;
        }
        if (whereOpen) {
          e.preventDefault();
          setWhereOpen(false);
          return;
        }
        if (useSpaceStore.getState().threadView) {
          e.preventDefault();
          useSpaceStore.getState().openThreadView(null);
          return;
        }
        if (isSearchingInChat) {
          e.preventDefault();
          setIsSearchingInChat(false);
          return;
        }
        const ui = useUIStore.getState();
        if (ui.deskPane !== null) {
          e.preventDefault();
          ui.setDeskPane(null);
          if (st.activeLocationData) st.closeLocationSheet();
          return;
        }
        if (st.activeLocationData) {
          e.preventDefault();
          st.closeLocationSheet();
          return;
        }
        window.dispatchEvent(new CustomEvent(LIST_SEARCH_CLOSE_EVENT));
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isSearchingInChat, whereOpen]);

  // Вужче за 768 колонка одна: показуємо або список, або відкриту розмову.
  // Пошук завжди повертає до списку — шукають саме в ньому.
  const showList = !activeChat || !!store.searchQuery;

  return (
    <div className={`messenger-scale flex w-full h-full bg-canvas text-ink overflow-hidden select-none relative font-sans ${className}`}>
      {/* 1. Left Sidebar (Workspaces, Folders, Circles, Chats) */}
      {!isSidebarCollapsed && (
        <div className={`${showList ? 'flex' : 'hidden'} md:flex w-full md:w-auto h-full shrink-0 transition-all duration-200`}>
          <Sidebar
          currentUser={store.currentUser}
          chats={store.chats}
          activeChatId={store.activeChatId}
          onSelectChat={(chatId) => (isSpaceChat(store.chats.find((c) => c.id === chatId)) ? openSpace(chatId) : store.setActiveChat(chatId))}
          onNewChat={() => store.setCreateChatModalOpen(true)}
          onOpenUserProfile={() => {
            setSelectedProfileUser(null);
            store.setProfileModalOpen(true);
          }}
        />
        </div>
      )}

      {/* 2. Main Chat Area */}
      {activeChat ? (
        // Розмова лежить на рівному тлі (макет 04); хром кольором людини не тонується ніколи (§1.3).
        <div
          data-testid="talk-root"
          className={`${showList ? 'hidden' : 'flex'} md:flex flex-1 flex-col h-full min-w-0 msg-stage relative overflow-hidden`}
        >
          {/* Header.
              `networkLatencyMs` — тільки те, що зняв getStats() на зʼєднанні.
              Тут стояло `?? (isWsConnected ? 12 : null)`: щойно вузол відповідав,
              шапка писала «12 мс» тим самим шрифтом, що й справжній вимір, і
              людина не могла відрізнити зняте від вигаданого. Немає виміру —
              немає числа. */}
          <Header
            activeTransportStatus={
              diagnostics?.activeStatus && diagnostics.activeStatus !== 'offline'
                ? diagnostics.activeStatus
                : isWsConnected
                  ? 'server-ws'
                  : 'offline'
            }
            transportMode={diagnostics?.transportMode || 'auto'}
            networkLatencyMs={diagnostics?.latencyMs ?? null}
            currentChat={activeChat}
            currentUser={store.currentUser}
            onOpenScheduledMessages={() => store.setScheduledDrawerOpen(true)}
            scheduledMessagesCount={store.getScheduledForActiveChat().length}
            onOpenSettings={() => store.setSettingsModalOpen(true)}
            onOpenGroupDetails={() => {
              if (!__PHONE_BUILD__ && !isPhoneShell()) {
                setInfoSubView('profile');
                useUIStore.getState().setDeskPane('info');
              } else {
                store.setGroupDetailsOpen(true);
              }
            }}
            isSoundEnabled={isSoundEnabled}
            onToggleSound={() => setIsSoundEnabled(!isSoundEnabled)}
            isSearching={isSearchingInChat}
            onToggleSearch={() => setIsSearchingInChat(!isSearchingInChat)}
            onOpenWhere={() => {
              if (!__PHONE_BUILD__ && !isPhoneShell()) {
                setInfoSubView('where');
                useUIStore.getState().setDeskPane('info');
              } else {
                setWhereOpen(true);
              }
            }}
            pinnedCount={
              activeChat.messages.filter((m) => m.isPinned).length +
              (activeChat.pinnedMessage && !activeChat.messages.some((m) => m.isPinned && m.id === activeChat.pinnedMessage?.id) ? 1 : 0)
            }
            onScrollToPinned={() => chatAreaRef.current?.scrollToPinned()}
            onBack={() => store.setActiveChat('')}
            onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
            isSidebarCollapsed={isSidebarCollapsed}
          />

          {/* Messages Feed */}
          <div className="flex-1 min-h-0 relative isolate" data-wallpaper={hasWallpaper ? 'on' : undefined}>
            <ChatWallpaper chatId={activeChat.id} />
            <ChatArea
              ref={chatAreaRef}
              currentChat={activeChat}
              messages={activeChat.messages}
              currentUserId={store.currentUser.id}
              onOpenLocation={(loc) => store.openLocationSheet(loc)}
              onVotePoll={(msgId, optId) => store.votePoll(msgId, optId)}
              onPayBillShare={(msgId, payerId) => store.payBillShare(msgId, payerId)}
              onAddReaction={(msgId, emoji) => store.addReaction(msgId, emoji)}
              onReplyMessage={(msg, quoted) => store.startReply(msg, quoted)}
              onEditMessage={(msg) => store.startEdit(msg)}
              // Вибір «для всіх / для себе» людина вже зробила у вікні ChatArea.
              // Тут стояло відкриття ДРУГОГО такого ж вікна, і вибір із першого
              // мовчки губився — саме тому «для всіх» не робило нічого.
              onDeleteMessage={(msgId, forEveryone) => store.deleteMessage(msgId, Boolean(forEveryone))}
              onTogglePinMessage={(msgId) => store.togglePinMessage(msgId)}
              onForwardMessage={(msg) => store.openForwardModal(msg)}
              onSelectMemberByName={handleSelectMemberByName}
              selectedMessageIds={store.selectedMessageIds}
              onToggleSelectMessage={(msgId) => store.toggleSelectMessage(msgId)}
              onSelectMessages={(ids) => store.selectMessages(ids)}
              selectionAnchorId={store.selectionAnchorId}
              isSelectionMode={store.multiSelectMode}
              onUpdateTableData={(msgId, data) => store.updateTableData(msgId, data)}
              onUpdateTaskListData={(msgId, tasks) => store.updateTaskListData(msgId, tasks)}
              onOpenImageLightbox={(url, title) => store.openLightbox(url, title)}
              isSearching={isSearchingInChat}
              onCloseSearch={() => setIsSearchingInChat(false)}
              jumpToMessageId={
                store.pendingJump?.chatId === activeChat.id ? store.pendingJump.messageId : undefined
              }
              onJumped={() => store.clearPendingJump()}
              onOpenWhere={() => {
                if (!__PHONE_BUILD__ && !isPhoneShell()) {
                  setInfoSubView('where');
                  useUIStore.getState().setDeskPane('info');
                } else {
                  setWhereOpen(true);
                }
              }}
            />
          </div>

          {/* Вибір листів (msg-x): смуга дій стає на місце композера, композер
              лишається змонтованим — набраний текст не губиться. */}
          {store.multiSelectMode && (store.reportPick ? <ReportPickBar chat={activeChat} /> : <SelectionBar chat={activeChat} />)}

          {/* Відповідей-здогадок над композером немає (§1.4: нічого вигаданого). */}
          {/* Composer Input Bar — або пауза, поки звірений контакт має новий ключ, або читання після виходу з групи. */}
          {!activeChat.contactKeyHold && activeChat.standing !== 'left' && activeChat.standing !== 'removed' && (
            <WhereAskChips chat={activeChat} />
          )}
          <GroupRoleProbe chat={activeChat} />
          {!activeChat.contactKeyHold && !readOnlyChannel(activeChat) && <SlowModeNote chat={activeChat} />}
          {!activeChat.contactKeyHold && !store.multiSelectMode && isRequest(activeChat) && (
            <StrangerBar chat={activeChat} onAdded={() => setStrangerTick((n) => n + 1)} />
          )}
          <div data-composer-slot className={store.multiSelectMode ? 'hidden' : 'contents'}>
          {(activeChat.type === 'group' || activeChat.type === 'channel') &&
          (activeChat.standing === 'left' || activeChat.standing === 'removed') ? (
            <div
              data-testid="group-standing-bar"
              className="shrink-0 border-t border-line bg-surface px-4 py-3 text-center text-footnote text-ink-2"
            >
              {t(activeChat.standing === 'removed' ? 'groupProfileSheet.youRemoved' : 'groupProfileSheet.youLeft')}
            </div>
          ) : activeChat.contactKeyHold ? (
            <KeyHoldBar chat={activeChat} />
          ) : readOnlyChannel(activeChat) ? (
            <ChannelReadBar chat={activeChat} />
          ) : (
            <MessageComposer
            onSendMessage={(text, opts) => store.sendMessage(text, opts)}
            onScheduleDraft={(text, done) => {
              scheduleDoneRef.current = done;
              setScheduleSeed(text);
              store.setScheduledDrawerOpen(true);
            }}
            onSendVoiceMessage={(dur, transcript, audioBlob, waveform) => {
              void store.sendVoiceMessage(dur, transcript, audioBlob, waveform);
            }}
            onOpenScheduledList={() => store.setScheduledDrawerOpen(true)}
            scheduledCountInCurrentChat={store.getScheduledForActiveChat().length}
            replyingTo={store.replyingTo}
            onCancelReply={() => store.cancelReply()}
            editingMessage={store.editingMessage}
            onCancelEdit={() => store.cancelEdit()}
            onSaveEdit={(msgId, newText) => {
              store.editMessage(msgId, newText);
              store.cancelEdit();
            }}
            selectedMessagesForQuote={[]}
            onSynthesizeMultiQuote={(_title, _comment) => {}}
            onClearSelectedQuotes={() => {}}
            chatMembers={activeChat.members || []}
            lastPeerMessage={lastPeerMessageOf(activeChat.messages, store.currentUser.id)}
            chatId={activeChat.id}
            // Чернетка потрібна композеру лише при зміні розмови — читаємо її на місці.
            initialDraft={useMessengerStore.getState().drafts[activeChat.id] || ''}
            onDraftChange={(cId, text) => store.setDraft(cId, text)}
            wherePeer={wherePeer}
            onOpenWhere={() => {
              if (!__PHONE_BUILD__ && !isPhoneShell()) {
                setInfoSubView('where');
                useUIStore.getState().setDeskPane('info');
              } else {
                setWhereOpen(true);
              }
            }}
            signalTyping={typingReaches(activeChat)}
          />)}
          </div>
          {whereOpen && wherePeer && <WhereSheet peer={wherePeer} onClose={() => setWhereOpen(false)} />}
          {deskPane !== 'wallet' && <SolanaSheets />}
          <SettleUpHost />
        </div>
      ) : (
        // На телефоні колонка одна: або список, або розмова. Заставка «Оберіть
        // бесіду» там відбирала б у списку половину екрана й пропонувала
        // вибрати зі списку, якого не видно.
        <div className="hidden md:flex flex-1 flex-col items-center justify-center p-8 text-center bg-canvas select-none">
          <MessageCircle aria-hidden className="w-12 h-12 mb-4 text-ink-3" strokeWidth={1.5} />
          <h2 className="text-title-2 text-ink">{t('messengerRoot.pickChat')}</h2>
          <p className="mt-2 text-callout text-ink-2 max-w-[320px]">{t('messengerRoot.pickChatHint')}</p>
        </div>
      )}

      {!__PHONE_BUILD__ && !isPhoneShell() && (
        <Suspense fallback={null}>
          <DeskSidePane
            activeChat={activeChat}
            infoSubView={infoSubView}
            onInfoSubViewChange={setInfoSubView}
            onSelectMember={(m) => {
              setSelectedProfileUser(m);
              store.setProfileModalOpen(true);
            }}
            onSearchInChat={isSearchingInChat ? undefined : () => setIsSearchingInChat(true)}
          />
        </Suspense>
      )}

      {/* Хребет: простори → топіки → гілки. Повний екран на телефоні. */}
      <ThreadView />
      <EduHost />
      <SpaceHost />
      <DirectionTabHost />

      {/* Зустріч і рахунок, які відкрив помічник чи сторінка закладу — поверх будь-якого екрана. */}
      <MeetupDraftHost />
      <ShareInHost />
      <ProfilesHost showTrigger={false} />
      <HelpDoor />
      <GroupJoinDoor />
      <AskSheetHost />

      {/* 3. Global Modals & Drawers
        *
        * Одна межа на весь блок, `fallback={null}`. Порожньо тут означає
        * «модалка ще не відкрита» — те саме, що бачить оператор до дотику;
        * крутілка на чверть кадру була б шумом, а не звісткою. Шматок
        * лежить локальним файлом поруч із бандлом, мережі в цьому кроці
        * немає взагалі.
        */}
      <Suspense fallback={null}>
      <CreateChatModal
        isOpen={store.isCreateChatModalOpen}
        onClose={() => store.setCreateChatModalOpen(false)}
        onConversationReady={async (conversationId) => {
          // Розмову вже створив вузол — забираємо її в список і відкриваємо.
          store.setCreateChatModalOpen(false);
          await store.refreshConversations();
          store.setActiveChat(conversationId);
        }}
      />

      {activeChat && store.isGroupDetailsOpen && (
        <GroupProfileSheet
          onClose={() => store.setGroupDetailsOpen(false)}
          chat={activeChat}
          onSelectMember={(m) => {
            setSelectedProfileUser(m);
            store.setProfileModalOpen(true);
          }}
          onTogglePinChat={(chatId) => store.togglePinChat(chatId)}
          onSearch={isSearchingInChat ? undefined : () => setIsSearchingInChat(true)}
        />
      )}

      <UserProfileModal
        isOpen={store.isProfileModalOpen}
        onClose={() => {
          store.setProfileModalOpen(false);
          setSelectedProfileUser(null);
        }}
        currentUser={store.currentUser}
        targetUser={selectedProfileUser}
        onUpdateCurrentUser={(updated) => store.updateCurrentUser(updated)}
        onStartDirectChat={async (target) => {
          const cid = await store
            .createDirectMessage(target.name, 'friends', target.avatar, target.contactId)
            .catch((e) => {
              useUIStore.getState().toast({
                kind: 'error',
                message: e instanceof Error ? e.message : t('store.chatOpen'),
              });
              return '';
            });
          if (cid) store.setActiveChat(cid);
          store.setProfileModalOpen(false);
          setSelectedProfileUser(null);
        }}
      />

      <SettingsModal
        isOpen={store.isSettingsModalOpen}
        onClose={() => store.setSettingsModalOpen(false)}
        isSoundEnabled={isSoundEnabled}
        onToggleSound={() => setIsSoundEnabled(!isSoundEnabled)}
        onExportAllData={() => {}}
        onOpenProfile={() => {
          store.setSettingsModalOpen(false);
          setSelectedProfileUser(null);
          store.setProfileModalOpen(true);
        }}
      />

      {/* Відкладені: на ПК їх шле ця сторінка, на телефоні — будильник апарата. */}
      <ScheduledMessagesDrawer
        isOpen={store.isScheduledDrawerOpen}
        onClose={() => {
          scheduleDoneRef.current = null;
          setScheduleSeed('');
          store.setScheduledDrawerOpen(false);
        }}
        scheduledMessages={store.scheduledMessages}
        currentChatId={activeChat?.id || ''}
        chats={store.chats}
        onSendNow={(id) => store.sendScheduledNow(id)}
        onCancel={(id) => store.cancelScheduledMessage(id)}
        onMove={(id, to) =>
          store.rescheduleScheduledMessage(id, to.kind === 'online' ? { whenOnline: true } : { date: to.date, time: to.time, repeat: to.repeat })
        }
        onEditText={(id, text) => store.editScheduledText(id, text)}
        onCreate={(draft) => {
          store.addScheduledMessage(draft);
          scheduleDoneRef.current?.();
          scheduleDoneRef.current = null;
          setScheduleSeed('');
        }}
        initialText={scheduleSeed}
      />

      <LocationSheetModal
        location={store.activeLocationData}
        isOpen={!!store.activeLocationData && (__PHONE_BUILD__ || isPhoneShell())}
        onClose={() => store.closeLocationSheet()}
        groupMemberIds={activeChat?.type === 'group' ? activeChat.members?.map((m) => m.id) : undefined}
      />

      <MediaLightboxModal
        isOpen={store.isMediaLightboxOpen}
        onClose={() => store.closeLightbox()}
        mediaUrl={store.activeLightboxUrl}
        mediaTitle={store.activeLightboxTitle || undefined}
      />

      <ForwardMessageModal
        isOpen={store.isForwardModalOpen}
        onClose={() => store.closeForwardModal()}
        chats={store.chats}
        currentChatId={activeChat?.id || ''}
        messagesToForward={
          store.activeForwardMessages?.length
            ? store.activeForwardMessages
            : store.activeForwardMessage
              ? [store.activeForwardMessage]
              : []
        }
        onConfirmForward={(targetChatId, opts) => {
          const list = store.activeForwardMessages?.length
            ? store.activeForwardMessages
            : store.activeForwardMessage
              ? [store.activeForwardMessage]
              : [];
          store.clearSelection();
          void (async () => {
            for (const msg of list) {
              await store.forwardMessage(forwardPayload(msg, { redact: Boolean(opts?.redact) }), targetChatId);
            }
            if (opts?.caption) store.sendMessage(opts.caption, { to: targetChatId });
          })();
        }}
      />

      </Suspense>

      <CallOverlay />
    </div>
  );
};
