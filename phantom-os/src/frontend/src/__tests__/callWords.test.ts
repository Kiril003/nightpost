import { describe, expect, it } from 'vitest';
import { tNow } from '../components/chat/tNow';
import { parseServe, weakSide } from '../services/callView';
import {
  CALL_JARGON,
  callStateLine,
  endedLine,
  linkLine,
  roomFullLine,
  stallLine,
} from '../services/callWords';

const quiet = {
  radio: null,
  radioReason: null,
  linkNote: null,
  videoDropped: false,
} as const;

describe('callWords & weakSide (media-6)', () => {
  describe('weakSide', () => {
    it('returns me when outbound loss is high and inbound is clean', () => {
      expect(
        weakSide({ upLossPct: 18, downLossPct: 0, rttMs: 80, peerUpLossPct: 0 }),
      ).toBe('me');
    });

    it('returns them when peer outbound loss is high and our outbound is clean', () => {
      expect(
        weakSide({ upLossPct: 0, downLossPct: 15, rttMs: 80, peerUpLossPct: 15 }),
      ).toBe('them');
    });

    it('returns path when inbound loss is high but peerUpLossPct is null (audio call, no serve)', () => {
      expect(
        weakSide({ upLossPct: 0, downLossPct: 15, rttMs: 80, peerUpLossPct: null }),
      ).toBe('path');
    });

    it('returns path when both up and down losses are high', () => {
      expect(
        weakSide({ upLossPct: 15, downLossPct: 15, rttMs: 80, peerUpLossPct: 15 }),
      ).toBe('path');
    });

    it('returns path when losses are 0 but rttMs is high', () => {
      expect(
        weakSide({ upLossPct: 0, downLossPct: 0, rttMs: 600, peerUpLossPct: 0 }),
      ).toBe('path');
    });

    it('returns null when all metrics are clean', () => {
      expect(
        weakSide({ upLossPct: 0, downLossPct: 0, rttMs: 80, peerUpLossPct: 0 }),
      ).toBeNull();
    });
  });

  describe('parseServe', () => {
    const baseFrame = {
      t: 'serve',
      v: 1,
      seq: 3,
      active: true,
      h: 720,
      fps: 30,
      kbps: 2000,
      mbh: 450,
    };

    it('keeps valid up loss percentage', () => {
      const parsed = parseServe({ ...baseFrame, up: 18 });
      expect(parsed).not.toBeNull();
      expect(parsed?.up).toBe(18);
    });

    it('clamps up loss percentage to 100', () => {
      const parsed = parseServe({ ...baseFrame, up: 500 });
      expect(parsed).not.toBeNull();
      expect(parsed?.up).toBe(100);
    });

    it('parses old frame without up: no up field, peer loss unknown', () => {
      const parsed = parseServe(baseFrame);
      expect(parsed).not.toBeNull();
      expect(parsed?.up ?? null).toBeNull();
    });
  });

  describe('linkLine', () => {
    it('shows weakMe when weakSide is me', () => {
      const line = linkLine({ ...quiet, weakSide: 'me' });
      expect(line).toBe(tNow('callWords.weakMe'));
      expect(line).toBe('Слабкий звʼязок у вас');
      expect(line).not.toMatch(CALL_JARGON);
    });

    it('shows weakThem with peer name when weakSide is them', () => {
      const line = linkLine({
        ...quiet,
        weakSide: 'them',
        peer: { displayName: 'Олена' },
      });
      expect(line).toContain('Олена');
      expect(line).toBe(tNow('callWords.weakThem', { name: 'Олена' }));
      expect(line).not.toMatch(CALL_JARGON);
    });

    it('falls back to generic weak when weakSide is them but peer name is empty', () => {
      const line = linkLine({
        ...quiet,
        weakSide: 'them',
        peer: { displayName: '   ' },
      });
      expect(line).toBe(tNow('callWords.weak'));
      expect(line).not.toMatch(CALL_JARGON);
    });

    it('shows generic weak when weakSide is path', () => {
      const line = linkLine({ ...quiet, weakSide: 'path' });
      expect(line).toBe(tNow('callWords.weak'));
      expect(line).not.toMatch(CALL_JARGON);
    });

    it('prioritizes radio over weakSide', () => {
      const line = linkLine({
        ...quiet,
        radioReason: 'пряма доріжка так і не встала',
        weakSide: 'me',
        peer: { displayName: 'Олена' },
      });
      expect(line).toBe(tNow('callWords.weak'));
      expect(line).not.toMatch(CALL_JARGON);
    });

    it('keeps human call words free of technical jargon', () => {
      const samples = [
        callStateLine({ state: 'calling', media: 'audio', stall: null, endedReason: null }),
        callStateLine({ state: 'ringing', media: 'video', stall: null, endedReason: null }),
        stallLine({ kind: 'no-answer', note: 'raw' }),
        stallLine({ kind: 'no-path', note: 'raw' }),
        endedLine('завершено'),
        endedLine('TURN error 437'),
        roomFullLine(8),
        linkLine({ ...quiet, weakSide: 'me' }) ?? '',
        linkLine({ ...quiet, weakSide: 'them', peer: { displayName: 'Олена' } }) ?? '',
      ];
      for (const s of samples) {
        expect(s).not.toMatch(CALL_JARGON);
      }
    });
  });
});
