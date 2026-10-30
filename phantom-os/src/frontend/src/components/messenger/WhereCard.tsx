import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { MapPin } from 'lucide-react';
import { FormSheet, PrimaryButton } from './FormSheet';
import { PRIMITIVES, policy, readEnv } from '../../feel/motionTokens';
import {
  WHERE_PRECISIONS,
  WHERE_SPANS,
  agoWord,
  mineLine,
  onStopPhase,
  onWhereChanged,
  openPeerCheck,
  stopPhaseOf,
  stopShare,
  theirsLine,
  whereApi,
  whyNotLeadsToCheck,
  whyNotSentence,
  type StopPhase,
  type WherePrecision,
  type WhereState,
} from '../../services/peerWhere';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { geoNow, type GeoNowFail } from '../../services/geoNow';

type Peer = { id: string; name: string };

function usePeerWhere(peerId: string | null) {
  const [state, setState] = useState<WhereState | null>(null);
  const [now, setNow] = useState(Date.now());
  // Новий монтаж не успадковує «shut»: затвор лише для підтвердження, побаченого цим екземпляром.
  const [phase, setPhase] = useState<StopPhase>(() => (stopPhaseOf(peerId ?? '') === 'stopping' ? 'stopping' : 'live'));
  const gen = useRef(0);
  const apply = useCallback((s: WhereState) => {
    gen.current += 1;
    setState(s);
    setNow(Date.now());
  }, []);
  const read = useCallback(() => {
    if (!peerId) return;
    const g = ++gen.current;
    whereApi.get(peerId).then(
      (s) => {
        if (g !== gen.current) return;
        setState(s);
        if (s.mine) setPhase((p) => (p === 'shut' ? 'live' : p));
      },
      () => {
        if (g === gen.current) setState(null);
      },
    );
    setNow(Date.now());
  }, [peerId]);

  useEffect(() => {
    setState(null);
    read();
    const off = onWhereChanged(read);
    return () => {
      gen.current += 1;
      off();
    };
  }, [read]);

  useEffect(() => {
    setPhase(stopPhaseOf(peerId ?? '') === 'stopping' ? 'stopping' : 'live');
    if (!peerId) return;
    return onStopPhase((id, next) => {
      if (id !== peerId) return;
      setPhase(next);
      if (next === 'shut') {
        gen.current += 1;
        setState((prev) => (prev?.mine ? { ...prev, mine: null } : prev));
        setNow(Date.now());
      }
    });
  }, [peerId]);

  const live = !!(state?.mine || state?.theirs);
  useEffect(() => {
    if (!live) return;
    const id = window.setInterval(read, 30_000);
    return () => window.clearInterval(id);
  }, [live, read]);

  return { state, now, refresh: read, apply, phase };
}

const CARD = 'w-full max-w-[340px] rounded-control border border-line bg-surface px-3 py-2.5';
const ACTION =
  'touch-floor min-h-[40px] px-3 rounded-control bg-raised text-footnote font-semibold text-ink-2 active:bg-canvas';

/** Живі картки «Де я» в хвості розмови. Зниклий стан лишається сірим, доки розмова відкрита. */
export const WhereCards: React.FC<{ peer: Peer; onChange: () => void }> = ({ peer, onChange }) => {
  const { t } = useTranslation();
  const { state, now, apply, phase } = usePeerWhere(peer.id);
  const seen = useRef<{ mine: boolean; theirsAt: number | null }>({ mine: false, theirsAt: null });
  const [error, setError] = useState<string | null>(null);
  const spec = useMemo(() => policy(readEnv()).fill, []);

  useEffect(() => {
    seen.current = { mine: false, theirsAt: null };
  }, [peer.id]);
  if (state?.mine) seen.current.mine = true;
  if (state?.theirs) seen.current.theirsAt = state.theirs.at_ms;

  const mineEnded = !state?.mine && (seen.current.mine || phase === 'shut');
  const theirsEnded = !state?.theirs && seen.current.theirsAt != null;
  if (!state?.mine && !state?.theirs && !mineEnded && !theirsEnded) return null;

  const stop = () => {
    setError(null);
    stopShare(peer.id).then(apply, () => setError(t('whereCard.stopFailed')));
  };
  const toMap = () => {
    whereApi.openMap(peer.id).catch(() => setError(t('whereCard.stoppedSharing', { name: peer.name })));
  };

  return (
    <div className="mt-3 flex flex-col gap-2" data-testid="where-cards">
      {state?.theirs && (
        <div className={`${CARD} self-start`} data-testid="where-card-theirs">
          <Row icon line={theirsLine(peer.name, state.theirs, now)} stand={state.theirs.stand ? t('whereCard.standTheirs') : null} />
          <div className="mt-2 flex gap-2">
            <button type="button" className={ACTION} onClick={toMap}>
              {t('whereCard.onMap')}
            </button>
          </div>
        </div>
      )}
      {theirsEnded && (
        <div className={`${CARD} self-start opacity-60`} data-testid="where-card-theirs-ended">
          <Row line={{ title: t('whereCard.stoppedSharingTitle', { name: peer.name }), meta: t('whereCard.lastSeen', { agoWord: agoWord(seen.current.theirsAt!, now) }) }} />
        </div>
      )}
      {state?.mine && (
        <div
          className={`${CARD} self-end`}
          data-testid="where-card-mine"
          data-state={phase === 'stopping' ? 'stopping' : 'live'}
        >
          <Row icon line={mineLine(state.mine, now)} stand={state.mine.stand ? t('whereCard.standMine') : null} />
          <div className="mt-2 flex gap-2">
            <button type="button" className={ACTION} disabled={phase === 'stopping'} onClick={onChange}>
              {t('whereCard.change')}
            </button>
            <button type="button" className={ACTION} disabled={phase === 'stopping'} onClick={stop}>
              {phase === 'stopping' ? t('whereCard.stopping') : t('whereCard.stop')}
            </button>
          </div>
        </div>
      )}
      {mineEnded && (
        <div
          className={`${CARD} self-end opacity-60`}
          data-testid="where-card-mine-ended"
          data-state={phase === 'shut' ? 'shut' : 'ended'}
        >
          <Row
            line={{ title: t('whereCard.youStopped'), meta: t('whereCard.erased') }}
            shut={phase === 'shut'}
            spec={spec}
          />
        </div>
      )}
      {error && <p className="text-callout text-danger">{error}</p>}
    </div>
  );
};

