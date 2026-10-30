import { create } from 'zustand';
import { tNow } from '../components/chat/tNow';
import { cachedMediaUrl, loadMedia } from '../services/messengerMedia';
import {
  loadPlayed,
  markPlayed,
  nextInRow,
  type VoiceQueueItem,
} from '../services/voiceQueue';

interface AudioPlaybackState {
  currentMsgId: string | null;
  audioUrl: string | null;
  isPlaying: boolean;
  progress: number; // 0..1
  currentTime: number; // seconds
  duration: number; // seconds
  playbackRate: number; // 1, 1.5, 2
  error: string | null;
  queue: VoiceQueueItem[];

  play: (msgId: string, url: string, initialDuration?: number, queue?: VoiceQueueItem[]) => void;
  pause: () => void;
  toggle: (msgId: string, url: string, initialDuration?: number, queue?: VoiceQueueItem[]) => void;
  seek: (progressFraction: number) => void;
  setPlaybackRate: (rate: number) => void;
  cyclePlaybackRate: () => void;
  stop: () => void;
}

let globalAudio: HTMLAudioElement | null = null;

function getOrCreateAudio(): HTMLAudioElement {
  if (!globalAudio && typeof Audio !== 'undefined') {
    globalAudio = new Audio();
  }
  return globalAudio!;
}

export const useAudioPlaybackStore = create<AudioPlaybackState>((set, get) => ({
  currentMsgId: null,
  audioUrl: null,
  isPlaying: false,
  progress: 0,
  currentTime: 0,
  duration: 0,
  playbackRate: 1,
  error: null,
  queue: [],

  play: (msgId: string, url: string, initialDuration: number = 0, queue?: VoiceQueueItem[]) => {
    const audio = getOrCreateAudio();
    if (!audio) return;

    const existingQueue = get().queue;
    const kept = existingQueue.some((item) => item.id === msgId) ? existingQueue : [];
    const nextQueue = queue ?? kept;

    // Same audio resumed
    if (get().currentMsgId === msgId && get().audioUrl === url) {
      set({ queue: nextQueue });
      audio.playbackRate = get().playbackRate;
      audio.play().then(() => {
        markPlayed(msgId);
        set({ isPlaying: true, error: null });
      }).catch((e) => {
        console.warn('[AudioPlayback] Resume failed:', e);
        set({ isPlaying: false, error: tNow('audioPlayback.playError') });
      });
      return;
    }

    // New audio track
    audio.pause();
    audio.src = url;
    audio.playbackRate = get().playbackRate;
    audio.currentTime = 0;

    audio.ontimeupdate = () => {
      if (!audio || !audio.duration) return;
      set({
        currentTime: audio.currentTime,
        progress: Math.min(1, Math.max(0, audio.currentTime / audio.duration)),
      });
    };

    audio.onended = () => {
      set({ isPlaying: false, progress: 0, currentTime: 0 });
      const { currentMsgId, queue: currentQueue } = get();
      const next = currentMsgId ? nextInRow(currentQueue, currentMsgId, loadPlayed()) : null;
      if (!next) return;
      const liveUrl = next.url || (next.media?.blobId ? cachedMediaUrl(next.media.blobId) : undefined);
      if (liveUrl) {
        get().play(next.id, liveUrl, next.durationSec, currentQueue);
      } else if (next.media?.blobId) {
        void loadMedia(next.media)
          .then((u) => {
            get().play(next.id, u, next.durationSec, currentQueue);
          })
          .catch(() => undefined);
      }
    };

    audio.onerror = () => {
      set({ isPlaying: false, error: tNow('audioPlayback.loadError') });
    };

    set({ currentMsgId: msgId, audioUrl: url, queue: nextQueue });

    audio.play().then(() => {
      markPlayed(msgId);
      const dur = (Number.isFinite(audio.duration) && audio.duration > 0)
        ? audio.duration
        : initialDuration;
      set({
        currentMsgId: msgId,
        audioUrl: url,
        isPlaying: true,
        progress: 0,
        currentTime: 0,
        duration: dur,
        error: null,
      });
    }).catch((e) => {
      console.warn('[AudioPlayback] Play failed:', e);
      set({
        currentMsgId: msgId,
        audioUrl: url,
        isPlaying: false,
        error: tNow('audioPlayback.blocked'),
      });
    });
  },

  pause: () => {
    if (globalAudio) {
      globalAudio.pause();
    }
    set({ isPlaying: false });
  },

  toggle: (msgId: string, url: string, initialDuration: number = 0, queue?: VoiceQueueItem[]) => {
    const state = get();
    if (state.currentMsgId === msgId && state.isPlaying) {
      state.pause();
    } else {
      state.play(msgId, url, initialDuration, queue);
    }
  },

  seek: (progressFraction: number) => {
    const audio = globalAudio;
    if (!audio) return;
    const dur = audio.duration || get().duration;
    if (dur > 0 && Number.isFinite(dur)) {
      const clamped = Math.min(1, Math.max(0, progressFraction));
      const targetTime = clamped * dur;
      audio.currentTime = targetTime;
      set({
        currentTime: targetTime,
        progress: clamped,
      });
    }
  },

  setPlaybackRate: (rate: number) => {
    if (globalAudio) {
      globalAudio.playbackRate = rate;
    }
    set({ playbackRate: rate });
  },

  cyclePlaybackRate: () => {
    const current = get().playbackRate;
    const next = current === 1 ? 1.5 : current === 1.5 ? 2 : 1;
    if (globalAudio) {
      globalAudio.playbackRate = next;
    }
    set({ playbackRate: next });
  },

  stop: () => {
    if (globalAudio) {
      globalAudio.pause();
      globalAudio.currentTime = 0;
    }
    set({
      currentMsgId: null,
      audioUrl: null,
      isPlaying: false,
      progress: 0,
      currentTime: 0,
      error: null,
      queue: [],
    });
  },
}));
