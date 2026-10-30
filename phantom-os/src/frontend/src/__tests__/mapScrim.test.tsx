import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { OfflinePanel } from '../components/map/panels/OfflinePanel';
import { LayerLibraryPanel } from '../components/map/panels/LayerLibraryPanel';
import tacticalSrc from '../components/map/TacticalMap.tsx?raw';

vi.mock('../services/api', async (orig) => ({
  ...(await orig<object>()),
  mapApi: { getLayers: vi.fn(() => new Promise(() => {})), getOfflineRegions: vi.fn(() => new Promise(() => {})) },
}));

describe('PC map panels and dialogs sit on a scrim and close on the first outside click', () => {
  it.each([
    ['OfflinePanel', (onClose: () => void) => <OfflinePanel open onClose={onClose} />],
    ['LayerLibraryPanel', (onClose: () => void) => <LayerLibraryPanel open onClose={onClose} />],
  ])('%s', (_name, mount) => {
    const onClose = vi.fn();
    const { container } = render(mount(onClose));
    const backdrop = container.firstElementChild as HTMLElement;
    expect(backdrop.className).toContain('bg-[color:var(--ph-scrim)]');
    fireEvent.click(backdrop);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('both TacticalMap dialogs use the scrim', () => {
    const backdrops = tacticalSrc.match(/absolute inset-0 z-40 flex items-center justify-center bg-\[color:var\(--ph-scrim\)\]/g) ?? [];
    expect(backdrops).toHaveLength(2);
  });
});
