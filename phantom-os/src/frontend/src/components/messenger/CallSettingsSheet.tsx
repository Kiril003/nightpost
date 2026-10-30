import React, { useEffect, useState, useSyncExternalStore } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { FormSheet } from './FormSheet';
import { callEngine } from '../../services/callEngine';
import {
  currentNet,
  loadCallPrefs,
  onCallPrefsChange,
  saveCallPrefs,
  type CallMode,
  type CallPrefs,
  type VideoCap,
} from '../../services/callPrefs';
import { callCostEstimate, ensureCallCostTracking } from '../../services/callCost';
import { onLoudspeaker, useAudioRoutes } from './call/audioRoute';
import { chipTone } from './kit/FilterChip';

export interface CallSettingsSheetProps {
  open?: boolean;
  onClose?: () => void;
  active?: boolean;
  audioOnly?: boolean;
  onAudioOnlyChange?: (on: boolean) => void;
  musicMode?: boolean;
  onMusicModeChange?: (on: boolean) => void;
  readDataUsedBytes?: () => Promise<number | null> | number | null;
}

export const formatDataUsed = (bytes: number | null, locale = 'en', unit = 'MB'): string => {
  if (bytes === null || !Number.isFinite(bytes) || bytes < 0) return '—';
  const n = new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(bytes / 1_000_000);
  return `${n} ${unit}`;
};

const MODES: readonly CallMode[] = ['auto', 'save', 'best'];
const VIDEO_CAPS: readonly VideoCap[] = ['auto', 360, 720, 1080];
ensureCallCostTracking();