const { k: SHUTTER_K, zeta: SHUTTER_ZETA } = PRIMITIVES.fill.spring;

const Row: React.FC<{
  line: { title: string; meta: string };
  icon?: boolean;
  stand?: string | null;
  shut?: boolean;
  spec?: ReturnType<typeof policy>['fill'];
}> = ({ line, icon, stand, shut, spec }) => (
  <div className="flex items-start gap-2.5">
    <div className="relative overflow-hidden w-8 h-8 rounded-control bg-raised flex items-center justify-center shrink-0">
      <MapPin className={`w-4 h-4 ${icon ? 'text-accent-text' : 'text-ink-3'}`} strokeWidth={2} />
      {shut && (
        <motion.div
          aria-hidden
          data-testid="where-shutter"
          data-motion={spec?.end ? 'end' : 'run'}
          className="absolute inset-0 bg-line"
          initial={spec?.end ? false : { clipPath: 'inset(0 0 100% 0)' }}
          animate={{ clipPath: 'inset(0 0 0% 0)' }}
          transition={{ type: 'spring', stiffness: SHUTTER_K, damping: 2 * SHUTTER_ZETA * Math.sqrt(SHUTTER_K), mass: 1 }}
        />
      )}
    </div>
    <div className="min-w-0 flex-1">
      <div className="text-footnote font-semibold text-ink leading-tight">{line.title}</div>
      <div className="text-callout text-ink-3 leading-tight mt-0.5">{line.meta}</div>
      {stand && <div className="text-callout text-warning leading-tight mt-0.5">{stand}</div>}
    </div>
  </div>
);

const GEO_FAIL = {
  geoUnsupported: 'messageComposer.geoUnsupported',
  geoPermission: 'messageComposer.geoPermission',
  geoTimeout: 'messageComposer.geoTimeout',
  geoFailed: 'messageComposer.geoFailed',
} as const satisfies Record<GeoNowFail, string>;

