/**
 * Екран дзвінка. Показує людину, стан одним рядком і великі кнопки — більше
 * нічого.
 *
 * 26.09, закон власника: «людині треба показувати щось гарне а не цю
 * нісенітницю». Звідси прибрано дороги ICE/STUN/TURN, кодек, кбіт/с, RTT,
 * назву сходинки звуку, лічильники рації, рядок про DTLS-SRTP і звірку
 * контакту. Рушій далі рахує все це чесно — для журналу; на екран його слова
 * потрапляють лише через `services/callWords.ts`, де кожна причина стає
 * одним людським реченням.
 *
 * Що лишилось із 05.09 і чому: картка не обіцяє нічого наперед (жодної
 * «захищено» над набором номера), причина завершення й зупинки доходить до
 * людини — але словами людини. Показу екрана, «підняти руку» і реакцій тут
 * нема, бо до співрозмовника вони не доїжджали.
 *
 * 08.10 (P0-5, D7 власника — «dark, premium, full-bleed»): картка завжди
 * нічна; відео співрозмовника — на весь екран, без відео — велике обличчя на
 * рівному полотні (`call/CallBackdrop`, 17.10 без світіння). Своє відео — віконце,
 * що тягнеться в будь-який кут (`call/SelfView`). Під іменем — таймер і
 * смужки якості з виміру (`call/QualityBars`), «через посередника», коли
 * пара йде через ретранслятор. Звук — у телефон, динамік чи навушники через
 * міст (`call/RoutePicker`); старий APK — та сама кнопка «Звук», що була.
 * Камера перевертається `replaceTrack`, без нових переговорів. Рух —
 * лише transform/opacity (`call/motion`).
 */

import React, { useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { Mic, MicOff, Video, VideoOff, Phone, PhoneOff, ChevronDown, Volume2, VolumeX, UserMinus, SwitchCamera, SlidersHorizontal, X } from 'lucide-react';
import { CallSettingsSheet, formatDataUsed } from './CallSettingsSheet';
import { callEngine } from '../../services/callEngine';
import { callNextLabel, nextEventFor } from '../../services/callHeader';
import { footLabel } from '../../services/callRoom';
import type { LegView } from '../../services/callRoom';
import { callStateLine, linkLine, roomFullLine } from '../../services/callWords';
import { mbhLabel, remoteTile } from '../../services/callView';
import { dayApi } from '../../services/dayApi';
import type { DayState } from '../../services/dayApi';
import { declineReplies, pick } from '../../services/declineReplies';
import { useLiving } from '../../services/livingPulse';
import { useMessengerStore } from '../../stores/messengerStore';
import { callCoversScreen, useCallChromeStore } from '../../stores/callChromeStore';
import { soundFx } from '../../utils/messengerSound';
import { Avatar } from './Avatar';
import { useTranslation } from '../../i18n/useTranslation';
import { CallButton, END, GLYPH } from './call/CallButton';
import { CallBackdrop, VideoScrim } from './call/CallBackdrop';
import { QualityBars } from './call/QualityBars';
import { RoutePicker } from './call/RoutePicker';
import { SelfView } from './call/SelfView';
import { useAudioRoutes } from './call/audioRoute';
import { useBreath, useRise } from './call/motion';

const clock = (ms: number): string => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60).toString().padStart(2, '0');
  const s = (total % 60).toString().padStart(2, '0');
  return h > 0 ? `${h}:${m}:${s}` : `${m}:${s}`;
};

const CallTimer: React.FC<{ startedAt: number | null }> = ({ startedAt }) => {
  const { t } = useTranslation();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (startedAt === null) return;
    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, [startedAt]);
  if (startedAt === null) return <span>{t('callOverlay.connecting')}</span>;
  return (
    <span data-call-timer style={{ fontVariantNumeric: 'tnum' }}>
      {clock(now - startedAt)}
    </span>
  );
};

