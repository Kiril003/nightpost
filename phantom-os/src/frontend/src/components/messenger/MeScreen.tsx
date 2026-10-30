/**
 * «Я» — особистий центр: фото, імʼя, @нік і «про себе»; як мене знаходять;
 * мої пристрої; що PHANTOM про мене памʼятає; тиша й хто бачить місце;
 * мережа одним рядком; налаштування внизу.
 *
 * Кожна частина малюється лише з того, що справді віддав міст або вузол
 * (джерела — у `MeSections`). Немає відповіді — немає частини.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { Suspense, lazy, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Camera, ChevronRight, FileText, Flag, LifeBuoy, Lock, Phone, Settings, SunMoon, Users, X } from 'lucide-react';
import { openCallSettings } from '../../services/callSettingsRoute';
import { CallLogSheet } from './CallLogSheet';
import { MyReports, openMyReports } from '../support/MyReports';
import { HelpCentre } from '../support/HelpCentre';
import { listPages } from '../../services/builder/pages';
import { useSupportStore } from '../../stores/supportStore';
import { Avatar } from './Avatar';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { messengerApi } from '../../services/messengerApi';
import { isMachineName } from '../../services/selfName';
import { minutesLeft, onWhereChanged, whereApi, wherePeerOf } from '../../services/peerWhere';
import { readThemeMode, THEME_MODE_EVENT } from '../../services/theme';
import { QuietNightsCard } from './QuietNights';
import { FormSheet } from './FormSheet';
import { HandleSheet, useMyHandle } from './HandleSheet';
import { PhotoCropSheet } from './PhotoCropSheet';
import { openImage } from '../../services/avatarCompress';
import { useLiving } from '../../services/livingPulse';
import { FindMe, MyDevices, MyMemory, useNetworkLine, useOwnDevices } from './MeSections';
import { PhantomRows } from './PhantomFeed';
import { MyIntents } from './MyIntents';
import { OfflineEntry } from './OfflineSetup';
import { MeGroup, MeTile } from './MeRow';
import { PointsCard } from './PointsCard';
import { GiftShelf, GiftViewSheet } from './gifts/GiftShelf';
import { receivedGifts, type ShelfGift } from './gifts/giftCatalogue';
import { canHaveCircle } from './circles';
import { CIRCLES, CIRCLE_KEY } from './todayFormat';
import { LIST_FILTER_EVENT } from './ChatTypeChips';
import { useEdgeStretch } from '../../feel/useEdgeStretch';
import { MediaLightboxModal } from './MediaLightboxModal';
import { ProfilesHost } from './ProfilesSheet';
import { ProfileFan } from './ProfileFan';
import { PeopleScreen } from './PeopleScreen';
import { BusinessEntry } from './directions/BusinessEntry';
import { useRequestsStore } from '../../stores/requestsStore';
import { MomentsProfile } from './MomentsProfile';
import { StaffWork } from './StaffWork';
import { momentHighlights, type Moment as HighlightMoment } from '../../services/moments';

/** Конструктор сторінок — окремим шматком: блоки сторінки не змішуються з картками розмов у бандлі. */
const BuilderHub = lazy(() => import('../builder/BuilderPages').then((m) => ({ default: m.BuilderHub })));

const CallSettingsSheet = lazy(() => import('./CallSettingsSheet').then((m) => ({ default: m.CallSettingsSheet })));

const HEAD_CIRCLES = ['close', 'friends', 'work'] as const;

