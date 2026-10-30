/**
 * Чернетка зустрічі: заклад, час, люди → «Запросити».
 *
 * Відкривається з «+ → Ще → Зустріч», зі сторінки закладу («Зустрітися тут»)
 * і від помічника телефона (`__phantomOpenMeetupDraft`), уже заповнена з
 * його слів. Помічник нічого не надсилає: «Запросити» натискає людина.
 *
 * Люди — зі свого списку цього телефона; ті, хто зараз ділиться місцем,
 * нагорі з «поруч/далеко» (координат у вебі немає). Заклади — ті, що мають
 * сторінку біля мене (`/messenger/venues/near`). Чого не знайшли — сказано.
 *
 * «Запросити» = нова група «Cafe X · 19:00» з цими людьми і картка зустрічі
 * в ній (тимчасова: ховається через добу після зустрічі, якщо ніхто не
 * лишить), або картка в цю групу. Поки дріт не везе картку зустрічі —
 * кнопку вимкнено з причиною.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Bell, CalendarClock, Check, ChevronRight, Info, MapPin, Minus, Plus, Search, UserPlus, Users, X } from 'lucide-react';
import { CIRCLES, CIRCLE_KEY } from '../todayFormat';
import { metersBetween, useOwnFix, walkMinutes } from '../../../services/meetup/walk';
import { distanceLabel } from '../../../services/peerWire';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { Avatar } from '../Avatar';
import { navigateTo } from '../../../services/nav';
import { useMessengerStore } from '../../../stores/messengerStore';
import { request } from '../../../services/api';
import { messengerApi, phoneBridge } from '../../../services/messengerApi';
import { whereApi } from '../../../services/peerWhere';
import { isOwnDeviceContact } from '../../../services/peopleIdentity';
import {
  buildMeetupDraft,
  groupTitle,
  meetupFromDraft,
  parseDraftArgs,
  rankInvitees,
  resolveVenue,
  type InviteNear,
  type PeopleResolution,
} from '../../../services/meetup/draft';
import { cardKindRefusal, inviteMeetup, meetupApi, ratingOf, refusalWords, remindBefore, snapOfPage } from '../../../services/meetupApi';
import { openMeetupAsk, parkMeetupDraft, useMeetupAsk, type ParkedDraft } from '../../../services/nativeMeetupDraft';
import { MAX_SLOTS, MAX_TITLE, MEETUP_KIND, isPinVenue, parseMeetupData, placeVenue, typedVenue, type MeetupInvitee, type VenueSnap } from '../../../types/meetup';
import { VenueSheet } from './VenueSheet';
import { BillDraftSheet, BillSheet } from './BillSheet';
import { newCardId, ratingWord, whenWord } from './words';
import { displayTitle, isUnnamed } from '../chatListFormat';
import { tNow } from '../../chat/tNow';
import { useTranslation } from '../../../i18n/useTranslation';
import { useWhenFlow } from '../when/WhenFlow';
import { slotMs, toSlot } from '../when/whenMath';
export interface MeetupSeed {
  /** Слова помічника: `{people, place, at}` (рядок JSON чи обʼєкт). */
  args?: unknown;
  venue?: VenueSnap | null;
  venueId?: string | null;
  /** Розмова, з якої відкрито («+»): у групі можна покликати сюди ж. */
  chatId?: string | null;
  /** Чернетка, яку людина відклала, щоб поставити мітку на мапі. */
  parked?: ParkedDraft | null;
}

const VENUE_HEX = /^[0-9a-f]{16}$/;

function placeRowToSnap(raw: unknown): VenueSnap | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const name = typeof o.name === 'string' ? o.name.trim() : '';
  const lat = typeof o.lat === 'number' && Number.isFinite(o.lat) ? o.lat : null;
  const lon = typeof o.lon === 'number' && Number.isFinite(o.lon) ? o.lon : null;
  if (!name || lat === null || lon === null) return null;
  const address = typeof o.address === 'string' ? o.address.trim() : '';
  const id = typeof o.id === 'string' && o.id.trim() ? o.id.trim() : null;
  if (!id) return placeVenue({ lat, lon, name, address });
  const mv = typeof o.menu_version === 'string' || typeof o.menu_version === 'number' ? o.menu_version : null;
  return {
    id,
    name,
    address,
    lat,
    lon,
    rating: ratingOf(o.rating),
    menuVersion: mv,
    wallet: typeof o.wallet === 'string' && o.wallet.trim() ? o.wallet.trim() : null,
  };
}

/** Множинний вибір — квадрат із галочкою, а не круг перемикача (UX-аудит W4). */
const CheckBox: React.FC<{ on: boolean }> = ({ on }) => (
  <span
    aria-hidden
    data-shape="square"
    className={`w-[18px] h-[18px] shrink-0 rounded-[4px] flex items-center justify-center ${
      on ? 'bg-ink text-canvas' : 'border-2 border-ink-3'
    }`}
  >
    {on && <Check className="w-3.5 h-3.5" strokeWidth={3} />}
  </span>
);

const Lead: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="w-10 h-10 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">{children}</span>
);
const SumRow: React.FC<{ testId: string; icon: React.ReactNode; main: string; sub?: string; empty?: boolean; onClick: () => void; value?: string; subTestId?: string }> = ({
  testId,
  icon,
  main,
  sub,
  empty,
  onClick,
  value,
  subTestId,
}) => (
  <button
    type="button"
    data-testid={testId}
    data-value={value}
    onClick={onClick}
    className="w-full min-h-16 px-3 py-2 flex items-center gap-3 text-left active:bg-overlay"
  >
    {icon}
    <span className="flex-1 min-w-0">
      <span className={`block text-headline truncate ${empty ? 'text-ink-3' : 'text-ink'}`}>{main}</span>
      {sub && (
        <span className="block text-footnote text-ink-3 truncate" data-testid={subTestId}>
          {sub}
        </span>
      )}
    </span>
    <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
  </button>
);

