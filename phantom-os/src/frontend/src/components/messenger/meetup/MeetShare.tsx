/**
 * «Показати, де я» з картки зустрічі (макети 07–09): до зустрічі, на годину
 * або лише час прибуття. Рахує відстань і гасить показ телефон; веб бачить
 * лише стан: хто, до коли, чи ми вже зустрілись.
 */
import React, { useEffect, useState } from 'react';
import { Clock, MapPinCheck, Navigation, Timer } from 'lucide-react';
import { FormSheet } from '../FormSheet';
import { useTranslation } from '../../../i18n/useTranslation';
import { phoneBridge } from '../../../services/messengerApi';
import { nearWord, onWhereChanged, whereApi, type WhereState } from '../../../services/peerWhere';
import { hhmm } from './words';

export interface MeetPeer {
  id: string;
  name: string;
}

const MEET_MAX = 6 * 3600_000;

/** Кінець показу «до зустрічі»: кінець плану, але не далі, ніж приймає телефон. */
export const meetUntil = (end: number, now: number) => Math.min(end, now + MEET_MAX - 60_000);

export function useMeetWhere(peers: MeetPeer[], active: boolean, nonce = 0): Record<string, WhereState | null> {
  const [states, setStates] = useState<Record<string, WhereState | null>>({});
  const key = peers.map((p) => p.id).join(',');
  useEffect(() => {
    if (!active || !key || !phoneBridge()) {
      setStates({});
      return;
    }
    let alive = true;
    const ids = key.split(',');
    const load = () =>
      void Promise.allSettled(ids.map((id) => whereApi.get(id))).then((res) => {
        if (!alive) return;
        const next: Record<string, WhereState | null> = {};
        res.forEach((r, i) => (next[ids[i]] = r.status === 'fulfilled' ? r.value : null));
        setStates(next);
      });
    load();
    const off = onWhereChanged(load);
    return () => {
      alive = false;
      off();
    };
  }, [key, active, nonce]);
  return states;
}

const Option: React.FC<{
  icon: React.ReactNode;
  label: string;
  hint: string;
  testId: string;
  disabled?: boolean;
  onClick: () => void;
}> = ({ icon, label, hint, testId, disabled, onClick }) => (
  <button
    type="button"
    data-testid={testId}
    disabled={disabled}
    onClick={onClick}
    className="w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left active:bg-overlay disabled:opacity-40 transition-colors duration-quick"
  >
    <span className="w-6 shrink-0 flex items-center justify-center text-ink-2">{icon}</span>
    <span className="flex-1 min-w-0">
      <span className="block text-body text-ink">{label}</span>
      <span className="block text-footnote text-ink-3">{hint}</span>
    </span>
  </button>
);

export const MeetShareSheet: React.FC<{
  peers: MeetPeer[];
  /** Кінець зустрічі, мс. */
  end: number;
  onArrivalOnly: () => void;
  onClose: () => void;
  onStarted: () => void;
}> = ({ peers, end, onArrivalOnly, onClose, onStarted }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const until = meetUntil(end, Date.now());

  const start = async (show: (id: string) => Promise<WhereState>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    const res = await Promise.allSettled(peers.map((p) => show(p.id)));
    setBusy(false);
    if (!res.some((r) => r.status === 'fulfilled')) {
      setError(t('meetShare.failed'));
      return;
    }
    onStarted();
    onClose();
  };

  return (
    <FormSheet open onClose={onClose} title={t('meetShare.title')} testId="meet-share">
      <p className="px-4 pb-2 text-footnote text-ink-3" data-testid="meet-share-who">
        {peers.length ? t('meetShare.who', { names: peers.map((p) => p.name).join(', ') }) : t('meetShare.nobody')}
      </p>
      <Option
        icon={<MapPinCheck size={20} strokeWidth={1.75} />}
        label={t('meetShare.until')}
        hint={t('meetShare.untilHint', { time: hhmm(until) })}
        testId="meet-share-until"
        disabled={busy || !peers.length}
        onClick={() => void start((id) => whereApi.showMeet(id, until))}
      />
      <Option
        icon={<Timer size={20} strokeWidth={1.75} />}
        label={t('meetShare.hour')}
        hint={t('meetShare.hourHint')}
        testId="meet-share-hour"
        disabled={busy || !peers.length}
        onClick={() => void start((id) => whereApi.show(id, 'exact', 60))}
      />
      <Option
        icon={<Clock size={20} strokeWidth={1.75} />}
        label={t('meetShare.eta')}
        hint={t('meetShare.etaHint')}
        testId="meet-share-eta"
        onClick={() => {
          onClose();
          onArrivalOnly();
        }}
      />
      {error && (
        <p className="px-4 pt-2 text-caption text-danger" data-testid="meet-share-error">
          {error}
        </p>
      )}
    </FormSheet>
  );
};

/** Рядок на картці: показ іде (08) або згас, бо ми зустрілись (09). Інакше — нічого. */
export const MeetLiveRow: React.FC<{
  peers: MeetPeer[];
  states: Record<string, WhereState | null>;
  onStop: () => void;
}> = ({ peers, states, onStop }) => {
  const { t } = useTranslation();
  const sharing = peers.filter((p) => states[p.id]?.mine);
  if (!sharing.length) {
    if (!peers.some((p) => states[p.id]?.met_at_ms)) return null;
    return (
      <div data-testid="meet-done" className="mx-4 mt-1 px-3 min-h-11 rounded-control bg-ink/[.06] flex items-center gap-2">
        <MapPinCheck className="w-[18px] h-[18px] shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
        <span className="flex-1 min-w-0 text-footnote text-ink">{t('meetShare.met')}</span>
      </div>
    );
  }
  const meet = sharing.some((p) => states[p.id]?.mine?.meet);
  const first = sharing[0];
  const theirs = states[first.id]?.theirs;
  const untilMs = states[first.id]?.mine?.until_ms;
  const meta = theirs
    ? `${first.name} · ${nearWord(theirs.near, theirs.precision)}`
    : untilMs
      ? t('meetShare.until_', { time: hhmm(untilMs) })
      : first.name;
  return (
    <div data-testid="meet-live" className="mx-4 mt-1 pl-3 min-h-11 rounded-control bg-ink/[.06] flex items-center gap-2">
      <Navigation className="w-[18px] h-[18px] shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
      <span className="flex-1 min-w-0 py-1.5">
        <span className="block text-footnote font-semibold text-ink">{meet ? t('meetShare.live') : t('meetShare.liveHour')}</span>
        <span className="block text-caption text-ink-2 truncate" data-testid="meet-live-meta">
          {meta}
        </span>
      </span>
      <button
        type="button"
        data-testid="meet-stop"
        onClick={() => {
          void Promise.allSettled(sharing.map((p) => whereApi.stop(p.id))).then(onStop);
        }}
        className="h-11 px-3 shrink-0 text-footnote font-semibold text-accent"
      >
        {t('meetShare.stop')}
      </button>
    </div>
  );
};
