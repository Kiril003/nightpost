import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { HelpDoor } from '../HelpDoor';
import rootSource from '../../messenger/MessengerRoot.tsx?raw';

type W = Window & { __phantomOpenHelp?: () => boolean };

afterEach(() => cleanup());

describe('Допомога на телефоні: рідний профіль відкриває веб-Допомогу (скарги, звернення, обмеження)', () => {
  it('__phantomOpenHelp відкриває Допомогу і каже «відкрито»; після зняття хоста двері зникають', async () => {
    const { unmount } = render(<HelpDoor />);
    const w = window as W;
    expect(typeof w.__phantomOpenHelp).toBe('function');
    expect(screen.queryByTestId('help-centre-sheet')).toBeNull();
    let opened = false;
    act(() => {
      opened = w.__phantomOpenHelp!();
    });
    expect(opened).toBe(true);
    expect(await screen.findByTestId('help-centre-sheet')).toBeTruthy();
    unmount();
    expect(w.__phantomOpenHelp).toBeUndefined();
  });

  it('двері змонтовані в корені месенджера, поруч з іншими дверима рідної оболонки', () => {
    expect(rootSource).toMatch(/<HelpDoor\s*\/>/);
  });
});