const COVER_PATHS: readonly string[] = (() => {
  const [a, b, c] = [0.7, 1.9, 3.1];
  const n = 24;
  return Array.from({ length: 14 }, (_, idx) => {
    const k = idx + 1;
    const r = k * 19;
    const pts = Array.from({ length: n }, (__, i) => {
      const t = (i / n) * Math.PI * 2;
      const w = r * (1 + 0.16 * Math.sin(2 * t + a + k * 0.12) + 0.09 * Math.sin(3 * t + b - k * 0.2) + 0.05 * Math.sin(5 * t + c));
      return [310 + Math.cos(t) * w * 1.25, 30 + Math.sin(t) * w] as const;
    });
    let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
    for (let i = 0; i < n; i++) {
      const p0 = pts[(i + n - 1) % n];
      const p1 = pts[i];
      const p2 = pts[(i + 1) % n];
      const p3 = pts[(i + 2) % n];
      const c1x = p1[0] + (p2[0] - p0[0]) / 6;
      const c1y = p1[1] + (p2[1] - p0[1]) / 6;
      const c2x = p2[0] - (p3[0] - p1[0]) / 6;
      const c2y = p2[1] - (p3[1] - p1[1]) / 6;
      d += `C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
    }
    return `${d}Z`;
  });
})();

export type MePage = 'profile' | 'code' | 'invite' | 'knows' | 'prefs' | 'look' | 'privacy' | 'handle' | 'offline' | 'feed';

interface Watcher {
  id: string;
  name: string;
  avatar?: string;
  untilMs: number | null;
}

/** Кому я зараз показую своє місце — з PeerWhere кожної розмови вдвох. */
const useWatchers = () => {
  const chats = useMessengerStore((s) => s.chats);
  const faces = useLiving((s) => s.faces);
  const [watchers, setWatchers] = useState<Watcher[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const [tick, setTick] = useState(0);

  const peers: Array<{ id: string; name: string; avatar?: string }> = [];
  for (const c of chats) {
    const p = wherePeerOf(c);
    if (p) peers.push({ ...p, avatar: faces[p.id]?.avatar || c.avatar });
  }
  const peersKey = peers.map((p) => p.id).join('|');

  useEffect(() => onWhereChanged(() => setTick((t) => t + 1)), []);

  useEffect(() => {
    if (peers.length === 0) {
      setWatchers([]);
      return;
    }
    let alive = true;
    void Promise.allSettled(peers.map((p) => whereApi.get(p.id))).then((rows) => {
      if (!alive) return;
      const found: Watcher[] = [];
      rows.forEach((r, i) => {
        if (r.status !== 'fulfilled' || !r.value.mine) return;
        found.push({ id: peers[i].id, name: peers[i].name, avatar: peers[i].avatar, untilMs: r.value.mine.until_ms });
      });
      setWatchers(found);
      setNow(Date.now());
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [peersKey, tick]);

  useEffect(() => {
    if (watchers.length === 0) return;
    const t = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(t);
  }, [watchers.length]);

  const stop = async (id: string) => {
    soundFx.playTap();
    try {
      const s = await whereApi.stop(id);
      if (!s.mine) setWatchers((w) => w.filter((x) => x.id !== id));
    } catch {
      /* лишаємо рядок — телефон не зупинив */
    }
  };

  const live = watchers.filter((w) => w.untilMs === null || w.untilMs > now);
  return { watchers: live, now, stop };
};

export const MeScreen: React.FC<{ onClose: () => void; onOpen: (page: MePage) => void; handlesSheet?: boolean }> = ({
  onClose,
  onOpen,
  handlesSheet,
}) => {
  const stretchRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(stretchRef);
  const { t } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const chats = useMessengerStore((s) => s.chats);
  const gifts = React.useMemo(() => receivedGifts(chats, me.id), [chats, me.id]);
  const [giftOpen, setGiftOpen] = useState<ShelfGift | null>(null);
  const { watchers, now, stop } = useWatchers();
  const handle = useMyHandle();
  const devices = useOwnDevices();
  const network = useNetworkLine(onClose);
  const [handleOpen, setHandleOpen] = useState(false);

  const open = (page: MePage) => {
    soundFx.playTap();
    if (page === 'handle' && !handlesSheet) setHandleOpen(true);
    onOpen(page);
  };
  const [themeMode, setThemeMode] = useState(readThemeMode);
  useEffect(() => {
    const sync = () => setThemeMode(readThemeMode());
    window.addEventListener(THEME_MODE_EVENT, sync);
    return () => window.removeEventListener(THEME_MODE_EVENT, sync);
  }, []);
  const lookValue =
    themeMode === 'day' ? t('meSections.themeDay') : themeMode === 'night' ? t('meSections.themeNight') : t('meSections.themeAuto');
  const allCircleCounts = CIRCLES.map((id) => ({
    id,
    n: chats.filter((c) => canHaveCircle(c) && (c.circles?.includes(id) || c.circle === id)).length,
  }));
  const circleCounts = allCircleCounts.filter(
    (x) => (HEAD_CIRCLES as readonly string[]).includes(x.id) || x.n > 0,
  );
  const bioText = (me.bio?.trim() || '').replace(/\s+/g, ' ');
  const statusLine = (me.status?.trim() || '').replace(/\s+/g, ' ');
  const about = bioText || statusLine;
  const unnamed = isMachineName(me.name);
  const [highlights, setHighlights] = useState<HighlightMoment[]>([]);
  useEffect(() => {
    let alive = true;
    momentHighlights('me')
      .then((rows) => {
        if (!alive) return;
        const next = Array.isArray(rows) ? rows : [];
        setHighlights((prev) => (prev.length === 0 && next.length === 0 ? prev : next));
      })
      .catch(() => {
        if (!alive) return;
        setHighlights((prev) => (prev.length === 0 ? prev : []));
      });
    return () => {
      alive = false;
    };
  }, []);
  const [naming, setNaming] = useState(false);
  const [draft, setDraft] = useState('');
  const [nameBusy, setNameBusy] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const [picked, setPicked] = useState<HTMLImageElement | null>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);
  const [callsOpen, setCallsOpen] = useState(false);
  const [callLogOpen, setCallLogOpen] = useState(false);
  const [peopleOpen, setPeopleOpen] = useState(false);
  const [momentsOpen, setMomentsOpen] = useState(false);
  const requestCount = useRequestsStore((s) => s.incoming.length);
  useEffect(() => {
    void useRequestsStore.getState().load();
  }, []);
  const [viewing, setViewing] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [pagesOpen, setPagesOpen] = useState(false);
  const pagesCount = listPages().length;
  const myReportsOpen = useSupportStore((s) => s.myReportsOpen);
  const closeMyReports = useSupportStore((s) => s.closeMyReports);

  const pickPhoto = () => {
    soundFx.playTap();
    setPhotoError(null);
    picker.current?.click();
  };
  const onPicked = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    const opened = await openImage(file);
    if (opened.ok) setPicked(opened.img);
    else setPhotoError(t('meScreen.notPhoto'));
  };

  const saveName = async () => {
    const next = draft.trim();
    if (!next) return;
    setNameBusy(true);
    setNameError(null);
    try {
      const id = await messengerApi.renameSelf(next);
      const given = String((id?.bundle as { name?: unknown } | undefined)?.name ?? '').trim() || next;
      useMessengerStore.getState().updateCurrentUser({ name: given });
      setNaming(false);
    } catch {
      setNameError(t('meScreen.nameRefused'));
    } finally {
      setNameBusy(false);
    }
  };

  const writeTo = (chatId: string) => {
    useMessengerStore.getState().setActiveChat(chatId);
    onClose();
  };

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('meScreen.profile')}
      data-testid="me-screen"
      ref={stretchRef}
      className="f-dialog f-dialog-canvas fixed inset-0 z-[80] bg-canvas text-ink font-sans overflow-y-auto no-scrollbar ph-screen-in"
    >
      <div className="relative w-full max-w-[560px] mx-auto pb-[calc(var(--sab)+24px)]">
        <div data-testid="me-cover" aria-hidden className="absolute inset-x-0 top-0 h-[calc(var(--sat)+144px)] bg-cover overflow-hidden">
          <svg
            className="absolute inset-0 w-full h-full text-cover-line"
            viewBox="0 0 390 176"
            preserveAspectRatio="xMidYMid slice"
            fill="none"
            stroke="currentColor"
            strokeWidth={1}
          >
            {COVER_PATHS.map((d, i) => (
              <path key={i} d={d} />
            ))}
          </svg>
        </div>

        <button
          type="button"
          onClick={onClose}
          aria-label={t('meScreen.close')}
          className="absolute right-3 top-[calc(var(--sat)+8px)] z-[3] w-11 h-11 min-h-0 min-w-0 flex items-center justify-center rounded-full bg-canvas text-ink active:bg-raised"
        >
          <X className="w-5 h-5" strokeWidth={1.75} />
        </button>

        {/* Голова профілю у вигляді D: обкладинка, аватар 96 на межі, імʼя, @нік і кола. */}
        <header className="px-4 flex flex-col items-center text-center">
          <div className="relative z-[2] mt-[calc(var(--sat)+64px)] shrink-0">
            <ProfileFan>
              <button
                type="button"
                data-testid="me-photo"
                onClick={me.avatar ? () => { soundFx.playTap(); setViewing(true); } : pickPhoto}
                className="block rounded-full ring-4 ring-canvas"
                aria-label={me.avatar ? t('meScreen.viewPhoto') : t('meScreen.addPhoto')}
              >
                <Avatar
                  src={me.avatar}
                  name={unnamed ? t('meScreen.initial') : me.name}
                  tone={me.id || me.name}
                  radius="rounded-full"
                  className="w-[96px] h-[96px] text-[38px]"
                />
              </button>
            </ProfileFan>
            <button
              type="button"
              data-testid="me-photo-change"
              onClick={pickPhoto}
              aria-label={me.avatar ? t('meScreen.changePhoto') : t('meScreen.addPhoto')}
              className="absolute -right-2 -bottom-2 w-11 h-11 min-h-0 min-w-0 flex items-center justify-center"
            >
              <span className="w-8 h-8 rounded-full bg-raised ring-2 ring-canvas flex items-center justify-center">
                <Camera className="w-4 h-4 text-ink" strokeWidth={1.75} aria-hidden="true" />
              </span>
            </button>
          </div>

          {unnamed ? (
            naming ? (
              <form
                data-testid="me-head-unnamed"
                className="mt-4 w-full max-w-[320px] flex items-center gap-2"
                onSubmit={(e) => {
                  e.preventDefault();
                  void saveName();
                }}
              >
                <input
                  autoFocus
                  value={draft}
                  maxLength={64}
                  onChange={(e) => setDraft(e.target.value)}
                  aria-label={t('msgr.add.yourName')}
                  placeholder={t('meScreen.namePlaceholder')}
                  className="flex-1 min-w-0 h-11 px-4 rounded-control bg-raised border border-line-strong text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
                />
                <button
                  type="submit"
                  disabled={nameBusy || !draft.trim()}
                  className="h-11 min-h-0 px-4 rounded-control bg-accent text-on-accent text-headline hover:bg-accent-hover disabled:opacity-40"
                >
                  {t('msgr.add.done')}
                </button>
              </form>
            ) : (
              <button
                type="button"
                data-testid="me-ask-name"
                onClick={() => {
                  soundFx.playTap();
                  setDraft('');
                  setNaming(true);
                }}
                className="mt-3 min-h-11 px-3 text-title-1 text-ink"
              >
                {t('meScreen.askName')}
              </button>
            )
          ) : (
            <button
              type="button"
              data-testid="me-head"
              onClick={() => open('profile')}
              aria-label={t('meScreen.editProfile')}
              className="mt-2 max-w-full min-h-11 px-3 rounded-control hover:bg-overlay/60 active:bg-overlay"
            >
              <span className="block truncate text-display text-ink">{me.name || t('msgr.add.unnamed')}</span>
            </button>
          )}

          {(handle.kind === 'set' || handle.kind === 'none') && (
            <button
              type="button"
              data-testid="me-handle"
              onClick={() => open('handle')}
              className="h-12 min-h-0 px-3 rounded-control text-body text-ink-2 hover:bg-overlay/60 active:bg-overlay"
            >
              {handle.kind === 'set' ? `@${handle.handle}` : t('meScreen.setHandle')}
            </button>
          )}

          {bioText && statusLine && statusLine !== bioText && (
            <p data-testid="me-status" className="mt-1 max-w-[320px] text-callout text-ink-2 line-clamp-1">
              {statusLine}
            </p>
          )}

          {about && (
            <p data-testid="me-about" className="mt-1 max-w-[320px] text-callout text-ink-2 line-clamp-2">
              {about}
            </p>
          )}

          <ProfilesHost />

          {circleCounts.some((x) => x.n > 0) && (
            <div data-testid="me-circles" className="mt-3 flex flex-wrap justify-center gap-2">
              {circleCounts.map(({ id, n }) => (
                <span
                  key={id}
                  data-testid="me-circle"
                  role={n > 0 ? 'button' : undefined}
                  tabIndex={n > 0 ? 0 : undefined}
                  onClick={
                    n > 0
                      ? () => {
                          soundFx.playTap();
                          window.dispatchEvent(new CustomEvent(LIST_FILTER_EVENT, { detail: id }));
                          onClose();
                        }
                      : undefined
                  }
                  className={`h-8 px-3 rounded-full border border-line-strong flex items-center gap-1.5 text-subhead text-ink tabular-nums ${
                    n > 0 ? 'cursor-pointer hover:bg-overlay/60 active:bg-overlay' : ''
                  }`}
                >
                  {t(CIRCLE_KEY[id])} <b className="font-semibold text-ink-3">{n}</b>
                </span>
              ))}
            </div>
          )}
        </header>
        {nameError && <p className="px-4 pt-2 text-center text-callout text-danger">{nameError}</p>}
        {photoError && <p role="status" className="px-4 pt-2 text-center text-callout text-warning">{photoError}</p>}
        {viewing && me.avatar && (
          <MediaLightboxModal isOpen onClose={() => setViewing(false)} mediaUrl={me.avatar} mediaTitle={me.name} />
        )}
        <input ref={picker} type="file" accept="image/*" onChange={(e) => void onPicked(e)} className="hidden" data-testid="me-photo-input" />
        {picked && (
          <PhotoCropSheet
            img={picked}
            onClose={() => setPicked(null)}
            onDone={(avatar) => {
              setPicked(null);
              useMessengerStore.getState().updateCurrentUser({ avatar });
            }}
          />
        )}

        <FindMe handle={handle.kind === 'set' ? handle.handle : null} onEdit={() => open('profile')} />

        <button
          type="button"
          data-testid="me-moments"
          onClick={() => {
            soundFx.playTap();
            setMomentsOpen(true);
          }}
          className="mx-4 mt-4 w-[calc(100%-32px)] min-h-14 px-4 rounded-[16px] bg-raised flex items-center gap-3 text-left"
        >
          <span className="flex-1 text-body text-ink">{t('momentsR.mine')}</span>
          <ChevronRight className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
        </button>

        {highlights.length > 0 && (
          <section data-testid="me-highlights" className="mx-4 mt-4 rounded-[16px] bg-raised px-4 py-3">
            <h3 className="text-caption text-ink-3">{t('moments.title')}</h3>
            <div className="mt-2 flex gap-2 overflow-x-auto no-scrollbar">
              {highlights.map((m) => (
                <span
                  key={m.id}
                  data-testid={`me-highlight-${m.id}`}
                  className="shrink-0 h-12 px-3 rounded-control bg-overlay flex items-center text-footnote text-ink max-w-[160px] truncate"
                >
                  {m.text || t('moments.kept')}
                </span>
              ))}
            </div>
          </section>
        )}

        <PointsCard className="mx-4 mt-4" />

        <section data-testid="me-gifts" className="mx-4 mt-4 rounded-[16px] bg-raised px-4 py-3">
          <h3 data-testid="me-gifts-title" className="text-caption text-ink-3">
            {gifts.length > 0 ? `${t('gifts.shelf')} · ${gifts.length}` : t('gifts.shelf')}
          </h3>
          {gifts.length > 0 ? (
            <div className="mt-2.5 -mx-1.5">
              <GiftShelf
                items={gifts}
                onOpen={(g) => {
                  soundFx.playTap();
                  setGiftOpen(g);
                }}
              />
              <GiftViewSheet
                gift={giftOpen}
                onClose={() => setGiftOpen(null)}
                onChat={(g) => {
                  soundFx.playTap();
                  setGiftOpen(null);
                  useMessengerStore.getState().openChatAtMessage(g.chatId, g.msgId);
                  onClose();
                }}
              />
            </div>
          ) : (
            <p data-testid="gift-shelf-empty" className="mt-1 text-body text-ink-2">
              {t('gifts.shelfEmpty')}
            </p>
          )}
        </section>

        <div data-testid="me-tiles" className="mx-4 mt-5 grid grid-cols-2 gap-2.5">
          <MeTile
            testId="me-tile-look"
            icon={SunMoon}
            title={t('meSections.look')}
            value={lookValue}
            onClick={() => open('look')}
          />
          <MeTile
            testId="me-tile-privacy"
            icon={Lock}
            title={t('meSections.privacy')}
            value={watchers.length > 0 ? t('meScreen.whoSees') : undefined}
            onClick={() => open('privacy')}
          />
          <MeTile
            testId="me-tile-people"
            wide
            icon={Users}
            title={t('meSections.myPeople')}
            value={requestCount > 0 ? `${t('people.requests')} · ${requestCount}` : network.line || undefined}
            onClick={() => {
              soundFx.playTap();
              setPeopleOpen(true);
            }}
          />
        </div>

        <MyDevices devices={devices} onWrite={writeTo} />

        <MyMemory onAll={() => open('knows')} />

        <div className="mt-4">
          {watchers.length > 0 && (
            <section data-testid="me-watchers" className="mx-4 mt-2 rounded-[16px] bg-raised">
              <h3 className="px-4 pt-3 pb-1 text-caption text-ink-3">{t('meScreen.whoSees')}</h3>
              {watchers.map((w, i) => (
                <React.Fragment key={w.id}>
                  {i > 0 && <div className="ml-4 h-px bg-line" />}
                  <div className="flex items-center gap-3 py-2 pl-4 pr-2.5">
                    <Avatar src={w.avatar} name={w.name} tone={w.id} radius="rounded-full" className="w-10 h-10 text-subhead" />
                    <span className="flex-1 min-w-0">
                      <span className="block truncate text-headline text-ink">{w.name}</span>
                      <span className="block text-caption text-success">
                        {w.untilMs === null ? t('meScreen.untilOff') : minutesLeft(w.untilMs, now)}
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => void stop(w.id)}
                      className="h-11 min-h-0 px-4 rounded-control bg-overlay text-subhead text-ink active:bg-raised"
                    >
                      {t('meScreen.stop')}
                    </button>
                  </div>
                </React.Fragment>
              ))}
            </section>
          )}
          <QuietNightsCard />
        </div>

        {/* PHANTOM: що він робить і рука, що зупиняє все. Немає відповіді — немає частини. */}
        <PhantomRows onFeed={() => open('feed')} />

        <StaffWork />

        {/* Налаштування — один згрупований список на `raised` (§7.2): єдина картка екрана. */}
        <div className="mx-4 mt-6 rounded-[16px] bg-raised overflow-hidden">
          <BusinessEntry onChatOpened={onClose} />
          <div aria-hidden className="ml-12 h-px bg-line" />
          <button
            type="button"
            data-testid="me-prefs"
            onClick={() => open('prefs')}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
          >
            <Settings className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('settingsRoot.title')}</span>
            <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          </button>
          <div aria-hidden className="ml-12 h-px bg-line" />
          <button
            type="button"
            data-testid="me-calls"
            onClick={() => {
              soundFx.playTap();
              setCallLogOpen(true);
            }}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
          >
            <Phone className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('callsHub.title')}</span>
            <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          </button>
          <div aria-hidden className="ml-12 h-px bg-line" />
          <button
            type="button"
            data-testid="me-pages"
            onClick={() => {
              soundFx.playTap();
              setPagesOpen(true);
            }}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
          >
            <FileText className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('builder.title')}</span>
            {pagesCount > 0 && <span className="text-subhead text-ink-3 tabular-nums">{pagesCount}</span>}
            <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          </button>
          <div aria-hidden className="ml-12 h-px bg-line" />
          <button
            type="button"
            data-testid="me-help"
            onClick={() => {
              soundFx.playTap();
              setHelpOpen(true);
            }}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
          >
            <LifeBuoy className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('help.title')}</span>
            <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          </button>
          <div aria-hidden className="ml-12 h-px bg-line" />
          <button
            type="button"
            data-testid="me-my-reports"
            onClick={() => {
              soundFx.playTap();
              openMyReports();
            }}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
          >
            <Flag className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
            <span className="flex-1 text-body text-ink">{t('support.myReports.title')}</span>
            <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
          </button>
        </div>
        {peopleOpen && <PeopleScreen onClose={() => setPeopleOpen(false)} />}
        {momentsOpen && <MomentsProfile onClose={() => setMomentsOpen(false)} />}
        {callLogOpen && (
          <CallLogSheet
            onClose={() => setCallLogOpen(false)}
            onSettings={() => {
              setCallLogOpen(false);
              if (!openCallSettings()) setCallsOpen(true);
            }}
          />
        )}
        {callsOpen && (
          <Suspense fallback={null}>
            <CallSettingsSheet active={false} onClose={() => setCallsOpen(false)} />
          </Suspense>
        )}
        {pagesOpen && (
          <Suspense fallback={null}>
            <BuilderHub onClose={() => setPagesOpen(false)} />
          </Suspense>
        )}
        {helpOpen && <HelpCentre open onClose={() => setHelpOpen(false)} />}
        {myReportsOpen && <MyReports open={myReportsOpen} onClose={closeMyReports} />}
        {handleOpen && !handlesSheet && (
          <FormSheet open onClose={() => setHandleOpen(false)} title={t('phoneSettingsSheet.pageHandle')} testId="me-handle-sheet">
            <HandleSheet onDone={() => setHandleOpen(false)} />
          </FormSheet>
        )}

        {/* Без інтернету (розд. 07): рядки приходять із мосту телефона; на ПК їх немає — і групи теж. */}
        <MeGroup title={t('meScreen.offline')} testId="me-offline-group">
          <OfflineEntry onOpen={() => open('offline')} />
          <MyIntents onOpen={() => open('offline')} />
        </MeGroup>
        {/* «Офлайн-мапи» (рюкзак) і «Для розробника» (relay-fs) тут були, але
            ця «Я» — настільна (телефон іде в рідний профіль), а на ПК в бекенді
            немає ні /messenger/backpack, ні /messenger/relayfs: рядки або
            мовчали, або обіцяли те, чого компʼютер не вміє. Віднято (04.10). */}
      </div>
    </div>,
    document.body,
  );
};

export default MeScreen;
