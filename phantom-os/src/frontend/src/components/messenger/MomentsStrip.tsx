/** «Моменти» на Today (DESIGN_SYSTEM 2.5): Add, свої, непобачені, побачені. Фото, коротке відео або текст. */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown, ChevronRight, Clock, EyeOff, ImagePlus, Lock, Pin, Plus, Users, X } from 'lucide-react';
import type { Chat } from '../../types/messenger';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { prefersReduced } from '../../feel/physics';
import { audienceCount, momentCircles } from '../../services/audienceCount';
import { CAPTION_MAX, MEDIA_MAX, TONES, MomentMediaError, audienceChosen, preserveContactsAudiencePref, stripPeople, useMoments, watchMoments, type Audience, type Moment, type MomentPatch, type Reach } from '../../services/moments';
import { Avatar } from './Avatar';
import { CIRCLES } from './todayFormat';
import { useRise } from './call/motion';
import { MomentsFeedPage } from './MomentsFeed';
import { MomentUndoToast } from './MomentUndoToast';
import { AUDIENCE_RUNGS, MomentAudienceSheet } from './MomentAudienceSheet';
import { useMomentProfiles, useMomentScope, useOwnedPeers } from '../../services/momentProfile';
import { useProfiles } from '../../stores/profilesStore';
import { MomentViewer, TextCard, circleLabel, isVideo, momentPeople, toneStyle, useMomentMedia, type MomentPerson } from './MomentViewer';
import { chipTone } from './kit/FilterChip';

export { momentCircles, momentPeople, type MomentPerson };

const TILE_RING_PX = 62;
const TILE_GAP_PX = 6;
const CAPTION_LINE_PX = 16;
const BAND_PAD_TOP_PX = 12;
const BAND_PAD_BOTTOM_PX = 4;
export const BAND_MIN_HEIGHT_PX = BAND_PAD_TOP_PX + TILE_RING_PX + TILE_GAP_PX + CAPTION_LINE_PX + BAND_PAD_BOTTOM_PX;
const BAND_STYLE: React.CSSProperties = { minHeight: `${BAND_MIN_HEIGHT_PX}px` };
const SETTLE_DISTANCE_PX = 4;
const SETTLE_MS = 200;
const STAMP_MS = 760;

const StackCircle: React.FC<{ group: Moment[]; label: string; avatar?: string; index: number }> = ({ group, label, avatar, index }) => {
  const m = { ...group[group.length - 1], seen: group.every((x) => x.seen) };
  const { url } = useMomentMedia(m);
  const cover = !isVideo(m) ? url ?? m.media[0]?.thumb ?? null : null;
  return (
    <span className={`shrink-0 rounded-full border-2 border-canvas bg-canvas ${index > 0 ? '-ml-2' : ''}`}>
      <span
        data-moment-circle={m.id}
        data-person={m.mine ? 'me' : m.fromId}
        data-seen={m.seen ? 'true' : 'false'}
        className={`flex w-8 h-8 rounded-full overflow-hidden items-center justify-center ${m.seen ? '' : 'border-[1.5px] border-ink'}`}
      >
        <Avatar src={avatar ?? cover} name={label} tone={m.mine ? null : m.fromId} className="w-full h-full text-caption" />
      </span>
    </span>
  );
};

const Tile: React.FC<{ group: Moment[]; label: string; avatar?: string; onOpen: () => void }> = ({ group, label, avatar, onOpen }) => {
  const { t } = useTranslation();
  const m = { ...group[group.length - 1], seen: group.every((x) => x.seen) };
  const { url } = useMomentMedia(m);
  const cover = !isVideo(m) ? url ?? m.media[0]?.thumb ?? null : null;
  return (
    <li className="shrink-0 w-[72px]">
      <button
        type="button"
        data-moment-tile={m.id}
        data-person={m.mine ? 'me' : m.fromId}
        data-seen={m.seen ? 'true' : 'false'}
        onClick={onOpen}
        aria-label={t('moments.open', { name: label })}
        className="flex flex-col items-center gap-1.5 w-[72px] min-w-0 min-h-0 p-0"
      >
        <Avatar src={avatar ?? cover} name={label} tone={m.mine ? null : m.fromId} className="w-[52px] h-[52px] text-title-2" momentsSize="w-[62px] h-[62px]" moments={m.seen ? 'seen' : 'unseen'} />
        <span className="text-caption text-ink-2 truncate w-[72px] text-center">{label}</span>
      </button>
    </li>
  );
};

export type MomentsVariant = 'stack' | 'band' | 'row' | 'host';

const composeOpeners: Array<() => boolean> = [];
const momentOpeners: Array<(id: string) => boolean> = [];
const feedOpeners: Array<() => void> = [];

/** Смуга списку розмов відкриває момент і «+» у хості, що вже змонтований (variant="host"). */
export const openMomentInHost = (id: string): boolean => momentOpeners[momentOpeners.length - 1]?.(id) ?? false;
export const composeMomentInHost = (): boolean => composeOpeners[composeOpeners.length - 1]?.() ?? false;

