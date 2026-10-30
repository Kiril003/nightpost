/**
 * Відкладені листи — аркуш (FormSheet), а не бічна панель «керування чергою».
 *
 * Що правда (03.10): лист має день і годину (`scheduledDate` +
 * `scheduledExactTime`) і свою розмову; корінь месенджера щохвилини питає стор
 * `sendDueScheduled`, і лист іде, щойно настав його час, — але ЛИШЕ поки
 * PHANTOM відкритий. Тому аркуш так і каже, без «фонового надсилання».
 *
 * На телефоні (власник 20.10) лист лежить на апараті й іде будильником, навіть
 * коли застосунок закрито (`/messenger/scheduled`), тож примітки «лише поки
 * відкритий» там немає. Правки немає: вона нічого не робила.
 */
import React, { useMemo, useState } from 'react';
import { CalendarClock, Pencil, Repeat as RepeatIcon, Send, Trash2, Radio } from 'lucide-react';
import type { Chat, ScheduledMessage } from '../../types/messenger';
import { scheduledDueMs, scheduledOnDevice, SCHEDULE_GRACE_MS } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { Avatar } from './Avatar';
import { DoorRow, FormSheet, PrimaryButton } from './FormSheet';
import { isSavedNotes } from './chatListFormat';
import { useWhenFlow, type WhenResult } from './when/WhenFlow';
import { dayLabel } from './when/whenModel';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { translate, type MessageKey } from '../../i18n';

export interface ScheduledDraft {
  chatId: string;
  text: string;
  date: string;
  time: string;
  repeat?: NonNullable<ScheduledMessage['repeat']>;
  whenOnline?: boolean;
}

interface ScheduledMessagesDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  scheduledMessages: ScheduledMessage[];
  currentChatId: string;
  chats: Chat[];
  onSendNow: (scheduledId: string) => void;
  onCancel: (scheduledId: string) => void;
  onCreate?: (draft: ScheduledDraft) => void;
  /** Перенести: новий час (з повтором) або «коли буде в мережі». */
  onMove?: (scheduledId: string, to: WhenResult) => void;
  onEditText?: (scheduledId: string, text: string) => void;
  /** Текст, який людина вже набрала й переносить сюди з «Запланувати» у композері. */
  initialText?: string;
}

const pad = (n: number) => String(n).padStart(2, '0');
/** Місцевий день «РРРР-ММ-ДД» — той, що бачить людина, а не UTC. */
export const localDay = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (d: Date, n: number): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

type T = Translation['t'];
const ukT: T = (key, vars) => translate(key, 'uk', vars);

const MONTHS: MessageKey[] = [
  'scheduledMessagesDrawer.month1',
  'scheduledMessagesDrawer.month2',
  'scheduledMessagesDrawer.month3',
  'scheduledMessagesDrawer.month4',
  'scheduledMessagesDrawer.month5',
  'scheduledMessagesDrawer.month6',
  'scheduledMessagesDrawer.month7',
  'scheduledMessagesDrawer.month8',
  'scheduledMessagesDrawer.month9',
  'scheduledMessagesDrawer.month10',
  'scheduledMessagesDrawer.month11',
  'scheduledMessagesDrawer.month12',
];
const clock = (hhmm: string) => hhmm.replace(/^0(\d)/, '$1');

/** «Сьогодні о 19:00», «Завтра о 9:00», «6 жовтня о 9:00». */
export const whenLabel = (date: string, time: string, now: Date, t: T = ukT): string => {
  const hhmm = clock(time);
  if (date === localDay(now)) return t('scheduledMessagesDrawer.today', { time: hhmm });
  if (date === localDay(addDays(now, 1))) return t('scheduledMessagesDrawer.tomorrow', { time: hhmm });
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) return hhmm;
  const year = Number(m[1]) === now.getFullYear() ? '' : t('scheduledMessagesDrawer.yearSuffix', { year: m[1] });
  return t('scheduledMessagesDrawer.dated', { day: Number(m[3]), month: t(MONTHS[Number(m[2]) - 1]), year, time: hhmm });
};

