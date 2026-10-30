import { describe, it, expect } from 'vitest';
import placeCard from '../components/map/PlaceCard.tsx?raw';
import hudShell from '../components/map/hud/HudShell.tsx?raw';

const bottom = (src: string, marker: RegExp) => {
  const m = src.match(marker);
  expect(m, String(marker)).not.toBeNull();
  return Number(m![1]);
};

describe('PC map place card', () => {
  it('sits above the bottom search row instead of covering it', () => {
    const row = bottom(hudShell, /absolute bottom-\[(\d+)px\] left-\[76px\] right-\[76px\] flex items-end/);
    const card = bottom(placeCard, /absolute bottom-\[(\d+)px\] left-1\/2/);
    expect(card).toBeGreaterThanOrEqual(row + 44 + 12);
  });
});
