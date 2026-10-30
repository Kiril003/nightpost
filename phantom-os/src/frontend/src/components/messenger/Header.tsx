import { pickPlural } from '../chat/tNow';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { createPortal } from 'react-dom';
import {
  AudioLines,
  ClipboardList,
  Search,
  Clock,
  Bell,
  BellOff,
  Phone,
  Video,
  Bookmark,
  EllipsisVertical,
  ArrowLeft,
  PanelLeftClose,
  PanelLeftOpen,
  Pin,
  Settings2,
  SlidersHorizontal,
  Timer,
  UserRound,
  Users,
} from 'lucide-react';
import { Chat, UserProfile, ActiveTransportStatus, TransportProtocol } from '../../types/messenger';
import { isPhoneShell } from './phoneShell';
import { chatCircleTag } from './todayFormat';
import { useFolders } from '../../stores/folderStore';
import { chatToneKey, displayTitle, isNodeChat, isSavedNotes } from './chatListFormat';
import { durationWord } from '../../services/disappearWords';
import { callGate } from '../../services/callGate';
import { useIceRoads } from '../../hooks/useIceRoads';
import { Avatar } from './Avatar';
import { PhantomMark } from './PhantomMark';
import { AskGlyphButton } from './ask/AskSheet';
import { chatAsk } from '../../services/askContext';
import { openAsk } from '../../stores/askSheetStore';
import { soundFx } from '../../utils/messengerSound';
import { planOfGroup } from '../../services/meetup/expiry';
import { whenWord } from './meetup/words';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { PeerProfileSheet } from './PeerProfileSheet';
import { useProfiles } from '../../stores/profilesStore';
import { ownable, ownerOf, useChatProfiles } from '../../stores/chatProfileStore';
import { peerProfileEdits } from './peerProfileEdits';
import { DeviceSheet } from './DeviceSheet';
import { WhereHeaderSign } from './WhereCard';
import { PhoneChatMenu, hasPhoneChatMenu } from './PhoneChatMenu';
import { phantomSelfApi } from '../../services/phantomSelfApi';
import { navigateTo } from '../../services/nav';
import { CallSettingsSheet } from './CallSettingsSheet';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { listenLiving, presenceCode, presenceLine, useLiving } from '../../services/livingPulse';
import { namesakeHint } from '../../services/peopleIdentity';
import { onPeerCheck, wherePeerOf } from '../../services/peerWhere';
import { isChannel } from './GroupSettingsPanel';
import { WorkHost } from './work/WorkHost';
import { isWorkGroup } from '../../services/workEvents';
import { openWork } from '../../services/workBus';

interface HeaderProps {
  currentChat: Chat;
  currentUser: UserProfile;
  onOpenScheduledMessages?: () => void;
  scheduledMessagesCount?: number;
  onOpenSettings: () => void;
  onOpenGroupDetails: () => void;
  /** Поділитися своїм місцем у цю розмову. */
  onOpenWhere?: () => void;
  isSoundEnabled: boolean;
  onToggleSound: () => void;
  onToggleSearch: () => void;
  isSearching: boolean;
  pinnedCount?: number;
  onScrollToPinned?: () => void;
  onBack?: () => void;
  onToggleSidebar?: () => void;
  isSidebarCollapsed?: boolean;
  activeTransportStatus?: ActiveTransportStatus;
  transportMode?: TransportProtocol;
  networkLatencyMs?: number | null;
}

// Кругла кнопка-іконка — єдина форма для всієї правої групи шапки. На
// телефоні — коло 40 на піднятій поверхні (ціль дотику 44 дає
// touch-floor-inline), на ПК — тиха іконка 32.
/** Кнопка-значок (§7.8): коло 44, привид, `text-2` у спокої, `overlay` на дотик. */
const ICON_BTN =
  'w-11 h-11 min-w-0 min-h-0 rounded-full flex items-center justify-center shrink-0 transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line';
