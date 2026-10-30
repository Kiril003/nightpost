/**
 * Меню розмови (⋮) на телефоні — лише справжні дії.
 *
 * Настільне меню на телефоні несло «Список покупок / справ», «Спільний
 * бюджет», «Навчальні картки (Flashcards)» і «Опитування спільноти» з
 * вписаними «Варіант 1 / Варіант 2» — порожні картки, які клались у розмову
 * одним дотиком, — а ще «Відкладені повідомлення» (відкладене живе, лише поки
 * відкрита сторінка), глобальний «Звук» і «Налаштування месенджера», яким місце
 * не в меню розмови. Тут лишилось те, що робить саме з ЦІЄЮ розмовою
 * саме те, що написано:
 *
 *  - картка співрозмовника (звірка ключа, назва, видалення) — лише для
 *    розмови вдвох із живою людиною: у помічника й у «Збереженого» ключа нема;
 *  - «Про групу» — склад і назва групи;
 *  - тло розмови — своє поверх загального з «Вигляду»;
 *  - звук розмови — місцева тиша, що переживає перезапуск (`chatHush`);
 *  - очистити історію — на цьому телефоні, з підтвердженням.
 */
import { tNow } from '../chat/tNow';
import React, { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Bell, BellOff, Brain, CalendarClock, Check, Clock, Eraser, FileDown, Image as ImageIcon, Search, SlidersHorizontal, Timer, UserRound, Users } from 'lucide-react';
import type { Chat } from '../../types/messenger';
import { DoorGroup, DoorRow, FormSheet } from './FormSheet';
import { displayTitle, isSavedNotes } from './chatListFormat';
import { isAssistantKind } from '../../services/assistantChat';
import { messengerApi } from '../../services/messengerApi';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { soundFx } from '../../utils/messengerSound';
import { PhantomKnows } from './PhantomKnows';
import { ChatWallpaperPicker } from './ChatWallpaperPicker';
import { DISAPPEAR_CHOICES, durationWord } from '../../services/disappearWords';
import { useTranslation } from '../../i18n/useTranslation';
import { circleName, useCirclesStore } from '../../stores/circlesStore';
import { chatToText } from './feed/chatDates';
import { shareChatText } from '../../services/chatExport';
import { chatHush, effectiveRule, type HushRule } from '../../services/chatHush';
import { GraduationCap } from 'lucide-react';
import { eduItems, classOf } from '../../services/edu/eduModel';
import { openClass } from '../../services/edu/eduNav';
import { useProfiles } from '../../stores/profilesStore';
import { MakeClassSheet } from './directions/education/MakeClassSheet';
import { useWhenFlow } from './when/WhenFlow';
import { WhenRow } from './when/WhenRow';
import { dayKey } from './when/whenModel';

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export interface PhoneChatMenuDoors {
  card: boolean;
  group: boolean;
  mute: boolean;
  clear: boolean;
  export: boolean;
  /** «Що я про тебе знаю» — лише в розмові з самим PHANTOM. */
  knows: boolean;
  /** Зникаючі повідомлення — лише розмова вдвох з людиною (не група, не нотатки, не PHANTOM). */
  timer: boolean;
  /** У групі строку немає — і це сказано рядком, а не тишею. */
  timerGroup: boolean;
  /** Відкладені листи: людина, група або «Збережене», не помічник. */
  scheduled: boolean;
  wallpaper: boolean;
}

/** Які двері має розмова. Порожньо — кнопки ⋮ не буде зовсім. */
export const phoneChatMenuDoors = (chat: Chat): PhoneChatMenuDoors => {
  const saved = isSavedNotes(chat);
  const group = chat.type === 'group' || chat.type === 'channel';
  const assistant = isAssistantKind(chat.type);
  return {
    card: !saved && !group && !assistant && Boolean(chat.peerNodeId),
    group,
    mute: !saved,
    clear: true,
    export: !assistant,
    knows: chat.type === 'phantom',
    timer: !saved && !group && !assistant && chat.type !== 'phantom' && Boolean(chat.peerNodeId),
    timerGroup: group,
    scheduled: !assistant && chat.type !== 'phantom',
    wallpaper: true,
  };
};

export const hasPhoneChatMenu = (chat: Chat): boolean => Object.values(phoneChatMenuDoors(chat)).some(Boolean);