export const CallSettingsSheet: React.FC<CallSettingsSheetProps> = ({
  open = true,
  onClose,
  active,
  audioOnly,
  onAudioOnlyChange,
  musicMode,
  onMusicModeChange,
  readDataUsedBytes,
}) => {
  const { t, locale } = useTranslation();
  const snapshot = useSyncExternalStore(callEngine.subscribe, callEngine.getSnapshot);
  const [prefs, setPrefs] = useState<CallPrefs>(() => loadCallPrefs());
  const [dataBytes, setDataBytes] = useState<number | null>(null);
  const route = useAudioRoutes(snapshot.callId, open && (active ?? snapshot.state === 'active') && Boolean(musicMode ?? snapshot.musicMode));

  useEffect(() => {
    setPrefs(loadCallPrefs());
    return onCallPrefsChange((next) => setPrefs(next));
  }, []);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const val = await (readDataUsedBytes ? readDataUsedBytes() : callEngine.dataUsedBytes());
        if (!cancelled) {
          setDataBytes(typeof val === 'number' && Number.isFinite(val) ? val : null);
        }
      } catch {
        if (!cancelled) setDataBytes(null);
      }
    };
    void poll();
    const id = setInterval(() => void poll(), 2000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [open, readDataUsedBytes]);

  if (!open) return null;

  const net = currentNet((navigator as Navigator & { connection?: { type?: unknown } }).connection);
  const videoMB = callCostEstimate(net, 'video');
  const audioMB = callCostEstimate(net, 'audio');
  const formatCost = (mb: number): string => {
    const n = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(Math.round(mb));
    return `≈ ${n} ${t('callSettings.mb')} / ${t('media7.per10min')}`;
  };

  const isActive = active ?? snapshot.state === 'active';
  const isAudioOnly = Boolean(audioOnly ?? snapshot.audioOnly);
  const setAudioOnly = onAudioOnlyChange ?? ((next: boolean) => callEngine.setAudioOnly(next));
  const isMusic = Boolean(musicMode ?? snapshot.musicMode);
  const setMusic = onMusicModeChange ?? ((next: boolean) => void callEngine.setMusicMode(next));

  // Лише виміряне з getStats: немає числа — немає рядка.
  const st = snapshot.stats;
  const nf = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
  const soundParts: string[] = [];
  if (st?.micHz) soundParts.push(`${nf.format(st.micHz / 1000)} ${t('callSettings.khz')}`);
  if (typeof st?.audioKbpsOut === 'number') soundParts.push(`${st.audioKbpsOut} ${t('callSettings.kbps')}`);
  const soundLine = soundParts.join(' · ');
  const frame = (p?: number | null, fps?: number | null) => (p ? `${p}p${fps ? fps : ''}` : null);
  const videoParts = [
    frame(st?.sentP, st?.sentFps) && `↑ ${frame(st?.sentP, st?.sentFps)}`,
    frame(st?.recvP, st?.recvFps) && `↓ ${frame(st?.recvP, st?.recvFps)}`,
  ].filter(Boolean);
  const videoLine = isAudioOnly ? '' : videoParts.join(' · ');

  const updatePrefs = (patch: Partial<CallPrefs>) => {
    const next: CallPrefs = { ...prefs, ...patch };
    setPrefs(next);
    saveCallPrefs(next);
  };

  const modeLabel = (mode: CallMode): string => {
    if (mode === 'save') return t('callSettings.mode.save');
    if (mode === 'best') return t('callSettings.mode.best');
    return t('callSettings.mode.auto');
  };

  const videoLabel = (cap: VideoCap): string =>
    cap === 'auto' ? t('callSettings.mode.auto') : `${cap}p`;

  return (
    <FormSheet open onClose={onClose} title={t('callsHub.title')} layerClassName="z-[10000]" testId="call-settings-sheet">
      <div data-call-settings-sheet className="px-5 pb-2 flex flex-col gap-3">
        <span data-call-setting="scope" className="text-footnote text-ink-3">{t('callsHub.allCalls')}</span>
        <div
          role="group"
          aria-label={t('callSettings.wifi')}
          data-call-setting="wifi"
          className="flex min-h-[48px] flex-col gap-1.5"
        >
          <span className="text-callout text-ink-2">{t('callSettings.wifi')}</span>
          <div className="grid grid-cols-3 gap-1.5">
            {MODES.map((mode) => {
              const selected = prefs.wifi === mode;
              return (
                <button
                  key={mode}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => updatePrefs({ wifi: mode })}
                  className={`min-h-[48px] rounded-control border px-2.5 text-callout transition-colors ${chipTone(selected)}`}
                >
                  {modeLabel(mode)}
                </button>
              );
            })}
          </div>
        </div>

        <div
          role="group"
          aria-label={t('callSettings.mobile')}
          data-call-setting="mobile"
          className="flex min-h-[48px] flex-col gap-1.5"
        >
          <span className="text-callout text-ink-2">{t('callSettings.mobile')}</span>
          <div className="grid grid-cols-3 gap-1.5">
            {MODES.map((mode) => {
              const selected = prefs.mobile === mode;
              return (
                <button
                  key={mode}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => updatePrefs({ mobile: mode })}
                  className={`min-h-[48px] rounded-control border px-2.5 text-callout transition-colors ${chipTone(selected)}`}
                >
                  {modeLabel(mode)}
                </button>
              );
            })}
          </div>
        </div>

        <div
          role="group"
          aria-label={t('callSettings.video')}
          data-call-setting="video"
          className="flex min-h-[48px] flex-col gap-1.5"
        >
          <span className="text-callout text-ink-2">{t('callSettings.video')}</span>
          <div className="grid grid-cols-4 gap-1.5">
            {VIDEO_CAPS.map((cap) => {
              const selected = prefs.video === cap;
              return (
                <button
                  key={String(cap)}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => updatePrefs({ video: cap })}
                  className={`min-h-[48px] rounded-control border px-2 text-callout transition-colors ${chipTone(selected)}`}
                >
                  {videoLabel(cap)}
                </button>
              );
            })}
          </div>
        </div>

        {isActive && (
          <button
            type="button"
            role="switch"
            aria-checked={isAudioOnly}
            aria-label={t('callSettings.audioOnly')}
            data-call-setting="audio-only"
            onClick={() => setAudioOnly(!isAudioOnly)}
            className="flex min-h-[48px] w-full items-center gap-3 rounded-control text-left active:bg-raised transition-colors"
          >
            <span className="flex-1 min-w-0 text-callout text-ink">{t('callSettings.audioOnly')}</span>
            <span
              aria-hidden="true"
              data-switch-track
              className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${isAudioOnly ? 'bg-accent' : 'bg-overlay'}`}
            >
              <span
                className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${isAudioOnly ? 'translate-x-5 bg-on-accent' : 'bg-ink-2'}`}
              />
            </span>
          </button>
        )}

        {isActive && (
          <button
            type="button"
            role="switch"
            aria-checked={isMusic}
            aria-label={t('callSettings.music')}
            data-call-setting="music"
            onClick={() => setMusic(!isMusic)}
            className="flex min-h-[48px] w-full items-center gap-3 rounded-control text-left active:bg-raised transition-colors"
          >
            <span className="flex-1 min-w-0 text-callout text-ink">{t('callSettings.music')}</span>
            <span
              aria-hidden="true"
              data-switch-track
              className={`relative shrink-0 w-[52px] h-8 rounded-full transition-colors duration-instant ease-standard ${isMusic ? 'bg-accent' : 'bg-overlay'}`}
            >
              <span
                className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${isMusic ? 'translate-x-5 bg-on-accent' : 'bg-ink-2'}`}
              />
            </span>
          </button>
        )}

        {isActive && isMusic && onLoudspeaker(route.state) && (
          <div data-call-setting="music-hint" className="text-footnote text-ink-2">
            {t('callSettings.musicHint')}
          </div>
        )}

        {videoMB !== null && (
          <div
            data-call-setting="cost-video"
            data-call-cost="video"
            className="flex min-h-[48px] items-center justify-between gap-3 border-t border-line pt-2"
          >
            <span className="text-callout text-ink-2">{t('media7.video')}</span>
            <span className="text-callout text-ink tnum">{formatCost(videoMB)}</span>
          </div>
        )}

        {audioMB !== null && (
          <div
            data-call-setting="cost-audio"
            data-call-cost="audio"
            className="flex min-h-[48px] items-center justify-between gap-3 border-t border-line pt-2"
          >
            <span className="text-callout text-ink-2">{t('media7.audio')}</span>
            <span className="text-callout text-ink tnum">{formatCost(audioMB)}</span>
          </div>
        )}

        <div
          data-call-setting="data-used"
          className="flex min-h-[48px] items-center justify-between gap-3 border-t border-line pt-2"
        >
          <span className="text-callout text-ink-2">{t('callSettings.dataUsed')}</span>
          <span data-call-data-used className="text-callout text-ink tnum">
            {formatDataUsed(dataBytes, locale, t('callSettings.mb'))}
          </span>
        </div>

        {isActive && soundLine && (
          <div data-call-setting="sound-now" className="flex min-h-[48px] items-center justify-between gap-3">
            <span className="text-callout text-ink-2">{t('callSettings.sound')}</span>
            <span data-call-sound-now className="text-callout text-ink tnum">{soundLine}</span>
          </div>
        )}
        {isActive && videoLine && (
          <div data-call-setting="video-now" className="flex min-h-[48px] items-center justify-between gap-3">
            <span className="text-callout text-ink-2">{t('callSettings.video')}</span>
            <span data-call-video-now className="text-callout text-ink tnum">{videoLine}</span>
          </div>
        )}
      </div>
    </FormSheet>
  );
};
