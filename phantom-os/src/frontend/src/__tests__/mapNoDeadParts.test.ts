import { describe, it, expect } from 'vitest';

const SOURCES = import.meta.glob(['../**/*.{ts,tsx}', '!../__tests__/**', '!../**/*.test.{ts,tsx}'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const MAP_PARTS = Object.keys(SOURCES).filter((p) => p.startsWith('../components/map/'));

describe('PC map: no part ships without a caller', () => {
  it.each(MAP_PARTS)('%s is imported by app code', (path) => {
    const stem = path.split('/').pop()!.replace(/\.tsx?$/, '');
    const importer = new RegExp(`(from\\s+|import\\()['"][^'"]*/${stem}(\\.tsx?)?['"]`);
    const callers = Object.entries(SOURCES).filter(([p, src]) => p !== path && importer.test(src));
    expect(callers.map(([p]) => p), path).not.toEqual([]);
  });
});
