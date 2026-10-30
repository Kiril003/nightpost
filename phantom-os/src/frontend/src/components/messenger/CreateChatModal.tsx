import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AtSign,
  CalendarPlus,
  Check,
  ChevronRight,
  ClipboardPaste,
  Copy,
  Loader2,
  Megaphone,
  QrCode,
  ScanLine,
  Search,
  Share2,
  UserPlus,
  Users,
  LayoutGrid,
  Camera,
  X,
} from 'lucide-react';
import { openMeetupAsk } from '../../services/nativeMeetupDraft';
import { QrScanner } from './QrScanner';
import { DirectorySearchPanel } from './DirectorySearchPanel';
import { NetworkPersonRow, useNetworkPerson } from './GlobalSearch';
import { displayTitle, UNNAMED } from './chatListFormat';
import { isPhoneShell } from './phoneShell';
import { canNavigate, navigateTo } from '../../services/nav';
import { useSpaceNav } from '../../services/spaces/spaceNav';
import { InviteCard, MyQr, MyQrName } from './InviteCard';
import { HandleSheet, useMyHandle } from './HandleSheet';
import { Avatar, AVATAR_SIZE } from './Avatar';
import { FormSheet, PrimaryButton } from './FormSheet';
import { stripMetadata } from '../../services/stripMetadata';
import { messengerApi } from '../../services/messengerApi';
import { sendContactRequest } from '../../stores/requestsStore';
import type { NodeContact, NodeIdentity } from '../../services/messengerApi';
import { soundFx } from '../../utils/messengerSound';
import { haptic } from '../../utils/haptic';
import { Lock } from 'lucide-react';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useMessengerStore } from '../../stores/messengerStore';
import { DEFAULT_CIRCLES, useCirclesStore } from '../../stores/circlesStore';
import { personFacts, personName } from './personFacts';
import { chatKeep } from '../../services/chatKeep';
import { selfAdded } from '../../services/selfAdded';
import { isOwnDeviceContact } from '../../services/peopleIdentity';
import { useTranslation } from '../../i18n/useTranslation';
import {
  InviteTooNew,
  addInvitedContact,
  copyText,
  inviteInClipboard,
  inviteShareText,
  isOwnInvite,
  isMutual,
  isWaiting,
  parseInvite,
  phoneCodeOf,
  phoneInviteName,
  phoneInvitePeer,
  speaksReach,
} from '../../utils/messengerInvite';
import type { InviteSource, ParsedInvite } from '../../utils/messengerInvite';

interface CreateChatModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConversationReady: (conversationId: string) => void;
}

/**
 * Скільки людей можна взяти, не рахуючи себе.
 *
 * Стеля групи — `PeerGroup.MAX_MEMBERS` = 32, і власний ключ теж займає місце
 * у складі. Тому вибрати можна 31: інакше міст відмовив би вже після того, як
 * людина зібрала групу.
 */
const MAX_GROUP_PICK = 31;

/** Як часто питати, чи людина вже отримала наш контакт, поки аркуш відкритий. */
const REACH_POLL_MS = 1500;

/**
 * Скільки чекати після першого кадру, перш ніж смикати міст.
 *
 * Власник 08.10: аркуші «піднімаються замерзлими». Аркуш їде самим
 * transform (form.css `f-sheet-in`, 360 мс), але в тому ж кадрі, що й
 * перший рендер, сторінка просила в моста ключ (кожен виклик /identity
 * ще й витрачає prekey), читала буфер обміну й малювала QR — і перший кадр
 * аркуша чекав на все це. Тепер аркуш піднімається порожнім, а ключ, QR і
 * буфер — після того, як він уже рушив.
 */
const LIFT_SETTLE_MS = 160;

