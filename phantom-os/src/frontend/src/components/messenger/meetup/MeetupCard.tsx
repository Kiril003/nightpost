/**
 * Картка зустрічі (`widget:meetup`): де, коли, хто іде і коли буде.
 *
 * Тіло картки незмінне — заклад, час, хто кличе, кого. Усе, що міняється
 * (іду / не йду, час прибуття, «Лишити», «Я на місці», страви), — позначки
 * `PH-CST:`, які міст згортає в `card_marks`. Дотик шле позначку й міняє
 * показ лише після того, як міст її взяв (`setCardMark`).
 */
import React, { useMemo, useState } from 'react';
import { localNameOf } from '../../../services/localName';
import { Bell, CalendarClock, CalendarDays, CalendarX, Check, Footprints, ListChecks, UserPlus, Map as MapIcon, MapPin, MapPinCheck, Navigation, ReceiptText } from 'lucide-react';
import { TicketCard } from '../kit/TicketCard';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { useMessengerStore } from '../../../stores/messengerStore';
import { hasVenuePage, isPinVenue, parseMeetupData, type MeetupData, type VenueSnap } from '../../../types/meetup';
import type { Message } from '../../../types/messenger';
import { HERE_VALUE, KEEP_VALUE, PLUS_VALUE, cancelledBy, etaValue, lockValue, movedAtOf, readCardMarks, rsvpValue, slotValue } from '../../../services/meetup/marks';
import { chosenAt } from '../../../services/meetup/draft';
import { expiryState, isPlanGroup } from '../../../services/meetup/expiry';
import { cancelMeetup, checkinWords, meetupApi, meetupReminded, moveMeetup, refusalWords, remindBefore, setCardMark } from '../../../services/meetupApi';
import { openMeetupAsk } from '../../../services/nativeMeetupDraft';
import { soundFx } from '../../../utils/messengerSound';
import { leavePlan, useOwnFix } from '../../../services/meetup/walk';
import { EtaPicker } from './EtaPicker';
import { MeetLiveRow, MeetShareSheet, useMeetWhere } from './MeetShare';
import { phoneBridge } from '../../../services/messengerApi';
import { VenueSheet } from './VenueSheet';
import { PlanSheet } from './PlanSheet';
import { PlanMoveSheet } from './PlanMoveSheet';
import { BillDraftSheet } from './BillSheet';
import { tNow } from '../../chat/tNow';
import { useFactDiff } from '../../../services/factDiff';
import { hhmm, ratingWord, shortWhen, whenWord } from './words';

import { useTranslation } from '../../../i18n/useTranslation';

const dayOf = (ms: number) => new Date(ms).toDateString();
import { Avatar } from '../Avatar';
/**
 * «Я на місці» — у вікні сервера балів: ±2 год від часу зустрічі
 * (`points.py` `WINDOW_MS`). Пізніше сервер відмовив би `outside_window`,
 * тож пропонувати бали там — обіцяти те, чого не буде.
 */
const HERE_FROM = 2 * 3600_000;
const HERE_UNTIL = 2 * 3600_000;

const Chip: React.FC<{
  label: string;
  on?: boolean;
  primary?: boolean;
  testId: string;
  onClick: () => void;
  disabled?: boolean;
}> = ({ label, on, primary, testId, onClick, disabled }) => (
  <button
    type="button"
    data-testid={testId}
    aria-pressed={on}
    disabled={disabled}
    onClick={(e) => {
      e.stopPropagation();
      onClick();
    }}
    className={`touch-floor-inline h-9 px-4 shrink-0 rounded-full inline-flex items-center gap-1.5 text-callout transition-colors duration-instant active:opacity-80 disabled:opacity-40 ${
      primary ? 'bg-accent text-on-accent font-semibold' : 'bg-ink/[.08] text-ink font-medium'
    }`}
  >
    {on && <Check className="w-4 h-4" strokeWidth={2} aria-hidden />}
    {label}
  </button>
);

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