const NEAR_WORD: Record<InviteNear, string> = {
  get same() { return tNow('meetupDraftSheet.nearSame'); },
  get near() { return tNow('meetupDraftSheet.near'); },
  get far() { return tNow('meetupDraftSheet.far'); },
};

/**
 * Кого можна покликати: лише люди з людським іменем, кожна один раз.
 *
 * UX-аудит №3 (09.10): у «Хто» стояли «Вузол 4d839df7», «bd6e·d7af» і три
 * Марти — імена, які склав код, і та сама людина з кількох вузлів. Машинні
 * імена (`displayTitle` → «Без імені» / «Нова розмова») сюди не йдуть, хвіст
 * відбитка зрізано, а однакове імʼя лишається одне — те, з ким є розмова.
 */
export function realInvitees(list: readonly MeetupInvitee[], chatPeers: ReadonlySet<string> = new Set()): MeetupInvitee[] {
  const seenIds = new Set<string>();
  const byName = new Map<string, MeetupInvitee>();
  const order: string[] = [];
  for (const c of list) {
    if (!c.id || seenIds.has(c.id)) continue;
    seenIds.add(c.id);
    const name = displayTitle(c.name || '');
    if (!name || isUnnamed(name) || name === tNow('chatList.newChat')) continue;
    const key = name.toLocaleLowerCase();
    const had = byName.get(key);
    if (!had) {
      byName.set(key, { id: c.id, name });
      order.push(key);
    } else if (!chatPeers.has(had.id) && chatPeers.has(c.id)) {
      byName.set(key, { id: c.id, name });
    }
  }
  return order.map((k) => byName.get(k)!);
}

const capital = (s: string): string => (s ? s[0].toLocaleUpperCase() + s.slice(1) : s);

type DraftStep = 'when' | 'who' | 'where' | 'review';
const STEPS: readonly DraftStep[] = ['when', 'who', 'where', 'review'];

type WhereMap = Record<string, { near: InviteNear | null; expires_at_ms: number } | null>;