/** Фото людини — з облич сітки, інакше з розмови. Немає — літера на тоні. */
function usePeerPhoto(peerNodeId: string | undefined): string | null {
  const face = useLiving((s) => (peerNodeId ? s.faces[peerNodeId]?.avatar : undefined));
  const chatAvatar = useMessengerStore((s) =>
    peerNodeId ? s.chats.find((c) => c.peerNodeId === peerNodeId)?.avatar : undefined,
  );
  return face || chatAvatar || null;
}

function useHasVideo(stream: MediaStream | null): boolean {
  const [has, setHas] = useState(false);
  useEffect(() => {
    const refresh = () => setHas((stream?.getVideoTracks() ?? []).some((t) => t.enabled));
    refresh();
    if (!stream) return;
    stream.addEventListener('addtrack', refresh);
    stream.addEventListener('removetrack', refresh);
    const id = setInterval(refresh, 500);
    return () => {
      stream.removeEventListener('addtrack', refresh);
      stream.removeEventListener('removetrack', refresh);
      clearInterval(id);
    };
  }, [stream]);
  return has;
}

const StreamVideo: React.FC<{
  stream: MediaStream | null;
  muted: boolean;
  mirrored?: boolean;
  className: string;
  /** ПОГЛЯД: цей елемент — плитка співрозмовника, її пікселі й видимість. */
  onTile?: (el: HTMLElement | null) => void;
}> = ({ stream, muted, mirrored, className, onTile }) => {
  const ref = useRef<HTMLVideoElement | null>(null);
  useEffect(() => {
    if (!onTile) return;
    onTile(ref.current);
    return () => onTile(null);
  }, [onTile]);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.srcObject = stream;
    if (!stream) return;
    const p = el.play();
    if (p !== undefined) {
      p.catch(() => {
        // Автовідтворення зі звуком заборонене — стартуємо беззвучно й
        // повертаємо звук одразу після старту.
        if (!muted) {
          el.muted = true;
          el.play().then(() => {
            el.muted = false;
          }).catch(() => undefined);
        }
      });
    }
  }, [stream, muted]);
  return (
    <video
      ref={ref}
      autoPlay
      playsInline
      muted={muted}
      className={className}
      style={{ transform: mirrored ? 'scaleX(-1)' : undefined }}
    />
  );
};

/**
 * Вуха кімнати: без цього елемента додаткова нога німа — `<StreamVideo>`
 * грає рівно один потік, а `AnalyserNode` у Chromium без медіаелемента
 * повертає нулі. `muted` бере прапорець динаміка.
 */
const LegEars: React.FC<{ legs: LegView[]; speakerOn: boolean }> = ({ legs, speakerOn }) => (
  <>
    {legs
      .filter((l) => !l.primary && l.stream && l.foot !== 'gone')
      .map((l) => (
        <audio
          key={l.peerId}
          data-call-leg-audio={l.peerId}
          autoPlay
          muted={!speakerOn}
          ref={(el) => {
            if (el && el.srcObject !== l.stream) el.srcObject = l.stream;
          }}
        />
      ))}
  </>
);

const LegRow: React.FC<{ leg: LegView; canDrop: boolean }> = ({ leg, canDrop }) => {
  const { t } = useTranslation();
  const dead = leg.foot === 'gone';
  return (
    <div
      data-call-leg={leg.peerId}
      data-call-leg-foot={leg.foot}
      data-call-leg-speaking={leg.speaking ? 'yes' : 'no'}
      className="flex items-center gap-3 px-3 py-2 rounded-control transition-colors duration-standard"
      style={{ opacity: dead ? 0.5 : 1 }}
    >
      <div
        className="rounded-full shrink-0 p-[2px] transition-colors duration-instant"
        style={{ outline: leg.speaking && !dead ? '2px solid var(--ph-accent-line)' : 'none' }}
      >
        <Avatar name={leg.name} tone={leg.peerId} radius="rounded-full" className="w-9 h-9 text-subhead" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-headline text-ink truncate">{leg.name}</div>
        <div className="text-callout text-ink-3 truncate">{footLabel(leg)}</div>
      </div>
      {canDrop && !dead && (
        <button
          onClick={() => {
            soundFx.playTap();
            void callEngine.dropLeg(leg.peerId);
          }}
          className="w-12 h-12 touch-floor rounded-full flex items-center justify-center shrink-0 text-ink-3 hover:bg-raised transition-colors"
          aria-label={t('callOverlay.dropLeg', { name: leg.name })}
          title={t('callOverlay.dropLeg', { name: leg.name })}
        >
          <UserMinus className="w-5 h-5" />
        </button>
      )}
    </div>
  );
};


