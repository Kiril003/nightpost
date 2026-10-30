/** Стрічка «Моменти» (20-moments): нове коло за колом, свої, «Усе переглянуто», побачене — за «Старіші». */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, Eye, Heart, Pencil, Play, Plus, Send, Settings, UserPlus } from 'lucide-react';
import { loadMedia } from '../../services/messengerMedia';
import { pluralKey } from '../chat/tNow';
import { buildFeed, type FeedFilter, type FeedSection } from './moments/feedModel';
import { MomentComposer, ringRankOf } from './MomentsStrip';
import { CIRCLES, type RealCircle } from './todayFormat';
import type { MessageKey } from '../../i18n';
import { Avatar } from './Avatar';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useMoments, watchMoments, type Moment, type MomentMedia } from '../../services/moments';
import { useMomentScope } from '../../services/momentProfile';
import { MomentViewer, audienceLine, circleLabel, momentPeople, toneStyle } from './MomentViewer';
import { useEdgeStretch } from '../../feel/useEdgeStretch';
import { TopBar } from './kit/TopBar';
import { MomentInsights } from './MomentInsights';
import { MomentShare, canShareMoment } from './MomentShare';
import { MomentUndoToast } from './MomentUndoToast';
import { MomentsSettings } from './MomentsSettings';
import { PeopleScreen } from './PeopleScreen';
import { FilterChip } from './kit/FilterChip';

const agoLabel = (t: (k: MessageKey, v?: Record<string, string | number>) => string, atMs: number, now = Date.now()): string => {
  const min = Math.max(0, Math.floor((now - atMs) / 60_000));
  if (min < 1) return t('momentsR.agoNow');
  if (min < 60) return t('momentsR.agoMin', { n: min });
  if (min < 1440) return t('momentsR.agoHour', { n: Math.floor(min / 60) });
  return t('momentsR.agoDay', { n: Math.floor(min / 1440) });
};

type T = (k: MessageKey, v?: Record<string, string | number>) => string;