interface PhoneChatMenuProps {
  chat: Chat;
  onClose: () => void;
  onOpenCard: () => void;
  onOpenGroupDetails: () => void;
  /** Пошук у розмові (§7.1): живе тут, у «Ще», а не рядком чипів під шапкою. */
  onSearch?: () => void;
  /** Налаштування дзвінка без набору — лише там, де шапка має слухавку. */
  onCallSettings?: () => void;
}

export const PhoneChatMenu: React.FC<PhoneChatMenuProps> = ({ chat, onClose, onOpenCard, onOpenGroupDetails, onSearch, onCallSettings }) => {
  const { t } = useTranslation();
  const doors = phoneChatMenuDoors(chat);
  const muted = useMessengerStore((s) => s.chats.find((c) => c.id === chat.id)?.muted ?? false);
  const [confirmClear, setConfirmClear] = useState(false);
  const exportThisChat = async () => {
    const live = useMessengerStore.getState().chats.find((c) => c.id === chat.id) ?? chat;
    const text = chatToText(live.title || chat.title, live.messages ?? [], t('msgr.exportMedia'));
    const out = await shareChatText(text, live.title || chat.title);
    if (out === 'failed') useUIStore.getState().toast({ kind: 'error', message: t('msgr.exportFailed') });
    else if (out === 'copied') useUIStore.getState().toast({ kind: 'success', message: t('msgr.exportCopied') });
    onClose();
  };
  const [knows, setKnows] = useState(false);
  const [timer, setTimer] = useState(false);
  const [wallpaper, setWallpaper] = useState(false);
  const [hush, setHush] = useState(false);
  const [makingClass, setMakingClass] = useState(false);
  const meId = useMessengerStore((st) => st.currentUser.id);
  const studyProfile = useProfiles((st) => st.profiles.find((p) => p.id === st.activeId)?.kind === 'study');
  const isClass = doors.group && classOf(eduItems(chat, meId), meId) !== null;

  useSyncExternalStore(chatHush.subscribe, chatHush.version, chatHush.version);

  const ringKey = chat.circle && chat.circle !== 'all' ? chat.circle : null;
  const knownCircles = useCirclesStore((st) => st.circles);
  const ringInfo = ringKey ? knownCircles.find((c) => c.id === ringKey) : undefined;
  const ringName = ringInfo ? circleName(ringInfo, t) : null;
  const chatRule = chatHush.rule('chat', chat.id);
  const ringRule = ringKey ? chatHush.rule('ring', ringKey) : undefined;
  const moved = useRef<{ rule: HushRule | null; muted: boolean; copied: HushRule | null } | null>(null);

  const [scope, setScope] = useState<'chat' | 'ring'>(() => (!chatRule && ringRule ? 'ring' : 'chat'));
  const scopedRule = scope === 'ring' ? ringRule : chatRule;
  const isRuleOff = Boolean(scopedRule && !scopedRule.from && !scopedRule.to);
  const hushMode: 'on' | 'off' | 'hours' =
    (scope === 'chat' && muted) || isRuleOff
      ? 'off'
      : scopedRule?.from && scopedRule?.to
        ? 'hours'
        : 'on';

  const [fromTime, setFromTime] = useState<string>(scopedRule?.from || '19:00');
  const [toTime, setToTime] = useState<string>(scopedRule?.to || '09:00');

  useEffect(() => {
    setFromTime(scopedRule?.from || '19:00');
    setToTime(scopedRule?.to || '09:00');
  }, [hush, scope, scopedRule?.from, scopedRule?.to]);

  const seconds = useMessengerStore((s) => s.chats.find((c) => c.id === chat.id)?.disappearSeconds ?? 0);
  const scheduledHere = useMessengerStore((s) => s.scheduledMessages.filter((m) => m.chatId === chat.id).length);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const scopeTarget = (sc: 'chat' | 'ring'): { scope: 'chat' | 'ring'; key: string } =>
    sc === 'ring' && ringKey ? { scope: 'ring', key: ringKey } : { scope: 'chat', key: chat.id };

  const openHush = () => {
    const cRule = chatHush.rule('chat', chat.id);
    const rRule = ringKey ? chatHush.rule('ring', ringKey) : undefined;
    const nextScope: 'chat' | 'ring' = !cRule && rRule ? 'ring' : 'chat';
    const active = nextScope === 'ring' ? rRule : cRule;
    setScope(nextScope);
    setFromTime(active?.from || '19:00');
    setToTime(active?.to || '09:00');
    setHush(true);
  };

  const pickMode = (nextMode: 'on' | 'off' | 'hours') => {
    const target = scopeTarget(scope);
    if (nextMode === 'on') {
      if (muted) useMessengerStore.getState().toggleMuteChat(chat.id);
      chatHush.setRule(target.scope, target.key, null);
      setFromTime('19:00');
      setToTime('09:00');
      return;
    }
    if (nextMode === 'off') {
      if (target.scope === 'ring') {
        const kept: HushRule = {
          ...(scopedRule?.ask ? { ask: true } : {}),
          ...(scopedRule?.plan ? { plan: true } : {}),
        };
        chatHush.setRule('ring', target.key, kept);
      } else {
        if (!muted) useMessengerStore.getState().toggleMuteChat(chat.id);
        const kept: HushRule | null =
          scopedRule?.ask || scopedRule?.plan
            ? {
                ...(scopedRule.ask ? { ask: true } : {}),
                ...(scopedRule.plan ? { plan: true } : {}),
              }
            : null;
        chatHush.setRule('chat', chat.id, kept);
      }
      setFromTime('19:00');
      setToTime('09:00');
      return;
    }
    if (muted) useMessengerStore.getState().toggleMuteChat(chat.id);
    const f = scopedRule?.from || fromTime || '19:00';
    const tVal = scopedRule?.to || toTime || '09:00';
    setFromTime(f);
    setToTime(tVal);
    const nextRule: HushRule = {
      from: f,
      to: tVal,
      ...(scopedRule?.ask ? { ask: true } : {}),
      ...(scopedRule?.plan ? { plan: true } : {}),
    };
    chatHush.setRule(target.scope, target.key, nextRule);
  };

  const updateHours = (nextFrom: string, nextTo: string) => {
    setFromTime(nextFrom);
    setToTime(nextTo);
    const target = scopeTarget(scope);
    const nextRule: HushRule = {
      from: nextFrom,
      to: nextTo,
      ...(scopedRule?.ask ? { ask: true } : {}),
      ...(scopedRule?.plan ? { plan: true } : {}),
    };
    chatHush.setRule(target.scope, target.key, nextRule);
  };
  // Тихі години — колеса зі спільних вікон «Коли» (msg-x), без системних полів часу.
  const [hushPick, setHushPick] = useState<'from' | 'to' | null>(null);
  const hushFlow = useWhenFlow({
    primaryKey: 'when.meetAt',
    allowPast: true,
    onPick: (r) => {
      if (r.kind === 'at') {
        if (hushPick === 'from') updateHours(r.time, toTime);
        else updateHours(fromTime, r.time);
      }
      setHushPick(null);
      hushFlow.reset();
    },
  });
  const openHushTime = (which: 'from' | 'to') => {
    hushFlow.open('time', { date: dayKey(new Date()), time: which === 'from' ? fromTime : toTime });
    setHushPick(which);
  };

  const pickScope = (nextScope: 'chat' | 'ring') => {
    if (nextScope === scope || (nextScope === 'ring' && !ringKey)) return;
    if (nextScope === 'ring' && ringKey) {
      const existingRing = chatHush.rule('ring', ringKey);
      if (!existingRing) {
        const own = chatHush.rule('chat', chat.id) ?? null;
        const copied: HushRule | null = own ?? (muted ? {} : null);
        if (copied) {
          chatHush.setRule('ring', ringKey, copied);
          chatHush.setRule('chat', chat.id, null);
          if (muted) useMessengerStore.getState().toggleMuteChat(chat.id);
          moved.current = { rule: own, muted, copied };
        }
      }
      const nextRing = chatHush.rule('ring', ringKey);
      setFromTime(nextRing?.from || '19:00');
      setToTime(nextRing?.to || '09:00');
    } else {
      if (moved.current) {
        const prev = moved.current;
        moved.current = null;
        chatHush.setRule('chat', chat.id, prev.rule);
        const nowMuted = useMessengerStore.getState().chats.find((c) => c.id === chat.id)?.muted ?? false;
        if (prev.muted && !nowMuted) {
          useMessengerStore.getState().toggleMuteChat(chat.id);
        }
        if (ringKey) {
          const currentRing = chatHush.rule('ring', ringKey) ?? null;
          if (JSON.stringify(currentRing) === JSON.stringify(prev.copied)) {
            chatHush.setRule('ring', ringKey, null);
          }
        }
      }
      const currentChatRule = chatHush.rule('chat', chat.id);
      setFromTime(currentChatRule?.from || '19:00');
      setToTime(currentChatRule?.to || '09:00');
    }
    setScope(nextScope);
  };

  const toggleBreak = (field: 'ask' | 'plan') => {
    const target = scopeTarget(scope);
    const current = chatHush.rule(target.scope, target.key);
    const nextVal = !current?.[field];
    const nextRule: HushRule = {
      ...(hushMode === 'hours' ? { from: fromTime, to: toTime } : {}),
      ...(current?.ask ? { ask: true } : {}),
      ...(current?.plan ? { plan: true } : {}),
      [field]: nextVal,
    };
    chatHush.setRule(target.scope, target.key, nextRule);
  };

  const activeHintRule = effectiveRule(chat);
  const fromRing = !chatHush.rule('chat', chat.id) && Boolean(ringRule) && !muted;
  const muteWord = muted
    ? t('chats40.off')
    : activeHintRule?.from && activeHintRule?.to && activeHintRule.from !== activeHintRule.to
      ? `${activeHintRule.from}–${activeHintRule.to}`
      : activeHintRule
        ? t('chats40.off')
        : undefined;
  const muteHint =
    fromRing && muteWord
      ? ringName
        ? t('chatSound.ruleIn', { rule: muteWord, circle: ringName })
        : muteWord
      : muteWord;

  const ringNote =
    ringRule?.from && ringRule?.to && ringRule.from !== ringRule.to
      ? ringName
        ? t('chats40.ringActive', { hours: `${ringRule.from}–${ringRule.to}`, circle: ringName })
        : `${t('chats40.hours')}: ${ringRule.from}–${ringRule.to}`
      : ringRule
        ? ringName
          ? t('chats40.ringMuted', { circle: ringName })
          : t('chats40.off')
        : null;

  const clear = async () => {
    setBusy(true);
    setNote(null);
    try {
      await messengerApi.clearConversation(chat.id);
      useMessengerStore.getState().updateChat(chat.id, {
        messages: [],
        lastSnippet: undefined,
        lastKind: undefined,
        lastAuthor: undefined,
        unreadCount: 0,
        pinnedMessage: null,
      });
      soundFx.playTap();
      useUIStore.getState().toast({ kind: 'success', message: t('sidebar.cleared') });
      onClose();
    } catch {
      setNote(t('phoneChatMenu.clearFailed'));
    } finally {
      setBusy(false);
    }
  };

  const pickTimer = async (next: number) => {
    setBusy(true);
    setNote(null);
    try {
      const me = useMessengerStore.getState().currentUser;
      const r = await messengerApi.setTimer(chat.id, next, { author_id: me.id, author_name: me.name });
      useMessengerStore.getState().updateChat(chat.id, { disappearSeconds: r.disappear_s });
      soundFx.playTap();
      setTimer(false);
    } catch {
      setNote(t('phoneChatMenu.timerFailed'));
    } finally {
      setBusy(false);
    }
  };

  const checkMark = (
    <Check className="w-5 h-5 shrink-0 text-accent-text" strokeWidth={1.75} aria-label={t('phoneChatMenu.selected')} />
  );

  return (
    <>
    <FormSheet
      open
      onClose={onClose}
      title={
        knows
          ? t('phoneChatMenu.knowsTitle')
          : timer
            ? t('phoneChatMenu.timerTitle')
            : wallpaper
              ? t('look.title')
              : hush
                ? t('chats40.title')
                : displayTitle(chat.title)
      }
      testId="phone-chat-menu"
      tall={knows}
      onBack={
        knows
          ? () => setKnows(false)
          : timer
            ? () => setTimer(false)
            : wallpaper
              ? () => setWallpaper(false)
              : hush
                ? () => setHush(false)
                : undefined
      }
    >
      {knows ? (
        <PhantomKnows />
      ) : wallpaper ? (
        <ChatWallpaperPicker chatId={chat.id} />
      ) : hush ? (
        <div data-testid="hush-sheet" className="pb-2">
          <div role="radiogroup" aria-label={t('chats40.title')}>
            <DoorGroup inset="text">
              <DoorRow
                label={t('chats40.on')}
                onClick={() => pickMode('on')}
                trailing={hushMode === 'on' ? checkMark : undefined}
              />
              <DoorRow
                label={t('chats40.off')}
                onClick={() => pickMode('off')}
                trailing={hushMode === 'off' ? checkMark : undefined}
              />
              <DoorRow
                label={t('chats40.hours')}
                onClick={() => pickMode('hours')}
                trailing={hushMode === 'hours' ? checkMark : undefined}
              />
            </DoorGroup>
          </div>
          {hushMode === 'on' && ringNote && (
            <p data-testid="hush-ring-note" className="px-5 pt-2 text-footnote text-ink-3">
              {ringNote}
            </p>
          )}
          {hushMode === 'hours' && (
            <div className="px-4 pt-2">
              <WhenRow testId="hush-from" icon={<Clock {...ICO} />} label={t('chats40.from')} value={fromTime} onClick={() => openHushTime('from')} />
              <WhenRow testId="hush-to" icon={<Clock {...ICO} />} label={t('chats40.to')} value={toTime} onClick={() => openHushTime('to')} />
            </div>
          )}
          {ringKey && (
            <div className="pt-3">
              <DoorGroup inset="text">
                <DoorRow
                  label={t('chats40.scopeChat')}
                  onClick={() => pickScope('chat')}
                  trailing={scope === 'chat' ? checkMark : undefined}
                />
                <DoorRow
                  label={ringName ? t('chats40.scopeRing', { circle: ringName }) : t('chatSound.scopeRingGeneric')}
                  onClick={() => pickScope('ring')}
                  trailing={scope === 'ring' ? checkMark : undefined}
                />
              </DoorGroup>
            </div>
          )}
          {(hushMode === 'off' || hushMode === 'hours') && (
            <div className="pt-3" role="group" aria-label={t('chats40.breakLabel')}>
              <p className="px-5 pb-1 text-footnote text-ink-3">{t('chats40.breakLabel')}</p>
              <DoorGroup inset="text">
                <div data-testid="hush-break-ask">
                  <DoorRow
                    label={t('chats40.breakAsk')}
                    onClick={() => toggleBreak('ask')}
                    trailing={scopedRule?.ask ? checkMark : undefined}
                  />
                </div>
                <div data-testid="hush-break-plan">
                  <DoorRow
                    label={t('chats40.breakPlan')}
                    onClick={() => toggleBreak('plan')}
                    trailing={scopedRule?.plan ? checkMark : undefined}
                  />
                </div>
              </DoorGroup>
            </div>
          )}
        </div>
      ) : timer ? (
        <div data-testid="timer-choices" className="pb-2" role="radiogroup" aria-label={t('phoneChatMenu.timerLabel')}>
          <DoorGroup inset="text">
          {DISAPPEAR_CHOICES.map((c) => (
            <DoorRow
              key={c.seconds}
              label={c.label}
              onClick={() => void (busy || c.seconds === seconds ? undefined : pickTimer(c.seconds))}
              trailing={c.seconds === seconds ? checkMark : undefined}
            />
          ))}
          </DoorGroup>
          <p className="px-5 pt-2 text-footnote text-ink-3">
            {t('phoneChatMenu.timerNote')}
          </p>
          <p data-testid="timer-screenshot-note" className="px-5 pt-2 text-footnote text-ink-3">{tNow('disappear.screenshotNote')}</p>
        </div>
      ) : confirmClear ? (
        <div className="px-4 pb-4">
          <p className="text-callout text-ink-2">
            {t(doors.group ? 'chatActionsSheet.clearSentenceGroup' : 'phoneChatMenu.clearNote')}
          </p>
          <button
            type="button"
            disabled={busy}
            onClick={() => void clear()}
            className="mt-4 w-full h-12 min-h-0 rounded-control bg-danger text-headline text-white active:opacity-90"
          >
            {busy ? t('phoneChatMenu.clearing') : t('phoneChatMenu.clear')}
          </button>
          <button
            type="button"
            onClick={() => setConfirmClear(false)}
            className="mt-2 w-full h-12 min-h-0 rounded-control text-headline text-ink active:bg-overlay"
          >
            {t('phoneChatMenu.cancel')}
          </button>
        </div>
      ) : (
        <div className="pb-2">
          <DoorGroup>
          {onSearch && (
            <DoorRow
              icon={<Search {...ICO} />}
              label={t('phoneChatMenu.searchChat')}
              onClick={() => {
                onClose();
                onSearch();
              }}
            />
          )}
          {doors.knows && (
            <DoorRow
              icon={<Brain {...ICO} />}
              label={t('phoneChatMenu.knowsDoor')}
              hint={t('phoneChatMenu.knowsHint')}
              onClick={() => setKnows(true)}
            />
          )}
          {doors.card && (
            <DoorRow
              icon={<UserRound {...ICO} />}
              label={t('phoneChatMenu.profile')}
              hint={t('phoneChatMenu.profileHint')}
              onClick={() => {
                onClose();
                onOpenCard();
              }}
            />
          )}
          {doors.group && (
            <DoorRow
              icon={<Users {...ICO} />}
              label={t('phoneChatMenu.aboutGroup')}
              onClick={() => {
                onClose();
                onOpenGroupDetails();
              }}
            />
          )}
          {doors.group && (isClass || studyProfile) && (
            <DoorRow
              icon={<GraduationCap {...ICO} />}
              label={isClass ? t('edu.class') : t('edu.make')}
              onClick={() => {
                if (isClass) {
                  onClose();
                  openClass(chat.id);
                } else {
                  setMakingClass(true);
                }
              }}
            />
          )}
          {doors.timer && (
            <DoorRow
              icon={<Timer {...ICO} />}
              label={t('phoneChatMenu.timerDoor')}
              hint={durationWord(seconds)}
              onClick={() => setTimer(true)}
            />
          )}
          {doors.timerGroup && (
            <div data-testid="timer-door-group" aria-disabled="true" className="w-full min-h-12 px-4 py-1.5 flex items-center gap-4">
              <span className="w-5 shrink-0 flex items-center justify-center text-ink-3">
                <Timer {...ICO} />
              </span>
              <span className="flex-1 min-w-0">
                <span className="block text-subhead text-ink-3">{t('phoneChatMenu.timerDoor')}</span>
                <span className="block text-footnote text-ink-3">{t('phoneChatMenu.timerOnlyDm')}</span>
              </span>
            </div>
          )}
          {doors.scheduled && (
            <DoorRow
              icon={<CalendarClock {...ICO} />}
              label={t('phoneChatMenu.scheduledDoor')}
              hint={scheduledHere > 0 ? String(scheduledHere) : undefined}
              onClick={() => {
                onClose();
                useMessengerStore.getState().setScheduledDrawerOpen(true);
              }}
            />
          )}
          {onCallSettings && (
            <DoorRow
              icon={<SlidersHorizontal {...ICO} />}
              label={t('callsHub.forAllChats')}
              onClick={() => {
                onClose();
                onCallSettings();
              }}
            />
          )}
          {doors.wallpaper && (
            <DoorRow icon={<ImageIcon {...ICO} />} label={t('look.door')} onClick={() => setWallpaper(true)} />
          )}
          {doors.mute && (
            <DoorRow
              icon={muteWord ? <BellOff {...ICO} /> : <Bell {...ICO} />}
              label={t('chats40.title')}
              hint={muteHint}
              onClick={openHush}
            />
          )}
          {doors.export && (
            <DoorRow
              icon={<FileDown {...ICO} />}
              label={t('msgr.export')}
              onClick={() => void exportThisChat()}
            />
          )}
          {doors.clear && <DoorRow icon={<Eraser {...ICO} />} label={t('phoneChatMenu.clearHistory')} onClick={() => setConfirmClear(true)} />}
          </DoorGroup>
        </div>
      )}
      {note && <p className="px-4 pb-4 text-footnote text-danger">{note}</p>}
    </FormSheet>
    {hushPick && (
      <FormSheet open onClose={() => setHushPick(null)} onBack={() => setHushPick(null)} title={t(hushPick === 'from' ? 'chats40.from' : 'chats40.to')} testId="hush-time-sheet" footer={hushFlow.footer}>
        {hushFlow.body}
      </FormSheet>
    )}
    {makingClass && (
      <MakeClassSheet
        chatId={chat.id}
        onClose={() => setMakingClass(false)}
        onDone={() => {
          onClose();
          openClass(chat.id);
        }}
      />
    )}
    </>
  );
};

export default PhoneChatMenu;