/** Дія картки: значок над коротким словом, як у нижній смузі M3. */
const IconAction: React.FC<{
  icon: React.ReactNode;
  label: string;
  full?: string;
  testId: string;
  onClick: () => void;
  on?: boolean;
  disabled?: boolean;
}> = ({ icon, label, full, testId, onClick, on, disabled }) => (
  <button
    type="button"
    data-testid={testId}
    aria-label={full ?? label}
    aria-pressed={on}
    disabled={disabled}
    onClick={(e) => {
      e.stopPropagation();
      onClick();
    }}
    className="flex-1 min-w-0 min-h-14 py-1.5 flex flex-col items-center gap-1 text-caption font-medium text-ink-2 disabled:opacity-40 active:opacity-80"
  >
    <span className={`w-10 h-8 rounded-full flex items-center justify-center text-ink ${on ? 'bg-ink/[.08]' : ''}`}>{icon}</span>
    <span className="max-w-full truncate px-0.5">{label}</span>
  </button>
);

type MapBridge = { openMap?: (lat: number, lon: number, label?: string) => boolean };

/** Місце зустрічі — міткою на мапі телефона; на ПК — на OSM. */
function openVenueOnMap(v: VenueSnap): void {
  if (v.lat === null || v.lon === null) return;
  const b = (globalThis as { PhantomBridge?: MapBridge }).PhantomBridge;
  if (b && typeof b.openMap === 'function') {
    b.openMap(v.lat, v.lon, v.name);
    return;
  }
  window.open(`https://www.openstreetmap.org/?mlat=${v.lat}&mlon=${v.lon}#map=17/${v.lat}/${v.lon}`, '_blank', 'noopener');
}

const iAmHereOf = (msg: Message, meId: string) => readCardMarks(msg.cardMarks).here.has(meId);

const capital = (s: string) => s.charAt(0).toLocaleUpperCase() + s.slice(1);

/** Хто в зустрічі: той, хто кличе, і ті, кого кличуть. Імʼя того, хто кличе, — з підпису картки. */
function peopleOf(m: MeetupData, msg: Message, meId: string, meName: string) {
  const organizerName = m.organizer === meId ? meName : m.organizer === msg.senderId ? msg.senderName : tNow('meetupCard.whoInvites');
  return [{ id: m.organizer, name: organizerName }, ...m.invitees.filter((p) => p.id !== m.organizer)];
}

