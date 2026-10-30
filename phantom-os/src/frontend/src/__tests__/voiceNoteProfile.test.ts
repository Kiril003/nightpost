import { describe, expect, it } from 'vitest';
import { pickVoiceMime, voiceNoteProfile } from '../hooks/voiceNoteProfile';

describe('voiceNoteProfile', () => {
  it('returns 48 kHz mono 48 kbps with voice filters enabled for voice mode', () => {
    expect(voiceNoteProfile('voice')).toEqual({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
        sampleRate: 48000,
      },
      bitsPerSecond: 48000,
    });
    expect(voiceNoteProfile()).toEqual(voiceNoteProfile('voice'));
  });

  it('returns 48 kHz stereo 128 kbps with voice filters disabled for music mode', () => {
    expect(voiceNoteProfile('music')).toEqual({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        channelCount: 2,
        sampleRate: 48000,
      },
      bitsPerSecond: 128000,
    });
  });
});

describe('pickVoiceMime', () => {
  it('prefers webm opus, then ogg opus, then mp4, and falls back to empty string', () => {
    expect(pickVoiceMime(() => true)).toBe('audio/webm;codecs=opus');
    expect(pickVoiceMime((m) => m !== 'audio/webm;codecs=opus')).toBe('audio/ogg;codecs=opus');
    expect(pickVoiceMime((m) => m === 'audio/mp4')).toBe('audio/mp4');
    expect(pickVoiceMime(() => false)).toBe('');
  });
});
