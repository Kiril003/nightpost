/**
 * Аркуш плану (макет 04): хто вже на місці, хто в дорозі, хто запізнюється
 * (і «Покликати»), що хто принесе, що після зустрічі, і «Лишити» групу.
 * Усе — з позначок картки; чого немає, того не показано.
 */
import React, { useEffect, useState } from 'react';
import { Bell, Check, Clock, MapPinCheck, Map as MapIcon, Navigation, Plus, ReceiptText, ShieldCheck, Star, Timer } from 'lucide-react';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../Avatar';
import { SafetyVerifyBlock, contactForPeer, needsVerify } from '../VerifyContact';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { localNameOf } from '../../../services/localName';
import { messengerApi, type NodeContact } from '../../../services/messengerApi';
import { KEEP_VALUE, bringValue, movedAtOf, readCardMarks } from '../../../services/meetup/marks';
import { chosenAt } from '../../../services/meetup/draft';
import { expiryState, isPlanGroup } from '../../../services/meetup/expiry';
import { meetupReminded, remindBefore, setCardMark } from '../../../services/meetupApi';
import { openMeetupAsk } from '../../../services/nativeMeetupDraft';
import { hasVenuePage, type MeetupData } from '../../../types/meetup';
import type { Message } from '../../../types/messenger';
import { hhmm, whenWord } from './words';

const WINDOW = 2 * 3600_000;

const capital = (s: string) => s.charAt(0).toLocaleUpperCase() + s.slice(1);