const useAfterLift = (open: boolean): boolean => {
  const [lifted, setLifted] = useState(false);
  useEffect(() => {
    if (!open) {
      setLifted(false);
      return undefined;
    }
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const frame = (cb: () => void) =>
      typeof window.requestAnimationFrame === 'function'
        ? window.requestAnimationFrame(() => cb())
        : setTimeout(cb, 16);
    frame(() =>
      frame(() => {
        if (alive) timer = setTimeout(() => alive && setLifted(true), LIFT_SETTLE_MS);
      }),
    );
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [open]);
  return lifted;
};

/** Мій @нік під QR — або заклик його взяти, коли ніка ще немає. */
const MyHandleLine: React.FC<{ onGet: () => void }> = ({ onGet }) => {
  const { t } = useTranslation();
  const mine = useMyHandle();
  if (mine.kind === 'set') {
    return <span className="max-w-full truncate text-callout text-ink-2">@{mine.handle}</span>;
  }
  if (mine.kind === 'none') {
    return (
      <button
        type="button"
        onClick={() => {
          soundFx.playTap();
          onGet();
        }}
        className="h-11 min-h-0 px-4 rounded-full bg-accent-soft text-accent-text text-callout font-semibold flex items-center gap-1.5 active:opacity-80"
      >
        <AtSign className="w-4 h-4 shrink-0" strokeWidth={2} aria-hidden="true" />
        {t('msgr.add.getNick')}
      </button>
    );
  }
  // Ще питаємо або довідника не видно — мовчимо, а не обіцяємо.
  return null;
};

type Step = 'doors' | 'add' | 'paste' | 'added' | 'handle' | 'group' | 'directory';

/** Люди цього пристрою: мої ж пристрої — у «Моїх пристроях», не серед людей. */
const peopleOf = (rows: NodeContact[]): NodeContact[] => {
  const chats = useMessengerStore.getState().chats;
  return rows.filter((c) => !isOwnDeviceContact(c, chats));
};

export const CreateChatModal: React.FC<CreateChatModalProps> = ({
  isOpen,
  onClose,
  onConversationReady,
}) => {
  const { t, locale } = useTranslation();
  // Телефон і ПК відкривають той самий аркуш «Нова розмова» (03.10, v1.2).
  // Відрізняє їх лише одне: глобальний довідник платформи. На телефоні його
  // роль грає @нік; на ПК він має власний тихий рядок.
  const onPhone = typeof window !== 'undefined' && !!(window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  const phone = __PHONE_BUILD__ || isPhoneShell();

  // Group creation form state
  const [groupTitle, setGroupTitle] = useState('');
  /**
   * Кого беремо в групу.
   *
   * Досі веб не слав учасників узагалі — ні `member_ids`, ні `contact_ids`, —
   * тож міст відмовляв 422 `no_members`, і створення груп було зламане й на
   * ПК, просто тихіше. Група починається з людей, а не з назви.
   */
  const [groupMembers, setGroupMembers] = useState<string[]>([]);
  const [groupFace, setGroupFace] = useState('');
  const faceRef = useRef<HTMLInputElement>(null);
  /** null — ще не питали; [] — спитали, і в списку нікого. Це різні стани. */
  const [contacts, setContacts] = useState<NodeContact[] | null>(null);
  const [contactsRefused, setContactsRefused] = useState<string | null>(null);

  // Запрошення, вставлене руками чи з буфера
  const [text, setText] = useState('');
  const [parsed, setParsed] = useState<ParsedInvite | null>(null);
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [address, setAddress] = useState('');
  const [clipboardInvite, setClipboardInvite] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [phoneStep, setPhoneStep] = useState<Step>('doors');
  const [asChannel, setAsChannel] = useState(false);
  const [query, setQuery] = useState('');
  const fieldRef = useRef<HTMLTextAreaElement | null>(null);

  // «Додати людину»
  const [scanning, setScanning] = useState(false);
  const [me, setMe] = useState<NodeIdentity | null>(null);
  const [meFailed, setMeFailed] = useState<string | null>(null);
  const meAsked = useRef(false);
  /** Ще одна спроба взяти мій ключ — після відмови моста (холодний старт). */
  const [meTry, setMeTry] = useState(0);
  /** Скан уже є, міст додає людину: на місці камери — «Додаю…», а не тиша. */
  const [adding, setAdding] = useState(false);
  const selfId = useMessengerStore((s) => s.currentUser.id);
  const myName = useMessengerStore((s) => s.currentUser.name);
  const myAvatar = useMessengerStore((s) => s.currentUser.avatar);
  /** Відкриття аркуша: відповідь моста з минулого відкриття сюди не лягає. */
  const session = useRef(0);
  const [nick, setNick] = useState('');
  const [nickFocused, setNickFocused] = useState(false);
  const [handed, setHanded] = useState<'shared' | 'copied' | null>(null);
  const handedTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [showRaw, setShowRaw] = useState(false);
  const [added, setAdded] = useState<{ contact: NodeContact; conversationId: string } | null>(null);

  useEscapeClose(isOpen, onClose);
  const lifted = useAfterLift(isOpen);
  // Той самий пошук за @ніком, що й у шапці списку, — не окремий аркуш.
  const network = useNetworkPerson([], query, isOpen);

  // «Створити групу» з порожніх «Груп» у списку — одразу до збору групи.
  const startAt = useMessengerStore((s) => s.createChatStart);
  const knownChats = useMessengerStore((s) => s.chats);
  const storeCircles = useCirclesStore((s) => s.circles);
  const knownCircles = storeCircles.length > 0 ? storeCircles : DEFAULT_CIRCLES;
  useEffect(() => {
    if (!isOpen || !startAt) return;
    if (startAt === 'add') {
      setPhoneStep('add');
      return;
    }
    setAsChannel(startAt === 'channel');
    setPhoneStep('group');
  }, [isOpen, startAt]);

  const reset = useCallback(() => {
    session.current += 1;
    setGroupTitle('');
    setGroupMembers([]);
    setGroupFace('');
    setAsChannel(false);
    setText('');
    setParsed(null);
    setName('');
    setNameTouched(false);
    setAddress('');
    setClipboardInvite(null);
    setScanning(false);
    setError(null);
    setPhoneStep('doors');
    setQuery('');
    setMe(null);
    setMeFailed(null);
    meAsked.current = false;
    setMeTry(0);
    setAdding(false);
    setNick('');
    setNickFocused(false);
    setHanded(null);
    setShowRaw(false);
    setAdded(null);
  }, []);

  useEffect(() => {
    if (!isOpen) reset();
  }, [isOpen, reset]);

  useEffect(
    () => () => {
      if (handedTimer.current) clearTimeout(handedTimer.current);
    },
    [],
  );

  useEffect(() => {
    if (!isOpen) return;
    let alive = true;

    // Люди для групи — з власного списку цього пристрою, і нізвідки більше.
    // Довідника всіх людей не існує за побудовою; групу можна зібрати лише з
    // тих, кого вже додано й чий ключ тут є.
    setContactsRefused(null);
    void messengerApi
      .listContacts()
      .then((rows) => {
        if (alive) setContacts(peopleOf(rows));
      })
      .catch((e: unknown) => {
        // «Не спитали» і «нікого немає» — різні речі: список лишається null,
        // а причина стає видимою, замість порожньої панелі без пояснення.
        if (!alive) return;
        setContacts(null);
        setContactsRefused(
          e instanceof Error && e.message ? e.message : t('createChatModal.loadFailed'),
        );
      });
    return () => {
      alive = false;
    };
  }, [isOpen]);

  // Буфер обміну — синхронний виклик моста; лише коли аркуш уже рушив.
  useEffect(() => {
    if (!isOpen || !lifted) return undefined;
    let alive = true;
    void inviteInClipboard().then((found) => {
      if (alive && found) setClipboardInvite(found);
    });
    return () => {
      alive = false;
    };
  }, [isOpen, lifted]);

  useEffect(() => {
    const raw = text.trim();
    if (!raw) {
      setParsed(null);
      return;
    }
    let found: ParsedInvite | null = null;
    try {
      found = parseInvite(raw);
      setError(null);
    } catch (err) {
      setParsed(null);
      setError(err instanceof InviteTooNew ? t('msgr.add.tooNew') : t('msgr.add.notInvite'));
      return;
    }
    setParsed(found);
    if (!found) {
      setError(t('msgr.add.notInvite'));
      return;
    }
    if (found.kind === 'invite') {
      if (found.address) setAddress(found.address);
      if (!nameTouched) setName(found.name);
    }
  }, [text, nameTouched, t]);

  const pickable = contacts ?? [];
  /** Людей ще нема — тоді перший екран аркуша і є «Додати людину». */
  const addHome = contacts !== null && !contactsRefused && pickable.length === 0;
  const addVisible = phoneStep === 'add' || (phoneStep === 'doors' && addHome);
  const nickLookup = useNetworkPerson([], nick, isOpen && addVisible);

  // Мій ключ — раз на відкриття (кожен /identity витрачає prekey), і лише
  // коли «Додати людину» справді на екрані й аркуш уже піднявся. Відмова
  // (ключі ще готуються на холодному старті) знімає «вже питали»: інакше QR,
  // «Надіслати» і «Копіювати» лишались мертві до закриття аркуша.
  const wantMe = isOpen && lifted && addVisible && phone;
  useEffect(() => {
    if (!wantMe || meAsked.current) return;
    meAsked.current = true;
    const mine = session.current;
    const refused = () => {
      if (session.current !== mine) return;
      meAsked.current = false;
      setMeFailed(t('msgr.add.notReady'));
    };
    messengerApi
      .identity()
      .then((got) => {
        if (session.current !== mine) return;
        setMe(got);
        if (!got.link && !got.compact) refused();
      })
      .catch(refused);
  }, [wantMe, meTry, t]);
  const retryMe = () => {
    soundFx.playTap();
    setMeFailed(null);
    setMeTry((n) => n + 1);
  };

  // Поки людина не отримала наш контакт — питаємо міст, чи вже.
  const waitingFor = added && isWaiting(added.contact) ? added.contact.id : null;
  useEffect(() => {
    if (!isOpen || !waitingFor) return undefined;
    const timer = setInterval(() => {
      messengerApi
        .listContacts()
        .then((rows) => {
          setContacts(peopleOf(rows));
          const fresh = rows.find((c) => c.id === waitingFor);
          if (fresh) setAdded((prev) => (prev && prev.contact.id === waitingFor ? { ...prev, contact: fresh } : prev));
        })
        .catch(() => {
          /* міст мовчить — спитаємо наступного разу */
        });
    }, REACH_POLL_MS);
    return () => clearInterval(timer);
  }, [isOpen, waitingFor]);

  // Ключі зійшлись — сильніший тик рівно тоді, коли між двома зʼявляється замок.
  const metFor = useRef<string | null>(null);
  const mutualNow = added && isMutual(added.contact) ? added.contact.id : null;
  useEffect(() => {
    if (!mutualNow || metFor.current === mutualNow) return undefined;
    metFor.current = mutualNow;
    const timer = setTimeout(() => haptic('success'), MEET_LOCK_MS);
    return () => clearTimeout(timer);
  }, [mutualNow]);

  if (!isOpen) return null;

  const close = () => {
    soundFx.playTap();
    reset();
    onClose();
  };

  const go = (step: Step, channel = false) => {
    setError(null);
    if (step === 'group') setAsChannel(channel);
    setNickFocused(false);
    if (step !== 'add' && step !== 'doors') setScanning(false);
    setPhoneStep(step);
  };
  const toAdd = () => go(addHome ? 'doors' : 'add');

  const pasteFromClipboard = () => {
    if (!clipboardInvite) return;
    soundFx.playTap();
    setText(clipboardInvite);
    setClipboardInvite(null);
  };

  // Group submit handler
  const handleCreateGroup = async () => {
    if (!groupTitle.trim() || groupMembers.length === 0 || busy) return;
    setBusy(true);
    setError(null);
    try {
      soundFx.playSend();
      const newChatId = await useMessengerStore
        .getState()
        .createGroup(groupTitle.trim(), 'all', '', '', groupMembers, asChannel ? 'channel' : 'group');
      // Фото — тим самим шляхом, що й «Поставити» в налаштуваннях групи; відмова фото не скасовує групу.
      if (groupFace) await useMessengerStore.getState().setGroupFace(newChatId, groupFace).catch(() => {});
      reset();
      onConversationReady(newChatId);
    } catch (err: any) {
      setError(err?.message || t('createChatModal.groupFailed'));
    } finally {
      setBusy(false);
    }
  };

  // Імʼя, яким людина назвалась у запрошенні (конверт ПК або код телефона).
  const claimedName =
    parsed?.kind === 'invite' ? parsed.name.trim() : parsed?.kind === 'compact' ? phoneInviteName(parsed.compact) : '';
  // Людина зі свого списку: її розмова, а якщо ще немає — нова з її ключем.
  const openWith = async (c: NodeContact) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const list = await messengerApi.listConversations();
      const existing = list.find((x) => x.contact_id === c.id);
      const conversation =
        existing ??
        (await messengerApi.createConversation({ title: c.display_name, kind: 'dm', circle: 'all', contact_id: c.id }));
      if (!existing) chatKeep.add(conversation.id);
      selfAdded.mark(conversation.id);
      soundFx.playTap();
      reset();
      onConversationReady(conversation.id);
    } catch {
      setError(t('createChatModal.openFailed'));
    } finally {
      setBusy(false);
    }
  };

  /**
   * Додати людину за запрошенням — з камери, з буфера чи вставлене руками.
   *
   * Одного сканування досить: додаємо одразу, без другого кроку. Якщо міст
   * каже, що людина вже на звʼязку, — одразу розмова. Якщо ні — чесно
   * показуємо «чекаємо, поки вона отримає ваш контакт»: писати можна вже
   * зараз, лист чекатиме в дорозі.
   */
  const addPerson = async (found: ParsedInvite, source: InviteSource | undefined, typed: string, addr: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    const mine = session.current;
    try {
      const claimed = found.kind === 'invite' ? found.name.trim() : '';
      const fromPhone = found.kind === 'compact' ? phoneInviteName(found.compact) : '';
      // Імені немає — телефону йде порожнє, а не заглушка: міст записав би її
      // як імʼя від власника, і справжнє імʼя з профілю людини вже ніколи б
      // її не перебило (PeerRoster.absorbProfile ↔ nameByOwner). Вузол ПК
      // порожнього не приймає — йому чесне «Без імені».
      const display = typed.trim() || claimed || fromPhone || (__PHONE_BUILD__ || isPhoneShell() ? '' : UNNAMED);
      const key = found.kind === 'bundle' ? { bundle: found.bundle } : { compact: found.compact };
      const contact = await addInvitedContact(display, key, addr.trim(), source);
      sendContactRequest(contact.id);
      const existing = (await messengerApi.listConversations()).find((c) => c.contact_id === contact.id);
      let conversation =
        existing ??
        (await messengerApi.createConversation({
          title: contact.display_name,
          kind: 'dm',
          circle: 'all',
          contact_id: contact.id,
        }));
      if (typed.trim() && conversation.title !== typed.trim()) {
        try {
          conversation = await messengerApi.renameConversation(conversation.id, typed.trim());
        } catch {
          /* назва лишиться з контакту */
        }
      }
      chatKeep.add(conversation.id);
      selfAdded.mark(conversation.id);
      if (session.current !== mine) {
        // Аркуш закрили, поки міст додавав: людина й розмова вже є — хай
        // стоять у списку, а не зʼявляються лише після наступного оновлення.
        void Promise.resolve()
          .then(() => useMessengerStore.getState().refreshConversations())
          .catch(() => {});
        return;
      }
      soundFx.playSend();
      // Скан камерою, і міст каже, що ключі зійшлись, — це момент: аркуш лишається.
      if (!isWaiting(contact) && !(source === 'camera' && isMutual(contact))) {
        reset();
        onConversationReady(conversation.id);
        return;
      }
      // Розмова вже є — хай стоїть і в списку, навіть якщо аркуш закриють.
      void Promise.resolve()
        .then(() => useMessengerStore.getState().refreshConversations())
        .catch(() => {});
      // І людина вже є в списку цього аркуша: «Назад» веде не до першого
      // запуску з «Поки що нікого», а «Нова група» — до неї. Рядок моста
      // кладемо одразу, а тоді питаємо весь список наново.
      setContacts((prev) => (prev === null ? prev : peopleOf([...prev.filter((c) => c.id !== contact.id), contact])));
      void messengerApi
        .listContacts()
        .then((rows) => {
          if (session.current === mine) setContacts(peopleOf(rows));
        })
        .catch(() => {
          /* лишається рядок із відповіді моста */
        });
      setAdded({ contact, conversationId: conversation.id });
      setPhoneStep('added');
    } catch {
      if (session.current === mine) setError(t('msgr.add.failed'));
    } finally {
      busyRef.current = false;
      setBusy(false);
      setAdding(false);
    }
  };

  /**
   * Сканер щось прочитав. Не запрошення (меню, Wi-Fi поруч із QR людини) —
   * камера світить далі, а під нею — підказка; `false` каже сканеру шукати
   * далі й цей самий QR більше не пропонувати.
   */
  const onScanned = (raw: string): boolean => {
    let found: ParsedInvite | null = null;
    try {
      found = parseInvite(raw);
    } catch (err) {
      setError(err instanceof InviteTooNew ? t('msgr.add.tooNew') : t('msgr.add.notInvite'));
      return false;
    }
    if (!found) {
      setError(t('msgr.add.notInvite'));
      return false;
    }
    haptic('tap');
    soundFx.playChime();
    setScanning(false);
    setAdding(true);
    void addPerson(found, 'camera', '', found.kind === 'invite' ? found.address : '');
    return true;
  };

  // Мій код: у QR — сирий (D6), у «Надіслати» — https-посилання і код запасом.
  const phoneCode = phoneCodeOf(me);
  const myCode = phone ? (phoneCode ?? (me?.link || me?.compact || null)) : null;
  const shareBody = myCode ? (phoneCode ? inviteShareText(phoneCode, locale) : myCode) : null;
  // Обіцянка «одного сканування досить» — лише від моста, що її тримає (B0).
  const oneScan = speaksReach(me, contacts);
  // Своє ж запрошення в буфері (щойно скопійоване, щоб надіслати) не
  // пропонуємо «відкрити»: це закінчилось би «не вдалося додати».
  const clipOffer =
    clipboardInvite && !isOwnInvite(clipboardInvite, [phoneCode ? phoneInvitePeer(phoneCode) : null, selfId])
      ? clipboardInvite
      : null;

  const flash = (kind: 'shared' | 'copied') => {
    setHanded(kind);
    if (handedTimer.current) clearTimeout(handedTimer.current);
    handedTimer.current = setTimeout(() => setHanded(null), 2200);
  };

  const copyLink = async () => {
    if (!shareBody) return;
    soundFx.playTap();
    if (await copyText(shareBody)) {
      flash('copied');
      return;
    }
    setError(t('msgr.add.copyFailed'));
    setShowRaw(true);
  };

  const shareLink = async () => {
    if (!shareBody) return;
    soundFx.playTap();
    const bridge = (window as unknown as { PhantomBridge?: { shareText?: (t: string, title?: string) => boolean } })
      .PhantomBridge;
    try {
      if (bridge && typeof bridge.shareText === 'function' && bridge.shareText(shareBody, t('msgr.add.shareTitle'))) {
        flash('shared');
        return;
      }
      if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
        await navigator.share({ text: shareBody });
        flash('shared');
        return;
      }
    } catch {
      return; // людина передумала — це не помилка
    }
    await copyLink();
  };

  const needle = query.trim().replace(/^@/, '').toLocaleLowerCase('uk-UA');
  // Імʼя — те саме, що в списку розмов: «bd6e·d7af» тут читалось як імʼя (A52, 02.10).
  const matching = pickable.filter((c) => !needle || displayTitle(c.display_name).toLocaleLowerCase('uk-UA').includes(needle));

  const pickedPeople = groupMembers.map((id) => pickable.find((c) => c.id === id)).filter((c): c is NodeContact => !!c);

  const peopleList = (onPick: (c: NodeContact) => void, picking: boolean) =>
    contactsRefused ? (
      <p className="px-4 py-2 text-callout text-danger">{contactsRefused}</p>
    ) : contacts === null ? (
      <p className="px-4 py-2 text-callout text-ink-3">{t('createChatModal.loading')}</p>
    ) : matching.length === 0 ? (
      query.trim().startsWith('@') ? null : (
        <p className="px-4 py-2 text-callout text-ink-3">{needle ? t('createChatModal.noNameMatch') : t('createChatModal.nobodyYet')}</p>
      )
    ) : (
      <ul>
        {matching.map((c) => {
          const ready = c.session_ready;
          const picked = groupMembers.includes(c.id);
          const full = picking && !picked && groupMembers.length >= MAX_GROUP_PICK;
          const facts = personFacts(c.peer_node_id, knownChats, knownCircles, t);
          const shown = personName(displayTitle(c.display_name), t);
          return (
            <li key={c.id}>
              <button
                type="button"
                role={picking ? 'checkbox' : undefined}
                aria-checked={picking ? picked : undefined}
                disabled={(picking && !ready) || full || (!picking && busy)}
                onClick={() => onPick(c)}
                className="w-full min-h-[64px] px-4 py-2 flex items-center gap-3.5 text-left active:bg-raised disabled:active:bg-transparent"
              >
                <Avatar
                  name={shown}
                  src={c.avatar_thumb || facts.avatar}
                  tone={c.peer_node_id}
                  radius="rounded-full"
                  className={`${AVATAR_SIZE.row} ${picking && !ready ? 'opacity-50' : ''}`}
                />
                <span className="flex-1 min-w-0">
                  <span className={`block truncate text-headline ${!picking || ready ? 'text-ink' : 'text-ink-3'}`}>
                    {shown}
                  </span>
                  {facts.line && <span data-person-line className="block truncate text-footnote text-ink-2">{facts.line}</span>}
                  {!ready && <span className="block text-caption text-ink-3">{t('createChatModal.notReachable')}</span>}
                </span>
                {picking && (
                  <span
                    aria-hidden="true"
                    className={`w-6 h-6 shrink-0 rounded-full flex items-center justify-center ${
                      picked ? 'bg-ink text-canvas' : 'border-2 border-f-ink-3'
                    }`}
                  >
                    {picked && <Check className="w-4 h-4" strokeWidth={2.5} />}
                  </span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    );

  const searchField = (placeholder: string) => (
    <div className="mx-4 relative">
      <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="w-full h-10 pl-9 pr-4 rounded-control bg-overlay text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
      />
    </div>
  );

  const quietRow = (label: string, onClick: () => void, icon?: React.ReactNode) => (
    <button
      type="button"
      onClick={() => {
        soundFx.playTap();
        onClick();
      }}
      className="w-full min-h-12 px-4 flex items-center gap-3 text-left text-callout text-ink-2 active:bg-overlay"
    >
      {icon && <span className="w-5 shrink-0 flex items-center justify-center text-ink-3">{icon}</span>}
      <span className="flex-1 min-w-0 truncate">{label}</span>
      <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
    </button>
  );

  const clipboardBanner = (testId: string, then: () => void) =>
    clipOffer && (
      <div className="p-1.5 pl-4 rounded-full bg-overlay flex items-center gap-3" data-testid={testId}>
        <ClipboardPaste className="w-4 h-4 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
        <span className="flex-1 min-w-0 truncate text-callout text-ink">{t('msgr.add.clipboard')}</span>
        <button
          type="button"
          onClick={() => {
            pasteFromClipboard();
            then();
          }}
          className="h-11 min-h-0 px-4 rounded-full bg-accent text-on-accent text-callout font-semibold active:bg-accent-press"
        >
          {t('msgr.add.open')}
        </button>
      </div>
    );

  const segment = (on: boolean) =>
    `h-10 min-h-0 min-w-0 px-2 rounded-full text-callout font-semibold flex items-center justify-center gap-1.5 transition-colors duration-quick ${
      on ? 'bg-line-strong text-ink' : 'text-ink-2 active:text-ink'
    }`;

  // «Додати людину» — одне місце: мій QR / камера, посилання, @нік.
  const addBody = (
    <div className="px-4 pb-3" data-testid="add-person">
      <div className="sticky top-0 z-10 py-1 bg-raised">
        <div className="mx-auto w-full max-w-[320px] p-1 grid grid-cols-2 gap-1 rounded-full bg-overlay" role="group">
          <button
            type="button"
            aria-pressed={!scanning}
            disabled={adding}
            onClick={() => {
              soundFx.playTap();
              // Підказка «це не запрошення» стосувалась камери — з нею й іде.
              if (scanning) setError(null);
              setScanning(false);
              setNickFocused(false);
            }}
            className={segment(!scanning)}
          >
            <QrCode className="w-4 h-4 shrink-0" strokeWidth={2} aria-hidden="true" />
            <span className="truncate">{t('msgr.add.myQr')}</span>
          </button>
          <button
            type="button"
            aria-pressed={scanning}
            disabled={adding}
            onClick={() => {
              soundFx.playTap();
              setError(null);
              setScanning(true);
              setNickFocused(false);
            }}
            className={segment(scanning)}
          >
            <ScanLine className="w-4 h-4 shrink-0" strokeWidth={2} aria-hidden="true" />
            <span className="truncate">{t('msgr.add.scan')}</span>
          </button>
        </div>
      </div>

      {(!nickFocused || scanning || adding) && (
        phone || scanning || adding ? (
          <div className="mt-4 mx-auto w-full max-w-[272px]">
            {scanning ? (
              <>
                <QrScanner onFound={onScanned} />
                {error && (
                  <p data-testid="scan-note" role="status" className="mt-2 text-center text-callout text-ink-2">
                    {error}
                  </p>
                )}
              </>
            ) : adding ? (
              // Скан прочитано, міст додає (ключ, список, розмова). На холодному
              // мосту чи повільному ретрансляторі це секунди — і без цього стану
              // аркуш виглядав так, ніби скан не вдався.
              <div
                data-testid="add-person-adding"
                role="status"
                className="w-full aspect-square rounded-sheet bg-overlay flex flex-col items-center justify-center gap-3 text-callout text-ink-2"
              >
                <Loader2 className="w-6 h-6 animate-spin text-ink-3" strokeWidth={2} aria-hidden="true" />
                <span>{t('msgr.add.adding')}</span>
              </div>
            ) : (
              <MyQr code={myCode} failed={meFailed} onRetry={retryMe} />
            )}
          </div>
        ) : (
          // ПК: свій конверт PHI з адресою вузла — картка ПК як і була.
          <div className="mt-4">
            <InviteCard />
          </div>
        )
      )}

      {phone && !scanning && !adding && !nickFocused && (
        <div className="mt-2 min-h-11 flex flex-col items-center gap-1">
          {me && <MyQrName me={me} onRenamed={setMe} />}
          {lifted && <MyHandleLine onGet={() => go('handle')} />}
        </div>
      )}

      {oneScan && !nickFocused && (
        <span data-testid="one-scan" className="sr-only">
          {t('msgr.add.oneScan')}
        </span>
      )}

      {phone && (
        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            data-testid="share-invite"
            onClick={() => void shareLink()}
            disabled={!shareBody}
            className={`flex-1 min-w-0 h-12 min-h-0 px-4 rounded-full ${
              clipOffer ? 'bg-overlay text-ink active:bg-surface' : 'bg-accent text-on-accent active:bg-accent-press'
            } text-headline flex items-center justify-center gap-2 transition-colors duration-instant disabled:opacity-40`}
          >
            <Share2 className="w-5 h-5 shrink-0" strokeWidth={2} aria-hidden="true" />
            <span className="truncate">{handed === 'shared' ? t('msgr.add.shared') : t('msgr.add.share')}</span>
          </button>
          <button
            type="button"
            onClick={() => void copyLink()}
            disabled={!shareBody}
            aria-label={handed === 'copied' ? t('msgr.add.copied') : t('msgr.add.copy')}
            className="w-12 h-12 min-h-0 min-w-0 shrink-0 rounded-full bg-overlay text-ink flex items-center justify-center active:bg-surface disabled:opacity-40"
          >
            {handed === 'copied' ? (
              <Check className="w-5 h-5" strokeWidth={2.25} aria-hidden="true" />
            ) : (
              <Copy className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
            )}
          </button>
        </div>
      )}
      {showRaw && shareBody && (
        <textarea
          readOnly
          value={shareBody}
          rows={3}
          onFocus={(e) => e.currentTarget.select()}
          className="mt-3 w-full px-4 py-3 text-callout rounded-card bg-overlay text-ink-2 focus:outline-none resize-none"
        />
      )}

      <label className="mt-3 h-12 pl-4 pr-3 rounded-full bg-overlay flex items-center gap-2 focus-within:outline focus-within:outline-2 focus-within:outline-accent-line">
        <AtSign className="w-[18px] h-[18px] shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
        <input
          type="text"
          value={nick}
          onChange={(e) => setNick(e.target.value)}
          onFocus={() => setNickFocused(true)}
          onBlur={(e) => { if (!e.currentTarget.value.trim()) setNickFocused(false); }}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="search"
          aria-label={t('msgr.add.nick')}
          placeholder={t('msgr.add.nickPlaceholder')}
          className="flex-1 min-w-0 h-12 bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline-none"
        />
      </label>
      {nickLookup.kind === 'found' && (
        <ul className="mt-1 -mx-4">
          <NetworkPersonRow
            person={nickLookup.person}
            onOpenChat={(id) => {
              reset();
              onConversationReady(id);
            }}
          />
        </ul>
      )}
      {nickLookup.kind !== 'found' && nickLookup.kind !== 'off' && (
        <p className="mt-2 px-4 text-callout text-ink-3" role="status">
          {nickLookup.kind === 'none'
            ? t('msgr.add.nickNone')
            : nickLookup.kind === 'down'
              ? t('msgr.add.nickDown')
              : t('msgr.add.searching')}
        </p>
      )}

      {clipOffer && <div className="mt-3">{clipboardBanner('clipboard-invite', () => go('paste'))}</div>}
      {error && !scanning && (
        <p className="mt-3 text-center text-callout text-danger" role="alert">
          {error}
        </p>
      )}

      <div className="mt-2 -mx-4">
        {quietRow(t('msgr.add.paste'), () => go('paste'), <ClipboardPaste className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />)}
        {!onPhone && quietRow(t('createChatModal.globalDirectory'), () => go('directory'))}
        {addHome && quietRow(t('msgr.add.newGroup'), () => go('group'), <Users className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />)}
        {addHome && quietRow(t('groupsTg.newChannel'), () => go('group', true), <Megaphone className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />)}
      </div>
    </div>
  );

  const phoneHomeBody = (
    <div className="pb-2">
      <div className="mx-4 mt-1 rounded-card bg-overlay overflow-hidden">
        {canNavigate() && (
          <>
            <button
              type="button"
              data-testid="door-scan"
              onClick={() => {
                soundFx.playTap();
                close();
                navigateTo('scan');
              }}
              className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
            >
              <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden="true">
                <ScanLine className="w-5 h-5" strokeWidth={1.75} />
              </span>
              <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('msgV2.scan')}</span>
              <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
            </button>
            <div aria-hidden="true" className="ml-16 h-px bg-line" />
          </>
        )}
        <button
          type="button"
          data-testid="door-group"
          onClick={() => {
            soundFx.playTap();
            go('group');
          }}
          className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden="true">
            <Users className="w-5 h-5" strokeWidth={1.75} />
          </span>
          <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('msgr.add.newGroup')}</span>
          <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
        </button>
        <div aria-hidden="true" className="ml-16 h-px bg-line" />
        <button
          type="button"
          data-testid="door-channel"
          onClick={() => {
            soundFx.playTap();
            go('group', true);
          }}
          className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden="true">
            <Megaphone className="w-5 h-5" strokeWidth={1.75} />
          </span>
          <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('groupsTg.newChannel')}</span>
          <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
        </button>
        <div aria-hidden="true" className="ml-16 h-px bg-line" />
        <button
          type="button"
          onClick={() => {
            soundFx.playTap();
            go('add');
          }}
          className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink flex items-center justify-center" aria-hidden="true">
            <UserPlus className="w-5 h-5" strokeWidth={1.75} />
          </span>
          <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('msgr.add.door')}</span>
          <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
        </button>
        <div aria-hidden="true" className="ml-16 h-px bg-line" />
        <button
          type="button"
          data-testid="door-space"
          onClick={() => {
            soundFx.playTap();
            close();
            useSpaceNav.getState().push({ s: 'create' });
          }}
          className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden="true">
            <LayoutGrid className="w-5 h-5" strokeWidth={1.75} />
          </span>
          <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('space.new')}</span>
          <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
        </button>
        <div aria-hidden="true" className="ml-16 h-px bg-line" />
        <button
          type="button"
          data-testid="door-plan"
          onClick={() => {
            soundFx.playTap();
            close();
            openMeetupAsk({ kind: 'meetup', chatId: null });
          }}
          className="w-full min-h-[56px] px-3 py-2 flex items-center gap-3 text-left active:bg-surface"
        >
          <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center" aria-hidden="true">
            <CalendarPlus className="w-5 h-5" strokeWidth={1.75} />
          </span>
          <span className="flex-1 min-w-0 truncate text-headline text-ink">{t('meetupDoor.plan')}</span>
          <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={2.4} aria-hidden="true" />
        </button>
      </div>

      <div className="mt-3">{searchField(t('createChatModal.nameOrNick'))}</div>
      {clipOffer && <div className="mx-4 mt-3">{clipboardBanner('clipboard-invite', () => go('paste'))}</div>}

      {pickable.length > 0 && !needle && <h3 className="px-4 pt-5 pb-1 text-footnote font-semibold text-ink-3">{t('createChatModal.yourPeople')}</h3>}
      <div className={needle ? 'pt-1' : ''}>{(pickable.length > 0 || contacts === null || contactsRefused) && peopleList((c) => void openWith(c), false)}</div>
      {network.kind === 'found' && (
        <ul>
          <NetworkPersonRow
            person={network.person}
            onOpenChat={(id) => {
              reset();
              onConversationReady(id);
            }}
          />
        </ul>
      )}
      {network.kind === 'none' && matching.length === 0 && (
        <p className="px-4 pt-3 text-callout text-ink-2">{t('createChatModal.noNick')}</p>
      )}

      {/* Глобальний довідник платформи — лише на ПК: на телефоні його роль
          грає @нік. Власний крок аркуша з «Назад». */}
      {!onPhone && <div className="mt-3 border-t border-line pt-1">{quietRow(t('createChatModal.globalDirectory'), () => go('directory'))}</div>}
      {error && <p className="px-4 mt-3 text-callout text-danger">{error}</p>}
    </div>
  );

  const phoneGroupBody = (
    <div className="pb-2">
      <div className="px-4 flex items-center gap-3">
        <button
          type="button"
          data-testid="new-group-face"
          aria-label={t('createChatModal.photo')}
          onClick={() => faceRef.current?.click()}
          className="relative w-14 h-14 shrink-0 rounded-control"
        >
          {groupFace || groupTitle.trim() ? (
            <Avatar src={groupFace || undefined} name={groupTitle.trim() || '·'} tone={groupTitle.trim() || 'new-group'} radius="rounded-control" className="w-14 h-14 text-[22px]" />
          ) : (
            <span data-testid="new-group-tile" aria-hidden="true" className="w-14 h-14 rounded-control bg-raised text-ink-2 flex items-center justify-center">
              {asChannel ? <Megaphone className="w-6 h-6" strokeWidth={1.75} /> : <Users className="w-6 h-6" strokeWidth={1.75} />}
            </span>
          )}
          <span aria-hidden="true" className="absolute -right-1 -bottom-1 w-6 h-6 rounded-full bg-ink text-canvas border-2 border-surface flex items-center justify-center">
            <Camera className="w-3.5 h-3.5" strokeWidth={2} />
          </span>
        </button>
        <input
          ref={faceRef}
          type="file"
          accept="image/*"
          hidden
          data-testid="new-group-face-input"
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = '';
            if (!f) return;
            void stripMetadata(f).then((clean) => {
              const r = new FileReader();
              r.onload = () => setGroupFace(String(r.result || ''));
              r.readAsDataURL(clean);
            });
          }}
        />
        <input
          id="new-group-title"
          type="text"
          value={groupTitle}
          onChange={(e) => setGroupTitle(e.target.value)}
          placeholder={asChannel ? t('groupsTg.channelName') : t('createChatModal.groupNamePlaceholder')}
          aria-label={asChannel ? t('groupsTg.channelName') : t('createChatModal.groupName')}
          autoFocus
          className="flex-1 min-w-0 h-12 px-4 rounded-full bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
        />
      </div>
      {pickable.length > 0 && <div className="mt-4">{searchField(t('createChatModal.findAmong'))}</div>}
      {pickedPeople.length > 0 && (
        <ul data-testid="picked-strip" className="mt-4 px-4 flex gap-2 overflow-x-auto no-scrollbar">
          {pickedPeople.map((c) => {
            const nm = displayTitle(c.display_name);
            return (
              <li key={c.id} className="shrink-0">
                <button
                  type="button"
                  aria-label={t('createChatModal.unpick', { name: nm })}
                  onClick={() => setGroupMembers((prev) => prev.filter((x) => x !== c.id))}
                  className="h-11 pl-1 pr-2.5 rounded-full bg-raised flex items-center gap-2 text-subhead text-ink active:bg-overlay"
                >
                  <Avatar name={nm} src={c.avatar_thumb} tone={c.peer_node_id} radius="rounded-full" className="w-8 h-8 text-[13px]" />
                  <span className="max-w-[96px] truncate">{nm.split(/\s+/)[0]}</span>
                  <X className="w-4 h-4 text-ink-3" strokeWidth={2} aria-hidden="true" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <p className="px-4 pt-5 pb-1 text-footnote font-semibold text-ink-3">
        {asChannel ? t('groupsTg.firstSubs') : t('createChatModal.people')}{groupMembers.length > 0 && <span className="text-ink-2"> · {groupMembers.length}</span>}
      </p>
      {contacts !== null && pickable.length === 0 && !contactsRefused ? (
        <div className="px-4">
          <p className="text-callout text-ink-2">{t('createChatModal.emptyList')}</p>
          <button type="button" onClick={toAdd} className="mt-1 h-11 -ml-1 px-1 text-headline text-accent-text">
            {t('msgr.add.door')}
          </button>
        </div>
      ) : (
        peopleList((c) => {
          soundFx.playTap();
          const picked = groupMembers.includes(c.id);
          setGroupMembers((prev) => (picked ? prev.filter((x) => x !== c.id) : [...prev, c.id]));
        }, true)
      )}
      {error && <p className="px-4 mt-3 text-callout text-danger">{error}</p>}
    </div>
  );

  // Вставлене запрошення: людина — куля тону з її ключа, імʼя — поле.
  const pasteBody = (
    <div className="px-4 pb-4 space-y-3" data-testid="add-person-paste">
      {clipOffer && !text.trim() && clipboardBanner('clipboard-invite-paste', () => {})}
      {parsed ? (
        <div className="pt-2 flex flex-col items-center text-center" data-testid="incoming-invite">
          <Avatar
            name={name.trim() || claimedName || '?'}
            tone={text.trim()}
            radius="rounded-full"
            className="w-24 h-24 text-[36px]"
          />
          <input
            value={name}
            onChange={(e) => {
              setNameTouched(true);
              setName(e.target.value);
            }}
            placeholder={claimedName || t('msgr.add.nameLabel')}
            aria-label={t('msgr.add.nameLabel')}
            className="mt-4 w-full h-12 px-4 rounded-full bg-overlay text-center text-headline text-ink placeholder:text-ink-3 placeholder:font-normal focus:outline focus:outline-2 focus:outline-accent-line"
          />
          <button
            type="button"
            onClick={() => {
              setText('');
              setName('');
              setNameTouched(false);
            }}
            className="mt-1 h-11 px-3 text-callout text-ink-3"
          >
            {t('msgr.add.other')}
          </button>
        </div>
      ) : (
        <textarea
          ref={fieldRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={t('msgr.add.pastePlaceholder')}
          aria-label={t('msgr.add.pasteLabel')}
          rows={3}
          className="w-full px-4 py-3 text-body rounded-card bg-overlay text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line resize-none"
        />
      )}
      {error && <p className="text-callout text-danger">{error}</p>}
    </div>
  );

  const addedName = added ? personName(displayTitle(added.contact.display_name), t) : '';
  const addedWaiting = added ? isWaiting(added.contact) : false;
  const addedBody = added && (
    <div className="px-4 pt-6 pb-2 flex flex-col items-center text-center" data-testid="person-added">
      <KeyMeet
        met={!addedWaiting}
        me={<Avatar name={myName} src={myAvatar} tone={selfId} radius="rounded-full" className="w-20 h-20 text-[30px]" />}
        them={
          <Avatar
            name={addedName}
            src={added.contact.avatar_thumb}
            tone={added.contact.peer_node_id}
            radius="rounded-full"
            className="w-20 h-20 text-[30px]"
          />
        }
      />
      <p className="mt-4 max-w-full truncate text-title-2 text-ink">{addedName}</p>
      {addedWaiting && (
        <div className="mt-2 max-w-full flex items-center justify-center gap-2">
          <span aria-hidden="true" className="w-2 h-2 shrink-0 rounded-full bg-warning" />
          <p data-testid="reach-status" role="status" className="min-w-0 text-callout text-ink-2">
            {t('msgr.add.waiting', { name: addedName })}
          </p>
        </div>
      )}
    </div>
  );

  const phoneSheet = () => {
    const back = () => go('doors');
    if (phoneStep === 'group') {
      return (
        <FormSheet
          open
          tall
          onClose={close}
          onBack={back}
          title={asChannel ? t('groupsTg.newChannel') : t('createChatModal.newGroup')}
          testId="new-group-sheet"
          footer={
            <PrimaryButton
              onClick={handleCreateGroup}
              disabled={!groupTitle.trim() || groupMembers.length === 0 || busy}
            >
              {busy ? t('createChatModal.creating') : t('createChatModal.create')}
            </PrimaryButton>
          }
        >
          {phoneGroupBody}
        </FormSheet>
      );
    }
    if (phoneStep === 'paste') {
      return (
        <FormSheet
          open
          onClose={close}
          onBack={toAdd}
          title={t('msgr.add.paste')}
          testId="new-person-sheet"
          footer={
            <PrimaryButton
              onClick={() => parsed && void addPerson(parsed, undefined, name, address)}
              disabled={!parsed || busy}
            >
              {busy ? t('msgr.add.adding') : t('msgr.add.confirm')}
            </PrimaryButton>
          }
        >
          {pasteBody}
        </FormSheet>
      );
    }
    if (phoneStep === 'added' && added) {
      return (
        <FormSheet
          open
          onClose={close}
          onBack={() => {
            setAdded(null);
            toAdd();
          }}
          title={t('msgr.add.title')}
          testId="person-added-sheet"
          footer={
            <PrimaryButton
              onClick={() => {
                soundFx.playTap();
                const id = added.conversationId;
                reset();
                onConversationReady(id);
              }}
            >
              {t('msgr.add.write')}
            </PrimaryButton>
          }
        >
          {addedBody}
        </FormSheet>
      );
    }
    if (phoneStep === 'handle') {
      return (
        <FormSheet open onClose={close} onBack={toAdd} title={t('msgr.add.nickTitle')} testId="add-handle-sheet">
          <HandleSheet onDone={toAdd} />
        </FormSheet>
      );
    }
    if (phoneStep === 'directory') {
      return (
        <FormSheet open tall onClose={close} onBack={back} title={t('createChatModal.globalDirectory')} hideTitle testId="new-directory-sheet">
          {/* Друга смуга пошуку — сервер платформи, а не цей вузол; панель
              сама себе називає вголос. «Почати розмову» не заводить власної
              дороги: рядок PH2 лягає в те саме поле запрошення. */}
          <div className="px-4 pb-4">
            <DirectorySearchPanel
              onUseInvite={(compact) => {
                setText(compact);
                setNameTouched(false);
                go('paste');
              }}
            />
          </div>
        </FormSheet>
      );
    }
    if (phoneStep === 'add' && !addHome) {
      return (
        <FormSheet open tall onClose={close} onBack={back} title={t('msgr.add.title')} testId="add-person-sheet">
          {addBody}
        </FormSheet>
      );
    }
    // Перший екран. Людей ще нема — він і є «Додати людину».
    return (
      <FormSheet open tall onClose={close} title={addHome ? t('msgr.add.title') : t('createChatModal.newChat')} testId="new-chat-sheet">
        {addHome ? addBody : phoneHomeBody}
      </FormSheet>
    );
  };
  return phoneSheet();
};

/** Скільки йдуть двоє назустріч, перш ніж між ними стає замок (identity.css, .ph-meet-lock). */
const MEET_LOCK_MS = 280;

function KeyMeet({ met, me, them }: { met: boolean; me: React.ReactNode; them: React.ReactNode }) {
  const [settled, setSettled] = useState(false);
  useEffect(() => {
    const frame = requestAnimationFrame(() => setSettled(true));
    return () => cancelAnimationFrame(frame);
  }, []);
  return (
    <div data-testid="key-meet" data-met={String(met)} data-settled={String(settled)} className="ph-meet relative flex items-center justify-center">
      <span data-meet-side="me" className="relative z-0 rounded-full ring-4 ring-raised">{me}</span>
      <span data-meet-side="them" className="relative z-10 rounded-full ring-4 ring-raised">{them}</span>
      {met && settled && (
        <span className="ph-meet-lock absolute z-20 w-9 h-9 rounded-full bg-raised border border-line flex items-center justify-center">
          <Lock aria-hidden="true" className="w-4 h-4 text-ink" strokeWidth={2.2} />
        </span>
      )}
    </div>
  );
}

