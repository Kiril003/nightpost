/**
 * Пошук по всіх розмовах одразу — видача в шапці списку телефона.
 *
 * «Люди» (назви розмов і гілок, учасники гілок) і «Листи» (текст, підписи
 * вкладень, назви файлів у всіх розмовах, разом зі «Збереженим»). Дотик по
 * листу відкриває розмову й прокручує до нього з підсвіткою.
 *
 * Звідки листи — див. `services/globalSearch.ts`: на телефоні шукає вузол по
 * своєму журналу (листи не копіюються у веб), а індекс вебу доповнює тим, що
 * вже лежить у сторі, і будується в простої, коли пошук відкрили.
 *
 * Запит, схожий на @нік, ще й питає довідник (той самий маршрут мосту, що
 * `WebMessengerDirectory.users`): точний збіг — одна людина в «Людях».
 * Переліку схожих довідник не віддає за побудовою.
 */
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Bookmark, MapPin, Search } from 'lucide-react';
import { normalise } from '../../services/peerDirectory';
import { useMessengerStore } from '../../stores/messengerStore';
import type { Chat } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { messengerApi, phoneBridge } from '../../services/messengerApi';
import { foldQuery, MessageIndex, type Fragment } from '../../services/messageSearch';
import {
  filterLetters,
  filterPeople,
  isChatResult,
  lettersFromIndex,
  lettersFromNode,
  mergeLetters,
  NO_FILTERS,
  onlyExactTag,
  placesFromWire,
  ringsIn,
  searchPeople,
  type LetterResult,
  type NodeSearchReport,
  type PersonResult,
  type PlaceResult,
  type SearchFilters,
  type SearchRing,
} from '../../services/globalSearch';
import { CIRCLE_KEY } from './todayFormat';
import { circleLabel } from './MomentViewer';
import { handleTag, personKey, ringOfPeer, sameNameSuffixes } from '../../services/sameName';

export { personKey } from '../../services/sameName';
import { Avatar, AVATAR_SIZE } from './Avatar';
import { PhantomMark } from './PhantomMark';
import { selfAdded } from '../../services/selfAdded';
import { chatKind, chatTimeLabel, chatToneKey, hasLetter, isNodeChat, isSavedNotes, selfWord } from './chatListFormat';
import { sortChatsByRecent } from '../../services/chatOrder';

/** Скільки чекати після останньої літери, перш ніж питати вузол. */
const NODE_DEBOUNCE_MS = 150;

/** Місця шукаються з другої літери: одна дає півміста. */
const PLACES_DEBOUNCE_MS = 300;
const PLACES_MIN = 2;

/** Скільки чекати, перш ніж питати довідник: сервер бачить час звернень. */
const DIRECTORY_DEBOUNCE_MS = 450;

export interface NetworkPerson {
  handle: string;
  name: string;
}

export type NetworkLookup =
  | { kind: 'off' }
  | { kind: 'searching'; handle: string }
  | { kind: 'found'; handle: string; person: NetworkPerson }
  | { kind: 'none'; handle: string }
  | { kind: 'down'; handle: string };

/** off — не телефон, фільтр або коротко; refused — телефон не знає, де ви. */
export type PlacesState = 'off' | 'searching' | 'found' | 'refused';

export interface GlobalFindings {
  needle: string;
  people: PersonResult[];
  letters: LetterResult[];
  places?: PlaceResult[];
  placesState?: PlacesState;
  /** Чи вузол уже відповів на цей запит (або його немає). */
  settled: boolean;
  network: NetworkLookup;
  filters: SearchFilters;
  setFilters: (f: SearchFilters) => void;
  rings: ReturnType<typeof ringsIn>;
}