export const MeetupDraftSheet: React.FC<{ open: boolean; onClose: () => void; seed?: MeetupSeed }> = ({
  open,
  onClose,
  seed,
}) => {
  const { t } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const originChat = useMessengerStore((s) => (seed?.chatId ? s.chats.find((c) => c.id === seed.chatId) : undefined));
  const [contacts, setContacts] = useState<MeetupInvitee[] | null>(null);
  const [contactsRefused, setContactsRefused] = useState<string | null>(null);
  const [where, setWhere] = useState<WhereMap>({});
  const [venues, setVenues] = useState<VenueSnap[] | null>(null);
  const [places, setPlaces] = useState<VenueSnap[] | null>(null);
  const [venuesRefused, setVenuesRefused] = useState<string | null>(null);
  const [venue, setVenue] = useState<VenueSnap | null>(seed?.venue ?? null);
  const [query, setQuery] = useState(seed?.parked && !seed.venue ? seed.parked.query : '');
  const [slots, setSlots] = useState<Array<number | null>>([seed?.parked?.at ?? null]);
  const [active, setActive] = useState(0);
  const at = slots[0] ?? null;
  // Кроки як у рендерах W1–W5: Коли → Хто → Де → Перевірка. Помічник і повернення з мапи — одразу на перевірку.
  const [step, setStep] = useState<DraftStep>(seed?.parked ? (seed.venue ? 'review' : 'where') : seed?.args ? 'review' : 'when');
  const [whoQuery, setWhoQuery] = useState('');
  const [circle, setCircle] = useState<string | null>(null);
  const [remind, setRemind] = useState(true);
  const [durationMin, setDurationMin] = useState<number | null>(120);
  const [spots, setSpots] = useState<number | null>(null);
  const [plusOne, setPlusOne] = useState(false);
  const [title, setTitle] = useState('');
  const [list, setList] = useState<'near' | 'past'>('near');
  const [picked, setPicked] = useState<string[]>(seed?.parked?.picked ?? []);
  const [missing, setMissing] = useState<PeopleResolution['missing']>([]);
  const [intoHere, setIntoHere] = useState(seed?.parked?.intoHere ?? false);
  const [peek, setPeek] = useState<VenueSnap | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const args = useMemo(() => parseDraftArgs(seed?.args), [seed?.args]);
  // «Коли» — ті самі вікна, що й «Надіслати пізніше» (власник 28.10: «смужка з фіксованим часом»).
  const [whenOpen, setWhenOpen] = useState(false);
  const activeAt = slots[active] ?? null;
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    initial: activeAt !== null ? toSlot(new Date(activeAt)) : undefined,
    onPick: (r) => {
      if (r.kind !== 'at') return;
      const ms = slotMs(r);
      if (ms !== null) setSlots((s) => s.map((x, i) => (i === active ? ms : x)));
      setWhenOpen(false);
      flow.reset();
    },
  });
  const openWhen = (i = active) => {
    const x = slots[i] ?? slots[i - 1] ?? null;
    setActive(i);
    flow.reset(x !== null ? toSlot(new Date(x)) : {});
    setWhenOpen(true);
  };

  // Люди — зі свого списку; хто ділиться місцем — з мосту, без координат.
  useEffect(() => {
    if (!open) return;
    let alive = true;
    setContactsRefused(null);
    messengerApi.listContacts().then(
      (rows) => {
        if (!alive) return;
        const chats = useMessengerStore.getState().chats;
        const list = realInvitees(
          rows
            .filter((c) => !isOwnDeviceContact(c, chats))
            .map((c) => ({ id: c.peer_node_id || c.id, name: c.display_name }))
            .filter((c) => c.id !== me.id),
          new Set(chats.map((c) => c.peerNodeId).filter((x): x is string => !!x)),
        );
        setContacts(list);
        if (!phoneBridge()) return;
        void Promise.allSettled(list.slice(0, 32).map((c) => whereApi.get(c.id))).then((res) => {
          if (!alive) return;
          const map: WhereMap = {};
          res.forEach((r, i) => {
            const theirs = r.status === 'fulfilled' ? r.value?.theirs : null;
            map[list[i].id] = theirs ? { near: theirs.near, expires_at_ms: theirs.expires_at_ms } : null;
          });
          setWhere(map);
        });
      },
      (e) => {
        if (!alive) return;
        setContacts([]);
        setContactsRefused(refusalWords(e, 'svc.meetup.what.contacts'));
      },
    );
    return () => {
      alive = false;
    };
  }, [open, me.id]);

  // Заклади зі сторінкою поруч — лише через міст (клітинку з місця рахує телефон).
  useEffect(() => {
    if (!open) return;
    let alive = true;
    setVenuesRefused(null);
    if (!phoneBridge()) {
      setVenues([]);
      setVenuesRefused(t('meetupDraftSheet.venuesPhoneOnly'));
      return;
    }
    meetupApi.near().then(
      (list) => alive && setVenues(list),
      () => {
        if (!alive) return;
        setVenues([]);
        setVenuesRefused(t('phonefix.meetup.placesOff'));
      },
    );
    return () => {
      alive = false;
    };
  }, [open]);

  // Слова помічника → люди й час, щойно є список людей.
  useEffect(() => {
    if (!open || contacts === null || seed?.parked) return;
    const d = buildMeetupDraft(args, { contacts, venues: [], now: new Date() });
    const ids = d.invitees.map((p) => p.id);
    // З розмови вдвох — співрозмовник уже обраний (рендер W4: «у цій розмові»).
    const peer = originChat?.type !== 'group' ? originChat?.peerNodeId : null;
    if (ids.length === 0 && !args.people.length && peer && contacts.some((c) => c.id === peer)) ids.push(peer);
    setPicked(ids);
    setMissing(d.missing);
    if (d.at !== null) setSlots([d.at]);
  }, [open, contacts, args, seed?.parked, originChat?.type, originChat?.peerNodeId]);

  // …і заклад, щойно є перелік закладів (або пошук, якщо не знайшли).
  useEffect(() => {
    if (!open || seed?.venue || !args.place) return;
    const hit = resolveVenue(args.place, venues ?? []);
    if (hit) setVenue(hit);
    else setQuery(args.place);
  }, [open, venues, args.place, seed?.venue]);

  useEffect(() => {
    if (open && seed?.venue) setVenue(seed.venue);
  }, [open, seed?.venue]);

  useEffect(() => {
    const id = seed?.venueId;
    if (!open || !id || !VENUE_HEX.test(id)) return;
    let alive = true;
    meetupApi.venue(id).then(
      (page) => {
        if (!alive) return;
        const snap = snapOfPage(page);
        if (snap) setVenue(snap);
      },
      () => {},
    );
    return () => {
      alive = false;
    };
  }, [open, seed?.venueId]);

  // «У цю групу»: склад — окремим шляхом мосту, спитати, якщо ще не питали.
  const originId = originChat?.type === 'group' ? originChat.id : null;
  const originMembersKnown = originChat?.members !== undefined;
  useEffect(() => {
    if (!open || !intoHere || !originId || originMembersKnown) return;
    void useMessengerStore.getState().loadGroupMembers(originId);
  }, [open, intoHere, originId, originMembersKnown]);

  const ranked = useMemo(() => rankInvitees(contacts ?? [], where, Date.now()), [contacts, where]);
  // Де вже зустрічались у цій розмові (або в усіх, якщо відкрито не з розмови) — з карток, без вигадок.
  const pastVenues = useMemo(() => {
    const chats = useMessengerStore.getState().chats;
    const pool = originChat ? [originChat] : chats;
    const seen = new Map<string, VenueSnap>();
    for (const c of pool) {
      for (const m of c.messages ?? []) {
        if (m.type !== MEETUP_KIND || m.isDeleted) continue;
        const v = parseMeetupData(m.meetupData)?.venue;
        if (v && v.lat !== null && !seen.has(v.id)) seen.set(v.id, v);
      }
    }
    return [...seen.values()];
  }, [originChat]);
  const fix = useOwnFix(open && step === 'where');

  useEffect(() => {
    if (!open || step !== 'where' || list !== 'near' || !phoneBridge()) return;
    let alive = true;
    const timer = window.setTimeout(() => {
      const qs =
        `q=${encodeURIComponent(query.trim())}` +
        (fix ? `&lat=${fix.lat.toFixed(3)}&lon=${fix.lon.toFixed(3)}` : '');
      request<{ places?: unknown[] }>('GET', `/messenger/places?${qs}`).then(
        (res) => {
          if (!alive) return;
          const rows = Array.isArray(res?.places) ? res.places : [];
          setPlaces(rows.map(placeRowToSnap).filter((v): v is VenueSnap => !!v));
          setVenuesRefused(null);
        },
        () => {
          if (!alive) return;
          setPlaces(null);
          setVenuesRefused(t('phonefix.meetup.placesOff'));
        },
      );
    }, 300);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [open, step, list, query, fix, t]);

  const shownVenues = useMemo(() => {
    const q = query.trim().toLocaleLowerCase('uk');
    if (list === 'past') {
      return q ? pastVenues.filter((v) => v.name.toLocaleLowerCase('uk').includes(q)) : pastVenues;
    }
    if (places !== null) {
      return places.map((p) => {
        if (isPinVenue(p)) return p;
        const fromNear = (venues ?? []).find((v) => v.id === p.id);
        return fromNear
          ? {
              ...p,
              address: p.address || fromNear.address,
              rating: p.rating ?? fromNear.rating,
              menuVersion: p.menuVersion ?? fromNear.menuVersion,
              wallet: p.wallet ?? fromNear.wallet ?? null,
            }
          : p;
      });
    }
    const base = venues ?? [];
    return q ? base.filter((v) => v.name.toLocaleLowerCase('uk').includes(q)) : base;
  }, [venues, places, pastVenues, list, query]);

  if (!open) return null;

  const invitees = ranked.filter((p) => picked.includes(p.id)).map(({ id, name }) => ({ id, name }));
  const groupOrigin = originChat?.type === 'group' ? originChat : undefined;
  const inGroup = intoHere && !!groupOrigin;
  // Той самий перелік, що піде в картку: обрані, а в «цю групу» без обраних —
  // склад групи без мене. Склад ще не приїхав — людей ще немає, і кнопка це знає.
  const groupPeople = (groupOrigin?.members ?? [])
    .filter((m) => !m.isMe && m.id !== me.id)
    .map((m) => ({ id: m.id, name: m.name }));
  const people = inGroup && invitees.length === 0 ? groupPeople : invitees;
  const typed = !venue && query.trim() ? typedVenue(query) : null;
  const place = venue ?? typed;
  const now = Date.now();
  const times = slots.filter((x): x is number => x !== null);
  // Чого бракує — окремо для кожного кроку; перевірка збирає все разом.
  const whenWhy =
    at === null || times.length < slots.length
      ? t('meetupDraftSheet.pickTime')
      : times.some((x) => x <= now)
        ? t('meetupDraftSheet.past')
        : new Set(times).size < times.length
          ? t('meetupPlan.sameTime')
          : null;
  const whoWhy =
    people.length === 0
      ? inGroup && groupOrigin!.members === undefined
        ? t('meetupDraftSheet.groupUnknown')
        : t('meetupDraftSheet.pickPerson')
      : null;
  const whereWhy = place ? null : t('phonefix.meetup.pickPlace');
  const why = cardKindRefusal(MEETUP_KIND) ?? whereWhy ?? whenWhy ?? whoWhy;
  const stepWhy = step === 'when' ? whenWhy : step === 'who' ? whoWhy : step === 'where' ? whereWhy : why;
  const voting = times.length >= 2;
  const canRemind = !voting && at !== null && at - 30 * 60_000 > now;

  const close = () => {
    setError(null);
    onClose();
  };

  const invite = async () => {
    if (why || busy || !place || at === null) return;
    setBusy(true);
    setError(null);
    const meetup = meetupFromDraft(
      { venue: place, venueQuery: null, at, invitees: people, missing: [], ready: true },
      { id: newCardId(), organizer: me.id, newGroup: !inGroup, slots: times.length >= 2 ? times : undefined, durationMin, spots, plusOne, title },
    );
    if (!meetup) {
      setBusy(false);
      setError(t('meetupDraftSheet.pickPerson'));
      return;
    }
    try {
      const chatId = await inviteMeetup(meetup, groupTitle(meetup), inGroup ? groupOrigin!.id : null);
      // Нагадування організатору — той самий будильник, що й «Нагадати» на картці.
      if (remind && canRemind) remindBefore(meetup, chatId)?.catch(() => {});
      close();
    } catch (e) {
      setError(e instanceof Error ? e.message : t('meetupDraftSheet.notSent'));
    } finally {
      setBusy(false);
    }
  };

  const canPin = typeof (globalThis as { PhantomBridge?: { navigate?: unknown } }).PhantomBridge?.navigate === 'function';
  const pickOnMap = () => {
    parkMeetupDraft({ picked, at, query, intoHere, chatId: seed?.chatId ?? null });
    if (navigateTo('map', { pick: 'meetup' })) {
      close();
    } else {
      parkMeetupDraft(null);
      setError(t('meetupPin.noMap'));
    }
  };

  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));
  const pickVenue = (v: VenueSnap) => {
    setVenue(v);
    setStep('review');
  };
  const goStep = (s: DraftStep) => {
    setError(null);
    setStep(s);
  };
  const stepIndex = STEPS.indexOf(step);

  // Кола — з моїх розмов вдвох, лише ті, де є хтось із цього списку.
  const known = new Set(ranked.map((p) => p.id));
  const circles = CIRCLES.map((name) => ({
    name,
    ids: [
      ...new Set(
        useMessengerStore
          .getState()
          .chats.filter((c) => c.circle === name && c.type !== 'group' && c.peerNodeId && known.has(c.peerNodeId))
          .map((c) => c.peerNodeId!),
      ),
    ],
  })).filter((c) => c.ids.length > 0);
  const circleOf = new Map<string, string>();
  for (const c of circles) for (const id of c.ids) if (!circleOf.has(id)) circleOf.set(id, c.name);
  const peerHere = originChat?.type !== 'group' ? originChat?.peerNodeId ?? null : null;
  const q = whoQuery.trim().toLocaleLowerCase();
  const inCircle = circle ? new Set(circles.find((c) => c.name === circle)?.ids ?? []) : null;
  const shownPeople = ranked.filter((p) => (!inCircle || inCircle.has(p.id)) && (!q || p.name.toLocaleLowerCase().includes(q)));

  const outline = (on: boolean) =>
    `h-10 min-h-0 px-4 rounded-full text-footnote font-semibold shrink-0 inline-flex items-center gap-1.5 ${
      on ? 'bg-accent-soft text-ink' : 'border border-line-strong text-ink'
    }`;
  const label = (text: string, first = false) => <h3 className={`px-4 ${first ? '' : 'pt-4'} pb-1.5 text-caption text-ink-3`}>{text}</h3>;
  const STEP_WORD: Record<DraftStep, string> = {
    when: t('meetupDraftSheet.when'),
    who: t('meetX.who'),
    where: t('meetupDraftSheet.where'),
    review: t('meetX.review'),
  };

  const stepper = (
    <p className="px-4 pb-3 text-footnote text-ink-3" data-testid="draft-steps">
      <span aria-current="step">
        {t('when.step', { n: stepIndex + 1, total: STEPS.length })} · {STEP_WORD[step]}
      </span>
    </p>
  );

  const venueLine = (v: VenueSnap) => {
    const bits: string[] = [];
    if (fix && v.lat !== null && v.lon !== null) {
      const to = { lat: v.lat, lon: v.lon };
      bits.push(distanceLabel(metersBetween(fix, to)), t('meetupCard.walk', { n: walkMinutes(fix, to) }));
    } else if (v.address) bits.push(v.address);
    const r = ratingWord(v.rating);
    if (r) bits.push(r);
    return bits.join(' · ');
  };

  const noTimeYet = slots.length === 1 && slots[0] === null;
  const inFlow = whenOpen || (step === 'when' && noTimeYet && flow.win !== 'quick');
  const next = () => {
    if (stepWhy) return;
    goStep(STEPS[stepIndex + 1]);
  };

  const missingNote = missing.length > 0 && (
    <div className="px-4 pb-1" data-testid="draft-missing">
      {missing.map((m) => (
        <p key={m.name} className="text-footnote text-ink-2">
          {m.candidates.length > 1
            ? t('meetupDraftSheet.whichOne', { name: m.name, options: m.candidates.map((c) => c.name).join(t('meetupDraftSheet.or')) })
            : t('meetupDraftSheet.notFound', { name: m.name })}
        </p>
      ))}
    </div>
  );

  const slotValue = at !== null ? `${toSlot(new Date(at)).date}T${toSlot(new Date(at)).time}` : '';

  let body: React.ReactNode;
  if (step === 'when') {
    body = noTimeYet ? (
      <div data-testid="draft-when-quick">{flow.quick}</div>
    ) : (
      <>
        <div data-testid="draft-at" data-value={slotValue} className="px-2">
          {slots.map((x, i) => (
            <div key={i} className="flex items-center">
              <button
                type="button"
                data-testid={`draft-slot-${i}`}
                onClick={() => openWhen(i)}
                className="flex-1 min-w-0 min-h-14 px-2 rounded-control flex items-center gap-4 text-left active:bg-overlay"
              >
                <CalendarClock className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
                <span className={`flex-1 min-w-0 text-headline truncate ${x !== null ? 'text-ink' : 'text-ink-3'}`}>
                  {x !== null ? capital(whenWord(x, now)) : t('when.pickTime')}
                </span>
                <ChevronRight className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
              </button>
              {slots.length > 1 && (
                <button
                  type="button"
                  data-testid={`draft-slot-remove-${i}`}
                  aria-label={t('meetupPlan.removeTime')}
                  onClick={() => {
                    setSlots((s) => s.filter((_, k) => k !== i));
                    setActive(0);
                  }}
                  className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2"
                >
                  <X className="w-5 h-5" strokeWidth={1.75} />
                </button>
              )}
            </div>
          ))}
        </div>
        {slots.length < MAX_SLOTS && at !== null && (
          <button
            type="button"
            data-testid="draft-add-slot"
            onClick={() => {
              setSlots((s) => [...s, null]);
              openWhen(slots.length);
            }}
            className="w-full min-h-12 px-4 flex items-center gap-3 text-left text-callout font-semibold text-ink active:bg-overlay"
          >
            <Plus className="w-5 h-5 shrink-0" strokeWidth={1.75} aria-hidden />
            {t('meetupPlan.addTime')}
          </button>
        )}
        {slots.length > 1 && <p className="px-4 text-footnote text-ink-3">{t('meetupPlan.voteNote')}</p>}

        {label(t('meetupPlan.howLong'))}
        <div className="px-4 flex gap-2 flex-wrap" role="radiogroup" aria-label={t('meetupPlan.howLong')}>
          {[60, 120, 180, null].map((d) => (
            <button
              key={String(d)}
              type="button"
              role="radio"
              aria-checked={durationMin === d}
              data-testid={`draft-duration-${d ?? 'open'}`}
              onClick={() => setDurationMin(d)}
              className={outline(durationMin === d)}
            >
              {d === null ? t('meetupPlan.openEnd') : t('meetupPlan.hours', { n: d / 60 })}
            </button>
          ))}
        </div>
      </>
    );
  } else if (step === 'who') {
    body = (
      <div data-testid="draft-who">
        <div className="px-4">
          <label className="h-12 w-full rounded-full bg-ink/[.06] px-4 flex items-center gap-2">
            <Search className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
            <input
              value={whoQuery}
              onChange={(e) => setWhoQuery(e.target.value)}
              placeholder={t('meetX.whoSearch')}
              aria-label={t('meetX.whoSearch')}
              data-testid="draft-who-query"
              className="flex-1 min-w-0 h-full bg-transparent text-callout text-ink placeholder:text-ink-3 outline-none"
            />
          </label>
        </div>
        {circles.length > 0 && (
          <div className="px-4 pt-3 flex gap-2 overflow-x-auto no-scrollbar" role="radiogroup" aria-label={t('meetX.who')}>
            {[null, ...circles.map((c) => c.name)].map((name) => (
              <button
                key={name ?? 'all'}
                type="button"
                role="radio"
                aria-checked={circle === name}
                data-testid={`draft-circle-${name ?? 'all'}`}
                onClick={() => setCircle(name)}
                className={outline(circle === name)}
              >
                {name === null ? t('meetX.all') : t(CIRCLE_KEY[name as keyof typeof CIRCLE_KEY])}
              </button>
            ))}
          </div>
        )}
        {groupOrigin && (
          <div className="px-4 pt-3 flex gap-2" role="radiogroup" aria-label={t('meetupDraftSheet.whereToInvite')}>
            {[
              { here: false, label: t('meetupDraftSheet.newGroup') },
              { here: true, label: t('meetupDraftSheet.inGroup', { title: groupOrigin.title }) },
            ].map((o) => (
              <button
                key={String(o.here)}
                type="button"
                role="radio"
                aria-checked={intoHere === o.here}
                onClick={() => setIntoHere(o.here)}
                className={`${outline(intoHere === o.here)} truncate`}
              >
                {o.label}
              </button>
            ))}
          </div>
        )}
        {people.length > 0 && (
          <div className="px-4 pt-3 flex gap-3 overflow-x-auto no-scrollbar" data-testid="draft-who-summary">
            {people.map((p) => (
              <div key={p.id} className="w-14 shrink-0 flex flex-col items-center gap-1">
                <span className="relative">
                  <Avatar name={p.name} tone={p.id} className="w-12 h-12 text-callout" />
                  {picked.includes(p.id) && (
                    <button
                      type="button"
                      aria-label={t('meetX.removePerson', { name: p.name })}
                      onClick={() => toggle(p.id)}
                      className="absolute -top-1 -right-1 w-6 h-6 touch-floor-inline rounded-full bg-raised text-ink flex items-center justify-center"
                    >
                      <X className="w-3.5 h-3.5" strokeWidth={2} />
                    </button>
                  )}
                </span>
                <span className="w-full text-caption text-ink-2 truncate text-center">{p.name}</span>
              </div>
            ))}
          </div>
        )}
        <div className="pt-2">{missingNote}</div>
        {contactsRefused && <p className="px-4 text-footnote text-ink-3">{contactsRefused}</p>}
        {contacts !== null && contacts.length === 0 && !contactsRefused && (
          <p className="px-4 text-footnote text-ink-3">{t('meetupDraftSheet.noPeople')}</p>
        )}
        {contacts !== null && contacts.length > 0 && shownPeople.length === 0 && (
          <p className="px-4 pt-2 text-footnote text-ink-3" data-testid="draft-who-none">
            {t('meetX.noMatch')}
          </p>
        )}
        <div className="pt-1">
          {shownPeople.map((p) => {
            const on = picked.includes(p.id);
            const c = circleOf.get(p.id);
            const sub = [c ? t(CIRCLE_KEY[c as keyof typeof CIRCLE_KEY]) : null, p.id === peerHere ? t('meetX.inThisChat') : null, p.near ? NEAR_WORD[p.near] : null]
              .filter(Boolean)
              .join(' · ');
            return (
              <button
                key={p.id}
                type="button"
                role="checkbox"
                aria-checked={on}
                aria-label={p.name}
                data-testid={`draft-person-${p.id}`}
                onClick={() => toggle(p.id)}
                className="w-full min-h-14 px-4 py-1.5 flex items-center gap-3 text-left active:bg-overlay"
              >
                <Avatar name={p.name} tone={p.id} className="w-10 h-10 text-callout" />
                <span className="flex-1 min-w-0">
                  <span className="block text-callout text-ink truncate">{p.name}</span>
                  {sub && <span className="block text-footnote text-ink-3 truncate">{sub}</span>}
                </span>
                <CheckBox on={on} />
              </button>
            );
          })}
        </div>
      </div>
    );
  } else if (step === 'where') {
    body = (
          <div data-testid="draft-venue-pick">
            <div className="px-4">
              <label className="h-12 w-full rounded-control bg-ink/[.06] px-3 flex items-center gap-2">
                <Search className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={t('meetupPlan.search')}
                  data-testid="draft-venue-query"
                  className="flex-1 min-w-0 h-full bg-transparent text-callout text-ink placeholder:text-ink-3 outline-none"
                />
              </label>
            </div>
            {canPin && (
              <button
                type="button"
                data-testid="draft-pick-map"
                onClick={pickOnMap}
                className="w-full min-h-12 px-4 flex items-center gap-3 text-left active:bg-overlay"
              >
                <MapPin className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
                <span className="flex-1 min-w-0 text-callout text-ink">{t('meetupPin.pick')}</span>
              </button>
            )}
            {pastVenues.length > 0 && (
              <div className="px-4 pt-2 pb-1 flex gap-2" role="tablist">
                {(['near', 'past'] as const).map((k) => (
                  <button
                    key={k}
                    type="button"
                    role="tab"
                    aria-selected={list === k}
                    data-testid={`draft-list-${k}`}
                    onClick={() => setList(k)}
                    className={outline(list === k)}
                  >
                    {k === 'near' ? t('meetupPlan.nearby') : originChat?.type === 'group' ? t('meetupPlan.groupPlaces') : t('meetupPlan.pastPlaces')}
                  </button>
                ))}
              </div>
            )}
            {typed && shownVenues.length === 0 && !canPin ? (
              <p className="px-4 pt-2 text-footnote text-ink-3" data-testid="draft-typed-note">
                {t('phonefix.meetup.typedNote')}
              </p>
            ) : venuesRefused && list === 'near' ? (
              <p className="px-4 pt-2 text-footnote text-ink-3" data-testid="draft-venues-refused">
                {venuesRefused}
              </p>
            ) : (
              venues !== null && shownVenues.length === 0 && (
                <p className="px-4 pt-2 text-footnote text-ink-3">{t('meetupDraftSheet.none')}</p>
              )
            )}
            {shownVenues.slice(0, 12).map((v) => (
              <div key={v.id} className={`flex items-center ${venue?.id === v.id ? 'bg-accent-soft' : ''}`}>
                <button
                  type="button"
                  data-testid={`draft-venue-${v.id}`}
                  aria-pressed={venue?.id === v.id}
                  onClick={() => pickVenue(v)}
                  className="flex-1 min-w-0 min-h-14 pl-4 flex items-center gap-3 text-left active:bg-overlay"
                >
                  <MapPin className="w-5 h-5 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
                  <span className="flex-1 min-w-0">
                    <span className="block text-callout text-ink truncate">{v.name}</span>
                    <span className="block text-footnote text-ink-3 truncate">{venueLine(v)}</span>
                  </span>
                  {venue?.id === v.id && <Check className="w-5 h-5 shrink-0 text-ink" strokeWidth={2} aria-hidden />}
                </button>
                <button
                  type="button"
                  aria-label={t('meetupDraftSheet.venuePage', { name: v.name })}
                  onClick={() => setPeek(v)}
                  className="w-11 h-11 min-h-0 min-w-0 mr-2 flex items-center justify-center text-ink-3 rounded-full active:bg-overlay"
                >
                  <Info className="w-5 h-5" strokeWidth={1.75} />
                </button>
              </div>
            ))}
          </div>
    );
  } else {
    const placeSub = !place ? undefined : isPinVenue(place) ? t('meetupPin.onMap') : typed ? t('phonefix.meetup.typedNote') : ratingWord(place.rating) || place.address || undefined;
    body = (
      <div data-testid="draft-review">
        <div className="px-4">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value.slice(0, MAX_TITLE))}
            placeholder={t('msgTypes.eventTitle')}
            aria-label={t('msgTypes.eventTitle')}
            data-testid="draft-title"
            className="h-14 w-full rounded-control border border-line-strong bg-transparent px-4 text-headline text-ink placeholder:text-ink-3 outline-none"
          />
        </div>
        <div className="mx-4 mt-3 rounded-card border border-line-strong divide-y divide-line overflow-hidden">
          <SumRow
            testId="draft-at"
            value={slotValue}
            onClick={() => goStep('when')}
            icon={<Lead><CalendarClock className="w-5 h-5" strokeWidth={1.75} /></Lead>}
            main={at !== null ? capital(whenWord(at, now)) : t('when.pickTime')}
            empty={at === null}
            sub={voting ? t('meetX.slotsN', { n: times.length }) : durationMin !== null ? t('meetupPlan.hours', { n: durationMin / 60 }) : t('meetupPlan.openEnd')}
          />
          <SumRow
            testId="draft-venue"
            onClick={() => goStep('where')}
            icon={<Lead><MapPin className="w-5 h-5" strokeWidth={1.75} /></Lead>}
            main={place ? place.name : t('meetX.pickPlace')}
            empty={!place}
            sub={placeSub}
            subTestId={typed ? 'draft-typed-note' : undefined}
          />
          <SumRow
            testId="draft-row-who"
            onClick={() => goStep('who')}
            icon={
              people.length > 0 ? (
                <span className="w-10 shrink-0 flex">
                  {people.slice(0, 2).map((p, i) => (
                    <Avatar key={p.id} name={p.name} tone={p.id} className={`w-7 h-7 text-caption ring-2 ring-surface ${i ? '-ml-3 mt-3' : ''}`} />
                  ))}
                </span>
              ) : (
                <Lead><Users className="w-5 h-5" strokeWidth={1.75} /></Lead>
              )
            }
            main={people.length > 0 ? people.map((p) => p.name).join(', ') : t('meetX.pickPeople')}
            empty={people.length === 0}
            sub={people.length > 0 ? (inGroup ? t('meetupDraftSheet.inGroup', { title: groupOrigin!.title }) : t('meetX.getInvite')) : undefined}
          />
        </div>
        <div className="pt-2">{missingNote}</div>
        {canRemind && (
          <button
            type="button"
            role="switch"
            aria-checked={remind}
            data-testid="draft-remind"
            onClick={() => setRemind((x) => !x)}
            className="w-full min-h-16 px-4 flex items-center gap-3 text-left"
          >
            <Lead><Bell className="w-5 h-5" strokeWidth={1.75} /></Lead>
            <span className="flex-1 min-w-0">
              <span className="block text-callout text-ink">{t('meetX.remind')}</span>
              <span className="block text-footnote text-ink-3">{t('meetX.remindHint')}</span>
            </span>
            <span aria-hidden className={`w-[52px] h-8 rounded-full p-1 flex ${remind ? 'bg-ink justify-end' : 'bg-ink/[.12] justify-start'}`}>
              <span className="w-6 h-6 rounded-full bg-surface" />
            </span>
          </button>
        )}
        <div className="px-4 min-h-14 flex items-center gap-3">
          <Users className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
          <span className="flex-1 min-w-0 text-callout text-ink">{t('meetupPlan.spots')}</span>
          <div className="h-11 rounded-full border border-line-strong flex items-center" data-testid="draft-spots">
            <button
              type="button"
              aria-label={t('meetupPlan.fewer')}
              data-testid="draft-spots-minus"
              disabled={spots === null}
              onClick={() => setSpots((n) => (n === null || n <= 2 ? null : n - 1))}
              className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink disabled:opacity-40"
            >
              <Minus className="w-4 h-4" strokeWidth={2} />
            </button>
            <span className="min-w-[72px] text-center text-callout font-semibold text-ink" data-testid="draft-spots-value">
              {spots === null ? t('meetupPlan.noLimit') : t('meetupPlan.upTo', { n: spots })}
            </span>
            <button
              type="button"
              aria-label={t('meetupPlan.more')}
              data-testid="draft-spots-plus"
              disabled={spots !== null && spots >= 99}
              onClick={() => setSpots((n) => (n === null ? Math.max(2, people.length + 1) : n + 1))}
              className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink disabled:opacity-40"
            >
              <Plus className="w-4 h-4" strokeWidth={2} />
            </button>
          </div>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={plusOne}
          data-testid="draft-plus-one"
          onClick={() => setPlusOne((x) => !x)}
          className="w-full min-h-14 px-4 flex items-center gap-3 text-left"
        >
          <UserPlus className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
          <span className="flex-1 min-w-0 text-callout text-ink">{t('meetupPlan.plusOne')}</span>
          <span aria-hidden className={`w-[52px] h-8 rounded-full p-1 flex ${plusOne ? 'bg-ink justify-end' : 'bg-ink/[.12] justify-start'}`}>
            <span className="w-6 h-6 rounded-full bg-surface" />
          </span>
        </button>
        {!intoHere && <p className="px-4 pt-1 text-footnote text-ink-3">{t('meetupDraftSheet.autoHide')}</p>}
      </div>
    );
  }

  return (
    <>
      <FormSheet
        open
        onClose={close}
        onBack={inFlow ? flow.back ?? (() => setWhenOpen(false)) : stepIndex > 0 ? () => goStep(STEPS[stepIndex - 1]) : undefined}
        title={inFlow ? flow.title : t('meetupPlan.title')}
        tall
        testId="meetup-draft"
        footer={
          inFlow ? (
            flow.footer
          ) : (
            <div>
              {step === 'review' ? (
                <PrimaryButton onClick={() => void invite()} disabled={!!why || busy}>
                  {t('meetupDraftSheet.invite')}
                </PrimaryButton>
              ) : (
                <PrimaryButton onClick={next} disabled={!!stepWhy}>
                  {step === 'who' && people.length > 0 ? t('meetX.nextN', { n: people.length }) : t('meetupPlan.next')}
                </PrimaryButton>
              )}
              {(stepWhy || error) && !(step === 'when' && noTimeYet && !error) && (
                <p className={`pt-2 text-footnote ${error ? 'text-danger' : 'text-ink-3'}`} data-testid="draft-why">
                  {error ?? stepWhy}
                </p>
              )}
            </div>
          )
        }
      >
        {inFlow ? (
          <div data-testid="draft-when-flow">{flow.win === 'quick' ? flow.quick : flow.body}</div>
        ) : (
          <>
            {stepper}
            {step === 'when' && place && (
              // Відкрито зі сторінки місця чи з мапи — видно, для якого місця план.
              <div className="px-4 pb-2 flex items-center gap-2 text-footnote text-ink-2" data-testid="draft-venue">
                <MapPin className="w-4 h-4 shrink-0" strokeWidth={1.75} aria-hidden />
                <span className="truncate">{place.name}</span>
              </div>
            )}
            {body}
          </>
        )}
        <div className="h-4" />
      </FormSheet>
      {peek && (
        <VenueSheet
          open
          onClose={() => setPeek(null)}
          venueId={peek.id}
          snap={peek}
          onMeetHere={(v) => {
            pickVenue(v);
            setPeek(null);
          }}
        />
      )}
    </>
  );
};

/**
 * Господар аркушів сцени в корені месенджера: чернетка від помічника чи зі
 * сторінки закладу, «розділи рахунок». Одна дорога для нативу й для карток.
 */
export const MeetupDraftHost: React.FC = () => {
  const { ask, n: askN } = useMeetupAsk();
  const meId = useMessengerStore((s) => s.currentUser.id);
  const msg = useMessengerStore((s) =>
    ask && ask.kind !== 'meetup' ? s.chats.flatMap((c) => c.messages).find((m) => m.id === ask.messageId) : undefined,
  );
  const close = () => openMeetupAsk(null);
  if (!ask) return null;
  if (ask.kind === 'meetup') {
    // Кожне прохання — нова чернетка: без `key` друге «збери Петра» успадкувало б заклад і час першого.
    return <MeetupDraftSheet key={askN} open onClose={close} seed={{ args: ask.args, venue: ask.venue, venueId: ask.venueId, chatId: ask.chatId, parked: ask.parked }} />;
  }
  if (!msg) return null;
  return ask.kind === 'bill' ? (
    <BillSheet open onClose={close} msg={msg} meId={meId} />
  ) : (
    <BillDraftSheet open onClose={close} meetupMsg={msg} />
  );
};
