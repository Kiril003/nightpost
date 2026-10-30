import { cachedMediaUrl } from './messengerMedia';
import type { Message, SecureMedia } from '../types/messenger';

export interface VoiceQueueItem {
  id: string;
  url: string;
  durationSec: number;
  ts: number;
  media?: SecureMedia;
}

export const VOICE_PLAYED_KEY = 'np.voicePlayed.v1';
export const ROW_GAP_MS = 10 * 60 * 1000;

const MAX_PLAYED_ITEMS = 500;

export function loadPlayed(): Set<string> {
  try {
    const raw = localStorage.getItem(VOICE_PLAYED_KEY);
    if (!raw) return new Set();
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((item): item is string => typeof item === 'string'));
  } catch {
    return new Set();
  }
}

export function markPlayed(id: string): void {
  if (!id) return;
  try {
    const played = loadPlayed();
    played.delete(id);
    played.add(id);
    const list = Array.from(played);
    const trimmed = list.length > MAX_PLAYED_ITEMS ? list.slice(list.length - MAX_PLAYED_ITEMS) : list;
    localStorage.setItem(VOICE_PLAYED_KEY, JSON.stringify(trimmed));
  } catch {
    // Ігноруємо помилки доступу до сховища
  }
}

export function nextInRow(
  queue: readonly VoiceQueueItem[],
  currentId: string,
  played: ReadonlySet<string>,
): VoiceQueueItem | null {
  const idx = queue.findIndex((item) => item.id === currentId);
  if (idx < 0) return null;
  const current = queue[idx];
  for (let i = idx + 1; i < queue.length; i += 1) {
    const item = queue[i];
    if (played.has(item.id)) continue;
    return item.ts - current.ts <= ROW_GAP_MS ? item : null;
  }
  return null;
}

/** Черга розмови з голосових, чий звук уже є на пристрої; без звуку — пропуск. */
export function voiceQueueOf(messages: readonly Message[]): VoiceQueueItem[] {
  const out: VoiceQueueItem[] = [];
  for (const m of messages) {
    const v = m.voiceData;
    if (!v || m.isDeleted) continue;
    const url = v.media ? (v.media.blobId ? cachedMediaUrl(v.media.blobId) : m.localPreviewUrl) : v.audioUrl;
    const parsedSentAt = m.sentAt ? Date.parse(m.sentAt) : NaN;
    const ts = Number.isFinite(parsedSentAt) ? parsedSentAt : Date.parse(m.timestamp);
    out.push({
      id: m.id,
      url: url ?? '',
      durationSec: v.duration,
      ts: Number.isFinite(ts) ? ts : 0,
      ...(v.media ? { media: v.media } : {}),
    });
  }
  return out;
}