/** Людина за @ніком у мережі. Знайомого не питаємо — він уже серед «Людей». */
export const useNetworkPerson = (chats: Chat[], query: string, active: boolean): NetworkLookup => {
  const handle = active ? normalise(query) : null;
  const known = handle !== null && chats.some((c) => (c.handle || '').replace(/^@/, '').toLowerCase() === handle);
  const asked = handle !== null && !known ? handle : null;
  const [answer, setAnswer] = useState<NetworkLookup>({ kind: 'off' });
  useEffect(() => {
    if (!asked) return undefined;
    let alive = true;
    const t = setTimeout(() => {
      messengerApi
        .listDirectoryUsers(asked)
        .then((rows) => {
          if (!alive) return;
          const hit = (rows || []).find((u) => u.username.toLowerCase() === asked);
          setAnswer(
            hit
              ? { kind: 'found', handle: asked, person: { handle: asked, name: hit.display_name?.trim() || '' } }
              : { kind: 'none', handle: asked },
          );
        })
        .catch(() => alive && setAnswer({ kind: 'down', handle: asked }));
    }, DIRECTORY_DEBOUNCE_MS);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [asked]);
  if (!asked) return { kind: 'off' };
  return answer.kind !== 'off' && answer.handle === asked ? answer : { kind: 'searching', handle: asked };
};

export const useGlobalSearch = (chats: Chat[], query: string, active: boolean): GlobalFindings => {
  const meId = useMessengerStore((s) => s.currentUser.id);
  const indexRef = useRef<MessageIndex | null>(null);
  if (!indexRef.current || indexRef.current.meId !== meId) indexRef.current = new MessageIndex(meId);
  const index = indexRef.current;
  const [built, setBuilt] = useState(0);
  const [filters, setFilters] = useState<SearchFilters>(NO_FILTERS);
  const rings = useMemo(() => ringsIn(chats), [chats]);
  const onNode = phoneBridge() !== null;

  // Індекс — у простої і лише коли пошук відкрили: перший кадр списку не чекає.
  useEffect(() => {
    if (!active) return undefined;
    index.enqueue(chats);
    index.schedule(() => setBuilt((n) => n + 1));
    return () => index.cancel();
  }, [chats, active, index]);

  const needle = foldQuery(query);

  const [node, setNode] = useState<{ needle: string; report: NodeSearchReport | null } | null>(null);
  useEffect(() => {
    if (!onNode || !needle) return undefined;
    const raw = query.trim();
    const t = setTimeout(() => {
      messengerApi
        .searchMessages(raw)
        .then((report) => setNode({ needle, report }))
        // Відмову мосту не ховаємо за «нічого»: видача лишається з індексу вебу.
        .catch(() => setNode({ needle, report: null }));
    }, NODE_DEBOUNCE_MS);
    return () => clearTimeout(t);
    // query входить через needle; сам рядок не перезапускає пошуку на пробілі.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needle, onNode]);

  const people = useMemo(() => filterPeople(searchPeople(chats, needle), chats, filters), [chats, needle, filters]);
  const letters = useMemo(() => {
    if (!needle) return [];
    const fromIndex = lettersFromIndex(index.search(needle, 200), chats, needle, meId);
    const report = node?.needle === needle ? node.report : null;
    const fromNode = report?.hits ? lettersFromNode(report.hits, chats, needle) : [];
    // Тег і фільтри застосовуємо ДО злиття, щоб межа видачі в mergeLetters не забивалась зайвим.
    return mergeLetters(
      filterLetters(onlyExactTag(fromNode, needle), chats, filters),
      filterLetters(onlyExactTag(fromIndex, needle), chats, filters),
    );
    // `built` — сигнал, що індекс доріс.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needle, chats, node, built, index, filters]);

  const rawNetwork = useNetworkPerson(chats, query, active);
  const filtered = filters.ring !== null || filters.hasPayment;
  const network: NetworkLookup = filtered ? { kind: 'off' } : rawNetwork;

  const placesAsked = onNode && active && !filtered && needle.length >= PLACES_MIN;
  const [placesAnswer, setPlacesAnswer] = useState<{ needle: string; rows: PlaceResult[] | null } | null>(null);
  useEffect(() => {
    if (!placesAsked) return undefined;
    let alive = true;
    const raw = query.trim();
    const t = setTimeout(() => {
      messengerApi
        .searchPlaces(raw)
        .then((res) => alive && setPlacesAnswer({ needle, rows: placesFromWire(res, needle) }))
        .catch(() => alive && setPlacesAnswer({ needle, rows: null }));
    }, PLACES_DEBOUNCE_MS);
    return () => {
      alive = false;
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needle, placesAsked]);
  const placesNow = placesAsked && placesAnswer?.needle === needle ? placesAnswer : null;
  const placesState: PlacesState = !placesAsked ? 'off' : !placesNow ? 'searching' : placesNow.rows ? 'found' : 'refused';

  return {
    needle,
    people,
    letters,
    places: placesNow?.rows ?? [],
    placesState,
    settled: !onNode || node?.needle === needle,
    network,
    filters,
    setFilters,
    rings,
  };
};

/** Збіг — тоном «свого» листа: він тепліший за полотно в усіх чотирьох фазах. */
export const Lit: React.FC<{ text: string; at: number; len: number }> = ({ text, at, len }) =>
  at < 0 || len <= 0 ? (
    <>{text}</>
  ) : (
    <>
      {text.slice(0, at)}
      <mark data-search-hit className="bg-accent-soft text-ink rounded-sm">
        {text.slice(at, at + len)}
      </mark>
      {text.slice(at + len)}
    </>
  );

const FIELD_WORD: Record<Fragment['field'], MessageKey | null> = {
  text: null,
  caption: 'globalSearch.fieldCaption',
  file: 'globalSearch.fieldFile',
};

const PersonRow: React.FC<{ p: PersonResult; len: number; suffix?: string; onOpen: () => void }> = ({
  p,
  len,
  suffix,
  onOpen,
}) => {
  const { t } = useTranslation();
  const chat = p.chat;
  const tag = handleTag(p.handle);
  return (
    <li>
      <button
        type="button"
        data-search-person={p.key}
        onClick={onOpen}
        className="w-full min-h-[60px] px-4 py-2 flex items-center gap-3 text-left bg-canvas active:bg-overlay transition-colors duration-quick"
      >
        {chat && isSavedNotes(chat) ? (
          <span className="w-10 h-10 shrink-0 rounded-control bg-raised text-ink-2 flex items-center justify-center">
            <Bookmark className="w-5 h-5" strokeWidth={1.75} />
          </span>
        ) : chat && isNodeChat(chat) ? (
          <PhantomMark size="w-10 h-10" />
        ) : (
          <Avatar
            src={chat?.avatar ?? p.member?.avatar}
            name={p.name}
            tone={chat ? chatToneKey(chat) : p.member?.id}
            className={AVATAR_SIZE.rowDesk}
          />
        )}
        <span className="min-w-0 flex-1">
          <span className="block truncate text-headline text-ink">
            <Lit text={p.name} at={p.at} len={len} />
            {suffix && <span className="text-ink-3">{t('sameName.suffix', { suffix })}</span>}
          </span>
          {p.where ? (
            <span className="block truncate text-callout text-ink-3">{t('globalSearch.inChat', { where: p.where })}</span>
          ) : (
            tag &&
            suffix !== tag && (
              <span className="block truncate text-callout text-ink-3">{tag}</span>
            )
          )}
        </span>
      </button>
    </li>
  );
};

const LetterRow: React.FC<{ l: LetterResult; onOpen: () => void }> = ({ l, onOpen }) => {
  const { t } = useTranslation();
  const fieldKey = FIELD_WORD[l.fragment.field];
  const author = selfWord(l.author);
  const who = author === l.where ? author : `${author} · ${l.where}`;
  const time = chatTimeLabel(l.sentAt);
  return (
    <li>
      <button
        type="button"
        data-search-letter={l.messageId}
        onClick={onOpen}
        className="w-full min-h-[64px] px-4 py-2.5 flex flex-col gap-0.5 text-left bg-canvas active:bg-overlay transition-colors duration-quick"
      >
        <span className="w-full flex items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate text-footnote font-semibold text-ink-2">{who}</span>
          {time && <span className="shrink-0 text-caption tnum text-ink-3">{time}</span>}
        </span>
        <span className="w-full line-clamp-2 break-words text-callout text-ink">
          {fieldKey && <span className="text-ink-3">{t(fieldKey)}</span>}
          <Lit text={l.fragment.text} at={l.fragment.at} len={l.fragment.len} />
        </span>
      </button>
    </li>
  );
};

type MapBridge = { openMap?: (lat: number, lon: number, label?: string) => boolean };

/** Місце — міткою на мапі телефона (той самий вхід, що в картці зустрічі). */
const openPlaceOnMap = (pl: PlaceResult): void => {
  const b = (globalThis as { PhantomBridge?: MapBridge }).PhantomBridge;
  if (b && typeof b.openMap === 'function') b.openMap(pl.lat, pl.lon, pl.name);
};

export const distanceWord = (m: number, t: Translation['t'], locale: string): string =>
  m < 1000
    ? t('globalSearch.metres', { n: String(Math.max(10, Math.round(m / 10) * 10)) })
    : t('globalSearch.km', {
        n: new Intl.NumberFormat(locale, { maximumFractionDigits: m < 10_000 ? 1 : 0 }).format(m / 1000),
      });

const PlaceRow: React.FC<{ pl: PlaceResult; len: number; onOpen: () => void }> = ({ pl, len, onOpen }) => {
  const { t, locale } = useTranslation();
  const line = [pl.distanceM !== null ? distanceWord(pl.distanceM, t, locale) : '', pl.address].filter(Boolean).join(' · ');
  return (
    <li>
      <button
        type="button"
        data-search-place={pl.key}
        onClick={onOpen}
        className="w-full min-h-[60px] px-4 py-2 flex items-center gap-3 text-left bg-canvas active:bg-overlay transition-colors duration-quick"
      >
        <span aria-hidden className={`${AVATAR_SIZE.rowDesk} shrink-0 rounded-full bg-raised flex items-center justify-center text-ink-2`}>
          <MapPin className="w-5 h-5" strokeWidth={1.75} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-headline text-ink">
            <Lit text={pl.name} at={pl.at} len={len} />
          </span>
          {line && <span className="block truncate text-callout text-ink-3">{line}</span>}
        </span>
      </button>
    </li>
  );
};

const codeOf = (err: unknown): string => {
  const c = (err as { code?: unknown } | null)?.code;
  return typeof c === 'string' ? c : '';
};

const refusal = (err: unknown, t: Translation['t']): string => {
  const code = codeOf(err);
  if (code === 'no_user') return t('globalSearch.noUser');
  if (code === 'unreachable' || code === 'no_directory' || code === 'directory_closed') {
    return t('globalSearch.offline');
  }
  if (code === 'not_proven') return t('globalSearch.notProven');
  return t('globalSearch.openFailed');
};

/** Людина з мережі: дотик — одразу розмова, без кодів і запрошень. */
export const NetworkPersonRow: React.FC<{ person: NetworkPerson; onOpenChat: (chatId: string) => void }> = ({
  person,
  onOpenChat,
}) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  // Після оновлення стору людина вже «своя» і цей рядок зникає з видачі,
  // тож розмову відкриваємо тут, а коло обирають потім у профілі.
  const write = async () => {
    if (busy) return;
    soundFx.playTap();
    setBusy(true);
    setNote(null);
    try {
      const conv = await messengerApi.startChatByUsername(person.handle);
      selfAdded.mark(conv.id);
      await useMessengerStore.getState().refreshConversations();
      onOpenChat(conv.id);
    } catch (err) {
      setNote(refusal(err, t));
    } finally {
      setBusy(false);
    }
  };
  const name = person.name || `@${person.handle}`;
  return (
    <li>
      <button
        type="button"
        data-search-network={person.handle}
        onClick={() => void write()}
        disabled={busy}
        className="w-full min-h-[60px] px-4 py-2 flex items-center gap-3 text-left active:bg-overlay transition-colors duration-quick"
      >
        <Avatar name={person.name || person.handle} tone={person.handle} className={AVATAR_SIZE.rowDesk} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-headline text-ink">{name}</span>
          <span className="block truncate text-callout text-ink-3" role={note ? 'status' : undefined}>
            {note ?? (busy ? t('globalSearch.opening') : person.name ? `@${person.handle}` : t('globalSearch.write'))}
          </span>
        </span>
      </button>
    </li>
  );
};

const ringLabel = (ring: SearchRing, t: Translation['t']): string => t(CIRCLE_KEY[ring]);

const chipClass = (active: boolean): string =>
  `min-h-[32px] px-3 py-1 rounded-full text-footnote font-medium transition-colors duration-quick ${
    active ? 'bg-accent-soft text-accent-text' : 'bg-raised text-ink-2 active:bg-overlay'
  }`;

const RECENT_MAX = 8;

/** Порожній запит: ті, з ким говорили останніми, — один дотик, без прози. */
const SearchRecent: React.FC<{ chats: Chat[]; onOpenChat: (chatId: string) => void }> = ({ chats, onOpenChat }) => {
  const { t } = useTranslation();
  const recent = useMemo(
    () => sortChatsByRecent(chats.filter((c) => !c.archived && chatKind(c) !== 'other' && hasLetter(c))).slice(0, RECENT_MAX),
    [chats],
  );
  if (recent.length === 0) return <div data-search-empty className="h-2" />;
  return (
    <section data-search-empty data-testid="search-recent" aria-label={t('globalSearch.recent')}>
      <h2 className="px-4 pt-4 pb-2 text-caption text-ink-3">{t('globalSearch.recent')}</h2>
      <ul className="px-2 flex gap-1 overflow-x-auto no-scrollbar">
        {recent.map((c) => (
          <li key={c.id} className="shrink-0">
            <button
              type="button"
              data-search-recent={c.id}
              onClick={() => {
                soundFx.playTap();
                onOpenChat(c.id);
              }}
              className="w-[72px] min-h-0 py-1 flex flex-col items-center gap-1.5 rounded-control active:bg-overlay"
            >
              <Avatar src={c.avatar} name={c.title} tone={chatToneKey(c)} className={AVATAR_SIZE.row} />
              <span className="w-full px-1 truncate text-caption text-ink-2 text-center">{c.title}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
};

export const GlobalSearchResults: React.FC<{
  query: string;
  found: GlobalFindings;
  onOpenChat: (chatId: string) => void;
  onOpenMessage: (chatId: string, messageId: string) => void;
}> = ({ query, found, onOpenChat, onOpenMessage }) => {
  const { t } = useTranslation();
  const storeChats = useMessengerStore((st) => st.chats);
  const { people: found0, letters, needle, settled, network } = found;
  const people = useMemo(() => found0.filter((p) => !isChatResult(p)), [found0]);
  const groups = useMemo(() => found0.filter(isChatResult), [found0]);
  const places = found.places ?? [];
  const placesState = found.placesState ?? 'off';
  const filters = found.filters ?? NO_FILTERS;
  const rings = found.rings ?? [];
  const filterActive = filters.ring !== null || filters.hasPayment;
  const chats = useMemo(() => {
    const seen = new Set(storeChats.map((c) => c.id));
    const extra = found0
      .map((p) => p.chat)
      .filter((c): c is Chat => c !== undefined && !seen.has(c.id));
    return extra.length > 0 ? [...storeChats, ...extra] : storeChats;
  }, [storeChats, found0]);
  const shownQuery = query.trim();
  if (!needle) {
    return <SearchRecent chats={storeChats} onOpenChat={onOpenChat} />;
  }
  const filterBar = (
    <div
      data-search-filters
      role="group"
      aria-label={t('chats46.filters')}
      className="px-4 pt-3 pb-1 flex flex-nowrap items-center gap-2 overflow-x-auto no-scrollbar [&>button]:shrink-0 [&>button]:whitespace-nowrap"
    >
      {rings.map((ring) => {
        const activeRing = filters.ring === ring;
        return (
          <button
            key={ring}
            type="button"
            data-filter-ring={ring}
            aria-pressed={activeRing}
            onClick={() => {
              soundFx.playTap();
              found.setFilters({ ...filters, ring: activeRing ? null : ring });
            }}
            className={chipClass(activeRing)}
          >
            {ringLabel(ring, t)}
          </button>
        );
      })}
      <button
        type="button"
        data-filter="payment"
        aria-pressed={filters.hasPayment}
        onClick={() => {
          soundFx.playTap();
          found.setFilters({ ...filters, hasPayment: !filters.hasPayment });
        }}
        className={chipClass(filters.hasPayment)}
      >
        {t('chats46.hasPayment')}
      </button>
    </div>
  );
  const netPerson = network.kind === 'found' ? network.person : null;
  // Чесне «нікого» — лише коли довідник відповів і серед своїх теж порожньо.
  const noOne = network.kind === 'none' && people.length === 0;
  // Без мережі шукаємо лише серед своїх; про це кажемо, лише коли питали саме нік.
  const offline = network.kind === 'down' && people.length === 0 && shownQuery.startsWith('@');
  const hasPeople = people.length > 0 || netPerson !== null || noOne || offline;
  if (!hasPeople && groups.length === 0 && places.length === 0 && letters.length === 0) {
    // Поки вузол, довідник чи місця не відповіли, «нічого» було б неправдою.
    if (!settled || network.kind === 'searching' || placesState === 'searching') return null;
    return (
      <div>
        {filterBar}
        {filterActive ? (
          <p data-search-none-filtered className="px-4 pt-6 text-body text-ink break-words">
            {t('chats46.nothingFiltered')}
          </p>
        ) : (
          <div data-search-none className="px-6 pt-16 flex flex-col items-center gap-2 text-center">
            <span aria-hidden className="w-14 h-14 mb-2 rounded-full bg-raised flex items-center justify-center text-ink-2">
              <Search className="w-6 h-6" strokeWidth={1.75} />
            </span>
            <p className="text-headline text-ink break-words">{t('globalSearch.nothing', { query: shownQuery })}</p>
            <p className="text-callout text-ink-2">
              {t(placesState === 'found' ? 'globalSearch.nothingAll' : 'globalSearch.nothingOwn')}
            </p>
          </div>
        )}
      </div>
    );
  }
  const suffixes = sameNameSuffixes(
    people.flatMap((p) => {
      const id = personKey(p);
      if (!id) return [];
      return [
        {
          id,
          name: p.name,
          handle: p.chat?.handle ?? p.handle,
          ring: ((r) => r && circleLabel(t, r))(ringOfPeer(chats, id)),
        },
      ];
    }),
  );
  const tap = (fn: () => void) => () => {
    soundFx.playTap();
    fn();
  };
  return (
    <div data-search-results>
      {filterBar}
      {hasPeople && (
        <section aria-label={t('globalSearch.people')}>
          <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{t('globalSearch.people')}</h2>
          <ul>
            {people.map((p) => {
              const id = personKey(p);
              return (
                <PersonRow
                  key={p.key}
                  p={p}
                  len={needle.length}
                  suffix={id ? suffixes.get(id) : undefined}
                  onOpen={tap(() => onOpenChat(p.chatId))}
                />
              );
            })}
            {netPerson && <NetworkPersonRow person={netPerson} onOpenChat={onOpenChat} />}
          </ul>
          {noOne && (
            <p data-search-no-handle className="px-4 pt-1 pb-3 text-callout text-ink-2">
              {t('globalSearch.noUserFull', { handle: network.kind === 'none' ? network.handle : '' })}
            </p>
          )}
          {offline && (
            <p data-search-offline className="px-4 pt-1 pb-3 text-callout text-ink-2">
              {t('globalSearch.offlineScope')}
            </p>
          )}
        </section>
      )}
      {groups.length > 0 && (
        <section aria-label={t('globalSearch.chats')}>
          <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{t('globalSearch.chats')}</h2>
          <ul>
            {groups.map((p) => (
              <PersonRow key={p.key} p={p} len={needle.length} onOpen={tap(() => onOpenChat(p.chatId))} />
            ))}
          </ul>
        </section>
      )}
      {places.length > 0 && (
        <section aria-label={t('globalSearch.places')}>
          <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{t('globalSearch.places')}</h2>
          <ul>
            {places.map((pl) => (
              <PlaceRow key={pl.key} pl={pl} len={needle.length} onOpen={tap(() => openPlaceOnMap(pl))} />
            ))}
          </ul>
        </section>
      )}
      {letters.length > 0 && (
        <section aria-label={t('globalSearch.letters')}>
          <h2 className="px-4 pt-4 pb-1 text-caption text-ink-3">{t('globalSearch.messages')}</h2>
          <ul>
            {letters.map((l) => (
              <LetterRow
                key={`${l.chatId}:${l.messageId}`}
                l={l}
                onOpen={tap(() => onOpenMessage(l.chatId, l.messageId))}
              />
            ))}
          </ul>
        </section>
      )}
      <div aria-hidden className="h-4" />
    </div>
  );
};
