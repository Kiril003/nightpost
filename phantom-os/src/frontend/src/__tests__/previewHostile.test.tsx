import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImageViewer from '../components/messenger/preview/ImageViewer';
import TextViewer from '../components/messenger/preview/TextViewer';

declare global {
  interface Window {
    __pwn?: number;
  }
}

describe('hostile file previews', () => {
  const createObjectURL = vi.fn(() => 'blob:svg-rewritten');
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    delete window.__pwn;
    createObjectURL.mockClear();
    revokeObjectURL.mockClear();
    Object.defineProperty(URL, 'createObjectURL', {
      value: createObjectURL,
      configurable: true,
      writable: true,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: revokeObjectURL,
      configurable: true,
      writable: true,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    delete window.__pwn;
  });

  it('renders hostile SVG exclusively via <img> without executing scripts', async () => {
    const hostileSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" onload="window.__pwn=1"><script>window.__pwn=1</script></svg>';
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(hostileSvg, { headers: { 'Content-Type': 'application/octet-stream' } }),
        ),
      ),
    );

    const { container, rerender, unmount } = render(
      <ImageViewer
        url="blob:cipher-svg"
        name="diagram.svg"
        mime="application/octet-stream"
        maxBytes={2 * 1024 * 1024}
        onFail={() => {}}
      />,
    );

    await waitFor(() => {
      expect(container.querySelectorAll('img')).toHaveLength(1);
    });
    expect(container.querySelectorAll('svg')).toHaveLength(0);
    expect(container.querySelectorAll('script')).toHaveLength(0);
    expect(container.querySelectorAll('iframe')).toHaveLength(0);
    expect(container.querySelectorAll('object')).toHaveLength(0);
    expect(window.__pwn).toBeUndefined();
    expect(createObjectURL).toHaveBeenCalledTimes(1);

    rerender(
      <ImageViewer
        url="blob:cipher-svg"
        name="diagram.svg"
        mime="application/octet-stream"
        maxBytes={2 * 1024 * 1024}
        onFail={() => {}}
      />,
    );
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).not.toHaveBeenCalled();

    unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:svg-rewritten');
  });

  it('renders hostile HTML inside TextViewer as literal textContent', async () => {
    const payload = '<img src=x onerror="window.__pwn=1">';
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(payload, { headers: { 'Content-Type': 'text/plain' } }))),
    );

    const { container } = render(
      <TextViewer
        url="blob:text-payload"
        name="notes.txt"
        mime="text/plain"
        maxBytes={1024 * 1024}
      />,
    );

    await waitFor(() => {
      expect(container.querySelector('pre')).not.toBeNull();
    });
    expect(container.querySelector('pre')?.textContent).toBe(payload);
    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(window.__pwn).toBeUndefined();
  });

  it('stops before reading text when actual blob size exceeds maxBytes', async () => {
    const oversized = new Uint8Array(20 * 1024 * 1024);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(oversized))),
    );

    const { container, getByTestId } = render(
      <TextViewer
        url="blob:oversized"
        name="notes.txt"
        mime="text/plain"
        maxBytes={1024 * 1024}
      />,
    );

    await waitFor(() => {
      expect(getByTestId('preview-too-big')).toBeTruthy();
    });
    expect(container.querySelector('pre')).toBeNull();
  });
});