/** Одне медіа поста: ключ іде з кадру, байти — лише коли слайд поруч. */
function useMediaUrl(media: MomentMedia | undefined, on: boolean): string | null {
  const [url, setUrl] = useState<string | null>(null);
  const ready = media?.state === 'ready';
  useEffect(() => {
    setUrl(null);
    if (!media || !ready || !on) return;
    let live = true;
    loadMedia(media).then(
      (u) => live && setUrl(u),
      () => undefined,
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [media?.blobId, ready, on]);
  return url;
}

const SlideBody: React.FC<{ media: MomentMedia; on: boolean }> = ({ media, on }) => {
  const url = useMediaUrl(media, on);
  const video = media.mime.startsWith('video/');
  const thumb = media.thumb ?? null;
  return (
    <>
      {!video && (url || thumb) && <img src={url ?? thumb!} alt="" draggable={false} className={`absolute inset-0 w-full h-full object-cover ${url ? '' : 'blur-sm scale-110'}`} />}
      {video && url && <video src={url} autoPlay muted loop playsInline preload="metadata" className="absolute inset-0 w-full h-full object-cover" />}
      {video && !url && thumb && <img src={thumb} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover blur-sm scale-110" />}
      {video && (
        <span aria-hidden className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <span className="w-14 h-14 rounded-full flex items-center justify-center" style={{ background: 'var(--np-moment-label)' }}>
            <Play className="w-6 h-6 text-white" strokeWidth={2} fill="currentColor" />
          </span>
        </span>
      )}
    </>
  );
};

/** Карусель до межі кадру: гортання вбік, «1/3» і крапки, дотик — перегляд. */
const Carousel: React.FC<{ m: Moment; name: string; onOpen: () => void }> = ({ m, name, onOpen }) => {
  const { t } = useTranslation();
  const [at, setAt] = useState(0);
  const n = m.media.length;
  return (
    <div className="relative px-4">
      <div
        data-post-media
        data-slides={n}
        onScroll={(e) => {
          const el = e.currentTarget;
          setAt(Math.max(0, Math.min(n - 1, Math.round(el.scrollLeft / Math.max(1, el.clientWidth)))));
        }}
        className="flex overflow-x-auto snap-x snap-mandatory no-scrollbar aspect-[4/5] rounded-[16px] bg-raised"
      >
        {m.media.map((md, i) => (
          <button
            key={`${md.blobId}-${i}`}
            type="button"
            data-post-open
            data-post-slide={i}
            onClick={onOpen}
            aria-label={t('moments.open', { name })}
            className="relative shrink-0 w-full h-full snap-center p-0 min-h-0 min-w-0 overflow-hidden"
          >
            <SlideBody media={md} on={i <= at + 1} />
          </button>
        ))}
      </div>
      {n > 1 && (
        <>
          <span data-post-count className="absolute top-3 right-7 h-7 px-2.5 rounded-full flex items-center text-footnote text-white tnum pointer-events-none" style={{ background: 'var(--np-moment-label)' }}>
            {t('momentsF.slide', { i: at + 1, n })}
          </span>
          <div data-post-dots aria-hidden className="pt-2 flex justify-center gap-1.5">
            {m.media.map((md, i) => (
              <span key={`${md.blobId}-${i}`} className={`w-1.5 h-1.5 rounded-full ${i === at ? 'bg-ink' : 'bg-line-strong'}`} />
            ))}
          </div>
        </>
      )}
    </div>
  );
};

/** Пост, що побув на екрані (≥60 % і 0,8 с), — побачений: автор бачить це у «Хто бачив». */
function useSeenWhenShown(ref: React.RefObject<HTMLElement>, m: Moment) {
  const markSeen = useMoments((s) => s.markSeen);
  useEffect(() => {
    const el = ref.current;
    if (!el || m.mine || m.seen || typeof IntersectionObserver === 'undefined') return;
    let timer: number | undefined;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e && e.isIntersecting && e.intersectionRatio >= 0.6) {
          if (timer === undefined) timer = window.setTimeout(() => void markSeen(m.id).catch(() => undefined), 800);
        } else if (timer !== undefined) {
          window.clearTimeout(timer);
          timer = undefined;
        }
      },
      { threshold: [0, 0.6] },
    );
    io.observe(el);
    return () => {
      io.disconnect();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [ref, m.id, m.mine, m.seen, markSeen]);
}

const FeedPost: React.FC<{ m: Moment; name: string; avatar?: string; circle?: RealCircle | null; onOpen: () => void; onShare?: () => void; onInsights?: () => void; onEdit?: () => void }> = ({ m, name, avatar, circle, onOpen, onShare, onInsights, onEdit }) => {
  const { t } = useTranslation();
  const react = useMoments((s) => s.react);
  const ref = useRef<HTMLLIElement>(null);
  useSeenWhenShown(ref, m);
  const liked = m.myReaction === '❤️';
  const toggle = () => {
    void react(m.id, liked ? '' : '❤️').catch(() => {});
  };
  const sub = !m.mine && circle ? circleLabel(t, circle) : audienceLine(t, m);
  return (
    <li ref={ref} data-feed-post={m.id} data-seen={m.seen ? 'true' : 'false'} className="pb-2">
      <div className="flex items-center gap-3 px-4 py-2">
        <Avatar src={avatar} name={name} tone={m.mine ? null : m.fromId} className="w-10 h-10 text-callout" />
        <div data-post-author className="flex-1 min-w-0">
          <p className="truncate text-headline text-ink">{name}</p>
          <p className="truncate text-footnote text-ink-3">
            <span data-post-time className="tnum">{agoLabel(t, m.atMs)}</span>
            {' · '}
            <span data-post-circle>{sub}</span>
          </p>
        </div>
        {onEdit && (
          <button type="button" data-post-edit aria-label={t('momentsF.edit')} onClick={onEdit} className="w-11 h-11 min-h-0 min-w-0 -mr-2 rounded-full flex items-center justify-center text-ink-2">
            <Pencil className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          </button>
        )}
      </div>
      {m.media.length > 0 && <Carousel m={m} name={name} onOpen={onOpen} />}
      {m.text &&
        (m.media.length === 0 && (m.tone ?? 0) > 0 ? (
          <button type="button" data-post-open data-post-tone={m.tone} onClick={onOpen} aria-label={t('moments.open', { name })} className="block w-[calc(100%-32px)] min-h-0 min-w-0 mx-4 my-1 px-5 py-8 rounded-[16px] text-left" style={toneStyle(m.tone)}>
            <span data-post-caption className={`block ${m.text.length <= 140 ? 'text-title-1 font-normal' : 'text-body'} whitespace-pre-wrap break-words`}>{m.text}</span>
          </button>
        ) : m.media.length === 0 ? (
          <button type="button" data-post-open onClick={onOpen} aria-label={t('moments.open', { name })} className="block w-full min-h-0 min-w-0 px-4 py-1 text-left">
            <span data-post-caption className={`block ${m.text.length <= 140 ? 'text-title-1 font-normal' : 'text-body'} text-ink whitespace-pre-wrap break-words`}>{m.text}</span>
          </button>
        ) : (
          <p data-post-caption className="px-4 pt-2 text-body text-ink whitespace-pre-wrap break-words">{m.text}</p>
        ))}
      <div className="flex items-center gap-1 px-2 pt-1">
        <button type="button" data-post-react aria-pressed={liked} aria-label={t('momentsR.like')} onClick={toggle} className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink">
          <Heart className="w-6 h-6" strokeWidth={1.75} fill={liked ? 'var(--ph-accent)' : 'none'} style={liked ? { color: 'var(--ph-accent)' } : undefined} aria-hidden />
        </button>
        {onShare && (
          <button type="button" data-post-share aria-label={t('momentsR.sendTo')} onClick={onShare} className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink">
            <Send className="w-6 h-6" strokeWidth={1.75} aria-hidden />
          </button>
        )}
        {onInsights && (
          <button type="button" data-post-insights onClick={onInsights} className="ml-auto h-11 min-h-0 min-w-0 px-3 mr-2 rounded-full flex items-center gap-2 text-callout text-ink">
            <Eye className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            <span className="tnum">{t('moments.seenCount', { n: m.viewers.length })}</span>
          </button>
        )}
      </div>
    </li>
  );
};

const EmptyArt: React.FC = () => (
  <svg data-moments-art viewBox="0 0 220 150" className="w-[220px] h-[150px]" aria-hidden>
    <g transform="rotate(-8 60 80)"><rect x="24" y="30" width="72" height="96" rx="10" fill="var(--ph-raised)" /><path d="M24 98l26-30 20 22 26-18v50a10 10 0 0 1-10 10H34a10 10 0 0 1-10-10z" fill="var(--ph-line-strong)" /></g>
    <g transform="rotate(8 160 80)"><rect x="124" y="30" width="72" height="96" rx="10" fill="var(--ph-raised)" /><circle cx="160" cy="62" r="10" fill="var(--ph-line-strong)" /></g>
    <rect x="74" y="16" width="72" height="104" rx="10" fill="var(--ph-canvas)" stroke="var(--ph-line-strong)" strokeWidth="2" />
    <path d="M74 96l24-26 18 20 30-24v40a10 10 0 0 1-10 10H84a10 10 0 0 1-10-10z" fill="var(--ph-raised)" />
  </svg>
);

const sectionTitle = (t: T, circle: RealCircle | null) => (circle ? circleLabel(t, circle) : t('momentsF.others'));

const FeedChips: React.FC<{ circles: RealCircle[]; filter: FeedFilter; onPick: (f: FeedFilter) => void }> = ({ circles, filter, onPick }) => {
  const { t } = useTranslation();
  const keys: FeedFilter[] = ['all', ...circles];
  return (
    <div data-feed-chips role="tablist" aria-label={t('momentsF.filter')} className="flex items-center gap-1.5 overflow-x-auto no-scrollbar px-4">
      {keys.map((k) => {
        const on = k === filter;
        return (
          <FilterChip key={k} active={on} data-feed-chip={k} onClick={() => onPick(k)}>
            {k === 'all' ? t('momentsF.all') : circleLabel(t, k)}
          </FilterChip>
        );
      })}
    </div>
  );
};

/** Головна стрічка: чипи кіл, нове за колами, свої, «Усе переглянуто», «Старіші». */
export const MomentsFeed: React.FC<{ onOpenChat?: (chatId: string) => void; onCompose?: () => void }> = ({ onOpenChat, onCompose }) => {
  const { t, locale } = useTranslation();
  const list = useMoments((s) => s.list);
  const chats = useMessengerStore((s) => s.chats);
  const meAvatar = useMessengerStore((s) => s.currentUser.avatar);
  const [viewing, setViewing] = useState<string | null>(null);
  const [peopleOpen, setPeopleOpen] = useState(false);
  const [insight, setInsight] = useState<string | null>(null);
  const [sharing, setSharing] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [filter, setFilter] = useState<FeedFilter>('all');
  const [showOlder, setShowOlder] = useState(false);
  const byId = useMemo(() => new Map(momentPeople(chats).map((p) => [p.id, p])), [chats]);
  const ringOf = useMemo(() => ringRankOf(chats), [chats]);

  useEffect(() => watchMoments(), []);

  const inScope = useMomentScope(chats);
  const scoped = useMemo(() => (list ?? []).filter(inScope), [list, inScope]);
  const known = useRef<Set<string>>(new Set());
  const freshSet = useRef<Set<string>>(new Set());
  const fresh = useMemo(() => {
    for (const m of scoped) {
      if (known.current.has(m.id)) continue;
      known.current.add(m.id);
      if (!m.mine && !m.seen) freshSet.current.add(m.id);
    }
    return new Set(freshSet.current);
  }, [scoped]);
  const model = useMemo(() => buildFeed(scoped, ringOf, { filter, freshIds: fresh }), [scoped, ringOf, filter, fresh]);
  const hasFresh = model.fresh.length > 0;
  const olderOpen = showOlder || !hasFresh;
  const olderCount = model.older.reduce((n, s) => n + s.posts.length, 0);
  const ordered = useMemo(
    () => [...model.fresh.flatMap((s) => s.posts), ...model.mine, ...(olderOpen ? model.older.flatMap((s) => s.posts) : [])],
    [model, olderOpen],
  );
  const nameOf = (m: Moment) => (m.mine ? t('moments.you') : byId.get(m.fromId)?.name || m.fromName || t('chatList.unnamed'));
  const circleOf = (m: Moment): RealCircle | null => {
    const r = ringOf(m.fromId);
    return r < CIRCLES.length ? CIRCLES[r] : null;
  };

  if (scoped.length === 0) {
    return (
      <div data-moments-feed data-empty className="px-8 pt-[8vh] pb-10 flex flex-col items-center text-center gap-3">
        <EmptyArt />
        <p className="mt-4 text-title-2 text-ink">{t('momentsR.empty')}</p>
        <p className="text-subhead font-normal text-ink-3">{t('momentsR.emptyLine')}</p>
        {onCompose && (
          <button type="button" data-feed-first onClick={onCompose} className="mt-4 w-full max-w-[320px] h-12 rounded-full bg-accent text-on-accent text-headline inline-flex items-center justify-center gap-2">
            <Plus className="w-5 h-5" strokeWidth={2} aria-hidden />
            {t('momentsR.first')}
          </button>
        )}
        <button type="button" data-feed-add-people onClick={() => setPeopleOpen(true)} className="w-full max-w-[320px] h-12 rounded-full bg-raised text-ink text-body inline-flex items-center justify-center gap-2">
          <UserPlus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
          {t('momentsR.addPeople')}
        </button>
        {peopleOpen && <PeopleScreen onClose={() => setPeopleOpen(false)} />}
      </div>
    );
  }

  const post = (m: Moment) => (
    <FeedPost
      key={m.id}
      m={m}
      name={nameOf(m)}
      avatar={m.mine ? meAvatar || undefined : byId.get(m.fromId)?.avatar}
      circle={m.mine ? null : circleOf(m)}
      onOpen={() => setViewing(m.id)}
      onInsights={m.mine ? () => setInsight(m.id) : undefined}
      onShare={canShareMoment(m) ? () => setSharing(m.id) : undefined}
      onEdit={m.mine ? () => setEditing(m.id) : undefined}
    />
  );
  const section = (s: FeedSection, isNew: boolean) => (
    <div key={`${isNew ? 'n' : 'o'}-${s.circle ?? 'other'}`} data-feed-section={s.circle ?? 'other'} data-feed-new={isNew ? 'true' : undefined}>
      <h3 className="px-4 pt-4 pb-1 text-footnote text-ink-3">
        {sectionTitle(t, s.circle)}
        {isNew && ` · ${t(pluralKey(locale, s.posts.length, 'momentsF.newN'), { n: s.posts.length })}`}
      </h3>
      <ul>{s.posts.map(post)}</ul>
    </div>
  );
  const theirs = model.fresh.length + model.older.length > 0;
  const endCard = (withOlder: boolean) => (
    <div data-feed-end className="mx-4 mt-4 mb-2 rounded-[16px] border border-line-strong py-6 flex flex-col items-center gap-2 text-center">
      <Check className="w-6 h-6 text-success" strokeWidth={2} aria-hidden />
      <p className="text-headline text-ink">{t('momentsR.allSeen')}</p>
      {withOlder && (
        <button type="button" data-feed-older onClick={() => setShowOlder(true)} className="mt-2 h-11 min-h-0 px-6 rounded-full bg-raised text-callout text-ink tnum">
          {t('momentsF.olderN', { n: olderCount })}
        </button>
      )}
    </div>
  );

  return (
    <section data-moments-feed data-filter={filter} aria-label={t('moments.feedTitle')} className="pb-4">
      {model.circles.length >= 2 && (
        <FeedChips
          circles={model.circles}
          filter={filter}
          onPick={(f) => {
            setFilter(f);
            setShowOlder(false);
          }}
        />
      )}
      {model.fresh.map((s) => section(s, true))}
      {model.mine.length > 0 && (
        <div data-feed-section="me">
          <h3 className="px-4 pt-4 pb-1 text-footnote text-ink-3">{t('momentsF.you')}</h3>
          <ul>{model.mine.map(post)}</ul>
        </div>
      )}
      {hasFresh && endCard(!showOlder && olderCount > 0)}
      {olderOpen && model.older.map((s) => section(s, false))}
      {!hasFresh && theirs && endCard(false)}
      {insight && ordered.find((m) => m.id === insight) && <MomentInsights m={ordered.find((m) => m.id === insight)!} avatarOf={(id) => byId.get(id)?.avatar} onClose={() => setInsight(null)} />}
      {editing && ordered.find((m) => m.id === editing) && (
        <MomentComposer file={null} editing={ordered.find((m) => m.id === editing)!} people={[...byId.values()]} chats={chats} onClose={() => setEditing(null)} />
      )}
      {sharing && ordered.find((m) => m.id === sharing) && <MomentShare m={ordered.find((m) => m.id === sharing)!} onClose={() => setSharing(null)} />}
      {viewing && (
        <MomentViewer
          moments={ordered}
          startId={viewing}
          nameOf={nameOf}
          avatarOf={(m) => byId.get(m.fromId)?.avatar}
          chatOf={(m) => byId.get(m.fromId)?.chatId ?? chats.find((c) => c.peerNodeId === m.fromId)?.id}
          onOpenChat={(id) => {
            setViewing(null);
            if (onOpenChat) onOpenChat(id);
            else useMessengerStore.getState().setActiveChat(id);
          }}
          onClose={() => setViewing(null)}
        />
      )}
    </section>
  );
};

export const MomentsFeedPage: React.FC<{ onClose: () => void; onOpenChat: (chatId: string) => void; onCompose?: () => void }> = ({ onClose, onOpenChat, onCompose }) => {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const stretchRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(stretchRef);
  const { t } = useTranslation();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:feed', true, () => closeRef.current()), []);
  return createPortal(
    <div
      data-moments-page
      role="dialog"
      aria-modal="true"
      aria-label={t('moments.feedTitle')}
      className="fixed inset-0 z-[60] bg-canvas flex flex-col"
      style={{ backgroundColor: 'var(--ph-canvas)', border: 0, boxShadow: 'none' }}
    >
      <TopBar title={t('moments.feedTitle')} onBack={onClose} backLabel={t('moments.back')} backProps={{ 'data-moments-back': true }}
        safeTop
        trailing={
          <span className="ml-auto flex items-center">
            <button type="button" data-feed-settings aria-label={t('momentsR.settings')} onClick={() => setSettingsOpen(true)} className="w-11 h-11 min-h-0 min-w-0 rounded-full flex items-center justify-center text-ink">
              <Settings className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </button>
            {onCompose && (
              <button type="button" data-feed-compose aria-label={t('moments.newTitle')} onClick={onCompose} className="w-11 h-11 min-h-0 min-w-0 mr-2 rounded-full flex items-center justify-center bg-ink text-canvas">
                <Plus className="w-6 h-6" strokeWidth={2} aria-hidden />
              </button>
            )}
          </span>
        }
      />
      <div ref={stretchRef} className="flex-1 min-h-0 overflow-y-auto">
        <MomentsFeed
          onCompose={onCompose}
          onOpenChat={(id) => {
            onClose();
            onOpenChat(id);
          }}
        />
      </div>
      {settingsOpen && <MomentsSettings onClose={() => setSettingsOpen(false)} />}
      <MomentUndoToast />
    </div>,
    document.body,
  );
};

export default MomentsFeed;