export const PlanSheet: React.FC<{
  msg: Message;
  meetup: MeetupData;
  meId: string;
  onClose: () => void;
  onOpenMap: () => void;
  onRate: () => void;
}> = ({ msg, meetup, meId, onClose, onOpenMap, onRate }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const chat = chats.find((c) => c.messages.some((m) => m.id === msg.id));
  const activeChatId = useMessengerStore((s) => s.activeChatId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [thing, setThing] = useState('');
  const [pinged, setPinged] = useState<string[]>([]);
  const [remindedNow, setRemindedNow] = useState(false);
  const [contacts, setContacts] = useState<NodeContact[] | null>(null);
  const [verifyId, setVerifyId] = useState<string | null>(null);

  const now = Date.now();
  const st = readCardMarks(msg.cardMarks);
  const chosen = movedAtOf(msg.cardMarks, meetup.organizer) ?? chosenAt(meetup, st.lock);
  const at = chosen ?? meetup.at;
  const people = [meetup.organizer, ...meetup.invitees.map((p) => p.id).filter((id) => id !== meetup.organizer)];
  const fallback = (id: string) => (id === msg.senderId ? msg.senderName : meetup.invitees.find((p) => p.id === id)?.name) ?? t('meetupCard.someone');
  const nameOf = (id: string) => (id === meId ? t('meetupCard.you') : localNameOf(chats, id, fallback(id)));
  const live = chosen !== null && now >= at - WINDOW && now <= at + WINDOW;
  const here = people.filter((id) => st.here.has(id));
  const meHere = st.here.has(meId);
  const peersHere = meHere ? here.filter((id) => id !== meId) : [];
  const toVerify = contacts
    ? peersHere.flatMap((id) => {
        const c = contactForPeer(contacts, id);
        return c && needsVerify(c) ? [{ id, c }] : [];
      })
    : [];

  useEffect(() => {
    if (peersHere.length === 0) return;
    let alive = true;
    void messengerApi
      .listContacts()
      .then((list) => {
        if (alive) setContacts(list);
      })
      .catch(() => {
        if (alive) setContacts([]);
      });
    return () => {
      alive = false;
    };
  }, [peersHere.join(',')]);

  const coming = people.filter((id) => st.rsvp.get(id) === 'yes' && !st.here.has(id));
  const onWay = coming.filter((id) => st.eta.has(id) && st.eta.get(id)! * 60_000 > now);
  const late = coming.filter((id) => !onWay.includes(id) && now > (st.eta.has(id) ? st.eta.get(id)! * 60_000 : at) + 60_000);
  const expiry = chat && isPlanGroup(chat.messages) ? expiryState({ ...meetup, at }, msg.cardMarks, now) : ({ kind: 'none' } as const);
  const canPing = !!chat && chat.id === activeChatId;
  const reminded = remindedNow || meetupReminded(meetup.id, at);
  const canRemind = chosen !== null && st.rsvp.get(meId) === 'yes' && at - 30 * 60_000 > now && !reminded;
  const onMap = meetup.venue.lat !== null && meetup.venue.lon !== null;

  const mark = async (value: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    const said = await setCardMark(msg, meId, value);
    setBusy(false);
    if (said) setError(said);
    return said;
  };

  const addThing = async () => {
    let value: string;
    try {
      value = bringValue(thing);
    } catch {
      setError(t('planSheet.tooLong'));
      return;
    }
    if (!(await mark(value))) {
      setThing('');
      setAdding(false);
    }
  };

  const ping = (id: string) => {
    if (!canPing || pinged.includes(id)) return;
    setPinged((p) => [...p, id]);
    void useMessengerStore.getState().sendMessage(t('planSheet.pingText', { name: nameOf(id) }));
  };

  const ask = (a: Parameters<typeof openMeetupAsk>[0]) => {
    onClose();
    openMeetupAsk(a);
  };

  const row = (icon: React.ReactNode, body: React.ReactNode, end?: React.ReactNode, testId?: string) => (
    <div className="px-4 min-h-14 flex items-center gap-3" data-testid={testId}>
      <span className="w-6 shrink-0 flex justify-center text-ink-2">{icon}</span>
      <div className="flex-1 min-w-0 flex items-center gap-3">{body}</div>
      {end}
    </div>
  );
  const ico = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;
  const after = [
    chosen !== null && now >= at - WINDOW && { id: 'split', icon: <ReceiptText {...ico} />, label: t('planSheet.split'), go: () => ask({ kind: 'bill-draft', messageId: msg.id }) },
    hasVenuePage(meetup.venue) && { id: 'rate', icon: <Star {...ico} />, label: t('planSheet.rate'), go: onRate },
    canRemind && {
      id: 'remind',
      icon: <Bell {...ico} />,
      label: t('meetupCard.remindShort'),
      go: () => {
        const r = remindBefore({ ...meetup, at }, chat?.id ?? activeChatId, now);
        if (!r) return;
        setRemindedNow(true);
        r.catch(() => setRemindedNow(false));
      },
    },
  ].filter((x): x is { id: string; icon: React.ReactElement; label: string; go: () => void } => !!x);

  return (
    <FormSheet
      open
      onClose={onClose}
      title={meetup.venue.name}
      tall
      testId="plan-sheet"
      footer={
        expiry.kind === 'will_hide' ? (
          <div className="flex items-center gap-3" data-testid="plan-expiry">
            <Timer className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
            <div className="flex-1 min-w-0">
              <p className="text-footnote font-semibold text-ink">{t('planGroup.closes', { when: whenWord(expiry.at, now) })}</p>
              <p className="text-caption text-ink-3">{t('planSheet.keepWhy')}</p>
            </div>
            <button
              type="button"
              data-testid="plan-keep"
              disabled={busy}
              onClick={() => void mark(KEEP_VALUE)}
              className="h-11 min-h-0 px-5 rounded-full border border-line-strong text-callout font-semibold text-ink disabled:opacity-40"
            >
              {t('meetupCard.keep')}
            </button>
          </div>
        ) : undefined
      }
    >
      <div className="px-4 pb-2 flex items-center gap-2">
        <p className="flex-1 min-w-0 text-footnote text-ink-2" data-testid="plan-when">
          {chosen === null
            ? t('meetupVote.voting')
            : capital(whenWord(at, now)) + (meetup.durationMin ? `–${hhmm(at + meetup.durationMin * 60_000)}` : '')}
        </p>
        {onMap && (
          <button
            type="button"
            aria-label={t('meetupPin.showOnMap')}
            data-testid="plan-map"
            onClick={onOpenMap}
            className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink active:bg-overlay"
          >
            <MapIcon className="w-5 h-5" strokeWidth={1.75} />
          </button>
        )}
      </div>

      {live && (here.length > 0 || onWay.length > 0 || late.length > 0) && (
        <section data-testid="plan-now">
          <h3 className="px-4 pt-2 pb-1 text-caption font-semibold text-ink-2">{t('planSheet.now', { time: hhmm(now) })}</h3>
          {here.length > 0 &&
            row(
              <MapPinCheck {...ico} />,
              <>
                <span className="flex shrink-0">
                  {here.slice(0, 4).map((id, i) => (
                    <Avatar key={id} name={nameOf(id)} tone={id} className={`w-8 h-8 text-caption ring-2 ring-surface ${i ? '-ml-2' : ''}`} />
                  ))}
                </span>
                <span className="text-callout text-ink">{t('planSheet.here', { n: here.length })}</span>
              </>,
              undefined,
              'plan-here',
            )}
          {onWay.map((id) =>
            row(
              <Navigation {...ico} />,
              <>
                <Avatar name={nameOf(id)} tone={id} className="w-8 h-8 text-caption" />
                <span className="min-w-0 text-callout text-ink truncate">{t('planSheet.onWay', { name: nameOf(id) })}</span>
              </>,
              <span className="text-footnote text-ink-2 shrink-0">{t('planSheet.inMin', { n: Math.max(1, Math.round((st.eta.get(id)! * 60_000 - now) / 60_000)) })}</span>,
              `plan-onway-${id}`,
            ),
          )}
          {late.map((id) =>
            row(
              <Clock {...ico} />,
              <>
                <Avatar name={nameOf(id)} tone={id} className="w-8 h-8 text-caption" />
                <span className="min-w-0 text-callout text-ink truncate">{nameOf(id)}</span>
              </>,
              <span className="flex items-center gap-1 shrink-0">
                <span className="text-footnote text-danger">
                  {t('planSheet.late', { n: Math.max(1, Math.round((now - (st.eta.has(id) ? st.eta.get(id)! * 60_000 : at)) / 60_000)) })}
                </span>
                {id !== meId && canPing && (
                  <button
                    type="button"
                    data-testid={`plan-ping-${id}`}
                    disabled={pinged.includes(id)}
                    onClick={() => ping(id)}
                    className="h-11 min-h-0 px-3 rounded-full text-callout font-semibold text-ink active:bg-overlay disabled:text-ink-3"
                  >
                    {pinged.includes(id) ? t('planSheet.pinged') : t('planSheet.ping')}
                  </button>
                )}
              </span>,
              `plan-late-${id}`,
            ),
          )}
          <div className="mx-4 mt-1 border-t-[0.5px] border-line" />
        </section>
      )}

      {toVerify.length > 0 && (
        <section data-testid="plan-verify">
          {toVerify.map(({ id, c }) => (
            <div key={id}>
              {row(
                <ShieldCheck {...ico} />,
                <>
                  <Avatar name={nameOf(id)} tone={id} className="w-8 h-8 text-caption" />
                  <span className="min-w-0 text-callout text-ink truncate">{nameOf(id)}</span>
                </>,
                <button
                  type="button"
                  data-testid={`plan-verify-${id}`}
                  onClick={() => setVerifyId(id)}
                  className="h-11 min-h-0 px-3 rounded-full text-callout font-semibold text-ink active:bg-overlay"
                >
                  {t('chats21.verifyKeys')}
                </button>,
                `plan-verify-row-${id}`,
              )}
              {verifyId === id && (
                <div className="px-4 pb-2">
                  <SafetyVerifyBlock
                    contact={c}
                    startOpen
                    startPanel="qr"
                    onVerified={(updated) => {
                      setContacts((prev) =>
                        prev
                          ? prev.map((item) =>
                              item.id === updated.id || item.peer_node_id === updated.peer_node_id ? updated : item,
                            )
                          : [updated],
                      );
                      setVerifyId(null);
                    }}
                    onDismiss={() => setVerifyId(null)}
                  />
                </div>
              )}
            </div>
          ))}
          <div className="mx-4 mt-1 border-t-[0.5px] border-line" />
        </section>
      )}

      <section data-testid="plan-bring">
        <h3 className="px-4 pt-3 pb-1 text-caption font-semibold text-ink-2">{t('planSheet.bring')}</h3>
        {st.bring.size === 0 && !adding && <p className="px-4 pb-1 text-footnote text-ink-3">{t('planSheet.bringEmpty')}</p>}
        {[...st.bring.entries()].map(([what, who]) => {
          const mine = who.includes(meId);
          return (
            <button
              key={what}
              type="button"
              role="checkbox"
              aria-checked={mine}
              data-testid={`plan-bring-${what}`}
              disabled={busy}
              onClick={() => void mark(bringValue(what))}
              className="w-full min-h-12 px-4 flex items-center gap-4 text-left disabled:opacity-60"
            >
              <span
                aria-hidden
                className={`w-[18px] h-[18px] shrink-0 rounded-[4px] flex items-center justify-center ${mine ? 'bg-ink text-surface' : 'border-2 border-ink-3'}`}
              >
                {mine && <Check className="w-3.5 h-3.5" strokeWidth={3} />}
              </span>
              <span className="flex-1 min-w-0 text-callout text-ink truncate">{what}</span>
              <span className="text-footnote text-ink-2 shrink-0 truncate max-w-[40%]">{who.map(nameOf).join(', ')}</span>
            </button>
          );
        })}
        {adding ? (
          <form
            className="px-4 py-1 flex items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void addThing();
            }}
          >
            <input
              autoFocus
              value={thing}
              onChange={(e) => setThing(e.target.value)}
              placeholder={t('planSheet.bringWhat')}
              data-testid="plan-bring-input"
              className="flex-1 min-w-0 h-11 rounded-control bg-ink/[.06] px-3 text-callout text-ink placeholder:text-ink-3 outline-none"
            />
            <button
              type="submit"
              data-testid="plan-bring-save"
              disabled={!thing.trim() || busy}
              className="h-11 min-h-0 px-4 rounded-full text-callout font-semibold text-ink bg-ink/[.08] disabled:opacity-40"
            >
              {t('planSheet.iBring')}
            </button>
          </form>
        ) : (
          <button
            type="button"
            data-testid="plan-bring-add"
            onClick={() => setAdding(true)}
            className="w-full min-h-12 px-4 flex items-center gap-4 text-left text-callout font-semibold text-ink active:bg-overlay"
          >
            <Plus className="w-5 h-5 shrink-0" strokeWidth={1.75} aria-hidden />
            {t('planSheet.add')}
          </button>
        )}
      </section>

      {after.length > 0 && (
        <section data-testid="plan-after">
          <div className="mx-4 mt-1 border-t-[0.5px] border-line" />
          <h3 className="px-4 pt-3 pb-1 text-caption font-semibold text-ink-2">{t('planSheet.after')}</h3>
          <div className="px-1 flex">
            {after.map((a) => (
              <button
                key={a.id}
                type="button"
                data-testid={`plan-after-${a.id}`}
                onClick={a.go}
                className="flex-1 min-w-0 min-h-16 py-2 flex flex-col items-center gap-1.5 text-footnote text-ink-2 active:opacity-80"
              >
                <span className="text-ink">{a.icon}</span>
                <span className="max-w-full truncate px-1">{a.label}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      {error && (
        <p className="px-4 pt-2 text-caption text-danger" data-testid="plan-error">
          {error}
        </p>
      )}
      <div className="h-4" />
    </FormSheet>
  );
};
