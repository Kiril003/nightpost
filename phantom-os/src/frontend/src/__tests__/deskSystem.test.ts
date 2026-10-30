import { describe, it, expect, vi, afterEach } from 'vitest';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import React from 'react';
import { render, screen, within, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DeskIsland } from '../components/shell/DeskIsland';
import { DeskShell } from '../components/shell/DeskShell';
import { useAuthStore } from '../stores/authStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';

const ROOT = process.cwd();
const SHELL_DIR = join(ROOT, 'src/components/shell');

function walkFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? walkFiles(full) : [full];
  });
}

function stripComments(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('//'))
    .map((line) => line.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n');
}

function setTestLocale(locale: 'uk' | 'en'): void {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: locale } }));
}

afterEach(() => {
  setTestLocale('uk');
  vi.restoreAllMocks();
  cleanup();
});

describe('PC desk system tokens & shell rules', () => {
  it('deskWarm.css does not exist and DeskShell.tsx does not import it', () => {
    expect(existsSync(join(SHELL_DIR, 'deskWarm.css'))).toBe(false);
    const deskShell = readFileSync(join(SHELL_DIR, 'DeskShell.tsx'), 'utf8');
    expect(deskShell).not.toContain('deskWarm.css');
  });

  it('no file under src/components/shell/ contains a hex colour literal', () => {
    const hexRe = /#[0-9a-fA-F]{3,8}\b/;
    const offenders: string[] = [];
    for (const file of walkFiles(SHELL_DIR)) {
      const code = stripComments(readFileSync(file, 'utf8'));
      const match = code.match(hexRe);
      if (match) {
        offenders.push(`${file.replace(ROOT, '')}: ${match[0]}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('no src/components/shell/*.tsx contains shadow, drop-shadow, backdrop-blur, blur-, gradient or brightness-', () => {
    const forbiddenRe = /\b(?:shadow(?:-[a-z0-9]+)?|drop-shadow(?:-[a-z0-9]+)?|backdrop-blur(?:-[a-z0-9]+)?|blur-[a-z0-9]+|bg-gradient-[a-z0-9-]+|gradient|brightness-[a-z0-9]+)\b/;
    const offenders: string[] = [];
    for (const file of walkFiles(SHELL_DIR).filter((f) => f.endsWith('.tsx'))) {
      const code = stripComments(readFileSync(file, 'utf8'));
      const match = code.match(forbiddenRe);
      if (match) {
        offenders.push(`${file.replace(ROOT, '')}: ${match[0]}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('renders DeskIsland slot labels in uk and en', () => {
    setTestLocale('uk');
    const { unmount } = render(
      React.createElement(MemoryRouter, null, React.createElement(DeskIsland, { unread: 0 })),
    );
    const ukDock = screen.getByTestId('phantom-dock');
    const ukLabels = within(ukDock).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
    expect(ukLabels).toEqual(['Сьогодні', 'Мапа', 'День', 'Гаманець', 'Рушій', 'Я']);
    expect(within(ukDock).getByRole('button', { name: 'Я' }).textContent).toBe('Я');
    expect(ukDock.className).toMatch(/\b(?:border-f-line|ring-f-line|border-line)\b/);
    unmount();

    setTestLocale('en');
    render(
      React.createElement(MemoryRouter, null, React.createElement(DeskIsland, { unread: 0 })),
    );
    const enDock = screen.getByTestId('phantom-dock');
    const enLabels = within(enDock).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
    expect(enLabels).toEqual(['Today', 'Map', 'Day', 'Wallet', 'Engine', 'You']);
    expect(within(enDock).getByRole('button', { name: 'You' }).textContent).toBe('You');
  });

  it('renders TopStrip title-2 wordmark and no self avatar: the self lives in the «Я» slot only', () => {
    const prevUser = useAuthStore.getState().user;
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    try {
      useAuthStore.setState({
        user: {
          id: 'u1',
          username: 'olena',
          role: 'ROOT',
          preferences: { display_name: 'Олена Коваль Іванівна' },
        } as never,
      });
      render(
        React.createElement(
          QueryClientProvider,
          { client: qc },
          React.createElement(MemoryRouter, null, React.createElement(DeskShell)),
        ),
      );
      const top = screen.getByTestId('desk-top');
      const brand = within(top).getByTestId('desk-brand');
      expect(brand.innerHTML).toContain('text-[17px]');
      expect(brand.innerHTML).toContain('leading-6');
      expect(within(top).queryByText('ОК')).toBeNull();
      expect(within(top).getAllByRole('button')).toEqual([brand]);
    } finally {
      useAuthStore.setState({ user: prevUser });
    }
  });
});