/** Поверхні картки (стиль D): рівні, з лінією, без скла й тіні — і над живим відео теж. */
const DOCK: React.CSSProperties = { background: 'var(--ph-surface)', border: '1px solid var(--ph-line)' };
const CHIP: React.CSSProperties = { background: 'var(--ph-raised)', border: '1px solid var(--ph-line)' };

export const CallOverlay: React.FC = () => {
  const { t, locale } = useTranslation();
  const snapshot = useSyncExternalStore(callEngine.subscribe, callEngine.getSnapshot);
  const [isMinimized, setIsMinimized] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [declineOpen, setDeclineOpen] = useState(false);
  const [day, setDay] = useState<DayState | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const inCall = snapshot.state !== 'idle' && snapshot.state !== 'ended';
  useEffect(() => {
    if (!inCall) return;
    let cancelled = false;
    setNowMs(Date.now());
    dayApi
      .today()
      .then((res) => {
        if (!cancelled) setDay(res);
      })
      .catch(() => {
        if (!cancelled) setDay(null);
      });
    const id = setInterval(() => setNowMs(Date.now()), 30_000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [inCall]);
  const next = useMemo(() => nextEventFor(day?.items ?? [], nowMs), [day, nowMs]);
  const peerNodeId = snapshot.peer?.peerNodeId;
  const photo = usePeerPhoto(peerNodeId);
  const replyChat = useMessengerStore((s) =>
    peerNodeId ? s.chats.find((c) => c.peerNodeId === peerNodeId) : undefined,
  );
  const sendMessage = useMessengerStore((s) => s.sendMessage);
  const remoteHasVideo = useHasVideo(snapshot.remoteStream);
  const localHasVideo = useHasVideo(snapshot.localStream);
  const active = snapshot.state === 'active';
  const ringing = snapshot.state === 'ringing';
  useEffect(() => {
    if (!ringing) setDeclineOpen(false);
  }, [ringing]);
  const covers = callCoversScreen(snapshot.state, isMinimized);
  useEffect(() => {
    useCallChromeStore.setState({ covers });
    return () => useCallChromeStore.setState({ covers: false });
  }, [covers]);
  const route = useAudioRoutes(snapshot.callId, active);

  const faceRef = useRef<HTMLDivElement | null>(null);
  const dockRef = useRef<HTMLDivElement | null>(null);
  const headRef = useRef<HTMLDivElement | null>(null);
  const [insets, setInsets] = useState({ top: 72, bottom: 230 });
  const waiting = snapshot.state === 'ringing' || snapshot.state === 'calling';
  useBreath(faceRef, waiting && !isMinimized);
  useRise(dockRef, `${snapshot.state}:${isMinimized}`);

  // Де шапка й панель кнопок — щоб своє відео не заїжджало під них.
  useLayoutEffect(() => {
    const top = headRef.current?.offsetHeight ?? 0;
    const bottom = dockRef.current?.offsetHeight ?? 0;
    if (!top || !bottom) return;
    setInsets((prev) => (prev.top === top && prev.bottom === bottom ? prev : { top, bottom }));
  });

  // Клавіші на ПК: M — мікрофон, V — камера, S — динамік, P — згорнути.
  useEffect(() => {
    if (snapshot.state !== 'active') return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
      if (e.code === 'KeyM') {
        e.preventDefault();
        soundFx.playTap();
        callEngine.toggleMic();
      } else if (e.code === 'KeyV') {
        // У голосовому дзвінку камери немає — клавіша не вдає дію.
        if (!callEngine.getSnapshot().hasCamera) return;
        e.preventDefault();
        soundFx.playTap();
        callEngine.toggleCamera();
      } else if (e.code === 'KeyS') {
        e.preventDefault();
        soundFx.playTap();
        callEngine.toggleSpeaker();
      } else if (e.code === 'KeyP') {
        e.preventDefault();
        soundFx.playTap();
        setIsMinimized((v) => !v);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [snapshot.state]);

  // Новий дзвінок завжди відкривається на весь екран.
  useEffect(() => {
    if (snapshot.state !== 'active') setIsMinimized(false);
  }, [snapshot.state]);

  // Мить завершення — щоб підсумок сказав, скільки тривала розмова.
  const [endedAt, setEndedAt] = useState<number | null>(null);
  useEffect(() => {
    setEndedAt(snapshot.state === 'ended' ? Date.now() : null);
  }, [snapshot.state]);

  if (snapshot.state === 'idle' || typeof document === 'undefined') return null;

  const name = snapshot.peer?.displayName || t('callOverlay.peerFallback');
  const toneKey = snapshot.peer?.peerNodeId || snapshot.peer?.contactId || name;
  const room = snapshot.legs;
  const alive = room.filter((l) => l.foot !== 'gone');
  const note = active
    ? linkLine({
        radio: snapshot.radio,
        radioReason: snapshot.radioReason,
        linkNote: snapshot.linkNote,
        videoDropped: snapshot.videoDropped,
        weakSide: snapshot.weakSide,
        peer: snapshot.peer,
      })
    : null;

  const battNote = active && snapshot.battOff ? t('callWords.battOff') : null;

  const hangup = () => {
    soundFx.playTap();
    callEngine.hangup();
  };
  const toggleMic = () => {
    soundFx.playTap();
    callEngine.toggleMic();
  };

  /* Згорнутий дзвінок — пігулка вгорі, поки людина ходить застосунком. */
  if (active && isMinimized) {
    return createPortal(
      <div
        data-call-overlay
        data-call-state={snapshot.state}
        data-call-pill
        className="fixed left-1/2 -translate-x-1/2 z-[9999] w-[min(420px,calc(100vw-32px))] flex items-center gap-2 pl-1.5 pr-1.5 py-1.5 rounded-full border border-line bg-surface select-none f-fade-in"
        style={{ top: 'calc(env(safe-area-inset-top, 0px) + 8px)' }}
      >
        <button
          type="button"
          onClick={() => {
            soundFx.playTap();
            setIsMinimized(false);
          }}
          className="flex-1 min-w-0 min-h-[48px] flex items-center gap-2.5 text-left rounded-full"
          aria-label={t('callOverlay.backToCall')}
        >
          <Avatar src={photo} name={name} tone={toneKey} radius="rounded-full" className="w-10 h-10 text-callout" />
          <span className="min-w-0 flex-1">
            <span className="block text-headline text-ink truncate">{name}</span>
            <span className="block text-caption text-ink-2">
              <CallTimer startedAt={snapshot.startedAt} />
            </span>
          </span>
        </button>
        <button
          type="button"
          onClick={toggleMic}
          aria-pressed={!snapshot.micOn}
          className={`w-12 h-12 touch-floor rounded-full flex items-center justify-center transition-colors ${
            snapshot.micOn ? 'bg-raised text-ink' : 'bg-ink text-canvas'
          }`}
          aria-label={snapshot.micOn ? t('callOverlay.micOff') : t('callOverlay.micOn')}
          title={snapshot.micOn ? t('callOverlay.micOff') : t('callOverlay.micOn')}
        >
          {snapshot.micOn ? <Mic className="w-5 h-5" /> : <MicOff className="w-5 h-5" />}
        </button>
        <button
          type="button"
          onClick={hangup}
          className="w-12 h-12 touch-floor rounded-full flex items-center justify-center"
          style={{ background: END, color: GLYPH }}
          aria-label={t('callOverlay.endCall')}
          title={t('callOverlay.endCall')}
        >
          <PhoneOff className="w-5 h-5" />
        </button>
      </div>,
      document.body,
    );
  }

  const isVideoCall = snapshot.media === 'video';
  /** Відео співрозмовника на весь екран. */
  // Голосовий дзвінок теж показує відео, поки співрозмовник тримає «Показати».
  const fullVideo = active && remoteHasVideo && !snapshot.audioOnly;
  /** Поки дзвонимо з відео — на весь екран своя камера (як дзеркало перед розмовою). */
  const preview = !active && !ringing && isVideoCall && snapshot.cameraOn && localHasVideo;
  const showSelf = active && snapshot.hasCamera && snapshot.cameraOn && localHasVideo;
  const mirrored = snapshot.facing !== 'environment';
  const stall = snapshot.stall;
  const routeReady = route.state.status === 'ready' ? route.state : null;

  const stateLine = active ? (
    <>
      <CallTimer startedAt={snapshot.startedAt} />
      {/* Скільки співрозмовник справді шле нам — його bytesSent, не план. */}
      {snapshot.served?.mbh != null && <span data-call-mbh> · {mbhLabel(snapshot.served.mbh)}</span>}
    </>
  ) : (
    callStateLine({
      state: snapshot.state,
      media: snapshot.media,
      stall: snapshot.stall,
      endedReason: snapshot.endedReason,
      resuming: snapshot.resuming,
    }) + (snapshot.state === 'ended' && snapshot.startedAt !== null && endedAt !== null ? ` · ${clock(endedAt - snapshot.startedAt)}` : '')
  );

  return createPortal(
    <div
      data-call-overlay
      data-call-state={snapshot.state}
      data-call-media={snapshot.media}
      data-theme="night"
      className="fixed inset-0 z-[9999] flex flex-col bg-canvas text-ink select-none f-fade-in overflow-hidden"
    >
      {/* ── тло: відео співрозмовника, своя камера перед розмовою або світло людини ── */}
      {fullVideo ? (
        <StreamVideo
          stream={snapshot.remoteStream}
          muted={!snapshot.speakerOn || Boolean(snapshot.catchingUp)}
          className="absolute inset-0 w-full h-full object-cover"
          onTile={remoteTile.set}
        />
      ) : preview ? (
        <StreamVideo stream={snapshot.localStream} muted mirrored={mirrored} className="absolute inset-0 w-full h-full object-cover" />
      ) : (
        <CallBackdrop />
      )}
      {(fullVideo || preview) && <VideoScrim dim={preview} />}
      {/* Звук співрозмовника грає, навіть коли відео немає. */}
      {!fullVideo && active && (
        <StreamVideo stream={snapshot.remoteStream} muted={!snapshot.speakerOn || Boolean(snapshot.catchingUp)} className="hidden" />
      )}
      <LegEars legs={room} speakerOn={snapshot.speakerOn} />

      {/* ── шапка ── */}
      <div
        ref={headRef}
        className="relative z-10 flex items-center gap-3 px-4 shrink-0 min-h-[64px]"
        style={{ paddingTop: 'env(safe-area-inset-top, 0px)' }}
      >
        {active ? (
          <button
            type="button"
            onClick={() => {
              soundFx.playTap();
              setIsMinimized(true);
            }}
            className="w-12 h-12 touch-floor rounded-full flex items-center justify-center shrink-0 text-ink"
            style={CHIP}
            aria-label={t('callOverlay.minimize')}
            title={t('callOverlay.minimizeHint')}
          >
            <ChevronDown className="w-6 h-6" />
          </button>
        ) : (
          <span className="w-12 h-12 shrink-0" />
        )}
        {fullVideo && (
          <div className="min-w-0 text-left px-3 py-1.5 rounded-control" style={DOCK} data-call-name-plate>
            <h2 className="text-title-2 text-ink truncate">{name}</h2>
            <div className="mt-0.5 flex items-center gap-3 text-footnote text-ink-2">
              <span data-call-line data-call-stall={stall ? stall.kind : undefined} className="tnum" aria-live="polite">
                {stateLine}
              </span>
              <QualityBars stats={snapshot.stats} />
            </div>
          </div>
        )}
        {next && (
          <span data-call-next className="ml-auto text-footnote text-ink-2 tnum truncate" style={fullVideo ? { ...DOCK, padding: '4px 10px', borderRadius: 999 } : undefined}>
            {callNextLabel(next, t)}
          </span>
        )}
        {active && (
          <button
            type="button"
            data-call-settings-btn
            onClick={() => {
              soundFx.playTap();
              setSettingsOpen((v) => !v);
            }}
            aria-label={t('callSettings.title')}
            title={t('callSettings.title')}
            className={`${next ? '' : 'ml-auto '}w-12 h-12 touch-floor rounded-full flex items-center justify-center shrink-0 text-ink`}
            style={CHIP}
          >
            <SlidersHorizontal className="w-6 h-6" />
          </button>
        )}
      </div>

      {/* ── середина: обличчя, імʼя, стан ── */}
      <div className="relative z-10 flex-1 min-h-0 flex flex-col items-center px-6 text-center pt-[7vh]">
        {!fullVideo && (
          <>
            {/* Аватар без кілець і світіння (біблія 05 §2.7): «ще дзвонить» каже
                рядок стану й повільне дихання самого обличчя, а не ореол. */}
            <div data-call-face ref={faceRef} className="mb-6" style={{ willChange: waiting ? 'transform' : undefined }}>
              <Avatar
                src={photo}
                name={name}
                tone={toneKey}
                radius="rounded-full"
                className="w-[168px] h-[168px] text-[60px]"
              />
            </div>
            <h2 className="text-display text-ink max-w-full break-words">{name}</h2>
            <div
              data-call-line
              data-call-stall={stall ? stall.kind : undefined}
              className="mt-2 text-body text-ink-2 tnum"
              aria-live="polite"
            >
              {stateLine}
            </div>
            {active && <QualityBars stats={snapshot.stats} className="mt-3 text-ink-2" />}
          </>
        )}
        {battNote ? (
          <button
            type="button"
            data-call-link-note
            data-testid="call-batt-keep"
            onClick={() => callEngine.keepVideoOnLowBattery()}
            className="mt-5 min-h-[48px] px-4 py-2.5 rounded-full text-callout text-ink max-w-[320px]"
            style={CHIP}
          >
            {battNote}
          </button>
        ) : (
          note && (
            <div
              data-call-link-note
              className="mt-5 px-4 py-2.5 rounded-full text-callout text-ink max-w-[320px]"
              style={CHIP}
            >
              {note}
            </div>
          )
        )}

        {room.length > 1 && (
          <div
            data-call-room={active ? '' : 'dialing'}
            className="mt-6 w-full max-w-[380px] max-h-[34vh] overflow-y-auto rounded-sheet p-1 text-left"
            style={DOCK}
          >
            <div className="px-3 pt-2 pb-1 text-footnote font-semibold text-ink-3">{t('callOverlay.inCall', { n: alive.length })}</div>
            {room.map((leg) => (
              <LegRow key={leg.peerId} leg={leg} canDrop={active && alive.length > 1} />
            ))}
            {snapshot.roomCrowded && snapshot.roomMax !== null && (
              <div data-call-room-crowded className="px-3 pb-2 pt-1 text-callout text-ink-3">
                {roomFullLine(snapshot.roomMax + 1)}
              </div>
            )}
          </div>
        )}
      </div>

      {showSelf && (
        <SelfView insets={insets}>
          <StreamVideo stream={snapshot.localStream} muted mirrored={mirrored} className="w-full h-full object-cover" />
        </SelfView>
      )}

      {/* ── панель кнопок ──
          z-30, вище за своє відео (SelfView z-20): панель — окремий шар, і
          меню маршрутів усередині неї інакше малювалось би ПІД віконцем,
          перетягнутим у нижній кут. Саме віконце панелі не перекриває
          (`insets.bottom`), тож вищий шар не ховає його. */}
      <div
        ref={dockRef}
        className="relative z-30 shrink-0 px-4 pt-4 flex flex-col items-center"
        style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 28px)' }}
      >
        {ringing ? (
          <>
            {declineOpen && replyChat && (
              <div className="w-full max-w-[300px] flex flex-col gap-2 mb-4">
                {declineReplies({ nowMs: Date.now(), day, nav: null, t }).map((r) => (
                  <button
                    key={r.kind}
                    type="button"
                    data-decline-reply={r.kind}
                    onClick={() => {
                      soundFx.playTap();
                      callEngine.decline();
                      void pick(r, (text) => {
                        useMessengerStore.getState().setActiveChat(replyChat.id);
                        sendMessage(text);
                      });
                    }}
                    className="min-h-[48px] px-4 py-2.5 rounded-full text-callout text-ink touch-floor"
                    style={CHIP}
                  >
                    {r.text}
                  </button>
                ))}
              </div>
            )}
            <div className="w-full max-w-[300px] flex justify-between items-start">
              <div className="flex flex-col items-center gap-2">
                <CallButton
                  onClick={() => {
                    soundFx.playTap();
                    callEngine.decline();
                  }}
                  label={t('callOverlay.decline')}
                  hint={t('callOverlay.declineHint')}
                  tone="end"
                >
                  <PhoneOff className="w-8 h-8" />
                </CallButton>
                {replyChat && (
                  <button
                    type="button"
                    data-decline-more
                    aria-label={t('declineReplies.open')}
                    aria-expanded={declineOpen}
                    onClick={() => {
                      soundFx.playTap();
                      setDeclineOpen((v) => !v);
                    }}
                    className="w-12 h-12 touch-floor rounded-full flex items-center justify-center text-ink"
                    style={CHIP}
                  >
                    <ChevronDown className="w-5 h-5" />
                  </button>
                )}
              </div>
              <CallButton
                onClick={() => {
                  soundFx.playSend();
                  void callEngine.accept();
                }}
                label={t('callOverlay.accept')}
                hint={t('callOverlay.acceptHint')}
                tone="accept"
              >
                {snapshot.media === 'video' ? <Video className="w-8 h-8" /> : <Phone className="w-8 h-8" />}
              </CallButton>
            </div>
          </>
        ) : snapshot.state === 'ended' ? (
          <div data-call-ended-controls className="w-full max-w-[300px] flex justify-between items-start">
            <CallButton
              onClick={() => {
                soundFx.playTap();
                callEngine.dismiss();
              }}
              label={t('coreChat.close')}
              hint={t('coreChat.close')}
              tone="idle"
            >
              <X className="w-8 h-8" />
            </CallButton>
            {(snapshot.peer?.peerNodeId || snapshot.peer?.contactId) && (
              <CallButton
                onClick={() => {
                  soundFx.playSend();
                  void callEngine.redial();
                }}
                label={t('coreChat.callAgain')}
                hint={t('coreChat.callAgain')}
                tone="accept"
              >
                {snapshot.media === 'video' ? <Video className="w-8 h-8" /> : <Phone className="w-8 h-8" />}
              </CallButton>
            )}
          </div>
        ) : active ? (
          <>
            <div data-call-controls className="flex items-start justify-center gap-1.5 px-2 pt-3 pb-2.5 rounded-[36px]" style={DOCK}>
              {routeReady ? (
                <RoutePicker
                  routes={routeReady.routes}
                  busy={routeReady.busy}
                  onChoose={(id) => void route.choose(id)}
                  onOpen={route.refresh}
                />
              ) : (
                <CallButton
                  onClick={() => {
                    soundFx.playTap();
                    callEngine.toggleSpeaker();
                  }}
                  label={t('callOverlay.sound')}
                  size="md"
                  hint={snapshot.speakerOn ? t('callOverlay.soundOffHint') : t('callOverlay.soundOnHint')}
                  tone={snapshot.speakerOn ? 'idle' : 'on'}
                  pressed={!snapshot.speakerOn}
                >
                  {snapshot.speakerOn ? <Volume2 className="w-7 h-7" /> : <VolumeX className="w-7 h-7" />}
                </CallButton>
              )}
              <CallButton
                onClick={toggleMic}
                label={t('callOverlay.mic')}
                size="md"
                hint={snapshot.micOn ? t('callOverlay.micOffHint') : t('callOverlay.micOnHint')}
                tone={snapshot.micOn ? 'idle' : 'on'}
                pressed={!snapshot.micOn}
              >
                {snapshot.micOn ? <Mic className="w-7 h-7" /> : <MicOff className="w-7 h-7" />}
              </CallButton>
              {/* Камера — лише у відеодзвінку: у голосовому відеодоріжки немає,
                  і кнопка, що вдає дію, гірша за її відсутність. */}
              {snapshot.hasCamera && (
                <CallButton
                  onClick={() => {
                    soundFx.playTap();
                    callEngine.toggleCamera();
                  }}
                  label={t('callOverlay.camera')}
                  size="md"
                  hint={snapshot.cameraOn ? t('callOverlay.cameraOffHint') : t('callOverlay.cameraOnHint')}
                  tone={snapshot.cameraOn ? 'idle' : 'on'}
                  pressed={!snapshot.cameraOn}
                >
                  {snapshot.cameraOn ? <Video className="w-7 h-7" /> : <VideoOff className="w-7 h-7" />}
                </CallButton>
              )}
              {!snapshot.hasCamera && !isVideoCall && active && (
                <CallButton
                  onClick={() => undefined}
                  onPointerDown={() => {
                    soundFx.playTap();
                    void callEngine.peekCamera(true);
                  }}
                  onPointerUp={() => void callEngine.peekCamera(false)}
                  onPointerLeave={() => void callEngine.peekCamera(false)}
                  onPointerCancel={() => void callEngine.peekCamera(false)}
                  label={
                    !snapshot.peeking && snapshot.peekBytes != null
                      ? formatDataUsed(snapshot.peekBytes, locale, t('callSettings.mb'))
                      : t('callOverlay.peek')
                  }
                  size="md"
                  hint={t('callOverlay.peekHint')}
                  tone={snapshot.peeking ? 'on' : 'idle'}
                  pressed={Boolean(snapshot.peeking)}
                  data-call-peek
                >
                  <Video className="w-7 h-7" />
                </CallButton>
              )}
              {/* Перевернути — лише коли камера знімає: перевертати нічого. */}
              {snapshot.hasCamera && snapshot.cameraOn && (
                <CallButton
                  onClick={() => {
                    soundFx.playTap();
                    void callEngine.flipCamera();
                  }}
                  label={t('call.flip.short')}
                  size="md"
                  hint={t('call.flip')}
                  tone="idle"
                  data-call-flip={snapshot.facing ?? 'user'}
                >
                  <SwitchCamera className="w-7 h-7" />
                </CallButton>
              )}
            </div>
            <CallSettingsSheet open={settingsOpen} onClose={() => setSettingsOpen(false)} />
            <div className="mt-6">
              <CallButton onClick={hangup} hint={t('callOverlay.endCall')} tone="end">
                <Phone className="w-8 h-8 rotate-[135deg]" fill="currentColor" strokeWidth={1.5} />
              </CallButton>
            </div>
          </>
        ) : (
          <CallButton onClick={hangup} label={t('callOverlay.cancel')} hint={t('callOverlay.cancelHint')} tone="end">
            <PhoneOff className="w-8 h-8" />
          </CallButton>
        )}
      </div>
    </div>,
    document.body,
  );
};
