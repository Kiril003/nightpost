import { describe, it, expect } from 'vitest';
import { watchBaseTiles, TILES_OK_TO_CLEAR } from '../components/map/tilesWatch';

type Listener = (e: unknown) => void;

function fakeMap() {
  const listeners: Record<string, Listener[]> = {};
  return {
    on(evt: string, cb: Listener) {
      (listeners[evt] ??= []).push(cb);
    },
    off(evt: string, cb: Listener) {
      listeners[evt] = (listeners[evt] ?? []).filter((l) => l !== cb);
    },
    emit(evt: string, e: unknown) {
      for (const l of listeners[evt] ?? []) l(e);
    },
    count: (evt: string) => (listeners[evt] ?? []).length,
  };
}

const omtError = { sourceId: 'openmaptiles', error: { message: 'Failed to fetch' } };
const omtTile = { sourceId: 'openmaptiles', tile: {} };

describe('R0-12 offline banner: one transition, base tiles only', () => {
  it('errors from other sources never raise the banner', () => {
    const map = fakeMap();
    const log: Array<string | null> = [];
    watchBaseTiles(map, (d) => log.push(d));
    for (let i = 0; i < 10; i += 1) {
      map.emit('error', { sourceId: 'terrain-dem', error: { message: '404' } });
      map.emit('data', omtTile);
    }
    expect(log).toEqual([]);
  });

  it('interleaved base errors and tiles do not loop the banner', () => {
    const map = fakeMap();
    const log: Array<string | null> = [];
    watchBaseTiles(map, (d) => log.push(d));
    for (let i = 0; i < 10; i += 1) {
      map.emit('error', omtError);
      map.emit('data', omtTile);
    }
    expect(log).toEqual(['нема зв’язку з сервером тайлів']);
  });

  it('clears once after enough good base tiles in a row', () => {
    const map = fakeMap();
    const log: Array<string | null> = [];
    watchBaseTiles(map, (d) => log.push(d));
    map.emit('error', omtError);
    for (let i = 0; i < TILES_OK_TO_CLEAR + 5; i += 1) map.emit('data', omtTile);
    expect(log).toEqual(['нема зв’язку з сервером тайлів', null]);
  });

  it('detach removes both listeners', () => {
    const map = fakeMap();
    const detach = watchBaseTiles(map, () => {});
    expect(map.count('error') + map.count('data')).toBe(2);
    detach();
    expect(map.count('error') + map.count('data')).toBe(0);
  });
});
