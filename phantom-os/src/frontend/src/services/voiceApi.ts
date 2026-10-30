/**
 * Voice API client — talks to the Phase-07 /api/v1/voice routes.
 *
 * All calls go through the same fetch helper as the rest of api.ts (cookie
 * + bearer token, 401 propagation). STT takes a Blob (MediaRecorder output
 * or a file), TTS returns a Blob so the caller can turn it into an
 * object URL for <audio>.
 */

import { request, BASE } from './api';
import { onPhone, phoneRequest } from './phonePort';
import { readToken } from './tokenStore';

function _authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const token = readToken();
  const headers: Record<string, string> = { ...extra };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

export interface VoiceSTTResponse {
  text: string;
  confidence: number;
  engine: 'whisper' | 'vosk' | 'noop' | string;
  language: string;
  wake_word_matched: boolean;
}

export interface VoiceStatusResponse {
  stt_engine: string;
  tts_engine: string;
  stt_mode: string;
  language: string;
  tts_enabled: boolean;
  tts_voice: string;
  wake_word_enabled: boolean;
  wake_words: string;
}

const STT_TIMEOUT_MS = 30_000;

async function blobToBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export const voiceApi = {
  /** Upload a recorded clip and get the transcript back. */
  transcribe: async (blob: Blob, filename = 'clip.webm'): Promise<VoiceSTTResponse> => {
    const phone = onPhone()
      ? phoneRequest(
          'POST',
          '/messenger/voice/transcribe',
          JSON.stringify({ mime: blob.type || 'audio/webm', data_b64: await blobToBase64(blob) }),
        )
      : null;
    if (phone) {
      const env = await phone;
      if (env.ok) {
        const d = (env.data ?? {}) as { text?: unknown };
        return {
          text: typeof d.text === 'string' ? d.text : '',
          confidence: 1,
          engine: 'phone',
          language: '',
          wake_word_matched: false,
        };
      }
      if (env.status === 503 && env.code === 'no_engine') {
        return {
          text: '',
          confidence: 0,
          engine: 'phone',
          language: '',
          wake_word_matched: false,
        };
      }
      throw new Error(env.detail || env.code || `STT HTTP ${env.status}`);
    }
    const form = new FormData();
    form.append('file', blob, filename);
    const res = await fetch(`${BASE}/voice/stt`, {
      method: 'POST',
      credentials: 'include',
      headers: _authHeaders(),
      body: form,
      signal: AbortSignal.timeout(STT_TIMEOUT_MS),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'STT failed' }));
      throw new Error(err.detail || `STT HTTP ${res.status}`);
    }
    return (await res.json()) as VoiceSTTResponse;
  },

  /**
   * Synthesize `text` to a WAV blob. Empty voice/speed fall through to
   * whatever is set in Settings → Voice.
   */
  synthesize: async (
    text: string,
    opts: { voice?: string; speed?: number; emotionScale?: number } = {}
  ): Promise<{ blob: Blob; engine: string; sampleRate: number }> => {
    const res = await fetch(`${BASE}/voice/tts`, {
      method: 'POST',
      credentials: 'include',
      headers: _authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        text,
        voice: opts.voice ?? '',
        speed: opts.speed ?? 1.0,
        emotion_scale: opts.emotionScale ?? 1.0,
      }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'TTS failed' }));
      throw new Error(err.detail || `TTS HTTP ${res.status}`);
    }
    const blob = await res.blob();
    return {
      blob,
      engine: res.headers.get('X-Engine') ?? 'unknown',
      sampleRate: Number(res.headers.get('X-Sample-Rate') ?? 22050),
    };
  },

  status: () => request<VoiceStatusResponse>('GET', '/voice/status'),

  /**
   * The operator's explicit "stop talking" — the only interrupt that
   * doesn't require saying or typing something else. `stopped: false`
   * just means nothing was on air when the request landed (not an
   * error — the caller races the last sentence ending on its own).
   */
  stop: () => request<{ stopped: boolean }>('POST', '/voice/stop'),
};
