import { describe, expect, it } from 'vitest';
import { AUDIO_CAPTURE, AUDIO_LEVELS, AUDIO_PROFILES, tuneOpus } from '../services/callOpus';

/** Шматок справжнього offer із Chrome — рівно те, що приходить у рушій. */
const SDP = [
  'v=0',
  'o=- 4611731400430051336 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8',
  'c=IN IP4 0.0.0.0',
  'a=rtpmap:111 opus/48000/2',
  'a=fmtp:111 minptime=10;useinbandfec=1',
  'a=rtpmap:63 red/48000/2',
  'a=ptime:20',
].join('\r\n');

const fmtpOf = (sdp: string): Map<string, string> => {
  const line = sdp.split(/\r\n|\n/).find((l) => l.startsWith('a=fmtp:111 ')) ?? '';
  const map = new Map<string, string>();
  line
    .slice('a=fmtp:111 '.length)
    .split(';')
    .filter(Boolean)
    .forEach((kv) => {
      const eq = kv.indexOf('=');
      map.set(eq < 0 ? kv : kv.slice(0, eq), eq < 0 ? '' : kv.slice(eq + 1));
    });
  return map;
};

describe('tuneOpus', () => {
  /**
   * 08.10, P0-5: верх драбини — Opus МОНО на повну смугу, 64 кбіт/с. Стерео
   * 160k на мікрофон телефона, що пише один канал, — подвійна ціна за той
   * самий голос; стерео лишено майбутньому режиму музики/екрана.
   * Червоніє на коді до 08.10: там стояло stereo=1 і 160000.
   */
  it('сходинка 0 — рівно моно fullband 64k: stereo=0, sprop-stereo=0, maxplaybackrate=48000, FEC, без DTX, ptime 20', () => {
    const top = AUDIO_LEVELS[0];
    const params = fmtpOf(tuneOpus(SDP, top));
    expect(params.get('stereo')).toBe('0');
    expect(params.get('sprop-stereo')).toBe('0');
    expect(params.get('maxplaybackrate')).toBe('48000');
    expect(params.get('maxaveragebitrate')).toBe('64000');
    expect(params.get('useinbandfec')).toBe('1');
    expect(params.get('usedtx')).toBe('0');
    expect(params.get('ptime')).toBe('20');
    expect(tuneOpus(SDP, top)).toContain('a=ptime:20');
  });

  it('нижні сходинки — як були: економний 32k/40 мс, вузький 10k/120 мс, обидва з DTX', () => {
    expect(AUDIO_PROFILES.thrifty).toMatchObject({ bitrate: 32000, ptime: 40, dtx: true, stereo: false });
    expect(AUDIO_PROFILES.narrow).toMatchObject({ bitrate: 10000, ptime: 120, dtx: true, stereo: false, cbr: true });
    // Драбина спускається: кожна наступна сходинка не дорожча за попередню.
    const rates = AUDIO_LEVELS.map((l) => AUDIO_PROFILES[l].bitrate);
    expect([...rates].sort((a, b) => b - a)).toEqual(rates);
  });

  it('жодна сходинка не просить стерео — мікрофон телефона пише один канал', () => {
    AUDIO_LEVELS.forEach((level) => {
      const params = fmtpOf(tuneOpus(SDP, level));
      expect(params.get('useinbandfec')).toBe('1');
      expect(params.get('stereo'), level).toBe('0');
      expect(params.get('sprop-stereo'), level).toBe('0');
    });
  });

  it('захоплення — один канал 48 кГц з обробкою голосу', () => {
    expect(AUDIO_CAPTURE.channelCount).toEqual({ ideal: 1 });
    expect(AUDIO_CAPTURE.sampleRate).toEqual({ ideal: 48000 });
    expect(AUDIO_CAPTURE.echoCancellation).toBe(true);
    expect(AUDIO_CAPTURE.noiseSuppression).toBe(true);
  });

  it('несе бітрейт і ptime своєї сходинки', () => {
    (['crystal', 'full', 'thrifty', 'narrow'] as const).forEach((level) => {
      const tuned = tuneOpus(SDP, level);
      const params = fmtpOf(tuned);
      const profile = AUDIO_PROFILES[level];
      expect(params.get('maxaveragebitrate')).toBe(String(profile.bitrate));
      expect(params.get('maxptime')).toBe(String(profile.maxptime));
      expect(tuned).toContain(`a=ptime:${profile.ptime}`);
      expect(tuned).toContain(`a=maxptime:${profile.maxptime}`);
    });
  });

  it('cbr вмикає лише на найвужчій сходинці', () => {
    expect(fmtpOf(tuneOpus(SDP, 'narrow')).get('cbr')).toBe('1');
    expect(fmtpOf(tuneOpus(SDP, 'full')).get('cbr')).toBe('0');
    expect(fmtpOf(tuneOpus(SDP, 'crystal')).get('cbr')).toBe('0');
  });

  it('maxplaybackrate не лишається від верхньої сходинки на нижній', () => {
    const once = tuneOpus(SDP, 'crystal');
    expect(fmtpOf(tuneOpus(once, 'narrow')).has('maxplaybackrate')).toBe(false);
  });

  it('не викидає чужих параметрів, які поставив браузер', () => {
    expect(fmtpOf(tuneOpus(SDP, 'narrow')).get('minptime')).toBe('10');
  });

  it('лишає рівно один ptime — інакше опис недійсний', () => {
    const lines = tuneOpus(SDP, 'narrow').split('\r\n');
    expect(lines.filter((l) => l.startsWith('a=ptime:'))).toHaveLength(1);
    expect(lines.filter((l) => l.startsWith('a=maxptime:'))).toHaveLength(1);
  });

  it('без opus у SDP нічого не чіпає', () => {
    const noOpus = 'v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 8\r\na=rtpmap:8 PCMA/8000';
    expect(tuneOpus(noOpus, 'narrow')).toBe(noOpus);
  });
});
