import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Users } from 'lucide-react';
import { EmptyState } from '../components/messenger/kit/EmptyState';

afterEach(() => {
  cleanup();
});

function withReducedMotion(reduce: boolean, run: () => void) {
  const origMatchMedia = window.matchMedia;
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: reduce && query.includes('reduce'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
  try {
    run();
  } finally {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: origMatchMedia,
    });
  }
}

describe('EmptyState motion policy', () => {
  it('applies settle motion on the icon tile when motion is enabled and preserves label and action', () => {
    withReducedMotion(false, () => {
      const onClick = vi.fn();
      render(
        <EmptyState
          testId="empty"
          icon={<Users data-testid="icon" />}
          label="No groups yet"
          action={{ label: 'Create group', onClick }}
        />,
      );
      const tile = document.querySelector('[data-empty-tile]') as HTMLElement;
      expect(tile).not.toBeNull();
      expect(tile.getAttribute('data-motion')).toBe('settle');
      expect(tile.classList.contains('np-settle')).toBe(true);
      expect(tile.style.getPropertyValue('--np-ms')).toBe('360ms');
      expect(tile.style.getPropertyValue('--np-ease')).toMatch(/^linear\(/);

      expect(screen.getByRole('heading', { name: 'No groups yet' })).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Create group' }));
      expect(onClick).toHaveBeenCalledTimes(1);
    });
  });

  it('renders the plain end state when prefers-reduced-motion is enabled and preserves label and action', () => {
    withReducedMotion(true, () => {
      const onClick = vi.fn();
      render(
        <EmptyState
          testId="empty"
          icon={<Users data-testid="icon" />}
          label="No groups yet"
          action={{ label: 'Create group', onClick }}
        />,
      );
      const tile = document.querySelector('[data-empty-tile]') as HTMLElement;
      expect(tile).not.toBeNull();
      expect(tile.getAttribute('data-motion')).toBe('end');
      expect(tile.classList.contains('np-settle')).toBe(false);
      expect(tile.style.getPropertyValue('--np-ms')).toBe('');
      expect(tile.getAttribute('style')).toBeNull();

      expect(screen.getByRole('heading', { name: 'No groups yet' })).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Create group' }));
      expect(onClick).toHaveBeenCalledTimes(1);
    });
  });
});