/** Аркуш «Показати, де я»: точність, строк, одна кнопка. Коли не можна — одне речення, а при новому телефоні — дорога в профіль. */
export const WhereSheet: React.FC<{ peer: Peer; onClose: () => void; inline?: boolean }> = ({ peer, onClose, inline = false }) => {
  const { t } = useTranslation();
  const { state } = usePeerWhere(peer.id);
  const [precision, setPrecision] = useState<WherePrecision>('district');
  const [minutes, setMinutes] = useState<number | null>(60);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pointBusy, setPointBusy] = useState(false);
  const sendGeoPoint = useMessengerStore((st) => st.sendGeoPoint);
  const primed = useRef(false);

  useEffect(() => {
    if (!state?.mine || primed.current) return;
    primed.current = true;
    setPrecision(state.mine.precision);
    if (state.mine.until_ms == null) setMinutes(null);
  }, [state]);

  const show = () => {
    setBusy(true);
    setError(null);
    whereApi.show(peer.id, precision, minutes).then(
      () => onClose(),
      (e: { message?: string }) => {
        setBusy(false);
        setError(e?.message || t('whereCard.startFailed'));
      },
    );
  };

  // Разова точка — звичайне повідомлення в розмову, як у групі; показ наживо нижче.
  const sendPoint = () => {
    setPointBusy(true);
    setError(null);
    const fail = (msg: string) => {
      setPointBusy(false);
      setError(msg);
    };
    geoNow().then(
      (point) => sendGeoPoint(point).then(onClose, () => fail(t('messageComposer.placeNotSent'))),
      (why: GeoNowFail) => fail(t(GEO_FAIL[why])),
    );
  };

  // Обрана клітинка — «своя» пара self/on-self, і підказка під словом теж
  // бере чорнило цієї пари (on-self-2): сірий ink-3 на бурштині не читався.
  // Необрана — surface: аркуш сам raised, і raised на raised зливався.
  const chip = (on: boolean) =>
    `touch-floor min-h-[56px] flex-1 px-2 py-1.5 rounded-control text-center flex flex-col items-center justify-center transition-colors duration-instant ease-standard ${
      on ? 'bg-self text-on-self' : 'bg-surface text-ink'
    }`;
  const hint = (on: boolean) => `text-footnote ${on ? 'text-on-self-2' : 'text-ink-3'}`;

  const blocked = !!state && !state.eligible && !!state.why_not;
  const footer = blocked ? (
    whyNotLeadsToCheck(state!.why_not) ? (
      <PrimaryButton
        onClick={() => {
          onClose();
          openPeerCheck(peer.id);
        }}
      >
        {t('whereCard.check')}
      </PrimaryButton>
    ) : null
  ) : (
    <PrimaryButton disabled={busy || !state} onClick={show}>
      {state?.mine ? t('whereCard.change') : t('whereCard.show')}
    </PrimaryButton>
  );

  return (
    <FormSheet open inline={inline} onClose={onClose} title={t('whereCard.sheetTitle')} footer={footer} testId="where-sheet">
      <div className="px-4 pb-3">
        <button
          type="button"
          onClick={sendPoint}
          disabled={pointBusy}
          className="touch-floor w-full min-h-[56px] px-4 rounded-control bg-surface text-ink flex items-center gap-3 text-left disabled:opacity-60"
          data-testid="where-send-point"
        >
          <MapPin className="w-5 h-5 text-accent-text shrink-0" strokeWidth={1.75} />
          <span className="text-headline">{pointBusy ? t('whereCard.sendingPoint') : t('whereCard.sendPoint')}</span>
        </button>
      </div>
      {blocked ? (
        <p className="px-4 pb-2 text-body text-ink-2" data-testid="where-why-not">
          {whyNotSentence(state!.why_not!, peer.name)}
        </p>
      ) : (
        <div className="px-4 pb-2 space-y-3">
          <div className="text-footnote font-semibold text-ink-3">{t('whereCard.live')}</div>
          <p className="text-callout text-ink-2">{t('whereCard.visibleOnly', { name: peer.name })}</p>
          <div className="text-footnote font-semibold text-ink-3">{t('whereCard.precision')}</div>
          <div className="flex gap-2">
            {WHERE_PRECISIONS.map((p) => (
              <button key={p.value} type="button" className={chip(precision === p.value)} onClick={() => setPrecision(p.value)} aria-pressed={precision === p.value}>
                <span className="text-headline">{p.label}</span>
                <span className={hint(precision === p.value)}>{p.hint}</span>
              </button>
            ))}
          </div>
          <div className="text-footnote font-semibold text-ink-3">{t('whereCard.howLong')}</div>
          <div className="flex gap-2">
            {WHERE_SPANS.map((s) => (
              <button key={s.label} type="button" className={chip(minutes === s.minutes)} onClick={() => setMinutes(s.minutes)} aria-pressed={minutes === s.minutes}>
                <span className="text-headline">{s.label}</span>
                <span className={hint(minutes === s.minutes)}>{s.hint}</span>
              </button>
            ))}
          </div>
        </div>
      )}
      {error && <p className="px-4 pb-2 text-callout text-danger" data-testid="where-error">{error}</p>}
    </FormSheet>
  );
};

/** Тихий знак у шапці: ви зараз показуєте місце цій людині. Дотик — «Зупинити». */
export const WhereHeaderSign: React.FC<{ peer: Peer }> = ({ peer }) => {
  const { t } = useTranslation();
  const { state, refresh, apply, phase } = usePeerWhere(peer.id);
  const [open, setOpen] = useState(false);
  if (!state?.mine && phase !== 'stopping') return null;
  return (
    <span className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="w-11 h-11 flex items-center justify-center rounded-control text-accent-text"
        aria-label={t('whereCard.signLabel', { name: peer.name })}
        title={t('whereCard.signTitle')}
        data-testid="where-header-sign"
        data-state={phase === 'stopping' ? 'stopping' : 'live'}
      >
        <MapPin className="w-[18px] h-[18px]" strokeWidth={1.75} />
      </button>
      {open && (
        <button
          type="button"
          onClick={() => {
            setOpen(false);
            stopShare(peer.id).then(apply, refresh);
          }}
          className="absolute right-0 top-12 z-40 whitespace-nowrap touch-floor min-h-[44px] px-4 rounded-control bg-surface border border-line text-footnote font-semibold text-ink shadow-e2"
        >
          {t('whereCard.stopSharing')}
        </button>
      )}
    </span>
  );
};