export interface MomentsStripProps {
  chats: Chat[];
  onOpenChat: (chatId: string) => void;
  variant?: MomentsVariant;
  onStateChange?: (state: 'compact' | 'open') => void;
}

export function ringRankOf(chats: Chat[]): (fromId: string) => number {
  const rank = new Map<string, number>();
  for (const c of chats) {
    if (!c.peerNodeId) continue;
    const rings = c.circles ?? (c.circle ? [c.circle] : []);
    const best = CIRCLES.findIndex((r) => rings.includes(r));
    const value = best < 0 ? CIRCLES.length : best;
    rank.set(c.peerNodeId, Math.min(value, rank.get(c.peerNodeId) ?? value));
  }
  return (fromId) => rank.get(fromId) ?? CIRCLES.length;
}

export const MomentsStrip: React.FC<MomentsStripProps> = ({ chats, onOpenChat, variant = 'band', onStateChange }) => {
  const { t } = useTranslation();
  const meAvatar = useMessengerStore((s) => s.currentUser.avatar);
  const list = useMoments((s) => s.list);
  const unavailable = useMoments((s) => s.unavailable);
  const [composing, setComposing] = useState(false);
  const [viewing, setViewing] = useState<string | null>(null);
  const [feedOpen, setFeedOpen] = useState(false);
  const people = useMemo(() => momentPeople(chats), [chats]);
  const byId = useMemo(() => new Map(people.map((p) => [p.id, p])), [people]);

  useEffect(() => watchMoments(), []);

  const ringOf = useMemo(() => ringRankOf(chats), [chats]);
  const inScope = useMomentScope(chats);
  const groups = useMemo(() => stripPeople((list ?? []).filter(inScope), ringOf), [list, ringOf, inScope]);
  const ordered = useMemo(() => groups.flat(), [groups]);
  const [order, setOrder] = useState<string[]>([]);
  useEffect(() => {
    if (!viewing) setOrder(ordered.map((m) => m.id));
  }, [ordered, viewing]);

  const startedEmpty = useRef(list === null);
  const [settle, setSettle] = useState(false);
  const arrivedList = useRef<Moment[] | null>(null);
  const loaded = list !== null;

  useEffect(() => {
    if (settle && list !== arrivedList.current) setSettle(false);
  }, [list, settle]);

  useEffect(() => {
    if (!loaded || !startedEmpty.current) return undefined;
    startedEmpty.current = false;
    if (prefersReduced()) return undefined;
    arrivedList.current = list;
    setSettle(true);
    const timer = window.setTimeout(() => setSettle(false), SETTLE_MS);
    return () => {
      window.clearTimeout(timer);
      setSettle(false);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded]);

  const bandRef = useRef<HTMLDivElement>(null);
  const stackRef = useRef<HTMLButtonElement>(null);
  useRise(bandRef, settle, SETTLE_DISTANCE_PX, SETTLE_MS);
  useRise(stackRef, settle, SETTLE_DISTANCE_PX, SETTLE_MS);

  useEffect(() => {
    if (variant === 'row') return undefined;
    const openFeed = () => setFeedOpen(true);
    feedOpeners.push(openFeed);
    return () => {
      feedOpeners.splice(feedOpeners.indexOf(openFeed), 1);
    };
  }, [variant]);

  useEffect(() => {
    if (variant === 'row') return undefined;
    const w = window as unknown as { __phantomComposeMoment?: () => boolean };
    const open = () => {
      setComposing(true);
      return true;
    };
    composeOpeners.push(open);
    w.__phantomComposeMoment = open;
    return () => {
      composeOpeners.splice(composeOpeners.indexOf(open), 1);
      const last = composeOpeners[composeOpeners.length - 1];
      if (last) w.__phantomComposeMoment = last;
      else delete w.__phantomComposeMoment;
    };
  }, [variant]);

  const orderedRef = useRef(ordered);
  orderedRef.current = ordered;
  const loadedRef = useRef(list !== null);
  loadedRef.current = list !== null;
  const waitingOpen = useRef<string | null>(null);
  useEffect(() => {
    const id = waitingOpen.current;
    if (!id || !loadedRef.current) return;
    waitingOpen.current = null;
    if (ordered.some((m) => m.id === id)) setViewing(id);
  }, [ordered]);
  useEffect(() => {
    if (variant === 'row') return undefined;
    const w = window as unknown as { __phantomOpenMoment?: (id: string) => boolean };
    const open = (id: string) => {
      if (!loadedRef.current) {
        waitingOpen.current = id;
        return true;
      }
      if (!orderedRef.current.some((m) => m.id === id)) return false;
      setViewing(id);
      return true;
    };
    momentOpeners.push(open);
    w.__phantomOpenMoment = open;
    return () => {
      momentOpeners.splice(momentOpeners.indexOf(open), 1);
      const last = momentOpeners[momentOpeners.length - 1];
      if (last) w.__phantomOpenMoment = last;
      else delete w.__phantomOpenMoment;
    };
  }, [variant]);

  const composeOverlay = composing ? (
    <MomentComposer
      file={null}
      people={people}
      chats={chats}
      onClose={() => setComposing(false)}
    />
  ) : null;

  if (list === null) {
    if (variant === 'stack' || variant === 'row' || variant === 'host' || unavailable) return composeOverlay;
    return (
      <div
        data-moments
        data-moments-band
        data-moments-state="loading"
        aria-busy="true"
        aria-label={t('moments.title')}
        style={BAND_STYLE}
      >
        {composeOverlay}
      </div>
    );
  }

  const nameOf = (m: Moment) => (m.mine ? t('moments.you') : byId.get(m.fromId)?.name || m.fromName || t('chatList.unnamed'));
  const sequence = order.map((id) => ordered.find((m) => m.id === id)).filter((m): m is Moment => Boolean(m));
  const compose = () => {
    soundFx.playTap();
    const w = window as unknown as { __phantomComposeMoment?: () => boolean };
    if (w.__phantomComposeMoment) w.__phantomComposeMoment();
    else setComposing(true);
  };
  const plus = (size: string) => (
    <span data-moment-ring className={`flex ${size} rounded-full bg-raised items-center justify-center`}>
      <Plus className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden />
    </span>
  );

  const overlays = (
    <>
      <MomentUndoToast />
      {composeOverlay}
      {viewing && sequence.length > 0 && (
        <MomentViewer
          moments={sequence}
          startId={viewing}
          nameOf={nameOf}
          avatarOf={(m) => (m.mine ? meAvatar || undefined : byId.get(m.fromId)?.avatar)}
          chatOf={(m) => byId.get(m.fromId)?.chatId ?? chats.find((c) => c.peerNodeId === m.fromId)?.id}
          onOpenChat={(id) => {
            setViewing(null);
            onOpenChat(id);
          }}
          onClose={() => setViewing(null)}
        />
      )}
      {feedOpen && <MomentsFeedPage onClose={() => setFeedOpen(false)} onOpenChat={onOpenChat} onCompose={() => setComposing(true)} />}
    </>
  );

  if (variant === 'host') return overlays;

  if (variant === 'row') {
    if (groups.length === 0) return null;
    const topGroups = groups.slice(0, 3);
    const names = groups.map((g) => nameOf(g[0]));
    const shown = names.slice(0, 2).join(', ');
    const rest = names.length - 2;
    const who = rest > 0 ? `${shown} ${t('messageCard.more', { n: rest })}` : shown;
    const rowLabel = `${t('moments.title')} · ${who}`;
    return (
      <>
        <button
          ref={stackRef}
          type="button"
          data-moments
          data-moments-row
          data-moments-settle={settle ? '' : undefined}
          aria-label={rowLabel}
          onClick={() => {
            soundFx.playTap();
            const host = feedOpeners[feedOpeners.length - 1];
            if (host) host();
            else setFeedOpen(true);
          }}
          className="w-full h-14 px-4 flex items-center gap-3 text-left bg-canvas hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick"
        >
          <span data-moments-stack className="shrink-0 flex items-center">
            {topGroups.map((g, idx) => (
              <StackCircle
                key={g[0].mine ? 'me' : g[0].fromId}
                group={g}
                label={nameOf(g[0])}
                avatar={g[0].mine ? meAvatar || undefined : byId.get(g[0].fromId)?.avatar}
                index={idx}
              />
            ))}
          </span>
          <span className="min-w-0 flex-1 self-stretch flex items-center justify-between gap-2 border-b border-line text-callout text-ink-2">
            <span className="min-w-0 truncate">
              <strong className="font-semibold text-ink">{t('moments.title')}</strong>
              {` · ${who}`}
            </span>
            <ChevronRight className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
          </span>
        </button>
        {composeOverlay}
        {feedOpen && <MomentsFeedPage onClose={() => setFeedOpen(false)} onOpenChat={onOpenChat} onCompose={() => setComposing(true)} />}
      </>
    );
  }

  if (variant === 'stack') {
    if (groups.length === 0) return overlays;
    const topGroups = groups.slice(0, 3);
    return (
      <>
        <button
          ref={stackRef}
          type="button"
          data-moments-stack
          data-moments-settle={settle ? '' : undefined}
          aria-label={t('moments.title')}
          onClick={() => {
            soundFx.playTap();
            if (onStateChange) onStateChange('open');
            else {
              const first = topGroups[0];
              if (first) setViewing((first.find((m) => !m.seen) ?? first[0]).id);
            }
          }}
          className="h-11 min-h-0 px-1.5 inline-flex items-center rounded-full"
        >
          {topGroups.map((g, idx) => (
            <StackCircle
              key={g[0].mine ? 'me' : g[0].fromId}
              group={g}
              label={nameOf(g[0])}
              avatar={g[0].mine ? meAvatar || undefined : byId.get(g[0].fromId)?.avatar}
              index={idx}
            />
          ))}
        </button>
        {overlays}
      </>
    );
  }

  if (groups.length === 0) {
    return (
      <div
        ref={bandRef}
        data-moments
        data-moments-band
        data-moments-state="ready"
        data-moments-settle={settle ? '' : undefined}
        data-people-row
        data-moments-pill
        aria-label={t('moments.title')}
        style={BAND_STYLE}
        className="flex items-start px-4 pt-2 pb-1"
      >
        <button
          type="button"
          data-moment-add
          onClick={compose}
          className="h-11 min-h-0 px-4 inline-flex items-center gap-2 rounded-full bg-raised text-subhead text-ink"
        >
          <Plus className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden />
          {t('today.door.moment')}
        </button>
        {overlays}
      </div>
    );
  }

  return (
    <div
      ref={bandRef}
      data-moments
      data-moments-band
      data-moments-state="ready"
      data-moments-settle={settle ? '' : undefined}
      data-people-row
      data-own-swipe
      aria-label={t('moments.title')}
      style={BAND_STYLE}
      className="flex items-start gap-1 overflow-x-auto no-scrollbar px-3 pt-3 pb-1"
    >
      <ul className="contents">
        <li className="shrink-0 w-[72px]">
          <button type="button" data-moment-add onClick={compose} className="flex flex-col items-center gap-1.5 w-[72px] min-w-0 min-h-0 p-0">
            {plus('w-[62px] h-[62px]')}
            <span className="text-caption text-ink-2 truncate w-[72px] text-center">{t('moments.add')}</span>
          </button>
        </li>
        {groups.map((g) => (
          <Tile
            key={g[0].mine ? 'me' : g[0].fromId}
            group={g}
            label={nameOf(g[0])}
            avatar={g[0].mine ? meAvatar || undefined : byId.get(g[0].fromId)?.avatar}
            onOpen={() => setViewing((g.find((m) => !m.seen) ?? g[0]).id)}
          />
        ))}
        <li className="shrink-0 w-[72px]">
          <button type="button" data-moments-open onClick={() => setFeedOpen(true)} className="flex flex-col items-center gap-1.5 w-[72px] min-w-0 min-h-0 p-0">
            <span className="flex w-[62px] h-[62px] rounded-full bg-raised items-center justify-center">
              <ChevronRight className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden />
            </span>
            <span className="text-caption text-ink-2 truncate w-[72px] text-center">{t('moments.title')}</span>
          </button>
        </li>
      </ul>
      {overlays}
    </div>
  );
};

const Chip: React.FC<{ on: boolean; onClick: () => void; children: React.ReactNode; testId?: string }> = ({ on, onClick, children, testId }) => (
  <button
    type="button"
    data-testid={testId}
    aria-pressed={on}
    onClick={onClick}
    className={`shrink-0 h-11 min-h-0 px-4 rounded-full border text-subhead inline-flex items-center gap-2 ${chipTone(on)}`}
  >
    {children}
  </button>
);

const RUNGS = AUDIENCE_RUNGS;

const reachOf = (a: Audience): Reach => (a.kind === 'circle' ? 'circles' : a.kind);

export interface MomentComposerProps {
  /** Вибране фото чи відео; null — почати з тексту. */
  file: File | null;
  people: MomentPerson[];
  chats: Chat[];
  onClose: () => void;
  /** Наперед вибрані люди (зустріч): аудиторія «Вибрані люди» з ними. */
  preset?: { ids: string[] };
  /** Свій опублікований момент: правка підпису, тону й аудиторії одним «Зберегти». */
  editing?: Moment;
}

const EditCover: React.FC<{ m: Moment }> = ({ m }) => {
  const { url } = useMomentMedia(m);
  const src = url ?? m.media[0]?.thumb ?? null;
  return (
    <div data-moment-edit-cover className="relative mt-4 w-[120px] aspect-[3/4] rounded-[12px] overflow-hidden bg-raised">
      {src && !isVideo(m) && <img src={src} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover" />}
      {url && isVideo(m) && <video src={url} muted playsInline preload="metadata" className="absolute inset-0 w-full h-full object-cover" />}
      {m.media.length > 1 && (
        <span className="absolute top-2 right-2 h-6 px-2 rounded-full flex items-center text-footnote text-white tnum" style={{ background: 'var(--np-moment-label)' }}>{m.media.length}</span>
      )}
    </div>
  );
};

export const MomentComposer: React.FC<MomentComposerProps> = ({ file: initial, people: allPeople, chats: allChats, onClose, preset, editing }) => {
  const { t } = useTranslation();
  // S10: момент із профілю — лише його людям; кола звужують усередині.
  const { owned, scoped } = useOwnedPeers(allChats);
  const people = useMemo(() => allPeople.filter((p) => owned(p.id)), [allPeople, owned]);
  const chats = useMemo(() => allChats.filter((c) => !c.peerNodeId || owned(c.peerNodeId)), [allChats, owned]);
  const narrowed = people.length < allPeople.length;
  const post = useMoments((s) => s.post);
  const update = useMoments((s) => s.update);
  const prefs = useMoments((s) => s.prefs);
  const loadPrefs = useMoments((s) => s.loadPrefs);
  const [files, setFiles] = useState<File[]>(initial ? [initial] : []);
  const [caption, setCaption] = useState(editing?.text ?? '');
  const [tone, setTone] = useState(editing?.tone ?? 0);
  const [reach, setReach] = useState<Reach>(editing ? editing.reach : preset ? 'people' : momentCircles(chats).some((c) => c.name === 'close') && !audienceChosen() ? 'circles' : 'contacts');
  const [ids, setIds] = useState<string[]>(editing?.reach === 'people' ? editing.to ?? [] : preset?.ids ?? []);
  const [names, setNames] = useState<string[]>(() =>
    editing ? editing.circles : !preset && !audienceChosen() && momentCircles(chats).some((c) => c.name === 'close') ? ['close'] : []);
  const [leftOut, setLeftOut] = useState<string[]>([]);
  const [keep, setKeep] = useState(editing?.keep ?? false);
  const [ready, setReady] = useState(Boolean(preset || editing));
  const [busy, setBusy] = useState(false);
  const [stamped, setStamped] = useState(false);
  const [audienceOpen, setAudienceOpen] = useState(false);
  const [error, setError] = useState('');
  const touched = useRef(Boolean(preset || editing));
  const audienceDirty = useRef(false);
  const peopleRef = useRef(people);
  peopleRef.current = people;
  const chatsRef = useRef(chats);
  chatsRef.current = chats;
  const input = useRef<HTMLInputElement>(null);
  const previews = useMemo(() => files.map((f) => ({ url: URL.createObjectURL(f), video: f.type.startsWith('video/') })), [files]);
  useEffect(() => () => {
    for (const x of previews) URL.revokeObjectURL(x.url);
  }, [previews]);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:compose', true, () => closeRef.current()), []);
  useEffect(() => (audienceOpen ? registerPhoneLayer('moments:audience', true, () => setAudienceOpen(false)) : undefined), [audienceOpen]);
  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      if (active) {
        touched.current = true;
        setReady(true);
      }
    }, 1500);
    void Promise.resolve(loadPrefs())
      .catch(() => null)
      .then((loaded) => {
        if (!active) return;
        const p = loaded ?? useMoments.getState().prefs;
        if (p && !touched.current && p.audience.kind === 'contacts' && !audienceChosen() && momentCircles(chatsRef.current).some((c) => c.name === 'close')) {
          setReach('circles');
          setNames(['close']);
          setKeep(p.keep);
        } else if (p && !touched.current) {
          const r = reachOf(p.audience);
          setReach(r === 'everyone' && !p.everyone ? 'contacts' : r);
          if (p.audience.kind === 'people') setIds(p.audience.ids.filter((id) => peopleRef.current.some((x) => x.id === id)));
          if (p.audience.kind === 'circles') setNames(p.audience.names);
          if (p.audience.kind === 'circle') setNames([p.audience.name]);
          setKeep(p.keep);
        }
      })
      .finally(() => {
        window.clearTimeout(timer);
        if (active) setReady(true);
      });
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [loadPrefs]);

  const circles = useMemo(() => momentCircles(chats), [chats]);
  const rungs = RUNGS.filter((r) => (r.reach === 'everyone' ? prefs?.everyone === true && !narrowed : r.reach !== 'circles' || circles.length > 0));
  const noOne = people.length === 0;
  const excluded = useMemo(() => new Set(leftOut), [leftOut]);
  const chosenCircles = useMemo(() => circles.filter((c) => names.includes(c.name)), [circles, names]);
  const activePeopleIds = useMemo(() => ids.filter((id) => !excluded.has(id)), [ids, excluded]);
  const activeCircleIds = useMemo(
    () => [...new Set(chosenCircles.flatMap((c) => c.ids))].filter((id) => !excluded.has(id)),
    [chosenCircles, excluded],
  );
  const activeContactIds = useMemo(
    () => [...new Set(people.map((p) => p.id))].filter((id) => !excluded.has(id)),
    [people, excluded],
  );
  const audience: Audience = useMemo(
    () =>
      reach === 'me' ? { kind: 'me' }
        : reach === 'people' ? { kind: 'people', ids: activePeopleIds }
          : reach === 'circles' ? { kind: 'circles', names: activeCircleIds.length > 0 ? chosenCircles.map((c) => c.name) : [], ids: activeCircleIds }
            : reach === 'everyone' && !narrowed ? { kind: 'everyone' }
              : excluded.size > 0 || narrowed ? { kind: 'people', ids: activeContactIds } : { kind: 'contacts' },
    [reach, activePeopleIds, chosenCircles, activeCircleIds, excluded, activeContactIds, narrowed],
  );
  const count = useMemo(() => audienceCount(audience, chats), [audience, chats]);
  const outerRings = useMemo(
    () => (count ? (['social', 'work'] as const).filter((r) => (count.byRing[r]?.length ?? 0) > 0) : []),
    [count],
  );
  const textOnly = editing ? editing.media.length === 0 : files.length === 0;
  const tonesOn = (prefs?.tones ?? 0) > 0;
  const hasContent = !textOnly || caption.trim().length > 0;
  const closeCircle = circles.find((c) => c.name === 'close');
  const onlyClose = reach === 'circles' && names.length === 1 && names[0] === 'close' && leftOut.length === 0;
  const reachable =
    reach === 'me'
    || (!noOne && (reach === 'people' ? activePeopleIds.length > 0 : reach === 'circles' ? activeCircleIds.length > 0 : reach === 'contacts' ? activeContactIds.length > 0 : true));
  const canShare = !busy && hasContent && reachable;
  const video = previews.some((x) => x.video);

  const pick = (r: Reach) => {
    touched.current = true;
    audienceDirty.current = true;
    setReady(true);
    if (r !== reach) {
      setLeftOut([]);
      setReach(r);
    }
  };

  const quickClose = () => {
    touched.current = true;
    audienceDirty.current = true;
    setReady(true);
    setLeftOut([]);
    setReach('circles');
    setNames(['close']);
  };

  const addFiles = (picked: File[]) => {
    if (picked.length === 0) return;
    const over = files.length + picked.length > MEDIA_MAX;
    setFiles((cur) => [...cur, ...picked].slice(0, MEDIA_MAX));
    setError(over ? t('momentsF.maxMedia', { n: MEDIA_MAX }) : '');
  };

  const share = async () => {
    if (!canShare) return;
    setBusy(true);
    setError('');
    if (editing) {
      try {
        const patch: MomentPatch = {};
        if (caption.trim() !== editing.text) patch.text = caption;
        if (textOnly && tonesOn && tone !== (editing.tone ?? 0)) patch.tone = tone;
        if (audienceDirty.current) patch.audience = audience;
        await update(editing.id, patch);
        soundFx.playSend();
        onClose();
      } catch (e) {
        setError(t((e as { code?: string } | null)?.code === 'no_one' ? 'moments.noOne' : 'moments.editFailed'));
        setBusy(false);
      }
      return;
    }
    try {
      const row = await post(files, caption, audience, keep, files.length === 0 && tonesOn ? tone : 0);
      useMomentProfiles.getState().record(row.id, useProfiles.getState().activeId);
      if (reach === 'contacts' && (excluded.size > 0 || narrowed)) {
        preserveContactsAudiencePref(activeContactIds, keep);
      }
      soundFx.playSend();
      if (prefersReduced()) {
        onClose();
      } else {
        setStamped(true);
        window.setTimeout(() => closeRef.current(), STAMP_MS);
      }
    } catch (e) {
      const code = e instanceof MomentMediaError ? e.message : (e as { code?: string } | null)?.code || '';
      setError(
        code === 'video_too_long' ? t('moments.videoTooLong')
          : code === 'video_too_big' ? t('moments.videoTooBig')
            : code === 'video_unreadable' || (e instanceof Error && /decode|image/i.test(e.message)) ? t('moments.badPhoto')
              : code === 'no_one' ? t('moments.noOne')
                : t('moments.failed'),
      );
      setBusy(false);
    }
  };

  const audienceLabel =
    reach === 'me' ? t('moments.rungMe')
      : reach === 'circles' && chosenCircles.length > 0 ? chosenCircles.map((c) => circleLabel(t, c.name)).join(', ')
        : t(RUNGS.find((r) => r.reach === reach)?.key ?? 'moments.rungContacts');
  const AudienceIcon = reach === 'me' ? Lock : Users;

  return createPortal(
    <div data-moment-composer role="dialog" aria-modal="true" aria-label={editing ? t('momentsF.editTitle') : t('moments.newTitle')} data-moment-editing={editing ? 'true' : undefined} className="fixed inset-0 z-[1300] bg-canvas flex flex-col" style={{ backgroundColor: 'var(--ph-canvas)', border: 0, boxShadow: 'none' }}>
      <header className="h-14 shrink-0 px-1 flex items-center gap-1" style={{ marginTop: 'env(safe-area-inset-top)' }}>
        <button type="button" data-moment-cancel onClick={onClose} aria-label={t('moments.cancel')} className="w-12 h-12 min-h-0 min-w-0 flex items-center justify-center rounded-full text-ink">
          <X className="w-6 h-6" strokeWidth={2} aria-hidden />
        </button>
        <h2 className="text-title-2 text-ink">{editing ? t('momentsF.editTitle') : t('moments.newTitle')}</h2>
      </header>
      <div className={`flex-1 min-h-0 overflow-y-auto px-4 pb-4 ${stamped ? 'np-moment-travel' : ''}`}>
        <h3 className="mb-2 text-caption text-ink-3">{t('moments.whoSees')}</h3>
        <div className="flex gap-2 flex-wrap">
          <button
            type="button"
            data-moment-audience-chip
            onClick={() => setAudienceOpen(true)}
            className="h-12 min-h-0 min-w-0 max-w-full pl-4 pr-3 inline-flex items-center gap-2 rounded-full bg-ink text-canvas text-body"
          >
            <AudienceIcon className="w-5 h-5 shrink-0" strokeWidth={1.75} aria-hidden />
            <span data-moment-audience-label className="truncate">{audienceLabel}</span>
            {ready && count && reach !== 'me' && <span className="opacity-70">{count.total}</span>}
            <ChevronDown className="w-4 h-4 shrink-0" strokeWidth={2} aria-hidden />
          </button>
          {closeCircle && !onlyClose && (
            <button
              type="button"
              data-moment-quick-close
              onClick={quickClose}
              className="h-12 min-h-0 min-w-0 px-4 inline-flex items-center gap-2 rounded-full border border-line-strong text-body text-ink"
            >
              <Users className="w-5 h-5 shrink-0" strokeWidth={1.75} aria-hidden />
              {circleLabel(t, 'close')}
              <span className="text-ink-3 tnum">{closeCircle.ids.length}</span>
            </button>
          )}
          {leftOut.length > 0 && (
            <span className="h-12 px-4 inline-flex items-center gap-2 rounded-full border border-line-strong text-body text-ink-2">
              <EyeOff className="w-5 h-5" strokeWidth={1.75} aria-hidden />
              {t('momentsR.without', { n: leftOut.length })}
            </span>
          )}
        </div>
        {noOne && reach !== 'me' && <p data-moment-noone className="mt-2 text-callout text-ink-3">{t('moments.noOne')}</p>}
        {reach === 'me' && <p data-moment-device-only className="mt-2 text-footnote text-ink-3">{t('momentsR.deviceOnly')}</p>}
        {editing && !textOnly && <EditCover m={editing} />}
        {(editing ? textOnly : files.length === 0) && (
          <div className="relative mt-4 mx-auto w-full max-w-[280px] aspect-[3/4] rounded-[16px] overflow-hidden bg-raised">
            {caption.trim() && <TextCard text={caption.trim()} tone={tonesOn ? tone : 0} />}
            {!editing && !caption.trim() && (
              <button
                type="button"
                data-moment-pick
                onClick={() => input.current?.click()}
                className="absolute inset-0 w-full h-full min-h-0 flex flex-col items-center justify-center gap-2 text-ink-2"
              >
                <ImagePlus className="w-8 h-8" strokeWidth={1.5} aria-hidden />
                <span className="text-callout">{t('moments.pickMedia')}</span>
                <span className="text-footnote text-ink-3">{t('moments.orText')}</span>
              </button>
            )}
          </div>
        )}
        {!editing && files.length > 0 && (
          <ul data-moment-carousel className="mt-4 -mx-4 px-4 flex gap-2 overflow-x-auto">
            {previews.map((x, i) => (
              <li key={x.url} data-moment-item={i} className="relative shrink-0 w-[120px] aspect-[3/4] rounded-[12px] overflow-hidden bg-raised">
                {!x.video && <img data-moment-preview src={x.url} alt="" className="absolute inset-0 w-full h-full object-cover" />}
                {x.video && <video data-moment-preview src={x.url} muted playsInline autoPlay loop className="absolute inset-0 w-full h-full object-cover" />}
                {files.length > 1 && (
                  <span data-moment-num className="absolute top-2 left-2 w-6 h-6 rounded-full bg-canvas text-ink text-footnote tnum flex items-center justify-center">{i + 1}</span>
                )}
                <button
                  type="button"
                  data-moment-unpick
                  aria-label={t('moments.removeMedia')}
                  onClick={() => setFiles((cur) => cur.filter((_, j) => j !== i))}
                  className="touch-floor-inline absolute top-2 right-2 w-7 h-7 rounded-full flex items-center justify-center text-white"
                  style={{ background: 'var(--np-moment-label)' }}
                >
                  <X className="w-4 h-4" strokeWidth={2} aria-hidden />
                </button>
              </li>
            ))}
            {files.length < MEDIA_MAX && (
              <li className="shrink-0 w-[120px] aspect-[3/4]">
                <button
                  type="button"
                  data-moment-add-media
                  aria-label={t('momentsF.addMedia')}
                  onClick={() => input.current?.click()}
                  className="w-full h-full min-h-0 min-w-0 rounded-[12px] border border-line-strong flex items-center justify-center text-ink-2"
                >
                  <Plus className="w-6 h-6" strokeWidth={1.75} aria-hidden />
                </button>
              </li>
            )}
          </ul>
        )}
        <input
          ref={input}
          type="file"
          accept="image/*,video/*"
          multiple
          className="hidden"
          data-moment-input
          onChange={(e) => {
            const picked = Array.from(e.target.files ?? []);
            e.target.value = '';
            addFiles(picked);
          }}
        />
        {video && <p className="mt-2 text-center text-footnote text-ink-3">{t('moments.videoLimit')}</p>}
        <div className="relative mt-4">
          <textarea
            data-moment-caption
            value={caption}
            maxLength={CAPTION_MAX}
            rows={textOnly ? 3 : 2}
            onChange={(e) => setCaption(e.target.value)}
            placeholder={textOnly ? t('moments.textPlaceholder') : t('moments.caption')}
            className="w-full px-4 pt-3 pb-7 rounded-[16px] border border-line-strong bg-transparent text-body text-ink placeholder:text-ink-3 outline-none resize-none"
          />
          <span data-moment-count className="absolute right-4 bottom-2 text-footnote text-ink-3">{`${caption.length} / ${CAPTION_MAX}`}</span>
        </div>
        {textOnly && tonesOn && (
          <div data-moment-tones role="radiogroup" aria-label={t('momentsF.tone')} className="mt-3 flex gap-1">
            {Array.from({ length: TONES + 1 }, (_, n) => (
              <button
                key={n}
                type="button"
                role="radio"
                aria-checked={tone === n}
                data-moment-tone={n}
                aria-label={n === 0 ? t('momentsF.toneNone') : t('momentsF.toneN', { n })}
                onClick={() => setTone(n)}
                className={`touch-floor-inline w-10 h-10 rounded-full p-[3px] border-2 ${tone === n ? 'border-ink' : 'border-transparent'}`}
              >
                <span className={`block w-full h-full rounded-full ${n === 0 ? 'border border-line-strong' : ''}`} style={{ backgroundColor: toneStyle(n).backgroundColor }} />
              </button>
            ))}
          </div>
        )}
        {!editing && <h3 className="mt-5 mb-2 text-subhead text-ink-2">{t('moments.howLong')}</h3>}
        {!editing && <div className="flex gap-2">
          <Chip testId="keep-day" on={!keep} onClick={() => { touched.current = true; setKeep(false); }}>
            <Clock className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            {t('moments.day')}
          </Chip>
          <Chip testId="keep-profile" on={keep} onClick={() => { touched.current = true; setKeep(true); }}>
            <Pin className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            {t('moments.keepProfile')}
          </Chip>
        </div>}
        {error && <p role="alert" data-moment-error className="mt-3 text-callout text-danger">{error}</p>}
      </div>
      <div className="shrink-0 px-4 pt-2" style={{ paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}>
        <button
          type="button"
          data-moment-share
          disabled={!canShare}
          onClick={share}
          className="w-full h-12 rounded-full bg-accent text-on-accent text-headline disabled:opacity-40"
        >
          {editing ? t('momentsF.save') : busy ? t('moments.sharing') : t('moments.share')}
        </button>
      </div>
      {stamped && (
        <div data-moment-stamp className="absolute inset-0 flex items-center justify-center pointer-events-none" aria-live="polite">
          <span className="np-moment-stamp h-14 px-6 rounded-full bg-ink text-canvas inline-flex items-center gap-2 text-headline">
            <Check className="w-5 h-5" strokeWidth={2.25} aria-hidden />
            {t('momentsR.published')}
          </span>
        </div>
      )}
      {audienceOpen && (
        <MomentAudienceSheet
          rungs={rungs}
          reach={reach}
          onReach={pick}
          circles={circles}
          chosenCircles={chosenCircles.map((c) => c.name)}
          onToggleCircle={(name) => {
            touched.current = true;
            audienceDirty.current = true;
            setNames((cur) => (cur.includes(name) ? cur.filter((x) => x !== name) : [...cur, name]));
          }}
          people={people}
          activePeopleIds={activePeopleIds}
          onTogglePerson={(id, on) => {
            touched.current = true;
            audienceDirty.current = true;
            if (on) {
              setIds((cur) => cur.filter((x) => x !== id));
            } else {
              setLeftOut((cur) => cur.filter((x) => x !== id));
              setIds((cur) => (cur.includes(id) ? cur : [...cur, id]));
            }
          }}
          count={count}
          seeN={scoped}
          ready={ready}
          noOne={noOne}
          outerRings={outerRings}
          onLeaveOut={(ring) => {
            const drop = count?.byRing[ring] ?? [];
            touched.current = true;
            audienceDirty.current = true;
            setLeftOut((cur) => [...new Set([...cur, ...drop])]);
          }}
          onDone={() => setAudienceOpen(false)}
        />
      )}
    </div>,
    document.body,
  );
};

export default MomentsStrip;