const FIELD =
  'w-full rounded-control bg-overlay text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line';

const REPEAT_WORD = {
  daily: 'when.repeatDaily',
  weekly: 'when.repeatWeekly',
  monthly: 'when.repeatMonthly',
  weekdays: 'when.repeatWeekdays',
} as const;

/** «Коли … буде в мережі» — лише розмова вдвох на телефоні: там апарат чує, що людина озвалась. */
const onlineNameOf = (chat: Chat | undefined): string | null =>
  chat && scheduledOnDevice() && chat.type === 'dm' && !isSavedNotes(chat) ? chat.title : null;

type View = { v: 'main' } | { v: 'menu'; id: string } | { v: 'edit'; id: string } | { v: 'move'; id: string };

export const ScheduledMessagesDrawer: React.FC<ScheduledMessagesDrawerProps> = ({
  isOpen,
  onClose,
  scheduledMessages,
  currentChatId,
  chats,
  onSendNow,
  onCancel,
  onCreate,
  onMove,
  onEditText,
  initialText,
}) => {
  const { t, locale } = useTranslation();
  const now = new Date();
  const nowMs = now.getTime();
  const [view, setView] = useState<View>({ v: 'main' });
  const [chatId, setChatId] = useState(() => (chats.some((c) => c.id === currentChatId) ? currentChatId : chats[0]?.id ?? ''));
  const [text, setText] = useState(initialText ?? '');
  const [seedSeen, setSeedSeen] = useState(initialText);
  if (initialText !== seedSeen) {
    setSeedSeen(initialText);
    setText(initialText ?? '');
  }
  const [draft, setDraft] = useState('');
  const rowOf = (id: string) => scheduledMessages.find((s) => s.id === id);
  const target = view.v === 'main' ? null : rowOf(view.id) ?? null;
  const moving = view.v === 'move' && target;

  const create = (r: WhenResult) => {
    const body = text.trim();
    if (!onCreate || !chatId || !body) return;
    soundFx.playSend();
    if (r.kind === 'online') onCreate({ chatId, text: body, date: '', time: '', whenOnline: true });
    else onCreate({ chatId, text: body, date: r.date, time: r.time, ...(r.repeat ? { repeat: r.repeat } : {}) });
    setText('');
    flow.reset();
  };
  const move = (r: WhenResult) => {
    if (view.v !== 'move') return;
    onMove?.(view.id, r);
    flow.reset();
    setView({ v: 'main' });
  };

  const flow = useWhenFlow({
    onPick: (r) => (view.v === 'move' ? move(r) : create(r)),
    onlineName: onlineNameOf(chats.find((c) => c.id === (moving ? target.chatId : chatId))),
    repeatable: true,
    disabled: view.v === 'main' && (!onCreate || !text.trim() || !chatId),
    initial: moving ? { date: target.scheduledDate, time: target.scheduledExactTime, repeat: target.repeat ?? '' } : undefined,
    primaryKey: view.v === 'move' ? 'when.moveTo' : 'when.sendAt',
  });

  const rows = useMemo(
    () =>
      [...scheduledMessages].sort(
        (a, b) => (a.whenOnline ? 1 : 0) - (b.whenOnline ? 1 : 0) || (scheduledDueMs(a) ?? Number.MAX_SAFE_INTEGER) - (scheduledDueMs(b) ?? Number.MAX_SAFE_INTEGER),
      ),
    [scheduledMessages],
  );
  const groups = useMemo(() => {
    const out: { key: string; label: string; rows: ScheduledMessage[] }[] = [];
    for (const s of rows) {
      const key = s.whenOnline ? 'online' : s.scheduledDate || 'other';
      let g = out.find((x) => x.key === key);
      if (!g) {
        const label =
          key === 'online'
            ? t('sched.onlineGroup')
            : s.scheduledDate
              ? dayLabel(s.scheduledDate, now, locale, { today: t('when.today'), tomorrow: t('when.tomorrow') })
              : '';
        g = { key, label, rows: [] };
        out.push(g);
      }
      g.rows.push(s);
    }
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, locale]);
  const toneOf = (id: string) => chats.find((c) => c.id === id)?.peerNodeId ?? id;
  const whenOf = (s: ScheduledMessage) =>
    s.whenOnline
      ? t('when.onlineAny')
      : s.scheduledDate && s.scheduledExactTime
        ? whenLabel(s.scheduledDate, s.scheduledExactTime, now, t)
        : s.scheduledTime;

  const close = () => {
    flow.reset();
    setView({ v: 'main' });
    onClose();
  };

  let title = initialText ? t('when.later') : t('scheduledMessagesDrawer.label');
  let onBack: (() => void) | undefined = flow.back;
  let content: React.ReactNode;
  let footer: React.ReactNode = flow.win === 'quick' ? undefined : flow.footer;

  if (view.v !== 'main' && !target) {
    // Лист пішов або зник, поки людина дивилась меню.
    content = null;
    onBack = () => setView({ v: 'main' });
  } else if (view.v === 'menu' && target) {
    title = whenOf(target);
    onBack = () => setView({ v: 'main' });
    content = (
      <div data-testid="scheduled-menu" className="pb-2">
        <p className="px-4 pb-3 text-body text-ink-2 whitespace-pre-wrap break-words line-clamp-3">«{target.text}»</p>
        <DoorRow
          icon={<Send className="w-5 h-5" strokeWidth={1.75} />}
          label={t('sched.menuSendNow')}
          onClick={() => {
            soundFx.playSend();
            onSendNow(target.id);
            setView({ v: 'main' });
          }}
        />
        {onMove && (
          <DoorRow
            icon={<CalendarClock className="w-5 h-5" strokeWidth={1.75} />}
            label={t('when.reschedule')}
            onClick={() => {
              flow.reset();
              setView({ v: 'move', id: target.id });
            }}
          />
        )}
        {onEditText && (
          <DoorRow
            icon={<Pencil className="w-5 h-5" strokeWidth={1.75} />}
            label={t('sched.menuEdit')}
            onClick={() => {
              setDraft(target.text ?? '');
              setView({ v: 'edit', id: target.id });
            }}
          />
        )}
        <DoorRow
          icon={<Trash2 className="w-5 h-5" strokeWidth={1.75} />}
          label={t('sched.menuDelete')}
          tone="danger"
          onClick={() => {
            soundFx.playTap();
            onCancel(target.id);
            setView({ v: 'main' });
          }}
        />
      </div>
    );
  } else if (view.v === 'edit' && target) {
    title = t('sched.menuEdit');
    onBack = () => setView({ v: 'menu', id: target.id });
    content = (
      <div className="px-4 pb-2">
        <textarea
          aria-label={t('scheduledMessagesDrawer.textLabel')}
          rows={4}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          className={`${FIELD} px-3 py-2.5 resize-none`}
          style={{ '--f-raise': 'var(--ph-overlay)' } as React.CSSProperties}
        />
      </div>
    );
    footer = (
      <PrimaryButton
        disabled={!draft.trim()}
        onClick={() => {
          if (!draft.trim()) return;
          onEditText?.(target.id, draft);
          setView({ v: 'menu', id: target.id });
        }}
      >
        {t('sched.save')}
      </PrimaryButton>
    );
  } else if (view.v === 'move' && target) {
    title = flow.win === 'quick' ? t('when.reschedule') : flow.title;
    onBack = flow.back ?? (() => setView({ v: 'menu', id: target.id }));
    content = flow.win === 'quick' ? flow.quick : flow.body;
  } else if (flow.win !== 'quick') {
    title = flow.title;
    content = flow.body;
  } else {
    content = (
      <>
        {!__PHONE_BUILD__ && (
          <p className="px-4 pb-3 text-footnote text-ink-3">
            {t('scheduledMessagesDrawer.note', { device: t('scheduledMessagesDrawer.deviceComputer') })}
          </p>
        )}
        {onCreate && (
          <section data-testid="scheduled-create" className="pb-2">
            <div className="px-4 pt-1 pb-2 space-y-3">
              {chats.length > 1 && (
                <label className="block">
                  <span className="block pb-1 text-footnote font-semibold text-ink-3">{t('scheduledMessagesDrawer.to')}</span>
                  <select value={chatId} onChange={(e) => setChatId(e.target.value)} className={`${FIELD} h-11 px-3`}>
                    {chats.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.title}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <textarea
                aria-label={t('scheduledMessagesDrawer.textLabel')}
                rows={initialText ? 2 : 3}
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={t('scheduledMessagesDrawer.placeholder')}
                className={`${FIELD} px-3 py-2.5 resize-none`}
                // Нічне правило messenger.css фарбує БУДЬ-ЯКУ textarea в --f-raise
                // з !important, а це рівно поверхня аркуша: поле зникало.
                style={{ '--f-raise': 'var(--ph-overlay)' } as React.CSSProperties}
              />
            </div>
            <div role="group" aria-label={t('scheduledMessagesDrawer.whenLabel')}>
              {flow.quick}
            </div>
          </section>
        )}

        {rows.length === 0 ? (
          !onCreate && (
            <p data-testid="scheduled-empty" className="px-4 py-10 text-center text-body text-ink-2">
              {t('scheduledMessagesDrawer.empty')}
            </p>
          )
        ) : (
          <div aria-label={t('scheduledMessagesDrawer.listLabel')} role="list" className={onCreate ? 'border-t border-line mt-1' : ''}>
            {groups.map((g) => (
              <div key={g.key} role="presentation">
                {g.label && <p className="px-4 pt-3 pb-1 text-footnote text-ink-3">{g.label}</p>}
                {g.rows.map((s) => {
                  const due = scheduledDueMs(s);
                  const missed = !s.whenOnline && due !== null && nowMs - due > SCHEDULE_GRACE_MS;
                  const when = whenOf(s);
                  return (
                    <button
                      key={s.id}
                      type="button"
                      role="listitem"
                      data-testid="scheduled-row"
                      onClick={() => {
                        soundFx.playTap();
                        setView({ v: 'menu', id: s.id });
                      }}
                      className="w-full text-left px-4 py-2.5 flex gap-3 active:bg-overlay"
                    >
                      <Avatar
                        name={s.chatTitle || t('scheduledMessagesDrawer.chat')}
                        tone={toneOf(s.chatId)}
                        radius="rounded-full"
                        className="w-10 h-10 shrink-0 text-subhead"
                      />
                      <span className="flex-1 min-w-0">
                        <span className="flex items-baseline gap-2">
                          <span className="flex-1 min-w-0 truncate text-headline text-ink">{s.chatTitle || t('scheduledMessagesDrawer.chat')}</span>
                          <span className={`shrink-0 inline-flex items-center gap-1 text-footnote ${s.failure ? 'text-danger' : missed ? 'text-warning' : 'text-ink-2'}`}>
                            {s.repeat && <RepeatIcon aria-label={t(REPEAT_WORD[s.repeat])} className="w-3.5 h-3.5" strokeWidth={1.75} />}
                            {s.whenOnline && <Radio aria-hidden="true" className="w-3.5 h-3.5" strokeWidth={1.75} />}
                            {s.failure
                              ? t('scheduledMessagesDrawer.notSent')
                              : missed
                                ? t('scheduledMessagesDrawer.missed', { when })
                                : s.whenOnline
                                  ? ''
                                  : s.scheduledExactTime || when}
                          </span>
                        </span>
                        <span className="mt-0.5 block text-body text-ink-2 whitespace-pre-wrap break-words line-clamp-4">{s.text}</span>
                        {s.failure && (
                          <span data-testid="scheduled-failure" className="mt-0.5 block text-footnote text-danger">
                            {s.failure}
                          </span>
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        )}
      </>
    );
  }

  return (
    <FormSheet open={isOpen} onClose={close} onBack={onBack} title={title} tall testId="scheduled-drawer" footer={footer}>
      {content}
    </FormSheet>
  );
};
