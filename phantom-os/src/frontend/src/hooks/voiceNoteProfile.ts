export type VoiceNoteMode = 'voice' | 'music';

export interface VoiceNoteProfile {
  audio: MediaTrackConstraints;
  bitsPerSecond: number;
}

const VOICE_MIME_CANDIDATES = [
  'audio/webm;codecs=opus',
  'audio/ogg;codecs=opus',
  'audio/mp4',
] as const;

export function voiceNoteProfile(mode: VoiceNoteMode = 'voice'): VoiceNoteProfile {
  if (mode === 'music') {
    return {
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        channelCount: 2,
        sampleRate: 48000,
      },
      bitsPerSecond: 128000,
    };
  }
  return {
    audio: {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
      channelCount: 1,
      sampleRate: 48000,
    },
    bitsPerSecond: 48000,
  };
}

export function pickVoiceMime(isTypeSupported: (mime: string) => boolean): string {
  for (const mime of VOICE_MIME_CANDIDATES) {
    try {
      if (isTypeSupported(mime)) return mime;
    } catch {
      /* ignore */
    }
  }
  return '';
}