export const MeetupCard: React.FC<{ msg: Message; meId: string }> = ({ msg, meId }) => {
  const { t } = useTranslation();
  const meetup = useMemo(() => parseMeetupData(msg.meetupData), [msg.meetupData]);
  const meName = useMessengerStore((s) => s.currentUser.name);
  const chats = useMessengerStore((s) => s.chats);
  const chatId = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id))?.id ?? s.activeChatId);
  const planGroup = useMessengerStore((s) => {
    const c = s.chats.find((x) => x.messages.some((m) => m.id === msg.id));
    return c ? isPlanGroup(c.messages) : true;
  });
  const [open, setOpen] = useState(false);
  const [etaOpen, setEtaOpen] = useState(false);
  const [venueOpen, setVenueOpen] = useState(false);
  const [billOpen, setBillOpen] = useState(false);
  const [planOpen, setPlanOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [whereNonce, setWhereNonce] = useState(0);
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [remindedAt, setRemindedAt] = useState<number | null>(null);
  const early = meetup ? readCardMarks(msg.cardMarks) : null;
  const parsedAt = meetup && early ? (movedAtOf(msg.cardMarks, meetup.organizer) ?? chosenAt(meetup, early.lock) ?? 0) : 0;
  const myYes = early?.rsvp.get(meId) === 'yes';
  const fix = useOwnFix(myYes && parsedAt > Date.now() && parsedAt - Date.now() < 2 * 3600_000 && !iAmHereOf(msg, meId));
  const end = parsedAt + (meetup?.durationMin ?? 120) * 60_000;
  const meetPeers = meetup && early ? peopleOf(meetup, msg, meId, meName).filter((p) => p.id !== meId && early.rsvp.get(p.id) === 'yes') : [];
  const changed = useFactDiff(meetup ? meetup.id : null, parsedAt > 0 ? { at: parsedAt } : null);
  const whereLive = useMeetWhere(meetPeers, myYes && parsedAt > 0 && Date.now() < end && Date.now() > parsedAt - 6 * 3600_000, whereNonce);

  if (!meetup) {
    return <TicketCard testId="card-unreadable" title={t('meetupCard.meetup')} status={t('meetupCard.unreadable')} />;
  }

  const now = Date.now();
  const st = readCardMarks(msg.cardMarks);
  const moved = movedAtOf(msg.cardMarks, meetup.organizer);
  const chosen = moved ?? chosenAt(meetup, st.lock);
  const voting = chosen === null;
  const at = chosen ?? meetup.at;
  const cancelled = cancelledBy(msg.cardMarks, meetup.organizer);
  const iOrganize = meetup.organizer === meId;
  const people = peopleOf(meetup, msg, meId, meName);
  const going = people.filter((p) => st.rsvp.get(p.id) === 'yes').length;
  const maybe = people.filter((p) => st.rsvp.get(p.id) === 'maybe').length;
  const hereCount = people.filter((p) => st.here.has(p.id)).length;
  const myRsvp = st.rsvp.get(meId) ?? null;
  const myEta = st.eta.has(meId) ? st.eta.get(meId)! * 60_000 : null;
  const iAmHere = st.here.has(meId);
  const expiry = planGroup ? expiryState({ ...meetup, at }, msg.cardMarks, now) : ({ kind: 'none' } as const);
  const canManage = iOrganize && Boolean(msg.isSelf) && !cancelled && expiry.kind !== 'expired';
  const taken = going + people.filter((p) => st.rsvp.get(p.id) === 'yes' && st.plus.has(p.id)).length;
  const full = meetup.spots != null && taken >= meetup.spots;
  const spotsWord = meetup.spots == null ? '' : full ? t('meetupSpots.full') : t('meetupSpots.left', { n: meetup.spots - taken });
  const nameOf = (id: string) => (id === meId ? t('meetupCard.you') : localNameOf(chats, id, people.find((p) => p.id === id)?.name ?? t('meetupCard.someone')));
  const hereWindow = !voting && now >= at - HERE_FROM && now <= at + HERE_UNTIL;
  const leave = voting ? null : leavePlan(fix, meetup.venue, at, now);

  const mark = async (value: string) => {
    if (busy) return;
    soundFx.playTap();
    setBusy(true);
    setError(null);
    const said = await setCardMark(msg, meId, value);
    setBusy(false);
    if (said) setError(said);
    return said;
  };

  const here = async (claim: boolean) => {
    setConsent(false);
    if (claim) {
      setBusy(true);
      try {
        setNote(checkinWords(await meetupApi.checkin({ ...meetup, at })));
      } catch (e) {
        setError(refusalWords(e, 'svc.meetup.what.points'));
        return;
      } finally {
        setBusy(false);
      }
    }
    await mark(HERE_VALUE);
  };

  const reminded = remindedAt === at || meetupReminded(meetup.id, at);
  const remind = async () => {
    if (reminded) return;
    const r = remindBefore({ ...meetup, at }, chatId, now);
    if (!r) return;
    setRemindedAt(at);
    try {
      await r;
    } catch {
      setRemindedAt(null);
    }
  };

  const onMove = async (newAt: number) => {
    if (busy) return;
    soundFx.playTap();
    setBusy(true);
    setError(null);
    const said = await moveMeetup(msg, meId, meetup, at, newAt, chatId);
    setRemindedAt(null);
    setBusy(false);
    if (said) setError(said);
  };

  const onCancelPlan = async () => {
    if (busy) return;
    soundFx.playTap();
    setCancelOpen(false);
    setBusy(true);
    setError(null);
    const said = await cancelMeetup(msg, meId);
    setBusy(false);
    if (said) setError(said);
  };

  const splitOpen = !voting && now >= at - HERE_FROM;
  const paged = hasVenuePage(meetup.venue);
  const onMap = meetup.venue.lat !== null && meetup.venue.lon !== null;
  const actions = [
    myRsvp !== 'no' && !voting && now < at + HERE_UNTIL && (
      <IconAction
        key="eta"
        icon={<Navigation {...ICO} />}
        label={myEta !== null ? t('meetupCard.etaShort', { time: hhmm(myEta) }) : t('meetupCard.onMyWay')}
        full={myEta !== null ? t('meetupCard.etaAt', { time: hhmm(myEta) }) : t('meetupCard.onMyWay')}
        testId="meetup-eta"
        on={myEta !== null}
        disabled={busy}
        onClick={() => (phoneBridge() ? setShareOpen(true) : setEtaOpen(true))}
      />
    ),
    myRsvp !== 'no' && hereWindow && !iAmHere && (
      <IconAction key="here" icon={<MapPinCheck {...ICO} />} label={t('meetupCard.here')} testId="meetup-here" disabled={busy} onClick={() => (paged ? setConsent(true) : void here(false))} />
    ),
    meetup.plusOne && myRsvp === 'yes' && !voting && now < at && (
      <IconAction
        key="plus"
        icon={<UserPlus {...ICO} />}
        label="+1"
        full={t('meetupSpots.plusOne')}
        testId="meetup-plus"
        on={st.plus.has(meId)}
        disabled={busy || (full && !st.plus.has(meId))}
        onClick={() => void mark(PLUS_VALUE)}
      />
    ),
    splitOpen && <IconAction key="split" icon={<ReceiptText {...ICO} />} label={t('planGroup.split')} full={t('meetupCard.split')} testId="meetup-split" onClick={() => setBillOpen(true)} />,
    myRsvp === 'yes' && !voting && at - 30 * 60_000 > now && !reminded && (
      <IconAction key="remind" icon={<Bell {...ICO} />} label={t('meetupCard.remindShort')} full={t('meetupCard.remindAt', { time: hhmm(at - 30 * 60_000) })} testId="meetup-remind" onClick={() => void remind()} />
    ),
    <IconAction key="plan" icon={<ListChecks {...ICO} />} label={t('planSheet.open')} full={t('planSheet.openFull')} testId="meetup-plan" onClick={() => setPlanOpen(true)} />,
  ].filter(Boolean);
  const manage = canManage
    ? [
        <IconAction key="move" icon={<CalendarClock {...ICO} />} label={t('meetupOrganiser.move')} testId="meetup-move" disabled={busy} onClick={() => setMoveOpen(true)} />,
        <IconAction key="cancel" icon={<CalendarX {...ICO} />} label={t('meetupOrganiser.cancel')} testId="meetup-cancel" disabled={busy} onClick={() => setCancelOpen(true)} />,
      ]
    : [];

  return (
    <>
      <TicketCard
        testId="card-meetup"
        title={meetup.title || meetup.venue.name}
        kind={{ label: t('meetupCard.meetup'), icon: <CalendarDays strokeWidth={1.75} /> }}
        perforated
        status={
          cancelled
            ? t('meetupOrganiser.cancelled')
            : voting
              ? t('meetupVote.voting')
              : capital(whenWord(at, now)) + (meetup.durationMin ? `–${hhmm(at + meetup.durationMin * 60_000)}` : '')
        }
        lines={
          (paged || onMap || meetup.venue.address || meetup.title) && (
            <div className="-mb-2 min-h-11 flex items-center">
              {paged ? (
                <button
                  type="button"
                  data-testid="meetup-venue"
                  onClick={() => setVenueOpen(true)}
                  className="flex-1 min-w-0 min-h-11 -ml-4 pl-4 flex items-center gap-1.5 text-left active:bg-ink/[.06]"
                >
                  <MapPin className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
                  <span className="flex-1 min-w-0 text-subhead font-normal text-ink-3 truncate">
                    {meetup.title ? `${meetup.venue.name} · ` : ''}
                    {meetup.venue.address ? `${meetup.venue.address} · ` : ''}
                    {ratingWord(meetup.venue.rating)}
                  </span>
                </button>
              ) : (
                <span className="flex-1 min-w-0 flex items-center gap-1.5 text-subhead font-normal text-ink-3">
                  <MapPin className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />
                  <span className="truncate">{meetup.title ? `${meetup.venue.name} · ` : ''}{isPinVenue(meetup.venue) ? t('meetupPin.onMap') : meetup.venue.address}</span>
                </span>
              )}
              {onMap && (
                <button
                  type="button"
                  data-testid="meetup-map"
                  aria-label={t('meetupPin.showOnMap')}
                  onClick={() => openVenueOnMap(meetup.venue)}
                  className="w-9 h-9 min-h-0 min-w-0 -mr-1 shrink-0 rounded-full bg-overlay flex items-center justify-center text-ink touch-floor-inline active:opacity-80"
                >
                  <MapIcon className="w-[18px] h-[18px]" strokeWidth={1.75} />
                </button>
              )}
            </div>
          )
        }
      >
        <button
          type="button"
          data-testid="meetup-people"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
          className="mx-4 min-h-12 w-[calc(100%-32px)] flex items-center gap-2.5 text-left"
        >
          <span className="flex shrink-0">
            {people.slice(0, 4).map((p, i) => (
              <Avatar key={p.id} name={nameOf(p.id)} tone={p.id} className={`w-6 h-6 text-caption border-2 border-surface ${i ? '-ml-1.5' : ''}`} />
            ))}
          </span>
          <span className="flex-1 min-w-0">
            <span data-testid="meetup-going" className="block text-subhead font-normal text-ink-2 truncate tnum">
              {[
                t(going === 1 ? 'meetupCard.goingOne' : 'meetupCard.goingMany', { n: going, total: people.length }),
                spotsWord,
                maybe > 0 ? t('meetupCard.maybeCount', { n: maybe }) : '',
                hereCount > 0 ? t('meetupCard.hereCount', { n: hereCount }) : '',
              ].filter(Boolean).join(' · ')}
            </span>
            <span className="block text-footnote text-ink-3 truncate">{people.map((p) => nameOf(p.id)).join(', ')}</span>
          </span>
        </button>

        {open && (
          <ul className="px-4">
            {people.map((p) => {
              const r = st.rsvp.get(p.id);
              const eta = st.eta.get(p.id);
              const word = st.here.has(p.id)
                ? t('meetupCard.atPlace')
                : r === 'yes' ? t('meetupCard.going') : r === 'maybe' ? t('meetupCard.maybe') : r === 'no' ? t('meetupCard.notGoing') : t('meetupCard.noReply');
              return (
                <li key={p.id} data-testid={`meetup-person-${p.id}`} className="min-h-10 flex items-center gap-3">
                  <span className="flex-1 min-w-0 text-callout text-ink truncate">
                    {nameOf(p.id)}
                    {r === 'yes' && st.plus.has(p.id) ? ' +1' : ''}
                    {eta !== undefined && r !== 'no' ? ` · ${hhmm(eta * 60_000)}` : ''}
                  </span>
                  <span className={`text-footnote shrink-0 ${r === 'yes' || st.here.has(p.id) ? 'text-ink-2' : 'text-ink-3'}`}>{word}</span>
                </li>
              );
            })}
          </ul>
        )}

        {changed && !cancelled && (
          <p data-testid="meetup-changed" className="mx-4 mt-1 text-footnote text-ink-2 tnum">
            {t('meetupCard.timeMoved', {
              from: dayOf(changed.atFrom) === dayOf(changed.atTo) ? hhmm(changed.atFrom) : whenWord(changed.atFrom, now),
              to: dayOf(changed.atFrom) === dayOf(changed.atTo) ? hhmm(changed.atTo) : whenWord(changed.atTo, now),
            })}
          </p>
        )}

        {voting && meetup.slots && (
          <ul data-testid="meetup-vote" className="mx-4 mt-1 rounded-control border border-line-strong divide-y divide-line">
            {meetup.slots.map((slot, i) => {
              const voters = st.votes.get(i) ?? [];
              const mine = voters.includes(meId);
              return (
                <li key={i} className="min-h-12 flex items-center">
                  <button
                    type="button"
                    role="checkbox"
                    aria-checked={mine}
                    data-testid={`meetup-slot-${i}`}
                    disabled={busy}
                    onClick={() => void mark(slotValue(i))}
                    className="flex-1 min-w-0 min-h-12 px-3 flex items-center gap-3 text-left disabled:opacity-40"
                  >
                    <span aria-hidden className={`w-[18px] h-[18px] shrink-0 rounded-[4px] flex items-center justify-center ${mine ? 'bg-ink text-surface' : 'border-2 border-ink-3'}`}>
                      {mine && <Check className="w-3.5 h-3.5" strokeWidth={3} />}
                    </span>
                    <span className="flex-1 min-w-0 text-callout text-ink truncate">{capital(whenWord(slot, now))}</span>
                    <span className="text-footnote text-ink-2 shrink-0">{t('meetupVote.votes', { n: voters.length })}</span>
                  </button>
                  {iOrganize && (
                    <button
                      type="button"
                      data-testid={`meetup-lock-${i}`}
                      disabled={busy}
                      onClick={() => void mark(lockValue(i))}
                      className="touch-floor-inline h-9 mr-2 px-3 shrink-0 rounded-full text-footnote font-semibold text-ink bg-ink/[.08] active:opacity-80"
                    >
                      {t('meetupVote.pick')}
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        {leave && (
          <div data-testid="meetup-leave" className="mx-4 mt-1 px-3 min-h-11 rounded-control bg-ink/[.06] flex items-center gap-2">
            <Footprints className="w-[18px] h-[18px] shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
            <span className="flex-1 min-w-0 text-footnote font-semibold text-ink">
              {leave.leaveIn > 0 ? t('meetupCard.leaveIn', { n: leave.leaveIn }) : t('meetupCard.leaveNow')}
            </span>
            <span className="text-caption text-ink-2 shrink-0">{t('meetupCard.walk', { n: leave.walk })}</span>
          </div>
        )}
        <MeetLiveRow peers={meetPeers.map((p) => ({ id: p.id, name: nameOf(p.id) }))} states={whereLive} onStop={() => setWhereNonce((n) => n + 1)} />

        {!cancelled && (
          <div role="group" aria-label={t('meetupCard.rsvpLabel')} data-testid="meetup-rsvp" className="mx-4 mt-2 flex gap-2">
            {(['yes', 'maybe', 'no'] as const).map((r) => (
              <button
                key={r}
                type="button"
                data-testid={`meetup-rsvp-${r}`}
                aria-pressed={myRsvp === r}
                disabled={busy || (r === 'yes' && full && myRsvp !== 'yes')}
                onClick={(e) => {
                  e.stopPropagation();
                  if (myRsvp !== r) void mark(rsvpValue(r));
                }}
                className={`touch-floor-inline flex-1 min-w-0 h-9 px-2 rounded-full inline-flex items-center justify-center gap-1 whitespace-nowrap text-subhead font-semibold transition-colors duration-instant disabled:opacity-40 ${
                  // Рендер rsvp-card: обране — мʼяка заливка + чорнило; бурштину в картці нема (він — одна дія екрана).
                  myRsvp === r ? 'bg-accent-soft text-ink' : 'border border-line-strong text-ink active:bg-ink/[.06]'
                }`}
              >
                {myRsvp === r && <Check className="w-4 h-4 shrink-0" strokeWidth={2} aria-hidden />}
                {t(r === 'yes' ? 'meetupCard.rsvpYes' : r === 'maybe' ? 'meetupCard.rsvpMaybe' : 'meetupCard.rsvpNo')}
              </button>
            ))}
          </div>
        )}

        {!cancelled && actions.length > 0 && (
          <div data-testid="meetup-actions" className="px-1 pt-1 flex">
            {actions}
          </div>
        )}
        {manage.length > 0 && (
          <div data-testid="meetup-manage" className="px-1 flex">
            {manage}
            <span aria-hidden className="flex-[3]" />
          </div>
        )}
        {!cancelled && myRsvp === 'yes' && !voting && at > now && reminded && (
          <p data-testid="meetup-reminded" className="px-4 text-caption text-ink-3">
            {t('meetupCard.willRemind', { time: hhmm(at - 30 * 60_000) })}
          </p>
        )}

        {consent && (
          <div className="mx-4 mt-2 p-3 rounded-control bg-ink/[.08]" data-testid="here-consent">
            <p className="text-footnote text-ink-2">
              {t('meetupCard.consent')}
            </p>
            <div className="pt-2 flex gap-2">
              <Chip label={t('meetupCard.consentYes')} testId="here-consent-yes" primary onClick={() => void here(true)} />
              <Chip label={t('meetupCard.markOnly')} testId="here-mark-only" onClick={() => void here(false)} />
            </div>
          </div>
        )}

        {expiry.kind !== 'none' && (
          <div className="px-4 pt-2 flex items-center gap-2" data-testid="meetup-expiry">
            <span data-meetup-expiry-line className="flex-1 min-w-0 truncate text-caption text-ink-3">
              {expiry.kind === 'cancelled'
                ? t('meetupOrganiser.cancelled')
                : expiry.kind === 'kept'
                  ? t('meetupCard.keptBy', { names: expiry.by.map(nameOf).join(', ') })
                  : expiry.kind === 'will_hide'
                    ? t('meetupCard.willHide', { when: shortWhen(expiry.at, now) })
                    : t('meetupCard.expired')}
            </span>
            {expiry.kind !== 'kept' && (
              <button
                type="button"
                data-testid="meetup-keep"
                disabled={busy}
                onClick={() => void mark(KEEP_VALUE)}
                className="touch-floor-inline h-9 px-4 shrink-0 rounded-full text-callout font-medium text-ink bg-ink/[.08] active:opacity-80"
              >
                {t('meetupCard.keep')}
              </button>
            )}
          </div>
        )}

        {error && (
          <p className="px-4 pt-2 text-caption text-danger" data-testid="meetup-error">
            {error}
          </p>
        )}
        {note && !error && (
          <p className="px-4 pt-2 text-caption text-ink-3" data-testid="meetup-note">
            {note}
          </p>
        )}
      </TicketCard>

      <EtaPicker
        open={etaOpen}
        at={at}
        current={myEta}
        onClose={() => setEtaOpen(false)}
        onPick={(ms) => void mark(etaValue(ms))}
      />
      {shareOpen && (
        <MeetShareSheet
          peers={meetPeers.map((p) => ({ id: p.id, name: nameOf(p.id) }))}
          end={at + (meetup.durationMin ?? 120) * 60_000}
          onArrivalOnly={() => setEtaOpen(true)}
          onClose={() => setShareOpen(false)}
          onStarted={() => setWhereNonce((n) => n + 1)}
        />
      )}
      <VenueSheet
        open={venueOpen}
        onClose={() => setVenueOpen(false)}
        venueId={meetup.venue.id}
        snap={meetup.venue}
        onMeetHere={(venue) => openMeetupAsk({ kind: 'meetup', venue })}
      />
      <BillDraftSheet open={billOpen} onClose={() => setBillOpen(false)} meetupMsg={msg} />
      {planOpen && (
        <PlanSheet
          msg={msg}
          meetup={meetup}
          meId={meId}
          onClose={() => setPlanOpen(false)}
          onOpenMap={() => openVenueOnMap(meetup.venue)}
          onRate={() => {
            setPlanOpen(false);
            setVenueOpen(true);
          }}
        />
      )}
      <PlanMoveSheet
        open={moveOpen}
        at={at}
        onClose={() => setMoveOpen(false)}
        onPick={(ms) => void onMove(ms)}
      />
      <FormSheet
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        title={t('meetupOrganiser.cancelTitle')}
        testId="meetup-cancel-sheet"
        footer={
          <div className="flex flex-col gap-2">
            <div data-testid="meetup-cancel-confirm">
              <PrimaryButton onClick={() => void onCancelPlan()} disabled={busy}>
                {t('meetupOrganiser.cancelPlan')}
              </PrimaryButton>
            </div>
            <button
              type="button"
              data-testid="meetup-cancel-back"
              onClick={() => setCancelOpen(false)}
              className="w-full h-12 min-h-0 rounded-control text-callout font-medium text-ink-2 active:bg-overlay"
            >
              {t('meetupOrganiser.back')}
            </button>
          </div>
        }
      >
        <div className="px-4 py-2 text-callout text-ink">{meetup.venue.name} · {whenWord(at, now)}</div>
      </FormSheet>
    </>
  );
};
