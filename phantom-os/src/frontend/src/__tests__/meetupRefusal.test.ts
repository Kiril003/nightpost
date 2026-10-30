/**
 * Відмова мосту — словами, і правильними: «телефон ще не вміє» лише тоді,
 * коли шляху справді немає (старий міст, 405/501, власне «немає такого
 * шляху» мосту, FastAPI «Not Found» на ПК). 404 від самого сервера —
 * «не знайдено» його словами: заклад зняли з публікації, листа немає.
 */
import { describe, expect, it } from 'vitest';
import { ApiError } from '../services/api';
import { refusalWords } from '../services/meetupApi';

describe('refusalWords', () => {
  it('заклад, якого вже немає (404 сервера) — не «потрібне оновлення», а «не знайдено» його словами', () => {
    const said = refusalWords(new ApiError(404, 'UNKNOWN', 'Немає такого закладу'), 'svc.meetup.what.venuePages');
    expect(said).not.toContain('оновлення');
    expect(said).toContain('Немає такого закладу');
  });

  it('листа, на якому позначка, міст не знає (no_message) — теж не «оновлення»', () => {
    const said = refusalWords(new ApiError(404, 'no_message', 'листа немає'), 'svc.meetup.what.marks');
    expect(said).not.toContain('оновлення');
    expect(said).toContain('листа немає');
  });

  it('старий міст без шляху — «ще не вміє … оновлення»', () => {
    for (const e of [
      new ApiError(404, 'not_found', 'немає такого шляху: GET /messenger/venues/near'),
      new ApiError(404, 'not_found', 'no such path: GET /messenger/venues/near'),
      new ApiError(404, 'not_bridged', 'через міст телефона це ще не їде: venues'),
      new ApiError(404, 'UNKNOWN', 'Not Found'),
      new ApiError(405, 'UNKNOWN', 'Method Not Allowed'),
      new ApiError(501, 'UNKNOWN', 'x'),
    ]) {
      expect(refusalWords(e, 'svc.meetup.what.near')).toContain('ще не вміє');
    }
  });
});
