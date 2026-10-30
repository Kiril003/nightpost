import { describe, expect, it } from 'vitest';
import { viewerFor } from '../services/preview/registry';
import registryRaw from '../services/preview/registry.ts?raw';

describe('preview registry', () => {
  it('returns none/unknown for unknown extensions or executable mimes', () => {
    expect(viewerFor('a.xyz', '', 10)).toEqual({ family: 'none', reason: 'unknown' });
    expect(viewerFor('a.exe', 'application/x-msdownload', 10)).toEqual({
      family: 'none',
      reason: 'unknown',
    });
  });

  it('maps supported extensions to their preview family', () => {
    for (const name of ['notes.txt', 'app.log', 'data.json', 'cfg.yaml', 'main.py']) {
      const entry = viewerFor(name, '', 120);
      expect(entry.family).toBe('text');
    }
    expect(viewerFor('README.md', 'text/markdown', 120).family).toBe('markdown');
    expect(viewerFor('t.csv', 'text/csv', 120).family).toBe('csv');
    expect(viewerFor('x.svg', 'image/svg+xml', 120).family).toBe('svg');
    expect(viewerFor('p.jpg', 'image/jpeg', 120).family).toBe('image');
    expect(viewerFor('p.avif', 'image/avif', 120).family).toBe('image');
  });

  it('rejects mismatched mime for a known extension', () => {
    expect(viewerFor('notes.txt', 'image/png', 120)).toEqual({
      family: 'none',
      reason: 'unknown',
    });
  });

  it('returns none/too-big when size exceeds the family limit', () => {
    expect(viewerFor('notes.txt', 'text/plain', 20 * 1024 * 1024)).toEqual({
      family: 'none',
      reason: 'too-big',
    });
  });

  it('lazily loads a viewer component for every previewable family', async () => {
    const samples: Array<[string, string]> = [
      ['p.jpg', 'image/jpeg'],
      ['x.svg', 'image/svg+xml'],
      ['notes.txt', 'text/plain'],
      ['README.md', 'text/markdown'],
      ['t.csv', 'text/csv'],
    ];
    for (const [name, mime] of samples) {
      const entry = viewerFor(name, mime, 64);
      expect(entry.family).not.toBe('none');
      if (entry.family !== 'none') {
        const mod = await entry.load();
        expect(typeof mod.default).toBe('function');
      }
    }
  });

  it('has no static import of ImageViewer or TextViewer', () => {
    expect(registryRaw).not.toMatch(
      /import\s+[^;]*from\s+['"][^'"]*\/preview\/(Image|Text)Viewer['"]/,
    );
    expect(registryRaw).toContain("import('../../components/messenger/preview/ImageViewer')");
    expect(registryRaw).toContain("import('../../components/messenger/preview/TextViewer')");
  });
});