const ICON_BTN_IDLE = 'text-ink hover:bg-overlay active:bg-overlay';
const ICON_BTN_OFF = 'text-ink-3 opacity-40';
// Чому кнопка не натискається — сказано словами, а не сірим кольором, і
// словами, які описують СПРАВЖНЮ умову. Тут стояло «Дзвінки лише зі звіреними
// вузловими контактами», а вмикалось воно просто за «не група»: звірка не
// перевірялась ніде, і в групу теж дзвонити не можна не через звірку. Поруч
// уже обчислювався `hasPeer` — і не використовувався жодного разу. Тепер умова
// та сама, що й текст: потрібна 1:1 розмова з вузловою адресою, бо без неї
// сигнал дзвінка нема куди слати.
// Самі речення й умова переїхали в `services/callGate.ts`: друга слухавка —
// у картці співрозмовника — не питала НІЧОГО й мовчки не робила нічого, а
// розкласти умову вдруге поруч означало б два оголошення одного контракту.
// Рядок випадного меню: 36px на десктопі, 48 під палець; іконка + один рядок тексту.
const MENU_ITEM =
  'w-full min-h-11 px-3 rounded-control text-left text-subhead text-ink flex items-center gap-3 hover:bg-overlay transition-colors duration-quick';

// Стан каналу живе в статусному рядку разом зі звіркою, а не окремою пігулкою:
// це та сама відповідь на питання «наскільки цій розмові можна вірити».
export const Header: React.FC<HeaderProps> = ({
  currentChat,
  currentUser,
  onOpenScheduledMessages,
  scheduledMessagesCount = 0,
  onOpenSettings,
  onOpenGroupDetails,
  onOpenWhere,
  isSoundEnabled,
  onToggleSound,
  onToggleSearch,
  isSearching,
  pinnedCount = 0,
  onScrollToPinned,
  onBack,
  onToggleSidebar,
  isSidebarCollapsed,
  activeTransportStatus: _activeTransportStatus = 'offline',
  transportMode: _transportMode = 'auto',
  networkLatencyMs: _networkLatencyMs = null,
}) => {
  const { t, locale } = useTranslation();
  // Меню живе в порталі на body з координатами від кнопки: всередині шапки
  // воно опинялося під бульбашками стрічки, бо стрічка має власні шари z-50.
  const headerRef = useRef<HTMLElement>(null);
  const moreBtnRef = useRef<HTMLButtonElement>(null);
  const [menuAnchor, setMenuAnchor] = useState<{ top: number; right: number } | null>(null);
  const closeMenu = useCallback(() => setMenuAnchor(null), []);

  // Лист співрозмовника відкривається просто з шапки — звірка за 1 клік.
  const [sheet, setSheet] = useState<{ top: number; left: number; check?: boolean } | null>(null);
  const hasPeer = !!currentChat.peerNodeId;
  const isGroup = currentChat.type === 'group' || currentChat.type === 'channel';
  // «Збережене» — нотатки собі: дзвонити нікому, ключа звіряти ні з ким.
  const savedNotes = isSavedNotes(currentChat);
  const wherePeer = wherePeerOf(currentChat);
  // Під іменем — лише людське: хто друкує, коли були на звʼязку, скільки
  // людей у групі. Стан ключа тут не живе: безпека працює мовчки й озивається
  // лише подією (ключ змінився) нижче.
  const others = (currentChat.members ?? []).filter((m) => m.id !== currentUser?.id && m.name);
  const withMe = others.length < (currentChat.members?.length ?? 0);
  const plainLine: string | null = savedNotes
    ? null
    : isGroup && !isChannel(currentChat) && others.length > 0 && others.length <= 3
    ? withMe
      ? t('talk.andYou', { names: others.map((m) => m.name).join(', ') })
      : others.map((m) => m.name).join(', ')
    : isGroup
    ? currentChat.members?.length
      ? `${currentChat.members.length} ${
          isChannel(currentChat)
            ? t(pickPlural(locale, currentChat.members.length, 'groupsTg.sub1', 'groupsTg.sub2', 'groupsTg.sub5'))
            : t(pickPlural(locale, currentChat.members.length, 'header.member1', 'header.member2', 'header.member5'))
        }`
      : null
    : null;
  const plan = isGroup ? planOfGroup(currentChat.messages ?? [], Date.now()) : null;
  const planLine = plan?.state.kind === 'will_hide' ? t('planGroup.closes', { when: whenWord(plan.state.at, Date.now()) }) : null;
  const typing = useMessengerStore((s) => Boolean(s.typingStatus[currentChat.id]));
  const typingText = useMessengerStore((s) => s.typingStatus[currentChat.id]);
  const namesake = useMessengerStore((s) => namesakeHint(currentChat, s.chats));
  const profiles = useProfiles((s) => s.profiles);
  const activeProfileId = useProfiles((s) => s.activeId);
  const ownerId = useChatProfiles((s) => (ownable(currentChat) ? ownerOf(currentChat.id, s.map, profiles) : ''));
  const hereAs = ownerId && ownerId !== activeProfileId ? (profiles.find((p) => p.id === ownerId)?.name ?? '') : '';

  // Дзвонити можна лише в 1:1 розмову, у якої є вузлова адреса співрозмовника.
  const phoneShell = __PHONE_BUILD__ || isPhoneShell();

  // Стан людини словом — із того самого опитування, що й смуга списку.
  // Показує місце → «показує вам місце · поруч»; чули → «на звʼязку 5 хв
  // тому». Нічого з цього — рядок мовчить. «У мережі» не кажемо ніколи:
  // присутність тут — факт із віком, а не лампочка.
  useEffect(() => (phoneShell ? listenLiving() : undefined), [phoneShell]);
  const peerKey = !isGroup && !savedNotes ? currentChat.peerNodeId ?? null : null;
  const face = useLiving((s) => (peerKey ? s.faces[peerKey] : undefined));
  const showing = useLiving((s) => (peerKey ? s.pulse?.near.find((p) => p.peerId === peerKey) : undefined));
  const presence = peerKey ? presenceLine(showing, face, Date.now()) : null;
  // Зелене — лише «поруч»: людина в тій самій мережі або сама показує, що близько.
  const presenceNear = !!peerKey && presenceCode(showing, face, Date.now()) === 'near';
  const folders = useFolders((s) => s.folders);
  const circleTag = chatCircleTag(currentChat, folders, t);
  const ice = useIceRoads();
  const gate = callGate({ roads: ice, phoneShell, hasPeer, isGroup });
  const canCall = gate.can;
  const noCallNote = gate.why;

  const hasPinned = pinnedCount > 0 && !!onScrollToPinned;

  const startCall = (video: boolean) => {
    // Мовчазна відмова тут була найдорожчою вадою дзвінків: палець по кнопці
    // не робив РІВНО НІЧОГО, а причина жила в `title`, якого на тачскріні не
    // видно ніколи. Причина вже обчислена поруч — тепер її кажуть уголос.
    if (!canCall) {
      useUIStore.getState().toast({ kind: 'warn', message: noCallNote });
      return;
    }
    soundFx.playTap();
    window.dispatchEvent(
      new CustomEvent('phantom:start-call', {
        detail: {
          // Тут стояло `|| \`node_${currentChat.id}\``: коли адреси немає, вона
          // ВИГАДУВАЛАСЬ з ідентифікатора розмови, і дзвінок їхав у нікуди з
          // виглядом справжнього. Тепер без адреси кнопка просто не активна.
          peerNodeId: currentChat.peerNodeId,
          displayName: displayTitle(currentChat.title),
          // Стан звірки їде разом: у картці дзвінка його вже нема де взяти.
          // Невідомо — значить не звірено; «true за замовчуванням» було б обіцянкою.
          verified: currentChat.contactVerified ?? false,
          video,
        },
      }),
    );
  };

  const openSheet = (el?: HTMLElement | null, focus?: 'shared' | 'check') => {
    soundFx.playTap();
    // «N фото» веде одразу до спільного: картка та сама, прокручена до нього.
    if (focus) {
      const id = focus === 'shared' ? 'peer-profile-shared' : 'peer-profile-verify';
      window.setTimeout(() => {
        document.querySelector(`[data-testid="${id}"]`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
      }, 250);
    }
    const bar = headerRef.current?.getBoundingClientRect();
    const r = el?.getBoundingClientRect();
    const top = (bar?.bottom ?? 60) + 6;
    const left = Math.min(Math.max(8, r?.left ?? (bar?.left ?? 0) + 12), window.innerWidth - 308);
    setSheet({ top, left, check: focus === 'check' });
  };
  // Свій пристрій — не людина: його картка — вікно «Мій пристрій».
  const [deviceOpen, setDeviceOpen] = useState(false);
  const openCard = (el?: HTMLElement | null) => {
    if (currentChat.mine) {
      soundFx.playTap();
      setDeviceOpen(true);
    } else openSheet(el);
  };
  const openSheetRef = useRef(openSheet);
  openSheetRef.current = openSheet;
  useEffect(
    () => onPeerCheck((id) => {
      if (id === currentChat.peerNodeId) openSheetRef.current(null, 'check');
    }),
    [currentChat.peerNodeId],
  );

  // Телефон: ⋮ відкриває аркуш форми з дверима саме цієї розмови.
  const [phoneMenuOpen, setPhoneMenuOpen] = useState(false);
  const [callSheetOpen, setCallSheetOpen] = useState(false);
  const callDoor = !savedNotes && !isGroup && !isNodeChat(currentChat);
  useEffect(() => setCallSheetOpen(false), [currentChat.id]);

  const toggleMoreMenu = () => {
    soundFx.playTap();
    if (phoneShell) {
      setPhoneMenuOpen(true);
      return;
    }
    if (menuAnchor) {
      closeMenu();
      return;
    }
    const btn = moreBtnRef.current?.getBoundingClientRect();
    const bar = headerRef.current?.getBoundingClientRect();
    if (!btn || !bar) return;
    setMenuAnchor({ top: bar.bottom + 6, right: Math.max(8, window.innerWidth - btn.right) });
  };

  useEffect(() => {
    if (!menuAnchor) return;
    window.addEventListener('resize', closeMenu);
    return () => window.removeEventListener('resize', closeMenu);
  }, [menuAnchor, closeMenu]);

  useEscapeClose(!!menuAnchor, closeMenu);

  const subtitle = currentChat.topic || currentChat.customVibe || currentChat.description || '';
  const hasStatusLine = Boolean(currentChat.contactKeyChanged || typing || hereAs || presence || planLine || plainLine || subtitle);

  return (
    <header
      ref={headerRef}
      data-masthead
      className="relative flex flex-col bg-canvas border-b-[0.5px] border-line select-none shrink-0 z-30 text-ink"
    >
      {/* Шапка розмови (ідентичність 02.10): без смуги й без рамки — імʼя
          стоїть на тому ж рельєфі, що й стрічка, як заголовок листа. Людиною
          не тонується ніколи; 64 на телефоні, 60 на ПК. */}
      <div data-talk-bar className="relative h-14 px-1 md:px-3 flex items-center justify-between gap-1">
        <div className="flex items-center gap-1 min-w-0 flex-1">
          {onBack && (
            <button
              onClick={() => {
                soundFx.playTap();
                onBack();
              }}
              className={`md:hidden ${ICON_BTN} ph-press text-ink hover:bg-overlay`}
              title={t('header.backToChats')}
              aria-label={t('header.back')}
            >
              <ArrowLeft className="w-6 h-6" strokeWidth={1.75} />
            </button>
          )}

          {onToggleSidebar && (
            <button
              onClick={() => {
                soundFx.playTap();
                onToggleSidebar();
              }}
              className={`hidden md:flex ${ICON_BTN} ${ICON_BTN_IDLE}`}
              title={isSidebarCollapsed ? t('header.showList') : t('header.hideList')}
              aria-label={t('header.toggleList')}
            >
              {isSidebarCollapsed ? (
                <PanelLeftOpen className="w-5 h-5" strokeWidth={1.75} />
              ) : (
                <PanelLeftClose className="w-5 h-5" strokeWidth={1.75} />
              )}
            </button>
          )}

          <div
            role="button"
            tabIndex={0}
            data-chat-header-title
            onClick={(e) => {
              if (hasPeer) openCard(e.currentTarget as HTMLElement);
              else {
                soundFx.playTap();
                onOpenGroupDetails();
              }
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.currentTarget.click();
              }
            }}
            data-header-capsule
            className={`ph-press h-12 flex items-center gap-2 min-w-0 max-w-full cursor-pointer pl-1 pr-3 rounded-full hover:bg-overlay transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line`}
            title={hasPeer ? t('header.profile') : t('header.aboutChat')}
          >
            <span className="relative shrink-0" data-shared="chat-avatar">
              {savedNotes ? (
                <span data-header-saved className="w-10 h-10 rounded-full bg-raised text-ink-2 flex items-center justify-center">
                  <Bookmark className="w-6 h-6" strokeWidth={1.75} />
                </span>
              ) : isNodeChat(currentChat) ? (
                <PhantomMark size="w-10 h-10" />
              ) : (
                <Avatar
                  src={face?.avatar || currentChat.avatar}
                  name={displayTitle(currentChat.title)}
                  tone={chatToneKey(currentChat)}
                  radius={isGroup ? 'rounded-control' : 'rounded-full'}
                  className="w-10 h-10 text-headline"
                />
              )}
            </span>

            <span className="min-w-0 flex-1 flex flex-col justify-center" data-shared="chat-name">
              <span className="flex items-baseline gap-1.5 min-w-0">
                <h2 className="text-title-2 leading-[22px] text-ink truncate">{displayTitle(currentChat.title)}</h2>
                {namesake && (
                  <span data-testid="header-namesake" className="shrink min-w-0 truncate text-footnote text-ink-3">
                    · {namesake}
                  </span>
                )}
                {(currentChat.disappearSeconds ?? 0) > 0 && (
                  <span
                    data-testid="header-timer"
                    aria-label={`${t('phoneChatMenu.timerDoor')}: ${durationWord(currentChat.disappearSeconds ?? 0)}`}
                    className="shrink-0 inline-flex items-center gap-1 text-footnote text-ink-3"
                  >
                    <Timer className="w-3.5 h-3.5" strokeWidth={1.75} aria-hidden="true" />
                    {durationWord(currentChat.disappearSeconds ?? 0)}
                  </span>
                )}
              </span>

              {/* Один рядок стану людськими словами (§7.1). */}
              <span data-talk-status className="flex items-center gap-1.5 min-w-0 text-footnote text-ink-3">
                {circleTag && !namesake && !hereAs && (
                  <span data-testid="header-circle" className="shrink-0">
                    {circleTag}
                    {hasStatusLine ? ' ·' : ''}
                  </span>
                )}
                {currentChat.contactKeyChanged ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      openSheet(e.currentTarget as HTMLElement);
                    }}
                    className="touch-floor-inline relative min-h-0 min-w-0 truncate text-danger"
                    title={t('header.newPhoneTitle')}
                  >
                    {t('header.newPhone')}
                  </button>
                ) : typing ? (
                  <span className="truncate" data-testid="header-typing">
                    {isGroup && typingText ? typingText : t('header.typing')}
                  </span>
                ) : hereAs ? (
                  <span className="truncate" data-testid="header-here-as">
                    {t('header.hereAs', { name: hereAs })}
                  </span>
                ) : presence ? (
                  <span className="truncate" data-testid="header-presence" data-near={presenceNear || undefined}>
                    {presence}
                  </span>
                ) : planLine ? (
                  <span className="truncate" data-testid="header-plan-line">
                    {planLine}
                  </span>
                ) : plainLine ? (
                  <span className="truncate" data-testid="header-plain-line">
                    {plainLine}
                  </span>
                ) : subtitle ? (
                  <span className="truncate">{subtitle}</span>
                ) : null}
              </span>
            </span>
          </div>
        </div>

        <div data-header-actions className="flex items-center shrink-0">
          {wherePeer && <WhereHeaderSign peer={wherePeer} />}

          {isGroup && currentChat.liveRoom && !currentChat.liveRoom.mine && (
            <button
              type="button"
              data-live-room-join
              onClick={() => {
                soundFx.playTap();
                window.dispatchEvent(
                  new CustomEvent('phantom:start-call', {
                    detail: {
                      contactId: currentChat.id,
                      peerNodeId: currentChat.peerNodeId,
                      displayName: displayTitle(currentChat.title),
                      callId: currentChat.liveRoom?.callId,
                      video: false,
                    },
                  }),
                );
              }}
              className="touch-floor-inline shrink-0 h-9 px-4 mr-1 rounded-full bg-accent text-on-accent text-callout font-semibold active:bg-accent-press transition-colors duration-instant"
            >
              {t('liveRoomStrip.join')}
            </button>
          )}

          {!savedNotes && !isGroup && !isNodeChat(currentChat) && (
            <>
              <button
                onClick={() => startCall(false)}
                aria-disabled={!canCall}
                data-call-start="audio"
                className={`${ICON_BTN} ${canCall ? ICON_BTN_IDLE : ICON_BTN_OFF}`}
                title={canCall ? t('header.call') : noCallNote}
                aria-label={t('header.audioCall')}
              >
                <Phone className="w-6 h-6" strokeWidth={1.75} />
              </button>
              <button
                onClick={() => startCall(true)}
                aria-disabled={!canCall}
                data-call-start="video"
                className={`${ICON_BTN} ${canCall ? ICON_BTN_IDLE : ICON_BTN_OFF}`}
                title={canCall ? t('header.videoCall') : noCallNote}
                aria-label={t('header.videoCall')}
              >
                <Video className="w-6 h-6" strokeWidth={1.75} />
              </button>
            </>
          )}

          {/* PHANTOM — перша закріплена розмова з кнопкою дзвінка (NAV_CONTRACT). */}
          {isNodeChat(currentChat) && (
            <button
              onClick={() => {
                soundFx.playTap();
                if (!navigateTo('call-phantom')) {
                  void phantomSelfApi.voice(true).catch(() =>
                    useUIStore.getState().toast({ kind: 'warn', message: t('header.voiceUnavailable') }),
                  );
                }
              }}
              data-call-start="phantom"
              className={`${ICON_BTN} ${ICON_BTN_IDLE}`}
              title={t('header.voice')}
              aria-label={t('header.voice')}
            >
              <AudioLines className="w-6 h-6" strokeWidth={1.75} />
            </button>
          )}

          {isWorkGroup(currentChat) && (
            <button
              type="button"
              data-work-open
              onClick={() => {
                soundFx.playTap();
                openWork({ chatId: currentChat.id });
              }}
              className={`${ICON_BTN} ${ICON_BTN_IDLE}`}
              title={t('work.title')}
              aria-label={t('work.title')}
            >
              <ClipboardList className="w-6 h-6" strokeWidth={1.75} />
            </button>
          )}

          {!savedNotes && !isNodeChat(currentChat) && (
            <AskGlyphButton
              testId="ask-trigger-chat"
              className={`${ICON_BTN} ${ICON_BTN_IDLE}`}
              onClick={() => openAsk(chatAsk({ chat: { ...currentChat, title: displayTitle(currentChat.title) }, meId: currentUser?.id ?? '' }, { t, locale }))}
            />
          )}

          {(!phoneShell || hasPhoneChatMenu(currentChat)) && (
            <button
              ref={moreBtnRef}
              onClick={toggleMoreMenu}
              className={`${ICON_BTN} ${menuAnchor ? 'bg-overlay text-ink' : ICON_BTN_IDLE}`}
              title={t('header.more')}
              aria-label={t('header.more')}
              aria-haspopup="menu"
              aria-expanded={!!menuAnchor}
            >
              <EllipsisVertical className="w-6 h-6" strokeWidth={1.75} />
            </button>
          )}
        </div>
      </div>

      {phoneMenuOpen && (
        <PhoneChatMenu
          chat={currentChat}
          onClose={() => setPhoneMenuOpen(false)}
          onOpenCard={() => openSheet()}
          onOpenGroupDetails={onOpenGroupDetails}
          onSearch={isSearching ? undefined : onToggleSearch}
          onCallSettings={callDoor ? () => setCallSheetOpen(true) : undefined}
        />
      )}

      {callSheetOpen && <CallSettingsSheet onClose={() => setCallSheetOpen(false)} />}

      {/* Настільне меню «Ще» (§7.1): пошук живе тут, а не рядком чипів. */}
      {!__PHONE_BUILD__ && menuAnchor &&
        createPortal(
          <>
            <div className="fixed inset-0 z-[900]" onClick={closeMenu} />
            <div
              role="menu"
              className="fixed z-[901] w-64 bg-raised text-ink rounded-card shadow-e2 p-1 f-fade-in"
              style={{ top: menuAnchor.top, right: menuAnchor.right }}
            >
              {hasPeer && (
                <button
                  onClick={() => {
                    closeMenu();
                    openSheet();
                  }}
                  className={MENU_ITEM}
                >
                  <UserRound className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                  <span className="truncate">{t('header.profile')}</span>
                </button>
              )}
              {isGroup && (
                <button
                  onClick={() => {
                    closeMenu();
                    onOpenGroupDetails();
                  }}
                  className={MENU_ITEM}
                >
                  <Users className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                  <span className="truncate">{t('header.aboutGroup')}</span>
                </button>
              )}
              <button
                onClick={() => {
                  closeMenu();
                  onToggleSearch();
                }}
                className={MENU_ITEM}
              >
                <Search className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                <span className="truncate">{t('header.searchChat')}</span>
              </button>
              {hasPinned && (
                <button
                  onClick={() => {
                    closeMenu();
                    onScrollToPinned?.();
                  }}
                  className={MENU_ITEM}
                >
                  <Pin className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                  <span className="truncate flex-1">{t('header.pinned')}</span>
                  <span className="text-caption tnum text-ink-3">{pinnedCount}</span>
                </button>
              )}
              {onOpenScheduledMessages && (
                <button
                  onClick={() => {
                    closeMenu();
                    onOpenScheduledMessages();
                  }}
                  className={MENU_ITEM}
                >
                  <Clock className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                  <span className="truncate flex-1">{t('header.scheduled')}</span>
                  {scheduledMessagesCount > 0 && <span className="text-caption tnum text-ink-3">{scheduledMessagesCount}</span>}
                </button>
              )}
              <button
                onClick={() => {
                  closeMenu();
                  onToggleSound();
                }}
                className={MENU_ITEM}
              >
                {isSoundEnabled ? (
                  <BellOff className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                ) : (
                  <Bell className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                )}
                <span className="truncate">{isSoundEnabled ? t('header.soundOff') : t('header.soundOn')}</span>
              </button>
              {callDoor && (
                <button
                  onClick={() => {
                    closeMenu();
                    setCallSheetOpen(true);
                  }}
                  className={MENU_ITEM}
                >
                  <SlidersHorizontal className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                  <span className="truncate">{t('callSettings.title')}</span>
                </button>
              )}
              <div className="my-1 h-px bg-line" />
              <button
                onClick={() => {
                  closeMenu();
                  onOpenSettings();
                }}
                className={MENU_ITEM}
              >
                <Settings2 className="w-5 h-5 text-ink-2 shrink-0" strokeWidth={1.75} />
                <span className="truncate">{t('header.settings')}</span>
              </button>
            </div>
          </>,
          document.body,
        )}

      {deviceOpen && (
        <DeviceSheet
          title={displayTitle(currentChat.title)}
          peerNodeId={currentChat.peerNodeId}
          onClose={() => setDeviceOpen(false)}
          onProfile={() => {
            setDeviceOpen(false);
            openSheet(null);
          }}
        />
      )}
      {sheet && (
        <PeerProfileSheet
          peerNodeId={currentChat.peerNodeId}
          name={displayTitle(currentChat.title)}
          avatar={face?.avatar || currentChat.avatar}
          chatId={currentChat.id}
          onClose={() => setSheet(null)}
          onSearch={isSearching ? undefined : onToggleSearch}
          onWhere={onOpenWhere}
          startOnCheck={sheet.check}
          {...peerProfileEdits(currentChat.id)}
        />
      )}
      {isWorkGroup(currentChat) && <WorkHost chatId={currentChat.id} />}
    </header>
  );
};
