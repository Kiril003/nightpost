import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { mapApi } from '../services/api';
import { SearchResultsPanel } from '../components/map/panels/SearchResultsPanel';
import { StoryPanel } from '../components/map/panels/StoryPanel';
import omniMapSrc from '../components/map/OmniMap.tsx?raw';

const FORBIDDEN = /bg-ink-primary|backdrop-blur|shadow|gradient|glass/;

function allClasses(root: Element): string {
  return [root, ...Array.from(root.querySelectorAll('*'))]
    .map((el) => el.getAttribute('class') ?? '')
    .join(' ');
}

afterEach(() => vi.restoreAllMocks());

describe('R0-10 PC map panels: D tokens, no ink-on-ink', () => {
  it('SearchResultsPanel paints --ph-text on --ph-surface and carries no effects', () => {
    const { container } = render(
      <SearchResultsPanel
        open
        onClose={() => {}}
        onSelect={() => {}}
        results={{
          remembered: [],
          pois: [],
          osm: [{ id: 'o1', name: 'Ostrava', category: 'city', lat: 49.8, lon: 18.2 }],
        }}
      />,
    );
    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toContain('bg-[color:var(--ph-surface)]');
    expect(root.className).toContain('text-[color:var(--ph-text)]');
    expect(allClasses(root)).not.toMatch(FORBIDDEN);
    expect(screen.getByText('Ostrava')).toBeInTheDocument();
  });

  it('StoryPanel paints --ph-text on --ph-surface and carries no effects', async () => {
    vi.spyOn(mapApi, 'getGeoTaggedFacts').mockResolvedValue({ facts: [], total: 0 });
    let container!: HTMLElement;
    await act(async () => {
      ({ container } = render(<StoryPanel open onClose={() => {}} />));
    });
    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toContain('bg-[color:var(--ph-surface)]');
    expect(root.className).toContain('text-[color:var(--ph-text)]');
    expect(allClasses(root)).not.toMatch(FORBIDDEN);
  });

  it('OmniMap gives StoryPanel no default city centre', () => {
    expect(omniMapSrc).not.toMatch(/30\.52|50\.45/);
  });
});
